package geiler.addons.client.dungeon.f7

/** Reads the dungeon blessings Hypixel lists in the tab-list footer, after Odin's Blessing Display. */
object BlessingRules {
    enum class Blessing(@JvmField val label: String, @JvmField val code: String) {
        POWER("Power", "§4"),
        TIME("Time", "§5"),
        STONE("Stone", "§7"),
        LIFE("Life", "§c"),
        WISDOM("Wisdom", "§b"),
    }

    private val LEGACY_CODE = Regex("(?i)§[0-9A-FK-ORS]")
    private val BLESSING = Regex("Blessing of (Power|Time|Stone|Life|Wisdom) ([IVXLCDM]+)")

    @JvmStatic
    fun roman(text: String): Int {
        if (text.isEmpty() || !text.all { it in "IVXLCDM" }) return 0
        fun value(c: Char) = when (c) { 'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50; 'C' -> 100; 'D' -> 500; else -> 1000 }
        var total = 0
        for (i in text.indices) {
            val v = value(text[i])
            total += if (i + 1 < text.length && v < value(text[i + 1])) -v else v
        }
        return total
    }

    /** Blessing levels found in [footer]; colour codes are ignored. */
    @JvmStatic
    fun parseFooter(footer: String?): Map<Blessing, Int> {
        if (footer.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<Blessing, Int>()
        for (match in BLESSING.findAll(LEGACY_CODE.replace(footer, ""))) {
            val blessing = Blessing.entries.first { it.label == match.groupValues[1] }
            val level = roman(match.groupValues[2])
            if (level > 0) result[blessing] = level
        }
        return result
    }

    /** HUD lines for the enabled, active blessings in a fixed order. Order of switches: power, time, stone, life, wisdom. */
    @JvmStatic
    fun lines(levels: Map<Blessing, Int>, power: Boolean, time: Boolean, stone: Boolean, life: Boolean, wisdom: Boolean): List<String> {
        val enabled = mapOf(
            Blessing.POWER to power, Blessing.TIME to time, Blessing.STONE to stone,
            Blessing.LIFE to life, Blessing.WISDOM to wisdom,
        )
        return Blessing.entries.mapNotNull { blessing ->
            val level = levels[blessing] ?: return@mapNotNull null
            if (enabled[blessing] != true || level <= 0) null else "${blessing.code}${blessing.label}: §a$level§r"
        }
    }
}
