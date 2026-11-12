package geiler.addons.client.dungeon

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Kotlin layout calculations for the Java-compatible room-map API. */
object DungeonRoomOverlayLayoutLogic {
    @JvmStatic
    fun calculate(cellSize: Int, cellGap: Int, padding: Int): DungeonRoomOverlayLayout.Layout {
        val boundedCell = cellSize.coerceIn(6, 32)
        val boundedGap = cellGap.coerceIn(0, 12)
        val boundedPadding = padding.coerceIn(0, 24)
        val gridWidth = boundedCell * DungeonRoomOverlayLayout.GRID_CELLS +
            boundedGap * (DungeonRoomOverlayLayout.GRID_CELLS - 1)
        // Treat padding as the total frame margin and split it evenly around the room grid.
        val mapExtent = gridWidth + boundedPadding
        val inset = boundedPadding / 2
        return DungeonRoomOverlayLayout.Layout(mapExtent, mapExtent, inset, inset, boundedCell, boundedGap)
    }

    @JvmStatic
    fun scaledDimension(dimension: Int, scale: Float): Int {
        val finiteScale = if (scale.isFinite()) scale else 1.0f
        return max(1, ceil(max(1, dimension) * finiteScale.coerceIn(0.25f, 5.0f)).toInt())
    }

    @JvmStatic
    fun settingsPreviewHeight(width: Int, availableHeight: Int): Int =
        max(1, min(DungeonRoomOverlayLayout.SETTINGS_PREVIEW_SIZE, min(max(1, width), max(1, availableHeight))))

    /** Computes Odin-proportioned 8-by-4 door geometry, contained within the room gap. */
    @JvmStatic
    fun doorRect(
        left: Int,
        top: Int,
        roomSize: Int,
        roomGap: Int,
        direction: DungeonRoomOverlayGeometry.Direction?,
        doorScale: Float,
    ): DungeonRoomOverlayLayout.DoorRect {
        val boundedRoom = max(1, roomSize)
        val boundedGap = max(0, roomGap)
        val scale = if (doorScale.isFinite()) doorScale.coerceIn(0.1f, 1.0f) else 1.0f
        val opening = max(1, (boundedRoom * 0.5f * scale).roundToInt())
        val depth = if (boundedGap == 0) 0 else max(
            1,
            min(boundedGap, (min(boundedRoom * 0.25f, DungeonRoomOverlayLayout.ODIN_CONNECTION_SIZE.toFloat()) * scale).roundToInt()),
        )
        val alongEdge = (boundedRoom - opening) / 2
        return when (direction ?: DungeonRoomOverlayGeometry.Direction.NORTH) {
            DungeonRoomOverlayGeometry.Direction.NORTH ->
                DungeonRoomOverlayLayout.DoorRect(left + alongEdge, top - depth, opening, depth)
            DungeonRoomOverlayGeometry.Direction.EAST ->
                DungeonRoomOverlayLayout.DoorRect(left + boundedRoom, top + alongEdge, depth, opening)
            DungeonRoomOverlayGeometry.Direction.SOUTH ->
                DungeonRoomOverlayLayout.DoorRect(left + alongEdge, top + boundedRoom, opening, depth)
            DungeonRoomOverlayGeometry.Direction.WEST ->
                DungeonRoomOverlayLayout.DoorRect(left - depth, top + alongEdge, depth, opening)
        }
    }

    /**
     * Returns rectangles for each complete 2-by-2 footprint block. Partial blocks are omitted so
     * this cannot fill an outer gap or imply cells that are not present in the footprint.
     */
    @JvmStatic
    fun internalGapIntersections(
        footprint: List<DungeonRoomOverlayGeometry.Cell?>?,
        cellSize: Int,
        cellGap: Int,
    ): List<DungeonRoomOverlayLayout.GapRect> {
        if (footprint.isNullOrEmpty() || cellSize <= 0 || cellGap <= 0) return emptyList()

        val occupied = Array(DungeonRoomOverlayLayout.GRID_CELLS) { BooleanArray(DungeonRoomOverlayLayout.GRID_CELLS) }
        for (cell in footprint) {
            if (cell == null) continue
            val x = cell.x()
            val z = cell.z()
            if (x in 0 until DungeonRoomOverlayLayout.GRID_CELLS && z in 0 until DungeonRoomOverlayLayout.GRID_CELLS) {
                occupied[z][x] = true
            }
        }

        val stride = cellSize + cellGap
        val intersections = ArrayList<DungeonRoomOverlayLayout.GapRect>()
        for (z in 0 until DungeonRoomOverlayLayout.GRID_CELLS - 1) {
            for (x in 0 until DungeonRoomOverlayLayout.GRID_CELLS - 1) {
                if (occupied[z][x] && occupied[z][x + 1] && occupied[z + 1][x] && occupied[z + 1][x + 1]) {
                    intersections.add(
                        DungeonRoomOverlayLayout.GapRect(x * stride + cellSize, z * stride + cellSize, cellGap, cellGap),
                    )
                }
            }
        }
        return java.util.List.copyOf(intersections)
    }
}
