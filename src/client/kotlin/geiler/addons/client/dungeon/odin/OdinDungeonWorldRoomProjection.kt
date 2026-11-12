package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.TreeMap

/** Projects confirmed tile-local core data without reading Minecraft state or inferring doors. */
object OdinDungeonWorldRoomProjection {
    private const val MAX_DOOR_DIAGNOSTIC_EXAMPLES = 8
    private const val REASON = "verified-loaded-catalog-core-scan"
    private const val PARTIAL_CORE_ROOM_PREFIX = "partial-core:"

    /** Builds a partial map from existing scan results; it never requests chunks or infers doors. */
    @JvmStatic
    fun project(
        cores: Map<Int?, OdinDungeonMapCoreScanner.RoomCore?>?,
        playerCell: DungeonGridRules.Cell?,
        players: List<DungeonRoomOverlayGeometry.PlayerMarker>?,
        observedDoors: List<DungeonRoomOverlayGeometry.ObservedDoor>?,
    ): DungeonRoomOverlayGeometry.Snapshot {
        val catalogued = TreeMap<Int, OdinDungeonRoomCatalog.RoomData>()
        for ((tile, core) in cores.orEmpty()) {
            if (!validTile(tile) || core == null) continue
            val data = core.data ?: continue
            if (roomType(data.type()) == null || !supportedShape(data.shape())) continue
            catalogued[tile!!] = data
        }

        val doorEvidence = doorEdges(observedDoors!!)
        val confirmedDoors = doorEvidence.edges
        val possibleCandidates = candidates(catalogued, confirmedDoors)
        val candidateCounts = HashMap<Int, Int>()
        for (candidate in possibleCandidates) {
            for (tile in candidate.tiles) candidateCounts[tile] = candidateCounts.getOrDefault(tile, 0) + 1
        }

        val playerTile = tileIndex(playerCell)
        val resolved = possibleCandidates
            .filter { candidate -> candidate.tiles.all { candidateCounts.getOrDefault(it, 0) == 1 } }
            .sortedBy { it.tiles.first() }
        val cells = ArrayList<DungeonRoomOverlayGeometry.Cell>()
        val rooms = LinkedHashMap<String, DungeonRoomOverlayGeometry.Room>()
        val representedTiles = HashSet<Int>()
        for (candidate in resolved) {
            val id = "loaded-core:${tileKey(candidate.tiles)}"
            val type = roomType(candidate.data.type())!!
            val visibility = if (candidate.tiles.contains(playerTile)) {
                DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
            } else {
                DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
            }
            val footprint = ArrayList<DungeonRoomOverlayGeometry.Cell>(candidate.tiles.size)
            for (tile in candidate.tiles) {
                val cell = DungeonRoomOverlayGeometry.Cell(tile % 6, tile / 6, type, id, visibility)
                cells.add(cell)
                footprint.add(cell)
                representedTiles.add(tile)
            }
            val exits = exitsFor(candidate.tiles, confirmedDoors)
            val checkmark = if (visibility == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED) {
                "UNDISCOVERED"
            } else {
                ""
            }
            rooms[id] = DungeonRoomOverlayGeometry.Room(
                id, candidate.data.name(), type, footprint, exits, false, true, visibility,
                candidate.data.maxSecrets(), checkmark,
            )
        }

        var partialCoreCells = 0
        for ((tile, data) in catalogued) {
            if (representedTiles.contains(tile)) continue
            val visibility = if (tile == playerTile) {
                DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
            } else {
                DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
            }
            val id = "$PARTIAL_CORE_ROOM_PREFIX$tile"
            val type = roomType(data.type())!!
            val cell = DungeonRoomOverlayGeometry.Cell(tile % 6, tile / 6, type, id, visibility)
            val exits = exitsFor(listOf(tile), confirmedDoors)
            val checkmark = if (visibility == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED) {
                "UNDISCOVERED"
            } else {
                ""
            }
            cells.add(cell)
            rooms[id] = DungeonRoomOverlayGeometry.Room(
                id, data.name(), type, listOf(cell), exits, false, false, visibility,
                data.maxSecrets(), -1, checkmark,
            )
            representedTiles.add(tile)
            partialCoreCells++
        }

        var doorEdgesAttached = 0
        var doorEdgesUnattached = 0
        var doorOnlyRoomCreationBlocked = 0
        var borderCandidatesRejected = 0
        val attachedDoorExamples = ArrayList<String>(MAX_DOOR_DIAGNOSTIC_EXAMPLES)
        val unattachedDoorExamples = ArrayList<String>(MAX_DOOR_DIAGNOSTIC_EXAMPLES)
        for ((edge, kind) in confirmedDoors) {
            val firstOccupied = representedTiles.contains(edge.low)
            val secondOccupied = representedTiles.contains(edge.high)
            if (firstOccupied || secondOccupied) {
                doorEdgesAttached++
                if (attachedDoorExamples.size < MAX_DOOR_DIAGNOSTIC_EXAMPLES) {
                    attachedDoorExamples.add("${edge.low}-${edge.high}:$kind")
                }
                if (firstOccupied != secondOccupied) {
                    doorOnlyRoomCreationBlocked++
                    val emptyTile = if (firstOccupied) edge.high else edge.low
                    if (isBorderCell(emptyTile)) borderCandidatesRejected++
                }
            } else {
                doorEdgesUnattached++
                if (unattachedDoorExamples.size < MAX_DOOR_DIAGNOSTIC_EXAMPLES) {
                    unattachedDoorExamples.add("${edge.low}-${edge.high}")
                }
            }
        }
        cells.sortWith(compareBy<DungeonRoomOverlayGeometry.Cell> { it.z() }.thenBy { it.x() })
        val evidence = buildString {
            append(REASON)
            append(";completeRooms=").append(resolved.size)
            append(";partialCoreCells=").append(partialCoreCells)
            append(";doorEdges[tracker=").append(doorEvidence.trackerEdges)
            append(",trackerOnlyAccepted=").append(doorEdgesAttached)
            append(",unattachedRejected=").append(doorEdgesUnattached)
            append(",trackerInvalidRejected=").append(doorEvidence.invalidRejected)
            append(",doorOnlyRoomCreationBlocked=").append(doorOnlyRoomCreationBlocked)
            append(",borderCandidatesRejected=").append(borderCandidatesRejected).append(']')
            if (attachedDoorExamples.isNotEmpty()) {
                append(";trackerDoorAttached=").append(attachedDoorExamples.joinToString("|"))
            }
            if (unattachedDoorExamples.isNotEmpty()) {
                append(";unattachedDoorExamples=").append(unattachedDoorExamples.joinToString("|"))
            }
            if (doorEvidence.invalidExamples.isNotEmpty()) {
                append(";trackerDoorRejected=").append(doorEvidence.invalidExamples.joinToString("|"))
            }
        }
        return DungeonRoomOverlayGeometry.Snapshot(
            true,
            cells,
            ArrayList(rooms.values),
            if (validPlayerCell(playerCell)) playerCell else null,
            players ?: emptyList(),
            evidence,
        )
    }

    private fun candidates(
        catalogued: Map<Int, OdinDungeonRoomCatalog.RoomData>,
        doors: Map<EdgeKey, DungeonRoomOverlayGeometry.DoorKind>,
    ): List<Candidate> {
        val unique = LinkedHashMap<CandidateKey, Candidate>()
        for ((tile, data) in catalogued) {
            if (data.shape() == "1x1") {
                val tiles = listOf(tile)
                unique[CandidateKey(data, tiles)] = Candidate(data, tiles)
                continue
            }
            for (shape in orientedShapes(data.shape())) {
                for (originZ in 0 until 6) for (originX in 0 until 6) {
                    val tiles = ArrayList<Int>(shape.size)
                    var fits = true
                    for (offset in shape) {
                        val x = originX + offset.x
                        val z = originZ + offset.z
                        if (x !in 0 until 6 || z !in 0 until 6) {
                            fits = false
                            break
                        }
                        tiles.add(x + z * 6)
                    }
                    if (!fits || tiles.none { it == tile }) continue
                    tiles.sort()
                    if (!tiles.all { catalogued[it] == data } || hasInternalDoor(tiles, doors)) continue
                    val immutableTiles = java.util.List.copyOf(tiles)
                    unique[CandidateKey(data, immutableTiles)] = Candidate(data, immutableTiles)
                }
            }
        }
        return java.util.List.copyOf(unique.values)
    }

    private fun orientedShapes(shape: String?): List<List<Offset>> = when (shape) {
        "1x2" -> listOf(line(2, true), line(2, false))
        "1x3" -> listOf(line(3, true), line(3, false))
        "1x4" -> listOf(line(4, true), line(4, false))
        "2x2" -> listOf(listOf(Offset(0, 0), Offset(1, 0), Offset(0, 1), Offset(1, 1)))
        "L" -> listOf(
            listOf(Offset(0, 0), Offset(1, 0), Offset(0, 1)),
            listOf(Offset(0, 0), Offset(1, 0), Offset(1, 1)),
            listOf(Offset(0, 0), Offset(0, 1), Offset(1, 1)),
            listOf(Offset(1, 0), Offset(0, 1), Offset(1, 1)),
        )
        else -> emptyList()
    }

    private fun line(size: Int, horizontal: Boolean): List<Offset> =
        java.util.List.copyOf((0 until size).map { index ->
            if (horizontal) Offset(index, 0) else Offset(0, index)
        })

    private fun hasInternalDoor(
        tiles: List<Int>,
        doors: Map<EdgeKey, DungeonRoomOverlayGeometry.DoorKind>,
    ): Boolean {
        val footprint = tiles.toSet()
        for (tile in tiles) {
            val x = tile % 6
            val z = tile / 6
            if (x < 5 && tile + 1 in footprint && EdgeKey.of(tile, tile + 1)?.let(doors::containsKey) == true) return true
            if (z < 5 && tile + 6 in footprint && EdgeKey.of(tile, tile + 6)?.let(doors::containsKey) == true) return true
        }
        return false
    }

    private fun doorEdges(observedDoors: List<DungeonRoomOverlayGeometry.ObservedDoor>): DoorEvidence {
        val result = TreeMap<EdgeKey, DungeonRoomOverlayGeometry.DoorKind>(
            compareBy<EdgeKey> { it.low }.thenBy { it.high },
        )
        var invalidRejected = 0
        val invalidExamples = ArrayList<String>(MAX_DOOR_DIAGNOSTIC_EXAMPLES)
        for (door in observedDoors) {
            if (door == null) continue
            val edge = EdgeKey.of(door.firstCell(), door.secondCell())
            if (edge == null) {
                invalidRejected++
                if (invalidExamples.size < MAX_DOOR_DIAGNOSTIC_EXAMPLES) {
                    invalidExamples.add("${door.firstCell()}-${door.secondCell()}")
                }
                continue
            }
            result[edge] = door.kind()
        }
        return DoorEvidence(java.util.Map.copyOf(result), result.size, invalidRejected, java.util.List.copyOf(invalidExamples))
    }

    private fun isBorderCell(tile: Int): Boolean {
        val x = tile % 6
        val z = tile / 6
        return x == 0 || x == 5 || z == 0 || z == 5
    }

    private fun exitsFor(
        tiles: List<Int>,
        doors: Map<EdgeKey, DungeonRoomOverlayGeometry.DoorKind>,
    ): List<DungeonRoomOverlayGeometry.ObservedExit> {
        val footprint = tiles.toSet()
        val exits = LinkedHashSet<DungeonRoomOverlayGeometry.ObservedExit>()
        for ((edge, kind) in doors) {
            val firstInside = edge.low in footprint
            val secondInside = edge.high in footprint
            if (firstInside == secondInside) continue
            val from = if (firstInside) edge.low else edge.high
            val to = if (firstInside) edge.high else edge.low
            val dx = to % 6 - from % 6
            val dz = to / 6 - from / 6
            val direction = when {
                dx > 0 -> DungeonRoomOverlayGeometry.Direction.EAST
                dx < 0 -> DungeonRoomOverlayGeometry.Direction.WEST
                dz > 0 -> DungeonRoomOverlayGeometry.Direction.SOUTH
                else -> DungeonRoomOverlayGeometry.Direction.NORTH
            }
            exits.add(
                DungeonRoomOverlayGeometry.ObservedExit(
                    DungeonRoomOverlayGeometry.Cell(from % 6, from / 6, null, null), direction, kind,
                ),
            )
        }
        return java.util.List.copyOf(exits)
    }

    private fun roomType(type: String?): DungeonMapGeometry.RoomType? = when (type) {
        "NORMAL" -> DungeonMapGeometry.RoomType.ROOM
        "RARE" -> DungeonMapGeometry.RoomType.RARE
        "PUZZLE" -> DungeonMapGeometry.RoomType.PUZZLE
        "TRAP" -> DungeonMapGeometry.RoomType.TRAP
        "MINIBOSS" -> DungeonMapGeometry.RoomType.MINIBOSS
        "CHAMPION" -> DungeonMapGeometry.RoomType.CHAMPION
        "ENTRANCE" -> DungeonMapGeometry.RoomType.ENTRANCE
        "FAIRY" -> DungeonMapGeometry.RoomType.FAIRY
        "BLOOD" -> DungeonMapGeometry.RoomType.BLOOD
        else -> null
    }

    private fun supportedShape(shape: String?): Boolean = when (shape) {
        "1x1", "1x2", "1x3", "1x4", "2x2", "L" -> true
        else -> false
    }

    private fun validTile(tile: Int?): Boolean = tile != null && tile in 0 until 36

    private fun tileIndex(cell: DungeonGridRules.Cell?): Int =
        if (validPlayerCell(cell)) cell!!.componentX + cell.componentZ * 6 else -1

    private fun validPlayerCell(cell: DungeonGridRules.Cell?): Boolean =
        cell != null && cell.componentX in 0 until 6 && cell.componentZ in 0 until 6

    private fun tileKey(tiles: List<Int>): String = tiles.joinToString(",").ifEmpty { "empty" }

    private data class Offset(val x: Int, val z: Int)
    private data class Candidate(val data: OdinDungeonRoomCatalog.RoomData, val tiles: List<Int>)
    private data class CandidateKey(val data: OdinDungeonRoomCatalog.RoomData, val tiles: List<Int>)
    private data class DoorEvidence(
        val edges: Map<EdgeKey, DungeonRoomOverlayGeometry.DoorKind>,
        val trackerEdges: Int,
        val invalidRejected: Int,
        val invalidExamples: List<String>,
    )

    private data class EdgeKey(val low: Int, val high: Int) {
        companion object {
            fun of(first: Int, second: Int): EdgeKey? {
                if (first !in 0 until 36 || second !in 0 until 36) return null
                val dx = kotlin.math.abs(first % 6 - second % 6)
                val dz = kotlin.math.abs(first / 6 - second / 6)
                if (dx + dz != 1) return null
                return EdgeKey(minOf(first, second), maxOf(first, second))
            }
        }
    }
}
