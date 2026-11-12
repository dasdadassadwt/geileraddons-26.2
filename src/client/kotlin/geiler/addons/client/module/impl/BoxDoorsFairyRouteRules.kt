package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.BloodDoorTraceRules
import geiler.addons.client.dungeon.DungeonDoorTracker
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.odin.OdinDungeonMapGrid
import java.util.function.Function
import net.minecraft.world.phys.Vec3

/** Java-callable API and value types for the Kotlin map-derived Box Doors route rules. */
class BoxDoorsFairyRouteRules private constructor() {
    companion object {
        @JvmStatic
        fun mapCellAtMarker(
            map: OdinDungeonMapGrid.ScanResult?,
            marker: DungeonMapGeometry.MapPoint?,
        ): MapCellResolution = BoxDoorsFairyRouteLogic.mapCellAtMarker(map, marker)

        @JvmStatic
        fun select(
            map: OdinDungeonMapGrid.ScanResult?,
            currentMapCell: Int,
            currentWorldCell: Int,
            observedDoors: List<DungeonDoorTracker.Door>?,
        ): Selection = BoxDoorsFairyRouteLogic.select(map, currentMapCell, currentWorldCell, observedDoors)

        @JvmStatic
        fun select(
            map: OdinDungeonMapGrid.ScanResult?,
            currentMapCell: Int,
            currentWorldCell: Int,
            observedDoors: List<DungeonDoorTracker.Door>?,
            presenceOf: Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>?,
        ): Selection = BoxDoorsFairyRouteLogic.select(
            map, currentMapCell, currentWorldCell, observedDoors, presenceOf,
        )

        @JvmStatic
        fun select(
            map: OdinDungeonMapGrid.ScanResult?,
            currentMapCell: Int,
            offsetX: Int,
            offsetZ: Int,
            observedDoors: List<DungeonDoorTracker.Door>?,
        ): Selection = BoxDoorsFairyRouteLogic.select(map, currentMapCell, offsetX, offsetZ, observedDoors)

        @JvmStatic
        fun select(
            map: OdinDungeonMapGrid.ScanResult?,
            currentMapCell: Int,
            offsetX: Int,
            offsetZ: Int,
            observedDoors: List<DungeonDoorTracker.Door>?,
            presenceOf: Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>?,
        ): Selection = BoxDoorsFairyRouteLogic.select(
            map, currentMapCell, offsetX, offsetZ, observedDoors, presenceOf,
        )

        @JvmStatic
        fun chooseTracerTarget(
            route: Selection?,
            orderedWitherDoors: List<DungeonDoorTracker.Door>?,
            bloodDoor: DungeonDoorTracker.Door?,
            presenceOf: Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>?,
        ): TraceChoice = BoxDoorsFairyRouteLogic.chooseTracerTarget(
            route, orderedWitherDoors, bloodDoor, presenceOf,
        )

        @JvmStatic
        fun chooseTracerTarget(
            route: Selection?,
            orderedWitherDoors: List<DungeonDoorTracker.Door>?,
            bloodDoor: DungeonDoorTracker.Door?,
        ): TraceChoice = BoxDoorsFairyRouteLogic.chooseTracerTarget(route, orderedWitherDoors, bloodDoor)

        /** Compatibility overload retained for callers of the former room-boundary selector. */
        @Deprecated("Compatibility overload retained for callers of the former room-boundary selector")
        @java.lang.Deprecated
        @JvmStatic
        fun chooseTracerTarget(
            route: Selection?,
            closedObservedDoors: List<DungeonDoorTracker.Door>?,
            confirmedRoomFootprint: Set<Int>?,
        ): TraceChoice = BoxDoorsFairyRouteLogic.chooseTracerTarget(
            route, closedObservedDoors, confirmedRoomFootprint,
        )

        /** Compatibility-only geometry helper; the Box Doors renderer no longer calls this. */
        @Deprecated("Compatibility-only geometry helper")
        @java.lang.Deprecated
        @JvmStatic
        fun selectLocal(
            closedObservedDoors: List<DungeonDoorTracker.Door>?,
            confirmedRoomFootprint: Set<Int>?,
        ): LocalSelection = BoxDoorsFairyRouteLogic.selectLocal(
            closedObservedDoors, confirmedRoomFootprint,
        )

        @JvmStatic
        fun mapToWorldCell(mapCell: Int, offsetX: Int, offsetZ: Int): Int =
            BoxDoorsFairyRouteLogic.mapToWorldCell(mapCell, offsetX, offsetZ)

        @JvmStatic
        fun fairyRoomCenter(worldFootprint: List<Int>?): Vec3? =
            BoxDoorsFairyRouteLogic.fairyRoomCenter(worldFootprint)
    }

    enum class Status {
        MAP_UNAVAILABLE,
        TRANSFORM_UNAVAILABLE,
        CURRENT_CELL_UNAVAILABLE,
        ROUTE_INCOMPLETE,
        ROUTE_AMBIGUOUS,
        CURRENT_ROOM_OFF_ROUTE,
        ROUTE_COMPLETE,
        NO_KEY_DOOR_ON_NEXT_EDGE,
        SELECTED_ENTRY,
        SELECTED_EXIT,
        SELECTED_BLOOD_EXIT,
        SELECTED_ROUTE_STEP,
        OBSERVED_EDGE_MISSING,
        OBSERVED_EDGE_AMBIGUOUS,
        OBSERVED_EDGE_OPEN,
        OBSERVED_EDGE_STATE_UNKNOWN,
    }

    enum class Transition { NONE, ENTER_FAIRY, EXIT_FAIRY, EXIT_FAIRY_TO_BLOOD, FOLLOW_ROUTE }

    @Deprecated("Compatibility status for the former room-boundary selector")
    @java.lang.Deprecated
    enum class LocalStatus { SELECTED, NONE, AMBIGUOUS }

    enum class TargetSource {
        MAP_ROUTE,
        WAITING_FOR_ROUTE_EDGE,
        ROUTE_HAS_NO_TARGET,
        TRACKER_WITHER_ROUTE,
        TRACKER_BLOOD_DOOR,
        TRACKER_ROUTE_COMPLETE,
        @Deprecated("Compatibility source for the former room-boundary selector")
        @java.lang.Deprecated
        LOCAL_FALLBACK,
        @Deprecated("Compatibility source for the former room-boundary selector")
        @java.lang.Deprecated
        LOCAL_NONE,
        @Deprecated("Compatibility source for the former room-boundary selector")
        @java.lang.Deprecated
        LOCAL_AMBIGUOUS,
    }

    @Deprecated("Compatibility result for the former room-boundary selector")
    @java.lang.Deprecated
    class LocalSelection(
        status: LocalStatus?,
        target: DungeonDoorTracker.Door?,
        candidateCount: Int,
        rejectionReason: String?,
    ) {
        private val statusValue = status
        private val targetValue = target
        private val candidateCountValue = candidateCount
        private val rejectionReasonValue = rejectionReason

        fun status(): LocalStatus? = statusValue
        fun target(): DungeonDoorTracker.Door? = targetValue
        fun candidateCount(): Int = candidateCountValue
        fun rejectionReason(): String? = rejectionReasonValue

        override fun equals(other: Any?): Boolean = other is LocalSelection &&
            statusValue == other.statusValue && targetValue == other.targetValue &&
            candidateCountValue == other.candidateCountValue && rejectionReasonValue == other.rejectionReasonValue

        override fun hashCode(): Int = boxDoorsRecordHash(
            statusValue, targetValue, candidateCountValue, rejectionReasonValue,
        )

        override fun toString(): String =
            "LocalSelection[status=$statusValue, target=$targetValue, candidateCount=$candidateCountValue, " +
                "rejectionReason=$rejectionReasonValue]"
    }

    class TraceChoice(
        target: DungeonDoorTracker.Door?,
        source: TargetSource?,
        localCandidateCount: Int,
        rejectionReason: String?,
    ) {
        private val targetValue = target
        private val sourceValue = source
        private val localCandidateCountValue = localCandidateCount
        private val rejectionReasonValue = rejectionReason

        fun target(): DungeonDoorTracker.Door? = targetValue
        fun source(): TargetSource? = sourceValue
        fun localCandidateCount(): Int = localCandidateCountValue
        fun candidateCount(): Int = localCandidateCountValue
        fun rejectionReason(): String? = rejectionReasonValue

        override fun equals(other: Any?): Boolean = other is TraceChoice &&
            targetValue == other.targetValue && sourceValue == other.sourceValue &&
            localCandidateCountValue == other.localCandidateCountValue && rejectionReasonValue == other.rejectionReasonValue

        override fun hashCode(): Int = boxDoorsRecordHash(
            targetValue, sourceValue, localCandidateCountValue, rejectionReasonValue,
        )

        override fun toString(): String =
            "TraceChoice[target=$targetValue, source=$sourceValue, " +
                "localCandidateCount=$localCandidateCountValue, rejectionReason=$rejectionReasonValue]"
    }

    class MapCellResolution(cell: Int, rejectionReason: String?) {
        private val cellValue = cell
        private val rejectionReasonValue = rejectionReason ?: "unknown"

        fun cell(): Int = cellValue
        fun rejectionReason(): String = rejectionReasonValue
        fun available(): Boolean = BoxDoorsFairyRouteLogic.isValidCell(cellValue)

        override fun equals(other: Any?): Boolean = other is MapCellResolution &&
            cellValue == other.cellValue && rejectionReasonValue == other.rejectionReasonValue

        override fun hashCode(): Int = boxDoorsRecordHash(cellValue, rejectionReasonValue)

        override fun toString(): String =
            "MapCellResolution[cell=$cellValue, rejectionReason=$rejectionReasonValue]"
    }

    class Selection(
        status: Status?,
        transition: Transition?,
        currentMapCell: Int,
        mapFromCell: Int,
        mapToCell: Int,
        fromCell: Int,
        toCell: Int,
        mapEdgeType: OdinDungeonMapGrid.DoorType?,
        expectedType: DungeonDoorTracker.Type?,
        target: DungeonDoorTracker.Door?,
        offsetX: Int,
        offsetZ: Int,
        targetRoomFootprint: List<Int>?,
        currentRoomFootprint: List<Int>?,
        routeRoomCount: Int,
        routeEdgeCount: Int,
        routeState: String?,
        rejectionReason: String?,
    ) {
        private val statusValue = status
        private val transitionValue = transition
        private val currentMapCellValue = currentMapCell
        private val mapFromCellValue = mapFromCell
        private val mapToCellValue = mapToCell
        private val fromCellValue = fromCell
        private val toCellValue = toCell
        private val mapEdgeTypeValue = mapEdgeType
        private val expectedTypeValue = expectedType
        private val targetValue = target
        private val offsetXValue = offsetX
        private val offsetZValue = offsetZ
        private val targetRoomFootprintValue = if (targetRoomFootprint == null) {
            java.util.List.of()
        } else {
            java.util.List.copyOf(targetRoomFootprint)
        }
        private val currentRoomFootprintValue = if (currentRoomFootprint == null) {
            java.util.List.of()
        } else {
            java.util.List.copyOf(currentRoomFootprint)
        }
        private val routeRoomCountValue = routeRoomCount
        private val routeEdgeCountValue = routeEdgeCount
        private val routeStateValue = routeState
        private val rejectionReasonValue = rejectionReason

        fun status(): Status? = statusValue
        fun transition(): Transition? = transitionValue
        fun currentMapCell(): Int = currentMapCellValue
        fun mapFromCell(): Int = mapFromCellValue
        fun mapToCell(): Int = mapToCellValue
        fun fromCell(): Int = fromCellValue
        fun toCell(): Int = toCellValue
        fun mapEdgeType(): OdinDungeonMapGrid.DoorType? = mapEdgeTypeValue
        fun expectedType(): DungeonDoorTracker.Type? = expectedTypeValue
        fun target(): DungeonDoorTracker.Door? = targetValue
        fun offsetX(): Int = offsetXValue
        fun offsetZ(): Int = offsetZValue
        fun targetRoomFootprint(): List<Int> = targetRoomFootprintValue
        fun currentRoomFootprint(): List<Int> = currentRoomFootprintValue
        fun routeRoomCount(): Int = routeRoomCountValue
        fun routeEdgeCount(): Int = routeEdgeCountValue
        fun routeState(): String? = routeStateValue
        fun rejectionReason(): String? = rejectionReasonValue
        fun diagnostic(): String = BoxDoorsFairyRouteLogic.diagnostic(this)

        override fun equals(other: Any?): Boolean = other is Selection &&
            statusValue == other.statusValue && transitionValue == other.transitionValue &&
            currentMapCellValue == other.currentMapCellValue && mapFromCellValue == other.mapFromCellValue &&
            mapToCellValue == other.mapToCellValue && fromCellValue == other.fromCellValue &&
            toCellValue == other.toCellValue && mapEdgeTypeValue == other.mapEdgeTypeValue &&
            expectedTypeValue == other.expectedTypeValue && targetValue == other.targetValue &&
            offsetXValue == other.offsetXValue && offsetZValue == other.offsetZValue &&
            targetRoomFootprintValue == other.targetRoomFootprintValue &&
            currentRoomFootprintValue == other.currentRoomFootprintValue &&
            routeRoomCountValue == other.routeRoomCountValue && routeEdgeCountValue == other.routeEdgeCountValue &&
            routeStateValue == other.routeStateValue && rejectionReasonValue == other.rejectionReasonValue

        override fun hashCode(): Int = boxDoorsRecordHash(
            statusValue, transitionValue, currentMapCellValue, mapFromCellValue, mapToCellValue,
            fromCellValue, toCellValue, mapEdgeTypeValue, expectedTypeValue, targetValue, offsetXValue,
            offsetZValue, targetRoomFootprintValue, currentRoomFootprintValue, routeRoomCountValue,
            routeEdgeCountValue, routeStateValue, rejectionReasonValue,
        )

        override fun toString(): String =
            "Selection[status=$statusValue, transition=$transitionValue, currentMapCell=$currentMapCellValue, " +
                "mapFromCell=$mapFromCellValue, mapToCell=$mapToCellValue, fromCell=$fromCellValue, " +
                "toCell=$toCellValue, mapEdgeType=$mapEdgeTypeValue, expectedType=$expectedTypeValue, " +
                "target=$targetValue, offsetX=$offsetXValue, offsetZ=$offsetZValue, " +
                "targetRoomFootprint=$targetRoomFootprintValue, currentRoomFootprint=$currentRoomFootprintValue, " +
                "routeRoomCount=$routeRoomCountValue, routeEdgeCount=$routeEdgeCountValue, " +
                "routeState=$routeStateValue, rejectionReason=$rejectionReasonValue]"
    }
}

private fun boxDoorsRecordHash(vararg components: Any?): Int {
    var hash = 0
    for (component in components) hash = 31 * hash + (component?.hashCode() ?: 0)
    return hash
}
