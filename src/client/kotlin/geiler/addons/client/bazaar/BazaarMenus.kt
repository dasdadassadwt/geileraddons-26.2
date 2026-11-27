package geiler.addons.client.bazaar

import java.util.Locale

/**
 * Which Bazaar menus the Fancy Bazaar skin draws: the category and product-group pages (`Bazaar ➜ ...`) and a
 * product's page (the one with Buy Instantly / Sell Instantly). Amount, order and confirm pages stay vanilla. Pure.
 */
object BazaarMenus {
    @JvmStatic
    fun isBazaar(title: String, itemNames: List<String>): Boolean =
        title.startsWith("Bazaar ➜") || (title.contains("➜") && itemNames.any { it == "Buy Instantly" } && itemNames.any { it == "Sell Instantly" })

    /** Lore lines that carry a price, in order. */
    @JvmStatic
    fun priceLines(lore: List<String>): List<String> = lore.map { it.trim() }.filter {
        val lower = it.lowercase(Locale.ROOT)
        lower.contains("price:") || lower.contains("price per unit") || (lower.contains("coins") && lower.contains(":"))
    }

    /** Auction House and Bazaar menus without a page of their own, drawn by the generic Fancy skin. */
    private val GENERIC_TITLES = setOf(
        "Auction House", "Co-op Auction House", "Manage Auctions", "Manage Bids", "Your Bids", "View Bids",
        "Auction Stats", "Create Auction", "Create BIN Auction", "Bazaar Orders", "Co-op Bazaar Orders",
    )

    /** Titles Hypixel reuses outside the AH and Bazaar too; owned only right after an AH or Bazaar menu. */
    private val FOLLOW_UP = listOf("are you sure", "how many do you want", "how much do you want", "confirm", "auction duration")

    /**
     * Whether the generic Fancy skin draws this menu: a known AH/Bazaar title, a menu whose "Go Back" leads to the
     * Bazaar or Auction House, or a follow-up page (amount, confirm) opened right after one ([recentContext]).
     */
    @JvmStatic
    fun isGeneric(title: String, backLore: String?, recentContext: Boolean): Boolean {
        if (title in GENERIC_TITLES) return true
        val back = backLore?.lowercase(Locale.ROOT).orEmpty()
        if (back.startsWith("to bazaar") || back.contains("auction house") || back.startsWith("to auctions")) return true
        val lower = title.lowercase(Locale.ROOT)
        return recentContext && FOLLOW_UP.any { lower.startsWith(it) }
    }

    @JvmStatic
    fun isFiller(itemId: String, name: String): Boolean = itemId.endsWith("stained_glass_pane") && name.isBlank()
}
