package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.TreeSet

/** Projects Odin map pixels and already-loaded signature observations into GeilerAddons' HUD model. */
class OdinDungeonMapProjection private constructor() {
    companion object {
        private const val MAX_DIAGNOSTIC_EXAMPLES = 8
        private const val PARTIAL_CORE_ROOM_PREFIX = "partial-core:"

        /** Java-callable overload retained for existing callers. */
        @JvmStatic
        fun project(
            source: OdinDungeonMapGrid.ScanResult?,
            cores: Map<Int, OdinDungeonMapCoreScanner.RoomCore>?,
            playerCell: DungeonGridRules.Cell?,
            players: List<DungeonRoomOverlayGeometry.PlayerMarker>?,
        ): DungeonRoomOverlayGeometry.Snapshot =
            project(source, cores, playerCell, players, -1, -1, false)

        /** Java-callable overload retained for existing callers. */
        @JvmStatic
        fun project(
            source: OdinDungeonMapGrid.ScanResult?,
            cores: Map<Int, OdinDungeonMapCoreScanner.RoomCore>?,
            playerCell: DungeonGridRules.Cell?,
            players: List<DungeonRoomOverlayGeometry.PlayerMarker>?,
            floorNumber: Int,
            puzzleCount: Int,
            predictionsEnabled: Boolean,
        ): DungeonRoomOverlayGeometry.Snapshot =
            project(source, cores, playerCell, players, floorNumber, puzzleCount, predictionsEnabled, emptyList())

        /** Combines map-pixel doors with positive loaded-client door observations from GeilerAddons. */
        @JvmStatic
        fun project(
            source: OdinDungeonMapGrid.ScanResult?,
            cores: Map<Int, OdinDungeonMapCoreScanner.RoomCore>?,
            playerCell: DungeonGridRules.Cell?,
            players: List<DungeonRoomOverlayGeometry.PlayerMarker>?,
            floorNumber: Int,
            puzzleCount: Int,
            predictionsEnabled: Boolean,
            observedDoors: List<DungeonRoomOverlayGeometry.ObservedDoor>?,
        ): DungeonRoomOverlayGeometry.Snapshot =
            project(source, cores, playerCell, players, floorNumber, puzzleCount, predictionsEnabled, observedDoors, emptySet())

        /** Adds cells whose rooms were already entered during this dungeon session. */
        @JvmStatic
        fun project(
            source: OdinDungeonMapGrid.ScanResult?,
            cores: Map<Int, OdinDungeonMapCoreScanner.RoomCore>?,
            playerCell: DungeonGridRules.Cell?,
            players: List<DungeonRoomOverlayGeometry.PlayerMarker>?,
            floorNumber: Int,
            puzzleCount: Int,
            predictionsEnabled: Boolean,
            observedDoors: List<DungeonRoomOverlayGeometry.ObservedDoor>?,
            walkedIntoTiles: Set<Int?>?,
        ): DungeonRoomOverlayGeometry.Snapshot {
            if (source == null || !source.available()) {
                return DungeonRoomOverlayGeometry.Snapshot.unavailable(
                    if (source == null) "Odin map scan was not supplied" else source.reason(),
                )
            }
            val observedCores = cores ?: emptyMap()
            val doorEvidence = doorEvidence(source, observedDoors)
            val visitedTiles = HashSet(validTiles(walkedIntoTiles))
            val playerTile = if (playerCell == null) -1 else playerCell.componentX + playerCell.componentZ * 6
            if (playerTile in 0..35) visitedTiles.add(playerTile)

            val cells = ArrayList<DungeonRoomOverlayGeometry.Cell>(36)
            val rooms = LinkedHashMap<String, DungeonRoomOverlayGeometry.Room>()
            val mappedTiles = HashSet<Int>()
            var unknownMapPixelsRejected = 0
            for (tile in source.tiles()) {
                if (tile != null && tile.type == OdinDungeonMapGrid.MapRoomType.UNKNOWN
                    && tile.roomColor == OdinDungeonMapGrid.MapRoomType.UNKNOWN.mapColor()) {
                    unknownMapPixelsRejected++
                }
            }
            var observedMapCells = 0
            var catalogMatches = 0
            var catalogPartialTileMatches = 0
            var catalogFootprintRejected = 0
            var catalogSignatureConflicts = 0
            var catalogTypeConflicts = 0
            var catalogUnknownColorMatches = 0
            var mapViewableRooms = 0
            var mapUnopenedRooms = 0
            var mapViewableCells = 0
            var mapUnopenedCells = 0
            val mapCheckmarks = LinkedHashMap<String, Int>()
            val typeConflictExamples = ArrayList<String>(MAX_DIAGNOSTIC_EXAMPLES)
            val partialCoreTiles = TreeSet<Int>()
            val partialCoreExamples = ArrayList<String>(MAX_DIAGNOSTIC_EXAMPLES)

            for (sourceRoom in source.rooms()) {
                val segments = sourceRoom.segments().sorted()
                observedMapCells += segments.size
                val checkmarkName = sourceRoom.checkmark()?.name ?: "MISSING"
                mapCheckmarks[checkmarkName] = (mapCheckmarks[checkmarkName] ?: 0) + 1
                val id = segmentKey(segments)
                val roomDataMatch = findRoomData(segments, observedCores)
                if (roomDataMatch.footprintMismatch) catalogFootprintRejected++
                if (roomDataMatch.signatureConflict) catalogSignatureConflicts++
                var data = roomDataMatch.data
                var catalogTypeConflict = false
                if (data != null && !catalogTypeCompatible(sourceRoom.type(), data.type)) {
                    catalogTypeConflicts++
                    catalogTypeConflict = true
                    if (typeConflictExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                        typeConflictExamples.add(id + ":" + sourceRoom.type() + "!=" + data.type)
                    }
                    data = null
                } else if (data != null) {
                    catalogMatches++
                    if (sourceRoom.type() == OdinDungeonMapGrid.MapRoomType.UNKNOWN) catalogUnknownColorMatches++
                }
                val type = if (data == null) mapType(sourceRoom.type()) else dataType(data.type)
                val walkedInto = segments.any { visitedTiles.contains(it) }
                val mapViewable = (sourceRoom.checkmark() != null
                    && sourceRoom.checkmark() != OdinDungeonMapGrid.Checkmark.UNDISCOVERED) || walkedInto
                val visibility = if (mapViewable) DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
                    else DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
                if (mapViewable) {
                    mapViewableRooms++
                    mapViewableCells += segments.size
                } else {
                    mapUnopenedRooms++
                    mapUnopenedCells += segments.size
                }
                val checkmark = sourceRoom.checkmark()?.name ?: ""
                val sourceRoomExits = exitsFor(segments, doorEvidence.edges)
                val partialDataByTile = LinkedHashMap<Int, OdinDungeonRoomCatalog.RoomData>()
                if (data == null && !catalogTypeConflict) {
                    for (tileIndex in segments) {
                        val tileData = observedCores[tileIndex]?.data ?: continue
                        if (!catalogTypeCompatible(sourceRoom.type(), tileData.type)) {
                            catalogTypeConflicts++
                            if (typeConflictExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                                typeConflictExamples.add(tileIndex.toString() + ":" + sourceRoom.type()
                                    + "!=" + tileData.type)
                            }
                            continue
                        }
                        // Exact core metadata may annotate a map-partial multi-tile room. It
                        // cannot split a larger map footprint into a smaller catalog room.
                        if (shapeTileCount(tileData.shape) <= segments.size) continue
                        partialDataByTile[tileIndex] = tileData
                    }
                }
                if (partialDataByTile.isNotEmpty()) {
                    catalogPartialTileMatches += partialDataByTile.size
                    for (tileIndex in segments) {
                        mappedTiles.add(tileIndex)
                        val tileData = partialDataByTile[tileIndex]
                        val tileId = if (tileData == null) "map-pixel:" + tileIndex
                            else PARTIAL_CORE_ROOM_PREFIX + tileIndex
                        val tileType = if (tileData == null) mapType(sourceRoom.type()) else dataType(tileData.type)
                        val cell = DungeonRoomOverlayGeometry.Cell(
                            tileIndex % 6, tileIndex / 6, tileType, tileId, visibility,
                        )
                        val exits = sourceRoomExits.filter { exit ->
                            val exitCell = exit.cell()
                            exitCell != null && exitCell.x() + exitCell.z() * 6 == tileIndex
                        }
                        cells.add(cell)
                        rooms[tileId] = DungeonRoomOverlayGeometry.Room(
                            tileId, tileData?.name ?: "Unknown", tileType, listOf(cell), exits,
                            false, false, visibility, tileData?.maxSecrets ?: 0, -1, checkmark,
                        )
                        if (tileData != null) {
                            partialCoreTiles.add(tileIndex)
                            if (partialCoreExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                                partialCoreExamples.add(tileIndex.toString() + ":" + tileData.name
                                    + ":" + tileData.shape)
                            }
                        }
                    }
                } else {
                    val footprint = ArrayList<DungeonRoomOverlayGeometry.Cell>(segments.size)
                    for (tileIndex in segments) {
                        val cell = DungeonRoomOverlayGeometry.Cell(
                            tileIndex % 6, tileIndex / 6, type, id, visibility,
                        )
                        mappedTiles.add(tileIndex)
                        cells.add(cell)
                        footprint.add(cell)
                    }
                    val completeShape = matchesRoomShape(sourceRoom.shape(), segments)
                    rooms[id] = DungeonRoomOverlayGeometry.Room(
                        id, data?.name ?: "Unknown", type, footprint, sourceRoomExits,
                        false, completeShape, visibility, data?.maxSecrets ?: 0, -1, checkmark,
                    )
                }
            }

            var verifiedPredictions = 0
            var predictedCells = 0
            var incompletePredictionsRejected = 0
            var ambiguousPredictionsRejected = 0
            var exactCorePredictions = 0
            var walkedCatalogRooms = 0
            val incompletePredictionExamples = ArrayList<String>(MAX_DIAGNOSTIC_EXAMPLES)
            val ambiguousPredictionExamples = ArrayList<String>(MAX_DIAGNOSTIC_EXAMPLES)
            val hiddenByData = LinkedHashMap<OdinDungeonRoomCatalog.RoomData, MutableList<Int>>()
            for ((index, core) in observedCores) {
                val data = core?.data
                if (index < 0 || index >= 36 || mappedTiles.contains(index) || data == null) continue
                hiddenByData.getOrPut(data) { ArrayList() }.add(index)
            }
            val hiddenDoorEdges = doorEvidence.edges.keys.toSet()
            val predictionCandidates = ArrayList<PredictionCandidate>()
            for ((data, indexes) in hiddenByData) {
                for (connected in connectedTileGroups(indexes, hiddenDoorEdges)) {
                    val groups = when {
                        matchesRoomShape(data.shape, connected) -> listOf(connected)
                        data.shape == "1x1" -> connected.map { listOf(it) }
                        else -> {
                            incompletePredictionsRejected++
                            if (incompletePredictionExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                                incompletePredictionExamples.add(data.name + ":" + segmentKey(connected))
                            }
                            continue
                        }
                    }
                    for (segments in groups) {
                        if (!matchesRoomShape(data.shape, segments)) {
                            incompletePredictionsRejected++
                            if (incompletePredictionExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                                incompletePredictionExamples.add(data.name + ":" + segmentKey(segments))
                            }
                            continue
                        }
                        predictionCandidates.add(PredictionCandidate(data, segments))
                    }
                }
            }
            val candidateCoverage = HashMap<Int, Int>()
            for (candidate in predictionCandidates) {
                for (tile in candidate.segments) {
                    candidateCoverage[tile] = (candidateCoverage[tile] ?: 0) + 1
                }
            }
            for (candidate in predictionCandidates) {
                if (candidate.segments.any { candidateCoverage[it] != 1 }) {
                    ambiguousPredictionsRejected++
                    if (ambiguousPredictionExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                        ambiguousPredictionExamples.add(candidate.data.name + ":" + segmentKey(candidate.segments))
                    }
                    continue
                }
                val data = candidate.data
                val segments = candidate.segments
                val id = "unopened:" + data.name + ":" + segmentKey(segments)
                val type = dataType(data.type)
                val walkedInto = segments.any { visitedTiles.contains(it) }
                val visibility = if (walkedInto) DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
                    else DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
                val footprint = ArrayList<DungeonRoomOverlayGeometry.Cell>(segments.size)
                for (index in segments) {
                    val cell = DungeonRoomOverlayGeometry.Cell(index % 6, index / 6, type, id, visibility)
                    cells.add(cell)
                    footprint.add(cell)
                }
                val exits = exitsFor(segments, doorEvidence.edges)
                val completeShape = matchesRoomShape(data.shape, segments)
                rooms[id] = DungeonRoomOverlayGeometry.Room(
                    id, data.name, type, footprint, exits, false, completeShape, visibility,
                    data.maxSecrets, OdinDungeonMapGrid.Checkmark.UNDISCOVERED.name,
                )
                exactCorePredictions++
                if (walkedInto) {
                    walkedCatalogRooms++
                } else {
                    verifiedPredictions++
                    predictedCells += segments.size
                }
            }

            val represented = HashSet<Int>()
            for (cell in cells) represented.add(cell.x() + cell.z() * 6)
            val exactCoreTiles = TreeSet<Int>()
            for (tiles in hiddenByData.values) exactCoreTiles.addAll(tiles)
            for (index in exactCoreTiles) {
                if (represented.contains(index)) continue
                val core = observedCores[index] ?: continue
                val data = core.data ?: continue
                val id = PARTIAL_CORE_ROOM_PREFIX + index
                val visibility = if (visitedTiles.contains(index))
                    DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
                else DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
                val type = dataType(data.type)
                val cell = DungeonRoomOverlayGeometry.Cell(index % 6, index / 6, type, id, visibility)
                val exits = exitsFor(listOf(index), doorEvidence.edges)
                cells.add(cell)
                rooms[id] = DungeonRoomOverlayGeometry.Room(
                    id, data.name, type, listOf(cell), exits, false, false, visibility,
                    data.maxSecrets, -1,
                    if (visibility == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED)
                        OdinDungeonMapGrid.Checkmark.UNDISCOVERED.name else "",
                )
                represented.add(index)
                partialCoreTiles.add(index)
                if (partialCoreExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                    partialCoreExamples.add(index.toString() + ":" + data.name + ":" + data.shape)
                }
            }

            var doorEdgesWithRoomEvidence = 0
            var doorEdgesWithoutRoomEvidence = 0
            var doorOnlyRoomCreationBlocked = 0
            var borderCandidatesRejected = 0
            val unattachedDoorExamples = ArrayList<String>(MAX_DIAGNOSTIC_EXAMPLES)
            for (edge in doorEvidence.edges.keys) {
                val firstOccupied = represented.contains(edge.low)
                val secondOccupied = represented.contains(edge.high)
                if (firstOccupied || secondOccupied) {
                    doorEdgesWithRoomEvidence++
                    if (firstOccupied != secondOccupied) {
                        doorOnlyRoomCreationBlocked++
                        val emptyTile = if (firstOccupied) edge.high else edge.low
                        if (isBorderCell(emptyTile)) borderCandidatesRejected++
                    }
                } else {
                    doorEdgesWithoutRoomEvidence++
                    if (unattachedDoorExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                        unattachedDoorExamples.add(edge.low.toString() + "-" + edge.high)
                    }
                }
            }
            cells.sortWith(compareBy<DungeonRoomOverlayGeometry.Cell> { it.z() }.thenBy { it.x() })
            val evidence = buildString {
                append("Odin pixel layout and room-core catalog; evidence[mapRooms=")
                append(source.rooms().size)
                append(",mapCells=").append(observedMapCells)
                append(",mapTileMask=").append(tileMask(mappedTiles))
                append(",unknownMapPixelsRejected=").append(unknownMapPixelsRejected)
                append(",catalogMatches=").append(catalogMatches)
                append(",catalogPartialTileMatches=").append(catalogPartialTileMatches)
                append(",doorEdges[mapPixels=").append(doorEvidence.mapPixelEdges)
                append(",tracker=").append(doorEvidence.trackerEdges)
                append(",trackerMatched=").append(doorEvidence.trackerMatchedEdges)
                append(",trackerOnlyAccepted=").append(doorEvidence.trackerOnlyAccepted)
                append(",trackerInvalidRejected=").append(doorEvidence.trackerInvalidRejected)
                append(",roomAttached=").append(doorEdgesWithRoomEvidence)
                append(",unattachedRejected=").append(doorEdgesWithoutRoomEvidence).append("]")
                append(",mapViewableRooms=").append(mapViewableRooms)
                append(",mapViewableCells=").append(mapViewableCells)
                append(",mapUnopenedRooms=").append(mapUnopenedRooms)
                append(",mapUnopenedCells=").append(mapUnopenedCells)
                append(",mapCheckmarks=").append(mapCheckmarks)
                append(",typeConflicts=").append(catalogTypeConflicts)
                append(",catalogFootprintRejected=").append(catalogFootprintRejected)
                append(",catalogUnknownColorMatches=").append(catalogUnknownColorMatches)
                append(",catalogSignatureConflicts=").append(catalogSignatureConflicts)
                append(",predictionCandidates=").append(predictionCandidates.size)
                append(",exactCorePredictions=").append(exactCorePredictions)
                append(",walkedCatalogRooms=").append(walkedCatalogRooms)
                append(",verifiedPredictions=").append(verifiedPredictions)
                append(",predictionCells=").append(predictedCells)
                append(",predictionIncompleteRejected=").append(incompletePredictionsRejected)
                append(",predictionAmbiguousRejected=").append(ambiguousPredictionsRejected)
                append(",partialCoreCells=").append(partialCoreTiles.size)
                append(",partialCoreTileMask=").append(tileMask(partialCoreTiles))
                append(",doorOnlyRoomCreationBlocked=").append(doorOnlyRoomCreationBlocked)
                append(",borderCandidatesRejected=").append(borderCandidatesRejected).append("]")
                if (doorEvidence.trackerOnlyAcceptedExamples.isNotEmpty()) {
                    append(",trackerOnlyDoorAccepted=")
                    append(doorEvidence.trackerOnlyAcceptedExamples.joinToString("|"))
                }
                if (doorEvidence.trackerInvalidExamples.isNotEmpty()) {
                    append(",trackerDoorRejected=")
                    append(doorEvidence.trackerInvalidExamples.joinToString("|"))
                }
                if (unattachedDoorExamples.isNotEmpty()) {
                    append(",unattachedDoorExamples=").append(unattachedDoorExamples.joinToString("|"))
                }
                if (partialCoreExamples.isNotEmpty()) {
                    append(",partialCoreExamples=").append(partialCoreExamples.joinToString("|"))
                }
                if (typeConflictExamples.isNotEmpty()) {
                    append(",typeConflictExamples=").append(typeConflictExamples.joinToString("|"))
                }
                if (incompletePredictionExamples.isNotEmpty()) {
                    append(",incompletePredictionExamples=")
                    append(incompletePredictionExamples.joinToString("|"))
                }
                if (ambiguousPredictionExamples.isNotEmpty()) {
                    append(",ambiguousPredictionExamples=")
                    append(ambiguousPredictionExamples.joinToString("|"))
                }
            }
            return DungeonRoomOverlayGeometry.Snapshot(
                true, cells, rooms.values.toList(), playerCell, players ?: emptyList(), evidence,
                OdinSpecialColumnPredictor.predict(
                    source, observedCores, floorNumber, puzzleCount, predictionsEnabled, playerTile,
                ),
            )
        }

        private fun validTiles(tileIndices: Set<Int?>?): Set<Int> {
            if (tileIndices.isNullOrEmpty()) return emptySet()
            val valid = HashSet<Int>()
            for (index in tileIndices) {
                if (index != null && index in 0..35) valid.add(index)
            }
            return valid
        }

        private fun tileMask(tiles: Set<Int>?): String {
            var mask = 0L
            if (tiles != null) {
                for (tile in tiles) if (tile in 0..35) mask = mask or (1L shl tile)
            }
            val hex = java.lang.Long.toHexString(mask)
            return "0".repeat((9 - hex.length).coerceAtLeast(0)) + hex
        }

        /** Partitions hidden signatures into four-neighbor groups without crossing observed doors. */
        private fun connectedTileGroups(tileIndices: List<Int>, doorEdges: Set<EdgeKey>): List<List<Int>> {
            val remaining = TreeSet<Int>()
            for (index in tileIndices) if (index in 0..35) remaining.add(index)
            val groups = ArrayList<List<Int>>()
            while (remaining.isNotEmpty()) {
                val start = remaining.pollFirst()
                val group = ArrayList<Int>()
                val pending = ArrayList<Int>()
                pending.add(start)
                var cursor = 0
                while (cursor < pending.size) {
                    val current = pending[cursor++]
                    group.add(current)
                    val x = current % 6
                    val z = current / 6
                    val neighbors = intArrayOf(
                        if (x > 0) current - 1 else -1,
                        if (x < 5) current + 1 else -1,
                        if (z > 0) current - 6 else -1,
                        if (z < 5) current + 6 else -1,
                    )
                    for (neighbor in neighbors) {
                        val edge = EdgeKey.of(current, neighbor)
                        if (neighbor >= 0 && (edge == null || !doorEdges.contains(edge))
                            && remaining.remove(neighbor)) {
                            pending.add(neighbor)
                        }
                    }
                }
                group.sort()
                groups.add(group)
            }
            return groups
        }

        /** Map pixels and positive world observations establish exit edges; map colors retain precedence. */
        private fun doorEvidence(
            source: OdinDungeonMapGrid.ScanResult?,
            observedDoors: List<DungeonRoomOverlayGeometry.ObservedDoor>?,
        ): DoorEvidence {
            val doors = LinkedHashMap<EdgeKey, DoorEdge>()
            if (source != null) {
                for (door in source.doors()) {
                    if (door == null) continue
                    val edge = EdgeKey.of(door.firstCell, door.secondCell) ?: continue
                    val kind = when (door.type) {
                        OdinDungeonMapGrid.DoorType.WITHER -> DungeonRoomOverlayGeometry.DoorKind.WITHER
                        OdinDungeonMapGrid.DoorType.BLOOD -> DungeonRoomOverlayGeometry.DoorKind.BLOOD
                        OdinDungeonMapGrid.DoorType.FAIRY -> DungeonRoomOverlayGeometry.DoorKind.FAIRY
                        OdinDungeonMapGrid.DoorType.NORMAL -> DungeonRoomOverlayGeometry.DoorKind.NORMAL
                        else -> DungeonRoomOverlayGeometry.DoorKind.NORMAL
                    }
                    doors[edge] = DoorEdge(door.firstCell, door.secondCell, kind)
                }
            }
            val mapPixelEdges = doors.size
            val trackerDoors = LinkedHashMap<EdgeKey, DungeonRoomOverlayGeometry.ObservedDoor>()
            var trackerInvalidRejected = 0
            val trackerInvalidExamples = ArrayList<String>(MAX_DIAGNOSTIC_EXAMPLES)
            if (observedDoors != null) {
                for (door in observedDoors) {
                    if (door == null) continue
                    val edge = EdgeKey.of(door.firstCell(), door.secondCell())
                    if (edge == null) {
                        trackerInvalidRejected++
                        if (trackerInvalidExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                            trackerInvalidExamples.add(door.firstCell().toString() + "-" + door.secondCell())
                        }
                        continue
                    }
                    trackerDoors[edge] = door
                }
            }
            var trackerMatchedEdges = 0
            var trackerOnlyAccepted = 0
            val trackerOnlyAcceptedExamples = ArrayList<String>(MAX_DIAGNOSTIC_EXAMPLES)
            for ((edge, door) in trackerDoors) {
                val previous = doors[edge]
                if (previous == null) {
                    trackerOnlyAccepted++
                    doors[edge] = DoorEdge(edge.low, edge.high, door.kind())
                    if (trackerOnlyAcceptedExamples.size < MAX_DIAGNOSTIC_EXAMPLES) {
                        trackerOnlyAcceptedExamples.add(edge.low.toString() + "-" + edge.high + ":" + door.kind())
                    }
                } else {
                    trackerMatchedEdges++
                    val refined = refineDoorKind(previous.kind, door.kind())
                    if (refined != previous.kind) {
                        doors[edge] = DoorEdge(previous.firstCell, previous.secondCell, refined)
                    }
                }
            }
            return DoorEvidence(
                doors.toMap(), mapPixelEdges, trackerDoors.size, trackerMatchedEdges,
                trackerOnlyAccepted, trackerInvalidRejected, trackerOnlyAcceptedExamples.toList(),
                trackerInvalidExamples.toList(),
            )
        }

        private fun refineDoorKind(
            mapKind: DungeonRoomOverlayGeometry.DoorKind,
            trackerKind: DungeonRoomOverlayGeometry.DoorKind?,
        ): DungeonRoomOverlayGeometry.DoorKind {
            if (mapKind == DungeonRoomOverlayGeometry.DoorKind.NORMAL && trackerKind != null
                && trackerKind != DungeonRoomOverlayGeometry.DoorKind.NORMAL
                && trackerKind != DungeonRoomOverlayGeometry.DoorKind.UNKNOWN) return trackerKind
            return mapKind
        }

        private fun isBorderCell(index: Int): Boolean {
            val x = index % 6
            val z = index / 6
            return x == 0 || x == 5 || z == 0 || z == 5
        }

        /** Only a complete catalog-shaped footprint is combined; partial groups remain tile-local. */
        private fun matchesRoomShape(shape: String?, segments: List<Int>): Boolean {
            if (segments.size != shapeTileCount(shape)) return false
            val unique = HashSet<Int>()
            var minX = 6
            var minZ = 6
            var maxX = -1
            var maxZ = -1
            for (index in segments) {
                if (index !in 0..35 || !unique.add(index)) return false
                val x = index % 6
                val z = index / 6
                minX = minOf(minX, x)
                minZ = minOf(minZ, z)
                maxX = maxOf(maxX, x)
                maxZ = maxOf(maxZ, z)
            }
            val width = maxX - minX
            val depth = maxZ - minZ
            return when (shape) {
                "1x1" -> width == 0 && depth == 0
                "1x2" -> (width == 0 && depth == 1) || (width == 1 && depth == 0)
                "1x3" -> (width == 0 && depth == 2) || (width == 2 && depth == 0)
                "1x4" -> (width == 0 && depth == 3) || (width == 3 && depth == 0)
                "2x2" -> width == 1 && depth == 1 && unique.size == 4
                "L" -> width == 1 && depth == 1 && unique.size == 3
                else -> false
            }
        }

        private fun shapeTileCount(shape: String?): Int = when (shape) {
            "1x1" -> 1
            "1x2" -> 2
            "1x3", "L" -> 3
            "1x4", "2x2" -> 4
            else -> -1
        }

        private fun findRoomData(
            segments: List<Int>,
            cores: Map<Int, OdinDungeonMapCoreScanner.RoomCore>,
        ): RoomDataMatch {
            var resolved: OdinDungeonRoomCatalog.RoomData? = null
            for (index in segments) {
                val candidate = cores[index]?.data ?: continue
                if (!matchesRoomShape(candidate.shape, segments)) return RoomDataMatch(null, true, false)
                if (resolved != null && resolved != candidate) return RoomDataMatch(null, false, true)
                resolved = candidate
            }
            return RoomDataMatch(resolved, false, false)
        }

        private fun catalogTypeCompatible(
            mapType: OdinDungeonMapGrid.MapRoomType?,
            catalogType: String?,
        ): Boolean {
            if (mapType == null || mapType == OdinDungeonMapGrid.MapRoomType.UNDISCOVERED) return false
            val catalogRoomType = dataType(catalogType)
            if (catalogRoomType == DungeonMapGeometry.RoomType.UNKNOWN) return false
            // Unknown map colors do not establish a room or authorize catalog metadata.
            if (mapType == OdinDungeonMapGrid.MapRoomType.UNKNOWN) return false
            return catalogRoomType.mapColor() == mapType.mapColor()
        }

        private fun exitsFor(
            segments: List<Int>,
            doors: Map<EdgeKey, DoorEdge>,
        ): List<DungeonRoomOverlayGeometry.ObservedExit> {
            val footprint = HashSet(segments)
            val exits = ArrayList<DungeonRoomOverlayGeometry.ObservedExit>()
            for (door in doors.values) {
                val firstInside = footprint.contains(door.firstCell)
                val secondInside = footprint.contains(door.secondCell)
                if (firstInside == secondInside) continue
                val from = if (firstInside) door.firstCell else door.secondCell
                val to = if (firstInside) door.secondCell else door.firstCell
                val dx = to % 6 - from % 6
                val dz = to / 6 - from / 6
                if (kotlin.math.abs(dx) + kotlin.math.abs(dz) != 1) continue
                val direction = when {
                    dx > 0 -> DungeonRoomOverlayGeometry.Direction.EAST
                    dx < 0 -> DungeonRoomOverlayGeometry.Direction.WEST
                    dz > 0 -> DungeonRoomOverlayGeometry.Direction.SOUTH
                    else -> DungeonRoomOverlayGeometry.Direction.NORTH
                }
                val x = from % 6
                val z = from / 6
                exits.add(
                    DungeonRoomOverlayGeometry.ObservedExit(
                        DungeonRoomOverlayGeometry.Cell(x, z, DungeonMapGeometry.RoomType.UNKNOWN, null),
                        direction,
                        door.kind,
                    ),
                )
            }
            return exits.toList()
        }

        private fun mapType(type: OdinDungeonMapGrid.MapRoomType?): DungeonMapGeometry.RoomType {
            return when (type) {
                null -> DungeonMapGeometry.RoomType.UNKNOWN
                OdinDungeonMapGrid.MapRoomType.ENTRANCE -> DungeonMapGeometry.RoomType.ENTRANCE
                OdinDungeonMapGrid.MapRoomType.FAIRY -> DungeonMapGeometry.RoomType.FAIRY
                OdinDungeonMapGrid.MapRoomType.NORMAL -> DungeonMapGeometry.RoomType.ROOM
                // Odin's map-color parser resolves the shared 63 color as RARE, but its renderer
                // deliberately uses the Normal palette for that ambiguous no-signature fallback.
                OdinDungeonMapGrid.MapRoomType.RARE -> DungeonMapGeometry.RoomType.ROOM
                OdinDungeonMapGrid.MapRoomType.BLOOD -> DungeonMapGeometry.RoomType.BLOOD
                OdinDungeonMapGrid.MapRoomType.CHAMPION -> DungeonMapGeometry.RoomType.CHAMPION
                OdinDungeonMapGrid.MapRoomType.PUZZLE -> DungeonMapGeometry.RoomType.PUZZLE
                OdinDungeonMapGrid.MapRoomType.TRAP -> DungeonMapGeometry.RoomType.TRAP
                OdinDungeonMapGrid.MapRoomType.UNKNOWN,
                OdinDungeonMapGrid.MapRoomType.UNDISCOVERED -> DungeonMapGeometry.RoomType.UNKNOWN
            }
        }

        private fun dataType(type: String?): DungeonMapGeometry.RoomType = when (type) {
            "NORMAL" -> DungeonMapGeometry.RoomType.ROOM
            "RARE" -> DungeonMapGeometry.RoomType.RARE
            "PUZZLE" -> DungeonMapGeometry.RoomType.PUZZLE
            "TRAP" -> DungeonMapGeometry.RoomType.TRAP
            "MINIBOSS" -> DungeonMapGeometry.RoomType.MINIBOSS
            "CHAMPION" -> DungeonMapGeometry.RoomType.CHAMPION
            "ENTRANCE" -> DungeonMapGeometry.RoomType.ENTRANCE
            "FAIRY" -> DungeonMapGeometry.RoomType.FAIRY
            "BLOOD" -> DungeonMapGeometry.RoomType.BLOOD
            else -> DungeonMapGeometry.RoomType.UNKNOWN
        }

        /** Stable room identity shared by map projection and local per-room progress. */
        @JvmStatic
        fun roomIdForSegments(segments: List<Int?>?): String {
            if (segments.isNullOrEmpty()) return "empty"
            val sorted = TreeSet<Int>()
            for (index in segments) if (index != null && index in 0..35) sorted.add(index)
            return if (sorted.isEmpty()) "empty" else sorted.joinToString(",")
        }

        private fun segmentKey(segments: List<Int>): String = roomIdForSegments(segments)
    }

    private data class DoorEdge(
        val firstCell: Int,
        val secondCell: Int,
        val kind: DungeonRoomOverlayGeometry.DoorKind,
    )

    private data class DoorEvidence(
        val edges: Map<EdgeKey, DoorEdge>,
        val mapPixelEdges: Int,
        val trackerEdges: Int,
        val trackerMatchedEdges: Int,
        val trackerOnlyAccepted: Int,
        val trackerInvalidRejected: Int,
        val trackerOnlyAcceptedExamples: List<String>,
        val trackerInvalidExamples: List<String>,
    )

    private data class PredictionCandidate(
        val data: OdinDungeonRoomCatalog.RoomData,
        val segments: List<Int>,
    )

    private data class EdgeKey(val low: Int, val high: Int) {
        companion object {
            fun of(first: Int, second: Int): EdgeKey? {
                if (first !in 0..35 || second !in 0..35) return null
                val dx = kotlin.math.abs(first % 6 - second % 6)
                val dz = kotlin.math.abs(first / 6 - second / 6)
                if (dx + dz != 1) return null
                return EdgeKey(minOf(first, second), maxOf(first, second))
            }
        }
    }

    private data class RoomDataMatch(
        val data: OdinDungeonRoomCatalog.RoomData?,
        val footprintMismatch: Boolean,
        val signatureConflict: Boolean,
    )
}
