package geiler.addons.client.macros.pathing

import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * The cost of getting to and from a few far-apart places, for every place in a [WalkGraph]: the landmarks of an A* with
 * landmarks, which is a search that is told, at every place, a lower bound on how far it still has to go.
 *
 * The straight line to the goal is the only thing a plain search has to go by, and it knows nothing of walls: in ground
 * with cliffs and dead ends it spends its time on every pocket that lies in the right direction. A landmark knows the real
 * cost. If the landmark L is known to be 80 away from the goal and 50 away from where the search is, the goal cannot be
 * nearer than 30 - going from here to the goal and then on to L is at least as long as the way to L - and the same the
 * other way round, from the cost of getting *to* L. The best of these bounds over all landmarks and both directions is a
 * far better guess than the straight line, and still never too high.
 *
 * The landmarks are chosen to be as far from each other as possible, the first being the middle of the map: places at the
 * edge, in different directions, are the ones that lie behind most routes.
 */
class Landmarks private constructor() {
    private val nodes = IntArray(COUNT)
    private val forward = arrayOfNulls<FloatArray>(COUNT)
    private val backward = arrayOfNulls<FloatArray>(COUNT)
    private var count = 0

    fun count(): Int = count

    /** The cost from landmark `k` to a place. */
    fun forward(k: Int, id: Int): Float = forward[k]!![id]

    /** The cost from a place to landmark `k`. */
    fun backward(k: Int, id: Int): Float = backward[k]!![id]

    /** Works the tables out a little at a time. */
    class Builder(private val graph: WalkGraph) {
        private val result = Landmarks()
        private val reverseFirst: IntArray
        private val reverseTo: IntArray
        private val reverseCost: FloatArray
        private var running: Dijkstra? = null
        private var runningForward = true

        init {
            val n = graph.size()
            val first = graph.firstEdges()
            val to = graph.edgeTargets()
            val cost = graph.edgeCosts()
            // The same map with every move turned round, for the cost of getting to a landmark.
            reverseFirst = IntArray(n + 1)
            for (target in to) reverseFirst[target + 1]++
            for (i in 0 until n) reverseFirst[i + 1] += reverseFirst[i]
            reverseTo = IntArray(to.size)
            reverseCost = FloatArray(to.size)
            val fill = reverseFirst.copyOf(n)
            for (from in 0 until n) {
                for (edge in first[from] until first[from + 1]) {
                    val slot = fill[to[edge]]++
                    reverseTo[slot] = from
                    reverseCost[slot] = cost[edge]
                }
            }
            result.nodes[0] = 0
            begin()
        }

        private fun begin() {
            val k = result.count
            running = if (runningForward) {
                Dijkstra(graph.size(), graph.firstEdges(), graph.edgeTargets(), graph.edgeCosts(), result.nodes[k])
            } else {
                Dijkstra(graph.size(), reverseFirst, reverseTo, reverseCost, result.nodes[k])
            }
        }

        /** Works until the time is up; true once all the landmarks are done. */
        fun step(deadlineNanos: Long): Boolean {
            while (running != null) {
                if (!running!!.step(deadlineNanos)) return false
                val k = result.count
                if (runningForward) {
                    result.forward[k] = running!!.distances()
                    runningForward = false
                    begin()
                    continue
                }
                result.backward[k] = running!!.distances()
                result.count++
                runningForward = true
                if (result.count >= COUNT) {
                    running = null
                    return true
                }
                result.nodes[result.count] = farthestFromAll()
                begin()
            }
            return true
        }

        fun result(): Landmarks = result

        /** The place that is furthest from every landmark so far, among those that can be got to and back from all of them. */
        private fun farthestFromAll(): Int {
            var best = 0
            var bestScore = -1.0f
            for (id in 0 until graph.size()) {
                var score = Float.POSITIVE_INFINITY
                for (k in 0 until result.count) {
                    val there = result.forward[k]!![id]
                    val back = result.backward[k]!![id]
                    if (there.isInfinite() || back.isInfinite()) {
                        score = -1.0f
                        break
                    }
                    score = min(score, there)
                }
                if (score > bestScore) {
                    bestScore = score
                    best = id
                }
            }
            return best
        }
    }

    /** Cheapest cost from one place to all the others, resumable. */
    private class Dijkstra(n: Int, private val first: IntArray, private val to: IntArray, private val cost: FloatArray, source: Int) {
        private val distance = FloatArray(n)
        private var heap = LongArray(1024)
        private var size = 0

        init {
            distance.fill(UNREACHED)
            distance[source] = 0.0f
            push(source, 0.0f)
        }

        fun distances(): FloatArray = distance

        /** True once every place has its cost. */
        fun step(deadlineNanos: Long): Boolean {
            var sinceClock = 0
            while (size > 0) {
                if (++sinceClock >= 256) {
                    if (System.nanoTime() >= deadlineNanos) return false
                    sinceClock = 0
                }
                val top = pop()
                val node = (top and 0xFFFFFFFFL).toInt()
                val at = Float.fromBits((top ushr 32).toInt())
                if (at > distance[node]) continue
                for (edge in first[node] until first[node + 1]) {
                    val next = to[edge]
                    val through = at + cost[edge]
                    if (through < distance[next]) {
                        distance[next] = through
                        push(next, through)
                    }
                }
            }
            return true
        }

        /** Costs are never negative, so the bits of a float order the same way as the number does. */
        private fun push(node: Int, at: Float) {
            val entry = (at.toRawBits().toLong() shl 32) or node.toLong()
            if (size == heap.size) heap = heap.copyOf(size * 2)
            var i = size++
            while (i > 0) {
                val parent = (i - 1) ushr 1
                if (heap[parent] <= entry) break
                heap[i] = heap[parent]
                i = parent
            }
            heap[i] = entry
        }

        private fun pop(): Long {
            val top = heap[0]
            val last = heap[--size]
            if (size > 0) {
                var i = 0
                while (true) {
                    val left = 2 * i + 1
                    if (left >= size) break
                    val right = left + 1
                    val child = if (right < size && heap[right] < heap[left]) right else left
                    if (heap[child] >= last) break
                    heap[i] = heap[child]
                    i = child
                }
                heap[i] = last
            }
            return top
        }
    }

    companion object {
        /** How many places are used. More makes the guide better and the tables bigger. */
        private const val COUNT = 4
        private const val UNREACHED = Float.POSITIVE_INFINITY
    }
}

/**
 * A goal with the landmarks' knowledge added to its own idea of how far it still is.
 *
 * The goal underneath says how far it is by the straight line. The landmarks of the [WalkGraph] know the real cost to and
 * from a few far places, and from those, for every place the search looks at, a lower bound on the cost still to pay (see
 * [Landmarks]); the larger of the two is what the search is told. Which stands the goal is reached from is worked out
 * once, here, from the map - the bound has to hold for the best of them.
 *
 * Where the map does not know a place the straight line is all there is, as it was before.
 */
class AltGoal private constructor(
    private val inner: Goal,
    private val graph: WalkGraph,
    private val marks: Landmarks,
    goalStands: IntArrayList,
) : Goal {
    /** For every landmark, the cheapest cost from it to a stand where the goal is reached, or infinity. */
    private val cheapestFrom = FloatArray(marks.count())

    /** For every landmark, the dearest cost from a stand where the goal is reached to it, or infinity. */
    private val dearestTo = FloatArray(marks.count())

    init {
        for (k in 0 until marks.count()) {
            var cheapest = Float.POSITIVE_INFINITY
            var dearest = 0.0f
            for (i in 0 until goalStands.size) {
                val id = goalStands.getInt(i)
                cheapest = min(cheapest, marks.forward(k, id))
                dearest = max(dearest, marks.backward(k, id))
            }
            cheapestFrom[k] = cheapest
            dearestTo[k] = dearest
        }
    }

    override fun heuristic(stand: Terrain.Stand): Double {
        var best = inner.heuristic(stand)
        val id = graph.idOf(stand)
        if (id < 0) return best
        for (k in cheapestFrom.indices) {
            val fromLandmark = marks.forward(k, id)
            if (!cheapestFrom[k].isInfinite() && !fromLandmark.isInfinite()) {
                best = max(best, (cheapestFrom[k] - fromLandmark).toDouble())
            }
            val toLandmark = marks.backward(k, id)
            if (!dearestTo[k].isInfinite() && !toLandmark.isInfinite()) {
                best = max(best, (toLandmark - dearestTo[k]).toDouble())
            }
        }
        return best
    }

    override fun reached(terrain: Terrain, stand: Terrain.Stand): Boolean = inner.reached(terrain, stand)

    override fun focus(terrain: Terrain): Goal.Focus? = inner.focus(terrain)

    companion object {
        /**
         * The goal told what the map knows, or the goal itself where there is no map that covers it and the start, or no
         * place the goal can be reached from in it.
         */
        @JvmStatic
        fun of(goal: Goal, terrain: Terrain, start: Terrain.Stand, graph: WalkGraph?, now: Long): Goal {
            val marks = graph?.landmarks()
            if (graph == null || marks == null || !graph.usable(now) || !graph.admits(start) || graph.idOf(start) < 0) return goal
            val goalStands = graph.goalStands(terrain, goal)
            if (goalStands == null || goalStands.isEmpty) return goal
            return AltGoal(goal, graph, marks, goalStands)
        }
    }
}
