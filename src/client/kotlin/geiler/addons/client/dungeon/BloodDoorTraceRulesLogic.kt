package geiler.addons.client.dungeon

import net.minecraft.core.BlockPos
import java.util.ArrayList
import java.util.HashSet
import java.util.function.Predicate

/** Pure room and block-snapshot rules behind the Java-facing door-trace API. */
object BloodDoorTraceRulesLogic {
    @JvmStatic
    fun shouldTrace(
        enabled: Boolean,
        target: DungeonDoorTracker.Door?,
        closedDoorPresent: Boolean,
        mapCalibrated: Boolean,
        currentRoomType: DungeonMapGeometry.RoomType?,
        currentShape: String?,
        currentCell: DungeonGridRules.Cell?,
        connectedCells: List<DungeonRoomTracker.CellKey?>?,
        playerInsideDetectedShape: Boolean,
    ): Boolean = target?.type() == DungeonDoorTracker.Type.BLOOD && eligibleConnectedRoom(
        enabled,
        target,
        closedDoorPresent,
        mapCalibrated,
        currentRoomType,
        currentShape,
        currentCell,
        connectedCells,
        playerInsideDetectedShape,
    )

    /** Avoid drawing a downstream Blood target while the current Wither target is traced. */
    @JvmStatic
    fun bloodSequenceAllowsTrace(activeWitherTrace: DungeonDoorTracker.Door?): Boolean =
        activeWitherTrace == null || activeWitherTrace.type() != DungeonDoorTracker.Type.WITHER

    @JvmStatic
    fun eligibleConnectedRoom(
        enabled: Boolean,
        target: DungeonDoorTracker.Door?,
        closedDoorPresent: Boolean,
        mapCalibrated: Boolean,
        currentRoomType: DungeonMapGeometry.RoomType?,
        currentShape: String?,
        currentCell: DungeonGridRules.Cell?,
        connectedCells: List<DungeonRoomTracker.CellKey?>?,
        playerInsideDetectedShape: Boolean,
    ): Boolean = enabled && target != null &&
        (target.type() == DungeonDoorTracker.Type.BLOOD || target.type() == DungeonDoorTracker.Type.WITHER) &&
        closedDoorPresent && mapCalibrated && currentRoomType == DungeonMapGeometry.RoomType.ROOM &&
        playerInsideDetectedShape && validCurrentShape(currentCell, currentShape, connectedCells) &&
        isBoundaryOfConnectedRoom(target, connectedCells)

    /** The live map shape, current map cell, and physical player position must agree. */
    @JvmStatic
    fun validCurrentShape(
        currentCell: DungeonGridRules.Cell?,
        shape: String?,
        connectedCells: List<DungeonRoomTracker.CellKey?>?,
    ): Boolean {
        if (currentCell == null || shape == null || connectedCells == null ||
            connectedCells.isEmpty() || connectedCells.size > 4
        ) return false
        val rawCells = HashSet(connectedCells)
        if (rawCells.size != connectedCells.size || rawCells.any { !inGrid(it) }) return false
        val current = DungeonRoomTracker.CellKey(currentCell.componentX, currentCell.componentZ)
        if (!rawCells.contains(current)) return false
        val offsets = rawCells.map { cell ->
            val key = cell ?: return false
            DungeonMapGeometry.CellOffset(key.x - current.x, key.z - current.z)
        }
        return shape == DungeonMapGeometry.shape(offsets) && connected(rawCells)
    }

    /** The tracked Blood door must be the exact edge between the room shape and its neighbor. */
    @JvmStatic
    fun isBoundaryOfShape(
        target: DungeonDoorTracker.Door?,
        connectedCells: List<DungeonRoomTracker.CellKey?>?,
    ): Boolean {
        if (target == null || target.type() != DungeonDoorTracker.Type.BLOOD ||
            connectedCells == null || connectedCells.isEmpty()
        ) return false
        return isBoundaryOfConnectedRoom(target, connectedCells)
    }

    @JvmStatic
    fun isBoundaryOfConnectedRoom(
        target: DungeonDoorTracker.Door?,
        connectedCells: List<DungeonRoomTracker.CellKey?>?,
    ): Boolean {
        if (target == null || connectedCells == null || connectedCells.isEmpty()) return false
        val first = cell(target.firstCell()) ?: return false
        val second = cell(target.secondCell()) ?: return false
        if (manhattan(first, second) != 1) return false
        val shape = HashSet<DungeonRoomTracker.CellKey?>(connectedCells)
        val firstInside = shape.contains(first)
        val secondInside = shape.contains(second)
        return firstInside != secondInside
    }

    private fun connected(cells: Set<DungeonRoomTracker.CellKey?>): Boolean {
        val visited = HashSet<DungeonRoomTracker.CellKey>()
        val pending = ArrayList<DungeonRoomTracker.CellKey>()
        pending.add(cells.iterator().next() ?: return false)
        var index = 0
        while (index < pending.size) {
            val current = pending[index]
            index++
            if (!visited.add(current)) continue
            for (candidate in cells) {
                if (candidate != null && !visited.contains(candidate) && manhattan(current, candidate) == 1) {
                    pending.add(candidate)
                }
            }
        }
        return visited.size == cells.size
    }

    private fun manhattan(first: DungeonRoomTracker.CellKey, second: DungeonRoomTracker.CellKey): Int =
        kotlin.math.abs(first.x - second.x) + kotlin.math.abs(first.z - second.z)

    private fun inGrid(cell: DungeonRoomTracker.CellKey?): Boolean =
        cell != null && cell.x >= 0 && cell.x < 6 && cell.z >= 0 && cell.z < 6

    private fun cell(index: Int): DungeonRoomTracker.CellKey? {
        if (index < 0 || index >= 36) return null
        return DungeonRoomTracker.CellKey(index % 6, index / 6)
    }

    @JvmStatic
    fun hasClosedBloodDoorBlocks(
        door: DungeonDoorTracker.Door?,
        isBloodDoorBlock: Predicate<BlockPos>?,
    ): Boolean = hasClosedDoorBlocks(door, DungeonDoorTracker.Type.BLOOD, isBloodDoorBlock)

    @JvmStatic
    fun hasClosedWitherDoorBlocks(
        door: DungeonDoorTracker.Door?,
        isWitherDoorBlock: Predicate<BlockPos>?,
    ): Boolean = hasClosedDoorBlocks(door, DungeonDoorTracker.Type.WITHER, isWitherDoorBlock)

    private fun hasClosedDoorBlocks(
        door: DungeonDoorTracker.Door?,
        type: DungeonDoorTracker.Type,
        isDoorBlock: Predicate<BlockPos>?,
    ): Boolean = doorPresence(door, type, Predicate { true }, isDoorBlock) == BloodDoorTraceRules.DoorPresence.CLOSED

    /** Unloaded block samples remain unknown rather than clearing a short grace trace. */
    @JvmStatic
    fun doorPresence(
        door: DungeonDoorTracker.Door?,
        type: DungeonDoorTracker.Type?,
        isLoaded: Predicate<BlockPos>?,
        isDoorBlock: Predicate<BlockPos>?,
    ): BloodDoorTraceRules.DoorPresence {
        val samples = doorSamplePositions(door, type)
        if (samples.isEmpty() || isLoaded == null || isDoorBlock == null) {
            return BloodDoorTraceRules.DoorPresence.UNKNOWN
        }
        var everySampleLoaded = true
        for (sample in samples) {
            if (!isLoaded.test(sample)) {
                everySampleLoaded = false
                continue
            }
            if (isDoorBlock.test(sample)) return BloodDoorTraceRules.DoorPresence.CLOSED
        }
        return if (everySampleLoaded) BloodDoorTraceRules.DoorPresence.OPEN
        else BloodDoorTraceRules.DoorPresence.UNKNOWN
    }

    private fun doorSamplePositions(
        door: DungeonDoorTracker.Door?,
        type: DungeonDoorTracker.Type?,
    ): List<BlockPos> {
        if (door == null || door.type() != type || door.width() != 3 || door.height() != 4 || door.depth() != 3 ||
            door.firstCell() < 0 || door.firstCell() >= 36 || door.secondCell() < 0 || door.secondCell() >= 36
        ) return emptyList()

        val firstX = door.firstCell() % 6
        val firstZ = door.firstCell() / 6
        val secondX = door.secondCell() % 6
        val secondZ = door.secondCell() / 6
        val alongX = firstZ == secondZ && kotlin.math.abs(firstX - secondX) == 1
        val alongZ = firstX == secondX && kotlin.math.abs(firstZ - secondZ) == 1
        if (!alongX && !alongZ) return emptyList()

        val planeX = door.x() + 1
        val planeZ = door.z() + 1
        val samples = ArrayList<BlockPos>(12)
        for (side in -1..1) {
            for (height in 0 until door.height()) {
                samples.add(
                    if (alongX) BlockPos(planeX, door.y() + height, planeZ + side)
                    else BlockPos(planeX + side, door.y() + height, planeZ),
                )
            }
        }
        return java.util.List.copyOf(samples)
    }
}
