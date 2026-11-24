package geiler.addons.client.carnival

import kotlin.random.Random

/**
 * Picks the next Fruit Digging dig, ported from SkyHanni's `FruitDiggingSolver` (LGPL-2.1): read the board, estimate
 * the hidden cells with [FruitDiggingBelief], then score each diggable cell by expected points, bomb/rum risk, the
 * multiplier it sets up and the information it reveals.
 */
class FruitDiggingSolver(private val size: Int = GRID, private val random: Random = Random.Default) {

    data class CellInput(
        val content: CarnivalFruit?,
        val diggable: Boolean,
        val ghost: Boolean,
        val mustFruit: Boolean,
        val minesCount: Int?,
        val treasure: CarnivalFruit?,
        val anchor: CarnivalFruit?,
    )

    data class Recommendation(
        val targetRow: Int,
        val targetCol: Int,
        val shovel: String,
        val expectedPoints: Double,
        val pBomb: Double,
        val pRum: Double,
        val samples: Int,
    )

    // `size` is read into `n` first: inside buildList, a bare `size` is the list's own size.
    private val neighborsOf: Array<IntArray> = Array(size * size) { id ->
        val n = size
        val row = id / n
        val col = id % n
        buildList {
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (r in 0 until n && c in 0 until n) add(r * n + c)
            }
        }.toIntArray()
    }

    private val allContents = CarnivalFruit.entries.filter { it.isEdible || it == CarnivalFruit.BOMB || it == CarnivalFruit.RUM }

    fun recommend(grid: Array<Array<CellInput>>, digsUsed: Int, nextMultiplier: Double, coconutProtection: Boolean): Recommendation? =
        Advisor(grid, digsUsed, nextMultiplier, coconutProtection).recommend()

    private inner class Advisor(
        grid: Array<Array<CellInput>>,
        private val digsUsed: Int,
        private val nextMultiplier: Double,
        private val coconutProtection: Boolean,
    ) {
        private val knownContent = arrayOfNulls<CarnivalFruit>(size * size)
        private val diggable = BooleanArray(size * size)
        private val ghost = BooleanArray(size * size)
        private val candidates = ArrayList<Int>()
        private val openCells = ArrayList<Int>()
        private val openOnBoard = HashSet<Int>()
        private var applesCollected = 0
        private var cherriesCollected = 0
        private val beliefs: Map<Int, Map<CarnivalFruit, Double>>
        private val sampleCount: Int

        init {
            for (row in 0 until size) for (col in 0 until size) {
                val id = row * size + col
                val cell = grid[row][col]
                val content = cell.content?.takeIf { it != CarnivalFruit.UNKNOWN && it != CarnivalFruit.NO_FRUIT }
                knownContent[id] = content
                diggable[id] = cell.diggable
                ghost[id] = cell.ghost && content == null
                if (cell.diggable) {
                    candidates.add(id)
                    if (content == null) {
                        openCells.add(id)
                        openOnBoard.add(id)
                    }
                } else if (content == null) {
                    openCells.add(id)
                }
                if (content != null && !cell.diggable) {
                    if (content == CarnivalFruit.APPLE) applesCollected++
                    if (content == CarnivalFruit.CHERRY) cherriesCollected++
                }
            }
            val pool = remainingBag()
            // A board read out of step with the bag (a missed reveal) cannot be dealt; fall back to no beliefs.
            if (pool.size == openCells.size) {
                val belief = FruitDiggingBelief(openCells, pool, clues(grid), random)
                beliefs = belief.estimate()
                sampleCount = belief.samples
            } else {
                beliefs = emptyMap()
                sampleCount = 0
            }
        }

        private fun remainingBag(): List<CarnivalFruit> {
            val counts = HashMap<CarnivalFruit, Int>()
            for (content in allContents) counts[content] = content.count
            for (known in knownContent) known?.let { counts[it] = (counts[it] ?: 0) - 1 }
            return buildList { for ((content, n) in counts) repeat(n.coerceAtLeast(0)) { add(content) } }
        }

        // SkyHanni's gatherClues loops `0 until size` inside buildList, which is the empty list's size, so its clues
        // never reach the belief. The grid size is read first here.
        private fun clues(grid: Array<Array<CellInput>>): List<FruitDiggingBelief.Clue> {
            val n = size
            return buildList { cluesInto(grid, n, this) }
        }

        private fun cluesInto(grid: Array<Array<CellInput>>, n: Int, out: MutableList<FruitDiggingBelief.Clue>) = with(out) {
            for (row in 0 until n) for (col in 0 until n) {
                val id = row * n + col
                val cell = grid[row][col]
                val hidden = neighborsOf[id].filter { it in openOnBoard }
                cell.minesCount?.let { add(FruitDiggingBelief.BombCount(hidden, it)) }
                cell.treasure?.let { add(orderClue(id, hidden, it, false)) }
                cell.anchor?.let { add(orderClue(id, hidden, it, true)) }
                if (ghost[id] || cell.mustFruit) add(FruitDiggingBelief.MustBeFruit(id))
            }
        }

        private fun orderClue(center: Int, hidden: List<Int>, named: CarnivalFruit, anchor: Boolean): FruitDiggingBelief.Clue {
            if (named == CarnivalFruit.NO_FRUIT) return FruitDiggingBelief.NoFruitNearby(hidden)
            val satisfied = neighborsOf[center].any { knownContent[it] == named && diggable[it] }
            val rank = FruitDiggingBelief.dowsingRank(named)
            return if (anchor) FruitDiggingBelief.FruitFloor(hidden, rank, named, satisfied)
            else FruitDiggingBelief.FruitCeil(hidden, rank, named, satisfied)
        }

        private fun pointValue(content: CarnivalFruit): Double = when (content) {
            CarnivalFruit.APPLE -> 100.0 * (applesCollected + 1)
            CarnivalFruit.CHERRY -> 200.0 + 300.0 * (cherriesCollected + 1)
            CarnivalFruit.BOMB, CarnivalFruit.RUM, CarnivalFruit.UNKNOWN, CarnivalFruit.NO_FRUIT -> 0.0
            else -> content.points.toDouble()
        }

        private fun distribution(cell: Int): Map<CarnivalFruit, Double> = beliefs[cell].orEmpty()

        private fun probabilityOf(cell: Int, content: CarnivalFruit): Double {
            knownContent[cell]?.let { return if (it == content) 1.0 else 0.0 }
            return distribution(cell)[content] ?: 0.0
        }

        private fun bombChance(cell: Int) = probabilityOf(cell, CarnivalFruit.BOMB)
        private fun rumChance(cell: Int) = probabilityOf(cell, CarnivalFruit.RUM)

        private fun expectedPoints(cell: Int): Double {
            val known = knownContent[cell]
            val raw = if (known != null) withWatermelon(known, cell)
            else distribution(cell).entries.sumOf { (content, p) -> p * withWatermelon(content, cell) }
            return raw * nextMultiplier
        }

        private fun withWatermelon(content: CarnivalFruit, cell: Int): Double {
            if (content != CarnivalFruit.WATERMELON) return pointValue(content)
            val nearby = averageNearbyFruitValue(cell)
            return pointValue(content) + 0.5 * (if (nearby > 0) nearby else averageRemainingFruitValue())
        }

        private fun averageNearbyFruitValue(cell: Int): Double {
            var value = 0.0
            var weight = 0.0
            for (nb in neighborsOf[cell]) {
                if (!diggable[nb]) continue
                val known = knownContent[nb]
                if (known != null) {
                    if (known.isEdible) { value += pointValue(known); weight += 1 }
                } else {
                    for ((content, p) in distribution(nb)) if (content.isEdible) { value += p * pointValue(content); weight += p }
                }
            }
            return if (weight > 0) value / weight else 0.0
        }

        private fun averageRemainingFruitValue(): Double {
            var count = 0
            var value = 0.0
            for (content in allContents) {
                if (!content.isEdible) continue
                val n = content.count - knownContent.count { it == content }
                count += n
                value += pointValue(content) * n
            }
            return if (count > 0) value / count else 0.0
        }

        private fun fruitValueAtRisk(cell: Int): Double {
            var total = 0.0
            for (nb in neighborsOf[cell]) {
                if (!diggable[nb]) continue
                val known = knownContent[nb]
                if (known != null) {
                    if (known.isEdible) total += pointValue(known)
                } else {
                    for ((content, p) in distribution(nb)) if (content.isEdible) total += p * pointValue(content)
                }
            }
            return total
        }

        private fun opportunityCost(): Double {
            val bestSafe = candidates.filter { bombChance(it) < 0.05 && rumChance(it) < 0.05 }.maxOfOrNull { expectedPoints(it) } ?: 0.0
            return if (bestSafe > 0) bestSafe else averageRemainingFruitValue()
        }

        private fun score(cell: Int, cost: Double): Double {
            var risk = (bombChance(cell) + rumChance(cell)) * cost * RISK_WEIGHT
            if (!coconutProtection) risk += bombChance(cell) * DESTRUCTION_WEIGHT * fruitValueAtRisk(cell)
            val setup = (probabilityOf(cell, CarnivalFruit.POMEGRANATE) - probabilityOf(cell, CarnivalFruit.DURIAN)) * SETUP_WEIGHT * cost
            val information = neighborsOf[cell].count { it in openOnBoard } * INFO_WEIGHT
            return expectedPoints(cell) - risk + setup + information
        }

        private fun shovelFor(cell: Int): String {
            var fruitNearby = 0.0
            for (nb in neighborsOf[cell]) {
                if (!diggable[nb]) continue
                val known = knownContent[nb]
                if (known != null) {
                    if (known.isEdible) fruitNearby += 1
                } else {
                    for ((content, p) in distribution(nb)) if (content.isEdible) fruitNearby += p
                }
            }
            return if (fruitNearby >= 0.25) "Anchor" else "Mines"
        }

        fun recommend(): Recommendation? {
            if (candidates.isEmpty()) return null
            val cost = opportunityCost()
            var ranked = candidates.sortedByDescending { score(it, cost) }
            // A durian halved the next dig: spend it on the cheapest safe cell and keep the good ones for full value.
            if (nextMultiplier < 1.0 && MAX_DIGS - digsUsed > 1) {
                val absorber = candidates.filter { bombChance(it) < 0.15 && rumChance(it) < 0.15 }.minByOrNull { expectedPoints(it) }
                if (absorber != null) ranked = listOf(absorber) + ranked.filter { it != absorber }
            }
            val best = ranked.first()
            return Recommendation(best / size, best % size, shovelFor(best), expectedPoints(best), bombChance(best), rumChance(best), sampleCount)
        }
    }

    companion object {
        const val GRID = 7
        const val MAX_DIGS = 15
        private const val RISK_WEIGHT = 1.0
        private const val DESTRUCTION_WEIGHT = 0.10
        private const val SETUP_WEIGHT = 0.5
        private const val INFO_WEIGHT = 15.0
    }
}
