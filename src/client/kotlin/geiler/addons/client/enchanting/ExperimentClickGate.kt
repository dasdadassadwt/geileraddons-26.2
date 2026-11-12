package geiler.addons.client.enchanting

import java.util.function.IntConsumer

/**
 * One by-slot gate for manual and automated sequence clicks.
 *
 * The Minecraft adapter proves that the screen and menu belong to the active experiment. This
 * gate checks the solver's expected step, wraps vanilla dispatch, and confirms one position or a
 * safe server-driven transition.
 */
class ExperimentClickGate {
    enum class SequenceDispatchResult(
        private val wasDispatched: Boolean,
        private val wasProgressAdvanced: Boolean,
    ) {
        REJECTED(false, false),
        CONFIRMED(true, true),
        ALREADY_ADVANCED(true, true),
        UNCERTAIN(true, false);

        /** Whether vanilla ran and the request must never be sent again. */
        fun dispatched(): Boolean = wasDispatched

        /** Whether one solver position was confirmed locally or by a safe transition. */
        fun progressAdvanced(): Boolean = wasProgressAdvanced
    }

    private var dispatchInProgress = false

    fun dispatching(): Boolean = dispatchInProgress

    /** Dispatches only the exact expected Chronomatron or Ultrasequencer slot. */
    fun dispatchSequenceClick(
        engine: ExperimentSolverEngine?,
        slotId: Int,
        contextValid: Boolean,
        vanillaDispatch: IntConsumer?,
    ): Boolean {
        val before = engine?.view()
        val expectedSequenceIndex = before?.current()?.map { it.index() }?.orElse(-1) ?: -1
        return dispatchSequenceClick(engine, expectedSequenceIndex, slotId, contextValid, vanillaDispatch)
    }

    /** Dispatches only the requested sequence index and slot, including repeated slot IDs. */
    fun dispatchSequenceClick(
        engine: ExperimentSolverEngine?,
        expectedSequenceIndex: Int,
        slotId: Int,
        contextValid: Boolean,
        vanillaDispatch: IntConsumer?,
    ): Boolean = dispatchSequenceClickResult(
        engine,
        expectedSequenceIndex,
        slotId,
        contextValid,
        vanillaDispatch,
    ).dispatched()

    /**
     * If vanilla ran but local confirmation or one safe transition did not follow, UNCERTAIN
     * still records the dispatch so the caller pauses instead of resending it.
     */
    fun dispatchSequenceClickResult(
        engine: ExperimentSolverEngine?,
        expectedSequenceIndex: Int,
        slotId: Int,
        contextValid: Boolean,
        vanillaDispatch: IntConsumer?,
    ): SequenceDispatchResult {
        if (dispatchInProgress || !contextValid || engine == null || vanillaDispatch == null) {
            return SequenceDispatchResult.REJECTED
        }
        val before = engine.view()
        val beforePosition = engine.acceptedSequencePosition()
        if ((before.type() != ExperimentType.CHRONOMATRON
                && before.type() != ExperimentType.ULTRASEQUENCER)
            || before.phase() != ExperimentPhase.SOLVE || before.milestoneReached()
            || before.current().isEmpty()
            || before.current().orElseThrow().index() != expectedSequenceIndex
            || before.visualIndex() != expectedSequenceIndex
            || beforePosition != expectedSequenceIndex
            || !before.current().orElseThrow().containsSlot(slotId)
        ) {
            return SequenceDispatchResult.REJECTED
        }
        if (!engine.onClick(slotId).expected()) return SequenceDispatchResult.REJECTED

        dispatchVanilla(Runnable { vanillaDispatch.accept(slotId) })
        if (engine.confirmClick(expectedSequenceIndex, slotId).visualStateChanged()) {
            return SequenceDispatchResult.CONFIRMED
        }
        return if (advancedExactlyOnce(
                before,
                engine.view(),
                expectedSequenceIndex,
                beforePosition,
                engine.acceptedSequencePosition(),
            )
        ) {
            SequenceDispatchResult.ALREADY_ADVANCED
        } else {
            SequenceDispatchResult.UNCERTAIN
        }
    }

    /** Wraps a non-sequence vanilla dispatch such as a Superpairs card click. */
    fun dispatchVanilla(vanillaDispatch: Runnable?) {
        val previous = dispatchInProgress
        dispatchInProgress = true
        try {
            vanillaDispatch!!.run()
        } finally {
            dispatchInProgress = previous
        }
    }

    private companion object {
        fun advancedExactlyOnce(
            before: SolverView?,
            after: SolverView?,
            dispatchedIndex: Int,
            beforePosition: Int,
            afterPosition: Int,
        ): Boolean {
            if (before == null || after == null || dispatchedIndex < 0
                || after.type() != before.type() || after.tier() != before.tier()
                || after.sequence().size != before.sequence().size
                || beforePosition != dispatchedIndex || afterPosition != dispatchedIndex + 1
            ) {
                return false
            }
            for (index in before.sequence().indices) {
                if (before.sequence()[index].value() != after.sequence()[index].value()) return false
            }
            val nextIndex = dispatchedIndex + 1
            val sameRound = after.completedRounds() == before.completedRounds()
            if (nextIndex < before.sequence().size) {
                return sameRound && after.phase() == ExperimentPhase.SOLVE
                    && after.visualIndex() == nextIndex
                    && after.current().isPresent
                    && after.current().orElseThrow().index() == nextIndex
                    && before.sequence()[nextIndex].value() == after.current().orElseThrow().value()
            }
            if (before.type() == ExperimentType.CHRONOMATRON) {
                return sameRound && after.phase() == ExperimentPhase.ROUND_COMPLETE
                    && after.visualIndex() == nextIndex && after.current().isEmpty
            }
            val finalStep = before.sequence()[nextIndex - 1]
            if (sameRound) {
                return (after.phase() == ExperimentPhase.SOLVE
                    || after.phase() == ExperimentPhase.ROUND_COMPLETE)
                    && after.current().isPresent
                    && after.current().orElseThrow().index() == nextIndex - 1
                    && after.current().orElseThrow().containsSlot(finalStep.slotIds().first())
                    && finalStep.value() == after.current().orElseThrow().value()
            }
            // The final Ultrasequencer click may coincide with its pane edge. Accept only one
            // round increment while the same sequence and terminal cursor remain visible.
            return nextIndex == before.sequence().size
                && after.completedRounds() == before.completedRounds() + 1
                && after.phase() == ExperimentPhase.ROUND_COMPLETE
                && after.visualIndex() == nextIndex - 1
                && after.current().isPresent
                && after.current().orElseThrow().index() == nextIndex - 1
                && after.current().orElseThrow().containsSlot(finalStep.slotIds().first())
                && finalStep.value() == after.current().orElseThrow().value()
        }
    }
}
