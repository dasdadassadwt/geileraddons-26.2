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
    class Row(val text: String, val buyItem: String? = null, val buyAmount: Int = 0)

    /**
     * The shopping-list card: a header, each visitor (green when everything they want is in hand, yellow when part of it is)
     * with their items, then what is still short to buy. Stock is handed out in the order visitors were read, and only to
     * visitors who can be served in full, so one stack is never counted for two of them.
     */
    @JvmStatic
    fun rows(needs: Map<String, Map<String, Int>>, haveOf: (String) -> Int): List<Row> {
        if (needs.isEmpty()) return emptyList()
        val stock = HashMap<String, Int>()
        fun left(item: String): Int = stock.getOrPut(item) { haveOf(item) }
        val body = ArrayList<Row>()
        var ready = 0
        for ((visitor, items) in needs) {
            val satisfied = items.count { (item, amount) -> left(item) >= amount }
            val served = items.isNotEmpty() && satisfied == items.size
            body += when {
                served -> Row("§a✔ $visitor §8Ready")
                satisfied > 0 -> Row("§e● $visitor §8$satisfied/${items.size}")
                else -> Row("§7○ $visitor")
            }
            for ((item, amount) in items.toSortedMap()) {
                val have = left(item)
                body += Row("§8  §7$item §${if (have >= amount) "a" else "e"}$have§7/§f$amount")
            }
            if (served) {
                ready++
                for ((item, amount) in items) stock[item] = left(item) - amount
            }
        }
        val total = HashMap<String, Int>()
        for (items in needs.values) for ((item, amount) in items) total.merge(item, amount, Int::plus)
        val short = total.toSortedMap().mapNotNull { (item, amount) ->
            (amount - haveOf(item)).takeIf { it > 0 }?.let { item to it }
        }
        val footer = if (short.isEmpty()) listOf(Row("§aEverything in hand")) else
            listOf(Row("§7To buy §8(click to search the Bazaar)")) + short.map { (item, missing) -> Row("§8  §f$item §e$missing", item, missing) }
        val header = Row("§7Visitor Shopping List §e${needs.size} §8(§a$ready ready§8)")
        return listOf(header) + body + footer
    }

    private fun clean(text: String): String = text.replace(FORMATTING, "").trim()
}
