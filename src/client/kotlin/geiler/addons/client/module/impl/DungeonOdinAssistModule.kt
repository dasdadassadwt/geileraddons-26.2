package geiler.addons.client.module.impl

import geiler.addons.client.access.PlayerTabOverlayAccess
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonMapInfoLogic
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGuideSegments
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.DungeonMapRoomDetector
import geiler.addons.client.dungeon.DungeonQueueFloorTracker
import geiler.addons.client.dungeon.DungeonSplitPersonalBestBook
import geiler.addons.client.dungeon.DungeonSplitPersonalBestStore
import geiler.addons.client.dungeon.DungeonSplitTimeMode
import geiler.addons.client.dungeon.odin.DungeonBoulderRules
import geiler.addons.client.dungeon.odin.DungeonIcePathSolver
import geiler.addons.client.dungeon.odin.DungeonLividTracker
import geiler.addons.client.dungeon.odin.DungeonLividOverlayLabel
import geiler.addons.client.dungeon.odin.DungeonLividReleaseRules
import geiler.addons.client.dungeon.odin.DungeonMaskProcTimers
import geiler.addons.client.dungeon.odin.DungeonOdinBlazeOverlay
import geiler.addons.client.dungeon.odin.DungeonOdinPuzzleObserver
import geiler.addons.client.dungeon.odin.DungeonOdinScoreCalculator
import geiler.addons.client.dungeon.odin.DungeonPuzzleAutoSelectionRules
import geiler.addons.client.dungeon.odin.DungeonPuzzleLogic
import geiler.addons.client.dungeon.odin.DungeonPuzzlePersonalBestStore
import geiler.addons.client.dungeon.odin.DungeonPuzzleTimingTracker
import geiler.addons.client.dungeon.odin.DungeonPuzzleVisualRenderer
import geiler.addons.client.dungeon.odin.DungeonQuizRules
import geiler.addons.client.dungeon.odin.DungeonScoreAlerts
import geiler.addons.client.dungeon.odin.DungeonServerTickCounter
import geiler.addons.client.dungeon.odin.DungeonSoundRules
import geiler.addons.client.dungeon.odin.DungeonSplitEntryBuffer
import geiler.addons.client.dungeon.odin.DungeonSplitTracker
import geiler.addons.client.dungeon.odin.DungeonTerminalBreakdown
import geiler.addons.client.dungeon.odin.DungeonTpMazeRenderGeometry
import geiler.addons.client.dungeon.odin.DungeonWaterBoardSolver
import geiler.addons.client.dungeon.odin.IceFillRenderGeometry
import geiler.addons.client.dungeon.odin.OdinDungeonPlayerMap
import geiler.addons.client.dungeon.odin.OdinDungeonPlayerRoster
import geiler.addons.client.dungeon.odin.SpiritLeapArrivalRules
import geiler.addons.client.dungeon.odin.SpiritLeapIncomingPresentation
import geiler.addons.client.dungeon.odin.SpiritLeapIncomingRules
import geiler.addons.client.dungeon.odin.SpiritLeapLayout
import geiler.addons.client.dungeon.odin.SpiritLeapRoster
import geiler.addons.client.dungeon.odin.SpiritLeapSelection
import geiler.addons.client.dungeon.odin.SpiritLeapMapLogic
import geiler.addons.client.dungeon.odin.ThreeWeirdosLogic
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.TextSetting
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.party.PartyMember
import geiler.addons.client.party.PartySnapshot
import geiler.addons.client.presentation.ComponentWidthLimiter
import geiler.addons.client.presentation.PlayerTabNameSource
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import java.util.Optional
import java.util.OptionalInt
import java.util.UUID
import java.util.function.IntPredicate

/** Event-facing, display-only adapter for the Odin-style dungeon helper logic. */
class DungeonOdinAssistModule private constructor() : Module(
    "Dungeon Feature Runtime",
    "Internal event adapter for independent dungeon modules.",
    Category.F7,
    false,
) {
    companion object {
        @JvmField
        val INSTANCE = DungeonOdinAssistModule()

        private const val PUZZLE_TRACER_START_DISTANCE = 0.5
        private const val PUZZLE_TRACER_DIAGNOSTIC_ORIGIN = "tracerOrigin=main-camera-forward-0.5-y0"
        private const val LEAP_CARD_TEXT_X = 60
        private const val LEAP_CARD_RIGHT_PADDING = 8

        @JvmStatic
        fun isLoadedLeapMemberDead(alive: Boolean, health: Float): Boolean =
            SpiritLeapDispatchLogic.isLoadedMemberDead(alive, health)

        @JvmStatic
        fun dispatchSpiritLeapTarget(
            menu: AbstractContainerMenu?,
            target: Optional<SpiritLeapSelection.Target>?,
            dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
        ): Boolean = SpiritLeapDispatchLogic.dispatchTarget(menu, target, dispatcher)

        private val DIAGNOSTIC_UNSAFE = Regex("[^A-Za-z0-9_ .,:/+-]")

        private fun diagnosticToken(value: String?, limit: Int): String {
            if (value.isNullOrBlank()) return "none"
            val clean = value.replace(DIAGNOSTIC_UNSAFE, "?").trim()
            return if (clean.length <= limit) clean else clean.substring(0, limit)
        }

        private fun diagnosticScanCompleteness(status: String?): String = when (status ?: "") {
            "complete" -> "true"
            "incomplete", "partial" -> "false"
            else -> "unknown"
        }

        private fun playLocalSound(soundId: String?, pitch: Float): String {
            val id = Identifier.tryParse(soundId?.trim().orEmpty()) ?: return "invalid-id"
            val event = BuiltInRegistries.SOUND_EVENT.getValue(id) ?: return "unregistered"
            val client = Minecraft.getInstance()
            if (!client.isSameThread) return "wrong-thread"
            client.soundManager.play(SimpleSoundInstance.forUI(event, pitch, 1.0f))
            return "submitted"
        }

        private fun playWeirdosCorrectClueSound() {
            val id = Identifier.tryParse(DungeonSoundRules.WEIRDOS_CORRECT_CLUE_SOUND) ?: return
            val event = BuiltInRegistries.SOUND_EVENT.getValue(id) ?: return
            val client = Minecraft.getInstance()
            val player = client.player ?: return
            if (!client.isSameThread) return
            player.level().playLocalSound(
                player,
                event,
                SoundSource.PLAYERS,
                DungeonSoundRules.WEIRDOS_CORRECT_CLUE_VOLUME,
                DungeonSoundRules.WEIRDOS_CORRECT_CLUE_PITCH,
            )
        }

        private fun lividFloorFiveBoss(): Boolean {
            val floor = DungeonContextTracker.currentFloor()
            return DungeonContextTracker.inDungeon() && floor != null && floor.number() == 5
                && !DungeonContextTracker.isMobEspPhase()
        }

        private fun spiritLeapMenuHeads(screen: AbstractContainerScreen<*>): List<SpiritLeapSelection.MenuHead> =
            screen.menu.slots.stream().map { slot ->
                SpiritLeapSelection.MenuHead(
                    slot.index,
                    if (slot.item.`is`(Items.PLAYER_HEAD)) slot.item.hoverName.string else null,
                    slot.item.`is`(Items.PLAYER_HEAD),
                )
            }.toList()

        private fun solverEnabled(id: String): Boolean =
            DungeonPuzzleSolversModule.INSTANCE.solverEnabled(id) && DungeonContextTracker.inDungeon()

        private fun puzzleFeatureActive(): Boolean {
            val module = DungeonPuzzleSolversModule.INSTANCE
            return module.hasEnabledSolvers() && DungeonContextTracker.isMobEspPhase()
        }

        private fun puzzleRuntimeActive(): Boolean {
            val module = DungeonPuzzleSolversModule.INSTANCE
            return module.isEnabled() && DungeonContextTracker.isMobEspPhase()
                && (module.anySolverEnabled() || DungeonPuzzleSolversModule.puzzleTimers.value() ||
                DungeonPuzzleSolversModule.draftPrompt.value())
        }

        private fun puzzleTimingActive(): Boolean {
            val module = DungeonPuzzleSolversModule.INSTANCE
            return module.isEnabled() && DungeonPuzzleSolversModule.puzzleTimers.value() &&
                DungeonContextTracker.isMobEspPhase()
        }

        private fun scorerEnabled(): Boolean = DungeonScorerModule.INSTANCE.isEnabled()
        private fun runSplitsEnabled(): Boolean = DungeonRunSplitsModule.INSTANCE.isEnabled()
        private fun lividEnabled(): Boolean = DungeonLividSolverModule.INSTANCE.isEnabled()
        private fun invincibilityEnabled(): Boolean = DungeonInvincibilityTimerModule.INSTANCE.isEnabled()
        private fun spiritLeapEnabled(): Boolean =
            SpiritLeapMenuModule.INSTANCE.isEnabled() && SpiritLeapMenuModule.showCardsAndKeys.value()
        private fun terminalStatsEnabled(): Boolean = DungeonTerminalStatsModule.INSTANCE.isEnabled()
        private fun terminalTitlesEnabled(): Boolean = DungeonTerminalTitlesModule.INSTANCE.isEnabled()

        internal fun scoreTabLines(client: Minecraft?): List<String> {
            if (client == null || client.gui == null) return emptyList()
            val player = client.player ?: return emptyList()
            if (player.connection == null) return emptyList()
            val overlay: PlayerTabOverlay = client.gui.hud.tabList ?: return emptyList()
            var ordered: List<PlayerInfo> = emptyList()
            try {
                val candidate = (overlay as PlayerTabOverlayAccess).`geileraddons$getPlayerInfos`()
                if (candidate != null && candidate.isNotEmpty()) ordered = candidate
            } catch (_: RuntimeException) {
                // Tab parsing is optional; the scorer remains silent when the vanilla row source is unavailable.
            } catch (_: LinkageError) {
                // Tab parsing is optional; the scorer remains silent when the vanilla row source is unavailable.
            }
            if (ordered.isEmpty()) return emptyList()
            val lines = ArrayList<String>(ordered.size)
            for (info in ordered) lines.add(PlayerTabNameSource.rawLine(info))
            return java.util.List.copyOf(lines)
        }

        private fun fitLeapCardText(font: Font, text: Component, maximumWidth: Int): Component =
            ComponentWidthLimiter.fit(
                text, maximumWidth,
                java.util.function.ToIntFunction { component -> font.width(component) },
            ) ?: text

        private fun leapCardName(playerName: String, fallbackColor: Int): Component {
            val base = Component.literal(playerName).withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(fallbackColor and 0x00FFFFFF)),
            )
            return PlayerPresentationModule.replaceRenderedName(playerName, base) ?: base
        }
    }

    private val optimizedSolutions: BooleanSetting
    private val tpMazeSingleCandidateColor: ColorSetting
    private val tpMazeMultipleCandidateColor: ColorSetting
    private val tpMazeVisitedColor: ColorSetting
    private val tpMazeTracer: BooleanSetting
    private val tpMazeTracerColor: ColorSetting
    private val scoreAlertsEnabled: BooleanSetting
    private val scoreThresholdOne: NumberSetting
    private val scoreThresholdTwo: NumberSetting
    private val scoreAlertText: TextSetting
    private val scoreAlertDurationTicks: NumberSetting
    private val scoreAlertSound: BooleanSetting
    private val scoreAlertSoundId: TextSetting
    private val scorePaulBonus: ChoiceSetting
    private val showSplits: BooleanSetting
    private val showCurrentSplit: BooleanSetting
    private val showTickTime: BooleanSetting
    private val showRealTime: BooleanSetting
    private val showPbPace: BooleanSetting
    private val showCountValue: BooleanSetting
    private val countSource: ChoiceSetting
    private val localRunSummary: BooleanSetting
    private val livid: BooleanSetting
    private val showBonzo: BooleanSetting
    private val showSpirit: BooleanSetting
    private val showPhoenix: BooleanSetting
    private val creeperBeamHitDing: BooleanSetting
    private val terminalBreakdownEnabled: BooleanSetting
    private val terminalTitles: BooleanSetting
    private val terminalSelfOnly: BooleanSetting
    private val leapOnMouseRelease: BooleanSetting
    private val leapAnnouncement: BooleanSetting
    private val leapSound: BooleanSetting
    private val incomingLeapTitle: BooleanSetting
    private val incomingLeapTitleDurationTicks: NumberSetting
    private val leapSoundId: TextSetting
    private val leapClassColors: BooleanSetting
    private val leapSort: ChoiceSetting
    private val leapKeyMode: ChoiceSetting
    private val terminalTitleTemplate: TextSetting
    private val customLeapOrder: TextSetting
    private val topLeftKey: TextSetting
    private val topRightKey: TextSetting
    private val bottomLeftKey: TextSetting
    private val bottomRightKey: TextSetting
    private val archerKey: TextSetting
    private val berserkKey: TextSetting
    private val healerKey: TextSetting
    private val mageKey: TextSetting
    private val tankKey: TextSetting
    private val scoreTracker = DungeonScoreAlerts()
    private val splitTracker = DungeonSplitTracker()
    private val splitEntryBuffer = DungeonSplitEntryBuffer()
    private val lividTracker = DungeonLividTracker()
    @Volatile private var publishedLividTarget: DungeonLividTracker.TargetSnapshot? = null
    private var titledLividTargetKey: String? = null
    private var lividMissingTicks = 0
    private var lastLividDiagnosticTick = Long.MIN_VALUE
    private var lastLividDiagnosticStatus: String? = null
    private var lastLividMarkerEvidence = "unobserved"
    private var lastLividMarkerSource = "none"
    private var lastLividCandidateDiagnostic = "not-scanned"
    private var lastLividRenderDiagnostic = "not-rendered"
    private var lividHudAnchor: Vec3? = null
    private var lividHudLabel: DungeonLividOverlayLabel.Snapshot? = null
    private var lastLividDiagnosticEvent = "startup"
    private var coalescedLividDiagnosticChanges = 0
    private val maskTimers = DungeonMaskProcTimers()
    private val terminals = DungeonTerminalBreakdown()
    private val leapRoster = SpiritLeapRoster()
    private val leapCardsCache = SpiritLeapRoster.CardsCache()
    private val leapCardHover = SpiritLeapLayout.HoverAnimation()
    private var cachedLeapSkinTextures: Map<String, Identifier> = emptyMap()
    private var cachedLeapSkinLevel: net.minecraft.client.multiplayer.ClientLevel? = null
    private var cachedLeapSkinRefreshTick = Long.MIN_VALUE
    private val leapArrivalCoalescer = SpiritLeapArrivalRules.Coalescer()
    private val incomingLeapCoalescer = SpiritLeapIncomingRules.Coalescer()
    private val puzzleObserver = DungeonOdinPuzzleObserver()
    private var waterBoardHudVisual: DungeonOdinPuzzleObserver.WaterBoardVisual? =
        DungeonOdinPuzzleObserver.WaterBoardVisual.empty()
    private var cachedTicTacToeFacingLevel: Any? = null
    private var cachedTicTacToeFacingRoom: String? = null
    private var cachedTicTacToeFacing: Direction? = null
    private val puzzleTimingTracker = DungeonPuzzleTimingTracker()
    private val blazeOverlay = DungeonOdinBlazeOverlay()
    private var puzzleLines: List<String> = emptyList()
    private var lastPuzzleDiagnosticTick = Long.MIN_VALUE
    private var lastPuzzleDiagnosticStatus: String? = null
    private var lastPuzzleScanStatus = "unknown"
    private var lastPuzzleScanReason = "No puzzle observation has been recorded."
    private var lastPuzzleDiagnosticEvent = "startup"
    private var lastPuzzleDiagnosticRender = "not-rendered"
    private var lastWaterBoardHudRender = "not-rendered"
    private var coalescedPuzzleDiagnosticChanges = 0L
    private var pendingPartyAnnouncement: String? = null
    private var pendingProcAlert: DungeonMaskProcTimers.Proc? = null
    private var pendingLeapSound = false
    private var wasInDungeon = false
    private var lastClientTick = 0L
    private var serverPingPacketCount = 0L
    private val serverTickCounter = DungeonServerTickCounter()
    private var lastPuzzleMapRefreshTick = Long.MIN_VALUE
    private var puzzleTimingLevel: Any? = null
    private var puzzlePersonalBestsLoaded = false
    private var lastPuzzleTimingRoomIdentity: String? = null
    private var scoreRunKey: String? = null
    private var scoreRunEntryKey: String? = null
    private var scoreBloodDone: Boolean? = null
    @Volatile private var mapRunInfo: DungeonMapInfoLogic.RunSnapshot? = null
    private var scoreRunEntryObserved = false
    private val scoreBonusSignals = DungeonOdinScoreCalculator.RunSignals()

    init {
        // This object is only an event/HUD adapter. Visible module state and settings belong to the
        // independently registered modules below; it must never become a second enable gate.
        optimizedSolutions = DungeonPuzzleSolversModule.optimizedSolutions
        tpMazeSingleCandidateColor = DungeonPuzzleSolversModule.tpMazeSingleCandidateColor
        tpMazeMultipleCandidateColor = DungeonPuzzleSolversModule.tpMazeMultipleCandidateColor
        tpMazeVisitedColor = DungeonPuzzleSolversModule.tpMazeVisitedColor
        tpMazeTracer = DungeonPuzzleSolversModule.tpMazeTracer
        tpMazeTracerColor = DungeonPuzzleSolversModule.tpMazeTracerColor
        scoreAlertsEnabled = DungeonScorerModule.alerts
        scoreThresholdOne = DungeonScorerModule.firstThreshold
        scoreThresholdTwo = DungeonScorerModule.secondThreshold
        scoreAlertText = DungeonScorerModule.alertText
        scoreAlertDurationTicks = DungeonScorerModule.durationTicks
        scoreAlertSound = DungeonScorerModule.playSound
        scoreAlertSoundId = DungeonScorerModule.soundId
        scorePaulBonus = DungeonScorerModule.paulBonus
        showSplits = DungeonRunSplitsModule.showSplits
        showCurrentSplit = DungeonRunSplitsModule.showCurrentSplit
        showTickTime = DungeonRunSplitsModule.showTickTime
        showRealTime = DungeonRunSplitsModule.showRealTime
        showPbPace = DungeonRunSplitsModule.showPbPace
        showCountValue = DungeonRunSplitsModule.showCountValue
        countSource = DungeonRunSplitsModule.countSource
        localRunSummary = DungeonRunSplitsModule.localRunSummary
        livid = DungeonLividSolverModule.highlight
        showBonzo = DungeonInvincibilityTimerModule.bonzo
        showSpirit = DungeonInvincibilityTimerModule.spirit
        showPhoenix = DungeonInvincibilityTimerModule.phoenix
        creeperBeamHitDing = DungeonPuzzleSolversModule.creeperBeamHitSound
        terminalBreakdownEnabled = DungeonTerminalStatsModule.breakdown
        terminalTitles = DungeonTerminalTitlesModule.replaceTitles
        terminalSelfOnly = DungeonTerminalStatsModule.selfOnly
        leapOnMouseRelease = SpiritLeapMenuModule.onMouseRelease
        leapAnnouncement = SpiritLeapMenuModule.announceLeaps
        leapSound = SpiritLeapMenuModule.playSound
        incomingLeapTitle = SpiritLeapMenuModule.incomingTitle
        incomingLeapTitleDurationTicks = SpiritLeapMenuModule.incomingTitleDurationTicks
        leapSoundId = SpiritLeapMenuModule.soundId
        leapClassColors = SpiritLeapMenuModule.classColors
        leapSort = SpiritLeapMenuModule.sort
        leapKeyMode = SpiritLeapMenuModule.keyMode
        terminalTitleTemplate = DungeonTerminalTitlesModule.titleTemplate
        customLeapOrder = SpiritLeapMenuModule.customOrder
        topLeftKey = SpiritLeapMenuModule.topLeftKey
        topRightKey = SpiritLeapMenuModule.topRightKey
        bottomLeftKey = SpiritLeapMenuModule.bottomLeftKey
        bottomRightKey = SpiritLeapMenuModule.bottomRightKey
        archerKey = SpiritLeapMenuModule.archerKey
        berserkKey = SpiritLeapMenuModule.berserkKey
        healerKey = SpiritLeapMenuModule.healerKey
        mageKey = SpiritLeapMenuModule.mageKey
        tankKey = SpiritLeapMenuModule.tankKey
    }

    override fun onDisable() {
        reset()
    }

    /** Called by the client tick hook. All Minecraft reads stay on the client thread. */
    fun tick(clientTick: Long, nowNanos: Long, inBoss: Boolean) {
        try {
            tickInternal(clientTick, nowNanos, inBoss)
        } finally {
            DungeonScorerModule.INSTANCE.tick(clientTick)
            recordPuzzleDiagnostics(clientTick)
            publishHudSnapshots()
        }
    }

    private fun recordPuzzleDiagnostics(clientTick: Long) {
        val module = DungeonPuzzleSolversModule.INSTANCE
        val enabled = module.isEnabled()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, module.name(), enabled)
        if (!enabled) {
            lastPuzzleDiagnosticTick = Long.MIN_VALUE
            lastPuzzleDiagnosticStatus = null
            setPuzzleScanState(Minecraft.getInstance(), "inactive", "Puzzle Solvers module is disabled.")
            return
        }
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return
        val floor = DungeonContextTracker.currentFloor()
        val room = puzzleObserver.currentPuzzleRoomDisplayName(client)
        val selected = if (puzzleLines.isEmpty()) "none" else puzzleLines.first()
        val guidanceLineCount = maxOf(0, puzzleLines.size - 1)
        val guidance = diagnosticGuidance()
        val timer = puzzleObserver.quizTimerSnapshot()
        val timerBucket = if (timer.remainingTicks() <= 0) 0 else (timer.remainingTicks() + 99) / 100
        val status = "enabled=true dungeon=${DungeonContextTracker.inDungeon()} " +
            "clear=${DungeonContextTracker.isClearPhase()} preBoss=${DungeonContextTracker.isMobEspPhase()} " +
            "floor=${floor?.displayName() ?: "unknown"} " +
            "room=${diagnosticToken(room, 64)} " +
            "enabledSolvers=${module.enabledSolverIds().stream().sorted().toList().joinToString(",")} " +
            "selected=${diagnosticToken(selected, 32)} " +
            "scanStatus=${diagnosticToken(lastPuzzleScanStatus, 16)} " +
            "scanComplete=${diagnosticScanCompleteness(lastPuzzleScanStatus)} " +
            "scanReason=${diagnosticToken(lastPuzzleScanReason, 120)} " +
            "guidanceLines=$guidanceLineCount guidance=$guidance " +
            "guidanceOmitted=${maxOf(0, guidanceLineCount - 3)} " +
            "quizTimer=${if (timer.stage() > 0 && timer.remainingTicks() > 0) "running" else "idle"} " +
            "quizStage=${timer.stage()} quizSecondsBucket=$timerBucket " +
            "quizState=${puzzleObserver.quizDiagnosticSummary()} " +
            "waterBoardState=${puzzleObserver.waterBoardDiagnosticSummary()} " +
            "ticTacToeState=${puzzleObserver.ticTacToeDiagnosticSummary()} " +
            "boulderState=${puzzleObserver.boulderDiagnosticSummary()} " +
            "icePathState=${puzzleObserver.icePathDiagnosticSummary()} " +
            "tpMazeState=${puzzleObserver.tpMazeDiagnosticSummary(client)} " +
            "render=${diagnosticToken(lastPuzzleDiagnosticRender, 160)} " +
            "waterHud=${diagnosticToken(lastWaterBoardHudRender, 64)} " +
            "event=${diagnosticToken(lastPuzzleDiagnosticEvent, 120)}"
        val changed = status != lastPuzzleDiagnosticStatus
        val elapsed = if (lastPuzzleDiagnosticTick == Long.MIN_VALUE) Long.MAX_VALUE
            else maxOf(0L, clientTick - lastPuzzleDiagnosticTick)
        if (changed && elapsed < 20) {
            coalescedPuzzleDiagnosticChanges++
            return
        }
        if (changed || elapsed >= 100) {
            GeilerAddonsLog.write(
                Category.F7,
                module.name(),
                clientTick,
                "STATUS $status coalescedChanges=$coalescedPuzzleDiagnosticChanges",
            )
            lastPuzzleDiagnosticStatus = status
            lastPuzzleDiagnosticTick = clientTick
            coalescedPuzzleDiagnosticChanges = 0
        }
    }

    private fun diagnosticGuidance(): String {
        if (puzzleLines.size <= 1) return "none"
        return puzzleLines.stream().skip(1).limit(3)
            .map { line -> diagnosticToken(line, 96) }
            .collect(java.util.stream.Collectors.joining(" / "))
    }

    private fun setPuzzleScanState(client: Minecraft?, status: String, reason: String) {
        if (client == null || !client.isSameThread) return
        lastPuzzleScanStatus = status
        lastPuzzleScanReason = reason
    }

    private fun notePuzzleDiagnosticEvent(event: String) {
        lastPuzzleDiagnosticEvent = diagnosticToken(event, 120)
    }

    private fun tickInternal(clientTick: Long, nowNanos: Long, inBoss: Boolean) {
        lastClientTick = maxOf(0L, clientTick)
        serverTickCounter.onClientTick()
        val inDungeon = DungeonContextTracker.inDungeon()
        val floor = DungeonContextTracker.currentFloor()
        val client = Minecraft.getInstance()
        splitEntryBuffer.discardIfStale(inDungeon, floor, client.level, client.connection, nowNanos)
        if (runSplitsEnabled()) {
            if (floor != null) splitTracker.tickContext(floor)
            else if (!inDungeon) splitTracker.tickContext(null)
        } else {
            splitEntryBuffer.clear()
            splitTracker.reset()
        }
        if (invincibilityEnabled()) maskTimers.tick(inDungeon)
        else {
            maskTimers.tick(false)
            pendingProcAlert = null
        }
        if (lividEnabled()) lividTracker.tick(inDungeon && floor != null && floor.number() == 5)
        else lividTracker.tick(false)
        if (!terminalStatsEnabled() && !terminalTitlesEnabled()) terminals.reset()
        if (!inDungeon) {
            if (wasInDungeon) reset()
            wasInDungeon = false
            mapRunInfo = null
            setPuzzleScanState(client, "inactive", "Dungeon context is inactive.")
            clearPuzzleGuidance()
            return
        }
        wasInDungeon = true
        if (floor != null) {
            val nextScoreRunKey = System.identityHashCode(client.level).toString() + "|" + floor.displayName() + "|" +
                DungeonMapRoomDetector.current().mapId.toString()
            if (scoreRunKey != nextScoreRunKey) {
                scoreRunKey = nextScoreRunKey
                if (scoreRunEntryObserved && scoreRunEntryKey == null) {
                    // The entry chat already reset run state; bind that observation to the first map identity.
                    scoreRunEntryKey = nextScoreRunKey
                } else {
                    scoreTracker.reset()
                    scoreBloodDone = null
                    scoreBonusSignals.resetUnknown()
                    scoreRunEntryObserved = false
                    scoreRunEntryKey = null
                }
            }
        }
        if (inBoss) scoreBloodDone = true
        val mapReading = DungeonMapRoomDetector.current()
        if (!inBoss && scoreBloodDone == null && mapReading != null && mapReading.geometry != null &&
            mapReading.geometry.roomType() == DungeonMapGeometry.RoomType.BLOOD) scoreBloodDone = false
        if (runSplitsEnabled()) {
            val pendingEntry = splitEntryBuffer.consumeIfReady(
                true,
                floor,
                client.level,
                client.connection,
                nowNanos,
            )
            if (pendingEntry != null) {
                val priorState = splitStartPriorState()
                if (splitTracker.observe(
                        pendingEntry.floor, "ENTRY", pendingEntry.capturedAtNanos, pendingEntry.clientTick,
                        pendingEntry.serverPingPackets, pendingEntry.serverTickCount,
                    )
                ) {
                    DungeonRunSplitsModule.INSTANCE.recordEntryStart(
                        clientTick, pendingEntry.floor, "buffered", priorState,
                    )
                }
                DungeonHelperModule.INSTANCE.observePhaseOutput(
                    pendingEntry.floor, DungeonGuideSegments.Event("ENTRY", "Run countdown chat"),
                )
            }
        }
        if (puzzleRuntimeActive()) updatePuzzleGuidance(clientTick, nowNanos)
        else {
            setPuzzleScanState(client, "inactive", "Puzzle runtime gate is inactive.")
            clearPuzzleGuidance()
        }
        if (!scorerEnabled() || !scoreAlertsEnabled.value()) DungeonScorerModule.INSTANCE.clearAlert()
        val mapInfoWanted = DungeonRoomOverlayModule.INSTANCE.mapInfoWanted()
        if (!scorerEnabled() && !mapInfoWanted) {
            mapRunInfo = null
            return
        }
        scoreBonusSignals.observePaul(
            when (scorePaulBonus.value()) {
                "YES" -> true
                "NO" -> false
                else -> null
            },
        )
        val teamSignals = scoreBonusSignals.teamSnapshot()
        val tabStats = DungeonOdinScoreCalculator.withTeamSignals(
            DungeonOdinScoreCalculator.withBonusSignals(
                DungeonOdinScoreCalculator.readTabLines(scoreTabLines(client)), scoreBonusSignals.snapshot(),
            ),
            teamSignals,
        )
        val scoreFloor = floor ?: teamSignals.floor
        val score = DungeonOdinScoreCalculator.calculate(scoreFloor, tabStats, scoreBloodDone, inBoss)
        mapRunInfo = DungeonMapInfoLogic.RunSnapshot(scoreFloor, tabStats, score, inBoss)
        if (!scorerEnabled()) return
        val crossed = scoreTracker.update(
            score, scoreThresholdOne.intValue(), scoreThresholdTwo.intValue(), scoreAlertsEnabled.value(),
        )
        if (!scoreAlertsEnabled.value()) return
        for (threshold in crossed) {
            val template = scoreAlertText.value().trim()
            val notice = (if (template.isEmpty()) "Score {score}" else template)
                .replace("{score}", threshold.toString())
            DungeonScorerModule.INSTANCE.showAlert(notice, clientTick + scoreAlertDurationTicks.intValue())
            val configuredSoundId = scoreAlertSoundId.value()
            val soundResult = if (scoreAlertSound.value()) {
                playLocalSound(configuredSoundId, 1.0f)
            } else {
                "disabled"
            }
            DungeonScorerModule.INSTANCE.recordSoundOutcome(clientTick, soundResult, configuredSoundId)
        }
    }

    /** The latest score, tab and floor data for the Dungeon Map info panel; null outside a dungeon or when unwanted. */
    fun mapRunInfo(): DungeonMapInfoLogic.RunSnapshot? = mapRunInfo

    /** Captures Odin's authoritative team-prefix floor and clear-percent updates on the client thread. */
    fun onDungeonTeamUpdate(teamText: String?) {
        scoreBonusSignals.observeTeamUpdate(teamText)
    }

    /** Called for incoming chat after Quiz tracking has identified an accepted choice. */
    fun styleQuizChatOption(component: Component?): Component? {
        val module = DungeonPuzzleSolversModule.INSTANCE
        if (component == null || !module.isEnabled() || !DungeonPuzzleSolversModule.quiz.value() ||
            !DungeonContextTracker.inDungeon()
        ) return component
        return puzzleObserver.styleQuizChatOption(component)
    }

    /** Called for received dungeon chat. Returns a queued party line only through takePartyAnnouncement(). */
    fun onChat(rawMessage: String?, nowNanos: Long, clientTick: Long) {
        onChat(rawMessage, nowNanos, clientTick, null)
    }

    /** Optional cooldown override comes from client-observed Bonzo mask item Lore. */
    fun onChat(rawMessage: String?, nowNanos: Long, clientTick: Long, bonzoCooldownSeconds: Int?) {
        if (rawMessage == null) return
        val plain = ChatText.plain(rawMessage).trim()
        val puzzleClient = Minecraft.getInstance()
        if (puzzleRuntimeActive() && puzzleClient.isSameThread) {
            val puzzleRoom = puzzleObserver.currentPuzzleRoomDisplayName(puzzleClient)
            val currentRoomIsPuzzle = puzzleRoom != null
            val puzzlePhase = DungeonContextTracker.isMobEspPhase()
            if (DungeonPuzzleSolversModule.puzzleTimers.value() && puzzlePhase && "Quiz" == puzzleRoom &&
                DungeonQuizRules.isOruoFinalAnswer(plain)
            ) {
                puzzleTimingTracker.onQuizFinalAnswer(puzzleRoom, true, nowNanos, puzzlePhase, true)
                    .ifPresent { showPuzzleCompletion(it) }
            }
            if (DungeonPuzzleSolversModule.draftPrompt.value()) {
                puzzleTimingTracker.onChatMessage(plain, true, puzzlePhase, currentRoomIsPuzzle)
                    .ifPresent { showDraftPrompt(it) }
            }
            if (puzzleFeatureActive()) {
                val correctWeirdosClue = puzzleObserver.onChat(
                    puzzleClient, plain, DungeonPuzzleSolversModule.threeWeirdos.value(),
                    DungeonPuzzleSolversModule.quiz.value(),
                )
                if (correctWeirdosClue) playWeirdosCorrectClueSound()
            }
        }
        val inDungeon = DungeonContextTracker.inDungeon()
        val floor = if (inDungeon) DungeonContextTracker.currentFloor() else null
        scoreBonusSignals.observePartyMessage(plain, floor)
        val splitPhase = DungeonGuideSegments.fromChat(floor ?: DungeonQueueFloorTracker.currentFloor(), plain)
        if (splitPhase != null) {
            when (splitPhase.segmentId) {
                "ENTRY" -> {
                    scoreBloodDone = false
                    scoreRunEntryObserved = true
                    scoreRunEntryKey = null
                    scoreBonusSignals.beginRun()
                    scoreTracker.reset()
                }
                "BLOOD_CLEAR", "BOSS_ENTRY", "COMPLETE", "BONZO_SIKE", "BONZO", "SCARF_MINIONS",
                "SCARF", "GUARDIANS", "PROFESSOR", "PROFESSOR_TRANSFORMED", "TRANSFORMED_PROFESSOR",
                "THORN", "LIVID", "TERRACOTTAS", "SADAN_GIANTS", "SADAN", "MAXOR", "STORM",
                "TERMINALS", "GOLDOR", "NECRON", "WITHER_KING" -> scoreBloodDone = true
                else -> Unit
            }
        }
        if (runSplitsEnabled() && splitPhase != null && "ENTRY" == splitPhase.segmentId) {
            if (inDungeon && floor != null) {
                val priorState = splitStartPriorState()
                if (splitTracker.observeEvent(
                        floor, splitPhase, nowNanos, clientTick, serverPingPacketCount, serverTickCounter.count(),
                    )
                ) {
                    DungeonRunSplitsModule.INSTANCE.recordEntryStart(clientTick, floor, "direct", priorState)
                }
                DungeonHelperModule.INSTANCE.observePhaseOutput(floor, splitPhase)
            } else {
                val client = Minecraft.getInstance()
                splitEntryBuffer.capture(
                    DungeonQueueFloorTracker.currentFloor(), client.level, client.connection, nowNanos, clientTick,
                    serverPingPacketCount, serverTickCounter.count(),
                )
            }
            return
        }
        if (!inDungeon || floor == null) return
        if (runSplitsEnabled()) {
            splitTracker.observeChat(floor, plain, nowNanos, clientTick, serverPingPacketCount, serverTickCounter.count())
            var totalUpdate: DungeonSplitPersonalBestBook.Update? = null
            for (closed in splitTracker.drainNewlyFinished()) {
                val update = DungeonSplitPersonalBestStore.record(floor, closed, System.currentTimeMillis())
                if ("COMPLETE" == closed.id) totalUpdate = update
            }
            if (totalUpdate != null && localRunSummary.value()) showLocalRunSummary(floor, totalUpdate)
        }
        if (lividEnabled() && livid.value()) {
            lividTracker.observeChat(plain, floor.number() == 5)
            if (DungeonLividReleaseRules.isRunEnd(plain)) releaseLivid("run-end")
        }
        if (invincibilityEnabled()) {
            val proc = maskTimers.observeChat(plain, true, bonzoCooldownSeconds)
            if (proc != null) pendingProcAlert = proc
        }
        if (spiritLeapEnabled()) {
            val arrival = SpiritLeapArrivalRules.parse(plain).orElse(null)
            if (arrival != null && leapArrivalCoalescer.accept(arrival, nowNanos)) {
                if (leapAnnouncement.value()) pendingPartyAnnouncement = SpiritLeapSelection.announcement(arrival.playerName)
                if (leapSound.value()) pendingLeapSound = true
            }
            if (PartyListBackend.hasFreshSnapshot()) {
                val party = PartyListBackend.snapshot()
                val client = Minecraft.getInstance()
                val localName = client.player?.gameProfile?.name
                if (party.inParty() && localName != null) {
                    val partyNames = party.members().mapNotNull { member -> member.name() }
                    val candidate = SpiritLeapIncomingRules.parse(plain, localName, partyNames).orElse(null)
                    if (candidate != null) presentIncomingLeap(candidate, party.members(), nowNanos, clientTick)
                }
            }
        }
    }

    /** Returns a replacement title for the received title hook, or null when no title should change. */
    fun onTerminalTitle(rawTitle: String?, localPlayerName: String?): String? {
        var replacement: String? = null
        if (DungeonContextTracker.inDungeon() && rawTitle != null) {
            val plain = ChatText.plain(rawTitle).trim()
            val completion = if ((terminalStatsEnabled() && terminalBreakdownEnabled.value()) || terminalTitlesEnabled()) {
                terminals.observeTitle(plain, localPlayerName, terminalStatsEnabled() && terminalSelfOnly.value())
            } else null
            if (terminalStatsEnabled() && terminalBreakdownEnabled.value() && completion != null) {
                DungeonTerminalStatsDiagnostics.recordCompletion(
                    lastClientTick, completion.action, completion.type, completion.current, completion.total,
                )
            }
            if (terminalTitlesEnabled() && terminalTitles.value()) {
                replacement = terminals.replacement(terminalTitleTemplate.value(), completion)
            }
        }
        if (terminalTitlesEnabled()) {
            val client = Minecraft.getInstance()
            val tick = client.level?.gameTime ?: 0L
            DungeonTerminalTitlesModule.recordTitleDecision(tick, rawTitle, replacement)
        }
        return replacement
    }

    /** Replaces only the local hit sound at a known endpoint while Creeper Beams guidance is selected. */
    fun replaceCreeperBeamHitSound(original: SoundInstance?): SoundInstance? {
        if (original == null) return null
        val enabled = solverEnabled("CREEPER_BEAMS") && creeperBeamHitDing.value()
        if (!enabled || !DungeonSoundRules.isCreeperBeamHit(original.identifier.toString())) return original
        val client = Minecraft.getInstance()
        val activePuzzle = solverEnabled("CREEPER_BEAMS") && client.isSameThread &&
            client.player != null && client.level != null && puzzleLines.isNotEmpty() &&
            "Creeper Beams" == puzzleLines.first()
        val target = BlockPos.containing(original.x, original.y, original.z)
        val matchedEndpoint = activePuzzle && puzzleObserver.isConfirmedCreeperBeamsEndpoint(client, target)
        if (!DungeonSoundRules.shouldReplaceCreeperBeamHit(
                original.identifier.toString(), enabled, activePuzzle, matchedEndpoint,
            )
        ) return original
        val id = Identifier.tryParse("block.note_block.pling") ?: return original
        val event = BuiltInRegistries.SOUND_EVENT.getValue(id) ?: return original
        notePuzzleDiagnosticEvent("creeper-beam-hit-sound=replaced:block.note_block.pling")
        return SimpleSoundInstance.forUI(event, 1.35f, 1.0f)
    }

    fun playLeapSound() {
        if (spiritLeapEnabled() && leapSound.value()) playLocalSound(leapSoundId.value(), 1.55f)
    }

    /** One-shot outputs are consumed by the event adapter so it cannot announce the same event twice. */
    fun takePartyAnnouncement(): String? {
        val line = pendingPartyAnnouncement
        pendingPartyAnnouncement = null
        return line
    }

    fun takeProcAlert(): DungeonMaskProcTimers.Proc? {
        val proc = pendingProcAlert
        pendingProcAlert = null
        return proc
    }

    fun takeLeapSound(): Boolean {
        val play = pendingLeapSound
        pendingLeapSound = false
        return play
    }

    fun onBloodWorldChanged() {
        maskTimers.reset()
        pendingProcAlert = null
        DungeonInvincibilityTimerModule.INSTANCE.clearSnapshots()
    }

    fun observeLividMarkerEvidence(
        blockState: String?, dyeName: String?, source: String?, available: Boolean,
    ): Boolean {
        if (!lividEnabled() || !livid.value() || !lividFloorFiveBoss()) return false
        val evidence = if (available) diagnosticToken(blockState, 48) else "unloaded"
        val evidenceSource = diagnosticToken(source, 24)
        lastLividMarkerEvidence = evidence
        lastLividMarkerSource = evidenceSource
        if (!available) return false
        if (dyeName.isNullOrBlank()) {
            lividTracker.clearMarker()
            return true
        }
        return lividTracker.observeMarkerWool(dyeName, true)
    }

    fun recordLividCandidateScan(
        scannedEntities: Int,
        namedCandidates: Int,
        markerMatches: Int,
        candidateSamples: List<String>?,
    ) {
        val sample = candidateSamples?.stream()?.limit(6)
            ?.map { name -> diagnosticToken(name, 48) }
            ?.collect(java.util.stream.Collectors.joining(",")) ?: "none"
        lastLividCandidateDiagnostic = "scanned=${maxOf(0, scannedEntities)} named=${maxOf(0, namedCandidates)} " +
            "markerMatches=${maxOf(0, markerMatches)} sample=${if (sample.isBlank()) "none" else sample}"
    }

    fun skipLividCandidateScan(reason: String?) {
        lastLividCandidateDiagnostic = "skip=${diagnosticToken(reason, 48)}"
    }

    fun recordLividRenderDiagnostic(outcome: String?) {
        lastLividRenderDiagnostic = diagnosticToken(outcome, 128)
    }

    fun lividWorldTimerLabel(): DungeonLividOverlayLabel.Snapshot? {
        return DungeonLividOverlayLabel.create(
            DungeonLividSolverModule.showTimer.value(),
            lividFloorFiveBoss(),
            lividTracker.invulnerabilityTicks(),
        ).takeIf { lividEnabled() && livid.value() }
    }

    fun isLividHighlightEnabled(): Boolean = lividEnabled() && livid.value()

    /** Releases the Livid target once its matched entity has been missing or dead for a few ticks. */
    fun observeLividTargetPresence(entities: Map<Int, net.minecraft.world.entity.LivingEntity>) {
        if (!lividEnabled() || !livid.value()) {
            lividMissingTicks = 0
            return
        }
        val id = lividTracker.matchedEntityId()
        if (id < 0) {
            lividMissingTicks = 0
            return
        }
        val entity = entities[id]
        val alive = entity != null && !entity.isRemoved && entity.isAlive && entity.health > 0.0f
        lividMissingTicks = DungeonLividReleaseRules.nextMissingTicks(lividMissingTicks, alive)
        if (DungeonLividReleaseRules.shouldRelease(lividMissingTicks)) {
            releaseLivid(if (entity == null) "entity-missing" else "entity-dead")
        }
    }

    private fun releaseLivid(reason: String) {
        if (lividTracker.released() && lividTracker.matchedEntityId() < 0) return
        lividTracker.releaseTarget()
        lividMissingTicks = 0
        publishedLividTarget = null
        lastLividDiagnosticEvent = "release=$reason"
        GeilerAddonsLog.write(
            Category.F7, DungeonLividSolverModule.INSTANCE.name(), lastClientTick, "RELEASE reason=$reason",
        )
    }

    fun observeLividPlayerEntity(uuid: UUID?, entityId: Int, formattedName: String?): Boolean =
        lividEnabled() && livid.value() &&
            lividTracker.observePlayerEntity(uuid, entityId, formattedName, lividFloorFiveBoss())

    fun lividSelectedTarget(blinded: Boolean): DungeonLividTracker.TargetSnapshot? =
        if (lividEnabled() && livid.value()) lividTracker.selectedTarget(lividFloorFiveBoss(), blinded) else null

    /** The immutable target captured for this tick's HUD and world-render snapshot. */
    fun publishedLividTarget(blinded: Boolean): DungeonLividTracker.TargetSnapshot? =
        if (blinded) null else publishedLividTarget

    fun observeWatcherName(observedName: String?): Boolean {
        val floor = DungeonContextTracker.currentFloor()
        return floor != null && splitTracker.observeWatcherName(
            floor, observedName, System.nanoTime(), lastClientTick, serverPingPacketCount, serverTickCounter.count(),
        )
    }

    /** Supply four currently observed teammates from party/class/death signals. */
    fun updateLeapRoster(members: List<SpiritLeapRoster.Member>?, localPlayerName: String?) {
        if (!spiritLeapEnabled()) {
            clearLeapRoster()
            return
        }
        leapRoster.update(members, localPlayerName)
        if (!leapRoster.hasObservedTeammates()) leapCardsCache.clear()
        refreshLeapSkinTexturesIfNeeded()
    }

    fun clearLeapRoster() {
        leapRoster.clear()
        leapCardsCache.clear()
    }

    fun leapCards(): List<SpiritLeapRoster.Card> {
        val sort = try {
            SpiritLeapRoster.Sort.valueOf(leapSort.value())
        } catch (_: IllegalArgumentException) {
            SpiritLeapRoster.Sort.ODIN
        }
        return leapCardsCache.cards(leapRoster, sort, customLeapOrder.value())
    }

    /** Uses the live, validated target heads as the identity source for every open Leap surface. */
    fun leapCards(screen: AbstractContainerScreen<*>?): List<SpiritLeapRoster.Card> {
        if (screen == null || !SpiritLeapSelection.isLeapMenu(screen.title.string)) return emptyList()
        val client = Minecraft.getInstance()
        val player = client.player ?: return emptyList()
        val localName = player.gameProfile.name
        val members = SpiritLeapRoster.resolveMenuHeads(spiritLeapMenuHeads(screen), leapCardMetadata(), localName)
        if (members.isEmpty()) return emptyList()
        val menuRoster = SpiritLeapRoster()
        menuRoster.update(members, localName)
        val sort = try {
            SpiritLeapRoster.Sort.valueOf(leapSort.value())
        } catch (_: IllegalArgumentException) {
            SpiritLeapRoster.Sort.ODIN
        }
        return leapCardsCache.cards(menuRoster, sort, customLeapOrder.value())
    }

    private fun leapCardMetadata(): List<SpiritLeapRoster.Member> {
        val byName = HashMap<String, SpiritLeapRoster.Member>()
        for (tabPlayer in OdinDungeonPlayerRoster.snapshot()) {
            if (tabPlayer == null || tabPlayer.name() == null || tabPlayer.name().isBlank()) continue
            byName.putIfAbsent(
                tabPlayer.name().lowercase(Locale.ROOT),
                SpiritLeapRoster.Member(tabPlayer.name(), tabPlayer.dungeonClass(), 0, false, tabPlayer.dead()),
            )
        }
        for (partyMember in leapRoster.observed()) {
            if (partyMember == null || partyMember.empty()) continue
            val key = partyMember.name().lowercase(Locale.ROOT)
            val tabMember = byName[key]
            val className = if (tabMember != null && !tabMember.dungeonClass().isBlank()) {
                tabMember.dungeonClass()
            } else partyMember.dungeonClass()
            byName[key] = SpiritLeapRoster.Member(
                partyMember.name(), className, partyMember.healthPercent(), partyMember.online(),
                partyMember.dead() || tabMember != null && tabMember.dead(),
            )
        }
        val client = Minecraft.getInstance()
        val level = client.level
        if (level != null) {
            for (player in level.players()) {
                val name = player.name.string
                val key = name.lowercase(Locale.ROOT)
                val known = byName[key]
                val health = if (player.maxHealth <= 0.0f) 0 else Math.round(player.health / player.maxHealth * 100.0f)
                byName[key] = SpiritLeapRoster.Member(
                    name,
                    known?.dungeonClass() ?: "",
                    health,
                    true,
                    isLoadedLeapMemberDead(player.isAlive, player.health),
                )
            }
        }
        return java.util.List.copyOf(byName.values)
    }

    /**
     * Replaces the native container surface for the validated Spirit Leap menu, removing its player
     * inventory and chest slots before drawing the four card quadrants.
     */
    fun renderSpiritLeapScreen(
        graphics: GuiGraphicsExtractor,
        font: Font,
        screenWidth: Int,
        screenHeight: Int,
        screen: AbstractContainerScreen<*>?,
        mouseX: Int,
        mouseY: Int,
    ): Boolean {
        if (!shouldRenderSpiritLeapScreen(screen)) return false
        val cards = leapCards(screen)
        if (cards.isEmpty()) return false
        graphics.nextStratum()
        SpiritLeapPovRenderer.INSTANCE.draw(graphics, screenWidth, screenHeight)
        renderSpiritLeapCards(graphics, font, screenWidth, screenHeight, mouseX, mouseY, cards)
        if (SpiritLeapMenuModule.showMap.value()) {
            val leapMapOn = leapMapShown()
            val drawn = leapMapOn && DungeonRoomOverlayModule.INSTANCE.drawLeapMap(
                graphics, font, screenWidth, leapCardsTop(screenWidth, screenHeight),
                SpiritLeapMenuModule.mapScale.value(),
            )
            val reason = if (!leapMapOn) "hud-map-visible" else if (drawn) "drawn" else "no-live-map"
            if (drawn != leapMapDrawnLast || reason != leapMapReasonLast) {
                leapMapDrawnLast = drawn
                leapMapReasonLast = reason
                SpiritLeapMenuModule.recordEvent(lastClientTick, "LEAP_MAP drawn=$drawn reason=$reason scale=${SpiritLeapMenuModule.mapScale.display()}")
            }
        }
        return true
    }

    /** Leap copy is on and the normal HUD dungeon map is not already visible (avoids two maps). */
    private fun leapMapShown(): Boolean = SpiritLeapMapLogic.showsLeapMap(
        SpiritLeapMenuModule.showMap.value(), DungeonRoomOverlayModule.INSTANCE.visible(),
    )

    private var leapMapDrawnLast: Boolean? = null
    private var leapMapReasonLast: String? = null

    /** Top edge of the card group, including hover growth, so the map sits above it. */
    private fun leapCardsTop(screenWidth: Int, screenHeight: Int): Int {
        val layout = SpiritLeapLayout.calculate(screenWidth, screenHeight, SpiritLeapMenuModule.cardDistance.value())
        return (layout.topLeft()?.y() ?: 0) - layout.maxHoverGrowth()
    }

    fun shouldRenderSpiritLeapScreen(screen: AbstractContainerScreen<*>?): Boolean {
        if (screen == null || !spiritLeapEnabled() || !DungeonContextTracker.inDungeon() ||
            !SpiritLeapSelection.isLeapMenu(screen.title.string)
        ) return false
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.gui.screen() != screen || client.player == null) return false
        val player = client.player ?: return false
        val localName = player.gameProfile.name
        val hasValidMenuHead = SpiritLeapRoster.resolveMenuHeads(spiritLeapMenuHeads(screen), emptyList(), localName)
            .isNotEmpty()
        return SpiritLeapMenuModule.shouldRenderLeapCards(true, true, hasValidMenuHead, true)
    }

    private fun renderSpiritLeapCards(
        graphics: GuiGraphicsExtractor,
        font: Font,
        screenWidth: Int,
        screenHeight: Int,
        mouseX: Int,
        mouseY: Int,
        cards: List<SpiritLeapRoster.Card>,
    ) {
        val layout = SpiritLeapLayout.calculate(screenWidth, screenHeight, SpiritLeapMenuModule.cardDistance.value())
        val hoveredQuadrant = SpiritLeapSelection.quadrantAt(mouseX.toDouble(), mouseY.toDouble(), screenWidth, screenHeight)
        leapCardHover.advance(hoveredQuadrant, System.nanoTime())
        val skinTextures = cachedLeapSkinTextures
        val radius: Int = SpiritLeapMenuModule.extraRoundness.intValue()
        for (card in cards) {
            val member = card.member()
            if (member.empty()) continue
            val bounds = layout.quadrant(card.quadrant()) ?: continue
            val x = bounds.x()
            val y = bounds.y()
            val classColor = SpiritLeapMenuModule.classColorArgb(member.dungeonClass())
            val background: Int = if (leapClassColors.value()) {
                (classColor and 0x00FFFFFF) or 0xB0000000.toInt()
            } else 0xC025252D.toInt()
            val hoverGrowth: Int = Math.round(leapCardHover.amount(card.quadrant()) * layout.maxHoverGrowth())
            GuiTheme.roundedRect(
                graphics, x - hoverGrowth, y - hoverGrowth, bounds.width() + hoverGrowth * 2,
                bounds.height() + hoverGrowth * 2, radius, background,
            )
            val skin = skinTextures[member.name().lowercase(Locale.ROOT)]
            val faceX = x + 10
            val faceSize = minOf(40, maxOf(1, bounds.height() - 12))
            val faceY = y + (bounds.height() - faceSize) / 2
            if (skin != null) {
                val pose = graphics.pose()
                pose.pushMatrix()
                pose.translate(faceX.toFloat(), faceY.toFloat())
                pose.scale(faceSize / 8.0f, faceSize / 8.0f)
                graphics.blit(RenderPipelines.GUI_TEXTURED, skin, 0, 0, 8f, 8f, 8, 8, 64, 64)
                graphics.blit(RenderPipelines.GUI_TEXTURED, skin, 0, 0, 40f, 8f, 8, 8, 64, 64)
                pose.popMatrix()
            } else {
                graphics.fill(faceX, faceY, faceX + faceSize, faceY + faceSize, classColor)
                val visibleName = leapCardName(
                    member.name(), if (leapClassColors.value()) 0xFFFFFFFF.toInt() else classColor,
                ).string
                val initial = if (visibleName.isEmpty()) "?" else {
                    visibleName.substring(0, visibleName.offsetByCodePoints(0, 1)).uppercase(Locale.ROOT)
                }
                val initialText = fitLeapCardText(font, Component.literal(initial), faceSize)
                graphics.text(
                    font, initialText, faceX + (faceSize - font.width(initialText)) / 2,
                    faceY + (faceSize - font.lineHeight) / 2, 0xFFFFFFFF.toInt(),
                )
            }
            val textWidth = maxOf(1, bounds.width() - LEAP_CARD_TEXT_X - LEAP_CARD_RIGHT_PADDING)
            val nameLine = fitLeapCardText(
                font, leapCardName(member.name(), if (leapClassColors.value()) 0xFFFFFFFF.toInt() else classColor),
                textWidth,
            )
            val classLine = fitLeapCardText(
                font, Component.literal(SpiritLeapLayout.displayClassLabel(member.dungeonClass(), member.dead())),
                textWidth,
            )
            val textTop = y + maxOf(2, (bounds.height() - font.lineHeight * 2 - 2) / 2)
            graphics.text(font, nameLine, x + LEAP_CARD_TEXT_X, textTop, 0xFFFFFFFF.toInt())
            graphics.text(
                font, classLine, x + LEAP_CARD_TEXT_X, textTop + font.lineHeight + 2,
                if (member.dead()) 0xFFFF5555.toInt()
                else if (leapClassColors.value()) 0xFFFFFFFF.toInt() else 0xFFB8C5DB.toInt(),
            )
        }
    }

    private fun refreshLeapSkinTexturesIfNeeded() {
        val client = Minecraft.getInstance()
        val level = client.level
        if (level == null) {
            cachedLeapSkinTextures = emptyMap()
            cachedLeapSkinLevel = null
            cachedLeapSkinRefreshTick = lastClientTick
            return
        }
        val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
        if (!SpiritLeapSelection.isLeapMenu(screen.title.string)) return
        if (cachedLeapSkinLevel == level && lastClientTick - cachedLeapSkinRefreshTick < 20L) return
        val textures = HashMap<String, Identifier>()
        val localPlayer = client.player
        if (localPlayer?.connection != null) {
            for (card in leapCards(screen)) {
                val member = card.member()
                if (member == null || member.empty()) continue
                try {
                    val info = localPlayer.connection.getPlayerInfo(member.name())
                    val texture = info?.skin?.body()?.texturePath()
                    if (texture != null) textures.putIfAbsent(member.name().lowercase(Locale.ROOT), texture)
                } catch (_: RuntimeException) {
                    // PlayerInfo only supplies the client-cached skin; the card still works without it.
                }
            }
        }
        for (player in level.players()) {
            try {
                val texture = player.skin?.body()?.texturePath()
                if (texture != null) textures[player.name.string.lowercase(Locale.ROOT)] = texture
            } catch (_: RuntimeException) {
                // The card still shows its name and class while a remote skin asset is unavailable.
            }
        }
        cachedLeapSkinTextures = java.util.Map.copyOf(textures)
        cachedLeapSkinLevel = level
        cachedLeapSkinRefreshTick = lastClientTick
    }

    private fun presentIncomingLeap(
        incoming: SpiritLeapIncomingRules.Incoming,
        partyMembers: List<PartyMember>,
        nowNanos: Long,
        clientTick: Long,
    ) {
        if (!incomingLeapCoalescer.accept(incoming, nowNanos)) return
        val client = Minecraft.getInstance()
        var titleResult = "disabled"
        var title: SpiritLeapIncomingPresentation.Title? = null
        var titleColor = 0xFFFFFFFF.toInt()
        if (incomingLeapTitle.value() && client.gui != null) {
            title = SpiritLeapIncomingPresentation.title(
                incoming, partyMembers, OdinDungeonPlayerRoster.snapshot(), incomingLeapTitleDurationTicks.intValue(),
            ).orElse(null)
            if (title != null) {
                val titlePlayerName = requireNotNull(title.playerName)
                val titleClassName = requireNotNull(title.className)
                titleColor = if (leapClassColors.value()) {
                    0xFF000000.toInt() or (SpiritLeapMenuModule.classColorArgb(titleClassName) and 0x00FFFFFF)
                } else 0xFFFFFFFF.toInt()
                client.gui.hud.setTimes(0, title.durationTicks, 0)
                val renderedName = PlayerPresentationModule.replaceRenderedName(
                    titlePlayerName, Component.literal(titlePlayerName),
                ) ?: Component.literal(titlePlayerName)
                val titleText = Component.empty().withStyle(
                    Style.EMPTY.withColor(TextColor.fromRgb(titleColor and 0x00FFFFFF)),
                ).append(Component.literal("Leap arrival: "))
                    .append(renderedName).append(Component.literal(" · $titleClassName"))
                client.gui.hud.setTitle(titleText)
                titleResult = "shown"
            } else {
                titleResult = "party-member-unavailable"
            }
        } else if (incomingLeapTitle.value()) {
            titleResult = "gui-unavailable"
        }
        var titleEvent = "INCOMING_TITLE result=$titleResult classColors=${leapClassColors.value()}"
        if (title != null && "shown" == titleResult) {
            titleEvent += " class=${SpiritLeapMenuModule.boundedDiagnosticText(title.className, 32)}" +
                " color=#${String.format(Locale.ROOT, "%08X", titleColor)} ticks=${title.durationTicks}"
        }
        SpiritLeapMenuModule.recordEvent(clientTick, titleEvent)
        if (leapSound.value()) playLeapSound()
    }

    /**
     * Direct key action. Only a physical key press in a validated Spirit Leap chest may dispatch a
     * vanilla menu click; callers should invoke this from the client's raw key press hook.
     */
    fun handleSpiritLeapKey(key: String?, dispatcher: ExperimentSolverModule.SlotClickDispatcher?): Boolean {
        if (!spiritLeapEnabled() || !DungeonContextTracker.inDungeon() || key == null || dispatcher == null) return false
        val client = Minecraft.getInstance()
        val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
        if (!client.isSameThread || client.player == null) return false
        if (!shouldRenderSpiritLeapScreen(screen)) return false
        val corners = mapOf(
            SpiritLeapSelection.Quadrant.TOP_LEFT to topLeftKey.value(),
            SpiritLeapSelection.Quadrant.TOP_RIGHT to topRightKey.value(),
            SpiritLeapSelection.Quadrant.BOTTOM_LEFT to bottomLeftKey.value(),
            SpiritLeapSelection.Quadrant.BOTTOM_RIGHT to bottomRightKey.value(),
        )
        val classes = mapOf(
            "Archer" to archerKey.value(),
            "Berserk" to berserkKey.value(),
            "Healer" to healerKey.value(),
            "Mage" to mageKey.value(),
            "Tank" to tankKey.value(),
        )
        val mode = if ("CLASS" == leapKeyMode.value()) SpiritLeapSelection.KeyMode.CLASS
            else SpiritLeapSelection.KeyMode.CORNERS
        val target = SpiritLeapSelection.targetForKey(
            true, screen.title.string, key, mode, corners, classes, leapCards(screen), spiritLeapMenuHeads(screen),
        )
        val dispatched = dispatchSpiritLeapTarget(screen.menu, target, dispatcher)
        SpiritLeapMenuModule.recordEvent(
            lastClientTick,
            "LEAP_INPUT type=key mode=$mode mapped=${target.isPresent} " +
                "slot=${target.map { it.containerSlot }.orElse(-1)} dispatched=$dispatched",
        )
        return dispatched
    }

    /** Dispatches a safely mapped teammate card at the configured mouse phase. */
    fun handleSpiritLeapMouseClick(
        screen: AbstractContainerScreen<*>?,
        mouseX: Double,
        mouseY: Double,
        mouseButton: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean = handleSpiritLeapMouseEvent(
        screen, mouseX, mouseY, mouseButton, SpiritLeapSelection.MousePhase.PRESS, dispatcher,
    )

    /** Handles release-phase leap input when configured, while consuming occupied-card interactions safely. */
    fun handleSpiritLeapMouseRelease(
        screen: AbstractContainerScreen<*>?,
        mouseX: Double,
        mouseY: Double,
        mouseButton: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean = handleSpiritLeapMouseEvent(
        screen, mouseX, mouseY, mouseButton, SpiritLeapSelection.MousePhase.RELEASE, dispatcher,
    )

    private fun handleSpiritLeapMouseEvent(
        screen: AbstractContainerScreen<*>?,
        mouseX: Double,
        mouseY: Double,
        mouseButton: Int,
        phase: SpiritLeapSelection.MousePhase,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean {
        if (!spiritLeapEnabled() || !DungeonContextTracker.inDungeon() || screen == null || dispatcher == null)
            return false
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.gui.screen() != screen || client.player == null ||
            !shouldRenderSpiritLeapScreen(screen)
        ) return false
        val menuTitle = screen.title.string
        val quadrant = SpiritLeapSelection.quadrantAt(mouseX, mouseY, screen.width, screen.height)
        val cards = leapCards(screen)
        val timing = if (leapOnMouseRelease.value()) SpiritLeapSelection.MouseTiming.RELEASE
            else SpiritLeapSelection.MouseTiming.PRESS
        if (leapMapShown()) {
            val scale = SpiritLeapMenuModule.mapScale.value()
            val cardsTop = leapCardsTop(screen.width, screen.height)
            val bounds = DungeonRoomOverlayModule.INSTANCE.leapMapBounds(client.font, screen.width, cardsTop, scale)
            val mx = mouseX.toFloat()
            val my = mouseY.toFloat()
            if (bounds != null && SpiritLeapMapLogic.contains(bounds[0], bounds[1], bounds[2], bounds[3], mx, my)) {
                // Any button is consumed over the map; the configured phase picks the nearest teammate marker.
                var name: String? = null
                var target: Optional<SpiritLeapSelection.Target> = Optional.empty()
                var dispatched = false
                if (timing.name == phase.name) {
                    val markers = DungeonRoomOverlayModule.INSTANCE.leapMapMarkers(client.font, screen.width, cardsTop, scale)
                    name = SpiritLeapMapLogic.nearestName(markers, mx, my, Float.MAX_VALUE)
                    target = SpiritLeapSelection.targetForPlayerName(
                        true, menuTitle, name, cards, spiritLeapMenuHeads(screen),
                    )
                    dispatched = dispatchSpiritLeapTarget(screen.menu, target, dispatcher)
                }
                SpiritLeapMenuModule.recordEvent(
                    lastClientTick,
                    "LEAP_INPUT type=map button=$mouseButton phase=${phase.name.lowercase(Locale.ROOT)} " +
                        "timing=${timing.name.lowercase(Locale.ROOT)} nearest=${diagnosticToken(name, 16)} " +
                        "mapped=${target.isPresent} slot=${target.map { it.containerSlot }.orElse(-1)} dispatched=$dispatched",
                )
                return true
            }
        }
        val action = SpiritLeapSelection.mouseAction(
            mouseButton, true, menuTitle, quadrant, cards, spiritLeapMenuHeads(screen), timing, phase,
        )
        if (!action.consumed) return false
        val selectedTarget = action.target
        val dispatched = dispatchSpiritLeapTarget(screen.menu, selectedTarget, dispatcher)
        SpiritLeapMenuModule.recordEvent(
            lastClientTick,
            "LEAP_INPUT type=mouse quadrant=$quadrant phase=${phase.name.lowercase(Locale.ROOT)} " +
                "timing=${timing.name.lowercase(Locale.ROOT)} consumed=true mapped=${selectedTarget?.isPresent == true} " +
                "slot=${selectedTarget?.map { it.containerSlot }?.orElse(-1) ?: -1} dispatched=$dispatched",
        )
        return true
    }

    /** Puzzle adapters provide only observations already read on the client; this module draws guidance only. */
    fun observeBlazes(formattedNames: List<String>?, lowerBlazeRoom: Boolean) {
        if (!solverEnabled("BLAZE") || formattedNames == null) return
        val targets = ArrayList<DungeonPuzzleLogic.BlazeTarget>()
        for ((index, name) in formattedNames.withIndex()) {
            val health = DungeonPuzzleLogic.parseBlazeHealth(name)
            if (health != null) targets.add(DungeonPuzzleLogic.BlazeTarget((index + 1).toString(), health))
        }
        val ordered = DungeonPuzzleLogic.orderBlazes(targets, lowerBlazeRoom).stream()
            .map { target -> "Blaze ${target.stableId} · ${target.health} HP" }.toList()
        setPuzzleLines("Blaze", ordered)
    }

    fun observeWaterBoard(
        initialState: Int,
        valveEffects: List<Int>,
        stateBits: Int,
        exactTargetState: Int,
        reachesDrain: IntPredicate,
        optimized: Boolean,
    ) {
        if (!solverEnabled("WATER_BOARD")) return
        val route = if (optimized) {
            DungeonWaterBoardSolver.optimized(initialState, valveEffects, stateBits, reachesDrain)
        } else DungeonWaterBoardSolver.standard(initialState, exactTargetState, valveEffects, stateBits)
        setPuzzleLines("Water Board", route.stream().map { index -> "Click valve ${index + 1}" }.toList())
    }

    fun observeIceFill(
        walkable: List<DungeonPuzzleLogic.Cell>,
        start: DungeonPuzzleLogic.Cell,
        goal: DungeonPuzzleLogic.Cell,
        nodeBudget: Int,
    ) {
        if (!solverEnabled("ICE_FILL")) return
        val route = DungeonPuzzleLogic.solveIceFill(walkable, start, goal, nodeBudget)
        setPuzzleLines("Ice Fill", route.stream().map { cell -> "Step ${cell.x}, ${cell.y}" }.toList())
    }

    fun observeCreeperBeams(endpointIds: List<Int>, pairCost: DungeonPuzzleLogic.PairCost) {
        if (!solverEnabled("CREEPER_BEAMS")) return
        val lines = DungeonPuzzleLogic.pairEndpoints(endpointIds, pairCost).stream()
            .map { pair -> "Connect ${pair.firstEndpointId} → ${pair.secondEndpointId}" }.toList()
        setPuzzleLines("Creeper Beams", lines)
    }

    fun observeBoulder(
        walkable: Set<DungeonPuzzleLogic.Cell>,
        player: DungeonPuzzleLogic.Cell,
        boulder: DungeonPuzzleLogic.Cell,
        goal: DungeonPuzzleLogic.Cell,
    ) {
        if (!solverEnabled("BOULDER")) return
        val pushes = DungeonPuzzleLogic.solveBoulder(walkable, player, boulder, goal)
        setPuzzleLines(
            "Boulder", pushes.stream().map { push ->
                val afterPush = requireNotNull(push.boulderAfterPush)
                "Push to ${afterPush.x}, ${afterPush.y}"
            }
                .toList(),
        )
    }

    /** Allows a feature-local answer adapter to use exact, ambiguity-safe fact matching. */
    fun observeQuiz(question: String?, independentlyAuthoredAnswers: Map<String, String>?) {
        if (!solverEnabled("QUIZ") || question == null || independentlyAuthoredAnswers == null) return
        val answer = DungeonQuizRules.resolveExactAnswer(question, independentlyAuthoredAnswers)
        setPuzzleLines(
            "Quiz",
            if (answer == null) listOf(
                "Observed prompt: ${ChatText.plain(question).trim()}",
                "Answer unknown; no unique verified fact matches this question.",
            ) else listOf("Observed prompt: ${ChatText.plain(question).trim()}", "Answer: $answer"),
        )
    }

    private fun setPuzzleLines(name: String, guidance: List<String>?) {
        if (!puzzleFeatureActive() || !DungeonContextTracker.inDungeon()) return
        val bounded = guidance?.stream()?.limit(10)?.toList() ?: emptyList()
        puzzleLines = if (bounded.isEmpty()) emptyList() else java.util.stream.Stream.concat(
            java.util.stream.Stream.of(name), bounded.stream(),
        ).toList()
    }

    fun clearPuzzleGuidance() {
        puzzleLines = emptyList()
    }

    private fun ensurePuzzlePersonalBestsLoaded() {
        if (puzzlePersonalBestsLoaded) return
        puzzleTimingTracker.restorePersonalBests(nullablePuzzleBestMap(DungeonPuzzlePersonalBestStore.load()))
        puzzlePersonalBestsLoaded = true
    }

    private fun nullablePuzzleBestMap(values: Map<String, Float>): Map<String?, Float?> =
        values.mapKeys { (name, _) -> name as String? }.mapValues { (_, seconds) -> seconds as Float? }

    private fun splitStartPriorState(): String {
        if (splitTracker.complete()) return "complete"
        return if (splitTracker.running()) "running" else "idle"
    }


    /** Snapshot used by the packet mixin before Vanilla applies a position correction. */
    fun captureTpMazeCorrectionStart(): Vec3? {
        val client = Minecraft.getInstance()
        val player = client.player ?: return null
        if (!tpMazeCorrectionRoomActive(client) || !client.isSameThread || client.level == null) return null
        return player.position()
    }

    /** Records only a client-thread correction while TP Maze guidance is the selected puzzle. */
    fun onTpMazePositionCorrection(before: Vec3?, packetPosition: Vec3?, packetYaw: Float, packetPitch: Float) {
        val client = Minecraft.getInstance()
        if (before == null || packetPosition == null || !tpMazeCorrectionRoomActive(client)) return
        if (client.isSameThread) {
            val player = client.player ?: return
            val playerBoundsBefore = player.boundingBox.move(before.subtract(player.position()))
            val accepted = puzzleObserver.observeTpMazeCorrection(client, packetPosition, packetYaw, packetPitch, playerBoundsBefore)
            notePuzzleDiagnosticEvent("tp-maze-correction=${if (accepted) "accepted" else "ignored"}")
        }
    }

    private fun tpMazeCorrectionRoomActive(client: Minecraft?): Boolean =
        client != null && solverEnabled("TP_MAZE") && puzzleObserver.currentPuzzleRoomMapIdentity(client) != null

    /** Forwards a locally observed Boulder target click to display-only route progress tracking. */
    fun observeBoulderClick(position: BlockPos?) {
        if (position == null || !solverEnabled("BOULDER")) return
        val client = Minecraft.getInstance()
        if (client.isSameThread) {
            val accepted = puzzleObserver.observeBoulderClick(client, position)
            notePuzzleDiagnosticEvent("boulder-click=${if (accepted) "accepted" else "ignored"}")
        }
    }

    /** Records one successful main-hand Water Board interaction for the current expected valve. */
    fun observeWaterBoardClick(position: BlockPos?) {
        if (position == null || !solverEnabled("WATER_BOARD")) return
        val client = Minecraft.getInstance()
        if (client.isSameThread) {
            val accepted = puzzleObserver.observeWaterBoardClick(client, position)
            notePuzzleDiagnosticEvent("water-board-click=${if (accepted) "accepted" else "ignored"}")
        }
    }

    private fun tpMazeModeActive(): Boolean {
        val client = Minecraft.getInstance()
        return DungeonPuzzleAutoSelectionRules.solverActiveForRoom(
            puzzleObserver.currentPuzzleRoomDisplayName(client), "TP_MAZE", solverEnabled("TP_MAZE"),
        )
    }

    private fun updatePuzzleGuidance(clientTick: Long, nowNanos: Long) {
        val client = Minecraft.getInstance()
        if (!puzzleRuntimeActive()) {
            setPuzzleScanState(client, "inactive", "Puzzle runtime gate is inactive.")
            puzzleObserver.reset()
            puzzleTimingTracker.onLevelLoad()
            lastPuzzleTimingRoomIdentity = null
            lastPuzzleMapRefreshTick = Long.MIN_VALUE
            clearPuzzleGuidance()
            return
        }
        val level = client.level
        if (!client.isSameThread || client.player == null || level == null) {
            setPuzzleScanState(client, "unavailable", "Client world is unavailable on the client thread.")
            puzzleLines = emptyList()
            return
        }
        ensurePuzzlePersonalBestsLoaded()
        if (puzzleTimingLevel != level) {
            puzzleTimingLevel = level
            puzzleTimingTracker.onLevelLoad()
            lastPuzzleTimingRoomIdentity = null
        }
        if (!solverEnabled("CREEPER_BEAMS")) puzzleObserver.clearCreeperBeamEndpointConfirmation()
        // The central context tracker may skip map reads when no other map consumer is enabled.
        // Refresh this bounded local map view at 2 Hz so the observer sees room transitions without
        // making its block/entity scans depend on another module's toggle.
        if (lastPuzzleMapRefreshTick == Long.MIN_VALUE || clientTick - lastPuzzleMapRefreshTick >= 10) {
            DungeonMapRoomDetector.tick(client)
            lastPuzzleMapRefreshTick = clientTick
        }
        val timingRoom = puzzleObserver.currentPuzzleRoomDisplayName(client)
        val timingIdentity = puzzleObserver.currentPuzzleRoomIdentity(client)
        if (timingIdentity != lastPuzzleTimingRoomIdentity) {
            notePuzzleDiagnosticEvent("room-change=${diagnosticToken(timingRoom, 48)}; reset")
            lastPuzzleTimingRoomIdentity = timingIdentity
            if (timingIdentity != null && puzzleTimingActive()) puzzleTimingTracker.onPuzzleRoomEnter(timingRoom, nowNanos)
        }
        puzzleObserver.tickQuizTimer(level.gameTime)
        if (!puzzleFeatureActive()) {
            setPuzzleScanState(client, "inactive", "Puzzle solver and timer gates are inactive.")
            puzzleObserver.resetSolver("QUIZ")
            puzzleObserver.resetSolver("TIC_TAC_TOE")
            clearPuzzleGuidance()
            return
        }
        val solvers = DungeonPuzzleSolversModule.INSTANCE
        val guidance = puzzleObserver.observe(
            client,
            clientTick,
            "AUTO",
            "AUTO",
            if (optimizedSolutions.value()) "OPTIMIZED" else "STANDARD",
            if (DungeonPuzzleSolversModule.useOptimizedIcePatterns.value()) DungeonOdinPuzzleObserver.IceFillStrategy.HARD
                else DungeonOdinPuzzleObserver.IceFillStrategy.EASY,
            true,
            solvers.enabledSolverIds(),
            DungeonPuzzleSolversModule.ticTacToePrediction.value(),
        )
        if (guidance == null) {
            setPuzzleScanState(client, "no-guidance", "No confirmed supported puzzle result was produced for the current room.")
            clearPuzzleGuidance()
        } else {
            setPuzzleScanState(client, guidance.scanStatus().id(), guidance.scanReason())
            setPuzzleLines(guidance.name(), guidance.lines())
        }
    }

    /** Set by the Livid world pass every frame; a null anchor means no Livid timer this frame. */
    fun setLividHudLabel(anchor: Vec3?, label: DungeonLividOverlayLabel.Snapshot?) {
        lividHudAnchor = anchor
        lividHudLabel = label
    }

    /** HUD element `livid_timer_labels`: real-font Livid timer projected above the target. */
    fun renderLividHud(graphics: GuiGraphicsExtractor) {
        val anchor = lividHudAnchor ?: return
        val label = lividHudLabel ?: return
        val mc = Minecraft.getInstance()
        ProjectedLabelRenderer.draw(graphics, mc.gameRenderer.mainCamera(), mc.font, anchor,
            label.text, label.colorArgb, DungeonLividOverlayLabel.HUD_SCALE)
    }

    /** Draws current Water Board countdown labels at their projected world positions. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return
        if (!puzzleFeatureActive()) {
            waterBoardHudVisual = DungeonOdinPuzzleObserver.WaterBoardVisual.empty()
            lastWaterBoardHudRender = "skipped=feature-inactive"
            return
        }
        if (!solverEnabled("WATER_BOARD")) {
            waterBoardHudVisual = DungeonOdinPuzzleObserver.WaterBoardVisual.empty()
            lastWaterBoardHudRender = "skipped=solver-disabled-or-not-dungeon"
            return
        }
        if (client.player == null || client.level == null || client.gameRenderer == null) {
            waterBoardHudVisual = DungeonOdinPuzzleObserver.WaterBoardVisual.empty()
            lastWaterBoardHudRender = "skipped=world-or-renderer-unavailable"
            return
        }
        if (client.gui.hud.isHidden) {
            lastWaterBoardHudRender = "skipped=hud-hidden"
            return
        }
        val water = waterBoardHudVisual
        if (water == null || !water.routeConfirmed()) {
            lastWaterBoardHudRender = "candidates=0 submitted=0 reason=no-confirmed-route"
            return
        }
        val candidates = water.scheduleLabels().size
        var submitted = 0
        val camera = client.gameRenderer.mainCamera()
        for (label in water.scheduleLabels()) {
            if (label == null) continue
            val position = label.position() ?: continue
            val text = label.text() ?: continue
            if (text.isBlank()) continue
            val world = Vec3(position.x() + 0.5, position.y() + 0.8, position.z() + 0.5)
            if (ProjectedLabelRenderer.draw(graphics, camera, client.font, world, text, label.color(), 1.0f))
                submitted++
        }
        lastWaterBoardHudRender = "candidates=$candidates submitted=$submitted"
    }

    /** Draws the remaining route only while every route tile still matches the loaded observation. */
    fun render(context: LevelRenderContext) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return
        val player = client.player
        val gameRenderer = client.gameRenderer
        if (player == null || client.level == null || gameRenderer == null) {
            waterBoardHudVisual = DungeonOdinPuzzleObserver.WaterBoardVisual.empty()
            lastPuzzleDiagnosticRender = "skipped:world-or-renderer-unavailable"
            return
        }
        waterBoardHudVisual = DungeonOdinPuzzleObserver.WaterBoardVisual.empty()
        val camera = gameRenderer.mainCamera()
        val cameraPosition = camera.position()
        val cameraForward = camera.forwardVector()
        val forwardLength = Math.sqrt(
            cameraForward.x().toDouble() * cameraForward.x().toDouble() +
                cameraForward.y().toDouble() * cameraForward.y().toDouble() +
                cameraForward.z().toDouble() * cameraForward.z().toDouble(),
        )
        var tracerStart = cameraPosition
        if (java.lang.Double.isFinite(forwardLength) && forwardLength > 1.0e-9) {
            val offset = PUZZLE_TRACER_START_DISTANCE / forwardLength
            tracerStart = cameraPosition.add(
                cameraForward.x().toDouble() * offset,
                cameraForward.y().toDouble() * offset,
                cameraForward.z().toDouble() * offset,
            )
        }
        var blazeTargets = -1
        var tpMazeRender = "inactive"
        var boulderTargets = 0
        var ticTacToeTargets = 0
        var visuals = "inactive"
        var icePath = "inactive"
        val iceFill: String
        if (blazeVisualGuidanceActive()) {
            val settings = blazeSettings()
            val blazeFrame = blazeOverlay.render(context, settings)
            blazeTargets = blazeFrame.targetCount()
            if (blazeFrame.targetCount() >= 0 && puzzleTimingActive()) {
                puzzleTimingTracker.onBlazeTrackedTargetCount(
                    blazeFrame.roomName(), blazeFrame.targetCount(), System.nanoTime(),
                    DungeonContextTracker.isMobEspPhase(), true,
                ).ifPresent { showPuzzleCompletion(it) }
            }
            if (settings.shouldSendComplete(blazeFrame) && player.connection != null) {
                player.connection.sendCommand("pc Blaze puzzle solved!")
            }
        } else blazeOverlay.reset()
        if (tpMazeModeActive()) tpMazeRender = renderTpMazeLinks(context, client, cameraPosition, tracerStart)
        if (boulderVisualGuidanceActive()) boulderTargets = renderBoulderTarget(context, client)
        if (solverEnabled("TIC_TAC_TOE")) ticTacToeTargets = renderTicTacToeTarget(context, client)
        if (puzzleFeatureActive()) {
            var snapshot = puzzleObserver.visualSnapshot(client)
            snapshot = DungeonOdinPuzzleObserver.PuzzleVisualSnapshot(
                if (DungeonPuzzleSolversModule.waterBoard.value()) snapshot.waterBoard()
                    else DungeonOdinPuzzleObserver.WaterBoardVisual.empty(),
                if (DungeonPuzzleSolversModule.creeperBeams.value()) snapshot.creeperPairs() else emptyList(),
                if (DungeonPuzzleSolversModule.threeWeirdos.value()) snapshot.weirdos() else emptyList(),
                if (DungeonPuzzleSolversModule.quiz.value()) snapshot.quizOptions() else emptyList(),
            )
            waterBoardHudVisual = snapshot.waterBoard()
            DungeonPuzzleVisualRenderer.render(
                context.poseStack(), WorldRenderBuffer.current(), context.submitNodeCollector(), cameraPosition,
                tracerStart, snapshot, puzzleVisualSettings(),
            )
            visuals = "water=${snapshot.waterBoard().orderedUpcomingLevers().size} " +
                "creeper=${snapshot.creeperPairs().size} weirdos=${snapshot.weirdos().size} " +
                "quiz=${snapshot.quizOptions().size}"
        }
        if (puzzleFeatureActive() && solverEnabled("ICE_PATH")) icePath = renderIcePath(context, client)
        if (!client.isSameThread || !solverEnabled("ICE_FILL") || puzzleLines.isEmpty() ||
            "Ice Fill" != puzzleLines.first() || client.player == null || client.level == null || client.gameRenderer == null
        ) {
            lastPuzzleDiagnosticRender = "$PUZZLE_TRACER_DIAGNOSTIC_ORIGIN tttTargets=$ticTacToeTargets " +
                "blazeTargets=$blazeTargets tpMaze=$tpMazeRender boulderTargets=$boulderTargets " +
                "visuals=$visuals iceFill=inactive icePath=$icePath"
            return
        }
        val path = puzzleObserver.iceRenderPath(client)
        val points = path.points()
        iceFill = "points=${points.size} progress=${path.progress()}"
        if (points.size >= 2) {
            val pose = context.poseStack()
            val buffers = WorldRenderBuffer.current()
            for (index in 0 until points.size - 1) {
                val from = points[index]
                val to = points[index + 1]
                val color = DungeonPuzzleSolversModule.iceFillColor.argb()
                val width = if (index == 0) 5.0f else 4.0f
                EspRenderer.renderLine(
                    pose, buffers,
                    from.x - cameraPosition.x(), from.y - cameraPosition.y(), from.z - cameraPosition.z(),
                    to.x - cameraPosition.x(), to.y - cameraPosition.y(), to.z - cameraPosition.z(),
                    color, width, true,
                )
            }
            GeilerAddonsRenderTypes.endBatches(buffers)
        }
        lastPuzzleDiagnosticRender = "$PUZZLE_TRACER_DIAGNOSTIC_ORIGIN tttTargets=$ticTacToeTargets " +
            "blazeTargets=$blazeTargets tpMaze=$tpMazeRender boulderTargets=$boulderTargets " +
            "visuals=$visuals iceFill=$iceFill icePath=$icePath"
    }

    private fun renderIcePath(context: LevelRenderContext, client: Minecraft): String {
        if (client.gameRenderer == null) return "skipped=renderer-unavailable"
        val path = puzzleObserver.icePathRenderPath(client)
        val points = path.points()
        if (points.size < 2) return "points=${points.size} segments=0"
        val camera = client.gameRenderer.mainCamera().position()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val color = DungeonPuzzleSolversModule.icePathColor.argb()
        for (index in 0 until points.size - 1) {
            val from = points[index]
            val to = points[index + 1]
            EspRenderer.renderLine(
                pose, buffers,
                from.x - camera.x(), from.y - camera.y(), from.z - camera.z(),
                to.x - camera.x(), to.y - camera.y(), to.z - camera.z(),
                color, 4.5f, true,
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
        return "points=${points.size} segments=${points.size - 1}"
    }

    private fun renderBoulderTarget(context: LevelRenderContext, client: Minecraft): Int {
        if (client.gameRenderer == null) return 0
        val targets = puzzleObserver.boulderTargets(
            client, DungeonPuzzleSolversModule.showAllBoulderClicks.value(),
        )
        if (targets.isEmpty()) return 0
        val camera = client.gameRenderer.mainCamera().position()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val color = DungeonPuzzleSolversModule.boulderColor.argb()
        val style = DungeonPuzzleVisualRenderer.boxStyle(DungeonPuzzleSolversModule.boulderStyle.value())
        val fillAlpha = (color ushr 24) / 2
        val fill: Int = when (style) {
            DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE -> 0
            DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE -> (color and 0x00FFFFFF) or (fillAlpha shl 24)
            else -> color
        }
        val outline: Int = if (style == DungeonPuzzleVisualRenderer.BoxStyle.FILLED) 0 else color
        for (target in targets) {
            if (target == null || target.renderPosition() == null) continue
            val position = target.renderPosition() ?: continue
            EspRenderer.renderBox(
                pose, buffers,
                position.x() - camera.x(), position.y() - camera.y(), position.z() - camera.z(),
                1.0, 1.0, 1.0, fill, outline, 2.5f, false,
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
        return targets.size
    }

    private fun renderTicTacToeTarget(context: LevelRenderContext, client: Minecraft): Int {
        val targets = puzzleObserver.ticTacToeVisualTargets(client)
        val target = targets.currentO() ?: return 0
        val roomIdentity = lastPuzzleTimingRoomIdentity ?: return 0
        if (cachedTicTacToeFacingLevel != client.level || roomIdentity != cachedTicTacToeFacingRoom) {
            cachedTicTacToeFacingLevel = client.level
            cachedTicTacToeFacingRoom = roomIdentity
            cachedTicTacToeFacing = findTicTacToeBoardFacing(client, target)
        }
        val facing = cachedTicTacToeFacing ?: return 0
        val camera = client.gameRenderer.mainCamera().position()
        var rendered = if (renderTicTacToePlate(context, camera, facing, target, 0x8055FF55.toInt(), 0xFF55FF55.toInt()))
            1 else 0
        val predictedX = targets.predictedX()
        if (predictedX != null && renderTicTacToePlate(context, camera, facing, predictedX, 0x80FFB347.toInt(), 0xFFFFB347.toInt()))
            rendered++
        val nextO = targets.nextO()
        if (nextO != null && renderTicTacToePlate(context, camera, facing, nextO, 0x8000D9FF.toInt(), 0xFF00D9FF.toInt()))
            rendered++
        GeilerAddonsRenderTypes.endBatches(WorldRenderBuffer.current())
        return rendered
    }

    private fun renderTicTacToePlate(
        context: LevelRenderContext,
        camera: Vec3,
        facing: Direction,
        target: DungeonOdinPuzzleObserver.WorldBlock?,
        fillColor: Int,
        outlineColor: Int,
    ): Boolean {
        if (target == null) return false
        val markerSize = 0.82
        val markerDepth = 0.04
        val centerX = target.x() + 0.5 - facing.stepX * 0.49
        val centerY = target.y() + 0.5 - facing.stepY * 0.49
        val centerZ = target.z() + 0.5 - facing.stepZ * 0.49
        val sizeX = if (facing.axis == Direction.Axis.X) markerDepth else markerSize
        val sizeY = if (facing.axis == Direction.Axis.Y) markerDepth else markerSize
        val sizeZ = if (facing.axis == Direction.Axis.Z) markerDepth else markerSize
        EspRenderer.renderBox(
            context.poseStack(), WorldRenderBuffer.current(),
            centerX - sizeX / 2.0 - camera.x(), centerY - sizeY / 2.0 - camera.y(),
            centerZ - sizeZ / 2.0 - camera.z(), sizeX, sizeY, sizeZ,
            fillColor, outlineColor, 2.5f, false,
        )
        return true
    }

    private fun findTicTacToeBoardFacing(
        client: Minecraft,
        target: DungeonOdinPuzzleObserver.WorldBlock,
    ): Direction? {
        val level = client.level ?: return null
        val targetCellBounds = AABB(
            target.x() - 0.1, target.y() - 0.1, target.z() - 0.1,
            target.x() + 1.1, target.y() + 1.1, target.z() + 1.1,
        )
        for (frame in level.getEntities(
            EntityTypeTest.forClass(ItemFrame::class.java), targetCellBounds,
        ) { entity -> !entity.isRemoved && entity.isAlive }) {
            val facing = frame.direction
            if (facing != null) return facing
        }
        val boardBounds = AABB(
            target.x() - 2.5, target.y() - 2.5, target.z() - 2.5,
            target.x() + 3.5, target.y() + 3.5, target.z() + 3.5,
        )
        val targetCenterX = target.x() + 0.5
        val targetCenterY = target.y() + 0.5
        val targetCenterZ = target.z() + 0.5
        var nearestDistanceSquared = Double.MAX_VALUE
        var nearestFacing: Direction? = null
        for (frame in level.getEntities(
            EntityTypeTest.forClass(ItemFrame::class.java), boardBounds,
        ) { entity -> !entity.isRemoved && entity.isAlive && entity.item.`is`(Items.FILLED_MAP) }) {
            val dx = frame.x - targetCenterX
            val dy = frame.y - targetCenterY
            val dz = frame.z - targetCenterZ
            val distanceSquared = dx * dx + dy * dy + dz * dz
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared
                nearestFacing = frame.direction
            }
        }
        return nearestFacing
    }

    private fun blazeSettings(): DungeonOdinBlazeOverlay.Settings {
        return DungeonOdinBlazeOverlay.Settings(
            DungeonPuzzleSolversModule.blazeNextLine.value(), DungeonPuzzleSolversModule.blazeLineCount.intValue(),
            DungeonPuzzleSolversModule.blazeLineWidth.value(),
            DungeonPuzzleVisualRenderer.boxStyle(DungeonPuzzleSolversModule.blazeStyle.value()),
            DungeonPuzzleSolversModule.blazeFirstColor.argb(), DungeonPuzzleSolversModule.blazeSecondColor.argb(),
            DungeonPuzzleSolversModule.blazeThirdColor.argb(), DungeonPuzzleSolversModule.blazeOtherColor.argb(),
            DungeonPuzzleSolversModule.blazeSendComplete.value(),
        )
    }

    private fun puzzleVisualSettings(): DungeonPuzzleVisualRenderer.Settings {
        return DungeonPuzzleVisualRenderer.Settings(
            DungeonPuzzleVisualRenderer.WaterSettings(
                DungeonPuzzleSolversModule.waterBoardTracer.value(),
                DungeonPuzzleSolversModule.waterBoardTracerFirstColor.argb(),
                DungeonPuzzleSolversModule.waterBoardTracerSecondColor.argb(),
            ),
            DungeonPuzzleVisualRenderer.CreeperSettings(
                DungeonPuzzleVisualRenderer.boxStyle(DungeonPuzzleSolversModule.creeperBeamStyle.value()),
                DungeonPuzzleSolversModule.creeperBeamTracer.value(), DungeonPuzzleSolversModule.creeperBeamAlpha.value(),
            ),
            DungeonPuzzleVisualRenderer.WeirdosSettings(
                DungeonPuzzleVisualRenderer.boxStyle(DungeonPuzzleSolversModule.weirdosStyle.value()),
                DungeonPuzzleSolversModule.weirdosCorrectColor.argb(), DungeonPuzzleSolversModule.weirdosWrongColor.argb(),
            ),
            DungeonPuzzleVisualRenderer.QuizSettings(
                DungeonPuzzleSolversModule.quizColor.argb(), DungeonPuzzleSolversModule.quizDepth.value(),
            ),
        )
    }

    private fun renderTpMazeLinks(
        context: LevelRenderContext,
        client: Minecraft,
        cameraPosition: Vec3,
        tracerStart: Vec3,
    ): String {
        if (client.gameRenderer == null) return "renderer-unavailable"
        val level = client.level ?: return "world-unavailable"
        val padVisuals = puzzleObserver.tpMazePadVisuals(client)
        val segments = puzzleObserver.tpMazeRenderSegments(client, false, tpMazeTracer.value(), tracerStart)
        if (padVisuals.isEmpty() && segments.isEmpty()) return "pads=0 tracer=0"
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        var renderedPads = 0
        for (pad in padVisuals) {
            val position = pad.position() ?: continue
            val blockPos = BlockPos(position.x, position.y, position.z)
            if (!level.hasChunkAt(blockPos)) continue
            val state = level.getBlockState(blockPos)
            if (!state.`is`(Blocks.END_PORTAL_FRAME)) continue
            val bounds = state.getShape(level, blockPos).bounds()
            val style = DungeonTpMazeRenderGeometry.padStyle(
                pad.visited(), pad.candidate(), pad.remainingCandidates(),
            )
            val color = when (style) {
                DungeonTpMazeRenderGeometry.PadStyle.VISITED -> tpMazeVisitedColor.argb()
                DungeonTpMazeRenderGeometry.PadStyle.SINGLE_CANDIDATE -> tpMazeSingleCandidateColor.argb()
                DungeonTpMazeRenderGeometry.PadStyle.MULTIPLE_CANDIDATES -> tpMazeMultipleCandidateColor.argb()
                else -> 0x80FFFFFF.toInt()
            }
            EspRenderer.renderBox(
                pose, buffers,
                blockPos.x + bounds.minX - cameraPosition.x(),
                blockPos.y + bounds.minY - cameraPosition.y(),
                blockPos.z + bounds.minZ - cameraPosition.z(),
                bounds.xsize, bounds.ysize, bounds.zsize, color, color, 1.5f,
                DungeonTpMazeRenderGeometry.depthTested(style),
            )
            renderedPads++
        }
        for (segment in segments) {
            val from = segment.from ?: continue
            val to = segment.to ?: continue
            EspRenderer.renderLine(
                pose, buffers,
                from.x - cameraPosition.x(), from.y - cameraPosition.y(), from.z - cameraPosition.z(),
                to.x - cameraPosition.x(), to.y - cameraPosition.y(), to.z - cameraPosition.z(),
                tpMazeTracerColor.argb(), 2.5f, DungeonTpMazeRenderGeometry.tracerDepthTested(),
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
        return "pads=$renderedPads/${padVisuals.size} tracerSegments=${segments.size},tracerDepthTested=true"
    }

    private fun blazeVisualGuidanceActive(): Boolean =
        solverEnabled("BLAZE") && DungeonContextTracker.isMobEspPhase() && puzzleLines.isNotEmpty() &&
            "Blaze" == puzzleLines.first()

    private fun boulderVisualGuidanceActive(): Boolean {
        if (!solverEnabled("BOULDER")) return false
        val activePuzzleName = if (puzzleLines.isEmpty()) null else puzzleLines.first()
        return DungeonBoulderRules.shouldRenderTarget("AUTO", activePuzzleName)
    }

    private fun publishHudSnapshots() {
        val lividModule = DungeonLividSolverModule.INSTANCE
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, lividModule.name(), lividModule.isEnabled())
        if (!DungeonContextTracker.inDungeon()) {
            publishedLividTarget = null
            publishLividTitle(lividModule, null)
            DungeonRunSplitsModule.INSTANCE.clearSnapshots()
            DungeonRunSplitsModule.INSTANCE.publishSplitRows(
                null, emptyList(), lastClientTick, false, null, serverTickCounter.count(),
            )
            DungeonLividSolverModule.INSTANCE.clearHudLines()
            DungeonInvincibilityTimerModule.INSTANCE.publishReadings(emptyList())
            DungeonTerminalStatsModule.INSTANCE.clearHudLines()
            val terminalCounts = terminals.counts()
            DungeonTerminalStatsDiagnostics.recordStatus(
                lastClientTick, terminalStatsEnabled(), false, null, terminalBreakdownEnabled.value(),
                terminalSelfOnly.value(), terminalCounts.getOrDefault("terminal", 0),
                terminalCounts.getOrDefault("lever", 0), terminalCounts.getOrDefault("device", 0),
                terminals.perPlayer().size, 0,
            )
            recordLeapStatus()
            recordLividDiagnostics()
            return
        }
        val now = System.nanoTime()
        val floor = DungeonContextTracker.currentFloor()
        var splitRows: List<DungeonSplitTracker.Split> = emptyList()
        if (runSplitsEnabled() && showSplits.value()) {
            splitRows = splitTracker.snapshot(now, lastClientTick, serverPingPacketCount, serverTickCounter.count())
        }
        DungeonRunSplitsModule.INSTANCE.publishSplitRows(
            floor, splitRows, lastClientTick, splitTracker.running(), splitTracker.currentId(), serverTickCounter.count(),
        )

        val lividLines = ArrayList<String>()
        var lividTitleColor = 0xFFFFD24A.toInt()
        val lividClient = Minecraft.getInstance()
        val lividPlayer = lividClient.player
        val lividBlinded = lividPlayer?.hasEffect(MobEffects.BLINDNESS) == true
        val selectedTarget = if (lividEnabled() && livid.value() && floor != null && floor.number() == 5)
            lividSelectedTarget(lividBlinded) else null
        publishedLividTarget = selectedTarget
        publishLividTitle(lividModule, selectedTarget)
        if (lividEnabled() && livid.value() && floor != null && floor.number() == 5) {
            if (lividModule.showTargetTitle() && selectedTarget != null) {
                val colorName = selectedTarget.colorName
                if (colorName != null) {
                    lividLines.add(DungeonLividSolverModule.lividLabel(colorName))
                    lividTitleColor = if (lividModule.useTargetColor()) selectedTarget.colorArgb
                        else lividModule.tracerColorArgb()
                }
            }
            if (DungeonLividSolverModule.showTimer.value() && lividTracker.timerVisible(lividFloorFiveBoss())) {
                lividLines.add(
                    "Livid invulnerability · ${String.format(Locale.ROOT, "%.1fs", lividTracker.invulnerabilityTicks() / 20.0)}",
                )
            }
        }
        lividModule.publishHudLines(lividLines, lividTitleColor)

        val invincibilityReadings = if (invincibilityEnabled()) {
            maskTimers.snapshot(showBonzo.value(), showSpirit.value(), showPhoenix.value())
        } else emptyList()
        DungeonInvincibilityTimerModule.INSTANCE.publishReadings(invincibilityReadings)
        DungeonInvincibilityTimerModule.INSTANCE.tick()

        val terminalLines = ArrayList<String>()
        val terminalCounts = terminals.counts()
        if (terminalStatsEnabled() && terminalBreakdownEnabled.value() && terminalCounts.isNotEmpty()) {
            terminalLines.add(
                "Terminals ${terminalCounts.getOrDefault("terminal", 0)} · " +
                    "Levers ${terminalCounts.getOrDefault("lever", 0)} · Devices ${terminalCounts.getOrDefault("device", 0)}",
            )
            for (player in terminals.perPlayer()) {
                terminalLines.add(
                    "${player.player} · T${player.terminals} L${player.levers} D${player.devices}",
                )
            }
        }
        DungeonTerminalStatsModule.INSTANCE.publishHudLines(terminalLines.stream().limit(18).toList())
        DungeonTerminalStatsDiagnostics.recordStatus(
            lastClientTick, terminalStatsEnabled(), true, floor?.displayName(), terminalBreakdownEnabled.value(),
            terminalSelfOnly.value(), terminalCounts.getOrDefault("terminal", 0),
            terminalCounts.getOrDefault("lever", 0), terminalCounts.getOrDefault("device", 0),
            terminals.perPlayer().size, terminalLines.size,
        )
        recordLeapStatus()
        recordLividDiagnostics()
    }

    private fun publishLividTitle(
        module: DungeonLividSolverModule,
        selectedTarget: DungeonLividTracker.TargetSnapshot?,
    ) {
        if (!module.isEnabled() || !module.showTargetTitle() || selectedTarget == null) {
            titledLividTargetKey = null
            return
        }
        val colorName = selectedTarget.colorName
        if (colorName == null) {
            titledLividTargetKey = null
            return
        }
        val targetKey = "${selectedTarget.uuid}|${selectedTarget.entityName}|${selectedTarget.colorName}"
        if (targetKey == titledLividTargetKey) return
        val acquiring = titledLividTargetKey == null
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.gui == null) return
        val titleText = DungeonLividSolverModule.lividLabel(colorName)
        val color = if (module.useTargetColor()) selectedTarget.colorArgb else module.tracerColorArgb()
        val title = Component.literal(titleText).withStyle { style ->
            style.withColor(TextColor.fromRgb(color and 0x00FFFFFF))
        }
        client.gui.hud.setTimes(0, 20, 0)
        client.gui.hud.setTitle(title)
        titledLividTargetKey = targetKey
        val transition = if (acquiring) "acquired" else "changed"
        lastLividDiagnosticEvent = "title=$transition target=${diagnosticToken(titleText, 64)} " +
            "color=${selectedTarget.colorName}"
        GeilerAddonsLog.write(
            Category.F7, module.name(), lastClientTick,
            "TITLE submitted event=$transition target=${diagnosticToken(titleText, 64)} " +
                "color=${selectedTarget.colorName} durationTicks=20",
        )
    }

    private fun recordLividDiagnostics() {
        val module = DungeonLividSolverModule.INSTANCE
        if (!module.isEnabled()) {
            lastLividDiagnosticTick = Long.MIN_VALUE
            lastLividDiagnosticStatus = null
            coalescedLividDiagnosticChanges = 0
            return
        }
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return
        val floor = DungeonContextTracker.currentFloor()
        val gate = when {
            !DungeonContextTracker.inDungeon() -> "outside-dungeon"
            floor == null -> "floor-unknown"
            floor.number() != 5 -> "outside-f5"
            DungeonContextTracker.isMobEspPhase() -> "pre-boss"
            else -> "eligible"
        }
        val target = publishedLividTarget
        val selected = if (target == null) "none" else
            "${diagnosticToken(target.entityName, 48)}:${diagnosticToken(target.colorName, 16)}#${target.entityId}"
        val timerTicks = lividTracker.invulnerabilityTicks()
        val timerVisible = DungeonLividSolverModule.showTimer.value() && lividTracker.timerVisible(lividFloorFiveBoss())
        val status = "enabled=true dungeon=${DungeonContextTracker.inDungeon()} " +
            "floor=${floor?.displayName() ?: "unknown"} phase=$gate " +
            "markerState=$lastLividMarkerEvidence markerSource=$lastLividMarkerSource " +
            "markerColor=${diagnosticToken(lividTracker.targetColorName(), 16)} " +
            "candidates={$lastLividCandidateDiagnostic} selected=$selected timerTicks=$timerTicks " +
            "timerSeconds=${String.format(Locale.ROOT, "%.1f", timerTicks / 20.0)} timerVisible=$timerVisible " +
            "titleEnabled=${module.showTargetTitle()} render={$lastLividRenderDiagnostic} " +
            "event={$lastLividDiagnosticEvent}"
        val changed = status != lastLividDiagnosticStatus
        val elapsed = if (lastLividDiagnosticTick == Long.MIN_VALUE) Long.MAX_VALUE
            else maxOf(0L, lastClientTick - lastLividDiagnosticTick)
        if (changed && elapsed < 20) {
            coalescedLividDiagnosticChanges++
            return
        }
        if (changed || elapsed >= 100) {
            GeilerAddonsLog.write(
                Category.F7, module.name(), lastClientTick,
                "STATUS $status coalescedChanges=$coalescedLividDiagnosticChanges",
            )
            lastLividDiagnosticStatus = status
            lastLividDiagnosticTick = lastClientTick
            coalescedLividDiagnosticChanges = 0
        }
    }

    /** A low-rate, settings-complete snapshot; detailed input events are separately tick-capped. */
    private fun recordLeapStatus() {
        if (lastClientTick % 20L != 0L || !SpiritLeapMenuModule.INSTANCE.isEnabled()) return
        val client = Minecraft.getInstance()
        val openScreen = client.gui.screen()
        var menuState = "closed"
        if (openScreen is AbstractContainerScreen<*>) {
            menuState = if (SpiritLeapSelection.isLeapMenu(openScreen.title.string)) "leap" else "other_container"
        }
        val width = openScreen?.width ?: 0
        val height = openScreen?.height ?: 0
        var cards = 0
        var menuHeads = 0
        var displayCards = 0
        var cardSource = "none"
        var online = 0
        var dead = 0
        for (card in leapCards()) {
            if (card == null || card.member() == null || card.member().empty()) continue
            cards++
            if (card.member().online()) online++
            if (card.member().dead()) dead++
        }
        if (openScreen is AbstractContainerScreen<*> && SpiritLeapSelection.isLeapMenu(openScreen.title.string)) {
            val localName = client.player?.gameProfile?.name
            val heads = spiritLeapMenuHeads(openScreen)
            menuHeads = SpiritLeapRoster.resolveMenuHeads(heads, emptyList(), localName).size
            if (shouldRenderSpiritLeapScreen(openScreen)) {
                displayCards = leapCards(openScreen).stream().filter { card ->
                    card != null && card.member() != null && !card.member().empty()
                }.count().toInt()
                if (displayCards > 0) cardSource = "menu-heads"
            }
        }
        val state = "moduleEnabled=true inDungeon=${DungeonContextTracker.inDungeon()} menu=$menuState " +
            "screen=${width}x$height rosterCards=$cards menuHeads=$menuHeads displayCards=$displayCards " +
            "cardSource=$cardSource online=$online dead=$dead ${SpiritLeapMenuModule.diagnosticSettings()} " +
            "pov=${SpiritLeapPovRenderer.INSTANCE.diagnosticState()}"
        SpiritLeapMenuModule.recordStatus(lastClientTick, state)
    }

    /** Counts nonzero legacy ping events and negative-ID server-tick packets after vanilla client-thread dispatch. */
    fun onServerPingPacket(pingId: Long) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { onServerPingPacket(pingId) }
            return
        }
        if (pingId != 0L && serverPingPacketCount < Long.MAX_VALUE) serverPingPacketCount++
        serverTickCounter.observePingId(pingId)
    }

    /** Server ticks since the run clock started (world-time anchored); 0 until the first world-time packet. */
    fun serverTicks(): Long = serverTickCounter.count()

    /** Anchors the split server-tick clock on the server's world time (once per second). */
    fun onServerGameTime(gameTime: Long) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { onServerGameTime(gameTime) }
            return
        }
        serverTickCounter.observeGameTime(gameTime)
    }

    /** Client-thread callback after the vanilla packet-thread handoff for a block event. */
    fun onPuzzleBlockEvent(packet: ClientboundBlockEventPacket?) {
        if (packet == null || !puzzleTimingActive()) return
        val client = Minecraft.getInstance()
        val level = client.level
        if (!client.isSameThread || client.player == null || level == null || !level.hasChunkAt(packet.pos))
            return
        val roomName = puzzleObserver.currentPuzzleRoomDisplayName(client) ?: return
        val local = puzzleObserver.sourceRoomRelativePosition(client, packet.pos) ?: return
        val blockId = BuiltInRegistries.BLOCK.getKey(packet.block).path
        ensurePuzzlePersonalBestsLoaded()
        puzzleTimingTracker.onPuzzleBlockEvent(
            roomName, blockId, local.x, local.y, local.z, System.nanoTime(), DungeonContextTracker.isMobEspPhase(), true,
        ).ifPresent { showPuzzleCompletion(it) }
    }

    /** Client-thread callback for the exact source-observed Creeper Beams block transition. */
    fun onPuzzleBlockChange(
        oldPosition: BlockPos?,
        oldState: BlockState?,
        newPosition: BlockPos?,
        newState: BlockState?,
    ) {
        if (oldPosition == null || newPosition == null || oldState == null || newState == null || oldPosition != newPosition)
            return
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.player == null || client.level == null) return
        if (solverEnabled("CREEPER_BEAMS")) {
            val recalculated = puzzleObserver.onCreeperBeamBlockUpdate(client, newPosition, oldState, newState)
            if (recalculated) notePuzzleDiagnosticEvent("creeper-beams-block-update=recalculated")
        }
        if (!puzzleTimingActive()) return
        val roomName = puzzleObserver.currentPuzzleRoomDisplayName(client)
        if (roomName != "Creeper Beams") return
        val local = puzzleObserver.sourceRoomRelativePosition(client, newPosition) ?: return
        val oldBlockId = BuiltInRegistries.BLOCK.getKey(oldState.block).path
        val newBlockId = BuiltInRegistries.BLOCK.getKey(newState.block).path
        ensurePuzzlePersonalBestsLoaded()
        puzzleTimingTracker.onPuzzleBlockUpdate(
            roomName, oldBlockId, newBlockId, local.x, local.y, local.z, System.nanoTime(),
            DungeonContextTracker.isMobEspPhase(), true,
        ).ifPresent { showPuzzleCompletion(it) }
    }

    /** Called on the client thread after login/respawn has installed its level. */
    fun onPuzzleLevelLoad() {
        puzzleTimingTracker.onLevelLoad()
        puzzleTimingLevel = Minecraft.getInstance().level
        lastPuzzleTimingRoomIdentity = null
        lastPuzzleMapRefreshTick = Long.MIN_VALUE
        puzzleObserver.reset()
        clearPuzzleGuidance()
    }

    /** Reset one visible solver's observations without clearing run-scoped timer/PB state. */
    fun resetPuzzle(solverId: String?) {
        if (solverId == null || solverId !in setOf(
                "WATER_BOARD", "TP_MAZE", "ICE_FILL", "BLAZE", "CREEPER_BEAMS", "THREE_WEIRDOS", "QUIZ",
                "BOULDER", "TIC_TAC_TOE",
            )
        ) return
        puzzleObserver.resetSolver(solverId)
        notePuzzleDiagnosticEvent("manual-reset=$solverId")
        if (solverId == "BLAZE") blazeOverlay.reset()
        val visibleName = when (solverId) {
            "WATER_BOARD" -> "Water Board"
            "TP_MAZE" -> "TP Maze"
            "ICE_FILL" -> "Ice Fill"
            "BLAZE" -> "Blaze"
            "CREEPER_BEAMS" -> "Creeper Beams"
            "THREE_WEIRDOS" -> "Three Weirdos"
            "QUIZ" -> "Quiz"
            "BOULDER" -> "Boulder"
            "TIC_TAC_TOE" -> "Tic Tac Toe"
            else -> ""
        }
        if (puzzleLines.isNotEmpty() && visibleName == puzzleLines.first()) clearPuzzleGuidance()
    }

    private fun showPuzzleCompletion(completion: DungeonPuzzleTimingTracker.Completion?) {
        if (completion == null) return
        ensurePuzzlePersonalBestsLoaded()
        if (completion.personalBest) {
            DungeonPuzzlePersonalBestStore.save(nullablePuzzleBestMap(puzzleTimingTracker.personalBestSnapshot()))
        }
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.gui == null) return
        val best = if (completion.personalBest) "new PB"
            else "best ${String.format(Locale.ROOT, "%.2fs", completion.previousBestSeconds)}"
        client.gui.hud.chat.addClientSystemMessage(
            Component.literal(
                "[GeilerAddons] ${completion.puzzleName} completed in " +
                    "${String.format(Locale.ROOT, "%.2fs", completion.elapsedSeconds)} · $best",
            ),
        )
    }

    private fun showDraftPrompt(prompt: DungeonPuzzleTimingTracker.DraftPrompt?) {
        val client = Minecraft.getInstance()
        if (prompt == null || !client.isSameThread || client.gui == null) return
        val text = requireNotNull(prompt.text)
        val runCommand = requireNotNull(prompt.runCommand)
        val hoverText = requireNotNull(prompt.hoverText)
        val clickable = Component.literal(text).withStyle { style ->
            style.withUnderlined(true)
                .withClickEvent(ClickEvent.RunCommand(runCommand))
                .withHoverEvent(HoverEvent.ShowText(Component.literal(hoverText)))
        }
        client.gui.hud.chat.addClientSystemMessage(clickable)
    }

    /** Resets run-scoped observations from the client disconnect/level load hook. */
    fun reset() {
        notePuzzleDiagnosticEvent("lifecycle-reset=level-load-or-disconnect")
        setPuzzleScanState(Minecraft.getInstance(), "inactive", "Puzzle observation state was reset.")
        SpiritLeapPovRenderer.INSTANCE.close()
        scoreTracker.reset()
        scoreRunKey = null
        scoreRunEntryKey = null
        scoreBloodDone = null
        scoreRunEntryObserved = false
        scoreBonusSignals.resetUnknown()
        mapRunInfo = null
        puzzleObserver.reset()
        puzzleTimingTracker.onLevelLoad()
        puzzleTimingLevel = null
        lastPuzzleTimingRoomIdentity = null
        blazeOverlay.reset()
        splitTracker.reset()
        splitEntryBuffer.clear()
        lividTracker.reset()
        lividMissingTicks = 0
        publishedLividTarget = null
        maskTimers.reset()
        terminals.reset()
        clearLeapRoster()
        cachedLeapSkinTextures = emptyMap()
        cachedLeapSkinLevel = null
        cachedLeapSkinRefreshTick = Long.MIN_VALUE
        leapArrivalCoalescer.clear()
        incomingLeapCoalescer.clear()
        puzzleLines = emptyList()
        DungeonScorerModule.INSTANCE.clearAlert()
        pendingPartyAnnouncement = null
        pendingProcAlert = null
        pendingLeapSound = false
        wasInDungeon = false
        lastClientTick = 0L
        serverPingPacketCount = 0L
        serverTickCounter.reset()
        lastPuzzleMapRefreshTick = Long.MIN_VALUE
        DungeonRunSplitsModule.INSTANCE.clearSnapshots()
        DungeonLividSolverModule.INSTANCE.clearHudLines()
        DungeonInvincibilityTimerModule.INSTANCE.clearSnapshots()
        DungeonTerminalStatsModule.INSTANCE.clearHudLines()
    }

    /** Apply disconnect resets on the client executor and discard work from an older connection. */
    fun resetAfterDisconnect(disconnectedConnection: ClientPacketListener?, client: Minecraft?) {
        if (client == null) return
        val expectedLevel = client.level
        val resetIfCurrentSession = Runnable {
            if (client.level != expectedLevel && client.level != null) return@Runnable
            val currentConnection = client.connection
            if (currentConnection != null && currentConnection != disconnectedConnection) return@Runnable
            reset()
        }
        if (client.isSameThread) resetIfCurrentSession.run() else client.execute(resetIfCurrentSession)
    }

    private fun selectedCountMode(): DungeonSplitTimeMode = try {
        DungeonSplitTimeMode.valueOf(countSource.value())
    } catch (_: IllegalArgumentException) {
        DungeonSplitTimeMode.CLIENT_TICKS
    }

    private fun showLocalRunSummary(floor: DungeonFloor, update: DungeonSplitPersonalBestBook.Update?) {
        val client = Minecraft.getInstance()
        if (client.gui == null || update == null) return
        val countMode = selectedCountMode()
        val showClientTicks = showTickTime.value()
        val showExtraCount = showCountValue.value() &&
            (countMode != DungeonSplitTimeMode.CLIENT_TICKS || !showClientTicks)
        val newBest = showRealTime.value() && update.isPersonalBest(DungeonSplitTimeMode.REAL_TIME) ||
            showClientTicks && update.isPersonalBest(DungeonSplitTimeMode.CLIENT_TICKS) ||
            showExtraCount && update.isPersonalBest(countMode)
        val entry = update.entry
        val summary = StringBuilder(floor.displayName()).append(" complete")
        if (showRealTime.value()) summary.append(" · ").append(
            DungeonSplitTimeMode.REAL_TIME.format(DungeonSplitTimeMode.REAL_TIME.value(entry, false)),
        )
        if (showClientTicks) summary.append(" · ").append(
            DungeonSplitTimeMode.CLIENT_TICKS.format(DungeonSplitTimeMode.CLIENT_TICKS.value(entry, false)),
        )
        if (showExtraCount) summary.append(" · ").append(countMode.format(countMode.value(entry, false)))
        if (showPbPace.value()) {
            val savedEntry = requireNotNull(entry)
            summary.append(" · ").append(
                DungeonSplitTimeMode.formatPbPace(savedEntry.lastRealTimeNanos, savedEntry.bestRealTimeNanos),
            )
        }
        var result = if (newBest) "new PB" else "best"
        if (!newBest && showRealTime.value()) result += " " +
            DungeonSplitTimeMode.REAL_TIME.format(DungeonSplitTimeMode.REAL_TIME.value(entry, true))
        if (!newBest && showClientTicks) result += ", " +
            DungeonSplitTimeMode.CLIENT_TICKS.format(DungeonSplitTimeMode.CLIENT_TICKS.value(entry, true))
        if (!newBest && showExtraCount) result += ", " + countMode.format(countMode.value(entry, true))
        client.gui.hud.chat.addClientSystemMessage(
            Component.literal("[GeilerAddons] $summary · $result · chat-observed milestones"),
        )
    }

    @JvmRecord
    data class BlazeObservation(val entityId: String?, val currentHealth: Int)

    private class Settings {
        val scoreAlerts = BooleanSetting("Score Alerts 270/300", true)
        val scoreThresholdOne = NumberSetting("Score Alert Threshold 1", 0f, 300f, 270f, true)
        val scoreThresholdTwo = NumberSetting("Score Alert Threshold 2", 0f, 300f, 300f, true)
        val scoreAlertText = TextSetting("Score Alert Text", "Score {score}", 80)
        val scoreAlertDurationTicks = NumberSetting("Score Alert Duration (Ticks)", 10f, 300f, 100f, true)
        val scoreAlertSound = BooleanSetting("Score Alert Sound", false)
        val scoreAlertSoundId = TextSetting("Score Alert Sound ID", "block.note_block.pling", 96)
        val splits = BooleanSetting("Dungeon Splits", true)
        val showCurrentSplit = BooleanSetting("Show Current Split", true)
        val showCountValue = BooleanSetting("Show Tick Timing", "Show Count Value", true)
        val countSource = ChoiceSetting("Count Source", "CLIENT_TICKS", mapOf(
            "CLIENT_TICKS" to "Client ticks (local)",
            "SERVER_PING_PACKETS" to "Odin ping-event proxy (not server ticks)",
        ), "CLIENT_TICKS", "SERVER_PING_PACKETS")
        val localRunSummary = BooleanSetting("Local Run Summary", true)
        val livid = BooleanSetting("Livid Highlight & Timer", true)
        val showBonzo = BooleanSetting("Bonzo Mask Timer", true)
        val showSpirit = BooleanSetting("Spirit Mask Timer", true)
        val showPhoenix = BooleanSetting("Phoenix Proc Timer", true)
        val terminalBreakdown = BooleanSetting("Terminal Breakdown", true)
        val terminalTitles = BooleanSetting("Replace Terminal Titles", false)
        val terminalSelfOnly = BooleanSetting("Count Self Only", false)
        val spiritLeap = BooleanSetting("Spirit Leap Cards & Keys", true)
        val leapOnMouseRelease = BooleanSetting("Leap on Mouse Release", false)
        val leapAnnouncement = BooleanSetting("Announce Leaps", false)
        val leapSound = BooleanSetting("Spirit Leap Sound", true)
        val incomingLeapTitle = BooleanSetting("Incoming Leap Title", false)
        val incomingLeapTitleDurationTicks = NumberSetting(
            "Incoming Leap Title Duration (Ticks)", SpiritLeapIncomingPresentation.MIN_DURATION_TICKS.toFloat(),
            SpiritLeapIncomingPresentation.MAX_DURATION_TICKS.toFloat(),
            SpiritLeapIncomingPresentation.DEFAULT_DURATION_TICKS.toFloat(), true,
        )
        val leapSoundId = TextSetting("Spirit Leap Sound ID", "block.note_block.pling", 96)
        val leapClassColors = BooleanSetting("Class Colors", true)
        val leapSort = ChoiceSetting("Leap Sorting", "ODIN", mapOf(
            "ODIN" to "Class Layout", "CLASS" to "Class Order", "NAME" to "Name", "HEALTH" to "Health",
            "CUSTOM" to "Custom",
        ), "ODIN", "CLASS", "NAME", "HEALTH", "CUSTOM")
        val leapKeyMode = ChoiceSetting("Leap Key Mode", "CORNERS", "CORNERS", "CLASS")
        val terminalTitleTemplate = TextSetting("Terminal Title Template", "&a{type} {current}/{total}", 80)
        val customLeapOrder = TextSetting("Custom Leap Order", "", 96)
        val topLeftKey = TextSetting("Top Left Key", "1", 24)
        val topRightKey = TextSetting("Top Right Key", "2", 24)
        val bottomLeftKey = TextSetting("Bottom Left Key", "3", 24)
        val bottomRightKey = TextSetting("Bottom Right Key", "4", 24)
        val archerKey = TextSetting("Archer Key", "1", 24)
        val berserkKey = TextSetting("Berserk Key", "2", 24)
        val healerKey = TextSetting("Healer Key", "3", 24)
        val mageKey = TextSetting("Mage Key", "4", 24)
        val tankKey = TextSetting("Tank Key", "5", 24)
    }
}

