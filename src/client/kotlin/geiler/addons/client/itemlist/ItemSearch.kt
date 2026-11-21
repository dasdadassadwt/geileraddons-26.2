package geiler.addons.client.itemlist

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Item search over precomputed lowercase text. Terms are ANDed. Syntax: plain words, `"phrase"`, `/regex/` (a bad regex
 * is ignored), `rarity:`/`r:`, `type:`/`t:`, `%CATEGORY`, `is:craftable|bazaar|forgeable|npc|pet|dungeon|soulbound|vanilla|mob`
 * and stat compares such as `damage>100`. Name hits rank above id hits above lore hits.
 */
class ItemSearch(
    private val entries: List<Entry>,
    index: RecipeIndex,
    private val aliases: Map<String, String> = DEFAULT_ALIASES,
    private val inBazaar: (String) -> Boolean = { false },
) {
    private class Row(
        val entry: Entry,
        val name: String,
        val id: String,
        val lore: String,
        val stats: Map<String, Double>,
        val flags: Set<String>,
        val acronym: String,
    )

    private val rows: List<Row> = entries.map { e ->
        val loreLines = e.lore.map { ItemListData.plain(it) }
        val name = e.plainName.lowercase()
        val flags = HashSet<String>()
        if (e.category == ItemListData.CAT_PET) flags.add("pet")
        if (e.category == ItemListData.CAT_MOB) flags.add("mob")
        if (e.category == ItemListData.CAT_VANILLA) flags.add("vanilla")
        if (e.type.contains("DUNGEON") || loreLines.any { it.contains("DUNGEON", ignoreCase = true) }) flags.add("dungeon")
        if (loreLines.any { it.contains("soulbound", ignoreCase = true) }) flags.add("soulbound")
        val recipes = index.recipesFor(e.id)
        if (recipes.any { it.type == ItemListData.CRAFTING }) flags.add("craftable")
        if (recipes.any { it.type == ItemListData.FORGE }) flags.add("forgeable")
        if (recipes.any { it.type == ItemListData.NPC_SHOP }) flags.add("npc")
        Row(e, name, e.id.lowercase().replace('_', ' '), loreLines.joinToString("\n").lowercase(), stats(loreLines), flags, acronym(name))
    }

    private val byAlias: Map<String, List<Row>> = HashMap<String, MutableList<Row>>().also { map ->
        for (row in rows) if (row.acronym.length >= 2) map.getOrPut(row.acronym) { ArrayList() }.add(row)
        for ((alias, id) in aliases) rows.firstOrNull { it.entry.id == id }?.let { map.getOrPut(alias) { ArrayList() }.add(it) }
    }

    private sealed interface Term {
        class Word(val text: String) : Term
        class Phrase(val text: String) : Term
        class Regex(val pattern: Pattern) : Term
        class Rarity(val value: String) : Term
        class Type(val value: String) : Term
        class Category(val value: String) : Term
        class Flag(val value: String) : Term
        class Stat(val key: String, val op: String, val number: Double) : Term
    }

    /** Entries matching [query] in [category] (`ALL` for any), best first. */
    fun search(query: String, category: String = ItemListData.CAT_ALL): List<Entry> {
        val terms = parse(query)
        val scored = ArrayList<Pair<Int, Row>>()
        for (row in rows) {
            if (category != ItemListData.CAT_ALL && row.entry.category != category) continue
            var score = 0
            var ok = true
            for (term in terms) {
                val s = match(term, row)
                if (s < 0) {
                    ok = false
                    break
                }
                score = maxOf(score, s)
            }
            if (ok) scored.add(score to row)
        }
        val order = if (terms.isEmpty()) compareBy<Pair<Int, Row>>({ it.second.name }) else compareBy({ it.first }, { it.second.name.length }, { it.second.name })
        return scored.sortedWith(order).map { it.second.entry }
    }

    /** The full name to suggest for a plain [query] (ghost text), or an empty string. */
    fun suggest(query: String): String {
        val q = query.trim().lowercase()
        if (q.isEmpty() || q.any { it in "\":%/<>=" }) return ""
        val byName = rows.filter { it.name.startsWith(q) && it.name != q }.minByOrNull { it.name.length }
        if (byName != null) return byName.entry.plainName
        val target = aliases[q]?.let { id -> rows.firstOrNull { it.entry.id == id } } ?: byAlias[q]?.firstOrNull()
        return target?.entry?.plainName?.takeIf { it.lowercase() != q } ?: ""
    }

    private fun match(term: Term, row: Row): Int = when (term) {
        is Term.Word -> word(term.text, row)
        is Term.Phrase -> if (row.name.contains(term.text)) 0 else if (row.lore.contains(term.text)) 3 else -1
        is Term.Regex -> if (term.pattern.matcher(row.name).find() || term.pattern.matcher(row.entry.id).find()) 0
        else if (term.pattern.matcher(row.lore).find()) 3 else -1
        is Term.Rarity -> if (row.entry.rarity.lowercase().startsWith(term.value)) 0 else -1
        is Term.Type -> if (row.entry.type.lowercase().contains(term.value)) 0 else -1
        is Term.Category -> if (row.entry.category.lowercase().startsWith(term.value)) 0 else -1
        is Term.Flag -> if (term.value in row.flags || (term.value == "bazaar" && inBazaar(row.entry.id))) 0 else -1
        is Term.Stat -> row.stats.entries.firstOrNull { it.key.startsWith(term.key) }?.let { if (compare(it.value, term.op, term.number)) 0 else -1 } ?: -1
    }

    private fun word(text: String, row: Row): Int = when {
        row.name.startsWith(text) -> 0
        row.name.contains(" $text") -> 1
        row.name.contains(text) -> 1
        byAlias[text]?.any { it === row } == true -> 1
        row.id.contains(text) -> 2
        row.lore.contains(text) -> 3
        else -> -1
    }

    private fun compare(value: Double, op: String, number: Double): Boolean = when (op) {
        ">" -> value > number
        "<" -> value < number
        ">=" -> value >= number
        "<=" -> value <= number
        else -> value == number
    }

    private fun parse(query: String): List<Term> {
        val terms = ArrayList<Term>()
        var i = 0
        val q = query
        while (i < q.length) {
            if (q[i] == ' ') {
                i++
                continue
            }
            if (q[i] == '"') {
                val end = q.indexOf('"', i + 1).let { if (it < 0) q.length else it }
                val phrase = q.substring(i + 1, end).trim().lowercase()
                if (phrase.isNotEmpty()) terms.add(Term.Phrase(phrase))
                i = end + 1
                continue
            }
            if (q[i] == '/') {
                val end = q.indexOf('/', i + 1)
                if (end > i + 1) {
                    try {
                        terms.add(Term.Regex(Pattern.compile(q.substring(i + 1, end), Pattern.CASE_INSENSITIVE)))
                    } catch (_: PatternSyntaxException) {
                        // A bad regex filters nothing.
                    }
                    i = end + 1
                    continue
                }
            }
            var end = q.indexOf(' ', i)
            if (end < 0) end = q.length
            token(q.substring(i, end).lowercase())?.let { terms.add(it) }
            i = end
        }
        return terms
    }

    private fun token(t: String): Term? {
        STAT.matchEntire(t)?.let { return Term.Stat(it.groupValues[1].replace('_', ' '), it.groupValues[2], it.groupValues[3].toDouble()) }
        val colon = t.indexOf(':')
        if (colon > 0) {
            val value = t.substring(colon + 1)
            if (value.isEmpty()) return null
            when (t.substring(0, colon)) {
                "rarity", "r" -> return Term.Rarity(value)
                "type", "t" -> return Term.Type(value)
                "is" -> return Term.Flag(value)
            }
        }
        if (t.startsWith("%") && t.length > 1) return Term.Category(t.substring(1))
        return Term.Word(t)
    }

    companion object {
        private val STAT = Regex("([a-z_]+)(>=|<=|>|<|=)(-?\\d+(?:\\.\\d+)?)")
        private val STAT_LINE = Regex("^([A-Za-z][A-Za-z ]*?): [+-]?([\\d,]+(?:\\.\\d+)?)")

        val DEFAULT_ALIASES: Map<String, String> = mapOf(
            "hype" to "HYPERION", "term" to "TERMINATOR", "juju" to "JUJU_SHORTBOW", "valk" to "VALKYRIE", "gdrag" to "GOLDEN_DRAGON;4",
        )

        /** First letters of each word of [name]: "aspect of the end" gives "aote". */
        @JvmStatic
        fun acronym(name: String): String =
            name.lowercase().split(' ', '-', '\'').filter { it.isNotEmpty() && it[0].isLetterOrDigit() }.map { it[0] }.joinToString("")

        /** "Damage: +260 (+30)" style lore lines as lowercase name to number. */
        @JvmStatic
        fun stats(lore: List<String>): Map<String, Double> {
            val map = LinkedHashMap<String, Double>()
            for (line in lore) {
                val m = STAT_LINE.find(line) ?: continue
                val value = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: continue
                map.putIfAbsent(m.groupValues[1].lowercase(), value)
            }
            return map
        }
    }
}
