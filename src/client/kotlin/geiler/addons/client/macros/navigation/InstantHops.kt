package geiler.addons.client.macros.navigation

import geiler.addons.client.macros.killer.TargetPlanner
import geiler.addons.client.macros.sim.EtherwarpMath
import geiler.addons.client.macros.sim.InstantTransmission
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Instant Transmission hops toward a place the feet cannot walk to, as plain arithmetic over a world probe: the item carries the
 * player a fixed length along the view when the way is clear (see [InstantTransmission]), so the hop is chosen by trying directions -
 * a fan round the way to the goal, steeply up as well as level - and taking the one whose landing leaves the player nearest the goal.
 * The pit with a platform four blocks up is the case: looked at steeply upward, the hop puts the player over the platform and the
 * fall puts them on it. A walk then goes on from there. Free of game state so checks can run it.
 */
object InstantHops {
    /** A hop that has been checked: the look, the direction as a unit vector, where it ends and the ground it falls to. */
    class Hop(
        @JvmField val yaw: Double,
        @JvmField val pitch: Double,
        @JvmField val dirX: Double,
        @JvmField val dirY: Double,
        @JvmField val dirZ: Double,
        @JvmField val landX: Double,
        @JvmField val landY: Double,
        @JvmField val landZ: Double,
        /** The height of the feet once the fall is over. */
        @JvmField val standY: Double,
        @JvmField val fall: Double,
        @JvmField val length: Double,
        @JvmField val score: Double,
    )

    /** Whether a hop that has the best score so far is to be taken at all: whether its landing is on a way to the goal, say. */
    fun interface Accept {
        fun of(hop: Hop): Boolean
    }

    /** Whether the macro may stand in a feet cell: the region of the run. */
    fun interface Allowed {
        fun at(x: Int, y: Int, z: Int): Boolean
    }

    /** Yaws tried either side of the way to the goal, and pitches (negative is up), in degrees. */
    private val YAW_OFFSETS = doubleArrayOf(0.0, -10.0, 10.0, -20.0, 20.0, -35.0, 35.0, -50.0, 50.0, -65.0, 65.0)
    private val PITCHES = doubleArrayOf(-80.0, -72.0, -64.0, -56.0, -48.0, -40.0, -32.0, -24.0, -16.0, -8.0, 0.0, 8.0, 16.0, 24.0, 32.0, 40.0)

    /** The way is walked in steps of this many blocks, coarser than the item's own: this is a plan, the item decides. */
    private const val PLAN_STEP = 0.25

    /** A hop that carries the player less than this far is not worth the click. */
    private const val MIN_HOP = 2.5

    /** A goal nearer than this share of the range is walked to: a hop of the item's length would overshoot it. */
    private const val MIN_GOAL_SHARE = 0.75

    /** The landing has to leave the player this many blocks nearer the goal, height counted, than they are. */
    const val MIN_GAIN = 2.0

    /** A block of fall costs this many blocks of nearness: a hop that lands on the ground is preferred to one that lands in a drop. */
    private const val FALL_WEIGHT = 0.3

    /**
     * The hop of [range] blocks from the feet at ([fx], [fy], [fz]) that leaves the player nearest the goal ([gx], [gy], [gz]), or null
     * when none gets them at least [MIN_GAIN] nearer. A hop is only taken when the item will carry the full length, the landing is
     * somewhere [allowed] says, and there is ground within [maxFall] below it.
     */
    @JvmStatic
    @JvmOverloads
    fun plan(
        probe: EtherwarpMath.Probe, fx: Double, fy: Double, fz: Double, gx: Double, gy: Double, gz: Double, range: Double, maxFall: Double,
        allowed: Allowed, accept: Accept = Accept { true },
    ): Hop? {
        val bearing = Math.toDegrees(atan2(-(gx - fx), gz - fz))
        val current = hypot(gx - fx, gz - fz) + TargetPlanner.heightCost(fy, gy)
        if (hypot(gx - fx, gz - fz) < range * MIN_GOAL_SHARE) return null
        val found = ArrayList<Hop>()
        for (offset in YAW_OFFSETS) {
            for (pitch in PITCHES) {
                val yaw = bearing + offset
                val yawRad = Math.toRadians(yaw)
                val pitchRad = Math.toRadians(pitch)
                val dx = -sin(yawRad) * cos(pitchRad)
                val dy = -sin(pitchRad)
                val dz = cos(yawRad) * cos(pitchRad)
                val end = InstantTransmission.trace(probe, fx, fy, fz, dx, dy, dz, range, PLAN_STEP)
                if (!end.moved || end.distance < MIN_HOP) continue
                val stand = groundBelow(probe, end.x, end.y, end.z, maxFall) ?: continue
                if (!allowed.at(floor(end.x).toInt(), stand, floor(end.z).toInt())) continue
                val fall = end.y - stand
                val score = hypot(gx - end.x, gz - end.z) + TargetPlanner.heightCost(stand.toDouble(), gy) + FALL_WEIGHT * fall
                if (score <= current - MIN_GAIN) found.add(Hop(yaw, pitch, dx, dy, dz, end.x, end.y, end.z, stand.toDouble(), fall, end.distance, score))
            }
        }
        // Best first; the first that is accepted is the hop. Few are looked at: accepting may cost a search.
        found.sortBy { it.score }
        for (i in 0 until minOf(found.size, ACCEPT_TRIES)) if (accept.of(found[i])) return found[i]
        return null
    }

    private const val ACCEPT_TRIES = 6

    /** The feet cell of the first ground below the landing within [maxFall]: solid under it and two free cells in it, or null. */
    private fun groundBelow(probe: EtherwarpMath.Probe, x: Double, y: Double, z: Double, maxFall: Double): Int? {
        val cx = floor(x).toInt()
        val cz = floor(z).toInt()
        val top = floor(y + 1.0E-6).toInt()
        var cy = top
        while (cy >= top - maxFall.toInt() - 1) {
            val under = probe.flags(cx, cy - 1, cz) and EtherwarpMath.OBSTRUCTS != 0
            val free = probe.flags(cx, cy, cz) and EtherwarpMath.OBSTRUCTS == 0 && probe.flags(cx, cy + 1, cz) and EtherwarpMath.OBSTRUCTS == 0
            if (under && free) return if (y - cy <= maxFall + 1.0E-6) cy else null
            if (probe.flags(cx, cy, cz) and EtherwarpMath.OBSTRUCTS != 0) return null
            cy--
        }
        return null
    }
}
