package geiler.addons.client.farming

import java.util.HashMap
import java.util.Optional

/** Coordinate and server-id mapping for the Garden's 5 by 5 plot grid. */
object GardenPlotGrid {
    const val GRID_SIZE = 5
    const val PLOT_SIZE = 96.0
    const val MIN_COORDINATE = -240.0
    const val MAX_COORDINATE = 240.0
    const val MIN_BUILD_Y = 0.0
    const val MAX_BUILD_Y = 256.0

    // Server plot IDs are arranged by Z row, not in simple row-major ID order.
    private val idsByRow = arrayOf(
        intArrayOf(21, 13, 9, 14, 22),
        intArrayOf(15, 5, 1, 6, 16),
        intArrayOf(10, 2, 0, 3, 11),
        intArrayOf(17, 7, 4, 8, 18),
        intArrayOf(23, 19, 12, 20, 24),
    )
    private val plots = buildPlots()
    private val byId = indexById(plots)

    /** Maps an in-Garden world position to its plot; the outer +240 edge belongs to the final row/column. */
    @JvmStatic
    fun plotAt(x: Double, y: Double, z: Double): Optional<Plot> {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return Optional.empty()
        if (y < MIN_BUILD_Y || y >= MAX_BUILD_Y) return Optional.empty()
        return plotAt(x, z)
    }

    /** Maps horizontal coordinates when the caller already established Garden/build-height context. */
    @JvmStatic
    fun plotAt(x: Double, z: Double): Optional<Plot> {
        // State the finite-coordinate rule before the range checks for positions from entities/camera.
        if (!x.isFinite() || !z.isFinite()) return Optional.empty()
        val column = coordinateIndex(x)
        val row = coordinateIndex(z)
        if (row < 0 || column < 0) return Optional.empty()
        return Optional.of(byId.getValue(idsByRow[row][column]))
    }

    @JvmStatic
    fun plotById(id: Int): Optional<Plot> = Optional.ofNullable(byId[id])

    @JvmStatic
    fun isValidId(id: Int): Boolean = byId.containsKey(id)

    @JvmStatic
    fun plots(): List<Plot> = plots

    private fun coordinateIndex(coordinate: Double): Int {
        if (!coordinate.isFinite() || coordinate < MIN_COORDINATE || coordinate > MAX_COORDINATE) return -1
        if (coordinate == MAX_COORDINATE) return GRID_SIZE - 1
        return kotlin.math.floor((coordinate - MIN_COORDINATE) / PLOT_SIZE).toInt()
    }

    private fun buildPlots(): List<Plot> {
        val result = ArrayList<Plot>(GRID_SIZE * GRID_SIZE)
        for (row in 0 until GRID_SIZE) {
            for (column in 0 until GRID_SIZE) {
                val minX = MIN_COORDINATE + column * PLOT_SIZE
                val minZ = MIN_COORDINATE + row * PLOT_SIZE
                result.add(Plot(idsByRow[row][column], row, column, minX, minZ))
            }
        }
        return java.util.List.copyOf(result)
    }

    private fun indexById(plots: List<Plot>): Map<Int, Plot> {
        val result = HashMap<Int, Plot>()
        for (plot in plots) {
            if (result.put(plot.id, plot) != null) {
                throw ExceptionInInitializerError("Duplicate Garden plot id ${plot.id}")
            }
        }
        if (result.size != GRID_SIZE * GRID_SIZE) {
            throw ExceptionInInitializerError("Garden plot grid must contain 25 unique plots")
        }
        return java.util.Map.copyOf(result)
    }

    /** A plot's place in the grid and its world extent. */
    @JvmRecord
    data class Plot(val id: Int, val gridZ: Int, val gridX: Int, val minX: Double, val minZ: Double) {
        fun maxX(): Double = minX + PLOT_SIZE
        fun maxZ(): Double = minZ + PLOT_SIZE
        fun centerX(): Double = minX + PLOT_SIZE / 2.0
        fun centerZ(): Double = minZ + PLOT_SIZE / 2.0

        override fun toString(): String =
            "Plot[id=$id, gridZ=$gridZ, gridX=$gridX, minX=$minX, minZ=$minZ]"
    }
}
