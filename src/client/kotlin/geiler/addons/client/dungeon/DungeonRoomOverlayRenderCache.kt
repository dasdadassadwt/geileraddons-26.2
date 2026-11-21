package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.helpers.SpecialColumnPredictor

/** Snapshot identity and bounded derived-data cache for the room-map renderer. */
class DungeonRoomOverlayRenderCache {
    private val logic = DungeonRoomOverlayRenderCacheLogic()

    fun resolve(
        snapshot: DungeonRoomOverlayGeometry.Snapshot?,
        revealUnopenedRooms: Boolean,
    ): Data = logic.resolve(snapshot, revealUnopenedRooms)

    fun rebuildCount(): Long = logic.rebuildCount()

    class Data private constructor(
        snapshot: DungeonRoomOverlayGeometry.Snapshot?,
        revealUnopenedRooms: Boolean,
    ) {
        private val logic = DungeonRoomOverlayRenderCacheDataLogic(snapshot, revealUnopenedRooms)

        companion object {
            @JvmStatic
            fun create(snapshot: DungeonRoomOverlayGeometry.Snapshot?, revealUnopenedRooms: Boolean): Data =
                Data(snapshot, revealUnopenedRooms)
        }

        fun matchesUnavailable(revealUnopenedRooms: Boolean): Boolean =
            logic.matchesUnavailable(revealUnopenedRooms)

        fun matches(snapshot: DungeonRoomOverlayGeometry.Snapshot, revealUnopenedRooms: Boolean): Boolean =
            logic.matches(snapshot, revealUnopenedRooms)

        fun available(): Boolean = logic.available()
        fun plan(): DungeonRoomOverlayGeometry.RenderPlan = logic.plan()
        fun roomsById(): Map<String, DungeonRoomOverlayGeometry.Room> = logic.roomsById()
        fun cellAt(x: Int, z: Int): DungeonRoomOverlayGeometry.Cell? = logic.cellAt(x, z)
        fun visibleCellAt(x: Int, z: Int): DungeonRoomOverlayGeometry.Cell? = logic.visibleCellAt(x, z)
        fun predictions(): List<SpecialColumnPredictor.Prediction> = logic.predictions()
        fun doorGeometry(cellSize: Int, cellGap: Int, doorScale: Float): List<DoorGeometry> =
            logic.doorGeometry(cellSize, cellGap, doorScale)
    }

    @JvmRecord
    data class DoorGeometry(
        val exit: DungeonRoomOverlayGeometry.ExitMarker?,
        val rect: DungeonRoomOverlayLayout.DoorRect?,
    )
}
