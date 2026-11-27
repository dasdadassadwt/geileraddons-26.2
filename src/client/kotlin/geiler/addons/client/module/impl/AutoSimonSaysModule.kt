package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.f7.AutoSimonSaysRules
import geiler.addons.client.dungeon.f7.AutomationLock
import geiler.addons.client.dungeon.f7.LeverAimRules
import geiler.addons.client.macros.input.SimulatedMouse
import geiler.addons.client.macros.rotation.FrameRotations
import geiler.addons.client.macros.rotation.FrameSteered
import geiler.addons.client.macros.rotation.LookControl
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom

/**
 * Presses the Simon Says buttons Simon Says has solved, in order. Three modes: an aim assist that pulls the view part of
 * the way to the next button, a trigger that clicks when the crosshair is on the right button, and auto, which turns to
 * each button with the Rotation Engine and clicks it. All clicks wait a delay drawn between two values. SS Skip presses
 * the start button twice more after your own press. Never clicks a button that is not next. Works in `/ga sim ss`.
 */
class AutoSimonSaysModule private constructor() : Module(
    "Auto Simon Says",
    "Presses the solved Simon Says buttons in F7 and M7: aim assist, trigger bot or full auto, plus SS skip. Needs Simon Says.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val mode = spec.choice("mode")
    private val pullStrength = spec.slider("pullStrength")
    private val pullSpeed = spec.slider("pullSpeed")
    private val pullFov = spec.slider("pullFov")
    private val triggerFov = spec.slider("triggerFov")
    private val delayMin = spec.slider("delayMin")
    private val delayMax = spec.slider("delayMax")
    private val reach = spec.slider("reach")
    private val autoStart = spec.toggle("autoStart")
    private val ssSkip = spec.toggle("ssSkip")
    private val skipGapMin = spec.slider("skipGapMin")
    private val skipGapMax = spec.slider("skipGapMax")

    private val look = LookControl()
    private val pull = LeverAimRules.Pull()
    private val trigger = LeverAimRules.Trigger(ThreadLocalRandom.current())
    private val skip = AutoSimonSaysRules.Skip(ThreadLocalRandom.current())
    private var pullPoint: Vec3? = null
    private var pullKey = LeverAimRules.NO_KEY
    private var releasePending = false
    private var lastOwnStartMs = 0L
    private var clicks = 0
    private var skips = 0
    private var lastState = ""
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    init {
        group(*spec.groups.toTypedArray())
        FrameRotations.register(object : FrameSteered {
            override fun advance(player: LocalPlayer, deltaSeconds: Float) = pullFrame(player, deltaSeconds)
        })
    }

    override fun onEnable() {
        idle()
        clicks = 0
        skips = 0
        diagnostics.enable(
            "mode=${mode.value()} delay=${delayMin.value().toInt()}..${delayMax.value().toInt()}ms reach=${reach.value()} " +
                "triggerFov=${triggerFov.value().toInt()} pull=${pullStrength.value().toInt()}%/${pullSpeed.value().toInt()}/${pullFov.value().toInt()} " +
                "ssSkip=${ssSkip.value()} gap=${skipGapMin.value().toInt()}..${skipGapMax.value().toInt()}ms",
        )
    }

    override fun onDisable() {
        releaseClick(Minecraft.getInstance())
        idle()
        skip.cancel()
        diagnostics.event("DISABLE clicks=$clicks skips=$skips")
        diagnostics.disable()
    }

    private fun idle() {
        look.release()
        pull.reset()
        trigger.clear()
        pullPoint = null
        pullKey = LeverAimRules.NO_KEY
    }

    private fun releaseClick(client: Minecraft) {
        if (!releasePending) return
        releasePending = false
        SimulatedMouse.release(client, SimulatedMouse.Button.RIGHT)
    }

    private fun press(client: Minecraft) {
        SimulatedMouse.press(client, SimulatedMouse.Button.RIGHT)
        releasePending = true
    }

    /** Your own right click on the start button (from Simon Says); our own skip presses are not counted. */
    fun onStartPressed() {
        if (!isEnabled() || !ssSkip.value() || skip.active()) return
        val now = System.currentTimeMillis()
        if (now - lastOwnStartMs < OWN_PRESS_WINDOW_MS) return
        skip.start(now, SKIP_EXTRA_PRESSES, skipGapMin.value().toLong(), skipGapMax.value().toLong())
        skips++
        diagnostics.event("SKIP_START extra=$SKIP_EXTRA_PRESSES gap=${skipGapMin.value().toInt()}..${skipGapMax.value().toInt()}ms")
    }

    fun tick() {
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        releaseClick(client)
        val player = client.player
        val level = client.level
        val ss = SimonSaysModule.INSTANCE
        val now = System.currentTimeMillis()
        val reason = when {
            player == null || level == null -> "no-world"
            client.gui.screen() != null -> "screen"
            !ss.deviceInReach() -> "not-at-device"
            AutomationLock.blocks(LOCK_NAME, now) -> "automation-lock"
            else -> null
        }
        if (reason != null) {
            if (skip.active()) diagnostics.event("SKIP_CANCEL reason=$reason left=${skip.left()}")
            skip.cancel()
            noteState("IDLE $reason")
            idle()
            return
        }
        diagnostics.advance()
        player!!
        val hit = client.hitResult as? BlockHitResult
        val aimedBlock = if (hit != null && hit.type == HitResult.Type.BLOCK) hit.blockPos else null

        if (skip.active()) {
            idle()
            if (skip.due(now)) {
                if (aimedBlock == ss.worldBlock(ss.startButton())) {
                    lastOwnStartMs = now
                    press(client)
                    diagnostics.event("SKIP_PRESS left=${skip.left()}")
                } else {
                    skip.cancel()
                    diagnostics.event("SKIP_CANCEL reason=not-aimed-at-start")
                }
            }
            return
        }

        val next = ss.nextButton()
        if (next == null) {
            // Auto mode starts the device itself: turn to the start button and press it once.
            if (AutoSimonSaysRules.mode(mode.value()) == AutoSimonSaysRules.Mode.AUTO && autoStart.value() && ss.awaitingStart()) {
                val start = ss.startButton()
                val a = AutoSimonSaysRules.aimPoint(start.x, start.y, start.z)
                val point = ss.worldPoint(Vec3(a[0], a[1], a[2]))
                if (player.eyePosition.distanceTo(point) <= reach.value()) {
                    pullPoint = null
                    look.lookAtExact(point)
                    val aimedStart = aimedBlock == ss.worldBlock(start)
                    fire(client, START_KEY, true, aimedStart, start, look.residualDegrees(player), now)
                    noteState("AUTO_START aimed=$aimedStart")
                    return
                }
            }
            noteState("WAIT no-solution")
            idle()
            return
        }
        val worldPos = ss.worldBlock(next)
        val a = AutoSimonSaysRules.aimPoint(next.x, next.y, next.z)
        val point = ss.worldPoint(Vec3(a[0], a[1], a[2]))
        val eye = player.eyePosition
        val distance = eye.distanceTo(point)
        if (distance > reach.value()) {
            noteState("WAIT out-of-reach")
            idle()
            return
        }
        val dx = point.x - eye.x
        val dy = point.y - eye.y
        val dz = point.z - eye.z
        val error = LeverAimRules.angularDistance(
            player.yRot.toDouble(), player.xRot.toDouble(), LeverAimRules.yawTo(dx, dz), LeverAimRules.pitchTo(dx, dy, dz),
        )
        val key = AutoSimonSaysRules.key(next.asLong(), ss.step(), ss.roundSize())
        // The device shows the order first; the stone button appears only when it waits for input.
        val buttonUp = level!!.getBlockState(worldPos).`is`(Blocks.STONE_BUTTON)
        val aimed = buttonUp && aimedBlock == worldPos
        val current = AutoSimonSaysRules.mode(mode.value())
        when (current) {
            AutoSimonSaysRules.Mode.AUTO -> {
                pullPoint = null
                look.lookAtExact(point)
                fire(client, key, true, aimed, next, error, now)
            }
            AutoSimonSaysRules.Mode.TRIGGER -> {
                look.release()
                pullPoint = null
                fire(client, key, error <= triggerFov.value(), aimed, next, error, now)
            }
            AutoSimonSaysRules.Mode.AIM_ASSIST -> {
                look.release()
                trigger.clear()
                if (error <= pullFov.value() && buttonUp) {
                    pullPoint = point
                    pullKey = key
                } else {
                    pullPoint = null
                    pull.reset()
                }
            }
        }
        noteState("${current.name} ${next.toShortString()} step=${ss.step()}/${ss.roundSize()} button=$buttonUp")
        diagnostics.status {
            "enabled=true mode=$current next=${next.toShortString()} step=${ss.step()}/${ss.roundSize()} error=${fmt(error)} " +
                "distance=${fmt(distance)} buttonUp=$buttonUp clicks=$clicks skips=$skips suppressedEvents=$it"
        }
    }

    private fun fire(client: Minecraft, key: Long, inFov: Boolean, aimed: Boolean, next: BlockPos, error: Double, now: Long) {
        if (!AutoSimonSaysRules.clicks(AutoSimonSaysRules.mode(mode.value()))) return
        if (!trigger.update(key, inFov, aimed, now, delayMin.value().toLong(), delayMax.value().toLong())) return
        press(client)
        clicks++
        diagnostics.event("CLICK pos=${next.toShortString()} delayMs=${trigger.lastDelayMs()} error=${fmt(error)} clicks=$clicks")
    }

    private fun noteState(line: String) {
        if (line == lastState) return
        lastState = line
        diagnostics.event("STATE $line")
    }

    /** Once per frame: the aim assist's share of the turn. */
    private fun pullFrame(player: LocalPlayer, deltaSeconds: Float) {
        val point = pullPoint ?: return
        if (!isEnabled()) return
        val eye = player.eyePosition
        val dx = point.x - eye.x
        val dy = point.y - eye.y
        val dz = point.z - eye.z
        val errYaw = LeverAimRules.wrap(LeverAimRules.yawTo(dx, dz) - player.yRot)
        val errPitch = LeverAimRules.pitchTo(dx, dy, dz) - player.xRot
        val step = pull.step(pullKey, errYaw, errPitch, deltaSeconds.toDouble(), pullStrength.value() / 100.0, pullSpeed.value().toDouble())
        if (step[0] != 0.0 || step[1] != 0.0) player.turn(step[0] / TURN_SCALE, step[1] / TURN_SCALE)
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

    companion object {
        private const val LOCK_NAME = "auto-ss"
        private const val SKIP_EXTRA_PRESSES = 2

        /** Trigger key of the start button; grid keys are packed block positions, so this never matches one. */
        private const val START_KEY = Long.MIN_VALUE + 1
        private const val OWN_PRESS_WINDOW_MS = 600L

        /** `Entity.turn` multiplies what it is given by this before applying it. */
        private const val TURN_SCALE = 0.15
        private val spec = ModuleSettingsSpec.of("Auto Simon Says")

        @JvmField
        val INSTANCE = AutoSimonSaysModule()
    }
}
