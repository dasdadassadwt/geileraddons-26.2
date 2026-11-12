package geiler.addons.client.enchanting

import java.util.Optional

/** Bonus-click stopping point, including serum adjustment and Ultrasequencer's one-round offset. */
class ExperimentMilestone(
    type: ExperimentType?,
    tier: ExperimentTier?,
    serumsConsumed: Int,
    displayedSequenceLength: Int,
    completedRoundThreshold: Int,
    maximumExtraClicks: Int,
) {
    private val milestoneType: ExperimentType
    private val milestoneTier: ExperimentTier
    private val serumCount: Int
    private val displayedLength = displayedSequenceLength
    private val roundThreshold = completedRoundThreshold
    private val extraClickLimit = maximumExtraClicks

    init {
        ExperimentMilestoneSupport.validate(type, tier, displayedSequenceLength, completedRoundThreshold)
        milestoneType = type!!
        milestoneTier = tier!!
        serumCount = ExperimentMilestoneSupport.normalizeSerums(serumsConsumed)
    }

    fun type(): ExperimentType = milestoneType

    fun tier(): ExperimentTier = milestoneTier

    fun serumsConsumed(): Int = serumCount

    fun displayedSequenceLength(): Int = displayedLength

    fun completedRoundThreshold(): Int = roundThreshold

    fun maximumExtraClicks(): Int = extraClickLimit

    fun reached(currentSequenceLength: Int, completedRounds: Int): Boolean =
        ExperimentMilestoneSupport.reached(this, currentSequenceLength, completedRounds, ExperimentPhase.SOLVE)

    fun reached(currentSequenceLength: Int, completedRounds: Int, phase: ExperimentPhase?): Boolean =
        ExperimentMilestoneSupport.reached(this, currentSequenceLength, completedRounds, phase)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExperimentMilestone
            && milestoneType == other.milestoneType
            && milestoneTier == other.milestoneTier
            && serumCount == other.serumCount
            && displayedLength == other.displayedLength
            && roundThreshold == other.roundThreshold
            && extraClickLimit == other.extraClickLimit)

    override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(
        milestoneType,
        milestoneTier,
        serumCount,
        displayedLength,
        roundThreshold,
        extraClickLimit,
    )

    override fun toString(): String = ExperimentValueSemantics.recordString(
        "ExperimentMilestone",
        "type" to milestoneType,
        "tier" to milestoneTier,
        "serumsConsumed" to serumCount,
        "displayedSequenceLength" to displayedLength,
        "completedRoundThreshold" to roundThreshold,
        "maximumExtraClicks" to extraClickLimit,
    )

    companion object {
        @JvmStatic
        fun forExperiment(
            type: ExperimentType?,
            tier: ExperimentTier?,
            serumsConsumed: Int,
        ): Optional<ExperimentMilestone> = ExperimentMilestoneSupport.forExperiment(type, tier, serumsConsumed)
    }
}
