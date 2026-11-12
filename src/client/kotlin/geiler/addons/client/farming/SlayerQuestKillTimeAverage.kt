package geiler.addons.client.farming

/** Keeps kill-time samples scoped to one active Slayer quest and its completed HUD grace period. */
class SlayerQuestKillTimeAverage(maxSamples: Int = SlayerKillTimeAverage.DEFAULT_MAX_SAMPLES) {
    private val samples = SlayerKillTimeAverage(maxSamples)
    private var questActive = false

    /** Starts a new sample window once; repeated chat/scoreboard start signals are harmless. */
    fun beginQuest(): Boolean {
        if (questActive) return false
        samples.reset()
        questActive = true
        return true
    }

    /** Closes the sample window but keeps its result visible after a successful quest. */
    fun completeQuest() {
        questActive = false
    }

    /** A failed quest has no completed average to display. */
    fun failQuest() {
        questActive = false
        samples.reset()
    }

    fun record(durationNanos: Long): Boolean = questActive && samples.record(durationNanos)

    fun sampleCount(): Int = samples.sampleCount()

    fun averageNanos(): Long = samples.averageNanos()

    fun questActive(): Boolean = questActive

    fun reset() {
        questActive = false
        samples.reset()
    }
}
