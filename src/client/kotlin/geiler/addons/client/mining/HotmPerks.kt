package geiler.addons.client.mining

import java.util.Locale

/**
 * The Heart of the Mountain perks and what leveling them costs. The cost of reaching level L is
 * `(L + offset) ^ power` powder, rounded down per level; the tables follow SkyHanni's `HotmData` (LGPL-2.1).
 * Abilities and the one-level perks have no powder cost.
 */
object HotmPerks {
    class Perk(val name: String, val maxLevel: Int, val offset: Double, val power: Double, val powder: String?) {
        fun hasCost(): Boolean = powder != null
    }

    private val PERKS: List<Perk> = listOf(
        Perk("Mining Speed", 50, 1.0, 3.0, "Mithril"),
        Perk("Mining Fortune", 50, 1.0, 3.05, "Mithril"),
        Perk("Titanium Insanium", 50, 1.0, 3.1, "Mithril"),
        Perk("Luck of the Cave", 45, 1.0, 3.07, "Mithril"),
        Perk("Efficient Miner", 100, 1.0, 2.6, "Mithril"),
        Perk("Quick Forge", 20, 1.0, 3.2, "Mithril"),
        Perk("Old-School", 20, 1.0, 4.0, "Gemstone"),
        Perk("Professional", 140, 1.0, 2.3, "Gemstone"),
        Perk("Mole", 200, 1.0, 2.17883, "Gemstone"),
        Perk("Gem Lover", 20, 1.0, 4.0, "Gemstone"),
        Perk("Seasoned Mineman", 100, 1.0, 2.3, "Gemstone"),
        Perk("Fortunate Mineman", 50, 1.0, 3.2, "Gemstone"),
        Perk("Blockhead", 20, 1.0, 4.0, "Gemstone"),
        Perk("Keep It Cool", 50, 1.0, 3.07, "Gemstone"),
        Perk("Lonesome Miner", 45, 1.0, 3.07, "Gemstone"),
        Perk("Great Explorer", 20, 1.0, 4.0, "Gemstone"),
        Perk("Powder Buff", 50, 1.0, 3.2, "Gemstone"),
        Perk("Speedy Mineman", 50, 1.0, 3.2, "Gemstone"),
        Perk("Subterranean Fisher", 40, 1.0, 3.07, "Gemstone"),
        Perk("Sky Mall", 1, 0.0, 0.0, null),
        Perk("Precision Mining", 1, 0.0, 0.0, null),
        Perk("Front Loaded", 1, 0.0, 0.0, null),
        Perk("Daily Grind", 1, 0.0, 0.0, null),
        Perk("Daily Powder", 1, 0.0, 0.0, null),
        Perk("Pickobulus", 3, 0.0, 0.0, null),
        Perk("Mining Speed Boost", 3, 0.0, 0.0, null),
        Perk("Maniac Miner", 3, 0.0, 0.0, null),
        Perk("Sheer Force", 3, 0.0, 0.0, null),
        Perk("Tunnel Vision", 3, 0.0, 0.0, null),
        Perk("Core of the Mountain", 10, 0.0, 0.0, null),
        Perk("No Stone Unturned", 50, 1.0, 3.05, "Glacite"),
        Perk("Strong Arm", 100, 1.0, 2.3, "Glacite"),
        Perk("Steady Hand", 100, 1.0, 2.6, "Glacite"),
        Perk("Warm Heart", 50, 1.0, 3.1, "Glacite"),
        Perk("Surveyor", 20, 1.0, 4.0, "Glacite"),
        Perk("Metal Head", 20, 1.0, 4.0, "Glacite"),
        Perk("Rags to Riches", 50, 1.0, 3.05, "Glacite"),
        Perk("Eager Adventurer", 100, 1.0, 2.3, "Glacite"),
        Perk("Crystalline", 50, 1.0, 3.3, "Glacite"),
        Perk("Gifts from the Departed", 100, 1.0, 2.45, "Glacite"),
        Perk("Mining Master", 10, 7.0, 5.0, "Glacite"),
        Perk("Dead Man's Chest", 50, 1.0, 3.2, "Glacite"),
        Perk("Vanguard Seeker", 50, 1.0, 3.1, "Glacite"),
        Perk("Mineshaft Mayhem", 1, 0.0, 0.0, null),
        Perk("Gemstone Infusion", 1, 0.0, 0.0, null),
        Perk("Miner's Blessing", 1, 0.0, 0.0, null),
    )

    private val BY_NAME: Map<String, Perk> = PERKS.associateBy { it.name.lowercase(Locale.ROOT) }

    @JvmStatic
    fun byName(name: String?): Perk? = if (name == null) null else BY_NAME[name.trim().lowercase(Locale.ROOT)]

    /** Powder to buy [level] (level 1 is free). Zero for perks without a powder cost. */
    @JvmStatic
    fun cost(perk: Perk, level: Int): Int =
        if (!perk.hasCost() || level < 2) 0 else Math.pow(level + perk.offset, perk.power).toInt()

    /** Powder spent to be at [level]: every purchase from level 2 up. */
    @JvmStatic
    fun totalCost(perk: Perk, level: Int): Long {
        var sum = 0L
        for (l in 2..level.coerceAtMost(perk.maxLevel)) sum += cost(perk, l)
        return sum
    }

    /** Levels left to buy from [level], and the powder they cost, capped at [wanted] levels and the perk's maximum. */
    @JvmStatic
    fun next(perk: Perk, level: Int, wanted: Int): Upgrade {
        val levels = (perk.maxLevel - level).coerceIn(0, wanted)
        return Upgrade(levels, totalCost(perk, level + levels) - totalCost(perk, level))
    }

    class Upgrade(val levels: Int, val cost: Long)

    /** Powder still needed to max a perk at [level]. */
    @JvmStatic
    fun toMax(perk: Perk, level: Int): Long = totalCost(perk, perk.maxLevel) - totalCost(perk, level)
}
