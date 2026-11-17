package geiler.addons.client.mining

/** Pure text rules for mining items and the Commissions menu, kept apart from Minecraft so they run offline. */
object MiningItemText {
    /** Drill fuel: the tank now and its size, from a lore line such as `Fuel: 2,450/3k`. */
    class Fuel(val current: Int, val max: Int) {
        fun fraction(): Double = if (max <= 0) 0.0 else (current.toDouble() / max).coerceIn(0.0, 1.0)
    }

    private val FUEL = Regex("^Fuel:\\s*([\\d,]+)\\s*/\\s*([\\d,.]+)(k?)$", RegexOption.IGNORE_CASE)
    private val MENU_PROGRESS = Regex("^Progress:\\s*([\\d.,]+)%$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun drillFuel(lore: List<String>): Fuel? {
        for (line in lore) {
            val match = FUEL.matchEntire(line.trim()) ?: continue
            val current = match.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            val sizeText = match.groupValues[2].replace(",", "")
            val size = sizeText.toDoubleOrNull() ?: continue
            val max = if (match.groupValues[3].isNotEmpty()) Math.round(size * 1000).toInt() else size.toInt()
            if (max <= 0) continue
            return Fuel(current.coerceAtMost(max), max)
        }
        return null
    }

    /** A Commissions-menu item: its name plus lore, or null when it is not a commission (a filler pane, a button). */
    @JvmStatic
    fun commissionFromMenu(name: String, lore: List<String>): MiningTabText.Commission? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        if (lore.any { it.trim().equals("COMPLETED", true) }) return MiningTabText.Commission(clean, 1.0, true, "DONE")
        for (line in lore) {
            val match = MENU_PROGRESS.matchEntire(line.trim()) ?: continue
            val percent = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            val complete = percent >= 100.0
            return MiningTabText.Commission(clean, if (complete) 1.0 else percent / 100.0, complete, match.groupValues[1] + "%")
        }
        return null
    }
}
