package geiler.addons.client.enchanting

import java.util.Optional

/** Kotlin constructor normalization and health checks behind the executor's Java-facing values. */
object UltrasequencerSequenceExecutorSupport {
    @JvmStatic
    fun normalizeAction(action: UltrasequencerSequenceExecutor.Action?): UltrasequencerSequenceExecutor.Action =
        action ?: UltrasequencerSequenceExecutor.Action.WAIT

    @JvmStatic
    fun normalizeExplanation(explanation: String?): String = ExperimentTextSupport.emptyIfNull(explanation)

    @JvmStatic
    fun normalizeCursor(solution: UltrasequencerSequenceExecutor.Solution?, cursor: Int): Int {
        if (solution == null) throw IllegalArgumentException("solution must not be null")
        return maxOf(0, minOf(solution.sequenceLength(), cursor))
    }

    @JvmStatic
    fun normalizePauseReason(pauseReason: String?): String =
        ExperimentTextSupport.defaultIfNull(pauseReason, "none")

    @JvmStatic
    fun normalizeTier(tier: ExperimentTier?): ExperimentTier = tier ?: ExperimentTier.UNKNOWN

    @JvmStatic
    fun normalizeSequence(sequence: List<SequenceStep>?): List<SequenceStep> =
        java.util.List.copyOf(sequence ?: emptyList())

    @JvmStatic
    fun solutionFrom(view: SolverView?): Optional<UltrasequencerSequenceExecutor.Solution> {
        if (view == null || view.type() != ExperimentType.ULTRASEQUENCER
            || view.phase() != ExperimentPhase.SOLVE || view.tier() == null
            || view.tier() == ExperimentTier.UNKNOWN || view.completedRounds() < 0
            || view.currentSequenceLength() <= 0
            || view.sequence().size != view.currentSequenceLength() || view.visualIndex() != 0
            || view.current().isEmpty() || view.sequence().isEmpty()
        ) {
            return Optional.empty()
        }

        for (index in view.sequence().indices) {
            val step = view.sequence()[index]
            if (step == null || step.index() != index || step.value().isBlank()
                || step.slotIds().size != 1
            ) {
                return Optional.empty()
            }
        }
        if (view.current().orElseThrow() != view.sequence().first()) return Optional.empty()
        val milestoneSequenceLength = view.milestone()
            .filter { it.type() == ExperimentType.ULTRASEQUENCER }
            .map { it.displayedSequenceLength() }
            .orElse(-1)
        return Optional.of(
            UltrasequencerSequenceExecutor.Solution(
                view.tier(),
                view.completedRounds(),
                view.currentSequenceLength(),
                milestoneSequenceLength,
                view.sequence(),
            ),
        )
    }
}

/** Stateful timing, context, and pause logic behind the Java executor façade. */
class UltrasequencerSequenceExecutorEngine {
    private var screenIdentity: Any? = null
    private var menuIdentity: Any? = null
    private var sessionGeneration = Long.MIN_VALUE
    private var contextBound = false
    private var solution: UltrasequencerSequenceExecutor.Solution? = null
    private var cursor = 0
    private var dueAtNanos = 0L
    private var delayArmed = false
    private var dispatchPending = false
    private var awaitingMenuUpdate = false
    private var menuRevisionBeforeClick = -1L
    private var menuUpdateWaitStartedAtNanos = 0L
    private var clickDelayAfterMenuUpdateNanos = 0L
    private var pendingClickSlotId = -1
    private var awaitingMenuSlotId = -1
    private var closeOnMilestone = false
    private var milestoneStopped = false
    private var milestoneCloseRequested = false
    private var paused = false
    private var pauseReason = "none"
    private var pendingPauseNotice = ""
    private var reason = "waiting for a complete solver solution"

    /** Binds work to one screen, menu, and controller session. */
    fun tick(
        screen: Any?,
        menu: Any?,
        sessionGeneration: Long,
        sessionValid: Boolean,
        view: SolverView?,
        nowNanos: Long,
        firstClickDelayNanos: Long,
    ): UltrasequencerSequenceExecutor.Decision = tickWithMenuRevision(
        screen, menu, sessionGeneration, sessionValid, view, nowNanos, firstClickDelayNanos, -1L,
    )

    /** Waits for a newer clientbound menu mutation after each click before arming the next one. */
    fun tickWithMenuRevision(
        screen: Any?,
        menu: Any?,
        sessionGeneration: Long,
        sessionValid: Boolean,
        view: SolverView?,
        nowNanos: Long,
        firstClickDelayNanos: Long,
        observedMenuRevision: Long,
    ): UltrasequencerSequenceExecutor.Decision {
        if (screen == null || menu == null) {
            reset()
            return waitDecision("waiting for the Ultrasequencer screen")
        }
        if (!contextBound || screen !== screenIdentity || menu !== menuIdentity
            || sessionGeneration != this.sessionGeneration
        ) {
            bindContext(screen, menu, sessionGeneration)
        }
        if (milestoneStopped) {
            return decision(UltrasequencerSequenceExecutor.Action.STOPPED, -1, -1, reason)
        }
        if (paused) return decision(UltrasequencerSequenceExecutor.Action.PAUSED, -1, -1, pauseReason)
        if (dispatchPending) return pause("A previous click attempt did not finish; it will not be resent.")
        if (!sessionValid) {
            val activeSolution = solution
            if (activeSolution != null && cursor < activeSolution.sequence().size) {
                return pause("The active experiment session is unavailable; automation paused without another click.")
            }
            reason = "waiting for the active Ultrasequencer session"
            return waitDecision(reason)
        }
        if (view != null && view.milestoneReached()) return reachMilestone()

        val current = UltrasequencerSequenceExecutor.Solution.from(view)
        if (current.isEmpty()) {
            val activeSolution = solution
            if (activeSolution != null && cursor < activeSolution.sequence().size) {
                return pause("The solver solution became unavailable, unhealthy, or incomplete before execution finished.")
            }
            if (view != null && view.type() == ExperimentType.ULTRASEQUENCER
                && view.phase() == ExperimentPhase.SOLVE
            ) {
                return pause("The solver's Ultrasequencer solution is unhealthy or incomplete; no click was sent.")
            }
            reason = if (cursor >= (solution?.sequence()?.size ?: 0)) {
                "sequence dispatched; waiting for the solver's next solution"
            } else {
                "waiting for a complete solver solution"
            }
            return waitDecision(reason)
        }

        val solverSolution = current.orElseThrow()
        val activeSolution = solution
        if (activeSolution == null) {
            if (isPastMilestone(solverSolution)) return reachMilestone()
            beginSolution(solverSolution, nowNanos, firstClickDelayNanos)
        } else if (activeSolution != solverSolution) {
            if (cursor < activeSolution.sequence().size) {
                return pause("The solver solution changed before the ordered sequence finished.")
            }
            if (isPastMilestone(solverSolution)) return reachMilestone()
            beginSolution(solverSolution, nowNanos, firstClickDelayNanos)
        }

        val capturedSolution = solution ?: return waitDecision("waiting for a complete solver solution")
        if (awaitingMenuUpdate) {
            if (observedMenuRevision <= menuRevisionBeforeClick) {
                if (elapsedAtLeast(nowNanos, menuUpdateWaitStartedAtNanos, MENU_UPDATE_WATCHDOG_NANOS)) {
                    return pause("No new menu state arrived after the click within 15 seconds; automation paused without retrying.")
                }
                reason = "click dispatched; waiting for a newer menu update"
                return waitDecision(reason)
            }
            awaitingMenuUpdate = false
            menuRevisionBeforeClick = -1L
            awaitingMenuSlotId = -1
            menuUpdateWaitStartedAtNanos = 0L
            if (cursor >= capturedSolution.sequence().size) {
                if (isMilestoneSequence(capturedSolution)) return reachMilestone()
                reason = "final slot update observed; waiting for the solver's next solution"
                return waitDecision(reason)
            }
            dueAtNanos = nowNanos + clickDelayAfterMenuUpdateNanos
            delayArmed = true
            reason = "menu update observed; waiting for the configured delay before the next solver slot"
            clickDelayAfterMenuUpdateNanos = 0L
        }
        if (cursor >= capturedSolution.sequence().size) {
            reason = "sequence dispatched; waiting for the solver's next solution"
            return waitDecision(reason)
        }
        if (!delayArmed) {
            dueAtNanos = nowNanos + maxOf(0L, firstClickDelayNanos)
            delayArmed = true
        }
        if (nowNanos < dueAtNanos) {
            reason = "waiting for configured click delay"
            return waitDecision(reason)
        }

        val step = capturedSolution.sequence()[cursor++]
        pendingClickSlotId = step.slotIds().first()
        dispatchPending = true
        delayArmed = false
        reason = "dispatching solver sequence position $cursor/${capturedSolution.sequenceLength()}"
        return decision(UltrasequencerSequenceExecutor.Action.CLICK, step.index(), pendingClickSlotId, reason)
    }

    /** Records whether vanilla dispatch ran; no solver or server state is consulted here. */
    fun dispatchFinished(
        vanillaDispatched: Boolean,
        nowNanos: Long,
        nextClickDelayNanos: Long,
    ): UltrasequencerSequenceExecutor.Decision =
        dispatchFinishedInternal(vanillaDispatched, nowNanos, nextClickDelayNanos, null)

    /** Production path; captures the menu revision immediately before vanilla dispatch. */
    fun dispatchFinishedWithMenuRevision(
        vanillaDispatched: Boolean,
        nowNanos: Long,
        nextClickDelayNanos: Long,
        revisionBeforeClick: Long,
    ): UltrasequencerSequenceExecutor.Decision = dispatchFinishedInternal(
        vanillaDispatched, nowNanos, nextClickDelayNanos, revisionBeforeClick,
    )

    private fun dispatchFinishedInternal(
        vanillaDispatched: Boolean,
        nowNanos: Long,
        nextClickDelayNanos: Long,
        revisionBeforeClick: Long?,
    ): UltrasequencerSequenceExecutor.Decision {
        if (!dispatchPending) return waitDecision(reason)
        dispatchPending = false
        if (!vanillaDispatched) {
            return pause("The screen or session guard rejected the click; it will not be retried.")
        }
        closeOnMilestone = true
        val activeSolution = solution
        if (revisionBeforeClick != null) {
            if (revisionBeforeClick < 0L) {
                return pause("The active menu update revision is unavailable; automation paused without retrying.")
            }
            if (pendingClickSlotId < 0) {
                return pause("The clicked menu slot is unavailable; automation paused without retrying.")
            }
            awaitingMenuUpdate = true
            menuRevisionBeforeClick = revisionBeforeClick
            awaitingMenuSlotId = pendingClickSlotId
            pendingClickSlotId = -1
            menuUpdateWaitStartedAtNanos = nowNanos
            clickDelayAfterMenuUpdateNanos = maxOf(0L, nextClickDelayNanos)
            reason = "click dispatched; waiting for a newer menu update before another slot"
            return waitDecision(reason)
        }
        pendingClickSlotId = -1
        if (activeSolution != null && cursor >= activeSolution.sequence().size
            && isMilestoneSequence(activeSolution)
        ) {
            return reachMilestone()
        }
        if (activeSolution != null && cursor < activeSolution.sequence().size) {
            dueAtNanos = nowNanos + maxOf(0L, nextClickDelayNanos)
            delayArmed = true
            reason = "click dispatched; waiting for configured delay before the next solver slot"
        } else {
            dueAtNanos = 0L
            delayArmed = false
            reason = "complete solver sequence dispatched; waiting for the solver's next solution"
        }
        return waitDecision(reason)
    }

    fun pauseForManualInput(): String {
        if (paused || milestoneStopped) return ""
        pause("manual Ultrasequencer input")
        pendingPauseNotice = ""
        return "Paused after manual Ultrasequencer input. Toggle Auto Experiments off and on to resume."
    }

    fun pauseForManualInput(screen: Any?, menu: Any?, sessionGeneration: Long): String {
        if (screen == null || menu == null) return ""
        if (!contextBound || screen !== screenIdentity || menu !== menuIdentity
            || sessionGeneration != this.sessionGeneration
        ) {
            bindContext(screen, menu, sessionGeneration)
        }
        return pauseForManualInput()
    }

    fun consumePauseNotice(): String {
        val notice = pendingPauseNotice
        pendingPauseNotice = ""
        return notice
    }

    fun reason(): String = reason

    fun pauseReason(): String = if (paused) pauseReason else "none"

    fun paused(): Boolean = paused

    fun cursor(): Int = cursor

    fun sequenceLength(): Int = solution?.sequence()?.size ?: 0

    fun awaitingMenuSlotId(): Int = if (awaitingMenuUpdate) awaitingMenuSlotId else -1

    /** Returns display progress only when the full captured identity and solution still match. */
    fun presentationProgress(
        screen: Any?,
        menu: Any?,
        sessionGeneration: Long,
        view: SolverView?,
    ): Optional<UltrasequencerSequenceExecutor.ExecutionProgress> {
        val capturedSolution = solution
        if (!contextBound || screen == null || menu == null || screen !== screenIdentity
            || menu !== menuIdentity || sessionGeneration != this.sessionGeneration || capturedSolution == null
        ) {
            return Optional.empty()
        }
        val current = UltrasequencerSequenceExecutor.Solution.from(view)
        if (current.isEmpty() || capturedSolution != current.orElseThrow()) return Optional.empty()
        return Optional.of(
            UltrasequencerSequenceExecutor.ExecutionProgress(
                capturedSolution,
                cursor,
                dispatchPending,
                paused,
                pauseReason,
            ),
        )
    }

    fun pendingDelay(): String =
        when {
            awaitingMenuUpdate -> "awaiting_menu_update"
            !delayArmed || solution == null || cursor >= solution!!.sequence().size -> "none"
            else -> "armed"
        }

    fun reset() {
        screenIdentity = null
        menuIdentity = null
        sessionGeneration = Long.MIN_VALUE
        contextBound = false
        solution = null
        cursor = 0
        dueAtNanos = 0L
        delayArmed = false
        dispatchPending = false
        awaitingMenuUpdate = false
        menuRevisionBeforeClick = -1L
        menuUpdateWaitStartedAtNanos = 0L
        clickDelayAfterMenuUpdateNanos = 0L
        pendingClickSlotId = -1
        awaitingMenuSlotId = -1
        closeOnMilestone = false
        milestoneStopped = false
        milestoneCloseRequested = false
        paused = false
        pauseReason = "none"
        pendingPauseNotice = ""
        reason = "waiting for a complete solver solution"
    }

    private fun beginSolution(
        next: UltrasequencerSequenceExecutor.Solution,
        nowNanos: Long,
        firstClickDelayNanos: Long,
    ) {
        solution = next
        cursor = 0
        dueAtNanos = nowNanos + maxOf(0L, firstClickDelayNanos)
        delayArmed = true
        dispatchPending = false
        awaitingMenuUpdate = false
        menuRevisionBeforeClick = -1L
        menuUpdateWaitStartedAtNanos = 0L
        clickDelayAfterMenuUpdateNanos = 0L
        pendingClickSlotId = -1
        awaitingMenuSlotId = -1
        reason = "solver solution captured; waiting for configured first-click delay"
    }

    private fun isPastMilestone(value: UltrasequencerSequenceExecutor.Solution): Boolean =
        value.milestoneSequenceLength() > 0 && value.sequenceLength() > value.milestoneSequenceLength()

    private fun isMilestoneSequence(value: UltrasequencerSequenceExecutor.Solution): Boolean =
        value.milestoneSequenceLength() > 0 && value.sequenceLength() >= value.milestoneSequenceLength()

    private fun bindContext(screen: Any, menu: Any, generation: Long) {
        reset()
        screenIdentity = screen
        menuIdentity = menu
        sessionGeneration = generation
        contextBound = true
    }

    private fun reachMilestone(): UltrasequencerSequenceExecutor.Decision {
        if (!milestoneStopped) {
            milestoneStopped = true
            paused = true
            pauseReason = "experiment bonus-click milestone reached"
            pendingPauseNotice = ""
            dispatchPending = false
            delayArmed = false
            awaitingMenuUpdate = false
            menuRevisionBeforeClick = -1L
            menuUpdateWaitStartedAtNanos = 0L
            clickDelayAfterMenuUpdateNanos = 0L
            pendingClickSlotId = -1
            awaitingMenuSlotId = -1
            reason = "experiment bonus-click milestone reached; automation stopped"
        }
        return if (closeOnMilestone && !milestoneCloseRequested) {
            milestoneCloseRequested = true
            decision(UltrasequencerSequenceExecutor.Action.CLOSE_MENU, -1, -1, reason)
        } else {
            decision(UltrasequencerSequenceExecutor.Action.STOPPED, -1, -1, reason)
        }
    }

    private fun pause(explanation: String): UltrasequencerSequenceExecutor.Decision {
        if (!paused) {
            paused = true
            pauseReason = if (explanation.isBlank()) "automation paused" else explanation
            pendingPauseNotice = pauseReason
            dispatchPending = false
            delayArmed = false
            awaitingMenuUpdate = false
            menuRevisionBeforeClick = -1L
            menuUpdateWaitStartedAtNanos = 0L
            clickDelayAfterMenuUpdateNanos = 0L
            pendingClickSlotId = -1
            awaitingMenuSlotId = -1
            reason = pauseReason
        }
        return decision(UltrasequencerSequenceExecutor.Action.PAUSED, -1, -1, pauseReason)
    }

    private fun waitDecision(explanation: String): UltrasequencerSequenceExecutor.Decision =
        decision(UltrasequencerSequenceExecutor.Action.WAIT, -1, -1, explanation)

    private fun decision(
        action: UltrasequencerSequenceExecutor.Action,
        index: Int,
        slot: Int,
        explanation: String,
    ): UltrasequencerSequenceExecutor.Decision =
        UltrasequencerSequenceExecutor.Decision(action, index, slot, explanation)

    private fun elapsedAtLeast(nowNanos: Long, startNanos: Long, durationNanos: Long): Boolean =
        nowNanos - startNanos >= durationNanos

    private companion object {
        const val MENU_UPDATE_WATCHDOG_NANOS = 15_000_000_000L
    }
}
