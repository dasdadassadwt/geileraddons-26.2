package geiler.addons.client.macros.rotation

import java.util.concurrent.ThreadLocalRandom
import java.util.random.RandomGenerator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Works out how far the view turns in one frame, for every macro: given where the view points, where it should, how far
 * away that is and how long the frame took, it says how many degrees of yaw and pitch to turn now. The caller turns the
 * view; nothing here knows of a player.
 *
 * There is one engine. A turn is a velocity-driven move along a skewed bell: its size and its speed range give a duration
 * that is clamped into the time range; the peak is drawn per turn and comes earlier than the middle, as in a hand's
 * movement; the velocity lags the profile and wanders (a slow noise), hesitates now and then, and homes in on the target
 * at the end rather than stopping on it. Around that, while humanized: a reaction delay before a turn from rest, a bend off
 * the straight line, a deliberate overshoot with a slower correction, small drift of the aim while holding, and pursuit of a
 * moving target at its own speed. Nothing is a constant: no fixed speed, no fixed easing, no fixed timing.
 *
 * Sideways effects reach the pitch only as far as the target needs pitch: with the view level and the target level,
 * everything stays horizontal.
 *
 * [random] is where every random draw comes from: the game's thread-local generator unless a check supplies a seeded one.
 */
class Rotator @JvmOverloads constructor(private val random: RandomGenerator? = null) {
    enum class Phase { IDLE, DELAY, MOVE, RECOVER, HOLD }

    private var activity: RotationConfig.Activity? = null
    private var phase = Phase.IDLE
    private var lastWantYaw = 0.0
    private var lastWantPitch = 0.0
    private var hasTurn = false

    private var velocityYaw = 0.0
    private var velocityPitch = 0.0
    private var targetVelocityYaw = 0.0
    private var targetVelocityPitch = 0.0

    private var turnSize = 0.0
    private var peak = 0.0
    private var skew = 0.5
    private var tau = 0.05
    private var delayLeft = 0.0
    private var pauseLeft = 0.0
    private var speedNoise = 0.0
    private var curve = 0.0
    private var overshooting = false
    private var overshootYaw = 0.0
    private var overshootPitch = 0.0
    private var wanderYaw = 0.0
    private var wanderPitch = 0.0

    /** The effect strengths this turn drew from the settings' ranges. */
    private var turnWander = 0.0
    private var turnNoise = 0.0
    private var turnPauses = 0.0
    private var turnCurve = 0.0
    private var turnOvershootChance = 0.0
    private var turnOvershootSize = 0.0
    private var turnNumber = 0
    private var turnSeconds = 0.0

    /** Forgets the turn: the next request is a new one, from standing still. */
    fun reset() {
        hasTurn = false
        overshooting = false
        phase = Phase.IDLE
        velocityYaw = 0.0
        velocityPitch = 0.0
        targetVelocityYaw = 0.0
        targetVelocityPitch = 0.0
        delayLeft = 0.0
        pauseLeft = 0.0
    }

    /** How fast the view is turning now, in degrees a second. */
    fun speed(): Double = hypot(velocityYaw, velocityPitch)

    fun phase(): Phase = phase

    /** Keeps turning for a moment after nothing asks for a turn any more, slowing down instead of stopping dead. */
    fun coast(seconds: Double): DoubleArray {
        val dt = min(seconds, MAX_DT)
        if (dt <= 0.0 || speed() < COAST_STOP_SPEED) {
            reset()
            return doubleArrayOf(0.0, 0.0)
        }
        val keep = exp(-dt / COAST_SECONDS)
        velocityYaw *= keep
        velocityPitch *= keep
        hasTurn = false
        phase = Phase.IDLE
        return doubleArrayOf(velocityYaw * dt, velocityPitch * dt)
    }

    /**
     * One frame.
     *
     * @param distance how far the target is, in blocks, or NaN when it is not known
     * @return the yaw and the pitch to turn by, in degrees
     */
    fun step(
        settings: RotationConfig.Settings,
        activity: RotationConfig.Activity,
        yaw: Double,
        pitch: Double,
        wantYaw: Double,
        wantPitch: Double,
        distance: Double,
        seconds: Double,
    ): DoubleArray {
        val yawError = wrap(wantYaw - yaw)
        val pitchError = wantPitch - pitch
        if (settings.instant) {
            reset()
            return doubleArrayOf(yawError, pitchError)
        }
        val dt = min(seconds, MAX_DT)
        if (dt <= 0.0) return doubleArrayOf(0.0, 0.0)
        val random = this.random ?: ThreadLocalRandom.current()
        val humanized = settings.humanized
        val k = if (humanized) settings.effectIntensity else 0.0

        val jumpYaw = wrap(wantYaw - lastWantYaw)
        val jumpPitch = wantPitch - lastWantPitch
        val jump = hypot(jumpYaw, jumpPitch)
        // A change of what is looked at is a new turn; a change of what the look is *called* at the same angle is not. The macro
        // alternates between a mob and a point on the way, and starting over at each switch - new speed, new curve, a new
        // overshoot - is what made the view look chaotic. A precise activity (a teleport's exact angles) always starts afresh.
        val kindSwitch = this.activity != null && activity != this.activity
        val cause = when {
            !hasTurn -> "first"
            jump > settings.newTurnDegrees -> "jump"
            kindSwitch && (activity.precise || this.activity?.precise == true) -> "precise"
            else -> null
        }
        if (cause != null) {
            newTurn(settings, activity, yawError, pitchError, distance, random, cause)
        } else {
            if (kindSwitch) this.activity = activity
            // Pursuit: the target's own angular speed, smoothed, is what the view adds to keep up with it.
            val blend = 1.0 - exp(-dt / TARGET_SPEED_SECONDS)
            targetVelocityYaw += (jumpYaw / dt - targetVelocityYaw) * blend
            targetVelocityPitch += (jumpPitch / dt - targetVelocityPitch) * blend
        }
        lastWantYaw = wantYaw
        lastWantPitch = wantPitch
        turnSeconds += dt

        // How much pitch this target needs: none while it is within a degree of level with the view.
        val need = clamp01((abs(pitchError) - PITCH_NEED_FREE) / PITCH_NEED_FULL)
        val pitchWeight = need * settings.verticalMovement

        if (delayLeft > 0.0) {
            delayLeft -= dt
            val keep = exp(-dt / COAST_SECONDS)
            velocityYaw *= keep
            velocityPitch *= keep
            return doubleArrayOf(velocityYaw * dt, velocityPitch * dt)
        }
        if (phase == Phase.DELAY) phase = Phase.MOVE

        // The point the view heads for: the target, past it while an overshoot lasts, with the aim wandering around it.
        val wanderSize = if (activity.precise) 0.0 else turnWander * k
        wanderYaw = ou(wanderYaw, dt, WANDER_SECONDS, wanderSize, random)
        wanderPitch = ou(wanderPitch, dt, WANDER_SECONDS, wanderSize * pitchWeight, random)
        var goalYaw = yawError + wanderYaw
        var goalPitch = pitchError + wanderPitch
        if (overshooting) {
            val towardYaw = goalYaw + overshootYaw
            val towardPitch = goalPitch + overshootPitch
            // Over once the view is at the far point, or has gone past the target the other way.
            if (hypot(towardYaw, towardPitch) < OVERSHOOT_ARRIVED || towardYaw * overshootYaw + towardPitch * overshootPitch < 0.0) {
                overshooting = false
                phase = Phase.RECOVER
                diagnostics?.invoke("overshoot reached n=$turnNumber activity=${activity.name} afterMs=${fmt(turnSeconds * 1000.0)} speed=${fmt(speed())}")
                turnSize = hypot(goalYaw, goalPitch)
                peak *= RECOVER_SPEED_SHARE
                skew = RECOVER_SKEW
            } else {
                goalYaw = towardYaw
                goalPitch = towardPitch
            }
        }
        val remaining = hypot(goalYaw, goalPitch)
        if (phase != Phase.HOLD && remaining < HOLD_DEGREES && speed() < SETTLED_SPEED) {
            diagnostics?.invoke("settled n=$turnNumber activity=${activity.name} afterMs=${fmt(turnSeconds * 1000.0)} residual=${fmt(remaining)}")
        }
        if (remaining <= SNAP_DEGREES && !overshooting && wanderSize == 0.0) {
            velocityYaw = 0.0
            velocityPitch = 0.0
            phase = Phase.HOLD
            return doubleArrayOf(goalYaw, goalPitch)
        }
        if (phase == Phase.HOLD && remaining > REENGAGE_DEGREES) {
            engage(settings, activity, remaining, distance, random)
            phase = Phase.MOVE
        } else if ((phase == Phase.MOVE || phase == Phase.RECOVER) && remaining < HOLD_DEGREES) {
            phase = Phase.HOLD
        }

        val dirYaw = if (remaining > 1.0E-9) goalYaw / remaining else 0.0
        val dirPitch = if (remaining > 1.0E-9) goalPitch / remaining else 0.0

        var desired = 0.0
        if (phase != Phase.HOLD) {
            val size = max(turnSize, remaining)
            turnSize = size
            val progress = 1.0 - remaining / size
            val warped = progress.pow(ln(0.5) / ln(skew))
            val bell = (4.0 * warped * (1.0 - warped)).pow(BELL_SHARPNESS)
            desired = min(peak * max(bell, START_FLOOR), remaining * HOMING_RATE)

            if (humanized && remaining > 0.5) {
                speedNoise = ou(speedNoise, dt, NOISE_SECONDS, NOISE_SIZE * turnNoise * k, random)
                desired *= 1.0 + speedNoise.coerceIn(-NOISE_LIMIT, NOISE_LIMIT)
                if (pauseLeft > 0.0) {
                    pauseLeft -= dt
                    desired *= PAUSE_SPEED_SHARE
                } else if (phase == Phase.MOVE && remaining > PAUSE_FROM_DEGREES && progress in 0.12..0.88 &&
                    random.nextDouble() < turnPauses * k * dt
                ) {
                    pauseLeft = PAUSE_MIN_SECONDS + random.nextDouble() * (PAUSE_MAX_SECONDS - PAUSE_MIN_SECONDS)
                }
            }
        }

        // Holding is not standing still: what is left of the aim keeps being closed, gently.
        if (phase == Phase.HOLD) desired = remaining * HOMING_RATE
        var wantedYaw = dirYaw * desired
        var wantedPitch = dirPitch * desired
        if (phase != Phase.HOLD && curve != 0.0 && remaining > 0.5) {
            // Sideways, as a share of the speed: zero at the start and at the arrival.
            val progress = 1.0 - remaining / max(turnSize, remaining)
            val bend = curve * BEND_SHARE * sin(Math.PI * clamp01(progress)) * desired
            wantedYaw += -dirPitch * bend
            wantedPitch += dirYaw * bend * pitchWeight
        }
        // Following a moving target: its own angular speed on top, so a steady target is held and a mover is kept up with.
        val pursuit = if (phase == Phase.HOLD) 1.0 else PURSUIT_SHARE
        wantedYaw += targetVelocityYaw * pursuit
        wantedPitch += targetVelocityPitch * pursuit

        val response = 1.0 - exp(-dt / tau)
        velocityYaw += (wantedYaw - velocityYaw) * response
        velocityPitch += (wantedPitch - velocityPitch) * response
        var stepYaw = velocityYaw * dt
        var stepPitch = velocityPitch * dt

        // The lag alone never carries the view past the target: only a chosen overshoot does.
        if (!overshooting && remaining > 0.0) {
            val along = stepYaw * dirYaw + stepPitch * dirPitch
            if (along > remaining) {
                val scale = remaining / along
                stepYaw *= scale
                stepPitch *= scale
                velocityYaw *= scale
                velocityPitch *= scale
            }
        }
        return doubleArrayOf(stepYaw, stepPitch)
    }

    /** A new turn: what to do about a target that is somewhere else than before. */
    private fun newTurn(
        settings: RotationConfig.Settings,
        next: RotationConfig.Activity,
        yawError: Double,
        pitchError: Double,
        distance: Double,
        random: RandomGenerator,
        cause: String,
    ) {
        activity = next
        hasTurn = true
        turnNumber++
        turnSeconds = 0.0
        val moving = speed() > MOVING_SPEED
        val humanized = settings.humanized
        val k = if (humanized) settings.effectIntensity else 0.0
        val length = hypot(yawError, pitchError)
        overshooting = false
        curve = 0.0
        pauseLeft = 0.0
        delayLeft = 0.0
        targetVelocityYaw = 0.0
        targetVelocityPitch = 0.0
        turnWander = draw(settings.aimWanderMin, settings.aimWander, random)
        turnNoise = draw(settings.speedNoiseMin, settings.speedNoise, random)
        turnPauses = draw(settings.pausesPerSecondMin, settings.pausesPerSecond, random)
        turnCurve = draw(settings.curveStrengthMin, settings.curveStrength, random)
        turnOvershootChance = draw(settings.overshootChanceMin, settings.overshootChance, random)
        turnOvershootSize = draw(settings.overshootSizeMin, settings.overshootSize, random)
        engage(settings, next, length, distance, random)
        phase = Phase.MOVE
        if (humanized && k > 0.0) humanize(settings, next, yawError, pitchError, length, moving, k, random)
        diagnostics?.invoke(
            "turn n=$turnNumber activity=${next.name} size=${fmt(length)} peak=${fmt(peak)} skew=${fmt(skew)} lagMs=${fmt(tau * 1000.0)} " +
                "delayMs=${fmt(delayLeft * 1000.0)} curve=${fmt(curve)} overshoot=${overshooting} cause=$cause " +
                "startSpeed=${fmt(speed())} moving=$moving dist=${fmt(distance)}",
        )
    }

    /** The effects a human turn has and a clean one does not: reaction delay, bend, overshoot. */
    private fun humanize(
        settings: RotationConfig.Settings,
        next: RotationConfig.Activity,
        yawError: Double,
        pitchError: Double,
        length: Double,
        moving: Boolean,
        k: Double,
        random: RandomGenerator,
    ) {
        if (next.reacts && !moving && length > REACT_FROM_DEGREES) {
            val share = random.nextDouble().pow(1.5)
            delayLeft = (settings.reactionMinMs + share * (settings.reactionMaxMs - settings.reactionMinMs)) / 1000.0
            if (delayLeft > 0.0) phase = Phase.DELAY
        }
        val pitchWeight = clamp01((abs(pitchError) - PITCH_NEED_FREE) / PITCH_NEED_FULL) * settings.verticalMovement
        if (length > CURVE_FROM_DEGREES) {
            curve = (if (random.nextBoolean()) 1.0 else -1.0) * (0.3 + random.nextDouble() * 0.7) * turnCurve * k
        }
        if (!next.precise && length > OVERSHOOT_FROM_DEGREES && random.nextDouble() * 100.0 < turnOvershootChance * k) {
            val past = length * turnOvershootSize / 100.0 * (0.6 + random.nextDouble() * 0.8)
            val side = (if (random.nextBoolean()) 1.0 else -1.0) * OVERSHOOT_SIDE_SHARE * past
            val dirYaw = yawError / length
            val dirPitch = pitchError / length
            overshootYaw = dirYaw * past - dirPitch * side
            overshootPitch = (dirPitch * past + dirYaw * side) * pitchWeight
            overshooting = past > 0.25
        }
    }

    /** One draw between the two ends of a range setting. */
    private fun draw(low: Double, high: Double, random: RandomGenerator): Double =
        if (high <= low) high else low + random.nextDouble() * (high - low)

    /** Draws the speed, shape and lag of a move over [length] degrees and clamps its duration into the time range. */
    private fun engage(
        settings: RotationConfig.Settings,
        next: RotationConfig.Activity,
        length: Double,
        distance: Double,
        random: RandomGenerator,
    ) {
        val humanized = settings.humanized
        val pace = if (humanized) {
            settings.paceMin + random.nextDouble() * (settings.paceMax - settings.paceMin)
        } else {
            (settings.paceMin + settings.paceMax) / 2.0
        }
        val base = next.baseSpeed * pace * settings.distanceFactor(distance)
        var seconds = MIN_JERK_PEAK * max(length, 0.01) / base
        if (humanized) seconds *= 0.9 + random.nextDouble() * 0.2
        seconds = seconds.coerceIn(settings.timeMinMs / 1000.0, settings.timeMaxMs / 1000.0)
        turnSize = length
        peak = min(MAX_PEAK_SPEED, MIN_JERK_PEAK * length / seconds)
        skew = if (humanized) SKEW_LOW + random.nextDouble() * (SKEW_HIGH - SKEW_LOW) else 0.5
        tau = if (humanized) LAG_LOW + random.nextDouble() * (LAG_HIGH - LAG_LOW) else LAG_LOW
    }

    companion object {
        /** A minimum-jerk move's peak speed is this many times its mean speed. */
        private const val MIN_JERK_PEAK = 1.875
        private const val MAX_PEAK_SPEED = 1600.0

        /** The peak of the bell comes at this share of the way, drawn per turn; a hand starts quicker than it ends. */
        private const val SKEW_LOW = 0.34
        private const val SKEW_HIGH = 0.5
        private const val BELL_SHARPNESS = 0.9

        /** The view starts moving at once: at least this share of the peak. */
        private const val START_FLOOR = 0.14

        /** The rate, per second, at which what is left shrinks at the end: a time constant of about 90 ms. */
        private const val HOMING_RATE = 11.0

        /** How soon the speed follows what it should be, in seconds, drawn per turn. */
        private const val LAG_LOW = 0.04
        private const val LAG_HIGH = 0.075

        private const val MAX_DT = 0.1
        private const val SETTLED_SPEED = 25.0

        /**
         * Receives one line for the start of every turn and for its overshoot and settling, while a diagnostics sink is
         * set (the Rotation Engine module's). Called on the game thread; the sink bounds the volume.
         */
        @JvmField
        var diagnostics: ((String) -> Unit)? = null

        private fun fmt(value: Double): String = if (value.isNaN()) "NaN" else String.format(java.util.Locale.ROOT, "%.2f", value)
        private const val SNAP_DEGREES = 0.015
        private const val HOLD_DEGREES = 0.35
        private const val REENGAGE_DEGREES = 1.2
        private const val MOVING_SPEED = 40.0
        private const val REACT_FROM_DEGREES = 6.0
        private const val CURVE_FROM_DEGREES = 3.0
        private const val OVERSHOOT_FROM_DEGREES = 12.0
        private const val OVERSHOOT_ARRIVED = 0.8
        private const val OVERSHOOT_SIDE_SHARE = 0.25
        private const val RECOVER_SPEED_SHARE = 0.5
        private const val RECOVER_SKEW = 0.4
        private const val BEND_SHARE = 0.22

        private const val PURSUIT_SHARE = 0.9
        private const val TARGET_SPEED_SECONDS = 0.06

        /** Pitch counts as needed from this many degrees, fully from this many more. */
        private const val PITCH_NEED_FREE = 1.0
        private const val PITCH_NEED_FULL = 7.0

        private const val NOISE_SECONDS = 0.2
        private const val NOISE_SIZE = 0.1
        private const val NOISE_LIMIT = 0.4
        private const val WANDER_SECONDS = 0.25

        private const val PAUSE_FROM_DEGREES = 3.0
        private const val PAUSE_SPEED_SHARE = 0.15
        private const val PAUSE_MIN_SECONDS = 0.04
        private const val PAUSE_MAX_SECONDS = 0.12

        private const val COAST_SECONDS = 0.07
        private const val COAST_STOP_SPEED = 2.0

        private fun clamp01(value: Double): Double = if (value < 0.0) 0.0 else if (value > 1.0) 1.0 else value

        /** An angle brought into -180 to 180 degrees. */
        private fun wrap(degrees: Double): Double {
            var wrapped = degrees % 360.0
            if (wrapped >= 180.0) {
                wrapped -= 360.0
            } else if (wrapped < -180.0) {
                wrapped += 360.0
            }
            return wrapped
        }

        /** One step of an Ornstein-Uhlenbeck process: noise that drifts back to zero, the same at any frame rate. */
        private fun ou(value: Double, dt: Double, seconds: Double, size: Double, random: RandomGenerator): Double {
            if (size <= 0.0) return 0.0
            val keep = exp(-dt / seconds)
            return value * keep + size * sqrt(1.0 - keep * keep) * random.nextGaussian()
        }
    }
}
