package geiler.addons.client.macros.killer

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Whether the view is on the target. The target is looked at, at any distance, whenever it can be seen and is inside the
 * view cone; otherwise the view follows the way ahead. The cone has hysteresis - it is entered at [ENTER_DEGREES] off the view
 * and left at [LEAVE_DEGREES] - so a mob at its edge does not make the view flip between the mob and the route, which started
 * a new turn each time.
 *
 * Angles are Minecraft yaw: 0 south, 90 west.
 */
class LookFocus {
    private var locked = false

    /** [offDegrees] is how far the target is from where the view points; [visible] whether there is a clear line to it. */
    fun update(offDegrees: Double, visible: Boolean): Boolean {
        locked = visible && abs(offDegrees) <= (if (locked) LEAVE_DEGREES else ENTER_DEGREES)
        return locked
    }

    fun reset() {
        locked = false
    }

    companion object {
        const val ENTER_DEGREES = 70.0
        const val LEAVE_DEGREES = 100.0

        /** The angle, 0 to 180, between the view's yaw and the direction from ([px], [pz]) to ([tx], [tz]). */
        @JvmStatic
        fun offDegrees(viewYaw: Double, px: Double, pz: Double, tx: Double, tz: Double): Double {
            val dx = tx - px
            val dz = tz - pz
            if (dx * dx + dz * dz < 1.0E-12) return 0.0
            val bearing = Math.toDegrees(atan2(-dx, dz))
            var d = (bearing - viewYaw) % 360.0
            if (d > 180.0) d -= 360.0 else if (d < -180.0) d += 360.0
            return abs(d)
        }
    }
}

/**
 * Where the view rests when there is nothing to hit: level, a little up at most, and drifting a few degrees either side of a
 * base heading. Never at the sky, never at the ground, never at some far point of its own.
 */
object IdleLook {
    const val MAX_DRIFT_DEGREES = 25.0
    const val MAX_PITCH_DEGREES = 3.0

    /** The yaw and pitch to rest at, from two even draws between 0 and 1. */
    @JvmStatic
    fun angles(baseYaw: Double, drawYaw: Double, drawPitch: Double): DoubleArray =
        doubleArrayOf(baseYaw + (drawYaw * 2.0 - 1.0) * MAX_DRIFT_DEGREES, drawPitch * MAX_PITCH_DEGREES)
}
