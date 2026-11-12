package geiler.addons.client.enchanting

/** Immutable snapshot and derived-count behavior behind the Java-compatible Superpairs records. */
object SuperpairsViewLogic {
    @JvmStatic
    fun copyCards(cards: List<SuperpairsBoard.Card>?): List<SuperpairsBoard.Card> =
        java.util.List.copyOf(cards ?: emptyList())

    @JvmStatic
    fun copyPairs(pairs: Map<String, List<Int>>?): Map<String, List<Int>> =
        java.util.Map.copyOf(pairs ?: emptyMap())

    @JvmStatic
    fun knownCount(cards: List<SuperpairsBoard.Card>): Int = cards.count { card ->
        val value = card.value()
        value != null && value.any { !Character.isWhitespace(it) }
    }

    @JvmStatic
    fun discoveredCount(cards: List<SuperpairsBoard.Card>): Int = knownCount(cards)

    @JvmStatic
    fun resolvedCount(cards: List<SuperpairsBoard.Card>): Int =
        cards.count { it.state() == SuperpairsBoard.CardState.RESOLVED }

    @JvmStatic
    fun collectedCount(cards: List<SuperpairsBoard.Card>): Int = resolvedCount(cards)

    @JvmStatic
    fun resolvedPairs(cards: List<SuperpairsBoard.Card>): Int = resolvedCount(cards) / 2

    @JvmStatic
    fun totalPairs(cards: List<SuperpairsBoard.Card>): Int = cards.size / 2

    @JvmStatic
    fun waitingForReveal(awaitingRevealSlot: Int): Boolean = awaitingRevealSlot >= 0

    @JvmStatic
    fun empty(): SuperpairsBoard.View = SuperpairsBoard.View(emptyList(), -1, -1, emptyMap())
}
