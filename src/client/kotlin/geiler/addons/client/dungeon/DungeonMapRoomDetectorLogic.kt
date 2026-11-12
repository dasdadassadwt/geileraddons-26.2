package geiler.addons.client.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import java.util.ArrayList

/** Client-thread map detection behind the Java record and static-method compatibility surface. */
object DungeonMapRoomDetectorLogic {
    private const val ENTRY_SCAN_INTERVAL_NANOS = 1_000_000_000L
    private const val GEOMETRY_REFRESH_TICKS = 20L
    private val DEFAULT_MAP_ID = MapId(1024)

    @Volatile
    private var current = readingUnavailable(
        DungeonMapRoomDetector.Status.NOT_SCANNED,
        null,
        DungeonMapRoomDetector.MapIdSource.UNKNOWN,
        "map detector has not run yet",
    )

    @Volatile
    private var currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN

    private var cachedMapId: MapId? = null
    private var lastEntryScanNanos = 0L
    private var clientTick = 0L
    private var lastGeometryRefreshTick = Long.MIN_VALUE
    private var geometryMap: MapItemSavedData? = null
    private var decorationLevel: ClientLevel? = null
    private var decorationMapId: MapId? = null
    private var packetDecorations: List<DungeonMapRoomDetector.SavedMapDecoration> = emptyList()
    private var cachedCalibration: DungeonMapGeometry.Calibration? = null
    private var cachedGeometry: DungeonMapGeometry.RoomGeometry? = null

    /** Captures ordered map decorations from the packet before saved-data ordering is lost. */
    @JvmStatic
    fun onMapItemData(packet: ClientboundMapItemDataPacket?) {
        if (packet == null) return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        if (client.player == null) return
        if (decorationLevel !== level) {
            decorationLevel = level
            decorationMapId = null
            packetDecorations = emptyList()
        }
        val expected = currentMapId(client).mapId
        if (expected == null || expected != packet.mapId()) return
        val decorations: List<MapDecoration?> = packet.decorations().orElse(null) ?: return
        val copy = ArrayList<DungeonMapRoomDetector.SavedMapDecoration>(decorations.size)
        for (decoration in decorations) {
            if (decoration == null) continue
            val frame = decoration.type().value() == MapDecorationTypes.FRAME.value()
            copy.add(
                DungeonMapRoomDetector.SavedMapDecoration(
                    decoration.x().toInt(), decoration.y().toInt(), decoration.rot().toInt() and 0xFF, frame,
                ),
            )
        }
        decorationMapId = packet.mapId()
        packetDecorations = java.util.List.copyOf(copy)
    }

    /** Reads local inventory and saved map data only; it never requests a world chunk. */
    @JvmStatic
    fun tick(client: Minecraft?) {
        val level = client?.level
        val player = client?.player
        if (client == null || player == null || level == null) {
            cachedMapId = null
            decorationLevel = null
            decorationMapId = null
            packetDecorations = emptyList()
            lastEntryScanNanos = 0L
            resetGeometryCache()
            current = readingUnavailable(
                DungeonMapRoomDetector.Status.NO_CLIENT_WORLD,
                null,
                DungeonMapRoomDetector.MapIdSource.UNKNOWN,
                "client world or player is unavailable",
            )
            currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN
            return
        }
        clientTick++
        val resolution = currentMapId(client)
        val mapId = resolution.mapId
        val mapIdSource = resolution.source ?: DungeonMapRoomDetector.MapIdSource.UNKNOWN
        val map = MapItem.getSavedData(mapId, level)
        val colors = map?.colors
        if (map == null || colors == null) {
            resetGeometryCache()
            current = readingUnavailable(
                DungeonMapRoomDetector.Status.MAP_DATA_MISSING,
                mapId,
                mapIdSource,
                "the current level has no saved data for this map id",
            )
            currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN
            return
        }

        val self = localPlayerFrame(map)
        if (self == null) {
            current = readingUnavailable(
                DungeonMapRoomDetector.Status.PLAYER_MARKER_MISSING,
                mapId,
                mapIdSource,
                "the map has no local-player frame marker",
            )
            currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN
            return
        }

        val marker = DungeonMapGeometry.MapPoint((self.x().toInt() shr 1) + 64, (self.y().toInt() shr 1) + 64)
        if (geometryMap !== map) {
            resetGeometryCache()
            geometryMap = map
        }
        val calibrationNow = cachedCalibration
        if (calibrationNow == null || !calibrationNow.calibrated || !calibrationStillPresent(colors, calibrationNow)) {
            cachedCalibration = DungeonMapGeometry.calibrate(colors, marker)
            cachedGeometry = null
            lastGeometryRefreshTick = Long.MIN_VALUE
        }
        val calibration = cachedCalibration
        if (calibration == null || !calibration.calibrated) {
            current = readingUnavailable(
                DungeonMapRoomDetector.Status.CALIBRATION_UNAVAILABLE,
                mapId,
                mapIdSource,
                marker,
                calibration,
                calibration?.reason ?: "calibration unavailable",
            )
            currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN
            return
        }
        val mapCell = DungeonMapGeometry.roomMapCell(
            marker, calibration.entrance, calibration.roomPixelSize + 4,
        )
        val oldGeometry = cachedGeometry
        val refreshGeometry = shouldRefreshGeometry(
            true,
            oldGeometry != null,
            oldGeometry?.currentMapCell(),
            mapCell,
            clientTick - lastGeometryRefreshTick,
            oldGeometry?.roomType() == DungeonMapGeometry.RoomType.UNKNOWN,
        )
        if (refreshGeometry) {
            cachedGeometry = DungeonMapGeometry.roomAt(colors, marker, calibration)
            lastGeometryRefreshTick = clientTick
        }
        val geometry = cachedGeometry
        val origin = DungeonMapGeometry.physicalCellAt(player.getX(), player.getZ())
        if (origin == null) {
            current = readingUnavailable(
                DungeonMapRoomDetector.Status.CURRENT_CELL_UNAVAILABLE,
                mapId,
                mapIdSource,
                marker,
                calibration,
                geometry,
                "player world coordinates cannot be represented as a catalog grid cell",
            )
            currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN
            return
        }
        val cell = DungeonGridRules.cellAtOrigin(origin.baseX, origin.baseZ)
        val reason = geometry?.fallbackReason()
        val status = if (geometry?.roomType() == DungeonMapGeometry.RoomType.UNKNOWN) {
            DungeonMapRoomDetector.Status.ROOM_TYPE_UNAVAILABLE
        } else {
            DungeonMapRoomDetector.Status.AVAILABLE
        }
        current = DungeonMapRoomDetector.Reading(status, mapId, resolution.source, marker, calibration, geometry, cell, reason)
        currentRoomClearStatus = DungeonMapGeometry.roomClearStatus(colors, geometry)
    }

    private fun calibrationStillPresent(colors: ByteArray?, calibration: DungeonMapGeometry.Calibration?): Boolean {
        if (colors == null || calibration == null || !calibration.calibrated || calibration.entrance == null) return false
        val x = calibration.entrance.x
        val z = calibration.entrance.z - 1
        return x >= 0 && z >= 0 && x < DungeonMapGeometry.MAP_SIZE && z < DungeonMapGeometry.MAP_SIZE &&
            (colors[x + (z shl 7)].toInt() and 0xFF) == DungeonMapGeometry.ENTRANCE_COLOR
    }

    @JvmStatic
    fun shouldRefreshGeometry(
        sameMap: Boolean,
        hasCachedGeometry: Boolean,
        cachedCell: DungeonMapGeometry.MapPoint?,
        playerCell: DungeonMapGeometry.MapPoint?,
        ticksSinceRefresh: Long,
        roomUnknown: Boolean,
    ): Boolean = !sameMap || !hasCachedGeometry || cachedCell == null || playerCell == null ||
        cachedCell != playerCell || ticksSinceRefresh < 0L || ticksSinceRefresh >= GEOMETRY_REFRESH_TICKS || roomUnknown

    private fun resetGeometryCache() {
        geometryMap = null
        cachedCalibration = null
        cachedGeometry = null
        lastGeometryRefreshTick = Long.MIN_VALUE
    }

    /** Cheap entry probe before dungeon context; checks the local map marker and nearby pixels only. */
    @JvmStatic
    fun hasEntryCandidate(client: Minecraft?): Boolean {
        val level = client?.level
        if (client == null || client.player == null || level == null) return false
        val mapId = currentMapId(client).mapId
        val map = MapItem.getSavedData(mapId, level)
        val colors = map?.colors
        if (map == null || colors == null) return false
        val marker = localPlayerFrame(map) ?: return false
        val point = DungeonMapGeometry.MapPoint((marker.x().toInt() shr 1) + 64, (marker.y().toInt() shr 1) + 64)
        return hasDungeonMapMarkerColor(colors, point)
    }

    @JvmStatic
    fun hasDungeonMapMarkerColor(colors: ByteArray?, marker: DungeonMapGeometry.MapPoint?): Boolean {
        if (colors == null || colors.size < DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE ||
            marker == null || marker.x < 0 || marker.z < 0 ||
            marker.x >= DungeonMapGeometry.MAP_SIZE || marker.z >= DungeonMapGeometry.MAP_SIZE
        ) return false
        for (dz in -1..1) {
            for (dx in -1..1) {
                val x = marker.x + dx
                val z = marker.z + dz
                if (x < 0 || z < 0 || x >= DungeonMapGeometry.MAP_SIZE || z >= DungeonMapGeometry.MAP_SIZE) continue
                val color = colors[x + (z shl 7)].toInt() and 0xFF
                if (DungeonMapGeometry.RoomType.fromColor(color) != DungeonMapGeometry.RoomType.UNKNOWN) return true
            }
        }
        return false
    }

    /** Entry calibration is limited to one attempt per second before context is confirmed. */
    @JvmStatic
    fun shouldRunEntryScan(nowNanos: Long): Boolean {
        if (!entryScanIntervalElapsed(lastEntryScanNanos, nowNanos)) return false
        lastEntryScanNanos = nowNanos
        return true
    }

    @JvmStatic
    fun entryScanIntervalElapsed(lastScanNanos: Long, nowNanos: Long): Boolean =
        lastScanNanos == 0L || nowNanos - lastScanNanos >= ENTRY_SCAN_INTERVAL_NANOS

    @JvmStatic
    fun deferUntilDungeonContext() {
        current = readingUnavailable(
            DungeonMapRoomDetector.Status.NOT_SCANNED,
            cachedMapId,
            DungeonMapRoomDetector.MapIdSource.UNKNOWN,
            "full map geometry is gated until dungeon context or map-entry evidence is available",
        )
        currentRoomClearStatus = DungeonMapGeometry.RoomClearStatus.UNKNOWN
    }

    private fun currentMapId(client: Minecraft): DungeonMapRoomDetector.MapIdResolution {
        val player = client.player ?: return DungeonMapRoomDetector.MapIdResolution(
            DEFAULT_MAP_ID, DungeonMapRoomDetector.MapIdSource.DEFAULT,
        )
        val inventory = player.getInventory()
        val hotbar = inventory.getNonEquipmentItems()
        val stack: ItemStack? = if (hotbar.size > 8) hotbar[8] else ItemStack.EMPTY
        val hasHotbarMapId = stack != null && stack.`is`(Items.FILLED_MAP) && stack.has(DataComponents.MAP_ID)
        val selected = resolveMapId(
            hasHotbarMapId,
            if (hasHotbarMapId) stack.get(DataComponents.MAP_ID) else null,
            cachedMapId,
        )
        val resolution = DungeonMapRoomDetector.MapIdResolution(selected.key, selected.value)
        if (resolution.source == DungeonMapRoomDetector.MapIdSource.HOTBAR) cachedMapId = resolution.mapId
        return resolution
    }

    private fun localPlayerFrame(map: MapItemSavedData): MapDecoration? {
        for (decoration in map.getDecorations()) {
            if (decoration.type().value() == MapDecorationTypes.FRAME.value()) return decoration
        }
        return null
    }

    @JvmStatic
    fun resolveMapId(
        hasHotbarMapId: Boolean,
        hotbarMapId: MapId?,
        cachedMapId: MapId?,
    ): Map.Entry<MapId, DungeonMapRoomDetector.MapIdSource> {
        if (hasHotbarMapId && hotbarMapId != null) {
            return java.util.AbstractMap.SimpleImmutableEntry(hotbarMapId, DungeonMapRoomDetector.MapIdSource.HOTBAR)
        }
        if (cachedMapId != null) {
            return java.util.AbstractMap.SimpleImmutableEntry(cachedMapId, DungeonMapRoomDetector.MapIdSource.CACHED)
        }
        return java.util.AbstractMap.SimpleImmutableEntry(DEFAULT_MAP_ID, DungeonMapRoomDetector.MapIdSource.DEFAULT)
    }

    @JvmStatic
    fun current(): DungeonMapRoomDetector.Reading = current

    /** Returns a defensive client-thread snapshot from the already-saved map. */
    @JvmStatic
    fun overlaySnapshot(client: Minecraft?): DungeonMapRoomDetector.OverlaySnapshot? {
        val level = client?.level
        if (client == null || client.player == null || level == null) return null
        val reading = current
        if (!reading.calibrated() || reading.mapId == null || reading.currentCell == null) return null
        val map = MapItem.getSavedData(reading.mapId, level)
        val colors = map?.colors
        if (map == null || colors == null || colors.size < DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE) {
            return null
        }
        return DungeonMapRoomDetector.OverlaySnapshot(
            colors.clone(), reading.mapId, reading.calibration, reading.geometry, reading.currentCell,
        )
    }

    /** Returns saved map pixels without requiring the legacy map calibration. */
    @JvmStatic
    fun savedMapColors(client: Minecraft?): ByteArray? {
        val level = client?.level
        if (client == null || client.player == null || level == null) return null
        val reading = current
        val mapId = reading.mapId ?: currentMapId(client).mapId
        val map = MapItem.getSavedData(mapId, level)
        val colors = map?.colors
        if (map == null || colors == null || colors.size < DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE) return null
        return colors.clone()
    }

    /** Returns packet-ordered decorations only when their world and map IDs still match. */
    @JvmStatic
    fun savedMapDecorations(client: Minecraft?): List<DungeonMapRoomDetector.SavedMapDecoration> {
        val level = client?.level
        if (client == null || client.player == null || level == null) return emptyList()
        if (decorationLevel !== level) {
            decorationLevel = level
            decorationMapId = null
            packetDecorations = emptyList()
        }
        val mapId = currentMapId(client).mapId
        return if (mapId == decorationMapId) packetDecorations else emptyList()
    }

    @JvmStatic
    fun currentRoomCleared(): Boolean = currentRoomClearStatus.cleared()

    @JvmStatic
    fun currentRoomClearStatus(): DungeonMapGeometry.RoomClearStatus = currentRoomClearStatus

    @JvmStatic
    fun diagnostics(): String {
        val reading = current
        val calibration = reading.calibration
        val calibrationText = if (calibration != null && calibration.calibrated) {
            "entrance=${calibration.entrance}, pixelRoomSize=${calibration.roomPixelSize}"
        } else "unavailable(${reading.fallbackReason})"
        val geometry = reading.geometry
        val roomType = geometry?.roomType()
        val room = if (geometry == null || roomType == null) "unknown" else
            "${roomType.label()}/${geometry.shape()} at ${geometry.currentMapCell()}"
        return "map=${reading.status}, mapId=${reading.mapId?.id() ?: "unknown"} [${reading.mapIdSource}]" +
            ", calibration=$calibrationText, mapRoom=$room" +
            ", roomClear=$currentRoomClearStatus" +
            ", fallback=${reading.fallbackReason ?: "none"}"
    }

    private fun readingUnavailable(
        status: DungeonMapRoomDetector.Status,
        mapId: MapId?,
        source: DungeonMapRoomDetector.MapIdSource,
        reason: String?,
    ) = DungeonRoomRoomReading.create(status, mapId, source, null, null, null, null, reason)

    private fun readingUnavailable(
        status: DungeonMapRoomDetector.Status,
        mapId: MapId?,
        source: DungeonMapRoomDetector.MapIdSource,
        marker: DungeonMapGeometry.MapPoint?,
        calibration: DungeonMapGeometry.Calibration?,
        reason: String?,
    ) = DungeonRoomRoomReading.create(status, mapId, source, marker, calibration, null, null, reason)

    private fun readingUnavailable(
        status: DungeonMapRoomDetector.Status,
        mapId: MapId?,
        source: DungeonMapRoomDetector.MapIdSource,
        marker: DungeonMapGeometry.MapPoint?,
        calibration: DungeonMapGeometry.Calibration?,
        geometry: DungeonMapGeometry.RoomGeometry?,
        reason: String?,
    ) = DungeonRoomRoomReading.create(status, mapId, source, marker, calibration, geometry, null, reason)

    private object DungeonRoomRoomReading {
        fun create(
            status: DungeonMapRoomDetector.Status,
            mapId: MapId?,
            source: DungeonMapRoomDetector.MapIdSource,
            marker: DungeonMapGeometry.MapPoint?,
            calibration: DungeonMapGeometry.Calibration?,
            geometry: DungeonMapGeometry.RoomGeometry?,
            cell: DungeonGridRules.Cell?,
            reason: String?,
        ) = DungeonMapRoomDetector.Reading(status, mapId, source, marker, calibration, geometry, cell, reason)
    }
}

