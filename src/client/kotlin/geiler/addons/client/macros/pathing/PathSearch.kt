package geiler.addons.client.macros.pathing

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A* over the stands of [Terrain], spread across as many ticks as it needs.
 *
 * The slicing is the point. A search for something standing behind a wall has to give up on the whole search radius
 * before it can say so, and doing that inside one tick is a visible freeze. So [step] runs on a wall-clock budget and the
 * caller keeps walking the route it already has while the next one is still being worked out.
 *
 * The heuristic is deliberately weighted above the true remaining cost. A mob that moves every tick makes the perfectly
 * optimal route worthless by the time it is finished; finding a route that is a few percent longer in a fraction of the
 * nodes is the better trade every time.
 */
class PathSearch(
    private val terrain: Terrain,
    private val start: Terrain.Stand,
    private val goal: Goal,
    private val settings: PathingSettings,
    /** How much of the per-tick budget a slice may use, see [SearchBudget]. */
    private val budgetScale: Double = 1.0,
    /** Whether to start with the cheap tier and go on to the thorough ones only when it finds nothing, see [SearchTier]. */
    tiered: Boolean = true,
) {
    enum class Status {
        /** Still searching. */
        RUNNING,

        /** A stand that satisfies the goal was reached. */
        FOUND,

        /** Nothing within the budget satisfies the goal. */
        FAILED,
    }

    /**
     * What the searches of this session have cost so far: how many there were, the stands they looked at, the time they
     * took and the time it took to make routes out of what they found.
     */
    data class Totals(val searches: Long, val expansions: Long, val searchNanos: Long, val buildNanos: Long) {
        /** In a line, for the status command. */
        fun describe(): String = String.format(
            Locale.ROOT, "%d searches, %d stands looked at in %.1f ms (%.2f us each), routes built in %.1f ms",
            searches, expansions, searchNanos / 1.0E6, if (expansions == 0L) 0.0 else searchNanos / 1.0E3 / expansions, buildNanos / 1.0E6,
        )
    }

    private val nodes = Long2ObjectOpenHashMap<PathNode>()
    private var open = NodeHeap()
    private val startHeuristic: Double
    private val plainWeight: Double
    private val blocks: Double
    private var weight = 1.0
    private var tier = if (tiered) SearchTier.GREEDY else SearchTier.STANDARD
    private var radiusLimit = 0.0
    private var tierExpansions = 0
    private var best: PathNode
    private var reached: PathNode? = null
    private var status = Status.RUNNING
    private var expansions = 0

    init {
        val first = PathNode(start)
        first.h = goal.heuristic(start)
        startHeuristic = first.h
        blocks = first.h / terrain.heuristicCostPerBlock()
        val far = max(0.0, min(1.0, (blocks - WEIGHT_NEAR_BLOCKS) / (WEIGHT_FAR_BLOCKS - WEIGHT_NEAR_BLOCKS)))
        plainWeight = WEIGHT_NEAR + (WEIGHT_FAR - WEIGHT_NEAR) * far
        best = first
        beginTier(tier, first)
        totalSearches++
    }

    /** Sets the search up from the start again for this tier; what the failed ones learnt is not kept, but the closest stand they got to is. */
    private fun beginTier(next: SearchTier, first: PathNode = PathNode(start).also { it.h = startHeuristic }) {
        tier = next
        nodes.clear()
        open = NodeHeap()
        weight = next.weight(plainWeight)
        radiusLimit = next.radius(blocks, (settings.searchRadius() + RADIUS_SLACK).toDouble())
        tierExpansions = 0
        first.f = weight * first.h
        nodes.put(start.key(), first)
        open.insert(first)
    }

    /** The tier that ran last: the one that found the way, or the last that was tried. */
    fun tier(): SearchTier = tier

    /** Goes on to the next tier when there is one; true when it has begun. */
    private fun escalate(capped: Boolean): Boolean {
        val next = SearchTier.next(tier, capped) ?: return false
        beginTier(next)
        return true
    }

    fun status(): Status = status

    /** How many stands the search has looked at so far; what a failed one says about how far it got. */
    fun expansions(): Int = expansions

    fun goal(): Goal = goal

    fun terrain(): Terrain = terrain

    /** Runs for one tick worth of budget. Call again while this returns [Status.RUNNING]. */
    fun step(): Status {
        if (status != Status.RUNNING) return status
        val began = System.nanoTime()
        val before = expansions
        try {
            return work()
        } finally {
            totalNanos += System.nanoTime() - began
            totalExpansions += (expansions - before).toLong()
        }
    }

    private fun work(): Status {
        val deadline = System.nanoTime() + (max(0.25, settings.searchMillisPerTick() * budgetScale) * 1.0E6).toLong()
        var untilClockCheck = EXPANSIONS_PER_CLOCK_CHECK
        while (true) {
            if (open.isEmpty()) {
                // Everything within this tier's bounds has been seen. A cheap tier's bounds were a guess, so it goes on.
                if (escalate(false)) continue
                status = Status.FAILED
                return status
            }
            if (--untilClockCheck <= 0) {
                if (System.nanoTime() >= deadline) return Status.RUNNING
                untilClockCheck = EXPANSIONS_PER_CLOCK_CHECK
            }

            val current = open.pop()
            current.closed = true
            // Tested on the way out rather than on the way in, so the first stand that satisfies the goal
            // is also the cheapest one that does.
            if (goal.reached(terrain, current.stand)) {
                reached = current
                status = Status.FOUND
                return status
            }
            if (++tierExpansions > tier.maxExpansions) {
                if (escalate(true)) continue
                status = Status.FAILED
                return status
            }
            expansions++

            terrain.expand(current.stand) { to, cost, move -> offer(current, to, cost, move) }
        }
    }

    private fun offer(from: PathNode, to: Terrain.Stand, cost: Double, move: MoveType) {
        if (outsideBounds(to)) return

        val g = from.g + cost * terrain.costFactor(to.x, to.y, to.z) + turnCost(from, to)
        val key = to.key()
        var node = nodes.get(key)
        if (node == null) {
            node = PathNode(to)
            node.h = goal.heuristic(to)
            nodes.put(key, node)
            link(node, from, g, move)
            open.insert(node)
            trackBest(node)
            return
        }

        if (node.closed || g >= node.g - 1.0E-6) return
        link(node, from, g, move)
        if (node.heapIndex >= 0) open.decreased(node) else open.insert(node)
    }

    /**
     * What it costs to change heading at this stand, so that the route runs straight where it can. Measured against how
     * the stand was entered, which for a stand that can be reached in more than one way is only the way found first.
     */
    private fun turnCost(from: PathNode, to: Terrain.Stand): Double {
        val straightness = settings.straightness()
        val parent = from.parent
        if (straightness <= 0.0 || parent == null) return 0.0
        val inX = from.stand.x - parent.stand.x
        val inZ = from.stand.z - parent.stand.z
        val outX = to.x - from.stand.x
        val outZ = to.z - from.stand.z
        if ((inX == 0 && inZ == 0) || (outX == 0 && outZ == 0)) return 0.0
        // Both are steps of the grid, so the angle between them is a whole number of eighths of a turn.
        val turn = abs(HEADING[(inX + 1) * 3 + inZ + 1] - HEADING[(outX + 1) * 3 + outZ + 1])
        return TURN_PENALTY * straightness * (if (turn > 4) 8 - turn else turn)
    }

    private fun link(node: PathNode, from: PathNode, g: Double, move: MoveType) {
        node.parent = from
        node.move = move
        node.g = g
        node.f = g + weight * node.h
    }

    /** Remembers the stand that got closest, which is what a failed search has to offer instead. */
    private fun trackBest(node: PathNode) {
        if (node.h < best.h || (node.h == best.h && node.g < best.g)) best = node
    }

    private fun outsideBounds(stand: Terrain.Stand): Boolean {
        val dx = stand.centerX() - start.centerX()
        val dz = stand.centerZ() - start.centerZ()
        val radius = radiusLimit
        return dx * dx + dz * dz > radius * radius || abs(stand.floorY - start.floorY) > MAX_VERTICAL
    }

    /**
     * The route this search produced, or null when it has nothing worth walking. A failed search still
     * hands back the way to the stand that got closest, as long as that is a real improvement - walking
     * it often puts the player somewhere the next search can finish from.
     */
    fun path(): Path? {
        val reachedNode = reached
        val end = reachedNode ?: best
        if (end === nodes.get(start.key()) && reachedNode == null) return null
        if (reachedNode == null && best.h > startHeuristic - MIN_PARTIAL_GAIN * terrain.heuristicCostPerBlock()) return null

        val chain = ArrayList<PathNode>()
        var node: PathNode? = end
        while (node != null) {
            chain.add(node)
            node = node.parent
        }
        chain.reverse()
        val began = System.nanoTime()
        try {
            return Path.build(terrain, chain, settings.pathRandomness(), settings.pathSeed(), settings.waypointSpacing(), settings.offCentre())
        } finally {
            totalBuildNanos += System.nanoTime() - began
        }
    }

    companion object {
        /**
         * How much the estimate of what is left counts against what has been spent. Near the goal the route is worth being
         * exact about; far from it the search is better off going at the goal and leaving the last few percent of the length
         * to the straightening of the route afterwards - a plain 1.25 explores about twice as much of a long way as 2 does,
         * to get a route a couple of percent shorter.
         */
        private const val WEIGHT_NEAR = 1.1
        private const val WEIGHT_FAR = 1.7
        private const val WEIGHT_NEAR_BLOCKS = 30.0
        private const val WEIGHT_FAR_BLOCKS = 150.0

        /** What a change of heading costs, per 45 degrees, at full straightness. Moves cost about 3.6 a block. */
        private const val TURN_PENALTY = 1.2

        /** Checking the clock costs a syscall, so only do it every so many expansions. */
        private const val EXPANSIONS_PER_CLOCK_CHECK = 64

        /** A partial route is only worth walking if it actually gets closer, in blocks. */
        private const val MIN_PARTIAL_GAIN = 3.0

        /** How far past the search radius a stand may still be considered. */
        const val RADIUS_SLACK = 8
        const val MAX_VERTICAL = 40

        /** The heading of a step of the grid in eighths of a turn, by (dx + 1) * 3 + dz + 1. */
        private val HEADING = intArrayOf(5, 4, 3, 6, 0, 2, 7, 0, 1)

        private var totalSearches = 0L
        private var totalExpansions = 0L
        private var totalNanos = 0L
        private var totalBuildNanos = 0L

        @JvmStatic
        fun totals(): Totals = Totals(totalSearches, totalExpansions, totalNanos, totalBuildNanos)
    }
}
