package geiler.addons.client.itemlist

/** The Item List's inventory search: every word (or "quoted phrase") must appear in an item's name or lore, ignoring case. */
object SlotSearch {
    private val TERM = Regex("\"([^\"]*)\"|(\\S+)")

    @JvmStatic
    fun terms(query: String): List<String> =
        TERM.findAll(query.lowercase()).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { it.isNotEmpty() }.toList()

    /** True for a blank query, so callers decide whether blank means "off". */
    @JvmStatic
    fun matches(query: String, name: String, lore: List<String>): Boolean = matches(terms(query), name, lore)

    fun matches(terms: List<String>, name: String, lore: List<String>): Boolean {
        if (terms.isEmpty()) return true
        val text = (listOf(name) + lore).joinToString("\n").lowercase()
        return terms.all { it in text }
    }
}
