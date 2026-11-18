package geiler.addons.client.hunting

import geiler.addons.client.location.SafariBiome
import java.util.TreeMap

/**
 * Tally for one Critter Safari run, from arrival until the player leaves.
 *
 * Everything derives from "who caught what, how many times". Your catches come from `CAPTURE!` lines and
 * partymates' from the `LOOT SHARE!` line that names them, so per-player and per-biome progress fall out
 * for free. Pure and single-threaded (client thread only), so it can be replayed offline.
 *
 * [uniqueOnly] counts a species as done at its first catch; otherwise a species with a spawn quota stays
 * outstanding until every spawn is caught. [unavailable] is the set of species the run can no longer produce.
 */
class SafariRun(val selfName: String, val startedAtMillis: Long) {
    var uniqueOnly = false
    var unavailable: Set<Critter> = emptySet()

    private val own = LinkedHashMap<Critter, Int>()
    private val shared = LinkedHashMap<Critter, MutableMap<String, Int>>()
    private val attempts = LinkedHashMap<Critter, Int>()
    private val failures = LinkedHashMap<Critter, Int>()
    private val shards = LinkedHashMap<Critter, Int>()
    private val sparklings = LinkedHashSet<Critter>()
    private var nearby: Map<Critter, Int> = emptyMap()

    fun record(event: CritterEvent) {
        val critter = event.critter ?: return
        when (event.type) {
            CritterEvent.Type.OWN_CATCH -> {
                own.merge(critter, 1, Int::plus)
                shards.merge(critter, event.shards, Int::plus)
            }
            CritterEvent.Type.SHARED_CATCH -> {
                val who = event.catcher ?: return
                shared.getOrPut(critter) { TreeMap() }.merge(who, 1, Int::plus)
                shards.merge(critter, event.shards, Int::plus)
            }
            CritterEvent.Type.ATTEMPT -> attempts.merge(critter, 1, Int::plus)
            CritterEvent.Type.FAILED -> failures.merge(critter, 1, Int::plus)
        }
        if (event.sparkling) sparklings.add(critter)
    }

    // --- your progress ---
    fun caughtByYou(critter: Critter): Boolean = own.getOrDefault(critter, 0) > 0
    fun ownUnique(): Int = own.size
    fun ownUnique(biome: SafariBiome): Int = own.keys.count { it.biome == biome }
    fun ownTotal(): Int = own.values.sum()

    // --- party progress (yours + loot share) ---
    fun partyCatches(critter: Critter): Int =
        own.getOrDefault(critter, 0) + shared[critter].orEmpty().values.sum()

    fun partyTotal(): Int = own.values.sum() + shared.values.sumOf { it.values.sum() }

    fun partyTotal(biome: SafariBiome): Int =
        own.filterKeys { it.biome == biome }.values.sum() +
            shared.filterKeys { it.biome == biome }.values.sumOf { it.values.sum() }

    /** How many catches finish a species: its quota unless [uniqueOnly], otherwise one. */
    fun required(critter: Critter): Int = if (uniqueOnly || !critter.hasQuota()) 1 else critter.quota

    /** Done by catching enough, or because the run can no longer produce it (so a biome cannot stall on it). */
    fun isComplete(critter: Critter): Boolean {
        if (isUnavailable(critter)) return true
        return partyCatches(critter) >= required(critter)
    }

    fun isUnavailable(critter: Critter): Boolean = critter in unavailable && partyCatches(critter) == 0

    fun remaining(critter: Critter): Int = (required(critter) - partyCatches(critter)).coerceAtLeast(0)

    fun partyUnique(): Int = CritterSpecies.all().count { isComplete(it) }
    fun partyUnique(biome: SafariBiome): Int = CritterSpecies.inBiome(biome).count { isComplete(it) }
    fun biomeComplete(biome: SafariBiome): Boolean = partyUnique(biome) == CritterSpecies.inBiome(biome).size
    fun dexComplete(): Boolean = partyUnique() == CritterSpecies.count()
    fun allCaughtExcept(exception: Critter): Boolean =
        CritterSpecies.all().all { it == exception || isComplete(it) }

    /** Species in [biome] the run is not finished with, in roster order. */
    fun missing(biome: SafariBiome): List<Critter> = CritterSpecies.inBiome(biome).filter { !isComplete(it) }

    // --- who caught what ---
    fun catchersOf(critter: Critter): List<String> =
        buildList {
            if (caughtByYou(critter)) add(selfName)
            addAll(shared[critter].orEmpty().keys)
        }

    /** Unique species per player per biome, local player first: the "who covers which biome" view. */
    fun uniquePerPlayer(): Map<String, Map<SafariBiome, Int>> {
        val result = LinkedHashMap<String, MutableMap<SafariBiome, Int>>()
        if (own.isNotEmpty()) {
            val mine = LinkedHashMap<SafariBiome, Int>()
            own.keys.forEach { mine.merge(it.biome, 1, Int::plus) }
            result[selfName] = mine
        }
        val others = TreeMap<String, MutableMap<SafariBiome, Int>>()
        for ((critter, byPlayer) in shared) {
            for (player in byPlayer.keys) {
                others.getOrPut(player) { LinkedHashMap() }.merge(critter.biome, 1, Int::plus)
            }
        }
        result.putAll(others)
        return result
    }

    // --- capsules, sparklings, nearby ---
    fun attempts(critter: Critter): Int = attempts.getOrDefault(critter, 0)
    fun failures(critter: Critter): Int = failures.getOrDefault(critter, 0)
    fun totalAttempts(): Int = attempts.values.sum()
    fun totalFailures(): Int = failures.values.sum()
    fun sparklings(): Set<Critter> = sparklings

    /** Replaced wholesale each scan: a critter that escapes a capsule returns as a new entity, so a running total would climb forever. */
    fun setNearby(counts: Map<Critter, Int>) {
        nearby = counts.toMap()
    }

    fun nearby(critter: Critter): Int = nearby.getOrDefault(critter, 0)

    // --- shards and time ---
    /** Shards that reached you (own catches and loot share), by species, for pricing. */
    fun shardCounts(): Map<Critter, Int> = shards.toMap()

    fun totalShards(): Int = shards.values.sum()

    /** Set when the run closes, so a finished run keeps its length instead of counting on forever. 0 while live. */
    var endedAtMillis = 0L

    /** Run length: up to [nowMillis] while live, and frozen at [endedAtMillis] once closed. */
    fun elapsedMillis(nowMillis: Long): Long =
        ((if (endedAtMillis > 0) endedAtMillis else nowMillis) - startedAtMillis).coerceAtLeast(0)
    fun isEmpty(): Boolean = own.isEmpty() && shared.isEmpty() && attempts.isEmpty()
}
