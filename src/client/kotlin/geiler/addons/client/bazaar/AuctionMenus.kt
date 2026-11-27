package geiler.addons.client.bazaar

import java.util.Locale

/**
 * Reads Hypixel's Auction House menus for the Fancy Auction House skin. Slot roles follow Skyblocker's
 * `AuctionBrowserScreen` / `AuctionViewScreen` (behavior only): categories in column 0, listings in a 6 by 4 block,
 * bottom row 46 previous, 47 reset, 48 search, 49 back, 50 sort, 51 rarity, 52 BIN filter, 53 next. Pure.
 */
object AuctionMenus {
    const val PREVIOUS = 46
    const val RESET = 47
    const val SEARCH = 48
    const val BACK = 49
    const val SORT = 50
    const val RARITY = 51
    const val TYPE = 52
    const val NEXT = 53
    const val VIEW_ITEM = 13
    const val VIEW_EDIT_BID = 31

    class Listing(val price: Long?, val bin: Boolean, val ends: String?)

    enum class ViewAction(val label: String) {
        BUY("Buy"), CANT_AFFORD("Can't afford"), TOP_BID("You have the top bid"), CANCEL("Cancel auction"),
        COLLECT("Collect"), GRACE("Grace period"), PROFILE("Switch profile to collect"),
    }

    private val COINS = Regex("([\\d,]+(?:\\.\\d+)?)")
    private val NUMBER = Regex("(\\d+)")

    @JvmStatic
    fun isBrowser(title: String): Boolean = title == "Auctions Browser" || title.startsWith("Auctions: ")

    @JvmStatic
    fun isView(title: String): Boolean = title == "BIN Auction View" || title == "Auction View"

    @JvmStatic
    fun isBin(title: String): Boolean = title.lowercase(Locale.ROOT).contains("bin")

    @JvmStatic
    fun isListingSlot(slot: Int, rows: Int): Boolean = slot > 9 && slot < (rows - 1) * 9 && slot % 9 in 2..7

    @JvmStatic
    fun listingSlots(rows: Int): List<Int> = (0 until rows * 9).filter { isListingSlot(it, rows) }

    @JvmStatic
    fun listing(lore: List<String>): Listing {
        var bin: Long? = null
        var start: Long? = null
        var top: Long? = null
        var ends: String? = null
        for (raw in lore) {
            val line = raw.trim()
            val lower = line.lowercase(Locale.ROOT)
            when {
                lower.startsWith("buy it now:") -> bin = coins(line)
                lower.startsWith("starting bid:") -> start = coins(line)
                lower.startsWith("top bid:") -> top = coins(line)
                lower.startsWith("ends in:") -> ends = line.substringAfter(':').trim()
                lower.startsWith("ended") || lower == "status: expired!" -> ends = "Ended"
            }
        }
        return Listing(bin ?: top ?: start, bin != null, ends)
    }

    /** The option marked `▶` in a sort, rarity or type button's lore, or "". */
    @JvmStatic
    fun option(lore: List<String>): String = lore.firstOrNull { it.trim().startsWith("▶") }?.trim()?.removePrefix("▶")?.trim() ?: ""

    @JvmStatic
    fun selectedCategory(lore: List<String>): Boolean = lore.any { it.lowercase(Locale.ROOT).contains("currently") }

    /** Page number named in a page arrow's lore, or 0. */
    @JvmStatic
    fun page(lore: List<String>): Int = lore.firstOrNull { it.contains("Page", ignoreCase = true) }?.let { NUMBER.find(it)?.value?.toIntOrNull() } ?: 0

    /** What the action slot of an auction view offers, from its item and name; null when it is not an action. */
    @JvmStatic
    fun viewAction(itemId: String, name: String): ViewAction? {
        val lower = name.lowercase(Locale.ROOT)
        if (lower.contains("collect auction")) return ViewAction.COLLECT
        return when {
            itemId == "minecraft:gold_nugget" -> ViewAction.BUY
            itemId == "minecraft:poisonous_potato" -> ViewAction.CANT_AFFORD
            itemId == "minecraft:gold_block" -> ViewAction.TOP_BID
            itemId == "minecraft:red_terracotta" -> ViewAction.CANCEL
            itemId == "minecraft:name_tag" -> ViewAction.PROFILE
            itemId.endsWith("_bed") -> ViewAction.GRACE
            else -> null
        }
    }

    private val TIME_PART = Regex("""(\d+)\s*([dhms])""")
    private val PAGE_OF = Regex("""\(?(\d+)\s*/\s*(\d+)\)?""")

    /**
     * A listing's "Ends in" text as one short decimal ("05h 55m 12s" is "5.9h", "13d" stays "13d"), so it fits a card.
     * Text without time parts comes back unchanged.
     */
    @JvmStatic
    fun shortTime(text: String): String {
        var seconds = 0L
        var found = false
        for (m in TIME_PART.findAll(text.lowercase(Locale.ROOT))) {
            found = true
            val n = m.groupValues[1].toLong()
            seconds += when (m.groupValues[2]) { "d" -> n * 86_400; "h" -> n * 3_600; "m" -> n * 60; else -> n }
        }
        if (!found) return text
        fun one(v: Double, unit: String): String {
            val r = Math.round(v * 10) / 10.0
            return (if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.ROOT, "%.1f", r)) + unit
        }
        return when {
            seconds >= 86_400 -> one(seconds / 86_400.0, "d")
            seconds >= 3_600 -> one(seconds / 3_600.0, "h")
            seconds >= 60 -> one(seconds / 60.0, "m")
            else -> "${seconds}s"
        }
    }

    /** "(2/57)" or "Page 2/57" in a page arrow's lore as current to total, or null when the lore has no total. */
    @JvmStatic
    fun pageOf(lore: List<String>): Pair<Int, Int>? {
        for (line in lore) {
            val m = PAGE_OF.find(line) ?: continue
            val current = m.groupValues[1].toInt()
            val total = m.groupValues[2].toInt()
            if (total in 1..10_000 && current in 1..total) return current to total
        }
        return null
    }

    private fun coins(line: String): Long? = COINS.find(line.substringAfter(':'))?.value?.replace(",", "")?.toDoubleOrNull()?.toLong()
}
