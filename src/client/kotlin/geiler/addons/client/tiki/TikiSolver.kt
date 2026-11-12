package geiler.addons.client.tiki

import java.util.ArrayDeque
import java.util.EnumMap

/**
 * Shortest click plan for a tiki: three stacked heads on a 16-slot rotation, solved when all
 * three rotations match. Every reachable state is solvable in at most five clicks.
 */
object TikiSolver {
    const val SLOTS = 16

    const val STEP = 2

    /** [Plan.clicks] when the count is unknowable because the slot cannot be read. */
    const val UNKNOWN_CLICKS = -1

    private val moves = arrayOf(
        intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 1), intArrayOf(1, -1),
        intArrayOf(2, 1), intArrayOf(2, -1),
    )
    private const val VALUES = SLOTS / STEP
    private const val STATES = VALUES * VALUES * VALUES

    enum class Rule {
        /** Recorded behavior: any head matching another head is locked. */
        OBSERVED {
            override fun apply(rotations: IntArray, index: Int, direction: Int): IntArray {
                val next = rotations.copyOf()
                if (!locked(rotations, index)) next[index] = TikiSolver.wrap(next[index] + TikiSolver.STEP * direction)
                val companion = (index + 1) % 3
                if (!locked(rotations, companion)) {
                    next[companion] = TikiSolver.wrap(next[companion] + TikiSolver.STEP * direction)
                }
                return next
            }

            override fun locked(rotations: IntArray, index: Int): Boolean =
                rotations[index] == rotations[(index + 1) % 3] ||
                    rotations[index] == rotations[(index + 2) % 3]
        },

        /** Literal wiki reading, kept as a user-selectable comparison rule. */
        WIKI {
            override fun apply(rotations: IntArray, index: Int, direction: Int): IntArray {
                val next = rotations.copyOf()
                val topPairFrozen = rotations[1] == rotations[2]
                if (!(topPairFrozen && index >= 1)) {
                    next[index] = TikiSolver.wrap(next[index] + TikiSolver.STEP * direction)
                }
                val companion = (index + 1) % 3
                if (!(topPairFrozen && companion >= 1)) {
                    next[companion] = TikiSolver.wrap(next[companion] + TikiSolver.STEP * direction)
                }
                return next
            }

            override fun locked(rotations: IntArray, index: Int): Boolean = index >= 1 && rotations[1] == rotations[2]
        };

        abstract fun apply(rotations: IntArray, index: Int, direction: Int): IntArray

        abstract fun locked(rotations: IntArray, index: Int): Boolean
    }

    /** Keep the JVM record shape and component names exposed by the former Java solver. */
    @JvmRecord
    data class Plan(val index: Int, val direction: Int, val clicks: Int, val remaining: Int) {
        // Kotlin data classes use parentheses here; retain the Java record format used by existing diagnostics.
        override fun toString(): String =
            "Plan[index=$index, direction=$direction, clicks=$clicks, remaining=$remaining]"
    }

    private data class Tables(val forward: Array<IntArray>, val bestMove: ByteArray, val distance: ByteArray)

    private val tables: Map<Rule, Tables> = EnumMap<Rule, Tables>(Rule::class.java).apply {
        Rule.entries.forEach { put(it, build(it)) }
    }

    /** Returns the next run of clicks, or null if solved, unsolvable, or invalid. */
    @JvmStatic
    fun solve(rotations: IntArray?, rule: Rule): Plan? {
        if (!isSolvableState(rotations)) return null
        val stateRotations = rotations!!
        val table = tables.getValue(rule)
        val state = encode(stateRotations)
        val move = table.bestMove[state].toInt()
        if (move < 0) return null

        var clicks = 0
        var cursor = state
        while (table.bestMove[cursor].toInt() == move) {
            clicks++
            cursor = table.forward[cursor][move]
        }
        return Plan(moves[move][0], moves[move][1], clicks, table.distance[state].toInt())
    }

    /** Every identical-click run from the current state through the solution. */
    @JvmStatic
    fun solveSequence(rotations: IntArray?, rule: Rule): List<Plan> {
        val plans = ArrayList<Plan>()
        if (!isSolvableState(rotations)) return plans
        var state = rotations!!.copyOf()
        // Each iteration consumes at least one click; the documented five-click bound is below this guard.
        repeat(SLOTS) {
            val next = solve(state, rule) ?: return plans
            plans.add(next)
            state = applyRun(state, next, rule)
        }
        return plans
    }

    /** Plan while one slot is unreadable; defined only for the observed rule. */
    @JvmStatic
    fun solveWithHidden(rotations: IntArray?, hidden: Int): Plan? {
        if (rotations == null || rotations.size != 3 || hidden !in 0..2) return null
        val companion = (hidden + 1) % 3
        val feeder = (hidden + 2) % 3
        for (index in intArrayOf(companion, feeder)) {
            val rotation = rotations[index]
            if (rotation !in 0 until SLOTS || rotation % STEP != 0) return null
        }

        if (rotations[feeder] == rotations[companion]) {
            return Plan(hidden, 1, UNKNOWN_CLICKS, UNKNOWN_CLICKS)
        }
        val forward = Math.floorMod(rotations[companion] - rotations[feeder], SLOTS) / STEP
        val backward = SLOTS / STEP - forward
        val left = forward <= backward
        val clicks = if (left) forward else backward
        return Plan(feeder, if (left) 1 else -1, clicks, clicks)
    }

    @JvmStatic
    fun applyMove(rotations: IntArray, index: Int, direction: Int, rule: Rule): IntArray =
        rule.apply(rotations, index, direction)

    @JvmStatic
    fun isLocked(rotations: IntArray, index: Int, rule: Rule): Boolean = rule.locked(rotations, index)

    @JvmStatic
    fun isSolved(rotations: IntArray): Boolean = rotations[0] == rotations[1] && rotations[1] == rotations[2]

    /** Tiki rotations must be even and inside the sixteen-position ring. */
    @JvmStatic
    fun isSolvableState(rotations: IntArray?): Boolean = rotations != null && rotations.size == 3 &&
        rotations.all { it in 0 until SLOTS && it % STEP == 0 }

    private fun applyRun(rotations: IntArray, plan: Plan, rule: Rule): IntArray {
        var state = rotations
        repeat(plan.clicks) { state = rule.apply(state, plan.index, plan.direction) }
        return state
    }

    private fun build(rule: Rule): Tables {
        val forward = Array(STATES) { IntArray(moves.size) }
        for (state in 0 until STATES) {
            val rotations = decode(state)
            for (move in moves.indices) {
                forward[state][move] = encode(rule.apply(rotations, moves[move][0], moves[move][1]))
            }
        }

        val reverse = Array(STATES) { ArrayList<IntArray>() }
        for (state in 0 until STATES) {
            for (move in moves.indices) reverse[forward[state][move]].add(intArrayOf(state, move))
        }

        val distance = ByteArray(STATES) { (-1).toByte() }
        val bestMove = ByteArray(STATES) { (-1).toByte() }
        val queue = ArrayDeque<Int>()
        for (value in 0 until VALUES) {
            val solved = value + value * VALUES + value * VALUES * VALUES
            distance[solved] = 0
            queue.addLast(solved)
        }
        while (queue.isNotEmpty()) {
            val target = queue.removeFirst()
            for (edge in reverse[target]) {
                val from = edge[0]
                if (distance[from].toInt() != -1) continue
                distance[from] = (distance[target] + 1).toByte()
                bestMove[from] = edge[1].toByte()
                queue.addLast(from)
            }
        }
        preferSelfMoving(rule, forward, bestMove, distance)
        return Tables(forward, bestMove, distance)
    }

    private fun preferSelfMoving(rule: Rule, forward: Array<IntArray>, bestMove: ByteArray, distance: ByteArray) {
        for (state in 0 until STATES) {
            val current = bestMove[state].toInt()
            if (current < 0) continue
            val rotations = decode(state)
            if (!rule.locked(rotations, moves[current][0])) continue
            for (move in moves.indices) {
                if (rule.locked(rotations, moves[move][0])) continue
                if (distance[forward[state][move]] == (distance[state] - 1).toByte()) {
                    bestMove[state] = move.toByte()
                    break
                }
            }
        }
    }

    private fun encode(rotations: IntArray): Int =
        rotations[0] / STEP + rotations[1] / STEP * VALUES + rotations[2] / STEP * VALUES * VALUES

    private fun decode(state: Int): IntArray = intArrayOf(
        state % VALUES * STEP,
        state / VALUES % VALUES * STEP,
        state / (VALUES * VALUES) * STEP,
    )

    private fun wrap(rotation: Int): Int = Math.floorMod(rotation, SLOTS)
}
