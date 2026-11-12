package geiler.addons.client.enchanting

import geiler.addons.client.enchanting.AutoExperimentAutomation.Action
import geiler.addons.client.enchanting.AutoExperimentAutomation.Decision
import geiler.addons.client.enchanting.AutoExperimentAutomation.Snapshot

/**
 * Minecraft-free timing and pause state for Chronomatron automation.
 *
 * Each tick can return at most one click request. The caller must validate the live screen and
 * menu again, dispatch through its guarded vanilla click path, and report the result before this
 * state machine will consider another click.
 */
class AutoExperimentAutomationEngine {
    private enum class Mode {
        IDLE,
        FIRST_CLICK_DELAY,
        CLICK_DELAY,
        CLICK_PENDING,
        AWAITING_MENU_UPDATE,
        AWAITING_STAGE,
        PAUSED,
        STOPPED,
    }

    private var mode = Mode.IDLE
    private var contextInitialized = false
    private var screenIdentity: Any? = null
    private var menuIdentity: Any? = null
    private var type: ExperimentType? = null
    private var tier: ExperimentTier? = null
    private var paused = false
    private var pauseExplanation = ""
    private var dueAtNanos = 0L
    private var delayedSequenceIndex = -1
    private var delayedSequenceLength = -1
    private var delayedSlotId = -1
    private var pendingSequenceIndex = -1
    private var pendingSlotId = -1
    private var pendingMenuUpdateRevision = -1L
    private var awaitingMenuSlotId = -1
    private var menuUpdateWaitStartedAtNanos = 0L
    private var clickDelayAfterMenuUpdateNanos = 0L
    private var closeOnMilestone = false
    private var completedSequenceLength = -1
    private var completedRounds = -1
    private var completionStatus: String? = ""
    private var stageWaitStartedAtNanos = 0L
    private var stageWatchdogArmed = false

    /** Called whenever Auto Experiments is switched off or on to discard stale work and pauses. */
    fun reset() {
        paused = false
        pauseExplanation = ""
        clearContext()
    }

    /** Cancels a queued click after the player takes over; a module toggle is required to resume. */
    fun pauseForManualInput(): String {
        if (paused) return ""
        paused = true
        closeOnMilestone = false
        pauseExplanation = "Paused after a manual experiment click. Toggle Auto Experiments off and on to resume."
        mode = Mode.PAUSED
        pendingSequenceIndex = -1
        pendingSlotId = -1
        pendingMenuUpdateRevision = -1L
        awaitingMenuSlotId = -1
        menuUpdateWaitStartedAtNanos = 0L
        clickDelayAfterMenuUpdateNanos = 0L
        return pauseExplanation
    }

    /** Returns a due click once, then waits for the adapter to report its guarded dispatch result. */
    fun tick(snapshot: Snapshot?, nowNanos: Long, firstClickDelayNanos: Long): Decision =
        tickWithMenuRevision(snapshot, nowNanos, firstClickDelayNanos, -1L)

    /**
     * Rechecks the active menu after each accepted non-final click. A new menu revision is required
     * before another automated click can be armed, so delayed server updates cannot advance a stale
     * local cursor.
     */
    fun tickWithMenuRevision(
        snapshot: Snapshot?,
        nowNanos: Long,
        firstClickDelayNanos: Long,
        observedMenuRevision: Long,
    ): Decision {
        if (snapshot == null || !snapshot.enabled()) {
            reset()
            return waitDecision()
        }
        if (!snapshot.gameEnabled() || !eligibleContext(snapshot)) {
            clearContext()
            return waitDecision()
        }
        if (!matchesContext(snapshot)) beginContext(snapshot)
        if (paused) return waitDecision()

        if (mode == Mode.STOPPED) {
            // A new puzzle may reuse the same chest menu after the previous one finished.
            if (snapshot.phase() == ExperimentPhase.MEMORIZE
                && ExperimentPhase.isRememberStatus(snapshot.status())) {
                mode = Mode.IDLE
            } else {
                return decision(Action.STOPPED, "")
            }
        }
        if (snapshot.tier() == null || snapshot.tier() == ExperimentTier.UNKNOWN) {
            return pause("The experiment tier is unknown; automation paused without clicking.")
        }
        if (snapshot.milestoneReached()) {
            mode = Mode.STOPPED
            val shouldClose = closeOnMilestone
            closeOnMilestone = false
            pendingSequenceIndex = -1
            pendingSlotId = -1
            return decision(if (shouldClose) Action.CLOSE_MENU else Action.STOPPED, "")
        }
        if (snapshot.phase() == ExperimentPhase.COMPLETE) {
            mode = Mode.STOPPED
            closeOnMilestone = false
            pendingSequenceIndex = -1
            pendingSlotId = -1
            return decision(Action.STOPPED, "")
        }

        val phase = snapshot.phase() ?: ExperimentPhase.IDLE
        if (phase == ExperimentPhase.MEMORIZE || phase == ExperimentPhase.WAITING) {
            // These are ordinary game phases. They are not a reason to click or to trip the watchdog.
            mode = Mode.IDLE
            pendingSlotId = -1
            pendingMenuUpdateRevision = -1L
            awaitingMenuSlotId = -1
            menuUpdateWaitStartedAtNanos = 0L
            clickDelayAfterMenuUpdateNanos = 0L
            stageWatchdogArmed = false
            return waitDecision()
        }
        if (phase == ExperimentPhase.ROUND_COMPLETE) {
            if (mode != Mode.AWAITING_STAGE) beginAwaitingStage(snapshot, nowNanos)
            val reported = ExperimentPhase.detect(snapshot.type(), snapshot.status())
            if (stageWatchdogArmed
                && (reported == ExperimentPhase.MEMORIZE || reported == ExperimentPhase.WAITING)
                && ExperimentPhase.isKnownStatus(snapshot.type(), snapshot.status())
                && ExperimentPhase.normalizeStatus(snapshot.status()) !=
                ExperimentPhase.normalizeStatus(completionStatus)) {
                // Some game variants keep the local model at ROUND_COMPLETE until the next ordered
                // board update. A known memorize/wait status proves that the stage has moved on; a timer
                // still waits for a valid next sequence within the watchdog window.
                stageWatchdogArmed = false
            }
            return watchdogDecision(nowNanos)
        }
        if (phase != ExperimentPhase.SOLVE) {
            return pause("The experiment state is unavailable; automation paused without clicking.")
        }

        if (mode == Mode.AWAITING_STAGE) {
            if (isNextStage(snapshot)) {
                beginFirstClickDelay(snapshot, nowNanos, firstClickDelayNanos)
                return waitDecision()
            }
            val reported = ExperimentPhase.detect(snapshot.type(), snapshot.status())
            if ((reported == ExperimentPhase.MEMORIZE || reported == ExperimentPhase.WAITING)
                && ExperimentPhase.isKnownStatus(snapshot.type(), snapshot.status())
                && ExperimentPhase.normalizeStatus(snapshot.status()) !=
                ExperimentPhase.normalizeStatus(completionStatus)) {
                // The server has entered the next memorize/wait phase, even if the local model is still
                // holding the just-finished sequence until its next ordered menu event arrives.
                stageWatchdogArmed = false
            }
            if (stageWatchdogArmed && elapsedAtLeast(nowNanos, stageWaitStartedAtNanos,
                    AutoExperimentAutomation.STAGE_TRANSITION_WATCHDOG_NANOS)) {
                return pause("The next experiment stage did not appear within 15 seconds; no click was retried.")
            }
            if (isCompletedSequence(snapshot)) return waitDecision()
            return waitDecision()
        }

        if (mode == Mode.AWAITING_MENU_UPDATE) {
            if (observedMenuRevision <= pendingMenuUpdateRevision) {
                if (elapsedAtLeast(nowNanos, menuUpdateWaitStartedAtNanos,
                        AutoExperimentAutomation.STAGE_TRANSITION_WATCHDOG_NANOS)) {
                    return pause("No new menu state arrived after the click within 15 seconds; automation paused without retrying.")
                }
                return waitDecision()
            }
            if (!matchesDelayedStep(snapshot)) {
                return pause("The expected next step changed while waiting for the menu update; automation paused without clicking.")
            }
            dueAtNanos = nowNanos + clickDelayAfterMenuUpdateNanos
            mode = Mode.CLICK_DELAY
            pendingMenuUpdateRevision = -1L
            awaitingMenuSlotId = -1
            menuUpdateWaitStartedAtNanos = 0L
            clickDelayAfterMenuUpdateNanos = 0L
        }

        if (!ExperimentPhase.isKnownStatus(snapshot.type(), snapshot.status())
            && !isCorrectFeedback(snapshot.status())) {
            return pause("The experiment state is unavailable; automation paused without clicking.")
        }
        if (isCompletedSequence(snapshot)) {
            beginAwaitingStage(snapshot, nowNanos)
            return waitDecision()
        }
        if (!hasExpectedStep(snapshot)) {
            return pause("The expected experiment sequence is unavailable; automation paused without clicking.")
        }

        if (mode == Mode.IDLE) {
            beginFirstClickDelay(snapshot, nowNanos, firstClickDelayNanos)
            return waitDecision()
        }
        if (mode == Mode.CLICK_PENDING) return waitDecision()
        if (mode == Mode.FIRST_CLICK_DELAY || mode == Mode.CLICK_DELAY) {
            if (!matchesDelayedStep(snapshot)) {
                return pause("The expected experiment step changed during its delay; automation paused without clicking.")
            }
            if (!deadlineReached(nowNanos, dueAtNanos)) return waitDecision()
            pendingSequenceIndex = delayedSequenceIndex
            pendingSlotId = delayedSlotId
            mode = Mode.CLICK_PENDING
            return Decision(Action.CLICK, pendingSequenceIndex, pendingSlotId, "")
        }
        return pause("The experiment automation state is inconsistent; automation paused without clicking.")
    }

    /** Compatibility overload for older callers; inter-click timing is applied after dispatch. */
    fun tick(
        snapshot: Snapshot?,
        nowNanos: Long,
        firstClickDelayNanos: Long,
        ignoredClickDelayNanos: Long,
    ): Decision = tick(snapshot, nowNanos, firstClickDelayNanos)

    /**
     * Re-anchors a scheduled sequence after the player correctly advances it manually. The stale
     * delayed slot is discarded, and the newly observed step receives the normal inter-click delay.
     */
    fun synchronizeAfterManualProgress(snapshot: Snapshot?, nowNanos: Long, clickDelayNanos: Long): Decision {
        reset()
        if (snapshot == null || !snapshot.enabled()) return waitDecision()
        if (!snapshot.gameEnabled() || !eligibleContext(snapshot)) {
            clearContext()
            return waitDecision()
        }
        beginContext(snapshot)
        if (snapshot.tier() == null || snapshot.tier() == ExperimentTier.UNKNOWN) {
            return pause("The experiment tier is unknown; automation paused without clicking.")
        }
        if (snapshot.milestoneReached() || snapshot.phase() == ExperimentPhase.COMPLETE) {
            mode = Mode.STOPPED
            return decision(Action.STOPPED, "")
        }

        val phase = snapshot.phase() ?: ExperimentPhase.IDLE
        if (phase == ExperimentPhase.MEMORIZE || phase == ExperimentPhase.WAITING) {
            mode = Mode.IDLE
            stageWatchdogArmed = false
            return waitDecision()
        }
        if (!ExperimentPhase.isKnownStatus(snapshot.type(), snapshot.status())
            && !isCorrectFeedback(snapshot.status())) {
            return pause("The experiment state is unavailable; automation paused without clicking.")
        }
        if (phase == ExperimentPhase.ROUND_COMPLETE || isCompletedSequence(snapshot)) {
            beginAwaitingStage(snapshot, nowNanos)
            return waitDecision()
        }
        if (!hasExpectedStep(snapshot)) {
            return pause("The expected experiment sequence is unavailable; automation paused without clicking.")
        }

        captureDelayedStep(snapshot)
        dueAtNanos = nowNanos + maxOf(0L, clickDelayNanos)
        mode = Mode.CLICK_DELAY
        return waitDecision()
    }

    /** Completes the one outstanding click request. A rejected dispatch is never retried. */
    fun clickResult(
        dispatched: Boolean,
        afterDispatch: Snapshot?,
        nowNanos: Long,
        clickDelayNanos: Long,
    ): Decision {
        return clickResultInternal(dispatched, afterDispatch, nowNanos, clickDelayNanos, null, null)
    }

    /** Click-result path used by the client adapter; the revision is captured before vanilla dispatch. */
    fun clickResultWithMenuRevision(
        dispatched: Boolean,
        afterDispatch: Snapshot?,
        nowNanos: Long,
        clickDelayNanos: Long,
        menuRevisionBeforeClick: Long,
        clickedSlotId: Int,
    ): Decision = clickResultInternal(
        dispatched, afterDispatch, nowNanos, clickDelayNanos, menuRevisionBeforeClick, clickedSlotId,
    )

    private fun clickResultInternal(
        dispatched: Boolean,
        afterDispatch: Snapshot?,
        nowNanos: Long,
        clickDelayNanos: Long,
        menuRevisionBeforeClick: Long?,
        clickedSlotId: Int?,
    ): Decision {
        if (mode != Mode.CLICK_PENDING) return waitDecision()
        val dispatchedSequenceIndex = pendingSequenceIndex
        val dispatchedFinalStep = delayedSequenceLength > 0
            && pendingSequenceIndex == delayedSequenceLength - 1 && pendingSlotId >= 0
        pendingSequenceIndex = -1
        pendingSlotId = -1
        if (!dispatched) {
            return pause("The experiment changed before the click could be dispatched; automation paused without retrying.")
        }
        if (afterDispatch == null || !afterDispatch.contextValid() || !matchesContext(afterDispatch)
            || !afterDispatch.enabled() || !afterDispatch.gameEnabled()) {
            return pause("The experiment screen or menu changed during the click; automation paused without retrying.")
        }
        closeOnMilestone = true
        if (afterDispatch.milestoneReached()) {
            mode = Mode.STOPPED
            closeOnMilestone = false
            return decision(Action.CLOSE_MENU, "")
        }
        if (afterDispatch.phase() == ExperimentPhase.COMPLETE) {
            mode = Mode.STOPPED
            closeOnMilestone = false
            return decision(Action.STOPPED, "")
        }
        if (dispatchedFinalStep || afterDispatch.phase() == ExperimentPhase.ROUND_COMPLETE
            || isCompletedSequence(afterDispatch)) {
            beginAwaitingStage(afterDispatch, nowNanos)
            return waitDecision()
        }
        if (afterDispatch.phase() == ExperimentPhase.MEMORIZE
            || afterDispatch.phase() == ExperimentPhase.WAITING) {
            mode = Mode.IDLE
            stageWatchdogArmed = false
            return waitDecision()
        }
        if (afterDispatch.phase() != ExperimentPhase.SOLVE || !hasExpectedStep(afterDispatch)) {
            return pause("The next experiment step is unavailable after the click; automation paused without retrying.")
        }
        if (afterDispatch.currentIndex() != dispatchedSequenceIndex + 1) {
            return pause("The experiment sequence did not advance after the click; automation paused without retrying.")
        }
        captureDelayedStep(afterDispatch)
        if (menuRevisionBeforeClick == null) {
            // Compatibility path for Minecraft-free callers predating menu revision tracking.
            dueAtNanos = nowNanos + maxOf(0L, clickDelayNanos)
            mode = Mode.CLICK_DELAY
            return waitDecision()
        }
        if (menuRevisionBeforeClick < 0L) {
            return pause("The active menu update revision is unavailable; automation paused without retrying.")
        }
        if (clickedSlotId == null || clickedSlotId < 0) {
            return pause("The clicked menu slot is unavailable; automation paused without retrying.")
        }
        pendingMenuUpdateRevision = menuRevisionBeforeClick
        awaitingMenuSlotId = clickedSlotId
        menuUpdateWaitStartedAtNanos = nowNanos
        clickDelayAfterMenuUpdateNanos = maxOf(0L, clickDelayNanos)
        mode = Mode.AWAITING_MENU_UPDATE
        return waitDecision()
    }

    private fun eligibleContext(snapshot: Snapshot): Boolean =
        snapshot.contextValid() && snapshot.screenIdentity() != null && snapshot.menuIdentity() != null
            && snapshot.type() == ExperimentType.CHRONOMATRON

    private fun matchesContext(snapshot: Snapshot): Boolean =
        contextInitialized && screenIdentity === snapshot.screenIdentity()
            && menuIdentity === snapshot.menuIdentity() && type == snapshot.type() && tier == snapshot.tier()

    fun awaitingMenuSlotId(): Int = if (mode == Mode.AWAITING_MENU_UPDATE) awaitingMenuSlotId else -1

    private fun beginContext(snapshot: Snapshot) {
        clearCycle()
        contextInitialized = true
        screenIdentity = snapshot.screenIdentity()
        menuIdentity = snapshot.menuIdentity()
        type = snapshot.type()
        tier = snapshot.tier()
        mode = if (paused) Mode.PAUSED else Mode.IDLE
    }

    private fun clearContext() {
        contextInitialized = false
        screenIdentity = null
        menuIdentity = null
        type = null
        tier = null
        clearCycle()
        mode = if (paused) Mode.PAUSED else Mode.IDLE
    }

    private fun clearCycle() {
        dueAtNanos = 0L
        delayedSequenceIndex = -1
        delayedSequenceLength = -1
        delayedSlotId = -1
        pendingSequenceIndex = -1
        pendingSlotId = -1
        pendingMenuUpdateRevision = -1L
        awaitingMenuSlotId = -1
        menuUpdateWaitStartedAtNanos = 0L
        clickDelayAfterMenuUpdateNanos = 0L
        closeOnMilestone = false
        completedSequenceLength = -1
        completedRounds = -1
        completionStatus = ""
        stageWaitStartedAtNanos = 0L
        stageWatchdogArmed = false
        if (!paused) mode = Mode.IDLE
    }

    private fun beginFirstClickDelay(snapshot: Snapshot, nowNanos: Long, delayNanos: Long) {
        captureDelayedStep(snapshot)
        dueAtNanos = nowNanos + maxOf(0L, delayNanos)
        stageWatchdogArmed = false
        mode = Mode.FIRST_CLICK_DELAY
    }

    private fun captureDelayedStep(snapshot: Snapshot) {
        delayedSequenceIndex = snapshot.currentIndex()
        delayedSequenceLength = snapshot.sequenceLength()
        delayedSlotId = snapshot.expectedSlotId()
    }

    private fun matchesDelayedStep(snapshot: Snapshot): Boolean =
        hasExpectedStep(snapshot) && snapshot.currentIndex() == delayedSequenceIndex
            && snapshot.sequenceLength() == delayedSequenceLength
            && snapshot.expectedSlotId() == delayedSlotId

    private fun beginAwaitingStage(snapshot: Snapshot, nowNanos: Long) {
        mode = Mode.AWAITING_STAGE
        completedSequenceLength = snapshot.sequenceLength()
        completedRounds = snapshot.completedRounds()
        completionStatus = snapshot.status()
        stageWaitStartedAtNanos = nowNanos
        pendingMenuUpdateRevision = -1L
        awaitingMenuSlotId = -1
        menuUpdateWaitStartedAtNanos = 0L
        clickDelayAfterMenuUpdateNanos = 0L
        val reported = ExperimentPhase.detect(snapshot.type(), snapshot.status())
        val knownStatus = ExperimentPhase.isKnownStatus(snapshot.type(), snapshot.status())
        stageWatchdogArmed = !knownStatus
            || (reported != ExperimentPhase.MEMORIZE && reported != ExperimentPhase.WAITING)
        pendingSlotId = -1
    }

    private fun watchdogDecision(nowNanos: Long): Decision =
        if (stageWatchdogArmed && elapsedAtLeast(nowNanos, stageWaitStartedAtNanos,
                AutoExperimentAutomation.STAGE_TRANSITION_WATCHDOG_NANOS)) {
            pause("The next experiment stage did not appear within 15 seconds; no click was retried.")
        } else {
            waitDecision()
        }

    private fun isNextStage(snapshot: Snapshot): Boolean = hasExpectedStep(snapshot)
        && snapshot.currentIndex() == 0
        && (snapshot.sequenceLength() > completedSequenceLength || snapshot.completedRounds() > completedRounds)

    private fun hasExpectedStep(snapshot: Snapshot): Boolean = snapshot.sequenceAvailable()
        && snapshot.sequenceLength() > 0 && snapshot.currentIndex() >= 0
        && snapshot.currentIndex() < snapshot.sequenceLength() && snapshot.expectedSlotId() >= 0

    private fun isCompletedSequence(snapshot: Snapshot): Boolean = snapshot.sequenceAvailable()
        && snapshot.sequenceLength() > 0 && snapshot.currentIndex() >= snapshot.sequenceLength()

    private fun isCorrectFeedback(status: String?): Boolean {
        val normalized = ExperimentPhase.normalizeStatus(status)
        return normalized == "correct" || normalized == "correct!"
    }

    private fun pause(explanation: String?): Decision {
        if (paused) return waitDecision()
        paused = true
        closeOnMilestone = false
        pauseExplanation = explanation ?: "Automation paused."
        mode = Mode.PAUSED
        pendingSequenceIndex = -1
        pendingSlotId = -1
        pendingMenuUpdateRevision = -1L
        awaitingMenuSlotId = -1
        menuUpdateWaitStartedAtNanos = 0L
        clickDelayAfterMenuUpdateNanos = 0L
        return decision(Action.PAUSED, pauseExplanation)
    }

    companion object {
        private fun waitDecision(): Decision = decision(Action.WAIT, "")

        private fun decision(action: Action, explanation: String?): Decision =
            Decision(action, -1, -1, explanation)

        private fun deadlineReached(nowNanos: Long, deadlineNanos: Long): Boolean =
            nowNanos - deadlineNanos >= 0L

        private fun elapsedAtLeast(nowNanos: Long, startNanos: Long, durationNanos: Long): Boolean =
            nowNanos - startNanos >= durationNanos
    }
}
