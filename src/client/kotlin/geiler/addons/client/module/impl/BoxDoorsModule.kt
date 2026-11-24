package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.BloodDoorTraceRules
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.DungeonDoorAdjacency
import geiler.addons.client.dungeon.DungeonDoorBlockOverlayController
import geiler.addons.client.dungeon.DungeonDoorTracker
import geiler.addons.client.dungeon.DungeonDoorTraceGrace
import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.DungeonKeyRules
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.DungeonMapRoomDetector
import geiler.addons.client.dungeon.helpers.DungeonMapGrid
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.presentation.PlayerTabNameSource
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.Objects
import java.util.TreeSet
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.jvm.JvmRecord
import kotlin.math.roundToInt

/** Boxes detected dungeon doorways and traces the next known Wither door or Blood entrance. */
class BoxDoorsModule private constructor(settings: Settings) : Module(
    "Box Doors", "Highlight dungeon doorways and trace the next known Wither door.", Category.F7,
    settings.normal, settings.normalFill, settings.normalOutline, settings.normalFillColor,
    settings.normalOutlineColor, settings.normalThroughWalls,
    settings.glass, settings.glassFill, settings.glassOutline, settings.glassFillColor,
    settings.glassOutlineColor, settings.glassThroughWalls,
    settings.start, settings.startFill, settings.startOutline, settings.startFillColor,
    settings.startOutlineColor, settings.startThroughWalls,
    settings.wither, settings.witherFill, settings.witherOutline, settings.witherFillColor,
    settings.witherOutlineColor, settings.witherKeyFillColor, settings.witherKeyOutlineColor,
    settings.witherThroughWalls, settings.blood, settings.bloodFill, settings.bloodOutline,
    settings.bloodFillColor, settings.bloodOutlineColor, settings.bloodKeyFillColor,
    settings.bloodKeyOutlineColor, settings.bloodThroughWalls, settings.visibleWitherDoors,
    settings.tracer, settings.traceBloodDoor, settings.tracerColor, settings.tracerWidth,
    settings.tracerThroughWalls, settings.bloodRushClassFilter, settings.bloodRushTank,
    settings.bloodRushHealer, settings.bloodRushMage, settings.bloodRushBerserk,
    settings.bloodRushArcher, settings.range, settings.debug,
) {
    private val settings = settings
    private var observedWitherKeys = 0
    private var observedBloodKey = false
    private var keyOwnership = DungeonKeyRules.KeyOwnership.NONE
    private var keyStateInDungeon = false
    private var keyStateFloor: String? = null
    private var keyStateLevel: ClientLevel? = null
    private var traceCell = -1
    private var traceFromCell = -1
    private var traceToCell = -1
    private var enteredThroughWither = false
    private val witherTraceGrace = DungeonDoorTraceGrace()
    private val bloodTraceGrace = DungeonDoorTraceGrace()
    private var fairyRouteMap: DungeonMapGrid.ScanResult? = null
    private var fairyRouteMapLevel: ClientLevel? = null
    private var fairyRouteMapTick = Long.MIN_VALUE
    private var fairyRouteSelection: BoxDoorsFairyRouteRules.Selection? = null
    private var fairyRouteSelectionLevel: ClientLevel? = null
    private var fairyRoutePromptScan = "not-requested"
    private var fairyRouteMapInputDiagnostic = "not-sampled"
    private var lastTraceDiagnostic = ""
    private var lastTraceDiagnosticTick = Long.MIN_VALUE
    private var lastAutomaticTraceDiagnostic = ""
    private var lastAutomaticTraceDiagnosticTick = Long.MIN_VALUE
    private var lastAutomaticTraceDiagnosticLevel: ClientLevel? = null
    private var suppressedAutomaticTraceDiagnostics = 0L
    private var lastRenderTraceOutcome = "not-rendered"
    private var bloodRushClassDecision = BloodRushClassDecision(null, "not-sampled", "filter-off", true)

    init {
        // Normal, Glass, and Start settings stay in the flat config list for existing profiles,
        // but the visible Box Doors highlights now cover only Wither and Blood doors.
        group(
            SettingGroup("Door Highlights").containing(
                SettingGroup.switched("Wither doors", settings.wither,
                    settings.witherFill, settings.witherFillColor, settings.witherKeyFillColor,
                    settings.witherOutline, settings.witherOutlineColor, settings.witherKeyOutlineColor,
                    settings.witherThroughWalls),
                SettingGroup.switched("Blood doors", settings.blood,
                    settings.bloodFill, settings.bloodFillColor, settings.bloodKeyFillColor,
                    settings.bloodOutline, settings.bloodOutlineColor, settings.bloodKeyOutlineColor,
                    settings.bloodThroughWalls),
            ),
            SettingGroup("Routes & Tracers", settings.tracer, settings.traceBloodDoor,
                settings.visibleWitherDoors, settings.tracerColor, settings.tracerWidth,
                settings.tracerThroughWalls),
            SettingGroup("Blood Rush Classes", settings.bloodRushClassFilter,
                settings.bloodRushTank, settings.bloodRushHealer, settings.bloodRushMage,
                settings.bloodRushBerserk, settings.bloodRushArcher),
            SettingGroup("General", settings.range),
            SettingGroup("Diagnostics", settings.debug),
        )
    }

    override fun isSettingVisible(setting: Setting): Boolean {
        if (setting === settings.normalFillColor) return settings.normal.value() && settings.normalFill.value()
        if (setting === settings.normalOutlineColor) return settings.normal.value() && settings.normalOutline.value()
        if (setting === settings.glassFillColor) return settings.glass.value() && settings.glassFill.value()
        if (setting === settings.glassOutlineColor) return settings.glass.value() && settings.glassOutline.value()
        if (setting === settings.startFillColor) return settings.start.value() && settings.startFill.value()
        if (setting === settings.startOutlineColor) return settings.start.value() && settings.startOutline.value()
        if (setting === settings.witherFillColor) return settings.wither.value() && settings.witherFill.value()
        if (setting === settings.witherOutlineColor) return settings.wither.value() && settings.witherOutline.value()
        if (setting === settings.witherKeyFillColor) return settings.wither.value() && settings.witherFill.value()
        if (setting === settings.witherKeyOutlineColor) return settings.wither.value() && settings.witherOutline.value()
        if (setting === settings.bloodFillColor) return settings.blood.value() && settings.bloodFill.value()
        if (setting === settings.bloodOutlineColor) return settings.blood.value() && settings.bloodOutline.value()
        if (setting === settings.bloodKeyFillColor) return settings.blood.value() && settings.bloodFill.value()
        if (setting === settings.bloodKeyOutlineColor) return settings.blood.value() && settings.bloodOutline.value()
        if (setting === settings.bloodRushTank || setting === settings.bloodRushHealer ||
            setting === settings.bloodRushMage || setting === settings.bloodRushBerserk ||
            setting === settings.bloodRushArcher
        ) return settings.bloodRushClassFilter.value()
        if (setting === settings.tracerColor || setting === settings.tracerWidth ||
            setting === settings.tracerThroughWalls
        ) return settings.tracer.value() || settings.traceBloodDoor.value()
        return true
    }

    override fun isActive(): Boolean = isEnabled() && DungeonContextTracker.inDungeon()

    override fun inactiveReason(): String? =
        if (isEnabled() && !DungeonContextTracker.inDungeon()) "Waiting for a dungeon" else null

    fun tick() = tick(true)

    /** Lets the client bootstrap own the shared door scan when other dungeon consumers need it. */
    fun tick(updateDoorTracker: Boolean) {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), isEnabled() || settings.debug.value())
        val client = Minecraft.getInstance()
        val inDungeon = DungeonContextTracker.inDungeon()
        val floor = DungeonContextTracker.currentFloor()?.displayName()
        if (!inDungeon || !isEnabled() || client.level == null || client.player == null) {
            resetKeyState()
            lastRenderTraceOutcome = "not-active"
        } else if (!keyStateInDungeon || client.level !== keyStateLevel || !Objects.equals(floor, keyStateFloor)) {
            resetKeyState()
            keyStateInDungeon = true
            keyStateFloor = floor
            keyStateLevel = client.level
        }
        if (inDungeon && isEnabled() && client.level != null && client.player != null) {
            val inventoryKeys = scanKeyOwnership(client)
            keyOwnership = DungeonKeyRules.combineOwnership(observedWitherKeys > 0, observedBloodKey,
                inventoryKeys.wither, inventoryKeys.blood)
        }
        if (updateDoorTracker) DungeonDoorTracker.tick(if (isEnabled()) client else null)
        updateBloodTraceContext(client, inDungeon, floor)
        updateFairyRouteState(client, inDungeon && isEnabled() &&
            (settings.tracer.value() || settings.traceBloodDoor.value()))
        bloodRushClassDecision = resolveBloodRushClass(client)
        reportTraceDebug(client)
        reportAutomaticTraceDiagnostics(client)
    }

    private fun updateFairyRouteState(client: Minecraft?, enabled: Boolean) {
        if (!enabled || client == null || client.player == null || client.level == null) {
            fairyRouteMap = null
            fairyRouteMapLevel = null
            fairyRouteMapTick = Long.MIN_VALUE
            fairyRouteSelection = null
            fairyRouteSelectionLevel = null
            fairyRoutePromptScan = "not-requested"
            fairyRouteMapInputDiagnostic = "not-sampled"
            return
        }
        val level = client.level!!
        val tick = level.gameTime
        if (fairyRouteMapLevel !== level || tick < fairyRouteMapTick || tick - fairyRouteMapTick >= 5) {
            val colors = DungeonMapRoomDetector.savedMapColors(client)
            fairyRouteMap = if (colors == null) null else DungeonMapGrid.scan(colors)
            fairyRouteMapLevel = level
            fairyRouteMapTick = tick
        }
        val reading = DungeonMapRoomDetector.current()
        val playerMapPosition = reading?.playerMapPosition
        val mapCell = BoxDoorsFairyRouteRules.mapCellAtMarker(fairyRouteMap, playerMapPosition)
        val currentMapCell = mapCell.cell()
        val worldCell = if (reading == null || !reading.hasCurrentCell()) null else reading.currentCell
        val currentWorldCell = if (worldCell == null) -1 else worldCell.componentZ * 6 + worldCell.componentX
        fairyRouteMapInputDiagnostic = routeMapInputDiagnostic(playerMapPosition, fairyRouteMap,
            mapCell, worldCell, currentWorldCell)
        val selection = BoxDoorsFairyRouteRules.select(fairyRouteMap, currentMapCell,
            currentWorldCell, DungeonDoorTracker.doors()) { door -> doorPresence(level, door) }
        fairyRouteSelection = selection
        fairyRouteSelectionLevel = level
        if (selection.status() == BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_MISSING ||
            selection.status() == BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_AMBIGUOUS ||
            selection.status() == BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_STATE_UNKNOWN
        ) {
            fairyRoutePromptScan = promptScanName(DungeonDoorTracker.requestPromptRescan(
                level, selection.fromCell(), selection.toCell(),
            ))
        } else if (selection.target() != null) {
            fairyRoutePromptScan = "match-present"
        } else {
            fairyRoutePromptScan = "not-needed"
        }
    }

    private fun resolveBloodRushClass(client: Minecraft?): BloodRushClassDecision {
        var playerClass: DungeonClass? = null
        val resolution: String
        if (client?.player?.connection == null) {
            resolution = "player-unavailable"
        } else {
            val player = client.player!!
            val playerInfo = player.connection.getPlayerInfo(player.uuid)
            if (playerInfo == null) {
                resolution = "tab-row-missing"
            } else {
                val tabClass = parseBloodRushClassFromTabLine(PlayerTabNameSource.rawLine(playerInfo))
                playerClass = tabClass.playerClass
                resolution = tabClass.resolution ?: "tab-row-unrecognized"
            }
        }
        val filterEnabled = settings.bloodRushClassFilter.value()
        if (!filterEnabled) return BloodRushClassDecision(playerClass, resolution, "filter-off", true)
        if (playerClass == null) return BloodRushClassDecision(null, resolution, "blocked-class-unresolved", false)
        val selected = when (playerClass) {
            DungeonClass.TANK -> settings.bloodRushTank.value()
            DungeonClass.HEALER -> settings.bloodRushHealer.value()
            DungeonClass.MAGE -> settings.bloodRushMage.value()
            DungeonClass.BERSERK -> settings.bloodRushBerserk.value()
            DungeonClass.ARCHER -> settings.bloodRushArcher.value()
        }
        val allowed = bloodRushClassAllowsVisuals(filterEnabled, playerClass, selected)
        return BloodRushClassDecision(playerClass, resolution,
            if (allowed) "allowed-class-selected" else "blocked-class-unchecked", allowed)
    }

    private fun updateBloodTraceContext(client: Minecraft?, inDungeon: Boolean, floor: String?) {
        if (!inDungeon || client?.player == null || client.level == null ||
            client.level !== keyStateLevel || !Objects.equals(floor, keyStateFloor)
        ) {
            traceCell = -1
            traceFromCell = -1
            traceToCell = -1
            enteredThroughWither = false
            return
        }
        val position = client.player!!.blockPosition()
        val cell = DungeonGridRules.cellAt(position.x, position.z)
        val nextCell = if (cell == null) -1 else cell.componentZ * 6 + cell.componentX
        if (nextCell != traceCell) {
            traceFromCell = traceCell
            traceToCell = nextCell
            traceCell = nextCell
        }
        enteredThroughWither = traceFromCell >= 0 && traceToCell >= 0 && DungeonDoorTracker.doors().any { door ->
            door.type() == DungeonDoorTracker.Type.WITHER &&
                ((door.firstCell() == traceFromCell && door.secondCell() == traceToCell) ||
                    (door.firstCell() == traceToCell && door.secondCell() == traceFromCell))
        }
    }

    /** Local-player pickup/open events are a fallback when the inventory item name is hidden. */
    fun onChatMessage(line: String?) {
        if (!DungeonContextTracker.inDungeon() || line == null) return
        val message = ChatStringSemantics.trim(geiler.addons.client.tree.ChatText.plain(line))
        val client = Minecraft.getInstance()
        val localName = client.player?.name?.string ?: ""
        when {
            DungeonKeyRules.isPickupForPlayer(message, localName, DungeonDoorTracker.Type.WITHER) -> observedWitherKeys++
            DungeonKeyRules.isPickupForPlayer(message, localName, DungeonDoorTracker.Type.BLOOD) -> observedBloodKey = true
            DungeonKeyRules.isDoorOpenedByPlayer(message, localName, DungeonDoorTracker.Type.WITHER) ->
                observedWitherKeys = maxOf(0, observedWitherKeys - 1)
            message.equals("The BLOOD DOOR has been opened!", ignoreCase = true) -> observedBloodKey = false
        }
    }

    private fun resetKeyState() {
        observedWitherKeys = 0
        observedBloodKey = false
        keyOwnership = DungeonKeyRules.KeyOwnership.NONE
        keyStateInDungeon = false
        keyStateFloor = null
        keyStateLevel = null
        traceCell = -1
        traceFromCell = -1
        traceToCell = -1
        enteredThroughWither = false
        fairyRouteMap = null
        fairyRouteMapLevel = null
        fairyRouteMapTick = Long.MIN_VALUE
        fairyRouteSelection = null
        fairyRouteSelectionLevel = null
        fairyRoutePromptScan = "not-requested"
        fairyRouteMapInputDiagnostic = "not-sampled"
        witherTraceGrace.reset()
        bloodTraceGrace.reset()
    }

    private fun reportTraceDebug(client: Minecraft?) {
        if (!settings.debug.value() || !isEnabled()) {
            lastTraceDiagnostic = ""
            return
        }
        val doors = DungeonDoorTracker.doors()
        val witherDoors = doors.count { it.type() == DungeonDoorTracker.Type.WITHER }
        val bloodDoors = doors.count { it.type() == DungeonDoorTracker.Type.BLOOD }
        val route = if (client?.level == null || fairyRouteSelectionLevel !== client.level) null else fairyRouteSelection
        val tracerRoom = tracerRoomContext(client)
        val choice = chooseTracerTarget(route, client)
        val target = choice.target()?.takeIf { it.type() == DungeonDoorTracker.Type.BLOOD }
        val bloodPresence = if (target == null || client?.level == null) BloodDoorTraceRules.DoorPresence.UNKNOWN
            else doorPresence(client.level, target)
        val closedBloodTarget = bloodPresence == BloodDoorTraceRules.DoorPresence.CLOSED
        val traceDecision = settings.traceBloodDoor.value() && closedBloodTarget && target != null
        val player = client?.player
        val targetDistance = if (target == null || player == null) -1.0 else
            Math.sqrt(player.distanceToSqr(target.centerX(), target.centerY(), target.centerZ()))
        val state = "dungeon=${DungeonContextTracker.inDungeon()}, cell=$traceCell" +
            ", transition=$traceFromCell->$traceToCell, enteredThroughWither=$enteredThroughWither" +
            ", doors(wither=$witherDoors, blood=$bloodDoors), bloodTracer=${settings.traceBloodDoor.value()}" +
            ", bloodRushClass=${bloodRushClassDecision.playerClass?.name?.lowercase(Locale.ROOT) ?: "unknown"}" +
            "/${bloodRushClassDecision.resolution}, classFilter=${bloodRushClassDecision.decision}" +
            ", route=${route?.diagnostic() ?: "not-sampled"}" +
            ", targetChoice=${choice.source()?.name?.lowercase(Locale.ROOT) ?: "unknown"}" +
            "/candidates=${choice.candidateCount()}" +
            "/scan=$fairyRoutePromptScan/rejection=${choice.rejectionReason()}" +
            ", routeInput=$fairyRouteMapInputDiagnostic" +
            ", keyOwnership(wither=${keyOwnership.wither},blood=${keyOwnership.blood})" +
            ", bloodTarget=${if (target == null) "none" else bloodPresence.toString().lowercase(Locale.ROOT)}" +
            ", physicalCell=${tracerRoom.physicalCell()?.let(::cellId) ?: "unknown"}" +
            ", mapCell=${tracerRoom.mapCell()?.let(::cellId) ?: "unknown"}" +
            ", cellSource=${tracerRoom.source()}" +
            ", roomFootprint=${if (tracerRoom.confirmed()) TreeSet(tracerRoom.footprint()) else "unknown"}" +
            ", roomRejection=${tracerRoom.rejectionReason() ?: "none"}" +
            ", selectedBloodTarget=${target != null}, traceDecision=$traceDecision" +
            (if (target == null) "" else " (about ${Math.round(targetDistance / 5.0) * 5} blocks)")
        val tick = client?.level?.gameTime ?: 0L
        if (state == lastTraceDiagnostic && tick - lastTraceDiagnosticTick < 100) return
        lastTraceDiagnostic = state
        lastTraceDiagnosticTick = tick
        GeilerAddonsLog.write(Category.F7, name(), tick, "door trace: $state")
    }

    fun render(context: LevelRenderContext) {
        if (!isActive()) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        if (client.level == null) return
        bloodRushClassDecision = resolveBloodRushClass(client)
        val classVisualsAllowed = bloodRushClassDecision.allowsVisuals
        val camera = client.gameRenderer.mainCamera().position()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val range = settings.range.value().roundToInt()
        val hasWitherKey = keyOwnership.wither
        val hasBloodKey = keyOwnership.blood
        val observedDoors = DungeonDoorTracker.doors()
        val visibleWither = HashSet(DungeonDoorTracker.orderedWitherDoors(
            player.blockPosition(), settings.visibleWitherDoors.value().roundToInt()))
        val doorPresences = HashMap<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>()
        var drew = false
        var bloodBoxes = 0
        var witherBoxes = 0
        var classFilteredBoxes = 0
        var witherClosed = 0
        var witherOpen = 0
        var witherUnknown = 0
        var bloodClosed = 0
        var bloodOpen = 0
        var bloodUnknown = 0
        for (door in observedDoors) {
            if (!BoxDoorsHighlightRules.rendersBox(door.type())) continue
            val blood = door.type() == DungeonDoorTracker.Type.BLOOD
            val presence = doorPresence(client.level!!, door)
            doorPresences[door] = presence
            if (blood) {
                when (presence) {
                    BloodDoorTraceRules.DoorPresence.CLOSED -> bloodClosed++
                    BloodDoorTraceRules.DoorPresence.OPEN -> bloodOpen++
                    BloodDoorTraceRules.DoorPresence.UNKNOWN -> bloodUnknown++
                }
            } else {
                when (presence) {
                    BloodDoorTraceRules.DoorPresence.CLOSED -> witherClosed++
                    BloodDoorTraceRules.DoorPresence.OPEN -> witherOpen++
                    BloodDoorTraceRules.DoorPresence.UNKNOWN -> witherUnknown++
                }
            }
            if (!BoxDoorsHighlightRules.rendersBox(door.type(), presence)) continue
            if (door.type() == DungeonDoorTracker.Type.WITHER &&
                (!settings.wither.value() || !visibleWither.contains(door))
            ) continue
            if (blood && !settings.blood.value()) continue
            if (player.distanceToSqr(door.centerX(), door.centerY(), door.centerZ()) > range.toDouble() * range) continue
            val fill = if (blood) settings.bloodFill.value() else settings.witherFill.value()
            val outline = if (blood) settings.bloodOutline.value() else settings.witherOutline.value()
            if (!fill && !outline) continue
            if (!classVisualsAllowed) {
                classFilteredBoxes++
                continue
            }
            val fillColor = if (!fill) 0 else if (blood) {
                if (hasBloodKey) settings.bloodKeyFillColor.argb() else settings.bloodFillColor.argb()
            } else if (hasWitherKey) settings.witherKeyFillColor.argb() else settings.witherFillColor.argb()
            val outlineColor = if (!outline) 0 else if (blood) {
                if (hasBloodKey) settings.bloodKeyOutlineColor.argb() else settings.bloodOutlineColor.argb()
            } else if (hasWitherKey) settings.witherKeyOutlineColor.argb() else settings.witherOutlineColor.argb()
            val throughWalls = if (blood) settings.bloodThroughWalls.value() else settings.witherThroughWalls.value()
            EspRenderer.renderBox(pose, buffers, door.x() - camera.x, door.y() - camera.y,
                door.z() - camera.z, door.width().toDouble(), door.height().toDouble(), door.depth().toDouble(),
                fillColor, outlineColor, 1.5f, !throughWalls)
            if (blood) bloodBoxes++ else witherBoxes++
            drew = true
        }
        var activeWitherTrace: DungeonDoorTracker.Door? = null
        val tracerRoom = tracerRoomContext(client)
        val route = if (fairyRouteSelectionLevel === client.level) fairyRouteSelection else null
        val targetChoice = chooseTracerTarget(route, client)
        val routeTarget = targetChoice.target()
        val orderedRouteTarget = targetChoice.source() == BoxDoorsFairyRouteRules.TargetSource.MAP_ROUTE ||
            targetChoice.source() == BoxDoorsFairyRouteRules.TargetSource.TRACKER_WITHER_ROUTE ||
            targetChoice.source() == BoxDoorsFairyRouteRules.TargetSource.TRACKER_BLOOD_DOOR
        val targetIsWither = routeTarget != null && routeTarget.type() == DungeonDoorTracker.Type.WITHER
        val targetIsBlood = routeTarget != null && routeTarget.type() == DungeonDoorTracker.Type.BLOOD
        val directFairyBloodExit = route != null &&
            route.transition() == BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY_TO_BLOOD
        var selectedWither: DungeonDoorTracker.Door? = null
        var witherTarget: DungeonDoorTracker.Door? = null
        var witherPresence = BloodDoorTraceRules.DoorPresence.UNKNOWN
        var witherTargetPresence = BloodDoorTraceRules.DoorPresence.UNKNOWN
        var witherEligible = false
        var witherRangeBypass = false
        var witherOutcome = "disabled"
        val witherRouteSource = targetChoice.source()?.name?.lowercase(Locale.ROOT)?.replace('_', '-') ?: "unknown"
        var witherRouteDiagnostic = if (settings.tracer.value() || settings.traceBloodDoor.value())
            if (route == null) "status=not-sampled,promptScan=$fairyRoutePromptScan"
            else "${route.diagnostic()},promptScan=$fairyRoutePromptScan"
        else "status=disabled"
        if (settings.tracer.value() || settings.traceBloodDoor.value()) {
            witherRouteDiagnostic += ",routeInput($fairyRouteMapInputDiagnostic)"
        }
        if (settings.tracer.value()) {
            if (targetIsBlood) {
                witherTraceGrace.reset()
                witherOutcome = if (directFairyBloodExit) "fairy-blood-exit" else "next-target-is-blood"
            } else if (!targetIsWither) {
                witherTraceGrace.reset()
                witherOutcome = targetChoice.rejectionReason().toString()
            } else {
                selectedWither = routeTarget
                witherPresence = doorPresences.getOrDefault(selectedWither, BloodDoorTraceRules.DoorPresence.UNKNOWN)
                witherEligible = witherPresence == BloodDoorTraceRules.DoorPresence.CLOSED
                if (witherPresence != BloodDoorTraceRules.DoorPresence.CLOSED || !witherEligible) {
                    witherTraceGrace.reset()
                } else {
                    witherTarget = witherTraceGrace.update(DungeonContextTracker.inDungeon(),
                        client.level, DungeonContextTracker.currentFloor(), selectedWither, witherPresence, true,
                        { retained -> doorPresence(client.level!!, retained) }, client.level!!.gameTime)
                }
                if (witherTarget != null) {
                    activeWitherTrace = witherTarget
                    witherTargetPresence = doorPresence(client.level!!, witherTarget!!)
                    if (!classVisualsAllowed) {
                        witherOutcome = "class-filtered-${bloodRushClassDecision.decision}"
                    } else if (!tracerCanRender(witherTarget, witherTargetPresence, tracerRoom, orderedRouteTarget) ||
                        !BoxDoorsHighlightRules.rendersTracer(witherTargetPresence)
                    ) {
                        witherOutcome = "retained-target-${witherTargetPresence.name.lowercase(Locale.ROOT)}"
                    } else {
                        witherRangeBypass = shouldBypassTracerRange(witherTarget, witherTargetPresence, tracerRoom)
                        val tracerDrew = renderTracer(settings, client, pose, buffers, camera, witherTarget!!,
                            witherTargetPresence, tracerRoom, orderedRouteTarget)
                        drew = drew || tracerDrew
                        witherOutcome = if (tracerDrew) "line-submitted" else "not-submitted"
                    }
                } else {
                    witherOutcome = traceOutcome(selectedWither, witherPresence, witherEligible,
                        targetChoice.rejectionReason())
                }
            }
        } else {
            witherTraceGrace.reset()
        }
        var selectedBlood: DungeonDoorTracker.Door? = null
        var bloodTarget: DungeonDoorTracker.Door? = null
        var bloodPresence = BloodDoorTraceRules.DoorPresence.UNKNOWN
        var bloodTargetPresence = BloodDoorTraceRules.DoorPresence.UNKNOWN
        var bloodEligible = false
        var bloodOutcome = "disabled"
        if (settings.traceBloodDoor.value()) {
            if (targetIsWither || !BloodDoorTraceRules.bloodSequenceAllowsTrace(activeWitherTrace)) {
                // Do not let Blood's leave-room grace keep drawing it while a Wither is still next in sequence.
                bloodTraceGrace.reset()
                bloodOutcome = "withheld-by-wither-sequence"
            } else if (!targetIsBlood) {
                bloodTraceGrace.reset()
                bloodOutcome = targetChoice.rejectionReason().toString()
            } else {
                selectedBlood = routeTarget
                bloodPresence = doorPresences.getOrDefault(selectedBlood, BloodDoorTraceRules.DoorPresence.UNKNOWN)
                bloodEligible = bloodPresence == BloodDoorTraceRules.DoorPresence.CLOSED
                if (bloodPresence != BloodDoorTraceRules.DoorPresence.CLOSED || !bloodEligible) {
                    bloodTraceGrace.reset()
                } else {
                    bloodTarget = bloodTraceGrace.update(DungeonContextTracker.inDungeon(),
                        client.level, DungeonContextTracker.currentFloor(), selectedBlood, bloodPresence, true,
                        { retained -> doorPresence(client.level!!, retained) }, client.level!!.gameTime)
                }
                // The Blood entrance may be far across the grid, so its tracer intentionally ignores box range.
                if (bloodTarget != null) {
                    bloodTargetPresence = doorPresence(client.level!!, bloodTarget!!)
                    if (!classVisualsAllowed) {
                        bloodOutcome = "class-filtered-${bloodRushClassDecision.decision}"
                    } else if (!tracerCanRender(bloodTarget, bloodTargetPresence, tracerRoom, orderedRouteTarget) ||
                        !BoxDoorsHighlightRules.rendersTracer(bloodTargetPresence)
                    ) {
                        bloodOutcome = "retained-target-${bloodTargetPresence.name.lowercase(Locale.ROOT)}"
                    } else {
                        val tracerDrew = renderTracer(settings, client, pose, buffers, camera, bloodTarget!!,
                            bloodTargetPresence, tracerRoom, orderedRouteTarget)
                        drew = drew || tracerDrew
                        bloodOutcome = if (tracerDrew) "line-submitted" else "not-submitted"
                    }
                } else {
                    bloodOutcome = traceOutcome(selectedBlood, bloodPresence, bloodEligible,
                        targetChoice.rejectionReason())
                }
            }
        } else {
            bloodTraceGrace.reset()
        }
        lastRenderTraceOutcome = "boxes(wither=$witherBoxes,blood=$bloodBoxes)" +
            "; bloodRushClass=${bloodRushClassDecision.playerClass?.name?.lowercase(Locale.ROOT) ?: "unknown"}" +
            "/${bloodRushClassDecision.resolution},classFilter=${bloodRushClassDecision.decision}" +
            ",classFilteredBoxes=$classFilteredBoxes" +
            "; boxEligibility(wither=closed:$witherClosed,open:$witherOpen,unknown:$witherUnknown" +
            ";blood=closed:$bloodClosed,open:$bloodOpen,unknown:$bloodUnknown)" +
            "; targetChoice(source=$witherRouteSource,fallbackSteps=${targetChoice.candidateCount()}" +
            ",routeStatus=${route?.status()?.name?.lowercase(Locale.ROOT) ?: "not-sampled"}" +
            ",scan=$fairyRoutePromptScan,rejection=${targetChoice.rejectionReason()})" +
            "; witherRoute($witherRouteDiagnostic)" +
            "; witherTrace(selected=${doorId(selectedWither)},presence=$witherPresence" +
            ",eligible=$witherEligible,target=${doorId(witherTarget)},outcome=$witherOutcome)" +
            "; bloodTrace(selected=${doorId(selectedBlood)},presence=$bloodPresence" +
            ",eligible=$bloodEligible,target=${doorId(bloodTarget)},outcome=$bloodOutcome)" +
            "; witherRoom(${tracerRoomDiagnostics(tracerRoom,
                witherTarget ?: selectedWither, witherTargetPresence.takeIf { witherTarget != null } ?: witherPresence,
                witherRangeBypass, witherOutcome, orderedRouteTarget)})" +
            "; bloodRoom(${tracerRoomDiagnostics(tracerRoom,
                bloodTarget ?: selectedBlood, bloodTargetPresence.takeIf { bloodTarget != null } ?: bloodPresence,
                false, bloodOutcome, orderedRouteTarget)})"
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    private fun reportAutomaticTraceDiagnostics(client: Minecraft?) {
        if (!isEnabled()) return
        val level = client?.level
        val tick = client?.level?.gameTime ?: 0L
        val doors = DungeonDoorTracker.doors()
        val witherDoors = doors.count { it.type() == DungeonDoorTracker.Type.WITHER }
        val bloodDoors = doors.count { it.type() == DungeonDoorTracker.Type.BLOOD }
        val state = "enabled=true, inDungeon=${DungeonContextTracker.inDungeon()}" +
            ", level=${client?.level != null}, doors(wither=$witherDoors,blood=$bloodDoors)" +
            ", bloodRushClass=${bloodRushClassDecision.playerClass?.name?.lowercase(Locale.ROOT) ?: "unknown"}" +
            "/${bloodRushClassDecision.resolution}" +
            ", classFilter(enabled=${settings.bloodRushClassFilter.value()},decision=${bloodRushClassDecision.decision})" +
            ", sharedRecords=${sharedKeyDoorSummary(doors)}" +
            ", highlights(wither=${settings.wither.value()},blood=${settings.blood.value()},range=${settings.range.value().roundToInt()})" +
            ", keys(wither=${keyOwnership.wither},blood=${keyOwnership.blood})" +
            ", $lastRenderTraceOutcome"
        val levelChanged = level !== lastAutomaticTraceDiagnosticLevel ||
            (lastAutomaticTraceDiagnosticTick != Long.MIN_VALUE && tick < lastAutomaticTraceDiagnosticTick)
        val elapsed = if (lastAutomaticTraceDiagnosticTick == Long.MIN_VALUE || levelChanged) Long.MAX_VALUE
            else tick - lastAutomaticTraceDiagnosticTick
        val changed = state != lastAutomaticTraceDiagnostic
        if (elapsed < 20) {
            if (changed) suppressedAutomaticTraceDiagnostics++
            return
        }
        if (!changed && !levelChanged && elapsed < 100) return
        GeilerAddonsLog.write(Category.F7, name(), tick,
            "AUTO door decisions: $state, suppressedChanges=$suppressedAutomaticTraceDiagnostics")
        lastAutomaticTraceDiagnostic = state
        lastAutomaticTraceDiagnosticTick = tick
        lastAutomaticTraceDiagnosticLevel = level
        suppressedAutomaticTraceDiagnostics = 0
    }

    private class Settings {
        val normal = BooleanSetting("Show Normal Doors", true)
        val normalFill = BooleanSetting("Normal Door Fill", true)
        val normalOutline = BooleanSetting("Normal Door Outline", true)
        val normalFillColor = ColorSetting("Normal Door Fill Color", 69, 217, 255, 42)
        val normalOutlineColor = ColorSetting("Normal Door Outline Color", 69, 217, 255, 255)
        val normalThroughWalls = BooleanSetting("Normal Doors Through Walls", "Draw Through Walls", true)
        val glass = BooleanSetting("Show Glass Doors", true)
        val glassFill = BooleanSetting("Glass Door Fill", true)
        val glassOutline = BooleanSetting("Glass Door Outline", true)
        val glassFillColor = ColorSetting("Glass Door Fill Color", 90, 213, 255, 48)
        val glassOutlineColor = ColorSetting("Glass Door Outline Color", 90, 213, 255, 255)
        val glassThroughWalls = BooleanSetting("Glass Doors Through Walls", "Draw Through Walls", true)
        val start = BooleanSetting("Show Start Door", true)
        val startFill = BooleanSetting("Start Door Fill", true)
        val startOutline = BooleanSetting("Start Door Outline", true)
        val startFillColor = ColorSetting("Start Door Fill Color", 255, 225, 94, 48)
        val startOutlineColor = ColorSetting("Start Door Outline Color", 255, 225, 94, 255)
        val startThroughWalls = BooleanSetting("Start Door Through Walls", "Draw Through Walls", true)
        val wither = BooleanSetting("Show Wither Doors", true)
        val witherFill = BooleanSetting("Wither Door Fill", true)
        val witherOutline = BooleanSetting("Wither Door Outline", true)
        val witherFillColor = ColorSetting("Wither Door Fill Color", 255, 107, 107, 72)
        val witherOutlineColor = ColorSetting("Wither Door Outline Color", 255, 107, 107, 255)
        val witherKeyFillColor = ColorSetting("Wither Door Fill Color · Key", 0, 220, 88, 72)
        val witherKeyOutlineColor = ColorSetting("Wither Door Outline Color · Key", 0, 255, 110, 255)
        val witherThroughWalls = BooleanSetting("Wither Doors Through Walls", "Draw Through Walls", true)
        val blood = BooleanSetting("Show Blood Doors", true)
        val bloodFill = BooleanSetting("Blood Door Fill", true)
        val bloodOutline = BooleanSetting("Blood Door Outline", true)
        val bloodFillColor = ColorSetting("Blood Door Fill Color", 255, 55, 60, 72)
        val bloodOutlineColor = ColorSetting("Blood Door Outline Color", 255, 55, 60, 255)
        val bloodKeyFillColor = ColorSetting("Blood Door Fill Color · Key", 0, 220, 88, 72)
        val bloodKeyOutlineColor = ColorSetting("Blood Door Outline Color · Key", 0, 255, 110, 255)
        val bloodThroughWalls = BooleanSetting("Blood Doors Through Walls", "Draw Through Walls", true)
        val visibleWitherDoors = NumberSetting("Wither Doors Ahead", 1f, 6f, 2f, true)
        val tracer = BooleanSetting("Trace Next Wither Door", true)
        val traceBloodDoor = BooleanSetting("Trace Blood Door", true)
        val bloodRushClassFilter = BooleanSetting("Filter by Blood Rush Class", false)
        val bloodRushTank = BooleanSetting("Tank", true)
        val bloodRushHealer = BooleanSetting("Healer", true)
        val bloodRushMage = BooleanSetting("Mage", true)
        val bloodRushBerserk = BooleanSetting("Berserk", true)
        val bloodRushArcher = BooleanSetting("Archer", true)
        val tracerColor = ColorSetting("Wither Door Tracer Color", 12, 0, 0, 220)
        val tracerWidth = NumberSetting("Wither Door Tracer Width", 0.5f, 5.0f, 3.0441177f)
        val tracerThroughWalls = BooleanSetting("Door Tracers Through Walls", "Draw Through Walls", true)
        val range = NumberSetting("Door Render Range", 16f, 256f, 192f, true)
        val debug = BooleanSetting("Debug Door Tracing", false)
    }

    @JvmRecord
    data class BloodRushTabClassResolution(
        val playerClass: DungeonClass?,
        val resolution: String?,
    ) {
        override fun toString(): String =
            "BloodRushTabClassResolution[playerClass=$playerClass, resolution=$resolution]"
    }

    class TracerRoomContext(
        private val physicalCellValue: DungeonGridRules.Cell?,
        private val mapCellValue: DungeonGridRules.Cell?,
        private val cellValue: DungeonGridRules.Cell?,
        footprintOffsets: List<DungeonMapGeometry.CellOffset>?,
        source: String?,
        private val rejectionReasonValue: String?,
    ) {
        private val footprintOffsetsValue: List<DungeonMapGeometry.CellOffset> =
            java.util.List.copyOf(footprintOffsets ?: java.util.List.of())
        private val sourceValue = source ?: "unknown"

        fun physicalCell(): DungeonGridRules.Cell? = physicalCellValue
        fun mapCell(): DungeonGridRules.Cell? = mapCellValue
        fun cell(): DungeonGridRules.Cell? = cellValue
        fun footprintOffsets(): List<DungeonMapGeometry.CellOffset> = footprintOffsetsValue
        fun source(): String = sourceValue
        fun rejectionReason(): String? = rejectionReasonValue
        fun confirmed(): Boolean = cellValue != null && rejectionReasonValue == null
        fun footprint(): Set<Int> = if (confirmed()) DungeonDoorAdjacency.footprint(cellValue!!, footprintOffsetsValue)
            else java.util.Set.of()

        override fun equals(other: Any?): Boolean = this === other ||
            (other is TracerRoomContext &&
                physicalCellValue == other.physicalCellValue &&
                mapCellValue == other.mapCellValue &&
                cellValue == other.cellValue &&
                footprintOffsetsValue == other.footprintOffsetsValue &&
                sourceValue == other.sourceValue &&
                rejectionReasonValue == other.rejectionReasonValue)

        override fun hashCode(): Int {
            var result = Objects.hashCode(physicalCellValue)
            result = 31 * result + Objects.hashCode(mapCellValue)
            result = 31 * result + Objects.hashCode(cellValue)
            result = 31 * result + Objects.hashCode(footprintOffsetsValue)
            result = 31 * result + Objects.hashCode(sourceValue)
            result = 31 * result + Objects.hashCode(rejectionReasonValue)
            return result
        }

        override fun toString(): String =
            "TracerRoomContext[physicalCell=$physicalCellValue, mapCell=$mapCellValue, cell=$cellValue, " +
                "footprintOffsets=$footprintOffsetsValue, source=$sourceValue, rejectionReason=$rejectionReasonValue]"
    }

    private data class BloodRushClassDecision(
        val playerClass: DungeonClass?, val resolution: String, val decision: String, val allowsVisuals: Boolean,
    )

    companion object {
        private val BLOOD_RUSH_CLASS_SUFFIX: Pattern = Pattern.compile("\\(([^()]*)\\)\\s*$")
        private val WHITESPACE_RUN: Pattern = Pattern.compile("\\s+")

        @JvmField
        val INSTANCE = BoxDoorsModule(Settings())

        @JvmStatic
        fun parseBloodRushClassFromTabLine(rawLine: String?): BloodRushTabClassResolution {
            if (rawLine == null || ChatStringSemantics.isBlank(rawLine))
                return BloodRushTabClassResolution(null, "tab-row-unrecognized")
            val suffix: Matcher = BLOOD_RUSH_CLASS_SUFFIX.matcher(ChatStringSemantics.strip(rawLine))
            if (!suffix.find()) return BloodRushTabClassResolution(null, "tab-row-unrecognized")
            val classAndLevel = ChatStringSemantics.strip(suffix.group(1))
            if (classAndLevel.isEmpty()) return BloodRushTabClassResolution(null, "tab-class-unrecognized")
            val className = WHITESPACE_RUN.split(classAndLevel, 2)[0]
            if (className.equals("DEAD", ignoreCase = true))
                return BloodRushTabClassResolution(null, "resolved-dead-row")
            val playerClass = DungeonClass.parse(className)
            return BloodRushTabClassResolution(playerClass,
                if (playerClass == null) "tab-class-unrecognized" else "resolved")
        }

        @JvmStatic
        fun bloodRushClassAllowsVisuals(filterEnabled: Boolean, playerClass: DungeonClass?, classSelected: Boolean): Boolean =
            !filterEnabled || (playerClass != null && classSelected)

        private fun routeMapInputDiagnostic(
            marker: DungeonMapGeometry.MapPoint?,
            map: DungeonMapGrid.ScanResult?,
            mapCell: BoxDoorsFairyRouteRules.MapCellResolution?,
            worldCell: DungeonGridRules.Cell?,
            currentWorldCell: Int,
        ): String {
            val layout = map?.layout()
            val markerText = if (marker == null) "unknown" else "${marker.x},${marker.z}"
            val layoutText = if (layout == null) "unavailable" else
                "${if (layout.available) "available" else "unavailable"}(start=${layout.startX},${layout.startY}" +
                    ",room=${layout.roomSize},gap=${layout.roomGap})"
            val mapCellText = if (mapCell == null || !mapCell.available()) "unknown" else mapCell.cell().toString()
            val worldCellText = if (worldCell == null) "unknown" else "$currentWorldCell(${cellId(worldCell)})"
            return "marker=$markerText,layout=$layoutText,mapCell=$mapCellText" +
                ",worldCell=$worldCellText,mapCellRejection=${mapCell?.rejectionReason() ?: "unavailable"}"
        }

        private fun promptScanName(result: DungeonDoorTracker.PromptScanResult?): String =
            result?.name?.lowercase(Locale.ROOT)?.replace('_', '-') ?: "unavailable"

        private fun chooseTracerTarget(
            route: BoxDoorsFairyRouteRules.Selection?, client: Minecraft?,
        ): BoxDoorsFairyRouteRules.TraceChoice {
            val playerPosition = client?.player?.blockPosition()
            val orderedWitherDoors = if (playerPosition == null) emptyList() else
                DungeonDoorTracker.orderedWitherDoors(playerPosition, Int.MAX_VALUE)
            val bloodDoor = if (playerPosition == null) null else DungeonDoorTracker.nextBloodDoor(playerPosition)
            return BoxDoorsFairyRouteRules.chooseTracerTarget(route, orderedWitherDoors, bloodDoor) { door ->
                if (client?.level == null) BloodDoorTraceRules.DoorPresence.UNKNOWN else doorPresence(client.level!!, door)
            }
        }

        @JvmStatic
        fun resolveTracerRoomContext(
            physicalCell: DungeonGridRules.Cell?,
            mapCell: DungeonGridRules.Cell?,
            calibratedMapCell: Boolean,
            knownMapRoom: Boolean,
            mapOffsets: List<DungeonMapGeometry.CellOffset>?,
        ): TracerRoomContext {
            val trustedMapCell = if (calibratedMapCell) mapCell else null
            val offsets = mapOffsets ?: emptyList()
            val validFootprint = knownMapRoom && validMapFootprint(mapCell, offsets)
            if (physicalCell != null && trustedMapCell != null) {
                if (physicalCell.componentX != trustedMapCell.componentX ||
                    physicalCell.componentZ != trustedMapCell.componentZ
                ) return TracerRoomContext(physicalCell, mapCell, null, emptyList(), "conflict",
                    "physical-map-cell-conflict")
                if (validFootprint)
                    return TracerRoomContext(physicalCell, mapCell, physicalCell, offsets, "physical+map-agree", null)
                return TracerRoomContext(physicalCell, mapCell, physicalCell,
                    listOf(DungeonMapGeometry.CellOffset(0, 0)), "physical", null)
            }
            if (physicalCell != null) return TracerRoomContext(physicalCell, mapCell, physicalCell,
                listOf(DungeonMapGeometry.CellOffset(0, 0)), "physical", null)
            if (trustedMapCell != null && validFootprint)
                return TracerRoomContext(null, mapCell, trustedMapCell, offsets, "map-fallback", null)
            val rejection = if (trustedMapCell == null) "current-cell-unknown" else "map-footprint-unconfirmed"
            return TracerRoomContext(null, mapCell, null, emptyList(), "unknown", rejection)
        }

        @JvmStatic
        fun tracerRoomAdjacent(
            target: DungeonDoorTracker.Door?, closed: Boolean, room: TracerRoomContext?,
        ): Boolean = target != null && closed && room != null && room.confirmed() &&
            DungeonDoorAdjacency.bordersCurrentRoom(target, room.cell(), room.footprintOffsets())

        @JvmStatic
        fun tracerCanRender(
            target: DungeonDoorTracker.Door?, presence: BloodDoorTraceRules.DoorPresence?, room: TracerRoomContext?,
        ): Boolean = target != null && (target.type() == DungeonDoorTracker.Type.WITHER ||
            target.type() == DungeonDoorTracker.Type.BLOOD) && presence == BloodDoorTraceRules.DoorPresence.CLOSED

        @JvmStatic
        fun tracerCanRender(
            target: DungeonDoorTracker.Door?, presence: BloodDoorTraceRules.DoorPresence?,
            room: TracerRoomContext?, orderedRouteTarget: Boolean,
        ): Boolean = tracerCanRender(target, presence, room)

        @JvmStatic
        fun shouldBypassTracerRange(
            target: DungeonDoorTracker.Door?, presence: BloodDoorTraceRules.DoorPresence?, room: TracerRoomContext?,
        ): Boolean = tracerCanRender(target, presence, room)

        @JvmStatic
        fun tracerWithinConfiguredRange(
            target: DungeonDoorTracker.Door?, presence: BloodDoorTraceRules.DoorPresence?, room: TracerRoomContext?,
            distanceSquared: Double, configuredRange: Int,
        ): Boolean = tracerWithinConfiguredRange(target, presence, room, distanceSquared, configuredRange, false)

        @JvmStatic
        fun tracerWithinConfiguredRange(
            target: DungeonDoorTracker.Door?, presence: BloodDoorTraceRules.DoorPresence?, room: TracerRoomContext?,
            distanceSquared: Double, configuredRange: Int, orderedRouteTarget: Boolean,
        ): Boolean = tracerCanRender(target, presence, room)

        private fun physicalPlayerCell(client: Minecraft?): DungeonGridRules.Cell? {
            val position = client?.player?.blockPosition() ?: return null
            return DungeonGridRules.cellAt(position.x, position.z)
        }

        private fun tracerRoomContext(client: Minecraft?): TracerRoomContext {
            val physicalCell = physicalPlayerCell(client)
            val map = DungeonMapRoomDetector.current()
            val mapCell = map?.currentCell
            val calibratedMapCell = map != null && map.hasCurrentCell()
            val geometry = map?.geometry
            val offsets = geometry?.connectedCells() ?: emptyList()
            val knownMapRoom = calibratedMapCell && geometry != null &&
                geometry.roomType() != DungeonMapGeometry.RoomType.UNKNOWN
            return resolveTracerRoomContext(physicalCell, mapCell, calibratedMapCell, knownMapRoom, offsets)
        }

        @Suppress("SENSELESS_COMPARISON") // Java callers can supply a null footprint entry.
        private fun validMapFootprint(
            origin: DungeonGridRules.Cell?, offsets: List<DungeonMapGeometry.CellOffset>?,
        ): Boolean {
            if (origin == null || offsets.isNullOrEmpty() || offsets.size > 36) return false
            val expected = TreeSet<Int>()
            var includesOrigin = false
            for (offset in offsets) {
                if (offset == null) return false
                val x = origin.componentX + offset.x
                val z = origin.componentZ + offset.z
                if (x !in 0..5 || z !in 0..5 || !expected.add(z * 6 + x)) return false
                if (offset.x == 0 && offset.z == 0) includesOrigin = true
            }
            if (!includesOrigin) return false
            return DungeonDoorAdjacency.footprint(origin, offsets).size == expected.size
        }

        private fun renderTracer(
            settings: Settings, client: Minecraft, pose: PoseStack, buffers: WorldRenderBuffer.BufferSource, camera: Vec3,
            target: DungeonDoorTracker.Door, presence: BloodDoorTraceRules.DoorPresence,
            room: TracerRoomContext, exactMapRoute: Boolean,
        ): Boolean {
            if (!tracerWithinConfiguredRange(target, presence, room,
                    client.player!!.distanceToSqr(target.centerX(), target.centerY(), target.centerZ()),
                    settings.range.value().roundToInt(), exactMapRoute)
            ) return false
            val endpoints = BlockEspTracerGeometry.endpoints(camera,
                client.gameRenderer.mainCamera().forwardVector(),
                Vec3(target.centerX(), target.centerY(), target.centerZ()), 0.5)
            EspRenderer.renderLine(pose, buffers,
                endpoints.start.x, endpoints.start.y, endpoints.start.z,
                endpoints.end.x, endpoints.end.y, endpoints.end.z,
                settings.tracerColor.argb(), settings.tracerWidth.value(),
                !settings.tracerThroughWalls.value())
            return true
        }

        private fun traceOutcome(
            selected: DungeonDoorTracker.Door?, presence: BloodDoorTraceRules.DoorPresence,
            eligible: Boolean, selectionRejection: String?,
        ): String {
            if (selected == null) return selectionRejection ?: "no-selected-door"
            if (presence == BloodDoorTraceRules.DoorPresence.OPEN) return "authoritatively-open"
            if (presence == BloodDoorTraceRules.DoorPresence.UNKNOWN) return "source-unknown"
            return if (eligible) "not-retained" else "not-eligible"
        }

        private fun tracerRoomDiagnostics(
            room: TracerRoomContext?, target: DungeonDoorTracker.Door?, presence: BloodDoorTraceRules.DoorPresence,
            rangeBypass: Boolean, outcome: String, orderedRouteTarget: Boolean,
        ): String {
            val physicalCell = room?.physicalCell()?.let(::cellId) ?: "unknown"
            val mapCell = room?.mapCell()?.let(::cellId) ?: "unknown"
            val selectedCell = if (room == null || !room.confirmed()) "unknown" else room.cell()?.let(::cellId) ?: "unknown"
            val footprint = if (room == null || !room.confirmed()) "unknown" else TreeSet(room.footprint()).toString()
            val adjacent = tracerRoomAdjacent(target, presence == BloodDoorTraceRules.DoorPresence.CLOSED, room)
            val rejection = when {
                target == null -> "target-unavailable"
                presence != BloodDoorTraceRules.DoorPresence.CLOSED -> "door-${presence.name.lowercase(Locale.ROOT)}"
                outcome.startsWith("class-filtered") -> "blood-rush-class-filter"
                else -> "none"
            }
            return "physicalCell=$physicalCell,mapCell=$mapCell,cell=$selectedCell" +
                ",cellSource=${room?.source() ?: "unknown"},footprint=$footprint,adjacent=$adjacent" +
                ",orderedTarget=$orderedRouteTarget,tracerRange=ignored,rejection=$rejection"
        }

        private fun cellId(cell: DungeonGridRules.Cell): String = "${cell.componentX},${cell.componentZ}"

        private fun doorId(door: DungeonDoorTracker.Door?): String = if (door == null) "none" else
            "${door.type()}[${door.firstCell()}-${door.secondCell()}@${door.x()},${door.y()},${door.z()}]"

        private fun doorPresence(
            level: ClientLevel?, target: DungeonDoorTracker.Door?,
        ): BloodDoorTraceRules.DoorPresence {
            if (level == null || target == null) return BloodDoorTraceRules.DoorPresence.UNKNOWN
            if (target.type() == DungeonDoorTracker.Type.BLOOD || target.type() == DungeonDoorTracker.Type.WITHER) {
                return BloodDoorTraceRules.doorPresence(target, target.type(), { level.hasChunkAt(it) }) { position ->
                    BoxDoorsHighlightRules.isDoorSourceState(target.type(),
                        DungeonDoorBlockOverlayController.authoritativeState(level, position))
                }
            }
            return BloodDoorTraceRules.DoorPresence.UNKNOWN
        }

        private fun scanKeyOwnership(client: Minecraft?): DungeonKeyRules.KeyOwnership {
            val player = client?.player ?: return DungeonKeyRules.KeyOwnership.NONE
            var wither = false
            var blood = false
            for (slot in 0 until player.inventory.containerSize) {
                val stack = player.inventory.getItem(slot)
                if (stack.isEmpty) continue
                val displayName = geiler.addons.client.utility.SkyblockItems.realName(stack)
                if (!wither) wither = DungeonKeyRules.isMatchingKey(displayName, DungeonDoorTracker.Type.WITHER)
                if (!blood) blood = DungeonKeyRules.isMatchingKey(displayName, DungeonDoorTracker.Type.BLOOD)
                if (wither && blood) break
            }
            if (!wither || !blood) {
                val carried = player.containerMenu.carried
                if (!carried.isEmpty) {
                    val displayName = geiler.addons.client.utility.SkyblockItems.realName(carried)
                    if (!wither) wither = DungeonKeyRules.isMatchingKey(displayName, DungeonDoorTracker.Type.WITHER)
                    if (!blood) blood = DungeonKeyRules.isMatchingKey(displayName, DungeonDoorTracker.Type.BLOOD)
                }
            }
            return DungeonKeyRules.KeyOwnership(wither, blood)
        }

        /** Small stable preview of shared W/B records; full source coordinates are logged by Glass Doors. */
        private fun sharedKeyDoorSummary(doors: List<DungeonDoorTracker.Door>): String {
            val keyDoors = doors.filter {
                it.type() == DungeonDoorTracker.Type.WITHER || it.type() == DungeonDoorTracker.Type.BLOOD
            }.sortedWith(compareBy<DungeonDoorTracker.Door> { it.type()?.name.orEmpty() }
                .thenBy { it.firstCell() }.thenBy { it.secondCell() })
            val preview = keyDoors.take(8).map { door ->
                "${door.type()}:${door.identity()}@${door.x()},${door.y()},${door.z()}" +
                    " sources=${door.sourcePositions().map(BlockPos::toShortString)}"
            }
            return "count=${keyDoors.size},preview=$preview" +
                if (keyDoors.size > preview.size) ",additional=${keyDoors.size - preview.size}" else ""
        }
    }
}
