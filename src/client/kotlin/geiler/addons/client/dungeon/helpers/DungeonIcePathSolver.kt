package geiler.addons.client.dungeon.helpers

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap

/** Bounded shortest-slide planner for the 17 by 17 silverfish Ice Path board. */
object DungeonIcePathSolver {
    const val BOARD_SIZE = 17

    private val DIRECTIONS = arrayOf(
        intArrayOf(-1, 0),
        intArrayOf(1, 0),
        intArrayOf(0, -1),
        intArrayOf(0, 1),
    )

    /**
     * Returns the fewest cardinal slides from [start] to the left exit lane. The silverfish
     * moves until a wall or the board edge stops it; [blocks] marks walls by row and column.
     */
    @JvmStatic
    fun solve(blocks: Array<out BooleanArray?>?, start: Cell?): List<Cell> {
        if (!validBoard(blocks) || !inside(start)) return emptyList()

        val board = blocks ?: return emptyList()
        val origin = start ?: return emptyList()
        if (board[origin.row]!![origin.column]) return emptyList()

        val queue = ArrayDeque<Cell>()
        val previous = HashMap<Cell, Cell?>()
        queue.add(origin)
        previous[origin] = null
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (isExit(at)) return unwind(previous, at)
            for (direction in DIRECTIONS) {
                val stop = slide(board, at, direction[0], direction[1])
                if (stop == at || previous.containsKey(stop)) continue
                previous[stop] = at
                queue.addLast(stop)
            }
        }
        return emptyList()
    }

    @JvmStatic
    fun validBoard(blocks: Array<out BooleanArray?>?): Boolean {
        if (blocks == null || blocks.size != BOARD_SIZE) return false
        for (row in blocks) if (row == null || row.size != BOARD_SIZE) return false
        return true
    }

    private fun slide(blocks: Array<out BooleanArray?>, start: Cell, rowStep: Int, columnStep: Int): Cell {
        var row = start.row
        var column = start.column
        var nextRow = row + rowStep
        var nextColumn = column + columnStep
        while (inside(nextRow, nextColumn) && !blocks[nextRow]!![nextColumn]) {
            row = nextRow
            column = nextColumn
            nextRow += rowStep
            nextColumn += columnStep
        }
        return Cell(row, column)
    }

    private fun isExit(cell: Cell): Boolean = cell.row == 0 && cell.column in 7..9

    private fun inside(cell: Cell?): Boolean = cell != null && inside(cell.row, cell.column)

    private fun inside(row: Int, column: Int): Boolean =
        row in 0 until BOARD_SIZE && column in 0 until BOARD_SIZE

    private fun unwind(previous: Map<Cell, Cell?>, end: Cell): List<Cell> {
        val reversed = ArrayList<Cell>()
        var cell: Cell? = end
        while (cell != null) {
            reversed.add(cell)
            cell = previous[cell]
        }
        Collections.reverse(reversed)
        return java.util.List.copyOf(reversed)
    }

    @JvmRecord
    data class Cell(val row: Int, val column: Int)

    @JvmRecord
    data class Point(val x: Double, val y: Double, val z: Double)
}
