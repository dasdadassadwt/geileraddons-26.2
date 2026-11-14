package geiler.addons.client.macros.navigation

import kotlin.math.abs

/**
 * Whether a teleport landing is somewhere to stand, not just somewhere with ground a long way below: a hop that carries the player past
 * an edge puts them over a drop. Plain arithmetic, so checks can run it.
 */
object HopRules {
    /** How far the floor under a landing may be from the route's height at that point, in blocks. */
    const val ROUTE_HEIGHT_TOLERANCE = 2.0

    /**
     * [landingY] is where the feet end up, [floorY] the ground under them, [routeY] the height of the route at that point (or NaN when
     * there is no route to hold it against, as for a hop onto a mob). The fall is the way down to the floor.
     */
    @JvmStatic
    fun landingOk(landingY: Double, floorY: Double, routeY: Double, hopDrop: Double): Boolean {
        if (floorY.isNaN() || landingY - floorY > hopDrop) return false
        return routeY.isNaN() || abs(floorY - routeY) <= ROUTE_HEIGHT_TOLERANCE
    }
}
