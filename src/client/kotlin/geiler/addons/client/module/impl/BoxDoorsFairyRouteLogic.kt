package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.BloodDoorTraceRules
import geiler.addons.client.dungeon.DungeonDoorTracker
import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.helpers.DungeonMapGrid
import java.util.ArrayDeque
import java.util.Locale
import java.util.function.Function
import net.minecraft.world.phys.Vec3

/** Kotlin implementation of the map-derived Box Doors route rules. */
object BoxDoorsFairyRouteLogic {
    private const val GRID_SIZE = 6
    private const val CELL_COUNT = GRID_SIZE * GRID_SIZE

    fun mapCellAtMarker(
        map: DungeonMapGrid.ScanResult?,
        marker: DungeonMapGeometry.MapPoint?,
    ): BoxDoorsFairyRouteRules.MapCellResolution {
        if (map == null || !map.available()) return BoxDoorsFairyRouteRules.MapCellResolution(-1, "map-scan-unavailable")
        val layout = map.layout()
        if (layout == null || !layout.available || layout.roomSize <= 0 || layout.roomGap <= 0)
            return BoxDoorsFairyRouteRules.MapCellResolution(-1, "map-layout-unavailable")
        if (marker == null) return BoxDoorsFairyRouteRules.MapCellResolution(-1, "player-map-marker-unavailable")
        if (marker.x < 0 || marker.z < 0 || marker.x >= 128 || marker.z >= 128)
            return BoxDoorsFairyRouteRules.MapCellResolution(-1, "player-map-marker-out-of-bounds")

        val relativeX = marker.x - layout.startX
        val relativeZ = marker.z - layout.startY
        if (relativeX < 0 || relativeZ < 0)
            return BoxDoorsFairyRouteRules.MapCellResolution(-1, "player-map-marker-outside-grid")
        val x = relativeX / layout.roomGap
        val z = relativeZ / layout.roomGap
        if (!validCell(x, z)) return BoxDoorsFairyRouteRules.MapCellResolution(-1, "player-map-marker-outside-grid")
        if (relativeX % layout.roomGap >= layout.roomSize || relativeZ % layout.roomGap >= layout.roomSize)
            return BoxDoorsFairyRouteRules.MapCellResolution(-1, "player-map-marker-in-room-gap")

        val cell = z * GRID_SIZE + x
        val tiles = map.tiles()
        if (tiles == null || tiles.size <= cell)
            return BoxDoorsFairyRouteRules.MapCellResolution(-1, "map-tile-unavailable")
        val tile = tiles[cell]
        if (tile == null || tile.index != cell)
            return BoxDoorsFairyRouteRules.MapCellResolution(-1, "map-tile-index-mismatch")
        val type = tile.type
        if (type == null || type == DungeonMapGrid.MapRoomType.UNKNOWN ||
            type == DungeonMapGrid.MapRoomType.UNDISCOVERED
        ) return BoxDoorsFairyRouteRules.MapCellResolution(-1, "player-map-marker-on-unknown-tile")
        return BoxDoorsFairyRouteRules.MapCellResolution(cell, "inside-recognized-tile")
    }

    fun select(
        map: DungeonMapGrid.ScanResult?, currentMapCell: Int, currentWorldCell: Int,
        observedDoors: List<DungeonDoorTracker.Door>?,
    ): BoxDoorsFairyRouteRules.Selection = select(
        map, currentMapCell, currentWorldCell, observedDoors,
        Function { _: DungeonDoorTracker.Door -> BloodDoorTraceRules.DoorPresence.CLOSED },
    )

    fun select(
        map: DungeonMapGrid.ScanResult?, currentMapCell: Int, currentWorldCell: Int,
        observedDoors: List<DungeonDoorTracker.Door>?,
        presenceOf: Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>?,
    ): BoxDoorsFairyRouteRules.Selection {
        if (map == null || !map.available())
            return failure(BoxDoorsFairyRouteRules.Status.MAP_UNAVAILABLE, currentMapCell, 0, 0,
                "unavailable", "map-scan-unavailable", 0, 0)
        if (!validCell(currentMapCell) || !validCell(currentWorldCell))
            return failure(BoxDoorsFairyRouteRules.Status.TRANSFORM_UNAVAILABLE, currentMapCell, 0, 0,
                "unavailable", "calibrated-map-or-world-current-cell-unavailable", 0, 0)
        val offsetX = currentWorldCell % GRID_SIZE - currentMapCell % GRID_SIZE
        val offsetZ = currentWorldCell / GRID_SIZE - currentMapCell / GRID_SIZE
        return select(map, currentMapCell, offsetX, offsetZ, observedDoors, presenceOf)
    }

    fun select(
        map: DungeonMapGrid.ScanResult?, currentMapCell: Int, offsetX: Int, offsetZ: Int,
        observedDoors: List<DungeonDoorTracker.Door>?,
    ): BoxDoorsFairyRouteRules.Selection = select(
        map, currentMapCell, offsetX, offsetZ, observedDoors,
        Function { _: DungeonDoorTracker.Door -> BloodDoorTraceRules.DoorPresence.CLOSED },
    )

    /** Resolves the unique room-group route and matches its exact physical door edge. */
    fun select(
        map: DungeonMapGrid.ScanResult?, currentMapCell: Int, offsetX: Int, offsetZ: Int,
        observedDoors: List<DungeonDoorTracker.Door>?,
        presenceOf: Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>?,
    ): BoxDoorsFairyRouteRules.Selection {
        val layout = map?.layout()
        if (map == null || !map.available() || layout == null || !layout.available)
            return failure(BoxDoorsFairyRouteRules.Status.MAP_UNAVAILABLE, currentMapCell, offsetX, offsetZ,
                "unavailable", "map-scan-or-layout-unavailable", 0, 0)
        if (!validCell(currentMapCell))
            return failure(BoxDoorsFairyRouteRules.Status.CURRENT_CELL_UNAVAILABLE, currentMapCell, offsetX, offsetZ,
                "unavailable", "map-current-cell-unavailable", 0, 0)

        val route = buildRoute(map, offsetX, offsetZ)
        if (route.status != null)
            return failure(route.status, currentMapCell, offsetX, offsetZ,
                route.state, route.rejection, route.roomCount, route.edgeCount)

        val currentRoom = route.ownerByCell[currentMapCell]
        if (currentRoom < 0)
            return failure(BoxDoorsFairyRouteRules.Status.CURRENT_ROOM_OFF_ROUTE, currentMapCell, offsetX, offsetZ,
                "unique", "current-map-cell-has-no-recognized-room", route.roomCount, route.edgeCount)
        val firstRouteEdge = route.roomPath.indexOf(currentRoom)
        if (firstRouteEdge < 0)
            return failure(BoxDoorsFairyRouteRules.Status.CURRENT_ROOM_OFF_ROUTE, currentMapCell, offsetX, offsetZ,
                "unique", "current-room-is-not-on-Entrance-to-Blood-route", route.roomCount, route.edgeCount)

        for (routeIndex in firstRouteEdge until route.pathEdges.size) {
            val edge = route.pathEdges[routeIndex]
            val expectedType = trackerType(edge.mapEdge.type) ?: continue
            val transition = transition(edge)
            val candidate = selection(
                selectedStatus(transition), transition, currentMapCell, edge, expectedType,
                observedDoors, presenceOf, offsetX, offsetZ,
                translatedCurrentFootprint(route, currentRoom, offsetX, offsetZ),
                route.roomCount, route.edgeCount,
            )
            if (candidate.status() == BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_OPEN) continue
            return candidate
        }
        return failure(BoxDoorsFairyRouteRules.Status.ROUTE_COMPLETE, currentMapCell, offsetX, offsetZ,
            "unique", "no-closed-key-door-ahead-on-route", route.roomCount, route.edgeCount)
    }

    private fun selection(
        selectedStatus: BoxDoorsFairyRouteRules.Status,
        transition: BoxDoorsFairyRouteRules.Transition,
        currentMapCell: Int,
        edge: RouteEdge,
        expectedType: DungeonDoorTracker.Type,
        observedDoors: List<DungeonDoorTracker.Door>?,
        presenceOf: Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>?,
        offsetX: Int,
        offsetZ: Int,
        currentRoomFootprint: List<Int>,
        routeRoomCount: Int,
        routeEdgeCount: Int,
    ): BoxDoorsFairyRouteRules.Selection {
        var match: DungeonDoorTracker.Door? = null
        var matches = 0
        if (observedDoors != null) for (door in observedDoors) {
            if (door == null || door.type() != expectedType ||
                !sameEdge(door.firstCell(), door.secondCell(), edge.worldFrom, edge.worldTo)
            ) continue
            match = door
            matches++
        }

        val status: BoxDoorsFairyRouteRules.Status
        val rejection: String
        when {
            matches == 0 -> {
                status = BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_MISSING
                rejection = "exact-type-and-world-edge-not-observed"
            }
            matches != 1 -> {
                status = BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_AMBIGUOUS
                rejection = "multiple-observed-records-match-exact-world-edge"
            }
            else -> {
                val presence = presenceOf?.apply(match!!) ?: BloodDoorTraceRules.DoorPresence.UNKNOWN
                when (presence) {
                    BloodDoorTraceRules.DoorPresence.OPEN -> {
                        status = BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_OPEN
                        rejection = "exact-route-door-is-open"
                    }
                    BloodDoorTraceRules.DoorPresence.CLOSED -> {
                        status = selectedStatus
                        rejection = "none"
                    }
                    else -> {
                        status = BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_STATE_UNKNOWN
                        rejection = "exact-route-door-state-unknown"
                    }
                }
            }
        }
        return BoxDoorsFairyRouteRules.Selection(
            status, transition, currentMapCell, edge.mapFrom, edge.mapTo, edge.worldFrom, edge.worldTo,
            edge.mapEdge.type, expectedType, if (matches == 1) match else null,
            offsetX, offsetZ, edge.targetRoomFootprint, currentRoomFootprint,
            routeRoomCount, routeEdgeCount, "unique", rejection,
        )
    }

    fun chooseTracerTarget(
        route: BoxDoorsFairyRouteRules.Selection?,
        orderedWitherDoors: List<DungeonDoorTracker.Door>?,
        bloodDoor: DungeonDoorTracker.Door?,
        presenceOf: Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>?,
    ): BoxDoorsFairyRouteRules.TraceChoice {
        if (route != null && isSelectedRouteStatus(route.status()) && route.target() != null)
            return BoxDoorsFairyRouteRules.TraceChoice(route.target(), BoxDoorsFairyRouteRules.TargetSource.MAP_ROUTE,
                0, "none")
        if (route != null && shouldPromptScan(route.status()))
            return BoxDoorsFairyRouteRules.TraceChoice(null,
                BoxDoorsFairyRouteRules.TargetSource.WAITING_FOR_ROUTE_EDGE, 0, route.rejectionReason())
        if (route != null && !allowsTrackerFallback(route.status()))
            return BoxDoorsFairyRouteRules.TraceChoice(null,
                BoxDoorsFairyRouteRules.TargetSource.ROUTE_HAS_NO_TARGET, 0, route.rejectionReason())
        return chooseTrackerFallback(orderedWitherDoors, bloodDoor, presenceOf)
    }

    fun chooseTracerTarget(
        route: BoxDoorsFairyRouteRules.Selection?,
        orderedWitherDoors: List<DungeonDoorTracker.Door>?,
        bloodDoor: DungeonDoorTracker.Door?,
    ): BoxDoorsFairyRouteRules.TraceChoice = chooseTracerTarget(
        route, orderedWitherDoors, bloodDoor,
        Function { _: DungeonDoorTracker.Door -> BloodDoorTraceRules.DoorPresence.CLOSED },
    )

    @Deprecated("Compatibility overload retained for callers of the former room-boundary selector")
    fun chooseTracerTarget(
        route: BoxDoorsFairyRouteRules.Selection?,
        closedObservedDoors: List<DungeonDoorTracker.Door>?,
        confirmedRoomFootprint: Set<Int>?,
    ): BoxDoorsFairyRouteRules.TraceChoice {
        if (route != null && isSelectedRouteStatus(route.status()) && route.target() != null)
            return BoxDoorsFairyRouteRules.TraceChoice(route.target(), BoxDoorsFairyRouteRules.TargetSource.MAP_ROUTE,
                0, "none")
        if (route != null && shouldPromptScan(route.status()))
            return BoxDoorsFairyRouteRules.TraceChoice(null,
                BoxDoorsFairyRouteRules.TargetSource.WAITING_FOR_ROUTE_EDGE, 0, route.rejectionReason())
        if (route != null && !allowsTrackerFallback(route.status()))
            return BoxDoorsFairyRouteRules.TraceChoice(null,
                BoxDoorsFairyRouteRules.TargetSource.ROUTE_HAS_NO_TARGET, 0, route.rejectionReason())
        val local = selectLocal(closedObservedDoors, confirmedRoomFootprint)
        return when (local.status()) {
            BoxDoorsFairyRouteRules.LocalStatus.SELECTED -> BoxDoorsFairyRouteRules.TraceChoice(
                local.target(), BoxDoorsFairyRouteRules.TargetSource.LOCAL_FALLBACK, local.candidateCount(), "none")
            BoxDoorsFairyRouteRules.LocalStatus.NONE -> BoxDoorsFairyRouteRules.TraceChoice(
                null, BoxDoorsFairyRouteRules.TargetSource.LOCAL_NONE, local.candidateCount(), local.rejectionReason())
            BoxDoorsFairyRouteRules.LocalStatus.AMBIGUOUS -> BoxDoorsFairyRouteRules.TraceChoice(
                null, BoxDoorsFairyRouteRules.TargetSource.LOCAL_AMBIGUOUS, local.candidateCount(), local.rejectionReason())
            null -> throw NullPointerException("local selection status")
        }
    }

    @Deprecated("Compatibility-only geometry helper")
    fun selectLocal(
        closedObservedDoors: List<DungeonDoorTracker.Door>?,
        confirmedRoomFootprint: Set<Int>?,
    ): BoxDoorsFairyRouteRules.LocalSelection {
        if (confirmedRoomFootprint.isNullOrEmpty())
            return BoxDoorsFairyRouteRules.LocalSelection(
                BoxDoorsFairyRouteRules.LocalStatus.NONE, null, 0, "current-room-footprint-unconfirmed")
        val candidates = ArrayList<DungeonDoorTracker.Door>()
        if (closedObservedDoors != null) for (door in closedObservedDoors) {
            if (door == null || (door.type() != DungeonDoorTracker.Type.WITHER &&
                    door.type() != DungeonDoorTracker.Type.BLOOD)
            ) continue
            val first = door.firstCell()
            val second = door.secondCell()
            if (!validCell(first) || !validCell(second)) continue
            val dx = kotlin.math.abs(first % GRID_SIZE - second % GRID_SIZE)
            val dz = kotlin.math.abs(first / GRID_SIZE - second / GRID_SIZE)
            if (dx + dz != 1) continue
            if (confirmedRoomFootprint.contains(first) != confirmedRoomFootprint.contains(second))
                candidates.add(door)
        }
        if (candidates.isEmpty())
            return BoxDoorsFairyRouteRules.LocalSelection(
                BoxDoorsFairyRouteRules.LocalStatus.NONE, null, 0,
                "no-closed-key-door-on-current-room-boundary")
        if (candidates.size != 1)
            return BoxDoorsFairyRouteRules.LocalSelection(
                BoxDoorsFairyRouteRules.LocalStatus.AMBIGUOUS, null, candidates.size,
                "multiple-closed-key-doors-on-current-room-boundary")
        return BoxDoorsFairyRouteRules.LocalSelection(
            BoxDoorsFairyRouteRules.LocalStatus.SELECTED, candidates.first(), 1, "none")
    }

    private fun chooseTrackerFallback(
        orderedWitherDoors: List<DungeonDoorTracker.Door>?,
        bloodDoor: DungeonDoorTracker.Door?,
        presenceOf: Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>?,
    ): BoxDoorsFairyRouteRules.TraceChoice {
        var inspected = 0
        if (orderedWitherDoors != null) for (door in orderedWitherDoors) {
            if (door == null || door.type() != DungeonDoorTracker.Type.WITHER) continue
            inspected++
            val presence = presenceOf?.apply(door) ?: BloodDoorTraceRules.DoorPresence.UNKNOWN
            if (presence == BloodDoorTraceRules.DoorPresence.OPEN) continue
            return BoxDoorsFairyRouteRules.TraceChoice(door,
                BoxDoorsFairyRouteRules.TargetSource.TRACKER_WITHER_ROUTE, inspected,
                if (presence == BloodDoorTraceRules.DoorPresence.UNKNOWN) "tracker-wither-state-unknown" else "none")
        }
        if (bloodDoor != null && bloodDoor.type() == DungeonDoorTracker.Type.BLOOD) {
            inspected++
            val presence = presenceOf?.apply(bloodDoor) ?: BloodDoorTraceRules.DoorPresence.UNKNOWN
            if (presence == BloodDoorTraceRules.DoorPresence.OPEN)
                return BoxDoorsFairyRouteRules.TraceChoice(null,
                    BoxDoorsFairyRouteRules.TargetSource.TRACKER_ROUTE_COMPLETE, inspected, "blood-door-open")
            return BoxDoorsFairyRouteRules.TraceChoice(bloodDoor,
                BoxDoorsFairyRouteRules.TargetSource.TRACKER_BLOOD_DOOR, inspected,
                if (presence == BloodDoorTraceRules.DoorPresence.UNKNOWN) "tracker-blood-state-unknown" else "none")
        }
        return BoxDoorsFairyRouteRules.TraceChoice(null,
            BoxDoorsFairyRouteRules.TargetSource.TRACKER_ROUTE_COMPLETE, inspected,
            if (inspected == 0) "no-tracker-door-records" else "wither-route-open-blood-door-unavailable")
    }

    private fun allowsTrackerFallback(status: BoxDoorsFairyRouteRules.Status?): Boolean = when (status) {
        BoxDoorsFairyRouteRules.Status.MAP_UNAVAILABLE,
        BoxDoorsFairyRouteRules.Status.TRANSFORM_UNAVAILABLE,
        BoxDoorsFairyRouteRules.Status.CURRENT_CELL_UNAVAILABLE,
        BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
        BoxDoorsFairyRouteRules.Status.ROUTE_AMBIGUOUS,
        BoxDoorsFairyRouteRules.Status.CURRENT_ROOM_OFF_ROUTE -> true
        null -> throw NullPointerException("route status")
        else -> false
    }

    private fun shouldPromptScan(status: BoxDoorsFairyRouteRules.Status?): Boolean = when (status) {
        BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_MISSING,
        BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_AMBIGUOUS,
        BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_STATE_UNKNOWN -> true
        null -> throw NullPointerException("route status")
        else -> false
    }

    private fun isSelectedRouteStatus(status: BoxDoorsFairyRouteRules.Status?): Boolean = when (status) {
        BoxDoorsFairyRouteRules.Status.SELECTED_ENTRY,
        BoxDoorsFairyRouteRules.Status.SELECTED_EXIT,
        BoxDoorsFairyRouteRules.Status.SELECTED_BLOOD_EXIT,
        BoxDoorsFairyRouteRules.Status.SELECTED_ROUTE_STEP -> true
        null -> throw NullPointerException("route status")
        else -> false
    }

    private fun buildRoute(map: DungeonMapGrid.ScanResult, offsetX: Int, offsetZ: Int): RouteBuild {
        val roomCount = map.rooms().size
        if (roomCount == 0)
            return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
                "no-recognized-map-rooms", "incomplete", 0, map.doors().size)
        val ownerByCell = IntArray(CELL_COUNT) { -1 }
        val entrances = ArrayList<Int>()
        val bloodRooms = ArrayList<Int>()
        for (roomIndex in 0 until roomCount) {
            val room = map.rooms()[roomIndex]
            if (room == null || room.type() == null || room.segments().isEmpty())
                return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
                    "empty-or-untyped-room-group", "incomplete", roomCount, map.doors().size)
            for (cell in room.segments()) {
                if (!validCell(cell) || ownerByCell[cell] >= 0)
                    return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
                        "room-segment-invalid-or-owned-by-multiple-groups", "incomplete", roomCount, map.doors().size)
                ownerByCell[cell] = roomIndex
            }
            if (room.type() == DungeonMapGrid.MapRoomType.ENTRANCE) entrances.add(roomIndex)
            if (room.type() == DungeonMapGrid.MapRoomType.BLOOD) bloodRooms.add(roomIndex)
        }
        if (entrances.size > 1 || bloodRooms.size > 1)
            return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_AMBIGUOUS,
                "Entrance-groups=${entrances.size};Blood-groups=${bloodRooms.size}",
                "ambiguous", roomCount, map.doors().size)
        if (entrances.size != 1 || bloodRooms.size != 1)
            return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
                "Entrance-groups=${entrances.size};Blood-groups=${bloodRooms.size}",
                "incomplete", roomCount, map.doors().size)

        val graph = ArrayList<MutableList<GraphEdge>>(roomCount)
        repeat(roomCount) { graph.add(ArrayList()) }
        var edgeId = 0
        for (door in map.doors()) {
            if (!validEdge(door))
                return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
                    "invalid-map-door-edge", "incomplete", roomCount, map.doors().size)
            val firstOwner = ownerByCell[door.firstCell]
            val secondOwner = ownerByCell[door.secondCell]
            if (firstOwner < 0 || secondOwner < 0)
                return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
                    "map-door-endpoint-has-no-recognized-room-group", "incomplete", roomCount, map.doors().size)
            if (firstOwner == secondOwner) continue
            val graphEdge = GraphEdge(edgeId++, firstOwner, secondOwner, door)
            graph[firstOwner].add(graphEdge)
            graph[secondOwner].add(graphEdge)
        }

        val entrance = entrances.first()
        val blood = bloodRooms.first()
        val firstPath = findPath(graph, entrance, blood, -1)
            ?: return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
                "Entrance-and-Blood-groups-are-disconnected", "incomplete", roomCount, edgeId)
        for (routeEdge in firstPath) {
            if (findPath(graph, entrance, blood, routeEdge.id) != null)
                return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_AMBIGUOUS,
                    "multiple-simple-Entrance-to-Blood-paths", "ambiguous", roomCount, edgeId)
        }

        val roomPath = ArrayList<Int>(firstPath.size + 1)
        val translatedPath = ArrayList<RouteEdge>(firstPath.size)
        var currentRoom = entrance
        roomPath.add(currentRoom)
        for (graphEdge in firstPath) {
            val nextRoom = graphEdge.otherRoom(currentRoom)
            val mapDoor = graphEdge.door
            val mapFrom = if (ownerByCell[mapDoor.firstCell] == currentRoom) mapDoor.firstCell else mapDoor.secondCell
            val mapTo = if (mapFrom == mapDoor.firstCell) mapDoor.secondCell else mapDoor.firstCell
            val worldFrom = translate(mapFrom, offsetX, offsetZ)
            val worldTo = translate(mapTo, offsetX, offsetZ)
            if (worldFrom < 0 || worldTo < 0)
                return RouteBuild.failure(BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
                    "translated-route-edge-falls-outside-world-grid", "incomplete", roomCount, edgeId)
            translatedPath.add(RouteEdge(mapFrom, mapTo, worldFrom, worldTo, mapDoor,
                map.rooms()[currentRoom].type()!!, map.rooms()[nextRoom].type()!!,
                translateFootprint(map.rooms()[nextRoom].segments(), offsetX, offsetZ)))
            currentRoom = nextRoom
            roomPath.add(currentRoom)
        }
        return RouteBuild.success(ownerByCell, roomPath, translatedPath, roomCount, edgeId)
    }

    private fun translateFootprint(mapCells: List<Int>, offsetX: Int, offsetZ: Int): List<Int> {
        val worldCells = ArrayList<Int>(mapCells.size)
        for (mapCell in mapCells) {
            val worldCell = translate(mapCell, offsetX, offsetZ)
            if (worldCell < 0) return emptyList()
            worldCells.add(worldCell)
        }
        return java.util.List.copyOf(worldCells)
    }

    private fun translatedCurrentFootprint(route: RouteBuild, roomIndex: Int, offsetX: Int, offsetZ: Int): List<Int> {
        for (edge in route.pathEdges) {
            if (edge.toRoomType == DungeonMapGrid.MapRoomType.FAIRY && route.ownerByCell[edge.mapTo] == roomIndex)
                return edge.targetRoomFootprint
        }
        for (pathIndex in route.roomPath.indices) {
            if (route.roomPath[pathIndex] == roomIndex) {
                if (pathIndex > 0) return route.pathEdges[pathIndex - 1].targetRoomFootprint
                return translateFootprintFromOwner(route, roomIndex, offsetX, offsetZ)
            }
        }
        return emptyList()
    }

    private fun translateFootprintFromOwner(route: RouteBuild, roomIndex: Int, offsetX: Int, offsetZ: Int): List<Int> {
        val cells = ArrayList<Int>()
        for (cell in route.ownerByCell.indices) if (route.ownerByCell[cell] == roomIndex) {
            val translated = translate(cell, offsetX, offsetZ)
            if (translated < 0) return emptyList()
            cells.add(translated)
        }
        return java.util.List.copyOf(cells)
    }

    private fun findPath(graph: List<List<GraphEdge>>, start: Int, target: Int, blockedEdgeId: Int): List<GraphEdge>? {
        val previousRoom = IntArray(graph.size) { -1 }
        val previousEdge = arrayOfNulls<GraphEdge>(graph.size)
        val pending = ArrayDeque<Int>()
        previousRoom[start] = start
        pending.addLast(start)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current == target) break
            for (edge in graph[current]) {
                if (edge.id == blockedEdgeId) continue
                val next = edge.otherRoom(current)
                if (previousRoom[next] >= 0) continue
                previousRoom[next] = current
                previousEdge[next] = edge
                pending.addLast(next)
            }
        }
        if (previousRoom[target] < 0) return null
        val reverse = ArrayDeque<GraphEdge>()
        var at = target
        while (at != start) {
            val edge = previousEdge[at] ?: return null
            reverse.addFirst(edge)
            at = previousRoom[at]
        }
        return java.util.List.copyOf(reverse)
    }

    private fun transition(edge: RouteEdge): BoxDoorsFairyRouteRules.Transition = when {
        edge.mapEdge.type == DungeonMapGrid.DoorType.BLOOD &&
            edge.toRoomType == DungeonMapGrid.MapRoomType.BLOOD ->
            if (edge.fromRoomType == DungeonMapGrid.MapRoomType.FAIRY)
                BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY_TO_BLOOD
            else BoxDoorsFairyRouteRules.Transition.FOLLOW_ROUTE
        edge.toRoomType == DungeonMapGrid.MapRoomType.FAIRY -> BoxDoorsFairyRouteRules.Transition.ENTER_FAIRY
        edge.fromRoomType == DungeonMapGrid.MapRoomType.FAIRY -> BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY
        else -> BoxDoorsFairyRouteRules.Transition.FOLLOW_ROUTE
    }

    private fun selectedStatus(transition: BoxDoorsFairyRouteRules.Transition): BoxDoorsFairyRouteRules.Status = when (transition) {
        BoxDoorsFairyRouteRules.Transition.ENTER_FAIRY -> BoxDoorsFairyRouteRules.Status.SELECTED_ENTRY
        BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY -> BoxDoorsFairyRouteRules.Status.SELECTED_EXIT
        BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY_TO_BLOOD -> BoxDoorsFairyRouteRules.Status.SELECTED_BLOOD_EXIT
        else -> BoxDoorsFairyRouteRules.Status.SELECTED_ROUTE_STEP
    }

    fun mapToWorldCell(mapCell: Int, offsetX: Int, offsetZ: Int): Int = translate(mapCell, offsetX, offsetZ)

    fun isValidCell(cell: Int): Boolean = validCell(cell)

    fun diagnostic(selection: BoxDoorsFairyRouteRules.Selection): String {
        val mapType = selection.mapEdgeType()?.name?.lowercase(Locale.ROOT) ?: "none"
        val expectedType = selection.expectedType()?.name?.lowercase(Locale.ROOT) ?: "none"
        val target = selection.target()
        val observedType = if (target == null) "none" else target.type()?.name?.lowercase(Locale.ROOT) ?: "unknown"
        return "route=${selection.routeState()},rooms=${selection.routeRoomCount()},edges=${selection.routeEdgeCount()}" +
            ",transform=offset(${selection.offsetX()},${selection.offsetZ()})" +
            ",currentMap=${diagnosticCell(selection.currentMapCell())}" +
            ",mapEdge=${diagnosticEdge(selection.mapFromCell(), selection.mapToCell())}" +
            ",worldEdge=${diagnosticEdge(selection.fromCell(), selection.toCell())}" +
            ",mapType=$mapType,expected=$expectedType" +
            ",status=${selection.status()!!.name.lowercase(Locale.ROOT)}" +
            ",transition=${selection.transition()!!.name.lowercase(Locale.ROOT)}" +
            ",observed=$observedType,rejection=${selection.rejectionReason()}"
    }

    /** Returns the centroid of the translated world-room footprint at its walkable floor level. */
    fun fairyRoomCenter(worldFootprint: List<Int>?): Vec3? {
        if (worldFootprint.isNullOrEmpty()) return null
        var x = 0.0
        var z = 0.0
        for (cell in worldFootprint) {
            if (!validCell(cell)) return null
            val componentX = cell % GRID_SIZE
            val componentZ = cell / GRID_SIZE
            x += DungeonGridRules.GRID_MIN + componentX * DungeonGridRules.CELL_STRIDE + DungeonGridRules.ROOM_SIZE / 2.0
            z += DungeonGridRules.GRID_MIN + componentZ * DungeonGridRules.CELL_STRIDE + DungeonGridRules.ROOM_SIZE / 2.0
        }
        return Vec3(x / worldFootprint.size, DungeonGridRules.SCAN_MIN_Y + 1.0, z / worldFootprint.size)
    }

    private fun trackerType(type: DungeonMapGrid.DoorType?): DungeonDoorTracker.Type? = when (type) {
        DungeonMapGrid.DoorType.BLOOD -> DungeonDoorTracker.Type.BLOOD
        DungeonMapGrid.DoorType.WITHER, DungeonMapGrid.DoorType.FAIRY -> DungeonDoorTracker.Type.WITHER
        else -> null
    }

    private fun validEdge(edge: DungeonMapGrid.Door?): Boolean {
        if (edge == null || edge.type == null || !validCell(edge.firstCell) || !validCell(edge.secondCell)) return false
        val dx = kotlin.math.abs(edge.firstCell % GRID_SIZE - edge.secondCell % GRID_SIZE)
        val dz = kotlin.math.abs(edge.firstCell / GRID_SIZE - edge.secondCell / GRID_SIZE)
        return dx + dz == 1
    }

    private fun sameEdge(first: Int, second: Int, candidateFirst: Int, candidateSecond: Int): Boolean =
        (first == candidateFirst && second == candidateSecond) ||
            (first == candidateSecond && second == candidateFirst)

    private fun validCell(cell: Int): Boolean = cell >= 0 && cell < CELL_COUNT
    private fun validCell(x: Int, z: Int): Boolean = x >= 0 && x < GRID_SIZE && z >= 0 && z < GRID_SIZE

    private fun diagnosticCell(cell: Int): String = if (validCell(cell)) cell.toString() else "unknown"

    private fun diagnosticEdge(first: Int, second: Int): String =
        if (validCell(first) && validCell(second)) "$first-$second" else "none"

    private fun translate(mapCell: Int, offsetX: Int, offsetZ: Int): Int {
        if (!validCell(mapCell)) return -1
        val x = mapCell % GRID_SIZE + offsetX
        val z = mapCell / GRID_SIZE + offsetZ
        return if (validCell(x, z)) z * GRID_SIZE + x else -1
    }

    private fun failure(
        status: BoxDoorsFairyRouteRules.Status, currentMapCell: Int, offsetX: Int, offsetZ: Int,
        routeState: String, rejection: String, roomCount: Int, edgeCount: Int,
    ) = BoxDoorsFairyRouteRules.Selection(
        status, BoxDoorsFairyRouteRules.Transition.NONE, currentMapCell,
        -1, -1, -1, -1, null, null, null, offsetX, offsetZ,
        emptyList(), emptyList(), roomCount, edgeCount, routeState, rejection,
    )

    private data class GraphEdge(
        val id: Int, val firstRoom: Int, val secondRoom: Int, val door: DungeonMapGrid.Door,
    ) {
        fun otherRoom(room: Int): Int = if (firstRoom == room) secondRoom else firstRoom
    }

    private data class RouteEdge(
        val mapFrom: Int,
        val mapTo: Int,
        val worldFrom: Int,
        val worldTo: Int,
        val mapEdge: DungeonMapGrid.Door,
        val fromRoomType: DungeonMapGrid.MapRoomType,
        val toRoomType: DungeonMapGrid.MapRoomType,
        val targetRoomFootprint: List<Int>,
    )

    private data class RouteBuild(
        val status: BoxDoorsFairyRouteRules.Status?,
        val state: String,
        val rejection: String,
        val ownerByCell: IntArray,
        val roomPath: List<Int>,
        val pathEdges: List<RouteEdge>,
        val roomCount: Int,
        val edgeCount: Int,
    ) {
        companion object {
            fun failure(
                status: BoxDoorsFairyRouteRules.Status, rejection: String, state: String, roomCount: Int, edgeCount: Int,
            ) = RouteBuild(status, state, rejection, IntArray(CELL_COUNT), emptyList(), emptyList(), roomCount, edgeCount)

            fun success(
                owners: IntArray, rooms: List<Int>, edges: List<RouteEdge>, roomCount: Int, edgeCount: Int,
            ) = RouteBuild(null, "unique", "none", owners, java.util.List.copyOf(rooms),
                java.util.List.copyOf(edges), roomCount, edgeCount)
        }
    }
}
