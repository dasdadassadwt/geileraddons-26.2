package geiler.addons.client.misc

/** Quiver Display rules, after Odin's QuiverDisplay: the arrow count is the `Arrows Remaining` lore line of the quiver item. */
object QuiverRules {
    private val REMAINING = Regex("^Arrows Remaining: ([0-9,]+)$")
    private val FORMATTING = Regex("§.")

    /** The item ids Hypixel uses for the quiver item. */
    @JvmStatic
    fun isArrowItem(itemId: String): Boolean = itemId == "minecraft:arrow" || itemId == "minecraft:feather"

    /** The count from the first `Arrows Remaining: N` line, or null when there is none or it is not an int. */
    @JvmStatic
    fun arrowsRemaining(lore: List<String>): Int? {
        for (line in lore) {
            val match = REMAINING.find(line.replace(FORMATTING, "").trim()) ?: continue
            return match.groupValues[1].replace(",", "").toIntOrNull()
        }
        return null
    }

    @JvmStatic
    fun text(name: String, count: Int): String = "§f$name §a%,d".format(java.util.Locale.ROOT, count)
}
