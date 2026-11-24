package geiler.addons.client.collection

/**
 * Hypixel's sack summary, sent about every 30 s while items go into sacks: the chat line
 * `[Sacks] +128 items. (Last 30s.)` carries the per-item list only in its hover text, one row per item such as
 * ` +64 Enchanted Hay Bale (Farming Sack)`. Row pattern from SkyHanni's `SackApi` (LGPL-2.1). Long lists end in
 * "and N other items", which cannot be attributed and is reported as [Result.truncated].
 */
object SackChangeParser {
    class Change(val delta: Int, val itemName: String)
    class Result(val changes: List<Change>, val truncated: Boolean)

    private val HEADER = Regex("^\\[Sacks] [+-][\\d,]+ items?(?:, [+-][\\d,]+ items?)?\\. \\(Last \\d+s\\.\\)$")
    private val ROW = Regex("^ *(?<delta>[+-][\\d,]+) (?<item>.+) \\((?<sacks>.+)\\)$")
    private val OTHER = Regex("other items", RegexOption.IGNORE_CASE)

    /** Whether a plain chat line is the sack summary. */
    @JvmStatic
    fun isSackMessage(plain: String): Boolean = HEADER.matches(plain.trim())

    /** Item deltas in a sack summary's hover text (plain text, one row per line). */
    @JvmStatic
    fun parseHover(hover: String): Result {
        val changes = ArrayList<Change>()
        var truncated = false
        for (line in hover.lineSequence()) {
            if (OTHER.containsMatchIn(line)) {
                truncated = true
                continue
            }
            val match = ROW.matchEntire(line.trimEnd()) ?: continue
            val delta = match.groups["delta"]!!.value.replace(",", "").replace("+", "").toIntOrNull() ?: continue
            if (delta == 0) continue
            changes.add(Change(delta, match.groups["item"]!!.value.trim()))
        }
        return Result(changes, truncated)
    }
}
