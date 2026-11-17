package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.mining.MiningConditions
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier

/**
 * Two comfort features for the deep mines, after SkyHanni's High Heat Sound and Cold Overlay: mute the wolf pant that
 * plays at 90+ heat in the Crystal Hollows depths, and show the frozen screen edge as Glacite cold rises.
 */
class HeatAndColdModule private constructor(settings: Settings) : Module(
    "Heat and Cold",
    "Mutes the high heat panting and shows a frost overlay as your cold rises.",
    Category.MINING,
    false,
    settings.muteHeat, settings.coldOverlay, settings.threshold, settings.maxOpacity,
) {
    private val muteHeat = settings.muteHeat
    private val coldOverlay = settings.coldOverlay
    private val threshold = settings.threshold
    private val maxOpacity = settings.maxOpacity

    private val diagnostics = ModuleDiagnostics(Category.MINING, { name() }, 8, 100L, 400)
    private var heat: Int? = null
    private var cold = 0
    private var tick = 0L
    private var muted = 0L
    private var lastAlpha = 0f

    init {
        group(SettingGroup("Heat", muteHeat), SettingGroup("Cold", coldOverlay, threshold, maxOpacity))
    }

    private fun settingsText(): String =
        "muteHeat=${muteHeat.value()};coldOverlay=${coldOverlay.value()};threshold=${threshold.intValue()};maxOpacity=${maxOpacity.intValue()}"

    override fun onEnable() {
        heat = null
        cold = 0
        muted = 0
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        heat = null
        cold = 0
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        diagnostics.advance()
        tick++
        if (mc.level == null || mc.player == null) {
            heat = null
            cold = 0
            return
        }
        if (tick % READ_TICKS == 0L) {
            val sidebar = MiningContext.sidebarLines()
            heat = MiningConditions.heat(sidebar)
            cold = MiningConditions.cold(sidebar) ?: 0
        }
        diagnostics.statusOnChange { "heat=${heat ?: "none"} cold=$cold alpha=${"%.2f".format(java.util.Locale.ROOT, lastAlpha)} muted=$muted island=${geiler.addons.client.location.HypixelModApi.currentIsland()}" }
    }

    /** `handleSoundEvent` HEAD: true drops the wolf pant at 90+ heat in the Crystal Hollows depths. */
    fun onSound(packet: ClientboundSoundPacket): Boolean {
        if (!isEnabled() || !muteHeat.value()) return false
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) return false
        val player = mc.player ?: return false
        if (player.y > DEPTHS_Y || (heat ?: 0) < MUTE_HEAT) return false
        val id = packet.sound.value().location().toString()
        if (!MiningConditions.isHeatSound(id, packet.volume, packet.pitch)) return false
        muted++
        diagnostics.event("HEAT_SOUND_MUTED heat=$heat y=${player.y.toInt()} muted=$muted")
        return true
    }

    /** The frost texture over the whole screen, fading in above the threshold. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        lastAlpha = 0f
        if (!isEnabled() || !coldOverlay.value() || cold <= 0) return
        val mc = Minecraft.getInstance()
        if (mc.level == null || !MiningContext.inMiningIsland()) return
        val alpha = MiningConditions.coldAlpha(cold, threshold.intValue(), maxOpacity.intValue())
        lastAlpha = alpha
        if (alpha <= 0f) return
        val argb = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        val width = mc.window.guiScaledWidth
        val height = mc.window.guiScaledHeight
        graphics.blit(RenderPipelines.GUI_TEXTURED, FROST, 0, 0, 0.0f, 0.0f, width, height, width, height, argb)
    }

    private class Settings {
        val muteHeat = BooleanSetting("Mute High Heat", true)
        val coldOverlay = BooleanSetting("Cold Overlay", true)
        val threshold = NumberSetting("Cold Threshold", 0f, 99f, 50f, true)
        val maxOpacity = NumberSetting("Max Opacity", 5f, 100f, 50f, true)
    }

    companion object {
        private const val READ_TICKS = 10L
        private const val DEPTHS_Y = 65.0
        private const val MUTE_HEAT = 90
        private val FROST: Identifier = Identifier.fromNamespaceAndPath("minecraft", "textures/misc/powder_snow_outline.png")

        @JvmField
        val INSTANCE = HeatAndColdModule(Settings())
    }
}
