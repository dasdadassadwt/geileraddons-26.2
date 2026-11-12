package geiler.addons.client.module.impl

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.enchanting.ChronomatronEvent
import geiler.addons.client.enchanting.ExperimentBoardGeometry
import geiler.addons.client.enchanting.ExperimentCell
import geiler.addons.client.enchanting.ExperimentPhase
import geiler.addons.client.enchanting.ExperimentSnapshot
import geiler.addons.client.enchanting.ExperimentSnapshotLogic
import geiler.addons.client.enchanting.ExperimentSolverEngine
import geiler.addons.client.enchanting.ExperimentTier
import geiler.addons.client.enchanting.SuperpairsClicks
import geiler.addons.client.enchanting.ExperimentType
import geiler.addons.client.enchanting.SequenceStep
import geiler.addons.client.enchanting.SolverView
import geiler.addons.client.enchanting.SuperpairsBoard
import geiler.addons.client.enchanting.SuperpairsPairColors
import geiler.addons.client.enchanting.UltrasequencerSequenceExecutor
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.ContainerListener
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import java.util.ArrayList
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.List as JavaList
import java.util.Locale
import java.util.Map as JavaMap
import java.util.Optional
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The screen-facing half of the experimentation solver.
 *
 * The shared controller owns observation and solver state. This adapter renders its immutable
 * view, draws the Superpairs memory maintained by the shared menu session, and translates an
 * accepted custom hit back into vanilla slotClicked. Sequence progress advances only after that
 * ordinary vanilla dispatch, so the custom surface never predicts a click the server has not accepted.
 */
class ExperimentSolverModule private constructor(private val settings: Settings) : Module(
    "Experimentation Solver",
    "Solves Experimentation Table games.",
    Category.ENCHANTING,
    *settings.all(),
) {
    private val chronomatron = settings.chronomatron
    private val ultrasequencer = settings.ultrasequencer
    private val superpairs = settings.superpairs
    private val hideTextAboveBoard = settings.hideTextAboveBoard
    private val showSuperpairsClicksRemaining = settings.showSuperpairsClicksRemaining
    private val chronomatronFutureClicks = settings.chronomatronFutureClicks
    private val ultrasequencerFutureClicks = settings.ultrasequencerFutureClicks
    private val preventMisclicks = settings.preventMisclicks
    private val serumsConsumed = settings.serumsConsumed
    private val size = settings.size
    private val roundness = settings.roundness
    private val slotGap = settings.slotGap
    private val completeSounds = settings.completeSounds
    private val maxClickAlert = settings.maxClickAlert
    private val completeSound = settings.completeSound
    private val completeSoundPitch = settings.completeSoundPitch
    private val completeSoundVolume = settings.completeSoundVolume
    private val panelColor = settings.panelColor
    private val textColor = settings.textColor
    private val currentColor = settings.currentColor
    private val nextColor = settings.nextColor
    private val nextNextColor = settings.nextNextColor
    private val nextNextNextColor = settings.nextNextNextColor
    private val discoveredColor = settings.discoveredColor
    private val unknownColor = settings.unknownColor
    private var maxAlertedSessionGeneration = Long.MIN_VALUE
    private var superpairsFeedbackSlot = -1
    private var superpairsFeedbackStartedNanos = 0L
    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var reportedDiagnosticFingerprint = ""
    private var suppressedDiagnosticChanges = 0L
    private var diagnosticClickTick = Long.MIN_VALUE
    private var diagnosticClicksThisTick = 0
    private var suppressedDiagnosticClicks = 0L

    init {
        group(
            SettingGroup("Experiments", settings.chronomatron, settings.ultrasequencer, settings.superpairs),
            SettingGroup("Display", settings.hideTextAboveBoard, settings.showSuperpairsClicksRemaining),
            SettingGroup("Preview", settings.chronomatronFutureClicks, settings.ultrasequencerFutureClicks),
            SettingGroup("Protection", settings.preventMisclicks, settings.serumsConsumed),
            SettingGroup("Appearance", settings.size, settings.roundness, settings.slotGap),
            SettingGroup(
                "Sounds", settings.completeSounds, settings.maxClickAlert, settings.completeSound,
                settings.completeSoundPitch, settings.completeSoundVolume,
            ),
            SettingGroup(
                "Colors", settings.panelColor, settings.textColor, settings.currentColor, settings.nextColor,
                settings.nextNextColor, settings.nextNextNextColor, settings.discoveredColor, settings.unknownColor,
            ),
        )
    }

    override fun configName(): String = "Solver"

    /** Whether the adapter should claim a recognized experiment of the requested type. */
    fun supports(type: ExperimentType): Boolean = when (type) {
        ExperimentType.CHRONOMATRON -> chronomatron.value()
        ExperimentType.ULTRASEQUENCER -> ultrasequencer.value()
        ExperimentType.SUPERPAIRS -> superpairs.value()
    }

    /** The pure engine shared with the menu controller. */
    fun engine(): ExperimentSolverEngine = ExperimentController.INSTANCE.engine()

    private fun solverView(): SolverView = ExperimentController.INSTANCE.view()

    private fun session(): Session? = ExperimentController.INSTANCE.session()

    fun configuration(): ExperimentSolverEngine.Configuration =
        ExperimentSolverEngine.Configuration(serumsConsumed.intValue())

    fun chronomatron(): BooleanSetting = chronomatron

    fun ultrasequencer(): BooleanSetting = ultrasequencer

    fun superpairs(): BooleanSetting = superpairs

    fun chronomatronFutureClicks(): NumberSetting = chronomatronFutureClicks

    fun ultrasequencerFutureClicks(): NumberSetting = ultrasequencerFutureClicks

    override fun isSettingVisible(setting: Setting): Boolean {
        val maximumFutureClicks = max(chronomatronFutureClicks.intValue(), ultrasequencerFutureClicks.intValue())
        if (setting === nextColor) return maximumFutureClicks >= 1
        if (setting === nextNextColor) return maximumFutureClicks >= 2
        if (setting === nextNextNextColor) return maximumFutureClicks >= 3
        return true
    }

    fun serumsConsumed(): NumberSetting = serumsConsumed

    /** Called once per client tick, before the screen is drawn. */
    fun tick() {
        ExperimentController.INSTANCE.tick()
        alertAtMaxClickMilestone()
        if (isEnabled()) {
            if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
            reportDiagnosticStatus()
        }
    }

    private fun alertAtMaxClickMilestone() {
        val activeSession = session() ?: return
        if (!isEnabled() || !supports(activeSession.type)) return
        val generation = ExperimentController.INSTANCE.sessionGeneration()
        if (displayPhase() == Phase.MAX_CLICKS && maxAlertedSessionGeneration != generation) {
            maxAlertedSessionGeneration = generation
            if (shouldPlayMaxClickMilestoneSound(maxClickAlert.value(), completeSounds.value())) {
                playSound(completeSound, completeSoundPitch, completeSoundVolume)
            }
        }
    }

    /** Resets remembered stacks when the container is closed or replaced. */
    fun onScreenClosed() {
        ExperimentController.INSTANCE.onScreenClosed()
        superpairsFeedbackSlot = -1
        maxAlertedSessionGeneration = Long.MIN_VALUE
    }

    /** Attach before the opening inventory packets can arrive. */
    fun onScreenOpened(screen: AbstractContainerScreen<*>) {
        ExperimentController.INSTANCE.onScreenOpened(screen)
    }

    /** Called after a server menu mutation, before listeners are broadcast. */
    fun onContainerUpdated(menu: AbstractContainerMenu) {
        ExperimentController.INSTANCE.onContainerUpdated(menu, -1)
    }

    fun onContainerUpdated(menu: AbstractContainerMenu, updatedSlotId: Int) {
        ExperimentController.INSTANCE.onContainerUpdated(menu, updatedSlotId)
    }

    fun observesMenu(menu: AbstractContainerMenu): Boolean = ExperimentController.INSTANCE.observesMenu(menu)

    /**
     * Draws the complete replacement surface. Returning true tells the mixin to skip vanilla container
     * extraction for this frame.
     */
    fun renderCustomScreen(
        screen: AbstractContainerScreen<*>,
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ): Boolean {
        if (!ownsScreen(screen)) return false
        observe(screen)
        val solverView = solverView()
        val width = max(1, min(screen.width, max(1, graphics.guiWidth())))
        val height = max(1, min(screen.height, max(1, graphics.guiHeight())))
        val layout = layout(screen, width, height)

        // The rounded board and compact status header form one centered surface.
        graphics.enableScissor(0, 0, width, height)
        GuiTheme.roundedRect(
            graphics, layout.panelX, layout.panelY, layout.panelWidth, layout.panelHeight,
            layout.radius, panelColor.argb(),
        )
        // Item rendering and text are not guaranteed to respect the tile rectangle. Keep every
        // translated child inside the replacement surface, including during a small-screen fit.
        val panelLeft = max(0, min(width, layout.panelX))
        val panelTop = max(0, min(height, layout.panelY))
        val panelRight = max(panelLeft, min(width, layout.panelX + layout.panelWidth))
        val panelBottom = max(panelTop, min(height, layout.panelY + layout.panelHeight))
        graphics.enableScissor(panelLeft, panelTop, panelRight, panelBottom)

        val font = Minecraft.getInstance().font
        if (infoHeight() > 0) renderStatus(graphics, font, layout, screen)
        if (layout.slotLayouts.isEmpty() && solverView.type() != ExperimentType.SUPERPAIRS) {
            val phase = displayPhase()
            val label = if (phase == Phase.SOLVE) Phase.WAITING.label else phase.label
            graphics.centeredText(
                font, label, layout.panelX + layout.panelWidth / 2,
                layout.boardY + max(0, (layout.panelHeight - infoHeight() - font.lineHeight) / 2),
                textColor.argb(),
            )
        }

        for (slotLayout in layout.slotLayouts) {
            val visual = slotLayout.visual
            val radius = min(layout.radius, slotLayout.size / 2)
            if (visual.cardState != null) {
                var fill = slotColor(visual)
                var border = if (visual.cardState == SuperpairsBoard.CardState.MATCH && visual.pairIndex >= 0) {
                    SuperpairsPairColors.border(visual.pairIndex)
                } else {
                    superpairBorderColor(visual.cardState)
                }
                val feedback = superpairsFeedback(visual.boardSlot.slot.index)
                if (feedback > 0.0f) {
                    // Server reveal can arrive a frame later; this flash acknowledges the click locally.
                    fill = GuiTheme.lerpColor(fill, 0xFFFFFFFF.toInt(), 0.18f * feedback)
                    border = GuiTheme.lerpColor(border, 0xFFFFFFFF.toInt(), feedback)
                }
                GuiTheme.roundedRectBordered(
                    graphics, slotLayout.x, slotLayout.y, slotLayout.size, slotLayout.size,
                    radius, fill, fill, border,
                )
            } else {
                GuiTheme.roundedRect(
                    graphics, slotLayout.x, slotLayout.y, slotLayout.size, slotLayout.size,
                    radius, slotColor(visual),
                )
            }

            if (solverView.type() == ExperimentType.SUPERPAIRS) {
                renderSuperpairsContent(graphics, font, slotLayout, mouseX, mouseY)
            } else if (!ChatStringSemantics.isBlank(visual.orderLabel)) {
                val orderLabel = fitText(font, visual.orderLabel, max(1, slotLayout.size - 4))
                graphics.centeredText(
                    font, orderLabel, slotLayout.x + slotLayout.size / 2,
                    slotLayout.y + max(0, (slotLayout.size - font.lineHeight) / 2), textColor.argb(),
                )
            }
        }
        graphics.disableScissor()
        graphics.disableScissor()
        return true
    }

    private fun infoHeight(): Int = if (hideTextAboveBoard.value()) 0 else INFO_HEIGHT

    private fun renderStatus(
        graphics: GuiGraphicsExtractor,
        font: Font,
        layout: Layout,
        screen: AbstractContainerScreen<*>,
    ) {
        val lines = statusLines(screen)
        if (lines.isEmpty()) return
        val x = layout.panelX + 7
        val available = max(1, layout.panelWidth - 14)
        var y = layout.panelY + 5
        for (i in 0 until min(3, lines.size)) {
            val line = fitText(font, lines[i], available)
            if (!ChatStringSemantics.isBlank(line)) {
                val color = if (i == 0) textColor.argb() else GuiTheme.withOpacity(textColor.argb(), 0.78f)
                graphics.text(font, line, x, y, color)
            }
            y += font.lineHeight + 1
        }
        val dividerY = min(layout.panelY + infoHeight() - 3, layout.panelY + max(0, layout.panelHeight - 2))
        val inset = min(6, max(0, layout.panelWidth / 2))
        graphics.fill(
            layout.panelX + inset, dividerY,
            layout.panelX + max(1, layout.panelWidth - inset), dividerY + 1,
            GuiTheme.withOpacity(unknownColor.argb(), 0.45f),
        )
    }

    private fun statusLines(screen: AbstractContainerScreen<*>): List<String> {
        val session = session() ?: return emptyList()
        val solverView = solverView()
        val typeName = solverView.type()?.displayName() ?: return emptyList()
        var phase = displayPhase().label
        if (ChatStringSemantics.isBlank(phase)) phase = "SOLVE"
        var instruction = session.instruction
        if (ChatStringSemantics.isBlank(instruction)) instruction = phase
        val budget = if (showSuperpairsClicksRemaining.value() && session.serverClicksRemaining >= 0) {
            " • Budget " + session.serverClicksRemaining
        } else {
            ""
        }

        if (solverView.type() == ExperimentType.SUPERPAIRS) {
            val board = solverView.superpairs()
            val selected = if (board.selectedSlot() < 0 || cardPosition(board.selectedSlot(), board) <= 0) {
                "none"
            } else {
                "#" + cardPosition(board.selectedSlot(), board)
            }
            val waiting = if (board.waitingForReveal()) " • revealing" else ""
            val totalCards = board.cards().size
            val cardsLeft = max(0, totalCards - board.resolvedCount())
            return listOf(
                typeName + " • " + phase,
                "Status: " + instruction + " • Selected: " + selected + waiting,
                "Collected: " + board.collectedCount() / 2 + "/" + board.totalPairs() + " pairs • " +
                    cardsLeft + " cards left • Discovered: " + board.discoveredCount() + "/" + totalCards + budget,
            )
        }

        val length = solverView.sequence().size
        val progress = max(0, min(length, solverView.visualIndex()))
        val lengthText = if (length > 0) length.toString() else "?"
        val progressText = if (length > 0) progress.toString() + "/" + lengthText else "0/?"
        var remainingText = if (length > 0) max(0, length - progress).toString() else "?"
        var next = solverView.current().map { "#" + (it.index() + 1) }.orElse("none")
        var autoProgress = ""
        if (solverView.type() == ExperimentType.ULTRASEQUENCER) {
            val execution = AutoExperimentsModule.INSTANCE.ultrasequencerPresentationProgress(screen, solverView)
            if (execution.isPresent) {
                val snapshot = execution.orElseThrow()
                val autoCursor = snapshot.cursor()
                autoProgress = " • Auto cursor: " + autoCursor + "/" + snapshot.sequenceLength()
                if (snapshot.dispatchPending()) {
                    autoProgress += " (dispatching)"
                    next = "#" + autoCursor
                    remainingText = (snapshot.sequenceLength() - autoCursor + 1).toString()
                } else {
                    next = if (autoCursor < snapshot.sequenceLength()) "#" + (autoCursor + 1) else "none"
                    remainingText = (snapshot.sequenceLength() - autoCursor).toString()
                    if (snapshot.paused() && autoCursor < snapshot.sequenceLength()) {
                        autoProgress += " (paused)"
                        next = "paused"
                    }
                }
            }
        }
        val finished = if (displayPhase() == Phase.MAX_CLICKS) {
            "complete"
        } else if (solverView.milestoneReached()) {
            "target reached"
        } else {
            max(0, solverView.completedRounds()).toString() + " rounds"
        }
        return listOf(
            typeName + " • " + phase,
            "Status: " + instruction,
            "Progress: " + progressText + autoProgress + " • Next: " + next + " • " +
                remainingText + " clicks left • Done: " + finished + budget,
        )
    }

    private fun renderSuperpairsContent(
        graphics: GuiGraphicsExtractor,
        font: Font,
        slotLayout: SlotLayout,
        mouseX: Int,
        mouseY: Int,
    ) {
        val boardSlot = slotLayout.visual.boardSlot
        val state = slotLayout.visual.cardState
        val stack = boardSlot.renderStack
        if (!stack.isEmpty) {
            val itemX = slotLayout.x + max(0, (slotLayout.size - 16) / 2)
            val itemY = slotLayout.y + max(1, (slotLayout.size - 20) / 2)
            graphics.item(stack, itemX, itemY)
            graphics.itemDecorations(font, stack, itemX, itemY)
            if (slotLayout.contains(mouseX.toDouble(), mouseY.toDouble())) {
                graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY)
            }
        } else if (state == SuperpairsBoard.CardState.SELECTED) {
            // Show immediate local acknowledgement while the real item is still in flight.
            graphics.centeredText(
                font, "?", slotLayout.x + slotLayout.size / 2,
                slotLayout.y + max(0, (slotLayout.size - font.lineHeight) / 2), superpairTextColor(state),
            )
        }
        if (!ChatStringSemantics.isBlank(boardSlot.name) && slotLayout.size >= 34) {
            val label = font.plainSubstrByWidth(boardSlot.name, max(1, slotLayout.size - 4))
            graphics.centeredText(
                font, label, slotLayout.x + slotLayout.size / 2,
                slotLayout.y + slotLayout.size - font.lineHeight, superpairTextColor(state!!),
            )
        }
        if (state == SuperpairsBoard.CardState.RESOLVED) {
            val marker = "✓"
            graphics.text(
                font, marker, slotLayout.x + slotLayout.size - font.width(marker) - 3,
                slotLayout.y + 3, superpairTextColor(state),
            )
        }
    }

    /**
     * Handles a click on the replacement surface. The dispatcher is the mixin's invoker for
     * vanilla's protected slotClicked method; this module never constructs or sends a packet.
     */
    fun handleCustomClick(
        screen: AbstractContainerScreen<*>?,
        event: MouseButtonEvent,
        dispatcher: SlotClickDispatcher,
    ): Boolean {
        if (!ownsScreen(screen)) return false
        val activeScreen = screen ?: return false
        observe(activeScreen)
        val layout = layout(activeScreen, max(1, activeScreen.width), max(1, activeScreen.height))
        if (!layout.contains(event.x(), event.y())) {
            // Preserve vanilla's normal close behavior outside the replacement surface.
            return false
        }
        var hit: SlotLayout? = null
        for (candidate in layout.slotLayouts) {
            if (candidate.contains(event.x(), event.y())) {
                hit = candidate
                break
            }
        }
        if (hit != null) AutoExperimentsModule.INSTANCE.pauseAfterManualInput(activeScreen)
        val clickedSlotId = hit?.visual?.boardSlot?.slot?.index ?: -1
        // Experiment buttons are left-click controls. Consume other buttons over the hidden menu.
        if (event.button() != 0) {
            reportDiagnosticClick(DiagnosticClickOutcome.NON_PRIMARY_CONSUMED, clickedSlotId, event.button())
            return true
        }
        val selected = hit ?: run {
            reportDiagnosticClick(DiagnosticClickOutcome.EMPTY_PANEL_CONSUMED, -1, event.button())
            return true
        }
        if (!accepts(selected.visual)) {
            reportDiagnosticClick(DiagnosticClickOutcome.PROTECTION_REJECTED, clickedSlotId, event.button())
            return true
        }

        val boardSlot = selected.visual.boardSlot
        if (solverView().type() == ExperimentType.SUPERPAIRS) {
            if (!ExperimentController.INSTANCE.dispatchSuperpairsClick(
                    activeScreen, boardSlot.slot.index, event.button(), dispatcher,
                )
            ) {
                reportDiagnosticClick(DiagnosticClickOutcome.DISPATCH_REJECTED, clickedSlotId, event.button())
                return true
            }
            superpairsFeedbackSlot = boardSlot.slot.index
            superpairsFeedbackStartedNanos = System.nanoTime()
        } else if (!ExperimentController.INSTANCE.dispatchSequenceClick(
                activeScreen, boardSlot.slot.index, dispatcher,
            )
        ) {
            reportDiagnosticClick(DiagnosticClickOutcome.DISPATCH_REJECTED, clickedSlotId, event.button())
            return true
        }
        reportDiagnosticClick(DiagnosticClickOutcome.VANILLA_DISPATCHED, clickedSlotId, event.button())
        return true
    }

    private fun accepts(visual: SlotVisual): Boolean {
        val solverView = solverView()
        if (solverView.phase() != ExperimentPhase.SOLVE) return false
        if (displayPhase() != Phase.SOLVE) return false
        if (!preventMisclicks.value()) return true
        if (solverView.type() == ExperimentType.SUPERPAIRS) {
            return visual.cardState != SuperpairsBoard.CardState.RESOLVED
        }
        // Future cells are informative. With protection enabled only the actual next click is legal.
        return visual.priority == Priority.CURRENT
    }

    private fun observe(screen: AbstractContainerScreen<*>) {
        ExperimentController.INSTANCE.observeScreen(screen)
        alertAtMaxClickMilestone()
    }

    private fun displayPhase(): Phase {
        val session = session() ?: return Phase.MEMORIZE
        val solverView = solverView()
        val milestoneReached = (solverView.phase() == ExperimentPhase.SOLVE ||
            solverView.phase() == ExperimentPhase.ROUND_COMPLETE) && solverView.milestoneReached()
        if (session.phase == Phase.MAX_CLICKS || solverView.phase() == ExperimentPhase.COMPLETE ||
            milestoneReached
        ) return Phase.MAX_CLICKS
        return when (solverView.phase()) {
            ExperimentPhase.MEMORIZE -> Phase.MEMORIZE
            ExperimentPhase.WAITING -> Phase.WAITING
            ExperimentPhase.SOLVE -> Phase.SOLVE
            ExperimentPhase.ROUND_COMPLETE -> Phase.ROUND_COMPLETE
            else -> session.phase
        }
    }

    private fun playSound(sound: TextSetting, pitch: NumberSetting, volume: NumberSetting) {
        ModuleSounds.play(ExperimentSnapshotLogic.normalizeText(sound.value()), volume.value(), pitch.value())
    }

    private fun slotColor(visual: SlotVisual): Int {
        visual.cardState?.let {
            return when (it) {
                SuperpairsBoard.CardState.SELECTED -> currentColor.argb()
                SuperpairsBoard.CardState.MATCH ->
                    if (visual.pairIndex >= 0) SuperpairsPairColors.color(visual.pairIndex) else SUPERPAIRS_MATCH_COLOR
                SuperpairsBoard.CardState.RESOLVED -> SUPERPAIRS_COLLECTED_COLOR
                SuperpairsBoard.CardState.KNOWN -> SUPERPAIRS_KNOWN_COLOR
                SuperpairsBoard.CardState.UNKNOWN -> SUPERPAIRS_UNKNOWN_COLOR
            }
        }
        return when (visual.priority) {
            Priority.CURRENT -> currentColor.argb()
            Priority.NEXT -> nextColor.argb()
            Priority.NEXT_NEXT -> nextNextColor.argb()
            Priority.NEXT_NEXT_NEXT -> nextNextNextColor.argb()
            Priority.NONE -> unknownColor.argb()
        }
    }

    private fun superpairsFeedback(slotId: Int): Float {
        if (superpairsFeedbackSlot != slotId) return 0.0f
        val elapsed = System.nanoTime() - superpairsFeedbackStartedNanos
        if (elapsed <= 0L) return 1.0f
        if (elapsed >= SUPERPAIRS_CLICK_FEEDBACK_NANOS) {
            superpairsFeedbackSlot = -1
            return 0.0f
        }
        return 1.0f - elapsed.toFloat() / SUPERPAIRS_CLICK_FEEDBACK_NANOS.toFloat()
    }

    protected override fun onEnable() {
        diagnosticTick = 0L
        lastDiagnosticTick = Long.MIN_VALUE
        reportedDiagnosticFingerprint = ""
        suppressedDiagnosticChanges = 0L
        diagnosticClickTick = Long.MIN_VALUE
        diagnosticClicksThisTick = 0
        suppressedDiagnosticClicks = 0L
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.ENCHANTING, name(), true)
    }

    protected override fun onDisable() {
        writeDiagnostic(
            "STATUS enabled=false;generation=${ExperimentController.INSTANCE.sessionGeneration().coerceAtLeast(0L)};" +
                "session=${session()?.type?.name ?: "none"};view=${solverView().type()?.name ?: "none"};" +
                "coalesced=$suppressedDiagnosticChanges;clickSuppressed=$suppressedDiagnosticClicks",
        )
        maxAlertedSessionGeneration = Long.MIN_VALUE
        superpairsFeedbackSlot = -1
        ExperimentController.INSTANCE.onOwnerDisabled()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.ENCHANTING, name(), false)
    }

    private fun reportDiagnosticStatus() {
        val fingerprint = diagnosticFingerprint()
        val changed = fingerprint != reportedDiagnosticFingerprint
        val heartbeatDue = lastDiagnosticTick == Long.MIN_VALUE ||
            diagnosticTick - lastDiagnosticTick >= DIAGNOSTIC_STATUS_INTERVAL_TICKS
        if (!changed && !heartbeatDue && suppressedDiagnosticClicks == 0L) return
        if (lastDiagnosticTick != Long.MIN_VALUE &&
            diagnosticTick - lastDiagnosticTick < DIAGNOSTIC_STATUS_MIN_INTERVAL_TICKS
        ) {
            if (changed) suppressedDiagnosticChanges = saturatingIncrement(suppressedDiagnosticChanges)
            return
        }

        writeDiagnostic(
            "STATUS $fingerprint;coalesced=$suppressedDiagnosticChanges;" +
                "clickSuppressed=$suppressedDiagnosticClicks",
        )
        reportedDiagnosticFingerprint = fingerprint
        lastDiagnosticTick = diagnosticTick
        suppressedDiagnosticChanges = 0L
        suppressedDiagnosticClicks = 0L
    }

    private fun diagnosticFingerprint(): String {
        val controller = ExperimentController.INSTANCE
        val currentScreen = Minecraft.getInstance().gui.screen()
        val container = currentScreen as? AbstractContainerScreen<*>
        val screenType = container?.let { ExperimentType.fromTitle(it.title.string).orElse(null) }
        val screenTier = container?.let {
            ExperimentTier.fromTitle(it.title.string).orElse(ExperimentTier.UNKNOWN)
        } ?: ExperimentTier.UNKNOWN
        val screenKind = when {
            currentScreen == null -> "none"
            container == null -> "other"
            else -> "container"
        }
        val activeSession = session()
        val view = solverView()
        val auto = AutoExperimentsModule.INSTANCE
        val solverOwnsScreen = screenType != null && supports(screenType)
        val autoOwnsScreen = screenType != null && auto.isEnabled() && auto.supports(screenType)
        val sessionObserved = activeSession?.attachedMenu?.let { controller.observesMenu(it) } ?: false
        val board = view.superpairs()
        val sequenceLength = view.sequence().size.coerceIn(0, MAX_DIAGNOSTIC_BOARD_SLOTS)
        val boardSlots = activeSession?.slots?.size?.coerceIn(0, MAX_DIAGNOSTIC_BOARD_SLOTS) ?: 0
        val clicksRemaining = activeSession?.serverClicksRemaining?.coerceIn(-1, MAX_DIAGNOSTIC_VALUE) ?: -1
        val selectedSlot = board.selectedSlot().coerceIn(-1, MAX_DIAGNOSTIC_VALUE)
        val awaitingRevealSlot = board.awaitingRevealSlot().coerceIn(-1, MAX_DIAGNOSTIC_VALUE)

        return buildString {
            append("enabled=true;screen=$screenKind;type=${screenType?.name ?: "none"};tier=${screenTier.name}")
            append(";solverOwns=$solverOwnsScreen;autoOwns=$autoOwnsScreen;observed=$sessionObserved")
            append(";generation=${controller.sessionGeneration().coerceAtLeast(0L)}")
            append(";session=${activeSession?.type?.name ?: "none"}/")
            append("${activeSession?.tier?.name ?: "none"}/${activeSession?.phase?.name ?: "none"}")
            append(";view=${view.type()?.name ?: "none"}/${view.tier()?.name ?: "none"}/${view.phase()?.name ?: "none"}")
            append(";progress=${view.visualIndex().coerceIn(0, MAX_DIAGNOSTIC_VALUE)}/")
            append("${view.authoritativeIndex().coerceIn(0, MAX_DIAGNOSTIC_VALUE)}/")
            append("${view.predictedIndex().coerceIn(0, MAX_DIAGNOSTIC_VALUE)}/$sequenceLength")
            append(";rounds=${view.completedRounds().coerceIn(-1, MAX_DIAGNOSTIC_VALUE)}")
            append(";next=${diagnosticSequencePreview(view)}")
            append(";board=$boardSlots;clicks=$clicksRemaining")
            append(";superpairs=${board.cards().size.coerceIn(0, MAX_DIAGNOSTIC_BOARD_SLOTS)},")
            append("${board.discoveredCount().coerceIn(0, MAX_DIAGNOSTIC_BOARD_SLOTS)},")
            append("${board.resolvedCount().coerceIn(0, MAX_DIAGNOSTIC_BOARD_SLOTS)},")
            append("$selectedSlot,$awaitingRevealSlot,${board.waitingForReveal()}")
            append(";settings=${chronomatron.value()},${ultrasequencer.value()},${superpairs.value()},")
            append("guard=${preventMisclicks.value()},future=${chronomatronFutureClicks.intValue().coerceIn(0, 3)}/")
            append("${ultrasequencerFutureClicks.intValue().coerceIn(0, 3)},serums=${serumsConsumed.intValue().coerceIn(0, 3)}")
        }
    }

    private fun diagnosticSequencePreview(view: SolverView): String =
        view.upcoming(MAX_DIAGNOSTIC_PREVIEW_STEPS).take(MAX_DIAGNOSTIC_PREVIEW_STEPS).joinToString("|") { step ->
            val slots = step.slotIds().take(MAX_DIAGNOSTIC_SLOTS_PER_STEP)
                .joinToString(",") { it.coerceIn(0, MAX_DIAGNOSTIC_VALUE).toString() }
            "${step.index().coerceIn(0, MAX_DIAGNOSTIC_VALUE)}:$slots"
        }.ifBlank { "-" }

    private fun reportDiagnosticClick(outcome: DiagnosticClickOutcome, slotId: Int, button: Int) {
        if (!isEnabled()) return
        if (diagnosticClickTick != diagnosticTick) {
            diagnosticClickTick = diagnosticTick
            diagnosticClicksThisTick = 0
        }
        if (diagnosticClicksThisTick >= MAX_DIAGNOSTIC_CLICK_EVENTS_PER_TICK) {
            suppressedDiagnosticClicks = saturatingIncrement(suppressedDiagnosticClicks)
            return
        }
        diagnosticClicksThisTick++
        val type = solverView().type()?.name ?: "none"
        writeDiagnostic(
            "CLICK outcome=${outcome.name};type=$type;slot=${slotId.coerceIn(-1, MAX_DIAGNOSTIC_VALUE)};" +
                "button=${button.coerceIn(0, MAX_DIAGNOSTIC_BUTTON)}",
        )
    }

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(
            Category.ENCHANTING,
            name(),
            diagnosticTick.coerceAtLeast(0L),
            line.take(MAX_DIAGNOSTIC_LINE_LENGTH),
        )
    }

    private fun saturatingIncrement(value: Long): Long =
        if (value >= MAX_DIAGNOSTIC_SUPPRESSED_COUNT) value else value + 1L

    private fun isSupported(screen: AbstractContainerScreen<*>?): Boolean {
        if (!isRecognized(screen)) return false
        return supports(ExperimentType.fromTitle(screen!!.title.string).orElseThrow())
    }

    /** Used by both mixin extraction hooks so vanilla cannot draw the original puzzle underneath. */
    fun ownsScreen(screen: AbstractContainerScreen<*>?): Boolean = isEnabled() && isSupported(screen)

    /** Allows the mixin to distinguish intentional vanilla dispatch from click-through input. */
    fun isDispatchingCustomClick(): Boolean = ExperimentController.INSTANCE.isDispatchingCustomClick()

    private fun visibleSlotVisuals(screen: AbstractContainerScreen<*>): List<SlotVisual> {
        val session = session() ?: return emptyList()
        val solverView = solverView()
        val type = solverView.type() ?: return emptyList()
        if (type == ExperimentType.SUPERPAIRS) {
            val states = LinkedHashMap<Int, SuperpairsBoard.CardState>()
            val pairIndexes = SuperpairsPairColors.indexBySlot(solverView.superpairs())
            for (card in solverView.superpairs().cards()) {
                states[card.slotId()] = card.state() ?: SuperpairsBoard.CardState.UNKNOWN
            }
            val result = ArrayList<SlotVisual>(session.slots.size)
            for (slot in session.slots) {
                val state = states.getOrDefault(slot.slot.index, SuperpairsBoard.CardState.UNKNOWN)
                val priority = when (state) {
                    SuperpairsBoard.CardState.SELECTED -> Priority.CURRENT
                    SuperpairsBoard.CardState.MATCH -> Priority.NEXT
                    else -> Priority.NONE
                }
                result += SlotVisual(slot, priority, "", state, pairIndexes[slot.slot.index] ?: -1)
            }
            return JavaList.copyOf(result)
        }
        if (solverView.phase() != ExperimentPhase.SOLVE || displayPhase() != Phase.SOLVE) return emptyList()

        val slotsById = LinkedHashMap<Int, BoardSlot>()
        for (slot in session.slots) slotsById[slot.slot.index] = slot
        val visible = LinkedHashMap<Int, MutableSlotVisual>()
        val visibleSteps = previewSteps(type)
        val upcoming = if (type == ExperimentType.ULTRASEQUENCER) {
            ultrasequencerPreview(
                solverView,
                AutoExperimentsModule.INSTANCE.ultrasequencerPresentationProgress(screen, solverView),
                visibleSteps,
            )
        } else {
            solverView.upcoming(visibleSteps)
        }
        for ((offset, step) in upcoming.withIndex()) {
            val priority = Priority.fromOffset(offset)
            for (slotId in step.slotIds()) {
                val slot = slotsById[slotId] ?: continue
                visible.computeIfAbsent(slotId) { MutableSlotVisual(slot) }.add(step.index() + 1, priority)
            }
        }
        val result = ArrayList<SlotVisual>(visible.size)
        for (slot in session.slots) visible[slot.slot.index]?.let { result += it.immutable() }
        return JavaList.copyOf(result)
    }

    /** Number of sequence buttons drawn for a puzzle, including the current button. */
    fun previewSteps(type: ExperimentType): Int = 1 + futureClicks(type)

    private fun futureClicks(type: ExperimentType): Int = when (type) {
        ExperimentType.CHRONOMATRON -> chronomatronFutureClicks.intValue()
        ExperimentType.ULTRASEQUENCER -> ultrasequencerFutureClicks.intValue()
        ExperimentType.SUPERPAIRS -> 0
    }

    private fun layout(screen: AbstractContainerScreen<*>, availableWidth: Int, availableHeight: Int): Layout {
        val session = session()
        val width = max(1, min(screen.width, availableWidth))
        val height = max(1, min(screen.height, availableHeight))
        val type = session?.type ?: ExperimentType.ULTRASEQUENCER
        val tier = session?.tier ?: ExperimentTier.UNKNOWN
        val geometry = ExperimentBoardGeometry.forExperiment(type, tier)
        val columns = geometry.columns()
        val rows = geometry.rows()
        val logicalGap = slotGap.intValue()
        val logicalBoardWidth = columns * BASE_SLOT_SIZE + (columns - 1) * logicalGap
        val logicalBoardHeight = rows * BASE_SLOT_SIZE + (rows - 1) * logicalGap
        val logicalPanelWidth = logicalBoardWidth + BASE_PANEL_PADDING * 2
        val logicalPanelHeight = logicalBoardHeight + BASE_PANEL_PADDING * 2
        val infoHeight = infoHeight()
        val fitScale = min(
            max(1, width - SCREEN_MARGIN * 2).toFloat() / logicalPanelWidth,
            max(1, height - SCREEN_MARGIN * 2 - infoHeight).toFloat() / logicalPanelHeight,
        )
        val scale = max(0.05f, min(size.value(), fitScale))
        val panelWidth = max(1, min(width, (logicalPanelWidth * scale).roundToInt()))
        val boardHeight = (logicalPanelHeight * scale).roundToInt()
        val panelHeight = max(1, min(height, boardHeight + infoHeight))
        val panelX = max(0, (width - panelWidth) / 2)
        val panelY = max(0, (height - panelHeight) / 2)
        val boardY = panelY + min(infoHeight, max(0, panelHeight - 1))
        val slotSize = max(1, (BASE_SLOT_SIZE * scale).roundToInt())
        val radius = max(0, min((roundness.intValue() * scale).roundToInt(), min(panelWidth, panelHeight) / 2))

        val visuals = visibleSlotVisuals(screen)
        val slotLayouts = ArrayList<SlotLayout>(visuals.size)
        for (visual in visuals) {
            val slotId = visual.boardSlot.slot.index
            val column = geometry.column(slotId)
            val row = geometry.row(slotId)
            if (column < 0 || column >= columns || row < 0 || row >= rows) continue
            val x = panelX + scaledSlotOffset(column, logicalGap, scale)
            var y = panelY + scaledSlotOffset(row, logicalGap, scale)
            y += boardY - panelY
            if (x < panelX || y < panelY || x + slotSize > panelX + panelWidth ||
                y + slotSize > panelY + panelHeight
            ) continue
            slotLayouts += SlotLayout(visual, x, y, slotSize)
        }
        return Layout(panelX, panelY, panelWidth, panelHeight, boardY, radius, JavaList.copyOf(slotLayouts))
    }

    fun interface SlotClickDispatcher {
        fun dispatch(slot: Slot, slotId: Int, button: Int, input: ContainerInput)
    }

    enum class Phase(val label: String) {
        MEMORIZE("MEMORIZE"),
        WAITING("WAITING"),
        SOLVE(""),
        ROUND_COMPLETE("ROUND COMPLETE"),
        MAX_CLICKS("MAX CLICKS"),
    }

    private enum class DiagnosticClickOutcome {
        NON_PRIMARY_CONSUMED,
        EMPTY_PANEL_CONSUMED,
        PROTECTION_REJECTED,
        DISPATCH_REJECTED,
        VANILLA_DISPATCHED,
    }

    private enum class Priority(val rank: Int) {
        CURRENT(0),
        NEXT(1),
        NEXT_NEXT(2),
        NEXT_NEXT_NEXT(3),
        NONE(4);

        companion object {
            fun fromOffset(offset: Int): Priority = when (offset) {
                0 -> CURRENT
                1 -> NEXT
                2 -> NEXT_NEXT
                else -> NEXT_NEXT_NEXT
            }
        }
    }

    class Session(type: ExperimentType, tier: ExperimentTier, menuId: Int) {
        @JvmField
        val type: ExperimentType = type
        @JvmField
        val tier: ExperimentTier = tier
        @JvmField
        val menuId: Int = menuId

        @JvmField
        val rememberedStacks: MutableMap<Int, ItemStack> = LinkedHashMap()
        private val superpairTypes: MutableList<ItemStack> = ArrayList()
        private val superpairValues: MutableMap<Int, String> = LinkedHashMap()

        // Rendering and observation caches are invalidated separately to avoid rebuilding tooltip
        // names and immutable item-stack copies on every unchanged menu update.
        private val superpairLiveStacks: MutableMap<Int, ItemStack> = LinkedHashMap()
        private var cachedStatusStack: ItemStack = ItemStack.EMPTY
        private var cachedStatusInfo = StatusInfo("", -1)
        private var statusCacheInitialized = false
        @JvmField
        val superpairCache = SuperpairsCacheGate()
        @JvmField
        var slots: List<BoardSlot> = emptyList()
        private var renderSlotsRevision = Long.MIN_VALUE
        @JvmField
        var instruction = ""
        @JvmField
        var phase = Phase.WAITING
        @JvmField
        val slotUpdates: MutableList<SlotUpdate> = ArrayList()
        @JvmField
        var serverClicksRemaining = -1
        @JvmField
        var attachedMenu: AbstractContainerMenu? = null
        private var updateSequence = 0L

        private val containerListener = object : ContainerListener {
            override fun slotChanged(handler: AbstractContainerMenu, slotId: Int, stack: ItemStack) {
                if (slotId < 0) return
                val updateStack = stack.copy()
                // Ultrasequencer is reconstructed from the authoritative menu once per client tick.
                // Its pane animation can update many slots in one packet; queuing each setter repeats work.
                if (type == ExperimentType.ULTRASEQUENCER) return
                if (slotId == STATUS_SLOT) {
                    if (type == ExperimentType.SUPERPAIRS) {
                        // The next tick reads the cached status stack once.
                        superpairCache.invalidateObservation()
                        return
                    }
                    enqueueSlotUpdate(
                        handler, SlotUpdate.Kind.STATUS, slotId, updateStack,
                        if (updateStack.isEmpty) "" else updateStack.getHoverName().getString(),
                    )
                    return
                }
                if (!ExperimentBoardGeometry.forExperiment(type, tier).containsSlot(slotId)) return
                if (type == ExperimentType.SUPERPAIRS) {
                    superpairCache.invalidateRender()
                    superpairCache.invalidateObservation()
                    // Late reveals can belong to earlier cards, so each revealed slot is remembered
                    // independently from the most recent local click.
                    return
                }
                // Capture the immutable frame at the callback boundary so later updates cannot
                // corrupt an earlier observation.
                enqueueSlotUpdate(handler, SlotUpdate.Kind.BOARD, slotId, updateStack, instruction(handler))
            }

            override fun dataChanged(handler: AbstractContainerMenu, property: Int, value: Int) = Unit
        }

        private fun enqueueSlotUpdate(
            handler: AbstractContainerMenu,
            kind: SlotUpdate.Kind,
            slotId: Int,
            stack: ItemStack,
            status: String,
        ) {
            val board = LinkedHashMap<Int?, ItemStack?>()
            for (slot in handler.slots) {
                if (isExperimentBoardSlot(type, tier, slot)) board[slot.index] = slot.getItem().copy()
            }
            if (kind == SlotUpdate.Kind.BOARD &&
                isExperimentBoardSlot(type, tier, findSlot(handler, slotId))
            ) {
                board[slotId] = stack.copy()
            }
            slotUpdates += SlotUpdate(kind, slotId, stack, status, ++updateSequence, board)
        }

        fun observe(screen: AbstractContainerScreen<*>): Boolean {
            attach(screen.getMenu())
            if (type == ExperimentType.SUPERPAIRS) return observeSuperpairs(screen)
            val menuSlots = ArrayList<Slot>()
            for (slot in screen.getMenu().slots) {
                if (slot.container !is Inventory && isBoardSlot(type, tier, slot)) menuSlots += slot
            }
            val statusInfo = status(screen.getMenu())
            instruction = statusInfo.text
            if (statusInfo.clicksRemaining >= 0) serverClicksRemaining = statusInfo.clicksRemaining
            menuSlots.sortWith(Comparator.comparingInt { it.index })
            captureSuperpairReveal(screen)

            val previousPhase = phase
            phase = phaseFor(type, instruction, phase)
            if (type == ExperimentType.SUPERPAIRS && previousPhase == Phase.MAX_CLICKS &&
                phase != Phase.MAX_CLICKS
            ) {
                resetSuperpairMemory()
            }
            // Solver memory is updated only by the queued listener frames drained after observation.
            if (slots.isEmpty() || renderSlotsRevision != updateSequence) {
                slots = JavaList.copyOf(boardSlots(menuSlots))
            }
            return true
        }

        private fun observeSuperpairs(screen: AbstractContainerScreen<*>): Boolean {
            val statusInfo = cachedStatus(screen.getMenu())
            val previousInstruction = instruction
            val previousClicks = serverClicksRemaining
            instruction = statusInfo.text
            if (statusInfo.clicksRemaining >= 0) serverClicksRemaining = statusInfo.clicksRemaining
            if (previousInstruction != instruction || previousClicks != serverClicksRemaining) {
                superpairCache.invalidateObservation()
            }

            val previousPhase = phase
            phase = phaseFor(type, instruction, phase)
            if (previousPhase == Phase.MAX_CLICKS && phase != Phase.MAX_CLICKS) resetSuperpairMemory()
            refreshRenderSlots(screen)
            return true
        }

        fun refreshRenderSlots(screen: AbstractContainerScreen<*>) {
            if (type == ExperimentType.SUPERPAIRS) {
                captureSuperpairReveal(screen)
                if (superpairLiveBoardChanged(screen)) superpairCache.invalidateRender()
                if (!superpairCache.consumeRenderDirty()) return
                val menuSlots = ArrayList<Slot>()
                for (slot in screen.getMenu().slots) {
                    if (isExperimentBoardSlot(type, tier, slot)) menuSlots += slot
                }
                menuSlots.sortWith(Comparator.comparingInt { it.index })
                slots = JavaList.copyOf(boardSlots(menuSlots))
                superpairLiveStacks.clear()
                for (slot in menuSlots) superpairLiveStacks[slot.index] = slot.getItem().copy()
                return
            }
            if (slots.isNotEmpty() && renderSlotsRevision == updateSequence) return
            captureSuperpairReveal(screen)
            val menuSlots = ArrayList<Slot>()
            for (slot in screen.getMenu().slots) {
                if (slot.container !is Inventory && isBoardSlot(type, tier, slot)) menuSlots += slot
            }
            menuSlots.sortWith(Comparator.comparingInt { it.index })
            slots = JavaList.copyOf(boardSlots(menuSlots))
            renderSlotsRevision = updateSequence
        }

        private fun boardSlots(menuSlots: List<Slot>): List<BoardSlot> {
            val result = ArrayList<BoardSlot>(menuSlots.size)
            for (slot in menuSlots) {
                val visible = slot.getItem()
                val hidden = type == ExperimentType.SUPERPAIRS && isHiddenCard(visible)
                val stored = rememberedStacks[slot.index]
                val renderStack = when {
                    type != ExperimentType.SUPERPAIRS -> visible.copy()
                    (hidden || visible.isEmpty) && stored != null -> stored.copy()
                    hidden -> ItemStack.EMPTY
                    else -> visible.copy()
                }
                val name = if (type == ExperimentType.SUPERPAIRS && !renderStack.isEmpty) {
                    renderStack.getHoverName().getString()
                } else {
                    ""
                }
                result += BoardSlot(slot, renderStack, name)
            }
            return JavaList.copyOf(result)
        }

        fun attach(menu: AbstractContainerMenu) {
            if (attachedMenu === menu) return
            attachedMenu?.removeSlotListener(containerListener)
            attachedMenu = menu
            menu.addSlotListener(containerListener)
        }

        fun close() {
            attachedMenu?.removeSlotListener(containerListener)
            attachedMenu = null
            slotUpdates.clear()
            superpairLiveStacks.clear()
        }

        private fun resetSuperpairMemory() {
            rememberedStacks.clear()
            superpairTypes.clear()
            superpairValues.clear()
            superpairLiveStacks.clear()
            superpairCache.invalidateRender()
        }

        fun takeSuperpairsObservationDirty(): Boolean = superpairCache.consumeObservationDirty()

        fun drainSlotUpdates(): List<SlotUpdate> {
            if (slotUpdates.isEmpty()) return emptyList()
            val updates = JavaList.copyOf(slotUpdates)
            slotUpdates.clear()
            return updates
        }

        fun snapshot(screen: AbstractContainerScreen<*>, predictPending: Boolean): ExperimentSnapshot =
            snapshot(screen, predictPending, null)

        @Suppress("UNUSED_PARAMETER")
        fun snapshot(
            screen: AbstractContainerScreen<*>,
            ignoredPredictPending: Boolean,
            update: SlotUpdate?,
        ): ExperimentSnapshot {
            if (type == ExperimentType.SUPERPAIRS && update != null && update.kind == SlotUpdate.Kind.BOARD) {
                for ((slotId, stack) in update.boardStacks) {
                    if (!isHiddenCard(stack)) rememberSuperpairStack(slotId, stack)
                }
            }
            val cells = ArrayList<ExperimentCell>(slots.size)
            var paneColor: String? = null
            for (slot in slots) {
                val captured = update?.boardStacks?.get(slot.slot.index)
                val stack = captured?.copy() ?: slot.slot.getItem().copy()
                if (type == ExperimentType.ULTRASEQUENCER && paneColor == null) {
                    paneColor = ultrasequencerPaneColor(stack)
                }
                val hidden = type == ExperimentType.SUPERPAIRS && isHiddenCard(stack)
                val revealed = !stack.isEmpty && !hidden
                val value = when (type) {
                    ExperimentType.CHRONOMATRON -> chronomatronValue(stack)
                    ExperimentType.SUPERPAIRS -> superpairValue(slot.slot.index, stack, revealed)
                    ExperimentType.ULTRASEQUENCER -> stack.getHoverName().getString()
                }
                val number = if (type == ExperimentType.ULTRASEQUENCER) {
                    ultrasequencerNumber(stack, value)
                } else {
                    parseNumber(value)
                }
                val highlighted = type == ExperimentType.CHRONOMATRON && !stack.isEmpty && stack.hasFoil()
                // Superpairs can hide unmatched cards again; preserve their remembered identity.
                val removed = type != ExperimentType.SUPERPAIRS && stack.isEmpty
                cells += ExperimentCell(slot.slot.index, value, number, revealed, highlighted, removed)
            }
            val status = when {
                update?.kind == SlotUpdate.Kind.STATUS -> update.status
                update != null && !ChatStringSemantics.isBlank(update.status) -> update.status
                else -> instruction
            }
            val snapshotRevision = update?.sequence ?: updateSequence
            return ExperimentSnapshot(
                screen.getTitle().getString(), status, cells, paneColor, snapshotRevision,
                update?.kind == SlotUpdate.Kind.STATUS,
            )
        }

        private fun superpairValue(slotId: Int, stack: ItemStack, revealed: Boolean): String? {
            var stored = rememberedStacks[slotId]
            if (revealed) {
                rememberSuperpairStack(slotId, stack)
                stored = rememberedStacks[slotId]
            }
            val identity = stored ?: return null
            return superpairValues.getOrPut(slotId) { superpairKey(identity) }
        }

        private fun captureSuperpairReveal(screen: AbstractContainerScreen<*>) {
            if (type != ExperimentType.SUPERPAIRS) return
            for (slot in screen.getMenu().slots) {
                if (!isExperimentBoardSlot(type, tier, slot)) continue
                val stack = slot.getItem()
                if (!isHiddenCard(stack)) rememberSuperpairStack(slot.index, stack)
            }
        }

        private fun rememberSuperpairStack(slotId: Int, stack: ItemStack?) {
            if (slotId < 0 || stack == null || isHiddenCard(stack)) return
            val copy = stack.copy()
            if (!rememberedStacks.containsKey(slotId)) {
                rememberedStacks[slotId] = copy
                superpairCache.invalidateRender()
            }
            superpairValues.getOrPut(slotId) { superpairKey(copy) }
        }

        private fun superpairKey(stack: ItemStack): String {
            for (index in superpairTypes.indices) {
                if (ItemStack.matches(superpairTypes[index], stack)) return "pair-" + index
            }
            superpairTypes += stack.copy()
            return "pair-" + (superpairTypes.size - 1)
        }

        private fun cachedStatus(menu: AbstractContainerMenu): StatusInfo {
            val current = statusStack(menu)
            if (!statusCacheInitialized || !sameStack(cachedStatusStack, current)) {
                cachedStatusStack = current.copy()
                cachedStatusInfo = status(menu)
                statusCacheInitialized = true
            }
            return cachedStatusInfo
        }

        private fun superpairLiveBoardChanged(screen: AbstractContainerScreen<*>): Boolean {
            var boardCount = 0
            for (slot in screen.getMenu().slots) {
                if (!isExperimentBoardSlot(type, tier, slot)) continue
                boardCount++
                val previous = superpairLiveStacks[slot.index]
                if (previous == null || !sameStack(previous, slot.getItem())) return true
            }
            return boardCount != superpairLiveStacks.size
        }

        private class StatusInfo(text: String?, clicksRemaining: Int) {
            val text: String = ExperimentSnapshotLogic.normalizeText(text)
            val clicksRemaining: Int = max(-1, clicksRemaining)
        }

        class SlotUpdate(
            kind: Kind?,
            slotId: Int,
            stack: ItemStack?,
            status: String?,
            sequence: Long,
            boardStacks: Map<Int?, ItemStack?>?,
        ) {
            enum class Kind {
                BOARD,
                STATUS,
            }

            @JvmField
            val kind: Kind = requireNotNull(kind) { "kind must not be null" }
            @JvmField
            val slotId: Int = slotId
            @JvmField
            val stack: ItemStack = stack?.copy() ?: ItemStack.EMPTY
            @JvmField
            val status: String = status ?: ""
            @JvmField
            val sequence: Long = sequence
            @JvmField
            val boardStacks: Map<Int, ItemStack> = run {
                val copy = LinkedHashMap<Int, ItemStack>()
                for ((key, value) in boardStacks.orEmpty()) {
                    if (key != null && value != null) copy[key] = value.copy()
                }
                JavaMap.copyOf(copy)
            }

            // Keep the accessors emitted by the frozen Java record for package-level callers.
            fun kind(): Kind = kind

            fun slotId(): Int = slotId

            fun stack(): ItemStack = stack

            fun status(): String = status

            fun sequence(): Long = sequence

            fun boardStacks(): Map<Int, ItemStack> = boardStacks

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is SlotUpdate) return false
                return kind == other.kind && slotId == other.slotId && stack == other.stack &&
                    status == other.status && sequence == other.sequence && boardStacks == other.boardStacks
            }

            override fun hashCode(): Int {
                var result = kind.hashCode()
                result = 31 * result + slotId
                result = 31 * result + stack.hashCode()
                result = 31 * result + status.hashCode()
                result = 31 * result + sequence.hashCode()
                result = 31 * result + boardStacks.hashCode()
                return result
            }

            override fun toString(): String =
                "SlotUpdate[kind=$kind, slotId=$slotId, stack=$stack, status=$status, " +
                    "sequence=$sequence, boardStacks=$boardStacks]"

            fun chronomatronEvent(): ChronomatronEvent =
                if (kind == Kind.STATUS) {
                    ChronomatronEvent.status(status)
                } else {
                    ChronomatronEvent.board(slotId, chronomatronValue(stack), !stack.isEmpty && stack.hasFoil())
                }
        }

        companion object {
            @JvmStatic
            fun ultrasequencerPaneColor(stack: ItemStack?): String? {
                if (stack == null || stack.isEmpty) return null
                val id = BuiltInRegistries.ITEM.getKey(stack.item)
                if (id != null && id.namespace == "minecraft") {
                    val path = id.path
                    if (path.endsWith("_stained_glass_pane")) {
                        val color = path.substring(0, path.length - "_stained_glass_pane".length)
                        return if (color == "black") null else color
                    }
                }
                return null
            }

            private fun chronomatronValue(stack: ItemStack?): String? {
                if (stack == null || stack.isEmpty) return null
                val id = BuiltInRegistries.ITEM.getKey(stack.item) ?: return null
                if (id.namespace != "minecraft") return null
                return when (id.path) {
                    "red_terracotta", "red_stained_glass" -> "red"
                    "orange_terracotta", "orange_stained_glass" -> "orange"
                    "yellow_terracotta", "yellow_stained_glass" -> "yellow"
                    "lime_terracotta", "lime_stained_glass" -> "lime"
                    "green_terracotta", "green_stained_glass" -> "green"
                    "cyan_terracotta", "cyan_stained_glass" -> "cyan"
                    "light_blue_terracotta", "light_blue_stained_glass" -> "light_blue"
                    "blue_terracotta", "blue_stained_glass" -> "blue"
                    "purple_terracotta", "purple_stained_glass" -> "purple"
                    "pink_terracotta", "pink_stained_glass" -> "pink"
                    else -> null
                }
            }

            /** Superpairs shows its click budget as "Remaining Clicks: N" in slot 4 (stack count N). */
            private fun superpairsBudget(menu: AbstractContainerMenu): Int {
                for (slot in menu.slots) {
                    if (slot.index != SuperpairsClicks.SLOT || slot.getItem().isEmpty) continue
                    val stack = slot.getItem()
                    return SuperpairsClicks.parse(stripFormatting(stack.getHoverName().getString()), stack.count)
                }
                return -1
            }

            private fun status(menu: AbstractContainerMenu): StatusInfo {
                val budget = superpairsBudget(menu)
                for (slot in menu.slots) {
                    if (slot.index != STATUS_SLOT || slot.getItem().isEmpty) continue
                    val stack = slot.getItem()
                    val lines = ArrayList<String>()
                    lines += stack.getHoverName().getString()
                    val client = Minecraft.getInstance()
                    val context = if (client.level == null) {
                        Item.TooltipContext.EMPTY
                    } else {
                        Item.TooltipContext.of(client.level!!)
                    }
                    stack.getTooltipLines(context, client.player, TooltipFlag.NORMAL)
                        .forEach { lines += it.getString() }
                    return StatusInfo(lines[0], if (budget >= 0) budget else parseClicksRemaining(lines))
                }
                return StatusInfo("", budget)
            }

            private fun parseClicksRemaining(lines: List<String>): Int {
                for (line in lines) {
                    val matcher: Matcher = CLICKS_REMAINING_PATTERN.matcher(stripFormatting(line))
                    if (!matcher.find()) continue
                    for (index in 1..matcher.groupCount()) {
                        val group = matcher.group(index) ?: continue
                        try {
                            return max(0, group.toInt())
                        } catch (_: NumberFormatException) {
                            return -1
                        }
                    }
                }
                return -1
            }

            private fun stripFormatting(value: String?): String = ChatText.plain(value ?: "").trim()

            private fun parseNumber(value: String?): Int {
                if (value == null) return -1
                return ExperimentSnapshotLogic.normalizeText(value).toIntOrNull() ?: -1
            }

            private fun ultrasequencerNumber(stack: ItemStack?, value: String?): Int =
                if (stack != null && !stack.isEmpty && value?.matches(Regex("\\d+")) == true) stack.count else -1

            private fun isHiddenCard(stack: ItemStack?): Boolean {
                if (stack == null || stack.isEmpty) return true
                val id = BuiltInRegistries.ITEM.getKey(stack.item)
                // Do not treat every glass variant as hidden: revealed Superpairs can use stained glass.
                if (id != null && (id.path == "cyan_stained_glass" || id.path == "black_stained_glass_pane")) {
                    return true
                }
                val name = stack.getHoverName().getString().lowercase(Locale.ROOT)
                return name.contains("click to reveal") || name.contains("unknown") || name == "?"
            }

            private fun sameStack(first: ItemStack?, second: ItemStack?): Boolean =
                first != null && second != null && first.count == second.count && ItemStack.matches(first, second)

            private fun statusStack(menu: AbstractContainerMenu): ItemStack {
                for (slot in menu.slots) if (slot.index == STATUS_SLOT) return slot.getItem()
                return ItemStack.EMPTY
            }

            private fun instruction(menu: AbstractContainerMenu): String = status(menu).text

            private fun findSlot(menu: AbstractContainerMenu, slotId: Int): Slot? =
                menu.slots.firstOrNull { it.index == slotId }

            private fun isExperimentBoardSlot(type: ExperimentType, tier: ExperimentTier, slot: Slot?): Boolean =
                slot != null && slot.container !is Inventory && isBoardSlot(type, tier, slot)

            private fun phaseFor(type: ExperimentType, status: String, previous: Phase): Phase {
                if (!ExperimentPhase.isKnownStatus(type, status)) return previous
                return when (ExperimentPhase.detect(type, status)) {
                    ExperimentPhase.MEMORIZE -> Phase.MEMORIZE
                    ExperimentPhase.WAITING -> Phase.WAITING
                    ExperimentPhase.SOLVE -> Phase.SOLVE
                    ExperimentPhase.ROUND_COMPLETE -> Phase.ROUND_COMPLETE
                    ExperimentPhase.COMPLETE -> Phase.MAX_CLICKS
                    ExperimentPhase.IDLE -> Phase.WAITING
                }
            }
        }
    }

    /**
     * Separate render-cache invalidation from solver observation invalidation; unchanged frames
     * consume neither path.
     */
    class SuperpairsCacheGate {
        private var renderDirty = true
        private var observationDirty = true

        fun invalidateRender() {
            renderDirty = true
            observationDirty = true
        }

        fun invalidateObservation() {
            observationDirty = true
        }

        fun consumeRenderDirty(): Boolean {
            if (!renderDirty) return false
            renderDirty = false
            return true
        }

        fun consumeObservationDirty(): Boolean {
            if (!observationDirty) return false
            observationDirty = false
            return true
        }
    }

    private class MutableSlotVisual(private val boardSlot: BoardSlot) {
        private val orders = ArrayList<Int>()
        private var priority = Priority.NONE

        fun add(order: Int, candidate: Priority) {
            if (order !in orders) orders += order
            if (candidate.rank < priority.rank) priority = candidate
        }

        fun immutable(): SlotVisual {
            val label = StringBuilder()
            for (order in orders) {
                if (label.isNotEmpty()) label.append(' ')
                label.append(order)
            }
            return SlotVisual(boardSlot, priority, label.toString(), null)
        }
    }

    data class BoardSlot(val slot: Slot, val renderStack: ItemStack, val name: String)

    private data class SlotVisual(
        val boardSlot: BoardSlot,
        val priority: Priority,
        val orderLabel: String,
        val cardState: SuperpairsBoard.CardState?,
        val pairIndex: Int = -1,
    )

    private data class Layout(
        val panelX: Int,
        val panelY: Int,
        val panelWidth: Int,
        val panelHeight: Int,
        val boardY: Int,
        val radius: Int,
        val slotLayouts: List<SlotLayout>,
    ) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= panelX && mouseX < panelX + panelWidth &&
                mouseY >= panelY && mouseY < panelY + panelHeight
    }

    private data class SlotLayout(val visual: SlotVisual, val x: Int, val y: Int, val size: Int) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size
    }

    private class Settings {
        val chronomatron = BooleanSetting("Chronomatron", true)
        val ultrasequencer = BooleanSetting("Ultrasequencer", true)
        val superpairs = BooleanSetting("Superpairs", true)
        val hideTextAboveBoard = BooleanSetting("Hide Text Above Board", false)
        val showSuperpairsClicksRemaining = BooleanSetting("Show Superpairs Clicks Remaining", true)
        val chronomatronFutureClicks = NumberSetting("Chronomatron Future Clicks", 0f, 3f, 3f, true)
        val ultrasequencerFutureClicks = NumberSetting("Ultrasequencer Future Clicks", 0f, 3f, 3f, true)
        val preventMisclicks = BooleanSetting("Prevent Misclicks", true)
        val serumsConsumed = NumberSetting("Serums Consumed", 0f, 3f, 0f, true)
        // Keep the original persistence key while removing dungeon-terminal wording from the UI.
        val size = NumberSetting("Term Size", "Size", 1f, 3f, 1.4978355f)
        val roundness = NumberSetting("Roundness", 0f, 15f, 0f, true)
        val slotGap = NumberSetting("Slot Gap", 0f, 8f, 0f, true)
        val completeSounds = BooleanSetting("Complete Sounds", false)
        val maxClickAlert = BooleanSetting("Max Click Alert", true)
        val completeSound = TextSetting("Complete Sound", "entity.experience_orb.pickup", 96)
        val completeSoundPitch = NumberSetting("Complete Sound Pitch", 0.1f, 2f, 1f)
        val completeSoundVolume = NumberSetting("Complete Sound Volume", 0f, 1f, 1f)
        val panelColor = ColorSetting("Panel Color", "Background", 27, 27, 34, 240)
        val textColor = ColorSetting("Text Color", 240, 240, 244, 255)
        val currentColor = ColorSetting("Current Color", "Order 1", 255, 255, 255, 255)
        val nextColor = ColorSetting("Next Color", "Order 2", 125, 125, 125, 255)
        val nextNextColor = ColorSetting("Next Next Color", "Order 3", 61, 61, 61, 255)
        val nextNextNextColor = ColorSetting("Next Next Next Color", "Order 4", 16, 16, 16, 255)
        val discoveredColor = ColorSetting("Discovered Color", 108, 99, 255, 255)
        val unknownColor = ColorSetting("Unknown Color", 75, 75, 85, 255)

        fun all(): Array<Setting> = arrayOf(
            chronomatron, ultrasequencer, superpairs, hideTextAboveBoard, showSuperpairsClicksRemaining,
            chronomatronFutureClicks, ultrasequencerFutureClicks, preventMisclicks, serumsConsumed,
            size, roundness, slotGap, completeSounds, maxClickAlert, completeSound, completeSoundPitch,
            completeSoundVolume, panelColor, textColor, currentColor, nextColor, nextNextColor,
            nextNextNextColor, discoveredColor, unknownColor,
        )
    }

    companion object {
        private const val BASE_SLOT_SIZE = 24
        private const val BASE_PANEL_PADDING = 2
        private const val SCREEN_MARGIN = 4
        private const val INFO_HEIGHT = 46
        private const val DIAGNOSTIC_STATUS_INTERVAL_TICKS = 100L
        private const val DIAGNOSTIC_STATUS_MIN_INTERVAL_TICKS = 20L
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 512
        private const val MAX_DIAGNOSTIC_CLICK_EVENTS_PER_TICK = 16
        private const val MAX_DIAGNOSTIC_SUPPRESSED_COUNT = 1_000_000L
        private const val MAX_DIAGNOSTIC_BOARD_SLOTS = 64
        private const val MAX_DIAGNOSTIC_SLOTS_PER_STEP = 4
        private const val MAX_DIAGNOSTIC_PREVIEW_STEPS = 3
        private const val MAX_DIAGNOSTIC_VALUE = 1024
        private const val MAX_DIAGNOSTIC_BUTTON = 15
        private const val SUPERPAIRS_CLICK_FEEDBACK_NANOS = 280_000_000L
        private const val SUPERPAIRS_UNKNOWN_COLOR = -1
        private const val SUPERPAIRS_UNKNOWN_BORDER = 0xFF25252D.toInt()
        private const val SUPERPAIRS_SELECTED_BORDER = -1
        private const val SUPERPAIRS_MATCH_COLOR = 0xFFFFC107.toInt()
        private const val SUPERPAIRS_MATCH_BORDER = 0xFFFF8F00.toInt()
        private const val SUPERPAIRS_KNOWN_COLOR = 0xFFE53935.toInt()
        private const val SUPERPAIRS_KNOWN_BORDER = 0xFFFFB4B4.toInt()
        private const val SUPERPAIRS_COLLECTED_COLOR = 0xFF16A34A.toInt()
        private const val SUPERPAIRS_COLLECTED_BORDER = 0xFFB7FFCB.toInt()
        private const val STATUS_SLOT = 49
        private val CLICKS_REMAINING_PATTERN = Pattern.compile(
            "(?i)(?:(\\d+)\\s*(?:clicks?|tries?|attempts?)\\s*(?:left|remaining)" +
                "|(?:clicks?|tries?|attempts?)\\s*(?:left|remaining)\\s*[:\\-]?\\s*(\\d+)" +
                "|(?:left|remaining)\\s*[:\\-]?\\s*(\\d+)\\s*(?:clicks?|tries?|attempts?)" +
                "|(?:left|remaining)\\s*(?:clicks?|tries?|attempts?)\\s*[:\\-]?\\s*(\\d+))",
        )

        @JvmField
        val INSTANCE = ExperimentSolverModule(Settings())

        @JvmStatic
        fun scaledSlotOffset(coordinate: Int, logicalGap: Int, scale: Float): Int =
            ((BASE_PANEL_PADDING + coordinate * (BASE_SLOT_SIZE + logicalGap)) * scale).roundToInt()

        @JvmStatic
        fun shouldPlayMaxClickMilestoneSound(maxClickAlert: Boolean, completeSounds: Boolean): Boolean =
            maxClickAlert || completeSounds

        @JvmStatic
        fun isRecognized(screen: AbstractContainerScreen<*>?): Boolean =
            screen != null && ExperimentType.fromTitle(screen.getTitle().getString()).isPresent

        @JvmStatic
        fun ultrasequencerPreview(
            solverView: SolverView?,
            progress: Optional<UltrasequencerSequenceExecutor.ExecutionProgress>?,
            visibleSteps: Int,
        ): List<SequenceStep> {
            if (solverView == null || progress == null || progress.isEmpty ||
                solverView.type() != ExperimentType.ULTRASEQUENCER
            ) {
                return solverView?.upcoming(visibleSteps) ?: emptyList()
            }
            val execution = progress.orElseThrow()
            val currentSolution = UltrasequencerSequenceExecutor.Solution.from(solverView)
            if (currentSolution.isEmpty || execution.solution() != currentSolution.orElseThrow()) {
                return solverView.upcoming(visibleSteps)
            }
            val sequence = execution.solution().sequence()
            val firstIndex = if (execution.dispatchPending()) max(0, execution.cursor() - 1) else execution.cursor()
            val endIndex = min(sequence.size, firstIndex + max(0, visibleSteps))
            if (firstIndex >= endIndex) return emptyList()
            return JavaList.copyOf(sequence.subList(firstIndex, endIndex))
        }

        private fun cardPosition(slotId: Int, board: SuperpairsBoard.View): Int {
            for (index in board.cards().indices) {
                if (board.cards()[index].slotId() == slotId) return index + 1
            }
            return 0
        }

        private fun fitText(font: Font, text: String?, available: Int): String {
            if (text == null || ChatStringSemantics.isBlank(text) || available <= 0) return ""
            if (font.width(text) <= available) return text
            val ellipsis = "…"
            if (available <= font.width(ellipsis)) return ellipsis
            return font.plainSubstrByWidth(text, available - font.width(ellipsis)) + ellipsis
        }

        private fun superpairBorderColor(state: SuperpairsBoard.CardState): Int = when (state) {
            SuperpairsBoard.CardState.SELECTED -> SUPERPAIRS_SELECTED_BORDER
            SuperpairsBoard.CardState.MATCH -> SUPERPAIRS_MATCH_BORDER
            SuperpairsBoard.CardState.RESOLVED -> SUPERPAIRS_COLLECTED_BORDER
            SuperpairsBoard.CardState.KNOWN -> SUPERPAIRS_KNOWN_BORDER
            SuperpairsBoard.CardState.UNKNOWN -> SUPERPAIRS_UNKNOWN_BORDER
        }

        private fun superpairTextColor(state: SuperpairsBoard.CardState): Int = when (state) {
            SuperpairsBoard.CardState.UNKNOWN,
            SuperpairsBoard.CardState.SELECTED,
            SuperpairsBoard.CardState.MATCH,
            -> 0xFF16161B.toInt()
            SuperpairsBoard.CardState.KNOWN,
            SuperpairsBoard.CardState.RESOLVED,
            -> -1
        }

        private fun isBoardSlot(type: ExperimentType, tier: ExperimentTier, slot: Slot?): Boolean =
            slot != null && ExperimentBoardGeometry.forExperiment(type, tier).containsSlot(slot.index)

    }
}
