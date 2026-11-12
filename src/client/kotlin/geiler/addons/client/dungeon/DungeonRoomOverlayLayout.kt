package geiler.addons.client.dungeon

/** Java-compatible layout constants and records for the room-map renderer. */
object DungeonRoomOverlayLayout {
    const val GRID_CELLS = 6
    const val ODIN_ROOM_SIZE = 16
    const val ODIN_ROOM_GAP = 4
    const val ODIN_CONNECTION_SIZE = 4
    const val ODIN_CANVAS_SIZE = 128
    const val SETTINGS_PREVIEW_SIZE = 460

    @JvmStatic
    fun calculate(cellSize: Int, cellGap: Int, padding: Int): Layout =
        DungeonRoomOverlayLayoutLogic.calculate(cellSize, cellGap, padding)

    @JvmRecord
    data class Layout(
        val width: Int,
        val height: Int,
        val gridTop: Int,
        val contentInset: Int,
        val cellSize: Int,
        val cellGap: Int,
    )

    @JvmStatic
    fun scaledDimension(dimension: Int, scale: Float): Int =
        DungeonRoomOverlayLayoutLogic.scaledDimension(dimension, scale)

    /** Gives the square map preview a readable area while respecting narrow or short settings panes. */
    @JvmStatic
    fun settingsPreviewHeight(width: Int, availableHeight: Int): Int =
        DungeonRoomOverlayLayoutLogic.settingsPreviewHeight(width, availableHeight)

    /** Computes Odin-proportioned 8-by-4 door geometry, contained within the room gap. */
    @JvmStatic
    fun doorRect(
        left: Int,
        top: Int,
        roomSize: Int,
        roomGap: Int,
        direction: DungeonRoomOverlayGeometry.Direction?,
        doorScale: Float,
    ): DoorRect = DungeonRoomOverlayLayoutLogic.doorRect(left, top, roomSize, roomGap, direction, doorScale)

    /**
     * Returns rectangles for the internal gap intersection of each complete 2-by-2 block in one
     * room footprint. Coordinates are relative to the six-by-six map grid origin. Partial blocks
     * are omitted so this cannot fill an outer gap or imply room cells that are not in the footprint.
     */
    @JvmStatic
    fun internalGapIntersections(
        footprint: List<DungeonRoomOverlayGeometry.Cell?>?,
        cellSize: Int,
        cellGap: Int,
    ): List<GapRect> = DungeonRoomOverlayLayoutLogic.internalGapIntersections(footprint, cellSize, cellGap)

    @JvmRecord
    data class GapRect(val x: Int, val y: Int, val width: Int, val height: Int)

    @JvmRecord
    data class DoorRect(val x: Int, val y: Int, val width: Int, val height: Int)
}
