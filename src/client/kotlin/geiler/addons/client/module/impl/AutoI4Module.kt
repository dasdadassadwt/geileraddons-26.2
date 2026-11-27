package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.f7.AutoI4Rules
import geiler.addons.client.dungeon.f7.AutomationLock
import geiler.addons.client.dungeon.f7.LeverAimRules
import geiler.addons.client.macros.input.SimulatedMouse
import geiler.addons.client.macros.rotation.LookControl
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import java.util.concurrent.ThreadLocalRandom

/**
 * Does the F7/M7 4th device (i4) while you stand on its plate: the Rotation Engine turns the view to the aim spot of the
 * current emerald that i4 helper shows, and the held bow is shot once the view is on it, after a reaction delay and the
 * bow's shot cooldown. Pressing a movement key stops it until you stand still again. Works in `/ga sim i4`.
 */
class AutoI4Module private constructor() : Module(
    "Auto i4",
    "Aims at and shoots the i4 device targets in F7 and M7 while you stand on the plate. Needs i4 helper.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val delayMin = spec.slider("delayMin")
    private val delayMax = spec.slider("delayMax")
    private val tolerance = spec.slider("tolerance")
    private val bowOnly = spec.toggle("bowOnly")
    private val prefire = spec.toggle("prefire")

    private val look = LookControl()
    private val shooter = AutoI4Rules.Shooter(ThreadLocalRandom.current())
    private var releasePending = false
    private var shots = 0
    private var lastState = ""
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        idle()
        shots = 0
        diagnostics.enable(
            "delay=${delayMin.value().toInt()}..${delayMax.value().toInt()}ms tolerance=${tolerance.value()} bowOnly=${bowOnly.value()} prefire=${prefire.value()}",
        )
    }

    override fun onDisable() {
        releaseClick(Minecraft.getInstance())
        idle()
        diagnostics.event("DISABLE shots=$shots")
        diagnostics.disable()
    }

    private fun idle() {
        look.release()
        shooter.reset()
    }

    private fun releaseClick(client: Minecraft) {
        if (!releasePending) return
        releasePending = false
        SimulatedMouse.release(client, SimulatedMouse.Button.RIGHT)
    }

    fun tick() {
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        releaseClick(client)
        val player = client.player
        val options = client.options
        val moving = options.keyUp.isDown || options.keyDown.isDown || options.keyLeft.isDown || options.keyRight.isDown ||
            options.keyJump.isDown
        val held = player?.mainHandItem
        val lore = held?.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()
        val isBow = lore.any { it.contains("Shot Cooldown") } || held?.item == net.minecraft.world.item.Items.BOW
        var index = I4HelperModule.INSTANCE.activeTargetIndex()
        var point = I4HelperModule.INSTANCE.activeAimPoint()
        var kind = "AIM"
        // No lit target: shoot the orange sphere nearest the crosshair, so the next emerald is hit as it lights up.
        if ((index < 0 || point == null) && prefire.value() && player != null) {
            val spots = I4HelperModule.INSTANCE.prefireAimPoints()
            val eye = player.eyePosition
            val errors = DoubleArray(spots.size) {
                val p = spots[it].second
                val dx = p.x - eye.x
                val dy = p.y - eye.y
                val dz = p.z - eye.z
                LeverAimRules.angularDistance(
                    player.yRot.toDouble(), player.xRot.toDouble(), LeverAimRules.yawTo(dx, dz), LeverAimRules.pitchTo(dx, dy, dz),
                )
            }
            val pick = LeverAimRules.pick(errors, 360.0)
            if (pick >= 0) {
                val panel = spots[pick].first
                val ordinal = spots.subList(0, pick).count { it.first == panel }
                index = AutoI4Rules.prefireKey(panel, ordinal)
                point = spots[pick].second
                kind = "PREFIRE"
            }
        }
        val reason = when {
            player == null -> "no-world"
            client.gui.screen() != null -> "screen"
            index < 0 || point == null -> "no-target"
            moving -> "moving"
            // The i4 sim simulates the bow itself, so no bow needs to be held there.
            bowOnly.value() && !isBow && geiler.addons.client.sim.DeviceSimService.frame(geiler.addons.client.sim.DeviceSimService.Kind.I4) == null -> "no-bow"
            AutomationLock.blocks(LOCK_NAME, System.currentTimeMillis()) -> "automation-lock"
            else -> null
        }
        if (reason != null) {
            noteState("IDLE $reason")
            idle()
            return
        }
        diagnostics.advance()
        player!!
        look.lookAtExact(point!!)
        val residual = look.residualDegrees(player)
        val aimed = residual <= tolerance.value()
        val cooldown = AutoI4Rules.cooldownMs(lore)
        val now = System.currentTimeMillis()
        if (shooter.update(index, aimed, now, delayMin.value().toLong(), delayMax.value().toLong(), cooldown)) {
            SimulatedMouse.press(client, SimulatedMouse.Button.RIGHT)
            releasePending = true
            shots++
            diagnostics.event("SHOT kind=$kind index=$index residual=${fmt(residual)} delayMs=${shooter.lastDelayMs()} cooldownMs=$cooldown shots=$shots")
        }
        noteState("$kind index=$index")
        diagnostics.status {
            "enabled=true index=$index residual=${fmt(residual)} aimed=$aimed cooldownMs=$cooldown shots=$shots suppressedEvents=$it"
        }
    }

    private fun noteState(line: String) {
        if (line == lastState) return
        lastState = line
        diagnostics.event("STATE $line")
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

    companion object {
        private const val LOCK_NAME = "auto-i4"
        private val spec = ModuleSettingsSpec.of("Auto i4")

        @JvmField
        val INSTANCE = AutoI4Module()
    }
}
