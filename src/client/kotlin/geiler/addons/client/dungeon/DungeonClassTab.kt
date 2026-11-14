package geiler.addons.client.dungeon

import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.floor

/** Class and class level of dungeon players, read from the tab list, and the Mage cooldown multiplier built on them. */
object DungeonClassTab {
    /** One parsed dungeon tab row; [level] is -1 when the row carries no class level. */
    data class Row(val name: String, val dungeonClass: DungeonClass?, val level: Int)

    private val ROW = Pattern.compile("^\\[(\\d+)] (?:\\[\\w+] )*(\\w+) .*?\\((\\w+)(?: (\\w+))*\\)$")

    @JvmStatic
    fun parse(line: String?): Row? {
        if (line == null) return null
        val matcher = ROW.matcher(line.trim { it <= ' ' })
        if (!matcher.matches()) return null
        val name = matcher.group(2) ?: return null
        val clazz = DungeonClass.parse(matcher.group(3)) ?: return null
        val level = matcher.group(4)?.let(::romanToInt) ?: -1
        return Row(name, clazz, level)
    }

    @JvmStatic
    fun parseAll(lines: Iterable<String?>): List<Row> = lines.mapNotNull(::parse)

    /** Roman numerals up to the class-level range (0-50, written I..L); 0 for anything unreadable. */
    @JvmStatic
    fun romanToInt(roman: String): Int {
        var total = 0
        var previous = 0
        for (ch in roman.uppercase(Locale.ROOT).reversed()) {
            val value = when (ch) {
                'I' -> 1
                'V' -> 5
                'X' -> 10
                'L' -> 50
                'C' -> 100
                else -> return 0
            }
            if (value < previous) total -= value else total += value
            previous = value
        }
        return total
    }

    /**
     * The factor the Mage class applies to ability cooldowns: `1 - 0.25 - floor(level / 2) / 100`, with the level
     * part doubled when the player is the only Mage. 1.0 for every other class.
     */
    @JvmStatic
    fun mageCooldownMultiplier(dungeonClass: DungeonClass?, level: Int, mageCount: Int): Double {
        if (dungeonClass != DungeonClass.MAGE) return 1.0
        val levelPart = floor(maxOf(level, 0) / 2.0) / 100.0 * (if (mageCount == 1) 2 else 1)
        return 1.0 - 0.25 - levelPart
    }

    /** Multiplier for [localName] from the parsed [rows]; 1.0 when the player's row is missing. */
    @JvmStatic
    fun mageCooldownMultiplier(rows: List<Row>, localName: String?): Double {
        val local = rows.firstOrNull { it.name.equals(localName, ignoreCase = true) } ?: return 1.0
        return mageCooldownMultiplier(local.dungeonClass, local.level, rows.count { it.dungeonClass == DungeonClass.MAGE })
    }
}
