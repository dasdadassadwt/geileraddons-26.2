package geiler.addons.client.enchanting

/** Chronomatron automation API and value types used by the client adapter. */
class AutoExperimentAutomation {
    enum class Action {
        WAIT,
        CLICK,
        PAUSED,
        STOPPED,
        CLOSE_MENU,
    }

    class Snapshot private constructor(
        private val enabledValue: Boolean,
        private val gameEnabledValue: Boolean,
        private val contextValidValue: Boolean,
        private val screenIdentityValue: Any?,
        private val menuIdentityValue: Any?,
        private val typeValue: ExperimentType?,
        private val tierValue: ExperimentTier?,
        private val phaseValue: ExperimentPhase?,
        private val statusValue: String,
        private val sequenceAvailableValue: Boolean,
        private val currentIndexValue: Int,
        private val sequenceLengthValue: Int,
        private val expectedSlotIdValue: Int,
        private val completedRoundsValue: Int,
        private val milestoneReachedValue: Boolean,
        @Suppress("UNUSED_PARAMETER") normalized: Unit,
    ) {
        constructor(
            enabled: Boolean,
            gameEnabled: Boolean,
            contextValid: Boolean,
            screenIdentity: Any?,
            menuIdentity: Any?,
            type: ExperimentType?,
            tier: ExperimentTier?,
            phase: ExperimentPhase?,
            status: String?,
            sequenceAvailable: Boolean,
            currentIndex: Int,
            sequenceLength: Int,
            expectedSlotId: Int,
            completedRounds: Int,
            milestoneReached: Boolean,
        ) : this(
            enabled,
            gameEnabled,
            contextValid,
            screenIdentity,
            menuIdentity,
            type,
            tier,
            phase,
            AutoExperimentAutomationSupport.normalizeStatus(status),
            sequenceAvailable,
            AutoExperimentAutomationSupport.nonNegative(currentIndex),
            AutoExperimentAutomationSupport.nonNegative(sequenceLength),
            expectedSlotId,
            AutoExperimentAutomationSupport.atLeastMinusOne(completedRounds),
            milestoneReached,
            Unit,
        )

        fun enabled(): Boolean = enabledValue
        fun gameEnabled(): Boolean = gameEnabledValue
        fun contextValid(): Boolean = contextValidValue
        fun screenIdentity(): Any? = screenIdentityValue
        fun menuIdentity(): Any? = menuIdentityValue
        fun type(): ExperimentType? = typeValue
        fun tier(): ExperimentTier? = tierValue
        fun phase(): ExperimentPhase? = phaseValue
        fun status(): String = statusValue
        fun sequenceAvailable(): Boolean = sequenceAvailableValue
        fun currentIndex(): Int = currentIndexValue
        fun sequenceLength(): Int = sequenceLengthValue
        fun expectedSlotId(): Int = expectedSlotIdValue
        fun completedRounds(): Int = completedRoundsValue
        fun milestoneReached(): Boolean = milestoneReachedValue

        override fun equals(other: Any?): Boolean = other is Snapshot
            && enabledValue == other.enabledValue
            && gameEnabledValue == other.gameEnabledValue
            && contextValidValue == other.contextValidValue
            && screenIdentityValue == other.screenIdentityValue
            && menuIdentityValue == other.menuIdentityValue
            && typeValue == other.typeValue
            && tierValue == other.tierValue
            && phaseValue == other.phaseValue
            && statusValue == other.statusValue
            && sequenceAvailableValue == other.sequenceAvailableValue
            && currentIndexValue == other.currentIndexValue
            && sequenceLengthValue == other.sequenceLengthValue
            && expectedSlotIdValue == other.expectedSlotIdValue
            && completedRoundsValue == other.completedRoundsValue
            && milestoneReachedValue == other.milestoneReachedValue

        override fun hashCode(): Int {
            var result = enabledValue.hashCode()
            result = 31 * result + gameEnabledValue.hashCode()
            result = 31 * result + contextValidValue.hashCode()
            result = 31 * result + (screenIdentityValue?.hashCode() ?: 0)
            result = 31 * result + (menuIdentityValue?.hashCode() ?: 0)
            result = 31 * result + (typeValue?.hashCode() ?: 0)
            result = 31 * result + (tierValue?.hashCode() ?: 0)
            result = 31 * result + (phaseValue?.hashCode() ?: 0)
            result = 31 * result + statusValue.hashCode()
            result = 31 * result + sequenceAvailableValue.hashCode()
            result = 31 * result + currentIndexValue
            result = 31 * result + sequenceLengthValue
            result = 31 * result + expectedSlotIdValue
            result = 31 * result + completedRoundsValue
            result = 31 * result + milestoneReachedValue.hashCode()
            return result
        }

        override fun toString(): String =
            "Snapshot[enabled=$enabledValue, gameEnabled=$gameEnabledValue, contextValid=$contextValidValue, " +
                "screenIdentity=$screenIdentityValue, menuIdentity=$menuIdentityValue, type=$typeValue, " +
                "tier=$tierValue, phase=$phaseValue, status=$statusValue, " +
                "sequenceAvailable=$sequenceAvailableValue, currentIndex=$currentIndexValue, " +
                "sequenceLength=$sequenceLengthValue, expectedSlotId=$expectedSlotIdValue, " +
                "completedRounds=$completedRoundsValue, milestoneReached=$milestoneReachedValue]"
    }

    class Decision private constructor(
        private val actionValue: Action,
        private val sequenceIndexValue: Int,
        private val slotIdValue: Int,
        private val explanationValue: String,
        @Suppress("UNUSED_PARAMETER") normalized: Unit,
    ) {
        constructor(action: Action?, sequenceIndex: Int, slotId: Int, explanation: String?) : this(
            AutoExperimentAutomationSupport.requireAction(action),
            sequenceIndex,
            slotId,
            AutoExperimentAutomationSupport.normalizeExplanation(explanation),
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

    private val engine = AutoExperimentAutomationEngine()

    /** Called whenever Auto Experiments is switched off or on to discard stale work and pauses. */
    fun reset() {
        engine.reset()
    }

    /** Cancels a queued click after the player takes over; a module toggle is required to resume. */
    fun pauseForManualInput(): String = engine.pauseForManualInput()

    /** Returns a due click once, then waits for the adapter to report its guarded dispatch result. */
    fun tick(snapshot: Snapshot?, nowNanos: Long, firstClickDelayNanos: Long): Decision =
        engine.tick(snapshot, nowNanos, firstClickDelayNanos)

    /** Client adapter path that gates each non-final click on a newer server-menu mutation. */
    fun tickWithMenuRevision(
        snapshot: Snapshot?,
        nowNanos: Long,
        firstClickDelayNanos: Long,
        observedMenuRevision: Long,
    ): Decision = engine.tickWithMenuRevision(
        snapshot, nowNanos, firstClickDelayNanos, observedMenuRevision,
    )

    /** Compatibility overload for older callers; inter-click timing is applied after dispatch. */
    fun tick(
        snapshot: Snapshot?,
        nowNanos: Long,
        firstClickDelayNanos: Long,
        ignoredClickDelayNanos: Long,
    ): Decision = engine.tick(snapshot, nowNanos, firstClickDelayNanos, ignoredClickDelayNanos)

    /** Re-anchors a scheduled sequence after the player correctly advances it manually. */
    fun synchronizeAfterManualProgress(snapshot: Snapshot?, nowNanos: Long, clickDelayNanos: Long): Decision =
        engine.synchronizeAfterManualProgress(snapshot, nowNanos, clickDelayNanos)

    /** Completes the one outstanding click request. A rejected dispatch is never retried. */
    fun clickResult(
        dispatched: Boolean,
        afterDispatch: Snapshot?,
        nowNanos: Long,
        clickDelayNanos: Long,
    ): Decision = engine.clickResult(dispatched, afterDispatch, nowNanos, clickDelayNanos)

    /** Client adapter path; the caller captures [menuRevisionBeforeClick] before vanilla dispatch. */
    fun clickResultWithMenuRevision(
        dispatched: Boolean,
        afterDispatch: Snapshot?,
        nowNanos: Long,
        clickDelayNanos: Long,
        menuRevisionBeforeClick: Long,
        clickedSlotId: Int,
    ): Decision = engine.clickResultWithMenuRevision(
        dispatched, afterDispatch, nowNanos, clickDelayNanos, menuRevisionBeforeClick, clickedSlotId,
    )

    fun awaitingMenuSlotId(): Int = engine.awaitingMenuSlotId()

    companion object {
        const val STAGE_TRANSITION_WATCHDOG_NANOS: Long = 15_000_000_000L
    }
}
