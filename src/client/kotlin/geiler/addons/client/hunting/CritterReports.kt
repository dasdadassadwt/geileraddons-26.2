package geiler.addons.client.hunting

import geiler.addons.client.location.SafariBiome

/** Plain-text views of a run, shared by the HUD panels, the commands and party posts. Pure, so they are checked offline. */
object CritterReports {
    /** `Gemzie x2` for a species with several spawns left, otherwise just the name. */
    @JvmStatic
    fun missingName(run: SafariRun, critter: Critter): String {
        val left = run.remaining(critter)
        return if (left > 1) "${critter.name} x$left" else critter.name
    }

    /** One line per biome with something still missing; empty when the dex is done. */
    @JvmStatic
    fun missingLines(run: SafariRun): List<String> =
        SafariBiome.values().mapNotNull { biome ->
            val missing = run.missing(biome)
            if (missing.isEmpty()) null else "${biome.displayName()}: ${missing.joinToString(", ") { missingName(run, it) }}"
        }

    /** Unique species per player per biome: who is covering which biome. */
    @JvmStatic
    fun playerLines(run: SafariRun): List<String> =
        run.uniquePerPlayer().map { (player, byBiome) ->
            val parts = SafariBiome.values().joinToString(" ") { "${it.displayName().first()}${byBiome[it] ?: 0}" }
            "$player: $parts"
        }

    /** `Party 23/37 (You 15) | 41 catches | 12m 05s`. */
    @JvmStatic
    fun summaryLine(run: SafariRun, elapsedMillis: Long): String =
        "Party ${run.partyUnique()}/${CritterSpecies.count()} (You ${run.ownUnique()}) | ${run.partyTotal()} catches | ${ShardValue.duration(elapsedMillis)}"

    /** Value text for one mode, e.g. `1.23M` or `-` while no price is known. */
    @JvmStatic
    fun valueText(coins: Double, haveData: Boolean): String = if (haveData) ShardValue.coins(coins) else "-"
}
