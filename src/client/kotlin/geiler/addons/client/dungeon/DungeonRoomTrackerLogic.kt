package geiler.addons.client.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import java.util.ArrayList
import kotlin.math.floor

/** Client-thread room footprint tracking behind the stable Java records and entrypoints. */
object DungeonRoomTrackerLogic {
    @Volatile
    private var state = DungeonRoomTracker.TrackerState.empty()

    @JvmStatic
    fun tick(client: Minecraft?, dungeonMobEspEnabled: Boolean) {
        val hasWorld = client?.level != null
        val hasPlayer = client?.player != null
        val active = shouldTrack(DungeonContextTracker.inDungeon(), dungeonMobEspEnabled, hasWorld, hasPlayer)
        if (!active) {
            clear()
            return
        }

        val level: ClientLevel = requireNotNull(client?.level)
        val playerCell = playerCell(client)
        val observed = currentMapFootprint(client)
        state = observe(state, true, level, playerCell, observed)
    }

    @JvmStatic
    fun shouldTrack(catacombsActive: Boolean, moduleEnabled: Boolean, hasWorld: Boolean, hasPlayer: Boolean) =
        catacombsActive && moduleEnabled && hasWorld && hasPlayer

    /** Builds an observation from calibrated map geometry and its corresponding physical grid cells. */
    @JvmStatic
    fun currentMapFootprint(client: Minecraft?): DungeonRoomTracker.MapRoomFootprint? {
        if (client?.level == null || client.player == null || !DungeonContextTracker.inDungeon()) return null
        val map = DungeonMapRoomDetector.current() ?: return null
        val geometry = map.geometry
        if (!map.hasCurrentCell() || !map.calibrated() || geometry == null
            || geometry.roomType() == DungeonMapGeometry.RoomType.UNKNOWN) return null

        val mapCell = map.currentCell ?: return null
        val physicalCell = playerCell(client) ?: return null
        if (mapCell.componentX != physicalCell.x || mapCell.componentZ != physicalCell.z) return null

        val offsets = geometry.connectedCells()
        if (offsets.isNullOrEmpty()) return null
        val distinctOffsetCount = offsets.stream().distinct().count()
        val cells = ArrayList<DungeonRoomTracker.CellKey>(offsets.size)
        for (offset in offsets) {
            val cell = DungeonRoomTracker.CellKey(mapCell.componentX + offset.x, mapCell.componentZ + offset.z)
            if (insideGrid(cell) && cell !in cells) cells.add(cell)
        }
        cells.sortWith(compareBy<DungeonRoomTracker.CellKey> { it.x }.thenBy { it.z })
        // A partial map shape at the edge of the six-by-six dungeon grid is not a confirmed footprint.
        if (cells.size.toLong() != distinctOffsetCount || cells.isEmpty() || physicalCell !in cells) return null
        return DungeonRoomTracker.MapRoomFootprint(null, java.util.List.copyOf(cells))
    }

    @JvmStatic
    fun currentFootprint(): DungeonRoomTracker.MapRoomFootprint? = state.footprint

    @JvmStatic
    fun confirmedPhysicalRoomCells(): List<DungeonRoomTracker.CellKey> =
        currentFootprint()?.cells() ?: emptyList()

    @JvmStatic
    fun diagnostics(): String {
        val current = state
        val footprint = current.footprint
        return "tracking=${current.world != null}, footprint=" +
            (if (footprint == null) "unconfirmed" else "${footprint.cells().size} physical cell(s)")
    }

    @JvmStatic
    fun clear() {
        state = DungeonRoomTracker.TrackerState.empty()
    }

    @JvmStatic
    @JvmName("observe")
    internal fun observe(
        previous: DungeonRoomTracker.TrackerState?,
        active: Boolean,
        world: Any?,
        playerCell: DungeonRoomTracker.CellKey?,
        observed: DungeonRoomTracker.MapRoomFootprint?,
    ): DungeonRoomTracker.TrackerState {
        if (!active || world == null) return DungeonRoomTracker.TrackerState.empty()
        val validObservation = if (confirmsPlayerCell(observed, playerCell)) observed else null
        if (previous == null || previous.world !== world) {
            return DungeonRoomTracker.TrackerState(world, validObservation)
        }

        val retained = previous.footprint
        if (retained == null) return DungeonRoomTracker.TrackerState(world, validObservation)
        if (playerCell == null || !retained.containsCell(playerCell)) {
            // The player has left the retained cells. A new observation can establish the next room now.
            return DungeonRoomTracker.TrackerState(world, validObservation)
        }
        if (validObservation == null || !sameCells(retained, validObservation)) {
            // Keep the confirmed shape during transient map loss while the player remains inside it.
            return DungeonRoomTracker.TrackerState(world, retained)
        }
        return DungeonRoomTracker.TrackerState(world, validObservation)
    }

    @JvmStatic
    fun normalizeCells(cells: List<DungeonRoomTracker.CellKey?>?): List<DungeonRoomTracker.CellKey> {
        val normalized = (cells ?: emptyList()).filterNotNull().filter(::insideGrid)
            .distinct().sortedWith(compareBy<DungeonRoomTracker.CellKey> { it.x }.thenBy { it.z })
        return java.util.List.copyOf(normalized)
    }

    @JvmStatic
    fun footprintKey(cells: List<DungeonRoomTracker.CellKey>): String =
        if (cells.isEmpty()) "" else "cells:" + cells.joinToString(";") { "${it.x},${it.z}" }

    @JvmStatic
    fun containsPhysicalCell(cells: List<DungeonRoomTracker.CellKey>?, worldX: Double, worldZ: Double): Boolean {
        if (cells.isNullOrEmpty() || !worldX.isFinite() || !worldZ.isFinite()
            || worldX < Int.MIN_VALUE || worldX > Int.MAX_VALUE
            || worldZ < Int.MIN_VALUE || worldZ > Int.MAX_VALUE) return false
        val physical = DungeonGridRules.cellAt(floor(worldX).toInt(), floor(worldZ).toInt()) ?: return false
        return DungeonRoomTracker.CellKey(physical.componentX, physical.componentZ) in cells
    }

    private fun confirmsPlayerCell(
        footprint: DungeonRoomTracker.MapRoomFootprint?,
        playerCell: DungeonRoomTracker.CellKey?,
    ) = footprint != null && playerCell != null && footprint.containsCell(playerCell)

    private fun sameCells(
        first: DungeonRoomTracker.MapRoomFootprint?,
        second: DungeonRoomTracker.MapRoomFootprint?,
    ) = first != null && second != null && first.cells() == second.cells()

    private fun playerCell(client: Minecraft?): DungeonRoomTracker.CellKey? {
        val player = client?.player ?: return null
        val position = player.blockPosition()
        val cell = DungeonGridRules.cellAt(position.x, position.z) ?: return null
        return DungeonRoomTracker.CellKey(cell.componentX, cell.componentZ)
    }

    private fun insideGrid(cell: DungeonRoomTracker.CellKey?): Boolean =
        cell != null && cell.x in 0 until 6 && cell.z in 0 until 6
}
