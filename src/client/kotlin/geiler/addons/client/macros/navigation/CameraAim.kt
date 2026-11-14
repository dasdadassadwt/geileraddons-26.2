package geiler.addons.client.macros.navigation

import geiler.addons.client.macros.killer.LookFocus

/**
 * Where the view looks while a route is walked. A route that bends back on itself - down off a ledge and round - has points a few
 * blocks ahead that lie 150 degrees from the way the keys push; a view on one of them never turns to the heading, and the
 * turn-first hold waits for a turn that is not coming. So the camera point is taken only from the part of the route that lies along
 * the heading.
 */
object CameraAim {
    /** How far, in degrees, a camera point may lie from the heading the keys are pointed at. */
    const val MAX_OFF_HEADING_DEGREES = 50.0

    /** Whether the point ([tx], [tz]), seen from ([px], [pz]), lies within [MAX_OFF_HEADING_DEGREES] of the heading [headingYaw]. */
    @JvmStatic
    fun along(px: Double, pz: Double, tx: Double, tz: Double, headingYaw: Double): Boolean =
        LookFocus.offDegrees(headingYaw, px, pz, tx, tz) <= MAX_OFF_HEADING_DEGREES
}

/**
 * The height the view looks at while a route is walked, eased. The route climbs a slab at a time and the player's eyes go up with each
 * step; a view that follows the height of every point exactly nods at each of them. The value is how far above or below the eyes the
 * point is, and it moves a fraction of the way to each new reading, so a step is a slope and the view stays level.
 */
class LookHeight {
    private var value = Double.NaN

    fun filter(raw: Double): Double {
        value = if (value.isNaN()) raw else value + (raw - value) * EASE
        return value
    }

    fun reset() {
        value = Double.NaN
    }

    companion object {
        /** The share of the way to a new reading taken each tick: about half a second to settle. */
        private const val EASE = 0.12
    }
}
