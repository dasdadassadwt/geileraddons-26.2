package geiler.addons.client.dungeon

import java.util.ArrayDeque
import java.util.HashSet

/** Kotlin implementation of the frozen map-pixel and room-grid rules. */
object DungeonMapGeometryLogic {
    private const val MAX_CONNECTED_CELLS = 36
    private const val MIN_ROOM_PIXELS = 6
    private val MAX_ROOM_PIXELS = DungeonMapGeometry.MAP_SIZE - 1
    private const val MAP_GAP_PIXELS = 4
    private const val MAP_SEARCH_STEP = 10
    private const val ROOM_BLOCKS = 32
    private const val RED_CHECKMARK_COLOR = 18
    private const val WHITE_CHECKMARK_COLOR = 34
    private const val GREEN_CHECKMARK_COLOR = 30
    private val cardinalDirections = arrayOf(
        intArrayOf(-1, 0), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(0, 1),
    )

    @JvmStatic
    fun roomTypeFromColor(color: Int): DungeonMapGeometry.RoomType {
        // The map's shared 74 color is Yellow/Champion. Miniboss means a room whose
        // loaded room metadata identifies a miniboss, not this ambiguous map pixel.
        val champion = DungeonMapGeometry.RoomType.CHAMPION
        if (color == champion.mapColor()) return champion
        for (type in DungeonMapGeometry.RoomType.values()) {
            if (type != DungeonMapGeometry.RoomType.UNKNOWN && type.mapColor() == color) return type
        }
        return DungeonMapGeometry.RoomType.UNKNOWN
    }

    @JvmStatic
    fun calibrate(colors: ByteArray?, playerMapPosition: DungeonMapGeometry.MapPoint?): DungeonMapGeometry.Calibration {
        if (!validColors(colors)) return failedCalibration("map color buffer is unavailable or incomplete")
        if (!inside(playerMapPosition)) return failedCalibration("player map marker is outside the 128x128 map")
        val map = colors!!
        val start = playerMapPosition!!
        val pending = ArrayDeque<DungeonMapGeometry.MapPoint>()
        val visited = HashSet<DungeonMapGeometry.MapPoint>()
        pending.add(start)
        visited.add(start)
        while (pending.isNotEmpty()) {
            val point = pending.remove()
            if (colorAt(map, point.x, point.z) == DungeonMapGeometry.ENTRANCE_COLOR) {
                val candidate = entranceTileAt(map, point)
                if (candidate.calibrated) return candidate
            }
            for (direction in cardinalDirections) {
                val next = DungeonMapGeometry.MapPoint(
                    point.x + direction[0] * MAP_SEARCH_STEP,
                    point.z + direction[1] * MAP_SEARCH_STEP,
                )
                if (inside(next) && visited.add(next)) pending.add(next)
            }
        }
        return failedCalibration("the player-anchored map search found no entrance tile")
    }

    @JvmStatic
    fun roomAt(
        colors: ByteArray?,
        playerMapPosition: DungeonMapGeometry.MapPoint?,
        calibration: DungeonMapGeometry.Calibration?,
    ): DungeonMapGeometry.RoomGeometry {
        if (!validColors(colors)) return unavailableRoom("map color buffer is unavailable or incomplete")
        if (calibration == null || !calibration.calibrated) {
            return unavailableRoom(calibration?.reason ?: "map calibration was not attempted")
        }
        if (!inside(playerMapPosition)) return unavailableRoom("player map marker is outside the 128x128 map")

        val stride = calibration.roomPixelSize + MAP_GAP_PIXELS
        val currentMapCell = roomMapCell(playerMapPosition, calibration.entrance, stride)
        if (!inside(currentMapCell)) return unavailableRoom("current map cell is outside the map image")
        val roomCell = currentMapCell!!
        val map = colors!!
        val color = colorAt(map, roomCell.x, roomCell.z).toByte()
        val type = DungeonMapGeometry.RoomType.fromColor(color.toInt())
        if (type == DungeonMapGeometry.RoomType.UNKNOWN) {
            return DungeonMapGeometry.RoomGeometry(
                true, calibration, roomCell, color, type, listOf(DungeonMapGeometry.CellOffset(0, 0)),
                "1x1", "current room tile is not drawn with a recognized map color",
            )
        }

        var cells = if (type == DungeonMapGeometry.RoomType.ROOM) {
            connectedRoomOffsets(map, roomCell, stride, calibration.roomPixelSize, color)
        } else {
            listOf(DungeonMapGeometry.CellOffset(0, 0))
        }
        if (cells.isEmpty()) cells = listOf(DungeonMapGeometry.CellOffset(0, 0))
        return DungeonMapGeometry.RoomGeometry(
            true, calibration, roomCell, color, type, cells, shape(cells), null,
        )
    }

    @JvmStatic
    fun roomClearStatus(
        colors: ByteArray?,
        room: DungeonMapGeometry.RoomGeometry?,
    ): DungeonMapGeometry.RoomClearStatus {
        if (!validColors(colors) || room == null || !room.calibrated() ||
            room.roomType() == DungeonMapGeometry.RoomType.UNKNOWN ||
            room.roomType() == DungeonMapGeometry.RoomType.ENTRANCE ||
            room.roomType() == DungeonMapGeometry.RoomType.BLOOD ||
            room.roomType() == DungeonMapGeometry.RoomType.FAIRY || room.connectedCells().isEmpty()
        ) return DungeonMapGeometry.RoomClearStatus.UNKNOWN
        val roomSize = room.calibration()!!.roomPixelSize
        val halfRoomSize = roomSize / 2
        if (halfRoomSize <= 0) return DungeonMapGeometry.RoomClearStatus.UNKNOWN
        val stride = roomSize + MAP_GAP_PIXELS
        val base = room.currentMapCell()!!
        for (segment in room.connectedCells()) {
            val middleX = base.x + segment.x * stride + halfRoomSize
            val middleZ = base.z + segment.z * stride + halfRoomSize
            for (offset in 0 until halfRoomSize) {
                val color = colorAt(colors!!, middleX, middleZ + offset)
                if (color == GREEN_CHECKMARK_COLOR) return DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN
                if (color == WHITE_CHECKMARK_COLOR) return DungeonMapGeometry.RoomClearStatus.CLEARED_WHITE
                if (color == RED_CHECKMARK_COLOR) return DungeonMapGeometry.RoomClearStatus.FAILED_RED
            }
        }
        return DungeonMapGeometry.RoomClearStatus.UNCLEARED
    }

    @JvmStatic
    fun physicalCellAt(worldX: Double, worldZ: Double): DungeonMapGeometry.WorldCell? {
        if (!worldX.isFinite() || !worldZ.isFinite() ||
            worldX < Int.MIN_VALUE + 16.0 || worldX > Int.MAX_VALUE - 16.0 ||
            worldZ < Int.MIN_VALUE + 16.0 || worldZ > Int.MAX_VALUE - 16.0
        ) return null
        val roundedX = (worldX + 8.5).toInt()
        val roundedZ = (worldZ + 8.5).toInt()
        val baseX = roundedX - Math.floorMod(roundedX, ROOM_BLOCKS) - 8
        val baseZ = roundedZ - Math.floorMod(roundedZ, ROOM_BLOCKS) - 8
        return DungeonMapGeometry.WorldCell(baseX, baseZ)
    }

    @JvmStatic
    fun roomMapCell(
        player: DungeonMapGeometry.MapPoint?,
        entrance: DungeonMapGeometry.MapPoint?,
        stride: Int,
    ): DungeonMapGeometry.MapPoint? {
        if (player == null || entrance == null || stride <= 0) return null
        val offsetX = Math.floorMod(entrance.x, stride)
        val offsetZ = Math.floorMod(entrance.z, stride)
        val relativeX = player.x + 2 - offsetX
        val relativeZ = player.z + 2 - offsetZ
        return DungeonMapGeometry.MapPoint(
            relativeX - Math.floorMod(relativeX, stride) + offsetX,
            relativeZ - Math.floorMod(relativeZ, stride) + offsetZ,
        )
    }

    @JvmStatic
    fun connectedRoomOffsets(
        colors: ByteArray?,
        start: DungeonMapGeometry.MapPoint?,
        stride: Int,
        roomSize: Int,
        roomColor: Byte,
    ): List<DungeonMapGeometry.CellOffset> {
        if (!validColors(colors) || !inside(start) || stride <= 0) return emptyList()
        val map = colors!!
        val startPoint = start!!
        val pending = ArrayDeque<DungeonMapGeometry.MapPoint>()
        val visited = HashSet<DungeonMapGeometry.MapPoint>()
        pending.add(startPoint)
        visited.add(startPoint)
        while (pending.isNotEmpty() && visited.size <= MAX_CONNECTED_CELLS) {
            val point = pending.removeFirst()
            // Keep the asymmetric north/west shared-gap tests from the baseline map traversal.
            addConnectedTile(map, DungeonMapGeometry.MapPoint(point.x - stride, point.z),
                point.x - 1, point.z, roomColor, visited, pending)
            addConnectedTile(map, DungeonMapGeometry.MapPoint(point.x, point.z - stride),
                point.x, point.z - 1, roomColor, visited, pending)
            addConnectedTile(map, DungeonMapGeometry.MapPoint(point.x + stride, point.z),
                point.x + roomSize, point.z, roomColor, visited, pending)
            addConnectedTile(map, DungeonMapGeometry.MapPoint(point.x, point.z + stride),
                point.x, point.z + roomSize, roomColor, visited, pending)
        }
        if (pending.isNotEmpty() || visited.size > MAX_CONNECTED_CELLS) return emptyList()
        val offsets = visited.map { point ->
            DungeonMapGeometry.CellOffset(
                (point.x - startPoint.x) / stride,
                (point.z - startPoint.z) / stride,
            )
        }.sortedWith(compareBy<DungeonMapGeometry.CellOffset> { it.x }.thenBy { it.z })
        return java.util.List.copyOf(offsets)
    }

    @JvmStatic
    fun shape(cells: List<DungeonMapGeometry.CellOffset>?): String {
        if (cells == null || cells.isEmpty()) return "unknown"
        val minX = cells.minOfOrNull { it.x } ?: 0
        val maxX = cells.maxOfOrNull { it.x } ?: 0
        val minZ = cells.minOfOrNull { it.z } ?: 0
        val maxZ = cells.maxOfOrNull { it.z } ?: 0
        val width = maxX - minX + 1
        val depth = maxZ - minZ + 1
        if (cells.size == 1) return "1x1"
        if (width == 1 && depth == cells.size || depth == 1 && width == cells.size) return "1x${cells.size}"
        if (width == 2 && depth == 2 && cells.size == 4) return "2x2"
        if (width == 2 && depth == 2 && cells.size == 3) return "L"
        return "custom-${cells.size}"
    }

    private fun addConnectedTile(
        colors: ByteArray,
        candidate: DungeonMapGeometry.MapPoint,
        gapX: Int,
        gapZ: Int,
        roomColor: Byte,
        visited: MutableSet<DungeonMapGeometry.MapPoint>,
        pending: ArrayDeque<DungeonMapGeometry.MapPoint>,
    ) {
        if (!inside(candidate) || colorAt(colors, gapX, gapZ) != java.lang.Byte.toUnsignedInt(roomColor)) return
        if (visited.add(candidate)) pending.addLast(candidate)
    }

    private fun entranceTileAt(
        colors: ByteArray,
        point: DungeonMapGeometry.MapPoint,
    ): DungeonMapGeometry.Calibration {
        var left = point.x
        while (left > 0 && colorAt(colors, left - 1, point.z) == DungeonMapGeometry.ENTRANCE_COLOR) left--
        var top = point.z
        while (top > 0 && colorAt(colors, left, top - 1) == DungeonMapGeometry.ENTRANCE_COLOR) top--
        val width = runLength(colors, left, top, 1, 0, DungeonMapGeometry.ENTRANCE_COLOR)
        if (width < MIN_ROOM_PIXELS || width > MAX_ROOM_PIXELS) {
            return failedCalibration("entrance color run is too short or exceeds the map bounds")
        }
        val insetTop = top + 1
        if (!inside(left, insetTop)) return failedCalibration("entrance top-left inset is outside the map")
        return DungeonMapGeometry.Calibration(true, DungeonMapGeometry.MapPoint(left, insetTop), width, null)
    }

    private fun runLength(colors: ByteArray, x: Int, z: Int, stepX: Int, stepZ: Int, color: Int): Int {
        var length = 0
        while (inside(x + length * stepX, z + length * stepZ) &&
            colorAt(colors, x + length * stepX, z + length * stepZ) == color
        ) length++
        return length
    }

    private fun validColors(colors: ByteArray?): Boolean =
        colors != null && colors.size >= DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE

    private fun colorAt(colors: ByteArray, x: Int, z: Int): Int =
        if (inside(x, z)) java.lang.Byte.toUnsignedInt(colors[x + (z shl 7)]) else -1

    private fun inside(point: DungeonMapGeometry.MapPoint?): Boolean =
        point != null && inside(point.x, point.z)

    private fun inside(x: Int, z: Int): Boolean =
        x >= 0 && z >= 0 && x < DungeonMapGeometry.MAP_SIZE && z < DungeonMapGeometry.MAP_SIZE

    private fun failedCalibration(reason: String) =
        DungeonMapGeometry.Calibration(false, null, 0, reason)

    private fun unavailableRoom(reason: String) = DungeonMapGeometry.RoomGeometry(
        false, null, null, (-1).toByte(), DungeonMapGeometry.RoomType.UNKNOWN, emptyList(), "unknown", reason,
    )
}
