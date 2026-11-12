package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.DungeonMapRoomDetector
import geiler.addons.client.dungeon.DungeonRoomOrientationDetector
import geiler.addons.client.dungeon.DungeonRoomOrientationRules
import geiler.addons.client.dungeon.SecretWaypointCategoryRules
import geiler.addons.client.dungeon.SecretWaypointClickFeedbackRules
import geiler.addons.client.dungeon.SecretWaypointCollectionRules
import geiler.addons.client.dungeon.SecretWaypointCollectionTracker
import geiler.addons.client.dungeon.SecretWaypointRoomCatalog
import geiler.addons.client.dungeon.SecretWaypointStore
import geiler.addons.client.dungeon.odin.OdinDungeonMapCoreScanner
import geiler.addons.client.gui.SecretWaypointEditorScreen
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

/** Authored and personal waypoints with click chimes and run-scoped collection feedback. */
class SecretWaypointsModule private constructor(private val settings: Settings) : Module(
    "Secret Waypoints",
    "Shows authored, personal, and loaded-room secret markers.",
    Category.F7,
    false,
    settings.enabledMarkers,
    settings.liveMarkers,
    settings.chestCandidates,
    settings.droppedItems,
    settings.essenceDrops,
    settings.batCandidates,
    settings.itemFrameCandidates,
    settings.redstoneKeys,
    settings.leverCandidates,
    settings.keepCollected,
    settings.name,
    settings.personalId,
    settings.defaultId,
    settings.range,
    settings.personalColor,
    settings.defaultColor,
    ModuleAction("Add Waypoint at Crosshair", "Save the targeted loaded dungeon block as a personal waypoint.") {
        INSTANCE.addAtCrosshair()
    },
    ModuleAction("Rename Most Recent Waypoint", "Rename the most recently created personal waypoint.") {
        INSTANCE.renameMostRecent()
    },
    ModuleAction("Update Most Recent at Crosshair", "Move, rename, and recolor the most recent personal waypoint.") {
        INSTANCE.updateMostRecentAtCrosshair()
    },
    ModuleAction("Remove Most Recent Waypoint", "Remove the most recently created personal waypoint.") {
        INSTANCE.removeMostRecent()
    },
    ModuleAction("List Personal Waypoints", "Show IDs and names so an existing personal waypoint can be edited below.") {
        INSTANCE.listPersonal()
    },
    ModuleAction("Rename Selected Personal Waypoint", "Rename the personal waypoint ID set below.") {
        INSTANCE.renameSelected()
    },
    ModuleAction("Update Selected at Crosshair", "Move, rename, and recolor the selected personal waypoint.") {
        INSTANCE.updateSelectedAtCrosshair()
    },
    ModuleAction("Remove Selected Personal Waypoint", "Remove the personal waypoint ID set below.") {
        INSTANCE.removeSelected()
    },
    ModuleAction("Hide Selected Built-in Waypoint", "Hide the built-in entry whose ID is set below.") {
        INSTANCE.hideDefault()
    },
    ModuleAction("Replace Selected Built-in at Crosshair", "Override the selected built-in location with the targeted block.") {
        INSTANCE.replaceDefaultAtCrosshair()
    },
    ModuleAction("Restore Selected Built-in Waypoint", "Clear the selected built-in entry's local override.") {
        INSTANCE.restoreDefault()
    },
    ModuleAction("Show Catalog Status", "Report built-in catalog coverage and local persistence status.") {
        INSTANCE.showCatalogStatus()
    },
    ModuleAction("Edit Current Room Waypoints", "Edit, add, or reset markers in the detected floor and room cells.") {
        INSTANCE.openCurrentRoomEditor()
    },
    ModuleAction("Copy Waypoints JSON", "Copy personal markers, local overrides, and confirmed collection state.") {
        INSTANCE.exportWaypoints()
    },
    ModuleAction("Import Waypoints JSON", "Merge a bounded, validated waypoint package from the clipboard.") {
        INSTANCE.importWaypoints()
    },
) {
    companion object {
        @JvmField
        val INSTANCE = SecretWaypointsModule(Settings())

        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val WHITESPACE = Regex("\\s+")

        private const val MAX_CLICK_FEEDBACK_TARGETS = 16
        private const val MAX_CLICK_DIAGNOSTIC_EVENTS_PER_TICK = 16
        private const val MAX_WAYPOINT_STATUS_LENGTH = 900
        private const val LEVER_SCAN_RADIUS = 8
        private const val LEVER_SCAN_VERTICAL_RADIUS = 6
        private const val LEVER_SCAN_PERIOD_TICKS = 10L
    }

    private val roomCoreScanner = OdinDungeonMapCoreScanner()
    private val collectionTracker = SecretWaypointCollectionTracker()
    private val clickedHighlights = LinkedHashMap<String, ClickHighlight>()
    private val recentlyChimedMarkers = LinkedHashMap<String, Long>()
    private val pendingPickups = LinkedHashMap<String, PendingPickup>()
    private val collectionTargets = LinkedHashMap<String?, Target>()
    private var cachedLeverCandidates: List<Target> = emptyList()
    private var cachedLeverRoomKey: String? = null
    private var lastLeverScanTick: Long = Long.MIN_VALUE
    @Volatile private var targets: List<Target> = emptyList()
    private var lastLevel: ClientLevel? = null
    private var activeRunKey: String? = null
    private var currentRoomCells: List<RoomCell> = emptyList()
    private var roomTemplateRetryTicks = 0
    private var currentRoomRotation = -1
    private var roomTemplateKey: String? = null
    private var roomTemplateResolved = false
    private var missingRoomReadings = 0
    private var previousAttack = false
    private var previousUse = false
    private var lastPersonalId: String? = null
    private var lastChimeAtMillis = 0L
    private var clickDiagnosticTicks = 0
    private var clickDiagnosticEventTick = Long.MIN_VALUE
    private var clickDiagnosticEventsThisTick = 0
    private var clickDiagnosticSuppressedEvents = 0
    private var lastWaypointDiagnosticState = ""
    private var lastWaypointDiagnosticTick = Long.MIN_VALUE
    private var lastKnownClientTick = 0L
    private var lastWorldWaypointBoxSubmissions = 0
    private var lastWorldClickBoxSubmissions = 0
    private var lastHudLabelSubmissions = 0

    init {
        group(
            SettingGroup.switched(
                "Waypoints", settings.enabledMarkers, settings.name, settings.personalId, settings.defaultId,
                settings.range, settings.personalColor, settings.defaultColor, settings.keepCollected,
            ),
            SettingGroup.switched(
                "Live Markers", settings.liveMarkers, settings.chestCandidates, settings.droppedItems,
                settings.essenceDrops, settings.batCandidates, settings.redstoneKeys, settings.leverCandidates,
            ),
        )
        SecretWaypointStore.load()
    }

    /** Keep the former persisted toggle without presenting a separate Item Frames category. */
    override fun isSettingVisible(setting: Setting): Boolean = setting !== settings.itemFrameCandidates

    override fun isActive(): Boolean = isEnabled() && DungeonContextTracker.inDungeon()

    override fun inactiveReason(): String? =
        if (isEnabled() && !DungeonContextTracker.inDungeon()) "Waiting for a dungeon" else null

    fun hasRuntimeConsumer(): Boolean = SecretWaypointDiagnosticsPolicy.hasRuntimeConsumer(
        isEnabled(), SecretClickedModule.INSTANCE.isEnabled(), DungeonContextTracker.inDungeon(),
    )

    /** Keeps the enabled waypoint trace alive while the dungeon-only runtime is waiting for context. */
    internal fun tickAutomaticDiagnostics(clientTick: Long) {
        lastKnownClientTick = clientTick
        if (!SecretWaypointDiagnosticsPolicy.shouldRunDiagnosticsOnlyTick(
                isEnabled(), SecretClickedModule.INSTANCE.isEnabled(), hasRuntimeConsumer(),
            )
        ) return
        reportAutomaticDiagnostics()
    }

    private fun runtimeActive(): Boolean = hasRuntimeConsumer()

    /** Call from the client tick; only already-loaded room data is used to resolve authored markers. */
    fun tick() {
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (SecretClickedModule.INSTANCE.isEnabled()) logClickDiagnosticsStatus(false, "tick")
        if (isEnabled() || SecretClickedModule.INSTANCE.isEnabled()) reportAutomaticDiagnostics()
        if (level !== lastLevel) {
            lastLevel = level
            currentRoomCells = emptyList()
            collectionTracker.beginRun(null)
            activeRunKey = null
            collectionTargets.clear()
            cachedLeverCandidates = emptyList()
            cachedLeverRoomKey = null
            lastLeverScanTick = Long.MIN_VALUE
            missingRoomReadings = 0
            roomCoreScanner.reset()
            clearMaterializedRoom()
            clearClickFeedback(client)
            targets = emptyList()
        }
        if (!runtimeActive() || level == null || player == null) {
            collectionTracker.beginRun(null)
            activeRunKey = null
            collectionTargets.clear()
            clearMaterializedRoom()
            currentRoomCells = emptyList()
            cachedLeverCandidates = emptyList()
            cachedLeverRoomKey = null
            lastLeverScanTick = Long.MIN_VALUE
            missingRoomReadings = 0
            clearClickFeedback(client)
            publishTargets(emptyList())
            return
        }
        val reading = DungeonMapRoomDetector.current()
        if (reading == null || !reading.hasCurrentCell()) {
            val retainedRoom = missingRoomReadings < 20 && level === lastLevel
                && currentRoomCells.isNotEmpty() && insideRoom(player.getX(), player.getZ(), currentRoomCells)
            if (retainedRoom) {
                missingRoomReadings++
                for (key in collectionTracker.tick()) onMarkerCollected(key, collectionTargets[key], false)
                if (SecretClickedModule.INSTANCE.isEnabled()) updatePendingPickups()
                return
            }
            missingRoomReadings = 0
            clearMaterializedRoom()
            currentRoomCells = emptyList()
            cachedLeverCandidates = emptyList()
            cachedLeverRoomKey = null
            lastLeverScanTick = Long.MIN_VALUE
            clearClickFeedback(client)
            publishTargets(emptyList())
            return
        }
        missingRoomReadings = 0
        val roomCells = roomCells(reading)
        currentRoomCells = roomCells
        val roomKey = roomKey(reading, roomCells)
        val currentFloor = DungeonContextTracker.currentFloor()
        val readingMapId = reading.mapId
        val nextRunKey = if (currentFloor == null || readingMapId == null) null
            else "${System.identityHashCode(level)}|${currentFloor.displayName()}|${readingMapId.id()}"
        if (activeRunKey != nextRunKey) {
            activeRunKey = nextRunKey
            collectionTracker.beginRun(nextRunKey)
            collectionTargets.clear()
            clickedHighlights.clear()
            recentlyChimedMarkers.clear()
            pendingPickups.clear()
        }
        updateRoomTemplateMarkers(client, reading, roomCells, roomKey, currentFloor)
        for (key in collectionTracker.tick()) onMarkerCollected(key, collectionTargets[key], false)
        if (currentFloor != null && SecretWaypointCollectionRules.confirmsAllRoomSecrets(
                DungeonMapRoomDetector.currentRoomClearStatus(),
            )
        ) confirmRoomMarkers(currentFloor, roomCells)
        collectionTargets.clear()
        val next = ArrayList<Target>()
        if (settings.enabledMarkers.value() || SecretClickedModule.INSTANCE.isEnabled())
            next.addAll(personalAndDefaultTargets(reading, roomCells))
        if (settings.liveMarkers.value() || SecretClickedModule.INSTANCE.isEnabled())
            next.addAll(liveRoomCandidates(client, roomCells, roomKey))
        publishTargets(filterCollectedTargets(next))
        if (SecretClickedModule.INSTANCE.isEnabled()) {
            updatePendingPickups()
            checkClick(client)
        } else clearClickFeedback(client)
    }

    override fun onEnable() {
        updateWaypointDiagnosticsEnabled()
        reportAutomaticDiagnostics("enabled", force = true)
    }

    override fun onDisable() {
        reportAutomaticDiagnostics("disabled", force = true)
        updateWaypointDiagnosticsEnabled()
        if (!SecretClickedModule.INSTANCE.isEnabled()) clearClickFeedback(Minecraft.getInstance())
        clearMaterializedRoom()
    }

    /** Resolves a room's bounded catalog only after its exact Odin identity and corner are available. */
    private fun updateRoomTemplateMarkers(
        client: Minecraft,
        reading: DungeonMapRoomDetector.Reading?,
        roomCells: List<RoomCell>,
        roomKey: String,
        floor: DungeonFloor?,
    ) {
        if (floor == null || reading == null || roomCells.isEmpty()) {
            clearMaterializedRoom()
            return
        }
        val currentCell = reading.currentCell
        if (currentCell == null) {
            clearMaterializedRoom()
            return
        }
        val key = "${floor.displayName()}|$roomKey"
        if (key != roomTemplateKey) {
            roomTemplateKey = key
            roomTemplateResolved = false
            roomTemplateRetryTicks = 0
            currentRoomRotation = -1
            SecretWaypointStore.setRuntimeDefaults(emptyList())
        }
        if (roomTemplateResolved || roomTemplateRetryTicks++ % 20 != 0) return

        val physicalCells = ArrayList<DungeonGridRules.Cell>(roomCells.size)
        for (roomCell in roomCells) {
            val cell = DungeonGridRules.cellAtOrigin(roomCell.baseX, roomCell.baseZ)
            if (cell == null || cell.componentX != roomCell.gridX || cell.componentZ != roomCell.gridZ) return
            physicalCells.add(cell)
        }
        if (physicalCells.none { cell ->
                cell.componentX == currentCell.componentX && cell.componentZ == currentCell.componentZ
            }
        ) return
        val core = roomCoreScanner.roomCoreAt(client, currentCell)
        val room = core?.data
        if (room == null || core!!.highestBlock <= 0) return
        val orientation = DungeonRoomOrientationDetector.resolve(
            client, physicalCells, room.shape(), room.type(), core.highestBlock,
        ) ?: return
        currentRoomRotation = orientation.rotation
        val templates = SecretWaypointStore.roomTemplates()
        val materialized = SecretWaypointRoomCatalog.materialize(
            floor.displayName(), physicalCells, room.name(), orientation, templates,
        )
        SecretWaypointStore.setRuntimeDefaults(materialized)
        val hasRoomTemplates = templates.any { marker -> marker.roomName?.equals(room.name(), ignoreCase = true) == true }
        // If authored data exists but no positions land inside the detected footprint, keep retrying;
        // the room core or orientation may still be settling after entry.
        roomTemplateResolved = materialized.isNotEmpty() || !hasRoomTemplates
    }

    private fun clearMaterializedRoom() {
        if (roomTemplateKey != null || !roomTemplateResolved) SecretWaypointStore.setRuntimeDefaults(emptyList())
        roomTemplateKey = null
        roomTemplateResolved = false
        roomTemplateRetryTicks = 0
        currentRoomRotation = -1
    }

    fun render(context: LevelRenderContext) {
        lastWorldWaypointBoxSubmissions = 0
        lastWorldClickBoxSubmissions = 0
        if (!runtimeActive()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        if (client.level == null) return
        val camera = client.gameRenderer.mainCamera().position()
        val pose: PoseStack = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        var drew = false
        val visibleTargets = if (isEnabled()) targets else emptyList()
        for (target in visibleTargets) {
            if (target.waypoint) {
                if (!settings.enabledMarkers.value() || !SecretWaypointCategoryRules.visible(target.id, selection())) continue
            } else if (!settings.liveMarkers.value()
                || !SecretWaypointCategoryRules.candidateVisible(target.label, selection())
            ) continue
            if (player.distanceToSqr(target.x, target.y, target.z)
                > settings.range.intValue().toDouble() * settings.range.intValue()
            ) continue
            val color = target.color
            val half = if (isBlockMarker(target.type)) 0.48 else 0.18
            val size = half * 2.0
            EspRenderer.renderBox(
                pose, buffers, target.x - half - camera.x, target.y - half - camera.y,
                target.z - half - camera.z, size, size, size, withAlpha(color, 48), color, 1.5f, true,
            )
            lastWorldWaypointBoxSubmissions++
            drew = true
        }
        val nowNanos = System.nanoTime()
        val clickModule = SecretClickedModule.INSTANCE
        val bossPhase = DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase()
        val visibleHighlights = if (SecretClickedModule.boxes.value()
            && (!bossPhase || SecretClickedModule.boxesInBoss.value()) && clickModule.isEnabled()
        ) clickedHighlights.values else emptyList()
        for (click in visibleHighlights) {
            val target = click.target
            if (isItemMarker(target.type) && !SecretClickedModule.itemBoxes.value()) continue
            if (SecretWaypointClickFeedbackRules.expired(nowNanos, click.deadlineNanos)
                || player.distanceToSqr(target.x, target.y, target.z)
                > settings.range.intValue().toDouble() * settings.range.intValue()
            ) continue
            val selectedColor = if (click.locked) SecretClickedModule.lockedColor else SecretClickedModule.boxColor
            val outline = selectedColor.argb()
            val style = SecretClickedModule.boxStyle.value()
            val fill = style == "FILLED" || style == "FILLED_OUTLINE"
            val drawOutline = style == "OUTLINE" || style == "FILLED_OUTLINE"
            val bounds = click.bounds
            EspRenderer.renderBox(
                pose, buffers, bounds.minX - camera.x, bounds.minY - camera.y, bounds.minZ - camera.z,
                bounds.maxX - bounds.minX, bounds.maxY - bounds.minY, bounds.maxZ - bounds.minZ,
                if (fill) withAlpha(outline, minOf(selectedColor.alpha(), 36)) else 0,
                if (drawOutline) outline else 0, 2.6f, SecretClickedModule.depthCheck.value(),
            )
            lastWorldClickBoxSubmissions++
            drew = true
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        lastHudLabelSubmissions = 0
        if (!isActive() || !settings.enabledMarkers.value() && !settings.liveMarkers.value()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        for (target in targets) {
            if (target.waypoint) {
                if (!settings.enabledMarkers.value() || !SecretWaypointCategoryRules.visible(target.id, selection())) continue
            } else if (!settings.liveMarkers.value()
                || !SecretWaypointCategoryRules.candidateVisible(target.label, selection())
            ) continue
            if (player.distanceToSqr(target.x, target.y, target.z)
                > settings.range.intValue().toDouble() * settings.range.intValue()
            ) continue
            ProjectedLabelRenderer.drawFloatingText(
                graphics, client.gameRenderer.mainCamera(), client.font,
                Vec3(target.x, target.y + 0.55, target.z), target.label, target.color, 0.9f,
            )
            lastHudLabelSubmissions++
        }
    }

    fun catalogStatus(): String? = SecretWaypointStore.catalogStatus()

    /** Automatic bounded status for the waypoint feature, independent of Secret Clicked diagnostics. */
    private fun reportAutomaticDiagnostics(reason: String? = null, force: Boolean = false) {
        val client = Minecraft.getInstance()
        val tick = lastKnownClientTick
        val waypointTargets = targets.count { it.waypoint }
        val state = (
            "enabled=${isEnabled()} inDungeon=${DungeonContextTracker.inDungeon()} " +
                "levelPresent=${client.level != null} playerPresent=${client.player != null} " +
                "floor=${DungeonContextTracker.currentFloor()?.displayName() ?: "unknown"} " +
                "roomCells=${currentRoomCells.size} templateResolved=$roomTemplateResolved rotation=$currentRoomRotation " +
                "waypointTargets=$waypointTargets liveCandidates=${targets.size - waypointTargets} " +
                "showWaypoints=${settings.enabledMarkers.value()} showLive=${settings.liveMarkers.value()} " +
                "range=${settings.range.intValue()} clickEnabled=${SecretClickedModule.INSTANCE.isEnabled()} " +
                "clickHighlights=${clickedHighlights.size} pendingPickups=${pendingPickups.size} " +
                "worldWaypointBoxes=$lastWorldWaypointBoxSubmissions worldClickBoxes=$lastWorldClickBoxSubmissions " +
                "hudLabels=$lastHudLabelSubmissions"
            ).take(MAX_WAYPOINT_STATUS_LENGTH)
        val previousTick = lastWaypointDiagnosticTick
        val changed = state != lastWaypointDiagnosticState
        if (!SecretWaypointDiagnosticsPolicy.shouldWriteStatus(previousTick, tick, changed, force)) return

        lastWaypointDiagnosticState = state
        lastWaypointDiagnosticTick = tick
        val prefix = reason?.let { "reason=$it " } ?: ""
        GeilerAddonsLog.write(Category.F7, name(), tick, "STATUS $prefix$state".take(MAX_WAYPOINT_STATUS_LENGTH + 48))
    }

    private fun openCurrentRoomEditor() {
        val floor = DungeonContextTracker.currentFloor()
        val reading = DungeonMapRoomDetector.current()
        if (floor == null || reading == null || !reading.hasCurrentCell()) {
            message("Enter a detected dungeon room first")
            return
        }
        val rooms = roomCells(reading).map { cell -> roomId(cell.gridX, cell.gridZ) }.distinct()
        if (rooms.isEmpty()) { message("The current room cells are unavailable"); return }
        val client = Minecraft.getInstance()
        client.gui.setScreen(SecretWaypointEditorScreen(client.gui.screen(), floor.displayName(), rooms))
    }

    private fun exportWaypoints() {
        val encoded = SecretWaypointStore.exportPackage()
        if (encoded == null) { message("Waypoint JSON exceeds the clipboard size limit"); return }
        Minecraft.getInstance().keyboardHandler.setClipboard(encoded)
        message("Copied waypoint JSON to the clipboard")
    }

    private fun importWaypoints() {
        val result = SecretWaypointStore.importPackage(Minecraft.getInstance().keyboardHandler.getClipboard())
        if (!result.success()) { message(result.error() ?: "Waypoint import failed"); return }
        message("Imported ${result.personal()} personal marker(s), ${result.overrides()} override(s), " +
            "and ${result.collected()} collected state(s)")
    }

    private fun showCatalogStatus() {
        message("${SecretWaypointStore.catalogStatus()} · local edits " +
            if (SecretWaypointStore.canPersistChanges()) "available" else "blocked")
    }

    private fun personalAndDefaultTargets(
        reading: DungeonMapRoomDetector.Reading,
        roomCells: List<RoomCell>,
    ): List<Target> {
        val floor = DungeonContextTracker.currentFloor() ?: return emptyList()
        val result = ArrayList<Target>()
        val selectedTypes = selection()
        for (cell in roomCells) {
            val roomId = roomId(cell.gridX, cell.gridZ)
            for (waypoint in SecretWaypointStore.visibleFor(floor.displayName(), roomId)) {
                val collectionKey = runtimeMarkerKey(waypoint)
                val markerType = waypointType(waypoint)
                val target = Target(
                    waypoint.id(), waypoint.name(), waypoint.x() + 0.5, waypoint.y() + 0.5,
                    waypoint.z() + 0.5, waypoint.color(), null, true, markerType, collectionKey,
                )
                if (collectionKey != null) collectionTargets[collectionKey] = target
                if (!SecretWaypointCategoryRules.visible(waypoint.id(), selectedTypes)) continue
                if (!settings.keepCollected.value() && collectionKey != null
                    && (collectionTracker.isCollected(collectionKey) || collectionTracker.isPending(collectionKey))
                ) continue
                result.add(target)
            }
        }
        return result
    }

    /** Builds bounded live candidates from this room's already-loaded chunks and entities. */
    private fun liveRoomCandidates(client: Minecraft?, roomCells: List<RoomCell>?, roomKey: String): List<Target> {
        if (roomCells.isNullOrEmpty()) return emptyList()
        val level = client?.level ?: return emptyList()
        val player = client.player ?: return emptyList()
        val result = ArrayList<Target>()
        val visitedChunks = HashSet<Long>()
        for (cell in roomCells) {
            val minChunkX = Math.floorDiv(cell.baseX, 16)
            val maxChunkX = Math.floorDiv(cell.baseX + DungeonGridRules.ROOM_SIZE - 1, 16)
            val minChunkZ = Math.floorDiv(cell.baseZ, 16)
            val maxChunkZ = Math.floorDiv(cell.baseZ + DungeonGridRules.ROOM_SIZE - 1, 16)
            for (chunkX in minChunkX..maxChunkX) for (chunkZ in minChunkZ..maxChunkZ) {
                val chunkKey = (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xFFFFFFFFL)
                if (!visitedChunks.add(chunkKey)) continue
                val chunk: LevelChunk = level.getChunkSource().getChunk(chunkX, chunkZ, false) ?: continue
                for (position in chunk.blockEntities.keys) {
                    if (!insideRoom(position.getX() + 0.5, position.getZ() + 0.5, roomCells)) continue
                    val state = chunk.getBlockState(position)
                    if (state.`is`(Blocks.CHEST) || state.`is`(Blocks.TRAPPED_CHEST)) {
                        result.add(
                            liveCandidate(
                                "chest:${position.asLong()}", "Chest candidate", position.getX() + 0.5,
                                position.getY() + 0.5, position.getZ() + 0.5, null,
                                SecretWaypointClickFeedbackRules.Type.CHEST,
                            ),
                        )
                    }
                }
            }
        }

        if (cachedLeverRoomKey != roomKey) {
            cachedLeverRoomKey = roomKey
            cachedLeverCandidates = emptyList()
            lastLeverScanTick = Long.MIN_VALUE
        }
        val gameTick = level.gameTime
        if (lastLeverScanTick == Long.MIN_VALUE || gameTick - lastLeverScanTick >= LEVER_SCAN_PERIOD_TICKS) {
            lastLeverScanTick = gameTick
            val levers = ArrayList<Target>()
            val playerPosition = player.blockPosition()
            val minY = maxOf(level.minY, playerPosition.getY() - LEVER_SCAN_VERTICAL_RADIUS)
            val maxY = minOf(level.maxY - 1, playerPosition.getY() + LEVER_SCAN_VERTICAL_RADIUS)
            for (y in minY..maxY) for (x in (playerPosition.getX() - LEVER_SCAN_RADIUS)..(playerPosition.getX() + LEVER_SCAN_RADIUS))
                for (z in (playerPosition.getZ() - LEVER_SCAN_RADIUS)..(playerPosition.getZ() + LEVER_SCAN_RADIUS)) {
                    if (!insideRoom(x + 0.5, z + 0.5, roomCells)) continue
                    val position = BlockPos(x, y, z)
                    if (!level.hasChunkAt(position) || !level.getBlockState(position).`is`(Blocks.LEVER)) continue
                    levers.add(
                        liveCandidate(
                            "lever:${position.asLong()}", "Lever candidate", position.getX() + 0.5,
                            position.getY() + 0.5, position.getZ() + 0.5, null,
                            SecretWaypointClickFeedbackRules.Type.LEVER,
                        ),
                    )
                }
            cachedLeverCandidates = java.util.List.copyOf(levers)
        }
        result.addAll(cachedLeverCandidates)

        val bounds = roomBounds(roomCells, level.minY, level.maxY)
        for (bat in level.getEntities(
            EntityTypeTest.forClass(Bat::class.java), bounds,
            { entity -> !entity.isRemoved() && entity.isAlive() && insideRoom(entity.getX(), entity.getZ(), roomCells) },
        )) {
            result.add(
                liveCandidate("bat:${bat.getUUID()}", "Bat candidate", bat.getX(), bat.getY(), bat.getZ(),
                    bat.getUUID(), SecretWaypointClickFeedbackRules.Type.BAT),
            )
        }
        for (item in level.getEntities(
            EntityTypeTest.forClass(ItemEntity::class.java), bounds,
            { entity -> !entity.isRemoved() && entity.isAlive() && insideRoom(entity.getX(), entity.getZ(), roomCells) },
        )) {
            val normalizedName = ChatText.stripForMatch(item.getItem().getHoverName().getString())
                .lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim().replace(WHITESPACE, " ")
            val label = SecretWaypointCategoryRules.itemCandidateLabel(normalizedName, selection()) ?: continue
            val type = SecretWaypointClickFeedbackRules.fromCandidateLabel(label)
            if (type == SecretWaypointClickFeedbackRules.Type.UNKNOWN) continue
            result.add(liveCandidate("item:${item.getUUID()}", label, item.getX(), item.getY(), item.getZ(), item.getUUID(), type))
        }
        for (frame in level.getEntities(
            EntityTypeTest.forClass(ItemFrame::class.java), bounds,
            { entity -> !entity.isRemoved() && entity.isAlive() && !entity.getItem().isEmpty()
                && insideRoom(entity.getX(), entity.getZ(), roomCells) },
        )) {
            result.add(
                liveCandidate("item-frame:${frame.getUUID()}", "Item frame candidate",
                    frame.getX(), frame.getY(), frame.getZ(), frame.getUUID(),
                    SecretWaypointClickFeedbackRules.Type.ITEM_FRAME),
            )
        }
        for (target in result) collectionTargets[target.collectionKey] = target
        return java.util.List.copyOf(result)
    }

    private fun liveCandidate(
        id: String, label: String, x: Double, y: Double, z: Double, entityId: UUID?,
        type: SecretWaypointClickFeedbackRules.Type,
    ): Target {
        val collectionKey = "candidate:live:$id" + (if (entityId == null) "" else ":$entityId")
        return Target("live:$id", label, x, y, z, 0xFF5FFF87.toInt(), entityId, false, type, collectionKey)
    }

    private fun roomBounds(roomCells: List<RoomCell>, minY: Int, maxY: Int): AABB {
        var minX = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        for (cell in roomCells) {
            minX = minOf(minX, cell.baseX)
            minZ = minOf(minZ, cell.baseZ)
            maxX = maxOf(maxX, cell.baseX + DungeonGridRules.ROOM_SIZE)
            maxZ = maxOf(maxZ, cell.baseZ + DungeonGridRules.ROOM_SIZE)
        }
        return AABB(minX - 1.0, minY.toDouble(), minZ - 1.0, maxX + 1.0, maxY.toDouble(), maxZ + 1.0)
    }

    private fun filterCollectedTargets(candidates: List<Target>): List<Target> {
        if (settings.keepCollected.value()) return java.util.List.copyOf(candidates)
        return candidates.filter { target ->
            target.collectionKey == null || !collectionTracker.isCollected(target.collectionKey)
                && !collectionTracker.isPending(target.collectionKey)
        }
    }

    /** Green room completion confirms every authored secret marker in this physical room. */
    private fun confirmRoomMarkers(floor: DungeonFloor?, roomCells: List<RoomCell>?) {
        if (floor == null || roomCells.isNullOrEmpty()) return
        for ((key, target) in collectionTargets) {
            if (!insideRoom(target.x, target.z, roomCells)
                || SecretWaypointRoomCatalog.markerCategory(target.id) == null) continue
            if (key != null && collectionTracker.confirm(key)) onMarkerCollected(key, target, false)
        }
    }

    private fun runtimeMarkerKey(waypoint: SecretWaypointStore.Waypoint?): String? {
        if (waypoint == null || activeRunKey == null || currentRoomRotation < 0) return null
        val identity = SecretWaypointRoomCatalog.roomInstanceIdentity(waypoint.id())
        val room = identity?.anchorRoomId() ?: waypoint.roomId()
        val floor = DungeonFloor.parse(waypoint.floor())
        return "$activeRunKey|${floor?.displayName() ?: waypoint.floor()}|$room|r$currentRoomRotation|${waypoint.id()}"
    }

    private fun waypointType(waypoint: SecretWaypointStore.Waypoint): SecretWaypointClickFeedbackRules.Type {
        var category = SecretWaypointRoomCatalog.markerCategory(waypoint.id())
        if (category == null) {
            val fallback = SecretWaypointCategoryRules.categoryOf(waypoint.id())
            category = fallback?.name?.lowercase(Locale.ROOT)
        }
        if (category == null) return SecretWaypointClickFeedbackRules.fromCandidateLabel(waypoint.name())
        return when (category) {
            "chest" -> SecretWaypointClickFeedbackRules.Type.CHEST
            "item" -> SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM
            "essence" -> SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP
            "bat" -> SecretWaypointClickFeedbackRules.Type.BAT
            "redstone" -> SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY
            "lever" -> SecretWaypointClickFeedbackRules.Type.LEVER
            else -> SecretWaypointClickFeedbackRules.Type.WAYPOINT
        }
    }

    private fun isBlockMarker(type: SecretWaypointClickFeedbackRules.Type): Boolean =
        type == SecretWaypointClickFeedbackRules.Type.CHEST || type == SecretWaypointClickFeedbackRules.Type.LEVER

    private fun onMarkerCollected(key: String?, target: Target?, playSound: Boolean) {
        if (target != null && SecretClickedModule.INSTANCE.isEnabled()) {
            addClickHighlight(target, false, System.nanoTime(), null)
        }
        if (playSound) {
            if (consumeRecentClickChime(key)) logChimeOutcome(target, "suppressed_recent_click")
            else playChime(target)
        }
    }

    /** Called for the server's localized locked-chest rejection; the marker returns after 11 ticks. */
    fun onChatMessage(rawMessage: String?) {
        if (!runtimeActive() || rawMessage == null) return
        val normalized = ChatText.stripForMatch(rawMessage).trim().replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
        if (normalized == "that chest is locked!") {
            val key = collectionTracker.onLockedChestMessage(
                SecretWaypointClickFeedbackRules.confirmationWindowTicks(
                    SecretClickedModule.feedbackWindowMillis.value().toInt(),
                ),
            )
            val target = key?.let { collectionTargets[it] }
            if (target != null && SecretClickedModule.INSTANCE.isEnabled()) {
                addClickHighlight(target, true, System.nanoTime(), null)
            }
            return
        }
        if (normalized == "you found a secret!" || normalized.startsWith("you found a secret ")) {
            val key = collectionTracker.confirmLatestBlockInteraction()
            if (key != null) onMarkerCollected(key, collectionTargets[key], true)
        }
    }

    /** Confirms the exact pending chest marker on the server's positive open-count block event. */
    fun onSecretBlockEvent(packet: ClientboundBlockEventPacket?) {
        if (!runtimeActive() || packet == null || packet.getB0() != 1 || packet.getB1() <= 0) return
        if (packet.getBlock() != Blocks.CHEST && packet.getBlock() != Blocks.TRAPPED_CHEST) return
        confirmPendingBlockAt(SecretWaypointClickFeedbackRules.Type.CHEST, packet.getPos())
    }

    /** Confirms a lever marker only on a server-observed unpowered-to-powered state transition. */
    fun onSecretBlockUpdate(position: BlockPos?, oldState: BlockState?, newState: BlockState?) {
        if (!runtimeActive() || position == null || oldState == null || newState == null
            || !oldState.`is`(Blocks.LEVER) || !newState.`is`(Blocks.LEVER)
            || !oldState.hasProperty(BlockStateProperties.POWERED)
            || !newState.hasProperty(BlockStateProperties.POWERED)
            || oldState.getValue(BlockStateProperties.POWERED)
            || !newState.getValue(BlockStateProperties.POWERED)
        ) return
        confirmPendingBlockAt(SecretWaypointClickFeedbackRules.Type.LEVER, position)
    }

    private fun confirmPendingBlockAt(type: SecretWaypointClickFeedbackRules.Type, position: BlockPos?) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread() || !DungeonContextTracker.inDungeon() || position == null) return
        for ((key, target) in collectionTargets) {
            if (key == null || target.type != type || !collectionTracker.isPending(key)
                || kotlin.math.floor(target.x).toInt() != position.x
                || kotlin.math.floor(target.y).toInt() != position.y
                || kotlin.math.floor(target.z).toInt() != position.z
            ) continue
            if (collectionTracker.confirmPendingBlockInteraction(key)) onMarkerCollected(key, target, true)
            return
        }
    }

    /** Confirms one catalogued item marker from the authoritative server pickup packet. */
    fun onSecretPickupPacket(packet: ClientboundTakeItemEntityPacket?) {
        val client = Minecraft.getInstance()
        if (!runtimeActive() || packet == null) return
        val level = client.level ?: return
        val player = client.player ?: return
        if (packet.getPlayerId() != player.id) return
        val picked = level.getEntity(packet.getItemId())
        val itemEntity = picked is ItemEntity
        var type = if (itemEntity) itemType(picked as ItemEntity) else SecretWaypointClickFeedbackRules.Type.UNKNOWN
        var match: CollectionMatch? = null
        if (itemEntity && SecretWaypointClickFeedbackRules.confirmsPickupPacket(
                packet.getPlayerId(), player.id, true, type,
            )
        ) {
            val pending = pendingPickupForEntity(packet.getItemId(), type)
            match = pending?.let { collectionMatch(it.target) }
            if (match == null) {
                val item = picked as ItemEntity
                match = nearestCollectionMarker(type, item.getX(), item.getY(), item.getZ(), 8.0)
            }
        } else {
            // Vanilla can remove the item before a delayed pickup notification is handled. The
            // click-time record retains the exact entity and target long enough to resolve that packet.
            val pending = pendingPickupForEntity(packet.getItemId(), null)
            if (pending != null && SecretWaypointClickFeedbackRules.confirmsPendingItemPickup(
                    packet.getPlayerId(), player.id, packet.getItemId(), pending.entity.getId(),
                    pending.type, pending.target?.collectionKey != null,
                )
            ) {
                type = pending.type
                match = collectionMatch(pending.target)
            }
        }
        val resolvedMatch = match
        if (resolvedMatch != null && collectionTracker.confirm(resolvedMatch.key)) {
            val pickedEntityId = packet.getItemId()
            onMarkerCollected(resolvedMatch.key, resolvedMatch.target, true)
            pendingPickups.entries.removeIf { entry -> entry.value.entity.id == pickedEntityId }
        }
    }

    /** Confirms a bat marker only on the authoritative bat-death sound, never on entity unload. */
    fun onBatDeathSound(soundId: String?, x: Double, y: Double, z: Double) {
        if (!runtimeActive() || !SecretWaypointClickFeedbackRules.confirmsBatDeathSound(soundId)) return
        var match = nearestCollectionMarker(SecretWaypointClickFeedbackRules.Type.BAT, x, y, z, 10.0)
        if (match == null) match = nearestPendingBatTarget(x, z, 10.0)
        if (match != null && collectionTracker.confirm(match.key)) {
            onMarkerCollected(match.key, match.target, true)
            pendingPickups.entries.removeIf { entry ->
                entry.value.type == SecretWaypointClickFeedbackRules.Type.BAT
                    && (
                        kotlin.math.abs(entry.value.entity.getX() - x)
                            + kotlin.math.abs(entry.value.entity.getZ() - z) < 10.0
                        )
            }
        }
    }

    private fun itemType(item: ItemEntity): SecretWaypointClickFeedbackRules.Type {
        val normalized = ChatText.stripForMatch(item.getItem().getHoverName().getString())
            .lowercase(Locale.ROOT).replace(NON_ALNUM, " ").trim().replace(WHITESPACE, " ")
        val label = SecretWaypointCategoryRules.itemCandidateLabel(
            normalized, SecretWaypointClickFeedbackRules.Selection(true, true, true, true, true, true),
        )
        return SecretWaypointClickFeedbackRules.fromCandidateLabel(label)
    }

    private fun nearestCollectionMarker(
        type: SecretWaypointClickFeedbackRules.Type, x: Double, y: Double, z: Double,
        maxHorizontalDistance: Double,
    ): CollectionMatch? {
        var nearest: CollectionMatch? = null
        var nearestDistance = Double.POSITIVE_INFINITY
        for ((key, target) in collectionTargets) {
            if (target.type != type || target.collectionKey == null || !insideRoom(x, z, currentRoomCells)) continue
            val distance = kotlin.math.abs(target.x - x) + kotlin.math.abs(target.z - z)
            if (distance >= maxHorizontalDistance || distance >= nearestDistance) continue
            nearest = CollectionMatch(key ?: continue, target)
            nearestDistance = distance
        }
        return nearest
    }

    private fun pendingPickupForEntity(
        entityId: Int, expectedType: SecretWaypointClickFeedbackRules.Type?,
    ): PendingPickup? {
        for (pending in pendingPickups.values) {
            if (pending.entity.id != entityId || pending.entity !is ItemEntity
                || expectedType != null && pending.type != expectedType
            ) continue
            return pending
        }
        return null
    }

    private fun nearestPendingBatTarget(x: Double, z: Double, maxHorizontalDistance: Double): CollectionMatch? {
        var nearest: CollectionMatch? = null
        var nearestDistance = Double.POSITIVE_INFINITY
        for ((_, pending) in pendingPickups) {
            val target = pending.target ?: continue
            if (pending.type != SecretWaypointClickFeedbackRules.Type.BAT || target.collectionKey == null
                || !insideRoom(x, z, currentRoomCells)
            ) continue
            val distance = kotlin.math.abs(target.x - x) + kotlin.math.abs(target.z - z)
            if (distance >= maxHorizontalDistance || distance >= nearestDistance) continue
            nearest = CollectionMatch(target.collectionKey!!, target)
            nearestDistance = distance
        }
        return nearest
    }

    private fun collectionMatch(target: Target?): CollectionMatch? =
        if (target == null || target.collectionKey == null) null
        else CollectionMatch(target.collectionKey!!, target)

    private fun checkClick(client: Minecraft) {
        if (!SecretClickedModule.INSTANCE.isEnabled()) return
        val player = client.player ?: return
        val attack = client.options.keyAttack.isDown()
        val use = client.options.keyUse.isDown()
        val pressed = attack && !previousAttack || use && !previousUse
        previousAttack = attack
        previousUse = use
        if (!pressed || client.gui.screen() != null) return
        val hit = client.hitResult
        val selectedTypes = selection()
        for (target in targets) {
            if (player.distanceToSqr(target.x, target.y, target.z)
                > settings.range.intValue().toDouble() * settings.range.intValue()
            ) continue
            val matched = (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK
            && kotlin.math.floor(target.x) == hit.blockPos.x.toDouble()
                && kotlin.math.floor(target.y) == hit.blockPos.y.toDouble()
                && kotlin.math.floor(target.z) == hit.blockPos.z.toDouble())
                || (hit is EntityHitResult && target.entityId != null
                && target.entityId == hit.entity.getUUID())
            if (!matched) continue
            val bossPhase = !DungeonContextTracker.isMobEspPhase()
            val action = SecretWaypointClickFeedbackRules.clickAction(
                target.type, selectedTypes, bossPhase, SecretClickedModule.allowSecretBlocksInBoss.value(),
            )
            if (action == SecretWaypointClickFeedbackRules.Action.IGNORE) {
                if (SecretWaypointClickFeedbackRules.selected(target.type, selectedTypes)
                    && SecretWaypointClickFeedbackRules.isSecretBlock(target.type) && bossPhase
                    && !SecretClickedModule.allowSecretBlocksInBoss.value()
                ) break
                continue
            }
            val nowNanos = System.nanoTime()
            val confirmationDeadline = SecretWaypointClickFeedbackRules.deadlineNanos(
                nowNanos, SecretClickedModule.feedbackWindowMillis.value().toInt(),
            )
            val key = feedbackKey(target)
            val clickedGeometry = feedbackGeometry(target, hit)
            addClickHighlight(target, false, nowNanos, clickedGeometry)
            if (SecretWaypointClickFeedbackRules.isSecretBlock(target.type) && target.collectionKey != null) {
                collectionTracker.beginBlockInteraction(
                    target.collectionKey, target.type, SecretWaypointClickFeedbackRules.confirmationWindowTicks(
                        SecretClickedModule.feedbackWindowMillis.value().toInt(),
                    ),
                )
                if (playChime(target)) {
                    recentlyChimedMarkers[target.collectionKey!!] = nowNanos + 2_000_000_000L
                    trimFeedbackEntries(recentlyChimedMarkers)
                }
            } else if (action == SecretWaypointClickFeedbackRules.Action.ACTIVATE_NOW) {
                pendingPickups.remove(key)
                if (playChime(target) && target.collectionKey != null) {
                    recentlyChimedMarkers[target.collectionKey!!] = nowNanos + 2_000_000_000L
                    trimFeedbackEntries(recentlyChimedMarkers)
                }
            } else if (hit is EntityHitResult) {
                val entity = hit.entity
                val itemCount = when (entity) {
                    is ItemEntity -> entity.getItem().count
                    is ItemFrame -> entity.getItem().count
                    else -> 0
                }
                val frameHadItem = entity is ItemFrame && !entity.getItem().isEmpty()
                pendingPickups[key] = PendingPickup(target.type, entity, target, itemCount, frameHadItem,
                    clickedGeometry, confirmationDeadline)
                trimFeedbackEntries(pendingPickups)
            }
            break
        }
    }

    private fun updatePendingPickups() {
        val nowNanos = System.nanoTime()
        val iterator = pendingPickups.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pending = entry.value
            val entity = pending.entity
            val gone = entity.isRemoved() || !entity.isAlive()
            val itemCountNow = when (entity) {
                is ItemEntity -> entity.getItem().count
                is ItemFrame -> entity.getItem().count
                else -> 0
            }
            val frameEmpty = pending.frameHadItem && entity is ItemFrame && entity.getItem().isEmpty()
            val confirmed = pending.type == SecretWaypointClickFeedbackRules.Type.ITEM_FRAME
                && SecretWaypointClickFeedbackRules.pickupConfirmed(
                    pending.type, gone, pending.itemCountBefore, itemCountNow, frameEmpty,
                )
            if (confirmed && !SecretWaypointClickFeedbackRules.expired(nowNanos, pending.deadlineNanos)) {
                playChime(pending.target)
                iterator.remove()
            } else if (SecretWaypointClickFeedbackRules.expired(nowNanos, pending.deadlineNanos)) {
                iterator.remove()
            }
        }
        clickedHighlights.entries.removeIf { entry ->
            SecretWaypointClickFeedbackRules.expired(nowNanos, entry.value.deadlineNanos)
        }
        recentlyChimedMarkers.entries.removeIf { entry -> SecretWaypointClickFeedbackRules.expired(nowNanos, entry.value) }
    }

    private fun selection() = SecretWaypointClickFeedbackRules.Selection(
        settings.chestCandidates.value(), settings.droppedItems.value(), settings.essenceDrops.value(),
        settings.batCandidates.value(), settings.redstoneKeys.value(), settings.leverCandidates.value(),
    )

    private fun feedbackKey(target: Target): String = (if (target.waypoint) "waypoint:" else "candidate:") + target.id +
        (target.entityId?.let { ":$it" } ?: "")

    private fun <T> trimFeedbackEntries(entries: LinkedHashMap<String, T>) {
        while (entries.size > MAX_CLICK_FEEDBACK_TARGETS) {
            val oldest = entries.keys.iterator()
            if (!oldest.hasNext()) break
            entries.remove(oldest.next())
        }
    }

    private fun addClickHighlight(target: Target?, locked: Boolean, nowNanos: Long, geometry: FeedbackGeometry?) {
        if (target == null) return
        val click = SecretClickedModule.INSTANCE
        val bossPhase = DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase()
        val resolvedGeometry = geometry ?: feedbackGeometry(target, null)
        val shouldShow = click.mayShowBox(isItemMarker(target.type), bossPhase)
        logClickDiagnosticEvent(
            "FEEDBACK targetType=${target.type.name} position=${targetPosition(target)} " +
                "geometrySource=${resolvedGeometry.source} ${boundsText(resolvedGeometry.bounds)} " +
                "locked=$locked outcome=${if (shouldShow) "stored" else "settings_gated"}",
        )
        if (!shouldShow) return
        val durationNanos = (SecretClickedModule.timeToStay.value() * 1_000_000_000f).toLong()
        clickedHighlights[feedbackKey(target)] = ClickHighlight(
            target, resolvedGeometry.bounds, resolvedGeometry.source, nowNanos + durationNanos, locked,
        )
        trimFeedbackEntries(clickedHighlights)
    }

    private fun feedbackGeometry(target: Target, hit: HitResult?): FeedbackGeometry {
        val client = Minecraft.getInstance()
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK
            && kotlin.math.floor(target.x).toInt() == hit.blockPos.x
            && kotlin.math.floor(target.y).toInt() == hit.blockPos.y
            && kotlin.math.floor(target.z).toInt() == hit.blockPos.z
        ) return blockFeedbackGeometry(client, hit.blockPos)
        if (hit is EntityHitResult && target.entityId != null
            && target.entityId == hit.entity.getUUID()
        ) return FeedbackGeometry(hit.entity.boundingBox, "entity_bounding_box")
        val previous = clickedHighlights[feedbackKey(target)]
        if (previous != null) return FeedbackGeometry(previous.bounds, previous.geometrySource)
        for (pending in pendingPickups.values) {
            if (pending.target != null && feedbackKey(target) == feedbackKey(pending.target)) return pending.geometry
        }
        val level = client.level
        if (target.entityId != null && level != null) {
            val search = AABB(
                target.x - 2.0, target.y - 2.0, target.z - 2.0,
                target.x + 2.0, target.y + 2.0, target.z + 2.0,
            )
            val matchingEntities = level.getEntities(
                EntityTypeTest.forClass(Entity::class.java), search,
                { entity -> target.entityId == entity.getUUID() },
            )
            if (matchingEntities.isNotEmpty())
                return FeedbackGeometry(matchingEntities.first().boundingBox, "entity_bounding_box_lookup")
        }
        val position = BlockPos.containing(target.x, target.y, target.z)
        if (isBlockMarker(target.type)) return blockFeedbackGeometry(client, position)
        return FeedbackGeometry(
            AABB(position.x.toDouble(), position.y.toDouble(), position.z.toDouble(),
                position.x + 1.0, position.y + 1.0, position.z + 1.0),
            "target_position_full_block_fallback",
        )
    }

    private fun blockFeedbackGeometry(client: Minecraft, position: BlockPos): FeedbackGeometry {
        val level = client.level
        if (level == null || !level.hasChunkAt(position)) {
            return FeedbackGeometry(fullBlockBounds(position), "unloaded_block_full_bounds_fallback")
        }
        val state = level.getBlockState(position)
        val shape = state.getShape(level, position)
        val emptyShape = shape.isEmpty
        var bounds = (if (emptyShape) AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) else shape.bounds())
            .move(position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
        var source = if (emptyShape) "block_outline_empty_full_block_fallback" else "block_outline"
        if (isChestBlock(state) && state.hasProperty(ChestBlock.TYPE)
            && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
        ) {
            val facing = state.getValue(ChestBlock.FACING)
            for (direction in arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                if (direction.axis == facing.axis) continue
                val neighborPos = position.relative(direction)
                if (!level.hasChunkAt(neighborPos)) continue
                val neighbor = level.getBlockState(neighborPos)
                if (!neighbor.`is`(state.block) || !neighbor.hasProperty(ChestBlock.TYPE)
                    || !neighbor.hasProperty(ChestBlock.FACING)
                    || neighbor.getValue(ChestBlock.FACING) != facing
                    || neighbor.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                ) continue
                val neighborShape = neighbor.getShape(level, neighborPos)
                val neighborEmpty = neighborShape.isEmpty
                val neighborBounds = (if (neighborEmpty) AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
                    else neighborShape.bounds()).move(
                    neighborPos.x.toDouble(), neighborPos.y.toDouble(), neighborPos.z.toDouble(),
                )
                bounds = bounds.minmax(neighborBounds)
                source = if (emptyShape || neighborEmpty)
                    "double_chest_outline_with_full_block_fallback" else "double_chest_outline"
                break
            }
        }
        return FeedbackGeometry(bounds, source)
    }

    private fun isChestBlock(state: BlockState): Boolean = state.`is`(Blocks.CHEST) || state.`is`(Blocks.TRAPPED_CHEST)

    private fun fullBlockBounds(position: BlockPos): AABB = AABB(
        position.x.toDouble(), position.y.toDouble(), position.z.toDouble(),
        position.x + 1.0, position.y + 1.0, position.z + 1.0,
    )

    private fun logClickDiagnosticsStatus(force: Boolean, reason: String) {
        val click = SecretClickedModule.INSTANCE
        if (!force && !click.isEnabled()) return
        if (force) clickDiagnosticTicks = 0
        else if (++clickDiagnosticTicks < 100) return
        else clickDiagnosticTicks = 0
        val client = Minecraft.getInstance()
        GeilerAddonsLog.write(Category.F7, click.name(), diagnosticTick(), "STATUS reason=$reason " +
            "enabled=${click.isEnabled()} inDungeon=${DungeonContextTracker.inDungeon()} " +
            "levelPresent=${client.level != null} targets=${targets.size} feedbackBoxes=${clickedHighlights.size} " +
            "pendingPickups=${pendingPickups.size} boxes=${SecretClickedModule.boxes.value()} itemBoxes=${SecretClickedModule.itemBoxes.value()} " +
            "depthCheck=${SecretClickedModule.depthCheck.value()} boxStyle=${SecretClickedModule.boxStyle.value()} chime=${SecretClickedModule.chime.value()} " +
            "soundId=${diagnosticSoundId(SecretClickedModule.sound.value())} gain=${SecretClickedModule.volume.value()} pitch=${SecretClickedModule.pitch.value()} " +
            "eventLimitPerTick=$MAX_CLICK_DIAGNOSTIC_EVENTS_PER_TICK")
    }

    fun onSecretClickedEnabled() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, SecretClickedModule.INSTANCE.name(), true)
        logClickDiagnosticsStatus(true, "enabled")
        updateWaypointDiagnosticsEnabled()
        reportAutomaticDiagnostics("secret-clicked-enabled", force = true)
    }

    fun onSecretClickedDisabled() {
        logClickDiagnosticsStatus(true, "disabled")
        reportAutomaticDiagnostics("secret-clicked-disabled", force = true)
        clearClickFeedbackFromFeature()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, SecretClickedModule.INSTANCE.name(), false)
        updateWaypointDiagnosticsEnabled()
    }

    private fun updateWaypointDiagnosticsEnabled() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(
            Category.F7,
            name(),
            isEnabled() || SecretClickedModule.INSTANCE.isEnabled(),
        )
    }

    private fun logClickDiagnosticEvent(event: String) {
        if (!SecretClickedModule.INSTANCE.isEnabled()) return
        val tick = diagnosticTick()
        if (clickDiagnosticEventTick != tick) {
            if (clickDiagnosticSuppressedEvents > 0) {
                GeilerAddonsLog.write(Category.F7, SecretClickedModule.INSTANCE.name(), tick,
                    "EVENTS_SUPPRESSED previousTick=$clickDiagnosticEventTick count=$clickDiagnosticSuppressedEvents " +
                        "limit=$MAX_CLICK_DIAGNOSTIC_EVENTS_PER_TICK")
            }
            clickDiagnosticEventTick = tick
            clickDiagnosticEventsThisTick = 0
            clickDiagnosticSuppressedEvents = 0
        }
        if (clickDiagnosticEventsThisTick >= MAX_CLICK_DIAGNOSTIC_EVENTS_PER_TICK) {
            clickDiagnosticSuppressedEvents = minOf(999_999, clickDiagnosticSuppressedEvents + 1)
            return
        }
        clickDiagnosticEventsThisTick++
        GeilerAddonsLog.write(Category.F7, SecretClickedModule.INSTANCE.name(), tick, "EVENT $event")
    }

    private fun logChimeOutcome(target: Target?, outcome: String) {
        val click = SecretClickedModule.INSTANCE
        logClickDiagnosticEvent(
            "SOUND targetType=${target?.type?.name ?: "UNKNOWN"} position=${targetPosition(target)} " +
                "soundId=${diagnosticSoundId(SecretClickedModule.sound.value())} gain=${SecretClickedModule.volume.value()} " +
                "pitch=${SecretClickedModule.pitch.value()} outcome=$outcome",
        )
    }

    private fun targetPosition(target: Target?): String = target?.let {
        "${formatCoord(it.x)},${formatCoord(it.y)},${formatCoord(it.z)}"
    } ?: "unknown"

    private fun boundsText(bounds: AABB): String =
        "min=${formatCoord(bounds.minX)},${formatCoord(bounds.minY)},${formatCoord(bounds.minZ)} " +
            "max=${formatCoord(bounds.maxX)},${formatCoord(bounds.maxY)},${formatCoord(bounds.maxZ)}"

    private fun formatCoord(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun diagnosticSoundId(soundId: String?): String {
        if (soundId == null) return "empty"
        val safe = soundId.trim().replace(Regex("[^A-Za-z0-9_:./-]"), "_")
        return if (safe.length <= 96) safe else safe.substring(0, 96)
    }

    private fun diagnosticTick(): Long = lastKnownClientTick

    private fun consumeRecentClickChime(collectionKey: String?): Boolean {
        if (collectionKey == null) return false
        val deadline = recentlyChimedMarkers.remove(collectionKey) ?: return false
        return !SecretWaypointClickFeedbackRules.expired(System.nanoTime(), deadline)
    }

    private fun isItemMarker(type: SecretWaypointClickFeedbackRules.Type): Boolean =
        type == SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM
            || type == SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP
            || type == SecretWaypointClickFeedbackRules.Type.ITEM_FRAME

    fun clearClickFeedbackFromFeature() = clearClickFeedback(Minecraft.getInstance())

    private fun clearClickFeedback(client: Minecraft?) {
        clickedHighlights.clear()
        recentlyChimedMarkers.clear()
        pendingPickups.clear()
        previousAttack = client?.options?.keyAttack?.isDown() == true
        previousUse = client?.options?.keyUse?.isDown() == true
    }

    private fun addAtCrosshair() {
        val client = Minecraft.getInstance()
        val pos = targetedLoadedBlock(client)
        val cell = pos?.let { DungeonGridRules.cellAt(it.x, it.z) }
        val floor = DungeonContextTracker.currentFloor()
        if (pos == null || cell == null || floor == null) { message("Point at a loaded dungeon block first"); return }
        val id = SecretWaypointStore.addPersonal(
            floor.displayName(), roomId(cell.componentX, cell.componentZ), settings.name.value(),
            pos.x, pos.y, pos.z, settings.personalColor.argb(),
        )
        if (id == null) { message("Waypoint was not saved; check the name or local waypoint file"); return }
        lastPersonalId = id
        settings.personalId.setValue(id)
        message("Added waypoint ${settings.name.value()}")
    }

    private fun renameMostRecent() {
        if (lastPersonalId == null || !SecretWaypointStore.renamePersonal(lastPersonalId, settings.name.value())) {
            message("No recent personal waypoint to rename")
            return
        }
        message("Renamed personal waypoint")
    }

    private fun removeMostRecent() {
        if (lastPersonalId == null || !SecretWaypointStore.removePersonal(lastPersonalId)) {
            message("No recent personal waypoint to remove")
            return
        }
        lastPersonalId = null
        message("Removed personal waypoint")
    }

    private fun listPersonal() {
        val waypoints = SecretWaypointStore.personal()
        if (waypoints.isEmpty()) { message("No personal waypoints are saved"); return }
        val limit = minOf(12, waypoints.size)
        for (i in 0 until limit) {
            val waypoint = waypoints[i]
            chatMessage("${waypoint.id()} · ${waypoint.name()} · ${waypoint.floor()} · ${waypoint.roomId()}")
        }
        if (waypoints.size > limit) message("Showing $limit of ${waypoints.size} personal waypoints")
    }

    private fun selectedPersonalId(): String? {
        val id = settings.personalId.value().trim()
        return if (id.isEmpty()) lastPersonalId else id
    }

    private fun renameSelected() {
        val id = selectedPersonalId()
        if (!SecretWaypointStore.renamePersonal(id, settings.name.value())) {
            message("Unknown personal waypoint ID or invalid name")
            return
        }
        message("Renamed personal waypoint")
    }

    private fun updateSelectedAtCrosshair() {
        val id = selectedPersonalId()
        val client = Minecraft.getInstance()
        val pos = targetedLoadedBlock(client)
        val cell = pos?.let { DungeonGridRules.cellAt(it.x, it.z) }
        val floor = DungeonContextTracker.currentFloor()
        if (pos == null || cell == null || floor == null || id == null
            || !SecretWaypointStore.updatePersonal(
                id, floor.displayName(), roomId(cell.componentX, cell.componentZ), settings.name.value(),
                pos.x, pos.y, pos.z, settings.personalColor.argb(),
            )
        ) {
            message("Point at a loaded dungeon block and set a valid personal waypoint ID")
            return
        }
        message("Updated personal waypoint")
    }

    private fun removeSelected() {
        val id = selectedPersonalId()
        if (!SecretWaypointStore.removePersonal(id)) { message("Unknown personal waypoint ID"); return }
        if (id != null && id == lastPersonalId) lastPersonalId = null
        if (id != null && id == settings.personalId.value()) settings.personalId.setValue("")
        message("Removed personal waypoint")
    }

    private fun updateMostRecentAtCrosshair() {
        val client = Minecraft.getInstance()
        val pos = targetedLoadedBlock(client)
        val cell = pos?.let { DungeonGridRules.cellAt(it.x, it.z) }
        val floor = DungeonContextTracker.currentFloor()
        val recentId = lastPersonalId
        if (pos == null || cell == null || floor == null || recentId == null
            || !SecretWaypointStore.updatePersonal(
                recentId, floor.displayName(), roomId(cell.componentX, cell.componentZ), settings.name.value(),
                pos.x, pos.y, pos.z, settings.personalColor.argb(),
            )
        ) {
            message("Point at a loaded block with a recent waypoint selected")
            return
        }
        message("Updated personal waypoint")
    }

    private fun hideDefault() {
        if (!SecretWaypointStore.hideDefault(settings.defaultId.value().trim())) {
            message("Unknown built-in waypoint ID")
            return
        }
        message("Hidden built-in waypoint override")
    }

    private fun restoreDefault() {
        if (!SecretWaypointStore.restoreDefault(settings.defaultId.value().trim())) {
            message("No override exists for that built-in ID")
            return
        }
        message("Restored built-in waypoint")
    }

    private fun replaceDefaultAtCrosshair() {
        val client = Minecraft.getInstance()
        val pos = targetedLoadedBlock(client)
        if (pos == null) { message("Point at a loaded dungeon block first"); return }
        val id = settings.defaultId.value().trim()
        val replacement = SecretWaypointStore.Waypoint(
            id, "", "", settings.name.value(), pos.x, pos.y, pos.z, settings.defaultColor.argb(),
        )
        if (!SecretWaypointStore.replaceDefault(id, replacement)) {
            message("Unknown built-in waypoint ID or invalid name")
            return
        }
        message("Saved local waypoint override")
    }

    private fun targetedLoadedBlock(client: Minecraft?): BlockPos? {
        val level = client?.level ?: return null
        val hit = client.hitResult as? BlockHitResult ?: return null
        if (hit.type != HitResult.Type.BLOCK) return null
        val pos = hit.blockPos
        return if (level.hasChunkAt(pos)) pos.immutable() else null
    }

    private fun playChime(target: Target?): Boolean {
        val click = SecretClickedModule.INSTANCE
        val bossPhase = DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase()
        if (!click.isEnabled() || !SecretClickedModule.chime.value()) {
            logChimeOutcome(target, "disabled")
            return false
        }
        if (bossPhase && !SecretClickedModule.chimeInBoss.value()) {
            logChimeOutcome(target, "boss_gated")
            return false
        }
        val nowMillis = System.currentTimeMillis()
        if (nowMillis - lastChimeAtMillis <= 10L) {
            logChimeOutcome(target, "duplicate_suppressed")
            return false
        }
        val configuredSoundId = SecretClickedModule.sound.value()?.trim() ?: ""
        val id = Identifier.tryParse(configuredSoundId)
        if (id == null) {
            logChimeOutcome(target, "invalid_sound_id")
            return false
        }
        val event: SoundEvent? = BuiltInRegistries.SOUND_EVENT.getValue(id)
        if (event == null) {
            logChimeOutcome(target, "unregistered_sound_id")
            return false
        }
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(event, SecretClickedModule.pitch.value(), SecretClickedModule.volume.value()),
        )
        lastChimeAtMillis = nowMillis
        logChimeOutcome(target, "submitted_to_sound_manager")
        return true
    }

    private fun roomCells(reading: DungeonMapRoomDetector.Reading): List<RoomCell> {
        val currentCell = reading.currentCell ?: return emptyList()
        val geometry = reading.geometry ?: return emptyList()
        val offsets = geometry.connectedCells().takeIf { it.isNotEmpty() }
            ?: listOf(DungeonMapGeometry.CellOffset(0, 0))
        val cells = ArrayList<RoomCell>()
        for (offset in offsets) {
            val gx = currentCell.componentX + offset.x
            val gz = currentCell.componentZ + offset.z
            val x = currentCell.baseX + offset.x * DungeonGridRules.CELL_STRIDE
            val z = currentCell.baseZ + offset.z * DungeonGridRules.CELL_STRIDE
            if (gx in 0..5 && gz in 0..5) cells.add(RoomCell(gx, gz, x, z))
        }
        return java.util.List.copyOf(cells)
    }

    private fun roomKey(reading: DungeonMapRoomDetector.Reading, cells: List<RoomCell>): String =
        java.lang.String.valueOf(reading.mapId) + "|" + cells.map { "${it.gridX},${it.gridZ}" }.sorted().joinToString(";")
            .ifEmpty { "unknown" }

    private fun roomId(gridX: Int, gridZ: Int): String = "cell:$gridX,$gridZ"

    private fun insideRoom(x: Double, z: Double, cells: List<RoomCell>?): Boolean {
        if (cells == null) return false
        for (cell in cells) {
            if (x >= cell.baseX - 0.5 && x < cell.baseX + DungeonGridRules.ROOM_SIZE + 0.5
                && z >= cell.baseZ - 0.5 && z < cell.baseZ + DungeonGridRules.ROOM_SIZE + 0.5
            ) return true
        }
        return false
    }

    private fun publishTargets(value: List<Target>) { targets = java.util.List.copyOf(value) }

    private fun message(text: String) {
        val client = Minecraft.getInstance()
        val gui = client.gui ?: return
        gui.hud.setOverlayMessage(Component.literal(text), true)
    }

    private fun chatMessage(text: String) {
        val client = Minecraft.getInstance()
        val gui = client.gui ?: return
        gui.hud.getChat().addClientSystemMessage(Component.literal(text))
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    @JvmRecord private data class RoomCell(val gridX: Int, val gridZ: Int, val baseX: Int, val baseZ: Int)
    @JvmRecord private data class Target(
        val id: String, val label: String, val x: Double, val y: Double, val z: Double, val color: Int,
        val entityId: UUID?, val waypoint: Boolean, val type: SecretWaypointClickFeedbackRules.Type,
        val collectionKey: String?,
    )
    @JvmRecord private data class PendingPickup(
        val type: SecretWaypointClickFeedbackRules.Type, val entity: Entity, val target: Target?,
        val itemCountBefore: Int, val frameHadItem: Boolean, val geometry: FeedbackGeometry, val deadlineNanos: Long,
    )
    @JvmRecord private data class FeedbackGeometry(val bounds: AABB, val source: String)
    @JvmRecord private data class ClickHighlight(
        val target: Target, val bounds: AABB, val geometrySource: String, val deadlineNanos: Long, val locked: Boolean,
    )
    @JvmRecord private data class CollectionMatch(val key: String, val target: Target)

    private class Settings {
        val enabledMarkers = BooleanSetting("Show Waypoints", true)
        val liveMarkers = BooleanSetting("Find Live Markers", true)
        val keepCollected = BooleanSetting("Keep Collected Markers Visible", false)
        val chestCandidates = BooleanSetting("Chests", true)
        val droppedItems = BooleanSetting("Dropped Items", "Items", true)
        val essenceDrops = BooleanSetting("Essence", true)
        val batCandidates = BooleanSetting("Bats", true)
        val itemFrameCandidates = BooleanSetting("Item Frames", true)
        val redstoneKeys = BooleanSetting("Redstone Keys", true)
        val leverCandidates = BooleanSetting("Levers", true)
        val name = TextSetting("Waypoint Name", "Personal Secret", 64)
        val personalId = TextSetting("Personal Waypoint ID", "", 128)
        val defaultId = TextSetting("Built-in Waypoint ID", "", 128)
        val range = NumberSetting("Marker Range", 8f, 128f, 64f, true)
        val personalColor = ColorSetting("Personal Waypoint Color", 56, 202, 255, 255)
        val defaultColor = ColorSetting("Built-in Waypoint Color", 95, 255, 135, 255)
    }
}

/** Pure decisions for the diagnostics-only tick and automatic STATUS cadence. */
internal object SecretWaypointDiagnosticsPolicy {
    private const val STATUS_HEARTBEAT_TICKS = 100L
    private const val STATUS_CHANGE_INTERVAL_TICKS = 20L

    @JvmStatic
    fun hasRuntimeConsumer(
        waypointEnabled: Boolean,
        secretClickedEnabled: Boolean,
        inDungeon: Boolean,
    ): Boolean = inDungeon && (waypointEnabled || secretClickedEnabled)

    @JvmStatic
    fun shouldRunDiagnosticsOnlyTick(
        waypointEnabled: Boolean,
        secretClickedEnabled: Boolean,
        hasRuntimeConsumer: Boolean,
    ): Boolean = (waypointEnabled || secretClickedEnabled) && !hasRuntimeConsumer

    @JvmStatic
    fun shouldWriteStatus(previousTick: Long, tick: Long, changed: Boolean, force: Boolean): Boolean {
        if (force || previousTick == Long.MIN_VALUE) return true
        val elapsedTicks = if (tick < previousTick) Long.MAX_VALUE else tick - previousTick
        val minimumInterval = if (changed) STATUS_CHANGE_INTERVAL_TICKS else STATUS_HEARTBEAT_TICKS
        return elapsedTicks >= minimumInterval
    }
}
