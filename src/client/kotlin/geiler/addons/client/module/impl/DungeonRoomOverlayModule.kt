package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import com.mojang.blaze3d.platform.NativeImage
import geiler.addons.GeilerAddons
import geiler.addons.client.access.PlayerTabOverlayAccess
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.dungeon.*
import geiler.addons.client.dungeon.helpers.*
import geiler.addons.client.dungeon.score.DungeonScoreState
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.*
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.party.PartyMember
import geiler.addons.client.party.PartySnapshot
import geiler.addons.client.presentation.PlayerTabNameSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import java.util.ArrayList
import java.util.HashMap
import java.util.Iterator
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects
import java.util.Optional
import java.util.Set
import kotlin.math.roundToInt

/** Configurable 6x6 dungeon map based only on map pixels and loaded client observations. */
class DungeonRoomOverlayModule private constructor(private val settings: Settings) : Module(
    "Dungeon Map",
    "Shows the Catacombs room map, room names and secrets, doors, predictions, and party markers.",
    Category.F7,
    false,
    *settings.all(),
), HudElement, ModulePreview {
    private val styleSettings: List<Setting> = settings.all().toList()
    private val rasterText = DungeonRoomMapRasterText()
    private val rasterTextTextures = LinkedHashMap<String, RasterTextTexture>(16, 0.75f, true)
    private val mapRenderCache = DungeonRoomOverlayRenderCache()
    private val previewRenderCache = DungeonRoomOverlayRenderCache()
    private var rasterTextureBytes = 0L
    @Volatile private var snapshot: DungeonRoomOverlayGeometry.Snapshot =
        DungeonRoomOverlayGeometry.Snapshot.unavailable("waiting for calibrated dungeon map")
    @Volatile private var playerSkinTextures: Map<String, Identifier> = emptyMap<String, Identifier>()
    private val roomCoreScanner = DungeonMapCoreScanner()
    private val roomLifecycle = DungeonRoomLifecycle()
    private val secretProgress = DungeonRoomSecretProgress()
    private val playerMarkerRetention = DungeonRoomPlayerMarkerRetention()
    private var lastMapGrid: DungeonMapGrid.ScanResult? = null
    @Volatile private var lastGoodMapSnapshot: DungeonRoomOverlayGeometry.Snapshot? = null
    private var lastGoodMapTick = -1L
    private var activeMapTicks = 0L
    private var playerMarkerTick = 0L
    private var lastMapBufferDiagnostics = "not-read"
    private var lastMapScanRejection = "not-scanned"
    private var mapBufferReads = 0L
    private var secretProgressLevel: Any? = null
    private var secretProgressFloor = -1
    private var currentMapRoomId: String? = null
    private var acceptedActionbarUpdates = 0L
    @Volatile private var lastEnteredRoomId: String? = null
    private var tickCounter = 0
    private var diagnosticTick = 0L
    private var lastAutomaticDiagnosticTick = Long.MIN_VALUE
    private var coalescedAutomaticDiagnostics = 0L
    private var lastAutomaticDiagnostic = ""
    private var lastRenderSummary = "not-rendered"
    private var lastPlayerMapDiagnostics = "not-projected"
    private var cachedRoomLabels: RoomLabelCache? = null
    private var interpolatedMarkerLevel: Any? = null
    private var previousLocalWorldX = Double.NaN
    private var previousLocalWorldZ = Double.NaN
    private var playerTeleportSnaps = 0L
    private var lastPlayerPositionSnapped = false
    private var lastCoreScanNanos = 0L
    private var maximumCoreScanNanos = 0L
    private var lastMapProjectionNanos = 0L
    private var maximumMapProjectionNanos = 0L
    private var mapProjectionCount = 0L
    private var lastHudRenderNanos = 0L
    private var maximumHudRenderNanos = 0L
    private var hudRenderCount = 0L

    init {
        val copyStyle = ModuleAction(
            "Copy Style to Clipboard",
            "Copy all Dungeon Map settings as bounded Base64 JSON.",
            Runnable { copyStyleToClipboard() },
        )
        val importStyle = ModuleAction(
            "Import Style from Clipboard",
            "Apply validated Dungeon Map JSON or Base64 from the clipboard.",
            Runnable { importStyleFromClipboard() },
        )
        val resetStyle = ModuleAction(
            "Reset All Map Settings",
            "Restore every Dungeon Map setting to its declared default.",
            Runnable { resetMapSettings() },
        )
        group(
            SettingGroup.switched("Overlay", settings.showMap, settings.showExits,
                settings.revealUnopened, settings.showInBoss, settings.rotateMap),
            SettingGroup.switched("Map Info", settings.mapInfo, settings.mapInfoIncludeRemaining,
                settings.mapInfoRemaining, settings.mapInfoDeaths, settings.mapInfoHideInBoss,
                settings.mapInfoBackground),
            SettingGroup.switchedFolded("Map Preview", settings.showPreview),
            SettingGroup.folded("Map Style", copyStyle, importStyle, resetStyle),
            SettingGroup.folded("Appearance").containing(
                SettingGroup.folded("Layout", settings.roomText, settings.mapScale, settings.roomTextScale,
                    settings.roomTextAlignment, settings.textShadowMode, settings.useMcFontRenderer,
                    settings.roomSize, settings.roomGap, settings.doorSize, settings.roomIconSize,
                    settings.roomIconAlignment, settings.iconStyle, settings.mapPadding, settings.outlineWidth),
                SettingGroup.folded("Players", settings.showPlayers, settings.showPlayerNames,
                    settings.showPlayerClasses, settings.playerClassNameOnly, settings.playerNamesUseClassColors,
                    settings.playerMarkersUseClassColors, settings.playerNamesOnlyWhileLeap,
                    settings.showPlayerHeads, settings.showOwnPlayerHead, settings.showSelfMarker,
                    settings.playerHeadSize, settings.playerHeadScale, settings.playerMarkerScale,
                    settings.playerNamesScale, settings.playerNameColor),
                SettingGroup.folded("Room Text and Icons", settings.showCheckmarks,
                    settings.hideCheckmarkWithName, settings.showPuzzleName, settings.showRoomSecrets,
                    settings.skipOneSecretRoom, settings.skipCommonRoomNames, settings.skipYellowRoomName,
                    settings.showFairyCheckmark, settings.checkmarkOnlyWhenGreen,
                    settings.checkmarkIfNoSecrets, settings.roomTextColorEnabled),
                SettingGroup.folded("Colors").containing(
                    SettingGroup.folded("Map", settings.showOutline, settings.background,
                        settings.outlineColor, settings.textColor, settings.currentRoomColor),
                    SettingGroup.folded("Rooms", settings.roomColor, settings.puzzleColor,
                        settings.trapColor, settings.minibossColor, settings.entranceColor, settings.fairyColor,
                        settings.bloodRoomColor, settings.unknownRoomColor, settings.rareRoomColor,
                        settings.championRoomColor, settings.pendingRoomColor),
                    SettingGroup.folded("Doors", settings.normalDoorColor, settings.glassDoorColor,
                        settings.startDoorColor, settings.witherDoorColor, settings.bloodDoorColor,
                        settings.fairyDoorColor, settings.unknownDoorColor, settings.normalDoorDynamicColor),
                    SettingGroup.folded("Unopened Rooms", settings.unopenedRoomColor,
                        settings.unopenedTextColor, settings.unopenedDarkenFactor, settings.disablePredictions)),
            ),
        )
    }

    private fun copyStyleToClipboard() {
        val encoded = DungeonRoomMapStyleCodec.encodeBase64(styleSettings)
        if (encoded == null) {
            mapStyleMessage("Could not export map settings: the style exceeds the 32 KiB limit.")
            return
        }
        val client = Minecraft.getInstance()
        try {
            client.keyboardHandler.setClipboard(encoded)
            mapStyleMessage("Copied Dungeon Map style as Base64 JSON.")
        } catch (_: RuntimeException) {
            mapStyleMessage("Could not access the clipboard.")
        }
    }

    private fun importStyleFromClipboard() {
        val client = Minecraft.getInstance()
        val clipboard = try {
            client.keyboardHandler.getClipboard()
        } catch (_: RuntimeException) {
            mapStyleMessage("Could not access the clipboard.")
            return
        }
        val result = DungeonRoomMapStyleCodec.decodeClipboard(clipboard)
        if (!result.success) {
            mapStyleMessage(result.error ?: "Unknown map style error.")
            return
        }
        if (!DungeonRoomMapStyleCodec.appliesTo(styleSettings, result.decoded)) {
            mapStyleMessage("Map style contains incompatible or out-of-range settings.")
            return
        }
        DungeonRoomMapStyleCodec.apply(styleSettings, result.decoded)
        ModConfig.markDirty()
        mapStyleMessage("Imported Dungeon Map style.")
    }

    private fun resetMapSettings() {
        if (!DungeonRoomMapStyleCodec.reset(styleSettings)) {
            mapStyleMessage("Could not reset Dungeon Map settings.")
            return
        }
        ModConfig.markDirty()
        mapStyleMessage("Restored Dungeon Map defaults.")
    }

    private fun mapStyleMessage(message: String) {
        val client = Minecraft.getInstance()
        if (client.gui != null) client.gui.hud.chat.addClientSystemMessage(
            Component.literal("[GeilerAddons] Dungeon Map: " + message),
        )
    }

    /** Keep the old persisted key so saved layouts and settings survive the display-name change. */
    override fun configName(): String = "Dungeon Room Map"

    override fun onDisable() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), false)
        secretProgress.reset()
        currentMapRoomId = null
        resetInterpolatedPosition()
        cachedRoomLabels = null
        clearRasterTextTextures()
    }

    private fun resetInterpolatedPosition() {
        interpolatedMarkerLevel = null
        previousLocalWorldX = Double.NaN
        previousLocalWorldZ = Double.NaN
        lastPlayerPositionSnapped = false
    }

    private fun clearRasterTextTextures() {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { clearRasterTextTextures() }
            return
        }
        val textures = client.textureManager
        for (texture in rasterTextTextures.values) textures.release(texture.id)
        rasterTextTextures.clear()
        rasterTextureBytes = 0L
    }

    override fun isSettingVisible(setting: Setting): Boolean =
        // Retained as a config key so older profiles can migrate to the richer shadow-mode setting.
        setting !== settings.legacyTextShadow

    /** Called after saved values are applied, preserving older shadow and marker-size preferences. */
    fun restoreLegacySettings(
        toggles: Map<String, Boolean>?,
        numbers: Map<String, Float>?,
        choices: Map<String, String>?,
    ): Boolean {
        val prefix = configName() + "."
        var migrated = false
        val modeKey = prefix + settings.textShadowMode.name() + ""
        val oldShadow = toggles?.get(prefix + settings.legacyTextShadow.name())
        val savedShadowMode = choices?.get(modeKey)
        if ((savedShadowMode == null || savedShadowMode !in settings.textShadowMode.choices()) && oldShadow != null) {
            settings.textShadowMode.setValue(if (oldShadow) "Drop" else "None")
            migrated = true
        }
        migrated = migrateLegacyAlignment(
            choices, prefix + settings.roomTextAlignment.name(), settings.roomTextAlignment,
        ) || migrated
        migrated = migrateLegacyAlignment(
            choices, prefix + settings.roomIconAlignment.name(), settings.roomIconAlignment,
        ) || migrated
        val headScaleKey = prefix + settings.playerHeadScale.name()
        val oldMarkerScale = numbers?.get(prefix + settings.playerMarkerScale.name())
        if (oldMarkerScale != null && numbers?.containsKey(headScaleKey) != true) {
            settings.playerHeadScale.setValue(oldMarkerScale)
            migrated = true
        }
        val oldClassColors = toggles?.get(prefix + settings.playerNamesUseClassColors.name())
        val markerColorsKey = prefix + settings.playerMarkersUseClassColors.name()
        if (toggles?.containsKey(markerColorsKey) != true && oldClassColors != null) {
            settings.playerMarkersUseClassColors.setValue(oldClassColors)
            migrated = true
        }
        return migrated
    }

    /** Latest lifecycle-confirmed room transition, keyed by Odin's physical 6x6 map cells. */
    fun lastEnteredRoomId(): String? = lastEnteredRoomId

    /** Clears run-local room counts for the start cue emitted by every new dungeon run. */
    fun onDungeonChatMessage(message: String?) {
        if (message == null) return
        if (isEnabled() && settings.showMap.value()) DungeonContextTracker.observeMapOverlayCountdown(message)
        if (!DungeonContextTracker.inDungeon() || !message.lowercase(Locale.ROOT)
                .contains("mort: here, i found this map when i first entered the dungeon.")) return
        resetRunProgress()
        lastMapGrid = null
        lastGoodMapSnapshot = null
        lastGoodMapTick = -1
        activeMapTicks = 0
        mapBufferReads = 0
        lastMapBufferDiagnostics = "not-read"
        lastMapScanRejection = "not-scanned"
        snapshot = DungeonRoomOverlayGeometry.Snapshot.unavailable("new dungeon run")
        tickCounter = 0
        lastRenderSummary = "new-run"
    }

    /** Accepts only the client's actionbar secret counter and only for the mapped room under the player. */
    fun onActionbar(content: String?, overlay: Boolean) {
        if (!overlay || !isEnabled() || !DungeonContextTracker.inDungeon()) return
        val client = Minecraft.getInstance()
        val accept = Runnable {
            if (!client.isSameThread || client.level == null || client.player == null || inBossPhase()
                || lastMapGrid == null || lastMapGrid?.available() != true) return@Runnable
            val grid = lastMapGrid ?: return@Runnable
            refreshCurrentMapRoom(client, grid)
            val roomId = currentMapRoomId
            if (roomId == null || !Objects.equals(roomId, resolveCurrentMapRoomId(client, grid, snapshot))) return@Runnable
            val room = roomById(snapshot, roomId)
            if (room == null || room.maxSecrets() <= 0) return@Runnable
            secretProgress.enterRoom(roomId)
            if (secretProgress.recordActionbar(content, roomId, room.maxSecrets())) {
                acceptedActionbarUpdates++
                snapshot = snapshotWithSecretProgress(snapshot)
                lastRenderSummary = renderSummary(snapshot)
            }
        }
        if (client.isSameThread) accept.run() else client.execute(accept)
    }

    /** Clears progress and visited-room observations when the network session ends. */
    fun onDisconnect() {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { onDisconnect() }
            return
        }
        secretProgress.reset()
        roomLifecycle.reset()
        roomCoreScanner.resetWorld()
        playerMarkerRetention.clear()
        lastMapGrid = null
        lastGoodMapSnapshot = null
        lastGoodMapTick = -1
        activeMapTicks = 0
        mapBufferReads = 0
        lastMapBufferDiagnostics = "not-read"
        lastMapScanRejection = "not-scanned"
        currentMapRoomId = null
        secretProgressLevel = null
        secretProgressFloor = -1
        acceptedActionbarUpdates = 0
        lastEnteredRoomId = null
        playerSkinTextures = emptyMap<String, Identifier>()
        resetInterpolatedPosition()
        DungeonContextTracker.clearMapOverlayStartupState()
        cachedRoomLabels = null
        snapshot = DungeonRoomOverlayGeometry.Snapshot.unavailable("disconnected")
        lastRenderSummary = "disconnected"
    }

    private fun resetRunProgress() {
        secretProgress.reset()
        roomLifecycle.reset()
        roomCoreScanner.reset()
        playerMarkerRetention.clear()
        lastGoodMapSnapshot = null
        lastGoodMapTick = -1
        currentMapRoomId = null
        acceptedActionbarUpdates = 0
        lastEnteredRoomId = null
    }

    private fun refreshCurrentMapRoom(client: Minecraft?, mapGrid: DungeonMapGrid.ScanResult?) {
        var playerCell: DungeonGridRules.Cell? = null
        val player = client?.player
        if (player != null) {
            val worldCell = DungeonMapGeometry.physicalCellAt(player.x, player.z)
            if (worldCell != null) playerCell = DungeonGridRules.cellAtOrigin(worldCell.baseX, worldCell.baseZ)
        }
        refreshCurrentMapRoom(client, mapGrid, playerCell)
    }

    private fun refreshCurrentMapRoom(
        client: Minecraft?,
        mapGrid: DungeonMapGrid.ScanResult?,
        playerCell: DungeonGridRules.Cell?,
    ) {
        val roomId = resolveCurrentMapRoomId(client, mapGrid, snapshot, playerCell)
        if (Objects.equals(currentMapRoomId, roomId)) return
        currentMapRoomId = roomId
        if (roomId == null) secretProgress.clearCurrentRoom() else secretProgress.enterRoom(roomId)
    }

    private fun resolveCurrentMapRoomId(
        client: Minecraft?,
        mapGrid: DungeonMapGrid.ScanResult?,
        state: DungeonRoomOverlayGeometry.Snapshot?,
    ): String? {
        var playerCell: DungeonGridRules.Cell? = null
        val player = client?.player
        if (player != null) {
            val worldCell = DungeonMapGeometry.physicalCellAt(player.x, player.z)
            if (worldCell != null) playerCell = DungeonGridRules.cellAtOrigin(worldCell.baseX, worldCell.baseZ)
        }
        return resolveCurrentMapRoomId(client, mapGrid, state, playerCell)
    }

    private fun resolveCurrentMapRoomId(
        client: Minecraft?,
        mapGrid: DungeonMapGrid.ScanResult?,
        state: DungeonRoomOverlayGeometry.Snapshot?,
        playerCell: DungeonGridRules.Cell?,
    ): String? {
        val activeClient = client ?: return null
        if (activeClient.player == null || activeClient.level == null || !isActive() || inBossPhase()
            || mapGrid == null || !mapGrid.available() || playerCell == null) return null
        val playerTile = playerCell.componentX + playerCell.componentZ * 6
        val mappedRooms = mapGrid.rooms().filter { room -> room != null && room.segments().contains(playerTile) }
        if (mappedRooms.size == 1) {
            val mapRoomId = DungeonMapProjection.roomIdForSegments(mappedRooms.first().segments())
            if (roomById(state, mapRoomId) != null) return mapRoomId
            val tileRooms = state?.rooms()?.filter { room ->
                room != null && room.footprint().any { cell -> cell.x() + cell.z() * 6 == playerTile }
            } ?: emptyList()
            return if (tileRooms.size == 1) tileRooms.first().id() else null
        }
        if (mappedRooms.isNotEmpty()) return null
        if (state == null || !state.available()) return null
        val matches = state.rooms().filter { room ->
            room != null && room.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED &&
                room.footprint().any { cell -> cell.x() + cell.z() * 6 == playerTile }
        }
        return if (matches.size == 1) matches.first().id() else null
    }

    private fun snapshotWithSecretProgress(
        source: DungeonRoomOverlayGeometry.Snapshot,
    ): DungeonRoomOverlayGeometry.Snapshot {
        if (!source.available()) return source
        val rooms = source.rooms().map { room ->
            if (room.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED) {
                room.withFoundSecrets(0)
            } else if (room.maxSecrets() <= 0) {
                room
            } else {
                secretProgress.observeMapCheckmark(room.id(), room.maxSecrets(), room.checkmark())
                room.withFoundSecrets(secretProgress.foundSecrets(room.id()))
            }
        }
        return DungeonRoomOverlayGeometry.Snapshot(
            source.available(), source.cells(), rooms, source.playerCell(), source.players(),
            source.reason(), source.predictions(),
        )
    }

    private fun roomById(state: DungeonRoomOverlayGeometry.Snapshot?, roomId: String?): DungeonRoomOverlayGeometry.Room? {
        if (state == null || !state.available() || roomId == null) return null
        return state.rooms().firstOrNull { room -> room != null && roomId == room.id() }
    }

    private fun renderSummary(state: DungeonRoomOverlayGeometry.Snapshot?): String {
        if (!hasRenderableMapPresentation(state)) {
            return "presentation=waiting-for-verified-data, visibilityMode=" + visibilityMode() +
                ", currentRoomSelected=false, currentRoomCells=0, currentRoomRenderedCells=0, playerMapCell=unavailable" +
                ", renderedPlayers=0, playerProjection={" + lastPlayerMapDiagnostics + "}"
        }
        val current = state ?: return "presentation=waiting-for-verified-data"
        val plan = mapRenderCache.resolve(current, settings.revealUnopened.value()).plan()
        val visibleRooms = plan.visibleCells().mapNotNull { it.roomId() }
            .filterNot(::isPartialCoreRoomId).distinct().size
        val renderedPartialCoreCells = plan.visibleCells().count { isPartialCoreRoomId(it.roomId()) }
        val unopened = plan.visibleCells().count {
            it.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
        }
        val observedMapCells = lastMapGrid?.takeIf { it.available() }?.rooms()?.sumOf { it.segments().size } ?: 0
        val verifiedUnopenedCandidates = current.cells().count {
            it.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
        }
        val currentId = currentMapRoomId(current)
        val currentRoomCells = if (currentId == null) 0 else current.cells().count {
            currentId == it.roomId() && it.visibility() != DungeonRoomOverlayGeometry.RoomVisibility.UNKNOWN
        }
        val currentRoomRenderedCells = if (currentId == null) 0 else plan.visibleCells().count { currentId == it.roomId() }
        val visibleRoomIds = plan.visibleCells().mapNotNull { it.roomId() }.toSet()
        val labels = current.rooms().count { visibleRoomIds.contains(it.id()) && (!"Unknown".equals(it.name()) || it.maxSecrets() > 0) }
        val renderedDoorHints = if (settings.showExits.value() && !settings.revealUnopened.value())
            plan.unopenedDoorHints().size else 0
        return "presentation=" + mapPresentation(current) + ", visibilityMode=" + visibilityMode() +
            ", currentRoomSelected=" + (currentId != null) + ", currentRoomCells=" + currentRoomCells +
            ", currentRoomRenderedCells=" + currentRoomRenderedCells + ", mapObservedCells=" + observedMapCells +
            ", verifiedUnopenedCandidates=" + verifiedUnopenedCandidates +
            ", renderedCells=" + plan.visibleCells().size + ", renderedRooms=" + visibleRooms +
            ", renderedPartialCoreCells=" + renderedPartialCoreCells +
            ", labels=" + labels + ", renderedUnopenedTiles=" + unopened +
            ", predictions=" + current.predictions().size + ", exitMarkers=" + plan.exits().size +
            ", renderedDoorMarkers=" + renderedDoorMarkerCount(plan) +
            ", doorHints={candidates=" + plan.doorHintCandidates() +
            ",accepted=" + plan.unopenedDoorHints().size + ",rendered=" + renderedDoorHints +
            ",visibleTargetRejected=" + plan.doorHintsAlreadyVisible() +
            ",outsideGridRejected=" + plan.doorHintsOutsideGrid() +
            ",revealSuppressed=" + plan.doorHintsSuppressedByReveal() + "}" +
            ", players=" + current.players().size +
            ", renderedPlayers=" + (if (settings.showPlayers.value()) current.players().size else 0) +
            ", playerMapCell=" + playerMapCellSummary(current) +
            ", playerProjection={" + lastPlayerMapDiagnostics + "}"
    }

    private fun renderedDoorMarkerCount(plan: DungeonRoomOverlayGeometry.RenderPlan?): Int {
        if (plan == null || !settings.showExits.value()) return 0
        val visibleTiles = plan.visibleCells().map { it.x() + it.z() * DungeonRoomOverlayLayout.GRID_CELLS }.toSet()
        val renderedEdges = HashSet<String>()
        for (exit in plan.exits()) {
            if (exit == null) continue
            val cell = exit.cell() ?: continue
            val direction = exit.direction() ?: continue
            val adjacent = neighbor(cell, direction)
            val adjacentIndex = adjacent.x() + adjacent.z() * DungeonRoomOverlayLayout.GRID_CELLS
            val neighborKnown = adjacent.x() >= 0 && adjacent.z() >= 0 &&
                adjacent.x() < DungeonRoomOverlayLayout.GRID_CELLS &&
                adjacent.z() < DungeonRoomOverlayLayout.GRID_CELLS && visibleTiles.contains(adjacentIndex)
            if (direction != DungeonRoomOverlayGeometry.Direction.EAST &&
                direction != DungeonRoomOverlayGeometry.Direction.SOUTH && neighborKnown) continue
            val tile = cell.x() + cell.z() * DungeonRoomOverlayLayout.GRID_CELLS
            if (neighborKnown) renderedEdges.add("edge:" + minOf(tile, adjacentIndex) + ":" + maxOf(tile, adjacentIndex))
            else renderedEdges.add("edge:" + tile + ":" + direction.name)
        }
        return renderedEdges.size
    }

    private fun hasAuthoritativeMapLayout(state: DungeonRoomOverlayGeometry.Snapshot?): Boolean =
        state != null && state.available() && lastMapGrid?.available() == true

    private fun isVerifiedLoadedCoreSnapshot(state: DungeonRoomOverlayGeometry.Snapshot?): Boolean =
        state != null && state.available() && state.reason()?.startsWith(VERIFIED_LOADED_CORE_REASON) == true

    private fun isPlayerMarkerOnlySnapshot(state: DungeonRoomOverlayGeometry.Snapshot?): Boolean =
        state != null && state.available() && state.cells().isEmpty() && state.rooms().isEmpty() &&
            state.playerCell() == null && PLAYER_MARKER_ONLY_REASON == state.reason() && hasLocalPlayerMarker(state)

    private fun hasRenderableMapPresentation(state: DungeonRoomOverlayGeometry.Snapshot?): Boolean =
        hasRenderableMapLayout(state) || isPlayerMarkerOnlySnapshot(state)

    private fun mapPresentation(state: DungeonRoomOverlayGeometry.Snapshot?): String {
        if (hasAuthoritativeMapLayout(state))
            return if (lastMapScanRejection == "none") "authoritative-map" else "retained-map-after-invalid-read"
        if (isVerifiedLoadedCoreSnapshot(state)) return "verified-loaded-core-scan"
        if (isPlayerMarkerOnlySnapshot(state)) return "verified-player-marker"
        return if (visible()) "waiting-for-verified-data" else "unavailable"
    }

    private fun playerMapCellSummary(state: DungeonRoomOverlayGeometry.Snapshot?): String {
        if (state == null) return "unavailable"
        val cell = state.playerCell()
        if (cell != null) return cell.componentX.toString() + "," + cell.componentZ
        return state.players().firstOrNull { it.localPlayer() }?.let { it.x().toString() + "," + it.z() }
            ?: "unavailable"
    }

    private fun visibilityMode(): String = if (settings.revealUnopened.value())
        "map-pixels-plus-exact-loaded-room-cores" else "odin-viewable-plus-current-and-confirmed-doors"

    private fun currentMapRoomId(state: DungeonRoomOverlayGeometry.Snapshot?): String? {
        if (state == null) return null
        val playerCell = state.playerCell() ?: return null
        return state.cells().firstOrNull { cell ->
            cell.x() == playerCell.componentX && cell.z() == playerCell.componentZ &&
                cell.visibility() != DungeonRoomOverlayGeometry.RoomVisibility.UNKNOWN &&
                !cell.roomId().isNullOrBlank()
        }?.roomId()
    }

    private fun activeRoomCores(): Map<Int, DungeonMapCoreScanner.RoomCore> = roomCoreScanner.snapshot()

    private fun activeRoomCoreAt(client: Minecraft, cell: DungeonGridRules.Cell?): DungeonMapCoreScanner.RoomCore? =
        if (cell == null) null else roomCoreScanner.roomCoreAt(client, cell)

    private fun hasLocalPlayerMarker(state: DungeonRoomOverlayGeometry.Snapshot?): Boolean =
        state != null && state.players().any { it.localPlayer() }

    private fun hasRenderableMapLayout(state: DungeonRoomOverlayGeometry.Snapshot?): Boolean =
        DungeonRoomOverlayLifecycleRules.hasRenderableMapLayout(
            state != null && state.available(), hasAuthoritativeMapLayout(state), isVerifiedLoadedCoreSnapshot(state),
        )

    private fun reportAutomaticMapDiagnostics() {
        if (!isEnabled()) return
        diagnosticTick++
        val current = snapshot
        val scannedCores = activeRoomCores()
        val plan = mapRenderCache.resolve(current, settings.revealUnopened.value()).plan()
        val catalogCoreMatches = scannedCores.values.count { it != null && it.data != null }
        val unresolvedScannedCores = scannedCores.size - catalogCoreMatches
        val loadedCoreFallback = isVerifiedLoadedCoreSnapshot(current)
        val completeMultiTileRooms = if (loadedCoreFallback) current.rooms().count {
            it != null && it.footprintKnown() && it.footprint().size > 1
        } else 0
        val unresolvedCoreCells = if (loadedCoreFallback) maxOf(
            0, DungeonRoomOverlayLayout.GRID_CELLS * DungeonRoomOverlayLayout.GRID_CELLS - current.cells().size,
        ) else 0
        val roomCount = current.rooms().count { it != null && !isPartialCoreRoom(it) }
        val verifiedRooms = current.rooms().count {
            it != null && !isPartialCoreRoom(it) &&
                it.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
        }
        val catalogRooms = current.rooms().count { it != null && !"Unknown".equals(it.name(), ignoreCase = true) }
        val pixelTypedRooms = current.rooms().count {
            it != null && "Unknown".equals(it.name(), ignoreCase = true) &&
                it.type() != DungeonMapGeometry.RoomType.UNKNOWN
        }
        val unknownRooms = current.rooms().count {
            it != null && !isPartialCoreRoom(it) && it.footprint().isNotEmpty() &&
                it.type() == DungeonMapGeometry.RoomType.UNKNOWN
        }
        val partialCoreCells = current.rooms().count(::isPartialCoreRoom)
        val floor = DungeonContextTracker.currentFloor()?.displayName()
            ?: DungeonContextTracker.mapOverlayPreloadFloor()?.displayName() ?: "unknown"
        val mapPixelDoorEdges = lastMapGrid?.takeIf { it.available() }?.doors()?.size ?: 0
        val worldTrackerDoorEdges = DungeonDoorTracker.doors().size
        val authoritativeMap = hasAuthoritativeMapLayout(current)
        val playerMarkerOnly = isPlayerMarkerOnlySnapshot(current)
        val renderablePresentation = hasRenderableMapPresentation(current)
        val renderedPartialCoreCells = if (renderablePresentation)
            plan.visibleCells().count { isPartialCoreRoomId(it.roomId()) } else 0
        val retainedAge = if (lastGoodMapTick < 0) -1 else maxOf(0, activeMapTicks - lastGoodMapTick)
        val retainedCells = lastGoodMapSnapshot?.cells()?.size ?: 0
        val retainedRooms = lastGoodMapSnapshot?.rooms()?.size ?: 0
        val status = "enabled=true, dungeon=" + DungeonContextTracker.inDungeon() + ", floor=" + floor +
            ", detector=Odin" + ", mapContext=" + DungeonContextTracker.mapOverlayContextSource() +
            ", preload={" + DungeonContextTracker.mapOverlayPreloadDiagnostics() + "}" +
            ", pixels={" + DungeonContextTracker.mapOverlayPixelDiagnostics() + "}" +
            ", mapAvailable=" + (current != null && current.available()) +
            ", mapPresentation=" + mapPresentation(current) +
            ", playerMapCell=" + (if (renderablePresentation) playerMapCellSummary(current) else "unavailable") +
            ", mapSnapshot=" + when {
                authoritativeMap -> "authoritative"
                loadedCoreFallback -> "verified-loaded-core-scan"
                playerMarkerOnly -> "verified-player-marker"
                current.available() -> "available"
                else -> "unavailable"
            } +
            ", mapBuffer={" + lastMapBufferDiagnostics + "}" +
            ", mapBufferReads=" + mapBufferReads +
            ", mapReadRejection=" + lastMapScanRejection +
            ", retainedSnapshot[ageTicks=" + retainedAge + ",cells=" + retainedCells +
            ",rooms=" + retainedRooms + ",fallbackActive=" + (retainedAge > 0) + "]" +
            ", mapData=" + DungeonMapRoomDetector.current().status +
            ", coreScanCoverage[tiles=" + scannedCores.size + "/" +
            (DungeonRoomOverlayLayout.GRID_CELLS * DungeonRoomOverlayLayout.GRID_CELLS) +
            ",catalogMatches=" + catalogCoreMatches + ",unresolvedScanned=" + unresolvedScannedCores +
            ",completeMultiTileRooms=" + completeMultiTileRooms + ",unresolvedCells=" + unresolvedCoreCells +
            "," + roomCoreScanner.diagnostics() + "]" +
            ", mapReconciliation=" + when {
                authoritativeMap -> "saved-map-pixels"
                current.available() -> "loaded-core-fallback"
                else -> "not-ready"
            } +
            ", cells=" + (if (renderablePresentation) plan.visibleCells().size else 0) +
            ", rooms=" + (if (renderablePresentation) roomCount else 0) +
            ", partialCoreCells=" + (if (renderablePresentation) partialCoreCells else 0) +
            ", renderedPartialCoreCells=" + renderedPartialCoreCells +
            ", verifiedUnopenedRooms=" + (if (renderablePresentation) verifiedRooms else 0) +
            ", roomAuthority[catalog=" + catalogRooms + ",mapPixel=" + pixelTypedRooms + ",unknown=" + unknownRooms + "]" +
            ", predictions=" + (if (authoritativeMap) current.predictions().size else 0) +
            ", exitMarkers=" + (if (renderablePresentation) plan.exits().size else 0) +
            ", renderedDoorMarkers=" + (if (renderablePresentation) renderedDoorMarkerCount(plan) else 0) +
            ", doorEvidence[mapPixelEdges=" + mapPixelDoorEdges + ",worldTrackerEdges=" + worldTrackerDoorEdges + "]" +
            ", players=" + (if (renderablePresentation) current.players().size else 0) +
            ", renderedPlayers=" + (if (renderablePresentation && settings.showPlayers.value()) current.players().size else 0) +
            ", playerProjection={" + lastPlayerMapDiagnostics + "}" +
            ", mapSource=" + (current?.reason() ?: "unavailable") +
            ", coreScan={" + roomCoreScanner.diagnostics() + "}" +
            ", trackedRooms=" + secretProgress.trackedRoomCount() +
            ", currentRoom=" + (if (currentMapRoomId == null) "none" else "mapped") +
            ", actionbarUpdates=" + acceptedActionbarUpdates +
            ", revealUnopened=" + settings.revealUnopened.value() +
            ", showCheckmarks=" + settings.showCheckmarks.value() +
            ", disablePredictions=" + settings.disablePredictions.value() +
            ", coreScanNanos=" + lastCoreScanNanos + "/" + maximumCoreScanNanos +
            ", mapProjectionNanos=" + lastMapProjectionNanos + "/" + maximumMapProjectionNanos +
            ", mapProjectionCount=" + mapProjectionCount +
            ", hudRenderNanos=" + lastHudRenderNanos + "/" + maximumHudRenderNanos +
            ", hudRenderCount=" + hudRenderCount +
            ", playerTeleportSnaps=" + playerTeleportSnaps + ", playerSnapped=" + lastPlayerPositionSnapped +
            ", mapInfo={" + mapInfoDiagnostics() + "}" +
            ", render=" + lastRenderSummary
        val elapsed = diagnosticTick - lastAutomaticDiagnosticTick
        val changed = status != lastAutomaticDiagnostic
        if (lastAutomaticDiagnosticTick == Long.MIN_VALUE || changed && elapsed >= 20 || !changed && elapsed >= 100) {
            GeilerAddonsLog.write(
                Category.F7, name(), diagnosticTick,
                "STATUS " + status + ", coalescedChanges=" + coalescedAutomaticDiagnostics,
            )
            lastAutomaticDiagnosticTick = diagnosticTick
            lastAutomaticDiagnostic = status
            coalescedAutomaticDiagnostics = 0
        } else if (changed) {
            coalescedAutomaticDiagnostics++
        }
    }

    private fun migrateLegacyAlignment(choices: Map<String, String>?, key: String, target: ChoiceSetting): Boolean {
        val legacy = choices?.get(key) ?: return false
        if (legacy == "Right") {
            target.setValue("Top Right")
            return true
        }
        if (legacy == "Left") {
            target.setValue("Top Left")
            return true
        }
        return false
    }

    override fun isActive(): Boolean = isEnabled() && DungeonContextTracker.isMapOverlayContextActive()

    /** Call on the client tick after DungeonContextTracker has refreshed map geometry. */
    fun tick() {
        playerMarkerTick++
        val client = Minecraft.getInstance()
        DungeonPlayerRoster.observeLevel(client.level)
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), isEnabled())
        val disposition = DungeonRoomOverlayLifecycleRules.tickDisposition(
            isEnabled(), DungeonContextTracker.isMapOverlayContextActive(),
            client.player != null && client.level != null,
        )
        if (disposition == DungeonRoomOverlayLifecycleRules.TickDisposition.RESET_SESSION) {
            snapshot = DungeonRoomOverlayGeometry.Snapshot.unavailable("waiting for calibrated dungeon map")
            playerSkinTextures = emptyMap<String, Identifier>()
            roomCoreScanner.reset()
            roomLifecycle.reset()
            secretProgress.reset()
            playerMarkerRetention.clear()
            lastMapGrid = null
            lastGoodMapSnapshot = null
            lastGoodMapTick = -1
            activeMapTicks = 0
            lastMapBufferDiagnostics = "not-read"
            lastMapScanRejection = "not-scanned"
            secretProgressLevel = null
            secretProgressFloor = -1
            currentMapRoomId = null
            acceptedActionbarUpdates = 0
            lastEnteredRoomId = null
            tickCounter = 0
            lastRenderSummary = "inactive"
            reportAutomaticMapDiagnostics()
            return
        }
        if (disposition == DungeonRoomOverlayLifecycleRules.TickDisposition.PAUSE_DISABLED) {
            // The overlay can be switched off temporarily within a run. Invalidate cached map
            // attribution, but retain room counts and run-scoped scanner/lifecycle observations.
            snapshot = DungeonRoomOverlayGeometry.Snapshot.unavailable("map disabled")
            playerSkinTextures = emptyMap<String, Identifier>()
            lastMapGrid = null
            currentMapRoomId = null
            secretProgress.clearCurrentRoom()
            tickCounter = 0
            lastRenderSummary = "disabled"
            reportAutomaticMapDiagnostics()
            return
        }
        val clientPlayer = client.player ?: return
        val clientLevel = client.level ?: return
        if (secretProgressLevel !== clientLevel) {
            secretProgress.reset()
            secretProgressLevel = clientLevel
            secretProgressFloor = -1
            currentMapRoomId = null
            lastMapGrid = null
            lastGoodMapSnapshot = null
            lastGoodMapTick = -1
            snapshot = DungeonRoomOverlayGeometry.Snapshot.unavailable("world changed")
            roomLifecycle.reset()
            roomCoreScanner.reset()
            playerMarkerRetention.clear()
            resetInterpolatedPosition()
        }
        var currentFloor = DungeonContextTracker.currentFloor()
        if (currentFloor == null && DungeonContextTracker.isMapOverlayPreloading())
            currentFloor = DungeonContextTracker.mapOverlayPreloadFloor()
        val floorNumber = currentFloor?.number() ?: -1
        if (floorNumber >= 0 && secretProgressFloor >= 0 && floorNumber != secretProgressFloor) {
            secretProgress.reset()
            currentMapRoomId = null
            lastMapGrid = null
            lastGoodMapSnapshot = null
            lastGoodMapTick = -1
            activeMapTicks = 0
            snapshot = DungeonRoomOverlayGeometry.Snapshot.unavailable("floor changed")
            roomLifecycle.reset()
            roomCoreScanner.reset()
            playerMarkerRetention.clear()
            acceptedActionbarUpdates = 0
        }
        if (floorNumber >= 0) secretProgressFloor = floorNumber
        val coreScanStarted = System.nanoTime()
        roomCoreScanner.tick(client)
        lastCoreScanNanos = maxOf(0L, System.nanoTime() - coreScanStarted)
        maximumCoreScanNanos = maxOf(maximumCoreScanNanos, lastCoreScanNanos)
        activeMapTicks++
        val periodicMapRefresh = DungeonRoomOverlayLifecycleRules.mapRefreshDue(++tickCounter)
        if (!periodicMapRefresh) {
            refreshCurrentMapRoom(client, lastMapGrid)
            reportAutomaticMapDiagnostics()
            return
        }
        val projectionStarted = System.nanoTime()
        val tab = dungeonTabSnapshot(client)
        val colors = DungeonMapRoomDetector.savedMapColors(client)
        if (colors == null) {
            recordMapBuffer(null, null)
            refreshLifecycleFromRetainedGrid(client)
            snapshot = mapUnavailableSnapshot(client, "saved map color data is temporarily unavailable", tab)
            lastRenderSummary = if (snapshot.available()) renderSummary(snapshot) else "map-unavailable"
            recordMapProjectionTime(projectionStarted)
            reportAutomaticMapDiagnostics()
            return
        }
        val mapGrid = DungeonMapGrid.scan(colors)
        recordMapBuffer(colors, mapGrid)
        DungeonContextTracker.observeAuthoritativeMapPixels(mapGrid.available())
        lastPlayerMapDiagnostics = "not-projected"
        if (!mapGrid.available()) {
            refreshLifecycleFromRetainedGrid(client)
            snapshot = mapUnavailableSnapshot(client, mapGrid.reason(), tab)
            lastRenderSummary = if (snapshot.available()) renderSummary(snapshot) else "map-layout-unavailable"
            recordMapProjectionTime(projectionStarted)
            reportAutomaticMapDiagnostics()
            return
        }
        lastMapGrid = mapGrid
        val worldCell = DungeonMapGeometry.physicalCellAt(clientPlayer.x, clientPlayer.z)
        val playerCell = if (worldCell == null) null
            else DungeonGridRules.cellAtOrigin(worldCell.baseX, worldCell.baseZ)
        refreshCurrentMapRoom(client, mapGrid, playerCell)
        val roomState = observeRoomLifecycle(client, mapGrid, playerCell)
        if (roomState.enteredRoomId() != null) lastEnteredRoomId = roomState.enteredRoomId()
        val loadedPlayers = ArrayList<DungeonRoomOverlayGeometry.PlayerMarker>()
        val textures = HashMap<String, Identifier>()
        val party = PartyListBackend.snapshot()
        val playerClasses = HashMap<String, String>()
        if (party.inParty()) for (member in party.members()) {
            if (member != null) {
                val name = member.name() ?: continue
                val dungeonClass = member.dungeonClass() ?: continue
                playerClasses[name.lowercase(Locale.ROOT)] = dungeonClass.displayName()
            }
        }
        // Solo or without party data the tab list still names every class (Map Auto Split needs your own).
        for (tab in geiler.addons.client.dungeon.helpers.DungeonPlayerRoster.snapshot()) {
            val name = tab.name() ?: continue
            val dungeonClass = tab.dungeonClass()
            if (tab.dead() || dungeonClass.isNullOrBlank()) continue
            playerClasses.putIfAbsent(name.lowercase(Locale.ROOT), dungeonClass)
        }
        for (player in clientLevel.players()) {
            val cell = DungeonGridRules.cellAt(
                kotlin.math.floor(player.x).toInt(), kotlin.math.floor(player.z).toInt(),
            )
            if (cell == null) continue
            val name = player.gameProfile.name
            loadedPlayers.add(
                DungeonRoomOverlayGeometry.PlayerMarker(
                    cell.componentX, cell.componentZ, name, player === clientPlayer,
                    (player.x - cell.baseX) / DungeonGridRules.CELL_STRIDE,
                    (player.z - cell.baseZ) / DungeonGridRules.CELL_STRIDE,
                    playerClasses.getOrDefault(name.lowercase(Locale.ROOT), ""), player.yRot,
                ),
            )
            try {
                val skin = player.skin
                if (skin?.body()?.texturePath() != null)
                    textures[name.lowercase(Locale.ROOT)] = skin.body().texturePath()
            } catch (_: RuntimeException) {
                // An already-observed player remains map-visible while a skin asset is unavailable.
            }
        }
        val localPlayerName = clientPlayer.gameProfile.name
        val localMarker = DungeonPlayerMap.projectLoadedPosition(
            localPlayerName, true, playerClasses.getOrDefault(localPlayerName.lowercase(Locale.ROOT), ""),
            clientPlayer.x, clientPlayer.z, clientPlayer.yRot,
        )
        if (localMarker != null && localMarker.x() >= 0 && localMarker.x() < DungeonRoomOverlayLayout.GRID_CELLS &&
            localMarker.z() >= 0 && localMarker.z() < DungeonRoomOverlayLayout.GRID_CELLS) {
            loadedPlayers.removeIf { marker ->
                marker != null && (marker.localPlayer() ||
                    marker.name() != null && marker.name().equals(localPlayerName, ignoreCase = true))
            }
            loadedPlayers.add(localMarker)
            try {
                val skin = clientPlayer.skin
                if (skin?.body()?.texturePath() != null)
                    textures[localPlayerName.lowercase(Locale.ROOT)] = skin.body().texturePath()
            } catch (_: RuntimeException) {
                // The local marker remains valid while its skin texture is loading.
            }
        }
        for (tabPlayer in tab.roster) {
            try {
                val info = clientPlayer.connection?.getPlayerInfo(tabPlayer.name())
                if (info?.skin?.body()?.texturePath() != null)
                    textures[tabPlayer.name().lowercase(Locale.ROOT)] = info.skin.body().texturePath()
            } catch (_: RuntimeException) {
                // Keep skin data when present; do not assign map decorations without identity evidence.
            }
        }
        val playerProjection = DungeonPlayerMap.projectWithDiagnostics(
            mapGrid.layout(), tab.roster, DungeonMapRoomDetector.savedMapDecorations(client),
            loadedPlayers, localPlayerName,
        )
        val retainedPlayers = playerMarkerRetention.merge(playerProjection.players(), tab.roster, playerMarkerTick)
        lastPlayerMapDiagnostics = playerProjection.diagnostics() + ",retention={" + retainedPlayers.diagnostics() + "}"
        val observedPlayers = retainedPlayers.players()
        playerSkinTextures = java.util.Map.copyOf(textures)
        val predictionsEnabled = !settings.disablePredictions.value()
        val projected = DungeonMapProjection.project(
            mapGrid, activeRoomCores(), playerCell, observedPlayers, floorNumber,
            tab.puzzleCount, predictionsEnabled, observedMapDoors(), roomState.walkedIntoTiles(),
        )
        val retainedProjection = DungeonRoomOverlaySnapshotRetention.merge(projected, lastGoodMapSnapshot)
            ?: projected
        snapshot = snapshotWithSecretProgress(retainedProjection)
        lastGoodMapSnapshot = snapshot
        lastGoodMapTick = activeMapTicks
        refreshCurrentMapRoom(client, mapGrid, playerCell)
        lastRenderSummary = renderSummary(snapshot)
        recordMapProjectionTime(projectionStarted)
        reportAutomaticMapDiagnostics()
    }

    private fun recordMapProjectionTime(startedNanos: Long) {
        lastMapProjectionNanos = maxOf(0L, System.nanoTime() - startedNanos)
        maximumMapProjectionNanos = maxOf(maximumMapProjectionNanos, lastMapProjectionNanos)
        mapProjectionCount++
    }

    private fun mapUnavailableSnapshot(
        client: Minecraft?,
        mapReason: String?,
        tab: DungeonTabSnapshot?,
    ): DungeonRoomOverlayGeometry.Snapshot {
        if (client?.player == null || client.level == null || !DungeonContextTracker.isMapOverlayContextActive())
            return DungeonRoomOverlayGeometry.Snapshot.unavailable(
                mapReason ?: "map data is unavailable outside a confirmed map context",
            )
        val playerCell = currentPlayerCell(client)
        val currentPlayers = localPlayerMarkers(client)
        val roster = tab?.roster ?: emptyList()
        val retainedPlayers = playerMarkerRetention.merge(currentPlayers, roster, playerMarkerTick)
        lastPlayerMapDiagnostics = localPlayerProjectionDiagnostics(currentPlayers.isNotEmpty()) +
            ",retention={" + retainedPlayers.diagnostics() + "}"
        // Tracker edges attach only to exact core-backed rooms and rendering requires a visible endpoint.
        val world = DungeonWorldRoomProjection.project(
            activeRoomCores().mapKeys { it.key as Int? },
            playerCell,
            retainedPlayers.players(),
            observedMapDoors(),
        )
        val retained = if (lastGoodMapSnapshot == null) world
            else DungeonRoomOverlaySnapshotRetention.merge(lastGoodMapSnapshot, world) ?: world
        if (!retained.available()) return playerMarkerOnlySnapshot(client, mapReason, retainedPlayers.players())
        val retainedEvidence = "world={" + world.reason() + "};lastGood={" +
            (lastGoodMapSnapshot?.reason() ?: "none") + "}"
        val reason = VERIFIED_LOADED_CORE_REASON + ";retained=" + (lastGoodMapSnapshot != null) +
            ";evidence={" + retainedEvidence + "};mapRead=" + (mapReason ?: "invalid")
        return snapshotWithSecretProgress(
            DungeonRoomOverlayGeometry.Snapshot(
                true, retained.cells(), retained.rooms(), playerCell,
                retainedPlayers.players(), reason, retained.predictions(),
            ),
        )
    }

    private fun refreshLifecycleFromRetainedGrid(client: Minecraft?) {
        val activeClient = client ?: return
        val playerCell = currentPlayerCell(activeClient)
        val grid = lastMapGrid
        val level = activeClient.level
        if (grid != null && grid.available()) {
            refreshCurrentMapRoom(activeClient, grid, playerCell)
            val state = observeRoomLifecycle(activeClient, grid, playerCell)
            if (state.enteredRoomId() != null) lastEnteredRoomId = state.enteredRoomId()
        } else if (level != null) {
            val tile = if (playerCell == null) -1 else playerCell.componentX + playerCell.componentZ * 6
            roomLifecycle.observe(level, DungeonContextTracker.inDungeon(), inBossPhase(),
                null, false, tile, emptyList())
        }
    }

    private fun currentPlayerCell(client: Minecraft?): DungeonGridRules.Cell? {
        val player = client?.player ?: return null
        val worldCell = DungeonMapGeometry.physicalCellAt(player.x, player.z)
        return if (worldCell == null) null
            else DungeonGridRules.cellAtOrigin(worldCell.baseX, worldCell.baseZ)
    }

    private fun localPlayerMarkers(client: Minecraft?): List<DungeonRoomOverlayGeometry.PlayerMarker> {
        val player = client?.player ?: return emptyList()
        val marker = DungeonPlayerMap.projectLoadedPosition(
            player.gameProfile.name, true, "", player.x, player.z, player.yRot,
        )
        return if (marker != null && marker.x() >= 0 && marker.x() < DungeonRoomOverlayLayout.GRID_CELLS &&
            marker.z() >= 0 && marker.z() < DungeonRoomOverlayLayout.GRID_CELLS) listOf(marker) else emptyList()
    }

    private fun recordMapBuffer(colors: ByteArray?, result: DungeonMapGrid.ScanResult?) {
        mapBufferReads++
        if (colors == null) {
            lastMapScanRejection = "saved-map-data-unavailable"
            lastMapBufferDiagnostics = "bytes=0,pixel0=na,complete=false,scan=rejected,rejection=" +
                lastMapScanRejection
            return
        }
        val complete = colors.size >= DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE
        val firstPixel = if (colors.isEmpty()) -1 else colors[0].toInt() and 0xFF
        val rejection = when {
            result == null -> "scan-not-run"
            result.available() -> "none"
            else -> result.reason()
        }
        lastMapScanRejection = rejection
        lastMapBufferDiagnostics = "bytes=" + colors.size + ",pixel0=" +
            (if (firstPixel < 0) "na" else firstPixel) + ",complete=" + complete +
            ",scan=" + (if (result?.available() == true) "accepted" else "rejected") +
            ",rejection=" + rejection
    }

    private fun playerMarkerOnlySnapshot(
        client: Minecraft?,
        mapReason: String?,
        players: List<DungeonRoomOverlayGeometry.PlayerMarker>?,
    ): DungeonRoomOverlayGeometry.Snapshot {
        val unavailable = DungeonRoomOverlayGeometry.Snapshot.unavailable(mapReason ?: "waiting for verified room identity")
        if (client?.player == null || client.level == null || !DungeonContextTracker.isMapOverlayContextActive())
            return unavailable
        val safePlayers = players?.toList() ?: emptyList()
        if (safePlayers.isEmpty()) return unavailable
        return DungeonRoomOverlayGeometry.Snapshot(true, emptyList(), emptyList(), null, safePlayers, PLAYER_MARKER_ONLY_REASON)
    }

    private fun localPlayerProjectionDiagnostics(projected: Boolean): String =
        "roster=0,loadedEntities=1,decorations=0,loadedMatches=0,unresolvedRoster=0,local=" +
            projected + ",localSource=client.player,decorationIdentity=unassigned"

    private fun observeRoomLifecycle(
        client: Minecraft,
        mapGrid: DungeonMapGrid.ScanResult?,
        playerCell: DungeonGridRules.Cell?,
    ): DungeonRoomLifecycle.Snapshot {
        val inDungeon = DungeonContextTracker.inDungeon()
        val inBoss = inBossPhase()
        val playerTile = if (playerCell == null) -1 else playerCell.componentX + playerCell.componentZ * 6
        val level = client.level
        if (!inDungeon || inBoss || level == null || mapGrid == null || !mapGrid.available() || playerCell == null)
            return roomLifecycle.observe(level, inDungeon, inBoss, null, false, playerTile, emptyList())
        val mapRoom = mapGrid.rooms().firstOrNull { it != null && it.segments().contains(playerTile) }
            ?: return roomLifecycle.observe(level, true, false, null, false, playerTile, emptyList())
        val roomCore = activeRoomCoreAt(client, playerCell)
        val roomData = roomCore?.data
        if (roomData == null || roomData.shape() != mapRoom.shape())
            return roomLifecycle.observe(level, true, false, null, false, playerTile, emptyList())
        val rotation = DungeonRoomRotation.resolve(roomData, mapRoom.segments(), roomCore.highestBlock) { probe ->
            val position = BlockPos(probe.x(), probe.y(), probe.z())
            if (!level.hasChunkAt(position)) DungeonRoomRotation.ProbeState.UNAVAILABLE
            else if (level.getBlockState(position).block == Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE))
                DungeonRoomRotation.ProbeState.BLUE_TERRACOTTA
            else DungeonRoomRotation.ProbeState.OTHER
        }
        val player = client.player ?: return roomLifecycle.observe(
            level, true, false, null, false, playerTile, emptyList(),
        )
        val heightReady = DungeonRoomRotation.passesHeightGate(
            player.blockPosition().y, roomCore.highestBlock, client.singleplayerServer != null,
        )
        val roomReady = rotation.resolved() && heightReady
        val roomId = if (roomReady) DungeonRoomLifecycle.roomId(mapRoom.segments()) else null
        return roomLifecycle.observe(level, true, false, roomId, roomReady,
            playerTile, if (roomReady) mapRoom.segments() else emptyList())
    }

    private fun inBossPhase(): Boolean =
        DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase()

    private fun observedMapDoors(): List<DungeonRoomOverlayGeometry.ObservedDoor> =
        DungeonDoorTracker.doors().map { door ->
            DungeonRoomOverlayGeometry.ObservedDoor(door.firstCell(), door.secondCell(), doorKind(door.type()))
        }

    private fun dungeonTabSnapshot(client: Minecraft?): DungeonTabSnapshot {
        val player = client?.player ?: return DungeonTabSnapshot(emptyList(), emptyList(), -1)
        val gui = client.gui ?: return DungeonTabSnapshot(emptyList(), emptyList(), -1)
        if (player.connection == null) return DungeonTabSnapshot(emptyList(), emptyList(), -1)
        val overlay = gui.hud.tabList ?: return DungeonTabSnapshot(emptyList(), emptyList(), -1)
        var ordered: List<PlayerInfo> = emptyList()
        try {
            val candidate = (overlay as PlayerTabOverlayAccess).`geileraddons$getPlayerInfos`()
            if (!candidate.isNullOrEmpty()) ordered = candidate
        } catch (_: RuntimeException) {
            // Loaded entities remain the safe fallback when vanilla tab ordering is unavailable.
        } catch (_: LinkageError) {
            // Keep a loaded player fallback if the optional tab accessor is unavailable.
        }
        if (ordered.isEmpty()) {
            val fallback = ArrayList(player.connection.listedOnlinePlayers)
            if (fallback.isNotEmpty()) ordered = fallback.toList()
        }
        val lines = ordered.map { PlayerTabNameSource.rawLine(it) }
        return DungeonTabSnapshot(
            lines, DungeonPlayerRoster.currentDungeonRows(lines, DungeonPlayerRoster.snapshot()),
            DungeonMapGrid.puzzleCountFromTabLines(lines),
        )
    }

    private data class DungeonTabSnapshot(
        val lines: List<String>,
        val roster: List<DungeonPlayerMap.TabPlayer>,
        val puzzleCount: Int,
    )

    override fun hudIslands(): kotlin.collections.Set<Island> = setOf(Island.CATACOMBS)

    override fun id(): String = "dungeon_room_map"
    override fun displayName(): String = "Dungeon Map"
    override fun previewGroupName(): String = "Map Preview"
    override fun previewEnabled(): Boolean = settings.showPreview.value()
    override fun width(font: Font?): Int =
        DungeonRoomOverlayLayout.scaledDimension(layout(font!!).width, settings.mapScale.value())
    override fun height(font: Font?): Int =
        DungeonRoomOverlayLayout.scaledDimension(layout(font!!).height + mapInfoHeight(), settings.mapScale.value())

    /** The Map Info panel is wanted whenever the module and its Map Info switch are on, so the data is gathered. */
    fun mapInfoWanted(): Boolean = isEnabled() && settings.mapInfo.value()

    /** The panel is part of the map box except in the boss phase when it is set to hide there. */
    private fun mapInfoShown(): Boolean =
        settings.mapInfo.value() && !(settings.mapInfoHideInBoss.value() && DungeonScoreState.core.inBoss)

    private fun mapInfoHeight(): Int = if (mapInfoShown()) DungeonMapInfoLogic.PANEL_HEIGHT else 0

    private fun mapInfoOptions() = DungeonMapInfoLogic.Options(
        includeRemaining = settings.mapInfoIncludeRemaining.value(),
        remainingIsAll = settings.mapInfoRemaining.value() == "Remaining",
        showUnfound = settings.mapInfoDeaths.value() == "Unfound",
    )

    /** Bounded evidence of what the Map Info panel is fed: counts and flags only, no names. */
    private fun mapInfoDiagnostics(): String {
        if (!settings.mapInfo.value()) return "off"
        val info = DungeonMapInfoLogic.info(DungeonScoreState.core, 0)
        return "shown=" + mapInfoShown() + ",found=" + info.secretsFound + ",percent=" + info.secretsPercent +
            ",total=" + info.totalSecrets + ",needed=" + info.neededSecrets + ",deaths=" + info.deaths +
            ",crypts=" + info.crypts + ",mimic=" + info.mimic + ",prince=" + info.prince + ",bats=" + info.batKillers +
            ",paul=" + info.paul + ",floor=" + info.floorNumber + ",score=" + (info.score ?: "none") +
            ",boss=" + DungeonScoreState.core.inBoss
    }

    /** Secrets of the rooms already opened on the map: the "known" part of the Unfound count. */
    private fun knownSecrets(state: DungeonRoomOverlayGeometry.Snapshot): Int =
        state.rooms().filter { it.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED }
            .sumOf { it.maxSecrets() }

    /** Draws the Map Info panel under the map, in the map's own scaled space, as wide as the map. */
    private fun drawMapInfo(
        graphics: GuiGraphicsExtractor,
        font: Font,
        state: DungeonRoomOverlayGeometry.Snapshot,
        layout: DungeonRoomOverlayLayout.Layout,
        preview: Boolean,
    ) {
        if (!mapInfoShown()) return
        val info = if (preview) DungeonMapInfoLogic.sampleInfo()
        else DungeonMapInfoLogic.info(DungeonScoreState.core, knownSecrets(state))
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(0f, layout.height.toFloat())
        graphics.fill(0, 0, layout.width, DungeonMapInfoLogic.PANEL_HEIGHT, settings.mapInfoBackground.argb())
        val options = mapInfoOptions()
        val fit = DungeonMapInfoLogic.fitScale(layout.width, info, options) { font.width(it) }
        // A narrow map shrinks the text so the secrets and score (and the second row) never overlap.
        pose.translate(0f, (DungeonMapInfoLogic.PANEL_HEIGHT * (1f - fit)) / 2f)
        pose.scale(fit, fit)
        val lines = DungeonMapInfoLogic.layout((layout.width / fit).toInt(), info, options) { font.width(it) }
        for (line in lines) graphics.text(font, Component.literal(line.text), line.x, line.y, 0xFFFFFFFF.toInt())
        pose.popMatrix()
    }
    override fun previewWidth(font: Font?): Int = width(font)
    override fun previewHeight(font: Font?): Int = height(font)
    override fun previewMode(): String = if (snapshot.available()) "live" else "sample"
    override fun visible(): Boolean = isActive() && settings.showMap.value() && !hudMapHiddenInLeapMenu() &&
        DungeonContextTracker.shouldShowMapOverlay(
            settings.showInBoss.value(), DungeonContextTracker.isMobEspPhase(),
            DungeonContextTracker.isMapOverlayStartupContext(),
        )
    override fun inactiveReason(): String? {
        if (isEnabled() && !isActive()) return "Waiting for a dungeon"
        if (isActive() && settings.showMap.value() && !settings.showInBoss.value() &&
            !DungeonContextTracker.isMobEspPhase()) return "Hidden during boss phase"
        return null
    }

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        if (!visible()) return
        val started = System.nanoTime()
        try {
            val layout = layout(font)
            val state = snapshotWithInterpolatedPlayers(snapshot)
            val scale = settings.mapScale.value()
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(
                x + maxOf(0f, (width(font) - layout.width * scale) / 2.0f),
                y + maxOf(0f, (height(font) - (layout.height + mapInfoHeight()) * scale) / 2.0f),
            )
            pose.scale(scale, scale)
            drawMapContents(graphics, font, state, layout, false)
            drawMapInfo(graphics, font, state, layout, false)
            pose.popMatrix()
        } finally {
            lastHudRenderNanos = maxOf(0L, System.nanoTime() - started)
            maximumHudRenderNanos = maxOf(maximumHudRenderNanos, lastHudRenderNanos)
            hudRenderCount++
        }
    }

    /** The HUD map is hidden while the Spirit Leap menu shows its clickable leap copy (the HUD copy sat top-left, unclickable). */
    private fun hudMapHiddenInLeapMenu(): Boolean {
        if (!SpiritLeapMenuModule.showMap.value()) return false
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return false
        return SpiritLeapMapLogic.hudMapHiddenInLeapMenu(
            DungeonAssistModule.INSTANCE.shouldRenderSpiritLeapScreen(screen), true,
        )
    }

    /**
     * Spirit Leap menu map placement `[x, y, width, height, scale]`: centered over the cards, its bottom edge just
     * above [cardsTop], shrunk to fit. Null without a live map or room for it.
     */
    fun leapMapBounds(font: Font, screenWidth: Int, cardsTop: Int, requestedScale: Float): FloatArray? {
        if (!isActive() || !snapshot.available()) return null
        val layout = layout(font)
        val scale = SpiritLeapMapLogic.scale(requestedScale, layout.height.toFloat())
        if (scale <= 0f) return null
        val width = layout.width * scale
        val height = layout.height * scale
        return floatArrayOf((screenWidth - width) / 2f, SpiritLeapMapLogic.top(height, cardsTop), width, height, scale)
    }

    /** Draws the live map for the Spirit Leap menu, never rotated so click positions match the markers. */
    fun drawLeapMap(graphics: GuiGraphicsExtractor, font: Font, screenWidth: Int, cardsTop: Int, requestedScale: Float): Boolean {
        val bounds = leapMapBounds(font, screenWidth, cardsTop, requestedScale) ?: return false
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(bounds[0], bounds[1])
        pose.scale(bounds[4], bounds[4])
        drawMapContents(graphics, font, snapshotWithInterpolatedPlayers(snapshot), layout(font), false, false)
        pose.popMatrix()
        return true
    }

    /** Teammate markers (not the local player) in screen space for the map drawn by [drawLeapMap]. */
    fun leapMapMarkers(font: Font, screenWidth: Int, cardsTop: Int, requestedScale: Float): List<SpiritLeapMapLogic.Marker> {
        val bounds = leapMapBounds(font, screenWidth, cardsTop, requestedScale) ?: return emptyList()
        val layout = layout(font)
        val stride = layout.cellSize + layout.cellGap
        return snapshotWithInterpolatedPlayers(snapshot).players().filter { !it.localPlayer() }.map { player ->
            val position = SpiritLeapMapLogic.markerPosition(
                bounds[0], bounds[1], bounds[4], layout.contentInset, layout.gridTop, stride,
                player.x(), player.z(), player.offsetX(), player.offsetZ(),
            )
            SpiritLeapMapLogic.Marker(player.name(), position[0], position[1])
        }
    }

    private fun drawMapContents(
        graphics: GuiGraphicsExtractor,
        font: Font,
        state: DungeonRoomOverlayGeometry.Snapshot,
        layout: DungeonRoomOverlayLayout.Layout,
        preview: Boolean,
        allowRotation: Boolean = true,
    ) {
        val pose = graphics.pose()
        graphics.fill(0, 0, layout.width, layout.height, settings.background.argb())
        graphics.enableScissor(0, 0, layout.width, layout.height)
        val gridX = layout.contentInset
        val gridY = layout.gridTop
        val provisional = !hasRenderableMapLayout(state)
        val hasPlayerPosition = hasLocalPlayerMarker(state)
        val rotateGrid = allowRotation && settings.rotateMap.value() && (!provisional || hasPlayerPosition)
        val rotationPivot = if (rotateGrid) playerPivot(state, layout, gridX, gridY) else null
        val rotationRadians = if (rotateGrid) mapRotationRadians(state) else 0.0f
        if (rotateGrid) {
            pose.pushMatrix()
            pose.translate(rotationPivot!![0], rotationPivot[1])
            pose.rotate(rotationRadians)
            pose.translate(-rotationPivot[0], -rotationPivot[1])
        }
        if (provisional) {
            if (settings.showPlayers.value() && hasPlayerPosition)
                drawPlayers(graphics, font, state, layout, gridX, gridY, preview)
        } else {
            val data = (if (preview) previewRenderCache else mapRenderCache)
                .resolve(state, settings.revealUnopened.value())
            val roomColors = roomColors()
            val roomsById = data.roomsById()
            // Map Auto Split "Hide Other Rooms": those rooms are left out entirely (cells, bridges, doors, text).
            val hidden = if (preview) emptySet() else DungeonAutoSplitModule.INSTANCE.hiddenCells(state)
            for (z in 0 until DungeonRoomOverlayLayout.GRID_CELLS) {
                for (cellX in 0 until DungeonRoomOverlayLayout.GRID_CELLS) {
                    if ((cellX to z) in hidden) continue
                    val visible = data.visibleCellAt(cellX, z) ?: continue
                    val left = gridX + cellX * (layout.cellSize + layout.cellGap)
                    val top = gridY + z * (layout.cellSize + layout.cellGap)
                    graphics.fill(left, top, left + layout.cellSize, top + layout.cellSize,
                        roomFillColor(visible, roomColors))
                }
            }
            drawRoomBridges(graphics, data, roomsById, layout, gridX, gridY, roomColors, hidden)
            if (settings.showExits.value()) {
                for (door in data.doorGeometry(layout.cellSize, layout.cellGap, settings.doorSize.value())) {
                    val exit = door.exit ?: continue
                    val cell = exit.cell() ?: continue
                    if ((cell.x() to cell.z()) in hidden) continue
                    val direction = exit.direction() ?: continue
                    val color = exitColor(exit, data, roomColors)
                    val adjacent = neighbor(cell, direction)
                    val neighborKnown = data.visibleCellAt(adjacent.x(), adjacent.z()) != null
                    if (direction == DungeonRoomOverlayGeometry.Direction.EAST ||
                        direction == DungeonRoomOverlayGeometry.Direction.SOUTH || !neighborKnown) {
                        val rect = door.rect ?: continue
                        if (rect.width <= 0 || rect.height <= 0) continue
                        graphics.fill(gridX + rect.x, gridY + rect.y, gridX + rect.x + rect.width,
                            gridY + rect.y + rect.height, color)
                    }
                }
            }
            drawUnopenedDoorHints(graphics, data.plan(), layout, gridX, gridY, roomColors)
            if (!preview) DungeonAutoSplitModule.INSTANCE.drawOverlay(graphics, state, gridX, gridY, layout.cellSize,
                layout.cellGap) { cx, cz -> data.visibleCellAt(cx, cz) != null }
            drawPredictions(graphics, data, layout, gridX, gridY, roomColors)
            drawRoomTextAndIcons(graphics, font, data, roomsById, layout, gridX, gridY, hidden)
            if (settings.showPlayers.value()) drawPlayers(graphics, font, state, layout, gridX, gridY, preview)
        }
        if (rotateGrid) pose.popMatrix()
        if (settings.showPlayers.value())
            drawLeapHeldPlayerNames(graphics, font, state, layout, gridX, gridY, preview,
                rotateGrid, rotationPivot, rotationRadians)
        graphics.disableScissor()
        if (settings.showOutline.value())
            drawOuterOutline(graphics, layout.width, layout.height,
                settings.outlineWidth.intValue(), settings.outlineColor.argb())
    }

    override fun drawPreview(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        if (width < 80 || height < 48) return false
        val layout = layout(font)
        val scale = minOf(width / layout.width.toFloat(), height / layout.height.toFloat())
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        drawMapContents(graphics, font, DungeonRoomOverlayPreview.sampleSnapshot(), layout, true)
        pose.popMatrix()
        return true
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val current = if (snapshot.available()) snapshot else DungeonRoomOverlayPreview.sampleSnapshot()
        val layout = layout(font)
        val scale = settings.mapScale.value()
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x + maxOf(0f, (width(font) - layout.width * scale) / 2.0f),
            y + maxOf(0f, (height(font) - (layout.height + mapInfoHeight()) * scale) / 2.0f))
        pose.scale(scale, scale)
        drawMapContents(graphics, font, current, layout, true)
        drawMapInfo(graphics, font, current, layout, true)
        pose.popMatrix()
    }

    override fun previewLines(font: Font, width: Int): List<Component> =
        listOf(Component.literal("Dungeon map preview"))

    override fun previewHeight(width: Int, availableHeight: Int): Int =
        DungeonRoomOverlayLayout.settingsPreviewHeight(width, availableHeight)

    private fun playerPivot(
        state: DungeonRoomOverlayGeometry.Snapshot,
        layout: DungeonRoomOverlayLayout.Layout,
        gridX: Int,
        gridY: Int,
    ): FloatArray {
        val self = state.players().firstOrNull { it.localPlayer() }
        if (self == null) {
            val cell = currentIndex(state)
            return floatArrayOf(
                gridX + cell.x * (layout.cellSize + layout.cellGap) + layout.cellSize / 2.0f,
                gridY + cell.z * (layout.cellSize + layout.cellGap) + layout.cellSize / 2.0f,
            )
        }
        val stride = layout.cellSize + layout.cellGap
        return floatArrayOf(
            gridX + ((self.x() + self.offsetX()) * stride).toFloat(),
            gridY + ((self.z() + self.offsetZ()) * stride).toFloat(),
        )
    }

    /** Converts Minecraft yaw into a screen-space rotation that keeps the local player facing north. */
    private fun mapRotationRadians(state: DungeonRoomOverlayGeometry.Snapshot?): Float {
        if (state != null) {
            val self = state.players().firstOrNull { it.localPlayer() }
            if (self != null) return Math.toRadians(-self.yawDegrees().toDouble() - 180.0).toFloat()
        }
        val client = Minecraft.getInstance()
        val player = client.player ?: return 0.0f
        return Math.toRadians(-player.yRot.toDouble() - 180.0).toFloat()
    }

    /**
     * Odin reads render-interpolated loaded-player transforms during map extraction. Keep the room
     * scan on its slower client-tick cadence, but refresh marker position and facing every HUD frame.
     */
    private fun snapshotWithInterpolatedPlayers(
        source: DungeonRoomOverlayGeometry.Snapshot?,
    ): DungeonRoomOverlayGeometry.Snapshot {
        val client = Minecraft.getInstance()
        val level = client.level
        if (source == null) return DungeonRoomOverlayGeometry.Snapshot.unavailable("map snapshot is unavailable")
        lastPlayerPositionSnapped = false
        if (!source.available() || source.players().isEmpty() || level == null) {
            resetInterpolatedPosition()
            return source
        }
        if (interpolatedMarkerLevel !== level) {
            resetInterpolatedPosition()
            interpolatedMarkerLevel = level
        }
        val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val loaded = HashMap<String, AbstractClientPlayer>()
        for (player in level.players())
            loaded[player.gameProfile.name.lowercase(Locale.ROOT)] = player
        val players = ArrayList<DungeonRoomOverlayGeometry.PlayerMarker>(source.players().size)
        for (marker in source.players()) {
            val entity = loaded[marker.name().lowercase(Locale.ROOT)]
            if (entity == null) {
                players.add(marker)
                continue
            }
            var snap = false
            if (marker.localPlayer()) {
                val currentX = entity.x
                val currentZ = entity.z
                snap = DungeonRoomPlayerMarkerGeometry.shouldSnap(
                    previousLocalWorldX, previousLocalWorldZ, currentX, currentZ,
                )
                val hadPrevious = previousLocalWorldX.isFinite() && previousLocalWorldZ.isFinite()
                lastPlayerPositionSnapped = snap && hadPrevious
                if (snap && hadPrevious) playerTeleportSnaps++
                previousLocalWorldX = currentX
                previousLocalWorldZ = currentZ
            }
            val position = if (snap) entity.position() else entity.getPosition(partialTick)
            val interpolated = DungeonPlayerMap.projectLoadedPosition(
                marker.name(), marker.localPlayer(), marker.dungeonClass(), position.x, position.z,
                if (snap && marker.localPlayer()) entity.yRot else entity.getYRot(partialTick),
            )
            if (interpolated != null) players.add(interpolated) else players.add(marker)
        }
        return DungeonRoomOverlayGeometry.Snapshot(
            source.available(), source.cells(), source.rooms(), source.playerCell(), players,
            source.reason(), source.predictions(),
        )
    }

    private fun roomFillColor(
        cell: DungeonRoomOverlayGeometry.Cell,
        colors: DungeonRoomOverlayStyle.RoomColors,
    ): Int = DungeonRoomOverlayStyle.roomFillColor(
        cell, colors, settings.unopenedRoomColor.argb(), settings.unopenedDarkenFactor.value().toDouble(),
    )

    private fun drawRoomBridges(
        graphics: GuiGraphicsExtractor,
        data: DungeonRoomOverlayRenderCache.Data,
        rooms: Map<String, DungeonRoomOverlayGeometry.Room>,
        layout: DungeonRoomOverlayLayout.Layout,
        gridX: Int,
        gridY: Int,
        roomColors: DungeonRoomOverlayStyle.RoomColors,
        hidden: kotlin.collections.Set<Pair<Int, Int>> = emptySet(),
    ) {
        for (room in rooms.values) {
            if (room.footprint().any { (it.x() to it.z()) in hidden }) continue
            val footprint = HashSet<String>()
            val visibleFootprint = ArrayList<DungeonRoomOverlayGeometry.Cell>()
            for (cell in room.footprint()) {
                if (data.visibleCellAt(cell.x(), cell.z()) != null) {
                    footprint.add(cell.x().toString() + "," + cell.z())
                    visibleFootprint.add(cell)
                }
            }
            if (footprint.size < 2) continue
            val color = roomFillColor(room.footprint().first(), roomColors)
            for (cell in room.footprint()) {
                val left = gridX + cell.x() * (layout.cellSize + layout.cellGap)
                val top = gridY + cell.z() * (layout.cellSize + layout.cellGap)
                if (footprint.contains((cell.x() + 1).toString() + "," + cell.z()))
                    graphics.fill(left + layout.cellSize, top, left + layout.cellSize + layout.cellGap,
                        top + layout.cellSize, color)
                if (footprint.contains(cell.x().toString() + "," + (cell.z() + 1)))
                    graphics.fill(left, top + layout.cellSize, left + layout.cellSize,
                        top + layout.cellSize + layout.cellGap, color)
            }
            for (intersection in DungeonRoomOverlayLayout.internalGapIntersections(
                visibleFootprint, layout.cellSize, layout.cellGap,
            )) {
                graphics.fill(gridX + intersection.x, gridY + intersection.y,
                    gridX + intersection.x + intersection.width, gridY + intersection.y + intersection.height,
                    color)
            }
        }
    }

    private fun drawPredictions(
        graphics: GuiGraphicsExtractor,
        data: DungeonRoomOverlayRenderCache.Data,
        layout: DungeonRoomOverlayLayout.Layout,
        gridX: Int,
        gridY: Int,
        colors: DungeonRoomOverlayStyle.RoomColors,
    ) {
        if (!DungeonRoomOverlayStyle.predictionsVisible(settings.disablePredictions.value())) return
        for (prediction in data.predictions()) {
            val mapped = data.cellAt(prediction.x(), prediction.z())
            // Map pixels and catalog identities stay authoritative, even for unrevealed rooms.
            if (!DungeonRoomOverlayStyle.predictionTargetAllowed(mapped)) continue
            val left = gridX + prediction.x() * (layout.cellSize + layout.cellGap)
            val top = gridY + prediction.z() * (layout.cellSize + layout.cellGap)
            val size = layout.cellSize
            graphics.fill(left, top, left + size, top + size,
                DungeonRoomOverlayStyle.roomColor(DungeonMapGeometry.RoomType.UNKNOWN, colors))
            drawCheckmark(graphics, "QUESTION_MARK", left, top, size)
        }
    }

    private fun drawUnopenedDoorHints(
        graphics: GuiGraphicsExtractor,
        plan: DungeonRoomOverlayGeometry.RenderPlan,
        layout: DungeonRoomOverlayLayout.Layout,
        gridX: Int,
        gridY: Int,
        colors: DungeonRoomOverlayStyle.RoomColors,
    ) {
        if (settings.revealUnopened.value() || !settings.showExits.value()) return
        for (hint in plan.unopenedDoorHints()) {
            val left = gridX + hint.x * (layout.cellSize + layout.cellGap)
            val top = gridY + hint.z * (layout.cellSize + layout.cellGap)
            val size = layout.cellSize
            graphics.fill(left, top, left + size, top + size,
                DungeonRoomOverlayStyle.roomColor(DungeonMapGeometry.RoomType.UNKNOWN, colors))
            drawCheckmark(graphics, "QUESTION_MARK", left, top, size)
        }
    }

    private fun drawRoomTextAndIcons(
        graphics: GuiGraphicsExtractor,
        font: Font,
        data: DungeonRoomOverlayRenderCache.Data,
        rooms: Map<String, DungeonRoomOverlayGeometry.Room>,
        layout: DungeonRoomOverlayLayout.Layout,
        gridX: Int,
        gridY: Int,
        hidden: kotlin.collections.Set<Pair<Int, Int>> = emptySet(),
    ) {
        val labels = resolveRoomLabels(data, font, layout, rooms)
        for (label in labels.labels) {
            if ((label.minX to label.minZ) in hidden) continue
            val cell = label.cell
            val room = label.room
            val footprint = label.footprint
            val markLeft = gridX + label.minX * (layout.cellSize + layout.cellGap)
            val markTop = gridY + label.minZ * (layout.cellSize + layout.cellGap)
            if (label.checkmark.isNotEmpty())
                drawSmallCheckmark(graphics, label.checkmark, markLeft, markTop, layout.cellSize)
            if (label.unknownPlaceholder) {
                if (cell.visibility() != DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED) {
                    drawCheckmark(graphics, "QUESTION_MARK", markLeft, markTop, layout.cellSize)
                    continue
                }
            }
            if (label.lines.isNotEmpty())
                drawCellText(graphics, font, label.lines, footprint, layout, gridX, gridY, roomTextColor(cell, room))
        }
    }

    private fun resolveRoomLabels(
        data: DungeonRoomOverlayRenderCache.Data,
        font: Font,
        layout: DungeonRoomOverlayLayout.Layout,
        rooms: Map<String, DungeonRoomOverlayGeometry.Room>,
    ): RoomLabelCache {
        val options = LabelOptions(
            settings.roomText.value(), settings.showCheckmarks.value(), settings.hideCheckmarkWithName.value(),
            settings.showPuzzleName.value(), settings.showRoomSecrets.value(), settings.skipOneSecretRoom.value(),
            settings.skipCommonRoomNames.value(), settings.skipYellowRoomName.value(),
            settings.showFairyCheckmark.value(), settings.checkmarkOnlyWhenGreen.value(),
            settings.checkmarkIfNoSecrets.value(), settings.roomTextAlignment.value(), inBossPhase(),
        )
        val textScale = settings.roomTextScale.value()
        cachedRoomLabels?.let {
            if (it.matches(data, font, layout.cellSize, layout.cellGap, textScale, options)) return it
        }
        val labels = ArrayList<RoomLabel>()
        val rendered = HashSet<String>()
        for (cell in data.plan().visibleCells()) {
            val key = cell.roomId() ?: cell.x().toString() + "," + cell.z()
            if (!rendered.add(key)) continue
            val room = rooms[key]
            val footprint = room?.footprint() ?: listOf(cell)
            var visibleFootprint = footprint.filter { data.visibleCellAt(it.x(), it.z()) != null }
            if (visibleFootprint.isEmpty()) visibleFootprint = listOf(cell)
            val minX = visibleFootprint.minOfOrNull { it.x() } ?: cell.x()
            val minZ = visibleFootprint.minOfOrNull { it.z() } ?: cell.z()
            val doneMiniboss = DungeonRoomOverlayStyle.minibossDoneCheckmark(cell.type(), room?.checkmark())
            val checkmark = doneMiniboss ?: checkmarkFor(cell, room)
            val roomText = roomLabel(cell, room)
            val decoration = DungeonRoomOverlayStyle.roomDecoration(
                settings.showCheckmarks.value() || doneMiniboss != null, checkmark, roomText)
            val maxX = visibleFootprint.maxOfOrNull { it.x() } ?: minX
            val maximumWidth = roomLabelMaximumWidth(minX, maxX, layout.cellSize, layout.cellGap, textScale)
            val label = decoration.label.orEmpty()
            val lines = if (label.isEmpty()) emptyList()
                else DungeonRoomMapLabel.wrap(label, maximumWidth) { textWidth(font, it) }
            val unknown = room == null || "Unknown".equals(room.name(), ignoreCase = true) ||
                cell.type() == DungeonMapGeometry.RoomType.UNKNOWN
            labels.add(RoomLabel(cell, room, visibleFootprint, minX, minZ, decoration.checkmark.orEmpty(), lines, unknown))
        }
        val cache = RoomLabelCache(data, font, layout.cellSize, layout.cellGap, textScale, options, labels.toList())
        cachedRoomLabels = cache
        return cache
    }

    private fun drawPresentedText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: Component,
        x: Float,
        y: Float,
        color: Int,
        preserveProfileStyle: Boolean,
    ) {
        if (!settings.useMcFontRenderer.value() && !preserveProfileStyle) {
            drawStyledText(graphics, font, text.string, x, y, color)
            return
        }
        val shadow = DungeonRoomOverlayStyle.textShadow(settings.textShadowMode.value())
        val drawX = kotlin.math.round(x).toInt()
        val drawY = kotlin.math.round(y).toInt()
        when (shadow) {
            DungeonRoomOverlayStyle.TextShadow.NONE -> graphics.text(font, text, drawX, drawY, color, false)
            DungeonRoomOverlayStyle.TextShadow.DROP -> graphics.text(font, text, drawX, drawY, color, true)
            // Explicit component colors override draw tint; do not false-color profile text as an outline.
            DungeonRoomOverlayStyle.TextShadow.OUTLINE -> graphics.text(font, text, drawX, drawY, color, false)
        }
    }

    private fun drawStyledText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Float,
        y: Float,
        color: Int,
    ) {
        drawStyledText(graphics, font, text, x, y, color,
            DungeonRoomOverlayStyle.textShadow(settings.textShadowMode.value()))
    }

    private fun drawStyledText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Float,
        y: Float,
        color: Int,
        shadow: DungeonRoomOverlayStyle.TextShadow,
    ) {
        if (!settings.useMcFontRenderer.value() && drawRasterText(graphics, font, text, x, y, color, shadow)) return
        drawMinecraftText(graphics, font, text, x, y, color, shadow)
    }

    private fun drawMinecraftText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Float,
        y: Float,
        color: Int,
        shadow: DungeonRoomOverlayStyle.TextShadow,
    ) {
        val textX = kotlin.math.round(x).toInt()
        val textY = kotlin.math.round(y).toInt()
        when (shadow) {
            DungeonRoomOverlayStyle.TextShadow.NONE -> graphics.text(font, text, textX, textY, color, false)
            DungeonRoomOverlayStyle.TextShadow.DROP -> graphics.text(font, text, textX, textY, color, true)
            DungeonRoomOverlayStyle.TextShadow.OUTLINE -> {
                graphics.text(font, text, textX - 1, textY, 0xD0000000.toInt(), false)
                graphics.text(font, text, textX + 1, textY, 0xD0000000.toInt(), false)
                graphics.text(font, text, textX, textY - 1, 0xD0000000.toInt(), false)
                graphics.text(font, text, textX, textY + 1, 0xD0000000.toInt(), false)
                graphics.text(font, text, textX, textY, color, false)
            }
        }
    }

    private fun drawRasterText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Float,
        y: Float,
        color: Int,
        shadow: DungeonRoomOverlayStyle.TextShadow,
    ): Boolean {
        val raster = rasterText.get(text, color, font.lineHeight, rasterShadow(shadow)) ?: return false
        val texture = rasterTexture(raster) ?: return false
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, texture.id,
            kotlin.math.round(x - raster.leftInset()).toInt(),
            kotlin.math.round(y - raster.topInset()).toInt(),
            0.0f, 0.0f, raster.drawWidth(), raster.drawHeight(),
            raster.imageWidth(), raster.imageHeight(), 0xFFFFFFFF.toInt(),
        )
        return true
    }

    private fun rasterTexture(raster: DungeonRoomMapRasterText.RasterText): RasterTextTexture? {
        val client = Minecraft.getInstance()
        val textures = client.textureManager
        val cached = rasterTextTextures[raster.textureKey()]
        if (cached != null && textures.getTexture(cached.id) === cached.texture) return cached
        if (cached != null) {
            textures.release(cached.id)
            rasterTextTextures.remove(raster.textureKey())
            rasterTextureBytes -= cached.decodedBytes
        }
        val id = GeilerAddons.id("dungeon_map/text/" + raster.textureKey())
        val image = try {
            raster.openPng().use { input ->
                val loaded = NativeImage.read(input)
                if (loaded.width != raster.imageWidth() || loaded.height != raster.imageHeight()) {
                    loaded.close()
                    return null
                }
                val decodedBytes = loaded.width.toLong() * loaded.height * 4
                if (decodedBytes > MAX_ONE_RASTER_TEXTURE_BYTES || decodedBytes > MAX_RASTER_TEXTURE_BYTES) {
                    loaded.close()
                    return null
                }
                loaded
            }
        } catch (_: java.io.IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        val decodedBytes = raster.imageWidth().toLong() * raster.imageHeight() * 4
        val dynamic = DynamicTexture({ "GeilerAddons dungeon map text" }, image)
        try {
            // Remove stale registration left behind if the texture manager reloaded between frames.
            textures.release(id)
            textures.register(id, dynamic)
        } catch (_: RuntimeException) {
            dynamic.close()
            return null
        }
        val created = RasterTextTexture(id, dynamic, decodedBytes)
        rasterTextTextures[raster.textureKey()] = created
        rasterTextureBytes += decodedBytes
        while (rasterTextTextures.size > MAX_RASTER_TEXT_TEXTURES ||
            rasterTextureBytes > MAX_RASTER_TEXTURE_BYTES) {
            val iterator = rasterTextTextures.entries.iterator()
            val removed = iterator.next().value
            textures.release(removed.id)
            iterator.remove()
            rasterTextureBytes -= removed.decodedBytes
        }
        return created
    }

    private fun rasterShadow(shadow: DungeonRoomOverlayStyle.TextShadow): DungeonRoomMapRasterText.Shadow =
        when (shadow) {
            DungeonRoomOverlayStyle.TextShadow.NONE -> DungeonRoomMapRasterText.Shadow.NONE
            DungeonRoomOverlayStyle.TextShadow.DROP -> DungeonRoomMapRasterText.Shadow.DROP
            DungeonRoomOverlayStyle.TextShadow.OUTLINE -> DungeonRoomMapRasterText.Shadow.OUTLINE
        }

    private fun textWidth(font: Font, text: String): Int {
        if (settings.useMcFontRenderer.value()) return font.width(text)
        val width = rasterText.width(text, font.lineHeight)
        return if (width == 0 && text.isNotEmpty()) font.width(text) else width
    }

    private fun textLineHeight(font: Font): Int =
        if (settings.useMcFontRenderer.value()) font.lineHeight else rasterText.lineHeight(font.lineHeight)

    private data class RasterTextTexture(
        val id: Identifier,
        val texture: DynamicTexture,
        val decodedBytes: Long,
    )

    private fun drawPlayers(
        graphics: GuiGraphicsExtractor,
        font: Font,
        state: DungeonRoomOverlayGeometry.Snapshot,
        layout: DungeonRoomOverlayLayout.Layout,
        gridX: Int,
        gridY: Int,
        preview: Boolean,
    ) {
        val textures: Map<String, Identifier> = if (preview) emptyMap() else playerSkinTextures
        val holdingLeap = preview || holdingSpiritLeap()
        var showNames = settings.showPlayerNames.value() || settings.showPlayerClasses.value()
        showNames = showNames && (!settings.playerNamesOnlyWhileLeap.value() || holdingLeap)
        for (player in state.players()) {
            val markerCenterX = gridX + ((player.x() + player.offsetX()) *
                (layout.cellSize + layout.cellGap)).toFloat()
            val markerCenterY = gridY + ((player.z() + player.offsetZ()) *
                (layout.cellSize + layout.cellGap)).toFloat()
            val cellLeft = gridX + player.x() * (layout.cellSize + layout.cellGap)
            val cellTop = gridY + player.z() * (layout.cellSize + layout.cellGap)
            val baseIcon = settings.playerHeadSize.intValue()
            val requestedSkin = if (settings.showPlayerHeads.value() &&
                (!player.localPlayer() || !settings.showSelfMarker.value() && settings.showOwnPlayerHead.value()))
                textures[player.name().lowercase(Locale.ROOT)] else null
            val iconScale = if (requestedSkin == null) settings.playerMarkerScale.value()
                else settings.playerHeadScale.value()
            val iconSize = (baseIcon * iconScale).roundToInt().coerceIn(0, layout.cellSize)
            val playerX = kotlin.math.round(markerCenterX - iconSize / 2.0f).toInt()
            val top = kotlin.math.round(markerCenterY - iconSize / 2.0f).toInt()
            val classColor = classColorArgb(player.dungeonClass())
            val skin = requestedSkin
            if (iconSize > 0) {
                val markerPose = graphics.pose()
                markerPose.pushMatrix()
                markerPose.translate(markerCenterX, markerCenterY)
                markerPose.rotate(DungeonRoomPlayerMarkerGeometry.facingRotationRadians(player.yawDegrees()))
                if (skin != null) {
                    markerPose.translate(-iconSize / 2.0f, -iconSize / 2.0f)
                    graphics.blit(RenderPipelines.GUI_TEXTURED, skin, 0, 0, 8.0f, 8.0f, iconSize, iconSize, 64, 64, 0xFFFFFFFF.toInt())
                    graphics.blit(RenderPipelines.GUI_TEXTURED, skin, 0, 0, 40.0f, 8.0f, iconSize, iconSize, 64, 64, 0xFFFFFFFF.toInt())
                } else if (player.localPlayer()) {
                    markerPose.scale(iconSize / 8.0f, iconSize / 8.0f)
                    graphics.blit(
                        RenderPipelines.GUI_TEXTURED, MAP_DECORATION_FRAME, -2, -4,
                        DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_U.toFloat(),
                        DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_V.toFloat(),
                        DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_WIDTH,
                        DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_HEIGHT,
                        DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_TEXTURE_SIZE,
                        DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_TEXTURE_SIZE,
                        settings.localPlayerColor.argb(),
                    )
                } else {
                    markerPose.translate(-iconSize / 2.0f, -iconSize / 2.0f)
                    val color = if (settings.playerMarkersUseClassColors.value() &&
                        player.dungeonClass().isNotBlank()) classColor else settings.playerColor.argb()
                    graphics.fill(0, 0, iconSize, iconSize, color)
                    val presentedName = playerNameComponent(player.name(), color).string
                    val initial = firstCodePoint(presentedName).uppercase(Locale.ROOT)
                    drawStyledText(
                        graphics, font, initial,
                        maxOf(0, (iconSize - textWidth(font, initial)) / 2).toFloat(),
                        maxOf(0, (iconSize - textLineHeight(font)) / 2).toFloat(),
                        0xFFFFFFFF.toInt(), DungeonRoomOverlayStyle.TextShadow.NONE,
                    )
                }
                markerPose.popMatrix()
                if (settings.playerMarkersUseClassColors.value() && player.dungeonClass().isNotBlank())
                    border(graphics, playerX, top, iconSize, classColor)
            }
            if (showNames && !holdingLeap) {
                val scale = settings.playerNamesScale.value()
                val pose = graphics.pose()
                val className = if (settings.showPlayerClasses.value()) player.dungeonClass() else ""
                val textColor = if (settings.playerNamesUseClassColors.value() &&
                    player.dungeonClass().isNotBlank()) classColor else settings.playerNameColor.argb()
                val presentedName = if (holdingLeap || settings.showPlayerNames.value())
                    playerNameComponent(player.name(), textColor) else Component.empty()
                val label = when {
                    holdingLeap -> presentedName
                    settings.playerClassNameOnly.value() && className.isNotBlank() -> Component.literal(className)
                    presentedName.string.isBlank() -> Component.literal(className)
                    className.isBlank() -> presentedName
                    else -> Component.empty().append(presentedName).append(Component.literal(" (" + className + ")"))
                }
                if (label.string.isBlank()) continue
                val fittedLabel = fitPresentedComponent(font, label, maxOf(1, (layout.cellSize / scale).roundToInt()))
                val labelIncludesPlayerName = holdingLeap || settings.showPlayerNames.value() &&
                    (!settings.playerClassNameOnly.value() || className.isBlank())
                val preserveProfileStyle = labelIncludesPlayerName &&
                    PlayerPresentationModule.hasEnabledNameStyle(player.name())
                val labelWidth = (if (preserveProfileStyle) font.width(fittedLabel) else textWidth(font, fittedLabel.string)) * scale
                pose.pushMatrix()
                pose.translate(
                    cellLeft + maxOf(0.0f, (layout.cellSize - labelWidth) / 2),
                    (cellTop + minOf(layout.cellSize - textLineHeight(font), iconSize)).toFloat(),
                )
                pose.scale(scale, scale)
                drawPresentedText(graphics, font, fittedLabel, 0f, 0f, textColor, preserveProfileStyle)
                pose.popMatrix()
            }
        }
    }

    private fun drawLeapHeldPlayerNames(
        graphics: GuiGraphicsExtractor,
        font: Font,
        state: DungeonRoomOverlayGeometry.Snapshot,
        layout: DungeonRoomOverlayLayout.Layout,
        gridX: Int,
        gridY: Int,
        preview: Boolean,
        rotated: Boolean,
        pivot: FloatArray?,
        rotationRadians: Float,
    ) {
        val holdingLeap = preview || holdingSpiritLeap()
        if (!holdingLeap || !settings.showPlayerNames.value() && !settings.showPlayerClasses.value()) return
        val textures: Map<String, Identifier> = if (preview) emptyMap() else playerSkinTextures
        for (player in state.players()) {
            val classColor = classColorArgb(player.dungeonClass())
            val textColor = if (settings.playerNamesUseClassColors.value() &&
                player.dungeonClass().isNotBlank()) classColor else settings.playerNameColor.argb()
            val label = playerNameComponent(player.name(), textColor)
            if (label.string.isBlank()) continue
            val preserveProfileStyle = PlayerPresentationModule.hasEnabledNameStyle(player.name())
            val measuredTextWidth = if (preserveProfileStyle) font.width(label).toFloat()
                else textWidth(font, label.string).toFloat()
            var scale = settings.playerNamesScale.value()
            if (!scale.isFinite() || scale <= 0.0f) scale = 1.0f
            var centerX = gridX + ((player.x() + player.offsetX()) *
                (layout.cellSize + layout.cellGap)).toFloat()
            var centerY = gridY + ((player.z() + player.offsetZ()) *
                (layout.cellSize + layout.cellGap)).toFloat()
            if (rotated && pivot != null) {
                val dx = centerX - pivot[0]
                val dy = centerY - pivot[1]
                val cos = kotlin.math.cos(rotationRadians)
                val sin = kotlin.math.sin(rotationRadians)
                centerX = pivot[0] + cos * dx - sin * dy
                centerY = pivot[1] + sin * dx + cos * dy
            }
            val requestedSkin = if (settings.showPlayerHeads.value() &&
                (!player.localPlayer() || !settings.showSelfMarker.value() && settings.showOwnPlayerHead.value()))
                textures[player.name().lowercase(Locale.ROOT)] else null
            val iconScale = if (requestedSkin == null) settings.playerMarkerScale.value()
                else settings.playerHeadScale.value()
            val iconSize = (settings.playerHeadSize.intValue() * iconScale).roundToInt()
                .coerceIn(0, layout.cellSize)
            val placement = leapHeldNamePlacement(
                centerX, centerY + iconSize / 2.0f + 1.0f, measuredTextWidth,
                textLineHeight(font).toFloat(), scale, layout.width, layout.height,
            )
            if (placement[2] <= 0.0f) continue
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(placement[0], placement[1])
            pose.scale(placement[2], placement[2])
            drawPresentedText(graphics, font, label, 0f, 0f, textColor, preserveProfileStyle)
            pose.popMatrix()
        }
    }

    private fun drawCellText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        lines: List<String>,
        footprint: List<DungeonRoomOverlayGeometry.Cell>,
        layout: DungeonRoomOverlayLayout.Layout,
        gridX: Int,
        gridY: Int,
        color: Int,
    ) {
        if (lines.isEmpty()) return
        val minZ = footprint.minOfOrNull { it.z() } ?: 0
        val maxZ = footprint.maxOfOrNull { it.z() } ?: minZ
        val roomHeight = (maxZ - minZ + 1) * layout.cellSize + (maxZ - minZ) * layout.cellGap
        val requestedScale = settings.roomTextScale.value()
        val heightScale = roomHeight / maxOf(1, lines.size * textLineHeight(font)).toFloat()
        val textScale = maxOf(0.05f, minOf(requestedScale, heightScale))
        val lineHeight = textLineHeight(font) * textScale
        val textHeight = lineHeight * lines.size
        val anchor = DungeonRoomOverlayStyle.alignmentAnchor(footprint, settings.roomTextAlignment.value())
        val centerX = gridX + (anchor.x() * (layout.cellSize + layout.cellGap)).toFloat() +
            layout.cellSize / 2.0f
        val centerY = gridY + (anchor.z() * (layout.cellSize + layout.cellGap)).toFloat() +
            layout.cellSize / 2.0f
        val textY = centerY - textHeight / 2.0f
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.scale(textScale, textScale)
        for ((index, line) in lines.withIndex()) {
            val textX = centerX - textWidth(font, line) * textScale / 2.0f
            pose.pushMatrix()
            pose.translate(textX / textScale, textY / textScale + index * textLineHeight(font))
            drawStyledText(graphics, font, line, 0f, 0f, color)
            pose.popMatrix()
        }
        pose.popMatrix()
    }

    private fun roomLabel(
        cell: DungeonRoomOverlayGeometry.Cell?,
        room: DungeonRoomOverlayGeometry.Room?,
    ): String {
        if (cell == null) return ""
        if (isYellowRoom(cell, room)) return ""
        var name = visibleRoomName(cell, room)
        if (cell.type() == DungeonMapGeometry.RoomType.FAIRY ||
            cell.type() == DungeonMapGeometry.RoomType.ENTRANCE ||
            cell.type() == DungeonMapGeometry.RoomType.BLOOD) return ""
        val puzzleRoom = isPuzzleRoom(cell, room)
        if (puzzleRoom && settings.roomText.value() == "Room Secrets") return name
        var maxSecrets = room?.maxSecrets() ?: 0
        val foundSecrets = room?.foundSecrets() ?: -1
        val showCount = shouldShowSecretCount(
            cell, room, settings.showRoomSecrets.value(), settings.skipOneSecretRoom.value(), inBossPhase(),
        )
        if (!showCount && settings.roomText.value() == "Room Secrets") return ""
        val unopened = room != null &&
            room.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED && showCount
        if (!showCount) maxSecrets = 0
        if (name.isEmpty() && (room == null || room.name() == "Unknown")) name = "?"
        return DungeonRoomMapLabel.build(name, foundSecrets, maxSecrets, settings.roomText.value(), unopened)
    }

    private fun checkmarkFor(
        cell: DungeonRoomOverlayGeometry.Cell?,
        room: DungeonRoomOverlayGeometry.Room?,
    ): String {
        if (cell == null || !settings.showCheckmarks.value()) return ""
        if (room == null ||
            cell.type() == DungeonMapGeometry.RoomType.FAIRY && !settings.showFairyCheckmark.value() ||
            settings.checkmarkOnlyWhenGreen.value() && room.checkmark() != "GREEN") return ""
        if (settings.roomText.value() == "Room Secrets" && room.maxSecrets() == 0 &&
            !settings.checkmarkIfNoSecrets.value()) return ""
        if (settings.hideCheckmarkWithName.value() && visibleRoomName(cell, room).isNotEmpty()) return ""
        return checkmarkIcon(room.checkmark())
    }

    private fun visibleRoomName(
        cell: DungeonRoomOverlayGeometry.Cell,
        room: DungeonRoomOverlayGeometry.Room?,
    ): String {
        var name = if (room == null || room.name() == "Unknown") "" else room.name()
        if (settings.skipCommonRoomNames.value() && name in listOf("Entrance", "Fairy", "Blood")) name = ""
        if (settings.skipYellowRoomName.value() && name.equals("Yellow", ignoreCase = true)) name = ""
        if (cell.type() == DungeonMapGeometry.RoomType.PUZZLE && !settings.showPuzzleName.value()) name = ""
        return name
    }

    private fun roomTextColor(
        cell: DungeonRoomOverlayGeometry.Cell,
        room: DungeonRoomOverlayGeometry.Room?,
    ): Int = DungeonRoomOverlayStyle.roomTextColor(
        cell.visibility(), room?.checkmark() ?: "", settings.roomTextColorEnabled.value(),
        settings.textColor.argb(), settings.unopenedTextColor.argb(),
    )

    private fun checkmarkIcon(checkmark: String?): String =
        if (checkmark == "GREEN" || checkmark == "WHITE" || checkmark == "RED") checkmark else ""

    private fun drawSmallCheckmark(
        graphics: GuiGraphicsExtractor,
        checkmark: String,
        left: Int,
        top: Int,
        cellSize: Int,
    ) {
        val texture = when (checkmark) {
            "GREEN" -> ICON_GREEN
            "WHITE" -> ICON_WHITE
            "RED" -> ICON_CROSS
            else -> null
        }
        if (texture == null || settings.roomIconSize.value() <= 0.0f) return
        val iconSize = maxOf(2, minOf(cellSize / 3,
            (cellSize / 3.0f * settings.roomIconSize.value()).roundToInt()))
        drawStyledIcon(graphics, fontForIcons(), checkmark, texture, left + cellSize - iconSize - 1, top + 1, iconSize)
    }

    private fun drawCheckmark(
        graphics: GuiGraphicsExtractor,
        checkmark: String,
        left: Int,
        top: Int,
        cellSize: Int,
    ) {
        val texture = when (checkmark) {
            "GREEN" -> ICON_GREEN
            "WHITE" -> ICON_WHITE
            "RED" -> ICON_CROSS
            "QUESTION_MARK" -> ICON_QUESTION
            else -> null
        }
        if (texture == null) return
        val iconSize = minOf(cellSize - 4, (minOf(cellSize, 12) * settings.roomIconSize.value()).roundToInt())
        if (iconSize <= 0) return
        val inset = maxOf(2, (cellSize - iconSize) / 2)
        drawStyledIcon(graphics, fontForIcons(), checkmark, texture, left + inset, top + inset, iconSize)
    }

    private fun drawCheckmark(
        graphics: GuiGraphicsExtractor,
        checkmark: String,
        footprint: List<DungeonRoomOverlayGeometry.Cell>,
        gridX: Int,
        gridY: Int,
        layout: DungeonRoomOverlayLayout.Layout,
    ) {
        val texture = when (checkmark) {
            "GREEN" -> ICON_GREEN
            "WHITE" -> ICON_WHITE
            "RED" -> ICON_CROSS
            "QUESTION_MARK" -> ICON_QUESTION
            else -> null
        }
        if (texture == null) return
        val iconSize = minOf(
            layout.cellSize - 2,
            (minOf(layout.cellSize, 12) * settings.roomIconSize.value()).roundToInt(),
        )
        if (iconSize <= 0) return
        val anchor = DungeonRoomOverlayStyle.alignmentAnchor(footprint, settings.roomIconAlignment.value())
        val centerX = gridX + (anchor.x() * (layout.cellSize + layout.cellGap)).roundToInt() +
            layout.cellSize / 2
        val centerY = gridY + (anchor.z() * (layout.cellSize + layout.cellGap)).roundToInt() +
            layout.cellSize / 2
        drawStyledIcon(graphics, fontForIcons(), checkmark, texture,
            centerX - iconSize / 2, centerY - iconSize / 2, iconSize)
    }

    private fun fontForIcons(): Font = Minecraft.getInstance().font

    private fun drawStyledIcon(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mark: String,
        texture: Identifier,
        x: Int,
        y: Int,
        size: Int,
    ) {
        val style = DungeonRoomOverlayStyle.iconStyle(settings.iconStyle.value())
        if (style == DungeonRoomOverlayStyle.IconStyle.ILLEGAL_MAP) {
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x.toFloat(), y.toFloat())
            pose.scale(size / 10.0f, size / 10.0f)
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0f, 0.0f, 10, 10, 10, 10)
            pose.popMatrix()
            return
        }
        var color = when (mark) {
            "GREEN" -> 0xFF55FF55.toInt()
            "WHITE" -> 0xFFFFFFFF.toInt()
            "RED" -> 0xFFFF5555.toInt()
            else -> 0xFFFFFF55.toInt()
        }
        var thickness: Int = if (style == DungeonRoomOverlayStyle.IconStyle.VANILLA_OLD)
            maxOf(2, size / 4) else maxOf(1, size / 7)
        if (style == DungeonRoomOverlayStyle.IconStyle.NEU) {
            drawIconDisc(graphics, x + size / 2, y + size / 2, maxOf(3, size / 2), 0xD0202020.toInt())
            color = when (mark) {
                "GREEN" -> 0xFF00E676.toInt()
                "WHITE" -> 0xFFF5F5F5.toInt()
                "RED" -> 0xFFFF5252.toInt()
                else -> 0xFFFFD740.toInt()
            }
            thickness = maxOf(1, size / 6)
        }
        drawStatusPixelGlyph(graphics, mark, x, y, size, color, thickness)
    }

    private fun drawIconDisc(graphics: GuiGraphicsExtractor, centerX: Int, centerY: Int, radius: Int, color: Int) {
        for (row in -radius..radius) {
            val half = kotlin.math.floor(kotlin.math.sqrt(maxOf(0, radius * radius - row * row).toDouble())).toInt()
            graphics.fill(centerX - half, centerY + row, centerX + half + 1, centerY + row + 1, color)
        }
    }

    private fun drawStatusPixelGlyph(
        graphics: GuiGraphicsExtractor,
        mark: String,
        x: Int,
        y: Int,
        size: Int,
        color: Int,
        thickness: Int,
    ) {
        val unit = maxOf(1, size / 10)
        val span = size - unit * 2
        if (mark == "GREEN" || mark == "WHITE") {
            fillDiagonal(graphics, x + unit, y + size / 2, thickness, color, 3, 1)
            fillDiagonal(graphics, x + size / 2, y + size * 2 / 3, thickness, color, 5, -1)
        } else if (mark == "RED") {
            for (i in 0 until span) {
                graphics.fill(x + unit + i, y + unit + i, x + unit + i + thickness, y + unit + i + thickness, color)
                graphics.fill(x + size - unit - i - thickness, y + unit + i,
                    x + size - unit - i, y + unit + i + thickness, color)
            }
        } else {
            val mid = x + size / 2
            graphics.fill(mid - size / 4, y + unit, mid + size / 4 + 1, y + unit + thickness, color)
            graphics.fill(mid + size / 4 - thickness + 1, y + unit, mid + size / 4 + 1,
                y + size / 2, color)
            graphics.fill(mid - thickness / 2, y + size / 2 - unit, mid + (thickness + 1) / 2,
                y + size * 2 / 3, color)
            graphics.fill(mid - thickness / 2, y + size - unit * 2, mid + (thickness + 1) / 2,
                y + size - unit, color)
        }
    }

    private fun fillDiagonal(
        graphics: GuiGraphicsExtractor,
        startX: Int,
        startY: Int,
        thickness: Int,
        color: Int,
        steps: Int,
        direction: Int,
    ) {
        for (step in 0 until steps) {
            val px = startX + step * maxOf(1, thickness - 1)
            val py = startY + step * direction * maxOf(1, thickness - 1)
            graphics.fill(px, py, px + thickness, py + thickness, color)
        }
    }

    private fun holdingSpiritLeap(): Boolean {
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        return geiler.addons.client.utility.SkyblockItems.realName(player.mainHandItem).lowercase(Locale.ROOT).contains("leap")
    }

    private fun layout(font: Font): DungeonRoomOverlayLayout.Layout =
        DungeonRoomOverlayLayout.calculate(
            settings.roomSize.intValue(), settings.roomGap.intValue(), settings.mapPadding.intValue(),
        )

    private fun roomColors(): DungeonRoomOverlayStyle.RoomColors =
        DungeonRoomOverlayStyle.RoomColors(
            settings.roomColor.argb(), settings.puzzleColor.argb(), settings.trapColor.argb(),
            settings.minibossColor.argb(), settings.entranceColor.argb(), settings.fairyColor.argb(),
            settings.bloodRoomColor.argb(), settings.unknownRoomColor.argb(), settings.rareRoomColor.argb(),
            settings.championRoomColor.argb(),
        )

    private fun doorColors(): DungeonRoomOverlayStyle.DoorColors =
        DungeonRoomOverlayStyle.DoorColors(
            settings.normalDoorColor.argb(), settings.glassDoorColor.argb(), settings.startDoorColor.argb(),
            settings.witherDoorColor.argb(), settings.bloodDoorColor.argb(), settings.fairyDoorColor.argb(),
            settings.unknownDoorColor.argb(),
        )

    private fun exitColor(
        exit: DungeonRoomOverlayGeometry.ExitMarker,
        data: DungeonRoomOverlayRenderCache.Data,
        colors: DungeonRoomOverlayStyle.RoomColors,
    ): Int {
        if (exit.kind() != DungeonRoomOverlayGeometry.DoorKind.NORMAL ||
            !settings.normalDoorDynamicColor.value()) return DungeonRoomOverlayStyle.doorColor(exit.kind(), doorColors())
        val from = exit.cell() ?: return settings.normalDoorColor.argb()
        val direction = exit.direction() ?: return settings.normalDoorColor.argb()
        val to = neighbor(from, direction)
        val first = data.cellAt(from.x(), from.z())
        val second = data.cellAt(to.x(), to.z())
        if (first != null && first.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED ||
            second != null && second.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED)
            return settings.unopenedRoomColor.argb()
        return DungeonRoomOverlayStyle.dynamicDoorColor(
            first?.type() ?: DungeonMapGeometry.RoomType.UNKNOWN,
            second?.type() ?: DungeonMapGeometry.RoomType.UNKNOWN,
            colors, settings.normalDoorColor.argb(),
        )
    }

    private fun fitPresentedComponent(font: Font, source: Component, maximumWidth: Int): Component {
        val original = source.string
        val fitted = fit(font, original, maximumWidth)
        if (fitted == original) return source
        val hasEllipsis = fitted.endsWith("…")
        val keepCodePoints = fitted.codePointCount(0, fitted.length) - if (hasEllipsis) 1 else 0
        if (keepCodePoints <= 0) return if (hasEllipsis)
            Component.literal("…").withStyle(source.style) else Component.empty()
        return rebuildPresentedPrefix(source, keepCodePoints, hasEllipsis)
    }

    private fun rebuildPresentedPrefix(source: Component, keepCodePoints: Int, ellipsis: Boolean): Component {
        val result: MutableComponent = Component.empty()
        val remaining = intArrayOf(keepCodePoints)
        val lastStyle = arrayOf(source.style)
        source.visit({ style, text ->
            if (remaining[0] <= 0 || text.isEmpty()) {
                Optional.empty<Void>()
            } else {
                val codePoints = minOf(remaining[0], text.codePointCount(0, text.length))
                val end = text.offsetByCodePoints(0, codePoints)
                result.append(Component.literal(text.substring(0, end)).withStyle(style))
                remaining[0] -= codePoints
                lastStyle[0] = style
                Optional.empty<Void>()
            }
        }, Style.EMPTY)
        if (ellipsis) result.append(Component.literal("…").withStyle(lastStyle[0]))
        return result
    }

    private fun firstCodePoint(text: String?): String {
        if (text.isNullOrEmpty()) return "?"
        return text.substring(0, text.offsetByCodePoints(0, 1))
    }

    private fun border(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: Int) {
        graphics.fill(x, y, x + size, y + 1, color)
        graphics.fill(x, y + size - 1, x + size, y + size, color)
        graphics.fill(x, y, x + 1, y + size, color)
        graphics.fill(x + size - 1, y, x + size, y + size, color)
    }

    private fun drawOuterOutline(graphics: GuiGraphicsExtractor, xMax: Int, yMax: Int, width: Int, color: Int) {
        for (i in 0 until width) {
            graphics.fill(i, i, xMax - i, i + 1, color)
            graphics.fill(i, yMax - 1 - i, xMax - i, yMax - i, color)
            graphics.fill(i, i, i + 1, yMax - i, color)
            graphics.fill(xMax - 1 - i, i, xMax - i, yMax - i, color)
        }
    }

    private fun neighbor(
        cell: DungeonRoomOverlayGeometry.Cell,
        direction: DungeonRoomOverlayGeometry.Direction,
    ): DungeonRoomOverlayGeometry.Cell = when (direction) {
        DungeonRoomOverlayGeometry.Direction.NORTH ->
            DungeonRoomOverlayGeometry.Cell(cell.x(), cell.z() - 1, null, null)
        DungeonRoomOverlayGeometry.Direction.EAST ->
            DungeonRoomOverlayGeometry.Cell(cell.x() + 1, cell.z(), null, null)
        DungeonRoomOverlayGeometry.Direction.SOUTH ->
            DungeonRoomOverlayGeometry.Cell(cell.x(), cell.z() + 1, null, null)
        DungeonRoomOverlayGeometry.Direction.WEST ->
            DungeonRoomOverlayGeometry.Cell(cell.x() - 1, cell.z(), null, null)
    }

    private fun fit(font: Font, text: String, requestedMaximumWidth: Int): String {
        val maximumWidth = maxOf(0, requestedMaximumWidth)
        if (textWidth(font, text) <= maximumWidth) return text
        val ellipsis = "…"
        val availableWidth = maximumWidth - textWidth(font, ellipsis)
        if (availableWidth < 0) return ""
        var codePoints = text.codePointCount(0, text.length)
        while (codePoints > 0) {
            val end = text.offsetByCodePoints(0, codePoints)
            if (textWidth(font, text.substring(0, end)) <= availableWidth)
                return text.substring(0, end) + ellipsis
            codePoints--
        }
        return ellipsis
    }

    private data class RoomLabel(
        val cell: DungeonRoomOverlayGeometry.Cell,
        val room: DungeonRoomOverlayGeometry.Room?,
        val footprint: List<DungeonRoomOverlayGeometry.Cell>,
        val minX: Int,
        val minZ: Int,
        val checkmark: String,
        val lines: List<String>,
        val unknownPlaceholder: Boolean,
    )

    private data class RoomLabelCache(
        val data: DungeonRoomOverlayRenderCache.Data,
        val font: Font,
        val cellSize: Int,
        val cellGap: Int,
        val textScale: Float,
        val options: LabelOptions,
        val labels: List<RoomLabel>,
    ) {
        fun matches(
            otherData: DungeonRoomOverlayRenderCache.Data,
            otherFont: Font,
            otherCellSize: Int,
            otherCellGap: Int,
            otherTextScale: Float,
            otherOptions: LabelOptions,
        ): Boolean =
            data === otherData && font === otherFont && cellSize == otherCellSize && cellGap == otherCellGap &&
                java.lang.Float.compare(textScale, otherTextScale) == 0 && options == otherOptions
    }

    private data class LabelOptions(
        val roomText: String,
        val showCheckmarks: Boolean,
        val hideCheckmarkWithName: Boolean,
        val showPuzzleName: Boolean,
        val showRoomSecrets: Boolean,
        val skipOneSecretRoom: Boolean,
        val skipCommonRoomNames: Boolean,
        val skipYellowRoomName: Boolean,
        val showFairyCheckmark: Boolean,
        val checkmarkOnlyWhenGreen: Boolean,
        val checkmarkIfNoSecrets: Boolean,
        val roomTextAlignment: String,
        val bossPhase: Boolean,
    )

    private fun isPartialCoreRoom(room: DungeonRoomOverlayGeometry.Room?): Boolean =
        room != null && isPartialCoreRoomId(room.id())

    private fun isPartialCoreRoomId(roomId: String?): Boolean =
        roomId != null && roomId.startsWith("partial-core:")

    private fun shouldShowSecretCount(
        cell: DungeonRoomOverlayGeometry.Cell?,
        room: DungeonRoomOverlayGeometry.Room?,
        showSecrets: Boolean,
        skipOneSecret: Boolean,
        bossPhase: Boolean,
    ): Boolean {
        val puzzleRoom = isPuzzleRoom(cell, room)
        val maximum = room?.maxSecrets() ?: 0
        return showSecrets && !puzzleRoom && !bossPhase && !(skipOneSecret && maximum == 1)
    }

    private fun isPuzzleRoom(
        cell: DungeonRoomOverlayGeometry.Cell?,
        room: DungeonRoomOverlayGeometry.Room?,
    ): Boolean =
        cell?.type() == DungeonMapGeometry.RoomType.PUZZLE ||
            room?.type() == DungeonMapGeometry.RoomType.PUZZLE

    private fun isYellowRoom(
        cell: DungeonRoomOverlayGeometry.Cell?,
        room: DungeonRoomOverlayGeometry.Room?,
    ): Boolean =
        cell?.type() == DungeonMapGeometry.RoomType.CHAMPION ||
            room != null && (room.type() == DungeonMapGeometry.RoomType.CHAMPION ||
                room.name().equals("Yellow", ignoreCase = true))

    companion object {
        private val MAX_RASTER_TEXT_TEXTURES = DungeonRoomMapRasterText.MAX_CACHE_ENTRIES
        private const val MAX_RASTER_TEXTURE_BYTES = 8L * 1024 * 1024
        private const val MAX_ONE_RASTER_TEXTURE_BYTES = 1024L * 1024
        private const val PLAYER_MARKER_ONLY_REASON =
            "verified continuous local-player position; room identity pending"
        private const val VERIFIED_LOADED_CORE_REASON = "verified-loaded-catalog-core-scan"
        private val ICON_CROSS = Identifier.fromNamespaceAndPath("geileraddons", "map/cross.png")
        private val ICON_GREEN = Identifier.fromNamespaceAndPath("geileraddons", "map/green_check.png")
        private val ICON_WHITE = Identifier.fromNamespaceAndPath("geileraddons", "map/white_check.png")
        private val ICON_QUESTION = Identifier.fromNamespaceAndPath("geileraddons", "map/question.png")
        private val MAP_DECORATION_FRAME = Identifier.fromNamespaceAndPath(
            "minecraft", "textures/map/decorations/frame.png",
        )

        @JvmField
        val INSTANCE = DungeonRoomOverlayModule(Settings())

        /** Returns the same client-view placement used for Leap-held player labels. */
        @JvmStatic
        fun leapHeldNamePlacement(
            centerX: Float,
            topY: Float,
            textWidth: Float,
            textHeight: Float,
            requestedScale: Float,
            viewportWidth: Int,
            viewportHeight: Int,
        ): FloatArray {
            val safeWidth = maxOf(1.0f, if (textWidth.isFinite()) textWidth else 1.0f)
            val safeHeight = maxOf(1.0f, if (textHeight.isFinite()) textHeight else 1.0f)
            var scale = if (requestedScale.isFinite() && requestedScale > 0.0f) requestedScale else 1.0f
            val width = maxOf(1, viewportWidth).toFloat()
            val height = maxOf(1, viewportHeight).toFloat()
            // Keep a one-pixel gutter for glyph overhang and text shadow at each viewport edge.
            val insetX = if (width > 2.0f) 1.0f else 0.0f
            val insetY = if (height > 2.0f) 1.0f else 0.0f
            val availableWidth = maxOf(1.0f, width - insetX * 2.0f)
            val availableHeight = maxOf(1.0f, height - insetY * 2.0f)
            scale = minOf(scale, minOf(availableWidth / safeWidth, availableHeight / safeHeight))
            if (!scale.isFinite() || scale <= 0.0f) return floatArrayOf(0.0f, 0.0f, 0.0f)
            val scaledWidth = safeWidth * scale
            val scaledHeight = safeHeight * scale
            var x = if (centerX.isFinite()) centerX - scaledWidth / 2.0f else 0.0f
            var y = if (topY.isFinite()) topY else 0.0f
            x = maxOf(insetX, minOf(maxOf(insetX, width - insetX - scaledWidth), x))
            y = maxOf(insetY, minOf(maxOf(insetY, height - insetY - scaledHeight), y))
            return floatArrayOf(x, y, scale)
        }

        /** Room labels can overhang by at most half a configured gap on each side. */
        @JvmStatic
        fun roomLabelMaximumWidth(minX: Int, maxX: Int, cellSize: Int, cellGap: Int, textScale: Float): Int {
            val cellsWide = maxOf(1, maxX - minX + 1)
            val roomWidth = cellsWide * maxOf(1, cellSize) + maxOf(0, cellsWide - 1) * maxOf(0, cellGap)
            return maxOf(1, kotlin.math.round(
                (roomWidth + maxOf(0, cellGap)) / maxOf(0.1f, textScale),
            ).toInt())
        }

        /** Uses the palette configured in Spirit Leap Menu so every class color in the mod follows one setting. */
        private fun classColorArgb(dungeonClass: String?): Int = SpiritLeapMenuModule.classColorArgb(dungeonClass)

        private fun doorKind(type: DungeonDoorTracker.Type?): DungeonRoomOverlayGeometry.DoorKind =
            when (type) {
                null, DungeonDoorTracker.Type.NORMAL -> DungeonRoomOverlayGeometry.DoorKind.NORMAL
                DungeonDoorTracker.Type.GLASS -> DungeonRoomOverlayGeometry.DoorKind.GLASS
                DungeonDoorTracker.Type.START -> DungeonRoomOverlayGeometry.DoorKind.START
                DungeonDoorTracker.Type.WITHER -> DungeonRoomOverlayGeometry.DoorKind.WITHER
                DungeonDoorTracker.Type.BLOOD -> DungeonRoomOverlayGeometry.DoorKind.BLOOD
            }

        private fun playerNameComponent(playerName: String?, fallbackColor: Int): Component {
            if (playerName == null) return Component.empty()
            val base = Component.literal(playerName).withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(fallbackColor and 0x00FFFFFF)),
            )
            return PlayerPresentationModule.replaceRenderedName(playerName, base) ?: base
        }

        private fun currentIndex(state: DungeonRoomOverlayGeometry.Snapshot): DungeonGridIndex {
            val playerCell = state.playerCell() ?: return DungeonGridIndex(0, 0)
            return DungeonGridIndex(playerCell.componentX, playerCell.componentZ)
        }

        private data class DungeonGridIndex(val x: Int, val z: Int)

        private class Settings {
            val showMap = BooleanSetting("Show Map", true)
            val showPreview = BooleanSetting("Show Preview", true)
            val showExits = BooleanSetting("Show Room Exits", true)
            val revealUnopened = BooleanSetting("Reveal Unopened Rooms", "Illegal Map: Reveal Unopened Rooms", true)
            val showInBoss = BooleanSetting("Show In Boss", false)
            val mapInfo = BooleanSetting("Show Map Info", "Map Info", true)
            val mapInfoIncludeRemaining = BooleanSetting("Include Remaining", false)
            val mapInfoRemaining = ChoiceSetting("Remaining Secrets", "Minimum", "Minimum", "Remaining")
            val mapInfoDeaths = ChoiceSetting("Map Info Deaths", "Deaths", "Deaths", "Unfound")
            val mapInfoHideInBoss = BooleanSetting("Hide Map Info In Boss", true)
            val showPlayers = BooleanSetting("Show Players", true)
            val showPlayerNames = BooleanSetting("Show Player Names", true)
            val showPlayerClasses = BooleanSetting("Show Player Class", false)
            val playerNamesUseClassColors = BooleanSetting("Color Players By Class", false)
            val playerMarkersUseClassColors = BooleanSetting("Color Player Markers By Class", true)
            val playerClassNameOnly = BooleanSetting("Replace Player Name With Class", false)
            val showPlayerHeads = BooleanSetting("Show Player Heads", true)
            val showOwnPlayerHead = BooleanSetting("Show Own Player Head", false)
            val showSelfMarker = BooleanSetting("Use Marker For Self", true)
            val playerNamesOnlyWhileLeap = BooleanSetting("Player Names Only While Holding Leap", true)
            val showOutline = BooleanSetting("Show Outline", true)
            val rotateMap = BooleanSetting("Rotate Map Around Player", false)
            val normalDoorDynamicColor = BooleanSetting("Normal Door Dynamic Color", true)
            val roomText = ChoiceSetting("Room Text", "Both", "Both", "Room Name", "Room Secrets", "Off")
            val roomTextAlignment = ChoiceSetting(
                "Room Text Alignment", "CenterL", "Top Left", "Top Right", "Bottom Left", "Bottom Right",
                "Center", "CenterL",
            )
            val roomIconAlignment = ChoiceSetting(
                "Room Icon Alignment", "Center", "Top Left", "Top Right", "Bottom Left", "Bottom Right",
                "Center", "CenterL",
            )
            val textShadowMode = ChoiceSetting("Text Shadow", "None", "None", "Drop", "Outline")
            val useMcFontRenderer = BooleanSetting("Use MC Font Renderer", true)
            val iconStyle = ChoiceSetting(
                "Icon Style", "IllegalMap",
                mapOf("IllegalMap" to "Item Icon", "NEU" to "Round", "Vanilla New" to "Thin",
                    "Vanilla Old" to "Bold"),
                "IllegalMap", "NEU", "Vanilla New", "Vanilla Old",
            )
            val legacyTextShadow = BooleanSetting("Text Shadow", "Legacy Text Shadow", false)
            val showCheckmarks = BooleanSetting("Show Checkmarks", false)
            val hideCheckmarkWithName = BooleanSetting("Hide Checkmark With Name", false)
            val showPuzzleName = BooleanSetting("Show Puzzle Name", true)
            val showRoomSecrets = BooleanSetting("Show Room Secrets", true)
            val skipOneSecretRoom = BooleanSetting("Skip Secret Count When Exactly One", false)
            val skipCommonRoomNames = BooleanSetting("Skip Common Room Names", false)
            val skipYellowRoomName = BooleanSetting("Skip Yellow Room Name", false)
            val showFairyCheckmark = BooleanSetting("Show Fairy Checkmark", false)
            val checkmarkOnlyWhenGreen = BooleanSetting("Checkmark Only When Green", false)
            val checkmarkIfNoSecrets = BooleanSetting("Checkmark If Secret Count Is Zero", false)
            val roomTextColorEnabled = BooleanSetting("Color Room Text By Checkmark", true)
            val mapScale = NumberSetting("Map Scale", 0.5f, 5.0f, 3.6f)
            val roomTextScale = NumberSetting("Room Text Scale", 0.1f, 2.0f, 0.45f)
            val roomSize = NumberSetting("Room Size", 8.0f, 24.0f, 16.0f, true)
            val roomGap = NumberSetting("Room Gap", 0.0f, 8.0f, 4.0f, true)
            val doorSize = NumberSetting("Door Size", 0.1f, 1.0f, 1.0f)
            val roomIconSize = NumberSetting("Room Icon Size", 0.0f, 2.0f, 1.0f)
            val mapPadding = NumberSetting("Map Padding", 0.0f, 24.0f, 12.0f, true)
            val outlineWidth = NumberSetting("Outline Width", 0.0f, 20.0f, 1.0f, true)
            val playerHeadSize = NumberSetting("Player Head Size", 4.0f, 16.0f, 8.0f, true)
            val playerHeadScale = NumberSetting("Player Head Scale", 0.0f, 10.0f, 1.0f)
            val playerMarkerScale = NumberSetting("Player Marker Scale", 0.0f, 10.0f, 1.0f)
            val playerNamesScale = NumberSetting("Player Names Scale", 0.1f, 10.0f, 0.75f)
            val unopenedDarkenFactor = NumberSetting("Unopened Darken Factor", 0.0f, 1.0f, 0.7f)
            val background = rgba("MapBackground", "Map Background", 242, 242, 242, 73)
            val mapInfoBackground = rgba("MapInfoBackground", "Map Info Background", 85, 85, 85, 0)
            val outlineColor = rgba("MapOutline", "Map Outline", 227, 225, 225, 186)
            val textColor = rgb("TextColor", "Text Color", 100, 100, 100)
            val playerNameColor = rgb("PlayerNameColor", "Player Name Color", 70, 70, 70)
            val currentRoomColor = rgb("CurrentRoom", "Current Room", 255, 255, 85)
            val playerColor = rgb("PlayerMarker", "Player Marker", 68, 214, 255)
            val localPlayerColor = rgb("LocalPlayerMarker", "Local Player Marker", 85, 255, 85)
            val roomColor = rgb("RoomColor", "Normal Room", 107, 58, 17)
            val puzzleColor = rgb("PuzzleColor", "Puzzle Room", 117, 0, 133)
            val trapColor = rgb("TrapColor", "Trap Room", 216, 127, 51)
            val minibossColor = rgb("MinibossColor", "Miniboss Room", 107, 58, 17)
            val entranceColor = rgb("EntranceColor", "Entrance Room", 20, 133, 0)
            val fairyColor = rgb("FairyColor", "Fairy Room", 244, 19, 139)
            val bloodRoomColor = rgb("BloodRoomColor", "Blood Room", 255, 0, 0)
            val unknownRoomColor = rgb("UnknownRoomColor", "Unknown Room", 40, 40, 40)
            val rareRoomColor = rgb("RareRoomColor", "Rare Room", 107, 58, 17)
            val championRoomColor = rgb("ChampionRoomColor", "Yellow Room", 254, 223, 0)
            val pendingRoomColor = rgb("PendingRoomColor", "Pending Room", 55, 65, 90)
            val normalDoorColor = rgb("NormalDoorColor", "Normal Door", 107, 58, 17)
            val glassDoorColor = rgb("GlassDoorColor", "Glass Door", 95, 228, 220)
            val startDoorColor = rgb("StartDoorColor", "Start Door", 100, 224, 133)
            val witherDoorColor = rgb("WitherDoorColor", "Wither Door", 0, 0, 0)
            val bloodDoorColor = rgb("BloodDoorColor", "Blood Door", 255, 85, 85)
            val fairyDoorColor = rgb("FairyDoorColor", "Fairy Door", 244, 19, 139)
            val unknownDoorColor = rgb("UnknownDoorColor", "Unknown Door", 28, 28, 28)
            val unopenedRoomColor = rgb("UnopenedRoomColor", "Unopened Room", 72, 76, 91)
            val unopenedTextColor = rgb("UnopenedTextColor", "Unopened Room Text", 225, 230, 240)
            val disablePredictions = BooleanSetting("Disable Predictions", false)

            fun all(): Array<Setting> = arrayOf(
                showMap, showPreview, showExits, revealUnopened, showInBoss,
                showPlayers, showPlayerNames, showPlayerClasses, playerNamesUseClassColors, playerMarkersUseClassColors,
                playerClassNameOnly, showPlayerHeads, showOwnPlayerHead, showSelfMarker, playerNamesOnlyWhileLeap,
                showOutline, rotateMap, normalDoorDynamicColor,
                roomText, roomTextAlignment, roomIconAlignment, textShadowMode, useMcFontRenderer, iconStyle,
                legacyTextShadow, showCheckmarks, hideCheckmarkWithName,
                showPuzzleName, showRoomSecrets, skipOneSecretRoom, skipCommonRoomNames,
                skipYellowRoomName, showFairyCheckmark, checkmarkOnlyWhenGreen, checkmarkIfNoSecrets,
                roomTextColorEnabled, mapScale, roomTextScale, roomSize, roomGap, doorSize, roomIconSize,
                mapPadding, outlineWidth, playerHeadSize, playerHeadScale, playerMarkerScale, playerNamesScale,
                unopenedDarkenFactor, background, outlineColor, textColor,
                playerNameColor, currentRoomColor, playerColor, localPlayerColor, roomColor, puzzleColor,
                trapColor, minibossColor, entranceColor, fairyColor, bloodRoomColor, unknownRoomColor,
                rareRoomColor, championRoomColor, pendingRoomColor, normalDoorColor, glassDoorColor,
                startDoorColor, witherDoorColor, bloodDoorColor, fairyDoorColor, unknownDoorColor,
                unopenedRoomColor, unopenedTextColor, disablePredictions,
                mapInfo, mapInfoIncludeRemaining, mapInfoRemaining, mapInfoDeaths, mapInfoHideInBoss, mapInfoBackground,
            )

            private fun rgb(id: String, name: String, red: Int, green: Int, blue: Int): ColorSetting =
                ColorSetting(id, name, red, green, blue)

            private fun rgba(
                id: String,
                name: String,
                red: Int,
                green: Int,
                blue: Int,
                alpha: Int,
            ): ColorSetting = ColorSetting(id, name, red, green, blue, alpha)
        }
    }
}

