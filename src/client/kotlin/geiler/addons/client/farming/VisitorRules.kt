package geiler.addons.client.farming

/**
 * Garden visitor chat and menus, as Hypixel words them: the arrival line is `NEW VISITOR <rank> <name> has arrived on your Garden!`.
 * The visitor menu reading follows SkyHanni's visitor listener and tooltip parser (slot 13 is the visitor, slot 29 `Accept Offer`).
 */
object VisitorRules {
    private val ARRIVAL = Regex("^NEW VISITOR [^ ].* has arrived on your Garden!$")
    private val FORMATTING = Regex("§.")
    private val HEADER = Regex("^Visitors:? ?[(]([0-9]{1,2})[)]$")
    private val AMOUNT = Regex("^(.+?)(?: x([0-9,]+))?$")
    private const val MAX_ITEMS = 12
    private const val MAX_VISITORS = 12

    /** One inventory stack: its clean name and count. */
    class Held(val name: String, val count: Int)

    @JvmStatic
    fun isArrivalLine(plain: String): Boolean = ARRIVAL.matches(plain.trim())

    /** The visitor menu's info item has four lore lines, the last `Offers Accepted: N`. */
    @JvmStatic
    fun isVisitorInfo(lore: List<String>): Boolean = lore.size == 4 && clean(lore[3]).startsWith("Offers Accepted: ")

    /** The `Items Required:` section of the accept item's lore: item name to amount (one when none is written). */
    @JvmStatic
    fun parseRequired(lore: List<String>): Map<String, Int> {
        val items = LinkedHashMap<String, Int>()
        var inSection = false
        for (raw in lore) {
            val line = clean(raw)
            if (!inSection) {
                inSection = line == "Items Required:"
                continue
            }
            if (line.isEmpty() || line.startsWith("Rewards:")) break
            val match = AMOUNT.find(line) ?: continue
            val amount = match.groupValues[2].replace(",", "").toIntOrNull() ?: 1
            if (items.size < MAX_ITEMS) items[match.groupValues[1].trim()] = amount
        }
        return items
    }

    /** The visitor names listed under the tab widget's `Visitors: (N)` header, or null when the widget is not there. */
    @JvmStatic
    fun visitorsInTab(lines: List<String>): List<String>? {
        val start = lines.indexOfFirst { HEADER.matches(clean(it)) }
        if (start < 0) return null
        val count = HEADER.find(clean(lines[start]))!!.groupValues[1].toInt().coerceAtMost(MAX_VISITORS)
        return lines.drop(start + 1).take(count).map { clean(it) }.filter { it.isNotEmpty() }
    }

    @JvmStatic
    fun have(held: List<Held>, name: String): Int = held.filter { it.name == name }.sumOf { it.count }

    /** One line of the shopping-list card; [buyItem] is the item a click on it searches for, with [buyAmount] to buy. */
    class Row(val text: String, val buyItem: String? = null, val buyAmount: Int = 0, val costText: String? = null)

    /** Coins as millions for the card: `1.23M`, one decimal from 10M, none from 100M, `<0.01M` below 5,000 coins. */
    @JvmStatic
    fun millions(coins: Double): String {
        val m = coins / 1_000_000.0
        return when {
            coins < 5_000.0 -> "<0.01M"
            m < 10.0 -> String.format(java.util.Locale.ROOT, "%.2fM", m)
            m < 100.0 -> String.format(java.util.Locale.ROOT, "%.1fM", m)
            else -> String.format(java.util.Locale.ROOT, "%.0fM", m)
        }
    }

    /** Visitor wording that differs from the Bazaar id (the Bazaar still says Nether Stalk, Pork, Huge Mushroom). */
    private val ID_ALIASES = mapOf(
        "ENCHANTED_NETHER_WART" to "ENCHANTED_NETHER_STALK", "MUTANT_NETHER_WART" to "MUTANT_NETHER_STALK", "ENCHANTED_COCOA_BEANS" to "ENCHANTED_COCOA",
        "ENCHANTED_HAY_BALE" to "ENCHANTED_HAY_BLOCK", "ENCHANTED_RED_MUSHROOM_BLOCK" to "ENCHANTED_HUGE_MUSHROOM_2",
        "ENCHANTED_BROWN_MUSHROOM_BLOCK" to "ENCHANTED_HUGE_MUSHROOM_1", "ENCHANTED_RAW_PORKCHOP" to "ENCHANTED_PORK",
        "ENCHANTED_COOKED_PORKCHOP" to "ENCHANTED_GRILLED_PORK", "ENCHANTED_RAW_MUTTON" to "ENCHANTED_MUTTON", "ENCHANTED_RAW_RABBIT" to "ENCHANTED_RABBIT",
        "ENCHANTED_RAW_COD" to "ENCHANTED_RAW_FISH", "ENCHANTED_COOKED_COD" to "ENCHANTED_COOKED_FISH", "ENCHANTED_MELON_SLICE" to "ENCHANTED_MELON",
    )
    private val TIERS = listOf("ENCHANTED_", "COMPACTED_", "MUTANT_", "POLISHED_")

    /** The Bazaar product id of a visitor's item: `Enchanted Melon` is `ENCHANTED_MELON`, with a few renamed ones. */
    @JvmStatic
    fun bazaarId(name: String): String {
        val id = name.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        return ID_ALIASES[id] ?: id
    }

    /** The plain crop behind a tier id (`ENCHANTED_MELON` is `MELON`); an id with no tier prefix is returned as it is. */
    @JvmStatic
    fun baseId(id: String): String = TIERS.firstOrNull { id.startsWith(it) }?.let { id.removePrefix(it) } ?: id

    /**
     * The shopping-list card: a header with the visitor and ready counts, then only what is still short to buy (no visitor
     * names). Stock is handed out in the order visitors were read, and only to
     * visitors who can be served in full, so one stack is never counted for two of them.
     */
    @JvmStatic
    @JvmOverloads
    fun rows(needs: Map<String, Map<String, Int>>, haveOf: (String) -> Int, unitPrice: (String) -> Double? = { null }): List<Row> {
        if (needs.isEmpty()) return emptyList()
        val stock = HashMap<String, Int>()
        fun left(item: String): Int = stock.getOrPut(item) { haveOf(item) }
        var ready = 0
        for (items in needs.values) {
            if (items.isNotEmpty() && items.all { (item, amount) -> left(item) >= amount }) {
                ready++
                for ((item, amount) in items) stock[item] = left(item) - amount
            }
        }
        val total = HashMap<String, Int>()
        for (items in needs.values) for ((item, amount) in items) total.merge(item, amount, Int::plus)
        val short = total.toSortedMap().mapNotNull { (item, amount) ->
            (amount - haveOf(item)).takeIf { it > 0 }?.let { item to it }
        }
        // Instabuy price of what is missing; an item the Bazaar does not list shows `?` and is left out of the total.
        val costs = short.associate { (item, missing) -> item to unitPrice(item)?.let { it * missing } }
        val footer = if (short.isEmpty()) listOf(Row("§aEverything in hand")) else
            short.map { (item, missing) -> Row("§f$item §e$missing", item, missing, costs[item]?.let { "§6${millions(it)}" } ?: "§8?") }
        val totalCost = costs.values.filterNotNull().sum()
        val header = Row("§7Visitor Shopping List §e${needs.size} §8(§a$ready ready§8)", costText = if (totalCost > 0.0) "§7Total §6${millions(totalCost)}" else null)
        return listOf(header) + footer
    }

    private fun clean(text: String): String = text.replace(FORMATTING, "").trim()
}
