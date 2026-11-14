package geiler.addons.client.macros.killer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What the camera is on while a route is walked. A person who runs somewhere looks where they are running, and glances at a mob only
 * when it is more or less where they are heading; a view that jumps to a mob 90 degrees off the way the keys push is what left the
 * player standing, turning, or strafing sideways at it. Plain arithmetic, so checks can run it.
 */
object LookPlan {
    /** A mob this close to the heading, in degrees, is looked at; one already looked at is kept up to [MOB_KEEP]. */
    const val MOB_CONE = 25.0
    const val MOB_KEEP = 40.0

    /** Going onto a mob from the route is not done sooner than this after the last switch, in milliseconds. */
    const val DWELL_MILLIS = 800L

    /**
     * Whether the camera is on the mob: [mobOffHeading] is how far, in degrees, the mob lies from the way the keys push (a sign does
     * not matter), [hasLos] whether it can be seen, [onMobNow] whether the camera is on it now and [sinceSwitchMillis] how long since
     * it last changed. A mob that cannot be seen is never looked at; in a fight the target always is.
     */
    @JvmStatic
    fun onMob(mobOffHeading: Double, hasLos: Boolean, onMobNow: Boolean, sinceSwitchMillis: Long, fighting: Boolean): Boolean {
        if (fighting) return true
        if (!hasLos) return false
        val off = abs(mobOffHeading)
        val want = if (onMobNow) off <= MOB_KEEP else off <= MOB_CONE
        if (want && !onMobNow && sinceSwitchMillis < DWELL_MILLIS) return false
        return want
    }
}

/** How far ahead along the route the camera looks: a little past the point the keys are steered at, so it leads the walk and the two agree. */
object CameraLead {
    /** How far past the steer point, along the route, the camera looks. */
    const val LEAD = 1.5

    /** The least look-ahead, in blocks. */
    const val MIN = 2.0

    /** [steerAhead] is how far along the route the steer point is (NaN for none), [old] the look-ahead there used to be, [remaining] the route left. */
    @JvmStatic
    fun ahead(remaining: Double, steerAhead: Double, old: Double): Double {
        val base = if (steerAhead.isNaN()) old else min(old, steerAhead + LEAD)
        return max(MIN, min(base, remaining))
    }
}
