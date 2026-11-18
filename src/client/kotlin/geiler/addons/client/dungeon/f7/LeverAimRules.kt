package geiler.addons.client.dungeon.f7

import java.util.random.RandomGenerator
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The maths behind Lever Aim Assist: where a lever is in view, which one to help with, how far the pull turns the view
 * before it stops, and when the trigger clicks. No Minecraft types, so it is checked offline.
 */
object LeverAimRules {
    /** Marks "no lever" for [Trigger.update]. */
    const val NO_KEY = Long.MIN_VALUE

    /** A lever's armor stand stands in its block; a block farther than this from the stand is another lever's. */
    private const val STAND_RADIUS = 1.5

    @JvmStatic
    fun wrap(degrees: Double): Double {
        var wrapped = degrees % 360.0
        if (wrapped >= 180.0) wrapped -= 360.0 else if (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }

    /** Minecraft yaw of a direction: 0 looks south (+z), 90 west (-x). */
    @JvmStatic
    fun yawTo(dx: Double, dz: Double): Double = Math.toDegrees(atan2(-dx, dz))

    /** Minecraft pitch of a direction: negative looks up, 90 straight down. */
    @JvmStatic
    fun pitchTo(dx: Double, dy: Double, dz: Double): Double = -Math.toDegrees(atan2(dy, hypot(dx, dz)))

    /** The angle between two views, in degrees, the same measure the Rotation Engine calls its residual. */
    @JvmStatic
    fun angularDistance(yaw: Double, pitch: Double, wantYaw: Double, wantPitch: Double): Double =
        hypot(wrap(wantYaw - yaw), wantPitch - pitch)

    /** The index of the smallest distance inside [fov], or -1. The first of equals wins. */
    @JvmStatic
    fun pick(distances: DoubleArray, fov: Double): Int {
        var best = -1
        var bestDistance = fov
        for (i in distances.indices) {
            if (distances[i] <= bestDistance && (best < 0 || distances[i] < distances[best])) {
                best = i
                bestDistance = distances[i]
            }
        }
        return best
    }

    /** The lever block a stand at ([x], [y], [z]) belongs to: the nearest of [blocks] (x, y, z) within reach of its centre. */
    @JvmStatic
    fun nearestBlock(x: Double, y: Double, z: Double, blocks: Array<IntArray>): IntArray? {
        var best: IntArray? = null
        var bestDistance = STAND_RADIUS
        for (block in blocks) {
            val dx = block[0] + 0.5 - x
            val dy = block[1] + 0.5 - y
            val dz = block[2] + 0.5 - z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            if (distance < bestDistance) {
                best = block
                bestDistance = distance
            }
        }
        return best
    }

    /** One delay between the two values ([unit] is a draw from 0 to 1); the values may come in either order. */
    @JvmStatic
    fun drawDelayMs(minMs: Long, maxMs: Long, unit: Double): Long {
        val low = max(0L, min(minMs, maxMs))
        val high = max(0L, max(minMs, maxMs))
        return low + ((high - low) * unit.coerceIn(0.0, 1.0)).toLong()
    }

    /**
     * Turns the view part of the way to a lever and stops. A pull has a fixed budget: [strength] of the angle to the
     * lever when it began. Each frame spends a share of what is left, so it eases in and out and ends in a gentle nudge
     * instead of a lock. The player's own turning away, or another lever, starts a new pull.
     */
    class Pull {
        private var key = NO_KEY
        private var budget = 0.0
        private var spent = 0.0
        private var startError = 0.0

        fun reset() {
            key = NO_KEY
            budget = 0.0
            spent = 0.0
            startError = 0.0
        }

        /**
         * One frame's turn, as yaw and pitch degrees to add to the view.
         *
         * @param errYaw yaw from the view to the lever, wrapped
         * @param errPitch pitch from the view to the lever
         * @param rate how fast the pull spends its budget, per second
         */
        fun step(key: Long, errYaw: Double, errPitch: Double, seconds: Double, strength: Double, rate: Double): DoubleArray {
            val error = hypot(errYaw, errPitch)
            if (this.key != key || error > startError + TURNED_AWAY_DEGREES) {
                this.key = key
                startError = error
                budget = error * strength.coerceIn(0.0, 1.0)
                spent = 0.0
            }
            if (error < 1.0E-9) return doubleArrayOf(0.0, 0.0)
            val left = budget - spent
            if (left <= 1.0E-6) return doubleArrayOf(0.0, 0.0)
            val travel = min(min(left * (1.0 - exp(-rate * max(seconds, 0.0))), left), error)
            spent += travel
            return doubleArrayOf(errYaw / error * travel, errPitch / error * travel)
        }

        private companion object {
            /** An error that grew by more than this since the pull began means the view was turned away. */
            const val TURNED_AWAY_DEGREES = 3.0
        }
    }

    /**
     * Decides when the trigger clicks: a lever in view arms a delay drawn between two values, the click comes once the
     * delay is over and the crosshair is on the lever, and a lever just clicked rests for a moment.
     */
    class Trigger(private val random: RandomGenerator) {
        private var pending = NO_KEY
        private var fireAtMs = 0L
        private var lastDelay = 0L
        private val restingUntil = HashMap<Long, Long>()

        /** The delay drawn for the lever armed last, in milliseconds. */
        fun lastDelayMs(): Long = lastDelay

        /** How many levers are remembered as just clicked; bounded. */
        fun cooldownEntries(): Int = restingUntil.size

        fun clear() {
            pending = NO_KEY
        }

        /** Whether the lever armed is armed now, for diagnostics. */
        fun armed(): Boolean = pending != NO_KEY

        /**
         * One tick. Returns true when the click should be sent now.
         *
         * @param inFov the lever is inside the trigger cone
         * @param aimed the crosshair is on the lever
         */
        fun update(key: Long, inFov: Boolean, aimed: Boolean, nowMs: Long, minMs: Long, maxMs: Long): Boolean {
            if (key == NO_KEY || !inFov) {
                pending = NO_KEY
                return false
            }
            val rest = restingUntil[key]
            if (rest != null) {
                if (nowMs < rest) {
                    pending = NO_KEY
                    return false
                }
                restingUntil.remove(key)
            }
            if (pending != key) {
                pending = key
                lastDelay = drawDelayMs(minMs, maxMs, random.nextDouble())
                fireAtMs = nowMs + lastDelay
            }
            if (nowMs < fireAtMs || !aimed) return false
            restingUntil[key] = nowMs + COOLDOWN_MS
            if (restingUntil.size > MAX_ENTRIES) restingUntil.values.removeIf { it <= nowMs }
            pending = NO_KEY
            return true
        }

        private companion object {
            const val COOLDOWN_MS = 1500L
            const val MAX_ENTRIES = 16
        }
    }
}
