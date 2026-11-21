package geiler.addons.client.dungeon.helpers

import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards
import java.util.ArrayList
import java.util.ArrayDeque
import java.util.Arrays
import java.util.function.IntPredicate

/** Water Board planners behind the stable Java facade. */
object DungeonWaterBoardSolver {
    /** Breadth-first search for the shortest lever sequence reaching the exact observed state. */
    @JvmStatic
    @JvmSuppressWildcards
    fun standard(
        initialState: Int,
        targetState: Int,
        valveEffects: List<Int?>?,
        stateBits: Int,
    ): List<Int> {
        if (stateBits < 0 || stateBits > 20 || valveEffects == null || valveEffects.size > 20)
            return java.util.List.of()
        val stateMask = if (stateBits == 0) 0 else (1 shl stateBits) - 1
        val initial = initialState and stateMask
        val target = targetState and stateMask
        if (initial == target) return java.util.List.of()
        val previousState = IntArray(1 shl stateBits)
        val previousLever = IntArray(1 shl stateBits)
        Arrays.fill(previousState, -1)
        Arrays.fill(previousLever, -1)
        val queue = ArrayDeque<Int>()
        queue.add(initial)
        previousState[initial] = initial
        while (!queue.isEmpty()) {
            val state = queue.removeFirst()
            for (lever in valveEffects.indices) {
                val next = state xor (valveEffects[lever]!! and stateMask)
                if (previousState[next] != -1) continue
                previousState[next] = state
                previousLever[next] = lever
                if (next == target) return unwindLevers(previousState, previousLever, initial, target)
                queue.addLast(next)
            }
        }
        return java.util.List.of()
    }

    @JvmStatic
    @JvmSuppressWildcards
    fun optimized(
        initialState: Int,
        valveEffects: List<Int?>?,
        stateBits: Int,
        reachesDrain: IntPredicate?,
    ): List<Int> {
        if (stateBits < 0 || stateBits > 20 || valveEffects == null || valveEffects.size > 20 || reachesDrain == null)
            return java.util.List.of()
        val limit = 1 shl valveEffects.size
        val stateMask = if (stateBits == 0) 0 else (1 shl stateBits) - 1
        var bestSubset = -1
        var bestClicks = Int.MAX_VALUE
        var state = initialState and stateMask
        var previousGray = 0
        for (iteration in 0 until limit) {
            val subset = iteration xor (iteration ushr 1)
            if (iteration > 0) {
                val changed = subset xor previousGray
                val valve = Integer.numberOfTrailingZeros(changed)
                state = state xor (valveEffects[valve]!! and stateMask)
            }
            previousGray = subset
            val clicks = Integer.bitCount(subset)
            if (clicks > bestClicks) continue
            if (!reachesDrain.test(state)) continue
            if (clicks < bestClicks || bestSubset < 0 || subset < bestSubset) {
                bestSubset = subset
                bestClicks = clicks
            }
        }
        if (bestSubset < 0) return java.util.List.of()
        val clicks = ArrayList<Int>(bestClicks)
        for (valve in valveEffects.indices) if ((bestSubset and (1 shl valve)) != 0) clicks.add(valve)
        return java.util.List.copyOf(clicks)
    }

    private fun unwindLevers(
        previousState: IntArray,
        previousLever: IntArray,
        initial: Int,
        goal: Int,
    ): List<Int> {
        val reverse = ArrayList<Int>()
        var state = goal
        while (state != initial) {
            if (state < 0 || previousLever[state] < 0) return java.util.List.of()
            reverse.add(previousLever[state])
            state = previousState[state]
        }
        reverse.reverse()
        return java.util.List.copyOf(reverse)
    }
}
