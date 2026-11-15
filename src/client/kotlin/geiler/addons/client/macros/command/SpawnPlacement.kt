package geiler.addons.client.macros.command

import java.util.Random

/**
 * Where `/spawnmob` puts mobs when the macro's painted area is in use: on the area's walkable cells only, whatever the radius reaches
 * beyond. Plain data, free of game state so checks can run it.
 */
object SpawnPlacement {
    /** The player counts as near the area when a walkable cell is this close across. */
    const val NEAR_BLOCKS = 16.0

    /**
     * [count] positions on [cells] (each x, y, z of the feet cell and the floor height in eighths of a block above its bottom): every cell
     * once in a random order, then again when there are more mobs than cells, each a little off the middle of its cell. Empty with no cells.
     */
    @JvmStatic
    fun pick(cells: List<IntArray>, count: Int, random: Random): List<DoubleArray> {
        if (cells.isEmpty() || count <= 0) return emptyList()
        val order = ArrayList<IntArray>(cells)
        val spots = ArrayList<DoubleArray>(count)
        while (spots.size < count) {
            order.shuffle(random)
            for (cell in order) {
                if (spots.size >= count) break
                spots.add(
                    doubleArrayOf(
                        cell[0] + 0.5 + (random.nextDouble() - 0.5) * JITTER,
                        cell[1] + cell[3] / 8.0,
                        cell[2] + 0.5 + (random.nextDouble() - 0.5) * JITTER,
                    ),
                )
            }
        }
        return spots
    }

    /** How far from the middle of a cell a mob may be put: well inside it. */
    private const val JITTER = 0.5
}
