package geiler.addons.client.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.level.saveddata.maps.MapId

/** Java-compatible map detector API backed by the client-thread Kotlin detector. */
object DungeonMapRoomDetector {
    /** Captures Odin's ordered map decorations from the packet before saved-data ordering is lost. */
    @JvmStatic
    fun onMapItemData(packet: ClientboundMapItemDataPacket?) = DungeonMapRoomDetectorLogic.onMapItemData(packet)

    /** Reads only the local inventory and saved map data; it never requests a world chunk. */
    @JvmStatic
    fun tick(client: Minecraft?) = DungeonMapRoomDetectorLogic.tick(client)

    @JvmStatic
    fun shouldRefreshGeometry(sameMap: Boolean, hasCachedGeometry: Boolean, cachedCell: DungeonMapGeometry.MapPoint?,
        playerCell: DungeonMapGeometry.MapPoint?, ticksSinceRefresh: Long, roomUnknown: Boolean): Boolean =
        DungeonMapRoomDetectorLogic.shouldRefreshGeometry(
            sameMap, hasCachedGeometry, cachedCell, playerCell, ticksSinceRefresh, roomUnknown,
        )

    @JvmStatic
    fun hasEntryCandidate(client: Minecraft?): Boolean = DungeonMapRoomDetectorLogic.hasEntryCandidate(client)

    @JvmStatic
    fun hasDungeonMapMarkerColor(colors: ByteArray?, marker: DungeonMapGeometry.MapPoint?): Boolean =
        DungeonMapRoomDetectorLogic.hasDungeonMapMarkerColor(colors, marker)

    @JvmStatic
    fun shouldRunEntryScan(nowNanos: Long): Boolean = DungeonMapRoomDetectorLogic.shouldRunEntryScan(nowNanos)

    @JvmStatic
    fun entryScanIntervalElapsed(lastScanNanos: Long, nowNanos: Long): Boolean =
        DungeonMapRoomDetectorLogic.entryScanIntervalElapsed(lastScanNanos, nowNanos)

    @JvmStatic
    fun deferUntilDungeonContext() = DungeonMapRoomDetectorLogic.deferUntilDungeonContext()

    @JvmStatic
    fun resolveMapId(hasHotbarMapId: Boolean, hotbarMapId: MapId?, cachedMapId: MapId?): MapIdResolution {
        val resolution = DungeonMapRoomDetectorLogic.resolveMapId(hasHotbarMapId, hotbarMapId, cachedMapId)
        return MapIdResolution(resolution.key, resolution.value)
    }

    @JvmStatic
    fun current(): Reading = DungeonMapRoomDetectorLogic.current()

    /** Returns a defensive client-thread snapshot for the optional map overlay. */
    @JvmStatic
    fun overlaySnapshot(client: Minecraft?): OverlaySnapshot? = DungeonMapRoomDetectorLogic.overlaySnapshot(client)

    /** Returns saved map pixels without requiring the legacy map calibration. */
    @JvmStatic
    fun savedMapColors(client: Minecraft?): ByteArray? = DungeonMapRoomDetectorLogic.savedMapColors(client)

    /** Returns ordered decorations from the latest matching map packet. */
    @JvmStatic
    fun savedMapDecorations(client: Minecraft?): List<SavedMapDecoration> =
        DungeonMapRoomDetectorLogic.savedMapDecorations(client)

    @JvmStatic
    fun currentRoomCleared(): Boolean = DungeonMapRoomDetectorLogic.currentRoomCleared()

    @JvmStatic
    fun currentRoomClearStatus(): DungeonMapGeometry.RoomClearStatus =
        DungeonMapRoomDetectorLogic.currentRoomClearStatus()

    @JvmStatic
    fun diagnostics(): String = DungeonMapRoomDetectorLogic.diagnostics()

    enum class Status {
        NOT_SCANNED,
        NO_CLIENT_WORLD,
        MAP_ITEM_MISSING,
        MAP_ID_MISSING,
        MAP_DATA_MISSING,
        PLAYER_MARKER_MISSING,
        CALIBRATION_UNAVAILABLE,
        CURRENT_CELL_UNAVAILABLE,
        ROOM_TYPE_UNAVAILABLE,
        AVAILABLE,
    }

    enum class MapIdSource { UNKNOWN, HOTBAR, CACHED, DEFAULT }

    data class MapIdResolution(val mapId: MapId?, val source: MapIdSource?) {
        fun mapId(): MapId? = mapId
        fun source(): MapIdSource? = source
        override fun toString(): String = "MapIdResolution[mapId=$mapId, source=$source]"
    }

    /** Map icon coordinates and rotations are normalized to the byte ranges used by map decorations. */
    class SavedMapDecoration(x: Int, z: Int, rotation: Int, val frame: Boolean) {
        val x: Int = x.coerceIn(-128, 127)
        val z: Int = z.coerceIn(-128, 127)
        val rotation: Int = Math.floorMod(rotation, 16)

        fun x(): Int = x
        fun z(): Int = z
        fun rotation(): Int = rotation
        fun frame(): Boolean = frame

        override fun equals(other: Any?): Boolean = other is SavedMapDecoration &&
            x == other.x && z == other.z && rotation == other.rotation && frame == other.frame

        override fun hashCode(): Int {
            var result = x
            result = 31 * result + z
            result = 31 * result + rotation
            result = 31 * result + if (frame) 1231 else 1237
            return result
        }

        override fun toString(): String =
            "SavedMapDecoration[x=$x, z=$z, rotation=$rotation, frame=$frame]"
    }

    /** Clones map pixels both on capture and read so the overlay sees a stable frame. */
    class OverlaySnapshot(
        colors: ByteArray?,
        private val mapIdValue: MapId?,
        private val calibrationValue: DungeonMapGeometry.Calibration?,
        private val currentRoomValue: DungeonMapGeometry.RoomGeometry?,
        private val currentCellValue: DungeonGridRules.Cell?,
    ) {
        private val colorValues: ByteArray = colors?.clone() ?: ByteArray(0)

        fun colors(): ByteArray = colorValues.clone()
        fun mapId(): MapId? = mapIdValue
        fun calibration(): DungeonMapGeometry.Calibration? = calibrationValue
        fun currentRoom(): DungeonMapGeometry.RoomGeometry? = currentRoomValue
        fun currentCell(): DungeonGridRules.Cell? = currentCellValue

        override fun equals(other: Any?): Boolean = other is OverlaySnapshot &&
            colorValues === other.colorValues && mapIdValue == other.mapIdValue &&
            calibrationValue == other.calibrationValue && currentRoomValue == other.currentRoomValue &&
            currentCellValue == other.currentCellValue

        override fun hashCode(): Int {
            var result = colorValues.hashCode()
            result = 31 * result + (mapIdValue?.hashCode() ?: 0)
            result = 31 * result + (calibrationValue?.hashCode() ?: 0)
            result = 31 * result + (currentRoomValue?.hashCode() ?: 0)
            result = 31 * result + (currentCellValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String = "OverlaySnapshot[colors=${java.lang.String.valueOf(colorValues)}, " +
            "mapId=$mapIdValue, calibration=$calibrationValue, currentRoom=$currentRoomValue, " +
            "currentCell=$currentCellValue]"
    }

    data class Reading(
        val status: Status?,
        val mapId: MapId?,
        val mapIdSource: MapIdSource?,
        val playerMapPosition: DungeonMapGeometry.MapPoint?,
        val calibration: DungeonMapGeometry.Calibration?,
        val geometry: DungeonMapGeometry.RoomGeometry?,
        val currentCell: DungeonGridRules.Cell?,
        val fallbackReason: String?,
    ) {
        fun status(): Status? = status
        fun mapId(): MapId? = mapId
        fun mapIdSource(): MapIdSource? = mapIdSource
        fun playerMapPosition(): DungeonMapGeometry.MapPoint? = playerMapPosition
        fun calibration(): DungeonMapGeometry.Calibration? = calibration
        fun geometry(): DungeonMapGeometry.RoomGeometry? = geometry
        fun currentCell(): DungeonGridRules.Cell? = currentCell
        fun fallbackReason(): String? = fallbackReason

        fun calibrated(): Boolean = calibration?.calibrated() == true && geometry?.calibrated() == true

        fun hasCurrentCell(): Boolean = calibrated() && currentCell != null

        fun confirmsDungeonRoom(): Boolean =
            hasCurrentCell() && geometry?.roomType() != null &&
                geometry.roomType() != DungeonMapGeometry.RoomType.UNKNOWN

        fun signature(): String = status.toString() + "|" +
            (currentCell?.let { "${it.componentX},${it.componentZ}" } ?: "none") +
            "|" + (geometry?.let { "${it.currentMapCell()}/${it.roomType()}/${it.shape()}/${it.connectedCells()}" }
                ?: "none")

        override fun toString(): String = "Reading[status=$status, mapId=$mapId, mapIdSource=$mapIdSource, " +
            "playerMapPosition=$playerMapPosition, calibration=$calibration, geometry=$geometry, " +
            "currentCell=$currentCell, fallbackReason=$fallbackReason]"
    }
}
