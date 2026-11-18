package geiler.addons.client.gui

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.MacroScriptConfigCodec
import geiler.addons.client.config.MacroStepConfigCodec
import geiler.addons.client.config.ModConfig
import geiler.addons.client.location.Island
import geiler.addons.client.macro.MacroChatTrigger
import geiler.addons.client.macro.MacroCondition
import geiler.addons.client.macro.MacroDefinition
import geiler.addons.client.macro.MacroFunction
import geiler.addons.client.macro.MacroJavaStrings
import geiler.addons.client.macro.MacroRunner
import geiler.addons.client.macro.MacroScript
import geiler.addons.client.macro.MacroStep
import geiler.addons.client.macro.MacroTreeRules
import geiler.addons.client.macro.MacroValue
import geiler.addons.client.macro.MacroVariableStore
import geiler.addons.client.macro.MacroWorldRegion
import geiler.addons.client.module.ModuleKeybindManager
import geiler.addons.client.module.impl.MacrosModule
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.utility.JavaStringSemantics
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import java.util.ArrayList
import java.util.IdentityHashMap
import java.util.Locale
import java.util.function.Consumer
import java.util.function.IntConsumer
import java.util.function.Supplier
import kotlin.math.ceil
import kotlin.math.floor
import geiler.addons.client.gui.GuiTheme.BORDER
import geiler.addons.client.gui.GuiTheme.BUTTON_BG
import geiler.addons.client.gui.GuiTheme.CARD_BG_ENABLED
import geiler.addons.client.gui.GuiTheme.CARD_BG_HOVER
import geiler.addons.client.gui.GuiTheme.CARD_BORDER
import geiler.addons.client.gui.GuiTheme.CARD_BORDER_ENABLED
import geiler.addons.client.gui.GuiTheme.CATEGORY_SELECTED
import geiler.addons.client.gui.GuiTheme.DIALOG_SHADE
import geiler.addons.client.gui.GuiTheme.MODULE_PANEL_BOTTOM
import geiler.addons.client.gui.GuiTheme.MODULE_PANEL_TOP
import geiler.addons.client.gui.GuiTheme.PANEL_BOTTOM
import geiler.addons.client.gui.GuiTheme.PANEL_HIGHLIGHT
import geiler.addons.client.gui.GuiTheme.PANEL_TOP
import geiler.addons.client.gui.GuiTheme.RADIUS
import geiler.addons.client.gui.GuiTheme.RADIUS_SMALL
import geiler.addons.client.gui.GuiTheme.SLIDER_FILL
import geiler.addons.client.gui.GuiTheme.TEXT_ERROR
import geiler.addons.client.gui.GuiTheme.TEXT_MUTED
import geiler.addons.client.gui.GuiTheme.TEXT_ON_ACCENT
import geiler.addons.client.gui.GuiTheme.TEXT_PRIMARY
import geiler.addons.client.gui.GuiTheme.TEXT_SECONDARY
import geiler.addons.client.gui.GuiTheme.TEXT_WARN
import geiler.addons.client.gui.GuiTheme.lerpColor
import geiler.addons.client.gui.GuiTheme.roundedRect
import geiler.addons.client.gui.GuiTheme.roundedRectBordered
import geiler.addons.client.gui.GuiTheme.selectionOutline
import geiler.addons.client.gui.GuiTheme.toggleSwitch
import geiler.addons.client.gui.GuiTheme.withOpacity

/** A self-contained Scratch-inspired editor for the existing macro and function models. */
class ScratchMacroEditorScreen(private val parent: Screen?, private val macro: MacroDefinition?) :
    Screen(Component.literal("Macro Editor")) {
    companion object {
        private const val MARGIN = 8
        private const val HEADER_HEIGHT = 70
        private const val FOOTER_HEIGHT = 22
        private const val GRID_STEP = 26
        private const val MAX_TREE_DEPTH = MacroTreeRules.MAX_DEPTH
        private const val MAX_CONDITION_DEPTH = 6
        private const val MAX_COMPOUND_TERMS = 12
        private const val BLOCK_HEIGHT = 27
        private const val C_MID_BAR = 20
        private const val C_BOTTOM_BAR = 16
        private const val C_INNER_MIN = 18
        private const val C_MIN_WIDTH = 110
        private const val CHIP_HEIGHT = 17
        private const val CHIP_TEXT_MAX = 180
        private const val PILL_TEXT = 0xFF575E75.toInt()
        private const val EDITOR_DIAGNOSTIC_EVENT_LIMIT = 16
        private const val EDITOR_DIAGNOSTIC_MODULE = "Macro Editor"
        private const val HAT_HEIGHT = 40
        private const val DRAG_THRESHOLD_SQUARED = 25

        private const val COLOR_MOTION = 0xFF4C97FF.toInt()
        private const val COLOR_EVENT = 0xFFFFBF00.toInt()
        private const val COLOR_CONTROL = 0xFFFFAB19.toInt()
        private const val COLOR_INPUT = 0xFF0FBD8C.toInt()
        private const val COLOR_VARIABLE = 0xFFFF8C1A.toInt()
        private const val COLOR_FUNCTION = 0xFFFF6680.toInt()
        private const val COLOR_REGION = 0xFF45C9B0.toInt()
        private const val HISTORY_LIMIT = 50

        private val EDITOR_DIAGNOSTIC_CATEGORY = geiler.addons.client.module.Category.MISCELLANEOUS
        private val PALETTE_BLOCKS: List<PaletteBlock> = listOf(
            PaletteBlock("Send message", "Send chat text; a leading / runs it as a command", Category.ACTIONS,
                Supplier { MacroStep.Chat("") }),
            PaletteBlock("Wait", "Pause for time or until a condition is met", Category.CONTROL,
                Supplier { MacroStep.Wait(250, 250) }),
            PaletteBlock("If / else", "Branch into then and else stacks", Category.CONTROL,
                Supplier { MacroStep.IfElse(MacroCondition.Always(true)) }),
            PaletteBlock("Repeat", "Repeat a nested stack a set number of times", Category.CONTROL,
                Supplier { MacroStep.Repeat(false, 3) }),
            PaletteBlock("Switch", "Choose a nested stack from a variable value", Category.CONTROL,
                Supplier { MacroStep.Switch() }),
            PaletteBlock("Comment", "Leave a note in this macro", Category.CONTROL,
                Supplier { MacroStep.Comment("Comment") }),
            PaletteBlock("Stop run", "Stop this macro and release held input", Category.CONTROL,
                Supplier { MacroStep.StopRun() }),
            PaletteBlock("Call another macro", "Run that macro’s On Call stack", Category.CONTROL,
                Supplier { MacroStep.MacroCall(-1) }),
            PaletteBlock("Press or hold key", "Capture a keyboard key in the inspector", Category.INPUT,
                Supplier { MacroStep.Key("", false, 250) }),
            PaletteBlock("Scroll", "Mouse wheel; scroll the hotbar, inventory, or menu up or down", Category.INPUT,
                Supplier { MacroStep.Scroll(MacroStep.Scroll.Direction.DOWN, 1) }),
            PaletteBlock("Block player input", "Block controls for a duration", Category.INPUT,
                Supplier { MacroStep.BlockPlayerInput(500) }),
            PaletteBlock("Set view angle", "Turn to a yaw and pitch with the Rotation Engine", Category.INPUT,
                Supplier { MacroStep.SetView(0.0f, 0.0f, 1_000) }),
            PaletteBlock("Start input block", "Block controls until stopped", Category.INPUT,
                Supplier { MacroStep.StartBlockPlayerInput() }),
            PaletteBlock("Stop input block", "Release a started input block", Category.INPUT,
                Supplier { MacroStep.StopBlockPlayerInput() }),
            PaletteBlock("Inventory click", "Click a slot directly or find an item by name", Category.INVENTORY,
                Supplier { MacroStep.InventoryClick() }),
            PaletteBlock("Show title", "Display text over the game view", Category.DISPLAY,
                Supplier { MacroStep.Title() }),
            PaletteBlock("Play sound", "Play a sound by registry id", Category.DISPLAY,
                Supplier { MacroStep.Sound("minecraft:entity.player.levelup") }),
            PaletteBlock("Close screen", "Close the currently open menu", Category.DISPLAY,
                Supplier { MacroStep.CloseScreen() }),
            PaletteBlock("Update variable", "Set or adjust a local or global variable", Category.VARIABLES,
                Supplier { MacroStep.UpdateVariable() }),
            PaletteBlock("Call a function", "Run a reusable My Block", Category.FUNCTIONS,
                Supplier { MacroStep.FunctionCall("") }),
            PaletteBlock("Switch world", "Wait for the destination world to load", Category.WORLD,
                Supplier { MacroStep.WorldSwitch(Island.HUB) })
        )

        @JvmField
        val MACRO_CATEGORY_COLORS: IntArray = intArrayOf(
            COLOR_MOTION, COLOR_EVENT, COLOR_CONTROL, COLOR_INPUT, COLOR_VARIABLE, COLOR_FUNCTION, COLOR_REGION
        )
    }

    private val hitTargets = ArrayList<HitTarget>()
    private val dropSlots = ArrayList<DropSlot>()
    private val cachedCanvasHitTargets = ArrayList<HitTarget>()
    private val cachedCanvasDropSlots = ArrayList<DropSlot>()
    private var visibleScriptPlacements: List<CanvasScriptPlacement> = emptyList()
    private val measuredStepHeights = IdentityHashMap<MacroStep, Int>()
    /** While dragging, the hovered insertion point reserves a node-sized gap so neighbours reflow instead of overlapping. */
    private var dropGapOwner: List<MacroStep>? = null
    private var dropGapIndex = -1
    private val measuredListLayouts = IdentityHashMap<List<MacroStep>, ListLayout>()
    private val measuredStepWidths = IdentityHashMap<MacroStep, IntArray>()
    private val measuredOwnWidths = IdentityHashMap<MacroStep, Int>()
    private val measuredListWidths = IdentityHashMap<List<MacroStep>, IntArray>()
    private var measuredWidthFont: Font? = null
    private var cachedPaletteLayout: PaletteLayout? = null
    private var cachedPaletteInput: String? = null
    private var cachedPaletteCategory: Category? = null

    private var tab = Tab.CODE
    private var category = Category.ACTIONS
    private var selectedScript: MacroScript? = null
    private var selectedFunction: MacroFunction? = null
    private var selectedStep: MacroStep? = null
    private var selectedOwner: MutableList<MacroStep>? = null
    private var paletteScroll = 0
    private var paletteSearch = ""
    private var inspectorScroll = 0
    private var inspectorMaxScroll = 0
    private var focusedField: String? = null
    private var fieldText = ""
    private var fieldInitialText = ""
    private var fieldCursor = 0
    private var fieldSelectionAnchor = -1
    private var fieldSelecting: FieldBinding? = null
    private var fieldSelectingBounds: GuiRect? = null
    private var fieldSelectionDragAnchor = -1
    private var focusedSetter: Consumer<String>? = null
    private var drag: DragState? = null
    private var lastLayout: Layout? = null
    private var currentMouseX = 0
    private var currentMouseY = 0
    private var dragGhostX = 0f
    private var dragGhostY = 0f
    private var dragGhostInitialized = false
    private val documentHistory: BoundedUndoHistory<DocumentSnapshot>
    private var fieldHistory: BoundedUndoHistory<FieldDraftState>? = null
    private var restoringHistory = false
    private var picker: PickerState? = null
    private var pickerSearchSelecting: PickerState? = null
    private var pickerSearchSelectionBounds: GuiRect? = null
    private var pickerSearchDragAnchor = -1
    private var globalManagerOpen = false
    private var globalNameDraft = ""
    private var globalNewType = MacroValue.Type.TEXT
    private var globalScroll = 0
    private var notice = ""
    private var noticeUntil = 0L
    private var replayingCanvasTargets = false
    private var canvasTransformScale = 1.0f
    private var canvasTransformX = 0f
    private var canvasTransformY = 0f
    private var canvasTransformClip: GuiRect? = null
    private var canvasTransformActive = false
    private var canvasTargetCacheValid = false
    private var editorDiagnosticsActive = false
    private var editorDiagnosticTick = Long.MIN_VALUE
    private var editorDiagnosticEvents = 0
    private var suppressedEditorDiagnosticEvents = 0
    private var frameMaxNodeWidth = 0
    private var frameViewportClippedNodes = 0
    private var frameStackBlocks = 0
    private var frameCBlocks = 0
    private var editorMaxStackBlocks = 0
    private var editorMaxCBlocks = 0
    private var editorMaxNodeWidthWorld = 0
    private var editorMaxNodeWidthPixels = 0
    private var editorLastViewportClippedNodes = 0
    private var editorMaxViewportClippedNodes = 0
    private var editorLastCanvasViewportWidth = 0
    private var editorLastCanvasZoomPercent = 100
    private var workspaceRevision = 0
    private var cachedWorkspaceRevision = -1
    private var cachedCanvasViewport: GuiRect? = null
    private var cachedCanvasFont: Font? = null
    private var cachedCanvasTab: Tab? = null
    private var cachedCanvasFunction: MacroFunction? = null
    private var cachedCanvasPanX = 0f
    private var cachedCanvasPanY = 0f
    private var cachedCanvasZoom = 0f
    private var visibleScriptsRevision = -1
    private var visibleScriptsViewport: GuiRect? = null
    private var visibleScriptsPanX = 0f
    private var visibleScriptsPanY = 0f
    private var visibleScriptsZoom = 0f
    private var visibleScriptsFont: Font? = null
    private var visibleDetachedPlacements: List<CanvasDetachedPlacement> = emptyList()
    private var visibleDetachedRevision = -1
    private var visibleDetachedViewport: GuiRect? = null
    private var visibleDetachedPanX = 0f
    private var visibleDetachedPanY = 0f
    private var visibleDetachedZoom = 0f
    private var visibleDetachedFont: Font? = null

    init {
        selectedScript = macro?.primaryKeyScript()
        selectedFunction = firstFunction()
        documentHistory = BoundedUndoHistory(HISTORY_LIMIT, captureDocumentSnapshot())
    }

    override fun init() {
        super.init()
        if (editorDiagnosticsActive) return
        editorDiagnosticsActive = true
        editorDiagnosticTick = Long.MIN_VALUE
        editorDiagnosticEvents = 0
        suppressedEditorDiagnosticEvents = 0
        GeilerAddonsLog.setModuleDiagnosticsEnabled(EDITOR_DIAGNOSTIC_CATEGORY, EDITOR_DIAGNOSTIC_MODULE, true)
        logEditorDiagnostic("STATUS action=open,macroId=${macro?.id() ?: -1},scripts=${macro?.scripts()?.size ?: 0},viewport=${width}x$height")
    }

    private fun editorDiagnosticGameTick(): Long {
        val level = minecraft?.level
        return level?.gameTime ?: System.currentTimeMillis() / 50L
    }

    private fun logEditorDiagnostic(line: String) {
        if (!editorDiagnosticsActive) return
        val tick = editorDiagnosticGameTick()
        if (editorDiagnosticTick != tick) {
            editorDiagnosticTick = tick
            editorDiagnosticEvents = 0
        }
        if (editorDiagnosticEvents >= EDITOR_DIAGNOSTIC_EVENT_LIMIT) {
            suppressedEditorDiagnosticEvents++
            return
        }
        editorDiagnosticEvents++
        GeilerAddonsLog.write(EDITOR_DIAGNOSTIC_CATEGORY, EDITOR_DIAGNOSTIC_MODULE, tick,
            "$line,suppressedEvents=$suppressedEditorDiagnosticEvents")
        suppressedEditorDiagnosticEvents = 0
    }

    private fun finishEditorDiagnostics() {
        if (!editorDiagnosticsActive) return
        val tick = editorDiagnosticGameTick()
        GeilerAddonsLog.write(EDITOR_DIAGNOSTIC_CATEGORY, EDITOR_DIAGNOSTIC_MODULE, tick,
            "STATUS action=close,macroId=${macro?.id() ?: -1},maxNodeWidthWorld=$editorMaxNodeWidthWorld," +
                "maxNodeWidthPx=$editorMaxNodeWidthPixels,canvasViewportWidthPx=$editorLastCanvasViewportWidth," +
                "canvasZoomPercent=$editorLastCanvasZoomPercent,viewportClippedNodesLast=$editorLastViewportClippedNodes," +
                "viewportClippedNodesMax=$editorMaxViewportClippedNodes,stackBlocksMax=$editorMaxStackBlocks," +
                "cBlocksMax=$editorMaxCBlocks,suppressedEvents=$suppressedEditorDiagnosticEvents")
        if (suppressedEditorDiagnosticEvents > 0) {
            GeilerAddonsLog.write(EDITOR_DIAGNOSTIC_CATEGORY, EDITOR_DIAGNOSTIC_MODULE, tick,
                "EVENTS_SUPPRESSED count=$suppressedEditorDiagnosticEvents")
        }
        editorDiagnosticsActive = false
        GeilerAddonsLog.setModuleDiagnosticsEnabled(EDITOR_DIAGNOSTIC_CATEGORY, EDITOR_DIAGNOSTIC_MODULE, false)
    }

    override fun isPauseScreen(): Boolean = false

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        currentMouseX = mouseX
        currentMouseY = mouseY
        hitTargets.clear()
        dropSlots.clear()
        graphics.fill(0, 0, width, height, DIALOG_SHADE)

        val layout = layout()
        lastLayout = layout
        roundedRectBordered(graphics, layout.panel.x, layout.panel.y, layout.panel.w, layout.panel.h,
            RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER)
        graphics.fill(layout.panel.x + 1, layout.panel.y + 1, layout.panel.x + layout.panel.w - 1,
            layout.panel.y + 3, PANEL_HIGHLIGHT)
        renderHeader(graphics, layout, mouseX, mouseY)

        renderPalette(graphics, layout.palette, mouseX, mouseY)
        renderCanvas(graphics, layout.canvas, mouseX, mouseY)
        renderInspector(graphics, layout.inspector, mouseX, mouseY)
        renderFooter(graphics, layout)
        renderDragPreview(graphics, mouseX, mouseY)
        renderPicker(graphics, mouseX, mouseY)
        renderGlobalManager(graphics, mouseX, mouseY)
    }

    private fun layout(): Layout {
        val panelW = maxOf(280, width - MARGIN * 2)
        val panelH = maxOf(180, height - MARGIN * 2)
        val panel = GuiRect(MARGIN, MARGIN, panelW, panelH)
        val footerY = panel.y + panel.h - FOOTER_HEIGHT
        val contentY = panel.y + HEADER_HEIGHT + 2
        val contentH = maxOf(100, footerY - contentY - 4)
        val innerX = panel.x + 7
        val innerW = panel.w - 14
        val gap = 6
        val paletteW = maxOf(92, minOf(194, Math.round(innerW * 0.20f)))
        var inspectorW = maxOf(150, minOf(310, Math.round(innerW * 0.27f)))
        var canvasW = innerW - paletteW - inspectorW - gap * 2
        var adjustedPaletteW = paletteW
        if (canvasW < 220) {
            adjustedPaletteW = maxOf(68, minOf(paletteW, Math.round(innerW * 0.18f)))
            inspectorW = maxOf(120, minOf(inspectorW, Math.round(innerW * 0.27f)))
            canvasW = innerW - adjustedPaletteW - inspectorW - gap * 2
        }
        val palette = GuiRect(innerX, contentY, adjustedPaletteW, contentH)
        val canvas = GuiRect(palette.x + palette.w + gap, contentY, canvasW, contentH)
        val inspector = GuiRect(canvas.x + canvas.w + gap, contentY, inspectorW, contentH)
        return Layout(panel, palette, canvas, inspector, footerY)
    }

    private fun renderHeader(graphics: GuiGraphicsExtractor, layout: Layout, mouseX: Int, mouseY: Int) {
        val panel = layout.panel
        graphics.text(font, "Macro Editor", panel.x + 13, panel.y + 8, TEXT_PRIMARY)
        val subtitle = if (macro == null) "No macro selected" else "Macro ${macro.id()}  ·  ${macro.name()}"
        graphics.text(font, trim(subtitle, panel.w - 170), panel.x + 13, panel.y + 22, TEXT_MUTED)

        val doneW = minOf(68, maxOf(50, panel.w / 7))
        val done = GuiRect(panel.x + panel.w - doneW - 9, panel.y + 7, doneW, 20)
        button(graphics, "Done", done, mouseX, mouseY, null, this::closeToParent, false, 0)

        var x = panel.x + 12
        val y = panel.y + 42
        val code = GuiRect(x, y, 65, 20)
        button(graphics, "Code", code, mouseX, mouseY, null,
            { commitFocusedField(); tab = Tab.CODE; clearSelection() }, tab == Tab.CODE, categoryColor(Category.ACTIONS))
        x += code.w + 4
        val functions = GuiRect(x, y, 87, 20)
        button(graphics, "My Blocks", functions, mouseX, mouseY, null,
            { commitFocusedField(); tab = Tab.FUNCTIONS; clearSelection(); ensureFunction() },
            tab == Tab.FUNCTIONS, categoryColor(Category.FUNCTIONS))
        val globals = GuiRect(functions.x + functions.w + 4, y, 72, 20)
        button(graphics, "Globals", globals, mouseX, mouseY, null,
            { commitFocusedField(); globalManagerOpen = true }, false, categoryColor(Category.VARIABLES))
        val boundScript = ModuleKeybindManager.bindingScript()
        val right = if (boundScript != null && macro != null && macro.scripts().contains(boundScript))
            "Listening for a key…" else "Drag stacks · snap blocks · live run status"
        val textX = globals.x + globals.w + 7
        val available = done.x - textX - 7
        if (available > 30) graphics.text(font, trim(right, available), textX, y + 6,
            if (right.startsWith("Listening")) TEXT_WARN else TEXT_MUTED)
    }

    private fun renderPalette(graphics: GuiGraphicsExtractor, pane: GuiRect, mouseX: Int, mouseY: Int) {
        drawPanel(graphics, pane, "Blocks", if (tab == Tab.FUNCTIONS)
            "Search nodes for this reusable block" else "Search a category, then click or drag")
        val viewport = GuiRect(pane.x + 6, pane.y + 39, pane.w - 12, maxOf(18, pane.h - 46))
        graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h)
        renderBlockPalette(graphics, viewport, mouseX, mouseY)
        graphics.disableScissor()
    }

    private fun renderBlockPalette(graphics: GuiGraphicsExtractor, viewport: GuiRect, mouseX: Int, mouseY: Int) {
        val search = GuiRect(viewport.x + 3, viewport.y + 1, viewport.w - 6, 20)
        val searchFocused = focusedField == "palette-search"
        roundedRectBordered(graphics, search.x, search.y, search.w, search.h, RADIUS_SMALL,
            MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, if (searchFocused) CARD_BORDER_ENABLED else BORDER)
        val query = if (searchFocused) fieldText else paletteSearch
        val searchLabel = if (javaIsBlank(query) && !searchFocused) "Search nodes…" else query
        drawBoundedFieldText(graphics, search, viewport, searchLabel, 5, 5, search.y + 6,
            searchFocused, if (javaIsBlank(query) && !searchFocused) TEXT_MUTED else TEXT_PRIMARY)
        registerHit(search, viewport, null, null, null, null, null, null,
            FieldBinding("palette-search", Supplier { paletteSearch }, Consumer { value ->
                paletteSearch = safe(value)
                paletteScroll = 0
            }, 5, 5))

        val columns = if (viewport.w >= 330) 4 else if (viewport.w >= 190) 3 else 2
        val categoryH = 18
        val chipGap = 2
        val chipW = maxOf(25, (viewport.w - 6 - chipGap * (columns - 1)) / columns)
        val categoryTop = search.y + search.h + 3
        val categories = Category.values()
        for (i in categories.indices) {
            val value = categories[i]
            val row = i / columns
            val column = i % columns
            val x = viewport.x + 3 + column * (chipW + chipGap)
            val w = if (column == columns - 1 || i == categories.size - 1)
                viewport.x + viewport.w - 3 - x else chipW
            val bounds = GuiRect(x, categoryTop + row * (categoryH + 2), w, categoryH)
            button(graphics, value.shortLabel, bounds, mouseX, mouseY, viewport,
                { category = value; paletteSearch = ""; paletteScroll = 0 },
                category == value && javaIsBlank(query), categoryColor(value))
        }

        val categoryRows = (categories.size + columns - 1) / columns
        val listTop = categoryTop + categoryRows * (categoryH + 2) + 3
        val list = GuiRect(viewport.x + 1, listTop, viewport.w - 2,
            maxOf(8, viewport.y + viewport.h - 2 - listTop))
        val paletteLayout = paletteLayout(query)
        val rows = paletteLayout.rows
        val contentHeight = paletteLayout.contentHeight
        paletteScroll = clamp(paletteScroll, 0, maxOf(0, contentHeight - list.h))
        var y = list.y - paletteScroll
        // The list scrolls under the search box and category chips, so it gets its own clip (scissors nest by intersection).
        graphics.enableScissor(list.x, list.y, list.x + list.w, list.y + list.h)
        for (row in rows) {
            if (row.header) {
                if (y + 18 > list.y && y < list.y + list.h) {
                    graphics.fill(list.x + 2, y + 16, list.x + list.w - 2, y + 17,
                        withOpacity(categoryColor(row.category), 0.48f))
                    graphics.text(font, trim(row.category.label, list.w - 8), list.x + 4, y + 4,
                        categoryColor(row.category))
                }
                y += 21
                continue
            }
            val block = row.block ?: continue
            if (y + 39 <= list.y || y >= list.y + list.h) {
                y += 39
                continue
            }
            val bounds = GuiRect(list.x + 1, y + 1, list.w - 4, 35)
            val hovered = bounds.contains(mouseX, mouseY)
            val background = if (hovered) MODULE_PANEL_TOP else MODULE_PANEL_BOTTOM
            roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
                background, background, BORDER)
            val paletteColor = categoryColor(block.category)
            val labelW = minOf(bounds.w - 8, font.width(block.label) + 18)
            paintShape(graphics, ScratchBlockShapes.stack(labelW, 17, false), bounds.x + 4, bounds.y + 3,
                paletteColor, blockOutline(paletteColor))
            graphics.text(font, trim(block.label, labelW - 14), bounds.x + 11, bounds.y + 8,
                macroAccentText(0xFFFFFFFF.toInt()))
            graphics.text(font, trim(block.hint, bounds.w - 12), bounds.x + 6, bounds.y + 24, TEXT_MUTED)
            registerHit(bounds, list, null, null, null, block, null, null)
            y += 39
        }
        if (rows.isEmpty()) graphics.centeredText(font, trim("No nodes match this search.", list.w - 8),
            list.x + list.w / 2, list.y + 18, TEXT_MUTED)
        graphics.disableScissor()
    }

    private fun renderCanvas(graphics: GuiGraphicsExtractor, pane: GuiRect, mouseX: Int, mouseY: Int) {
        drawPanel(graphics, pane, "Workspace", if (tab == Tab.CODE)
            "Move event hats freely · blocks snap into stacks and sockets"
        else "Define a reusable block and edit its local inputs")
        renderCanvasToolbar(graphics, pane, mouseX, mouseY)
        val viewport = GuiRect(pane.x + 5, pane.y + 63, pane.w - 10, maxOf(16, pane.h - 70))
        frameMaxNodeWidth = 0
        frameViewportClippedNodes = 0
        frameStackBlocks = 0
        frameCBlocks = 0
        renderGrid(graphics, viewport)
        val hitStart = hitTargets.size
        val dropStart = dropSlots.size
        if (canReuseCanvasTargets(viewport)) {
            hitTargets.addAll(cachedCanvasHitTargets)
            dropSlots.addAll(cachedCanvasDropSlots)
            replayingCanvasTargets = true
        } else {
            replayingCanvasTargets = false
        }
        graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h)
        val currentMacro = macro
        if (currentMacro == null) {
            graphics.centeredText(font, "No macro to edit.", viewport.x + viewport.w / 2,
                viewport.y + viewport.h / 2, TEXT_MUTED)
        } else {
            val zoom = canvasZoomFactor(currentMacro.canvasZoom())
            canvasTransformX = viewport.x + 16 + currentMacro.canvasPanX()
            canvasTransformY = viewport.y + 14 + currentMacro.canvasPanY()
            canvasTransformScale = zoom
            canvasTransformClip = viewport
            val worldViewport = GuiRect(
                floor((viewport.x - canvasTransformX) / zoom).toInt(),
                floor((viewport.y - canvasTransformY) / zoom).toInt(),
                maxOf(1, ceil(viewport.w / zoom).toInt()),
                maxOf(1, ceil(viewport.h / zoom).toInt()))
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(canvasTransformX, canvasTransformY)
            pose.scale(zoom, zoom)
            canvasTransformActive = true
            if (tab == Tab.CODE) renderScripts(graphics, worldViewport) else renderFunctionStack(graphics, worldViewport)
            canvasTransformActive = false
            pose.popMatrix()
        }
        graphics.disableScissor()
        recordCanvasLayoutMetrics(viewport, currentMacro?.canvasZoom() ?: 100f)
        if (!replayingCanvasTargets) cacheCanvasTargets(viewport, hitStart, dropStart)
        replayingCanvasTargets = false
        renderDropPreview(graphics, viewport, mouseX, mouseY)
        renderZoomControls(graphics, viewport, mouseX, mouseY)
    }

    private fun recordCanvasNodeBounds(bounds: GuiRect, viewport: GuiRect) {
        frameMaxNodeWidth = maxOf(frameMaxNodeWidth, bounds.w)
        if (bounds.x < viewport.x || bounds.x.toLong() + bounds.w > viewport.x.toLong() + viewport.w) {
            frameViewportClippedNodes++
        }
    }

    private fun recordCanvasLayoutMetrics(viewport: GuiRect, zoomPercent: Float) {
        if (!editorDiagnosticsActive) return
        val widthPixels = minOf(Int.MAX_VALUE.toDouble(), ceil(maxOf(0, frameMaxNodeWidth) * canvasTransformScale.toDouble())).toInt()
        editorMaxNodeWidthWorld = maxOf(editorMaxNodeWidthWorld, frameMaxNodeWidth)
        editorMaxNodeWidthPixels = maxOf(editorMaxNodeWidthPixels, widthPixels)
        editorLastViewportClippedNodes = frameViewportClippedNodes
        editorMaxViewportClippedNodes = maxOf(editorMaxViewportClippedNodes, frameViewportClippedNodes)
        editorMaxStackBlocks = maxOf(editorMaxStackBlocks, frameStackBlocks)
        editorMaxCBlocks = maxOf(editorMaxCBlocks, frameCBlocks)
        editorLastCanvasViewportWidth = viewport.w
        editorLastCanvasZoomPercent = Math.round(zoomPercent)
    }

    private fun canReuseCanvasTargets(viewport: GuiRect): Boolean {
        if (!canvasTargetCacheValid || cachedWorkspaceRevision != workspaceRevision ||
            viewport != cachedCanvasViewport || cachedCanvasFont !== font || cachedCanvasTab != tab ||
            cachedCanvasFunction !== selectedFunction) return false
        val currentMacro = macro
        if (currentMacro == null) return cachedCanvasPanX == 0f && cachedCanvasPanY == 0f && cachedCanvasZoom == 100f
        return cachedCanvasPanX == currentMacro.canvasPanX() && cachedCanvasPanY == currentMacro.canvasPanY() &&
            cachedCanvasZoom == currentMacro.canvasZoom()
    }

    private fun cacheCanvasTargets(viewport: GuiRect, hitStart: Int, dropStart: Int) {
        cachedCanvasHitTargets.clear()
        cachedCanvasDropSlots.clear()
        cachedCanvasHitTargets.addAll(hitTargets.subList(hitStart, hitTargets.size))
        cachedCanvasDropSlots.addAll(dropSlots.subList(dropStart, dropSlots.size))
        cachedCanvasViewport = viewport
        cachedCanvasFont = font
        cachedCanvasTab = tab
        cachedCanvasFunction = selectedFunction
        cachedWorkspaceRevision = workspaceRevision
        cachedCanvasPanX = macro?.canvasPanX() ?: 0f
        cachedCanvasPanY = macro?.canvasPanY() ?: 0f
        cachedCanvasZoom = macro?.canvasZoom() ?: 100f
        canvasTargetCacheValid = true
    }

    private fun renderCanvasToolbar(graphics: GuiGraphicsExtractor, pane: GuiRect, mouseX: Int, mouseY: Int) {
        val y = pane.y + 39
        val x = pane.x + 8
        if (tab == Tab.CODE) {
            val buttonW = if (pane.w < 560) maxOf(36, minOf(54, (pane.w - 24) / 5)) else 54
            val buttonsX = pane.x + pane.w - 8 - (buttonW * 5 + 12)
            val addKey = GuiRect(buttonsX, y, buttonW, 19)
            val addRegion = GuiRect(addKey.x + buttonW + 3, y, buttonW, 19)
            val addCall = GuiRect(addRegion.x + buttonW + 3, y, buttonW, 19)
            val addChat = GuiRect(addCall.x + buttonW + 3, y, buttonW, 19)
            val addPest = GuiRect(addChat.x + buttonW + 3, y, buttonW, 19)
            button(graphics, "+ Key", addKey, mouseX, mouseY, pane,
                { addScript(MacroScript.Trigger.KEY_PRESS) }, false, categoryColor(Category.ACTIONS))
            button(graphics, "+ Area", addRegion, mouseX, mouseY, pane,
                { addScript(MacroScript.Trigger.WORLD_REGION) }, false, categoryColor(Category.WORLD))
            button(graphics, "+ Call", addCall, mouseX, mouseY, pane,
                { addScript(MacroScript.Trigger.ON_CALL) }, false, categoryColor(Category.FUNCTIONS))
            button(graphics, "+ Chat", addChat, mouseX, mouseY, pane,
                { addScript(MacroScript.Trigger.CHAT) }, false, categoryColor(Category.ACTIONS))
            button(graphics, "+ Pest", addPest, mouseX, mouseY, pane,
                { addScript(MacroScript.Trigger.PEST_SPAWNED) }, false, categoryColor(Category.ACTIONS))
            val script = selectedScript
            val bindable = script != null && (script.trigger() == MacroScript.Trigger.KEY_PRESS ||
                script.trigger() == MacroScript.Trigger.CHAT)
            if (bindable) {
                val captureW = minOf(102, maxOf(72, pane.w / 5))
                val capture = GuiRect(x, y, captureW, 19)
                if (capture.x + capture.w + 6 <= buttonsX) {
                    val binding = ModuleKeybindManager.bindingScript() === script
                    button(graphics, if (binding) "Listening…" else "Set key", capture, mouseX, mouseY, pane,
                        this::toggleScriptBinding, false, if (binding) categoryColor(Category.ACTIONS) else 0)
                }
            }
            val currentMacro = macro
            if (script != null && currentMacro != null && currentMacro.scripts().size > 1) {
                val remove = GuiRect(addKey.x - 34, y, 30, 19)
                if (remove.x >= pane.x + 4) button(graphics, "×", remove, mouseX, mouseY, pane,
                    this::removeSelectedScript, false, TEXT_ERROR)
            }
        } else {
            val createW = if (pane.w < 360) maxOf(72, pane.w / 3) else 96
            val selectorX = x
            val selectorW = maxOf(72, pane.x + pane.w - 16 - selectorX - createW - 4)
            val selector = GuiRect(selectorX, y, selectorW, 19)
            val function = selectedFunction
            val name = if (function == null) "Function · choose" else "Function · ${function.name()}"
            button(graphics, name, selector, mouseX, mouseY, pane, this::openFunctionSelector,
                false, categoryColor(Category.FUNCTIONS))
            val create = GuiRect(pane.x + pane.w - 8 - createW, y, createW, 19)
            button(graphics, "+ New block", create, mouseX, mouseY, pane,
                this::createFunction, false, categoryColor(Category.FUNCTIONS))
        }
    }

    private fun openFunctionSelector() {
        val options = ArrayList<PickerOption>()
        for (function in MacrosModule.INSTANCE.functions()) {
            options.add(PickerOption(function.name()) { selectFunction(function) })
        }
        options.add(PickerOption("+ Create a function", this::createFunction))
        openPicker("Select a function to edit", options)
    }

    private fun renderScripts(graphics: GuiGraphicsExtractor, viewport: GuiRect) {
        val currentMacro = macro ?: return
        if (visibleScriptsRevision != workspaceRevision || viewport != visibleScriptsViewport ||
            visibleScriptsPanX != currentMacro.canvasPanX() || visibleScriptsPanY != currentMacro.canvasPanY() ||
            visibleScriptsZoom != currentMacro.canvasZoom() || visibleScriptsFont !== font) {
            val visible = ArrayList<CanvasScriptPlacement>()
            val zoom = canvasZoomFactor(currentMacro.canvasZoom())
            for (script in currentMacro.scripts()) {
                val stackW = requiredStackWidth(scriptHeaderLabel(script), script.steps(), 320)
                val x = Math.round(script.canvasX())
                val y = Math.round(script.canvasY())
                val stackH = HAT_HEIGHT + measureList(script.steps(), 0) + ScratchBlockShapes.NOTCH_DEPTH
                if (intersectsViewport(x, y, stackW, stackH, viewport)) {
                    visible.add(CanvasScriptPlacement(script, x, y, stackW, zoom))
                }
            }
            visibleScriptPlacements = visible.toList()
            visibleScriptsRevision = workspaceRevision
            visibleScriptsViewport = viewport
            visibleScriptsPanX = currentMacro.canvasPanX()
            visibleScriptsPanY = currentMacro.canvasPanY()
            visibleScriptsZoom = currentMacro.canvasZoom()
            visibleScriptsFont = font
        }
        for (placement in visibleScriptPlacements) {
            renderScriptStack(graphics, viewport, placement.script, placement.x, placement.y,
                placement.width, placement.zoom)
        }
        renderDetachedBlocks(graphics, viewport)
    }

    /** Draws editor-only blocks that are deliberately absent from every executable script list. */
    private fun renderDetachedBlocks(graphics: GuiGraphicsExtractor, viewport: GuiRect) {
        val currentMacro = macro ?: return
        if (currentMacro.detachedBlocks().isEmpty()) return
        if (visibleDetachedRevision != workspaceRevision || viewport != visibleDetachedViewport ||
            visibleDetachedPanX != currentMacro.canvasPanX() || visibleDetachedPanY != currentMacro.canvasPanY() ||
            visibleDetachedZoom != currentMacro.canvasZoom() || visibleDetachedFont !== font) {
            val width = 304
            val visible = ArrayList<CanvasDetachedPlacement>()
            for (index in currentMacro.detachedBlocks().indices) {
                val step = currentMacro.detachedBlocks()[index]
                val base = step as? MacroStep.Base ?: continue
                val x = Math.round(base.editorX())
                val y = Math.round(base.editorY())
                val note = step is MacroStep.Comment
                val height = if (note) 128 else measureStep(step, 0)
                val nodeWidth = if (note) maxOf(width, font.width((step as MacroStep.Comment).text()) + 16)
                    else ownStepWidth(step)
                if (intersectsViewport(x, y, nodeWidth, height + 12, viewport)) {
                    visible.add(CanvasDetachedPlacement(step, index, x, y, nodeWidth, height, note))
                }
            }
            visibleDetachedPlacements = visible.toList()
            visibleDetachedRevision = workspaceRevision
            visibleDetachedViewport = viewport
            visibleDetachedPanX = currentMacro.canvasPanX()
            visibleDetachedPanY = currentMacro.canvasPanY()
            visibleDetachedZoom = currentMacro.canvasZoom()
            visibleDetachedFont = font
        }
        for (placement in visibleDetachedPlacements) {
            val step = placement.step
            graphics.text(font, if (placement.note) "Note" else "Stored · inactive",
                placement.x + 5, placement.y - 11, TEXT_MUTED)
            drawStepNode(graphics, viewport, step, currentMacro.detachedBlocks(), placement.index,
                GuiRect(placement.x, placement.y, placement.width, placement.height), null, null, 0)
        }
    }

    private fun intersectsViewport(x: Int, y: Int, w: Int, h: Int, viewport: GuiRect): Boolean =
        x + w >= viewport.x && x <= viewport.x + viewport.w && y + h >= viewport.y && y <= viewport.y + viewport.h

    private fun renderScriptStack(graphics: GuiGraphicsExtractor, viewport: GuiRect, script: MacroScript,
        x: Int, y: Int, stackW: Int, zoom: Float) {
        val color = when (script.trigger()) {
            MacroScript.Trigger.KEY_PRESS, MacroScript.Trigger.CHAT,
            MacroScript.Trigger.PEST_SPAWNED, MacroScript.Trigger.PEST_COOLDOWN_READY -> categoryColor(Category.ACTIONS)
            MacroScript.Trigger.WORLD_REGION -> categoryColor(Category.WORLD)
            else -> categoryColor(Category.FUNCTIONS)
        }
        val selected = script === selectedScript
        val hat = drawHat(graphics, x, y, scriptHeaderLabel(script), color, if (selected) TEXT_PRIMARY else blockOutline(color))
        recordCanvasNodeBounds(hat, viewport)
        val state = MacroRunner.scriptState(script.id())
        val stateRect = GuiRect(hat.x + hat.w - 52, hat.y + ScratchBlockShapes.HAT_DOME + 7, 43, 17)
        val stateColor = stateColor(state)
        roundedRect(graphics, stateRect.x, stateRect.y, stateRect.w, stateRect.h, 7, withOpacity(0xFF000000.toInt(), 0.30f))
        graphics.centeredText(font, stateLabel(state), stateRect.x + stateRect.w / 2, stateRect.y + 5, stateColor)
        registerHit(hat, viewport, { selectScript(script) }, null, null, null, script, null)

        val contentY = hat.y + hat.h
        val steps = script.steps()
        if (steps.isEmpty()) {
            val socket = GuiRect(x, contentY + ScratchBlockShapes.NOTCH_DEPTH + 2, 140, 22)
            paintSocket(graphics, socket, color, "drop a block here")
            addDropSlot(socket, steps, 0, false, script, null, 0)
            return
        }
        drawStepList(graphics, viewport, steps, x, contentY, stackW, 0, script, null, 0)
    }

    /** Draws an event hat that hugs its label (with room for the state badge) and returns its bounds. */
    private fun drawHat(graphics: GuiGraphicsExtractor, x: Int, y: Int, label: String, color: Int, outline: Int): GuiRect {
        val w = maxOf(font.width(label) + 64, ScratchBlockShapes.HAT_DOME_WIDTH + 24)
        paintShape(graphics, ScratchBlockShapes.hat(w, HAT_HEIGHT), x, y, color, outline)
        graphics.text(font, label, x + 9, y + (HAT_HEIGHT + ScratchBlockShapes.HAT_DOME - 8) / 2 + 1,
            macroAccentText(0xFFFFFFFF.toInt()))
        return GuiRect(x, y, w, HAT_HEIGHT)
    }

    private fun renderFunctionStack(graphics: GuiGraphicsExtractor, viewport: GuiRect) {
        ensureFunction()
        val function = selectedFunction
        if (function == null) {
            graphics.centeredText(font, trim("Create a reusable block from the left panel.", viewport.w - 12),
                viewport.x + viewport.w / 2, viewport.y + viewport.h / 2, TEXT_MUTED)
            return
        }
        val x = Math.round(function.canvasX())
        val y = Math.round(function.canvasY())
        val w = requiredStackWidth("define  ${function.name()}", function.steps(), 320)
        val functionColor = categoryColor(Category.FUNCTIONS)
        val hat = drawHat(graphics, x, y, "define  ${function.name()}", functionColor, GuiTheme.selectionOutline(functionColor))
        recordCanvasNodeBounds(hat, viewport)
        registerHit(hat, viewport, { selectedStep = null; selectedOwner = null }, null, null, null, null, function)
        val contentY = hat.y + hat.h
        if (function.steps().isEmpty()) {
            val socket = GuiRect(x, contentY + ScratchBlockShapes.NOTCH_DEPTH + 2, 140, 22)
            paintSocket(graphics, socket, functionColor, "drop a block here")
            addDropSlot(socket, function.steps(), 0, true, null, function, 0)
            return
        }
        drawStepList(graphics, viewport, function.steps(), x, contentY, w, 0, null, function, 0)
    }

    private fun requiredStackWidth(header: String, steps: List<MacroStep>?, minimumWidth: Int): Int {
        val headerWidth = font.width(header) + 64
        val blockWidth = requiredListWidth(steps, maxOf(1, minimumWidth - 16), 0) + 16
        return maxOf(minimumWidth, maxOf(headerWidth, blockWidth))
    }

    private fun scriptHeaderLabel(script: MacroScript): String = when (script.trigger()) {
        MacroScript.Trigger.KEY_PRESS -> "when  ${script.keybind().displayName()}  pressed"
        MacroScript.Trigger.WORLD_REGION -> regionHatLabel(script.worldRegion())
        MacroScript.Trigger.ON_CALL -> "when this macro is called"
        MacroScript.Trigger.CHAT -> chatHatLabel(script)
        MacroScript.Trigger.PEST_SPAWNED -> "when a pest spawns"
        MacroScript.Trigger.PEST_COOLDOWN_READY -> "when pest cooldown is ready"
    }

    private fun requiredListWidth(steps: List<MacroStep>?, minimumWidth: Int, nesting: Int): Int {
        var width = minimumWidth
        if (steps == null || nesting > MAX_TREE_DEPTH || steps.isEmpty()) return width
        ensureWidthCacheFont()
        val cachedWidths = measuredListWidths[steps]
        if (cachedWidths != null && cachedWidths[nesting] != 0) return maxOf(minimumWidth, cachedWidths[nesting])
        var measuredWidth = 0
        for (step in steps) measuredWidth = maxOf(measuredWidth, requiredStepWidth(step, nesting))
        val widths = cachedWidths ?: IntArray(MAX_TREE_DEPTH + 1)
        widths[nesting] = measuredWidth
        measuredListWidths[steps] = widths
        width = maxOf(width, measuredWidth)
        return width
    }

    /** Horizontal extent of a node: its own block plus any expanded children, which may stick out past it. */
    private fun requiredStepWidth(step: MacroStep?, nesting: Int): Int {
        if (step == null || nesting > MAX_TREE_DEPTH) return 60
        ensureWidthCacheFont()
        val cachedWidths = measuredStepWidths[step]
        if (cachedWidths != null && cachedWidths[nesting] != 0) return cachedWidths[nesting]
        var width = ownStepWidth(step)
        val layout = if (nesting < MAX_TREE_DEPTH) cBlockLayout(step, nesting) else null
        if (layout != null) for (branch in layout.branches) {
            width = maxOf(width, ScratchBlockShapes.ARM_WIDTH + requiredListWidth(branch, 0, nesting + 1))
        }
        cacheRequiredStepWidth(step, nesting, width, cachedWidths)
        return width
    }

    /** The block's own width hugs its text, like Scratch. */
    private fun ownStepWidth(step: MacroStep): Int {
        ensureWidthCacheFont()
        measuredOwnWidths[step]?.let { return it }
        val preview = inlinePreview(step)
        val container = isContainer(step)
        var width = (if (preview == null) font.width(blockLabel(step)) else previewWidth(preview)) + 18 +
            if (container) 20 else 0
        if (container && ClickGuiState.macroNodeExpanded(step)) {
            width = maxOf(width, C_MIN_WIDTH)
            if (step is MacroStep.Switch) for (b in step.cases()) width = maxOf(width, font.width("case ${b.value()}") + 18)
        }
        width = maxOf(48, width)
        measuredOwnWidths[step] = width
        return width
    }

    private fun isContainer(step: MacroStep?): Boolean = step is MacroStep.IfElse || step is MacroStep.Repeat ||
        step is MacroStep.RepeatUntil || step is MacroStep.Switch

    private fun cacheRequiredStepWidth(step: MacroStep, nesting: Int, width: Int, cachedWidths: IntArray?) {
        val widths = cachedWidths ?: IntArray(MAX_TREE_DEPTH + 1)
        widths[nesting] = width
        measuredStepWidths[step] = widths
    }

    private fun ensureWidthCacheFont() {
        if (measuredWidthFont === font) return
        measuredWidthFont = font
        measuredStepWidths.clear()
        measuredOwnWidths.clear()
        measuredListWidths.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun drawStepList(graphics: GuiGraphicsExtractor, viewport: GuiRect, steps: List<MacroStep>?,
        x: Int, y: Int, w: Int, depth: Int, script: MacroScript?, function: MacroFunction?, nesting: Int): Int {
        if (steps == null || steps.isEmpty() || nesting > MAX_TREE_DEPTH) return y
        val layout = listLayout(steps, nesting)
        val first = layout.firstIntersecting(maxOf(0, viewport.y - y))
        for (i in first until steps.size) {
            val step = steps[i]
            val nodeY = y + layout.starts[i]
            val nodeH = layout.heights[i]
            if (nodeY > viewport.y + viewport.h) break
            val outer = GuiRect(x, nodeY, ownStepWidth(step), nodeH)
            if (intersectsViewport(outer.x, outer.y, requiredStepWidth(step, nesting), outer.h + ScratchBlockShapes.NOTCH_DEPTH, viewport)) {
                drawStepNode(graphics, viewport, step, steps as MutableList<MacroStep>, i, outer, script, function, nesting)
            }
        }
        return y + layout.totalHeight
    }

    private fun drawStepNode(graphics: GuiGraphicsExtractor, viewport: GuiRect, step: MacroStep,
        owner: MutableList<MacroStep>?, index: Int, bounds: GuiRect, script: MacroScript?, function: MacroFunction?, nesting: Int) {
        val color = blockColor(step)
        val selected = step === selectedStep
        val active = script != null && MacroRunner.activeStep(script.id()) === step
        val control = isContainer(step)
        recordCanvasNodeBounds(bounds, viewport)
        val currentMacro = macro
        val detachedNote = step is MacroStep.Comment && currentMacro != null && owner === currentMacro.detachedBlocks()
        if (detachedNote) {
            drawCommentNote(graphics, viewport, bounds, step as MacroStep.Comment, color, selected)
            registerHit(bounds, viewport, null, step, owner, null, script, function)
            return
        }
        val headH = minOf(BLOCK_HEIGHT, bounds.h)
        val layout = cBlockLayout(step, nesting)
        val shape = if (layout == null) ScratchBlockShapes.stack(bounds.w, headH, false)
            else ScratchBlockShapes.cBlock(bounds.w, layout.bars, layout.inners, layout.cap)
        val fill = if (active) lerpColor(color, 0xFFFFFFFF.toInt(), 0.18f) else color
        val outline = if (active) 0xFFFFFFFF.toInt() else if (selected) GuiTheme.selectionOutline(color) else blockOutline(color)
        if (layout == null) frameStackBlocks++ else frameCBlocks++
        paintShape(graphics, shape, bounds.x, bounds.y, fill, outline)
        val textColor = macroAccentText(0xFFFFFFFF.toInt())
        val head = GuiRect(bounds.x, bounds.y, bounds.w, headH)
        drawInlineBlockPreview(graphics, viewport, head, step, color, textColor)
        registerHit(head, viewport, null, step, owner, null, script, function)
        val slotW = maxOf(bounds.w, 96)
        val functionScope = function != null
        addDropSlot(GuiRect(bounds.x, bounds.y, slotW, maxOf(1, headH / 2)), owner, index,
            functionScope, script, function, nesting)
        if (layout == null) addDropSlot(GuiRect(bounds.x, bounds.y + headH / 2, slotW, maxOf(1, headH - headH / 2)),
            owner, index + 1, functionScope, script, function, nesting)
        if (control) {
            val toggleX = bounds.x + bounds.w - 18
            graphics.centeredText(font, if (layout != null) "\u25BE" else "\u25B8", toggleX + 9, bounds.y + 10, textColor)
            registerHit(GuiRect(toggleX, bounds.y, 18, headH), viewport, { ClickGuiState.toggleMacroNode(step); dirty() },
                null, null, null, null, null)
        }
        if (layout == null) return

        val armX = bounds.x + ScratchBlockShapes.ARM_WIDTH
        var y = bounds.y
        for (k in layout.branches.indices) {
            val barTop = y
            val barH = layout.bars[k]
            y += barH
            val label = layout.labels[k]
            if (label != null) {
                val labelY = if (k == 0) barTop + BLOCK_HEIGHT + (C_MID_BAR - 8) / 2 else barTop + (barH - 8) / 2
                graphics.text(font, trim(label, bounds.w - 14), bounds.x + 9, labelY, textColor)
            }
            val children = layout.branches[k]
            val innerH = layout.inners[k]
            val gap = GuiRect(armX, y, maxOf(60, bounds.w - ScratchBlockShapes.ARM_WIDTH), innerH)
            val branchArea = if (k == 0) gap else GuiRect(bounds.x, barTop, bounds.w, barH + innerH)
            val branchContext = DropSlot(gap, children, children.size, functionScope, script, function, nesting + 1)
            val branchName = (label ?: if (step is MacroStep.IfElse) "then" else "do").lowercase(Locale.ROOT)
            registerHit(branchArea, viewport, {
                selectedStep = null
                selectedOwner = children
                selectDropContext(branchContext)
                inspectorScroll = 0
                logEditorDiagnostic("EVENT action=select_branch,label=$branchName,blocks=${children.size},expanded=true")
            }, null, null, null, script, function)
            if (k > 0) {
                val previous = layout.branches[k - 1]
                addDropSlot(GuiRect(bounds.x, barTop, slotW, maxOf(1, barH / 2)), previous, previous.size,
                    functionScope, script, function, nesting + 1)
            }
            addDropSlot(GuiRect(bounds.x, barTop + barH / 2, slotW, maxOf(1, barH - barH / 2)), children, 0,
                functionScope, script, function, nesting + 1)
            if (selectedStep == null && selectedOwner === children) outlineRect(graphics, gap, withOpacity(0xFFFFFFFF.toInt(), 0.70f))
            if (children.isEmpty()) {
                graphics.text(font, trim("drop blocks here", gap.w - 10), gap.x + 6, gap.y + (innerH - 8) / 2 + 1, TEXT_MUTED)
                addDropSlot(gap, children, 0, functionScope, script, function, nesting + 1)
            } else {
                val after = drawStepList(graphics, viewport, children, armX, y, gap.w, 1, script, function, nesting + 1)
                addDropSlot(GuiRect(armX, maxOf(y, after - 3), slotW, 6), children, children.size,
                    functionScope, script, function, nesting + 1)
            }
            y += innerH
        }
        val bottomH = layout.bars[layout.bars.size - 1]
        registerHit(GuiRect(bounds.x, y, bounds.w, bottomH), viewport, null, step, owner, null, script, function)
        val last = layout.branches[layout.branches.size - 1]
        addDropSlot(GuiRect(bounds.x, y, slotW, maxOf(1, bottomH / 2)), last, last.size,
            functionScope, script, function, nesting + 1)
        addDropSlot(GuiRect(bounds.x, y + bottomH / 2, slotW, maxOf(1, bottomH - bottomH / 2)), owner, index + 1,
            functionScope, script, function, nesting)
        if (layout.loop) graphics.text(font, "\u21BB", bounds.x + bounds.w - 14, y + (bottomH - 8) / 2 + 1, textColor)
    }

    private fun paintShape(graphics: GuiGraphicsExtractor, shape: ScratchBlockShapes.Shape, x: Int, y: Int, fill: Int, outline: Int) =
        ScratchBlockRenderState.draw(graphics, shape, x, y, fill, outline)

    private fun outlineRect(graphics: GuiGraphicsExtractor, r: GuiRect, color: Int) {
        graphics.fill(r.x, r.y, r.x + r.w, r.y + 1, color)
        graphics.fill(r.x, r.y + r.h - 1, r.x + r.w, r.y + r.h, color)
        graphics.fill(r.x, r.y + 1, r.x + 1, r.y + r.h - 1, color)
        graphics.fill(r.x + r.w - 1, r.y + 1, r.x + r.w, r.y + r.h - 1, color)
    }

    private fun blockOutline(color: Int): Int = lerpColor(color, 0xFF000000.toInt(), 0.28f)

    private fun drawCommentNote(graphics: GuiGraphicsExtractor, viewport: GuiRect, bounds: GuiRect,
        comment: MacroStep.Comment, color: Int, selected: Boolean) {
        val outline = if (selected) GuiTheme.selectionOutline(color) else withOpacity(color, 0.78f)
        val surface = withOpacity(color, 0.12f)
        roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, 7,
            surface, surface, outline, if (selected) 2 else 1)
        graphics.fill(bounds.x + 1, bounds.y + 1, bounds.x + bounds.w - 1, bounds.y + 19, withOpacity(color, 0.68f))
        graphics.text(font, "COMMENT", bounds.x + 7, bounds.y + 6, 0xFFFFFFFF.toInt())
        val textY = bounds.y + 27
        val lineHeight = font.lineHeight + 2
        val maxLines = maxOf(1, (bounds.h - 32) / lineHeight)
        var lines = wrapComment(comment.text(), maxOf(32, bounds.w - 14))
        if (lines.isEmpty()) lines = listOf("Add text in Inspect")
        for (i in 0 until minOf(maxLines, lines.size)) {
            graphics.text(font, lines[i], bounds.x + 7, textY + i * lineHeight, TEXT_PRIMARY)
        }
        if (lines.size > maxLines) {
            val moreY = bounds.y + bounds.h - lineHeight - 5
            graphics.text(font, "…", bounds.x + bounds.w - 13, moreY, TEXT_MUTED)
        }
    }

    private fun wrapComment(text: String?, maxWidth: Int): List<String> {
        val cleaned = javaStrip(safe(text).replace('\n', ' ').replace('\r', ' '))
        if (cleaned.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        val line = StringBuilder()
        for (wordValue in cleaned.split(Regex("\\s+"))) {
            var word = wordValue
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && font.width(candidate) > maxWidth) {
                result.add(line.toString())
                line.setLength(0)
            }
            if (font.width(word) > maxWidth) word = trim(word, maxWidth)
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) result.add(line.toString())
        return result
    }

    private fun drawRawJsonPreview(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, rawJson: String?) {
        sectionTitle(graphics, viewport, y, "Saved raw data")
        val raw = rawJson ?: "{}"
        val visibleLength = minOf(raw.length, 8_192)
        val maxWidth = maxOf(32, viewport.w - 8)
        val line = StringBuilder()
        var offset = 0
        while (offset < visibleLength) {
            val codePoint = raw.codePointAt(offset)
            val text = String(Character.toChars(codePoint))
            if (codePoint == '\n'.code) {
                drawRawJsonLine(graphics, viewport, y, line.toString(), maxWidth)
                line.setLength(0)
            } else {
                val candidate = line.toString() + text
                if (line.isNotEmpty() && font.width(candidate) > maxWidth) {
                    drawRawJsonLine(graphics, viewport, y, line.toString(), maxWidth)
                    line.setLength(0)
                }
                line.append(text)
            }
            offset += Character.charCount(codePoint)
        }
        if (line.isNotEmpty() || raw.isEmpty()) drawRawJsonLine(graphics, viewport, y, line.toString(), maxWidth)
        if (visibleLength < raw.length) lineText(graphics, viewport, y, "Preview", "Truncated; saved data remains intact")
    }

    private fun drawRawJsonLine(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, text: String, maxWidth: Int) {
        graphics.text(font, trim(text, maxWidth), viewport.x + 4, y[0] + 2, TEXT_MUTED)
        y[0] += 12
    }

    /** Bars, gaps and child lists of an expanded control block; null for plain or collapsed blocks. */
    private fun cBlockLayout(step: MacroStep, nesting: Int): CBlockLayout? {
        if (!isContainer(step) || !ClickGuiState.macroNodeExpanded(step)) return null
        val branches = ArrayList<MutableList<MacroStep>>()
        val labels = ArrayList<String?>()
        when (step) {
            is MacroStep.IfElse -> {
                branches.add(step.thenSteps()); labels.add(null)
                if (step.elseEnabled()) { branches.add(step.elseSteps()); labels.add("else") }
            }
            is MacroStep.Repeat -> { branches.add(step.steps()); labels.add(null) }
            is MacroStep.RepeatUntil -> { branches.add(step.steps()); labels.add(null) }
            is MacroStep.Switch -> {
                for (branch in step.cases()) { branches.add(branch.steps()); labels.add("case ${branch.value()}") }
                branches.add(step.defaultSteps()); labels.add("otherwise")
            }
            else -> return null
        }
        val bars = IntArray(branches.size + 1) { C_MID_BAR }
        // A switch shows its first case label in a second row of the top bar.
        bars[0] = if (labels[0] == null) BLOCK_HEIGHT else BLOCK_HEIGHT + C_MID_BAR
        bars[branches.size] = C_BOTTOM_BAR
        val inners = IntArray(branches.size) { maxOf(C_INNER_MIN, measureList(branches[it], nesting + 1)) }
        return CBlockLayout(bars, inners, branches, labels, step is MacroStep.Repeat && step.mode() == MacroStep.Repeat.Mode.FOREVER,
            step is MacroStep.Repeat || step is MacroStep.RepeatUntil)
    }

    private fun measureStep(step: MacroStep?, nesting: Int): Int {
        if (step == null || nesting > MAX_TREE_DEPTH) return BLOCK_HEIGHT
        val cached = measuredStepHeights[step]
        if (cached != null) return cached
        val height = cBlockLayout(step, nesting)?.height ?: BLOCK_HEIGHT
        measuredStepHeights[step] = height
        return height
    }

    private fun measureList(list: List<MacroStep>?, nesting: Int): Int {
        if (list == null || list.isEmpty()) return 20
        return listLayout(list, nesting).totalHeight
    }

    private fun listLayout(list: List<MacroStep>, nesting: Int): ListLayout {
        val cached = measuredListLayouts[list]
        if (cached != null) return cached
        val starts = IntArray(list.size)
        val heights = IntArray(list.size)
        var total = 0L
        for (index in list.indices) {
            if (list === dropGapOwner && index == dropGapIndex) total += BLOCK_HEIGHT.toLong()
            starts[index] = minOf(Int.MAX_VALUE / 4L, total).toInt()
            heights[index] = measureStep(list[index], nesting)
            total = minOf(Int.MAX_VALUE / 4L, total + heights[index])
        }
        if (list === dropGapOwner && dropGapIndex >= list.size) total += BLOCK_HEIGHT.toLong()
        val layout = ListLayout(starts, heights, total.toInt())
        measuredListLayouts[list] = layout
        return layout
    }

    private fun paintSocket(graphics: GuiGraphicsExtractor, socket: GuiRect, color: Int, label: String) {
        roundedRectBordered(graphics, socket.x, socket.y, socket.w, socket.h, RADIUS_SMALL,
            withOpacity(color, 0.10f), withOpacity(color, 0.10f), withOpacity(color, 0.62f))
        graphics.text(font, trim(label, socket.w - 12), socket.x + 7, socket.y + maxOf(3, (socket.h - 8) / 2), TEXT_MUTED)
    }

    private fun addDropSlot(bounds: GuiRect, owner: MutableList<MacroStep>?, index: Int, functionScope: Boolean,
        script: MacroScript?, function: MacroFunction?, depth: Int) {
        if (replayingCanvasTargets || owner == null) return
        val screenBounds = if (canvasTransformActive) canvasToScreen(bounds) else bounds
        val clip = if (canvasTransformActive) canvasTransformClip else lastLayout?.let { canvasViewport(it) }
        val clipped = if (clip == null) screenBounds else screenBounds.intersection(clip) ?: return
        dropSlots.add(DropSlot(clipped, owner, index, functionScope, script, function, depth))
    }

    private fun renderGrid(graphics: GuiGraphicsExtractor, viewport: GuiRect) {
        graphics.fill(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h,
            withOpacity(TEXT_PRIMARY, 0.025f))
        val panX = macro?.canvasPanX() ?: 0f
        val panY = macro?.canvasPanY() ?: 0f
        val startX = viewport.x + Math.floorMod(Math.round(16 + panX), GRID_STEP)
        val startY = viewport.y + Math.floorMod(Math.round(14 + panY), GRID_STEP)
        val dot = withOpacity(TEXT_MUTED, 0.25f)
        var x = startX
        while (x < viewport.x + viewport.w) {
            var y = startY
            while (y < viewport.y + viewport.h) {
                graphics.fill(x, y, x + 2, y + 2, dot)
                y += GRID_STEP
            }
            x += GRID_STEP
        }
    }

    private fun renderZoomControls(graphics: GuiGraphicsExtractor, viewport: GuiRect, mouseX: Int, mouseY: Int) {
        val currentMacro = macro ?: return
        val y = viewport.y + viewport.h - 25
        val zoomOut = GuiRect(viewport.x + 8, y, 25, 20)
        val zoomLabel = GuiRect(zoomOut.x + 28, y, 55, 20)
        val zoomIn = GuiRect(zoomLabel.x + 58, y, 25, 20)
        val reset = GuiRect(zoomIn.x + 29, y, 49, 20)
        button(graphics, "−", zoomOut, mouseX, mouseY, viewport,
            { setZoomAround(0.9f, viewport.x + viewport.w / 2.0, viewport.y + viewport.h / 2.0) }, false, 0)
        roundedRect(graphics, zoomLabel.x, zoomLabel.y, zoomLabel.w, zoomLabel.h, RADIUS_SMALL, MODULE_PANEL_TOP)
        graphics.centeredText(font, "${Math.round(currentMacro.canvasZoom())}%", zoomLabel.x + zoomLabel.w / 2,
            zoomLabel.y + 6, TEXT_SECONDARY)
        button(graphics, "+", zoomIn, mouseX, mouseY, viewport,
            { setZoomAround(1.1f, viewport.x + viewport.w / 2.0, viewport.y + viewport.h / 2.0) }, false, 0)
        button(graphics, "Reset", reset, mouseX, mouseY, viewport,
            { currentMacro.setCanvasView(0f, 0f, 100f); dirty() }, false, 0)
    }

    private fun renderInspector(graphics: GuiGraphicsExtractor, pane: GuiRect, mouseX: Int, mouseY: Int) {
        drawPanel(graphics, pane, "Inspector", "Edit the selected block or event")
        val viewport = GuiRect(pane.x + 6, pane.y + 39, pane.w - 12, maxOf(18, pane.h - 46))
        graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h)
        val startY = viewport.y + 6
        val y = intArrayOf(startY - inspectorScroll)
        val function = selectedFunction
        val step = selectedStep
        val owner = selectedOwner
        val script = selectedScript
        if (tab == Tab.FUNCTIONS && function != null && step == null) {
            drawFunctionProperties(graphics, viewport, y, function, mouseX, mouseY)
        } else if (step != null && owner != null && owner.contains(step)) {
            drawStepProperties(graphics, viewport, y, step, mouseX, mouseY)
        } else if (tab == Tab.CODE && script != null) {
            drawScriptProperties(graphics, viewport, y, script, mouseX, mouseY)
        } else {
            graphics.text(font, trim("Select a block, event hat, or", viewport.w - 12), viewport.x + 6, y[0] + 4, TEXT_SECONDARY)
            graphics.text(font, trim("function to edit its settings.", viewport.w - 12), viewport.x + 6, y[0] + 17, TEXT_MUTED)
        }
        graphics.disableScissor()
        val contentHeight = maxOf(0, y[0] + inspectorScroll - startY)
        inspectorMaxScroll = maxOf(0, contentHeight - maxOf(0, viewport.h - 12))
        inspectorScroll = clamp(inspectorScroll, 0, inspectorMaxScroll)
        if (inspectorMaxScroll > 0) {
            // Scrollbar: the thumb shows how much of the option list is hidden and where the view sits.
            val track = GuiRect(pane.x + pane.w - 5, viewport.y, 3, viewport.h)
            val thumbH = maxOf(12, track.h * viewport.h / (viewport.h + inspectorMaxScroll))
            val thumbY = track.y + (track.h - thumbH) * inspectorScroll / inspectorMaxScroll
            graphics.fill(track.x, track.y, track.x + track.w, track.y + track.h, withOpacity(TEXT_MUTED, 0.25f))
            graphics.fill(track.x, thumbY, track.x + track.w, thumbY + thumbH, TEXT_SECONDARY)
        }
    }

    private fun drawScriptProperties(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        script: MacroScript, mouseX: Int, mouseY: Int) {
        sectionTitle(graphics, viewport, y, "Event stack")
        lineText(graphics, viewport, y, "Trigger", triggerName(script.trigger()))
        val runState = MacroRunner.scriptState(script.id())
        lineText(graphics, viewport, y, "Run state", stateLabel(runState))
        if (runState == MacroRunner.RunState.PAUSED) {
            lineText(graphics, viewport, y, "Paused because", MacroRunner.pausedReason(script.id()))
            lineText(graphics, viewport, y, "Resume rule", "Remove or replace the preserved block first")
            button(graphics, "Resume after resolving block", rowRect(viewport, y[0], 23), mouseX, mouseY,
                viewport, {
                    if (MacroRunner.resumePausedScript(script.id())) flash("Macro resumed after the preserved block.")
                    else flash("Remove or replace the highlighted preserved block before resuming.")
                }, false, categoryColor(Category.WORLD))
            y[0] += 28
        }
        if (isActiveRunState(runState)) {
            button(graphics, "Stop Run", rowRect(viewport, y[0], 23), mouseX, mouseY, viewport, {
                MacroRunner.cancelMacro(macro!!, "macro run stopped in the editor")
                flash("Macro run stopped.")
            }, false, TEXT_ERROR)
            y[0] += 28
        }
        when (script.trigger()) {
            MacroScript.Trigger.KEY_PRESS -> {
                lineText(graphics, viewport, y, "Hotkey", script.keybind().displayName())
                button(graphics, if (ModuleKeybindManager.bindingScript() === script) "Listening for key…" else "Capture hotkey",
                    rowRect(viewport, y[0], 23), mouseX, mouseY, viewport, this::toggleScriptBinding,
                    false, categoryColor(Category.ACTIONS))
                y[0] += 28
            }
            MacroScript.Trigger.WORLD_REGION -> drawRegionProperties(graphics, viewport, y, script.worldRegion(), script, mouseX, mouseY)
            MacroScript.Trigger.CHAT -> drawChatProperties(graphics, viewport, y, script, mouseX, mouseY)
            MacroScript.Trigger.PEST_SPAWNED, MacroScript.Trigger.PEST_COOLDOWN_READY -> {
                sectionTitle(graphics, viewport, y, "Garden event")
                val next = if (script.trigger() == MacroScript.Trigger.PEST_SPAWNED)
                    "Switch to Pest Cooldown Ready" else "Switch to Pest Spawned"
                button(graphics, next, rowRect(viewport, y[0], 23), mouseX, mouseY, viewport, {
                    script.setTrigger(if (script.trigger() == MacroScript.Trigger.PEST_SPAWNED)
                        MacroScript.Trigger.PEST_COOLDOWN_READY else MacroScript.Trigger.PEST_SPAWNED)
                    dirty()
                }, false, categoryColor(Category.ACTIONS))
                y[0] += 28
            }
            else -> Unit
        }
        button(graphics, "Remove this event stack", rowRect(viewport, y[0], 23), mouseX, mouseY,
            viewport, this::removeSelectedScript, false, TEXT_ERROR)
        y[0] += 28
    }

    private fun drawRegionProperties(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        region: MacroWorldRegion, script: MacroScript, mouseX: Int, mouseY: Int) {
        sectionTitle(graphics, viewport, y, "World area")
        button(graphics, if (region.placed()) "Recapture current position" else "Capture current position",
            rowRect(viewport, y[0], 23), mouseX, mouseY, viewport, {
                if (MacroRunner.captureWorldRegion(script)) dirty() else flash("Join a world before capturing an area.")
            }, false, categoryColor(Category.WORLD))
        y[0] += 28
        if (region.placed()) {
            button(graphics, "Clear placement", rowRect(viewport, y[0], 21), mouseX, mouseY, viewport,
                { region.clearPlacement(); dirty() }, false, TEXT_ERROR)
            y[0] += 25
            lineText(graphics, viewport, y, "Center",
                String.format(Locale.ROOT, "%.1f, %.1f, %.1f", region.x(), region.y(), region.z()))
            lineText(graphics, viewport, y, "World", trim(region.worldKey(), viewport.w - 68))
        } else {
            lineText(graphics, viewport, y, "Placement", "Not set")
        }
        button(graphics, "Shape  ·  ${title(region.shape().name)}", rowRect(viewport, y[0], 22),
            mouseX, mouseY, viewport, { region.setShape(next(region.shape())); dirty() }, false, categoryColor(Category.WORLD))
        y[0] += 26
        drawDoubleField(graphics, viewport, y, "Size / radius", "region-size", region::size, region::setSize)
        if (region.shape() == MacroWorldRegion.Shape.RING) {
            drawDoubleField(graphics, viewport, y, "Inner radius", "region-inner", region::innerSize, region::setInnerSize)
        }
        drawDoubleField(graphics, viewport, y, "Vertical tolerance", "region-y", region::yTolerance, region::setYTolerance)
        drawIntField(graphics, viewport, y, "Repeat delay (ms)", "region-delay", region::repeatDelayMillis,
            region::setRepeatDelayMillis)
        drawFloatField(graphics, viewport, y, "Outline width", "region-width", region::lineWidth, region::setLineWidth)
        drawIntField(graphics, viewport, y, "Fill opacity", "region-fill", region::fillOpacity, region::setFillOpacity)
        drawColorPickerControl(graphics, viewport, y, "Region color", region.color(),
            IntConsumer { argb -> region.setColor(argb and 0x00FFFFFF) }, mouseX, mouseY, Category.WORLD)
        toggleRow(graphics, viewport, y, "Once per loaded world", region.oncePerWorld(),
            { region.setOncePerWorld(!region.oncePerWorld()); dirty() }, mouseX, mouseY)
    }

    /** Chat event settings keep the trigger phrase, cooldown, comparison, and replay key together. */
    private fun drawChatProperties(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        script: MacroScript, mouseX: Int, mouseY: Int) {
        sectionTitle(graphics, viewport, y, "Chat message")
        drawTextField(graphics, viewport, y, "Text to look for", "chat-pattern-${script.id()}",
            script::chatPattern, script::setChatPattern)
        toggleRow(graphics, viewport, y, "Match anywhere in the line", script.chatContains(),
            { script.setChatContains(!script.chatContains()); dirty() }, mouseX, mouseY)
        drawIntField(graphics, viewport, y, "Minimum repeat delay (ms)", "chat-delay-${script.id()}",
            script::chatCooldownMillis, script::setChatCooldownMillis)
        lineText(graphics, viewport, y, "Replay hotkey", script.keybind().displayName())
        button(graphics, if (ModuleKeybindManager.bindingScript() === script)
            "Listening for key…" else "Capture replay hotkey",
            rowRect(viewport, y[0], 23), mouseX, mouseY, viewport, this::toggleScriptBinding,
            false, categoryColor(Category.ACTIONS))
        y[0] += 28
        if (javaIsBlank(script.chatPattern())) lineText(graphics, viewport, y, "Status", "Needs text before it can fire")
        lineText(graphics, viewport, y, "Behaviour", "Chat triggers never run while typing")
    }

    private fun drawFunctionProperties(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        function: MacroFunction, mouseX: Int, mouseY: Int) {
        sectionTitle(graphics, viewport, y, "Reusable block")
        drawTextField(graphics, viewport, y, "Name", "function-name-${function.id()}",
            function::name, function::setName)
        lineText(graphics, viewport, y, "Body", "${function.steps().size} blocks")
        sectionTitle(graphics, viewport, y, "Inputs")
        button(graphics, "+  Add input", rowRect(viewport, y[0], 22), mouseX, mouseY, viewport,
            { addFunctionParameter(function) }, false, categoryColor(Category.FUNCTIONS))
        y[0] += 26
        val count = minOf(function.parameters().size, 24)
        for (i in 0 until count) {
            val index = i
            val parameter = function.parameters()[i]!!
            graphics.text(font, trim("Input ${i + 1}", viewport.w - 8), viewport.x + 4, y[0] + 2, TEXT_MUTED)
            y[0] += 13
            val removeW = 25
            val typeW = minOf(68, maxOf(52, viewport.w / 3))
            val nameW = maxOf(48, viewport.w - typeW - removeW - 16)
            drawCompactField(graphics, viewport, y, "Name", "param-name-${function.id()}-$i",
                parameter::name, { value -> updateParameter(function, index, value, null, null) }, nameW)
            val type = GuiRect(viewport.x + 4, y[0], typeW, 19)
            button(graphics, parameter.type().name, type, mouseX, mouseY, viewport,
                { updateParameter(function, index, null, next(parameter.type()), null) }, false, categoryColor(Category.VARIABLES))
            val remove = GuiRect(type.x + type.w + 3, y[0], removeW, 19)
            button(graphics, "×", remove, mouseX, mouseY, viewport,
                { removeFunctionParameter(function, index) }, false, TEXT_ERROR)
            y[0] += 23
            drawTextField(graphics, viewport, y, "Default value", "param-default-${function.id()}-$i",
                parameter::defaultValue, { value -> updateParameter(function, index, null, null, value) })
            y[0] += 2
        }
        if (function.parameters().size > count) lineText(graphics, viewport, y, "More inputs", "Scroll to edit")
    }

    private fun drawStepProperties(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        step: MacroStep, mouseX: Int, mouseY: Int) {
        sectionTitle(graphics, viewport, y, "Block settings")
        lineText(graphics, viewport, y, "Block", blockLabel(step))
        val actions = rowRect(viewport, y[0], 21)
        val actionW = maxOf(1, (actions.w - 3) / 2)
        button(graphics, "Delete", GuiRect(actions.x, actions.y, actionW, actions.h),
            mouseX, mouseY, viewport, this::removeSelectedStep, false, TEXT_ERROR)
        button(graphics, "Undo", GuiRect(actions.x + actionW + 3, actions.y, actions.w - actionW - 3, actions.h),
            mouseX, mouseY, viewport, this::undoLastEdit, false,
            if (documentHistory.canUndo()) categoryColor(Category.FUNCTIONS) else 0)
        y[0] += 25
        if (step is MacroStep.Unknown) {
            sectionTitle(graphics, viewport, y, "Unavailable block")
            lineText(graphics, viewport, y, "Original type", step.originalType())
            lineText(graphics, viewport, y, "Execution", "This macro stops here")
            lineText(graphics, viewport, y, "Storage", "Raw settings are preserved")
            if (step.depthLimited()) lineText(graphics, viewport, y, "Reason", "Too deeply nested")
            drawRawJsonPreview(graphics, viewport, y, step.rawJson())
            return
        }
        drawIntField(graphics, viewport, y, "Delay before (ms)", "delay-min", step::delayMin,
            { value -> step.setDelayMin(value) })
        drawIntField(graphics, viewport, y, "Delay maximum (ms)", "delay-max", step::delayMax,
            { value -> step.setDelayMax(value) })

        when (step) {
            is MacroStep.Command -> drawTextField(graphics, viewport, y, "Command", "command", step::command, step::setCommand)
            is MacroStep.Chat -> {
                drawTextField(graphics, viewport, y, "Message or command", "chat", step::message, step::setMessage)
                lineText(graphics, viewport, y, "Commands", "Start the message with /.")
            }
            is MacroStep.Comment -> drawTextField(graphics, viewport, y, "Editor note", "comment-text", step::text, step::setText)
            is MacroStep.Wait -> {
                button(graphics, "Mode  ·  ${title(step.mode().name)}", rowRect(viewport, y[0], 22), mouseX, mouseY,
                    viewport, {
                        step.setMode(if (step.mode() == MacroStep.Wait.Mode.DURATION)
                            MacroStep.Wait.Mode.CONDITION else MacroStep.Wait.Mode.DURATION)
                        dirty()
                    }, false, categoryColor(Category.CONTROL))
                y[0] += 26
                if (step.mode() == MacroStep.Wait.Mode.DURATION) {
                    drawIntField(graphics, viewport, y, "Minimum wait (ms)", "wait-min", step::minMillis,
                        { value -> step.setMinMillis(value) })
                    drawIntField(graphics, viewport, y, "Maximum wait (ms)", "wait-max", step::maxMillis,
                        { value -> step.setMaxMillis(value) })
                } else {
                    drawConditionButton(graphics, viewport, y, "Wait until", step.condition(), step::setCondition, mouseX, mouseY, 0)
                    drawConditionProperties(graphics, viewport, y, step.condition(), step::setCondition, mouseX, mouseY, 0)
                }
            }
            is MacroStep.Key -> {
                button(graphics, "Input  ·  ${title(step.inputMode().name)}", rowRect(viewport, y[0], 22), mouseX, mouseY,
                    viewport, { step.setInputMode(next(step.inputMode())); dirty() }, false, categoryColor(Category.INPUT))
                y[0] += 26
                when (step.inputMode()) {
                    MacroStep.Key.InputMode.KEYBOARD -> {
                        lineText(graphics, viewport, y, "Key", if (javaIsBlank(step.key())) "Not set" else step.key())
                        button(graphics, if (ModuleKeybindManager.bindingKeyStep() === step)
                            "Listening for key…" else "Capture key",
                            rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
                                if (ModuleKeybindManager.bindingKeyStep() === step) ModuleKeybindManager.cancelBinding()
                                else ModuleKeybindManager.beginKeyStepBinding(step)
                            }, false, categoryColor(Category.INPUT))
                        y[0] += 26
                    }
                    MacroStep.Key.InputMode.MOUSE -> {
                        button(graphics, "Button  ·  ${title(step.mouseButton().name)}", rowRect(viewport, y[0], 22),
                            mouseX, mouseY, viewport, { step.setMouseButton(next(step.mouseButton())); dirty() },
                            false, categoryColor(Category.INPUT))
                        y[0] += 26
                    }
                    else -> {
                        drawIntField(graphics, viewport, y, "Hotbar slot (1–9)", "input-hotbar-slot",
                            step::hotbarSlot, step::setHotbarSlot)
                        lineText(graphics, viewport, y, "Safety", "Only outside inventories")
                    }
                }
                if (step.inputMode() != MacroStep.Key.InputMode.HOTBAR) {
                    toggleRow(graphics, viewport, y, "Hold instead of press", step.hold(),
                        { step.setHold(!step.hold()); dirty() }, mouseX, mouseY)
                    if (step.hold()) {
                        drawIntField(graphics, viewport, y, "Minimum hold (ms)", "key-hold-min", step::holdMinMillis,
                            { value -> step.setHoldRange(minOf(value, step.holdMaxMillis()), step.holdMaxMillis()) })
                        drawIntField(graphics, viewport, y, "Maximum hold (ms)", "key-hold-max", step::holdMaxMillis,
                            { value -> step.setHoldRange(step.holdMinMillis(), maxOf(step.holdMinMillis(), value)) })
                    }
                }
            }
            is MacroStep.SelectHotbarSlot -> {
                drawIntField(graphics, viewport, y, "Hotbar slot (1–9)", "hotbar-slot", step::slot, step::setSlot)
                lineText(graphics, viewport, y, "Safety", "Only outside inventories")
            }
            is MacroStep.MouseButton -> {
                button(graphics, "Button  ·  ${title(step.button().name)}", rowRect(viewport, y[0], 22),
                    mouseX, mouseY, viewport, { step.setButton(next(step.button())); dirty() }, false, categoryColor(Category.INPUT))
                y[0] += 26
                toggleRow(graphics, viewport, y, "Hold instead of click", step.hold(),
                    { step.setHold(!step.hold()); dirty() }, mouseX, mouseY)
                if (step.hold()) drawIntField(graphics, viewport, y, "Hold duration (ms)", "mouse-hold",
                    step::holdMillis, step::setHoldMillis)
            }
            is MacroStep.BlockPlayerInput -> drawIntField(graphics, viewport, y, "Block duration (ms)",
                "input-block-duration", step::durationMillis, step::setDurationMillis)
            is MacroStep.SetView -> {
                drawFloatField(graphics, viewport, y, "Yaw (-180 to 180)", "set-view-yaw", step::yaw, step::setYaw)
                drawFloatField(graphics, viewport, y, "Pitch (-90 to 90)", "set-view-pitch", step::pitch, step::setPitch)
                drawIntField(graphics, viewport, y, "Give up after (ms)", "set-view-time", step::maxMillis, step::setMaxMillis)
                lineText(graphics, viewport, y, "Turn", "Speed follows Rotation Engine; ends once aimed or on timeout")
            }
            is MacroStep.UpdateVariable -> {
                drawVariableTarget(graphics, viewport, y, "Target", step.name(), step.targetsGlobal(), step.globalVariableId(),
                    { definition ->
                        step.setGlobalVariableId(definition.id()); step.setName(definition.name())
                        step.setValue(withType(step.value(), definition.type())); dirty()
                    }, { step.setGlobalVariableId(null); dirty() }, mouseX, mouseY)
                if (!step.targetsGlobal()) drawTextField(graphics, viewport, y, "Local variable name",
                    "update-var-name", step::name, step::setName)
                button(graphics, "Operation  ·  ${title(step.operation().name)}", rowRect(viewport, y[0], 22), mouseX, mouseY,
                    viewport, { step.setOperation(nextEnum(step.operation())); dirty() }, false, categoryColor(Category.VARIABLES))
                y[0] += 26
                if (step.operation() == MacroStep.UpdateVariable.Operation.SET) {
                    drawMacroValueEditor(graphics, viewport, y, "Value", "update-var-value", step.value(),
                        step.value().type(), step::setValue, mouseX, mouseY)
                } else drawDoubleField(graphics, viewport, y, "Amount", "update-var-amount", step::amount, step::setAmount)
            }
            is MacroStep.FunctionCall -> drawFunctionCallProperties(graphics, viewport, y, step, mouseX, mouseY)
            is MacroStep.MacroCall -> drawMacroCallProperties(graphics, viewport, y, step, mouseX, mouseY)
            is MacroStep.IfElse -> {
                toggleRow(graphics, viewport, y, "Else branch", step.elseEnabled(), {
                    val enabled = !step.elseEnabled()
                    step.setElseEnabled(enabled)
                    val autoExpanded = enabled && !ClickGuiState.macroNodeExpanded(step)
                    if (autoExpanded) ClickGuiState.toggleMacroNode(step)
                    dirty()
                    logEditorDiagnostic("EVENT action=else_branch,enabled=$enabled,expanded=${ClickGuiState.macroNodeExpanded(step)}," +
                        "autoExpanded=$autoExpanded,thenBlocks=${step.thenSteps().size},elseBlocks=${step.elseSteps().size}")
                }, mouseX, mouseY)
                drawConditionButton(graphics, viewport, y, "Condition", step.condition(), step::setCondition, mouseX, mouseY, 0)
                drawConditionProperties(graphics, viewport, y, step.condition(), step::setCondition, mouseX, mouseY, 0)
            }
            is MacroStep.Repeat -> {
                button(graphics, "Mode  ·  ${title(step.mode().name)}", rowRect(viewport, y[0], 22), mouseX, mouseY,
                    viewport, { step.setMode(next(step.mode())); dirty() }, false, categoryColor(Category.CONTROL))
                y[0] += 26
                when (step.mode()) {
                    MacroStep.Repeat.Mode.COUNT -> drawIntField(graphics, viewport, y, "Repeat count", "repeat-count",
                        step::count, step::setCount)
                    MacroStep.Repeat.Mode.UNTIL -> {
                        drawConditionButton(graphics, viewport, y, "Stop condition", step.condition(), step::setCondition, mouseX, mouseY, 0)
                        drawConditionProperties(graphics, viewport, y, step.condition(), step::setCondition, mouseX, mouseY, 0)
                    }
                    else -> Unit
                }
            }
            is MacroStep.RepeatUntil -> {
                drawConditionButton(graphics, viewport, y, "Stop condition", step.condition(), step::setCondition, mouseX, mouseY, 0)
                drawConditionProperties(graphics, viewport, y, step.condition(), step::setCondition, mouseX, mouseY, 0)
            }
            is MacroStep.Switch -> {
                drawVariableTarget(graphics, viewport, y, "Switch value", step.name(), step.targetsGlobal(), step.globalVariableId(),
                    { definition -> step.setGlobalVariableId(definition.id()); step.setName(definition.name()); dirty() },
                    { step.setGlobalVariableId(null); dirty() }, mouseX, mouseY)
                if (!step.targetsGlobal()) drawTextField(graphics, viewport, y, "Local variable name",
                    "switch-var-name", step::name, step::setName)
                for (index in step.cases().indices) {
                    val caseIndex = index
                    val branch = step.cases()[index]
                    drawTextField(graphics, viewport, y, "Case ${index + 1} value", "switch-case-$index",
                        branch::value, branch::setValue)
                    button(graphics, "Remove case ${index + 1}", rowRect(viewport, y[0], 19), mouseX, mouseY,
                        viewport, { step.cases().removeAt(caseIndex); dirty() }, false, TEXT_ERROR)
                    y[0] += 23
                }
                if (step.cases().size < 16) {
                    button(graphics, "+ Add case", rowRect(viewport, y[0], 21), mouseX, mouseY, viewport,
                        { step.addCase(); dirty() }, false, categoryColor(Category.CONTROL))
                    y[0] += 25
                }
            }
            is MacroStep.WaitUntil -> {
                drawConditionButton(graphics, viewport, y, "Wait condition", step.condition(), step::setCondition, mouseX, mouseY, 0)
                drawConditionProperties(graphics, viewport, y, step.condition(), step::setCondition, mouseX, mouseY, 0)
            }
            is MacroStep.InventoryClick -> {
                button(graphics, "Target  ·  ${title(step.target().name)}", rowRect(viewport, y[0], 22), mouseX, mouseY,
                    viewport, { step.setTarget(if (step.target() == MacroStep.InventoryClick.Target.SLOT)
                        MacroStep.InventoryClick.Target.ITEM else MacroStep.InventoryClick.Target.SLOT); dirty() },
                    false, categoryColor(Category.INVENTORY))
                y[0] += 26
                if (step.target() == MacroStep.InventoryClick.Target.SLOT) {
                    drawIntField(graphics, viewport, y, "Slot id", "inventory-click-slot", step::slotId, step::setSlotId)
                    lineText(graphics, viewport, y, "Slot IDs", "Enable Dev → Slot IDs while a menu is open")
                } else {
                    drawTextField(graphics, viewport, y, "Item name", "inventory-click-name", step::name, step::setName)
                    toggleRow(graphics, viewport, y, "Partial name match", step.contains(),
                        { step.setContains(!step.contains()); dirty() }, mouseX, mouseY)
                    drawIntField(graphics, viewport, y, "Occurrence", "inventory-click-occurrence", step::occurrence, step::setOccurrence)
                    button(graphics, "Search area · ${itemScopeLabel(step.scope())}", rowRect(viewport, y[0], 22),
                        mouseX, mouseY, viewport, { step.setScope(nextItemScope(step.scope())); dirty() }, false, categoryColor(Category.INVENTORY))
                    y[0] += 26
                }
                button(graphics, "Mouse button · ${mouseButtonLabel(step.button())}", rowRect(viewport, y[0], 22),
                    mouseX, mouseY, viewport, { step.setButton((step.button() + 1) % 3); dirty() }, false, categoryColor(Category.INVENTORY))
                y[0] += 26
                toggleRow(graphics, viewport, y, "Shift click", step.shift(),
                    { step.setShift(!step.shift()); dirty() }, mouseX, mouseY)
            }
            is MacroStep.Scroll -> {
                button(graphics, "Direction · ${title(step.direction().name)}", rowRect(viewport, y[0], 22),
                    mouseX, mouseY, viewport, { step.setDirection(nextEnum(step.direction())); dirty() }, false, categoryColor(Category.INPUT))
                y[0] += 26
                drawIntField(graphics, viewport, y, "Wheel steps", "scroll-amount", step::amount, step::setAmount)
            }
            is MacroStep.Title -> {
                drawTextField(graphics, viewport, y, "Title text", "title-text", step::text, step::setText)
                button(graphics, "Font · ${titleFontLabel(step.font())}", rowRect(viewport, y[0], 22), mouseX, mouseY,
                    viewport, { openTitleFontPicker(step) }, false, categoryColor(Category.DISPLAY))
                y[0] += 26
                drawFloatField(graphics, viewport, y, "Text scale", "title-scale", step::scale, step::setScale)
                drawColorPickerControl(graphics, viewport, y, "Title text color", step.textColor(),
                    IntConsumer { argb -> step.setTextColor(argb and 0x00FFFFFF) }, mouseX, mouseY, Category.DISPLAY)
                toggleRow(graphics, viewport, y, "Show background", step.showBackground(),
                    { step.setShowBackground(!step.showBackground()); dirty() }, mouseX, mouseY)
                if (step.showBackground()) {
                    drawColorPickerControl(graphics, viewport, y, "Title background color", step.backgroundColor(),
                        IntConsumer { argb -> step.setBackgroundColor(argb and 0x00FFFFFF) }, mouseX, mouseY, Category.DISPLAY)
                    drawIntField(graphics, viewport, y, "Background opacity (0–255)", "title-background-opacity",
                        step::backgroundOpacity, step::setBackgroundOpacity)
                }
                drawIntField(graphics, viewport, y, "Fade in (ms)", "title-fade-in", step::fadeInMillis, step::setFadeInMillis)
                drawIntField(graphics, viewport, y, "Visible (ms)", "title-hold", step::holdMillis, step::setHoldMillis)
                drawIntField(graphics, viewport, y, "Fade out (ms)", "title-fade-out", step::fadeOutMillis, step::setFadeOutMillis)
            }
            is MacroStep.Sound -> {
                drawTextField(graphics, viewport, y, "Sound id", "sound-id", step::soundId, step::setSoundId)
                button(graphics, "Browse registered sounds", rowRect(viewport, y[0], 22), mouseX, mouseY,
                    viewport, { openSoundPicker(step) }, false, categoryColor(Category.DISPLAY))
                y[0] += 26
            }
            is MacroStep.WorldSwitch -> {
                button(graphics, "Destination · ${step.target().label()}", rowRect(viewport, y[0], 22),
                    mouseX, mouseY, viewport, { openWorldSwitchPicker(step) }, false, categoryColor(Category.WORLD))
                y[0] += 26
            }
            is MacroStep.StartBlockPlayerInput -> {
                lineText(graphics, viewport, y, "Input", "Starts blocking gameplay controls")
                lineText(graphics, viewport, y, "Safety", "Released on every run exit")
            }
            is MacroStep.StopBlockPlayerInput -> lineText(graphics, viewport, y, "Input", "Stops the current input block")
            is MacroStep.CloseScreen -> lineText(graphics, viewport, y, "Action", "Closes the current screen")
            else -> lineText(graphics, viewport, y, "Type", step.type())
        }
        val script = selectedScript
        if (script != null && script.trigger() == MacroScript.Trigger.WORLD_REGION) {
            sectionTitle(graphics, viewport, y, "Event area")
            drawRegionProperties(graphics, viewport, y, script.worldRegion(), script, mouseX, mouseY)
        }
    }

    private fun drawFunctionCallProperties(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        call: MacroStep.FunctionCall, mouseX: Int, mouseY: Int) {
        val current = MacrosModule.INSTANCE.function(call.functionId())
        button(graphics, "Function  ·  ${if (current == null) "Choose" else trim(current.name(), 100)}",
            rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
                val options = ArrayList<PickerOption>()
                for (function in MacrosModule.INSTANCE.functions()) {
                    options.add(PickerOption(function.name()) {
                        call.setFunctionId(function.id())
                        call.arguments().clear()
                        dirty()
                    })
                }
                if (options.isEmpty()) { flash("Create a function in My Blocks first."); return@button }
                openPicker("Choose a function", options)
            }, false, categoryColor(Category.FUNCTIONS))
        y[0] += 26
        if (current == null) {
            lineText(graphics, viewport, y, "Inputs", "Unresolved function reference")
            return
        }
        lineText(graphics, viewport, y, "Inputs", "${current.parameters().size} named values")
        for (i in current.parameters().indices) {
            val parameter = current.parameters()[i]!!
            while (call.arguments().size <= i) call.arguments().add(MacroValue.literal(parameter.type(), parameter.defaultValue()))
            val value = call.arguments()[i]
            drawMacroValueEditor(graphics, viewport, y, "${parameter.name()}  ·  ${parameter.type().name}",
                "call-arg-${call.functionId()}-$i", value, parameter.type(), Consumer { input ->
                    if (i < call.arguments().size) call.arguments()[i] = input
                }, mouseX, mouseY)
        }
    }

    private fun openTitleFontPicker(title: MacroStep.Title) {
        val options = listOf(
            PickerOption("Minecraft Default") { title.setFont("minecraft:default"); dirty() },
            PickerOption("Uniform Unicode") { title.setFont("minecraft:uniform"); dirty() })
        openPicker("Choose title font", options)
    }

    private fun openSoundPicker(sound: MacroStep.Sound) {
        val ids = ArrayList(BuiltInRegistries.SOUND_EVENT.keySet())
        ids.sortWith(Comparator { first, second -> first.compareTo(second) })
        val options = ArrayList<PickerOption>(ids.size)
        for (id in ids) options.add(PickerOption(id.toString()) { sound.setSoundId(id.toString()); dirty() })
        openPicker("Choose sound event · type to search", options, true)
    }

    private fun openWorldSwitchPicker(worldSwitch: MacroStep.WorldSwitch) {
        val options = ArrayList<PickerOption>()
        for (island in Island.values()) if (island.selectable()) {
            options.add(PickerOption(island.label()) { worldSwitch.setTarget(island); dirty() })
        }
        openPicker("Choose destination", options)
    }

    private fun titleFontLabel(font: String?): String =
        if (font == "minecraft:uniform") "Uniform Unicode" else "Minecraft Default"

    private fun colorText(color: Int): String = String.format(Locale.ROOT, "%06X", color and 0xFFFFFF)

    private fun drawColorPickerControl(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        label: String, color: Int, setter: IntConsumer, mouseX: Int, mouseY: Int, category: Category) {
        val opaque = 0xFF000000.toInt() or (color and 0x00FFFFFF)
        button(graphics, "$label · #${colorText(color)}", rowRect(viewport, y[0], 22), mouseX, mouseY,
            viewport, {
                minecraft.gui.setScreen(GuiColorPickerScreen(this, label, opaque, false) { argb ->
                    setter.accept(argb)
                    dirty()
                })
            }, false, categoryColor(category))
        y[0] += 26
    }

    private fun drawMacroCallProperties(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        call: MacroStep.MacroCall, mouseX: Int, mouseY: Int) {
        val macros = MacrosModule.INSTANCE.macros()
        val targets = ArrayList<MacroDefinition>()
        for (candidate in macros) if (candidate !== macro) targets.add(candidate)
        val current = MacrosModule.INSTANCE.macro(call.macroId())
        button(graphics, "Target  ·  ${if (current == null) "Choose macro" else trim(current.name(), 100)}",
            rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
                val options = ArrayList<PickerOption>()
                for (candidate in MacrosModule.INSTANCE.macros()) {
                    if (candidate === macro) continue
                    options.add(PickerOption(candidate.name()) { call.setMacroId(candidate.id()); dirty() })
                }
                if (options.isEmpty()) { flash("Create another macro before adding a call."); return@button }
                openPicker("Choose a macro", options)
            }, false, categoryColor(Category.FUNCTIONS))
        y[0] += 27
        drawConditionButton(graphics, viewport, y, "Condition", call.condition(), call::setCondition, mouseX, mouseY, 0)
        drawConditionProperties(graphics, viewport, y, call.condition(), call::setCondition, mouseX, mouseY, 0)
        lineText(graphics, viewport, y, "Behavior", "Waits for the target’s On Call stack")
        if (call.condition() != null) lineText(graphics, viewport, y, "When false", "The call is skipped; the workflow continues")
        lineText(graphics, viewport, y, "Available", "${targets.size} other macros")
    }

    private fun drawVariableTarget(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, label: String,
        localName: String, global: Boolean, globalId: String?, selectGlobal: Consumer<MacroVariableStore.Definition>,
        selectLocal: Runnable, mouseX: Int, mouseY: Int) {
        val target = if (global) globalVariableName(globalId) else "Local · $localName"
        button(graphics, "$label  ·  $target", rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
            val options = ArrayList<PickerOption>()
            options.add(PickerOption("Use a local variable") { selectLocal.run(); dirty() })
            for (definition in MacrosModule.INSTANCE.globalVariables().definitions()) {
                options.add(PickerOption("Global · ${definition.name()} · ${definition.type().name}") {
                    selectGlobal.accept(definition)
                    dirty()
                })
            }
            if (options.size == 1) {
                flash("Create a global variable with the Globals button first.")
                return@button
            }
            openPicker("Choose the variable scope", options)
        }, false, if (global) categoryColor(Category.VARIABLES) else 0)
        y[0] += 26
    }

    private fun drawMacroValueEditor(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        fieldLabel: String, fieldId: String, value: MacroValue, expectedType: MacroValue.Type?,
        setter: Consumer<MacroValue>, mouseX: Int, mouseY: Int) {
        val type = expectedType ?: MacroValue.Type.TEXT
        val mode = if (type == MacroValue.Type.ITEM_COUNT && value.scope() == MacroValue.Scope.NONE)
            "Carried inventory count" else when (value.scope()) {
            MacroValue.Scope.GLOBAL -> "Global · ${globalVariableName(value.value())}"
            MacroValue.Scope.LOCAL -> "Local variable"
            MacroValue.Scope.NONE -> "Literal value"
        }
        button(graphics, "Source  ·  $mode", rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
            val options = ArrayList<PickerOption>()
            if (type == MacroValue.Type.ITEM_COUNT) {
                options.add(PickerOption("Carried inventory item count") {
                    setter.accept(MacroValue.itemCount("", "")); dirty()
                })
            } else options.add(PickerOption("Literal value") { setter.accept(MacroValue.literal(type, "")); dirty() })
            options.add(PickerOption("Local variable") { setter.accept(MacroValue.variable(type, "value")); dirty() })
            for (definition in MacrosModule.INSTANCE.globalVariables().definitions()) {
                options.add(PickerOption("Global · ${definition.name()}") {
                    setter.accept(MacroValue.globalVariable(type, definition.id())); dirty()
                })
            }
            openPicker("Choose the input source", options)
        }, false, if (value.scope() == MacroValue.Scope.GLOBAL) categoryColor(Category.VARIABLES) else 0)
        y[0] += 26
        if (value.scope() == MacroValue.Scope.GLOBAL) {
            lineText(graphics, viewport, y, fieldLabel, "${globalVariableName(value.value())} · shared value")
            return
        }
        if (type == MacroValue.Type.ITEM_COUNT && value.scope() == MacroValue.Scope.NONE) {
            drawTextField(graphics, viewport, y, "$fieldLabel · SkyBlock ID", "$fieldId-skyblock-id",
                value::skyblockItemId, Consumer { input -> setter.accept(MacroValue.itemCount(input, value.vanillaItemId())) })
            drawTextField(graphics, viewport, y, "$fieldLabel · Vanilla ID fallback", "$fieldId-vanilla-id",
                value::vanillaItemId, Consumer { input -> setter.accept(MacroValue.itemCount(value.skyblockItemId(), input)) })
            return
        }
        val label = if (value.scope() == MacroValue.Scope.LOCAL) "$fieldLabel · local name" else fieldLabel
        drawTextField(graphics, viewport, y, label, fieldId, value::value, Consumer { input ->
            setter.accept(if (value.scope() == MacroValue.Scope.LOCAL) MacroValue.variable(type, input)
                else MacroValue.literal(type, input))
        })
    }

    private fun withType(value: MacroValue?, type: MacroValue.Type): MacroValue {
        if (type == MacroValue.Type.ITEM_COUNT && (value == null || !value.variableReference())) {
            return MacroValue.itemCount(value?.skyblockItemId() ?: "", value?.vanillaItemId() ?: "")
        }
        if (value == null || !value.variableReference()) return MacroValue.literal(type, value?.value() ?: "")
        return if (value.scope() == MacroValue.Scope.GLOBAL) MacroValue.globalVariable(type, value.value())
        else MacroValue.variable(type, value.value())
    }

    private fun globalVariableName(id: String?): String {
        val definition = MacrosModule.INSTANCE.globalVariables().definition(id)
        return definition?.name() ?: "Missing global"
    }

    private fun drawConditionButton(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, label: String,
        condition: MacroCondition?, setter: Consumer<MacroCondition>, mouseX: Int, mouseY: Int, depth: Int) {
        val current = conditionLabel(condition)
        button(graphics, "$label  ·  $current", rowRect(viewport, y[0], 22), mouseX, mouseY, viewport,
            { openConditionPicker(label, setter, depth) }, false, categoryColor(Category.CONTROL))
        y[0] += 26
    }

    private fun drawConditionProperties(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        condition: MacroCondition?, setter: Consumer<MacroCondition>, mouseX: Int, mouseY: Int, depth: Int) {
        if (depth >= MAX_CONDITION_DEPTH && isCompoundCondition(condition)) {
            lineText(graphics, viewport, y, "Nesting limit", "Choose a simpler condition above")
            return
        }
        when (condition) {
            is MacroCondition.Always -> toggleRow(graphics, viewport, y,
                "Result · ${if (condition.expected()) "Always true" else "Always false"}", condition.expected(),
                { setter.accept(MacroCondition.Always(!condition.expected())); dirty() }, mouseX, mouseY)
            is MacroCondition.World -> toggleRow(graphics, viewport, y, "Player is in a world", condition.mustBeInWorld(),
                { setter.accept(MacroCondition.World(!condition.mustBeInWorld())); dirty() }, mouseX, mouseY)
            is MacroCondition.Screen -> {
                drawTextField(graphics, viewport, y, "Screen title (blank = any)", "condition-screen-title",
                    Supplier { condition.title().orEmpty() }, Consumer { value ->
                        setter.accept(MacroCondition.Screen(value, condition.mustBeOpen(), condition.contains()))
                    })
                toggleRow(graphics, viewport, y, "Screen must be open", condition.mustBeOpen(),
                    { setter.accept(MacroCondition.Screen(condition.title(), !condition.mustBeOpen(), condition.contains())); dirty() }, mouseX, mouseY)
                toggleRow(graphics, viewport, y, "Match title contains", condition.contains(),
                    { setter.accept(MacroCondition.Screen(condition.title(), condition.mustBeOpen(), !condition.contains())); dirty() }, mouseX, mouseY)
            }
            is MacroCondition.Slot -> {
                drawIntField(graphics, viewport, y, "Slot id", "condition-slot-id", condition::slotId,
                    IntConsumer { value -> setter.accept(MacroCondition.Slot(maxOf(0, value), condition.mustExist())) })
                toggleRow(graphics, viewport, y, "Slot must exist", condition.mustExist(),
                    { setter.accept(MacroCondition.Slot(condition.slotId(), !condition.mustExist())); dirty() }, mouseX, mouseY)
            }
            is MacroCondition.Item -> {
                drawTextField(graphics, viewport, y, "Item name", "condition-item-name",
                    Supplier { condition.name().orEmpty() }, Consumer { value ->
                        setter.accept(MacroCondition.Item(value, condition.mustExist(), condition.contains(), condition.scope()))
                    })
                toggleRow(graphics, viewport, y, "Item must exist", condition.mustExist(),
                    { setter.accept(MacroCondition.Item(condition.name(), !condition.mustExist(), condition.contains(), condition.scope())); dirty() }, mouseX, mouseY)
                toggleRow(graphics, viewport, y, "Partial name match", condition.contains(),
                    { setter.accept(MacroCondition.Item(condition.name(), condition.mustExist(), !condition.contains(), condition.scope())); dirty() }, mouseX, mouseY)
                button(graphics, "Search area · ${itemScopeLabel(condition.scope().serializedName())}", rowRect(viewport, y[0], 22),
                    mouseX, mouseY, viewport, {
                        val scopes = MacroCondition.ItemScope.values()
                        val nextIndex = (condition.scope().ordinal + 1) % scopes.size
                        setter.accept(MacroCondition.Item(condition.name(), condition.mustExist(), condition.contains(), scopes[nextIndex]))
                        dirty()
                    }, false, categoryColor(Category.INPUT))
                y[0] += 26
            }
            is MacroCondition.InventoryCount -> {
                drawTextField(graphics, viewport, y, "SkyBlock item ID (preferred)", "condition-count-skyblock-id",
                    condition::skyBlockItemId, Consumer { value -> setter.accept(MacroCondition.InventoryCount(value,
                        condition.vanillaItemId(), condition.operator(), condition.threshold())) })
                drawTextField(graphics, viewport, y, "Vanilla item ID (fallback)", "condition-count-vanilla-id",
                    condition::vanillaItemId, Consumer { value -> setter.accept(MacroCondition.InventoryCount(
                        condition.skyBlockItemId(), value, condition.operator(), condition.threshold())) })
                button(graphics, "Compare · ${title(condition.operator().name.replace('_', ' '))}",
                    rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
                        val options = ArrayList<PickerOption>()
                        for (operator in MacroCondition.Variable.Operator.values()) {
                            options.add(PickerOption(title(operator.name.replace('_', ' '))) {
                                setter.accept(MacroCondition.InventoryCount(condition.skyBlockItemId(),
                                    condition.vanillaItemId(), operator, condition.threshold()))
                                dirty()
                            })
                        }
                        openPicker("Choose count comparison", options)
                    }, false, categoryColor(Category.CONTROL))
                y[0] += 26
                drawIntField(graphics, viewport, y, "Item count threshold", "condition-count-threshold",
                    condition::threshold, IntConsumer { value -> setter.accept(MacroCondition.InventoryCount(
                        condition.skyBlockItemId(), condition.vanillaItemId(), condition.operator(), value)) })
            }
            is MacroCondition.Chat -> {
                drawTextField(graphics, viewport, y, "Chat text", "condition-chat-text", { condition.text() ?: "" },
                    Consumer { value -> setter.accept(MacroCondition.Chat(value, condition.contains())) })
                toggleRow(graphics, viewport, y, "Match contains", condition.contains(),
                    { setter.accept(MacroCondition.Chat(condition.text(), !condition.contains())); dirty() }, mouseX, mouseY)
            }
            is MacroCondition.All -> drawCompoundConditionChildren(graphics, viewport, y, "AND requirement",
                condition.children(), Consumer { children -> setter.accept(MacroCondition.All(children)) },
                true, mouseX, mouseY, depth)
            is MacroCondition.Any -> drawCompoundConditionChildren(graphics, viewport, y, "OR option",
                condition.children(), Consumer { children -> setter.accept(MacroCondition.Any(children)) },
                false, mouseX, mouseY, depth)
            is MacroCondition.Not -> {
                drawConditionButton(graphics, viewport, y, "Inverted condition", condition.child(),
                    Consumer { child -> setter.accept(MacroCondition.Not(child)); dirty() }, mouseX, mouseY, depth + 1)
                drawConditionProperties(graphics, viewport, y, condition.child(),
                    Consumer { child -> setter.accept(MacroCondition.Not(child)); dirty() }, mouseX, mouseY, depth + 1)
            }
            is MacroCondition.Variable -> {
                drawVariableTarget(graphics, viewport, y, "Compare variable", condition.name(), condition.comparesGlobal(),
                    condition.globalVariableId(), Consumer { definition -> setter.accept(MacroCondition.Variable(
                        definition.name(), condition.operator(), condition.value(), definition.id())) },
                    Runnable { setter.accept(MacroCondition.Variable(condition.name(), condition.operator(), condition.value(), null)) },
                    mouseX, mouseY)
                if (!condition.comparesGlobal()) drawTextField(graphics, viewport, y, "Local variable",
                    "condition-variable-name", condition::name, Consumer { value ->
                        setter.accept(MacroCondition.Variable(value, condition.operator(), condition.value(), null))
                    })
                button(graphics, "Compare  ·  ${title(condition.operator().name.replace('_', ' '))}",
                    rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
                        val options = ArrayList<PickerOption>()
                        for (operator in MacroCondition.Variable.Operator.values()) {
                            options.add(PickerOption(title(operator.name.replace('_', ' '))) {
                                setter.accept(MacroCondition.Variable(condition.name(), operator,
                                    condition.value(), condition.globalVariableId()))
                                dirty()
                            })
                        }
                        openPicker("Choose comparison", options)
                    }, false, categoryColor(Category.CONTROL))
                y[0] += 26
                drawMacroValueEditor(graphics, viewport, y, "Compare to", "condition-variable-value",
                    condition.value(), condition.value().type(), Consumer { value ->
                        setter.accept(MacroCondition.Variable(condition.name(), condition.operator(), value,
                            condition.globalVariableId()))
                    }, mouseX, mouseY)
            }
            is MacroCondition.Hypixel -> {
                button(graphics, "Hypixel value · ${title(condition.field().name.replace('_', ' '))}",
                    rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
                        val options = ArrayList<PickerOption>()
                        for (field in MacroCondition.Hypixel.Field.values()) {
                            options.add(PickerOption(title(field.name.replace('_', ' '))) {
                                setter.accept(MacroCondition.Hypixel(field, MacroCondition.Hypixel.Operator.EQUALS,
                                    condition.expected(), condition.argument(), condition.plotId()))
                                dirty()
                            })
                        }
                        openPicker("Choose a Hypixel value", options)
                    }, false, categoryColor(Category.WORLD))
                y[0] += 26
                button(graphics, "Compare · ${title(condition.operator().name.replace('_', ' '))}",
                    rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
                        val options = ArrayList<PickerOption>()
                        for (operator in MacroCondition.Hypixel.Operator.values()) {
                            options.add(PickerOption(title(operator.name.replace('_', ' '))) {
                                setter.accept(MacroCondition.Hypixel(condition.field(), operator, condition.expected(),
                                    condition.argument(), condition.plotId()))
                                dirty()
                            })
                        }
                        openPicker("Choose comparison", options)
                    }, false, categoryColor(Category.CONTROL))
                y[0] += 26
                if (condition.field() == MacroCondition.Hypixel.Field.PARTY_HAS_MEMBER) {
                    drawTextField(graphics, viewport, y, "Party member name", "hypixel-party-member",
                        Supplier { condition.argument() }, Consumer { value -> setter.accept(MacroCondition.Hypixel(
                            condition.field(), condition.operator(), condition.expected(), value, condition.plotId())) })
                }
                if (condition.field() == MacroCondition.Hypixel.Field.GARDEN_PEST_STATUS ||
                    condition.field() == MacroCondition.Hypixel.Field.GARDEN_PEST_COUNT) {
                    drawIntField(graphics, viewport, y, "Plot id (-1 = current)", "hypixel-plot-id", condition::plotId,
                        IntConsumer { value -> setter.accept(MacroCondition.Hypixel(condition.field(), condition.operator(),
                            condition.expected(), condition.argument(), value)) })
                }
                drawTextField(graphics, viewport, y, "Expected value", "hypixel-expected", condition::expected,
                    Consumer { value -> setter.accept(MacroCondition.Hypixel(condition.field(), condition.operator(),
                        value, condition.argument(), condition.plotId())) })
                lineText(graphics, viewport, y, "Data requirement", "Missing or stale values stop this macro.")
            }
            is MacroCondition.Player -> {
                button(graphics, "Player value · ${title(condition.field().name.replace('_', ' '))}",
                    rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
                        val options = ArrayList<PickerOption>()
                        for (field in MacroCondition.Player.Field.values()) {
                            options.add(PickerOption(title(field.name.replace('_', ' '))) {
                                setter.accept(MacroCondition.Player(field, condition.operator(), "", ""))
                                dirty()
                            })
                        }
                        openPicker("Choose a player value", options)
                    }, false, categoryColor(Category.WORLD))
                y[0] += 26
                button(graphics, "Compare · ${title(condition.operator().name.replace('_', ' '))}",
                    rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
                        val options = ArrayList<PickerOption>()
                        for (operator in MacroCondition.Hypixel.Operator.values()) {
                            options.add(PickerOption(title(operator.name.replace('_', ' '))) {
                                setter.accept(MacroCondition.Player(condition.field(), operator, condition.expected(),
                                    condition.argument()))
                                dirty()
                            })
                        }
                        openPicker("Choose comparison", options)
                    }, false, categoryColor(Category.CONTROL))
                y[0] += 26
                val expectedLabel = when (condition.field()) {
                    MacroCondition.Player.Field.YAW -> "Yaw (-180 to 180)"
                    MacroCondition.Player.Field.PITCH -> "Pitch (-90 to 90)"
                    MacroCondition.Player.Field.X, MacroCondition.Player.Field.Y,
                    MacroCondition.Player.Field.Z -> "Block coordinate"
                    else -> "Block id (stone, minecraft:dirt)"
                }
                drawTextField(graphics, viewport, y, expectedLabel, "player-expected", condition::expected,
                    Consumer { value -> setter.accept(MacroCondition.Player(condition.field(), condition.operator(),
                        value, condition.argument())) })
                if (condition.field() == MacroCondition.Player.Field.YAW || condition.field() == MacroCondition.Player.Field.PITCH) {
                    drawTextField(graphics, viewport, y, "Tolerance (degrees, default 5)", "player-tolerance",
                        condition::argument, Consumer { value -> setter.accept(MacroCondition.Player(condition.field(),
                            condition.operator(), condition.expected(), value)) })
                }
                if (condition.field() == MacroCondition.Player.Field.BLOCK_AT) {
                    drawTextField(graphics, viewport, y, "Position (x y z)", "player-block-pos", condition::argument,
                        Consumer { value -> setter.accept(MacroCondition.Player(condition.field(), condition.operator(),
                            condition.expected(), value)) })
                }
                lineText(graphics, viewport, y, "Data requirement", "Unloaded chunks or bad values stop this macro.")
            }
            else -> if (condition != null && condition !is MacroCondition.Always) {
                lineText(graphics, viewport, y, "Logic", "Choose a condition type above")
            }
        }
    }

    private fun drawCompoundConditionChildren(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray,
        childLabel: String, children: List<MacroCondition>, setter: Consumer<List<MacroCondition>>,
        conjunction: Boolean, mouseX: Int, mouseY: Int, depth: Int) {
        val kind = if (conjunction) "AND" else "OR"
        button(graphics, "Add $kind requirement", rowRect(viewport, y[0], 22), mouseX, mouseY, viewport, {
            if (children.size >= MAX_COMPOUND_TERMS) {
                flash("A condition group can contain at most $MAX_COMPOUND_TERMS entries.")
                return@button
            }
            val updated = ArrayList(children)
            updated.add(MacroCondition.Always(true))
            setter.accept(updated)
            dirty()
        }, false, categoryColor(Category.CONTROL))
        y[0] += 26
        for (i in children.indices) {
            val childIndex = i
            val child = children[i]
            drawConditionButton(graphics, viewport, y, "$childLabel ${i + 1}", child,
                Consumer { updatedChild ->
                    val updated = ArrayList(children)
                    updated[childIndex] = updatedChild
                    setter.accept(updated)
                    dirty()
                }, mouseX, mouseY, depth + 1)
            drawConditionProperties(graphics, viewport, y, child, Consumer { updatedChild ->
                val updated = ArrayList(children)
                updated[childIndex] = updatedChild
                setter.accept(updated)
                dirty()
            }, mouseX, mouseY, depth + 1)
            button(graphics, "Remove $kind requirement ${i + 1}", rowRect(viewport, y[0], 22), mouseX, mouseY,
                viewport, {
                    val updated = ArrayList(children)
                    updated.removeAt(childIndex)
                    setter.accept(updated)
                    dirty()
                }, false, TEXT_ERROR)
            y[0] += 26
        }
    }

    private fun isCompoundCondition(condition: MacroCondition?): Boolean =
        condition is MacroCondition.All || condition is MacroCondition.Any || condition is MacroCondition.Not

    private fun openConditionPicker(label: String, setter: Consumer<MacroCondition>, depth: Int) {
        val options = ArrayList<PickerOption>()
        addConditionOption(options, "Always true", MacroCondition.Always(true), setter)
        addConditionOption(options, "Always false", MacroCondition.Always(false), setter)
        addConditionOption(options, "In world", MacroCondition.World(true), setter)
        addConditionOption(options, "Screen title", MacroCondition.Screen("", true, false), setter)
        addConditionOption(options, "Slot exists", MacroCondition.Slot(0, true), setter)
        addConditionOption(options, "Item exists", MacroCondition.Item("", true, false,
            MacroCondition.ItemScope.CONTAINER), setter)
        addConditionOption(options, "Inventory item count", MacroCondition.InventoryCount("", "minecraft:diamond",
            MacroCondition.Variable.Operator.GREATER_OR_EQUAL, 1), setter)
        addConditionOption(options, "Chat contains", MacroCondition.Chat("", true), setter)
        addConditionOption(options, "Variable comparison", MacroCondition.Variable("value",
            MacroCondition.Variable.Operator.EQUALS, MacroValue.literal(MacroValue.Type.TEXT, "")), setter)
        addConditionOption(options, "Hypixel state", MacroCondition.Hypixel(
            MacroCondition.Hypixel.Field.ISLAND, MacroCondition.Hypixel.Operator.EQUALS, "GARDEN"), setter)
        addConditionOption(options, "Player view, position or block", MacroCondition.Player(
            MacroCondition.Player.Field.YAW, MacroCondition.Hypixel.Operator.EQUALS, "0", ""), setter)
        if (depth < MAX_CONDITION_DEPTH) {
            addConditionOption(options, "AND · all requirements", MacroCondition.All(listOf(
                MacroCondition.Always(true), MacroCondition.Always(true))), setter)
            addConditionOption(options, "OR · any requirement", MacroCondition.Any(listOf(
                MacroCondition.Always(true), MacroCondition.Always(false))), setter)
            addConditionOption(options, "NOT · invert condition", MacroCondition.Not(MacroCondition.Always(true)), setter)
        }
        openPicker("Choose a ${label.lowercase(Locale.ROOT)}", options)
    }

    private fun addConditionOption(options: MutableList<PickerOption>, label: String, condition: MacroCondition,
        setter: Consumer<MacroCondition>) {
        options.add(PickerOption(label) { setter.accept(condition); dirty() })
    }

    private fun openPicker(title: String, options: List<PickerOption>) = openPicker(title, options, false)

    private fun openPicker(title: String, options: List<PickerOption>, searchable: Boolean) {
        if (options.isEmpty()) return
        picker = PickerState(title, options, searchable)
    }

    private fun mouseButtonLabel(button: Int): String = when (button) {
        1 -> "Right"
        2 -> "Middle"
        else -> "Left"
    }

    private fun itemScopeLabel(scope: String?): String = when ((scope ?: "container").lowercase(Locale.ROOT)) {
        "player" -> "Player inventory"
        "all" -> "Container + player"
        else -> "Container only"
    }

    private fun nextItemScope(scope: String?): String = when ((scope ?: "container").lowercase(Locale.ROOT)) {
        "container" -> "player"
        "player" -> "all"
        else -> "container"
    }

    private fun drawIntField(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, label: String,
        id: String, getter: Supplier<Int>, setter: IntConsumer) {
        drawTextField(graphics, viewport, y, label, id,
            Supplier { getter.get().toString() }, Consumer { value ->
                try {
                    setter.accept(javaTrim(value).toInt())
                } catch (_: NumberFormatException) { }
            })
    }

    private fun drawDoubleField(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, label: String,
        id: String, getter: Supplier<Double>, setter: Consumer<Double>) {
        drawTextField(graphics, viewport, y, label, id,
            Supplier { String.format(Locale.ROOT, "%.2f", getter.get()) }, Consumer { value ->
                try {
                    setter.accept(javaTrim(value).toDouble())
                } catch (_: NumberFormatException) { }
            })
    }

    private fun drawFloatField(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, label: String,
        id: String, getter: Supplier<Float>, setter: Consumer<Float>) {
        drawTextField(graphics, viewport, y, label, id,
            Supplier { String.format(Locale.ROOT, "%.2f", getter.get()) }, Consumer { value ->
                try {
                    setter.accept(javaTrim(value).toFloat())
                } catch (_: NumberFormatException) { }
            })
    }

    private fun drawTextField(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, label: String,
        id: String, getter: Supplier<String>, setter: Consumer<String>) {
        val x = viewport.x + 4
        val w = maxOf(1, viewport.w - 8)
        graphics.text(font, trim(label, w), x, y[0], TEXT_MUTED)
        y[0] += 11
        val bounds = GuiRect(x, y[0], w, 20)
        val focused = id == focusedField
        roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
            MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM,
            if (focused) CARD_BORDER_ENABLED else BORDER, if (focused) 2 else 1)
        val value = if (focused) fieldText else safe(getter.get())
        drawBoundedFieldText(graphics, bounds, viewport, value, 7, 5, bounds.y + 6,
            focused, TEXT_PRIMARY)
        val binding = FieldBinding(id, getter, setter)
        registerHit(bounds, viewport, null, null, null, null, null, null, binding)
        y[0] += 25
    }

    private fun drawBoundedFieldText(graphics: GuiGraphicsExtractor, bounds: GuiRect, clip: GuiRect, value: String,
        leftInset: Int, rightInset: Int, textY: Int, focused: Boolean, color: Int) {
        drawEditableFieldText(graphics, bounds, clip, if (focused) fieldText else safe(value),
            if (focused) fieldCursor else safe(value).length, if (focused) fieldSelectionAnchor else -1,
            leftInset, rightInset, textY, focused, color)
    }

    private fun drawEditableFieldText(graphics: GuiGraphicsExtractor, bounds: GuiRect, clip: GuiRect, value: String,
        cursor: Int, selectionAnchor: Int, leftInset: Int, rightInset: Int, textY: Int,
        showCaret: Boolean, color: Int) {
        val contentWidth = bounds.w - leftInset - rightInset
        if (contentWidth <= 0) return
        val content = GuiRect(bounds.x + leftInset, bounds.y + 2, contentWidth, maxOf(1, bounds.h - 4))
        val visible = content.intersection(clip) ?: return
        if (visible.w <= 0 || visible.h <= 0) return
        val text = safe(value)
        val layout = visibleFieldLayout(text, cursor, contentWidth, showCaret)
        if (showCaret && selectionAnchor >= 0 && selectionAnchor != cursor) {
            val selectionStart = minOf(selectionAnchor, cursor)
            val selectionEnd = maxOf(selectionAnchor, cursor)
            val clippedStart = maxOf(selectionStart, layout.startIndex)
            val clippedEnd = minOf(selectionEnd, layout.endIndex)
            if (clippedEnd > clippedStart) {
                val left = if (selectionStart < layout.startIndex) 0 else fieldBoundaryX(text, layout, clippedStart)
                val right = if (selectionEnd > layout.endIndex) font.width(layout.text)
                    else fieldBoundaryX(text, layout, clippedEnd)
                val selectionTop = maxOf(visible.y, bounds.y + 4)
                val selectionBottom = minOf(visible.y + visible.h, bounds.y + bounds.h - 4)
                val fillLeft = maxOf(visible.x, content.x + left)
                val fillRight = minOf(visible.x + visible.w, content.x + right)
                if (selectionBottom > selectionTop && fillRight > fillLeft) {
                    graphics.fill(fillLeft, selectionTop, fillRight, selectionBottom,
                        withOpacity(CATEGORY_SELECTED, 0.48f))
                }
            }
        }
        graphics.enableScissor(visible.x, visible.y, visible.x + visible.w, visible.y + visible.h)
        graphics.text(font, if (showCaret) layout.text else trim(text, contentWidth), content.x, textY, color)
        graphics.disableScissor()
    }

    private fun visibleFieldLayout(value: String, cursor: Int, availableWidth: Int, showCaret: Boolean): VisibleFieldLayout {
        val text = safe(value)
        if (availableWidth <= 0) return VisibleFieldLayout("", 0, 0, 0, 0, 0)
        if (text.isEmpty()) return VisibleFieldLayout(
            if (showCaret && availableWidth >= font.width("|")) "|" else "", 0, 0, 0, 0,
            if (showCaret) font.width("|") else 0)
        val caretWidth = if (showCaret) font.width("|") else 0
        val caret = clampCursor(text, cursor)
        var start = 0
        val ellipsis = "…"
        while (start < caret && font.width(text.substring(start, caret)) + caretWidth > availableWidth) {
            start += Character.charCount(text.codePointAt(start))
        }
        while (start < caret && font.width(ellipsis + text.substring(start, caret)) + caretWidth > availableWidth) {
            start += Character.charCount(text.codePointAt(start))
        }
        val clippedLeft = start > 0 && font.width(ellipsis) + caretWidth <= availableWidth
        val before = text.substring(start, caret)
        val prefix = if (clippedLeft) ellipsis else ""
        val afterWidth = maxOf(0, availableWidth - font.width(prefix + before) - caretWidth)
        val after = if (showCaret && afterWidth == 0) ""
            else font.plainSubstrByWidth(text.substring(caret), maxOf(0, afterWidth))
        val result = prefix + before + (if (showCaret) "|" else "") + after
        return VisibleFieldLayout(result, start, caret, caret + after.length, font.width(prefix), caretWidth)
    }

    private fun fieldBoundaryX(text: String, layout: VisibleFieldLayout, index: Int): Int =
        if (index <= layout.cursorIndex) {
            layout.prefixWidth + font.width(text.substring(layout.startIndex, index))
        } else {
            layout.prefixWidth + font.width(text.substring(layout.startIndex, layout.cursorIndex)) +
                layout.caretWidth + font.width(text.substring(layout.cursorIndex, index))
        }

    private fun clampCursor(text: String, cursor: Int): Int {
        var result = clamp(cursor, 0, text.length)
        if (result > 0 && result < text.length && Character.isHighSurrogate(text[result - 1]) &&
            Character.isLowSurrogate(text[result])) result--
        return result
    }

    private fun drawCompactField(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, label: String,
        id: String, getter: Supplier<String>, setter: Consumer<String>, width: Int) {
        val x = viewport.x + 4
        val fieldX = x + 37
        val available = maxOf(1, viewport.x + viewport.w - 4 - fieldX)
        val fieldW = minOf(available, maxOf(1, maxOf(40, width - 37)))
        graphics.text(font, trim(label, maxOf(1, fieldX - x - 4)), x, y[0] + 5, TEXT_MUTED)
        val bounds = GuiRect(fieldX, y[0], fieldW, 19)
        val focused = id == focusedField
        roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
            MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, if (focused) CARD_BORDER_ENABLED else BORDER)
        drawBoundedFieldText(graphics, bounds, viewport, if (focused) fieldText else safe(getter.get()),
            4, 4, bounds.y + 5, focused, TEXT_PRIMARY)
        registerHit(bounds, viewport, null, null, null, null, null, null,
            FieldBinding(id, getter, setter, 4, 4))
        y[0] += 22
    }

    private fun toggleRow(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, label: String,
        value: Boolean, action: Runnable, mouseX: Int, mouseY: Int) {
        val x = viewport.x + 4
        val rowW = maxOf(50, viewport.w - 8)
        val bounds = GuiRect(x, y[0], rowW, 21)
        graphics.text(font, trim(label, rowW - 47), bounds.x + 1, bounds.y + 6, TEXT_SECONDARY)
        toggleSwitch(graphics, bounds.x + bounds.w - 37, bounds.y + 3, 34, 15, value)
        registerHit(bounds, viewport, action, null, null, null, null, null)
        y[0] += 24
    }

    private fun sectionTitle(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, text: String) {
        graphics.fill(viewport.x + 3, y[0] + 1, viewport.x + viewport.w - 3, y[0] + 2, PANEL_HIGHLIGHT)
        graphics.text(font, trim(text, viewport.w - 8), viewport.x + 4, y[0] + 6, TEXT_PRIMARY)
        y[0] += 19
    }

    private fun lineText(graphics: GuiGraphicsExtractor, viewport: GuiRect, y: IntArray, label: String, value: String) {
        val x = viewport.x + 4
        val labelW = minOf(62, maxOf(42, viewport.w / 3))
        graphics.text(font, trim(label, labelW), x, y[0] + 4, TEXT_MUTED)
        graphics.text(font, trim(value, viewport.w - labelW - 15), x + labelW, y[0] + 4, TEXT_SECONDARY)
        y[0] += 17
    }

    private fun rowRect(viewport: GuiRect, y: Int, h: Int): GuiRect =
        GuiRect(viewport.x + 4, y, maxOf(48, viewport.w - 8), h)

    private fun drawPanel(graphics: GuiGraphicsExtractor, pane: GuiRect, title: String, subtitle: String) {
        roundedRectBordered(graphics, pane.x, pane.y, pane.w, pane.h, RADIUS_SMALL,
            MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, BORDER)
        graphics.fill(pane.x + 1, pane.y + 1, pane.x + pane.w - 1, pane.y + 3, PANEL_HIGHLIGHT)
        graphics.text(font, trim(title, pane.w - 16), pane.x + 8, pane.y + 8, TEXT_PRIMARY)
        graphics.text(font, trim(subtitle, pane.w - 16), pane.x + 8, pane.y + 21, TEXT_MUTED)
    }

    private fun button(graphics: GuiGraphicsExtractor, label: String, bounds: GuiRect, mouseX: Int, mouseY: Int,
        clip: GuiRect?, action: Runnable?, selected: Boolean, accent: Int) {
        if (bounds.w < 1 || bounds.h < 1) return
        val hovered = bounds.contains(mouseX, mouseY)
        val base = if (selected) CARD_BG_ENABLED else if (hovered) CARD_BG_HOVER else BUTTON_BG
        val border = if (selected) CARD_BORDER_ENABLED else if (accent != 0) withOpacity(accent, 0.8f) else CARD_BORDER
        roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
            base, base, border)
        if (accent != 0) graphics.fill(bounds.x + 1, bounds.y + 3, bounds.x + 3, bounds.y + bounds.h - 3, accent)
        graphics.centeredText(font, trim(label, bounds.w - 9), bounds.x + bounds.w / 2,
            bounds.y + maxOf(4, (bounds.h - 8) / 2), if (selected) TEXT_PRIMARY else TEXT_SECONDARY)
        registerHit(bounds, clip, action, null, null, null, null, null)
    }

    private fun registerHit(bounds: GuiRect, clip: GuiRect?, action: Runnable?, step: MacroStep?,
        owner: List<MacroStep>?, paletteBlock: PaletteBlock?, script: MacroScript?, function: MacroFunction?) {
        registerHit(bounds, clip, action, step, owner, paletteBlock, script, function, null)
    }

    private fun registerHit(bounds: GuiRect, clip: GuiRect?, action: Runnable?, step: MacroStep?,
        owner: List<MacroStep>?, paletteBlock: PaletteBlock?, script: MacroScript?, function: MacroFunction?,
        field: FieldBinding?) {
        if (replayingCanvasTargets) return
        var actualBounds = bounds
        var actualClip = clip
        if (canvasTransformActive) {
            actualBounds = canvasToScreen(actualBounds)
            actualClip = canvasTransformClip
        }
        val visible = if (actualClip == null) actualBounds else actualBounds.intersection(actualClip) ?: return
        hitTargets.add(HitTarget(visible, action, step, owner, paletteBlock, script, function, field))
    }

    private fun canvasToScreen(bounds: GuiRect): GuiRect {
        val left = floor(canvasTransformX + bounds.x * canvasTransformScale).toInt()
        val top = floor(canvasTransformY + bounds.y * canvasTransformScale).toInt()
        val right = ceil(canvasTransformX + (bounds.x + bounds.w) * canvasTransformScale).toInt()
        val bottom = ceil(canvasTransformY + (bounds.y + bounds.h) * canvasTransformScale).toInt()
        return GuiRect(left, top, maxOf(1, right - left), maxOf(1, bottom - top))
    }

    private fun renderFooter(graphics: GuiGraphicsExtractor, layout: Layout) {
        val historyHint = (if (documentHistory.canUndo()) "Ctrl+Z undo" else "") +
            (if (documentHistory.canRedo()) (if (documentHistory.canUndo()) "  ·  " else "") + "Ctrl+Y redo" else "")
        val hint = if (noticeUntil > System.currentTimeMillis()) notice else
            (if (historyHint.isEmpty()) "" else "$historyHint  ·  ") +
                "Drag empty canvas to pan  ·  Scroll to zoom  ·  Drag blocks to snap  ·  Esc to close"
        graphics.text(font, trim(hint, layout.panel.w - 20), layout.panel.x + 11,
            layout.panel.y + layout.panel.h - 16, if (noticeUntil > System.currentTimeMillis()) TEXT_WARN else TEXT_MUTED)
    }

    /** Highlights the exact insertion socket that will receive a dragged block. */
    private fun setDropGap(owner: List<MacroStep>?, index: Int) {
        if (owner === dropGapOwner && index == dropGapIndex) return
        dropGapOwner = owner
        dropGapIndex = index
        measuredStepHeights.clear()
        measuredListLayouts.clear()
        measuredStepWidths.clear()
        measuredOwnWidths.clear()
        measuredListWidths.clear()
    }

    private fun renderDropPreview(graphics: GuiGraphicsExtractor, viewport: GuiRect, mouseX: Int, mouseY: Int) {
        val currentDrag = drag
        if (currentDrag == null || !currentDrag.moved || (currentDrag.kind != DragKind.PALETTE && currentDrag.kind != DragKind.STEP)) {
            setDropGap(null, -1)
            return
        }
        val target = findDropSlot(mouseX, mouseY, false)
        // Empty lists already show a socket; only non-empty lists need a reserved gap.
        setDropGap(target?.owner?.takeIf { it.isNotEmpty() }, target?.index ?: -1)
        if (target == null) return
        val color = if (currentDrag.kind == DragKind.PALETTE) categoryColor(currentDrag.palette!!.category)
            else blockColor(currentDrag.step!!)
        val label = if (currentDrag.kind == DragKind.PALETTE) currentDrag.palette!!.label else blockLabel(currentDrag.step!!)
        val nodeWidth = if (currentDrag.kind == DragKind.STEP) ownStepWidth(currentDrag.step!!)
            else maxOf(96, font.width(label) + 18)
        val zoom = canvasTransformScale
        val scaledH = Math.round(BLOCK_HEIGHT * zoom)
        val x = target.bounds.x
        val y = target.bounds.y + maxOf(0, (target.bounds.h - scaledH) / 2)
        if (GuiRect(x, y, maxOf(1, Math.round(nodeWidth * zoom)), maxOf(1, scaledH)).intersection(viewport) == null) return
        graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(zoom, zoom)
        val node = GuiRect(0, 0, nodeWidth, BLOCK_HEIGHT)
        paintShape(graphics, ScratchBlockShapes.stack(nodeWidth, BLOCK_HEIGHT, false), 0, 0,
            withOpacity(color, 0.55f), withOpacity(0xFFFFFFFF.toInt(), 0.80f))
        val textColor = macroAccentText(0xFFFFFFFF.toInt())
        if (currentDrag.kind == DragKind.STEP) {
            drawInlineBlockPreview(graphics, node, node, currentDrag.step!!, color, textColor)
        } else {
            graphics.text(font, label, 9, 10, textColor)
        }
        pose.popMatrix()
        graphics.disableScissor()
    }

    private fun renderDragPreview(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val currentDrag = drag
        if (currentDrag == null || !currentDrag.moved || currentDrag.kind == DragKind.PAN) {
            dragGhostInitialized = false
            return
        }
        val label = when (currentDrag.kind) {
            DragKind.PALETTE -> currentDrag.palette!!.label
            DragKind.STEP -> blockLabel(currentDrag.step!!)
            else -> triggerName(currentDrag.script!!.trigger())
        }
        val w = minOf(230, maxOf(92, font.width(label) + 20))
        val targetX = mouseX + 12f
        val targetY = mouseY + 12f
        if (!dragGhostInitialized) {
            dragGhostX = targetX
            dragGhostY = targetY
            dragGhostInitialized = true
        } else {
            dragGhostX += (targetX - dragGhostX) * 0.58f
            dragGhostY += (targetY - dragGhostY) * 0.58f
        }
        val x = clamp(dragGhostX.toInt().let { kotlin.math.round(dragGhostX).toInt() }, 3, maxOf(3, width - w - 3))
        val y = clamp(kotlin.math.round(dragGhostY).toInt(), 3, maxOf(3, height - 24))
        val color = when (currentDrag.kind) {
            DragKind.PALETTE -> categoryColor(currentDrag.palette!!.category)
            DragKind.STEP -> blockColor(currentDrag.step!!)
            else -> categoryColor(Category.ACTIONS)
        }
        paintShape(graphics, ScratchBlockShapes.stack(w, 21, false), x, y, withOpacity(color, 0.94f), TEXT_PRIMARY)
        // Keep ghost text readable on pale category fills and the bright event color.
        graphics.text(font, trim(label, w - 12), x + 7, y + 7, macroAccentText(0xFFFAFCFF.toInt()))
    }

    private fun renderPicker(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val currentPicker = picker ?: return
        graphics.fill(0, 0, width, height, withOpacity(0xFF000000.toInt(), 0.52f))
        val options = currentPicker.visibleOptions()
        val maxHeight = maxOf(90, height - 24)
        val headerHeight = if (currentPicker.searchable) 88 else 64
        val maxRows = maxOf(1, minOf(8, (maxHeight - headerHeight) / 24))
        currentPicker.visibleRows = minOf(maxOf(1, options.size), maxRows)
        currentPicker.maxScroll = maxOf(0, options.size - currentPicker.visibleRows)
        currentPicker.scroll = clamp(currentPicker.scroll, 0, currentPicker.maxScroll)
        currentPicker.selected = if (options.isEmpty()) 0 else clamp(currentPicker.selected, 0, options.size - 1)
        if (currentPicker.selected < currentPicker.scroll) currentPicker.scroll = currentPicker.selected
        if (currentPicker.selected >= currentPicker.scroll + currentPicker.visibleRows) {
            currentPicker.scroll = currentPicker.selected - currentPicker.visibleRows + 1
        }
        val modalW = minOf(420, maxOf(230, width - 24))
        val modalH = minOf(maxHeight, headerHeight + currentPicker.visibleRows * 24)
        val x = maxOf(8, (width - modalW) / 2)
        val y = maxOf(8, (height - modalH) / 2)
        val modal = GuiRect(x, y, minOf(modalW, width - 16), minOf(modalH, height - 16))
        roundedRectBordered(graphics, modal.x, modal.y, modal.w, modal.h, RADIUS,
            PANEL_TOP, PANEL_BOTTOM, BORDER)
        graphics.text(font, trim(currentPicker.title, modal.w - 20), modal.x + 10, modal.y + 9, TEXT_PRIMARY)
        registerHit(GuiRect(0, 0, width, height), null, Runnable { picker = null },
            null, null, null, null, null)
        var rowsTop = modal.y + 31
        if (currentPicker.searchable) {
            val search = GuiRect(modal.x + 8, modal.y + 28, modal.w - 16, 20)
            roundedRectBordered(graphics, search.x, search.y, search.w, search.h, RADIUS_SMALL,
                MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, CARD_BORDER_ENABLED)
            if (javaIsBlank(currentPicker.search)) {
                graphics.enableScissor(search.x + 5, search.y + 3, search.x + search.w - 5, search.y + search.h - 3)
                graphics.text(font, trim("Type to filter sound events", search.w - 12),
                    search.x + 6, search.y + 6, TEXT_MUTED)
                graphics.disableScissor()
            } else {
                drawEditableFieldText(graphics, search, modal, currentPicker.search, currentPicker.searchCursor,
                    currentPicker.searchSelectionAnchor, 6, 6, search.y + 6, true, TEXT_PRIMARY)
            }
            registerHit(search, null, Runnable {
                val activePicker = picker
                if (activePicker != null && activePicker.searchable) {
                    activePicker.searchCursor = pickerCursorAtMouse(search, currentMouseX)
                    activePicker.searchSelectionAnchor = -1
                    pickerSearchSelecting = activePicker
                    pickerSearchSelectionBounds = search
                    pickerSearchDragAnchor = activePicker.searchCursor
                }
            }, null, null, null, null, null)
            rowsTop = modal.y + 55
        }
        val rows = GuiRect(modal.x + 7, rowsTop, modal.w - 14, currentPicker.visibleRows * 24)
        graphics.enableScissor(rows.x, rows.y, rows.x + rows.w, rows.y + rows.h)
        if (options.isEmpty()) {
            graphics.text(font, "No matching sound events", rows.x + 7, rows.y + 7, TEXT_MUTED)
        } else {
            for (visible in 0 until currentPicker.visibleRows) {
                val index = currentPicker.scroll + visible
                if (index >= options.size) break
                val option = options[index]
                val row = GuiRect(rows.x, rows.y + visible * 24, rows.w, 21)
                button(graphics, option.label, row, mouseX, mouseY, rows, Runnable {
                    option.action.run()
                    picker = null
                }, currentPicker.selected == index, categoryColor(Category.FUNCTIONS))
            }
        }
        graphics.disableScissor()
        val hint = if (currentPicker.searchable)
            "Type to search  ·  Backspace delete  ·  ↑ / ↓  ·  Enter select  ·  Esc close"
        else "↑ / ↓ select  ·  Enter confirm  ·  Esc close  ·  Scroll for more"
        graphics.centeredText(font, trim(hint, modal.w - 20), modal.x + modal.w / 2,
            modal.y + modal.h - 17, TEXT_MUTED)
    }

    private fun renderGlobalManager(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!globalManagerOpen || picker != null) return
        val modal = globalModalRect()
        graphics.fill(0, 0, width, height, withOpacity(0xFF000000.toInt(), 0.68f))
        roundedRectBordered(graphics, modal.x, modal.y, modal.w, modal.h, RADIUS,
            PANEL_TOP, PANEL_BOTTOM, BORDER)
        graphics.fill(modal.x + 1, modal.y + 1, modal.x + modal.w - 1, modal.y + 3, PANEL_HIGHLIGHT)
        graphics.text(font, "Global variables", modal.x + 12, modal.y + 10, TEXT_PRIMARY)
        graphics.text(font, trim("Shared across macros and function calls · values stay local to this client", modal.w - 24),
            modal.x + 12, modal.y + 25, TEXT_MUTED)
        registerHit(modal, null, null, null, null, null, null, null)
        val close = GuiRect(modal.x + modal.w - 54, modal.y + 7, 42, 20)
        button(graphics, "Done", close, mouseX, mouseY, modal,
            Runnable { commitFocusedField(); globalManagerOpen = false }, false, 0)

        val y = intArrayOf(modal.y + 43)
        val inputPane = GuiRect(modal.x + 8, y[0], modal.w - 16, 69)
        drawTextField(graphics, inputPane, y, "New variable name", "global-create-name",
            Supplier { globalNameDraft }, Consumer { value -> globalNameDraft = value })
        val controlsY = y[0] - 2
        val type = GuiRect(inputPane.x + 4, controlsY, 94, 21)
        button(graphics, "Type · ${globalNewType.name}", type, mouseX, mouseY, inputPane,
            Runnable { globalNewType = next(globalNewType) }, false, categoryColor(Category.VARIABLES))
        val create = GuiRect(inputPane.x + inputPane.w - 100, controlsY, 96, 21)
        button(graphics, "Create global", create, mouseX, mouseY, inputPane,
            Runnable { createGlobalVariable() }, false, categoryColor(Category.VARIABLES))
        y[0] = inputPane.y + inputPane.h + 7
        val store = MacrosModule.INSTANCE.globalVariables()
        val definitions = store.definitions()
        graphics.text(font, "${definitions.size} / ${MacroVariableStore.MAX_VARIABLES} variables",
            modal.x + 12, y[0], TEXT_MUTED)
        y[0] += 13
        val rows = GuiRect(modal.x + 8, y[0], modal.w - 16, maxOf(24, modal.y + modal.h - y[0] - 27))
        val rowHeight = 51
        val visibleRows = maxOf(1, rows.h / rowHeight)
        val maxScroll = maxOf(0, definitions.size - visibleRows)
        globalScroll = clamp(globalScroll, 0, maxScroll)
        graphics.enableScissor(rows.x, rows.y, rows.x + rows.w, rows.y + rows.h)
        for (visible in 0 until visibleRows) {
            val index = globalScroll + visible
            if (index >= definitions.size) break
            val definition = definitions[index]
            val row = GuiRect(rows.x, rows.y + visible * rowHeight, rows.w, rowHeight - 3)
            roundedRectBordered(graphics, row.x, row.y, row.w, row.h, RADIUS_SMALL,
                MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, BORDER)
            drawGlobalVariableRow(graphics, row, definition, mouseX, mouseY)
        }
        graphics.disableScissor()
        if (definitions.isEmpty()) graphics.centeredText(font,
            trim("Create a global variable above to share a value across macros.", rows.w - 12),
            rows.x + rows.w / 2, rows.y + 8, TEXT_MUTED)
        graphics.centeredText(font,
            trim("Unsaved live values reset on restart. Enable Save value per variable to keep it locally.", modal.w - 24),
            modal.x + modal.w / 2, modal.y + modal.h - 17, TEXT_MUTED)
    }

    private fun drawGlobalVariableRow(graphics: GuiGraphicsExtractor, row: GuiRect,
        definition: MacroVariableStore.Definition, mouseX: Int, mouseY: Int) {
        val store = MacrosModule.INSTANCE.globalVariables()
        val x = row.x + 4
        val name = GuiRect(x, row.y + 3, row.w - 8, 18)
        drawInlineField(graphics, name, row, "global-name-${definition.id()}",
            Supplier {
                val current = store.definition(definition.id())
                if (current == null) "" else current.name() ?: ""
            }, Consumer { value -> store.rename(definition.id(), value); dirty() })
        val buttonY = row.y + 25
        val gap = 3
        val controlsW = maxOf(1, row.w - 8 - gap * 3)
        val deleteW = minOf(48, maxOf(32, controlsW / 5))
        val saveW = minOf(61, maxOf(44, controlsW / 4))
        val typeW = minOf(67, maxOf(46, controlsW / 3))
        val valueW = maxOf(40, controlsW - deleteW - saveW - typeW)
        val value = GuiRect(x, buttonY, valueW, 19)
        drawInlineField(graphics, value, row, "global-value-${definition.id()}",
            Supplier { store.value(definition.id()).toString() },
            Consumer { input -> store.setValue(definition.id(), input); dirty() })
        val typeX = value.x + value.w + gap
        val type = GuiRect(typeX, buttonY, typeW, 19)
        button(graphics, definition.type().name, type, mouseX, mouseY, row,
            Runnable { store.setType(definition.id(), next(definition.type())); dirty() }, false,
            categoryColor(Category.VARIABLES))
        val saveX = typeX + typeW + gap
        val save = GuiRect(saveX, buttonY, saveW, 19)
        button(graphics, if (definition.persistValue()) "Saved" else "Save value", save, mouseX, mouseY, row,
            Runnable { store.setPersistValue(definition.id(), !definition.persistValue()); dirty() },
            definition.persistValue(), if (definition.persistValue()) categoryColor(Category.VARIABLES) else 0)
        val deleteX = saveX + saveW + gap
        val delete = GuiRect(deleteX, buttonY, deleteW, 19)
        button(graphics, "Remove", delete, mouseX, mouseY, row,
            Runnable { store.remove(definition.id()); globalScroll = maxOf(0, globalScroll - 1); dirty() },
            false, TEXT_ERROR)
    }

    private fun drawInlineField(graphics: GuiGraphicsExtractor, bounds: GuiRect, clip: GuiRect,
        id: String, getter: Supplier<String>, setter: Consumer<String>) {
        val focused = id == focusedField
        roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL,
            MODULE_PANEL_TOP, MODULE_PANEL_BOTTOM, if (focused) CARD_BORDER_ENABLED else BORDER)
        drawBoundedFieldText(graphics, bounds, clip, if (focused) fieldText else safe(getter.get()),
            4, 4, bounds.y + 5, focused, TEXT_PRIMARY)
        registerHit(bounds, clip, null, null, null, null, null, null,
            FieldBinding(id, getter, setter, 4, 4))
    }

    private fun createGlobalVariable() {
        val store = MacrosModule.INSTANCE.globalVariables()
        var name = javaStrip(globalNameDraft)
        if (name.isEmpty()) name = "Global ${store.definitions().size + 1}"
        val definition = store.create(name, globalNewType)
        if (definition == null) {
            flash("Choose a unique name and make room under the 256 variable limit.")
            return
        }
        globalNameDraft = ""
        globalScroll = Int.MAX_VALUE
        dirty()
        flash("Created ${definition.name()}")
    }

    private fun globalModalRect(): GuiRect {
        val modalW = minOf(620, maxOf(230, width - 24))
        val modalH = minOf(maxOf(170, height - 24), 500)
        return GuiRect(maxOf(8, (width - modalW) / 2), maxOf(8, (height - modalH) / 2),
            minOf(modalW, width - 16), minOf(modalH, height - 16))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().toInt()
        val y = event.y().toInt()
        currentMouseX = x
        currentMouseY = y
        if (globalManagerOpen) {
            if (event.button() != 0) return true
            if (!globalModalRect().contains(x, y)) {
                commitFocusedField()
                globalManagerOpen = false
                return true
            }
        }
        if (picker != null && event.button() != 0) return true
        if (event.button() == 2 && canvasViewportContains(x, y)) {
            val activeMacro = macro!!
            drag = DragState.pan(x, y, activeMacro.canvasPanX(), activeMacro.canvasPanY())
            return true
        }
        if (event.button() != 0) return super.mouseClicked(event, doubleClick)
        for (i in hitTargets.indices.reversed()) {
            val target = hitTargets[i]
            if (!target.bounds.contains(x, y)) continue
            val targetField = target.field
            if (targetField != null) {
                if (focusedField != null && focusedField != targetField.id) commitFocusedField()
                focusField(targetField)
                fieldCursor = fieldCursorAtMouse(targetField, target.bounds, x)
                fieldSelectionAnchor = -1
                fieldSelecting = targetField
                fieldSelectingBounds = target.bounds
                fieldSelectionDragAnchor = fieldCursor
                updateFieldHistoryCursor()
                return true
            }
            if (focusedField != null) commitFocusedField()
            if (target.paletteBlock != null) {
                drag = DragState.palette(target.paletteBlock, x, y)
                return true
            }
            if (target.step != null) {
                selectStep(target.step, target.owner, target.script, target.function)
                val owner = target.owner as? MutableList<MacroStep> ?: return true
                drag = DragState.step(target.step, owner, target.script, target.function, x, y, target.bounds)
                return true
            }
            if (target.script != null) {
                selectScript(target.script)
                drag = DragState.script(target.script, x, y)
                return true
            }
            if (target.function != null) {
                selectFunction(target.function)
                drag = DragState.function(target.function, x, y)
                return true
            }
            target.action?.run()
            return true
        }
        if (focusedField != null) commitFocusedField()
        if (canvasViewportContains(x, y)) {
            val activeMacro = macro!!
            drag = DragState.pan(x, y, activeMacro.canvasPanX(), activeMacro.canvasPanY())
            return true
        }
        val layout = lastLayout
        if (layout != null && layout.panel.contains(x, y)) return true
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val selectingPicker = pickerSearchSelecting
        val activePicker = picker
        if (selectingPicker != null && activePicker === selectingPicker && event.button() == 0 &&
            pickerSearchSelectionBounds != null) {
            currentMouseX = event.x().toInt()
            currentMouseY = event.y().toInt()
            activePicker.searchCursor = pickerCursorAtMouse(pickerSearchSelectionBounds!!, currentMouseX)
            activePicker.searchSelectionAnchor = if (pickerSearchDragAnchor == activePicker.searchCursor) -1
                else pickerSearchDragAnchor
            return true
        }
        val selectingField = fieldSelecting
        val focused = focusedField
        val fieldBounds = fieldSelectingBounds
        if (selectingField != null && event.button() == 0 && focused != null && selectingField.id == focused &&
            fieldBounds != null) {
            currentMouseX = event.x().toInt()
            currentMouseY = event.y().toInt()
            fieldCursor = fieldCursorAtMouse(selectingField, fieldBounds, currentMouseX)
            fieldSelectionAnchor = if (fieldSelectionDragAnchor == fieldCursor) -1 else fieldSelectionDragAnchor
            updateFieldHistoryCursor()
            return true
        }
        val activeDrag = drag ?: return super.mouseDragged(event, dragX, dragY)
        if (event.button() != 0 && event.button() != 2) return super.mouseDragged(event, dragX, dragY)
        val x = event.x().toInt()
        val y = event.y().toInt()
        currentMouseX = x
        currentMouseY = y
        val dx = x - activeDrag.startX
        val dy = y - activeDrag.startY
        if (!activeDrag.moved && dx * dx + dy * dy >= DRAG_THRESHOLD_SQUARED) activeDrag.moved = true
        if (!activeDrag.moved) return true
        when (activeDrag.kind) {
            DragKind.PAN -> macro?.let { activeMacro ->
                activeMacro.setCanvasView(activeDrag.startPanX + dx, activeDrag.startPanY + dy, activeMacro.canvasZoom())
                viewChanged()
            }
            DragKind.SCRIPT -> {
                val script = activeDrag.script!!
                val activeMacro = macro
                if (activeMacro != null) {
                    val zoom = canvasZoomFactor(activeMacro.canvasZoom())
                    script.setCanvasPosition(activeDrag.startCanvasX + dx / zoom, activeDrag.startCanvasY + dy / zoom)
                    viewChanged()
                }
            }
            DragKind.FUNCTION -> {
                val function = activeDrag.function!!
                val activeMacro = macro
                if (activeMacro != null) {
                    val zoom = canvasZoomFactor(activeMacro.canvasZoom())
                    function.setCanvasPosition(activeDrag.startCanvasX + dx / zoom, activeDrag.startCanvasY + dy / zoom)
                    viewChanged()
                }
            }
            else -> Unit
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (pickerSearchSelecting != null && event.button() == 0) {
            pickerSearchSelecting = null
            pickerSearchSelectionBounds = null
            pickerSearchDragAnchor = -1
            val activePicker = picker
            if (activePicker != null && activePicker.searchSelectionAnchor == activePicker.searchCursor) {
                activePicker.searchSelectionAnchor = -1
            }
            return true
        }
        if (fieldSelecting != null && event.button() == 0) {
            fieldSelecting = null
            fieldSelectingBounds = null
            fieldSelectionDragAnchor = -1
            if (fieldSelectionAnchor == fieldCursor) fieldSelectionAnchor = -1
            updateFieldHistoryCursor()
            return true
        }
        if (drag == null || (event.button() != 0 && event.button() != 2)) return super.mouseReleased(event)
        val released = drag!!
        val x = event.x().toInt()
        val y = event.y().toInt()
        currentMouseX = x
        currentMouseY = y
        drag = null
        when (released.kind) {
            DragKind.PALETTE -> {
                val palette = released.palette!!
                if (palette.detachedOnly) {
                    if (tab == Tab.CODE && canvasViewportContains(x, y)) insertPaletteDetached(palette, x - 64, y - 64)
                    else insertPalette(palette, null)
                    return true
                }
                if (!released.moved) insertPalette(palette, null) else {
                    val target = findDropSlot(x, y, false)
                    if (target != null) insertPalette(palette, target)
                    else if (tab == Tab.CODE && canvasViewportContains(x, y)) insertPaletteDetached(palette, x - 40, y - 10)
                }
                return true
            }
            DragKind.STEP -> if (released.moved) {
                val step = released.step!!
                val activeMacro = macro
                if (lastLayout?.palette?.contains(x, y) == true) {
                    deleteDraggedStep(released)
                    return true
                }
                if (step is MacroStep.Comment && activeMacro != null && released.owner === activeMacro.detachedBlocks()) {
                    val target = findDropSlot(x, y, false)
                    if (target != null) moveStep(released, target)
                    else if (tab == Tab.CODE && canvasViewportContains(x, y)) {
                        moveStepToDetached(released, x - released.pointerOffsetX, y - released.pointerOffsetY)
                    }
                    return true
                }
                val target = findDropSlot(x, y, false)
                if (target != null) moveStep(released, target)
                else if (tab == Tab.CODE && canvasViewportContains(x, y) &&
                    (released.function == null || released.owner === activeMacro?.detachedBlocks())) {
                    moveStepToDetached(released, x - released.pointerOffsetX, y - released.pointerOffsetY)
                }
                return true
            }
            DragKind.SCRIPT, DragKind.FUNCTION -> {
                recordDocumentEdit()
                viewChanged()
                return true
            }
            DragKind.PAN -> {
                viewChanged()
                return true
            }
            else -> return true
        }
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val layout = lastLayout ?: return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val x = mouseX.toInt()
        val y = mouseY.toInt()
        if (globalManagerOpen && globalModalRect().contains(x, y)) {
            val definitions = MacrosModule.INSTANCE.globalVariables().definitions()
            val visibleRows = maxOf(1, (globalModalRect().h - 166) / 51)
            val maxScroll = maxOf(0, definitions.size - visibleRows)
            globalScroll = clamp(globalScroll - kotlin.math.sign(scrollY).toInt() * 2, 0, maxScroll)
            return true
        }
        val activePicker = picker
        if (activePicker != null) {
            activePicker.scroll = clamp(activePicker.scroll - kotlin.math.sign(scrollY).toInt() * 3,
                0, activePicker.maxScroll)
            return true
        }
        // Side panels win over the canvas: the inspector sits inside the canvas bounds and its wheel was zooming instead.
        if (layout.palette.contains(x, y)) paletteScroll = maxOf(0, paletteScroll - kotlin.math.sign(scrollY).toInt() * 30)
        else if (layout.inspector.contains(x, y)) inspectorScroll = clamp(
            inspectorScroll - kotlin.math.sign(scrollY).toInt() * 28, 0, inspectorMaxScroll)
        else if (canvasViewportContains(x, y)) {
            if (scrollY != 0.0) {
                val factor = Math.pow(1.20, maxOf(-4.0, minOf(4.0, scrollY))).toFloat()
                setZoomAround(factor, mouseX, mouseY)
            }
        } else return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val activePicker = picker
        if (activePicker != null) {
            if (activePicker.searchable && event.isAllowedChatCharacter()) replacePickerSearch(event.codepointAsString())
            return true
        }
        if (focusedField == null || !event.isAllowedChatCharacter()) return super.charTyped(event)
        replaceFocusedFieldText(event.codepointAsString())
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val control = (event.modifiers() and InputConstants.MOD_CONTROL) != 0
        val shift = (event.modifiers() and GLFW.GLFW_MOD_SHIFT) != 0
        val activePicker = picker
        if (activePicker != null) {
            if (event.key() == InputConstants.KEY_ESCAPE) {
                picker = null
                return true
            }
            if (activePicker.searchable) {
                if (control && event.key() == GLFW.GLFW_KEY_A) {
                    activePicker.searchSelectionAnchor = 0
                    activePicker.searchCursor = activePicker.search.length
                    return true
                }
                if ((control && event.key() == GLFW.GLFW_KEY_C) ||
                    (control && event.key() == GLFW.GLFW_KEY_INSERT)) {
                    copySelectionToClipboard(activePicker.search, activePicker.searchSelectionAnchor,
                        activePicker.searchCursor)
                    return true
                }
                if ((control && event.key() == GLFW.GLFW_KEY_X) ||
                    (shift && event.key() == GLFW.GLFW_KEY_DELETE)) {
                    if (hasTextSelection(activePicker.searchSelectionAnchor, activePicker.searchCursor)) {
                        copySelectionToClipboard(activePicker.search, activePicker.searchSelectionAnchor,
                            activePicker.searchCursor)
                        replacePickerSearch("")
                    }
                    return true
                }
                if ((control && event.key() == GLFW.GLFW_KEY_V) ||
                    (shift && event.key() == GLFW.GLFW_KEY_INSERT)) {
                    replacePickerSearch(minecraft?.keyboardHandler?.clipboard ?: "")
                    return true
                }
                if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                    deletePickerSearch(event.key() == InputConstants.KEY_BACKSPACE)
                    return true
                }
                if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT) {
                    movePickerSearchCursor(if (event.key() == GLFW.GLFW_KEY_LEFT) -1 else 1, shift)
                    return true
                }
                if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) {
                    movePickerSearchCursorTo(if (event.key() == GLFW.GLFW_KEY_HOME) 0 else activePicker.search.length, shift)
                    return true
                }
            }
            val options = activePicker.visibleOptions()
            if (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN) {
                if (options.isNotEmpty()) {
                    val direction = if (event.key() == GLFW.GLFW_KEY_UP) -1 else 1
                    activePicker.selected = clamp(activePicker.selected + direction, 0, options.size - 1)
                    if (activePicker.selected < activePicker.scroll) activePicker.scroll = activePicker.selected
                    if (activePicker.selected >= activePicker.scroll + activePicker.visibleRows) {
                        activePicker.scroll = activePicker.selected - activePicker.visibleRows + 1
                    }
                }
                return true
            }
            if (event.key() == InputConstants.KEY_RETURN && options.isNotEmpty()) {
                options[activePicker.selected].action.run()
                picker = null
                return true
            }
            return true
        }
        if (control && event.key() == GLFW.GLFW_KEY_Z) {
            if (focusedField != null) {
                if (focusedField != "palette-search") {
                    if (shift) redoFieldDraft() else undoFieldDraft()
                }
                return true
            }
            if (shift) redoLastEdit() else undoLastEdit()
            return true
        }
        if (control && event.key() == GLFW.GLFW_KEY_Y) {
            if (focusedField != null) {
                if (focusedField != "palette-search") redoFieldDraft()
                return true
            }
            redoLastEdit()
            return true
        }
        if (focusedField != null) {
            if (control && event.key() == GLFW.GLFW_KEY_A) {
                fieldSelectionAnchor = if (fieldText.isEmpty()) -1 else 0
                fieldCursor = fieldText.length
                updateFieldHistoryCursor()
                return true
            }
            if ((control && event.key() == GLFW.GLFW_KEY_C) ||
                (control && event.key() == GLFW.GLFW_KEY_INSERT)) {
                copySelectionToClipboard(fieldText, fieldSelectionAnchor, fieldCursor)
                return true
            }
            if ((control && event.key() == GLFW.GLFW_KEY_X) ||
                (shift && event.key() == GLFW.GLFW_KEY_DELETE)) {
                if (hasTextSelection(fieldSelectionAnchor, fieldCursor)) {
                    copySelectionToClipboard(fieldText, fieldSelectionAnchor, fieldCursor)
                    replaceFocusedFieldText("")
                }
                return true
            }
            if ((control && event.key() == GLFW.GLFW_KEY_V) ||
                (shift && event.key() == GLFW.GLFW_KEY_INSERT)) {
                replaceFocusedFieldText(minecraft?.keyboardHandler?.clipboard ?: "")
                return true
            }
            if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
                deleteFocusedField(event.key() == InputConstants.KEY_BACKSPACE)
                return true
            }
            if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT) {
                val direction = if (event.key() == GLFW.GLFW_KEY_LEFT) -1 else 1
                if (!shift && hasTextSelection(fieldSelectionAnchor, fieldCursor)) {
                    fieldCursor = if (direction < 0) minOf(fieldSelectionAnchor, fieldCursor)
                        else maxOf(fieldSelectionAnchor, fieldCursor)
                    fieldSelectionAnchor = -1
                } else {
                    var next = fieldCursor
                    if (direction < 0 && next > 0) next -= Character.charCount(fieldText.codePointBefore(next))
                    else if (direction > 0 && next < fieldText.length) {
                        next += Character.charCount(fieldText.codePointAt(next))
                    }
                    moveFocusedFieldCursor(next, shift)
                }
                updateFieldHistoryCursor()
                return true
            }
            if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) {
                moveFocusedFieldCursor(if (event.key() == GLFW.GLFW_KEY_HOME) 0 else fieldText.length, shift)
                updateFieldHistoryCursor()
                return true
            }
            if (event.key() == InputConstants.KEY_RETURN) {
                commitFocusedField()
                return true
            }
            if (event.key() == InputConstants.KEY_ESCAPE) {
                cancelFocusedField()
                return true
            }
        }
        if (globalManagerOpen && event.key() == InputConstants.KEY_ESCAPE) {
            globalManagerOpen = false
            return true
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            if (isBindingInThisEditor()) ModuleKeybindManager.cancelBinding() else onClose()
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE) {
            removeSelectedStep()
            return true
        }
        return super.keyPressed(event)
    }

    private fun fieldCursorAtMouse(binding: FieldBinding?, bounds: GuiRect?, mouseX: Int): Int {
        if (binding == null || bounds == null) return fieldCursor
        val availableWidth = maxOf(1, bounds.w - binding.leftInset - binding.rightInset)
        val layout = visibleFieldLayout(fieldText, fieldCursor, availableWidth, true)
        val localX = clamp(mouseX - bounds.x - binding.leftInset, 0, availableWidth)
        var nearest = layout.startIndex
        var nearestDistance = kotlin.math.abs(localX - layout.prefixWidth)
        var index = layout.startIndex
        while (index < layout.endIndex) {
            val next = index + Character.charCount(fieldText.codePointAt(index))
            val distance = kotlin.math.abs(localX - fieldBoundaryX(fieldText, layout, next))
            if (distance < nearestDistance) {
                nearest = next
                nearestDistance = distance
            }
            index = next
        }
        return nearest
    }

    private fun pickerCursorAtMouse(bounds: GuiRect?, mouseX: Int): Int {
        val activePicker = picker ?: return 0
        if (bounds == null) return 0
        val availableWidth = maxOf(1, bounds.w - 12)
        val layout = visibleFieldLayout(activePicker.search, activePicker.searchCursor, availableWidth, true)
        val localX = clamp(mouseX - bounds.x - 6, 0, availableWidth)
        var nearest = layout.startIndex
        var nearestDistance = kotlin.math.abs(localX - layout.prefixWidth)
        var index = layout.startIndex
        while (index < layout.endIndex) {
            val next = index + Character.charCount(activePicker.search.codePointAt(index))
            val distance = kotlin.math.abs(localX - fieldBoundaryX(activePicker.search, layout, next))
            if (distance < nearestDistance) {
                nearest = next
                nearestDistance = distance
            }
            index = next
        }
        return nearest
    }

    private fun hasTextSelection(anchor: Int, cursor: Int): Boolean = anchor >= 0 && anchor != cursor

    private fun copySelectionToClipboard(text: String, anchor: Int, cursor: Int) {
        val client = minecraft ?: return
        if (!hasTextSelection(anchor, cursor)) return
        client.keyboardHandler.setClipboard(text.substring(minOf(anchor, cursor), maxOf(anchor, cursor)))
    }

    private fun replaceFocusedFieldText(replacement: String) {
        if (focusedField == null) return
        val start = if (hasTextSelection(fieldSelectionAnchor, fieldCursor)) minOf(fieldSelectionAnchor, fieldCursor)
            else fieldCursor
        val end = if (hasTextSelection(fieldSelectionAnchor, fieldCursor)) maxOf(fieldSelectionAnchor, fieldCursor)
            else fieldCursor
        val inserted = limitUtf16Length(sanitizeSingleLine(replacement), maxOf(0, 256 - (fieldText.length - (end - start))))
        val updated = fieldText.substring(0, start) + inserted + fieldText.substring(end)
        val changed = updated != fieldText
        fieldText = updated
        fieldCursor = start + inserted.length
        fieldSelectionAnchor = -1
        if (changed) recordFieldDraft() else updateFieldHistoryCursor()
        updatePaletteSearchDraft()
    }

    private fun deleteFocusedField(backwards: Boolean) {
        if (hasTextSelection(fieldSelectionAnchor, fieldCursor)) {
            replaceFocusedFieldText("")
            return
        }
        var start = fieldCursor
        var end = fieldCursor
        if (backwards && fieldCursor > 0) start -= Character.charCount(fieldText.codePointBefore(fieldCursor))
        else if (!backwards && fieldCursor < fieldText.length) end += Character.charCount(fieldText.codePointAt(fieldCursor))
        if (start == end) return
        fieldSelectionAnchor = start
        fieldCursor = end
        replaceFocusedFieldText("")
    }

    private fun moveFocusedFieldCursor(nextCursor: Int, extendSelection: Boolean) {
        val next = clampCursor(fieldText, nextCursor)
        if (extendSelection) {
            if (fieldSelectionAnchor < 0) fieldSelectionAnchor = fieldCursor
            fieldCursor = next
            if (fieldSelectionAnchor == fieldCursor) fieldSelectionAnchor = -1
        } else {
            fieldCursor = next
            fieldSelectionAnchor = -1
        }
    }

    private fun updatePaletteSearchDraft() {
        if (focusedField != "palette-search") return
        paletteSearch = fieldText
        paletteScroll = 0
    }

    private fun replacePickerSearch(replacement: String) {
        val activePicker = picker ?: return
        if (!activePicker.searchable) return
        val start = if (hasTextSelection(activePicker.searchSelectionAnchor, activePicker.searchCursor))
            minOf(activePicker.searchSelectionAnchor, activePicker.searchCursor) else activePicker.searchCursor
        val end = if (hasTextSelection(activePicker.searchSelectionAnchor, activePicker.searchCursor))
            maxOf(activePicker.searchSelectionAnchor, activePicker.searchCursor) else activePicker.searchCursor
        val inserted = limitUtf16Length(sanitizeSingleLine(replacement),
            maxOf(0, 128 - (activePicker.search.length - (end - start))))
        val updated = activePicker.search.substring(0, start) + inserted + activePicker.search.substring(end)
        activePicker.updateSearch(updated)
        activePicker.searchCursor = start + inserted.length
        activePicker.searchSelectionAnchor = -1
    }

    private fun deletePickerSearch(backwards: Boolean) {
        val activePicker = picker ?: return
        if (hasTextSelection(activePicker.searchSelectionAnchor, activePicker.searchCursor)) {
            replacePickerSearch("")
            return
        }
        var start = activePicker.searchCursor
        var end = activePicker.searchCursor
        if (backwards && activePicker.searchCursor > 0) {
            start -= Character.charCount(activePicker.search.codePointBefore(activePicker.searchCursor))
        } else if (!backwards && activePicker.searchCursor < activePicker.search.length) {
            end += Character.charCount(activePicker.search.codePointAt(activePicker.searchCursor))
        }
        if (start == end) return
        activePicker.searchSelectionAnchor = start
        activePicker.searchCursor = end
        replacePickerSearch("")
    }

    private fun movePickerSearchCursor(direction: Int, extendSelection: Boolean) {
        val activePicker = picker ?: return
        if (!extendSelection && hasTextSelection(activePicker.searchSelectionAnchor, activePicker.searchCursor)) {
            activePicker.searchCursor = if (direction < 0)
                minOf(activePicker.searchSelectionAnchor, activePicker.searchCursor)
            else maxOf(activePicker.searchSelectionAnchor, activePicker.searchCursor)
            activePicker.searchSelectionAnchor = -1
            return
        }
        var next = activePicker.searchCursor
        if (direction < 0 && next > 0) next -= Character.charCount(activePicker.search.codePointBefore(next))
        else if (direction > 0 && next < activePicker.search.length) {
            next += Character.charCount(activePicker.search.codePointAt(next))
        }
        movePickerSearchCursorTo(next, extendSelection)
    }

    private fun movePickerSearchCursorTo(nextCursor: Int, extendSelection: Boolean) {
        val activePicker = picker ?: return
        val next = clampCursor(activePicker.search, nextCursor)
        if (extendSelection) {
            if (activePicker.searchSelectionAnchor < 0) activePicker.searchSelectionAnchor = activePicker.searchCursor
            activePicker.searchCursor = next
            if (activePicker.searchSelectionAnchor == activePicker.searchCursor) activePicker.searchSelectionAnchor = -1
        } else {
            activePicker.searchCursor = next
            activePicker.searchSelectionAnchor = -1
        }
    }

    private fun sanitizeSingleLine(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val sanitized = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            val codePoint: Int
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                    codePoint = Character.toCodePoint(current, value[index + 1])
                    index += 2
                } else {
                    codePoint = 0xFFFD
                    index++
                }
            } else if (Character.isLowSurrogate(current)) {
                codePoint = 0xFFFD
                index++
            } else {
                codePoint = current.code
                index++
            }
            when (codePoint) {
                '\r'.code, '\n'.code, '\t'.code -> sanitized.append(' ')
                else -> if (!Character.isISOControl(codePoint)) sanitized.appendCodePoint(codePoint)
            }
        }
        return sanitized.toString()
    }

    private fun limitUtf16Length(value: String?, limit: Int): String {
        if (value == null || value.length <= limit) return value ?: ""
        var end = maxOf(0, limit)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])) end--
        return value.substring(0, end)
    }

    override fun onClose() {
        commitFocusedField()
        if (isBindingInThisEditor()) ModuleKeybindManager.cancelBinding()
        closeToParent()
    }

    override fun removed() {
        drag = null
        if (isBindingInThisEditor()) ModuleKeybindManager.cancelBinding()
        finishEditorDiagnostics()
        super.removed()
    }

    private fun closeToParent() {
        drag = null
        commitFocusedField()
        if (isBindingInThisEditor()) ModuleKeybindManager.cancelBinding()
        dirty()
        minecraft?.gui?.setScreen(parent)
    }

    private fun isBindingInThisEditor(): Boolean {
        val script = ModuleKeybindManager.bindingScript()
        val key = ModuleKeybindManager.bindingKeyStep()
        return (macro != null && ((script != null && macro.scripts().contains(script)) ||
            ModuleKeybindManager.bindingMacro() === macro)) || (key != null && selectedStep === key)
    }

    private fun setZoomAround(factor: Float, mouseX: Double, mouseY: Double) {
        val activeMacro = macro ?: return
        val layout = lastLayout ?: return
        val viewport = canvasViewport(layout)
        val oldZoom = activeMacro.canvasZoom()
        val oldFactor = canvasZoomFactor(oldZoom)
        val newZoom = (activeMacro.canvasZoom().toDouble() * factor).coerceIn(1.0, 100.0).toFloat()
        if (newZoom == oldZoom) return
        val newFactor = canvasZoomFactor(newZoom)
        val worldX = (mouseX - (viewport.x + 16 + activeMacro.canvasPanX())) / oldFactor
        val worldY = (mouseY - (viewport.y + 14 + activeMacro.canvasPanY())) / oldFactor
        val panX = (mouseX - viewport.x - 16 - worldX * newFactor).toFloat()
        val panY = (mouseY - viewport.y - 14 - worldY * newFactor).toFloat()
        activeMacro.setCanvasView(panX, panY, newZoom)
        viewChanged()
    }

    /** Mouse wheel changes only the canvas scale; the user's chosen pan remains fixed. */
    private fun canvasZoomFactor(zoomPercent: Float): Float = zoomPercent.coerceIn(1.0f, 100.0f) / 100.0f

    private fun canvasViewportContains(x: Int, y: Int): Boolean =
        lastLayout?.let { canvasViewport(it).contains(x, y) } ?: false

    private fun canvasViewport(layout: Layout): GuiRect {
        val pane = layout.canvas
        return if (pane == null) GuiRect(0, 0, 0, 0) else
            GuiRect(pane.x + 5, pane.y + 63, maxOf(1, pane.w - 10), maxOf(1, pane.h - 70))
    }

    private fun findDropSlot(x: Int, y: Int, includeOutOfBounds: Boolean): DropSlot? {
        for (i in dropSlots.indices.reversed()) {
            val slot = dropSlots[i]
            if (!slot.bounds.contains(x, y)) continue
            val activeDrag = drag
            if (activeDrag != null && activeDrag.kind == DragKind.STEP) {
                if ((activeDrag.function != null) != slot.functionScope) continue
                val step = activeDrag.step ?: continue
                if (isDescendantOwner(step, slot.owner)) continue
                if (slot.depth + subtreeDepth(step) > MAX_TREE_DEPTH) continue
            }
            return slot
        }
        return null
    }

    private fun insertPalette(block: PaletteBlock?, target: DropSlot?) {
        if (block == null) return
        if (block.detachedOnly) {
            val viewport = lastLayout?.let { canvasViewport(it) }
            val x = viewport?.let { it.x + it.w / 2 - 64 } ?: width / 2
            val y = viewport?.let { it.y + it.h / 2 - 64 } ?: height / 2
            insertPaletteDetached(block, x, y)
            return
        }
        val owner: MutableList<MacroStep>?
        val index: Int
        if (target != null) {
            owner = target.owner
            index = target.index
        } else {
            owner = insertionList()
            index = owner?.let { insertionIndex(it) } ?: 0
        }
        if (owner == null) return
        val step = ClickGuiState.expandNewMacroNode(block.factory.get()) ?: return
        owner.add(clamp(index, 0, owner.size), step)
        selectedStep = step
        selectedOwner = owner
        if (target != null) selectDropContext(target)
        else if (tab == Tab.CODE && selectedScript == null) selectedScript = macro!!.primaryKeyScript()
        inspectorScroll = 0
        dirty()
    }

    private fun insertPaletteDetached(block: PaletteBlock?, screenX: Int, screenY: Int) {
        val activeMacro = macro ?: return
        if (block == null) return
        val step = ClickGuiState.expandNewMacroNode(block.factory.get())
        val base = step as? MacroStep.Base ?: return
        base.setEditorPosition(canvasWorldX(screenX), canvasWorldY(screenY))
        val detached = activeMacro.detachedBlocks()
        detached.add(step)
        selectedStep = step
        selectedOwner = detached
        inspectorScroll = 0
        dirty()
    }

    private fun moveStepToDetached(source: DragState?, screenX: Int, screenY: Int) {
        val activeMacro = macro ?: return
        if (source?.step == null || source.owner == null) return
        val base = source.step as? MacroStep.Base ?: return
        val originalIndex = source.owner.indexOf(source.step)
        if (originalIndex < 0) return
        val originalOwner = source.owner
        val detached = activeMacro.detachedBlocks()
        if (originalOwner === detached) {
            base.setEditorPosition(canvasWorldX(screenX), canvasWorldY(screenY))
        } else {
            originalOwner.removeAt(originalIndex)
            base.setEditorPosition(canvasWorldX(screenX), canvasWorldY(screenY))
            detached.add(source.step)
        }
        selectedStep = source.step
        selectedOwner = detached
        selectedScript = null
        selectedFunction = null
        dirty()
    }

    private fun canvasWorldX(screenX: Int): Float {
        val activeMacro = macro!!
        val viewport = canvasViewport(lastLayout!!)
        return (screenX - viewport.x - 16 - activeMacro.canvasPanX()) / canvasZoomFactor(activeMacro.canvasZoom())
    }

    private fun canvasWorldY(screenY: Int): Float {
        val activeMacro = macro!!
        val viewport = canvasViewport(lastLayout!!)
        return (screenY - viewport.y - 14 - activeMacro.canvasPanY()) / canvasZoomFactor(activeMacro.canvasZoom())
    }

    private fun moveStep(source: DragState?, target: DropSlot?) {
        if (source?.owner == null || source.step == null || target == null) return
        if ((source.function != null) != target.functionScope || isDescendantOwner(source.step, target.owner) ||
            target.depth + subtreeDepth(source.step) > MAX_TREE_DEPTH) return
        val sourceIndex = source.owner.indexOf(source.step)
        if (sourceIndex < 0) return
        val destinationOwner = target.owner
        var destination = clamp(target.index, 0, destinationOwner.size)
        if (source.owner === destinationOwner && (destination == sourceIndex || destination == sourceIndex + 1)) return
        source.owner.removeAt(sourceIndex)
        if (source.owner === destinationOwner && sourceIndex < destination) destination--
        destinationOwner.add(clamp(destination, 0, destinationOwner.size), source.step)
        selectedStep = source.step
        selectedOwner = destinationOwner
        selectDropContext(target)
        dirty()
    }

    private fun selectDropContext(target: DropSlot) {
        if (target.functionScope) {
            tab = Tab.FUNCTIONS
            if (target.function != null) selectedFunction = target.function
        } else {
            tab = Tab.CODE
            if (target.script != null) selectedScript = target.script
        }
    }

    private fun isDescendantOwner(step: MacroStep, owner: List<MacroStep>): Boolean =
        MacroTreeRules.containsDescendantList(step, owner)

    private fun subtreeDepth(step: MacroStep): Int = MacroTreeRules.subtreeDepth(step)

    private fun removeSelectedStep() {
        val step = selectedStep ?: return
        val owner = selectedOwner ?: return
        val index = owner.indexOf(step)
        if (index < 0) return
        owner.removeAt(index)
        selectedStep = null
        selectedOwner = null
        inspectorScroll = 0
        flash("Block deleted · Ctrl+Z restores it")
        dirty()
    }

    /** Dropping a block back on the palette throws it away, like Scratch. Ctrl+Z restores it. */
    private fun deleteDraggedStep(source: DragState) {
        val owner = source.owner ?: return
        val index = owner.indexOf(source.step ?: return)
        if (index < 0) return
        owner.removeAt(index)
        if (selectedStep === source.step) {
            selectedStep = null
            selectedOwner = null
            inspectorScroll = 0
        }
        flash("Block deleted · Ctrl+Z restores it")
        dirty()
    }

    private fun undoLastEdit() {
        val previous = documentHistory.undo() ?: return
        restoreDocumentSnapshot(previous)
        flash("Undid the previous edit")
    }

    private fun redoLastEdit() {
        val next = documentHistory.redo() ?: return
        restoreDocumentSnapshot(next)
        flash("Redid the edit")
    }

    private fun insertionList(): MutableList<MacroStep>? {
        val owner = selectedOwner
        if (owner != null && (selectedStep == null || owner.contains(selectedStep))) return owner
        if (tab == Tab.FUNCTIONS) {
            ensureFunction()
            return selectedFunction?.steps()
        }
        val activeMacro = macro ?: return null
        if (selectedScript == null) selectedScript = activeMacro.primaryKeyScript()
        return selectedScript?.steps()
    }

    private fun insertionIndex(owner: List<MacroStep>): Int {
        if (selectedStep != null && selectedOwner === owner) {
            val index = owner.indexOf(selectedStep)
            if (index >= 0) return index + 1
        }
        return owner.size
    }

    private fun addScript(trigger: MacroScript.Trigger) {
        val activeMacro = macro ?: return
        commitFocusedField()
        val script = activeMacro.addScript(trigger)
        val n = activeMacro.scripts().size - 1
        script.setCanvasPosition((280 + (n % 3) * 300).toFloat(), ((n / 3) * 210).toFloat())
        selectScript(script)
        tab = Tab.CODE
        dirty()
    }

    private fun removeSelectedScript() {
        val activeMacro = macro ?: return
        val script = selectedScript ?: return
        val keyScripts = activeMacro.scripts().count { it.trigger() == MacroScript.Trigger.KEY_PRESS }
        if (script.trigger() == MacroScript.Trigger.KEY_PRESS && keyScripts <= 1) {
            flash("Keep at least one key event stack in each macro.")
            return
        }
        if (ModuleKeybindManager.bindingScript() === script) ModuleKeybindManager.cancelBinding()
        activeMacro.scripts().remove(script)
        selectedScript = activeMacro.primaryKeyScript()
        clearSelection()
        dirty()
    }

    private fun toggleScriptBinding() {
        val script = selectedScript ?: return
        if (script.trigger() != MacroScript.Trigger.KEY_PRESS) return
        if (ModuleKeybindManager.bindingScript() === script) ModuleKeybindManager.cancelBinding()
        else ModuleKeybindManager.beginScriptBinding(script)
    }

    private fun createFunction() {
        val function = MacrosModule.INSTANCE.createFunction()
        selectFunction(function)
        tab = Tab.FUNCTIONS
        dirty()
    }

    private fun ensureFunction() {
        val function = selectedFunction
        if (function == null || MacrosModule.INSTANCE.function(function.id()) == null) selectedFunction = firstFunction()
    }

    private fun firstFunction(): MacroFunction? {
        val functions = MacrosModule.INSTANCE.functions()
        return if (functions.isEmpty()) null else functions.first()
    }

    private fun addFunctionParameter(function: MacroFunction?) {
        if (function == null) return
        val number = function.parameters().size + 1
        function.parameters().add(MacroFunction.Parameter("input$number", MacroValue.Type.TEXT, ""))
        dirty()
    }

    private fun updateParameter(function: MacroFunction?, index: Int, name: String?,
        type: MacroValue.Type?, defaultValue: String?) {
        if (function == null || index < 0 || index >= function.parameters().size) return
        val old = function.parameters()[index]!!
        function.parameters()[index] = MacroFunction.Parameter(name ?: old.name(), type ?: old.type(),
            defaultValue ?: old.defaultValue())
        dirty()
    }

    private fun removeFunctionParameter(function: MacroFunction?, index: Int) {
        if (function != null && index >= 0 && index < function.parameters().size) {
            function.parameters().removeAt(index)
            dirty()
        }
    }

    private fun selectScript(script: MacroScript?) {
        if (script == null) return
        selectedScript = script
        selectedStep = null
        selectedOwner = null
        inspectorScroll = 0
    }

    private fun selectFunction(function: MacroFunction?) {
        if (function == null) return
        selectedFunction = function
        selectedStep = null
        selectedOwner = null
        inspectorScroll = 0
    }

    private fun selectStep(step: MacroStep, owner: List<MacroStep>?, script: MacroScript?, function: MacroFunction?) {
        selectedStep = step
        selectedOwner = owner as? MutableList<MacroStep>
        if (function != null) {
            tab = Tab.FUNCTIONS
            selectedFunction = function
        } else {
            tab = Tab.CODE
            if (script != null) selectedScript = script
        }
        inspectorScroll = 0
    }

    private fun clearSelection() {
        selectedStep = null
        selectedOwner = null
        inspectorScroll = 0
    }

    private fun focusField(binding: FieldBinding?) {
        if (binding == null) return
        if (binding.id == focusedField) {
            // Clicking the active field must retain its draft and per-field undo history.
            focusedSetter = binding.setter
            return
        }
        focusedField = binding.id
        focusedSetter = binding.setter
        fieldText = safe(binding.getter.get())
        fieldInitialText = fieldText
        fieldCursor = fieldText.length
        fieldSelectionAnchor = -1
        fieldHistory = if (focusedField == "palette-search") null
            else BoundedUndoHistory(HISTORY_LIMIT, currentFieldDraft())
    }

    private fun applyFocusedField() {
        focusedSetter?.accept(fieldText)
        if (focusedField != "palette-search") dirty()
    }

    private fun commitFocusedField() {
        if (focusedField == null) return
        applyFocusedField()
        focusedField = null
        focusedSetter = null
        fieldCursor = 0
        fieldSelectionAnchor = -1
        fieldSelecting = null
        fieldSelectingBounds = null
        fieldSelectionDragAnchor = -1
        fieldHistory = null
    }

    private fun cancelFocusedField() {
        if (focusedField == "palette-search") {
            paletteSearch = fieldInitialText
            paletteScroll = 0
        }
        fieldText = fieldInitialText
        focusedField = null
        focusedSetter = null
        fieldCursor = 0
        fieldSelectionAnchor = -1
        fieldSelecting = null
        fieldSelectingBounds = null
        fieldSelectionDragAnchor = -1
        fieldHistory = null
    }

    private fun dirty() {
        recordDocumentEdit()
        workspaceRevision++
        canvasTargetCacheValid = false
        measuredStepHeights.clear()
        measuredListLayouts.clear()
        measuredStepWidths.clear()
        measuredOwnWidths.clear()
        measuredListWidths.clear()
        visibleScriptsRevision = -1
        visibleDetachedRevision = -1
        ModConfig.markDirty()
    }

    private fun recordDocumentEdit() {
        if (!restoringHistory) documentHistory.record(captureDocumentSnapshot())
    }

    private fun captureDocumentSnapshot(): DocumentSnapshot {
        val activeMacro = macro
        val scripts = activeMacro?.let { MacroScriptConfigCodec.encode(it.scripts()).toString() }
        val detached = activeMacro?.let { MacroStepConfigCodec.encode(it.detachedBlocks()).toString() }
        val functionSnapshots = ArrayList<FunctionSnapshot>()
        for (function in MacrosModule.INSTANCE.functions()) {
            functionSnapshots.add(FunctionSnapshot(function.id(), function.name(),
        function.parameters().toList(), MacroStepConfigCodec.encode(function.steps()).toString(),
                function.canvasX(), function.canvasY()))
        }
        val store = MacrosModule.INSTANCE.globalVariables()
        val globalSnapshots = ArrayList<GlobalVariableSnapshot>()
        for (definition in store.definitions()) {
            globalSnapshots.add(GlobalVariableSnapshot(definition, safe(store.value(definition.id()).toString())))
        }
        return DocumentSnapshot(scripts, detached, functionSnapshots.toList(), globalSnapshots.toList())
    }

    private fun restoreDocumentSnapshot(snapshot: DocumentSnapshot) {
        restoringHistory = true
        try {
            val activeMacro = macro
            if (activeMacro != null) {
                activeMacro.scripts().clear()
                activeMacro.scripts().addAll(MacroScriptConfigCodec.decode(
                    JsonParser.parseString(snapshot.scriptsJson!!).asJsonArray))
                activeMacro.primaryKeyScript()
                activeMacro.detachedBlocks().clear()
                activeMacro.detachedBlocks().addAll(MacroStepConfigCodec.decode(
                    JsonParser.parseString(snapshot.detachedJson!!).asJsonArray))
            }
            val restoredFunctions = ArrayList<MacroFunction>(snapshot.functions.size)
            for (saved in snapshot.functions) {
                val function = MacroFunction(saved.id)
                function.setName(saved.name)
                function.parameters().addAll(saved.parameters)
                function.steps().addAll(MacroStepConfigCodec.decode(JsonParser.parseString(saved.stepsJson).asJsonArray))
                function.setCanvasPosition(saved.canvasX, saved.canvasY)
                restoredFunctions.add(function)
            }
            MacrosModule.INSTANCE.restoreFunctions(restoredFunctions)

            val store = MacrosModule.INSTANCE.globalVariables()
            val savedVariables = snapshot.globals.map { saved ->
                MacroVariableStore.SavedVariable(saved.definition,
                    if (saved.definition.persistValue()) saved.liveValue else null)
            }
            store.restore(savedVariables)
            for (saved in snapshot.globals) store.setValue(saved.definition.id(), saved.liveValue)
        } finally {
            restoringHistory = false
        }

        clearSelection()
        selectedScript = macro?.primaryKeyScript()
        selectedFunction = firstFunction()
        if (tab == Tab.FUNCTIONS) ensureFunction()
        focusedField = null
        focusedSetter = null
        fieldHistory = null
        fieldCursor = 0
        fieldSelectionAnchor = -1
        fieldSelecting = null
        fieldSelectingBounds = null
        fieldSelectionDragAnchor = -1
        drag = null
        workspaceRevision++
        canvasTargetCacheValid = false
        measuredStepHeights.clear()
        measuredListLayouts.clear()
        measuredStepWidths.clear()
        measuredOwnWidths.clear()
        measuredListWidths.clear()
        visibleScriptsRevision = -1
        visibleDetachedRevision = -1
        documentHistory.resetCurrent(captureDocumentSnapshot())
        ModConfig.markDirty()
    }

    private fun currentFieldDraft(): FieldDraftState = FieldDraftState(fieldText, fieldCursor, fieldSelectionAnchor)

    private fun recordFieldDraft() {
        fieldHistory?.record(currentFieldDraft())
    }

    private fun updateFieldHistoryCursor() {
        fieldHistory?.resetCurrent(currentFieldDraft())
    }

    private fun undoFieldDraft() {
        val previous = fieldHistory?.undo() ?: return
        restoreFieldDraft(previous)
    }

    private fun redoFieldDraft() {
        val next = fieldHistory?.redo() ?: return
        restoreFieldDraft(next)
    }

    private fun restoreFieldDraft(draft: FieldDraftState) {
        fieldText = draft.text
        fieldCursor = clampCursor(fieldText, draft.cursor)
        fieldSelectionAnchor = if (draft.selectionAnchor < 0) -1 else clampCursor(fieldText, draft.selectionAnchor)
        if (focusedField == "palette-search") {
            paletteSearch = fieldText
            paletteScroll = 0
        }
    }

    private fun viewChanged() {
        workspaceRevision++
        canvasTargetCacheValid = false
        visibleScriptsRevision = -1
        ModConfig.markDirty()
    }

    private fun flash(text: String?) {
        notice = text ?: ""
        noticeUntil = System.currentTimeMillis() + 2_400
    }

    private fun paletteLayout(search: String): PaletteLayout {
        val input = safe(search)
        val cached = cachedPaletteLayout
        if (cached != null && input == cachedPaletteInput && cachedPaletteCategory == category) return cached
        val query = javaStrip(input).lowercase(Locale.ROOT)
        val rows = ArrayList<PaletteRow>()
        var contentHeight = 0
        if (query.isEmpty()) {
            for (block in PALETTE_BLOCKS) {
                if (block.category == category) {
                    rows.add(PaletteRow(category, block, false))
                    contentHeight += 39
                }
            }
        } else {
            for (group in Category.values()) {
                val matches = ArrayList<PaletteBlock>()
                for (block in PALETTE_BLOCKS) {
                    if (block.category != group) continue
                    if (block.label.lowercase(Locale.ROOT).contains(query) ||
                        block.hint.lowercase(Locale.ROOT).contains(query) ||
                        group.label.lowercase(Locale.ROOT).contains(query)) matches.add(block)
                }
                if (matches.isEmpty()) continue
                rows.add(PaletteRow(group, null, true))
                contentHeight += 21
                for (block in matches) {
                    rows.add(PaletteRow(group, block, false))
                    contentHeight += 39
                }
            }
        }
        cachedPaletteInput = input
        cachedPaletteCategory = category
        return PaletteLayout(rows.toList(), contentHeight).also { cachedPaletteLayout = it }
    }

    private fun categoryColor(category: Category): Int {
        val custom = when (category) {
            Category.ACTIONS -> MacrosModule.INSTANCE.actionsNodeColor()
            Category.CONTROL -> MacrosModule.INSTANCE.controlNodeColor()
            Category.INPUT -> MacrosModule.INSTANCE.inputNodeColor()
            Category.INVENTORY -> MacrosModule.INSTANCE.inventoryNodeColor()
            Category.DISPLAY -> MacrosModule.INSTANCE.displayNodeColor()
            Category.VARIABLES -> MacrosModule.INSTANCE.dataNodeColor()
            Category.FUNCTIONS -> MacrosModule.INSTANCE.reusableNodeColor()
            Category.WORLD -> MacrosModule.INSTANCE.worldNodeColor()
        }
        if (!VisualModule.INSTANCE.themeSurfaces().value()) return custom
        // Keep macro category hues distinct while integrating them with the selected theme.
        return lerpColor(custom, SLIDER_FILL, 0.42f)
    }

    private fun macroAccentText(fallback: Int): Int =
        if (VisualModule.INSTANCE.themeSurfaces().value()) TEXT_ON_ACCENT else fallback

    private fun blockLabel(step: MacroStep): String {
        if (step is MacroStep.Unknown) return "unknown block  ${step.originalType()}"
        if (step is MacroStep.Command) return "run command  ${step.command()}"
        if (step is MacroStep.Chat) return "send chat  ${step.message()}"
        if (step is MacroStep.Key) return when (step.inputMode()) {
            MacroStep.Key.InputMode.KEYBOARD -> (if (step.hold()) "hold key  " else "press key  ") +
                (if (javaIsBlank(step.key())) "[capture]" else step.key())
            MacroStep.Key.InputMode.MOUSE -> (if (step.hold()) "hold " else "click ") +
                step.mouseButton().name.lowercase(Locale.ROOT) + " mouse"
            MacroStep.Key.InputMode.HOTBAR -> "select hotbar slot  ${step.hotbarSlot()}"
        }
        if (step is MacroStep.Wait) return "wait  ${step.minMillis()}–${step.maxMillis()} ms"
        if (step is MacroStep.Comment) return "comment  ${step.text()}"
        if (step is MacroStep.StopRun) return "stop this macro run"
        if (step is MacroStep.Scroll) return "scroll ${step.direction().name.lowercase(Locale.ROOT)}  ${step.amount()} step" +
            if (step.amount() == 1) "" else "s"
        if (step is MacroStep.InventoryClick) return if (step.target() == MacroStep.InventoryClick.Target.SLOT)
            "inventory click  slot ${step.slotId()}" else "inventory click  ${if (javaIsBlank(step.name())) "[item name]" else step.name()}"
        if (step is MacroStep.UpdateVariable) return "${step.operation().name.lowercase(Locale.ROOT)} variable  ${step.name()}"
        if (step is MacroStep.CloseScreen) return "close current screen"
        if (step is MacroStep.SelectHotbarSlot) return "select hotbar slot  ${step.slot()}"
        if (step is MacroStep.MouseButton) return (if (step.hold()) "hold " else "click ") +
            step.button().name.lowercase(Locale.ROOT) + " mouse"
        if (step is MacroStep.BlockPlayerInput) return "block player input  ${step.durationMillis()} ms"
        if (step is MacroStep.SetView) return "set view  yaw ${"%.1f".format(Locale.ROOT, step.yaw())}  pitch ${"%.1f".format(Locale.ROOT, step.pitch())}"
        if (step is MacroStep.StartBlockPlayerInput) return "start blocking player input"
        if (step is MacroStep.StopBlockPlayerInput) return "stop blocking player input"
        if (step is MacroStep.FunctionCall) {
            val function = MacrosModule.INSTANCE.function(step.functionId())
            return "call function  ${function?.name() ?: "[choose block]"}"
        }
        if (step is MacroStep.MacroCall) {
            val target = MacrosModule.INSTANCE.macro(step.macroId())
            val base = "call macro  ${target?.name() ?: "[choose macro]"}"
            return step.condition()?.let { "$base  if  ${conditionLabel(it)}" } ?: base
        }
        if (step is MacroStep.IfElse) return "if  ${conditionLabel(step.condition())}  then"
        if (step is MacroStep.Repeat) return when (step.mode()) {
            MacroStep.Repeat.Mode.COUNT -> "repeat  ${step.count()}  times"
            MacroStep.Repeat.Mode.FOREVER -> "repeat forever"
            MacroStep.Repeat.Mode.UNTIL -> "repeat until  ${conditionLabel(step.condition())}"
        }
        if (step is MacroStep.RepeatUntil) return "repeat until  ${conditionLabel(step.condition())}"
        if (step is MacroStep.WaitUntil) return "wait until  ${conditionLabel(step.condition())}"
        if (step is MacroStep.Switch) return "switch  ${step.name()}  ·  ${step.cases().size} cases"
        if (step is MacroStep.Title) return "show title  ${step.text()}"
        if (step is MacroStep.Sound) return "play sound  ${step.soundId()}"
        if (step is MacroStep.WorldSwitch) return "switch world  ${step.target().label()}"
        return step.type()
    }

    private fun drawInlineBlockPreview(graphics: GuiGraphicsExtractor, viewport: GuiRect, head: GuiRect,
        step: MacroStep, blockColor: Int, color: Int) {
        val clip = head.intersection(viewport) ?: return
        graphics.enableScissor(clip.x, clip.y, clip.x + clip.w, clip.y + clip.h)
        try {
            val textY = head.y + (head.h - 8) / 2 + 1
            val preview = inlinePreview(step)
            if (preview == null) {
                graphics.text(font, blockLabel(step), head.x + 9, textY, color)
                return
            }
            var x = head.x + 9
            graphics.text(font, preview.prefix, x, textY, color)
            x += font.width(preview.prefix) + 4
            val chipW = chipWidth(preview)
            val chipY = head.y + (head.h - CHIP_HEIGHT) / 2 + 1
            val value = chipText(preview)
            val edge = blockOutline(blockColor)
            val dark = lerpColor(blockColor, 0xFF000000.toInt(), 0.16f)
            when (preview.kind) {
                ChipKind.VALUE -> {
                    roundedRectBordered(graphics, x, chipY, chipW, CHIP_HEIGHT, CHIP_HEIGHT / 2,
                        0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), edge)
                    graphics.text(font, value, x + (chipW - font.width(value)) / 2, chipY + 5, PILL_TEXT, false)
                }
                ChipKind.CHOICE -> {
                    roundedRectBordered(graphics, x, chipY, chipW, CHIP_HEIGHT, CHIP_HEIGHT / 2, dark, dark, edge)
                    graphics.text(font, value, x + 7, chipY + 5, color)
                    graphics.text(font, "\u25BE", x + chipW - 12, chipY + 5, color)
                }
                ChipKind.CONDITION -> {
                    paintShape(graphics, ScratchBlockShapes.hexagon(chipW, CHIP_HEIGHT), x, chipY, dark, edge)
                    graphics.text(font, value, x + (chipW - font.width(value)) / 2, chipY + 5, color)
                }
            }
            x += chipW + 4
            if (!javaIsBlank(preview.suffix)) graphics.text(font, preview.suffix, x, textY, color)
        } finally {
            graphics.disableScissor()
        }
    }

    private fun chipText(preview: BlockPreview): String = trim(preview.value, CHIP_TEXT_MAX)

    private fun chipWidth(preview: BlockPreview): Int {
        val text = font.width(chipText(preview))
        return when (preview.kind) {
            ChipKind.VALUE -> maxOf(22, text + 12)
            ChipKind.CHOICE -> text + 22
            ChipKind.CONDITION -> maxOf(30, text + 20)
        }
    }

    private fun previewWidth(preview: BlockPreview): Int =
        font.width(preview.prefix) + 4 + chipWidth(preview) +
            if (javaIsBlank(preview.suffix)) 0 else 4 + font.width(preview.suffix)

    private fun inlinePreview(step: MacroStep): BlockPreview? {
        if (step is MacroStep.Unknown) return BlockPreview("preserved block", step.originalType(), "stops this run")
        if (step is MacroStep.Command) return BlockPreview("run command", step.command(), "")
        if (step is MacroStep.Chat) return BlockPreview("send chat", step.message(), "")
        if (step is MacroStep.Key) return when (step.inputMode()) {
            MacroStep.Key.InputMode.KEYBOARD -> BlockPreview(if (step.hold()) "hold key" else "press key",
                if (javaIsBlank(step.key())) "capture" else step.key(), "", ChipKind.CHOICE)
            MacroStep.Key.InputMode.MOUSE -> BlockPreview(if (step.hold()) "hold" else "click",
                step.mouseButton().name.lowercase(Locale.ROOT), "mouse", ChipKind.CHOICE)
            MacroStep.Key.InputMode.HOTBAR -> BlockPreview("select hotbar slot", step.hotbarSlot().toString(), "")
        }
        if (step is MacroStep.Wait) return if (step.mode() == MacroStep.Wait.Mode.CONDITION)
            BlockPreview("wait until", conditionLabel(step.condition()), "", ChipKind.CONDITION)
        else BlockPreview("wait", "${step.minMillis()}–${step.maxMillis()} ms", "")
        if (step is MacroStep.Comment) return BlockPreview("note", step.text(), "")
        if (step is MacroStep.Scroll) return BlockPreview("scroll", step.direction().name.lowercase(Locale.ROOT),
            "${step.amount()} step(s)", ChipKind.CHOICE)
        if (step is MacroStep.InventoryClick) return BlockPreview("inventory click",
            if (step.target() == MacroStep.InventoryClick.Target.SLOT) "slot ${step.slotId()}" else step.name(), "")
        if (step is MacroStep.UpdateVariable) return BlockPreview(step.operation().name.lowercase(Locale.ROOT), step.name(),
            if (step.operation() == MacroStep.UpdateVariable.Operation.SET) step.value().value() else step.amount().toString(),
            ChipKind.CHOICE)
        if (step is MacroStep.SelectHotbarSlot) return BlockPreview("select hotbar slot", step.slot().toString(), "")
        if (step is MacroStep.MouseButton) return BlockPreview(if (step.hold()) "hold" else "click",
            step.button().name.lowercase(Locale.ROOT), "mouse", ChipKind.CHOICE)
        if (step is MacroStep.BlockPlayerInput) return BlockPreview("block player input", "${step.durationMillis()} ms", "")
        if (step is MacroStep.SetView) return BlockPreview("set view", "yaw ${"%.0f".format(Locale.ROOT, step.yaw())}",
            "pitch ${"%.0f".format(Locale.ROOT, step.pitch())}")
        if (step is MacroStep.FunctionCall) {
            val function = MacrosModule.INSTANCE.function(step.functionId())
            return BlockPreview("call function", function?.name() ?: "choose", "", ChipKind.CHOICE)
        }
        if (step is MacroStep.MacroCall) {
            val target = MacrosModule.INSTANCE.macro(step.macroId())
            val suffix = step.condition()?.let { "if ${conditionLabel(it)}" } ?: ""
            return BlockPreview("call macro", target?.name() ?: "choose", suffix, ChipKind.CHOICE)
        }
        if (step is MacroStep.IfElse) return BlockPreview("if", conditionLabel(step.condition()), "then", ChipKind.CONDITION)
        if (step is MacroStep.Repeat) return when (step.mode()) {
            MacroStep.Repeat.Mode.COUNT -> BlockPreview("repeat", step.count().toString(), "times")
            MacroStep.Repeat.Mode.FOREVER -> null
            MacroStep.Repeat.Mode.UNTIL -> BlockPreview("repeat until", conditionLabel(step.condition()), "", ChipKind.CONDITION)
        }
        if (step is MacroStep.RepeatUntil) return BlockPreview("repeat until", conditionLabel(step.condition()), "", ChipKind.CONDITION)
        if (step is MacroStep.WaitUntil) return BlockPreview("wait until", conditionLabel(step.condition()), "", ChipKind.CONDITION)
        if (step is MacroStep.Switch) return BlockPreview("switch", step.name(), "", ChipKind.CHOICE)
        if (step is MacroStep.StopRun) return null
        if (step is MacroStep.Title) return BlockPreview("show title", step.text(), "")
        if (step is MacroStep.Sound) return BlockPreview("play sound", step.soundId(), "", ChipKind.CHOICE)
        return null
    }

    private fun blockColor(step: MacroStep): Int {
        if (step is MacroStep.Unknown) return TEXT_ERROR
        if (step is MacroStep.IfElse || step is MacroStep.Repeat || step is MacroStep.RepeatUntil ||
            step is MacroStep.Switch || step is MacroStep.WaitUntil || step is MacroStep.Comment ||
            step is MacroStep.Wait || step is MacroStep.MacroCall) return categoryColor(Category.CONTROL)
        if (step is MacroStep.UpdateVariable) {
            return categoryColor(Category.VARIABLES)
        }
        if (step is MacroStep.FunctionCall) return categoryColor(Category.FUNCTIONS)
        if (step is MacroStep.WorldSwitch) return categoryColor(Category.WORLD)
        if (step is MacroStep.InventoryClick) {
            return categoryColor(Category.INVENTORY)
        }
        if (step is MacroStep.Title || step is MacroStep.Sound || step is MacroStep.CloseScreen) {
            return categoryColor(Category.DISPLAY)
        }
        if (step is MacroStep.Key || step is MacroStep.MouseButton || step is MacroStep.SelectHotbarSlot ||
            step is MacroStep.BlockPlayerInput || step is MacroStep.StartBlockPlayerInput || step is MacroStep.SetView ||
            step is MacroStep.StopBlockPlayerInput || step is MacroStep.Scroll || step is MacroStep.StopRun) {
            return categoryColor(Category.INPUT)
        }
        return categoryColor(Category.ACTIONS)
    }

    private fun regionHatLabel(region: MacroWorldRegion?): String {
        if (region == null || !region.placed()) return "when inside  [area not placed]"
        return "when inside  ${region.shape().name.lowercase(Locale.ROOT)}" +
            if (region.oncePerWorld()) "  · once" else "  · repeat ${region.repeatDelayMillis()}ms"
    }

    /** The hat line describes the chat match and any manual key binding. */
    private fun chatHatLabel(script: MacroScript): String {
        val pattern = if (javaIsBlank(script.chatPattern())) "…" else script.chatPattern()
        val base = "when chat  ${if (script.chatContains()) "contains" else "matches"}  “$pattern”"
        return if (script.keybind().isBound()) "$base  · ${script.keybind().displayName()}" else base
    }

    private fun triggerName(trigger: MacroScript.Trigger): String = when (trigger) {
        MacroScript.Trigger.KEY_PRESS -> "Key pressed"
        MacroScript.Trigger.WORLD_REGION -> "World area"
        MacroScript.Trigger.ON_CALL -> "On Call"
        MacroScript.Trigger.CHAT -> "Chat message"
        MacroScript.Trigger.PEST_SPAWNED -> "Pest spawned"
        MacroScript.Trigger.PEST_COOLDOWN_READY -> "Pest cooldown ready"
    }

    private fun stateColor(state: MacroRunner.RunState): Int = when (state) {
        MacroRunner.RunState.RUNNING -> 0xFF37D997.toInt()
        MacroRunner.RunState.WAITING -> 0xFFFFC55C.toInt()
        MacroRunner.RunState.PAUSED -> 0xFFFF755D.toInt()
        MacroRunner.RunState.FINISHED -> 0xFF8CB7FF.toInt()
        MacroRunner.RunState.IDLE -> 0xFF394653.toInt()
    }

    private fun stateLabel(state: MacroRunner.RunState): String = when (state) {
        MacroRunner.RunState.RUNNING -> "RUN"
        MacroRunner.RunState.WAITING -> "WAIT"
        MacroRunner.RunState.PAUSED -> "PAUSE"
        MacroRunner.RunState.FINISHED -> "DONE"
        MacroRunner.RunState.IDLE -> "IDLE"
    }

    private fun isActiveRunState(state: MacroRunner.RunState): Boolean =
        state == MacroRunner.RunState.RUNNING || state == MacroRunner.RunState.WAITING ||
            state == MacroRunner.RunState.PAUSED

    private fun conditionLabel(condition: MacroCondition?): String {
        if (condition is MacroCondition.Always) return if (condition.expected()) "always" else "never"
        if (condition is MacroCondition.World) return if (condition.mustBeInWorld()) "in world" else "not in world"
        if (condition is MacroCondition.Screen) return "screen title"
        if (condition is MacroCondition.Slot) return "slot ${condition.slotId()}"
        if (condition is MacroCondition.Item) {
            val itemName = condition.name() ?: ""
            return "item ${if (javaIsBlank(itemName)) "exists" else itemName}"
        }
        if (condition is MacroCondition.InventoryCount) return "inventory count " +
            "${condition.operator().name.lowercase(Locale.ROOT)} ${condition.threshold()}"
        if (condition is MacroCondition.Chat) {
            val chatText = condition.text() ?: ""
            return "chat ${if (javaIsBlank(chatText)) "matches" else chatText}"
        }
        if (condition is MacroCondition.Variable) return "variable ${condition.operator().name.lowercase(Locale.ROOT)}"
        if (condition is MacroCondition.Hypixel) return "Hypixel ${condition.field().name.lowercase(Locale.ROOT)}"
        if (condition is MacroCondition.Player) return "player ${condition.field().name.lowercase(Locale.ROOT)}"
        if (condition is MacroCondition.All) return "all (${condition.children().size})"
        if (condition is MacroCondition.Any) return "any (${condition.children().size})"
        if (condition is MacroCondition.Not) return "not"
        return condition?.javaClass?.simpleName?.lowercase(Locale.ROOT) ?: "always"
    }

    private fun next(value: MacroWorldRegion.Shape): MacroWorldRegion.Shape {
        val values = MacroWorldRegion.Shape.values()
        return values[(value.ordinal + 1) % values.size]
    }

    private fun next(value: MacroStep.MouseButton.Button): MacroStep.MouseButton.Button {
        val values = MacroStep.MouseButton.Button.values()
        return values[(value.ordinal + 1) % values.size]
    }

    private fun next(value: MacroStep.Key.InputMode): MacroStep.Key.InputMode {
        val values = MacroStep.Key.InputMode.values()
        return values[(value.ordinal + 1) % values.size]
    }

    private fun next(value: MacroStep.Repeat.Mode): MacroStep.Repeat.Mode {
        val values = MacroStep.Repeat.Mode.values()
        return values[(value.ordinal + 1) % values.size]
    }

    private fun next(value: MacroValue.Type): MacroValue.Type {
        val values = MacroValue.Type.values()
        return values[(value.ordinal + 1) % values.size]
    }

    @Suppress("UNCHECKED_CAST")
    private fun <E : Enum<E>> nextEnum(value: E): E {
        val enumClass = value.javaClass.let { if (it.isEnum) it else it.superclass }
        val values = enumClass.enumConstants!!
        return values[(value.ordinal + 1) % values.size] as E
    }

    private fun trim(value: String?, width: Int): String = GuiTheme.fit(font, value, width)

    private fun title(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
        return if (lower.isEmpty()) lower else Character.toUpperCase(lower[0]).toString() + lower.substring(1)
    }

    private fun javaTrim(value: String): String = JavaStringSemantics.trim(value)

    private fun javaStrip(value: String): String = JavaStringSemantics.strip(value)

    private fun javaIsBlank(value: String): Boolean = JavaStringSemantics.isBlank(value)

    private fun safe(value: String?): String = value ?: ""

    private fun clamp(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(max, value))

    private fun clamp(value: Float, min: Float, max: Float): Float = maxOf(min, minOf(max, value))

    private data class Layout(val panel: GuiRect, val palette: GuiRect, val canvas: GuiRect, val inspector: GuiRect, val footerY: Int)


    private enum class Tab { CODE, FUNCTIONS }

    private enum class Category(val label: String, val shortLabel: String, val color: Int) {
        ACTIONS("Actions", "Act", COLOR_MOTION),
        CONTROL("Control", "Ctrl", COLOR_CONTROL),
        INPUT("Player Input", "Input", COLOR_INPUT),
        INVENTORY("Inventory", "Inv", COLOR_INPUT),
        DISPLAY("Display", "View", 0xFF2EB8D4.toInt()),
        VARIABLES("Data", "Data", COLOR_VARIABLE),
        FUNCTIONS("Reusable", "Reuse", COLOR_FUNCTION),
        WORLD("World", "Wld", 0xFF668E3D.toInt())
    }

    private enum class DragKind { PAN, SCRIPT, FUNCTION, STEP, PALETTE }

    private data class PaletteBlock(val label: String, val hint: String, val category: Category,
        val factory: Supplier<MacroStep>, val detachedOnly: Boolean = false)

    private data class PaletteRow(val category: Category, val block: PaletteBlock?, val header: Boolean)

    private data class PaletteLayout(val rows: List<PaletteRow>, val contentHeight: Int)

    private enum class ChipKind { VALUE, CHOICE, CONDITION }

    private data class BlockPreview(val prefix: String, val value: String, val suffix: String,
        val kind: ChipKind = ChipKind.VALUE)

    private class CBlockLayout(val bars: IntArray, val inners: IntArray, val branches: List<MutableList<MacroStep>>,
        val labels: List<String?>, val cap: Boolean, val loop: Boolean) {
        val height: Int = bars.sum() + inners.sum()
    }

    private data class PickerOption(val label: String, val action: Runnable)

    private data class CanvasScriptPlacement(val script: MacroScript, val x: Int, val y: Int,
        val width: Int, val zoom: Float)

    private data class CanvasDetachedPlacement(val step: MacroStep, val index: Int, val x: Int, val y: Int,
        val width: Int, val height: Int, val note: Boolean)

    private data class ListLayout(val starts: IntArray, val heights: IntArray, val totalHeight: Int) {
        fun firstIntersecting(y: Int): Int {
            var low = 0
            var high = heights.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (starts[middle] + heights[middle] < y) low = middle + 1 else high = middle
            }
            return low
        }
    }

    private class PickerState(title: String?, options: List<PickerOption>, val searchable: Boolean) {
        val title: String = title ?: "Choose an option"
        private val options: List<PickerOption> = options.toList()
        var search = ""
        var searchCursor = 0
        var searchSelectionAnchor = -1
        private var cachedSearch: String? = null
        private var cachedVisibleOptions: List<PickerOption>? = null
        var scroll = 0
        var maxScroll = 0
        var visibleRows = 1
        var selected = 0

        fun updateSearch(value: String?) {
            search = value ?: ""
            searchCursor = search.length
            searchSelectionAnchor = -1
            selected = 0
            scroll = 0
            cachedSearch = null
            cachedVisibleOptions = null
        }

        fun visibleOptions(): List<PickerOption> {
            if (!searchable || MacroJavaStrings.isBlank(search)) return options
            if (search != cachedSearch) {
                val query = search.lowercase(Locale.ROOT)
                cachedVisibleOptions = options.filter { it.label.lowercase(Locale.ROOT).contains(query) }
                cachedSearch = search
            }
            return cachedVisibleOptions ?: emptyList()
        }
    }

    private data class DocumentSnapshot(val scriptsJson: String?, val detachedJson: String?,
        val functions: List<FunctionSnapshot>, val globals: List<GlobalVariableSnapshot>)

    private data class FunctionSnapshot(val id: String, val name: String, val parameters: List<MacroFunction.Parameter?>,
        val stepsJson: String, val canvasX: Float, val canvasY: Float)

    private data class GlobalVariableSnapshot(val definition: MacroVariableStore.Definition, val liveValue: String)

    private data class FieldDraftState(val text: String, val cursor: Int, val selectionAnchor: Int)

    private data class FieldBinding(val id: String, val getter: Supplier<String>, val setter: Consumer<String>,
        val leftInset: Int = 7, val rightInset: Int = 5)

    private data class VisibleFieldLayout(val text: String, val startIndex: Int, val cursorIndex: Int,
        val endIndex: Int, val prefixWidth: Int, val caretWidth: Int)

    private data class HitTarget(val bounds: GuiRect, val action: Runnable?, val step: MacroStep?,
        val owner: List<MacroStep>?, val paletteBlock: PaletteBlock?, val script: MacroScript?,
        val function: MacroFunction?, val field: FieldBinding?)

    private data class DropSlot(val bounds: GuiRect, val owner: MutableList<MacroStep>, val index: Int,
        val functionScope: Boolean, val script: MacroScript?, val function: MacroFunction?, val depth: Int)

    private class DragState private constructor(
        val kind: DragKind,
        val startX: Int,
        val startY: Int,
        val startPanX: Float,
        val startPanY: Float,
        val startCanvasX: Float,
        val startCanvasY: Float,
        val script: MacroScript?,
        val function: MacroFunction?,
        val step: MacroStep?,
        val owner: MutableList<MacroStep>?,
        val palette: PaletteBlock?,
        val pointerOffsetX: Int,
        val pointerOffsetY: Int
    ) {
        var moved = false

        companion object {
            fun pan(x: Int, y: Int, panX: Float, panY: Float) = DragState(
                DragKind.PAN, x, y, panX, panY, 0f, 0f, null, null, null, null, null, 0, 0)

            fun palette(block: PaletteBlock, x: Int, y: Int) = DragState(
                DragKind.PALETTE, x, y, 0f, 0f, 0f, 0f, null, null, null, null, block, 0, 0)

            fun script(script: MacroScript, x: Int, y: Int) = DragState(
                DragKind.SCRIPT, x, y, 0f, 0f, script.canvasX(), script.canvasY(), script, null, null, null, null, 0, 0)

            fun function(function: MacroFunction, x: Int, y: Int) = DragState(
                DragKind.FUNCTION, x, y, 0f, 0f, function.canvasX(), function.canvasY(), null, function,
                null, null, null, 0, 0)

            fun step(step: MacroStep, owner: MutableList<MacroStep>, script: MacroScript?,
                function: MacroFunction?, x: Int, y: Int, bounds: GuiRect) = DragState(
                DragKind.STEP, x, y, 0f, 0f, 0f, 0f, script, function, step, owner, null,
                x - bounds.x, y - bounds.y)
        }
    }
}
