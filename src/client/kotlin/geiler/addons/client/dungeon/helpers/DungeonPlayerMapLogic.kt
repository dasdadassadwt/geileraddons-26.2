package geiler.addons.client.dungeon.helpers

import geiler.addons.client.dungeon.DungeonMapRoomDetector
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry
import geiler.addons.client.dungeon.DungeonRoomPlayerMarkerGeometry
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Locale

/** Kotlin projection logic; public Java records remain the caller-facing map contract. */
object DungeonPlayerMapLogic {
    private const val MAP_PIXELS = 128

    @JvmStatic
    fun project(
        layout: DungeonMapGrid.Layout?,
        tabPlayers: List<DungeonPlayerMap.TabPlayer?>?,
        decorations: List<DungeonMapRoomDetector.SavedMapDecoration?>?,
        loadedPlayers: List<DungeonRoomOverlayGeometry.PlayerMarker?>?,
        localPlayerName: String?,
    ): List<DungeonRoomOverlayGeometry.PlayerMarker> =
        projectWithDiagnostics(layout, tabPlayers, decorations, loadedPlayers, localPlayerName).players()

    @JvmStatic
    fun projectWithDiagnostics(
        tabPlayers: List<DungeonPlayerMap.TabPlayer?>?,
        decorations: List<DungeonMapRoomDetector.SavedMapDecoration?>?,
        loadedPlayers: List<DungeonRoomOverlayGeometry.PlayerMarker?>?,
        localPlayerName: String?,
    ): DungeonPlayerMap.Projection = projectWithDiagnostics(null, tabPlayers, decorations, loadedPlayers, localPlayerName)

    /** Pairs only complete ordered rosters with a calibrated map; otherwise names must match loaded entities. */
    @JvmStatic
    fun projectWithDiagnostics(
        layout: DungeonMapGrid.Layout?,
        tabPlayers: List<DungeonPlayerMap.TabPlayer?>?,
        decorations: List<DungeonMapRoomDetector.SavedMapDecoration?>?,
        loadedPlayers: List<DungeonRoomOverlayGeometry.PlayerMarker?>?,
        localPlayerName: String?,
    ): DungeonPlayerMap.Projection {
        val loaded: List<DungeonRoomOverlayGeometry.PlayerMarker?> = loadedPlayers ?: emptyList()
        val loadedByName = HashMap<String, DungeonRoomOverlayGeometry.PlayerMarker>()
        for (marker in loaded) {
            if (marker == null) continue
            val markerKey = key(marker.name())
            if (markerKey.isNotEmpty()) loadedByName.putIfAbsent(markerKey, marker)
        }

        val localKey = key(localPlayerName)
        val sourceRoster: List<DungeonPlayerMap.TabPlayer?> = tabPlayers ?: emptyList()
        val roster = ArrayList<DungeonPlayerMap.TabPlayer>()
        val seenRoster = HashSet<String>()
        var deadRosterRows = 0
        for (teammate in sourceRoster) {
            if (teammate == null) continue
            if (teammate.dead()) {
                deadRosterRows++
                continue
            }
            val teammateKey = key(teammate.name())
            if (teammateKey.isEmpty() || teammateKey == localKey || !seenRoster.add(teammateKey)) continue
            roster.add(teammate)
        }

        val icons: List<DungeonMapRoomDetector.SavedMapDecoration?> =
            decorations?.filter { marker -> marker != null && !marker.frame() } ?: emptyList()
        val calibrated = layout != null && layout.available && layout.roomGap > 0
        val paired = calibrated && roster.isNotEmpty() && icons.size == roster.size
        var pairingStatus = if (paired) "ordered" else if (!calibrated) "layoutUnavailable"
        else if (roster.isEmpty() && icons.isEmpty()) "empty"
        else if (icons.size != roster.size) "countMismatch" else "noRoster"

        val result = ArrayList<DungeonRoomOverlayGeometry.PlayerMarker>()
        var loadedMatches = 0
        var fallbackMarkers = 0
        var unresolvedRoster = 0
        var pairedDecorations = 0
        for (index in roster.indices) {
            val teammate = roster[index]
            val teammateKey = key(teammate.name())
            val entity = loadedByName[teammateKey]
            if (entity != null) loadedMatches++
            val dungeonClass = if (teammate.dungeonClass().isBlank()) entity?.dungeonClass() ?: ""
            else teammate.dungeonClass()

            var marker: DungeonRoomOverlayGeometry.PlayerMarker? = null
            if (paired) {
                marker = projectDecorationPosition(layout, icons[index], teammate.name(), dungeonClass)
                if (marker != null) pairedDecorations++
            }
            if (marker == null && entity != null) {
                marker = DungeonRoomOverlayGeometry.PlayerMarker(
                    entity.x(), entity.z(), teammate.name(), false,
                    entity.offsetX(), entity.offsetZ(), dungeonClass, entity.yawDegrees(),
                )
                fallbackMarkers++
            }
            if (marker == null) unresolvedRoster++ else result.add(marker)
        }

        val self = if (localKey.isEmpty()) null else loadedByName[localKey]
        if (self != null) {
            result.removeIf { marker -> marker.localPlayer() || key(marker.name()) == localKey }
            result.add(
                DungeonRoomOverlayGeometry.PlayerMarker(
                    self.x(), self.z(), self.name(), true, self.offsetX(), self.offsetZ(),
                    self.dungeonClass(), self.yawDegrees(),
                ),
            )
        }

        // Preserve conservative loaded-player markers when there is no roster at all.
        if (sourceRoster.isEmpty()) {
            for (marker in loaded) {
                if (marker == null || marker.localPlayer() || key(marker.name()) == localKey) continue
                if (result.none { existing -> key(existing.name()) == key(marker.name()) }) {
                    result.add(marker)
                    fallbackMarkers++
                }
            }
        }

        if (paired && pairedDecorations != icons.size) pairingStatus = "orderedPartial"
        val rejectedPairs = if (paired) icons.size - pairedDecorations else icons.size
        return DungeonPlayerMap.Projection(
            java.util.List.copyOf(result), roster.size, loadedByName.size, icons.size, loadedMatches,
            unresolvedRoster, self != null, pairedDecorations, fallbackMarkers, rejectedPairs,
            deadRosterRows, pairingStatus,
        )
    }

    private fun projectDecorationPosition(
        layout: DungeonMapGrid.Layout?,
        decoration: DungeonMapRoomDetector.SavedMapDecoration?,
        name: String,
        dungeonClass: String,
    ): DungeonRoomOverlayGeometry.PlayerMarker? {
        if (layout == null || !layout.available || layout.roomGap <= 0 || decoration == null) return null
        // Minecraft stores map icon positions as signed half-pixel offsets from the raster center.
        val pixelX = MAP_PIXELS / 2.0 + decoration.x() / 2.0
        val pixelZ = MAP_PIXELS / 2.0 + decoration.z() / 2.0
        val gridX = (pixelX - layout.startX) / layout.roomGap
        val gridZ = (pixelZ - layout.startY) / layout.roomGap
        if (!gridX.isFinite() || !gridZ.isFinite() || gridX < 0.0 || gridZ < 0.0 || gridX >= 6.0 || gridZ >= 6.0) return null
        val cellX = kotlin.math.floor(gridX).toInt()
        val cellZ = kotlin.math.floor(gridZ).toInt()
        // Remove the shared renderer's 180-degree offset to retain the icon's 16-step heading.
        val yaw = decoration.rotation() * (360.0f / 16.0f) - 180.0f
        return DungeonRoomOverlayGeometry.PlayerMarker(
            cellX, cellZ, name, false, gridX - cellX, gridZ - cellZ, dungeonClass, yaw,
        )
    }

    /** Projects an interpolated loaded-player position into the same 32-block map grid as Odin. */
    @JvmStatic
    fun projectLoadedPosition(
        name: String?,
        localPlayer: Boolean,
        dungeonClass: String?,
        worldX: Double,
        worldZ: Double,
        yawDegrees: Float,
    ): DungeonRoomOverlayGeometry.PlayerMarker? {
        if (!worldX.isFinite() || !worldZ.isFinite() || !yawDegrees.isFinite()) return null
        val position = DungeonRoomPlayerMarkerGeometry.projectContinuousWorldPosition(worldX, worldZ) ?: return null
        val cellX = kotlin.math.floor(position.x).toInt()
        val cellZ = kotlin.math.floor(position.z).toInt()
        return DungeonRoomOverlayGeometry.PlayerMarker(
            cellX, cellZ, name, localPlayer, position.x - cellX, position.z - cellZ, dungeonClass, yawDegrees,
        )
    }

    private fun key(value: String?): String = value?.let(::stripJavaWhitespace)?.lowercase(Locale.ROOT) ?: ""

    private fun stripJavaWhitespace(value: String): String = value.trim { Character.isWhitespace(it) }
}
