package geiler.addons.client.carnival

import java.util.EnumMap

/**
 * One Fruit Digging board, after SkyHanni's `CarnivalFruitDigging` (LGPL-2.1). World-free: the module turns block,
 * armor-stand, item and chat events into the calls here, so the rules can be checked offline.
 */
class FruitDiggingGame(private val solver: FruitDiggingSolver = FruitDiggingSolver()) {
    enum class Block { SAND, SANDSTONE, SANDSTONE_STAIRS, OTHER }

    class Cell {
        var adjacentMines: Int? = null
        var treasure: CarnivalFruit = CarnivalFruit.UNKNOWN
        var solved: CarnivalFruit = CarnivalFruit.UNKNOWN
        var anchored: CarnivalFruit = CarnivalFruit.UNKNOWN
        var uncovered: CarnivalFruit = CarnivalFruit.UNKNOWN
        var diggable = true
        var removedFromRemaining = false

        fun found(): CarnivalFruit = if (uncovered != CarnivalFruit.UNKNOWN) uncovered else solved

        fun toSolverInput(): FruitDiggingSolver.CellInput {
            val content = when {
                uncovered != CarnivalFruit.UNKNOWN -> uncovered
                solved != CarnivalFruit.UNKNOWN -> solved
                else -> null
            }
            return FruitDiggingSolver.CellInput(
                content, diggable, !diggable && content == null, false, adjacentMines,
                treasure.takeIf { it != CarnivalFruit.UNKNOWN }, anchored.takeIf { it != CarnivalFruit.UNKNOWN },
            )
        }
    }

    private val cells = Array(GRID * GRID) { Cell() }
    private val remaining = EnumMap<CarnivalFruit, Int>(CarnivalFruit::class.java)

    var playing = false
        private set
    var digsUsed = 0
        private set
    var lastDug: Int? = null
        private set
    var recommendation: FruitDiggingSolver.Recommendation? = null
        private set
    private var dirty = false

    init {
        reset()
    }

    fun cell(row: Int, col: Int): Cell = cells[row * GRID + col]

    fun reset() {
        playing = false
        for (i in cells.indices) cells[i] = Cell()
        remaining.clear()
        for (fruit in CarnivalFruit.entries) remaining[fruit] = fruit.count
        digsUsed = 0
        lastDug = null
        recommendation = null
        dirty = false
    }

    fun start() {
        reset()
        playing = true
        dirty = true
    }

    fun remaining(fruit: CarnivalFruit): Int = remaining[fruit] ?: 0

    fun lastDugFruit(): CarnivalFruit = lastDug?.let { cells[it].uncovered } ?: CarnivalFruit.NO_FRUIT

    /** What a fruit is worth on the next dig, as SkyHanni labels it. */
    fun pointValue(fruit: CarnivalFruit): Int = fruit.pointValue(fruit.count - remaining(fruit), lastDugFruit())

    /** A board block changed from sand: sandstone is a dig, sandstone stairs a fruit a bomb destroyed. */
    fun onBlockChange(row: Int, col: Int, old: Block, new: Block) {
        if (old != Block.SAND || !valid(row, col)) return
        val index = row * GRID + col
        val cell = cells[index]
        cell.diggable = false
        when (new) {
            Block.SANDSTONE -> {
                lastDug = index
                digsUsed++
            }
            Block.SANDSTONE_STAIRS -> takeFromRemaining(cell, cell.solved)
            else -> {}
        }
        dirty = true
    }

    /** An armor stand over a cell named a fruit: it was dug, or a watermelon exposed it. */
    fun onReveal(row: Int, col: Int, name: String) {
        if (!valid(row, col)) return
        val match = CarnivalPatterns.REVEAL.matchEntire(name.trim()) ?: return
        val fruit = CarnivalFruit.fromName(match.groups["name"]?.value ?: return) ?: return
        val cell = cell(row, col)
        takeFromRemaining(cell, fruit)
        cell.uncovered = fruit
        dirty = true
    }

    /** An anchor shovel dropped a fruit head over a cell: that cell holds it, and the dug cell gets the anchor clue. */
    fun onAnchorItem(row: Int, col: Int, fruit: CarnivalFruit) {
        if (!valid(row, col)) return
        val solvedCell = cell(row, col)
        val dug = lastDug?.let { cells[it] } ?: return
        var updated = false
        if (solvedCell.solved == CarnivalFruit.UNKNOWN) {
            solvedCell.solved = fruit
            takeFromRemaining(solvedCell, fruit)
            updated = true
        }
        if (solvedCell.diggable && dug.anchored == CarnivalFruit.UNKNOWN) {
            dug.anchored = fruit
            updated = true
        }
        if (updated) dirty = true
    }

    /** Game chat; returns true when the line belonged to the game. */
    fun onChat(message: String): Boolean {
        if (CarnivalPatterns.FRUIT_START.matches(message)) {
            start()
            return true
        }
        if (CarnivalPatterns.FRUIT_END.matches(message)) {
            reset()
            return true
        }
        if (!playing) return false
        val dug = lastDug?.let { cells[it] }
        CarnivalPatterns.MINES.matchEntire(message)?.let {
            dug?.adjacentMines = it.groups["bombs"]?.value?.toIntOrNull()
            dirty = true
            return true
        }
        CarnivalPatterns.TREASURE.matchEntire(message)?.let { match ->
            val fruit = CarnivalFruit.entries.find { it.inGameName == match.groups["fruit"]?.value } ?: return true
            dug?.treasure = fruit
            dirty = true
            return true
        }
        if (CarnivalPatterns.NO_FRUITS_NEARBY.matches(message)) {
            dug?.treasure = CarnivalFruit.NO_FRUIT
            dug?.anchored = CarnivalFruit.NO_FRUIT
            dirty = true
            return true
        }
        return false
    }

    /** Recomputes the recommended dig when the board changed; returns true when it ran. Costs up to 20k shuffles. */
    fun updateRecommendation(): Boolean {
        if (!dirty || !playing) return false
        dirty = false
        val grid = Array(GRID) { row -> Array(GRID) { col -> cell(row, col).toSolverInput() } }
        val last = lastDugFruit()
        val multiplier = when (last) {
            CarnivalFruit.POMEGRANATE -> 1.5
            CarnivalFruit.DURIAN -> 0.5
            else -> 1.0
        }
        recommendation = solver.recommend(grid, digsUsed, multiplier, last == CarnivalFruit.COCONUT)
        return true
    }

    private fun takeFromRemaining(cell: Cell, fruit: CarnivalFruit) {
        if (fruit == CarnivalFruit.UNKNOWN || fruit == CarnivalFruit.NO_FRUIT) return
        if (!cell.removedFromRemaining && !cell.diggable) {
            val count = remaining[fruit] ?: return
            remaining[fruit] = count - 1
            cell.removedFromRemaining = true
        }
    }

    companion object {
        const val GRID = FruitDiggingSolver.GRID

        // The board's north-west corner on the Hub Carnival (SkyHanni GamePos).
        const val START_X = -112
        const val START_Z = 19
        const val GRID_Y = 72

        @JvmStatic
        fun valid(row: Int, col: Int): Boolean = row in 0 until GRID && col in 0 until GRID

        /** Board cell of a world block position, or null off the board. */
        @JvmStatic
        fun rowOf(z: Double): Int = Math.floor(z - START_Z).toInt()

        @JvmStatic
        fun colOf(x: Double): Int = Math.floor(x - START_X).toInt()

        @JvmStatic
        fun label(row: Int, col: Int): String = "${'A' + row}${col + 1}"
    }
}
