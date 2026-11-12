package geiler.addons.client.enchanting

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Tick-driven number memory and mutation-driven round boundaries, following Skyblocker.
 *
 * Pane churn during an unfinished solve is the board's button animation, so only the final
 * accepted click lets a pane edge end that round. An edge before the timer can be reopened once
 * for an unplayed remembered round; a completed replay never accepts a stale timer.
 */
class UltrasequencerModel {
    enum class State {
        REMEMBER,
        WAIT,
        SHOW,
        END,
    }

    private val remembered: MutableMap<Int, ExperimentCell> = LinkedHashMap()
    private var currentState: State = State.REMEMBER
    private var nextSlot = -1
    private var lastPaneColor: String? = null
    private var completedRounds = 0

    /** Accepted clicks in the current replay; pane animations only end it after the final click. */
    private var clicksThisRound = 0

    /** The pane changed while a remembered round was waiting for its solve timer. */
    private var reopenUnplayedRoundOnTimer = false

    /** Whether this round has already contributed to [completedRounds]. */
    private var countedThisRound = false

    /** Called once after a screen tick, never for each slot update or render frame. */
    fun observe(
        status: String?,
        cells: List<ExperimentCell?>?,
        ignoredPaneColor: String?,
        ignoredStatusEvent: Boolean,
    ) {
        observe(status, cells, ignoredPaneColor)
    }

    fun observe(status: String?, cells: List<ExperimentCell?>?, ignoredPaneColor: String?) {
        val instruction = status ?: ""
        when (currentState) {
            State.REMEMBER -> {
                if (isRememberNotice(instruction)) capture(cells)
            }
            State.WAIT -> {
                if (isTimerNotice(instruction) && remembered.isNotEmpty()) currentState = State.SHOW
            }
            State.END -> {
                if (isTimerNotice(instruction)) {
                    if (canReopenUnplayedRound()) {
                        currentState = State.SHOW
                        reopenUnplayedRoundOnTimer = false
                    }
                    return
                }
                if (isRememberNotice(instruction)) {
                    beginNextRound()
                    capture(cells)
                } else {
                    reset()
                }
            }
            State.SHOW -> {
                // A new memory notice is authoritative even if this model missed the previous edge.
                if (isRememberNotice(instruction)) {
                    beginNextRound()
                    capture(cells)
                }
            }
        }
    }

    private fun isRememberNotice(instruction: String): Boolean =
        instruction == "Remember the pattern!" || ExperimentPhase.isRememberStatus(instruction)

    private fun isTimerNotice(instruction: String): Boolean =
        instruction.startsWith("Timer: ") || ExperimentPhase.isTimerStatus(instruction)

    private fun capture(cells: List<ExperimentCell?>?) {
        clearRound()
        var captured = false
        for (cell in cells!!) {
            if (cell == null || cell.removed() || !cell.hasValue()) continue
            val value = cell.value()
            if (value == null) continue
            if (!NUMBER_PATTERN.matches(value)) continue
            remembered[cell.slotId()] = cell
            if (value == "1") nextSlot = cell.slotId()
            captured = true
        }
        // A status update can precede board slots. Keep waiting for a later tick to capture them.
        currentState = if (captured) State.WAIT else State.REMEMBER
    }

    /** Inspect panes in slot order on actual menu mutations, including during memory. */
    fun markDirty(paneColors: List<String?>?) {
        for (color in paneColors!!) {
            if (color == null || color == "black") continue
            if (color != lastPaneColor) {
                val completed = roundFinished()
                if (completed && !countedThisRound) {
                    countedThisRound = true
                    completedRounds++
                }
                lastPaneColor = color
                // A solve pane transition is its pressed/released animation; only the final click
                // or a transition before solve may end the current state.
                if (!midSolve() && currentState != State.END) {
                    reopenUnplayedRoundOnTimer = !completed && currentState == State.WAIT &&
                        remembered.isNotEmpty() && clicksThisRound == 0
                    currentState = State.END
                }
                return
            }
        }
    }

    fun click(slotId: Int): Boolean {
        if (!canClick(slotId)) return false
        val current = remembered[nextSlot] ?: return false
        val wantedCount = current.numericValue() + 1
        for (cell in remembered.values) {
            if (cell.numericValue() == wantedCount) {
                nextSlot = cell.slotId()
                break
            }
        }
        clicksThisRound++
        // The final button remains selected until the pane color changes, as in Skyblocker.
        return true
    }

    /** True only while this exact sequence position remains eligible for a new click. */
    fun canClick(slotId: Int): Boolean {
        val current = remembered[nextSlot]
        return currentState == State.SHOW && slotId == nextSlot && current != null &&
            clicksThisRound < remembered.size && !roundFinished()
    }

    /** True when a live pane edge happened before any click in the remembered solve. */
    private fun canReopenUnplayedRound(): Boolean =
        currentState == State.END && reopenUnplayedRoundOnTimer && remembered.isNotEmpty() && clicksThisRound == 0

    private fun midSolve(): Boolean =
        currentState == State.SHOW && remembered.isNotEmpty() && clicksThisRound < remembered.size

    private fun roundFinished(): Boolean =
        currentState == State.SHOW && remembered.isNotEmpty() && clicksThisRound >= remembered.size

    private fun clearRound() {
        remembered.clear()
        nextSlot = -1
        clicksThisRound = 0
        countedThisRound = false
        reopenUnplayedRoundOnTimer = false
    }

    private fun beginNextRound() {
        if (roundFinished() && !countedThisRound) {
            countedThisRound = true
            completedRounds++
        }
        clearRound()
        currentState = State.REMEMBER
    }

    fun sequence(): List<SequenceStep> {
        val result = ArrayList<SequenceStep>()
        remembered.values.sortedBy { it.numericValue() }.forEach { cell ->
            result.add(SequenceStep(result.size, cell.value(), listOf(cell.slotId())))
        }
        return java.util.List.copyOf(result)
    }

    fun state(): State = currentState

    fun completedRounds(): Int = completedRounds

    fun acceptedClicks(): Int = clicksThisRound

    fun currentIndex(): Int {
        val steps = sequence()
        for (index in steps.indices) {
            if (steps[index].slotIds().contains(nextSlot)) return index
        }
        return steps.size
    }

    fun phase(): ExperimentPhase = when (currentState) {
        State.REMEMBER -> ExperimentPhase.MEMORIZE
        State.WAIT -> ExperimentPhase.WAITING
        State.SHOW -> ExperimentPhase.SOLVE
        State.END -> ExperimentPhase.ROUND_COMPLETE
    }

    fun reset() {
        clearRound()
        currentState = State.REMEMBER
        lastPaneColor = null
        completedRounds = 0
    }

    private companion object {
        val NUMBER_PATTERN = Regex("\\d+")
    }
}
