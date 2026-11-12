package geiler.addons.client.enchanting

import java.util.Optional

/** Immutable render/input snapshot produced by [ExperimentSolverEngine]. */
class SolverView(
    type: ExperimentType?,
    tier: ExperimentTier?,
    phase: ExperimentPhase?,
    currentSequenceLength: Int,
    completedRounds: Int,
    sequence: List<SequenceStep>?,
    authoritativeIndex: Int,
    predictedIndex: Int,
    visualIndex: Int,
    current: Optional<SequenceStep>?,
    next: Optional<SequenceStep>?,
    nextNext: Optional<SequenceStep>?,
    superpairs: SuperpairsBoard.View?,
    milestone: Optional<ExperimentMilestone>?,
    milestoneReached: Boolean,
) {
    private val experimentType = type
    private val experimentTier = tier
    private val experimentPhase = phase
    private val sequenceLength = currentSequenceLength
    private val roundCount = completedRounds
    private val orderedSequence = SolverViewSupport.normalizeSequence(sequence)
    private val serverIndex = authoritativeIndex
    private val predictedSequenceIndex = predictedIndex
    private val displayIndex = visualIndex
    private val currentStep = SolverViewSupport.normalizeOptional(current)
    private val nextStep = SolverViewSupport.normalizeOptional(next)
    private val nextNextStep = SolverViewSupport.normalizeOptional(nextNext)
    private val superpairsView = SolverViewSupport.normalizeSuperpairs(superpairs)
    private val currentMilestone = SolverViewSupport.normalizeOptional(milestone)
    private val hasReachedMilestone = milestoneReached

    fun type(): ExperimentType? = experimentType

    fun tier(): ExperimentTier? = experimentTier

    fun phase(): ExperimentPhase? = experimentPhase

    fun currentSequenceLength(): Int = sequenceLength

    fun completedRounds(): Int = roundCount

    fun sequence(): List<SequenceStep> = orderedSequence

    fun authoritativeIndex(): Int = serverIndex

    fun predictedIndex(): Int = predictedSequenceIndex

    fun visualIndex(): Int = displayIndex

    fun current(): Optional<SequenceStep> = currentStep

    fun next(): Optional<SequenceStep> = nextStep

    fun nextNext(): Optional<SequenceStep> = nextNextStep

    fun superpairs(): SuperpairsBoard.View = superpairsView

    fun milestone(): Optional<ExperimentMilestone> = currentMilestone

    fun milestoneReached(): Boolean = hasReachedMilestone

    /** The real remaining sequence, capped without manufacturing unavailable future clicks. */
    fun upcoming(maximum: Int): List<SequenceStep> = SolverViewSupport.upcoming(this, maximum)

    override fun equals(other: Any?): Boolean =
        this === other || (other is SolverView
            && experimentType == other.experimentType
            && experimentTier == other.experimentTier
            && experimentPhase == other.experimentPhase
            && sequenceLength == other.sequenceLength
            && roundCount == other.roundCount
            && orderedSequence == other.orderedSequence
            && serverIndex == other.serverIndex
            && predictedSequenceIndex == other.predictedSequenceIndex
            && displayIndex == other.displayIndex
            && currentStep == other.currentStep
            && nextStep == other.nextStep
            && nextNextStep == other.nextNextStep
            && superpairsView == other.superpairsView
            && currentMilestone == other.currentMilestone
            && hasReachedMilestone == other.hasReachedMilestone)

    override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(
        experimentType,
        experimentTier,
        experimentPhase,
        sequenceLength,
        roundCount,
        orderedSequence,
        serverIndex,
        predictedSequenceIndex,
        displayIndex,
        currentStep,
        nextStep,
        nextNextStep,
        superpairsView,
        currentMilestone,
        hasReachedMilestone,
    )

    override fun toString(): String = ExperimentValueSemantics.recordString(
        "SolverView",
        "type" to experimentType,
        "tier" to experimentTier,
        "phase" to experimentPhase,
        "currentSequenceLength" to sequenceLength,
        "completedRounds" to roundCount,
        "sequence" to orderedSequence,
        "authoritativeIndex" to serverIndex,
        "predictedIndex" to predictedSequenceIndex,
        "visualIndex" to displayIndex,
        "current" to currentStep,
        "next" to nextStep,
        "nextNext" to nextNextStep,
        "superpairs" to superpairsView,
        "milestone" to currentMilestone,
        "milestoneReached" to hasReachedMilestone,
    )

    companion object {
        @JvmStatic
        fun idle(): SolverView = SolverViewSupport.idle()
    }
}
