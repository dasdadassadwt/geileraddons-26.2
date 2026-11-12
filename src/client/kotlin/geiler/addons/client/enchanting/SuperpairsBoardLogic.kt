package geiler.addons.client.enchanting

import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.OptionalInt

/**
 * Renderer-independent Superpairs memory.
 *
 * Superpairs has no sequence to wait for. Every playable field may be clicked, and the only local
 * memory needed to discover a card is the last clicked slot. The adapter supplies a card value
 * after the server reveals it; this model retains discovered identities, duplicate hints, and pairs.
 */
internal class SuperpairsBoardLogic {
    private val slots = LinkedHashSet<Int>()
    private val known = LinkedHashMap<Int, String>()
    private val resolved = LinkedHashSet<Int>()
    private val duplicateSlots = LinkedHashSet<Int>()
    private val visibleSlots = HashSet<Int>()
    private var selectedSlot = -1
    private var awaitingRevealSlot = -1
    private var awaitingRevealAtNanos = 0L
    private var comparisonFirstSlot = -1
    private var comparisonSecondSlot = -1
    private var comparisonMatch = false
    private var comparisonMismatch = false
    private var comparisonRevealed = false

    fun reset() {
        slots.clear()
        known.clear()
        resolved.clear()
        duplicateSlots.clear()
        visibleSlots.clear()
        selectedSlot = -1
        awaitingRevealSlot = -1
        awaitingRevealAtNanos = 0L
        clearComparison()
    }

    /** Applies the current server-visible board while retaining values learned earlier. */
    fun observe(cells: Iterable<ExperimentCell?>?) {
        if (cells == null) return
        val visible = HashSet<Int>()
        for (cell in cells) {
            if (cell == null) continue
            slots.add(cell.slotId())
            if (!cell.revealed() || !cell.hasValue()) continue
            val value = cell.value() ?: continue
            visible.add(cell.slotId())
            if (cell.slotId() == awaitingRevealSlot ||
                ((cell.slotId() == comparisonFirstSlot || cell.slotId() == comparisonSecondSlot) &&
                    !visibleSlots.contains(cell.slotId()))
            ) {
                comparisonRevealed = true
            }
            if (!known.containsKey(cell.slotId())) {
                known[cell.slotId()] = value
                markDuplicate(cell.slotId(), value)
            }
        }

        if (awaitingRevealSlot >= 0 && known.containsKey(awaitingRevealSlot)) {
            awaitingRevealSlot = -1
            awaitingRevealAtNanos = 0L
        }
        evaluateComparison()
        if (comparisonMatch && comparisonRevealed && pairHidden(visible)) {
            resolved.add(comparisonFirstSlot)
            resolved.add(comparisonSecondSlot)
            selectedSlot = -1
            awaitingRevealSlot = -1
            awaitingRevealAtNanos = 0L
            clearComparison()
        } else if (comparisonMismatch && comparisonRevealed && pairHidden(visible)) {
            selectedSlot = -1
            awaitingRevealSlot = -1
            awaitingRevealAtNanos = 0L
            clearComparison()
        }

        visibleSlots.clear()
        visibleSlots.addAll(visible)
    }

    private fun pairHidden(visible: Set<Int>): Boolean =
        !visible.contains(comparisonFirstSlot) && !visible.contains(comparisonSecondSlot)

    private fun markDuplicate(slotId: Int, value: String) {
        for ((knownSlot, knownValue) in known) {
            if (knownSlot != slotId && value == knownValue && !resolved.contains(knownSlot)) {
                duplicateSlots.add(knownSlot)
                duplicateSlots.add(slotId)
            }
        }
    }

    private fun evaluateComparison() {
        if (comparisonFirstSlot < 0 || comparisonSecondSlot < 0) return
        val first = known[comparisonFirstSlot] ?: return
        val second = known[comparisonSecondSlot] ?: return
        comparisonMatch = first == second
        comparisonMismatch = !comparisonMatch
    }

    /** Records a local click and waits for that card's server reveal before accepting another. */
    fun click(slotId: Int): Boolean = clickAt(slotId, System.nanoTime())

    fun clickAt(slotId: Int, nowNanos: Long): Boolean {
        if (!slots.contains(slotId) || resolved.contains(slotId)) return false
        if (awaitingRevealSlot >= 0) {
            if (nowNanos - awaitingRevealAtNanos < REVEAL_RETRY_DELAY_NANOS) return false
            // The old request is no longer trusted. Drop only the unconfirmed selection; learned
            // identities and completed pairs remain authoritative.
            selectedSlot = -1
            awaitingRevealSlot = -1
            awaitingRevealAtNanos = 0L
            clearComparison()
        }
        if (selectedSlot == slotId) return false
        val previous = selectedSlot
        selectedSlot = slotId
        awaitingRevealSlot = if (known.containsKey(slotId)) -1 else slotId
        awaitingRevealAtNanos = if (awaitingRevealSlot >= 0) nowNanos else 0L
        clearComparison()
        if (previous >= 0 && previous != slotId && !resolved.contains(previous)) {
            comparisonFirstSlot = previous
            comparisonSecondSlot = slotId
            // Known identities classify a pair even if the server reveals and hides it in one
            // update; the hidden transition remains the completion boundary.
            comparisonRevealed = visibleSlots.contains(previous) || visibleSlots.contains(slotId) ||
                (known.containsKey(previous) && known.containsKey(slotId))
            evaluateComparison()
        }
        return true
    }

    private fun clearComparison() {
        comparisonFirstSlot = -1
        comparisonSecondSlot = -1
        comparisonMatch = false
        comparisonMismatch = false
        comparisonRevealed = false
    }

    fun suggestedMatch(slotId: Int): OptionalInt {
        val value = known[slotId] ?: return OptionalInt.empty()
        if (resolved.contains(slotId)) return OptionalInt.empty()
        for ((otherSlot, otherValue) in known) {
            if (otherSlot != slotId && !resolved.contains(otherSlot) && value == otherValue) {
                return OptionalInt.of(otherSlot)
            }
        }
        return OptionalInt.empty()
    }

    fun view(): SuperpairsBoard.View {
        val pairs = LinkedHashMap<String, MutableList<Int>>()
        for ((slotId, value) in known) pairs.getOrPut(value) { ArrayList() }.add(slotId)

        val cards = ArrayList<SuperpairsBoard.Card>()
        for (slot in slots) {
            val value = known[slot]
            val state = when {
                resolved.contains(slot) -> SuperpairsBoard.CardState.RESOLVED
                selectedSlot == slot -> SuperpairsBoard.CardState.SELECTED
                duplicateSlots.contains(slot) -> SuperpairsBoard.CardState.MATCH
                value != null -> SuperpairsBoard.CardState.KNOWN
                else -> SuperpairsBoard.CardState.UNKNOWN
            }
            cards.add(SuperpairsBoard.Card(slot, value, state))
        }

        val immutablePairs = LinkedHashMap<String, List<Int>>()
        for ((value, pairSlots) in pairs) immutablePairs[value] = java.util.List.copyOf(pairSlots)
        return SuperpairsBoard.View(cards, selectedSlot, awaitingRevealSlot, immutablePairs)
    }

    private companion object {
        const val REVEAL_RETRY_DELAY_NANOS = 500_000_000L
    }
}
