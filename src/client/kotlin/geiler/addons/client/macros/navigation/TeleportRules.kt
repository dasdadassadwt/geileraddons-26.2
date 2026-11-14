package geiler.addons.client.macros.navigation

import kotlin.math.sqrt

/** The decision of a warp, from facts: plain data and arithmetic, so checks can run it. */
object WarpVerdict {
    /** The landing the Etherwarp Sim says may be this far from the landing planned, in blocks. */
    const val LAND_TOLERANCE = 0.75

    /**
     * "ok", or why the click must not go out: the Etherwarp Sim does not paint the block green ([valid] false, with its [reason]), the
     * block it paints is not where the plan was made for, or the way to it goes through something shut. The plan's landing is NaN when
     * there is none to match.
     */
    @JvmStatic
    fun decide(
        valid: Boolean, reason: String, landX: Double, landY: Double, landZ: Double,
        planX: Double, planY: Double, planZ: Double, crossesShut: Boolean,
    ): String {
        if (!valid) return "sim: $reason"
        if (!planX.isNaN()) {
            val dx = landX - planX
            val dy = landY - planY
            val dz = landZ - planZ
            if (sqrt(dx * dx + dy * dy + dz * dz) > LAND_TOLERANCE) return "lands elsewhere"
        }
        if (crossesShut) return "crosses shut zone"
        return "ok"
    }
}

/** Whether the Area Killer may etherwarp along its own route, which it does only for a long clear stretch that a warp really shortens. */
object WarpRule {
    /** The least route left for a warp to be worth the crouch and the aim, in blocks. */
    const val MIN_ROUTE = 35.0

    /** The share of the flight a landing has to take off the route. */
    const val MIN_GAIN_SHARE = 0.8

    @JvmStatic
    @JvmOverloads
    fun allow(remaining: Double, flight: Double, gain: Double, mobsNear: Boolean, minRoute: Double = MIN_ROUTE): Boolean {
        if (flight <= 0.0 || mobsNear) return false
        if (remaining < minRoute) return false
        return gain >= flight * MIN_GAIN_SHARE - 1.0E-9
    }
}

/** When a stuck route may be escaped by teleporting. */
object StuckEscape {
    /** Two off-path failures this close together, in milliseconds, count as one that does not go away. */
    const val OFF_PATH_WINDOW_MILLIS = 4000L

    /** Whether [step] ("STUCK", "OFF_PATH", ...) may be answered with a teleport: [offPathCount] is how many off-path failures have come in a row, [sinceFirstMillis] the time since the first. */
    @JvmStatic
    fun warpAllowed(step: String, offPathCount: Int, sinceFirstMillis: Long): Boolean = when (step) {
        "STUCK" -> true
        "OFF_PATH" -> offPathCount >= 2 && sinceFirstMillis <= OFF_PATH_WINDOW_MILLIS
        else -> false
    }

    /** Whether a warp onto a mob is a better way there than walking: the walk is over twice the straight distance, or there is none. NaN: no map, no say. */
    @JvmStatic
    fun mobWarpUseful(walkCost: Double, straight: Double): Boolean {
        if (walkCost.isNaN()) return true
        return walkCost > straight * 2.0
    }
}
