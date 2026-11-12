package geiler.addons.client.enchanting

import java.util.Optional

/** Auto Experiments execution API and values consumed by the solver presentation. */
class UltrasequencerSequenceExecutor {
    enum class Action {
        WAIT,
        CLICK,
        PAUSED,
        STOPPED,
        CLOSE_MENU,
    }

    class Decision private constructor(
        private val actionValue: Action,
        private val sequenceIndexValue: Int,
        private val slotIdValue: Int,
        private val explanationValue: String,
        @Suppress("UNUSED_PARAMETER") normalized: Unit,
    ) {
        constructor(action: Action?, sequenceIndex: Int, slotId: Int, explanation: String?) : this(
            UltrasequencerSequenceExecutorSupport.normalizeAction(action),
            sequenceIndex,
            slotId,
            UltrasequencerSequenceExecutorSupport.normalizeExplanation(explanation),
            Unit,
        )

        fun action(): Action = actionValue
        fun sequenceIndex(): Int = sequenceIndexValue
        fun slotId(): Int = slotIdValue
        fun explanation(): String = explanationValue

        override fun equals(other: Any?): Boolean = other is Decision
            && actionValue == other.actionValue
            && sequenceIndexValue == other.sequenceIndexValue
            && slotIdValue == other.slotIdValue
            && explanationValue == other.explanationValue

        override fun hashCode(): Int {
            var result = actionValue.hashCode()
            result = 31 * result + sequenceIndexValue
            result = 31 * result + slotIdValue
            result = 31 * result + explanationValue.hashCode()
            return result
        }

        override fun toString(): String =
            "Decision[action=$actionValue, sequenceIndex=$sequenceIndexValue, " +
                "slotId=$slotIdValue, explanation=$explanationValue]"
    }

    /** Immutable, presentation-only view of the Auto cursor for the exact captured solution. */
    class ExecutionProgress private constructor(
        private val solutionValue: Solution?,
        private val cursorValue: Int,
        private val dispatchPendingValue: Boolean,
        private val pausedValue: Boolean,
        private val pauseReasonValue: String,
        @Suppress("UNUSED_PARAMETER") normalized: Unit,
    ) {
        constructor(
            solution: Solution?,
            cursor: Int,
            dispatchPending: Boolean,
            paused: Boolean,
            pauseReason: String?,
        ) : this(
            solution,
            UltrasequencerSequenceExecutorSupport.normalizeCursor(solution, cursor),
            dispatchPending,
            paused,
            UltrasequencerSequenceExecutorSupport.normalizePauseReason(pauseReason),
            Unit,
        )

        fun solution(): Solution = solutionValue!!
        fun cursor(): Int = cursorValue
        fun dispatchPending(): Boolean = dispatchPendingValue
        fun paused(): Boolean = pausedValue
        fun pauseReason(): String = pauseReasonValue
        fun sequenceLength(): Int = solutionValue!!.sequenceLength()

        override fun equals(other: Any?): Boolean = other is ExecutionProgress
            && solutionValue == other.solutionValue
            && cursorValue == other.cursorValue
            && dispatchPendingValue == other.dispatchPendingValue
            && pausedValue == other.pausedValue
            && pauseReasonValue == other.pauseReasonValue

        override fun hashCode(): Int {
            var result = solutionValue?.hashCode() ?: 0
            result = 31 * result + cursorValue
            result = 31 * result + dispatchPendingValue.hashCode()
            result = 31 * result + pausedValue.hashCode()
            result = 31 * result + pauseReasonValue.hashCode()
            return result
        }

        override fun toString(): String =
            "ExecutionProgress[solution=$solutionValue, cursor=$cursorValue, " +
                "dispatchPending=$dispatchPendingValue, paused=$pausedValue, pauseReason=$pauseReasonValue]"
    }

    /** Read-only health check and adapter over the Solver's immutable render snapshot. */
    class Solution private constructor(
        private val tierValue: ExperimentTier,
        private val completedRoundsValue: Int,
        private val sequenceLengthValue: Int,
        private val milestoneSequenceLengthValue: Int,
        private val sequenceValue: List<SequenceStep>,
        @Suppress("UNUSED_PARAMETER") normalized: Unit,
    ) {
        constructor(
            tier: ExperimentTier?,
            completedRounds: Int,
            sequenceLength: Int,
            milestoneSequenceLength: Int,
            sequence: List<SequenceStep>?,
        ) : this(
            UltrasequencerSequenceExecutorSupport.normalizeTier(tier),
            completedRounds,
            sequenceLength,
            milestoneSequenceLength,
            UltrasequencerSequenceExecutorSupport.normalizeSequence(sequence),
            Unit,
        )

        fun tier(): ExperimentTier = tierValue
        fun completedRounds(): Int = completedRoundsValue
        fun sequenceLength(): Int = sequenceLengthValue
        fun milestoneSequenceLength(): Int = milestoneSequenceLengthValue
        fun sequence(): List<SequenceStep> = sequenceValue

        override fun equals(other: Any?): Boolean = other is Solution
            && tierValue == other.tierValue
            && completedRoundsValue == other.completedRoundsValue
            && sequenceLengthValue == other.sequenceLengthValue
            && milestoneSequenceLengthValue == other.milestoneSequenceLengthValue
            && sequenceValue == other.sequenceValue

        override fun hashCode(): Int {
            var result = tierValue.hashCode()
            result = 31 * result + completedRoundsValue
            result = 31 * result + sequenceLengthValue
            result = 31 * result + milestoneSequenceLengthValue
            result = 31 * result + sequenceValue.hashCode()
            return result
        }

        override fun toString(): String =
            "Solution[tier=$tierValue, completedRounds=$completedRoundsValue, " +
                "sequenceLength=$sequenceLengthValue, milestoneSequenceLength=$milestoneSequenceLengthValue, " +
                "sequence=$sequenceValue]"

        companion object {
            @JvmStatic
            fun from(view: SolverView?): Optional<Solution> =
                UltrasequencerSequenceExecutorSupport.solutionFrom(view)
        }
    }

    private val engine = UltrasequencerSequenceExecutorEngine()

    /** Binds work to one screen, menu, and controller session. */
    fun tick(
        screen: Any?,
        menu: Any?,
        sessionGeneration: Long,
        sessionValid: Boolean,
        view: SolverView?,
        nowNanos: Long,
        firstClickDelayNanos: Long,
    ): Decision = engine.tick(
        screen,
        menu,
        sessionGeneration,
        sessionValid,
        view,
        nowNanos,
        firstClickDelayNanos,
    )

    /** Client adapter path that waits for a fresh server-menu mutation after every click. */
    fun tickWithMenuRevision(
        screen: Any?,
        menu: Any?,
        sessionGeneration: Long,
        sessionValid: Boolean,
        view: SolverView?,
        nowNanos: Long,
        firstClickDelayNanos: Long,
        observedMenuRevision: Long,
    ): Decision = engine.tickWithMenuRevision(
        screen, menu, sessionGeneration, sessionValid, view, nowNanos,
        firstClickDelayNanos, observedMenuRevision,
    )

    /** Records only whether vanilla dispatch ran; solver cursor or server state is not consulted. */
    fun dispatchFinished(vanillaDispatched: Boolean, nowNanos: Long, nextClickDelayNanos: Long): Decision =
        engine.dispatchFinished(vanillaDispatched, nowNanos, nextClickDelayNanos)

    /** Captures the menu revision from immediately before the vanilla click was dispatched. */
    fun dispatchFinishedWithMenuRevision(
        vanillaDispatched: Boolean,
        nowNanos: Long,
        nextClickDelayNanos: Long,
        menuRevisionBeforeClick: Long,
    ): Decision = engine.dispatchFinishedWithMenuRevision(
        vanillaDispatched, nowNanos, nextClickDelayNanos, menuRevisionBeforeClick,
    )

    fun pauseForManualInput(): String = engine.pauseForManualInput()

    fun pauseForManualInput(screen: Any?, menu: Any?, sessionGeneration: Long): String =
        engine.pauseForManualInput(screen, menu, sessionGeneration)

    fun consumePauseNotice(): String = engine.consumePauseNotice()
    fun reason(): String = engine.reason()
    fun pauseReason(): String = engine.pauseReason()
    fun paused(): Boolean = engine.paused()
    fun cursor(): Int = engine.cursor()
    fun sequenceLength(): Int = engine.sequenceLength()
    fun awaitingMenuSlotId(): Int = engine.awaitingMenuSlotId()

    /** Returns progress only while the captured identity and solver data still match. */
    fun presentationProgress(
        screen: Any?,
        menu: Any?,
        sessionGeneration: Long,
        view: SolverView?,
    ): Optional<ExecutionProgress> = engine.presentationProgress(screen, menu, sessionGeneration, view)

    fun pendingDelay(): String = engine.pendingDelay()

    fun reset() {
        engine.reset()
    }
}
