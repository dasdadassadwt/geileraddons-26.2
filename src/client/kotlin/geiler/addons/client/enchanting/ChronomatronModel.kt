package geiler.addons.client.enchanting

/**
 * Ordered-item Chronomatron state model, independent of slot positions and rendering.
 *
 * The model follows the four-state lifecycle: memory reveals append one normalized item identity,
 * replayed reveals only advance a chain counter, the timer opens the solve state, and completing
 * the remembered sequence closes the round. Slot ids detect the end of a reveal but are never
 * stored as solution truth.
 */
class ChronomatronModel {
    enum class State {
        REMEMBER,
        WAIT,
        SHOW,
        END,
    }

    private val items: MutableList<String> = ArrayList()
    private var currentState: State = State.REMEMBER
    private var chainLengthCount = 0
    private var currentRevealSlot = 0
    private var currentOrdinal = 0
    private var completedRounds = 0

    /** Applies one container event in the exact order received from the menu listener. */
    fun observe(event: ChronomatronEvent?) {
        if (event == null) return
        when (currentState) {
            State.REMEMBER -> observeRemember(event)
            State.WAIT -> observeWait(event)
            State.SHOW -> {
                // Solve-phase glints include the player's own clicks and are never memory evidence.
            }
            State.END -> observeEnd(event)
        }
    }

    private fun observeRemember(event: ChronomatronEvent) {
        if (event.kind() != ChronomatronEvent.Kind.BOARD) return
        if (currentRevealSlot == 0) {
            if (!event.highlighted()) return
            val value = event.value() ?: return
            if (items.size <= chainLengthCount) {
                items.add(value)
                currentState = State.WAIT
            } else {
                chainLengthCount++
            }
            currentRevealSlot = event.slotId()
        } else if (currentRevealSlot == event.slotId() && !event.highlighted()) {
            currentRevealSlot = 0
        }
    }

    private fun observeWait(event: ChronomatronEvent) {
        if (event.kind() == ChronomatronEvent.Kind.STATUS && event.status().startsWith("Timer: ")) {
            currentState = State.SHOW
        }
    }

    private fun observeEnd(event: ChronomatronEvent) {
        if (event.kind() != ChronomatronEvent.Kind.STATUS) return
        if (event.status().startsWith("Timer: ")) return
        // Any other text (click feedback, a lagging relabel) must not wipe the learned sequence: a
        // finished or failed game closes its menu, which discards this model with its session.
        if (event.status() == "Remember the pattern!") startReplay()
    }

    fun matches(ordinal: Int, value: String?): Boolean =
        currentState == State.SHOW && ordinal >= 0 && ordinal < items.size &&
            value != null && items[ordinal] == value

    /** Advances the same item ordinal that the live click guard just validated. */
    fun click(value: String?): Boolean {
        if (!matches(currentOrdinal, value)) return false
        currentOrdinal++
        if (currentOrdinal >= items.size) currentState = State.END
        return true
    }

    fun items(): List<String> = java.util.List.copyOf(items)

    fun state(): State = currentState

    fun chainLengthCount(): Int = chainLengthCount

    fun currentRevealSlot(): Int = currentRevealSlot

    fun currentOrdinal(): Int = currentOrdinal

    fun completedRounds(): Int = completedRounds

    fun phase(): ExperimentPhase = when (currentState) {
        State.REMEMBER -> ExperimentPhase.MEMORIZE
        State.WAIT -> ExperimentPhase.WAITING
        State.SHOW -> ExperimentPhase.SOLVE
        State.END -> ExperimentPhase.ROUND_COMPLETE
    }

    fun reset() {
        items.clear()
        chainLengthCount = 0
        currentRevealSlot = 0
        currentOrdinal = 0
        completedRounds = 0
        currentState = State.REMEMBER
    }

    private fun startReplay() {
        completedRounds++
        chainLengthCount = 0
        currentOrdinal = 0
        // Keep the previous reveal latched until its real non-glint update arrives. Clearing it
        // here mistakes the lingering highlight for the first replay pulse.
        currentState = State.REMEMBER
    }
}
