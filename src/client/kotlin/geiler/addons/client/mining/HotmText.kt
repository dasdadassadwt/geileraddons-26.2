package geiler.addons.client.mining

/** Reads one Heart of the Mountain menu item and builds the extra tooltip lines. Pure text, so it runs offline. */
object HotmText {
    /** What a perk's slot says: its level and state. [level] is 0 while locked; [enabled] is null for perks with no switch. */
    class Reading(val perk: HotmPerks.Perk, val level: Int, val unlocked: Boolean, val enabled: Boolean?) {
        fun maxed(): Boolean = unlocked && level >= perk.maxLevel
    }

    private val LEVEL = Regex("^Level (\\d+)(?:\\s*/\\s*(\\d+))?.*$")
    private val TOKENS = Regex("^Token of the Mountain:\\s*(\\d+)$")

    @JvmStatic
    fun read(name: String, lore: List<String>): Reading? {
        val perk = HotmPerks.byName(name) ?: return null
        val locked = lore.isNotEmpty() && lore.last().trim().let {
            it.startsWith("Requires") || it.endsWith("Mountain!") || it == "Click to unlock!"
        } && perk.name != "Core of the Mountain"
        if (locked) return Reading(perk, 0, false, null)
        val level = lore.firstNotNullOfOrNull { LEVEL.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() } ?: perk.maxLevel
        val enabled = when {
            lore.any { val t = it.trim(); t == "ENABLED" || t == "SELECTED" } -> true
            lore.any { val t = it.trim(); t == "DISABLED" || t == "Click to select!" } -> false
            else -> null
        }
        return Reading(perk, level.coerceAtMost(perk.maxLevel), true, enabled)
    }

    @JvmStatic
    fun tokens(lore: List<String>): Int? = lore.firstNotNullOfOrNull { TOKENS.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }

    /** Which extra tooltip lines to add; each maps to a setting. */
    class Options(val spent: Boolean, val toMax: Boolean, val nextLevels: Boolean, val have: Boolean, val levelsWanted: Int)

    /** The lines to add under a perk's cost: powder spent, powder to max, the next N levels, and what the player holds. */
    @JvmStatic
    fun tooltipLines(reading: Reading, options: Options, holding: Long?): List<String> {
        val perk = reading.perk
        if (!perk.hasCost() || !reading.unlocked) return emptyList()
        val out = ArrayList<String>(4)
        val color = powderColor(perk.powder)
        if (options.spent) {
            val spent = HotmPerks.totalCost(perk, reading.level)
            val total = HotmPerks.totalCost(perk, perk.maxLevel)
            val share = if (total == 0L) 0.0 else spent * 100.0 / total
            out.add("§7${perk.powder} spent: §e${MiningHudFormat.count(spent)}§7/§e${MiningHudFormat.count(total)} §7(§e${"%.1f".format(java.util.Locale.ROOT, share)}%§7)")
        }
        if (reading.maxed()) return out
        if (options.nextLevels) {
            val upgrade = HotmPerks.next(perk, reading.level, options.levelsWanted)
            val noun = if (upgrade.levels == 1) "level" else "levels"
            out.add("§7${perk.powder} for ${upgrade.levels} $noun: $color${MiningHudFormat.count(upgrade.cost)}")
        }
        if (options.toMax) out.add("§7${perk.powder} to max: $color${MiningHudFormat.count(HotmPerks.toMax(perk, reading.level))}")
        if (options.have && holding != null) out.add("§7You have $color${MiningHudFormat.count(holding)} ${perk.powder} Powder")
        return out
    }

    private fun powderColor(powder: String?): String = when (powder) {
        "Mithril" -> "§2"
        "Gemstone" -> "§d"
        else -> "§b"
    }

    /** Slot highlight color (ARGB): green enabled, red disabled, dark gray locked, none for always-on perks. */
    @JvmStatic
    fun highlight(reading: Reading): Int = when {
        !reading.unlocked -> 0x66404040
        reading.enabled == true -> 0x6655FF55
        reading.enabled == false -> 0x66FF5555
        else -> 0
    }

    /** Text drawn on a slot: the level, or empty when locked. */
    @JvmStatic
    fun slotText(reading: Reading): String = if (!reading.unlocked || reading.level <= 0 || reading.perk.maxLevel <= 1) "" else reading.level.toString()
}
