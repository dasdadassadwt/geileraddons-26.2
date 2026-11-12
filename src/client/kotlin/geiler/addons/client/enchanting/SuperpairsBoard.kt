package geiler.addons.client.enchanting

import java.util.OptionalInt

/** Renderer-independent Superpairs facade and immutable result values. */
class SuperpairsBoard {
    private val logic = SuperpairsBoardLogic()

    fun reset() {
        logic.reset()
    }

    /** Applies the current server-visible board while retaining values learned earlier. */
    fun observe(cells: Iterable<ExperimentCell>?) {
        logic.observe(cells)
    }

    /** Records a local click; stale reveal requests may be retried after the original grace period. */
    fun click(slotId: Int): Boolean = logic.click(slotId)

    /** Explicit-time overload retained for deterministic package-level model checks. */
    fun click(slotId: Int, nowNanos: Long): Boolean = logic.clickAt(slotId, nowNanos)

    fun suggestedMatch(slotId: Int): OptionalInt = logic.suggestedMatch(slotId)

    fun view(): View = logic.view()

    enum class CardState {
        UNKNOWN,
        KNOWN,
        SELECTED,
        MATCH,
        RESOLVED,
    }

    /** Kotlin class with Java record-style accessors and value semantics. */
    class Card(slotId: Int, value: String?, state: CardState?) {
        private val cardSlotId = slotId
        private val cardValue = value
        private val cardState = state

        fun slotId(): Int = cardSlotId

        fun value(): String? = cardValue

        fun state(): CardState? = cardState

        override fun equals(other: Any?): Boolean =
            this === other || (other is Card
                && cardSlotId == other.cardSlotId
                && cardValue == other.cardValue
                && cardState == other.cardState)

        override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(cardSlotId, cardValue, cardState)

        override fun toString(): String = ExperimentValueSemantics.recordString(
            "Card",
            "slotId" to cardSlotId,
            "value" to cardValue,
            "state" to cardState,
        )
    }

    /** Immutable board snapshot with derived counters. */
    class View(
        cards: List<Card>?,
        selectedSlot: Int,
        awaitingRevealSlot: Int,
        pairs: Map<String, List<Int>>?,
    ) {
        private val boardCards = SuperpairsViewLogic.copyCards(cards)
        private val selected = selectedSlot
        private val awaitingReveal = awaitingRevealSlot
        private val discoveredPairs = SuperpairsViewLogic.copyPairs(pairs)

        fun cards(): List<Card> = boardCards

        fun selectedSlot(): Int = selected

        fun awaitingRevealSlot(): Int = awaitingReveal

        fun pairs(): Map<String, List<Int>> = discoveredPairs

        fun knownCount(): Int = SuperpairsViewLogic.knownCount(boardCards)

        fun discoveredCount(): Int = SuperpairsViewLogic.discoveredCount(boardCards)

        fun resolvedCount(): Int = SuperpairsViewLogic.resolvedCount(boardCards)

        fun collectedCount(): Int = SuperpairsViewLogic.collectedCount(boardCards)

        fun resolvedPairs(): Int = SuperpairsViewLogic.resolvedPairs(boardCards)

        fun totalPairs(): Int = SuperpairsViewLogic.totalPairs(boardCards)

        fun waitingForReveal(): Boolean = SuperpairsViewLogic.waitingForReveal(awaitingReveal)

        override fun equals(other: Any?): Boolean =
            this === other || (other is View
                && boardCards == other.boardCards
                && selected == other.selected
                && awaitingReveal == other.awaitingReveal
                && discoveredPairs == other.discoveredPairs)

        override fun hashCode(): Int = ExperimentValueSemantics.recordHashCode(
            boardCards,
            selected,
            awaitingReveal,
            discoveredPairs,
        )

        override fun toString(): String = ExperimentValueSemantics.recordString(
            "View",
            "cards" to boardCards,
            "selectedSlot" to selected,
            "awaitingRevealSlot" to awaitingReveal,
            "pairs" to discoveredPairs,
        )

        companion object {
            @JvmStatic
            fun empty(): View = SuperpairsViewLogic.empty()
        }
    }
}
