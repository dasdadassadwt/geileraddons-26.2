package geiler.addons.client.dungeon

/** Java-compatible map geometry API backed by the frozen Kotlin map-pixel rules. */
object DungeonMapGeometry {
    const val MAP_SIZE = 128
    const val ENTRANCE_COLOR = 30

    /** Finds the entrance using the pinned source's 10-pixel cardinal search and inset origin. */
    @JvmStatic
    fun calibrate(colors: ByteArray?, playerMapPosition: MapPoint?): Calibration =
        DungeonMapGeometryLogic.calibrate(colors, playerMapPosition)

    /** Resolves the map room type and connected tile offsets for a calibrated map. */
    @JvmStatic
    fun roomAt(colors: ByteArray?, playerMapPosition: MapPoint?, calibration: Calibration?): RoomGeometry =
        DungeonMapGeometryLogic.roomAt(colors, playerMapPosition, calibration)

    /** Reads the same per-segment checkmark pixels Skyblocker uses for room clear state. */
    @JvmStatic
    fun roomClearStatus(colors: ByteArray?, room: RoomGeometry?): RoomClearStatus =
        DungeonMapGeometryLogic.roomClearStatus(colors, room)

    /** Calculates the map's 32-block room origin using the game's half-open cell convention. */
    @JvmStatic
    fun physicalCellAt(worldX: Double, worldZ: Double): WorldCell? =
        DungeonMapGeometryLogic.physicalCellAt(worldX, worldZ)

    /** Finds the top-left map pixel for the tile containing the local player's map marker. */
    @JvmStatic
    fun roomMapCell(player: MapPoint?, entrance: MapPoint?, stride: Int): MapPoint? =
        DungeonMapGeometryLogic.roomMapCell(player, entrance, stride)

    @JvmStatic
    fun connectedRoomOffsets(colors: ByteArray?, start: MapPoint?, stride: Int, roomSize: Int,
        roomColor: Byte): List<CellOffset> =
        DungeonMapGeometryLogic.connectedRoomOffsets(colors, start, stride, roomSize, roomColor)

    @JvmStatic
    fun shape(cells: List<CellOffset>?): String = DungeonMapGeometryLogic.shape(cells)

    enum class RoomClearStatus {
        UNKNOWN,
        UNCLEARED,
        CLEARED_WHITE,
        CLEARED_GREEN,
        FAILED_RED;

        fun cleared(): Boolean = this == CLEARED_WHITE || this == CLEARED_GREEN
    }

    enum class RoomType(private val color: Int, private val displayLabel: String) {
        ENTRANCE(30, "entrance"),
        ROOM(63, "room"),
        RARE(63, "rare"),
        PUZZLE(66, "puzzle"),
        TRAP(62, "trap"),
        MINIBOSS(74, "miniboss"),
        CHAMPION(74, "champion"),
        FAIRY(82, "fairy"),
        BLOOD(18, "blood"),
        UNKNOWN(85, "unknown");

        fun label(): String = displayLabel
        fun mapColor(): Int = color

        companion object {
            @JvmStatic
            fun fromColor(color: Int): RoomType = DungeonMapGeometryLogic.roomTypeFromColor(color)
        }
    }

    data class MapPoint(val x: Int, val z: Int) {
        fun x(): Int = x
        fun z(): Int = z
        override fun toString(): String = "MapPoint[x=$x, z=$z]"
    }

    data class WorldCell(val baseX: Int, val baseZ: Int) {
        fun baseX(): Int = baseX
        fun baseZ(): Int = baseZ
        override fun toString(): String = "WorldCell[baseX=$baseX, baseZ=$baseZ]"
    }

    data class CellOffset(val x: Int, val z: Int) {
        fun x(): Int = x
        fun z(): Int = z
        override fun toString(): String = "CellOffset[x=$x, z=$z]"
    }

    data class Calibration(val calibrated: Boolean, val entrance: MapPoint?, val roomPixelSize: Int,
        val reason: String?) {
        fun calibrated(): Boolean = calibrated
        fun entrance(): MapPoint? = entrance
        fun roomPixelSize(): Int = roomPixelSize
        fun reason(): String? = reason
        override fun toString(): String =
            "Calibration[calibrated=$calibrated, entrance=$entrance, roomPixelSize=$roomPixelSize, reason=$reason]"
    }

    /** The list is copied and made immutable, matching the prior Java record constructor. */
    class RoomGeometry(
        val calibrated: Boolean,
        val calibration: Calibration?,
        val currentMapCell: MapPoint?,
        val mapColor: Byte,
        val roomType: RoomType?,
        connectedCells: List<CellOffset>?,
        val shape: String?,
        val fallbackReason: String?,
    ) {
        val connectedCells: List<CellOffset> = java.util.List.copyOf(connectedCells ?: emptyList())

        fun calibrated(): Boolean = calibrated
        fun calibration(): Calibration? = calibration
        fun currentMapCell(): MapPoint? = currentMapCell
        fun mapColor(): Byte = mapColor
        fun roomType(): RoomType? = roomType
        fun connectedCells(): List<CellOffset> = connectedCells
        fun shape(): String? = shape
        fun fallbackReason(): String? = fallbackReason

        override fun equals(other: Any?): Boolean = other is RoomGeometry &&
            calibrated == other.calibrated && calibration == other.calibration &&
            currentMapCell == other.currentMapCell && mapColor == other.mapColor && roomType == other.roomType &&
            connectedCells == other.connectedCells && shape == other.shape && fallbackReason == other.fallbackReason

        override fun hashCode(): Int {
            var result = if (calibrated) 1231 else 1237
            result = 31 * result + (calibration?.hashCode() ?: 0)
            result = 31 * result + (currentMapCell?.hashCode() ?: 0)
            result = 31 * result + mapColor.toInt()
            result = 31 * result + (roomType?.hashCode() ?: 0)
            result = 31 * result + connectedCells.hashCode()
            result = 31 * result + (shape?.hashCode() ?: 0)
            result = 31 * result + (fallbackReason?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "RoomGeometry[calibrated=$calibrated, calibration=$calibration, currentMapCell=$currentMapCell, " +
                "mapColor=$mapColor, roomType=$roomType, connectedCells=$connectedCells, shape=$shape, " +
                "fallbackReason=$fallbackReason]"
    }
}
