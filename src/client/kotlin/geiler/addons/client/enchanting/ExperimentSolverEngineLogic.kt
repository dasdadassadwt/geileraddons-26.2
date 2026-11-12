package geiler.addons.client.enchanting

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Optional

/**
 * Shared, Minecraft-free state machine for Chronomatron, Ultrasequencer, and Superpairs.
 *
 * The sequence solvers keep one source of truth for each cursor: ordered menu events update the
 * memory/state machine and an accepted local click advances the cursor. A polled snapshot can
 * refresh the visible board, but it cannot rewrite progress from an inferred or delayed counter.
 */
internal class ExperimentSolverEngineLogic(configuration: ExperimentSolverEngine.Configuration?) {
    private class MutableStep(private val value: String, slotId: Int) {
        private val slots = ArrayList<Int>()

        init {
            addSlot(slotId)
        }

        private fun addSlot(slotId: Int) {
            if (slotId >= 0 && !slots.contains(slotId)) slots.add(slotId)
        }

        fun replaceSlots(slotIds: List<Int>) {
            slots.clear()
            for (slotId in slotIds) addSlot(slotId)
        }

        fun containsSlot(slotId: Int): Boolean = slots.contains(slotId)

        fun immutable(index: Int): SequenceStep = SequenceStep(index, value, slots)
    }

    private var currentConfiguration = configuration ?: ExperimentSolverEngine.Configuration.defaults()
    private var type: ExperimentType? = null
    private var tier: ExperimentTier = ExperimentTier.UNKNOWN
    private var currentPhase: ExperimentPhase = ExperimentPhase.IDLE
    private var lastRevision: Long = Long.MIN_VALUE
    private var currentSequenceLength = -1
    private var completedRounds = -1

    /** Kept as separate fields for the existing render contract; both mirror the model cursor. */
    private var authoritativeIndex = 0
    private var predictedIndex = 0
    private val sequence = ArrayList<MutableStep>()
    private val chronomatron = ChronomatronModel()
    private val chronomatronBoard = LinkedHashMap<Int, String>()
    private val ultrasequencer = UltrasequencerModel()
    private val superpairs = SuperpairsBoard()

    fun configuration(): ExperimentSolverEngine.Configuration = currentConfiguration

    fun configure(configuration: ExperimentSolverEngine.Configuration?) {
        currentConfiguration = configuration ?: ExperimentSolverEngine.Configuration.defaults()
    }

    /** Consumes a new server/container snapshot and returns the complete immutable render view. */
    fun observe(snapshot: ExperimentSnapshot?): SolverView = observe(snapshot, null)

    /** Consumes a snapshot together with its ordered Chronomatron event, when present. */
    fun observe(snapshot: ExperimentSnapshot?, chronomatronEvent: ChronomatronEvent?): SolverView {
        if (snapshot == null) {
            resetInternal()
            return SolverView.idle()
        }
        val incomingType = snapshot.type()
        if (incomingType == null) {
            resetInternal()
            return SolverView.idle()
        }

        val incomingTier = snapshot.tier()
        if (type != incomingType ||
            (tier != ExperimentTier.UNKNOWN && incomingTier != ExperimentTier.UNKNOWN && tier != incomingTier)
        ) {
            resetInternal()
            type = incomingType
            tier = incomingTier
        }
        if (lastRevision != Long.MIN_VALUE && snapshot.revision() < lastRevision) {
            // A delayed callback must not roll a local cursor or remembered sequence backwards.
            return view()
        }
        lastRevision = maxOf(lastRevision, snapshot.revision())
        if (tier == ExperimentTier.UNKNOWN && incomingTier != ExperimentTier.UNKNOWN) tier = incomingTier

        return when (type) {
            ExperimentType.CHRONOMATRON -> observeChronomatron(snapshot, chronomatronEvent)
            ExperimentType.ULTRASEQUENCER -> observeUltrasequencer(snapshot)
            ExperimentType.SUPERPAIRS -> observeSuperpairs(snapshot)
            else -> throw IllegalStateException("Unexpected experiment type: $type")
        }
    }

    /** Records a local click; sequence progress advances only after server-confirmed state. */
    fun onClick(slotId: Int): ClickDecision {
        if (type == null) return ClickDecision(slotId, false, false, false, false, "no experiment")
        if (currentPhase != ExperimentPhase.SOLVE) {
            return ClickDecision(slotId, true, false, false, false, "not in solve phase")
        }
        if (type == ExperimentType.SUPERPAIRS) {
            val changed = superpairs.click(slotId)
            return ClickDecision(slotId, true, changed, false, changed, if (changed) "selected" else "unplayable")
        }

        if (!expectedSequenceSlot(slotId)) {
            return ClickDecision(slotId, true, false, false, false, "unexpected slot")
        }
        return ClickDecision(slotId, true, true, false, false, "awaiting server confirmation")
    }

    /** Confirms a click after vanilla dispatched it; ordinary menu clicks may have no item diff. */
    fun confirmClick(slotId: Int): ClickDecision = confirmClick(displayIndex(), slotId)

    /** Confirms only the sequence index validated before vanilla received the click. */
    fun confirmClick(expectedSequenceIndex: Int, slotId: Int): ClickDecision {
        if (type == null) return ClickDecision(slotId, false, false, false, false, "no experiment")
        if (currentPhase != ExperimentPhase.SOLVE || type == ExperimentType.SUPERPAIRS) {
            return ClickDecision(slotId, true, false, false, false, "not a sequence click")
        }
        if (expectedSequenceIndex != displayIndex() || !expectedSequenceSlot(slotId)) {
            return ClickDecision(slotId, true, false, false, false, "unexpected slot")
        }
        if (!advanceSequenceClick(slotId)) {
            return ClickDecision(slotId, true, false, false, false, "unexpected slot")
        }
        return ClickDecision(slotId, true, true, false, true, "click dispatched")
    }

    /** Number of accepted positions in the current sequence, including the final Ultra click. */
    fun acceptedSequencePosition(): Int = when (type) {
        ExperimentType.CHRONOMATRON -> chronomatron.currentOrdinal()
        ExperimentType.ULTRASEQUENCER -> ultrasequencer.acceptedClicks()
        else -> 0
    }

    fun reset(): SolverView {
        resetInternal()
        return SolverView.idle()
    }

    /** Alias for lifecycle adapters that receive a disconnect rather than a screen close. */
    fun onDisconnect(): SolverView = reset()

    fun view(): SolverView {
        val steps = sequenceView()
        val visualIndex = minOf(displayIndex(), steps.size)
        val current = stepAt(steps, visualIndex)
        val next = stepAt(steps, visualIndex + 1)
        val nextNext = stepAt(steps, visualIndex + 2)
        val milestone = ExperimentMilestone.forExperiment(type, tier, currentConfiguration.serumsConsumed())
        val reached = (currentPhase == ExperimentPhase.SOLVE || currentPhase == ExperimentPhase.ROUND_COMPLETE) &&
            milestone.map { it.reached(currentSequenceLength, completedRounds, currentPhase) }.orElse(false)
        return SolverView(
            type,
            tier,
            currentPhase,
            currentSequenceLength,
            completedRounds,
            steps,
            authoritativeIndex,
            predictedIndex,
            visualIndex,
            current,
            next,
            nextNext,
            superpairs.view(),
            milestone,
            reached,
        )
    }

    private fun observeChronomatron(snapshot: ExperimentSnapshot, event: ChronomatronEvent?): SolverView {
        val explicitRememberEvent = event != null && event.kind() == ChronomatronEvent.Kind.STATUS &&
            ExperimentPhase.isRememberStatus(event.status())
        if (currentPhase == ExperimentPhase.COMPLETE && explicitRememberEvent) {
            // The same title can be opened for a fresh game after a terminal result.
            chronomatron.reset()
            chronomatronBoard.clear()
            currentPhase = ExperimentPhase.IDLE
        }

        chronomatron.observe(event)

        chronomatronBoard.clear()
        for (cell in snapshot.cells()) {
            if (cell.hasValue() && !cell.removed()) {
                val value = cell.value() ?: continue
                chronomatronBoard[cell.slotId()] = value
            }
        }
        syncChronomatronSequence()
        currentSequenceLength = sequence.size
        completedRounds = chronomatron.completedRounds()
        authoritativeIndex = chronomatron.currentOrdinal()
        predictedIndex = authoritativeIndex

        currentPhase = chronomatron.phase()
        return view()
    }

    private fun observeUltrasequencer(snapshot: ExperimentSnapshot): SolverView {
        if (currentPhase == ExperimentPhase.COMPLETE && ExperimentPhase.isRememberStatus(snapshot.status())) {
            ultrasequencer.reset()
            currentPhase = ExperimentPhase.IDLE
        }
        ultrasequencer.observe(
            snapshot.status(),
            snapshot.cells(),
            snapshot.ultrasequencerPaneColor(),
            snapshot.statusEvent(),
        )
        sequence.clear()
        for (step in ultrasequencer.sequence()) {
            val copy = MutableStep(step.value(), -1)
            copy.replaceSlots(step.slotIds())
            sequence.add(copy)
        }
        currentSequenceLength = sequence.size
        completedRounds = ultrasequencer.completedRounds()
        authoritativeIndex = ultrasequencer.currentIndex()
        predictedIndex = authoritativeIndex

        currentPhase = ultrasequencer.phase()
        return view()
    }

    /** Inventory mutations, unlike rendering, can signal an Ultrasequencer round boundary. */
    fun markUltrasequencerDirty(paneColors: List<String>?) {
        if (type != ExperimentType.ULTRASEQUENCER) return
        ultrasequencer.markDirty(paneColors)
        completedRounds = ultrasequencer.completedRounds()
        authoritativeIndex = ultrasequencer.currentIndex()
        predictedIndex = authoritativeIndex
        currentPhase = ultrasequencer.phase()
    }

    private fun observeSuperpairs(snapshot: ExperimentSnapshot): SolverView {
        if (currentPhase == ExperimentPhase.COMPLETE && !ExperimentPhase.isTerminalStatus(snapshot.status())) {
            superpairs.reset()
        }
        superpairs.observe(snapshot.cells())
        sequence.clear()
        currentSequenceLength = -1
        completedRounds = -1
        authoritativeIndex = 0
        predictedIndex = 0
        currentPhase = if (ExperimentPhase.isTerminalStatus(snapshot.status())) {
            ExperimentPhase.COMPLETE
        } else {
            ExperimentPhase.SOLVE
        }
        return view()
    }

    private fun syncChronomatronSequence() {
        sequence.clear()
        for (item in chronomatron.items()) {
            val step = MutableStep(item, -1)
            val liveSlots = ArrayList<Int>()
            for ((slotId, value) in chronomatronBoard) {
                if (item == value) liveSlots.add(slotId)
            }
            step.replaceSlots(liveSlots)
            sequence.add(step)
        }
    }

    private fun expectedSequenceSlot(slotId: Int): Boolean {
        val index = displayIndex()
        if (index < 0 || index >= sequence.size) return false
        if (type == ExperimentType.CHRONOMATRON) {
            return chronomatron.matches(index, chronomatronBoard[slotId])
        }
        return if (type == ExperimentType.ULTRASEQUENCER) {
            ultrasequencer.canClick(slotId) && sequence[index].containsSlot(slotId)
        } else {
            sequence[index].containsSlot(slotId)
        }
    }

    private fun advanceSequenceClick(slotId: Int): Boolean {
        val advanced = if (type == ExperimentType.CHRONOMATRON) {
            chronomatron.click(chronomatronBoard[slotId])
        } else {
            ultrasequencer.click(slotId)
        }
        if (!advanced) return false
        authoritativeIndex = displayIndexFromModel()
        predictedIndex = authoritativeIndex
        currentPhase = if (type == ExperimentType.CHRONOMATRON) chronomatron.phase() else ultrasequencer.phase()
        return true
    }

    private fun displayIndexFromModel(): Int = if (type == ExperimentType.CHRONOMATRON) {
        chronomatron.currentOrdinal()
    } else {
        ultrasequencer.currentIndex()
    }

    private fun sequenceView(): List<SequenceStep> {
        val result = ArrayList<SequenceStep>()
        for (index in sequence.indices) result.add(sequence[index].immutable(index))
        return java.util.List.copyOf(result)
    }

    private fun displayIndex(): Int = when (type) {
        ExperimentType.CHRONOMATRON -> chronomatron.currentOrdinal()
        ExperimentType.ULTRASEQUENCER -> ultrasequencer.currentIndex()
        else -> authoritativeIndex
    }

    private fun stepAt(steps: List<SequenceStep>, index: Int): Optional<SequenceStep> =
        if (index >= 0 && index < steps.size) Optional.of(steps[index]) else Optional.empty()

    private fun resetProgress() {
        currentPhase = ExperimentPhase.IDLE
        currentSequenceLength = -1
        completedRounds = -1
        authoritativeIndex = 0
        predictedIndex = 0
        sequence.clear()
        chronomatron.reset()
        chronomatronBoard.clear()
        ultrasequencer.reset()
        superpairs.reset()
    }

    private fun resetInternal() {
        type = null
        tier = ExperimentTier.UNKNOWN
        lastRevision = Long.MIN_VALUE
        resetProgress()
    }
}
