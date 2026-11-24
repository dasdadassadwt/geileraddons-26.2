package geiler.addons.client.carnival

import java.util.EnumMap
import kotlin.random.Random

/**
 * Monte Carlo estimate of what each hidden Fruit Digging cell holds, ported from SkyHanni's `FruitDiggingBelief`
 * (LGPL-2.1): the remaining bag is dealt onto the hidden cells many times and only deals that agree with every clue
 * are counted.
 */
class FruitDiggingBelief(
    private val openCells: List<Int>,
    private val pool: List<CarnivalFruit>,
    private val clues: List<Clue>,
    private val random: Random = Random.Default,
) {
    init {
        require(openCells.size == pool.size) { "openCells (${openCells.size}) and pool (${pool.size}) must be the same size" }
    }

    /** How many sampled boards agreed with the clues. */
    var samples: Int = 0
        private set

    sealed interface Clue

    class BombCount(val cells: List<Int>, val bombs: Int) : Clue

    class FruitFloor(val cells: List<Int>, val minRank: Int, val named: CarnivalFruit, val satisfiedByKnown: Boolean) : Clue

    class FruitCeil(val cells: List<Int>, val maxRank: Int, val named: CarnivalFruit, val satisfiedByKnown: Boolean) : Clue

    class NoFruitNearby(val cells: List<Int>) : Clue

    class MustBeFruit(val cell: Int) : Clue

    fun estimate(): Map<Int, Map<CarnivalFruit, Double>> {
        if (openCells.isEmpty()) {
            samples = 0
            return emptyMap()
        }
        if (clues.isEmpty()) {
            samples = TARGET_SAMPLES
            val marginal = baseRateDistribution()
            return openCells.associateWith { marginal }
        }

        val slotOf = HashMap<Int, Int>(openCells.size * 2)
        openCells.forEachIndexed { slot, cell -> slotOf[cell] = slot }
        val compiled = clues.map { it to slotsFor(it, slotOf) }

        val deal = pool.toTypedArray()
        val hits = Array(openCells.size) { EnumMap<CarnivalFruit, Int>(CarnivalFruit::class.java) }
        var accepted = 0
        var attempts = 0
        while (accepted < TARGET_SAMPLES && attempts < MAX_ATTEMPTS) {
            attempts++
            shuffle(deal)
            if (compiled.all { (clue, slots) -> satisfies(clue, slots, deal) }) {
                accepted++
                for (slot in deal.indices) hits[slot].merge(deal[slot], 1, Int::plus)
            }
        }
        samples = accepted
        if (accepted == 0) {
            val marginal = baseRateDistribution()
            return openCells.associateWith { marginal }
        }
        return buildMap {
            openCells.forEachIndexed { slot, cell -> put(cell, hits[slot].mapValues { (_, n) -> n.toDouble() / accepted }) }
        }
    }

    private fun baseRateDistribution(): Map<CarnivalFruit, Double> {
        val total = pool.size.toDouble()
        return pool.groupingBy { it }.eachCount().mapValues { (_, n) -> n / total }
    }

    private fun slotsFor(clue: Clue, slotOf: Map<Int, Int>): IntArray = when (clue) {
        is BombCount -> clue.cells.mapNotNull { slotOf[it] }.toIntArray()
        is FruitFloor -> clue.cells.mapNotNull { slotOf[it] }.toIntArray()
        is FruitCeil -> clue.cells.mapNotNull { slotOf[it] }.toIntArray()
        is NoFruitNearby -> clue.cells.mapNotNull { slotOf[it] }.toIntArray()
        is MustBeFruit -> slotOf[clue.cell]?.let { intArrayOf(it) } ?: IntArray(0)
    }

    private fun satisfies(clue: Clue, slots: IntArray, deal: Array<CarnivalFruit>): Boolean = when (clue) {
        is BombCount -> slots.count { deal[it] == CarnivalFruit.BOMB } == clue.bombs
        is NoFruitNearby -> slots.none { deal[it].isEdible }
        is FruitFloor -> {
            var found = clue.satisfiedByKnown
            var ok = true
            for (slot in slots) {
                val fruit = deal[slot]
                if (!fruit.isEdible) continue
                if (dowsingRank(fruit) < clue.minRank) { ok = false; break }
                if (fruit == clue.named) found = true
            }
            ok && found
        }
        is FruitCeil -> {
            var found = clue.satisfiedByKnown
            var ok = true
            for (slot in slots) {
                val fruit = deal[slot]
                if (!fruit.isEdible) continue
                if (dowsingRank(fruit) > clue.maxRank) { ok = false; break }
                if (fruit == clue.named) found = true
            }
            ok && found
        }
        is MustBeFruit -> slots.isEmpty() || deal[slots[0]].isEdible
    }

    private fun shuffle(array: Array<CarnivalFruit>) {
        for (i in array.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = array[i]
            array[i] = array[j]
            array[j] = tmp
        }
    }

    companion object {
        /** Treasure and anchor dowsing order fruit by points, with apples lowest. */
        @JvmStatic
        fun dowsingRank(fruit: CarnivalFruit): Int = if (fruit == CarnivalFruit.APPLE) 0 else fruit.points

        private const val TARGET_SAMPLES = 500
        private const val MAX_ATTEMPTS = 20_000
    }
}
