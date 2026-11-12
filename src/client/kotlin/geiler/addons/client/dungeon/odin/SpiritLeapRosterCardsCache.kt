package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.odin.SpiritLeapRoster.Card
import geiler.addons.client.dungeon.odin.SpiritLeapRoster.Member
import geiler.addons.client.dungeon.odin.SpiritLeapRoster.Sort
import java.util.regex.Pattern

/** Keeps the Java-facing cache adapter thin while preserving its immutable card reuse behavior. */
class SpiritLeapRosterCardsCacheLogic {
    private var cachedMembers: List<Member>? = null
    private var cachedSort: Sort? = null
    private var cachedCustomOrder = ""
    private var cachedCards: List<Card>? = null

    @JvmSuppressWildcards
    fun cards(roster: SpiritLeapRoster, sort: Sort?, customOrder: String?): List<Card> {
        val currentMembers = roster.observed()
        val currentCustomOrder = if (sort == Sort.CUSTOM && customOrder != null) customOrder else ""
        cachedCards?.let { cards ->
            if (cachedMembers != null && cachedMembers == currentMembers
                && cachedSort == sort && cachedCustomOrder == currentCustomOrder) return cards
        }

        val parsedOrder = if (sort == Sort.CUSTOM) {
            Pattern.compile(",").split(currentCustomOrder).toList()
        } else {
            emptyList()
        }
        val cards = roster.cards(sort, parsedOrder)
        cachedCards = cards
        cachedMembers = currentMembers
        cachedSort = sort
        cachedCustomOrder = currentCustomOrder
        return cards
    }

    fun clear() {
        cachedMembers = null
        cachedSort = null
        cachedCustomOrder = ""
        cachedCards = null
    }
}
