package geiler.addons.client.dungeon

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Catacombs XP per S+ run and the runs needed to reach a level. Base XP, completion caps, boost values and the formula
 * follow adjectils' dungeon calculator (MIT, Copyright (c) 2024 adjective_noun); the same numbers are used by other
 * open-source profile viewers. Hypixel publishes no formula, so every result is an estimate.
 */
object CataCalculator {
    enum class Floor(val baseXp: Int, private val master: Boolean, val completionCap: Int) {
        F1(110, false, 75), F2(220, false, 75), F3(560, false, 75), F4(1_420, false, 75),
        F5(2_400, false, 75), F6(4_880, false, 50), F7(28_000, false, 25),
        M1(15_000, true, 25), M2(20_000, true, 25), M3(35_000, true, 25), M4(55_000, true, 25),
        M5(70_000, true, 25), M6(100_000, true, 25), M7(300_000, true, 25),
        ;

        fun master(): Boolean = master
        fun tier(): Int = name.substring(1).toInt()
    }

    /** [mayor] is the mayor's Catacombs XP bonus, for example 0.5 for Derpy. */
    data class Boosts(val expertRing: Boolean, val hecatombLevel: Int, val explorerLevel: Int, val mayor: Double)

    /** What the profile says. A null boost means the profile does not show it, which is not the same as not having it. */
    data class Profile(
        val experience: Long,
        val completionsByFloor: Map<Floor, Int>,
        val dailyRunsLeft: Int,
        val expertRing: Boolean?,
        val hecatomb: Int?,
        val explorerLevel: Int?,
    ) {
        fun completions(floor: Floor): Int = completionsByFloor[floor] ?: 0
    }

    private const val EXPERT_RING = 0.10
    private const val EXPLORER_PER_LEVEL = 0.01
    private const val DAILY_MULTIPLIER = 1.4
    private const val OVERFLOW_PER_LEVEL = 200_000_000L
    private const val MAX_SIMULATED = 100_000

    private fun hecatomb(level: Int): Double = if (level <= 0) 0.0 else 0.004 + 0.0016 * min(level, 10)

    @JvmStatic
    fun xpPerRun(floor: Floor, completions: Int, boosts: Boosts, daily: Boolean): Long {
        val counted = completions.coerceIn(0, floor.completionCap)
        val hecatomb = hecatomb(boosts.hecatombLevel)
        val step = (if (boosts.expertRing) 0.024 else 0.022) + hecatomb / 50
        var multiplier = 0.95 + (if (boosts.expertRing) EXPERT_RING else 0.0) + hecatomb +
            boosts.explorerLevel.coerceIn(0, 10) * EXPLORER_PER_LEVEL + counted * step
        if (boosts.mayor > 0) multiplier += boosts.mayor + counted / 100.0
        val xp = floor.baseXp * multiplier * if (daily) DAILY_MULTIPLIER else 1.0
        // The small epsilon keeps float error (504000.0000001) from rounding up a whole XP.
        return ceil(xp - 1e-6).toLong()
    }

    /** Runs of [floor] for [needed] XP; the first [dailyRuns] of them get today's daily bonus. */
    @JvmStatic
    fun runs(floor: Floor, completions: Int, needed: Long, boosts: Boosts, dailyRuns: Int): Long {
        var left = needed
        var runs = 0L
        while (left > 0 && runs < MAX_SIMULATED) {
            val done = completions + runs.toInt()
            val daily = runs < dailyRuns
            val xp = xpPerRun(floor, done, boosts, daily)
            // Past the cap and the daily runs every run is the same, so the rest is one division.
            if (!daily && done >= floor.completionCap) return runs + (left + xp - 1) / xp
            left -= xp
            runs++
        }
        return runs
    }

    /** Total Catacombs XP at [level]; levels past 50 are the overflow levels. */
    @JvmStatic
    fun totalXpFor(level: Int): Long =
        if (level <= 50) DungeonStatsServiceCore.catacombsXpAt(level)
        else DungeonStatsServiceCore.catacombsXpAt(50) + (level - 50) * OVERFLOW_PER_LEVEL

    /** The level including the fraction toward the next one. */
    @JvmStatic
    fun levelOf(experience: Long): Double {
        val fifty = totalXpFor(50)
        if (experience >= fifty) return 50 + (experience - fifty).toDouble() / OVERFLOW_PER_LEVEL
        var level = 0
        while (experience >= totalXpFor(level + 1)) level++
        val start = totalXpFor(level)
        return level + (experience - start).toDouble() / (totalXpFor(level + 1) - start)
    }

    /** Derpy's key or the perk that carries its XP bonus; Aura's value is the reference calculator's. */
    @JvmStatic
    fun mayorBonus(mayorKey: String, perkNames: List<String>): Double = when {
        mayorKey.equals("aura", true) -> 0.59
        mayorKey.equals("derpy", true) || perkNames.any { it.equals("MOAR SKILLZ", true) } -> 0.5
        else -> 0.0
    }

    private fun short(xp: Long): String = when {
        xp >= 1_000_000 -> String.format(Locale.ROOT, "%.2fM", xp / 1_000_000.0)
        xp >= 10_000 -> "${floor(xp / 1_000.0).toLong()}k"
        else -> xp.toString()
    }

    /**
     * The chat answer for `/ga cata`. [mayor] is the mayor's bonus, or null when the election data could not be read.
     */
    @JvmStatic
    fun report(name: String, profile: Profile, target: Int, mayor: Double?, mayorName: String): List<String> {
        val needed = totalXpFor(target) - profile.experience
        val current = String.format(Locale.ROOT, "%.2f", floor(levelOf(profile.experience) * 100) / 100)
        if (needed <= 0) return listOf("§6[Cata] §f$name is §e$current§f, already at level $target.")
        val boosts = Boosts(
            profile.expertRing == true, profile.hecatomb ?: 0, profile.explorerLevel ?: 0, mayor ?: 0.0,
        )
        val lines = ArrayList<String>()
        lines += "§6[Cata] §f$name: §e$current §7to §e$target §7needs §b${String.format(Locale.US, "%,d", needed)} §7XP"
        lines += "§7S+ runs on each floor (XP of the next run):"
        for (row in Floor.entries.chunked(4)) {
            lines += row.joinToString("§8 | ") { floor ->
                val done = profile.completions(floor)
                val runs = runs(floor, done, needed, boosts, profile.dailyRunsLeft)
                val next = xpPerRun(floor, done, boosts, false)
                (if (floor.master()) "§c" else "§a") + floor.name + " §f" +
                    String.format(Locale.US, "%,d", runs) + " §8(" + short(next) + ")"
            }
        }
        val applied = ArrayList<String>()
        val missing = ArrayList<String>()
        val unknown = ArrayList<String>()
        when (profile.expertRing) {
            true -> applied += "Expert Ring"
            false -> missing += "Expert Ring"
            null -> unknown += "Expert Ring (inventory API off)"
        }
        when (val level = profile.hecatomb) {
            null -> unknown += "Hecatomb (inventory API off)"
            0 -> missing += "Hecatomb"
            else -> applied += "Hecatomb $level (assumes you wear it)"
        }
        when (val level = profile.explorerLevel) {
            null -> unknown += "Catacombs Explorer shard (not in profile data)"
            0 -> missing += "Catacombs Explorer shard"
            else -> applied += "Catacombs Explorer $level"
        }
        when {
            mayor == null -> unknown += "mayor (election data unavailable)"
            mayor > 0 -> applied += "$mayorName +${(mayor * 100).toInt()}% (every run, even after the term ends)"
        }
        if (profile.dailyRunsLeft > 0) applied += "daily +40% on ${profile.dailyRunsLeft} run(s) today only"
        unknown += "global dungeon XP boost"
        lines += "§aApplied: §7" + applied.joinToString(", ").ifEmpty { "no boosts" }
        if (missing.isNotEmpty()) lines += "§eNot owned: §7" + missing.joinToString(", ")
        lines += "§cNot detected, not applied: §7" + unknown.joinToString(", ")
        return lines
    }
}
