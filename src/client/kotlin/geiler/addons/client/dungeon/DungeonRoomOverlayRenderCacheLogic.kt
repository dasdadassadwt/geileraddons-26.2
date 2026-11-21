package geiler.addons.client.dungeon

import java.util.ArrayList
import java.util.HashMap

/** Snapshot identity and bounded derived-data cache behind the Kotlin cache facade. */
class DungeonRoomOverlayRenderCacheLogic {
    private var cached: DungeonRoomOverlayRenderCache.Data? = null
    private var rebuildCount = 0L

    fun resolve(
        snapshot: DungeonRoomOverlayGeometry.Snapshot?,
        revealUnopenedRooms: Boolean,
    ): DungeonRoomOverlayRenderCache.Data {
        val previous = cached
        if (snapshot == null || !snapshot.available()) {
            if (previous == null || !previous.matchesUnavailable(revealUnopenedRooms)) {
                cached = DungeonRoomOverlayRenderCache.Data.create(snapshot, revealUnopenedRooms)
                rebuildCount++
            }
            return checkNotNull(cached)
        }
        if (previous != null && previous.matches(snapshot, revealUnopenedRooms)) return previous
        cached = DungeonRoomOverlayRenderCache.Data.create(snapshot, revealUnopenedRooms)
        rebuildCount++
        return checkNotNull(cached)
    }

    fun rebuildCount(): Long = rebuildCount
}

/** Kotlin-owned cached projection data exposed through the outer Java-compatible API. */
class DungeonRoomOverlayRenderCacheDataLogic(
    snapshot: DungeonRoomOverlayGeometry.Snapshot?,
    private val revealUnopenedRooms: Boolean,
) {
    private val available = snapshot != null && snapshot.available()
    private val cells = snapshot?.cells() ?: emptyList()
    private val rooms = snapshot?.rooms() ?: emptyList()
    private val predictions = snapshot?.predictions() ?: emptyList()
    private val plan = DungeonRoomOverlayGeometry.renderPlan(snapshot, revealUnopenedRooms)
    private val roomsById: Map<String, DungeonRoomOverlayGeometry.Room>
    private val allCells = arrayOfNulls<DungeonRoomOverlayGeometry.Cell>(GRID_SIZE * GRID_SIZE)
    private val visibleCells = arrayOfNulls<DungeonRoomOverlayGeometry.Cell>(GRID_SIZE * GRID_SIZE)

    private var doorsResolved = false
    private var doorCellSize = 0
    private var doorCellGap = 0
    private var doorScale = 0.0f
    private var doorGeometry: List<DungeonRoomOverlayRenderCache.DoorGeometry> = emptyList()

    init {
        val byId = HashMap<String, DungeonRoomOverlayGeometry.Room>()
        for (room in rooms) {
            if (room == null) continue
            val id = room.id() ?: continue
            byId[id] = room
        }
        roomsById = java.util.Map.copyOf(byId)
        for (cell in cells) {
            val index = index(cell.x(), cell.z())
            if (index >= 0) allCells[index] = cell
        }
        for (cell in plan.visibleCells()) {
            val index = index(cell.x(), cell.z())
            if (index >= 0) visibleCells[index] = cell
        }
    }

    fun matchesUnavailable(reveal: Boolean): Boolean = !available && reveal == revealUnopenedRooms

    fun matches(snapshot: DungeonRoomOverlayGeometry.Snapshot, reveal: Boolean): Boolean =
        available && reveal == revealUnopenedRooms && cells === snapshot.cells() &&
            rooms === snapshot.rooms() && predictions === snapshot.predictions()

    fun available(): Boolean = available
    fun plan(): DungeonRoomOverlayGeometry.RenderPlan = plan
    fun roomsById(): Map<String, DungeonRoomOverlayGeometry.Room> = roomsById

    fun cellAt(x: Int, z: Int): DungeonRoomOverlayGeometry.Cell? {
        val index = index(x, z)
        return if (index < 0) null else allCells[index]
    }

    fun visibleCellAt(x: Int, z: Int): DungeonRoomOverlayGeometry.Cell? {
        val index = index(x, z)
        return if (index < 0) null else visibleCells[index]
    }

    fun predictions(): List<geiler.addons.client.dungeon.helpers.SpecialColumnPredictor.Prediction> = predictions

    fun doorGeometry(
        cellSize: Int,
        cellGap: Int,
        scale: Float,
    ): List<DungeonRoomOverlayRenderCache.DoorGeometry> {
        if (doorsResolved && doorCellSize == cellSize && doorCellGap == cellGap &&
            java.lang.Float.compare(doorScale, scale) == 0
        ) return doorGeometry
        val computed = ArrayList<DungeonRoomOverlayRenderCache.DoorGeometry>(plan.exits().size)
        for (exit in plan.exits()) {
            val cell = exit.cell() ?: continue
            val left = cell.x() * (cellSize + cellGap)
            val top = cell.z() * (cellSize + cellGap)
            val rect = DungeonRoomOverlayLayout.doorRect(left, top, cellSize, cellGap, exit.direction(), scale)
            computed.add(DungeonRoomOverlayRenderCache.DoorGeometry(exit, rect))
        }
        doorsResolved = true
        doorCellSize = cellSize
        doorCellGap = cellGap
        doorScale = scale
        doorGeometry = java.util.List.copyOf(computed)
        return doorGeometry
    }

    private fun index(x: Int, z: Int): Int =
        if (x < 0 || z < 0 || x >= GRID_SIZE || z >= GRID_SIZE) -1 else x + z * GRID_SIZE

    companion object {
        private const val GRID_SIZE = 6
    }
}
