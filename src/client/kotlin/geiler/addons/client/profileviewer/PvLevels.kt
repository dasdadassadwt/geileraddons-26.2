package geiler.addons.client.profileviewer

import geiler.addons.client.dungeon.CataCalculator

/** Progress inside one Catacombs or class level: XP gained into it and XP the level takes in total. */
class DungeonProgress(val into: Long, val need: Long) {
    val left: Long get() = (need - into).coerceAtLeast(0L)
    val fraction: Double get() = if (need <= 0L) 1.0 else (into.toDouble() / need).coerceIn(0.0, 1.0)

    fun percentText(): String = String.format(java.util.Locale.ROOT, "%.1f%%", fraction * 100)

    companion object {
        /** Progress of [xp] toward the next level (overflow levels past 50 included, as the level shown). */
        @JvmStatic
        fun of(xp: Long): DungeonProgress {
            val level = CataCalculator.levelOf(xp).toInt()
            val start = CataCalculator.totalXpFor(level)
            return DungeonProgress(xp - start, CataCalculator.totalXpFor(level + 1) - start)
        }
    }
}

/** SkyBlock level tables for the Profile Viewer. Values match the public tables NEU and the wiki use. */
object PvLevels {
    /** XP needed for each skill level from 1 to 60. */
    private val SKILL = longArrayOf(
        50, 125, 200, 300, 500, 750, 1000, 1500, 2000, 3500, 5000, 7500, 10000, 15000, 20000, 30000, 50000, 75000,
        100000, 200000, 300000, 400000, 500000, 600000, 700000, 800000, 900000, 1000000, 1100000, 1200000, 1300000,
        1400000, 1500000, 1600000, 1700000, 1800000, 1900000, 2000000, 2100000, 2200000, 2300000, 2400000, 2500000,
        2600000, 2750000, 2900000, 3100000, 3400000, 3700000, 4000000, 4300000, 4600000, 4900000, 5200000, 5500000,
        5800000, 6100000, 6400000, 6700000, 7000000,
    )
    private val RUNECRAFTING = longArrayOf(
        50, 100, 125, 160, 200, 250, 315, 400, 500, 625, 785, 1000, 1250, 1600, 2000, 2465, 3125, 4000, 5000, 6200,
        7800, 9800, 12200, 15300, 19050,
    )
    private val SOCIAL = longArrayOf(
        50, 100, 150, 250, 500, 750, 1000, 1250, 1500, 2000, 2500, 3000, 3750, 4500, 6000, 8000, 10000, 12500, 15000,
        20000, 25000, 30000, 35000, 40000, 50000,
    )

    /** XP for each pet level step; a rarity starts at its offset and takes 99 steps. */
    private val PET = longArrayOf(
        100, 110, 120, 130, 145, 160, 175, 190, 210, 230, 250, 275, 300, 330, 360, 400, 440, 490, 540, 600, 660, 730,
        800, 880, 960, 1050, 1150, 1260, 1380, 1510, 1650, 1800, 1960, 2130, 2310, 2500, 2700, 2920, 3160, 3420, 3700,
        4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700, 9700, 10800, 12000, 13300, 14700, 16200, 17800, 19500,
        21300, 23200, 25200, 27400, 29800, 32400, 35200, 38200, 41400, 44800, 48400, 52200, 56200, 60400, 64800,
        69400, 74200, 79200, 84700, 90700, 97200, 104200, 111700, 119700, 128200, 137200, 146700, 156700, 167700,
        179700, 192700, 206700, 221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700,
        441700, 476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700, 1016700, 1101700, 1191700,
        1286700, 1386700, 1496700, 1616700, 1746700, 1886700,
    )
    private val PET_OFFSET = mapOf("COMMON" to 0, "UNCOMMON" to 6, "RARE" to 11, "EPIC" to 16, "LEGENDARY" to 20, "MYTHIC" to 20)

    private val SLAYER = mapOf(
        "zombie" to longArrayOf(5, 15, 200, 1000, 5000, 20000, 100000, 400000, 1000000),
        "spider" to longArrayOf(5, 25, 200, 1000, 5000, 20000, 100000, 400000, 1000000),
        "wolf" to longArrayOf(10, 30, 250, 1500, 5000, 20000, 100000, 400000, 1000000),
        "enderman" to longArrayOf(10, 30, 250, 1500, 5000, 20000, 100000, 400000, 1000000),
        "blaze" to longArrayOf(10, 30, 250, 1500, 5000, 20000, 100000, 400000, 1000000),
        "vampire" to longArrayOf(20, 75, 240, 840, 2400),
    )

    /** Cumulative Heart of the Mountain XP for tiers 1 to 10. */
    private val HOTM = longArrayOf(0, 3000, 12000, 37000, 97000, 197000, 347000, 557000, 847000, 1247000)

    /** Cumulative Heart of the Forest XP for tiers 1 to 8 (tier 8 came with SkyBlock 0.27; wiki and NEU `leveling.json`). */
    private val HOTF = longArrayOf(0, 3000, 12000, 37000, 97000, 197000, 347000, 547000)

    /** Tokens per HOTM tier 1 to 10, per Core of the Mountain level 1 to 10, per HOTF tier 1 to 8, per Center of the Forest 1 to 5. */
    private val HOTM_TOKENS = intArrayOf(1, 2, 2, 2, 2, 2, 3, 2, 2, 2)
    private val CORE_TOKENS = intArrayOf(1, 0, 0, 0, 1, 0, 1, 0, 0, 2)
    private val HOTF_TOKENS = intArrayOf(1, 2, 2, 2, 2, 2, 2, 2)
    private val CENTER_TOKENS = intArrayOf(1, 0, 0, 0, 1)

    /** Highest reachable level per skill (with every cap raise); skills not listed go to 60. */
    val SKILL_CAP = mapOf(
        "FISHING" to 50, "ALCHEMY" to 50, "CARPENTRY" to 50, "FORAGING" to 57, "HUNTING" to 50,
        "RUNECRAFTING" to 25, "SOCIAL" to 25,
    )

    /**
     * A profile's own cap for [skill]: farming is 50 plus the Jacob's farming level cap perk, taming 50 plus one per pet
     * type sacrificed (up to 10); other skills use [SKILL_CAP] or 60.
     */
    @JvmStatic
    fun cap(skill: String, sections: PvSections): Int = when (skill) {
        "FARMING" -> 50 + sections.farmingCapPerk.coerceIn(0, 10)
        "TAMING" -> 50 + sections.petsSacrificed.coerceIn(0, 10)
        else -> SKILL_CAP[skill] ?: 60
    }

    @JvmStatic
    fun hotf(xp: Long): Int = HOTF.count { xp >= it }

    @JvmStatic
    fun hotfMax(): Int = HOTF.size

    /** Fraction of the way to the next HOTM or HOTF tier; 1 at the top. */
    @JvmStatic
    fun treeProgress(xp: Long, forest: Boolean): Double {
        val table = if (forest) HOTF else HOTM
        val tier = table.count { xp >= it }
        if (tier >= table.size) return 1.0
        return (xp - table[tier - 1]).toDouble() / (table[tier] - table[tier - 1])
    }

    @JvmStatic
    fun hotmTokens(tier: Int, core: Int): Int = HOTM_TOKENS.take(tier).sum() + CORE_TOKENS.take(core).sum()

    @JvmStatic
    fun hotfTokens(tier: Int, center: Int): Int = HOTF_TOKENS.take(tier).sum() + CENTER_TOKENS.take(center).sum()

    @JvmStatic
    fun skill(xp: Long, cap: Int): Double = progress(SKILL, xp, cap)

    @JvmStatic
    fun runecrafting(xp: Long): Double = progress(RUNECRAFTING, xp, 25)

    @JvmStatic
    fun social(xp: Long): Double = progress(SOCIAL, xp, 25)

    /** Level of [skill] (`COMBAT`, `RUNECRAFTING`...) with its own table and cap. */
    @JvmStatic
    fun skillByName(skill: String, xp: Long): Double = when (skill) {
        "RUNECRAFTING" -> runecrafting(xp)
        "SOCIAL" -> social(xp)
        else -> skill(xp, SKILL_CAP[skill] ?: 60)
    }

    @JvmStatic
    fun slayer(boss: String, xp: Long): Int = SLAYER[boss]?.count { xp >= it } ?: 0

    @JvmStatic
    fun slayerMax(boss: String): Int = SLAYER[boss]?.size ?: 0

    /** Whole pet level, 1 to 100. Golden Dragon past 100 is not modelled. */
    @JvmStatic
    fun pet(rarity: String, xp: Long): Int {
        val offset = PET_OFFSET[rarity] ?: 0
        var left = xp
        var level = 1
        for (i in offset until offset + 99) {
            if (left < PET[i]) break
            left -= PET[i]
            level++
        }
        return level
    }

    /** SkyBlock level colour as Hypixel shows it: a new colour every 40 levels, gray below 40, dark red from 480. */
    @JvmStatic
    fun sbLevelColor(level: Int): String = SB_LEVEL_COLORS[(level / 40).coerceIn(0, SB_LEVEL_COLORS.size - 1)]

    private val SB_LEVEL_COLORS = listOf("§7", "§f", "§e", "§a", "§2", "§b", "§3", "§9", "§d", "§5", "§6", "§c", "§4")

    /** Total XP to reach whole [level] of a skill table (`SKILL`, `RUNECRAFTING`, `SOCIAL`), for hovers. */
    @JvmStatic
    fun skillXpFor(skill: String, level: Int): Long {
        val table = when (skill) { "RUNECRAFTING" -> RUNECRAFTING; "SOCIAL" -> SOCIAL; else -> SKILL }
        return table.take(level.coerceIn(0, table.size)).sum()
    }

    @JvmStatic
    fun hotm(xp: Long): Int = HOTM.count { xp >= it }

    @JvmStatic
    fun catacombs(xp: Long): Double = CataCalculator.levelOf(xp)

    /** Level with the fraction toward the next one; a full table stays at [cap]. */
    private fun progress(table: LongArray, xp: Long, cap: Int): Double {
        var left = xp
        val max = minOf(cap, table.size)
        for (level in 0 until max) {
            val need = table[level]
            if (left < need) return level + left.toDouble() / need
            left -= need
        }
        return max.toDouble()
    }
}
