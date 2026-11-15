package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.f7.F7Support
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSoundPacket

/**
 * Mutes the loud sounds of the F7/M7 boss fight (Devonian's Cancel F7 Boss Sounds, GPL-3.0: behavior and the
 * published sound values only, written independently). Only acts inside the floor 7 boss room.
 */
class HideF7BossSoundsModule private constructor(private val s: Settings) : Module(
    "Hide F7 Boss Sounds",
    "Mutes the loud boss sounds played in the F7 and M7 boss fights.",
    Category.F7,
    false,
    s.cancelAboveCap,
    s.capVolume,
) {
    enum class Action { PASS, CANCEL, CAP }

    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 200)
    private var passed = 0L
    private var cancelled = 0L
    private var capped = 0L

    override fun onEnable() {
        passed = 0L
        cancelled = 0L
        capped = 0L
        diagnostics.enable(flags())
    }

    override fun onDisable() {
        diagnostics.event("COUNTS ${counters()}")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "${flags()} inF7Boss=${F7Support.inFloor7Boss()} ${counters()} suppressedEvents=$it" }
    }

    /**
     * `ClientPacketListener.handleSoundEvent` HEAD. Returns true to drop the packet. The packet is also seen once on the
     * network thread before it is re-queued; only the client-thread pass decides.
     */
    fun onSound(packet: ClientboundSoundPacket): Boolean {
        if (!isEnabled()) return false
        val client = Minecraft.getInstance()
        if (!client.isSameThread || !F7Support.inFloor7Boss()) return false
        val soundId = packet.sound.value().location().toString()
        val action = decide(soundId, packet.volume, packet.pitch, s.cancelAboveCap.value(), s.capVolume.value())
        when (action) {
            Action.PASS -> {
                if (passed < Long.MAX_VALUE) passed++
                return false
            }
            Action.CANCEL -> if (cancelled < Long.MAX_VALUE) cancelled++
            Action.CAP -> {
                if (capped < Long.MAX_VALUE) capped++
                client.level?.playLocalSound(
                    packet.x, packet.y, packet.z, packet.sound.value(), packet.source, 1f, packet.pitch, false,
                )
            }
        }
        diagnostics.event("$action sound=$soundId volume=${packet.volume} pitch=${packet.pitch}")
        return true
    }

    private fun flags(): String = "cancelAboveCap=${s.cancelAboveCap.value()} capVolume=${s.capVolume.value()}"

    private fun counters(): String = "passed=$passed cancelled=$cancelled capped=$capped"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Hide F7 Boss Sounds")
        val cancelAboveCap = spec.toggle("cancelAboveCap")
        val capVolume = spec.toggle("capVolume")
    }

    companion object {
        @JvmField
        val INSTANCE = HideF7BossSoundsModule(Settings())

        /** Pure rule for one boss-room sound. Cap Volume wins over cancelling for sounds louder than 1. */
        @JvmStatic
        fun decide(soundId: String, volume: Float, pitch: Float, cancelAboveCap: Boolean, capVolume: Boolean): Action {
            if (capVolume && volume > 1f) return Action.CAP
            if (cancelAboveCap && volume > 1f || volume >= 2f) return Action.CANCEL
            if (soundId == "minecraft:entity.wither.hurt" && volume == 0.5f &&
                (pitch == 1.0f || pitch == 0.93650794f || pitch == 1.0158731f || pitch == 1.1111112f)
            ) {
                return Action.CANCEL
            }
            if (soundId == "minecraft:entity.wither.death" && volume == 0.5f && pitch in 0.8730159f..1.1587301f) {
                return Action.CANCEL
            }
            return Action.PASS
        }
    }
}
