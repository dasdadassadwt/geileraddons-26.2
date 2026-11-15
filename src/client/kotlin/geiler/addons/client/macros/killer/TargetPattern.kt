package geiler.addons.client.macros.killer

import java.util.random.RandomGenerator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** How the Area Killer orders the mobs in range: each pattern is a different weighing of the walk and the turning. */
enum class TargetPattern(val label: String, val description: String) {
    NEAREST("Nearest", "The closest mob, then the one closest to that, and so on. The way the macro always worked."),
    LEAST_TURN("Least turn", "A short tour that turns the view as little as it can, so the camera keeps looking roughly forward."),
    SWEEP("Sweep", "A tour that keeps turning the same way, so a row or an arc of mobs is worked from one end to the other."),
    CLUSTER("Cluster", "Starts in the biggest crowd and goes on from there, so a crowd is worked down from its middle."),
    NEAR_RANDOM("Near random", "One of the three best short tours, drawn at random, so the order is not the same every time."),
    HUMAN("Human", "What is cheapest to reach on foot, ahead of the way it is going and in a crowd, and not switched for a small gain."),
}

/**
 * Orders the nearest mobs as a short tour, free of game state so checks can run it. A tour is scored by the walk it takes
 * plus the turning it asks for, which is one smooth cost instead of the hard slices and counts a sort would flip on at a
 * boundary. Angles are Minecraft yaw: 0 south, 90 west.
 *
 * Only the nearest [POOL] mobs are tried, and the first [DEPTH] visits of them: at most 1680 tours are scored. The rest
 * follow, nearest first. A plan that is handed back in is kept while the mobs are the same and no new tour is clearly
 * cheaper, so a mob that shifts a little does not change the order.
 */
object TargetPlanner {
    /** A mob: where it stands, with [y] its height. Height counts in the walk: a mob up a wall is further than it looks from above. */
    class Mob<T> @JvmOverloads constructor(
        @JvmField val item: T,
        @JvmField val x: Double,
        @JvmField val z: Double,
        @JvmField val y: Double = 0.0,
        /** The walk to it, in blocks, from the map of the ground; NaN when there is no map to say. A walk shorter than the line counts as the line. */
        @JvmField val cost: Double = Double.NaN,
        /** The stretch of ground it stands on, see [SegmentRules]; -1 when that is not known. */
        @JvmField val segment: Int = -1,
    )

    class Plan<T>(
        @JvmField val order: List<Mob<T>>,
        /** What the first [DEPTH] visits cost: blocks walked plus the weighed turning. */
        @JvmField val cost: Double,
        /** Degrees of turning over those visits, from the view. */
        @JvmField val turnDegrees: Double,
        /** The plan handed in was kept. */
        @JvmField val kept: Boolean,
        /** How many tours were scored. */
        @JvmField val evaluations: Int,
    )

    /** A mob this close counts as inside the view cone whichever way it lies: it is next to the player, not behind them. */
    const val NEAR_BLOCKS = 5.0

    /** A mob this many blocks above or below the walk is no further than the flat ground; every block more counts [VERTICAL_WEIGHT] blocks. */
    private const val FREE_RISE = 1.0
    private const val VERTICAL_WEIGHT = 2.5

    /** The extra walk, in blocks, of getting from a height [fromY] to [toY]: the climb and the drop, which a flat distance leaves out. */
    @JvmStatic
    fun heightCost(fromY: Double, toY: Double): Double = max(0.0, abs(toY - fromY) - FREE_RISE) * VERTICAL_WEIGHT

    const val POOL = 8
    const val DEPTH = 4

    /** A new tour has to be this much cheaper than the kept one to replace it. */
    const val REPLAN_GAIN = 0.4

    /** A kept plan needs at least this many of its mobs left to be kept (fewer mobs than that: all of them). */
    private const val KEEP_MIN = 2

    /**
     * What it costs, in blocks of walk, to leave the stretch of ground the player is on while it still holds a mob: a stretch is cleared before
     * the next is gone to. Nothing when there is no other mob on it, or when the segments are not known.
     */
    const val SEGMENT_LEAVE = 40.0

    /** Whether going for [mob] leaves the segment [now] while another mob of it (not [mob]) is still to be visited. */
    private fun <T> leaves(mob: Mob<T>, now: Int, others: Iterable<Mob<T>>): Boolean =
        now >= 0 && mob.segment >= 0 && mob.segment != now && others.any { it !== mob && it.segment == now }

    /** Neighbours within this many blocks count toward a mob's crowd. */
    private const val CROWD_RADIUS = 4.0

    /** A mob with a neighbour this close (up to [CROWD_CAP] of them) counts [CROWD_BONUS] blocks nearer each: one trip, more kills. */
    private const val CROWD_NEAR = 3.0
    private const val CROWD_BONUS = 1.0
    private const val CROWD_CAP = 3

    /** How much longer the walk to a mob is than the line to it (with the climb), 0 when there is no cost or it is no longer. */
    private fun <T> detour(mob: Mob<T>, px: Double, pz: Double, py: Double): Double {
        if (mob.cost.isNaN() || mob.cost.isInfinite()) return 0.0
        return max(0.0, mob.cost - (hypot(mob.x - px, mob.z - pz) + heightCost(py, mob.y)))
    }

    /** For each mob, how many blocks nearer its neighbours make it, in step with [mobs]. */
    private fun <T> neighbourBonus(mobs: List<Mob<T>>): DoubleArray = DoubleArray(mobs.size) { i ->
        min(CROWD_CAP, mobs.count { it !== mobs[i] && hypot(it.x - mobs[i].x, it.z - mobs[i].z) <= CROWD_NEAR }) * CROWD_BONUS
    }

    /** The [count] mobs that cost least to go for from ([px], [pz]): the walk and the climb less what the neighbours of each are worth. */
    @JvmStatic
    @JvmOverloads
    fun <T> nearestFirst(mobs: List<Mob<T>>, px: Double, pz: Double, py: Double, count: Int, segmentNow: Int = -1): List<Mob<T>> {
        val bonus = neighbourBonus(mobs)
        return mobs.indices.sortedBy {
            hypot(mobs[it].x - px, mobs[it].z - pz) + heightCost(py, mobs[it].y) + detour(mobs[it], px, pz, py) - bonus[it] +
                (if (leaves(mobs[it], segmentNow, mobs)) SEGMENT_LEAVE else 0.0)
        }.take(count).map { mobs[it] }
    }

    /** Below this many degrees a turn is not a change of direction. */
    private const val STRAIGHT_DEGREES = 15.0

    private class Weights(val perDegree: Double, val reversal: Double, val crowd: Double)

    private fun weights(pattern: TargetPattern) = when (pattern) {
        TargetPattern.NEAREST -> Weights(0.0, 0.0, 0.0)
        TargetPattern.LEAST_TURN -> Weights(0.4, 0.0, 0.0)
        TargetPattern.SWEEP -> Weights(0.05, 6.0, 0.0)
        TargetPattern.CLUSTER -> Weights(0.04, 0.0, 8.0)
        TargetPattern.NEAR_RANDOM -> Weights(0.05, 0.0, 0.0)
        TargetPattern.HUMAN -> Weights(0.05, 0.0, 4.0)
    }

    private class Tour(val visits: IntArray, val cost: Double, val turn: Double)

    /**
     * The mobs best first by [pattern], seen from ([px], [pz]) looking along [yaw]. [previous] is the order of the last plan.
     *
     * [coneDegrees] is the view cone, 0 for none: while any mob is inside it - that many degrees either side of [yaw] - the
     * tour is made over those alone, and the mobs behind the view follow after, nearest first. A person goes for what they can
     * see in front of them; one who turns round for the nearest mob behind them every time looks like a script.
     */
    @JvmStatic
    @JvmOverloads
    fun <T> plan(
        pattern: TargetPattern,
        mobs: List<Mob<T>>,
        px: Double,
        pz: Double,
        yaw: Double,
        random: RandomGenerator,
        previous: List<T>?,
        coneDegrees: Double = 0.0,
        py: Double = 0.0,
        segmentNow: Int = -1,
    ): Plan<T> {
        if (mobs.size < 2) return Plan(mobs.toList(), 0.0, 0.0, false, 0)
        val front = if (coneDegrees > 0.0) {
            mobs.filter { hypot(it.x - px, it.z - pz) <= NEAR_BLOCKS || LookFocus.offDegrees(yaw, px, pz, it.x, it.z) <= coneDegrees }
        } else {
            mobs
        }
        val gated = front.isNotEmpty() && front.size < mobs.size
        val usable = if (gated) front else mobs
        if (usable.size < 2) {
            // One mob in front: it is first, whatever its distance; the rest follow nearest first from it.
            val ordered = ArrayList<Mob<T>>(mobs.size)
            ordered.addAll(usable)
            ordered.addAll(chain(mobs.filter { m -> ordered.none { it === m } }, usable.last().x, usable.last().z, usable.last().y))
            return Plan(ordered, 0.0, 0.0, false, 0)
        }
        val segment = if (segmentNow >= 0) segmentNow else usable.minByOrNull { hypot(it.x - px, it.z - pz) }?.segment ?: -1
        val bonuses = neighbourBonus(usable)
        // What the walk costs over the line, for the first leg: a mob behind a wall is as far as the way round, not as far as it looks.
        val extras = DoubleArray(usable.size) { i -> detour(usable[i], px, pz, py) }
        val byDistance = usable.indices.sortedBy {
            hypot(usable[it].x - px, usable[it].z - pz) + heightCost(py, usable[it].y) + extras[it] - bonuses[it] +
                (if (leaves(usable[it], segment, usable)) SEGMENT_LEAVE else 0.0)
        }.map { usable[it] }
        val pool = byDistance.subList(0, min(POOL, byDistance.size))
        val poolBonus = DoubleArray(pool.size) { i -> bonuses[usable.indexOfFirst { it === pool[i] }] }
        val poolExtra = DoubleArray(pool.size) { i -> extras[usable.indexOfFirst { it === pool[i] }] }
        val w = weights(pattern)
        val crowd = DoubleArray(pool.size) { i ->
            val weighted = if (w.crowd == 0.0) 0.0 else pool.count { it !== pool[i] && hypot(it.x - pool[i].x, it.z - pool[i].z) <= CROWD_RADIUS } * w.crowd
            weighted - poolExtra[i]
        }
        val depth = min(DEPTH, pool.size)

        var evaluations = 0
        val best = ArrayList<Tour>()
        val keep = if (pattern == TargetPattern.NEAR_RANDOM) 3 else 1

        if (pattern == TargetPattern.NEAREST) {
            // A chain, not a search: always the mob closest to the last one.
            val left = pool.indices.toMutableList()
            val chain = IntArray(depth)
            var x = px
            var z = pz
            var y = py
            for (step in 0 until depth) {
                val next = left.minByOrNull {
                    hypot(pool[it].x - x, pool[it].z - z) + heightCost(y, pool[it].y) - poolBonus[it] + (if (step == 0) poolExtra[it] else 0.0)
                }!!
                left.remove(next)
                chain[step] = next
                x = pool[next].x
                z = pool[next].z
                y = pool[next].y
            }
            best.add(score(pool, chain, px, pz, py, yaw, w, crowd, segment))
            evaluations = 1
        } else {
            val picked = IntArray(depth)
            val used = BooleanArray(pool.size)
            fun search(step: Int, x: Double, z: Double, y: Double, heading: Double, lastSign: Double, cost: Double, turn: Double, at: Int) {
                if (step == depth) {
                    evaluations++
                    val tour = Tour(picked.copyOf(), cost, turn)
                    var at = best.size
                    while (at > 0 && best[at - 1].cost > tour.cost) at--
                    if (at < keep) {
                        best.add(at, tour)
                        if (best.size > keep) best.removeAt(best.size - 1)
                    }
                    return
                }
                for (i in pool.indices) {
                    if (used[i]) continue
                    val leg = legOf(pool[i], x, z, y, heading, lastSign, w)
                    var next = cost + leg.cost
                    if (step == 0) next -= crowd[i]
                    // The rest of the mobs of the segment still unvisited: those not yet used, in the pool.
                    if (at >= 0 && pool[i].segment >= 0 && pool[i].segment != at &&
                        pool.indices.any { !used[it] && it != i && pool[it].segment == at }
                    ) {
                        next += SEGMENT_LEAVE
                    }
                    used[i] = true
                    picked[step] = i
                    search(step + 1, pool[i].x, pool[i].z, pool[i].y, leg.heading, leg.sign, next, turn + abs(leg.turn), if (pool[i].segment >= 0) pool[i].segment else at)
                    used[i] = false
                }
            }
            search(0, px, pz, py, yaw, 0.0, 0.0, 0.0, segment)
        }

        var chosen = if (best.size > 1) best[random.nextInt(best.size)] else best[0]
        var kept = false
        // A mob that died or left is taken out of the kept plan, which goes on with the rest; the plan is not made again for it.
        val before = previous?.filter { item -> mobs.any { it.item == item } }
        // A kept plan whose first mob is behind the view is dropped while there is one in front to go for.
        val startsInFront = before == null || !gated || before.isEmpty() || usable.any { it.item == before[0] }
        if (before != null && startsInFront && before.size >= depth && before.size >= min(mobs.size, KEEP_MIN)) {
            val old = before.map { item -> mobs.first { it.item == item } }
            val oldIndices = oldTour(pool, old, depth)
            if (oldIndices != null) {
                val scored = score(pool, oldIndices, px, pz, py, yaw, w, crowd, segment)
                if (chosen.cost >= scored.cost - REPLAN_GAIN * abs(scored.cost)) {
                    chosen = scored
                    kept = true
                }
            }
        }

        val ordered = ArrayList<Mob<T>>(mobs.size)
        for (index in chosen.visits) ordered.add(pool[index])
        // The rest, nearest first from where the tour ends.
        ordered.addAll(chain(mobs.filter { m -> ordered.none { it === m } }, ordered.last().x, ordered.last().z, ordered.last().y))
        return Plan(ordered, chosen.cost, chosen.turn, kept, evaluations)
    }

    /** [rest] in a chain of the nearest next, from ([x], [z]). */
    private fun <T> chain(rest: List<Mob<T>>, startX: Double, startZ: Double, startY: Double): List<Mob<T>> {
        val out = ArrayList<Mob<T>>(rest.size)
        val left = rest.toMutableList()
        var x = startX
        var z = startZ
        var y = startY
        while (left.isNotEmpty()) {
            val atX = x
            val atZ = z
            val atY = y
            val next = left.minByOrNull { hypot(it.x - atX, it.z - atZ) + heightCost(atY, it.y) }!!
            left.remove(next)
            out.add(next)
            x = next.x
            z = next.z
            y = next.y
        }
        return out
    }

    /** The kept order as pool indices, when its first visits are all in the pool. */
    private fun <T> oldTour(pool: List<Mob<T>>, old: List<Mob<T>>, depth: Int): IntArray? {
        val indices = IntArray(depth)
        for (step in 0 until depth) {
            val index = pool.indexOfFirst { it === old[step] }
            if (index < 0) return null
            indices[step] = index
        }
        return indices
    }

    private fun <T> score(
        pool: List<Mob<T>>, tour: IntArray, px: Double, pz: Double, py: Double, yaw: Double, w: Weights, crowd: DoubleArray, segmentNow: Int,
    ): Tour {
        val used = BooleanArray(pool.size)
        var at = segmentNow
        var x = px
        var z = pz
        var y = py
        var heading = yaw
        var sign = 0.0
        var cost = 0.0
        var turn = 0.0
        for ((step, i) in tour.withIndex()) {
            val leg = legOf(pool[i], x, z, y, heading, sign, w)
            cost += leg.cost
            if (step == 0) cost -= crowd[i]
            if (at >= 0 && pool[i].segment >= 0 && pool[i].segment != at && pool.indices.any { !used[it] && it != i && pool[it].segment == at }) {
                cost += SEGMENT_LEAVE
            }
            used[i] = true
            if (pool[i].segment >= 0) at = pool[i].segment
            turn += abs(leg.turn)
            heading = leg.heading
            sign = leg.sign
            x = pool[i].x
            z = pool[i].z
            y = pool[i].y
        }
        return Tour(tour, cost, turn)
    }

    private class Leg(val cost: Double, val turn: Double, val heading: Double, val sign: Double)

    /** The walk to a mob and the turn it asks for from the current heading, with the penalty for turning back the other way. */
    private fun legOf(mob: Mob<*>, x: Double, z: Double, y: Double, heading: Double, lastSign: Double, w: Weights): Leg {
        val dx = mob.x - x
        val dz = mob.z - z
        val distance = hypot(dx, dz)
        if (distance < 1.0E-6) return Leg(0.0, 0.0, heading, lastSign)
        val to = Math.toDegrees(atan2(-dx, dz))
        val turn = wrap(to - heading)
        var cost = distance + heightCost(y, mob.y) + w.perDegree * abs(turn)
        var sign = lastSign
        if (abs(turn) > STRAIGHT_DEGREES) {
            val now = sign(turn)
            if (lastSign != 0.0 && now != lastSign) cost += w.reversal
            sign = now
        }
        return Leg(cost, turn, to, sign)
    }

    private fun wrap(degrees: Double): Double {
        var d = (degrees + 180.0) % 360.0
        if (d < 0.0) d += 360.0
        return d - 180.0
    }
}
