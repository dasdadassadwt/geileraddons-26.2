package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.DungeonMapRoomDetector
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

/** Kotlin owner for client-thread, loaded-only puzzle observation. */
class DungeonOdinPuzzleObserverCore {
    private data class WaterBoardProbe(val patternId: Int, val block: net.minecraft.world.level.block.Block, val relativePosition: BlockPos)
    private data class WaterBoardLever(val id: String, val label: String, val relativePosition: BlockPos)
    private data class WaterBoardScan(
        val detection: DungeonWaterBoardRules.Detection?,
        val leverPositions: Map<String, BlockPos>,
        val signatureFallback: Boolean,
        val rejectionReason: String?,
    )
    private data class CreeperBeamsSnapshot(
        val detection: DungeonCreeperBeamsRules.Detection,
        val trackingDetection: DungeonCreeperBeamsRules.Detection?,
        val catalog: DungeonCreeperBeamsCatalog.CatalogResult?,
        val observations: List<DungeonCreeperBeamsRules.ObservedBlock>,
        val newlyResolvedRotation: Int?,
    )
    private data class WeirdosClaim(val correctChest: Boolean)
    private data class Room(val floor: String, val signature: String, val cell: DungeonGridRules.Cell, val roomName: String?, val shape: String)
    private data class AssistModuleObservation(val id: String, val health: Int)

    companion object {
        private const val ROOM_SCAN_PERIOD_TICKS = 10
        private const val BLAZE_SCAN_PERIOD_TICKS = 5
        private const val WATER_BOARD_SCAN_PERIOD_TICKS = 5
        private val WATER_BOARD_MARKERS = listOf(
            WaterBoardProbe(0, Blocks.TERRACOTTA, BlockPos(14, 77, 27)),
            WaterBoardProbe(1, Blocks.EMERALD_BLOCK, BlockPos(16, 78, 27)),
            WaterBoardProbe(2, Blocks.DIAMOND_BLOCK, BlockPos(14, 78, 27)),
            WaterBoardProbe(3, Blocks.QUARTZ_BLOCK, BlockPos(14, 78, 27)),
        )
        private val WATER_BOARD_LEVERS = listOf(
            WaterBoardLever("coal_block", "Coal", BlockPos(20, 61, 10)),
            WaterBoardLever("gold_block", "Gold", BlockPos(20, 61, 15)),
            WaterBoardLever("quartz_block", "Quartz", BlockPos(20, 61, 20)),
            WaterBoardLever("diamond_block", "Diamond", BlockPos(10, 61, 20)),
            WaterBoardLever("emerald_block", "Emerald", BlockPos(10, 61, 15)),
            WaterBoardLever("hardened_clay", "Clay", BlockPos(10, 61, 10)),
            WaterBoardLever("water", "Water", BlockPos(15, 60, 5)),
        )
    }

    private var roomSignature: String? = null
    private var lastIceScanTick = Long.MIN_VALUE
    private var lastBlazeScanTick = Long.MIN_VALUE
    private var lastBoulderScanTick = Long.MIN_VALUE
    private var lastWaterBoardScanTick = Long.MIN_VALUE
    private var lastCreeperBeamsScanTick = Long.MIN_VALUE
    private var ticTacToeRoomIdentity: String? = null
    private var ticTacToeLevelIdentity: Any? = null
    private val ticTacToeSuggestion = DungeonTicTacToeSuggestionHold<DungeonOdinPuzzleObserver.WorldBlock>()
    private var ticTacToePredictedXTarget: DungeonOdinPuzzleObserver.WorldBlock? = null
    private var ticTacToePredictedOTarget: DungeonOdinPuzzleObserver.WorldBlock? = null
    private val ticTacToeHold = DungeonTicTacToeTargetHold()
    private var ticTacToeDiagnostic = "board=unknown;reason=not-scanned;pieces=0;X=0;O=0;turn=unknown;suggested=-;prediction=off;xReply=-;nextO=-"
    private var waterBoardRoomIdentity: String? = null
    private var waterBoardLevelIdentity: Any? = null
    private var creeperBeamsRoomIdentity: String? = null
    private var creeperBeamsLevelIdentity: Any? = null
    private var creeperBeamsResolvedRotation: Int? = null
    private var boulderRoomIdentity: String? = null
    private var boulderLevelIdentity: Any? = null
    private var boulderDetection: DungeonBoulderRules.Detection? = null
    private var boulderScanDiagnostic = "not-scanned"
    private val boulderProgress = DungeonBoulderProgressTracker()
    private var activeIceFillStrategy = DungeonOdinPuzzleObserver.IceFillStrategy.EASY
    private var iceRouteStrategy: DungeonOdinPuzzleObserver.IceFillStrategy? = null
    private var iceWorldRoute: List<IceFillRenderGeometry.Point> = emptyList()
    private var iceScanComplete = false
    private var iceLevelIdentity: Any? = null
    private var iceRoomIdentity: String? = null
    private var icePathBoard: Array<BooleanArray>? = null
    private var icePathSilverfish: DungeonIcePathSolver.Cell? = null
    private var icePathRoute: List<DungeonIcePathSolver.Cell> = emptyList()
    private var icePathWorldRoute: List<DungeonIcePathSolver.Point> = emptyList()
    private var icePathLevelIdentity: Any? = null
    private var icePathRoomIdentity: String? = null
    private var icePathScanDiagnostic = "not-scanned"
    private var portalScanComplete = false
    private var portalLevelIdentity: Any? = null
    private var tpMazeCatalogDetection: DungeonTpMazeRules.Detection? = null
    private var tpMazeIdentityDiagnostic = "not-observed"
    private var tpMazeTransformDiagnostic = "not-attempted"
    private var tpMazeCatalogDiagnostic = "not-attempted"
    private var tpMazeCorrectionDiagnostic = "none"
    private val tpMazeVisitedPadIds = HashSet<Int>()
    private val tpMazeViewCandidateIds = LinkedHashSet<Int>()
    private var tpMazeCurrentPadId: Int? = null
    private var tpMazeSuggestedPadId: Int? = null
    private val quizTracker = DungeonQuizTracker()
    private var quizVisualDiagnostic = "not-rendered"
    private var quizChatStyleDiagnostic = "idle"
    private val visualRoomCoreScanner = OdinDungeonMapCoreScanner()
    private var cachedBlazes: List<AssistModuleObservation> = emptyList()
    private var cachedWaterBoardScan: WaterBoardScan? = null
    private var waterBoardFallbackDiagnostic = "not-active"
    private var waterBoardProgress: WaterBoardProgress? = null
    private var cachedCreeperBeams: CreeperBeamsSnapshot? = null
    private val weirdosStatements: MutableMap<String, String> = LinkedHashMap()
    private val weirdosMarkers = DungeonWeirdosMarkerCache()
    private var weirdosLevelIdentity: Any? = null

    /** Reads only the already-confirmed current puzzle room. */
    fun observe(client: Minecraft?, clientTick: Long, selection: String?, blazeRoomPosition: String?): DungeonOdinPuzzleObserver.Guidance? =
        observe(client, clientTick, selection, blazeRoomPosition, "STANDARD", DungeonOdinPuzzleObserver.IceFillStrategy.EASY)

    fun observe(client: Minecraft?, clientTick: Long, selection: String?, blazeRoomPosition: String?, waterBoardStrategy: String?): DungeonOdinPuzzleObserver.Guidance? =
        observe(client, clientTick, selection, blazeRoomPosition, waterBoardStrategy, DungeonOdinPuzzleObserver.IceFillStrategy.EASY)

    fun observe(client: Minecraft?, clientTick: Long, selection: String?, blazeRoomPosition: String?, waterBoardStrategy: String?, iceFillStrategy: DungeonOdinPuzzleObserver.IceFillStrategy?): DungeonOdinPuzzleObserver.Guidance? =
        observe(client, clientTick, selection, blazeRoomPosition, waterBoardStrategy, iceFillStrategy, true)

    fun observe(client: Minecraft?, clientTick: Long, selection: String?, blazeRoomPosition: String?, waterBoardStrategy: String?, iceFillStrategy: DungeonOdinPuzzleObserver.IceFillStrategy?, creeperBeamGuidanceEnabled: Boolean): DungeonOdinPuzzleObserver.Guidance? =
        observe(client, clientTick, selection, blazeRoomPosition, waterBoardStrategy, iceFillStrategy, creeperBeamGuidanceEnabled,
            setOf("WATER_BOARD", "TP_MAZE", "ICE_PATH", "ICE_FILL", "BLAZE", "CREEPER_BEAMS", "THREE_WEIRDOS", "QUIZ", "BOULDER", "TIC_TAC_TOE"))

    fun observe(client: Minecraft?, clientTick: Long, selection: String?, blazeRoomPosition: String?, waterBoardStrategy: String?, iceFillStrategy: DungeonOdinPuzzleObserver.IceFillStrategy?, creeperBeamGuidanceEnabled: Boolean, enabledSolvers: Set<String>?): DungeonOdinPuzzleObserver.Guidance? =
        observe(client, clientTick, selection, blazeRoomPosition, waterBoardStrategy, iceFillStrategy, creeperBeamGuidanceEnabled, enabledSolvers, false)

    fun observe(client: Minecraft?, clientTick: Long, selection: String?, blazeRoomPosition: String?, waterBoardStrategy: String?, iceFillStrategy: DungeonOdinPuzzleObserver.IceFillStrategy?, creeperBeamGuidanceEnabled: Boolean, enabledSolvers: Set<String>?, ticTacToePredictionEnabled: Boolean): DungeonOdinPuzzleObserver.Guidance? {
        activeIceFillStrategy = iceFillStrategy ?: DungeonOdinPuzzleObserver.IceFillStrategy.EASY
        val allowed = enabledSolvers ?: emptySet()
        val automaticWaterBoard = selection == "AUTO" && allowed.contains("WATER_BOARD")
        waterBoardFallbackDiagnostic = if (automaticWaterBoard) "source=signature-fallback;state=not-attempted" else "not-active"
        if (!allowed.contains("QUIZ")) {
            quizTracker.reset()
            quizVisualDiagnostic = "solver=disabled"
        }
        val room = currentRoom(client)
        if (room == null) {
            if (automaticWaterBoard) rejectWaterBoardFallback(boundedDiagnostic(tpMazeIdentityDiagnostic, 64))
            clearIcePathRoute("room-unconfirmed")
            clearTicTacToe("room-unconfirmed")
            return null
        }
        ensureRoom(room)
        ensurePortalLevel(client?.level)
        if (!allowed.contains("TIC_TAC_TOE") || room.roomName != "Tic Tac Toe" ||
            (selection != "AUTO" && selection != "TIC_TAC_TOE")) {
            clearTicTacToe(if (!allowed.contains("TIC_TAC_TOE")) "solver-disabled" else "inactive")
        }
        if (!allowed.contains("ICE_PATH") || room.roomName != "Ice Path")
            clearIcePathRoute(if (!allowed.contains("ICE_PATH")) "solver-disabled" else "different-room")
        val observeCreeperBeams = selection == "CREEPER_BEAMS" && allowed.contains("CREEPER_BEAMS") && creeperBeamGuidanceEnabled ||
            (selection == "AUTO" && allowed.contains("CREEPER_BEAMS") && creeperBeamGuidanceEnabled &&
                DungeonCreeperBeamsRules.isCreeperBeamsRoom(room.roomName))
        if (!observeCreeperBeams) clearCreeperBeamsObservation()

        if (selection == "ICE_FILL" && allowed.contains("ICE_FILL"))
            return orFallback(observeIceFill(client, clientTick, room, true, activeIceFillStrategy), "Ice Fill",
                "The exact Ice Fill room and one loaded catalog pattern are required.")
        if (selection == "ICE_PATH" && allowed.contains("ICE_PATH")) return observeIcePath(client, clientTick, room)
        if (selection == "TP_MAZE" && allowed.contains("TP_MAZE"))
            return orFallback(observeTpMaze(client, clientTick, room, false), "TP Maze",
                "The exact Teleport Maze room and a unique loaded catalog orientation are required.")
        if (selection == "BLAZE" && allowed.contains("BLAZE"))
            return orFallback(observeBlazes(client, clientTick, room, blazeRoomPosition), "Blaze",
                "No confirmed Blaze target is available; wait for a complete room scan and a resolved health label.")
        if (selection == "THREE_WEIRDOS" && allowed.contains("THREE_WEIRDOS"))
            return orFallback(observeWeirdos(), "Three Weirdos", "Waiting for explicit clue chat from three confirmed room NPCs.")
        if (selection == "QUIZ" && allowed.contains("QUIZ"))
            return orFallback(observeQuiz(client), "Quiz", "Waiting for an Oruo question in the detected Puzzle room.")
        if (selection == "WATER_BOARD" && allowed.contains("WATER_BOARD"))
            return observeWaterBoard(client, clientTick, room, waterBoardStrategy)
        if (selection == "CREEPER_BEAMS" && allowed.contains("CREEPER_BEAMS"))
            return observeCreeperBeams(client, clientTick, room)
        if (selection == "BOULDER" && allowed.contains("BOULDER"))
            return orFallback(observeBoulder(client, clientTick, room), "Boulder", "The exact Boulder room and a unique loaded signature are required.")
        if (selection == "TIC_TAC_TOE" && allowed.contains("TIC_TAC_TOE") && room.roomName == "Tic Tac Toe")
            return observeTicTacToe(client, room, ticTacToePredictionEnabled)
        if (selection != "AUTO") return null

        val autoMode = DungeonPuzzleAutoSelectionRules.selectedMode(room.roomName, creeperBeamGuidanceEnabled)
        if (autoMode == null) {
            if (allowed.contains("TP_MAZE")) {
                val maze = observeTpMaze(client, clientTick, room, false)
                if (tpMazeCatalogDetection?.known() == true) return maze
            }
            if (allowed.contains("THREE_WEIRDOS") && weirdosStatements.isNotEmpty()) return observeWeirdos()
            if (allowed.contains("WATER_BOARD")) {
                if (!room.roomName.isNullOrBlank()) {
                    rejectWaterBoardFallback("room-name-present")
                } else if (room.shape != "1x1") {
                    rejectWaterBoardFallback("footprint-not-1x1")
                } else {
                    waterBoardFallbackDiagnostic = "source=signature-fallback;state=checking;reason=awaiting-signature"
                    val waterBoard = observeWaterBoard(client, clientTick, room, waterBoardStrategy, true)
                    if (waterBoard != null) return waterBoard
                }
            }
            return null
        }
        if (!allowed.contains(autoMode)) return null
        return when (autoMode) {
            "WATER_BOARD" -> observeWaterBoard(client, clientTick, room, waterBoardStrategy)
            "TP_MAZE" -> orFallback(observeTpMaze(client, clientTick, room, false), "TP Maze",
                "The exact Teleport Maze room and a unique loaded catalog orientation are required.")
            "ICE_FILL" -> orFallback(observeIceFill(client, clientTick, room, true, activeIceFillStrategy), "Ice Fill",
                "The exact Ice Fill room and one loaded catalog pattern are required.")
            "ICE_PATH" -> observeIcePath(client, clientTick, room)
            "BLAZE" -> orFallback(observeBlazes(client, clientTick, room, blazeRoomPosition), "Blaze",
                "No confirmed Blaze target is available; wait for a complete room scan and a resolved health label.")
            "CREEPER_BEAMS" -> observeCreeperBeams(client, clientTick, room)
            "THREE_WEIRDOS" -> orFallback(observeWeirdos(), "Three Weirdos", "Waiting for explicit clue chat from three confirmed room NPCs.")
            "QUIZ" -> orFallback(observeQuiz(client), "Quiz", "Waiting for an Oruo question in the detected Puzzle room.")
            "BOULDER" -> orFallback(observeBoulder(client, clientTick, room), "Boulder",
                "The exact Boulder room and a unique loaded signature are required.")
            "TIC_TAC_TOE" -> observeTicTacToe(client, room, ticTacToePredictionEnabled)
            else -> null
        }
    }

    /** Captures a clue only when its explicit speaker is a loaded, non-player entity in this room. */
    fun onChat(client: Minecraft?, rawMessage: String?) { onChat(client, rawMessage, true, true) }

    /** Applies only the solvers enabled for this active puzzle chat path. */
    fun onChat(client: Minecraft?, rawMessage: String?, weirdosEnabled: Boolean, quizEnabled: Boolean): Boolean {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || rawMessage == null) return false
        val level = client.level ?: return false
        if (!DungeonContextTracker.inDungeon()) {
            quizTracker.reset()
            quizVisualDiagnostic = "dungeon=inactive"
            return false
        }
        val room = currentRoom(client)
        if (!quizEnabled) {
            quizTracker.reset()
            quizVisualDiagnostic = "solver=disabled"
            quizChatStyleDiagnostic = "disabled"
        } else if (DungeonQuizRules.isOruoFinalAnswer(rawMessage)) {
            quizTracker.reset()
            quizVisualDiagnostic = "final-answer"
            quizChatStyleDiagnostic = "cleared"
        } else {
            val inQuizRoom = room?.roomName == "Quiz"
            quizTracker.onChat(rawMessage, true, inQuizRoom)
            val option = quizTracker.confirmedOption()
            if (option != null) quizChatStyleDiagnostic = "pending-${quizOptionLabel(option.marker())}"
            quizVisualDiagnostic = when {
                room == null -> "room=unknown;observing-explicit"
                inQuizRoom -> "room=quiz;observing"
                else -> "room=not-quiz;retained"
            }
        }
        if (!weirdosEnabled || room == null || room.roomName != "Three Weirdos") return false
        ensureRoom(room)
        ensureWeirdosLevel(level)
        weirdosMarkers.beginRoom(roomIdentity(room), level)
        val explicitSpeaker = DungeonOdinPuzzleObserverLogic.explicitNpcSpeaker(rawMessage) ?: return false
        val npc = confirmedNpc(client, explicitSpeaker) ?: return false
        val canonicalSpeaker = entityName(npc)
        val clue = parseExplicitNpcClue(rawMessage, setOf(canonicalSpeaker)) ?: return false
        val statement = clue.statement() ?: return false
        weirdosStatements[canonicalSpeaker] = statement
        val claim = parseWeirdosClaim(statement) ?: return false
        if (!level.hasChunkAt(npc.blockPosition())) return false
        val identity = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
        val transform = sourceRoomTransform(client, identity)
        if (transform == null || !transform.resolved()) return false
        val clay = BlockPos(transform.clayPosition().x(), transform.clayPosition().y(), transform.clayPosition().z())
        val chest = sourceWeirdosChestPosition(npc, clay, transform.rotation())
        weirdosMarkers.put(roomIdentity(room), level, entityName(npc), chest.x, chest.y, chest.z, claim.correctChest)
        return claim.correctChest
    }

    fun visualSnapshot(client: Minecraft?): DungeonOdinPuzzleObserver.PuzzleVisualSnapshot {
        if (client == null || !client.isSameThread) return DungeonOdinPuzzleObserver.PuzzleVisualSnapshot.empty()
        if (client.player == null || client.level == null) {
            quizVisualDiagnostic = "world=unavailable"
            return DungeonOdinPuzzleObserver.PuzzleVisualSnapshot.empty()
        }
        if (!DungeonContextTracker.inDungeon()) {
            quizVisualDiagnostic = "dungeon=inactive"
            return DungeonOdinPuzzleObserver.PuzzleVisualSnapshot.empty()
        }
        val room = currentRoom(client)
        if (room == null) {
            quizVisualDiagnostic = "identity=" + boundedDiagnostic(tpMazeIdentityDiagnostic, 72)
            return DungeonOdinPuzzleObserver.PuzzleVisualSnapshot.empty()
        }
        ensureRoom(room)
        ensureWeirdosLevel(client.level)
        weirdosMarkers.beginRoom(roomIdentity(room), client.level)
        val roomIdentity = OdinDungeonRoomIdentity.currentPuzzleRoom(client) ?: return DungeonOdinPuzzleObserver.PuzzleVisualSnapshot.empty()
        return DungeonOdinPuzzleObserver.PuzzleVisualSnapshot(
            waterBoardVisual(client, room), creeperPairVisuals(client, room), weirdosVisuals(client, room, roomIdentity), quizVisuals(client, room),
        )
    }

    fun currentPuzzleRoomDisplayName(client: Minecraft?): String? {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || !DungeonContextTracker.inDungeon()) return null
        val room = currentRoom(client) ?: return null
        if (DungeonPuzzleAutoSelectionRules.selectedMode(room.roomName, true) != null) return room.roomName
        return if (tpMazeCatalogDetection?.known() == true && roomIdentity(room) == roomSignature) "Teleport Maze" else null
    }

    fun currentPuzzleRoomIdentity(client: Minecraft?): String? {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || !DungeonContextTracker.inDungeon()) return null
        val room = currentRoom(client) ?: return null
        if (DungeonPuzzleAutoSelectionRules.selectedMode(room.roomName, true) == null) return null
        return "${room.floor}|${room.signature}|${room.cell.componentX},${room.cell.componentZ}|${room.roomName}"
    }

    fun currentPuzzleRoomMapIdentity(client: Minecraft?): String? {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || !DungeonContextTracker.inDungeon()) return null
        val room = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
        return if (room == null) null else OdinDungeonRoomIdentity.mapIdentity(room.floor(), room.mapSignature(), room.cell())
    }

    fun tpMazeDiagnosticSummary(client: Minecraft?): String {
        if (client == null || !client.isSameThread) return "thread=unavailable"
        if (client.player == null || client.level == null) return "world=unavailable"
        if (!DungeonContextTracker.inDungeon()) return "dungeon=inactive"
        val room = currentRoom(client)
        if (room == null) return boundedDiagnostic("identity=$tpMazeIdentityDiagnostic; maze-state=not-current; correction=$tpMazeCorrectionDiagnostic", 220)
        if (roomIdentity(room) != roomSignature) return boundedDiagnostic("identity=$tpMazeIdentityDiagnostic; maze-state=not-scanned", 220)
        val detection = when {
            tpMazeCatalogDetection == null -> "unscanned"
            tpMazeCatalogDetection!!.known() -> "ready:r${tpMazeCatalogDetection!!.rotation()}:pads${tpMazeCatalogDetection!!.pads().size}"
            else -> "unknown:${tpMazeCatalogDetection!!.unknownReason()}"
        }
        val padCount = tpMazeCatalogDetection?.pads()?.size ?: 0
        val summary = "identity=$tpMazeIdentityDiagnostic transform=$tpMazeTransformDiagnostic catalog=$tpMazeCatalogDiagnostic " +
            "detection=$detection pads=$padCount current=${valueOrDash(tpMazeCurrentPadId)} visited=${tpMazeVisitedPadIds.size} " +
            "candidates=${tpMazeViewCandidateIds.size} next=${valueOrDash(tpMazeSuggestedPadId)} correction=$tpMazeCorrectionDiagnostic"
        return boundedDiagnostic(summary, 320)
    }

    fun ticTacToeVisualTargets(client: Minecraft?): DungeonOdinPuzzleObserver.TicTacToeVisualTargets {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || !DungeonContextTracker.inDungeon() ||
            !DungeonContextTracker.isMobEspPhase()) return DungeonOdinPuzzleObserver.TicTacToeVisualTargets.empty()
        val room = currentRoom(client)
        if (room == null || room.roomName != "Tic Tac Toe" || client.level !== ticTacToeLevelIdentity || roomIdentity(room) != ticTacToeRoomIdentity)
            return DungeonOdinPuzzleObserver.TicTacToeVisualTargets.empty()
        return DungeonOdinPuzzleObserver.TicTacToeVisualTargets(ticTacToeSuggestion.current(), ticTacToePredictedXTarget, ticTacToePredictedOTarget)
    }

    fun ticTacToeDiagnosticSummary(): String = boundedDiagnostic(ticTacToeDiagnostic, 240)

    private fun observeTicTacToe(
        client: Minecraft?,
        room: Room,
        predictionEnabled: Boolean,
    ): DungeonOdinPuzzleObserver.Guidance {
        ticTacToePredictedXTarget = null
        ticTacToePredictedOTarget = null
        ticTacToeSuggestion.scanWithoutSuggestion()
        if (client == null || !client.isSameThread || client.player == null || client.level == null)
            return ticTacToeUnavailable("client-unavailable", "Board not confirmed: client world is unavailable.")
        val level = client.level ?:
            return ticTacToeUnavailable("client-unavailable", "Board not confirmed: client world is unavailable.")
        val identity = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
        val transform = sourceRoomTransform(client, identity)
        if (identity == null || transform == null || !transform.resolved())
            return ticTacToeUnavailable("transform-unresolved", "Board not confirmed: room orientation is unresolved.")

        val currentIdentity = roomIdentity(room)
        val clay = BlockPos(transform.clayPosition().x(), transform.clayPosition().y(), transform.clayPosition().z())
        val clayPosition = DungeonTicTacToeRules.Position(clay.x, clay.y, clay.z)
        val boardPositions = ArrayList<BlockPos>(9)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                val position = ticTacToeWorldPosition(
                    DungeonTicTacToeRules.Cell(row, column), clayPosition, transform.rotation(),
                ) ?: return ticTacToeUnavailable("position-invalid", "Board not confirmed: a board square has no world position.")
                if (!level.hasChunkAt(position))
                    return ticTacToeUnavailable("cell-unloaded", "Board not confirmed: a board chunk is unavailable.")
                boardPositions.add(position)
            }
        }

        val minX = boardPositions.minOf { it.x }
        val minY = boardPositions.minOf { it.y }
        val minZ = boardPositions.minOf { it.z }
        val maxX = boardPositions.maxOf { it.x }
        val maxY = boardPositions.maxOf { it.y }
        val maxZ = boardPositions.maxOf { it.z }
        val bounds = AABB(minX - 0.2, minY - 0.2, minZ - 0.2, maxX + 1.2, maxY + 1.2, maxZ + 1.2)
        val pieces = ArrayList<DungeonTicTacToeRules.Piece>(9)
        for (frame in level.getEntities(EntityTypeTest.forClass(ItemFrame::class.java), bounds) { entity ->
            !entity.isRemoved && entity.isAlive
        }) {
            val item = frame.item
            if (item.isEmpty) continue
            val roomPosition = DungeonTicTacToeRules.toRoom(
                DungeonTicTacToeRules.Position(frame.blockPosition().x, frame.blockPosition().y, frame.blockPosition().z),
                clayPosition, transform.rotation(),
            ) ?: continue
            pieces.add(DungeonTicTacToeRules.Piece(roomPosition.x, roomPosition.y, roomPosition.z, ticTacToeMapColor(item, client)))
        }

        val analysis = DungeonTicTacToeRules.analyze(pieces)
        ticTacToeRoomIdentity = currentIdentity
        ticTacToeLevelIdentity = level
        if (analysis.status() == DungeonTicTacToeRules.Status.READY) {
            val target = ticTacToeWorldPosition(analysis.suggestedCell(), clayPosition, transform.rotation())
            if (target == null || !level.hasChunkAt(target))
                return ticTacToeUnavailable("target-unloaded", "Board not confirmed: the suggested square is unavailable.")
            ticTacToeSuggestion.suggest(DungeonOdinPuzzleObserver.WorldBlock(target.x, target.y, target.z))
            val suggested = cellLabel(analysis.suggestedCell())
            val lines = ArrayList<String>()
            lines.add("Place O at row ${analysis.suggestedCell()!!.row + 1}, column ${analysis.suggestedCell()!!.column + 1}.")
            val prediction = if (predictionEnabled) DungeonTicTacToeRules.predict(pieces, analysis) else
                DungeonTicTacToeRules.Prediction(DungeonTicTacToeRules.PredictionStatus.UNAVAILABLE, null, null)
            var predictionState = if (predictionEnabled)
                prediction.status().name.lowercase(Locale.ROOT).replace('_', '-') else "disabled"
            var xReply = "-"
            var nextO = "-"
            if (predictionEnabled && prediction.xReply() != null) {
                val xTarget = ticTacToeWorldPosition(prediction.xReply(), clayPosition, transform.rotation())
                if (xTarget != null && level.hasChunkAt(xTarget)) {
                    ticTacToePredictedXTarget = DungeonOdinPuzzleObserver.WorldBlock(xTarget.x, xTarget.y, xTarget.z)
                    xReply = cellLabel(prediction.xReply())
                    val xReplyCell = prediction.xReply()!!
                    lines.add("If you place it, X's unique best reply is row ${xReplyCell.row + 1}, column ${xReplyCell.column + 1}.")
                    if (prediction.nextOMove() != null) {
                        val nextOMoveCell = prediction.nextOMove()!!
                        val oTarget = ticTacToeWorldPosition(nextOMoveCell, clayPosition, transform.rotation())
                        if (oTarget != null && level.hasChunkAt(oTarget)) {
                            ticTacToePredictedOTarget = DungeonOdinPuzzleObserver.WorldBlock(oTarget.x, oTarget.y, oTarget.z)
                            nextO = cellLabel(nextOMoveCell)
                            lines.add("Then O's best move is row ${nextOMoveCell.row + 1}, column ${nextOMoveCell.column + 1}.")
                        }
                    } else {
                        lines.add("That X reply ends the board.")
                    }
                } else {
                    predictionState = "target-unloaded"
                }
            } else if (predictionEnabled && prediction.status() == DungeonTicTacToeRules.PredictionStatus.AMBIGUOUS_X_REPLY) {
                lines.add("Prediction hidden: X has multiple equally strong replies.")
            } else if (predictionEnabled && prediction.status() == DungeonTicTacToeRules.PredictionStatus.TERMINAL_AFTER_O) {
                lines.add("That O move ends the board.")
            }
            if (predictionEnabled) ticTacToeHold.plan(pieces, analysis, prediction)
            updateTicTacToeDiagnostic("recognized", "none", analysis, suggested, predictionState, xReply, nextO)
            return DungeonOdinPuzzleObserver.Guidance(
                "Tic Tac Toe", lines, DungeonOdinPuzzleObserver.ScanStatus.COMPLETE,
                if (predictionEnabled) "Recognized board; conditional minimax prediction." else "Recognized board; O to move.",
            )
        }
        if (analysis.status() == DungeonTicTacToeRules.Status.NOT_O_TURN) {
            val held = if (predictionEnabled) ticTacToeHold.hold(pieces, analysis) else null
            var heldX = "-"
            var heldO = "-"
            if (held != null) {
                val xTarget = ticTacToeWorldPosition(held.xReply(), clayPosition, transform.rotation())
                if (xTarget != null && level.hasChunkAt(xTarget)) {
                    ticTacToePredictedXTarget = DungeonOdinPuzzleObserver.WorldBlock(xTarget.x, xTarget.y, xTarget.z)
                    heldX = cellLabel(held.xReply())
                }
                val oTarget = ticTacToeWorldPosition(held.nextOMove(), clayPosition, transform.rotation())
                if (oTarget != null && level.hasChunkAt(oTarget)) {
                    ticTacToePredictedOTarget = DungeonOdinPuzzleObserver.WorldBlock(oTarget.x, oTarget.y, oTarget.z)
                    heldO = cellLabel(held.nextOMove())
                }
            }
            updateTicTacToeDiagnostic("recognized", "not-o-turn", analysis, "-",
                if (held != null) "held-until-x" else if (predictionEnabled) "waiting-x" else "disabled", heldX, heldO)
            return DungeonOdinPuzzleObserver.Guidance(
                "Tic Tac Toe", listOf("Waiting for X to move."), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE,
                "The board is valid, but it is not O's turn.",
            )
        }
        if (analysis.status() == DungeonTicTacToeRules.Status.FINISHED) {
            ticTacToeSuggestion.clear()
            updateTicTacToeDiagnostic("recognized", "finished", analysis, "-", "finished", "-", "-")
            return DungeonOdinPuzzleObserver.Guidance(
                "Tic Tac Toe", listOf("Board finished; no move suggested."), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE,
                "The game is finished.",
            )
        }
        val reason = analysis.rejection().name.lowercase(Locale.ROOT).replace('_', '-')
        updateTicTacToeDiagnostic("rejected", reason, analysis, "-", "board-rejected", "-", "-")
        return DungeonOdinPuzzleObserver.Guidance(
            "Tic Tac Toe", listOf("Board not confirmed: $reason."), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE,
            "Board rejected: $reason.",
        )
    }

    private fun ticTacToeUnavailable(reason: String, guidance: String): DungeonOdinPuzzleObserver.Guidance {
        ticTacToePredictedXTarget = null
        ticTacToePredictedOTarget = null
        // Streaming chunks must not blink the marker: keep the held suggestion and its room until a real reset.
        if (reason != "cell-unloaded" && reason != "target-unloaded") {
            ticTacToeHold.clear()
            ticTacToeSuggestion.clear()
            ticTacToeRoomIdentity = null
            ticTacToeLevelIdentity = null
        }
        ticTacToeDiagnostic = boundedDiagnostic(
            "board=unknown;reason=$reason;pieces=0;X=0;O=0;turn=unknown;suggested=-;prediction=unavailable;xReply=-;nextO=-", 240,
        )
        return DungeonOdinPuzzleObserver.Guidance("Tic Tac Toe", listOf(guidance), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE, guidance)
    }

    private fun updateTicTacToeDiagnostic(
        board: String,
        reason: String,
        analysis: DungeonTicTacToeRules.Analysis,
        suggested: String,
        prediction: String,
        xReply: String,
        nextO: String,
    ) {
        ticTacToeDiagnostic = boundedDiagnostic(
            "board=$board;reason=$reason;pieces=${analysis.observedPieces()};X=${analysis.xCount()};O=${analysis.oCount()};" +
                "turn=${analysis.turn().name.lowercase(Locale.ROOT)};suggested=$suggested;prediction=$prediction;xReply=$xReply;nextO=$nextO", 240,
        )
    }

    private fun cellLabel(cell: DungeonTicTacToeRules.Cell?): String =
        if (cell == null) "-" else "${cell.row + 1},${cell.column + 1}"

    private fun ticTacToeWorldPosition(
        cell: DungeonTicTacToeRules.Cell?,
        clayPosition: DungeonTicTacToeRules.Position,
        rotation: OdinDungeonRoomRotation.Rotation,
    ): BlockPos? {
        val position = DungeonTicTacToeRules.worldPositionForCell(cell, clayPosition, rotation)
        return if (position == null) null else BlockPos(position.x, position.y, position.z)
    }

    private fun ticTacToeMapColor(item: net.minecraft.world.item.ItemStack?, client: Minecraft): Int {
        if (item == null || !item.`is`(Items.FILLED_MAP)) return -1
        val mapId: MapId? = item.get(DataComponents.MAP_ID)
        if (mapId == null) return -1
        val level = client.level ?: return -1
        val map = MapItem.getSavedData(mapId, level) ?: return -1
        if (map.colors == null || map.colors.size <= 8256) return -1
        return java.lang.Byte.toUnsignedInt(map.colors[8256])
    }

    fun tickQuizTimer(observedServerGameTime: Long) { quizTracker.onServerGameTime(observedServerGameTime) }

    fun quizTimerSnapshot(): DungeonOdinPuzzleObserver.QuizTimerSnapshot =
        DungeonOdinPuzzleObserver.QuizTimerSnapshot(quizTracker.timerTicks(), quizTracker.timerMaximumTicks(), quizTracker.timerStage())

    fun quizDiagnosticSummary(): String {
        val option = quizTracker.confirmedOption()
        val optionLabel = when (option?.marker()) {
            "ⓐ" -> "A"
            "ⓑ" -> "B"
            "ⓒ" -> "C"
            null -> "none"
            else -> "unknown"
        }
        val summary = "stage=${quizTracker.timerStage()};question=${if (quizTracker.question() == null) "unknown" else "known"};" +
            "acceptedAnswers=${quizTracker.acceptedAnswers().size};option=$optionLabel;chatStyle=$quizChatStyleDiagnostic;" +
            "visual=$quizVisualDiagnostic"
        return boundedDiagnostic(summary, 220)
    }

    fun styleQuizChatOption(component: Component?): Component? {
        val option = quizTracker.confirmedOption() ?: return component
        val styled = DungeonQuizChatStyler.apply(component, option.marker(), option.answer())
        quizChatStyleDiagnostic = if (styled !== component) "applied-${quizOptionLabel(option.marker())}"
            else "message-mismatch-${quizOptionLabel(option.marker())}"
        return styled
    }

    private fun quizOptionLabel(marker: String?): String = when (marker) {
        "ⓐ" -> "A"
        "ⓑ" -> "B"
        "ⓒ" -> "C"
        else -> "unknown"
    }

    fun boulderDiagnosticSummary(): String = boundedDiagnostic(boulderScanDiagnostic, 180)

    fun waterBoardDiagnosticSummary(): String {
        if (waterBoardFallbackDiagnostic.startsWith("source=signature-fallback;state=rejected;"))
            return boundedDiagnostic("locked=false;$waterBoardFallbackDiagnostic", 180)
        val progress = waterBoardProgress
        if (progress != null) return progress.diagnosticSummary()
        val scan = cachedWaterBoardScan
        if (scan == null) {
            if (waterBoardFallbackDiagnostic.startsWith("source=signature-fallback;"))
                return boundedDiagnostic("locked=false;$waterBoardFallbackDiagnostic", 180)
            return "locked=false;scan=unscanned"
        }
        val detection = scan.detection
        val state = if (detection == null) "unknown" else if (detection.known()) "match-awaiting-route" else "unknown:${detection.unknownReason()}"
        val source = if (scan.signatureFallback) "signature-fallback" else "room-catalog"
        val unknownReason = detection?.unknownReason()
        val rejection = scan.rejectionReason ?: unknownReason?.name?.lowercase(Locale.ROOT)?.replace('_', '-')
        return boundedDiagnostic("source=$source;locked=false;scan=$state" + (if (rejection == null) "" else ";reject=$rejection"), 180)
    }

    fun sourceRoomRelativePosition(client: Minecraft?, worldPosition: BlockPos?): BlockPos? {
        if (client == null || worldPosition == null || !client.isSameThread || client.level == null) return null
        val room = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
        val transform = sourceRoomTransform(client, room)
        if (transform == null || !transform.resolved()) return null
        val clay = BlockPos(transform.clayPosition().x(), transform.clayPosition().y(), transform.clayPosition().z())
        val relative = OdinDungeonRoomRotationLogic.rotateToNorth(transform.rotation(), worldPosition.x - clay.x, worldPosition.z - clay.z)
        return BlockPos(relative[0], worldPosition.y, relative[1])
    }

    private fun waterBoardVisual(client: Minecraft, room: Room): DungeonOdinPuzzleObserver.WaterBoardVisual {
        val progress = waterBoardProgress
        if (progress == null || waterBoardLevelIdentity !== client.level ||
            !progress.identity().startsWith("${room.floor}|${room.signature}|"))
            return DungeonOdinPuzzleObserver.WaterBoardVisual.empty()
        val upcoming = ArrayList<DungeonOdinPuzzleObserver.WorldBlock>(2)
        for (click in progress.pending()) {
            val position = progress.leverPositions()[click.leverId]
                ?: return DungeonOdinPuzzleObserver.WaterBoardVisual.empty()
            upcoming.add(DungeonOdinPuzzleObserver.WorldBlock(position.x, position.y, position.z))
            if (upcoming.size == 2) break
        }
        return DungeonOdinPuzzleObserver.WaterBoardVisual(upcoming, progress.scheduleLabels(client.level!!.gameTime), true)
    }

    private fun creeperPairVisuals(client: Minecraft, room: Room): List<DungeonOdinPuzzleObserver.CreeperPairVisual> {
        val snapshot = cachedCreeperBeams
        if (snapshot == null || creeperBeamsLevelIdentity !== client.level || creeperBeamsResolvedRotation == null ||
            creeperBeamsRoomIdentity(room) != creeperBeamsRoomIdentity) return emptyList()
        val pairs = snapshot.detection.pairs()
        val result = ArrayList<DungeonOdinPuzzleObserver.CreeperPairVisual>(pairs.size)
        for (index in pairs.indices) {
            val pair = pairs[index]
            result.add(DungeonOdinPuzzleObserver.CreeperPairVisual(worldBlock(pair.first), worldBlock(pair.second), false, index))
        }
        return java.util.List.copyOf(result)
    }

    private fun weirdosVisuals(
        client: Minecraft,
        currentRoom: Room,
        room: OdinDungeonRoomIdentity.Snapshot,
    ): List<DungeonOdinPuzzleObserver.WeirdoNpcVisual> {
        if (room.roomData() == null || room.geometry() == null || room.roomName() != "Three Weirdos") return emptyList()
        val roomName = room.roomName() ?: return emptyList()
        val mapSignature = room.mapSignature() ?: return emptyList()
        val cell = room.cell() ?: return emptyList()
        val geometry = room.geometry() ?: return emptyList()
        val snapshotRoom = Room(room.floor()?.name ?: "unknown", mapSignature, cell, roomName, geometry.shape() ?: "unknown")
        if (roomIdentity(currentRoom) != roomIdentity(snapshotRoom)) return emptyList()
        val result = ArrayList<DungeonOdinPuzzleObserver.WeirdoNpcVisual>()
        for (marker in weirdosMarkers.snapshot(roomIdentity(currentRoom), client.level)) {
            val chest = BlockPos(marker.x(), marker.y(), marker.z())
            if (!client.level!!.hasChunkAt(chest)) continue
            result.add(
                DungeonOdinPuzzleObserver.WeirdoNpcVisual(
                    DungeonOdinPuzzleObserver.WorldBlock(chest.x, chest.y, chest.z), marker.speaker(), marker.correctChest(),
                ),
            )
        }
        return java.util.List.copyOf(result)
    }

    private fun quizVisuals(client: Minecraft, room: Room): List<DungeonOdinPuzzleObserver.QuizOptionVisual> {
        val option = quizTracker.confirmedOption()
        if (room.roomName != "Quiz") {
            quizVisualDiagnostic = "room=not-quiz"
            return emptyList()
        }
        if (quizTracker.question() == null || quizTracker.acceptedAnswers().isEmpty()) {
            quizVisualDiagnostic = "question=unmatched"
            return emptyList()
        }
        if (option == null) {
            quizVisualDiagnostic = "option=unconfirmed"
            return emptyList()
        }
        val identity = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
        if (identity == null) {
            quizVisualDiagnostic = "identity=" + boundedDiagnostic(mapIdentityFailure(client), 64)
            return emptyList()
        }
        val transform = sourceRoomTransform(client, identity)
        if (transform == null || !transform.resolved()) {
            quizVisualDiagnostic = "transform=" + quizTransformDiagnostic(client, identity, transform)
            return emptyList()
        }
        val index = when (option.marker()) {
            "ⓐ" -> 0
            "ⓑ" -> 1
            "ⓒ" -> 2
            else -> -1
        }
        if (index < 0) {
            quizVisualDiagnostic = "option=invalid-marker"
            return emptyList()
        }
        val relative = when (index) {
            0 -> BlockPos(20, 70, 6)
            1 -> BlockPos(15, 70, 9)
            else -> BlockPos(10, 70, 6)
        }
        val clay = BlockPos(transform.clayPosition().x(), transform.clayPosition().y(), transform.clayPosition().z())
        val world = sourceRealPosition(relative, clay, transform.rotation()).offset(0, -1, 0)
        if (!client.level!!.hasChunkAt(world)) {
            quizVisualDiagnostic = "marker=unloaded"
            return emptyList()
        }
        quizVisualDiagnostic = "marker=ready;rotation=${transform.rotation().name}"
        return listOf(
            DungeonOdinPuzzleObserver.QuizOptionVisual(
                option.marker().orEmpty(), option.answer().orEmpty(),
                DungeonOdinPuzzleObserver.WorldBlock(world.x, world.y, world.z), true,
            ),
        )
    }

    private fun quizTransformDiagnostic(
        client: Minecraft,
        identity: OdinDungeonRoomIdentity.Snapshot,
        transform: OdinDungeonRoomRotation.Result?,
    ): String {
        if (identity.roomData() == null) {
            val core = visualRoomCoreScanner.roomCoreAt(client, identity.cell())
            return if (core == null) "room-core-unloaded" else "room-core-unmapped:${core.hash}"
        }
        if (identity.geometry() == null) return "map-geometry-missing"
        if (transform == null) return "unavailable"
        return "unresolved:" + boundedDiagnostic(transform.reason(), 64)
    }

    private fun sourceRoomTransform(
        client: Minecraft,
        room: OdinDungeonRoomIdentity.Snapshot?,
    ): OdinDungeonRoomRotation.Result? {
        if (room == null || room.roomData() == null || room.geometry() == null) return null
        val core = visualRoomCoreScanner.roomCoreAt(client, room.cell()) ?: return null
        val data = core.data ?: return null
        val tileIndices = room.connectedCells().map { offset ->
            (room.cell().componentZ + offset.z) * 6 + room.cell().componentX + offset.x
        }
        return OdinDungeonRoomRotation.resolve(data, tileIndices, core.highestBlock) { probe ->
            val state = loadedState(client, BlockPos(probe.x(), probe.y(), probe.z()))
            when {
                state == null -> OdinDungeonRoomRotation.ProbeState.UNAVAILABLE
                state.`is`(Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE)) -> OdinDungeonRoomRotation.ProbeState.BLUE_TERRACOTTA
                else -> OdinDungeonRoomRotation.ProbeState.OTHER
            }
        }
    }

    private fun sourceRealPosition(
        relative: BlockPos,
        clay: BlockPos,
        rotation: OdinDungeonRoomRotation.Rotation,
    ): BlockPos {
        val world = OdinDungeonRoomRotationLogic.rotateAroundNorth(rotation, relative.x, relative.z)
        return BlockPos(clay.x + world[0], relative.y, clay.z + world[1])
    }

    private fun knownBeamEndpoint(kind: DungeonCreeperBeamsRules.BlockKind): Boolean =
        kind == DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN || kind == DungeonCreeperBeamsRules.BlockKind.PRISMARINE

    private fun worldBlock(endpoint: DungeonCreeperBeamsRules.Endpoint?): DungeonOdinPuzzleObserver.WorldBlock? =
        if (endpoint == null) null else DungeonOdinPuzzleObserver.WorldBlock(endpoint.x, endpoint.y, endpoint.z)

    private fun worldPosition(endpoint: DungeonCreeperBeamsRules.Endpoint?): DungeonCreeperBeamsRules.WorldPosition? =
        if (endpoint == null) null else DungeonCreeperBeamsRules.WorldPosition(endpoint.x, endpoint.y, endpoint.z)

    private fun sourceWeirdosChestPosition(
        npc: ArmorStand,
        clay: BlockPos,
        rotation: OdinDungeonRoomRotation.Rotation,
    ): BlockPos {
        val worldX = npc.x.toInt() - 1
        val worldZ = npc.z.toInt() - 1
        val relative = OdinDungeonRoomRotationLogic.rotateToNorth(rotation, worldX - clay.x, worldZ - clay.z)
        val world = OdinDungeonRoomRotationLogic.rotateAroundNorth(rotation, relative[0] + 1, relative[1])
        return BlockPos(clay.x + world[0], 69, clay.z + world[1])
    }

    private fun parseExplicitNpcClue(rawMessage: String?, confirmedNpcNames: Set<String?>?): DungeonOdinPuzzleObserver.ClueLine? {
        val clue = DungeonOdinPuzzleObserverLogic.parseExplicitNpcClue(rawMessage, confirmedNpcNames) ?: return null
        return DungeonOdinPuzzleObserver.ClueLine(clue.speaker, clue.statement)
    }

    private fun parseWeirdosClaim(statement: String?): WeirdosClaim? {
        val correctChest = DungeonOdinPuzzleObserverLogic.parseWeirdosClaim(statement) ?: return null
        return WeirdosClaim(correctChest)
    }

    fun reset() { resetRoom(null) }

    /** Clears one solver's local observation without discarding state owned by the other solvers. */
    fun resetSolver(solverId: String?) {
        when (solverId ?: "") {
            "WATER_BOARD" -> {
                lastWaterBoardScanTick = Long.MIN_VALUE
                waterBoardRoomIdentity = null
                waterBoardLevelIdentity = null
                cachedWaterBoardScan = null
                waterBoardProgress = null
                waterBoardFallbackDiagnostic = "not-active"
            }
            "TP_MAZE" -> {
                portalLevelIdentity = null
                portalScanComplete = false
                tpMazeCatalogDetection = null
                tpMazeVisitedPadIds.clear()
                tpMazeViewCandidateIds.clear()
                tpMazeCurrentPadId = null
                tpMazeSuggestedPadId = null
            }
            "ICE_FILL" -> {
                lastIceScanTick = Long.MIN_VALUE
                clearIceRoute()
            }
            "ICE_PATH" -> clearIcePathRoute("reset")
            "BLAZE" -> {
                lastBlazeScanTick = Long.MIN_VALUE
                cachedBlazes = emptyList()
            }
            "CREEPER_BEAMS" -> clearCreeperBeamsObservation()
            "THREE_WEIRDOS" -> {
                weirdosStatements.clear()
                weirdosMarkers.clear()
            }
            "QUIZ" -> quizTracker.reset()
            "BOULDER" -> {
                lastBoulderScanTick = Long.MIN_VALUE
                boulderRoomIdentity = null
                boulderLevelIdentity = null
                boulderDetection = null
                boulderScanDiagnostic = "not-scanned"
                boulderProgress.clear()
            }
            "TIC_TAC_TOE" -> clearTicTacToe("reset")
        }
    }

    fun clearCreeperBeamEndpointConfirmation() { clearCreeperBeamsObservation() }

    fun isConfirmedCreeperBeamsEndpoint(client: Minecraft?, position: BlockPos?): Boolean {
        if (client == null || position == null || !client.isSameThread) return false
        if (client.player == null || client.level == null || !DungeonContextTracker.inDungeon()) {
            clearCreeperBeamsObservation()
            return false
        }
        val room = currentRoom(client)
        if (room == null || !DungeonCreeperBeamsRules.isCreeperBeamsRoom(room.roomName)) {
            clearCreeperBeamsObservation()
            return false
        }
        val currentIdentity = creeperBeamsRoomIdentity(room)
        val snapshot = cachedCreeperBeams
        if (currentIdentity != creeperBeamsRoomIdentity || creeperBeamsLevelIdentity !== client.level ||
            snapshot == null || !snapshot.detection.known()) return false
        val state = loadedState(client, position)
        if (state == null || (!state.`is`(Blocks.SEA_LANTERN) && !state.`is`(Blocks.PRISMARINE))) return false
        return DungeonCreeperBeamsRules.containsEndpoint(
            snapshot.detection, DungeonCreeperBeamsRules.WorldPosition(position.x, position.y, position.z),
        )
    }

    fun onCreeperBeamBlockUpdate(
        client: Minecraft?,
        position: BlockPos?,
        oldState: net.minecraft.world.level.block.state.BlockState?,
        newState: net.minecraft.world.level.block.state.BlockState?,
    ): Boolean {
        if (client == null || position == null || oldState == null || newState == null || !client.isSameThread ||
            client.player == null || client.level == null || !DungeonContextTracker.inDungeon()) return false
        val level = client.level ?: return false
        if (!knownBeamEndpoint(blockKind(oldState)) || !knownBeamEndpoint(blockKind(newState))) return false
        val room = currentRoom(client)
        if (room == null || !DungeonCreeperBeamsRules.isCreeperBeamsRoom(room.roomName)) return false
        ensureRoom(room)
        ensurePortalLevel(level)
        val cached = cachedCreeperBeams ?: return false
        val rotation = creeperBeamsResolvedRotation ?: return false
        lastCreeperBeamsScanTick = Long.MIN_VALUE
        cachedCreeperBeams = scanCreeperBeamsAtRotation(client, room, cached.catalog, rotation)
        lastCreeperBeamsScanTick = level.gameTime
        return true
    }

    private fun blockKind(state: net.minecraft.world.level.block.state.BlockState?): DungeonCreeperBeamsRules.BlockKind = when {
        state == null -> DungeonCreeperBeamsRules.BlockKind.OTHER
        state.`is`(Blocks.SEA_LANTERN) -> DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN
        state.`is`(Blocks.PRISMARINE) -> DungeonCreeperBeamsRules.BlockKind.PRISMARINE
        else -> DungeonCreeperBeamsRules.BlockKind.OTHER
    }

    fun observeTpMazeCorrection(
        client: Minecraft?,
        after: Vec3?,
        yaw: Float,
        pitch: Float,
        playerBoundsBefore: AABB?,
    ): Boolean {
        if (client == null || !client.isSameThread) return false
        if (client.player == null || client.level == null) {
            tpMazeCorrectionDiagnostic = "ignored:world-unavailable"
            return false
        }
        if (!DungeonContextTracker.inDungeon()) {
            tpMazeCorrectionDiagnostic = "ignored:dungeon-inactive"
            return false
        }
        if (after == null || !DungeonTpMazeRules.isTeleportLanding(DungeonTpMazeRules.Position(after.x, after.y, after.z))) {
            tpMazeCorrectionDiagnostic = "ignored:not-maze-landing"
            return false
        }
        val room = currentRoom(client)
        if (room == null) {
            tpMazeCorrectionDiagnostic = "ignored:room-unconfirmed"
            return false
        }
        ensureRoom(room)
        ensurePortalLevel(client.level)
        if (tpMazeCatalogDetection == null || !tpMazeCatalogDetection!!.known())
            observeTpMaze(client, client.level!!.gameTime, room, true)
        val detection = tpMazeCatalogDetection
        if (detection == null || !detection.known()) {
            tpMazeCorrectionDiagnostic = "ignored:maze-transform-unavailable"
            return false
        }
        if (!yaw.isFinite() || !pitch.isFinite()) {
            tpMazeCorrectionDiagnostic = "ignored:invalid-view-angle"
            return false
        }
        val correctionBounds = AABB.unitCubeFromLowerCorner(after).inflate(1.0, 0.0, 1.0)
        val occupiedBounds = playerBoundsBefore?.inflate(1.0, 0.0, 1.0)
        for (pad in detection.pads()) {
            val position = pad?.position ?: continue
            val padBounds = AABB(
                position.x.toDouble(), position.y.toDouble(), position.z.toDouble(),
                position.x + 1.0, position.y + 1.0, position.z + 1.0,
            )
            if (correctionBounds.intersects(padBounds) || (occupiedBounds != null && occupiedBounds.intersects(padBounds)))
                tpMazeVisitedPadIds.add(pad.id)
        }
        if (tpMazeViewCandidateIds.isEmpty())
            tpMazeViewCandidateIds.addAll(DungeonTpMazeRules.seedViewCandidates(detection.pads(), tpMazeViewCandidateIds))
        val occupiedPadIds = HashSet<Int>()
        val playerCandidateBounds = playerBoundsBefore?.inflate(0.5, 0.0, 0.5)
        if (playerCandidateBounds != null) for (pad in detection.pads()) {
            val position = pad?.position ?: continue
            if (playerCandidateBounds.intersects(
                    AABB(position.x - 0.5, position.y.toDouble(), position.z - 0.5,
                        position.x + 1.5, position.y + 1.0, position.z + 1.5),
                )) occupiedPadIds.add(pad.id)
        }
        tpMazeViewCandidateIds.retainAll(
            DungeonTpMazeRules.retainViewCandidates(
                detection.pads(), tpMazeViewCandidateIds, tpMazeVisitedPadIds, occupiedPadIds,
                DungeonTpMazeRules.Position(after.x, after.y, after.z), yaw, pitch, 32.0,
            ),
        )
        val currentId = tpMazePadAt(correctionBounds, detection.pads())
        if (currentId == null) {
            tpMazeCorrectionDiagnostic = "ignored:no-pad-at-landing"
            return false
        }
        tpMazeVisitedPadIds.add(currentId)
        tpMazeCurrentPadId = currentId
        val suggested = DungeonTpMazeRules.chooseNextPad(
            detection.pads(), currentId, tpMazeVisitedPadIds, tpMazeViewCandidateIds, yaw,
            DungeonTpMazeRules.Position(after.x, after.y, after.z),
        )
        tpMazeSuggestedPadId = suggested?.id()
        tpMazeCorrectionDiagnostic = "accepted:current=$currentId;visited=${tpMazeVisitedPadIds.size};" +
            "candidates=${tpMazeViewCandidateIds.size};next=${valueOrDash(tpMazeSuggestedPadId)}"
        return true
    }

    fun observeBoulderClick(client: Minecraft?, position: BlockPos?): Boolean {
        if (client == null || !client.isSameThread || client.player == null || client.level == null ||
            position == null || !DungeonContextTracker.isMobEspPhase()) return false
        val room = currentRoom(client)
        if (room == null || room.roomName != "Boulder" || roomIdentity(room) != boulderRoomIdentity ||
            boulderLevelIdentity !== client.level || boulderDetection?.known() != true) return false
        return boulderProgress.observeClick(DungeonBoulderRules.WorldPosition(position.x, position.y, position.z))
    }

    fun observeWaterBoardClick(client: Minecraft?, position: BlockPos?): Boolean {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || position == null ||
            !DungeonContextTracker.inDungeon() || waterBoardProgress == null || waterBoardLevelIdentity !== client.level) return false
        val room = currentRoom(client)
        if (room == null || roomIdentity(room) != roomSignature) return false
        return waterBoardProgress!!.observeClick(position, client.level!!.gameTime)
    }

    fun boulderCurrentTarget(client: Minecraft?): DungeonBoulderRules.Target? {
        val targets = boulderTargets(client, false)
        return targets.firstOrNull()
    }

    fun boulderTargets(client: Minecraft?, showAll: Boolean): List<DungeonBoulderRules.Target> {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || !DungeonContextTracker.inDungeon())
            return emptyList()
        val level = client.level ?: return emptyList()
        val room = currentRoom(client)
        if (room == null || room.roomName != "Boulder" || roomIdentity(room) != roomSignature ||
            roomIdentity(room) != boulderRoomIdentity || boulderLevelIdentity !== level || boulderDetection?.known() != true)
            return emptyList()
        // One target in an unloaded chunk must not hide the others (all solutions vanished after a click).
        val targets = boulderProgress.remainingTargets(showAll).filter { target ->
            val render = target.renderPosition()
            val click = target.clickPosition()
            render != null && click != null &&
                level.hasChunkAt(BlockPos(render.x(), render.y(), render.z())) &&
                level.hasChunkAt(BlockPos(click.x(), click.y(), click.z()))
        }
        return java.util.List.copyOf(targets)
    }

    fun tpMazeRenderSegments(
        client: Minecraft?,
        showObservedLinks: Boolean,
        showSuggestedTracer: Boolean,
        tracerStart: Vec3?,
    ): List<DungeonTpMazeRenderGeometry.Segment> {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || client.gameRenderer == null ||
            !DungeonContextTracker.inDungeon()) return emptyList()
        val room = currentRoom(client)
        if (room == null || roomIdentity(room) != roomSignature || portalLevelIdentity !== client.level) return emptyList()
        val segments = ArrayList<DungeonTpMazeRenderGeometry.Segment>(1)
        val suggestedPadId = tpMazeSuggestedPadId
        val detection = tpMazeCatalogDetection
        if (showSuggestedTracer && suggestedPadId != null && detection != null) {
            val target = detection.pads().firstOrNull { it.id == suggestedPadId }
            if (target != null && tracerStart != null) {
                val position = target.position
                if (position == null) return emptyList()
                val hint = DungeonTpMazeRenderGeometry.cameraToPadTracer(
                    toGraphPosition(tracerStart), toGraphPosition(position.x + 0.5, position.y + 0.8, position.z + 0.5),
                )
                if (hint != null) segments.add(hint)
            }
        }
        return java.util.List.copyOf(segments)
    }

    private fun observeIceFill(
        client: Minecraft?,
        clientTick: Long,
        room: Room,
        forceScan: Boolean,
        requestedStrategy: DungeonOdinPuzzleObserver.IceFillStrategy?,
    ): DungeonOdinPuzzleObserver.Guidance? {
        if (room.roomName != "Ice Fill") return null
        val activeClient = client ?: return null
        if (iceLevelIdentity != null && (iceLevelIdentity !== activeClient.level || roomIdentity(room) != iceRoomIdentity)) clearIceRoute()
        if (iceScanComplete) return iceGuidance()
        if (!forceScan && lastIceScanTick != Long.MIN_VALUE && clientTick - lastIceScanTick < ROOM_SCAN_PERIOD_TICKS) return null
        lastIceScanTick = clientTick
        val catalog = DungeonIceFillCatalog.bundled()
        if (!catalog.available()) return manual("Ice Fill", "The licensed route catalog is unavailable.", "No route was generated.")
        val identity = OdinDungeonRoomIdentity.currentPuzzleRoom(activeClient)
        val transform = sourceRoomTransform(activeClient, identity)
        if (transform == null || !transform.resolved())
            return manual("Ice Fill", "The room orientation is not resolved yet.", "The full catalog route will be shown when the room scan is available.")
        val clay = BlockPos(transform.clayPosition().x(), transform.clayPosition().y(), transform.clayPosition().z())
        val strategy = requestedStrategy ?: DungeonOdinPuzzleObserver.IceFillStrategy.EASY
        val route = ArrayList<IceFillRenderGeometry.Point>()
        for (floor in catalog.identifiers().indices) {
            val patterns = catalog.identifiers()[floor]
            for (pattern in patterns.indices) {
                val identifiers = patterns[pattern]
                if (identifiers.size < 2) continue
                val first = identifiers.first()
                val second = identifiers[1]
                val firstWorld = sourceRealPosition(BlockPos(first.x(), first.y(), first.z()), clay, transform.rotation())
                val secondWorld = sourceRealPosition(BlockPos(second.x(), second.y(), second.z()), clay, transform.rotation())
                val firstState = loadedState(activeClient, firstWorld)
                val secondState = loadedState(activeClient, secondWorld)
                if (firstState == null || secondState == null) {
                    iceScanComplete = false
                    return manual("Ice Fill", "A catalog identifier block is in an unloaded chunk.", "The scan will retry without requesting chunks.")
                }
                if (!firstState.isAir || secondState.isAir) continue
                for (point in catalog.route(strategy == DungeonOdinPuzzleObserver.IceFillStrategy.HARD, floor, pattern)) {
                    val world = sourceRealPosition(BlockPos(point.x(), point.y(), point.z()), clay, transform.rotation())
                    route.add(IceFillRenderGeometry.Point(world.x + 0.5, world.y + 0.1, world.z + 0.5))
                }
                break
            }
        }
        iceWorldRoute = java.util.List.copyOf(route)
        iceRouteStrategy = strategy
        iceLevelIdentity = activeClient.level
        iceRoomIdentity = roomIdentity(room)
        iceScanComplete = true
        return iceGuidance()
    }

    private fun observeIcePath(client: Minecraft?, clientTick: Long, room: Room?): DungeonOdinPuzzleObserver.Guidance {
        if (client == null || room == null || !client.isSameThread || client.level == null || room.roomName != "Ice Path") {
            clearIcePathRoute("room-or-client-unavailable")
            return DungeonOdinPuzzleObserver.Guidance(
                "Ice Path", emptyList(), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE,
                "The exact Ice Path room is not available on the client thread.",
            )
        }
        val level = client.level ?: run {
            clearIcePathRoute("room-or-client-unavailable")
            return DungeonOdinPuzzleObserver.Guidance(
                "Ice Path", emptyList(), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE,
                "The exact Ice Path room is not available on the client thread.",
            )
        }
        val identity = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
        val transform = sourceRoomTransform(client, identity)
        if (transform == null || !transform.resolved()) {
            clearIcePathRoute("orientation-unresolved")
            return DungeonOdinPuzzleObserver.Guidance(
                "Ice Path", emptyList(), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE,
                "The Ice Path room orientation is not resolved.",
            )
        }
        val clay = BlockPos(transform.clayPosition().x(), transform.clayPosition().y(), transform.clayPosition().z())
        val blocks = Array(DungeonIcePathSolver.BOARD_SIZE) { BooleanArray(DungeonIcePathSolver.BOARD_SIZE) }
        var loaded = 0
        var wallCount = 0
        for (row in 0 until DungeonIcePathSolver.BOARD_SIZE) {
            for (column in 0 until DungeonIcePathSolver.BOARD_SIZE) {
                val relative = BlockPos(23 - column, 67, 24 - row)
                val world = sourceRealPosition(relative, clay, transform.rotation())
                val state = loadedState(client, world)
                if (state == null) {
                    clearIcePathRoute("board-incomplete")
                    icePathScanDiagnostic = "state=partial;loaded=$loaded/289;reason=unloaded-block"
                    return DungeonOdinPuzzleObserver.Guidance(
                        "Ice Path", emptyList(), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE,
                        "A board block is in an unloaded chunk; the scan will retry without requesting chunks.",
                    )
                }
                loaded++
                blocks[row][column] = !state.isAir
                if (blocks[row][column]) wallCount++
            }
        }
        val silverfish = ArrayList<DungeonIcePathSolver.Cell>(1)
        for (entity in level.entitiesForRendering()) {
            if (entity.isRemoved || !entity.isAlive || entity.type !== EntityTypes.SILVERFISH) continue
            val position = entity.blockPosition()
            if (kotlin.math.abs(position.y - 66) > 8) continue
            val relative = OdinDungeonRoomRotationLogic.rotateToNorth(transform.rotation(), position.x - clay.x, position.z - clay.z)
            val row = 24 - relative[1]
            val column = 23 - relative[0]
            if (row in 0 until DungeonIcePathSolver.BOARD_SIZE && column in 0 until DungeonIcePathSolver.BOARD_SIZE)
                silverfish.add(DungeonIcePathSolver.Cell(row, column))
        }
        if (silverfish.size != 1) {
            clearIcePathRoute(if (silverfish.isEmpty()) "silverfish-missing" else "silverfish-ambiguous")
            icePathScanDiagnostic = "state=partial;loaded=$loaded/289;fish=${minOf(2, silverfish.size)};reason=" +
                if (silverfish.isEmpty()) "silverfish-missing" else "silverfish-ambiguous"
            return DungeonOdinPuzzleObserver.Guidance(
                "Ice Path", emptyList(), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE,
                "A unique silverfish position on the loaded Ice Path board is required.",
            )
        }
        val currentIdentity = roomIdentity(room)
        val start = silverfish.first()
        val changed = icePathBoard == null || !Arrays.deepEquals(icePathBoard, blocks) || start != icePathSilverfish ||
            icePathLevelIdentity !== client.level || currentIdentity != icePathRoomIdentity
        if (changed) {
            icePathRoute = DungeonIcePathSolver.solve(blocks, start)
            icePathWorldRoute = icePathWorldPoints(icePathRoute, clay, transform.rotation())
            icePathBoard = blocks
            icePathSilverfish = start
            icePathLevelIdentity = client.level
            icePathRoomIdentity = currentIdentity
        }
        val boardSignature = Arrays.deepHashCode(blocks)
        val state = when {
            icePathRoute.isEmpty() -> "unreachable"
            icePathRoute.size == 1 -> "at-exit"
            else -> "solved"
        }
        icePathScanDiagnostic = "state=$state;loaded=$loaded/289;walls=$wallCount;fish=1;start=${start.row},${start.column};" +
            "routeStops=${icePathRoute.size};board=${Integer.toUnsignedString(boardSignature)};refresh=${if (changed) "changed" else "stable"}"
        return DungeonOdinPuzzleObserver.Guidance(
            "Ice Path", emptyList(), DungeonOdinPuzzleObserver.ScanStatus.COMPLETE,
            if (icePathRoute.isEmpty()) "The observed board has no route to the exit lane." else "A route is available; the line is rendered in the world.",
        )
    }

    private fun icePathWorldPoints(
        route: List<DungeonIcePathSolver.Cell>?,
        clay: BlockPos,
        rotation: OdinDungeonRoomRotation.Rotation,
    ): List<DungeonIcePathSolver.Point> {
        if (route.isNullOrEmpty()) return emptyList()
        val points = ArrayList<DungeonIcePathSolver.Point>(route.size)
        for (cell in route) {
            val relative = BlockPos(23 - cell.column, 67, 24 - cell.row)
            val world = sourceRealPosition(relative, clay, rotation)
            points.add(DungeonIcePathSolver.Point(world.x + 0.5, world.y + 0.5, world.z + 0.5))
        }
        return java.util.List.copyOf(points)
    }

    fun icePathDiagnosticSummary(): String = boundedDiagnostic(icePathScanDiagnostic, 220)

    fun icePathRenderPath(client: Minecraft?): DungeonOdinPuzzleObserver.IcePathRenderPath {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || !DungeonContextTracker.inDungeon() ||
            client.level !== icePathLevelIdentity || roomSignature == null || roomSignature != icePathRoomIdentity || icePathRoute.isEmpty())
            return DungeonOdinPuzzleObserver.IcePathRenderPath(emptyList())
        return DungeonOdinPuzzleObserver.IcePathRenderPath(icePathWorldRoute)
    }

    fun iceRenderPath(client: Minecraft?): DungeonOdinPuzzleObserver.IceRenderPath {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || !iceScanComplete ||
            client.level !== iceLevelIdentity || iceWorldRoute.isEmpty())
            return DungeonOdinPuzzleObserver.IceRenderPath(emptyList(), 0)
        return DungeonOdinPuzzleObserver.IceRenderPath(iceWorldRoute, 0)
    }

    private fun iceGuidance(): DungeonOdinPuzzleObserver.Guidance {
        if (iceWorldRoute.isEmpty())
            return DungeonOdinPuzzleObserver.Guidance("Ice Fill", listOf("No catalog pattern matched the identifier blocks."))
        val strategy = if (iceRouteStrategy == DungeonOdinPuzzleObserver.IceFillStrategy.HARD) "Optimized" else "Default"
        return DungeonOdinPuzzleObserver.Guidance("Ice Fill", listOf("$strategy route · ${iceWorldRoute.size} points"))
    }

    private fun observeTpMaze(client: Minecraft?, clientTick: Long, room: Room, forceScan: Boolean): DungeonOdinPuzzleObserver.Guidance {
        if (client == null || client.level == null) return DungeonOdinPuzzleObserver.Guidance("TP Maze", emptyList())
        if (forceScan || tpMazeCatalogDetection == null || !tpMazeCatalogDetection!!.known() || portalLevelIdentity !== client.level) {
            portalLevelIdentity = client.level
            val catalog = DungeonTpMazeCatalog.bundled()
            val identity = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
            val transform = sourceRoomTransform(client, identity)
            val rotation = if (transform == null || !transform.resolved()) null else roomGridRotation(transform.rotation())
            tpMazeTransformDiagnostic = tpMazeTransformStatus(client, room, identity, transform)
            if (catalog == null || !catalog.available()) {
                tpMazeCatalogDetection = DungeonTpMazeRules.Detection(-1, emptyList(), DungeonTpMazeRules.UnknownReason.CATALOG_UNAVAILABLE)
                tpMazeCatalogDiagnostic = "unavailable:" + boundedDiagnostic(catalog?.reason() ?: "catalog-result-missing", 72)
            } else if (rotation == null) {
                tpMazeCatalogDetection = DungeonTpMazeRules.Detection(-1, emptyList(), DungeonTpMazeRules.UnknownReason.INCOMPLETE_SCAN)
                tpMazeCatalogDiagnostic = "available:pads=${catalog.pads().size}"
            } else {
                tpMazeCatalogDetection = DungeonTpMazeRules.Detection(
                    rotation, DungeonTpMazeRules.pads(room.cell.baseX, room.cell.baseZ, catalog, rotation), null,
                )
                tpMazeCatalogDiagnostic = "available:pads=${catalog.pads().size}"
            }
            portalScanComplete = tpMazeCatalogDetection!!.known()
        }
        val detection = tpMazeCatalogDetection
        if (detection == null || !detection.known()) {
            val displayReason = if (detection?.unknownReason() == DungeonTpMazeRules.UnknownReason.CATALOG_UNAVAILABLE)
                "The portal catalog is unavailable." else "The room orientation is not resolved yet."
            val diagnostic = "TP Maze unresolved; ${tpMazeScanDiagnostic()}"
            return DungeonOdinPuzzleObserver.Guidance(
                "TP Maze", listOf(displayReason, "No portal target is shown until the room orientation is known."),
                DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE, diagnostic,
            )
        }
        val currentId = tpMazeCurrentPadId
        if (currentId == null) return DungeonOdinPuzzleObserver.Guidance(
            "TP Maze", listOf("${detection.pads().size} portal pads mapped for this room.",
                "Enter a portal to identify the current group and show the next candidate."),
            DungeonOdinPuzzleObserver.ScanStatus.COMPLETE, "TP Maze mapped; ${tpMazeScanDiagnostic()}",
        )
        val suggestedPadId = tpMazeSuggestedPadId
        val suggested = if (suggestedPadId == null) null else detection.pads().firstOrNull { it.id == suggestedPadId }
        val lines = ArrayList<String>()
        lines.add("Portal $currentId · ${tpMazeVisitedPadIds.size} pads visited.")
        if (suggested == null) {
            lines.add("No unvisited portal candidate remains in this group.")
        } else {
            val position = suggested.position
            if (position == null) {
                lines.add("The selected portal has no resolved position.")
            } else {
                lines.add("Next portal: ${suggested.id} · ${position.x + 0.5}, ${position.y + 0.5}, ${position.z + 0.5}.")
                lines.add("Follow the tracer manually.")
            }
        }
        return DungeonOdinPuzzleObserver.Guidance(
            "TP Maze", lines, DungeonOdinPuzzleObserver.ScanStatus.COMPLETE, "TP Maze guidance; ${tpMazeScanDiagnostic()}",
        )
    }

    private fun tpMazeTransformStatus(
        client: Minecraft,
        room: Room,
        identity: OdinDungeonRoomIdentity.Snapshot?,
        transform: OdinDungeonRoomRotation.Result?,
    ): String {
        if (identity == null) return "identity=missing"
        if (identity.geometry() == null) return "map-geometry=missing"
        if (identity.roomData() == null) {
            val core = visualRoomCoreScanner.roomCoreAt(client, room.cell)
            return if (core == null) "room-core=unloaded" else "room-core=unmapped:${core.hash}"
        }
        if (transform == null) return "transform=unavailable"
        if (!transform.resolved()) return "rotation=unresolved:" + boundedDiagnostic(transform.reason(), 76)
        return "rotation=${transform.rotation().name}"
    }

    private fun tpMazeScanDiagnostic(): String {
        val detection = tpMazeCatalogDetection
        val padCount = detection?.pads()?.size ?: 0
        val name = when {
            detection == null -> "unscanned"
            detection.known() -> "ready"
            else -> detection.unknownReason().toString()
        }
        return boundedDiagnostic(
            "identity=$tpMazeIdentityDiagnostic; transform=$tpMazeTransformDiagnostic; catalog=$tpMazeCatalogDiagnostic; " +
                "detection=$name; pads=$padCount; current=${valueOrDash(tpMazeCurrentPadId)}; visited=${tpMazeVisitedPadIds.size}; " +
                "candidates=${tpMazeViewCandidateIds.size}; next=${valueOrDash(tpMazeSuggestedPadId)}; correction=$tpMazeCorrectionDiagnostic", 260,
        )
    }

    fun tpMazePadVisuals(client: Minecraft?): List<DungeonOdinPuzzleObserver.TpMazePadVisual> {
        if (client == null || !client.isSameThread || client.player == null || client.level == null || !DungeonContextTracker.inDungeon() ||
            !portalScanComplete || portalLevelIdentity !== client.level || tpMazeCatalogDetection?.known() != true) return emptyList()
        val room = currentRoom(client)
        val detection = tpMazeCatalogDetection
        if (room == null || roomIdentity(room) != roomSignature || detection == null || !detection.known()) return emptyList()
        val candidates = HashSet(tpMazeViewCandidateIds)
        candidates.removeAll(tpMazeVisitedPadIds)
        val remainingCandidates = candidates.size
        val visuals = ArrayList<DungeonOdinPuzzleObserver.TpMazePadVisual>(detection.pads().size)
        for (pad in detection.pads()) {
            visuals.add(
                DungeonOdinPuzzleObserver.TpMazePadVisual(
                    pad.id(), pad.position(), tpMazeVisitedPadIds.contains(pad.id()), candidates.contains(pad.id()), remainingCandidates,
                ),
            )
        }
        return if (visuals.size == DungeonTpMazeRules.PAD_COUNT) java.util.List.copyOf(visuals) else emptyList()
    }

    private fun roomGridRotation(rotation: OdinDungeonRoomRotation.Rotation?): Int? = when (rotation) {
        OdinDungeonRoomRotation.Rotation.SOUTH -> 0
        OdinDungeonRoomRotation.Rotation.WEST -> 3
        OdinDungeonRoomRotation.Rotation.NORTH -> 2
        OdinDungeonRoomRotation.Rotation.EAST -> 1
        null -> null
    }

    private fun observeBlazes(
        client: Minecraft?,
        clientTick: Long,
        room: Room,
        blazeRoomPosition: String?,
    ): DungeonOdinPuzzleObserver.Guidance? {
        if (lastBlazeScanTick == Long.MIN_VALUE || clientTick - lastBlazeScanTick >= BLAZE_SCAN_PERIOD_TICKS) {
            cachedBlazes = scanBlazes(client, room)
            lastBlazeScanTick = clientTick
        }
        val observations = cachedBlazes
        if (observations.isEmpty()) return null
        val targets = observations.map { DungeonPuzzleLogic.BlazeTarget(it.id, it.health) }
        val position = DungeonOdinBlazeRules.positionForRoomName(room.roomName)
        val ordered = when (position) {
            DungeonOdinBlazeRules.Position.LOWER -> DungeonPuzzleLogic.orderBlazes(targets, true)
            DungeonOdinBlazeRules.Position.HIGHER -> DungeonPuzzleLogic.orderBlazes(targets, false)
            DungeonOdinBlazeRules.Position.UNSET -> emptyList()
        }
        if (ordered.isEmpty()) {
            val reason = DungeonOdinBlazeRules.orderUnavailableMessage(room.roomName, blazeRoomPosition)
            return DungeonOdinPuzzleObserver.Guidance(
                "Blaze", listOf("${observations.size} loaded Blaze health labels observed.", reason),
                DungeonOdinPuzzleObserver.ScanStatus.PARTIAL, reason,
            )
        }
        val lines = ordered.map { "${it.stableId} · ${it.health} HP" }
        return DungeonOdinPuzzleObserver.Guidance("Blaze", lines)
    }

    private fun observeWeirdos(): DungeonOdinPuzzleObserver.Guidance? {
        if (weirdosStatements.isEmpty()) return null
        val markers = weirdosMarkers.snapshot(roomSignature, weirdosLevelIdentity)
        if (markers.isEmpty()) {
            val reason = "No direct reward-chest claim has been recognized yet."
            return DungeonOdinPuzzleObserver.Guidance(
                "Three Weirdos", listOf(reason), DungeonOdinPuzzleObserver.ScanStatus.PARTIAL, reason,
            )
        }
        val lines = markers.map { (if (it.correctChest()) "Correct chest: " else "Not the reward chest: ") + it.speaker() }
        return DungeonOdinPuzzleObserver.Guidance("Three Weirdos", lines)
    }

    private fun observeQuiz(client: Minecraft?): DungeonOdinPuzzleObserver.Guidance =
        DungeonOdinPuzzleObserver.Guidance("Quiz", emptyList())

    private fun observeBoulder(
        client: Minecraft?,
        clientTick: Long,
        room: Room,
    ): DungeonOdinPuzzleObserver.Guidance? {
        if (room.roomName != "Boulder" || client == null) return null
        val level = client.level ?: return null
        val identity = roomIdentity(room)
        if (identity != boulderRoomIdentity || boulderLevelIdentity !== level) {
            boulderRoomIdentity = identity
            boulderLevelIdentity = level
            boulderDetection = null
            boulderScanDiagnostic = "not-scanned"
            boulderProgress.clear()
            lastBoulderScanTick = Long.MIN_VALUE
        }
        if (boulderDetection == null || (!boulderDetection!!.known() &&
                (lastBoulderScanTick == Long.MIN_VALUE || clientTick - lastBoulderScanTick >= ROOM_SCAN_PERIOD_TICKS))) {
            lastBoulderScanTick = clientTick
            val catalog = DungeonBoulderCatalog.bundled()
            val identitySnapshot = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
            val transform = sourceRoomTransform(client, identitySnapshot)
            val rotation = if (transform == null || !transform.resolved()) null else roomGridRotation(transform.rotation())
            val observations = ArrayList<DungeonBoulderRules.ObservedBlock>()
            var complete = catalog.available() && rotation != null
            if (rotation != null) for (position in DungeonBoulderRules.probePositions(room.cell.baseX, room.cell.baseZ, rotation)) {
                val world = BlockPos(position.x(), position.y(), position.z())
                if (!level.hasChunkAt(world)) {
                    complete = false
                    break
                }
                observations.add(
                    DungeonBoulderRules.ObservedBlock(
                        DungeonBoulderRules.WorldPosition(position.x(), position.y(), position.z()), level.getBlockState(world).isAir,
                    ),
                )
            }
            boulderDetection = if (rotation == null) {
                DungeonBoulderRules.Detection(-1, null, emptyList(), DungeonBoulderRules.UnknownReason.AMBIGUOUS_ORIENTATION)
            } else {
                DungeonBoulderRules.detectAtRotation(room.cell.baseX, room.cell.baseZ, catalog, observations, complete, rotation)
            }
            val detection = boulderDetection!!
            val scanCoverage = "y=65:loaded=${observations.size}/42:complete=$complete"
            val observedSignature = if (observations.size == 42) ":signature=" + observations.joinToString("") {
                if (java.lang.Boolean.TRUE == it.isAir()) "0" else "1"
            } else ""
            boulderScanDiagnostic = if (detection.known()) {
                "$scanCoverage:matched:r${detection.rotation()}:signature=${detection.signature()}:targets=${detection.targets().size}"
            } else {
                "$scanCoverage$observedSignature:unknown:${detection.unknownReason()}:rotation=${rotation ?: "unknown"}"
            }
        }
        val detection = boulderDetection
        if (detection == null || !detection.known()) {
            val reason = if (detection == null) "The built-in Boulder signatures are unavailable." else when (detection.unknownReason()) {
                DungeonBoulderRules.UnknownReason.INCOMPLETE_SCAN -> "Some signature blocks are in unloaded chunks."
                DungeonBoulderRules.UnknownReason.CATALOG_UNAVAILABLE -> "The built-in Boulder signatures are unavailable."
                DungeonBoulderRules.UnknownReason.INVALID_OBSERVATION -> "The Boulder signature observations are ambiguous."
                DungeonBoulderRules.UnknownReason.NO_CATALOG_MATCH -> "The loaded block signature matches no catalog board."
                DungeonBoulderRules.UnknownReason.AMBIGUOUS_ORIENTATION -> "More than one room orientation matched the signature."
                null -> "The Boulder signature is unavailable."
            }
            return manual("Boulder", reason, "No target position is shown.")
        }
        boulderProgress.observe("$identity|${detection.signature()}|${detection.rotation()}", detection.targets())
        val progress = boulderProgress.snapshot()
        val lines = ArrayList<String>()
        lines.add("Catalog match · rotation ${detection.rotation()} · ${progress.total()} ordered target steps.")
        lines.add("Locally observed target interactions: ${progress.completed()}/${progress.total()}.")
        if (progress.complete()) {
            lines.add("All catalog targets were interacted with locally; verify the puzzle state in the room.")
        } else {
            val target = progress.current()
            if (target == null) {
                lines.add("The next catalog target has no resolved position.")
            } else {
                val stepNumber = progress.completed() + 1
                val render = target.renderPosition()
                val click = target.clickPosition()
                if (render == null || click == null) {
                    lines.add("The next catalog target has no resolved position.")
                } else {
                    lines.add("Next target $stepNumber/${progress.total()}: ${render.x()}, ${render.y()}, ${render.z()}.")
                    lines.add("Catalog interaction block: ${click.x()}, ${click.y()}, ${click.z()}.")
                }
            }
        }
        lines.add("Progress records only consumed local interactions, not server-confirmed pushes. No inputs are automated.")
        return DungeonOdinPuzzleObserver.Guidance("Boulder", lines)
    }

    private fun observeWaterBoard(
        client: Minecraft?,
        clientTick: Long,
        room: Room,
        strategy: String?,
    ): DungeonOdinPuzzleObserver.Guidance? = observeWaterBoard(client, clientTick, room, strategy, false)

    private fun observeWaterBoard(
        client: Minecraft?,
        clientTick: Long,
        room: Room,
        strategy: String?,
        signatureFallback: Boolean,
    ): DungeonOdinPuzzleObserver.Guidance? {
        if (client == null || client.level == null || !client.isSameThread) {
            if (signatureFallback) return null
            return manual("Water Board", "The client world is unavailable on the client thread.", "No route was generated.")
        }
        if (signatureFallback && ((!room.roomName.isNullOrBlank()) || room.shape != "1x1")) {
            rejectWaterBoardFallback(if (!room.roomName.isNullOrBlank()) "room-name-present" else "footprint-not-1x1")
            return null
        }
        val currentRoomIdentity = "${room.floor}|${room.signature}|${room.cell.baseX}|${room.cell.baseZ}" +
            if (signatureFallback) "|signature-fallback" else "|room-catalog"
        val optimized = strategy.equals("OPTIMIZED", ignoreCase = true)
        if (currentRoomIdentity != waterBoardRoomIdentity || waterBoardLevelIdentity !== client.level) {
            waterBoardRoomIdentity = currentRoomIdentity
            waterBoardLevelIdentity = client.level
            lastWaterBoardScanTick = Long.MIN_VALUE
            cachedWaterBoardScan = null
            waterBoardProgress = null
        } else if (waterBoardProgress != null && waterBoardProgress!!.optimized() != optimized) {
            waterBoardProgress = null
            cachedWaterBoardScan = null
            lastWaterBoardScanTick = Long.MIN_VALUE
        }
        if (waterBoardProgress != null) return waterBoardProgress!!.guidance(clientTick)
        if (lastWaterBoardScanTick == Long.MIN_VALUE || clientTick - lastWaterBoardScanTick >= WATER_BOARD_SCAN_PERIOD_TICKS) {
            cachedWaterBoardScan = scanWaterBoard(client, room, signatureFallback)
            lastWaterBoardScanTick = clientTick
        }
        val scan = cachedWaterBoardScan
        if (scan == null || scan.detection?.known() != true) {
            if (scan != null && scan.detection?.unknownReason() != DungeonWaterBoardRules.UnknownReason.UNLOADED_PROBES)
                waterBoardProgress = null
            val reason = if (scan == null) "Waiting for a loaded board signature." else when (scan.detection?.unknownReason()) {
                DungeonWaterBoardRules.UnknownReason.INCOMPLETE_ROTATIONS,
                DungeonWaterBoardRules.UnknownReason.ACTIVE_ORIENTATION_UNAVAILABLE -> "The active room orientation is not resolved."
                DungeonWaterBoardRules.UnknownReason.UNLOADED_PROBES -> "Some active pattern or slot probes are not in loaded chunks."
                DungeonWaterBoardRules.UnknownReason.NO_CONFIRMED_SIGNATURE -> "Loaded blocks do not match a confirmed Water Board signature."
                DungeonWaterBoardRules.UnknownReason.AMBIGUOUS_ROTATION -> "More than one room orientation matched the board probes."
                null -> "The Water Board signature is unavailable."
            }
            return if (signatureFallback) null else manual("Water Board", reason, "The route stays unknown until one loaded signature is confirmed.")
        }
        if (!DungeonWaterBoardCatalog.bundled().available()) {
            cachedWaterBoardScan = WaterBoardScan(scan.detection, scan.leverPositions, scan.signatureFallback, "catalog-unavailable")
            if (signatureFallback) return null
            return manual("Water Board", "The bundled route catalog is unavailable.", "No route was generated.")
        }
        val detection = scan.detection!!
        val route = DungeonWaterBoardCatalog.route(optimized, detection.patternId(), detection.extendedSlots())
        if (route.isEmpty()) {
            cachedWaterBoardScan = WaterBoardScan(scan.detection, scan.leverPositions, scan.signatureFallback, "route-not-found")
            if (signatureFallback) return null
            return manual("Water Board", "No licensed route matches the observed pattern and slot signature.", "No route was generated.")
        }
        val identity = "${room.floor}|${room.signature}|${detection.rotation()}|${detection.patternId()}|${detection.extendedSlots()}" +
            if (signatureFallback) "|signature-fallback" else "|room-catalog"
        waterBoardProgress = WaterBoardProgress(identity, optimized, detection, route, scan.leverPositions, signatureFallback)
        return waterBoardProgress!!.guidance(clientTick)
    }

    private fun scanWaterBoard(client: Minecraft, room: Room, signatureFallback: Boolean): WaterBoardScan {
        if (signatureFallback) {
            val candidates = ArrayList<DungeonWaterBoardRules.Candidate>(4)
            for (rotation in 0 until 4) {
                val patternIds = ArrayList<Int>(1)
                var loaded = true
                for (marker in WATER_BOARD_MARKERS) {
                    val position = waterBoardPosition(room, rotation, marker.relativePosition)
                    val state = loadedState(client, position)
                    if (state == null) loaded = false else if (state.`is`(marker.block)) patternIds.add(marker.patternId)
                }
                val extendedSlots = StringBuilder(3)
                for (slot in 0 until 5) {
                    val position = waterBoardPosition(room, rotation, BlockPos(15, 56, 19 - slot))
                    val state = loadedState(client, position)
                    if (state == null) loaded = false else if (!state.isAir) extendedSlots.append(slot)
                }
                candidates.add(DungeonWaterBoardRules.Candidate(rotation, patternIds, extendedSlots.toString(), loaded))
            }
            val detection = DungeonWaterBoardRules.detect(candidates)
            if (!detection.known()) return WaterBoardScan(detection, emptyMap(), true, null)
            val leverPositions = LinkedHashMap<String, BlockPos>()
            for (lever in WATER_BOARD_LEVERS) leverPositions[lever.id] = waterBoardPosition(room, detection.rotation(), lever.relativePosition)
            return WaterBoardScan(detection, java.util.Map.copyOf(leverPositions), true, null)
        }
        val identity = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
        val transform = sourceRoomTransform(client, identity)
        val rotation = if (transform == null || !transform.resolved()) null else roomGridRotation(transform.rotation())
        if (rotation == null) {
            return WaterBoardScan(
                DungeonWaterBoardRules.Detection(-1, -1, "", DungeonWaterBoardRules.UnknownReason.ACTIVE_ORIENTATION_UNAVAILABLE),
                emptyMap(), false, null,
            )
        }
        val patternIds = ArrayList<Int>(1)
        var loaded = true
        for (marker in WATER_BOARD_MARKERS) {
            val position = waterBoardPosition(room, rotation, marker.relativePosition)
            val state = loadedState(client, position)
            if (state == null) loaded = false else if (state.`is`(marker.block)) patternIds.add(marker.patternId)
        }
        val extendedSlots = StringBuilder(3)
        for (slot in 0 until 5) {
            val position = waterBoardPosition(room, rotation, BlockPos(15, 56, 19 - slot))
            val state = loadedState(client, position)
            if (state == null) loaded = false else if (!state.isAir) extendedSlots.append(slot)
        }
        val detection = DungeonWaterBoardRules.detect(
            listOf(DungeonWaterBoardRules.Candidate(rotation, patternIds, extendedSlots.toString(), loaded)),
        )
        if (!detection.known()) return WaterBoardScan(detection, emptyMap(), false, null)
        val leverPositions = LinkedHashMap<String, BlockPos>()
        for (lever in WATER_BOARD_LEVERS) leverPositions[lever.id] = waterBoardPosition(room, rotation, lever.relativePosition)
        return WaterBoardScan(detection, java.util.Map.copyOf(leverPositions), false, null)
    }

    private fun loadedState(client: Minecraft?, position: BlockPos): net.minecraft.world.level.block.state.BlockState? {
        val level = client?.level ?: return null
        return if (level.hasChunkAt(position)) level.getBlockState(position) else null
    }

    private fun waterBoardPosition(room: Room, rotation: Int, relative: BlockPos): BlockPos {
        val world = DungeonGridRules.toWorld(rotation, relative.x, relative.z)
        return BlockPos(room.cell.baseX + world.x, relative.y, room.cell.baseZ + world.z)
    }

    private fun observeCreeperBeams(
        client: Minecraft?,
        clientTick: Long,
        room: Room,
    ): DungeonOdinPuzzleObserver.Guidance {
        if (client == null || client.level == null || !client.isSameThread)
            return manual("Creeper Beams", "The client world is unavailable on the client thread.", "No endpoint pairing was generated.")
        if (!DungeonCreeperBeamsRules.isCreeperBeamsRoom(room.roomName)) {
            clearCreeperBeamsObservation()
            return manual("Creeper Beams", "The exact current Puzzle room is not Creeper Beams.", "No endpoint pairing was scanned.")
        }
        val currentRoomIdentity = creeperBeamsRoomIdentity(room)
        if (currentRoomIdentity != creeperBeamsRoomIdentity || creeperBeamsLevelIdentity !== client.level) {
            creeperBeamsRoomIdentity = currentRoomIdentity
            creeperBeamsLevelIdentity = client.level
            creeperBeamsResolvedRotation = null
            lastCreeperBeamsScanTick = Long.MIN_VALUE
            cachedCreeperBeams = null
        }
        if (lastCreeperBeamsScanTick == Long.MIN_VALUE || cachedCreeperBeams == null ||
            (cachedCreeperBeams!!.detection.known().not() && clientTick - lastCreeperBeamsScanTick >= ROOM_SCAN_PERIOD_TICKS)) {
            cachedCreeperBeams = scanCreeperBeams(client, room)
            creeperBeamsResolvedRotation = if (cachedCreeperBeams!!.detection.known()) cachedCreeperBeams!!.detection.rotation() else null
            lastCreeperBeamsScanTick = clientTick
        }
        val detection = cachedCreeperBeams?.detection
        if (detection == null || !detection.known()) {
            val unknown = detection?.unknownReason()
            val reason = if (unknown == DungeonCreeperBeamsRules.UnknownReason.CATALOG_UNAVAILABLE)
                "The bundled endpoint catalog could not be loaded." else "The room orientation is not resolved yet."
            return manual("Creeper Beams", reason, "Endpoint pairs stay hidden until room orientation is known.")
        }
        return DungeonOdinPuzzleObserver.Guidance(
            "Creeper Beams", DungeonCreeperBeamsRules.guidanceLines(detection, room.cell.baseX, room.cell.baseZ),
        )
    }

    private fun scanCreeperBeams(client: Minecraft, room: Room): CreeperBeamsSnapshot {
        val baseX = room.cell.baseX
        val baseZ = room.cell.baseZ
        val catalog = DungeonCreeperBeamsCatalog.bundled()
        val identity = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
        val transform = if (identity == null) null else sourceRoomTransform(client, identity)
        val rotation = if (transform == null || !transform.resolved()) null else roomGridRotation(transform.rotation())
        if (catalog == null || !catalog.available() || rotation == null) {
            val unknown = DungeonCreeperBeamsRules.Detection(
                -1, emptyList(), if (catalog == null || !catalog.available()) DungeonCreeperBeamsRules.UnknownReason.CATALOG_UNAVAILABLE
                else DungeonCreeperBeamsRules.UnknownReason.AMBIGUOUS_ORIENTATION,
            )
            return CreeperBeamsSnapshot(unknown, unknown, catalog, emptyList(), null)
        }
        return scanCreeperBeamsAtRotation(client, room, catalog, rotation)
    }

    private fun scanCreeperBeamsAtRotation(
        client: Minecraft,
        room: Room,
        catalog: DungeonCreeperBeamsCatalog.CatalogResult?,
        rotation: Int,
    ): CreeperBeamsSnapshot {
        val baseX = room.cell.baseX
        val baseZ = room.cell.baseZ
        if (catalog == null || !catalog.available()) {
            val unknown = DungeonCreeperBeamsRules.Detection(-1, emptyList(), DungeonCreeperBeamsRules.UnknownReason.CATALOG_UNAVAILABLE)
            return CreeperBeamsSnapshot(unknown, unknown, catalog, emptyList(), null)
        }
        val catalogPairs = DungeonCreeperBeamsRules.catalogPairsForRotation(baseX, baseZ, catalog, rotation)
        val activePairs = ArrayList<DungeonCreeperBeamsRules.Pair>()
        val observations = LinkedHashMap<DungeonCreeperBeamsRules.WorldPosition, DungeonCreeperBeamsRules.BlockKind>()
        for (pair in catalogPairs) {
            val pairKinds = arrayOf(DungeonCreeperBeamsRules.BlockKind.OTHER, DungeonCreeperBeamsRules.BlockKind.OTHER)
            var pairIndex = 0
            for (endpoint in listOf(pair.first, pair.second)) {
                val position = worldPosition(endpoint)!!
                val blockPos = BlockPos(position.x, position.y, position.z)
                val state = loadedState(client, blockPos)
                val kind = blockKind(state)
                pairKinds[pairIndex++] = kind
                if (state != null) observations[position] = kind
            }
            if (pairKinds[0] == DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN && pairKinds[1] == DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN)
                activePairs.add(pair)
        }
        val detection = DungeonCreeperBeamsRules.Detection(rotation, activePairs, null)
        val observedBlocks = observations.map { DungeonCreeperBeamsRules.ObservedBlock(it.key, it.value) }
        return CreeperBeamsSnapshot(detection, detection, catalog, observedBlocks, rotation)
    }

    fun planIceFill(
        observedCells: Set<DungeonPuzzleLogic.Cell?>?,
        playerCell: DungeonPuzzleLogic.Cell?,
        nodeBudget: Int,
    ): DungeonOdinPuzzleObserver.IcePlan {
        val plan = DungeonOdinPuzzleObserverLogic.planIceFill(observedCells, playerCell, nodeBudget)
        return DungeonOdinPuzzleObserver.IcePlan(plan.route, plan.reason)
    }

    private fun scanBlazes(client: Minecraft?, room: Room): List<AssistModuleObservation> {
        val level = client?.level ?: return emptyList()
        if (!client.isSameThread) return emptyList()
        val observations = ArrayList<AssistModuleObservation>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand || entity.isRemoved || !entity.isAlive || entity.customName == null) continue
            val name = ChatText.plain(entity.customName!!.string)
            val health = DungeonPuzzleLogic.parseBlazeHealth(name) ?: continue
            observations.add(AssistModuleObservation(entity.uuid.toString().substring(0, 8), health))
        }
        return observations.distinct()
    }

    private fun confirmedNpc(client: Minecraft?, speaker: String): ArmorStand? {
        val wanted = normalizeName(speaker)
        val level = client?.level ?: return null
        if (wanted.isBlank()) return null
        for (entity in level.entitiesForRendering()) {
            if (entity is ArmorStand && !entity.isRemoved && entity.isAlive && normalizeName(entityName(entity)) == wanted) return entity
        }
        return null
    }

    private fun entityName(entity: Entity): String {
        val component = entity.customName ?: entity.name
        return ChatText.plain(component.string).trim()
    }

    private fun normalizeName(value: String?): String = ChatText.plain(value ?: "").trim().lowercase(Locale.ROOT)

    private fun toGraphPosition(position: Vec3?): DungeonTpMazeGraph.Position? =
        if (position == null) null else toGraphPosition(position.x, position.y, position.z)

    private fun toGraphPosition(x: Double, y: Double, z: Double): DungeonTpMazeGraph.Position =
        DungeonTpMazeGraph.Position(x, y, z)

    private fun currentRoom(client: Minecraft?): Room? {
        if (client == null || !client.isSameThread) {
            tpMazeIdentityDiagnostic = "thread-unavailable"
            return null
        }
        if (client.player == null || client.level == null) {
            tpMazeIdentityDiagnostic = "world-unavailable"
            return null
        }
        if (!DungeonContextTracker.inDungeon()) {
            tpMazeIdentityDiagnostic = "dungeon-inactive"
            return null
        }
        val room = OdinDungeonRoomIdentity.currentPuzzleRoom(client)
        if (room == null) {
            tpMazeIdentityDiagnostic = mapIdentityFailure(client)
            return null
        }
        tpMazeIdentityDiagnostic = "confirmed:cell=${room.cell().componentX},${room.cell().componentZ};" +
            "name=${boundedDiagnostic(room.roomName(), 40)};data=${if (room.roomData() == null) "missing" else "ready"};" +
            "shape=${room.geometry()?.shape() ?: "missing"}"
        return Room(room.floor()?.name ?: "unknown", room.mapSignature(), room.cell(), room.roomName(), room.geometry()?.shape() ?: "unknown")
    }

    private fun mapIdentityFailure(client: Minecraft): String {
        val reading = DungeonMapRoomDetector.current() ?: return "map-reading-unavailable"
        if (!reading.hasCurrentCell()) return "map=${reading.status}:" + boundedDiagnostic(reading.fallbackReason, 64)
        val geometry = reading.geometry
        if (geometry == null) return "map-geometry-missing"
        val roomType = geometry.roomType() ?: return "map-room-type-missing"
        if (roomType != geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.PUZZLE)
            return "map-room=${roomType.label()}"
        val mapCell = reading.currentCell
        val playerCell = DungeonGridRules.cellAt(client.player!!.blockPosition().x, client.player!!.blockPosition().z)
        if (mapCell == null || playerCell == null || mapCell.componentX != playerCell.componentX ||
            mapCell.componentZ != playerCell.componentZ) return "map=puzzle;player-cell-mismatch"
        return "map=puzzle;identity-unavailable"
    }

    private fun rejectWaterBoardFallback(reason: String?) {
        clearWaterBoardFallbackState()
        waterBoardFallbackDiagnostic = "source=signature-fallback;state=rejected;reason=" + boundedDiagnostic(reason, 64)
    }

    private fun clearWaterBoardFallbackState() {
        val fallbackScan = cachedWaterBoardScan?.signatureFallback == true
        val fallbackProgress = waterBoardProgress?.signatureFallback() == true
        if (!fallbackScan && !fallbackProgress) return
        lastWaterBoardScanTick = Long.MIN_VALUE
        waterBoardRoomIdentity = null
        waterBoardLevelIdentity = null
        cachedWaterBoardScan = null
        waterBoardProgress = null
    }

    private fun ensureRoom(room: Room) {
        val signature = roomIdentity(room)
        if (signature != roomSignature) resetRoom(signature)
    }

    private fun ensureWeirdosLevel(level: Any?) {
        if (level == null || level === weirdosLevelIdentity) return
        weirdosLevelIdentity = level
        weirdosStatements.clear()
        weirdosMarkers.clear()
    }

    private fun ensurePortalLevel(level: Any?) {
        if (portalLevelIdentity === level) return
        portalLevelIdentity = level
        portalScanComplete = false
        tpMazeCatalogDetection = null
        tpMazeTransformDiagnostic = "not-attempted"
        tpMazeCatalogDiagnostic = "not-attempted"
        tpMazeCorrectionDiagnostic = "none"
        tpMazeVisitedPadIds.clear()
        tpMazeViewCandidateIds.clear()
        tpMazeCurrentPadId = null
        tpMazeSuggestedPadId = null
    }

    private fun tpMazePadAt(positionBounds: AABB?, pads: List<DungeonTpMazeRules.Pad>?): Int? {
        if (positionBounds == null || pads == null) return null
        for (pad in pads) {
            val position = pad?.position ?: continue
            if (positionBounds.intersects(AABB(position.x.toDouble(), position.y.toDouble(), position.z.toDouble(),
                    position.x + 1.0, position.y + 1.0, position.z + 1.0))) return pad.id
        }
        return null
    }

    private fun roomIdentity(room: Room): String = OdinDungeonRoomIdentity.mapIdentity(DungeonFloor.parse(room.floor), room.signature, room.cell)

    private fun creeperBeamsRoomIdentity(room: Room): String = roomIdentity(room)

    private fun clearCreeperBeamsObservation() {
        lastCreeperBeamsScanTick = Long.MIN_VALUE
        creeperBeamsRoomIdentity = null
        creeperBeamsLevelIdentity = null
        creeperBeamsResolvedRotation = null
        cachedCreeperBeams = null
    }

    private fun clearTicTacToe(reason: String?) {
        ticTacToeHold.clear()
        ticTacToeRoomIdentity = null
        ticTacToeLevelIdentity = null
        ticTacToeSuggestion.clear()
        ticTacToePredictedXTarget = null
        ticTacToePredictedOTarget = null
        ticTacToeDiagnostic = boundedDiagnostic(
            "board=inactive;reason=${reason ?: "unknown"};pieces=0;X=0;O=0;turn=unknown;suggested=-;" +
                "prediction=inactive;xReply=-;nextO=-", 240,
        )
    }

    private fun clearIceRoute() {
        iceWorldRoute = emptyList()
        iceRouteStrategy = null
        iceScanComplete = false
        iceLevelIdentity = null
        iceRoomIdentity = null
    }

    private fun clearIcePathRoute(diagnostic: String?) {
        icePathBoard = null
        icePathSilverfish = null
        icePathRoute = emptyList()
        icePathWorldRoute = emptyList()
        icePathLevelIdentity = null
        icePathRoomIdentity = null
        icePathScanDiagnostic = diagnostic ?: "unknown"
    }

    private fun resetRoom(signature: String?) {
        roomSignature = signature
        clearTicTacToe(if (signature == null) "dungeon-reset" else "room-transition")
        if (signature == null) {
            quizTracker.reset()
            quizVisualDiagnostic = "dungeon-reset"
        } else {
            quizVisualDiagnostic = "room-transition;retained"
        }
        lastIceScanTick = Long.MIN_VALUE
        lastBlazeScanTick = Long.MIN_VALUE
        lastBoulderScanTick = Long.MIN_VALUE
        lastWaterBoardScanTick = Long.MIN_VALUE
        lastCreeperBeamsScanTick = Long.MIN_VALUE
        waterBoardFallbackDiagnostic = "not-active"
        waterBoardRoomIdentity = null
        waterBoardLevelIdentity = null
        creeperBeamsRoomIdentity = null
        creeperBeamsLevelIdentity = null
        creeperBeamsResolvedRotation = null
        boulderRoomIdentity = null
        boulderLevelIdentity = null
        boulderDetection = null
        boulderScanDiagnostic = "not-scanned"
        boulderProgress.clear()
        clearIceRoute()
        clearIcePathRoute(if (signature == null) "dungeon-reset" else "room-transition")
        portalScanComplete = false
        portalLevelIdentity = null
        tpMazeCatalogDetection = null
        tpMazeTransformDiagnostic = "not-attempted"
        tpMazeCatalogDiagnostic = "not-attempted"
        tpMazeCorrectionDiagnostic = "none"
        tpMazeVisitedPadIds.clear()
        tpMazeViewCandidateIds.clear()
        tpMazeCurrentPadId = null
        tpMazeSuggestedPadId = null
        cachedBlazes = emptyList()
        cachedWaterBoardScan = null
        waterBoardProgress = null
        cachedCreeperBeams = null
        weirdosStatements.clear()
        weirdosMarkers.clear()
        weirdosLevelIdentity = null
    }

    private inner class WaterBoardProgress(
        private val identity: String,
        private val optimized: Boolean,
        private val detection: DungeonWaterBoardRules.Detection,
        route: List<DungeonWaterBoardCatalog.Click>,
        leverPositions: Map<String, BlockPos>,
        private val signatureFallback: Boolean,
    ) {
        private val route = route.toList()
        private val leverPositions = leverPositions.toMap()
        private val clicksByLever = HashMap<String, Int>()
        private var waterStartedTick = Long.MIN_VALUE

        fun identity(): String = identity
        fun optimized(): Boolean = optimized
        fun signatureFallback(): Boolean = signatureFallback
        fun leverPositions(): Map<String, BlockPos> = leverPositions

        fun diagnosticSummary(): String = boundedDiagnostic(
            "source=${if (signatureFallback) "signature-fallback" else "room-catalog"}" +
                ";locked=true;strategy=${if (optimized) "optimized" else "standard"}" +
                ";rotation=${detection.rotation()};pattern=${detection.patternId()}" +
                ";slots=${detection.extendedSlots()};remaining=${pending().size}/${route.size}", 180,
        )

        fun observeClick(position: BlockPos?, tick: Long): Boolean {
            if (position == null) return false
            val leverId = leverPositions.entries.firstOrNull { it.value == position }?.key ?: return false
            clicksByLever[leverId] = (clicksByLever[leverId] ?: 0) + 1
            if (leverId == "water" && waterStartedTick == Long.MIN_VALUE) waterStartedTick = tick
            return true
        }

        fun pending(): List<DungeonWaterBoardCatalog.Click> {
            val seen = HashMap<String, Int>()
            val pending = ArrayList<DungeonWaterBoardCatalog.Click>()
            for (click in route) {
                val leverId = click.leverId ?: continue
                val index = seen.getOrDefault(leverId, 0)
                seen[leverId] = index + 1
                if (index >= (clicksByLever[leverId] ?: 0)) pending.add(click)
            }
            return pending.toList()
        }

        fun scheduleLabels(tick: Long): List<DungeonOdinPuzzleObserver.WaterBoardLabel> {
            val labels = ArrayList<DungeonOdinPuzzleObserver.WaterBoardLabel>()
            val stackOffsets = HashMap<String, Int>()
            val seen = HashMap<String, Int>()
            for (click in route) {
                val lever = click.leverId ?: continue
                val stack = stackOffsets.getOrDefault(lever, 0)
                stackOffsets[lever] = stack + 1
                val occurrence = seen.getOrDefault(lever, 0)
                seen[lever] = occurrence + 1
                if (occurrence < (clicksByLever[lever] ?: 0)) continue
                val position = leverPositions[lever] ?: continue
                val text: String
                val color: Int
                if (waterStartedTick == Long.MIN_VALUE) {
                    val clickNow = click.seconds == 0.0
                    text = formattedSeconds(click.seconds)
                    color = if (clickNow) 0xFF55FF55.toInt() else 0xFFFFAA00.toInt()
                } else {
                    val ticksRemaining = waterStartedTick + Math.round(click.seconds * 20.0) - tick
                    val clickNow = ticksRemaining <= 0
                    text = formattedSeconds(maxOf(0L, ticksRemaining) / 20.0)
                    color = if (clickNow) 0xFF55FF55.toInt() else 0xFFFFAA00.toInt()
                }
                labels.add(
                    DungeonOdinPuzzleObserver.WaterBoardLabel(
                        DungeonOdinPuzzleObserver.WorldBlock(position.x, position.y + stack, position.z), text, color,
                    ),
                )
            }
            return labels.toList()
        }

        fun guidance(tick: Long): DungeonOdinPuzzleObserver.Guidance {
            val strategyName = if (optimized) "Optimized" else "Standard"
            val lines = ArrayList<String>()
            lines.add("$strategyName route · pattern ${detection.patternId() + 1} · slots ${detection.extendedSlots()}")
            val pending = pending()
            if (pending.isEmpty()) {
                lines.add("Route complete.")
                return DungeonOdinPuzzleObserver.Guidance("Water Board", lines)
            }
            if (waterStartedTick == Long.MIN_VALUE) {
                val beforeFlow = pending.filter { it.seconds == 0.0 }
                if (beforeFlow.isEmpty()) lines.add("CLICK ME: Water lever")
                else beforeFlow.take(3).forEach { lines.add("CLICK ME: ${leverLabel(it.leverId ?: "unknown")} lever") }
                return DungeonOdinPuzzleObserver.Guidance("Water Board", lines)
            }
            val next = pending.first()
            val elapsed = maxOf(0L, tick - waterStartedTick)
            val ticksRemaining = Math.round(next.seconds * 20.0) - elapsed
            if (ticksRemaining <= 0) lines.add("CLICK ME: ${leverLabel(next.leverId ?: "unknown")} lever")
            else lines.add(
                "Next: ${leverLabel(next.leverId ?: "unknown")} lever in ${String.format(Locale.ROOT, "%.1f", ticksRemaining / 20.0)}s.",
            )
            pending.drop(1).take(1).forEach { click ->
                val nextTicks = Math.round(click.seconds * 20.0) - elapsed
                lines.add(
                    "Next after: ${leverLabel(click.leverId ?: "unknown")} in " +
                        "${String.format(Locale.ROOT, "%.1f", maxOf(0L, nextTicks) / 20.0)}s.",
                )
            }
            return DungeonOdinPuzzleObserver.Guidance("Water Board", lines)
        }

        private fun leverLabel(id: String): String = WATER_BOARD_LEVERS.firstOrNull { it.id == id }?.label ?: "unknown"

        private fun formattedSeconds(seconds: Double): String = String.format(Locale.ROOT, "%.1fs", maxOf(0.0, seconds))
    }

    private fun manual(name: String, reason: String, action: String): DungeonOdinPuzzleObserver.Guidance =
        DungeonOdinPuzzleObserver.Guidance(name, listOf(reason, action), DungeonOdinPuzzleObserver.ScanStatus.INCOMPLETE, reason)

    private fun valueOrDash(value: Int?): String = value?.toString() ?: "-"

    private fun boundedDiagnostic(value: String?, limit: Int): String {
        if (value.isNullOrBlank()) return "none"
        val clean = StringBuilder(minOf(value.length, limit))
        var index = 0
        while (index < value.length && clean.length < limit) {
            val character = value[index]
            val allowed = character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                " _.,:;=/-+".contains(character)
            clean.append(if (allowed) character else '?')
            index++
        }
        return clean.toString()
    }

    private fun orFallback(
        guidance: DungeonOdinPuzzleObserver.Guidance?,
        name: String,
        reason: String,
    ): DungeonOdinPuzzleObserver.Guidance = guidance ?: manual(name, reason, "No click or movement automation is used.")
}
