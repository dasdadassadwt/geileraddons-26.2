package geiler.addons.client.macros.pathing

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Doors, gates and gaps between pillars: a place with a wall close on both sides of the way through. A route that goes through one at an
 * angle - a straight line from the last waypoint to the next, which is how a route is pulled taut - scrapes the wall and has to be
 * worked free of it. Walked from a point two blocks back in line with the opening, it goes straight in. Plain arithmetic over the room
 * round a point, so checks can run it without a world.
 */
object GapRules {
    /** Room to a side below which that side is a wall for a body of this width (the room is what is left beyond the body). */
    const val TIGHT = 0.35

    /** How far back from the opening the lead-in point is, in blocks, and the shorter one tried when that does not fit. */
    private val LEADS = doubleArrayOf(2.5, 1.5)

    /** A route this straight through the opening is left alone: degrees off the passage's axis and blocks off its middle. */
    private const val STRAIGHT_DEGREES = 10.0
    private const val CENTRED = 0.12

    private const val SAMPLE = 0.5

    /** How far each side is looked at: only the nearest wall matters, so a little more than [TIGHT]. */
    const val REACH = 0.6

    /** The room, in blocks, that a body at (x, z) has before a wall in direction (dirX, dirZ). */
    fun interface Room {
        fun at(x: Double, z: Double, dirX: Double, dirZ: Double): Double
    }

    /** Whether the player can walk from the first point to the second in a straight line. */
    fun interface Walk {
        fun ok(fromX: Double, fromZ: Double, toX: Double, toZ: Double): Boolean
    }

    /** The point to walk to first, in line with the opening, and the middle of the opening itself, both as x and z. */
    class Lead(@JvmField val leadX: Double, @JvmField val leadZ: Double, @JvmField val gapX: Double, @JvmField val gapZ: Double)

    /**
     * For the stretch of route from (ax, az) to (bx, bz): the lead-in that makes it go straight through the first opening on it, or null
     * when there is none, it is already straight, or no lead-in point can be walked to.
     */
    @JvmStatic
    fun find(ax: Double, az: Double, bx: Double, bz: Double, room: Room, walk: Walk): Lead? {
        val length = hypot(bx - ax, bz - az)
        if (length < 1.0) return null
        val dirX = (bx - ax) / length
        val dirZ = (bz - az) / length
        var d = SAMPLE
        while (d < length) {
            val x = ax + dirX * d
            val z = az + dirZ * d
            val east = room.at(x, z, 1.0, 0.0)
            val west = room.at(x, z, -1.0, 0.0)
            val south = room.at(x, z, 0.0, 1.0)
            val north = room.at(x, z, 0.0, -1.0)
            val acrossX = east < TIGHT && west < TIGHT
            val acrossZ = south < TIGHT && north < TIGHT
            // A wall each side one way and not the other is a passage along the other way; walls all round are a pillar's corner, none a field.
            if (acrossX != acrossZ) {
                // The passage runs along Z when its walls are east and west of it, else along X.
                val axisX = if (acrossX) 0.0 else 1.0
                val axisZ = if (acrossX) 1.0 else 0.0
                val along = dirX * axisX + dirZ * axisZ
                if (abs(along) < 0.2) {
                    d += SAMPLE
                    continue
                }
                val sign = if (along >= 0.0) 1.0 else -1.0
                // The middle of the opening: half the difference of the room either side, across the passage.
                val shift = if (acrossX) (east - west) * 0.5 else (south - north) * 0.5
                val gapX = if (acrossX) x + shift else x
                val gapZ = if (acrossX) z else z + shift
                val degrees = Math.toDegrees(acos(min(1.0, abs(along))))
                if (degrees < STRAIGHT_DEGREES && abs(shift) < CENTRED) return null
                for (lead in LEADS) {
                    val leadX = gapX - axisX * sign * lead
                    val leadZ = gapZ - axisZ * sign * lead
                    // A lead-in that is where the stretch starts is no lead-in.
                    if (hypot(leadX - ax, leadZ - az) < 0.5) continue
                    if (walk.ok(ax, az, leadX, leadZ) && walk.ok(leadX, leadZ, gapX, gapZ)) return Lead(leadX, leadZ, gapX, gapZ)
                }
                return null
            }
            d += SAMPLE
        }
        return null
    }
}
