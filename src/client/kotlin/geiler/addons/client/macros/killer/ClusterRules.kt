package geiler.addons.client.macros.killer

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Two small decisions of the Area Killer as plain arithmetic: when to stop walking because a crowd is within reach, and whether a
 * stretch of route has mobs along it that are to be killed on the way before it is skipped by a teleport. Free of game state so checks
 * can run them.
 */
object ClusterRules {
    /** Mobs within reach (and a little more) it takes to stop walking and fight where one stands. */
    const val ENTER_COUNT = 2

    /** How far beyond the weapon's reach a mob still counts as in the crowd, in blocks. */
    const val REACH_MARGIN = 1.5

    /** How far from a stretch of route a mob counts as on it, in blocks, and how far above or below. */
    const val ROUTE_SIDE = 6.0
    const val ROUTE_HEIGHT = 3.0

    /**
     * Whether the player stands to fight. It starts with [ENTER_COUNT] mobs near and goes on while even one is: a crowd that thins out
     * is finished where it is, not walked into the next one from the middle of it.
     */
    @JvmStatic
    fun standing(near: Int, was: Boolean): Boolean = if (was) near >= 1 else near >= ENTER_COUNT

    /** Mobs this close (blocks, all three ways) to the nearest one that can be hit are one crowd: five blocks apart is not. */
    const val CLUSTER_RADIUS = 3.0

    /** Ticks standing with nothing to hit after which the stand is given up, and how long (ms) it then stays off. */
    const val IDLE_TICKS = 10
    const val COOLDOWN_MILLIS = 3000L

    /**
     * How many mobs make up the crowd at the nearest one that can be hit now: that one and the others within [CLUSTER_RADIUS] of it.
     * [points] are mob positions (x, y, z), [hittable] says for each whether it is in reach and in sight. Zero when none can be hit,
     * so a crowd out of reach is never stood in front of.
     */
    @JvmStatic
    fun crowdSize(points: List<DoubleArray>, hittable: List<Boolean>): Int {
        val first = hittable.indexOf(true)
        if (first < 0) return 0
        val centre = points[first]
        var count = 0
        for (point in points) {
            val dx = point[0] - centre[0]
            val dy = point[1] - centre[1]
            val dz = point[2] - centre[2]
            if (dx * dx + dy * dy + dz * dz <= CLUSTER_RADIUS * CLUSTER_RADIUS) count++
        }
        return count
    }

    /** Whether a stand that has had nothing to hit for [idleTicks] ticks is given up. */
    @JvmStatic
    fun giveUp(idleTicks: Int): Boolean = idleTicks >= IDLE_TICKS

    /** Mobs this much above or below the player count as on their level. */
    const val LEVEL_HEIGHT = 3.0

    /**
     * The indices of the mobs (by height) worth going for now: those on the player's level while there are any, else all of them. The
     * balcony waits until the floor is clear, and a floor that respawns is not left for the walk up the stairs and round.
     */
    @JvmStatic
    fun onLevel(playerY: Double, heights: List<Double>): List<Int> {
        val same = heights.indices.filter { abs(heights[it] - playerY) <= LEVEL_HEIGHT }
        return if (same.isEmpty()) heights.indices.toList() else same
    }

    /**
     * Whether any mob (x, y, z) is near any of the sampled points (x, y, z) of a stretch of route: the stretch has something to kill on it.
     */
    @JvmStatic
    fun mobsOnStretch(points: List<DoubleArray>, mobs: List<DoubleArray>): Boolean {
        for (mob in mobs) {
            for (point in points) {
                if (hypot(mob[0] - point[0], mob[2] - point[2]) <= ROUTE_SIDE && abs(mob[1] - point[1]) <= ROUTE_HEIGHT) return true
            }
        }
        return false
    }
}
