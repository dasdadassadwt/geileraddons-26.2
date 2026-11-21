package geiler.addons.client.dungeon.helpers

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.regex.Pattern

/** Kotlin implementation for Odin's pinned 6x6 map pixel, door, room, and checkmark scan. */
object DungeonMapGridLogic {
    private const val MAP_SIZE = 128
    private const val TILE_COUNT = 36
    private const val EMPTY = 0
    private const val ROOM_SPACING = 4
    private val DIRECTIONS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
    private val PUZZLE_COUNT = Pattern.compile("^Puzzles: \\((\\d+)\\)$")
    private val DUNGEON_PLAYER_ROW = Pattern.compile(
        "^\\[(\\d+)] (?:\\[[^\\]]+] )*([A-Za-z0-9_]{1,16}) .*?\\((\\w+)(?: (\\w+))*\\)$",
    )

    @JvmStatic
    fun scan(colors: ByteArray?): DungeonMapGrid.ScanResult {
        if (colors == null || colors.size < MAP_SIZE * MAP_SIZE) {
            return unavailable("saved map color buffer is missing or incomplete")
        }
        val layout = detectLayout(colors)
        if (!layout.available) return unavailable(layout.reason ?: "")

        val tiles = Array(TILE_COUNT) {
            DungeonMapGrid.Tile(-1, -1, -1, DungeonMapGrid.MapRoomType.UNDISCOVERED, 0, 0,
                DungeonMapGrid.Checkmark.UNDISCOVERED)
        }
        val doors = ArrayList<DungeonMapGrid.Door>(60)
        val halfRoom = layout.roomSize / 2
        val connectionGap = layout.roomSize + ROOM_SPACING / 2
        for (index in 0 until TILE_COUNT) {
            val x = index % 6
            val z = index / 6
            val originX = layout.startX + x * layout.roomGap
            val originZ = layout.startY + z * layout.roomGap
            val corner = pixel(colors, originX, originZ)
            if (corner != EMPTY) {
                val type = mapRoomTypeFromMapColor(corner)
                if (type != null) {
                    val center = pixel(colors, originX + halfRoom, originZ + halfRoom)
                    val checkmark = if (type == DungeonMapGrid.MapRoomType.UNKNOWN) {
                        DungeonMapGrid.Checkmark.UNDISCOVERED
                    } else {
                        checkmark(center, corner)
                    }
                    tiles[index] = DungeonMapGrid.Tile(index, x, z, type, corner, center, checkmark)
                }
            }
            if (x < 5) {
                val doorColor = pixel(colors, originX + connectionGap, originZ + halfRoom)
                val sideColor = pixel(colors, originX + connectionGap, originZ + halfRoom - 4)
                if (sideColor == EMPTY && doorColor != EMPTY) {
                    doors.add(
                        DungeonMapGrid.Door(
                            index, index + 1, DungeonMapGrid.Direction.HORIZONTAL,
                            doorTypeFromColor(doorColor), doorColor,
                        ),
                    )
                }
            }
            if (z < 5) {
                val doorColor = pixel(colors, originX + halfRoom, originZ + connectionGap)
                val sideColor = pixel(colors, originX + halfRoom - 4, originZ + connectionGap)
                if (sideColor == EMPTY && doorColor != EMPTY) {
                    doors.add(
                        DungeonMapGrid.Door(
                            index, index + 6, DungeonMapGrid.Direction.VERTICAL,
                            doorTypeFromColor(doorColor), doorColor,
                        ),
                    )
                }
            }
        }
        return DungeonMapGrid.ScanResult(true, layout, tiles.toList(), groupRooms(colors, layout, tiles), doors, "")
    }

    /** Reads Odin's exact Hypixel tab-list puzzle counter; -1 means no matching line was present. */
    @JvmStatic
    fun puzzleCountFromTabLines(lines: Iterable<String?>?): Int {
        if (lines == null) return -1
        for (line in lines) {
            if (line == null) continue
            val matcher = PUZZLE_COUNT.matcher(stripJavaWhitespace(line))
            if (matcher.matches()) {
                return try {
                    matcher.group(1).toInt()
                } catch (_: NumberFormatException) {
                    -1
                }
            }
        }
        return -1
    }

    /** Returns the class or DEAD marker from an Odin-compatible dungeon tab-list row. */
    @JvmStatic
    fun dungeonClassFromTabLine(line: String?): String? = dungeonPlayerFromTabLine(line)?.dungeonClass

    /** Parses the exact username/class row shape used by Odin's dungeon listener. */
    @JvmStatic
    fun dungeonPlayerFromTabLine(line: String?): DungeonMapGrid.DungeonTabPlayer? {
        if (line == null) return null
        val matcher = DUNGEON_PLAYER_ROW.matcher(stripJavaWhitespace(line))
        if (!matcher.matches()) return null
        val dungeonClass = matcher.group(3)
        return DungeonMapGrid.DungeonTabPlayer(matcher.group(2), dungeonClass, dungeonClass.equals("DEAD", true))
    }

    @JvmStatic
    fun detectLayout(colors: ByteArray?): DungeonMapGrid.Layout {
        if (colors == null || colors.size < MAP_SIZE * MAP_SIZE) return unavailableLayout("map buffer is incomplete")
        for (index in 0 until MAP_SIZE * MAP_SIZE) {
            if ((colors[index].toInt() and 0xFF) != DungeonMapGrid.MapRoomType.ENTRANCE.mapColor()) continue
            var end = index
            while (end < colors.size && (colors[end].toInt() and 0xFF) == DungeonMapGrid.MapRoomType.ENTRANCE.mapColor()) end++
            val length = end - index
            if (length != 16 && length != 18) continue
            val roomGap = length + ROOM_SPACING
            var startX = (index % MAP_SIZE) % roomGap
            var startY = (index / MAP_SIZE) % roomGap
            if (startX == 0) startX = 22
            if (startY == 0) startY = 22
            return DungeonMapGrid.Layout(true, length, roomGap, startX, startY, null)
        }
        return unavailableLayout("map has no Odin-sized 16 or 18 pixel entrance run")
    }

    @JvmStatic
    fun mapRoomTypeFromMapColor(color: Int): DungeonMapGrid.MapRoomType? {
        var result: DungeonMapGrid.MapRoomType? = null
        for (type in DungeonMapGrid.MapRoomType.values()) {
            if (type.mapColor() == color) result = type
        }
        return result
    }

    @JvmStatic
    fun checkmarkFromMapColor(color: Int): DungeonMapGrid.Checkmark = when (color) {
        34 -> DungeonMapGrid.Checkmark.WHITE
        30 -> DungeonMapGrid.Checkmark.GREEN
        18 -> DungeonMapGrid.Checkmark.RED
        119 -> DungeonMapGrid.Checkmark.QUESTION_MARK
        else -> DungeonMapGrid.Checkmark.UNDISCOVERED
    }

    @JvmStatic
    fun doorTypeFromColor(color: Int): DungeonMapGrid.DoorType = when (color) {
        119 -> DungeonMapGrid.DoorType.WITHER
        18 -> DungeonMapGrid.DoorType.BLOOD
        82 -> DungeonMapGrid.DoorType.FAIRY
        else -> DungeonMapGrid.DoorType.NORMAL
    }

    private fun groupRooms(
        colors: ByteArray,
        layout: DungeonMapGrid.Layout,
        tiles: Array<DungeonMapGrid.Tile>,
    ): List<DungeonMapGrid.Room> {
        val visited = BooleanArray(TILE_COUNT)
        val rooms = ArrayList<DungeonMapGrid.Room>()
        val connectionGap = layout.roomSize + ROOM_SPACING / 2
        for (start in 0 until TILE_COUNT) {
            val type = tiles[start].type
            // Unknown-color pixels remain diagnostic tile data, but do not establish occupancy.
            if (type == null || type == DungeonMapGrid.MapRoomType.UNKNOWN ||
                type == DungeonMapGrid.MapRoomType.UNDISCOVERED || visited[start]
            ) continue
            val segments = ArrayList<Int>()
            val pending = ArrayDeque<Int>()
            pending.add(start)
            visited[start] = true
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                segments.add(current)
                val currentX = current % 6
                val currentZ = current / 6
                for (direction in DIRECTIONS) {
                    val nextX = currentX + direction[0]
                    val nextZ = currentZ + direction[1]
                    if (nextX < 0 || nextX > 5 || nextZ < 0 || nextZ > 5) continue
                    val next = nextX + nextZ * 6
                    if (visited[next] || tiles[next].type != type) continue
                    val gapX: Int
                    val gapZ: Int
                    if (direction[0] == 1) {
                        gapX = layout.startX + currentX * layout.roomGap + connectionGap
                        gapZ = layout.startY + currentZ * layout.roomGap
                    } else if (direction[0] == -1) {
                        gapX = layout.startX + nextX * layout.roomGap + connectionGap
                        gapZ = layout.startY + nextZ * layout.roomGap
                    } else if (direction[1] == 1) {
                        gapX = layout.startX + currentX * layout.roomGap
                        gapZ = layout.startY + currentZ * layout.roomGap + connectionGap
                    } else {
                        gapX = layout.startX + nextX * layout.roomGap
                        gapZ = layout.startY + nextZ * layout.roomGap + connectionGap
                    }
                    if (pixel(colors, gapX, gapZ) == EMPTY) continue
                    visited[next] = true
                    pending.addLast(next)
                }
            }
            var mark = DungeonMapGrid.Checkmark.UNDISCOVERED
            for (segment in segments) {
                val candidate = tiles[segment].checkmark
                if (candidate == null) continue
                if (candidate == DungeonMapGrid.Checkmark.NONE) {
                    if (mark == DungeonMapGrid.Checkmark.UNDISCOVERED ||
                        mark == DungeonMapGrid.Checkmark.QUESTION_MARK
                    ) mark = DungeonMapGrid.Checkmark.NONE
                } else if (candidate != DungeonMapGrid.Checkmark.UNDISCOVERED) {
                    mark = candidate
                }
            }
            rooms.add(DungeonMapGrid.Room(java.util.List.copyOf(segments), type, mark, roomShape(segments)))
        }
        return java.util.List.copyOf(rooms)
    }

    private fun checkmark(centerColor: Int, roomColor: Int): DungeonMapGrid.Checkmark {
        if (centerColor == roomColor) return DungeonMapGrid.Checkmark.NONE
        return checkmarkFromMapColor(centerColor)
    }

    private fun roomShape(segments: List<Int>): String {
        val minX = segments.minOfOrNull { it % 6 } ?: 0
        val maxX = segments.maxOfOrNull { it % 6 } ?: 0
        val minZ = segments.minOfOrNull { it / 6 } ?: 0
        val maxZ = segments.maxOfOrNull { it / 6 } ?: 0
        return when (segments.size) {
            1 -> "1x1"
            2 -> "1x2"
            3 -> if (maxX - minX == 1 && maxZ - minZ == 1) "L" else "1x3"
            4 -> if (maxX - minX == 1 && maxZ - minZ == 1) "2x2" else "1x4"
            else -> "1x1"
        }
    }

    private fun pixel(colors: ByteArray, x: Int, z: Int): Int =
        if (x < 0 || z < 0 || x >= MAP_SIZE || z >= MAP_SIZE) EMPTY
        else colors[z * MAP_SIZE + x].toInt() and 0xFF

    private fun unavailable(reason: String): DungeonMapGrid.ScanResult =
        DungeonMapGrid.ScanResult(false, null, emptyList(), emptyList(), emptyList(), reason)

    private fun unavailableLayout(reason: String): DungeonMapGrid.Layout =
        DungeonMapGrid.Layout(false, 0, 0, 0, 0, reason)

    private fun stripJavaWhitespace(value: String): String = value.trim { Character.isWhitespace(it) }
}
