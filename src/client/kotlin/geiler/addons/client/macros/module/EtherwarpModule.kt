package geiler.addons.client.macros.module

import geiler.addons.client.macros.sim.EtherwarpCooldown
import geiler.addons.client.macros.sim.EtherwarpMath
import geiler.addons.client.macros.sim.EtherwarpRules
import geiler.addons.client.macros.sim.EtherwarpSim
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.module.impl.ModuleDiagnostics
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB

/**
 * Hypixel SkyBlock's Etherwarp, as Odin shows it: a box where the warp lands (green when it works, red when it does not),
 * and a sound of your own for the warp. It never moves you itself on a server: the server does the warp. Hold an item with
 * the ethermerge upgrade (or the Etherwarp Conduit) and sneak. In singleplayer, with the Etherwarp Sim on, a diamond shovel
 * is the item and the warp is the sim's.
 *
 * One warp is let through per two ticks; clicks in between are dropped. Behavior reference: Odin's Etherwarp (BSD 3-Clause),
 * written independently; see docs/provenance/external-sources.md.
 */
class EtherwarpModule private constructor(private val c: Carrier) : Module(
    "Etherwarp",
    "Shows where an etherwarp lands: a green box when it works, a red one when it does not. The server " +
        "does the warp; the module can play a sound of your own. Hold the item and sneak (the Etherwarp Conduit " +
        "needs no sneak). In singleplayer, with the Etherwarp Sim on, a diamond shovel is the item: " +
        "sneak right click is the etherwarp, right click alone the Instant Transmission (8 blocks, one more per tuner).",
    Category.VISUAL,
    false,
    *c.all(),
) {
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() })
    private val cooldown = EtherwarpCooldown()

    private var tick = 0L
    private var warps = 0
    private var refused = 0
    private var dropped = 0

    /** The item last held, so its data is read once per stack and not every tick. */
    private var cachedStack: ItemStack? = null
    private var cachedHeld: Held? = null
    private var lastPreview: String? = null

    /** What the held item does: how far, whether it needs no sneak, and whether it is the sim's shovel. */
    private class Held(val distance: Double, val conduit: Boolean, val simulated: Boolean)

    init {
        group(
            SettingGroup("Preview", c.showGuess, c.successColor, c.showFailed, c.failColor, c.boxStyle, c.useServerPosition, c.fullBlock, c.depth),
            SettingGroup("Sounds", c.customSounds, c.sound, c.pitch, c.volume),
        )
    }

    override fun onEnable() {
        cooldown.reset()
        diagnostics.enable(
            "showGuess=${c.showGuess.rawValue()} style=${c.boxStyle.value()} fullBlock=${c.fullBlock.rawValue()} " +
                "depth=${c.depth.rawValue()} serverPosition=${c.useServerPosition.rawValue()} customSounds=${c.customSounds.rawValue()} " +
                "sound='${c.sound.value()}' pitch=${c.pitch.value()} volume=${c.volume.value()}",
        )
    }

    override fun onDisable() {
        lastPreview = null
        diagnostics.disable()
    }

    override fun settingTooltip(setting: Setting): String? = TOOLTIPS[setting.name()]

    // ------------------------------------------------------------------ the item

    private fun held(player: LocalPlayer): Held? {
        val stack = player.mainHandItem
        if (stack === cachedStack) return cachedHeld
        cachedStack = stack
        cachedHeld = readHeld(stack)
        return cachedHeld
    }

    private fun readHeld(stack: ItemStack): Held? {
        val sim = EtherwarpSimModule.INSTANCE
        if (sim.isActive() && stack.`is`(Items.DIAMOND_SHOVEL)) return Held(EtherwarpMath.range(sim.tuners()), false, true)
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
        val id = tag.getStringOr("id", "")
        if (!EtherwarpRules.isEtherwarpItem(id, tag.getIntOr("ethermerge", 0))) return null
        return Held(EtherwarpRules.distance(tag.getIntOr("tuned_transmission", 0)), EtherwarpRules.isConduit(id), false)
    }

    // ------------------------------------------------------------------ the ray

    private class Aim(val result: EtherwarpMath.Result, val originX: Double, val originY: Double, val originZ: Double, val eye: Double)

    private fun aim(player: LocalPlayer, distance: Double): Aim? {
        val level = Minecraft.getInstance().level ?: return null
        val server = c.useServerPosition.rawValue()
        // The position the server last saw is the previous tick's; the sneak the server saw is the last one sent.
        // Airborne the server's last position is a fall or a jump behind: the box would trail the view, so the current one is used.
        val useLast = server && player.onGround()
        val x = if (useLast) player.xo else player.x
        val y = if (useLast) player.yo else player.y
        val z = if (useLast) player.zo else player.z
        val sneaking = if (server) player.lastSentInput.shift() else player.isCrouching
        val eye = EtherwarpRules.eyeHeight(player.pose == Pose.SWIMMING, sneaking)
        return Aim(EtherwarpSim.traceFrom(level, x, y, z, eye, player.lookAngle, distance), x, y, z, eye)
    }

    // ------------------------------------------------------------------ the preview, once a tick

    /** The sim's shovel works whether or not this module is switched on: the module is the sim's click handler. */
    private fun simActive(): Boolean = EtherwarpSimModule.INSTANCE.isActive()

    private var simLogging = false

    /** Called every client tick: counts ticks and draws the box. */
    fun tick() {
        tick++
        if (!isEnabled()) {
            // Switched off, but the sim is on: its log is this module's, opened for as long as the sim runs.
            if (simActive() && !simLogging) {
                simLogging = true
                diagnostics.enable("sim only")
            } else if (!simActive() && simLogging) {
                simLogging = false
                diagnostics.disable()
            }
            if (!simActive()) return
        }
        diagnostics.advance()
        diagnostics.status { suppressed ->
            "enabled=true tick=$tick warps=$warps refused=$refused dropped=$dropped suppressedEvents=$suppressed"
        }
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null || client.gui.screen() != null || !c.showGuess.rawValue()) {
            lastPreview = null
            return
        }
        val held = held(player)
        if (held == null || (!isEnabled() && !held.simulated) || (!player.isCrouching && !held.conduit)) {
            lastPreview = null
            return
        }
        val aim = aim(player, held.distance) ?: return
        val result = aim.result
        val summary = if (result.hitBlock()) "${result.blockX},${result.blockY},${result.blockZ} ok=${result.valid}" else "none"
        if (summary != lastPreview) {
            lastPreview = summary
            diagnostics.event("preview target=$summary distance=${"%.1f".format(held.distance)} simulated=${held.simulated} reason='${result.reason}'")
        }
        if (!result.hitBlock()) {
            // Nothing in range along the view - open air, or too far: a failed-colour box where the ray ends, so the aim is not
            // blank. Only when failures are shown at all.
            if (c.showFailed.rawValue()) {
                val look = player.lookAngle
                val end = player.position().add(0.0, aim.eye, 0.0).add(look.scale(held.distance))
                val marker = AABB(end.x - 0.25, end.y - 0.25, end.z - 0.25, end.x + 0.25, end.y + 0.25, end.z + 0.25)
                Gizmos.cuboid(marker, GizmoStyle.stroke(c.failColor.opaqueArgb(), STROKE_WIDTH)).setAlwaysOnTop()
            }
            return
        }
        if (!result.valid && !c.showFailed.rawValue()) return
        val box = if (c.fullBlock.rawValue()) AABB(
            result.blockX.toDouble(), result.blockY.toDouble(), result.blockZ.toDouble(),
            result.blockX + 1.0, result.blockY + 1.0, result.blockZ + 1.0,
        ) else EtherwarpSim.boundsAt(level, result.blockX, result.blockY, result.blockZ) ?: AABB(
            result.blockX.toDouble(), result.blockY.toDouble(), result.blockZ.toDouble(),
            result.blockX + 1.0, result.blockY + 1.0, result.blockZ + 1.0,
        )
        val color = if (result.valid) c.successColor else c.failColor
        val style = when (c.boxStyle.value()) {
            "OUTLINE" -> GizmoStyle.stroke(color.opaqueArgb(), STROKE_WIDTH)
            "FILLED" -> GizmoStyle.fill(color.argb())
            else -> GizmoStyle.strokeAndFill(color.opaqueArgb(), STROKE_WIDTH, color.argb())
        }
        val gizmo = Gizmos.cuboid(box, style)
        if (c.depth.rawValue()) gizmo.setAlwaysOnTop()
    }

    // ------------------------------------------------------------------ the click

    /**
     * `Minecraft.startUseItem` HEAD, client thread.
     *
     * @return true when the click is taken: it was a warp of the sim, or a click inside the cooldown
     */
    fun onUse(): Boolean {
        if (!isEnabled() && !simActive()) return false
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return false
        val player = client.player ?: return false
        val level = client.level ?: return false
        val held = held(player)
        if (held == null || (!isEnabled() && !held.simulated)) {
            if (isEnabled()) diagnostics.event("ignored reason=wrong_item")
            return false
        }
        if (held.simulated && !player.isCrouching) {
            // The sim's shovel is the Aspect of the Void: a right click without sneaking is its Instant Transmission.
            if (!cooldown.tryWarp(tick)) {
                dropped++
                return true
            }
            val outcome = EtherwarpSim.instantTransmission(client, player, level, EtherwarpSimModule.INSTANCE.tuners())
            if (outcome.teleported) warps++
            diagnostics.event("${outcome.line} yaw=${fmt(player.yRot.toDouble())} pitch=${fmt(player.xRot.toDouble())} simulated=true")
            return true
        }
        if (!player.isCrouching && !held.conduit) {
            diagnostics.event("ignored reason=not_sneaking")
            return false
        }
        val aim = aim(player, held.distance) ?: return false
        val result = aim.result
        if (!result.valid) {
            refused++
            diagnostics.event(
                "refused reason='${result.reason}' from=${fmt(aim.originX)},${fmt(aim.originY)},${fmt(aim.originZ)} " +
                    "yaw=${fmt(player.yRot.toDouble())} pitch=${fmt(player.xRot.toDouble())} block=${result.blockX},${result.blockY},${result.blockZ}",
            )
            // The sim's shovel would otherwise be used on the block; on a server the click is the server's to answer.
            return held.simulated
        }
        if (!cooldown.tryWarp(tick)) {
            dropped++
            diagnostics.event("cooldown dropped click tick=$tick")
            return true
        }
        val customSound = c.customSounds.rawValue()
        if (held.simulated) {
            val outcome = EtherwarpSim.teleport(client, player, result, !customSound)
            if (outcome.teleported) {
                warps++
                if (customSound) playCustomSound(player)
            }
            diagnostics.event("${outcome.line} yaw=${fmt(player.yRot.toDouble())} pitch=${fmt(player.xRot.toDouble())} simulated=true")
            return true
        }
        warps++
        // The mod never moves the player or sends a position on a server: a client-made teleport packet is what an anticheat
        // flags. The click goes to the server, which does the warp; the sound swap in onSound is the only change.
        diagnostics.event("warp block=${result.blockX},${result.blockY},${result.blockZ} server answers")
        return false
    }

    // ------------------------------------------------------------------ sound

    /** `ClientPacketListener.handleSoundEvent` HEAD: swaps the server's etherwarp sound for the custom one. */
    fun onSound(packet: ClientboundSoundPacket): Boolean {
        if (!isEnabled() || !c.customSounds.rawValue()) return false
        if (packet.sound.value() != SoundEvents.ENDER_DRAGON_HURT || packet.volume != 1.0f || packet.pitch != EtherwarpSim.SOUND_PITCH) return false
        val player = Minecraft.getInstance().player ?: return false
        playCustomSound(player)
        diagnostics.event("sound swapped id='${c.sound.value()}'")
        return true
    }

    private fun playCustomSound(player: LocalPlayer) {
        val id = Identifier.tryParse(c.sound.value().trim())
        val event: SoundEvent? = if (id == null || !BuiltInRegistries.SOUND_EVENT.keySet().contains(id)) null
        else BuiltInRegistries.SOUND_EVENT.getValue(id)
        if (event == null) {
            diagnostics.event("sound unavailable id='${c.sound.value()}'")
            return
        }
        player.level().playLocalSound(
            player.x, player.y, player.z, event, SoundSource.PLAYERS, c.volume.value(), c.pitch.value(), false,
        )
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

    /** All the settings, with Odin's names, created before the module so they can be handed to its constructor. */
    private class Carrier {
        val showGuess = BooleanSetting("Show Guess", true)
        val successColor = ColorSetting("Success Color", 0x00, 0xFF, 0x14, 0x7F)
        val showFailed = BooleanSetting("Show when failed", true)
        val failColor = ColorSetting("Fail Color", 0xFF, 0x55, 0x55, 0x7F)
        val boxStyle = ChoiceSetting("Box Style", "FILLED", "FILLED", "OUTLINE", "FILLED_OUTLINE")
        val useServerPosition = BooleanSetting("Use Server Position", false)
        val fullBlock = BooleanSetting("Full Block", false)
        val depth = BooleanSetting("Depth", false)
        val customSounds = BooleanSetting("Custom Sounds", true)
        val sound = TextSetting("Etherwarp Sound", "entity.experience_orb.pickup", 96)
        val pitch = NumberSetting("Pitch", 0.0f, 2.0f, 0.1f)
        val volume = NumberSetting("Volume", 0.0f, 2.0f, 1.0f)

        fun all(): Array<Setting> = arrayOf(
            showGuess, successColor, showFailed, failColor, boxStyle, useServerPosition, fullBlock, depth,
            customSounds, sound, pitch, volume,
        )
    }

    companion object {
        private const val STROKE_WIDTH = 2.0f

        private val TOOLTIPS: Map<String, String> = mapOf(
            "Show Guess" to "Shows where the etherwarp will take you.",
            "Show when failed" to "Shows the box in the fail colour when there is no room to land.",
            "Use Server Position" to "Aims from the position and sneak the server last saw instead of the client's.",
            "Full Block" to "Draws the whole 1x1x1 block instead of the block's own shape.",
            "Depth" to "Draws the box through walls.",
            "Custom Sounds" to "Plays your own sound instead of the ender dragon hurt sound when you etherwarp.",
            "Etherwarp Sound" to "A sound id, like entity.experience_orb.pickup.",
        )

        @JvmField
        val INSTANCE = EtherwarpModule(Carrier())
    }
}
