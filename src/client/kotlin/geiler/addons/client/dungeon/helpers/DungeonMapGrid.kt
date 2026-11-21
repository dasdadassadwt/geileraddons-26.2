package geiler.addons.client.dungeon.helpers

/** Java-compatible map API for Odin's pinned 6x6 scan, backed by the Kotlin implementation. */
object DungeonMapGrid {
    @JvmStatic
    fun scan(colors: ByteArray?): ScanResult = DungeonMapGridLogic.scan(colors)

    /** Reads Odin's exact Hypixel tab-list puzzle counter; -1 means no matching line was present. */
    @JvmStatic
    fun puzzleCountFromTabLines(lines: Iterable<String?>?): Int =
        DungeonMapGridLogic.puzzleCountFromTabLines(lines)

    /** Returns the class or DEAD marker from an Odin-compatible dungeon tab-list row. */
    @JvmStatic
    fun dungeonClassFromTabLine(line: String?): String? = DungeonMapGridLogic.dungeonClassFromTabLine(line)

    /** Parses the exact username/class row shape used by Odin's dungeon listener. */
    @JvmStatic
    fun dungeonPlayerFromTabLine(line: String?): DungeonTabPlayer? =
        DungeonMapGridLogic.dungeonPlayerFromTabLine(line)

    @JvmStatic
    fun detectLayout(colors: ByteArray?): Layout = DungeonMapGridLogic.detectLayout(colors)

    data class DungeonTabPlayer(val name: String?, val dungeonClass: String?, val dead: Boolean) {
        fun name(): String? = name
        fun dungeonClass(): String? = dungeonClass
        fun dead(): Boolean = dead
        override fun toString(): String = "DungeonTabPlayer[name=$name, dungeonClass=$dungeonClass, dead=$dead]"
    }

    enum class MapRoomType(private val color: Int) {
        ENTRANCE(30), FAIRY(82), NORMAL(63), RARE(63), BLOOD(18), CHAMPION(74), UNKNOWN(85), PUZZLE(66), TRAP(62),
        UNDISCOVERED(-1);

        fun mapColor(): Int = color

        companion object {
            @JvmStatic
            fun fromMapColor(color: Int): MapRoomType? = DungeonMapGridLogic.mapRoomTypeFromMapColor(color)
        }
    }

    enum class Checkmark {
        NONE, WHITE, GREEN, RED, QUESTION_MARK, UNDISCOVERED;

        companion object {
            @JvmStatic
            fun fromMapColor(color: Int): Checkmark = DungeonMapGridLogic.checkmarkFromMapColor(color)
        }
    }

    enum class DoorType {
        NORMAL, WITHER, BLOOD, FAIRY;

        companion object {
            @JvmStatic
            fun fromColor(color: Int): DoorType = DungeonMapGridLogic.doorTypeFromColor(color)
        }
    }

    enum class Direction { HORIZONTAL, VERTICAL }

    data class Layout(val available: Boolean, val roomSize: Int, val roomGap: Int, val startX: Int, val startY: Int,
        val reason: String?) {
        fun available(): Boolean = available
        fun roomSize(): Int = roomSize
        fun roomGap(): Int = roomGap
        fun startX(): Int = startX
        fun startY(): Int = startY
        fun reason(): String? = reason
        override fun toString(): String =
            "Layout[available=$available, roomSize=$roomSize, roomGap=$roomGap, startX=$startX, startY=$startY, " +
                "reason=$reason]"
    }

    data class Tile(val index: Int, val x: Int, val z: Int, val type: MapRoomType?, val roomColor: Int,
        val centerColor: Int, val checkmark: Checkmark?) {
        fun index(): Int = index
        fun x(): Int = x
        fun z(): Int = z
        fun type(): MapRoomType? = type
        fun roomColor(): Int = roomColor
        fun centerColor(): Int = centerColor
        fun checkmark(): Checkmark? = checkmark

        fun mapSeen(): Boolean = type != null && type != MapRoomType.UNKNOWN && type != MapRoomType.UNDISCOVERED

        override fun toString(): String = "Tile[index=$index, x=$x, z=$z, type=$type, roomColor=$roomColor, " +
            "centerColor=$centerColor, checkmark=$checkmark]"
    }

    class Room(segments: List<Int>?, val type: MapRoomType?, val checkmark: Checkmark?, val shape: String?) {
        val segments: List<Int> = java.util.List.copyOf(segments ?: emptyList())

        fun segments(): List<Int> = segments
        fun type(): MapRoomType? = type
        fun checkmark(): Checkmark? = checkmark
        fun shape(): String? = shape

        override fun equals(other: Any?): Boolean = other is Room && segments == other.segments &&
            type == other.type && checkmark == other.checkmark && shape == other.shape

        override fun hashCode(): Int {
            var result = segments.hashCode()
            result = 31 * result + (type?.hashCode() ?: 0)
            result = 31 * result + (checkmark?.hashCode() ?: 0)
            result = 31 * result + (shape?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String = "Room[segments=$segments, type=$type, checkmark=$checkmark, shape=$shape]"
    }

    data class Door(val firstCell: Int, val secondCell: Int, val direction: Direction?, val type: DoorType?,
        val color: Int) {
        fun firstCell(): Int = firstCell
        fun secondCell(): Int = secondCell
        fun direction(): Direction? = direction
        fun type(): DoorType? = type
        fun color(): Int = color
        override fun toString(): String =
            "Door[firstCell=$firstCell, secondCell=$secondCell, direction=$direction, type=$type, color=$color]"
    }

    class ScanResult(
        val available: Boolean,
        val layout: Layout?,
        tiles: List<Tile>?,
        rooms: List<Room>?,
        doors: List<Door>?,
        reason: String?,
    ) {
        val tiles: List<Tile> = java.util.List.copyOf(tiles ?: emptyList())
        val rooms: List<Room> = java.util.List.copyOf(rooms ?: emptyList())
        val doors: List<Door> = java.util.List.copyOf(doors ?: emptyList())
        val reason: String = reason ?: ""

        fun available(): Boolean = available
        fun layout(): Layout? = layout
        fun tiles(): List<Tile> = tiles
        fun rooms(): List<Room> = rooms
        fun doors(): List<Door> = doors
        fun reason(): String = reason

        override fun equals(other: Any?): Boolean = other is ScanResult && available == other.available &&
            layout == other.layout && tiles == other.tiles && rooms == other.rooms && doors == other.doors &&
            reason == other.reason

        override fun hashCode(): Int {
            var result = if (available) 1231 else 1237
            result = 31 * result + (layout?.hashCode() ?: 0)
            result = 31 * result + tiles.hashCode()
            result = 31 * result + rooms.hashCode()
            result = 31 * result + doors.hashCode()
            result = 31 * result + reason.hashCode()
            return result
        }

        override fun toString(): String = "ScanResult[available=$available, layout=$layout, tiles=$tiles, " +
            "rooms=$rooms, doors=$doors, reason=$reason]"
    }
}
