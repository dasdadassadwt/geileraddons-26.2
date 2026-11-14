package geiler.addons.client.macros.killer

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The rest point: where the player goes and loiters when there is nothing left to hit, so the next mobs have time to load in. It is
 * one setting for the macro, not one per area. These are the rules; the walking there is the macro's.
 */
object RestPoint {
    /** Whether a rest point is set: three block coordinates. */
    @JvmStatic
    fun configured(point: IntArray?): Boolean = point != null && point.size == 3

    /** Whether nothing has been in reach for [delaySeconds], so it is time to go to the rest point. */
    @JvmStatic
    fun due(idleMillis: Long, delaySeconds: Double): Boolean = idleMillis >= delaySeconds * 1000.0

    @JvmStatic
    fun inside(px: Double, pz: Double, cx: Double, cz: Double, radius: Double): Boolean = hypot(px - cx, pz - cz) <= radius

    /** A spot in the disc round ([cx], [cz]), spread evenly over it: [angle01] picks the direction and [distance01] the distance. */
    @JvmStatic
    fun spot(cx: Double, cz: Double, radius: Double, angle01: Double, distance01: Double): DoubleArray {
        val angle = angle01 * 2.0 * Math.PI
        val reach = radius * sqrt(distance01)
        return doubleArrayOf(cx + cos(angle) * reach, cz + sin(angle) * reach)
    }

    /** How long to stand at a spot before the next one, in milliseconds: a few seconds. */
    @JvmStatic
    fun pauseMillis(random01: Double): Long = MIN_PAUSE_MILLIS + (random01 * (MAX_PAUSE_MILLIS - MIN_PAUSE_MILLIS)).toLong()

    private const val MIN_PAUSE_MILLIS = 2_500L
    private const val MAX_PAUSE_MILLIS = 7_000L
}

/**
 * Notices a walk that is going nowhere: the keys were asked to move the player and the speed stays under a block a second. Reports when
 * it has lasted [AFTER_TICKS] ticks and then every [REPEAT_TICKS], so the log says where and how long without a line a tick.
 */
class StallWatch {
    private var ticks = 0

    /** How long the current stall has lasted, in ticks; 0 when there is none. */
    fun ticks(): Int = ticks

    /** One tick: whether a movement was [asked] for and how fast the player is going, in blocks a second. True when to report. */
    fun tick(asked: Boolean, speed: Double): Boolean {
        if (!asked || speed >= MIN_SPEED) {
            ticks = 0
            return false
        }
        ticks++
        return ticks == AFTER_TICKS || (ticks > AFTER_TICKS && (ticks - AFTER_TICKS) % REPEAT_TICKS == 0)
    }

    companion object {
        const val AFTER_TICKS = 10
        const val REPEAT_TICKS = 20
        private const val MIN_SPEED = 1.0
    }
}
