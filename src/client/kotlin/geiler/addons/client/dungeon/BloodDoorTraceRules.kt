package geiler.addons.client.dungeon

import net.minecraft.core.BlockPos
import java.util.function.Predicate

/** Java-facing entrypoints for the shared Blood and Wither doorway rules. */
class BloodDoorTraceRules private constructor() {
    companion object {
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
        ): Boolean = BloodDoorTraceRulesLogic.shouldTrace(
            enabled, target, closedDoorPresent, mapCalibrated, currentRoomType, currentShape,
            currentCell, connectedCells, playerInsideDetectedShape,
        )

        @JvmStatic
        fun bloodSequenceAllowsTrace(activeWitherTrace: DungeonDoorTracker.Door?): Boolean =
            BloodDoorTraceRulesLogic.bloodSequenceAllowsTrace(activeWitherTrace)

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
        ): Boolean = BloodDoorTraceRulesLogic.eligibleConnectedRoom(
            enabled, target, closedDoorPresent, mapCalibrated, currentRoomType, currentShape,
            currentCell, connectedCells, playerInsideDetectedShape,
        )

        @JvmStatic
        fun validCurrentShape(
            currentCell: DungeonGridRules.Cell?,
            shape: String?,
            connectedCells: List<DungeonRoomTracker.CellKey?>?,
        ): Boolean = BloodDoorTraceRulesLogic.validCurrentShape(currentCell, shape, connectedCells)

        @JvmStatic
        fun isBoundaryOfShape(
            target: DungeonDoorTracker.Door?,
            connectedCells: List<DungeonRoomTracker.CellKey?>?,
        ): Boolean = BloodDoorTraceRulesLogic.isBoundaryOfShape(target, connectedCells)

        @JvmStatic
        fun isBoundaryOfConnectedRoom(
            target: DungeonDoorTracker.Door?,
            connectedCells: List<DungeonRoomTracker.CellKey?>?,
        ): Boolean = BloodDoorTraceRulesLogic.isBoundaryOfConnectedRoom(target, connectedCells)

        @JvmStatic
        fun hasClosedBloodDoorBlocks(door: DungeonDoorTracker.Door?, isBloodDoorBlock: Predicate<BlockPos>?): Boolean =
            BloodDoorTraceRulesLogic.hasClosedBloodDoorBlocks(door, isBloodDoorBlock)

        @JvmStatic
        fun hasClosedWitherDoorBlocks(door: DungeonDoorTracker.Door?, isWitherDoorBlock: Predicate<BlockPos>?): Boolean =
            BloodDoorTraceRulesLogic.hasClosedWitherDoorBlocks(door, isWitherDoorBlock)

        @JvmStatic
        fun doorPresence(
            door: DungeonDoorTracker.Door?,
            type: DungeonDoorTracker.Type?,
            isLoaded: Predicate<BlockPos>?,
            isDoorBlock: Predicate<BlockPos>?,
        ): DoorPresence = BloodDoorTraceRulesLogic.doorPresence(door, type, isLoaded, isDoorBlock)
    }

    enum class DoorPresence { CLOSED, OPEN, UNKNOWN }
}
