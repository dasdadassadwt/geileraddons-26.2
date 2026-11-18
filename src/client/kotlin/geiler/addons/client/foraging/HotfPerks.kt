package geiler.addons.client.foraging

import geiler.addons.client.mining.HotmPerks.Perk
import geiler.addons.client.mining.HotmText
import java.util.Locale

/**
 * The Heart of the Forest perks and what leveling them costs. The cost of reaching level L is `(L + 1) ^ power`, rounded
 * down per level (Daily Wishes is linear); the tables follow SkyHanni's `HotfData` (LGPL-2.1, behavior only). Which
 * currency a perk uses (Forest or Desert Whispers) is read from its own cost line, since the curves do not depend on it.
 * Abilities and the 2-level perks have no cost. Costs reuse the `HotmPerks` math.
 */
object HotfPerks {
    private const val WHISPERS = "Whispers"

    private fun paid(name: String, max: Int, power: Double) = Perk(name, max, 1.0, power, WHISPERS)
    private fun free(name: String, max: Int) = Perk(name, max, 0.0, 0.0, null)

    private val PERKS: List<Perk> = listOf(
        paid("Sweep", 50, 3.0),
        paid("Foraging Fortune", 50, 3.105),
        paid("Strength Boost", 50, 3.1),
        free("Damage Boost", 2),
        paid("Speed Boost", 50, 3.1),
        free("Axe Toss", 2),
        paid("Luck of the Forest", 40, 3.07),
        Perk("Daily Wishes", 100, 0.0, 0.0, WHISPERS) { level -> 200 + level * 18 },
        paid("250 Gifts", 40, 3.07),
        free("Lottery", 2),
        free("Foraging Madness", 2),
        paid("Deep Waters", 50, 2.9),
        paid("Efficient Forager", 100, 2.6),
        paid("Collector", 50, 2.9),
        free("Early Bird", 2),
        free("Precision Cutting", 2),
        free("Monster Hunter", 2),
        free("Tree Whisperer", 2),
        free("Homing Axe", 2),
        paid("Forest Strength", 50, 3.4),
        free("Beekeeper", 2),
        paid("Hunter's Luck", 50, 3.2),
        paid("Galatea's Might", 50, 3.2),
        paid("Essence Fortune", 50, 3.2),
        paid("Forest Speed", 50, 3.4),
        free("Maniac Slicer", 2),
        paid("Half Empty", 25, 4.1),
        paid("Ricochet", 10, 5.5),
        paid("Half Full", 25, 4.1),
        free(CORE, 5),
    )

    private val BY_NAME: Map<String, Perk> = PERKS.associateBy { it.name.lowercase(Locale.ROOT) }
    private val WHISPERS_LINE = Regex("^((?:Forest|Desert) Whispers):\\s*([\\d,]+)$")
    private val COST_LINE = Regex("^[\\d,]+ ((?:Forest|Desert) Whispers)$")

    @JvmStatic
    fun byName(name: String?): Perk? = if (name == null) null else BY_NAME[name.trim().lowercase(Locale.ROOT)]

    /** One Heart of the Forest menu item as a perk reading, or null for anything that is not a known perk. */
    @JvmStatic
    fun read(name: String, lore: List<String>): HotmText.Reading? =
        byName(name)?.let { HotmText.read(it, lore, "Forest!", CORE) }

    /** The whispers held by currency name ("Forest Whispers", "Desert Whispers"), from the heart item's lore. */
    @JvmStatic
    fun whispers(lore: List<String>): Map<String, Long> = buildMap {
        for (line in lore) {
            val match = WHISPERS_LINE.matchEntire(line.trim()) ?: continue
            match.groupValues[2].replace(",", "").toLongOrNull()?.let { put(match.groupValues[1], it) }
        }
    }

    /** The currency a perk's cost line names ("1,234 Desert Whispers"), or null for any other line. */
    @JvmStatic
    fun currency(costLine: String): String? = COST_LINE.matchEntire(costLine.trim())?.groupValues?.get(1)

    const val CORE = "Center of the Forest"
}
