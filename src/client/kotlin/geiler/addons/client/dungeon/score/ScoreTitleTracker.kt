package geiler.addons.client.dungeon.score

/** The two Score Titles milestones. Each fires once per run, in ascending order, when the score first reaches it. */
class ScoreTitleTracker {
    private val fired = HashSet<Int>()

    fun update(score: Int): List<Int> {
        if (score < 0 || score > 1_000) return emptyList()
        val crossed = ArrayList<Int>(2)
        for (threshold in THRESHOLDS) {
            if (score >= threshold && fired.add(threshold)) crossed.add(threshold)
        }
        return crossed
    }

    fun reset() = fired.clear()

    companion object {
        const val FIRST = 270
        const val SECOND = 300
        private val THRESHOLDS = intArrayOf(FIRST, SECOND)
    }
}
