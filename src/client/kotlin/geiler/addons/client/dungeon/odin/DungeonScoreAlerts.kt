package geiler.addons.client.dungeon.odin

/** One-shot score threshold crossings for a single Catacombs run. */
class DungeonScoreAlerts {
    private var runHighWater = -1
    private val firedThresholds = HashSet<Int>()

    /** Compatibility overload for the original 270/300 defaults. */
    fun update(score: Int): List<Int> = update(score, 270, 300, true)

    /**
     * Tracks the run high-water mark even while notices are disabled. When enabled, a newly observed
     * score already above a threshold fires immediately; each threshold value can fire only once until reset.
     */
    fun update(score: Int, firstThreshold: Int, secondThreshold: Int, emitAlerts: Boolean): List<Int> {
        if (score < 0 || score > 1_000) return java.util.List.of()
        var first = clampThreshold(firstThreshold)
        var second = clampThreshold(secondThreshold)
        if (first > second) {
            val swap = first
            first = second
            second = swap
        }
        runHighWater = maxOf(runHighWater, score)
        if (!emitAlerts) return java.util.List.of()

        val crossed = ArrayList<Int>(2)
        fireIfReached(first, crossed)
        if (second != first) fireIfReached(second, crossed)
        return java.util.List.copyOf(crossed)
    }

    /**
     * Consumes only the locally calculated score estimate. Unknown scores remain unavailable, and a partial
     * range can fire only thresholds guaranteed by its lower bound.
     */
    fun update(
        estimate: DungeonOdinScoreCalculator.ScoreEstimate?,
        firstThreshold: Int,
        secondThreshold: Int,
        emitAlerts: Boolean,
    ): List<Int> {
        if (estimate == null || !estimate.available()) return java.util.List.of()
        val minimum = estimate.minimum() ?: return java.util.List.of()
        val maximum = estimate.maximum() ?: return java.util.List.of()
        if (minimum < 0 || maximum > 1_000 || minimum > maximum) return java.util.List.of()
        return update(minimum, firstThreshold, secondThreshold, emitAlerts)
    }

    private fun fireIfReached(threshold: Int, crossed: MutableList<Int>) {
        if (runHighWater >= threshold && firedThresholds.add(threshold)) crossed.add(threshold)
    }

    private fun clampThreshold(threshold: Int): Int = maxOf(0, minOf(300, threshold))

    fun reset() {
        runHighWater = -1
        firedThresholds.clear()
    }
}
