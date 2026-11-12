package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.DungeonDoorTracker.Door
import geiler.addons.client.dungeon.DungeonDoorTracker.PromptScanResult
import geiler.addons.client.dungeon.DungeonDoorTracker.SpawnReference
import geiler.addons.client.dungeon.DungeonDoorTracker.StartDoorCaptureFrame
import geiler.addons.client.dungeon.DungeonDoorTracker.Type
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Arrays
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TreeMap

/** Stateful dungeon doorway scanner. All methods that read Minecraft state run on the client thread. */
object DungeonDoorTrackerLogic {
    private const val GRID_CELLS = 6
    private const val EDGES_PER_TICK = 4
    private const val LOADED_EDGE_RESCANS_PER_TICK = 12
    private const val SPAWN_START_RESCAN_TICKS = 20
    private const val SPAWN_START_MIN_FORWARD_DISTANCE = 16.0
    private const val SPAWN_START_MAX_FORWARD_DISTANCE = 20.0
    private const val SPAWN_START_MAX_YAW_DEVIATION = 10.0
    private const val PROMPT_EDGE_RESCANS_PER_TICK = 4
    private const val PROMPT_RESCAN_COOLDOWN_TICKS = 20L

    private val EDGES: List<Edge> = edges()
    private val edgeAvailabilityKnown = BooleanArray(EDGES.size)
    private val edgeWasAvailable = BooleanArray(EDGES.size)
    private val blockRescans: MutableSet<Int> = LinkedHashSet()
    private val loadedRescans: MutableSet<Int> = LinkedHashSet()
    private val promptRescans: MutableSet<Int> = LinkedHashSet()
    private val promptRescanTicks: MutableMap<Int, Long> = TreeMap()

    private var trackedLevel: ClientLevel? = null
    private var trackedDungeonActive = false
    private var dungeonSessionGeneration = 0L
    private var cursor = 0
    private var entranceCell = -1
    private var entranceChiseledCandidateEdges: Set<Int> = emptySet()
    private var entranceStartScans: Map<Int, DungeonDoorBlockReplacementRules.StartDoorVolumeScan> = emptyMap()
    private var uniqueEntranceStartEdge = -1
    private var entranceStartScanComplete = false
    private var lastPlayerCell = -1
    private var firstLoadedPlayer: SpawnReference? = null
    private var spawnStartDoor: Door? = null
    private var lastSpawnStartScanTick = Long.MIN_VALUE
    private var spawnStartDiagnostics = "waiting-for-first-loaded-player"
    private var entranceCandidateDiagnostics = "not-sampled"
    private var startDiagnosticsSummary = "unavailable"
    @Volatile private var snapshot: List<Door> = emptyList()

    @JvmStatic
    fun tick(client: Minecraft?) {
        val level = client?.level
        if (level != trackedLevel) {
            resetRunData()
            trackedLevel = level
            trackedDungeonActive = false
        }
        if (level == null || !DungeonContextTracker.inDungeon()) {
            if (trackedDungeonActive) resetRunData()
            trackedDungeonActive = false
            // Block hooks may still arrive while every consumer is disabled. Do not retain a queue
            // that would trigger a burst of stale edge scans when a consumer is re-enabled.
            blockRescans.clear()
            loadedRescans.clear()
            promptRescans.clear()
            snapshot = emptyList()
            startDiagnosticsSummary = "dungeon=false; map=" + DungeonMapRoomDetector.diagnostics() +
                "; firstLoadedPlayer=none; candidateEdges=" + entranceCandidateDiagnostics
            return
        }
        if (!trackedDungeonActive) {
            resetRunData()
            trackedLevel = level
            trackedDungeonActive = true
            dungeonSessionGeneration++
            if (dungeonSessionGeneration <= 0) dungeonSessionGeneration = 1
        }
        observeFirstLoadedPlayer(client)
        observeEntranceCell()
        observePlayerCell(client)
        observeChunkAvailability(level)
        observeEntranceStartCandidates(level)
        observeSpawnStartCandidate(level)
        updateStartDiagnosticsSummary()

        val inspectedThisTick: MutableSet<Int> = HashSet()
        var promptRescansThisTick = 0
        for (edgeIndex in promptRescans.toList()) {
            if (promptRescansThisTick >= PROMPT_EDGE_RESCANS_PER_TICK) break
            promptRescans.remove(edgeIndex)
            if (inspectedThisTick.contains(edgeIndex) || !edgeSamplesLoaded(level, EDGES[edgeIndex])) continue
            rescan(level, edgeIndex)
            inspectedThisTick.add(edgeIndex)
            promptRescansThisTick++
            promptRescanTicks[edgeIndex] = level.gameTime
        }
        for (edgeIndex in blockRescans.toList()) {
            if (inspectedThisTick.add(edgeIndex)) rescan(level, edgeIndex)
        }
        blockRescans.clear()
        var loadedRescansThisTick = 0
        for (edgeIndex in loadedRescans.toList()) {
            loadedRescans.remove(edgeIndex)
            if (inspectedThisTick.contains(edgeIndex)) continue
            rescan(level, edgeIndex)
            inspectedThisTick.add(edgeIndex)
            if (++loadedRescansThisTick >= LOADED_EDGE_RESCANS_PER_TICK) break
        }

        for (scanned in 0 until EDGES_PER_TICK) {
            val edgeIndex = cursor
            cursor = (cursor + 1) % EDGES.size
            if (inspectedThisTick.add(edgeIndex)) rescan(level, edgeIndex)
        }
    }

    @JvmStatic
    fun doors(): List<Door> {
        val selectedStart = spawnStartDoor
        if (selectedStart == null && snapshot.none { it.type() == Type.START }) return snapshot
        val result = ArrayList<Door>(snapshot.size + if (selectedStart == null) 0 else 1)
        for (door in snapshot) if (door.type() != Type.START) result.add(door)
        if (selectedStart != null) result.add(selectedStart)
        return java.util.List.copyOf(result)
    }

    /** Requests one bounded exact-edge rescan when its samples are already loaded; never loads chunks. */
    @JvmStatic
    fun requestPromptRescan(level: ClientLevel?, firstCell: Int, secondCell: Int): PromptScanResult {
        val edgeIndex = promptEdgeIndex(firstCell, secondCell)
        if (edgeIndex < 0) return PromptScanResult.INVALID_EDGE
        val ready = level != null && level == trackedLevel && trackedDungeonActive &&
            DungeonContextTracker.inDungeon()
        val loaded = ready && edgeSamplesLoaded(level!!, EDGES[edgeIndex])
        val tick = if (ready) level!!.gameTime else Long.MIN_VALUE
        val lastScanTick = promptRescanTicks.getOrDefault(edgeIndex, Long.MIN_VALUE)
        val decision = promptScanDecision(ready, loaded, promptRescans.contains(edgeIndex), lastScanTick, tick)
        if (decision == PromptScanResult.QUEUED) promptRescans.add(edgeIndex)
        return decision
    }

    /** Pure prompt-scan gate used by the client API and deterministic offline checks. */
    @JvmStatic
    fun promptScanDecision(
        trackerReady: Boolean,
        samplesLoaded: Boolean,
        alreadyQueued: Boolean,
        lastScanTick: Long,
        currentTick: Long,
    ): PromptScanResult {
        if (!trackerReady) return PromptScanResult.TRACKER_NOT_READY
        if (!samplesLoaded) return PromptScanResult.EDGE_UNLOADED
        if (alreadyQueued) return PromptScanResult.ALREADY_QUEUED
        if (lastScanTick != Long.MIN_VALUE && currentTick >= lastScanTick &&
            currentTick - lastScanTick < PROMPT_RESCAN_COOLDOWN_TICKS
        ) return PromptScanResult.RECENTLY_SCANNED
        return PromptScanResult.QUEUED
    }

    /** Bounded Start-room evidence for the automatic Glass Doors trace; this does not select targets. */
    @JvmStatic
    fun startDiagnosticsSummary(): String = startDiagnosticsSummary

    /** Monotonic identity for the active dungeon run, used only to reject stale temporary captures. */
    @JvmStatic
    fun dungeonSessionGeneration(): Long = dungeonSessionGeneration

    /**
     * Returns the calibrated Entrance cell and its world-grid origin for a temporary diagnostic capture.
     * This reuses the same map-to-grid conversion as automatic Start selection and performs no world writes.
     */
    @JvmStatic
    fun startDoorCaptureFrame(level: ClientLevel?): StartDoorCaptureFrame {
        val reading = DungeonMapRoomDetector.current()
        val mappedEntranceCell = diagnosticEntranceCell()
        var reason: String? = null
        if (!DungeonContextTracker.inDungeon()) reason = "dungeon-context-inactive"
        else if (level == null || level != trackedLevel || !trackedDungeonActive) {
            reason = "door-tracker-not-ready-for-current-world"
        } else if (reading == null || !reading.hasCurrentCell()) {
            reason = "calibrated-map-current-cell-unavailable"
        } else if (reading.mapId == null) {
            reason = "map-id-unavailable"
        } else if (mappedEntranceCell < 0) {
            reason = "map-derived-Entrance-grid-cell-unavailable"
        } else if (entranceCell != mappedEntranceCell) {
            reason = "door-tracker-Entrance-cell-not-current"
        }

        val cellX = if (mappedEntranceCell < 0) -1 else mappedEntranceCell % GRID_CELLS
        val cellZ = if (mappedEntranceCell < 0) -1 else mappedEntranceCell / GRID_CELLS
        val baseX = if (cellX < 0) 0 else DungeonGridRules.GRID_MIN + cellX * DungeonGridRules.CELL_STRIDE
        val baseZ = if (cellZ < 0) 0 else DungeonGridRules.GRID_MIN + cellZ * DungeonGridRules.CELL_STRIDE
        val calibration = if (reading == null) null else reading.calibration
        val geometry = if (reading == null) null else reading.geometry
        val entranceMapCell = if (calibration == null || !calibration.calibrated) null else
            DungeonMapGeometry.roomMapCell(
                calibration.entrance, calibration.entrance, calibration.roomPixelSize + 4,
            )
        val currentCell = if (reading == null) null else reading.currentCell
        val mapId = if (reading == null || reading.mapId == null) "unknown" else
            Integer.toString(reading.mapId!!.id())
        val currentMapCell = if (geometry == null) "unknown" else java.lang.String.valueOf(geometry.currentMapCell())
        val entrancePixel = entranceMapCell?.toString() ?: "unknown"
        val currentPhysicalCell = if (currentCell == null) "unknown" else
            currentCell.componentX.toString() + "," + currentCell.componentZ
        val summary = "mapReadiness=" +
            (if (reason == null) "ready" else "unavailable(" + reason + ")") +
            " mapStatus=" + (if (reading == null) "unavailable" else reading.status) +
            " mapId=" + mapId + " mapIdSource=" + (if (reading == null) "unknown" else reading.mapIdSource) +
            " runGeneration=" + dungeonSessionGeneration + " currentGridCell=" + currentPhysicalCell +
            " EntranceGridCell=" + (if (cellX < 0) "unknown" else "$cellX,$cellZ") +
            " EntranceWorldBase=" + (if (cellX < 0) "unknown" else "$baseX,$baseZ") +
            " entranceMapCell=" + entrancePixel + " currentMapCell=" + currentMapCell +
            " mapRoomPixelSize=" + (if (calibration == null) "unknown" else calibration.roomPixelSize) +
            " mapGridStride=" + (if (calibration == null) "unknown" else calibration.roomPixelSize + 4) +
            " currentTrackerEntranceCell=" + cellDescription(entranceCell) +
            " mapDiagnostics={" + DungeonMapRoomDetector.diagnostics() + "}"
        return StartDoorCaptureFrame(
            reason == null, reason, dungeonSessionGeneration, mapId, mappedEntranceCell,
            cellX, cellZ, baseX, baseZ, summary,
        )
    }

    /** First player pose after both dungeon context and the player's current chunk were ready. */
    @JvmStatic
    fun firstLoadedPlayer(): SpawnReference? = firstLoadedPlayer

    /** Called after a server block update has been applied on the client thread. */
    @JvmStatic
    fun onBlockChange(position: BlockPos?) {
        if (position == null || !DungeonContextTracker.inDungeon()) return
        for (edgeIndex in EDGES.indices) {
            val edge = EDGES[edgeIndex]
            if (changedBlockTouchesEdge(edge, position)) blockRescans.add(edgeIndex)
        }
    }

    /** Returns Wither doors in route order, falling back to the nearest observed doors if the graph is incomplete. */
    @JvmStatic
    fun orderedWitherDoors(playerPosition: BlockPos?, maximum: Int): List<Door> {
        if (playerPosition == null || maximum <= 0 || snapshot.isEmpty()) return emptyList()
        val start = nearestCellIndex(playerPosition)
        if (start < 0) return emptyList()
        var route = pathWitherDoors(start)
        if (route.isEmpty()) {
            route = snapshot.filter { it.type() == Type.WITHER }
                .sortedBy { distanceSquared(it, playerPosition) }
        }
        return if (route.size <= maximum) route else java.util.List.copyOf(route.subList(0, maximum))
    }

    /** A target is always available when a loaded Wither doorway is visible, even before the route is known. */
    @JvmStatic
    fun nextWitherDoor(playerPosition: BlockPos?): Door? {
        val doors = orderedWitherDoors(playerPosition, 1)
        return if (doors.isEmpty()) null else doors[0]
    }

    /** The single Blood room entrance, chosen from the loaded snapshot nearest to the player. */
    @JvmStatic
    fun nextBloodDoor(playerPosition: BlockPos?): Door? {
        if (playerPosition == null) return null
        return snapshot.filter { it.type() == Type.BLOOD }
            .minByOrNull { distanceSquared(it, playerPosition) }
    }

    /** True when the door borders the room currently occupied by the player. */
    @JvmStatic
    fun touchesCell(door: Door?, cell: DungeonGridRules.Cell?): Boolean {
        if (door == null || cell == null) return false
        val cellIndex = index(cell.componentX, cell.componentZ)
        return door.firstCell() == cellIndex || door.secondCell() == cellIndex
    }

    private fun pathWitherDoors(start: Int): List<Door> {
        val previous = IntArray(GRID_CELLS * GRID_CELLS) { -1 }
        val previousDoor = arrayOfNulls<Door>(previous.size)
        val queue = ArrayDeque<Int>()
        previous[start] = start
        queue.add(start)
        var bloodAdjacent = -1
        while (!queue.isEmpty()) {
            val current = queue.removeFirst()
            for (door in snapshot) {
                if (door.type() == Type.BLOOD &&
                    (door.firstCell() == current || door.secondCell() == current)
                ) {
                    bloodAdjacent = current
                    queue.clear()
                    break
                }
                if (door.type() == Type.BLOOD) continue
                val other = if (door.firstCell() == current) door.secondCell() else
                    if (door.secondCell() == current) door.firstCell() else -1
                if (other < 0 || previous[other] >= 0) continue
                previous[other] = current
                previousDoor[other] = door
                queue.add(other)
            }
        }
        if (bloodAdjacent < 0) return emptyList()
        val path = ArrayList<Door>()
        var at = bloodAdjacent
        while (at != start) {
            val door = previousDoor[at]
            if (at < 0 || door == null) throw NullPointerException()
            path.add(door)
            at = previous[at]
        }
        val ordered = ArrayList<Door>()
        for (i in path.size - 1 downTo 0) if (path[i].type() == Type.WITHER) ordered.add(path[i])
        return java.util.List.copyOf(ordered)
    }

    private fun nearestCellIndex(position: BlockPos): Int {
        val direct = DungeonGridRules.cellAt(position.x, position.z)
        if (direct != null) return index(direct.componentX, direct.componentZ)
        var closest: DungeonGridRules.Cell? = null
        var closestDistance = Double.POSITIVE_INFINITY
        for (dx in -1..1) for (dz in -1..1) {
            val candidate = DungeonGridRules.cellAt(position.x + dx, position.z + dz) ?: continue
            val centerX = candidate.baseX + DungeonGridRules.ROOM_SIZE / 2.0
            val centerZ = candidate.baseZ + DungeonGridRules.ROOM_SIZE / 2.0
            val x = position.x - centerX
            val z = position.z - centerZ
            val distance = x * x + z * z
            if (distance < closestDistance) {
                closest = candidate
                closestDistance = distance
            }
        }
        val selected = closest ?: return -1
        return index(selected.componentX, selected.componentZ)
    }

    private fun distanceSquared(door: Door, position: BlockPos): Double {
        val x = door.centerX() - position.x
        val y = door.centerY() - position.y
        val z = door.centerZ() - position.z
        return x * x + y * y + z * z
    }

    private fun inspect(level: ClientLevel, edgeIndex: Int, edge: Edge): Door? {
        val x = DungeonGridRules.GRID_MIN + edge.cellX * DungeonGridRules.CELL_STRIDE
        val z = DungeonGridRules.GRID_MIN + edge.cellZ * DungeonGridRules.CELL_STRIDE
        val planeX = if (edge.alongX) x + DungeonGridRules.ROOM_SIZE else x + 15
        val planeZ = if (edge.alongX) z + 15 else z + DungeonGridRules.ROOM_SIZE
        val center = BlockPos(planeX, DungeonGridRules.SCAN_MIN_Y, planeZ)
        val otherChunk = if (edge.alongX) center.east() else center.south()
        if (!level.hasChunkAt(center) || !level.hasChunkAt(otherChunk)) return null
        val block = DungeonDoorBlockOverlayController.authoritativeState(level, center) ?: return null
        val firstCell = index(edge.cellX, edge.cellZ)
        val secondCell = if (edge.alongX) index(edge.cellX + 1, edge.cellZ) else index(edge.cellX, edge.cellZ + 1)
        // The map-derived Entrance scan remains diagnostic-only. Start targets are published from the
        // first-loaded spawn-facing edge so a later map reading cannot switch the target mid-run.
        if (bothCellsBelongToCurrentRoom(firstCell, secondCell)) return null
        val sourceScan = scanDoorSourceVolume(level, edge, planeX, planeZ)
        if (!sourceScan.complete()) return null
        val witherSources = sourceScan.coalPositions()
        val sourceKind = DungeonDoorBlockReplacementRules.sourceDoorKind(sourceScan)
        val type: Type
        val sourcePositions: List<BlockPos>
        var sourceDiagnostics = ""
        if (sourceKind == DungeonDoorBlockReplacementRules.DoorKind.WITHER) {
            type = Type.WITHER
            sourcePositions = witherSources
            sourceDiagnostics = sourceScan.diagnosticSummary()
        } else {
            val bloodSources = sourceScan.redTerracottaPositions()
            if (sourceKind == DungeonDoorBlockReplacementRules.DoorKind.BLOOD) {
                type = Type.BLOOD
                sourcePositions = bloodSources
                sourceDiagnostics = sourceScan.diagnosticSummary()
            } else if (containsGlassDoor(level, edge, planeX, planeZ)) {
                type = Type.GLASS
                sourcePositions = emptyList()
            } else if ((block.isAir || block.`is`(Blocks.BARRIER)) && hasNormalFrame(level, edge, planeX, planeZ)) {
                type = Type.NORMAL
                sourcePositions = emptyList()
            } else return null
        }
        return centeredDoor(
            type, firstCell, secondCell, center,
            if (type == Type.WITHER || type == Type.BLOOD) sourcePositions else emptyList(),
            if (type == Type.WITHER || type == Type.BLOOD) sourceDiagnostics else "",
        )
    }

    /** A shared edge between occupied tiles of one multi-cell room is not a dungeon doorway. */
    private fun bothCellsBelongToCurrentRoom(firstCell: Int, secondCell: Int): Boolean {
        val first = cellKey(firstCell)
        val second = cellKey(secondCell)
        val retained = DungeonRoomTracker.currentFootprint()
        if (containsBoth(retained, first, second)) return true
        // Door scans can run before the optional room-footprint tracker has published its retained
        // state. Use the current calibrated map shape as direct confirmation in that case.
        return containsBoth(DungeonRoomTracker.currentMapFootprint(Minecraft.getInstance()), first, second)
    }

    private fun containsBoth(
        footprint: DungeonRoomTracker.MapRoomFootprint?,
        first: DungeonRoomTracker.CellKey,
        second: DungeonRoomTracker.CellKey,
    ): Boolean = footprint != null && footprint.containsCell(first) && footprint.containsCell(second)

    private fun cellKey(cellIndex: Int): DungeonRoomTracker.CellKey =
        DungeonRoomTracker.CellKey(cellIndex % GRID_CELLS, cellIndex / GRID_CELLS)

    /** Resolves the green map Entrance cell even when the player is standing in another room. */
    private fun observeEntranceCell() {
        val observed = diagnosticEntranceCell()
        if (observed == entranceCell) return
        queueCellEdges(entranceCell)
        entranceCell = observed
        entranceChiseledCandidateEdges = emptySet()
        entranceStartScans = emptyMap()
        uniqueEntranceStartEdge = -1
        entranceStartScanComplete = observed >= 0
        queueCellEdges(entranceCell)
    }

    /**
     * Finds a unique chiseled-brick volume in the gap on one of the map Entrance cell's edges.
     * This runs only in the shared client tick, never from the render-section Mixin.
     */
    private fun observeEntranceStartCandidates(level: ClientLevel) {
        val candidateEntranceCell = entranceCell
        if (candidateEntranceCell < 0) {
            entranceChiseledCandidateEdges = emptySet()
            entranceStartScans = emptyMap()
            uniqueEntranceStartEdge = -1
            entranceStartScanComplete = false
            entranceCandidateDiagnostics =
                "selectionEntranceGridCell=unknown; rejection=map-derived-Entrance-cell-unavailable"
            return
        }
        val matchingEdges = ArrayList<Int>()
        val edgeEvidence = ArrayList<String>()
        val scans: MutableMap<Int, DungeonDoorBlockReplacementRules.StartDoorVolumeScan> = HashMap()
        var scanComplete = true
        for (edgeIndex in EDGES.indices) {
            val edge = EDGES[edgeIndex]
            val firstCell = index(edge.cellX, edge.cellZ)
            val secondCell = if (edge.alongX) index(edge.cellX + 1, edge.cellZ) else index(edge.cellX, edge.cellZ + 1)
            if (!DungeonDoorBlockReplacementRules.touchesEntranceCell(candidateEntranceCell, firstCell, secondCell)) continue
            val planeX = edgePlaneX(edge)
            val planeZ = edgePlaneZ(edge)
            val scan = scanStartDoorVolume(level, edge, planeX, planeZ)
            scans[edgeIndex] = scan
            if (!scan.complete()) {
                scanComplete = false
                edgeEvidence.add(
                    startEdgeEvidence(
                        edgeIndex, edge, firstCell, secondCell, planeX, planeZ,
                        scan.loadedCount().toString() + "/48", scan.diagnosticSummary(), "waiting-for-loaded-samples",
                    ),
                )
                continue
            }
            val candidate = DungeonDoorBlockReplacementRules.isStartGapCandidate(true, scan)
            val rejection = if (candidate) "candidate-chiseled-source-present" else "no-chiseled-source"
            edgeEvidence.add(
                startEdgeEvidence(
                    edgeIndex, edge, firstCell, secondCell, planeX, planeZ,
                    scan.loadedCount().toString() + "/48", scan.diagnosticSummary(), rejection,
                ),
            )
            if (candidate) matchingEdges.add(edgeIndex)
        }
        val mappedEntranceCell = diagnosticEntranceCell()
        val mapping = mapEntranceMappingSummary()
        entranceCandidateDiagnostics = "selectionEntranceGridCell=" + cellDescription(entranceCell) +
            " candidateEntranceGridCell=" + cellDescription(candidateEntranceCell) +
            " mapDerivedEntranceGridCell=" + cellDescription(mappedEntranceCell) +
            " mapping={" + mapping + "} scanComplete=" + scanComplete +
            " matches=" + matchingEdges + " uniqueEdge=" +
            DungeonDoorBlockReplacementRules.uniqueEntranceStartEdge(scanComplete, matchingEdges) +
            " selectedPositions=" + (if (matchingEdges.size == 1)
                scans[matchingEdges.first()]!!.sourcePositions().map { it.toShortString() }
            else emptyList<String>()) + " edgeCandidates=" + edgeEvidence
        val candidates = java.util.Set.copyOf(matchingEdges)
        val uniqueEdge = DungeonDoorBlockReplacementRules.uniqueEntranceStartEdge(scanComplete, matchingEdges)
        if (candidates != entranceChiseledCandidateEdges || uniqueEdge != uniqueEntranceStartEdge ||
            scanComplete != entranceStartScanComplete || scans != entranceStartScans
        ) {
            entranceChiseledCandidateEdges = candidates
            entranceStartScans = java.util.Map.copyOf(scans)
            uniqueEntranceStartEdge = uniqueEdge
            entranceStartScanComplete = scanComplete
            queueCellEdges(entranceCell)
        }
    }

    /**
     * Selects the unique source-connected chiseled panel at the spawn player's forward grid edge.
     * This runs on the shared client tick and samples only the 60 positions in that loaded edge volume.
     */
    private fun observeSpawnStartCandidate(level: ClientLevel) {
        if (spawnStartDoor != null) return
        val tick = level.gameTime
        if (lastSpawnStartScanTick != Long.MIN_VALUE &&
            tick - lastSpawnStartScanTick < SPAWN_START_RESCAN_TICKS
        ) return
        lastSpawnStartScanTick = tick
        val spawn = firstLoadedPlayer
        if (spawn == null) {
            spawnStartDiagnostics = "status=waiting rejection=first-loaded-player-unavailable loaded=0/60"
            return
        }

        val normalizedYaw = Math.floorMod(Math.round(spawn.yaw.toDouble() * 1000.0).toInt(), 360_000) / 1000.0
        val quadrant = Math.floorMod(Math.round(normalizedYaw / 90.0).toInt(), 4)
        val cardinalYaw = quadrant * 90.0
        val yawDeviation = Math.abs(normalizeYawDelta(normalizedYaw - cardinalYaw))
        if (yawDeviation > SPAWN_START_MAX_YAW_DEVIATION) {
            spawnStartDiagnostics = String.format(
                Locale.ROOT,
                "status=waiting rejection=spawn-yaw-not-cardinal yaw=%.1f nearestCardinal=%.0f deviation=%.1f limit=%.1f loaded=0/60",
                spawn.yaw, cardinalYaw, yawDeviation, SPAWN_START_MAX_YAW_DEVIATION,
            )
            return
        }
        val forwardX = when (quadrant) { 1 -> -1; 3 -> 1; else -> 0 }
        val forwardZ = when (quadrant) { 0 -> 1; 2 -> -1; else -> 0 }
        val anchor = spawn.blockPosition ?: run {
            spawnStartDiagnostics = "status=waiting rejection=spawn-block-position-unavailable loaded=0/60"
            return
        }
        val playerCell = DungeonGridRules.cellAt(anchor.x, anchor.z)
        if (playerCell == null) {
            spawnStartDiagnostics = "status=waiting rejection=spawn-anchor-outside-dungeon-grid anchor=" +
                anchor.toShortString() + " loaded=0/60"
            return
        }
        val nextCellX = playerCell.componentX + forwardX
        val nextCellZ = playerCell.componentZ + forwardZ
        if (nextCellX < 0 || nextCellX >= GRID_CELLS || nextCellZ < 0 || nextCellZ >= GRID_CELLS) {
            spawnStartDiagnostics = "status=waiting rejection=spawn-forward-edge-outside-dungeon-grid cell=" +
                cellDescription(index(playerCell.componentX, playerCell.componentZ)) +
                " forward=" + forwardX + "," + forwardZ + " loaded=0/60"
            return
        }
        val alongX = forwardX != 0
        val edge = Edge(
            minOf(playerCell.componentX, nextCellX), minOf(playerCell.componentZ, nextCellZ), alongX,
        )
        val firstCell = index(edge.cellX, edge.cellZ)
        val secondCell = if (alongX) index(edge.cellX + 1, edge.cellZ) else index(edge.cellX, edge.cellZ + 1)
        val planeX = edgePlaneX(edge)
        val planeZ = edgePlaneZ(edge)
        val planeCenter = BlockPos(planeX, DungeonGridRules.SCAN_MIN_Y, planeZ)
        val deltaX = planeX + 0.5 - (anchor.x + 0.5)
        val deltaZ = planeZ + 0.5 - (anchor.z + 0.5)
        val forwardDistance = deltaX * forwardX + deltaZ * forwardZ
        val lateralDistance = deltaX * forwardZ - deltaZ * forwardX
        val positions = DungeonDoorBlockReplacementRules.spawnStartDoorVolumePositions(planeCenter, alongX)
        val loaded: MutableSet<Long> = HashSet()
        val materials: MutableMap<Long, String> = TreeMap()
        for (position in positions) {
            if (!level.hasChunkAt(position)) continue
            val state = DungeonDoorBlockOverlayController.authoritativeState(level, position) ?: continue
            loaded.add(position.asLong())
            val id = BuiltInRegistries.BLOCK.getKey(state.block)
            materials[position.asLong()] = if (id == null) "unknown" else id.toString()
        }
        val scan = DungeonDoorBlockReplacementRules.scanSpawnStartDoorVolume(planeCenter, alongX, loaded, materials)
        val orientation = if (alongX) "normal-X/tangent-Z" else "normal-Z/tangent-X"
        val geometry = String.format(
            Locale.ROOT,
            "anchor=%s yaw=%.1f yawDeviation=%.1f forward=%d,%d edge=%s-%s orientation=%s plane=%d,%d " +
                "forwardCenter=%.2f lateralCenter=%.2f expectedForwardCenter=[%.0f..%.0f] bounds=x[%d..%d],y[%d..%d],z[%d..%d]",
            anchor.toShortString(), spawn.yaw, yawDeviation, forwardX, forwardZ,
            cellDescription(firstCell), cellDescription(secondCell), orientation, planeX, planeZ,
            forwardDistance, lateralDistance, SPAWN_START_MIN_FORWARD_DISTANCE, SPAWN_START_MAX_FORWARD_DISTANCE,
            if (alongX) planeX - 1 else planeX - 2, if (alongX) planeX + 1 else planeX + 2,
            DungeonGridRules.SCAN_MIN_Y, DungeonGridRules.SCAN_MIN_Y + 3,
            if (alongX) planeZ - 2 else planeZ - 1, if (alongX) planeZ + 2 else planeZ + 1,
        )
        val summary = geometry + " volume=5x4x3 " + scan.diagnosticSummary()
        if (!scan.complete()) {
            spawnStartDiagnostics = "status=waiting rejection=spawn-window-not-fully-loaded selection=" +
                scan.selectionOutcome() + " " + summary
            return
        }
        if (!isSpawnStartForwardDistanceInRange(forwardDistance)) {
            spawnStartDiagnostics = "status=waiting rejection=spawn-forward-center-out-of-range " + summary
            return
        }
        val panel = scan.uniquePanelPositions()
        if (panel.isEmpty()) {
            spawnStartDiagnostics = "status=waiting rejection=" + scan.selectionOutcome() + " " + summary
            return
        }
        val minX = if (alongX) planeX - 1 else planeX - 2
        val minZ = if (alongX) planeZ - 2 else planeZ - 1
        spawnStartDoor = Door(
            Type.START, firstCell, secondCell, minX, DungeonGridRules.SCAN_MIN_Y,
            minZ, if (alongX) 3 else 5, 4, if (alongX) 5 else 3, panel,
            "locked-spawn-forward-edge " + summary,
        )
        spawnStartDiagnostics = "status=selected locked=true selection=first-complete-unique-scan " + summary
        queueCellEdges(firstCell)
        queueCellEdges(secondCell)
    }

    @JvmStatic
    fun isSpawnStartForwardDistanceInRange(forwardDistance: Double): Boolean =
        !(forwardDistance < SPAWN_START_MIN_FORWARD_DISTANCE || forwardDistance > SPAWN_START_MAX_FORWARD_DISTANCE)

    private fun normalizeYawDelta(degrees: Double): Double =
        Math.floorMod(Math.round((degrees + 180.0) * 1000.0), 360_000L) / 1000.0 - 180.0

    private fun startEdgeEvidence(
        edgeIndex: Int,
        edge: Edge,
        firstCell: Int,
        secondCell: Int,
        planeX: Int,
        planeZ: Int,
        loaded: String,
        histogram: String,
        rejection: String,
    ): String {
        val orientation = if (edge.alongX) "X-neighbor-edge/normal-X/tangent-Z" else "Z-neighbor-edge/normal-Z/tangent-X"
        val bounds = if (edge.alongX)
            "x[${planeX - 1}..${planeX + 1}],y[69..72],z[${planeZ - 1}..${planeZ + 2}]"
        else "x[${planeX - 1}..${planeX + 2}],y[69..72],z[${planeZ - 1}..${planeZ + 1}]"
        return "edge#$edgeIndex cells=" + cellDescription(firstCell) + "-" + cellDescription(secondCell) +
            " orientation=" + orientation + " bounds=" + bounds + " loaded=" + loaded +
            " materials=" + histogram + " result=" + rejection
    }

    private fun cellDescription(cell: Int): String = if (cell < 0 || cell >= GRID_CELLS * GRID_CELLS) "unknown" else
        (cell % GRID_CELLS).toString() + "," + (cell / GRID_CELLS)

    /** Only an in-grid position can anchor the start-door scan; the pre-teleport default (0,100,0) cannot. */
    @JvmStatic
    fun isUsableSpawnAnchor(blockX: Int, blockZ: Int): Boolean = DungeonGridRules.cellAt(blockX, blockZ) != null

    private fun observeFirstLoadedPlayer(client: Minecraft?) {
        if (firstLoadedPlayer != null || client == null || trackedLevel == null) return
        val player = client.player ?: return
        val position = player.blockPosition()
        val level = trackedLevel ?: return
        if (!level.hasChunkAt(position)) return
        // Keep waiting (and re-sampling next tick) until the server teleports the player into the dungeon grid.
        if (!isUsableSpawnAnchor(position.x, position.z)) return
        firstLoadedPlayer = SpawnReference(
            player.x, player.y, player.z, player.yRot, position.immutable(), level.gameTime,
        )
    }

    private fun updateStartDiagnosticsSummary() {
        startDiagnosticsSummary = "firstLoadedPlayer=" + describeSpawn(firstLoadedPlayer) +
            "; spawnSelector={" + spawnStartDiagnostics + "}" +
            "; mapEntrance={" + mapEntranceMappingSummary() + "}" +
            "; mapStartCrossCheck={" + mapStartCrossCheckSummary() + "}" +
            "; mapEntranceCandidateScan={" + entranceCandidateDiagnostics + "}" +
            "; selector=spawn-forward-grid-edge-unique-loaded-source-panel; map=diagnostic-only"
    }

    private fun mapStartCrossCheckSummary(): String {
        if (spawnStartDoor == null) return "spawnTarget=not-selected"
        if (entranceCell < 0) return "result=unavailable(map-Entrance-cell-not-ready)"
        if (!entranceStartScanComplete) return "result=unavailable(map-edge-scans-incomplete)"
        if (uniqueEntranceStartEdge < 0) return "result=no-unique-map-candidate"
        val scan = entranceStartScans[uniqueEntranceStartEdge]
        if (scan == null || !scan.complete()) return "result=unavailable(unique-map-scan-incomplete)"
        val spawnSources: MutableSet<Long> = HashSet()
        for (position in spawnStartDoor!!.sourcePositions()) spawnSources.add(position.asLong())
        val mapSources: MutableSet<Long> = HashSet()
        for (position in scan.sourcePositions()) mapSources.add(position.asLong())
        val overlap = mapSources.count { spawnSources.contains(it) }
        return "result=unique-map-candidate edge#$uniqueEntranceStartEdge mapSources=" + mapSources.size +
            " spawnSources=" + spawnSources.size + " shared=" + overlap +
            " exactSourceSetMatch=" + (mapSources == spawnSources)
    }

    private fun describeSpawn(spawn: SpawnReference?): String {
        if (spawn == null) return "waiting-for-loaded-player-position"
        return String.format(
            Locale.ROOT,
            "block=%s,pos=%.2f,%.2f,%.2f,yaw=%.1f,tick=%d",
            spawn.blockPosition?.toShortString() ?: "unknown", spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.tick,
        )
    }

    private fun mapEntranceMappingSummary(): String {
        val reading = DungeonMapRoomDetector.current()
        if (reading == null) return "status=unavailable"
        val calibration = reading.calibration
        val geometry = reading.geometry
        val physicalCell = reading.currentCell
        val currentMapCell = geometry?.currentMapCell()
        if (calibration == null || !calibration.calibrated || geometry == null || physicalCell == null || currentMapCell == null) {
            return "status=" + reading.status + ", roomType=" +
                (geometry?.roomType()?.toString() ?: "unknown") +
                ", mapId=" + (if (reading.mapId == null) "unknown" else reading.mapId!!.id()) +
                ", mapReason=" + (if (reading.fallbackReason == null) "none" else reading.fallbackReason)
        }
        val stride = calibration.roomPixelSize + 4
        val entranceMapCell = DungeonMapGeometry.roomMapCell(calibration.entrance, calibration.entrance, stride)
        val pixelDeltaX = if (entranceMapCell == null) Int.MIN_VALUE else entranceMapCell.x - currentMapCell.x
        val pixelDeltaZ = if (entranceMapCell == null) Int.MIN_VALUE else entranceMapCell.z - currentMapCell.z
        val aligned = stride > 0 && pixelDeltaX != Int.MIN_VALUE && pixelDeltaZ != Int.MIN_VALUE &&
            Math.floorMod(pixelDeltaX, stride) == 0 && Math.floorMod(pixelDeltaZ, stride) == 0
        val mappedX = if (aligned) physicalCell.componentX + pixelDeltaX / stride else -1
        val mappedZ = if (aligned) physicalCell.componentZ + pixelDeltaZ / stride else -1
        val mapped = if (mappedX >= 0 && mappedX < GRID_CELLS && mappedZ >= 0 && mappedZ < GRID_CELLS)
            index(mappedX, mappedZ) else -1
        return "status=" + reading.status + ", currentRoom=" + geometry.roomType() +
            ", mapId=" + (if (reading.mapId == null) "unknown" else reading.mapId!!.id()) +
            ", calibrationEntrancePixel=" + calibration.entrance +
            ", roomPixelSize=" + calibration.roomPixelSize + ", stride=" + stride +
            ", entranceMapCell=" + entranceMapCell + ", currentMapCell=" + currentMapCell +
            ", currentGridCell=" + cellDescription(index(physicalCell.componentX, physicalCell.componentZ)) +
            ", mapPixelDelta=" + pixelDeltaX + "," + pixelDeltaZ +
            ", gridAligned=" + aligned + ", mapDerivedEntranceGridCell=" + cellDescription(mapped) +
            ", mapping=selection-anchor"
    }

    private fun diagnosticEntranceCell(): Int {
        val reading = DungeonMapRoomDetector.current() ?: return -1
        val calibration = reading.calibration ?: return -1
        if (!calibration.calibrated) return -1
        val geometry = reading.geometry ?: return -1
        val currentCell = reading.currentCell ?: return -1
        val currentMapCell = geometry.currentMapCell() ?: return -1
        val stride = calibration.roomPixelSize + 4
        val entranceMapCell = DungeonMapGeometry.roomMapCell(calibration.entrance, calibration.entrance, stride)
            ?: return -1
        val deltaX = entranceMapCell.x - currentMapCell.x
        val deltaZ = entranceMapCell.z - currentMapCell.z
        if (Math.floorMod(deltaX, stride) != 0 || Math.floorMod(deltaZ, stride) != 0) return -1
        val x = currentCell.componentX + deltaX / stride
        val z = currentCell.componentZ + deltaZ / stride
        return if (x >= 0 && x < GRID_CELLS && z >= 0 && z < GRID_CELLS) index(x, z) else -1
    }

    private fun resetRunData() {
        cursor = 0
        entranceCell = -1
        entranceChiseledCandidateEdges = emptySet()
        entranceStartScans = emptyMap()
        uniqueEntranceStartEdge = -1
        entranceStartScanComplete = false
        lastPlayerCell = -1
        firstLoadedPlayer = null
        spawnStartDoor = null
        lastSpawnStartScanTick = Long.MIN_VALUE
        spawnStartDiagnostics = "waiting-for-first-loaded-player"
        entranceCandidateDiagnostics = "not-sampled"
        startDiagnosticsSummary = "unavailable"
        Arrays.fill(edgeAvailabilityKnown, false)
        Arrays.fill(edgeWasAvailable, false)
        blockRescans.clear()
        loadedRescans.clear()
        promptRescans.clear()
        promptRescanTicks.clear()
        snapshot = emptyList()
    }

    private fun edgePlaneX(edge: Edge): Int {
        val x = DungeonGridRules.GRID_MIN + edge.cellX * DungeonGridRules.CELL_STRIDE
        return if (edge.alongX) x + DungeonGridRules.ROOM_SIZE else x + 15
    }

    private fun edgePlaneZ(edge: Edge): Int {
        val z = DungeonGridRules.GRID_MIN + edge.cellZ * DungeonGridRules.CELL_STRIDE
        return if (edge.alongX) z + 15 else z + DungeonGridRules.ROOM_SIZE
    }

    /** Newly loaded adjacent chunks are noticed on the next client tick and prioritized for scanning. */
    private fun observeChunkAvailability(level: ClientLevel) {
        for (edgeIndex in EDGES.indices) {
            val loaded = edgeSamplesLoaded(level, EDGES[edgeIndex])
            if (DungeonDoorRefreshRules.shouldRescanAvailability(
                    edgeAvailabilityKnown[edgeIndex], edgeWasAvailable[edgeIndex], loaded,
                )
            ) {
                if (loaded) loadedRescans.add(edgeIndex) else {
                    loadedRescans.remove(edgeIndex)
                    val edge = EDGES[edgeIndex]
                    val firstCell = index(edge.cellX, edge.cellZ)
                    val secondCell = if (edge.alongX) index(edge.cellX + 1, edge.cellZ) else
                        index(edge.cellX, edge.cellZ + 1)
                    snapshot = DungeonDoorRefreshRules.onChunkUnload(snapshot, firstCell, secondCell)
                }
            }
            edgeAvailabilityKnown[edgeIndex] = true
            edgeWasAvailable[edgeIndex] = loaded
        }
    }

    private fun edgeSamplesLoaded(level: ClientLevel, edge: Edge): Boolean {
        val x = DungeonGridRules.GRID_MIN + edge.cellX * DungeonGridRules.CELL_STRIDE
        val z = DungeonGridRules.GRID_MIN + edge.cellZ * DungeonGridRules.CELL_STRIDE
        val planeX = if (edge.alongX) x + DungeonGridRules.ROOM_SIZE else x + 15
        val planeZ = if (edge.alongX) z + 15 else z + DungeonGridRules.ROOM_SIZE
        val center = BlockPos(planeX, DungeonGridRules.SCAN_MIN_Y, planeZ)
        val otherChunk = if (edge.alongX) center.east() else center.south()
        if (!level.hasChunkAt(center) || !level.hasChunkAt(otherChunk)) return false
        // The 3x3 sample square spans at most two chunks per axis, and the plane coordinate itself shares a
        // chunk with one of its neighbours, so the four corner samples cover every chunk without a set.
        for (sampleX in intArrayOf(planeX - 1, planeX + 1)) {
            for (sampleZ in intArrayOf(planeZ - 1, planeZ + 1)) {
                if (!level.hasChunkAt(BlockPos(sampleX, DungeonGridRules.SCAN_MIN_Y, sampleZ))) return false
            }
        }
        return true
    }

    @JvmStatic
    fun promptEdgeIndex(firstCell: Int, secondCell: Int): Int {
        if (firstCell < 0 || firstCell >= GRID_CELLS * GRID_CELLS ||
            secondCell < 0 || secondCell >= GRID_CELLS * GRID_CELLS
        ) return -1
        val firstX = firstCell % GRID_CELLS
        val firstZ = firstCell / GRID_CELLS
        val secondX = secondCell % GRID_CELLS
        val secondZ = secondCell / GRID_CELLS
        val dx = kotlin.math.abs(firstX - secondX)
        val dz = kotlin.math.abs(firstZ - secondZ)
        if (dx + dz != 1) return -1
        val cellX = minOf(firstX, secondX)
        val cellZ = minOf(firstZ, secondZ)
        val alongX = dx == 1
        for (edgeIndex in EDGES.indices) {
            val edge = EDGES[edgeIndex]
            if (edge.cellX == cellX && edge.cellZ == cellZ && edge.alongX == alongX) return edgeIndex
        }
        return -1
    }

    private fun changedBlockTouchesEdge(edge: Edge, position: BlockPos): Boolean {
        val x = DungeonGridRules.GRID_MIN + edge.cellX * DungeonGridRules.CELL_STRIDE
        val z = DungeonGridRules.GRID_MIN + edge.cellZ * DungeonGridRules.CELL_STRIDE
        val planeX = if (edge.alongX) x + DungeonGridRules.ROOM_SIZE else x + 15
        val planeZ = if (edge.alongX) z + 15 else z + DungeonGridRules.ROOM_SIZE
        return DungeonDoorRefreshRules.changedBlockTouchesEdge(
            edge.alongX, planeX, planeZ, DungeonGridRules.SCAN_MIN_Y, position.x, position.y, position.z,
        )
    }

    private fun observePlayerCell(client: Minecraft?) {
        val player = client?.player ?: return
        val position = player.blockPosition()
        val cell = DungeonGridRules.cellAt(position.x, position.z)
        val observed = if (cell == null) -1 else index(cell.componentX, cell.componentZ)
        if (observed == lastPlayerCell) return
        queueCellEdges(lastPlayerCell)
        lastPlayerCell = observed
        queueCellEdges(lastPlayerCell)
    }

    private fun queueCellEdges(cellIndex: Int) {
        if (cellIndex < 0 || cellIndex >= GRID_CELLS * GRID_CELLS) return
        for (edgeIndex in EDGES.indices) {
            val edge = EDGES[edgeIndex]
            val firstCell = index(edge.cellX, edge.cellZ)
            val secondCell = if (edge.alongX) index(edge.cellX + 1, edge.cellZ) else
                index(edge.cellX, edge.cellZ + 1)
            if (firstCell == cellIndex || secondCell == cellIndex) blockRescans.add(edgeIndex)
        }
    }

    private fun rescan(level: ClientLevel, edgeIndex: Int) {
        val edge = EDGES[edgeIndex]
        val firstCell = index(edge.cellX, edge.cellZ)
        val secondCell = if (edge.alongX) index(edge.cellX + 1, edge.cellZ) else
            index(edge.cellX, edge.cellZ + 1)
        val detected = inspect(level, edgeIndex, edge)
        snapshot = DungeonDoorRefreshRules.refreshEdge(snapshot, firstCell, secondCell, detected)
    }

    private fun containsGlassDoor(level: ClientLevel, edge: Edge, x: Int, z: Int): Boolean {
        var glassSamples = 0
        for (side in -1..1) for (height in 0..3) {
            val sample = if (edge.alongX)
                BlockPos(x, DungeonGridRules.SCAN_MIN_Y + height, z + side)
            else BlockPos(x + side, DungeonGridRules.SCAN_MIN_Y + height, z)
            if (!level.hasChunkAt(sample)) return false
            val state = DungeonDoorBlockOverlayController.authoritativeState(level, sample) ?: return false
            val blockId = BuiltInRegistries.BLOCK.getKey(
                state.block,
            ).path
            if (blockId == "glass" || blockId == "glass_pane" ||
                blockId.endsWith("_stained_glass") || blockId.endsWith("_stained_glass_pane")
            ) glassSamples++
        }
        return glassSamples >= 3
    }

    /** Reads the complete existing 3×4×3 bounds from authoritative client-side server-state memory. */
    private fun scanDoorSourceVolume(
        level: ClientLevel,
        edge: Edge,
        x: Int,
        z: Int,
    ): DungeonDoorBlockReplacementRules.DoorSourceVolumeScan {
        val center = BlockPos(x, DungeonGridRules.SCAN_MIN_Y, z)
        val loaded: MutableSet<Long> = HashSet()
        val materials: MutableMap<Long, String> = TreeMap()
        for (position in DungeonDoorBlockReplacementRules.doorSourceVolumePositions(center, edge.alongX)) {
            if (!level.hasChunkAt(position)) continue
            val state = DungeonDoorBlockOverlayController.authoritativeState(level, position) ?: continue
            loaded.add(position.asLong())
            materials[position.asLong()] = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        }
        return DungeonDoorBlockReplacementRules.scanDoorSourceVolume(center, edge.alongX, loaded, materials)
    }

    /** Scans the complete 4×4×3 Start candidate volume without loading any chunks. */
    private fun scanStartDoorVolume(
        level: ClientLevel,
        edge: Edge,
        x: Int,
        z: Int,
    ): DungeonDoorBlockReplacementRules.StartDoorVolumeScan {
        val center = BlockPos(x, DungeonGridRules.SCAN_MIN_Y, z)
        val loaded: MutableSet<Long> = HashSet()
        val materials: MutableMap<Long, String> = TreeMap()
        for (position in DungeonDoorBlockReplacementRules.startDoorVolumePositions(center, edge.alongX)) {
            if (!level.hasChunkAt(position)) continue
            val state = DungeonDoorBlockOverlayController.authoritativeState(level, position) ?: continue
            loaded.add(position.asLong())
            materials[position.asLong()] = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        }
        return DungeonDoorBlockReplacementRules.scanStartDoorVolume(center, edge.alongX, loaded, materials)
    }

    /** Keeps standard Wither/Blood door bounds at 3×4×3 around the detected edge plane. */
    @JvmStatic
    fun centeredDoor(type: Type?, firstCell: Int, secondCell: Int, planeCenter: BlockPos?): Door =
        centeredDoor(type, firstCell, secondCell, planeCenter, emptyList())

    @JvmStatic
    fun centeredDoor(
        type: Type?,
        firstCell: Int,
        secondCell: Int,
        planeCenter: BlockPos?,
        sourcePositions: List<BlockPos?>?,
    ): Door = centeredDoor(type, firstCell, secondCell, planeCenter, sourcePositions, "")

    @JvmStatic
    fun centeredDoor(
        type: Type?,
        firstCell: Int,
        secondCell: Int,
        planeCenter: BlockPos?,
        sourcePositions: List<BlockPos?>?,
        sourceDiagnostics: String?,
    ): Door {
        val center = planeCenter!!
        return Door(
            type, firstCell, secondCell, center.x - 1, center.y, center.z - 1,
            3, 4, 3, sourcePositions, sourceDiagnostics,
        )
    }

    private fun hasNormalFrame(level: ClientLevel, edge: Edge, x: Int, z: Int): Boolean {
        val floorY = DungeonGridRules.SCAN_MIN_Y
        var solidJambs = 0
        for (side in intArrayOf(-2, 2)) for (height in 1..3) {
            val jamb = if (edge.alongX)
                BlockPos(x, floorY + height, z + side)
            else BlockPos(x + side, floorY + height, z)
            if (!level.hasChunkAt(jamb)) return false
            val state = DungeonDoorBlockOverlayController.authoritativeState(level, jamb) ?: return false
            if (!state.isAir) solidJambs++
        }
        return solidJambs >= 4
    }

    private fun edges(): List<Edge> {
        val edges = ArrayList<Edge>(60)
        for (z in 0 until GRID_CELLS) for (x in 0 until GRID_CELLS) {
            if (x + 1 < GRID_CELLS) edges.add(Edge(x, z, true))
            if (z + 1 < GRID_CELLS) edges.add(Edge(x, z, false))
        }
        return java.util.List.copyOf(edges)
    }

    private fun index(x: Int, z: Int): Int = z * GRID_CELLS + x

    private data class Edge(val cellX: Int, val cellZ: Int, val alongX: Boolean)
}
