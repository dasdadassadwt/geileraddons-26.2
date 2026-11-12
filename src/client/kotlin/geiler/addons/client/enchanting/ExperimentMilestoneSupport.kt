package geiler.addons.client.enchanting

import java.util.Optional

/** Kotlin threshold and stopping rules behind the Java record boundary. */
object ExperimentMilestoneSupport {
    @JvmStatic
    fun validate(
        type: ExperimentType?,
        tier: ExperimentTier?,
        displayedSequenceLength: Int,
        completedRoundThreshold: Int,
    ) {
        if (type == null || type == ExperimentType.SUPERPAIRS) {
            throw IllegalArgumentException("Only sequence experiments have click milestones")
        }
        if (tier == null || tier == ExperimentTier.UNKNOWN) {
            throw IllegalArgumentException("A known tier is required")
        }
        if (displayedSequenceLength < 1 || completedRoundThreshold < 0) {
            throw IllegalArgumentException("Invalid milestone threshold")
        }
    }

    @JvmStatic
    fun normalizeSerums(serumsConsumed: Int): Int = serumsConsumed.coerceIn(0, 3)

    @JvmStatic
    fun forExperiment(
        type: ExperimentType?,
        tier: ExperimentTier?,
        serumsConsumed: Int,
    ): Optional<ExperimentMilestone> {
        if (type == null || type == ExperimentType.SUPERPAIRS || tier == null
            || tier == ExperimentTier.UNKNOWN
        ) {
            return Optional.empty()
        }
        val base = if (type == ExperimentType.CHRONOMATRON) {
            tier.chronomatronThreshold()
        } else {
            tier.ultrasequencerThreshold()
        }
        if (base < 1) return Optional.empty()
        val serumCount = normalizeSerums(serumsConsumed)
        val displayed = maxOf(1, base - serumCount)
        val completed = if (type == ExperimentType.ULTRASEQUENCER) displayed - 1 else displayed
        return Optional.of(
            ExperimentMilestone(type, tier, serumCount, displayed, completed, tier.maximumExtraClicks()),
        )
    }

    @JvmStatic
    fun reached(
        milestone: ExperimentMilestone,
        currentSequenceLength: Int,
        completedRounds: Int,
        phase: ExperimentPhase?,
    ): Boolean {
        var effectiveCompletedRounds = completedRounds
        if (milestone.type() == ExperimentType.CHRONOMATRON
            && phase == ExperimentPhase.ROUND_COMPLETE && completedRounds >= 0
        ) {
            // The counter increments with the next pattern; count this boundary immediately.
            effectiveCompletedRounds++
        }
        if (effectiveCompletedRounds >= 0) {
            return effectiveCompletedRounds >= milestone.completedRoundThreshold()
        }
        if (milestone.type() == ExperimentType.CHRONOMATRON
            && phase == ExperimentPhase.ROUND_COMPLETE
        ) {
            return currentSequenceLength >= milestone.displayedSequenceLength()
        }
        return if (milestone.type() == ExperimentType.ULTRASEQUENCER) {
            currentSequenceLength >= milestone.displayedSequenceLength()
        } else {
            currentSequenceLength > milestone.displayedSequenceLength()
        }
    }
}
