package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonMapGeometry
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

/** Kotlin implementation for the Java-callable Odin prediction facade. */
object OdinSpecialColumnPredictorLogic {
    @JvmRecord
    private data class SpecialRoom(
        val identity: String,
        val type: DungeonMapGeometry.RoomType,
        val leftX: Int,
    )

    @JvmSuppressWildcards
    fun predict(
        map: OdinDungeonMapGrid.ScanResult?,
        cores: Map<Int, OdinDungeonMapCoreScanner.RoomCore>?,
        floorNumber: Int,
        dungeonPuzzleCount: Int,
        enabled: Boolean,
    ): List<OdinSpecialColumnPredictor.Prediction> =
        predict(map, cores, floorNumber, dungeonPuzzleCount, enabled, -1)

    @JvmSuppressWildcards
    fun predict(
        map: OdinDungeonMapGrid.ScanResult?,
        cores: Map<Int, OdinDungeonMapCoreScanner.RoomCore>?,
        floorNumber: Int,
        dungeonPuzzleCount: Int,
        enabled: Boolean,
        walkedIntoTile: Int,
    ): List<OdinSpecialColumnPredictor.Prediction> {
        if (!enabled || map == null || !map.available()) return emptyList()

        val visibleRooms = HashMap<Int, OdinDungeonMapGrid.Room>()
        val knownRoomData = HashMap<Int, OdinDungeonRoomCatalog.RoomData>()
        val mapSeenTiles = HashSet<Int>()
        val explicitUnknownColorTiles = HashSet<Int>()
        val openedSpecialRooms = HashSet<SpecialRoom>()
        val puzzleIdentities = HashSet<String>()

        for (tile in map.tiles()) {
            if (tile != null && tile.index in 0 until 36) {
                if (tile.mapSeen()) mapSeenTiles.add(tile.index)
                if (tile.type == OdinDungeonMapGrid.MapRoomType.UNKNOWN &&
                    tile.roomColor == OdinDungeonMapGrid.MapRoomType.UNKNOWN.mapColor()
                ) {
                    explicitUnknownColorTiles.add(tile.index)
                }
            }
        }

        for (room in map.rooms()) {
            val segments = room.segments()
            val data = firstData(segments, cores)
            val type = if (data == null) mapType(room.type()) else roomType(data.type)
            val viewable = room.checkmark() != OdinDungeonMapGrid.Checkmark.UNDISCOVERED ||
                segments.contains(walkedIntoTile)
            val identity = if (data == null) "map:${sortedKey(segments)}" else "catalog:${data.name}"
            if (viewable && isSpecial(type)) {
                openedSpecialRooms.add(SpecialRoom(identity, type, segments.minOfOrNull { it % 6 } ?: 0))
            }
            if (type == DungeonMapGeometry.RoomType.PUZZLE) puzzleIdentities.add(identity)
            for (index in segments) {
                visibleRooms[index] = room
                if (data != null) knownRoomData[index] = data
            }
        }

        if (cores != null) {
            for ((index, core) in cores) {
                val data = core?.data
                if (index !in 0 until 36 || data == null) continue
                knownRoomData.putIfAbsent(index, data)
                if (data.type == "PUZZLE") puzzleIdentities.add("catalog:${data.name}")
            }
        }

        // The two supported map room sizes encode the logical width. The pixel origin varies
        // with centering, so startX is not a reliable map-size or special-column signal.
        val layout = map.layout() ?: return emptyList()
        val gridSize = when (layout.roomSize) {
            16 -> 6
            18 -> 5
            else -> 0
        }
        if (gridSize == 0) return emptyList()
        val specialColumn = if (floorNumber in 4..6 && gridSize == 6) 5 else -1
        val knownOneByOnes = HashSet<Int>()
        for (index in 0 until 36) {
            if (index == walkedIntoTile || isViewable(visibleRooms[index])) continue
            val x = index % 6
            val z = index / 6
            if (x >= gridSize || mapSeenTiles.contains(index) ||
                explicitUnknownColorTiles.contains(index) || knownRoomData.containsKey(index)
            ) continue
            if (!touchesViewableDoor(index, map.doors(), visibleRooms)) continue
            if (x == specialColumn || isVisibleFromAllSides(x, z, gridSize, visibleRooms)) {
                knownOneByOnes.add(index)
            }
        }

        val discoveredOneByOnes = knownOneByOnes.size
        val specialColumnRoomCount = knownOneByOnes.count { it % 6 == specialColumn }
        val puzzleCount = if (dungeonPuzzleCount >= 0) dungeonPuzzleCount else puzzleIdentities.size
        val discoveredFullSpecialColumn = when {
            discoveredOneByOnes == puzzleCount + 2 -> gridSize
            specialColumn != -1 -> countDiscoveredInColumn(specialColumn, gridSize, visibleRooms)
            else -> 0
        }
        val inputSpecialRoomCount =
            if (specialColumn != -1 && specialColumnRoomCount == 0) 1 else specialColumnRoomCount
        val specialSize = gridSize - discoveredFullSpecialColumn + inputSpecialRoomCount

        val result = ArrayList<OdinSpecialColumnPredictor.Prediction>()
        for (index in knownOneByOnes) {
            val x = index % 6
            val guesses = if (x == specialColumn) {
                guessSpecialTileColor(
                    specialSize,
                    discoveredOneByOnes - specialColumnRoomCount,
                    puzzleCount,
                    openedSpecialRooms,
                )
            } else {
                guessNonSpecialTileColor(inputSpecialRoomCount, puzzleCount, openedSpecialRooms)
            }
            if (guesses.isNotEmpty()) {
                result.add(
                    OdinSpecialColumnPredictor.Prediction(x, index / 6, java.util.List.copyOf(guesses)),
                )
            }
        }
        return java.util.List.copyOf(result)
    }

    private fun isViewable(room: OdinDungeonMapGrid.Room?): Boolean =
        room != null && room.checkmark() != OdinDungeonMapGrid.Checkmark.UNDISCOVERED

    private fun touchesViewableDoor(
        tile: Int,
        doors: List<OdinDungeonMapGrid.Door>?,
        rooms: Map<Int, OdinDungeonMapGrid.Room>,
    ): Boolean {
        if (doors == null) return false
        for (door in doors) {
            if (door == null) continue
            if (door.firstCell == tile && isViewable(rooms[door.secondCell])) return true
            if (door.secondCell == tile && isViewable(rooms[door.firstCell])) return true
        }
        return false
    }

    private fun isVisibleFromAllSides(
        x: Int,
        z: Int,
        gridSize: Int,
        rooms: Map<Int, OdinDungeonMapGrid.Room>,
    ): Boolean = checkSide(x + 1, z, gridSize, rooms) && checkSide(x - 1, z, gridSize, rooms) &&
        checkSide(x, z + 1, gridSize, rooms) && checkSide(x, z - 1, gridSize, rooms)

    private fun checkSide(x: Int, z: Int, gridSize: Int, rooms: Map<Int, OdinDungeonMapGrid.Room>): Boolean {
        if (x < 0 || x >= gridSize || z < 0 || z >= 6) return true
        return isViewable(rooms[x + z * 6])
    }

    private fun countDiscoveredInColumn(
        specialColumn: Int,
        gridSize: Int,
        visibleRooms: Map<Int, OdinDungeonMapGrid.Room>,
    ): Int {
        var discovered = gridSize
        for (z in 0 until gridSize) {
            val index = specialColumn - 1 + z * 6
            val room = visibleRooms[index]
            if (room == null || isViewable(room)) discovered--
        }
        return discovered
    }

    private fun guessSpecialTileColor(
        specialSize: Int,
        discoveredOutsideColumn: Int,
        puzzleCount: Int,
        openedSpecialRooms: Set<SpecialRoom>,
    ): List<DungeonMapGeometry.RoomType> {
        if (specialSize <= puzzleCount || discoveredOutsideColumn >= 2) {
            return listOf(DungeonMapGeometry.RoomType.PUZZLE)
        }
        if (openedSpecialRooms.any { it.type == DungeonMapGeometry.RoomType.TRAP && it.leftX != 5 }) {
            return listOf(DungeonMapGeometry.RoomType.PUZZLE)
        }
        if (specialSize == puzzleCount + 1) {
            return listOf(DungeonMapGeometry.RoomType.TRAP, DungeonMapGeometry.RoomType.PUZZLE)
        }
        if (discoveredOutsideColumn == 1) {
            return listOf(DungeonMapGeometry.RoomType.TRAP, DungeonMapGeometry.RoomType.PUZZLE)
        }
        return listOf(
            DungeonMapGeometry.RoomType.CHAMPION,
            DungeonMapGeometry.RoomType.TRAP,
            DungeonMapGeometry.RoomType.PUZZLE,
        )
    }

    private fun guessNonSpecialTileColor(
        specialColumnRoomCount: Int,
        puzzleCount: Int,
        openedSpecialRooms: Set<SpecialRoom>,
    ): List<DungeonMapGeometry.RoomType> {
        val nonSpecialPuzzles = openedSpecialRooms.count {
            it.type == DungeonMapGeometry.RoomType.PUZZLE && it.leftX != 5
        }
        val totalPuzzles = specialColumnRoomCount + nonSpecialPuzzles
        if (totalPuzzles == puzzleCount) {
            if (openedSpecialRooms.any { it.type == DungeonMapGeometry.RoomType.TRAP }) {
                return listOf(DungeonMapGeometry.RoomType.CHAMPION)
            }
            if (openedSpecialRooms.any { it.type == DungeonMapGeometry.RoomType.CHAMPION }) {
                return listOf(DungeonMapGeometry.RoomType.TRAP)
            }
            return listOf(DungeonMapGeometry.RoomType.CHAMPION, DungeonMapGeometry.RoomType.TRAP)
        }
        if (specialColumnRoomCount == puzzleCount + 1) {
            return listOf(DungeonMapGeometry.RoomType.CHAMPION)
        }
        val openedChampionsAndTraps = openedSpecialRooms.count {
            it.type == DungeonMapGeometry.RoomType.CHAMPION || it.type == DungeonMapGeometry.RoomType.TRAP
        }
        if (openedChampionsAndTraps == 2) return listOf(DungeonMapGeometry.RoomType.PUZZLE)
        if (openedSpecialRooms.size == puzzleCount + 1) {
            if (openedSpecialRooms.none { it.type == DungeonMapGeometry.RoomType.TRAP }) {
                return listOf(DungeonMapGeometry.RoomType.TRAP)
            }
            if (openedSpecialRooms.none { it.type == DungeonMapGeometry.RoomType.CHAMPION }) {
                return listOf(DungeonMapGeometry.RoomType.CHAMPION)
            }
            return listOf(DungeonMapGeometry.RoomType.PUZZLE)
        }

        val result = ArrayList<DungeonMapGeometry.RoomType>()
        result.add(DungeonMapGeometry.RoomType.PUZZLE)
        if (openedSpecialRooms.none { it.type == DungeonMapGeometry.RoomType.TRAP }) {
            result.add(DungeonMapGeometry.RoomType.TRAP)
        }
        if (openedSpecialRooms.none { it.type == DungeonMapGeometry.RoomType.CHAMPION }) {
            result.add(DungeonMapGeometry.RoomType.CHAMPION)
        }
        return java.util.List.copyOf(result)
    }

    private fun firstData(
        segments: List<Int>,
        cores: Map<Int, OdinDungeonMapCoreScanner.RoomCore>?,
    ): OdinDungeonRoomCatalog.RoomData? {
        if (cores == null) return null
        for (index in segments) {
            val data = cores[index]?.data
            if (data != null) return data
        }
        return null
    }

    private fun sortedKey(segments: List<Int>): String =
        if (segments.isEmpty()) "empty" else segments.sorted().joinToString(",")

    private fun isSpecial(type: DungeonMapGeometry.RoomType): Boolean =
        type == DungeonMapGeometry.RoomType.CHAMPION || type == DungeonMapGeometry.RoomType.TRAP ||
            type == DungeonMapGeometry.RoomType.PUZZLE

    private fun mapType(type: OdinDungeonMapGrid.MapRoomType?): DungeonMapGeometry.RoomType = when (type) {
        OdinDungeonMapGrid.MapRoomType.PUZZLE -> DungeonMapGeometry.RoomType.PUZZLE
        OdinDungeonMapGrid.MapRoomType.TRAP -> DungeonMapGeometry.RoomType.TRAP
        OdinDungeonMapGrid.MapRoomType.CHAMPION -> DungeonMapGeometry.RoomType.CHAMPION
        OdinDungeonMapGrid.MapRoomType.ENTRANCE -> DungeonMapGeometry.RoomType.ENTRANCE
        OdinDungeonMapGrid.MapRoomType.FAIRY -> DungeonMapGeometry.RoomType.FAIRY
        OdinDungeonMapGrid.MapRoomType.BLOOD -> DungeonMapGeometry.RoomType.BLOOD
        OdinDungeonMapGrid.MapRoomType.NORMAL -> DungeonMapGeometry.RoomType.ROOM
        OdinDungeonMapGrid.MapRoomType.RARE -> DungeonMapGeometry.RoomType.RARE
        else -> DungeonMapGeometry.RoomType.UNKNOWN
    }

    private fun roomType(type: String): DungeonMapGeometry.RoomType = when (type) {
        "PUZZLE" -> DungeonMapGeometry.RoomType.PUZZLE
        "TRAP" -> DungeonMapGeometry.RoomType.TRAP
        "CHAMPION" -> DungeonMapGeometry.RoomType.CHAMPION
        else -> DungeonMapGeometry.RoomType.UNKNOWN
    }
}
