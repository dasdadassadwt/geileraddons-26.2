package geiler.addons.client.gui

import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.macro.MacroTriggerContext
import geiler.addons.client.hud.HudManager
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Workspace
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.DebugState
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.ModuleCategoryFilter
import geiler.addons.client.module.ModuleKeybind
import geiler.addons.client.module.ModuleKeybindManager
import geiler.addons.client.module.ModuleManager
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.RangeSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.module.impl.GeneralModule
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.update.ProjectLinks
import geiler.addons.client.update.UpdateChecker
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.FormattedCharSequence
import org.lwjgl.glfw.GLFW
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Comparator
import java.util.Deque
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

import geiler.addons.client.gui.ClickGuiSettingsLayout.GROUP_INDENT
import geiler.addons.client.gui.ClickGuiSettingsLayout.PADDING
import geiler.addons.client.gui.ClickGuiSettingsLayout.pickerHeight
import geiler.addons.client.gui.ClickGuiSettingsLayout.previewHeight
import geiler.addons.client.gui.ClickGuiSettingsLayout.ROW_HEIGHT
import geiler.addons.client.gui.ClickGuiSettingsLayout.SWITCH_HEIGHT
import geiler.addons.client.gui.ClickGuiSettingsLayout.SWITCH_WIDTH
import geiler.addons.client.gui.GuiTheme.BORDER
import geiler.addons.client.gui.GuiTheme.BUTTON_BG
import geiler.addons.client.gui.GuiTheme.BUTTON_HOVER
import geiler.addons.client.gui.GuiTheme.CARD_BG
import geiler.addons.client.gui.GuiTheme.CARD_BG_ENABLED
import geiler.addons.client.gui.GuiTheme.CARD_BG_HOVER
import geiler.addons.client.gui.GuiTheme.CARD_BORDER
import geiler.addons.client.gui.GuiTheme.CARD_BORDER_ENABLED
import geiler.addons.client.gui.GuiTheme.CATEGORY_HOVER
import geiler.addons.client.gui.GuiTheme.CATEGORY_SELECTED
import geiler.addons.client.gui.GuiTheme.GROUP_HEADER
import geiler.addons.client.gui.GuiTheme.PANEL_BOTTOM
import geiler.addons.client.gui.GuiTheme.PANEL_HIGHLIGHT
import geiler.addons.client.gui.GuiTheme.PANEL_TOP
import geiler.addons.client.gui.GuiTheme.RADIUS_SMALL
import geiler.addons.client.gui.GuiTheme.SCROLLBAR
import geiler.addons.client.gui.GuiTheme.SLIDER_FILL
import geiler.addons.client.gui.GuiTheme.SLIDER_TRACK
import geiler.addons.client.gui.GuiTheme.TEXT_MUTED
import geiler.addons.client.gui.GuiTheme.TEXT_ON_ACCENT
import geiler.addons.client.gui.GuiTheme.TEXT_PRIMARY
import geiler.addons.client.gui.GuiTheme.TEXT_SECONDARY
import geiler.addons.client.gui.GuiTheme.TEXT_WARN
import geiler.addons.client.gui.GuiTheme.lerpColor
import geiler.addons.client.gui.GuiTheme.roundedRect
import geiler.addons.client.gui.GuiTheme.roundedRectBordered
import geiler.addons.client.gui.GuiTheme.roundedRectOutline
import geiler.addons.client.gui.GuiTheme.toggleSwitch
import geiler.addons.client.gui.GuiTheme.withOpacity
import geiler.addons.client.gui.ClickGuiTextEditing.applyKeyWithSelection
import geiler.addons.client.gui.ClickGuiTextEditing.deleteSelection
import geiler.addons.client.gui.ClickGuiTextEditing.insertReplacingSelection
import geiler.addons.client.gui.ClickGuiTextEditing.sanitizePaste
import geiler.addons.client.gui.ClickGuiTextEditing.selectAllState
import geiler.addons.client.gui.ClickGuiTextEditing.selectedText
import geiler.addons.client.gui.ClickGuiTextEditing.selection

/** The module and settings screen, rendered through Minecraft's client-thread extractor. */
class ClickGuiScreen @JvmOverloads constructor(
    /** Opened from the Classic menu: leaving a module's settings goes back to it instead of to the module grid. */
    private val fromClassic: Boolean = false,
) : Screen(Component.literal("GeilerAddons")) {
    private var selectedCategory: Category = ClickGuiState.category() ?: Category.of(ClickGuiState.workspace())[0]
    private var openSettingsModule: Module? = ClickGuiState.openModule()
    private var expandedColorSetting: ColorSetting? = ClickGuiState.expandedColor()
    private var draggingPicker: PickerPart? = null
    private var draggingNumberSetting: NumberSetting? = null

    /** The range being dragged and which of its two thumbs follows the mouse. */
    private var draggingRange: RangeSetting? = null
    private var draggingRangeHigh = false

    /** The preview that took the mouse press now being dragged, if any. */
    private var draggingPreview: ModulePreview? = null
    /** The card under a held left click; it shrinks slightly and stretches its knob until the release. */
    private var pressedCard: Module? = null
    private var focusedTextSetting: TextSetting? = null
    private var textCursor = 0
    private var textAnchor = -1
    private var focusedNumberSetting: NumberSetting? = null
    private var numberInput = ""
    private var numberCursor = 0
    private var numberAnchor = -1
    /** The colour whose hex field has focus, and what has been typed into it so far. */
    private var focusedHexSetting: ColorSetting? = null
    private var hexInput = ""
    private var hexCursor = 0
    private var hexAnchor = -1
    /** The field whose selection follows the mouse while the left button is held. */
    private var selectingField: SelectingField? = null
    private var settingsScroll = ClickGuiState.settingsScroll()
    private var gridScroll = ClickGuiState.gridScroll(selectedCategory)
    private var categoryScroll = ClickGuiState.categoryScroll()
    private var settingsScrollVisual = settingsScroll.toDouble()
    private var gridScrollVisual = gridScroll.toDouble()

    /** The current target view is applied immediately; these snapshots let it slide in cleanly. */
    private var transitionFrom: ViewState? = null
    private var transitionTo: ViewState? = null
    private var transitionDirection = 0
    private var transitionStartedNanos = 0L
    private val navigationQueue: Deque<NavigationRequest> = ArrayDeque()

    private val openedAtNanos = System.nanoTime()
    /** When the current view appeared; card entrances run on it, so they keep going after the category slide ends. */
    private var viewEnteredNanos = openedAtNanos
    private var lifecycleStartedNanos = openedAtNanos
    private var closing = false
    private var pendingScreen: Screen? = null
    private var lastFrameNanos = openedAtNanos
    /** When a scrollbar last moved; the thin bars fade out shortly after. */
    private var lastScrollNanos = 0L
    private var searchQuery = ""
    private var stashedGridQuery = ""
    private var searchCursor = 0
    private var searchFocused = false
    private var searchAnchor = -1

    /** The release-notes panel: open state, its scroll, and the wrapped lines last drawn in it. */
    private var changelogOpen = false
    private var changelogScroll = 0.0
    private var changelogCacheKey = ""
    private var changelogLines: List<String> = emptyList()

    /** Last accepted control, used for a short tactile pulse without delaying the next action. */
    private var lastInteraction: Any? = null
    private var lastInteractionNanos = 0L
    private val togglePulses: MutableMap<Any, TogglePulse> = IdentityHashMap()
    private val cardSummaryLayouts: MutableMap<Module, CardSummaryLayout> = IdentityHashMap()
    private val moduleSearch = ClickGuiModuleSearch()
    private var moduleSectionCacheCategory: Category? = null
    private var moduleSectionCacheModules: List<Module>? = null
    private var moduleSectionCacheQuery: String? = null
    private var moduleSectionCache: List<ClickGuiModuleGrid.Section> = emptyList()
    private var favoriteRevision = 0L
    private var orderedCategorySnapshot: List<Category>? = null
    private var orderedCategoryFavoritesRevision = -1L
    private var categoryWidthCachedFont: Font? = null
    private var categoryWidthCachedPanelWidth = Int.MIN_VALUE
    private var categoryWidthCachedPanelHeight = Int.MIN_VALUE
    private var categoryWidthCachedValue = 0
    private var settingsLayoutCache: SettingsLayoutCache? = null
    private var positionedSettingsLayout: SettingsLayoutCache? = null
    private var positionedSettingsRows: List<Row>? = null
    private var positionedSettingsStartY = Int.MIN_VALUE
    private var settingsLayoutRevision = 0L
    private var guiDiagnosticsActive = false
    private var guiDiagnosticEvents = 0
    private var suppressedGuiDiagnosticEvents = 0

    private var expansionFrom: ColorSetting? = null
    private var expansionTo: ColorSetting? = null
    private var expansionStartedNanos = 0L
    private var settingsLayoutTransition: SettingsLayoutTransition? = null

    init {
        settingsScrollVisual = settingsScroll.toDouble()
        gridScrollVisual = gridScroll.toDouble()
        // A search from the last two minutes carries over; it belongs to the grid, so a module open on top keeps it stashed.
        val recalled = ClickGuiState.recalledSearch()
        if (openSettingsModule == null) {
            searchQuery = recalled
            searchCursor = recalled.length
        } else {
            stashedGridQuery = recalled
        }
    }

    override fun init() {
        super.init()
        if (guiDiagnosticsActive) return
        guiDiagnosticsActive = true
        guiDiagnosticEvents = 0
        suppressedGuiDiagnosticEvents = 0
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, GUI_DIAGNOSTIC_MODULE, true)
        logGuiDiagnostic("STATUS action=open,${gridDiagnosticState(selectedCategory)},${designDiagnostic()}")
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        beginClose(null)
    }

    /** Screen replacement belongs to the client tick, not render extraction. */
    override fun tick() {
        super.tick()
        if (!closing) return
        val motion = motion()
        if (elapsedMillis(lifecycleStartedNanos, System.nanoTime()) < motion.closeMillis()) return
        val next = pendingScreen
        pendingScreen = null
        closing = false
        minecraft.gui.setScreen(next)
    }

    override fun removed() {
        // Written once on the way out rather than on every scroll notch.
        persistView()
        ClickGuiState.rememberSearch(if (openSettingsModule == null) searchQuery else stashedGridQuery)
        ModuleKeybindManager.cancelBinding()
        ModConfig.save()
        if (guiDiagnosticsActive) {
            val tick = guiDiagnosticTick()
            GeilerAddonsLog.write(
                Category.MISCELLANEOUS,
                GUI_DIAGNOSTIC_MODULE,
                tick,
                "STATUS action=close,category=${selectedCategory.name},gridScroll=$gridScroll,suppressedEvents=$suppressedGuiDiagnosticEvents",
            )
            if (suppressedGuiDiagnosticEvents > 0) {
                GeilerAddonsLog.write(
                    Category.MISCELLANEOUS,
                    GUI_DIAGNOSTIC_MODULE,
                    tick,
                    "EVENTS_SUPPRESSED count=$suppressedGuiDiagnosticEvents",
                )
            }
            guiDiagnosticsActive = false
            GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, GUI_DIAGNOSTIC_MODULE, false)
        }
        super.removed()
    }

    private fun guiDiagnosticTick(): Long =
        if (minecraft == null || minecraft.level == null) 0L else minecraft.level!!.gameTime

    private fun logGuiDiagnostic(line: String) {
        if (!guiDiagnosticsActive) return
        if (guiDiagnosticEvents >= GUI_DIAGNOSTIC_EVENT_LIMIT) {
            suppressedGuiDiagnosticEvents++
            return
        }
        guiDiagnosticEvents++
        GeilerAddonsLog.write(Category.MISCELLANEOUS, GUI_DIAGNOSTIC_MODULE, guiDiagnosticTick(), line)
    }

    private fun gridDiagnosticState(category: Category): String {
        val modules = visibleModules(category)
        val layout = moduleGridLayout(category, modules)
        val viewport = gridCardViewport(panelX() + categoryWidth(), panelY(), category)
        val sectionSummary = StringBuilder()
        for (section in layout.sections()) {
            if (sectionSummary.isNotEmpty()) sectionSummary.append('|')
            val label = section.section().label()
            sectionSummary.append(label?.replace(' ', '_')?.replace('&', '+') ?: "all")
                .append(':').append(section.section().modules().size)
        }
        val scroll = if (category == selectedCategory) gridScroll else ClickGuiState.gridScroll(category)
        return "category=${category.name},filter=${ClickGuiState.moduleFilter(category)},columns=${cardColumns()},queryActive=${searchQuery.isNotBlank()},cards=${modules.size},sections=$sectionSummary" +
            ",categoryScroll=$categoryScroll,categoryViewportHeight=${categoryViewportHeight(panelY())}" +
            ",viewport=${viewport.w}x${viewport.h},contentHeight=${layout.contentHeight()},scroll=$scroll" +
            ",maxScroll=${maxOf(0, layout.contentHeight() - viewport.h)}" + firstCardHitDiagnostic(category, modules)
    }

    /** Hit-area sizes (heart, switch, key) of the first card, so a dead or overlapping click area shows in the log. */
    private fun firstCardHitDiagnostic(category: Category, modules: List<Module>): String {
        val card = cardRects(category, modules, panelX() + categoryWidth(), panelY(), 0.0).firstOrNull() ?: return ""
        val bounds = Rect(card.x(), card.y(), card.w(), card.h())
        val hits = cardHitRects(card.module(), bounds)
        return ",hit=" + hits.joinToString("/") { if (it == null) "none" else "${it.w}x${it.h}" }
    }

    private fun persistView() {
        ClickGuiState.setCategory(selectedCategory)
        ClickGuiState.setOpenModule(openSettingsModule)
        ClickGuiState.setExpandedColor(expandedColorSetting)
        ClickGuiState.setSettingsScroll(settingsScroll)
        ClickGuiState.setCategoryScroll(categoryScroll)
        if (searchQuery.isBlank()) ClickGuiState.setGridScroll(selectedCategory, gridScroll)
        ModConfig.markDirty()
    }

    private fun motion(): ClickGuiMotion = ClickGuiMotion.fromSetting(VisualModule.INSTANCE.clickGuiMotion().value())

    /** Advances render-only animation state without putting any of it into the saved GUI state. */
    private fun updateAnimationState(now: Long, motion: ClickGuiMotion) {
        if (motion == ClickGuiMotion.NONE) {
            while (navigationQueue.isNotEmpty()) applyView(navigationQueue.removeFirst().view)
            transitionFrom = null
            transitionTo = null
        } else if (transitionFrom != null) {
            val elapsed = elapsedMillis(transitionStartedNanos, now)
            if (elapsed >= motion.transitionMillis()) {
                transitionFrom = null
                transitionTo = null
                if (navigationQueue.isNotEmpty()) {
                    val next = navigationQueue.removeFirst()
                    startNavigation(next.view, next.direction, now, motion)
                }
            }
        }

        val frameMillis = minOf(50L, elapsedMillis(lastFrameNanos, now))
        lastFrameNanos = now
        if (motion == ClickGuiMotion.NONE) {
            gridScrollVisual = gridScroll.toDouble()
            settingsScrollVisual = settingsScroll.toDouble()
        } else {
            gridScrollVisual = motion.settleScrollVisual(gridScrollVisual, gridScroll.toDouble(), frameMillis)
            settingsScrollVisual = motion.settleScrollVisual(settingsScrollVisual, settingsScroll.toDouble(), frameMillis)
        }
        if ((expansionFrom != null || expansionTo != null) &&
            (motion == ClickGuiMotion.NONE || elapsedMillis(expansionStartedNanos, now) >= motion.transitionMillis())
        ) {
            expansionFrom = null
            expansionTo = null
        }
        val layoutTransition = settingsLayoutTransition
        if (layoutTransition != null &&
            (motion == ClickGuiMotion.NONE || elapsedMillis(layoutTransition.startedNanos, now) >= motion.transitionMillis())
        ) settingsLayoutTransition = null
    }

    private fun lifecycleProgress(now: Long, motion: ClickGuiMotion): Float {
        if (motion == ClickGuiMotion.NONE) return 1.0f
        val elapsed = elapsedMillis(lifecycleStartedNanos, now)
        return if (closing) motion.closeProgress(elapsed) else motion.ease(elapsed.toFloat() / motion.lifecycleMillis())
    }

    private fun transitionProgress(now: Long, motion: ClickGuiMotion): Float {
        if (motion == ClickGuiMotion.NONE || transitionFrom == null) return 1.0f
        return motion.ease(elapsedMillis(transitionStartedNanos, now).toFloat() / motion.transitionMillis())
    }

    private fun currentView() = ViewState(selectedCategory, openSettingsModule, gridScroll, settingsScroll, expandedColorSetting)

    private fun applyView(view: ViewState) {
        if (view.module !== openSettingsModule) {
            // The search box filters whatever is on screen: the module grid, or the open module's settings.
            // Each keeps its own text, so opening a module from search results starts a fresh settings search
            // and going back restores the results.
            if (view.module == null) {
                searchQuery = stashedGridQuery
                stashedGridQuery = ""
            } else {
                if (openSettingsModule == null) stashedGridQuery = searchQuery
                searchQuery = ""
            }
            searchCursor = searchQuery.length
            searchAnchor = -1
        }
        selectedCategory = view.category
        openSettingsModule = view.module
        gridScroll = view.gridScroll
        settingsScroll = view.settingsScroll
        expandedColorSetting = view.expandedColor
    }

    private fun requestNavigation(target: ViewState, direction: Int) {
        val motion = motion()
        if (currentView() == target) return
        if (transitionFrom != null) {
            if (navigationQueue.isEmpty() || navigationQueue.peekLast().view != target) {
                navigationQueue.addLast(NavigationRequest(target, direction))
            }
            return
        }
        if (currentView() == target) return
        startNavigation(target, direction, System.nanoTime(), motion)
    }

    private fun startNavigation(target: ViewState, direction: Int, now: Long, motion: ClickGuiMotion) {
        val from = currentView()
        if (searchQuery.isBlank()) ClickGuiState.setGridScroll(from.category, from.gridScroll)
        applyView(target)
        viewEnteredNanos = now
        if (from.module !== target.module) {
            expansionFrom = null
            expansionTo = null
        }
        if (!motion.animated()) {
            transitionFrom = null
            transitionTo = null
            return
        }
        transitionFrom = from
        transitionTo = target
        transitionDirection = if (direction == 0) 1 else direction
        transitionStartedNanos = now
    }

    private fun beginClose(next: Screen?) {
        if (closing) return
        pendingScreen = next
        navigationQueue.clear()
        transitionFrom = null
        transitionTo = null
        val motion = motion()
        if (!motion.animated()) {
            minecraft.gui.setScreen(next)
            return
        }
        closing = true
        lifecycleStartedNanos = System.nanoTime()
    }

    private fun markInteraction(target: Any?) {
        lastInteraction = target
        lastInteractionNanos = System.nanoTime()
    }

    private fun playClick() {
        minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    private fun interactionPulse(target: Any?, now: Long, motion: ClickGuiMotion): Float {
        if (target == null || target !== lastInteraction || motion == ClickGuiMotion.NONE) return 0.0f
        return 1.0f - motion.ease(elapsedMillis(lastInteractionNanos, now).toFloat() / 180.0f)
    }

    private fun animateToggle(target: Any, from: Boolean, to: Boolean) {
        if (from == to) {
            togglePulses.remove(target)
            return
        }
        togglePulses[target] = TogglePulse(from, to, System.nanoTime())
    }

    /** Knob edge positions (0 = off, 1 = on) as [lead, trail]; they differ only while a toggle settles. */
    private fun toggleEdges(target: Any, on: Boolean, now: Long, motion: ClickGuiMotion): FloatArray {
        val end = if (on) 1.0f else 0.0f
        val pulse = togglePulses[target] ?: return floatArrayOf(end, end)
        val elapsed = elapsedMillis(pulse.startedNanos, now)
        if (elapsed >= motion.liquidMillis()) {
            togglePulses.remove(target)
            return floatArrayOf(end, end)
        }
        val from = if (pulse.from) 1.0f else 0.0f
        val to = if (pulse.to) 1.0f else 0.0f
        return floatArrayOf(lerp(from, to, motion.liquidLead(elapsed)), lerp(from, to, motion.liquidTrail(elapsed)))
    }

    private fun setExpandedColor(target: ColorSetting?) {
        val from = expandedColorSetting
        expandedColorSetting = target
        invalidateSettingsLayout()
        if (from === target || motion() == ClickGuiMotion.NONE) {
            expansionFrom = null
            expansionTo = null
            return
        }
        expansionFrom = from
        expansionTo = target
        expansionStartedNanos = System.nanoTime()
    }

    private fun startSettingsLayoutTransition(module: Module, from: List<Row>, to: List<Row>) {
        if (motion() == ClickGuiMotion.NONE || from == to) {
            settingsLayoutTransition = null
            return
        }
        settingsLayoutTransition = SettingsLayoutTransition.create(module, from, to, System.nanoTime())
    }

    private fun settingsLayoutProgress(now: Long, motion: ClickGuiMotion): Float {
        val transition = settingsLayoutTransition ?: return 1.0f
        if (motion == ClickGuiMotion.NONE) return 1.0f
        return motion.ease(elapsedMillis(transition.startedNanos, now).toFloat() / motion.transitionMillis())
    }

    private fun expansionProgress(now: Long, motion: ClickGuiMotion): Float {
        if (expansionFrom == null && expansionTo == null) return 1.0f
        if (motion == ClickGuiMotion.NONE) return 1.0f
        return motion.ease(elapsedMillis(expansionStartedNanos, now).toFloat() / motion.transitionMillis())
    }

    /** During collapse the old geometry remains briefly so the picker can reveal away cleanly. */
    private fun renderedExpandedColor(now: Long, motion: ClickGuiMotion): ColorSetting? {
        if (expansionTo == null && expansionFrom != null && expansionProgress(now, motion) < 1.0f) return expansionFrom
        return expandedColorSetting
    }

    private fun pickerReveal(setting: ColorSetting, now: Long, motion: ClickGuiMotion): Float {
        if (expansionFrom == null && expansionTo == null) return if (setting === expandedColorSetting) 1.0f else 0.0f
        val progress = expansionProgress(now, motion)
        if (setting === expansionTo) return progress
        if (setting === expansionFrom && expansionTo == null) return 1.0f - progress
        return if (setting === expandedColorSetting) 1.0f else 0.0f
    }

    // ---- layout -------------------------------------------------------------------------

    /** Pose scale from design px (GUI scale 2) to the current GUI px; see [ClickGuiDesignScale]. */
    private fun designPose(): Float {
        val window = minecraft.window
        return ClickGuiDesignScale.poseScale(window.width, window.height, window.guiScale.toDouble(),
            VisualModule.INSTANCE.lockGuiScale().value())
    }

    /** Screen size in design px; every layout below works in these units. */
    private fun vw(): Int = ClickGuiDesignScale.virtualSize(width, designPose())
    private fun vh(): Int = ClickGuiDesignScale.virtualSize(height, designPose())

    private fun designDiagnostic(): String {
        val window = minecraft.window
        return "poseScale=${String.format(java.util.Locale.ROOT, "%.3f", designPose())},guiScale=${window.guiScale}," +
            "virtual=${vw()}x${vh()},locked=${VisualModule.INSTANCE.lockGuiScale().value()}"
    }

    private fun panelWidth(): Int = ClickGuiHeaderGeometry.panelWidthForScreen(vw())

    private fun panelHeight(): Int = ClickGuiHeaderGeometry.panelHeightForScreen(vh())

    private fun categoryWidth(): Int {
        val panelWidth = panelWidth()
        val panelHeight = panelHeight()
        if (categoryWidthCachedFont === font && categoryWidthCachedPanelWidth == panelWidth &&
            categoryWidthCachedPanelHeight == panelHeight
        ) return categoryWidthCachedValue

        var longestLabel = 0
        for (category in Category.values()) longestLabel = maxOf(longestLabel, font.width(categoryLabel(category)))
        // label inset + widest label + gap + favourite heart + its inset
        val labelRoom = CATEGORY_LABEL_X + longestLabel + 5 + categoryFavoriteSize() + 4
        categoryWidthCachedFont = font
        categoryWidthCachedPanelWidth = panelWidth
        categoryWidthCachedPanelHeight = panelHeight
        categoryWidthCachedValue = minOf(maxOf(1, panelWidth - 1), maxOf(CATEGORY_MIN_WIDTH, labelRoom))
        return categoryWidthCachedValue
    }

    private fun moduleWidth(): Int = panelWidth() - categoryWidth()

    private fun settingsContentWidth(): Int = maxOf(1, moduleWidth() - 2 * (gridInset() + PADDING))

    private fun panelX(): Int = (width - panelWidth()) / 2

    private fun panelY(): Int = (height - panelHeight()) / 2

    private fun contentTop(outerPanelY: Int): Int = outerPanelY + headerHeight()

    /** The header band grows by one row only while there is an update to talk about. */
    private fun headerHeight(): Int = baseHeaderHeight() +
        if (UpdateChecker.newerVersion() == null) 0 else maxOf(12, Math.round(panelHeight() * 0.023f))

    private fun baseHeaderHeight(): Int = ClickGuiHeaderGeometry.baseHeaderHeightForPanelHeight(panelHeight())

    private fun headerInsetX(): Int = ClickGuiHeaderGeometry.headerInsetXForPanelWidth(panelWidth())


    private fun updateDownloadRect(panelX: Int, panelY: Int): Rect {
        val scale = maxOf(1, Math.round(panelWidth() / 1357.0f * 100))
        val buttonWidth = maxOf(44, 68 * scale / 100)
        val infoWidth = maxOf(30, 38 * scale / 100)
        val x = panelX + panelWidth() - headerInsetX() - buttonWidth - 4 - infoWidth
        return Rect(
            maxOf(panelX + headerInsetX(), x), panelY + baseHeaderHeight() + 1,
            buttonWidth, maxOf(12, Math.round(panelHeight() * 0.019f)),
        )
    }

    private fun updateInfoRect(panelX: Int, panelY: Int): Rect {
        val download = updateDownloadRect(panelX, panelY)
        return Rect(
            download.x + download.w + 3, download.y,
            maxOf(30, Math.round(panelWidth() * 0.028f)), download.h,
        )
    }

    private fun searchRect(panelX: Int, panelY: Int): Rect =
        ClickGuiHeaderGeometry.searchRectForLayout(panelX, panelY, panelWidth(), panelHeight(), categoryWidth())

    private fun workspaceRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.WORKSPACE)
    private fun githubRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.GITHUB)
    private fun settingsGearRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.SETTINGS)
    private fun themeRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.THEME)
    private fun cosmeticsRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.COSMETICS)
    private fun moveElementsRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.MOVE_ELEMENTS)
    private fun settingsBackRect(x: Int, panelY: Int): Rect = settingsHeaderSurfaceRect(x, panelY)

    private fun settingsHeaderSurfaceRect(x: Int, panelY: Int): Rect {
        val viewport = gridViewport(x, panelY)
        val headerHeight = settingsHeaderHeight()
        return Rect(
            viewport.x + PADDING, viewport.y + 1,
            maxOf(1, viewport.w - PADDING * 2), maxOf(18, headerHeight - 5),
        )
    }

    private fun settingsHeaderHeight(): Int = maxOf(22, Math.round(baseHeaderHeight() * 0.24f))

    private fun topBarRect(panelX: Int, panelY: Int, icon: TopBarIcon): Rect =
        ClickGuiHeaderGeometry.topBarRectForLayout(panelX, panelY, panelWidth(), panelHeight(), icon)

    private fun visibleModules(category: Category): List<Module> =
        moduleSearch.visibleModules(category, searchQuery, favoriteRevision, ClickGuiState.moduleFilter(category))

    private fun orderedCategories(): List<Category> {
        val snapshot = orderedCategorySnapshot
        if (snapshot != null && orderedCategoryFavoritesRevision == favoriteRevision) return snapshot
        val ordered = ClickGuiScreenCategoryBehavior.orderFavoriteCategories(
            Category.of(ClickGuiState.workspace()).toTypedArray(),
        )
        orderedCategorySnapshot = ordered
        orderedCategoryFavoritesRevision = favoriteRevision
        return ordered
    }

    private fun categoryEntries(): List<CategoryEntry> = categoryEntries(orderedCategories(), emptySet())

    private fun favoriteStateChanged() {
        favoriteRevision++
    }

    private fun setSearchQuery(value: String?) {
        var normalized = value ?: ""
        if (normalized.length > SEARCH_MAX_LENGTH) normalized = normalized.substring(0, SEARCH_MAX_LENGTH)
        if (normalized == searchQuery) return
        val leavingSearch = searchQuery.isNotBlank() && normalized.isBlank()
        searchQuery = normalized
        searchCursor = clampCursor(searchQuery, searchCursor)
        gridScroll = if (leavingSearch) ClickGuiState.gridScroll(selectedCategory) else 0
        gridScrollVisual = 0.0
        if (openSettingsModule != null) {
            settingsScroll = 0
            settingsScrollVisual = 0.0
            invalidateSettingsLayout()
        }
    }

    /** Toolbar shortcut: opens one module's settings page (General, Theme). */
    private fun openModulePage(module: Module, interaction: String) {
        if (!ModuleManager.contains(module.category(), module)) return
        setSearchQuery("")
        val target = ViewState(module.category(), module, ClickGuiState.gridScroll(module.category()), 0, null)
        if (currentView() == target) return
        markInteraction(interaction)
        playClick()
        requestNavigation(target, Integer.compare(indexOf(module.category()), indexOf(selectedCategory)))
    }

    private fun repositoryUrl(): String = FabricLoader.getInstance().getModContainer("geileraddons")
        .flatMap { it.metadata.contact.get("sources") }
        .orElse(REPOSITORY_URL)

    private fun renderHeader(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        panelX: Int,
        panelY: Int,
        rightX: Int,
        now: Long,
        motion: ClickGuiMotion,
    ) {
        val search = searchRect(panelX, panelY)
        var version = UpdateChecker.currentVersion()
        if (version.isBlank()) version = "unknown"
        val versionX = ClickGuiHeaderGeometry.versionTextX(panelX, categoryWidth())
        graphics.text(
            font, textFit(font, "v$version", maxOf(1, search.x - versionX - 8)), versionX,
            panelY + (baseHeaderHeight() - TEXT_HEIGHT) / 2, TEXT_MUTED, false,
        )

        if (search.w > 0) {
            roundedRect(graphics, search.x, search.y, search.w, search.h, componentRadius(),
                if (searchFocused) CARD_BG_HOVER else GROUP_HEADER)
        }

        renderTopBarButton(graphics, workspaceRect(panelX, panelY), TopBarIcon.WORKSPACE,
            "Switch to " + ClickGuiState.workspace().switchLabel(), mouseX, mouseY)
        renderTopBarButton(graphics, githubRect(panelX, panelY), TopBarIcon.GITHUB, "Open the GitHub page", mouseX, mouseY)
        renderTopBarButton(graphics, settingsGearRect(panelX, panelY), TopBarIcon.SETTINGS, "General settings", mouseX, mouseY)
        renderTopBarButton(graphics, themeRect(panelX, panelY), TopBarIcon.THEME, "Theme and colors", mouseX, mouseY)
        renderTopBarButton(graphics, cosmeticsRect(panelX, panelY), TopBarIcon.COSMETICS, "Cosmetics", mouseX, mouseY)
        renderTopBarButton(graphics, moveElementsRect(panelX, panelY), TopBarIcon.MOVE_ELEMENTS, "Move HUD elements", mouseX, mouseY)
        val newer = UpdateChecker.newerVersion()
        if (newer != null) {
            renderUpdateButton(graphics, font, updateDownloadRect(panelX, panelY), "Download", mouseX, mouseY)
            renderUpdateButton(graphics, font, updateInfoRect(panelX, panelY), "Info", mouseX, mouseY)
        }

        if (search.w > 0 && (searchFocused || searchQuery.isNotEmpty())) {
            val searchTextX = search.x + searchTextInsetForHeight(search.h)
            val searchTextWidth = searchTextFontWidth(search.w, search.h)
            val searchTextPixels = searchTextViewportWidth(search.w, search.h)
            val querySlice = visibleSlice(font, searchQuery, searchCursor, searchTextWidth)
            val searchTextY = search.y + (search.h - TEXT_HEIGHT) / 2
            graphics.enableScissor(searchTextX, search.y + 2, searchTextX + searchTextPixels, search.y + search.h - 2)
            val searchSelection = selection(searchQuery, searchCursor, searchAnchor)
            if (searchFocused && !searchSelection.isEmpty() && querySlice.text.isNotEmpty()) {
                val from = clampCursor(querySlice.text, searchSelection.start - querySlice.start)
                val to = clampCursor(querySlice.text, searchSelection.end - querySlice.start)
                val fromX = searchTextX + font.width(querySlice.text.substring(0, from))
                val toX = minOf(searchTextX + searchTextPixels, searchTextX + font.width(querySlice.text.substring(0, to)))
                graphics.fill(fromX, searchTextY - 1, toX, searchTextY + TEXT_HEIGHT + 1, CATEGORY_SELECTED)
            }
            graphics.text(font, querySlice.text, searchTextX, searchTextY, TEXT_PRIMARY, false)
            if (searchFocused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0L) {
                val cursorInSlice = clampCursor(querySlice.text, searchCursor - querySlice.start)
                val caretX = searchTextX + font.width(querySlice.text.substring(0, cursorInSlice))
                graphics.fill(caretX, search.y + maxOf(3, search.h / 5), caretX + 1,
                    search.y + search.h - maxOf(3, search.h / 5), TEXT_PRIMARY)
            }
            graphics.disableScissor()
        }
    }

    /** A flat toolbar button: no outline, the accent fill on hover, and a short tooltip. */
    private fun renderTopBarButton(
        graphics: GuiGraphicsExtractor,
        bounds: Rect,
        icon: TopBarIcon,
        tooltip: String,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = bounds.contains(mouseX, mouseY)
        roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h, componentRadius(), if (hovered) BUTTON_HOVER else GROUP_HEADER)
        val iconColor = if (hovered) TEXT_ON_ACCENT else TEXT_PRIMARY
        val texture = when (icon) {
            TopBarIcon.WORKSPACE -> ICON_CHEATS
            TopBarIcon.GITHUB -> ICON_GITHUB
            TopBarIcon.SETTINGS -> ICON_SETTINGS
            TopBarIcon.THEME -> ICON_THEME
            TopBarIcon.COSMETICS -> ICON_COSMETICS
            TopBarIcon.MOVE_ELEMENTS -> ICON_MOVE
        }
        val iconSize = minOf(32, maxOf(16, bounds.w - 16))
        val iconX = bounds.x + (bounds.w - iconSize) / 2
        val iconY = bounds.y + (bounds.h - iconSize) / 2
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(iconX.toFloat(), iconY.toFloat())
        pose.scale(iconSize / TOOLBAR_ICON_TEXTURE_SIZE.toFloat(), iconSize / TOOLBAR_ICON_TEXTURE_SIZE.toFloat())
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0f, 0.0f,
            TOOLBAR_ICON_TEXTURE_SIZE, TOOLBAR_ICON_TEXTURE_SIZE, TOOLBAR_ICON_TEXTURE_SIZE,
            TOOLBAR_ICON_TEXTURE_SIZE, iconColor,
        )
        pose.popMatrix()
        if (hovered) tooltip(graphics, tooltip, mouseX, mouseY)
    }

    /** Vanilla tooltips draw after the frame at the real GUI scale, so design-px coordinates are converted back. */
    private fun tooltip(graphics: GuiGraphicsExtractor, text: String, mouseX: Int, mouseY: Int) {
        val scale = designPose()
        graphics.setTooltipForNextFrame(Component.literal(text), Math.round(mouseX * scale), Math.round(mouseY * scale))
    }

    // ---- rendering ----------------------------------------------------------------------

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        rawMouseX: Int,
        rawMouseY: Int,
        partialTick: Float,
    ) {
        // Refresh before drawing: a theme colour drag updates the menu on the same frame.
        VisualModule.INSTANCE.refreshTheme()
        val design = designPose()
        val mouseX = Math.floor(rawMouseX / design.toDouble()).toInt()
        val mouseY = Math.floor(rawMouseY / design.toDouble()).toInt()
        val now = System.nanoTime()
        val motion = motion()
        updateAnimationState(now, motion)
        pollSliderDrag(mouseX, mouseY)
        val settingsModule = openSettingsModule
        if (settingsModule != null && !ModuleManager.contains(selectedCategory, settingsModule)) {
            openSettingsModule = null
            expandedColorSetting = null
        }
        clampViewScroll()
        if (closing && elapsedMillis(lifecycleStartedNanos, now) >= motion.closeMillis()) return

        val lifecycle = lifecycleProgress(now, motion)
        val visible = if (closing) 1.0f - lifecycle else lifecycle

        val panelX = panelX()
        val panelY = panelY()
        val panelWidth = panelWidth()
        val categoryWidth = categoryWidth()
        val panelHeight = panelHeight()
        val rightX = panelX + categoryWidth
        val font = this.font
        val centerX = vw() / 2.0f
        val centerY = vh() / 2.0f

        val pose = graphics.pose()
        pose.pushMatrix()
        pose.scale(design, design)
        pose.pushMatrix()
        val scale = when {
            closing -> motion.closeScale(lifecycle)
            motion == ClickGuiMotion.NONE -> 1.0f
            motion == ClickGuiMotion.REDUCED -> 0.90f + 0.10f * lifecycle
            else -> 0.72f + 0.28f * motion.spring(lifecycle)
        }
        pose.translate(centerX, centerY)
        pose.scale(scale, scale)
        pose.translate(-centerX, -centerY)

        // Flat panel: no dim, shadow or outline; one line under the top bar (module side) and one beside the categories.
        roundedRect(graphics, panelX, panelY, panelWidth, panelHeight, PANEL_RADIUS, PANEL_TOP, PANEL_BOTTOM)
        val line = withOpacity(BORDER, 0.9f)
        graphics.fill(rightX - 1, panelY + headerHeight(), rightX, panelY + panelHeight, line)
        graphics.fill(rightX, panelY + headerHeight() - 1, panelX + panelWidth, panelY + headerHeight(), line)

        graphics.enableScissor(panelX, panelY, panelX + panelWidth, panelY + panelHeight)
        renderHeader(graphics, font, mouseX, mouseY, panelX, panelY, rightX, now, motion)
        renderCategories(graphics, font, mouseX, mouseY, panelX, panelY, now, motion)
        renderView(graphics, font, mouseX, mouseY, rightX, panelY, now, motion, lifecycle)
        graphics.disableScissor()
        if (visible < 1.0f && motion != ClickGuiMotion.NONE) {
            val fade = (1.0f - visible) * if (motion == ClickGuiMotion.EXPRESSIVE) 0.42f else 0.20f
            roundedRect(graphics, panelX, panelY, panelWidth, panelHeight, PANEL_RADIUS, withOpacity(0xFF000000.toInt(), fade))
        }
        pose.popMatrix()
        renderBindingNotice(graphics, font)
        renderChangelogOverlay(graphics, font, mouseX, mouseY)
        pose.popMatrix()
        renderHoverTooltip(graphics, font, mouseX, mouseY)
    }

    private fun componentRadius(): Int = COMPONENT_RADIUS

    private fun renderUpdateButton(
        graphics: GuiGraphicsExtractor,
        font: Font,
        bounds: Rect,
        label: String,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = bounds.contains(mouseX, mouseY)
        roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL, if (hovered) BUTTON_HOVER else BUTTON_BG)
        graphics.centeredText(
            font, textFit(font, label, maxOf(1, bounds.w - 8)), bounds.x + bounds.w / 2,
            bounds.y + (bounds.h - TEXT_HEIGHT) / 2, if (hovered) TEXT_ON_ACCENT else TEXT_PRIMARY,
        )
    }

    private fun renderChangelogOverlay(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int) {
        if (!changelogOpen) return
        val panel = changelogPanelRect()
        graphics.fill(0, 0, vw(), vh(), withOpacity(0xFF000000.toInt(), 0.45f))
        roundedRect(graphics, panel.x, panel.y, panel.w, panel.h, PANEL_RADIUS, PANEL_TOP, PANEL_BOTTOM)
        val newer = UpdateChecker.newerVersion()
        val title = "GeilerAddons v${newer ?: "?"} is available"
        val currentVersion = UpdateChecker.currentVersion()
        val current = "You have v${if (currentVersion.isBlank()) "?" else currentVersion}"
        graphics.text(font, textFit(font, title, maxOf(1, panel.w - 24)), panel.x + 10, panel.y + 8, TEXT_WARN)
        graphics.text(font, textFit(font, current, maxOf(1, panel.w - 24)), panel.x + 10, panel.y + 19, TEXT_MUTED)

        val viewport = changelogViewport(panel)
        val lines = changelogLines()
        val contentHeight = lines.size * LINE_HEIGHT
        val maxScroll = maxOf(0, contentHeight - viewport.h)
        changelogScroll = changelogScroll.coerceIn(0.0, maxScroll.toDouble())
        graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h)
        for (index in lines.indices) {
            val lineY = viewport.y + index * LINE_HEIGHT - changelogScroll.toInt()
            if (lineY + LINE_HEIGHT < viewport.y || lineY > viewport.y + viewport.h) continue
            graphics.text(font, lines[index], viewport.x, lineY, TEXT_SECONDARY)
        }
        graphics.disableScissor()
        renderScrollbar(graphics, viewport, contentHeight, changelogScroll.toInt())
        graphics.text(font, "Escape or click outside to close", panel.x + 10, panel.y + panel.h - 13, TEXT_MUTED)
    }

    private fun changelogPanelRect(): Rect {
        val panelW = minOf(maxOf(240, panelWidth() - 40), maxOf(200, vw() - 24))
        val panelH = minOf(maxOf(120, panelHeight() + 40), maxOf(100, vh() - 24))
        return Rect((vw() - panelW) / 2, (vh() - panelH) / 2, panelW, panelH)
    }

    private fun changelogViewport(panel: Rect): Rect =
        Rect(panel.x + 10, panel.y + 32, maxOf(1, panel.w - 20), maxOf(1, panel.h - 50))

    private fun changelogLines(): List<String> {
        val notes = UpdateChecker.releaseNotes()
        val viewport = changelogViewport(changelogPanelRect())
        val key = "${vw()}\u0078${vh()}|${UpdateChecker.newerVersion()}|${notes.length}"
        if (key == changelogCacheKey) return changelogLines
        val wrapped = ArrayList<String>()
        for (line in java.util.regex.Pattern.compile("\n").split(notes, -1).asList()) {
            if (line.isEmpty()) {
                wrapped.add("")
                continue
            }
            var remaining = line
            while (font.width(remaining) > viewport.w) {
                var split = remaining.length
                while (split > 1 && font.width(remaining.substring(0, split)) > viewport.w) split--
                wrapped.add(remaining.substring(0, split))
                remaining = remaining.substring(split)
            }
            wrapped.add(remaining)
        }
        changelogCacheKey = key
        changelogLines = java.util.List.copyOf(wrapped)
        return changelogLines
    }

    private fun openChangelog() {
        changelogOpen = true
        changelogScroll = 0.0
        changelogCacheKey = ""
    }

    private fun closeChangelog() {
        changelogOpen = false
        changelogScroll = 0.0
    }

    private fun renderBindingNotice(graphics: GuiGraphicsExtractor, font: Font) {
        val target = ModuleKeybindManager.bindingModule()?.name()
            ?: ModuleKeybindManager.bindingMacro()?.name() ?: return
        val boxW = minOf(420, maxOf(220, vw() - 24))
        val boxH = 30
        val boxX = (vw() - boxW) / 2
        val boxY = vh() - boxH - 12
        roundedRect(graphics, boxX, boxY, boxW, boxH, COMPONENT_RADIUS, BUTTON_HOVER)
        graphics.text(font, "Listening for a hotkey: ${textFit(font, target, boxW - 170)}", boxX + 8, boxY + 5, TEXT_ON_ACCENT)
        graphics.text(
            font, "Press and release a key; Escape clears; click the key field to cancel.",
            boxX + 8, boxY + 16, TEXT_ON_ACCENT,
        )
    }

    private fun renderHoverTooltip(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int) {
        val module = openSettingsModule ?: return
        if (closing || transitionFrom != null || !inputSettled()) return
        val viewport = settingsViewport(panelX() + categoryWidth(), panelY())
        if (!viewport.contains(mouseX, mouseY)) return
        for (row in settingsRows(module, panelX() + categoryWidth(), panelY())) {
            if (!row.getBounds().contains(mouseX, mouseY)) continue
            val description = settingTooltip(module, row)
            if (!description.isNullOrBlank()) tooltip(graphics, description, mouseX, mouseY)
            return
        }
    }

    private fun settingTooltip(module: Module, row: Row): String? = when (row) {
        is GroupRow -> "Click to expand or collapse ${row.group.name}."
        is PreviewRow -> null
        is ColorRow -> "Click a colour to open its picker; drag the controls or enter a hex value."
        is NumberRow -> "Drag the slider or click the value field to enter a number between " +
            "${numberText(row.setting.min())} and ${numberText(row.setting.max())}. Press Enter to apply a typed value."
        is RangeRow -> module.settingTooltip(row.setting) ?: "Drag either end of the slider: each time the value is needed, one is drawn between the two ends."
        is ToggleRow -> module.settingTooltip(row.setting) ?: "Toggle ${row.setting.displayName()}."
        is ChoiceRow -> module.settingTooltip(row.setting)
            ?: "Click to choose the next value; right-click chooses the previous value."
        is TextRow -> if (row.setting.keybindCapture()) {
            "Click the field, then press the key or a middle or side mouse button to bind. Press Backspace or Delete to clear it."
        } else module.settingTooltip(row.setting)
            ?: "Click the field and type ${row.setting.displayName()}. Press Enter to finish."
        is ActionRow -> row.action.description ?: "Click to run ${row.action.label}."
    }

    private fun renderCategories(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        panelX: Int,
        panelY: Int,
        now: Long,
        motion: ClickGuiMotion,
    ) {
        val contentTop = contentTop(panelY)
        graphics.enableScissor(panelX, contentTop, panelX + categoryWidth(), panelY + panelHeight())
        val allRows = categoryRowBounds(panelX, panelY)
        val entries = categoryEntries()
        val fromView = transitionFrom
        val moving = fromView != null && fromView.category != selectedCategory
        if (moving) {
            val from = allRows[indexOf(fromView!!.category)]
            val to = allRows[indexOf(selectedCategory)]
            val progress = transitionProgress(now, motion)
            val y = Math.round(lerp(from.y, to.y, progress)) - categoryScroll
            roundedRect(graphics, to.x + 7, y, to.w - 7, to.h, componentRadius(), withOpacity(CATEGORY_SELECTED, 0.19f))
        }
        val viewportBottom = contentTop + categoryViewportHeight(panelY)
        // The rows scroll inside their own viewport so none can run under the workspace button.
        graphics.enableScissor(panelX, contentTop, panelX + categoryWidth(), viewportBottom)
        for (i in entries.indices) {
            val full = allRows[i]
            val y = full.y - categoryScroll
            if (y + full.h <= contentTop || y >= viewportBottom) continue
            val row = Rect(full.x, y, full.w, full.h)
            val entry = entries[i]
            val selected = entry.category === selectedCategory
            val hovered = row.contains(mouseX, mouseY)
            val favorite = categoryFavoriteRect(row)
            ClickGuiScreenRenderBehavior.renderCategoryRow(
                graphics, font, row.x, row.y, row.w, row.h, categoryLabel(entry.category),
                selected, !moving && selected, hovered, favorite.x, favorite.y, favorite.w, favorite.h,
                ClickGuiState.isFavorite(entry.category), categoryHeartHitRect(row, favorite).contains(mouseX, mouseY),
                componentRadius(), TEXT_HEIGHT, CATEGORY_TEXT_SCALE, FAVORITE_COLOR,
                ICON_FAVORITE_FILLED, ICON_FAVORITE_OUTLINE,
            )
        }
        graphics.disableScissor()
        val notice = UpdateChecker.bannerText()
        if (notice != null) {
            val text = textFit(font, notice, maxOf(1, categoryWidth() - 28))
            val y = panelY + panelHeight() - PADDING - TEXT_HEIGHT
            graphics.text(font, text, panelX + 14, y, TEXT_WARN)
        }
        graphics.disableScissor()
    }

    private fun renderView(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        rightX: Int,
        panelY: Int,
        now: Long,
        motion: ClickGuiMotion,
        lifecycle: Float,
    ) {
        val contentTop = contentTop(panelY)
        graphics.enableScissor(rightX, contentTop, rightX + moduleWidth(), panelY + panelHeight())
        val from = transitionFrom
        if (from != null) {
            val progress = transitionProgress(now, motion)
            val travel = moduleWidth() + 18
            val outgoingOffset = Math.round(-transitionDirection * travel * progress)
            val incomingOffset = Math.round(transitionDirection * travel * (1.0f - progress))
            renderViewAt(graphics, font, mouseX, mouseY, from, rightX, panelY, outgoingOffset,
                false, false, transitionStartedNanos, now, motion)
            renderViewAt(graphics, font, mouseX, mouseY, transitionTo!!, rightX, panelY, incomingOffset,
                false, true, transitionStartedNanos, now, motion)
            graphics.disableScissor()
            return
        }
        renderViewAt(graphics, font, mouseX, mouseY, currentView(), rightX, panelY, 0,
            true, true, viewEnteredNanos, now, motion)
        graphics.disableScissor()
    }

    private fun renderViewAt(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        view: ViewState,
        rightX: Int,
        panelY: Int,
        offset: Int,
        hoverable: Boolean,
        entering: Boolean,
        entranceStart: Long,
        now: Long,
        motion: ClickGuiMotion,
    ) {
        val pose = graphics.pose()
        pose.pushMatrix()
        if (offset != 0) pose.translate(offset.toFloat(), 0.0f)
        val modules = visibleModules(view.category)
        val module = view.module
        if (module != null && ModuleManager.contains(view.category, module)) {
            val scroll = if (transitionFrom == null) settingsScrollVisual else view.settingsScroll.toDouble()
            val expanded = if (transitionFrom == null) renderedExpandedColor(now, motion) else view.expandedColor
            renderSettingsView(graphics, font, mouseX, mouseY, rightX, panelY, module, scroll,
                expanded, hoverable, now, motion)
        } else {
            val scroll = if (transitionFrom == null) gridScrollVisual else view.gridScroll.toDouble()
            renderModuleGrid(graphics, font, mouseX, mouseY, view.category, modules, rightX, panelY,
                scroll, hoverable, entering, entranceStart, now, motion)
        }
        pose.popMatrix()
    }

    private fun renderModuleGrid(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        category: Category,
        modules: List<Module>,
        x: Int,
        panelY: Int,
        scroll: Double,
        hoverable: Boolean,
        entering: Boolean,
        entranceStart: Long,
        now: Long,
        motion: ClickGuiMotion,
    ) {
        val viewport = gridCardViewport(x, panelY, category)
        val layout = moduleGridLayout(category, modules)
        val maxScroll = maxOf(0, layout.contentHeight() - viewport.h)
        val appliedScroll = maxOf(0, minOf(maxScroll, Math.round(scroll).toInt()))
        val cards = visibleCardRects(layout, viewport, appliedScroll)
        val insideViewport = viewport.contains(mouseX, mouseY)
        renderFilterChips(graphics, font, mouseX, mouseY, category, x, panelY, hoverable)
        graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h)
        for (section in layout.sections()) {
            if (section.headingY() < 0) continue
            val headingY = viewport.y + section.headingY() - appliedScroll
            if (headingY + moduleSectionHeaderHeight() <= viewport.y || headingY >= viewport.y + viewport.h) continue
            renderModuleSectionHeading(graphics, font, section.section().label(), viewport, headingY)
        }
        for (card in cards) {
            val bounds = Rect(card.x(), card.y(), card.w(), card.h())
            if (bounds.y + bounds.h <= viewport.y || bounds.y >= viewport.y + viewport.h) continue
            val cardHoverable = hoverable && insideViewport
            val entrance = if (entering) cardEntrance(card.index(), entranceStart, now, motion) else 1.0f
            val lift = (1.0f - entrance) * if (motion == ClickGuiMotion.EXPRESSIVE) 10.0f else 5.0f
            val cardScale = 0.97f + 0.03f * if (motion == ClickGuiMotion.EXPRESSIVE) motion.spring(entrance) else entrance
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(bounds.x + bounds.w / 2.0f, bounds.y + bounds.h / 2.0f + lift)
            pose.scale(cardScale, cardScale)
            pose.translate(-(bounds.x + bounds.w / 2.0f), -(bounds.y + bounds.h / 2.0f))
            renderCard(graphics, font, mouseX, mouseY, card, cardHoverable, now, motion)
            pose.popMatrix()
        }
        graphics.disableScissor()
        renderScrollbar(graphics, viewport, layout.contentHeight(), appliedScroll)
    }

    private fun renderModuleSectionHeading(graphics: GuiGraphicsExtractor, font: Font, label: String?, viewport: Rect, y: Int) {
        val heading = label ?: ""
        val titleY = y + (moduleSectionHeaderHeight() - TEXT_HEIGHT) / 2
        graphics.text(font, heading, viewport.x, titleY, TEXT_MUTED, false)
        val lineLeft = viewport.x + font.width(heading) + PADDING
        val lineRight = viewport.x + viewport.w - SCROLLBAR_GUTTER
        if (lineLeft < lineRight) {
            val lineY = y + moduleSectionHeaderHeight() / 2
            graphics.fill(lineLeft, lineY, lineRight, lineY + 1, withOpacity(BORDER, 0.9f))
        }
    }

    private fun cardEntrance(index: Int, entranceStart: Long, now: Long, motion: ClickGuiMotion): Float {
        return motion.cardEntrance(index, elapsedMillis(entranceStart, now))
    }

    private fun renderCard(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        card: ClickGuiModuleGrid.CardRect,
        hoverable: Boolean,
        now: Long,
        motion: ClickGuiMotion,
    ) {
        val module = card.module()
        val bounds = Rect(card.x(), card.y(), card.w(), card.h())
        val enabled = module.showsToggleControl() && module.isEnabled()
        val hovered = hoverable && bounds.contains(mouseX, mouseY)
        val pressed = hovered && motion != ClickGuiMotion.NONE && pressedCard === module
        val toggle = cardSwitchRect(bounds)
        val keybind = if (module.showsKeybindControl()) keybindRect(module, bounds) else null
        val hits = cardHitRects(module, bounds)
        val keybindHovered = hoverable && hits[2]?.contains(mouseX, mouseY) == true
        val pose = graphics.pose()
        pose.pushMatrix()
        if (pressed) {
            pose.translate(bounds.x + bounds.w / 2.0f, bounds.y + bounds.h / 2.0f)
            pose.scale(0.98f, 0.98f)
            pose.translate(-(bounds.x + bounds.w / 2.0f), -(bounds.y + bounds.h / 2.0f))
        }

        val edges = toggleEdges(module, enabled, now, motion)
        val position = edges[1].coerceIn(0.0f, 1.0f)
        var background = lerpColor(CARD_BG, CARD_BG_ENABLED, position)
        if (hovered) background = lerpColor(background, lerpColor(CARD_BG_HOVER, CARD_BG_ENABLED, position), 0.85f)
        val pulse = interactionPulse(module, now, motion)
        if (pulse > 0.0f) background = lerpColor(background, CARD_BG_HOVER, pulse * 0.65f)
        roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h, componentRadius(), background)

        val favorite = moduleFavoriteRect(module, bounds)
        val textX = bounds.x + CARD_PADDING
        val textWidth = maxOf(1, cardTextRight(bounds, if (module.showsToggleControl()) toggle else null, favorite) - textX)
        val titleY = bounds.y + CARD_PADDING
        graphics.text(font, textFit(font, module.name(), textWidth), textX, titleY, TEXT_PRIMARY, false)
        val summary = if (searchQuery.isBlank()) module.description()
            else module.category().displayName() + " · " + module.description()
        val summaryLines = cardSummaryLines(font, module, summary, textWidth)
        val summaryY = titleY + LINE_HEIGHT + 4
        val lineLimit = maxOf(1, minOf(2, (bounds.y + bounds.h - 4 - summaryY) / CARD_LINE_HEIGHT))
        for (line in 0 until minOf(lineLimit, summaryLines.size)) {
            graphics.text(font, summaryLines[line], textX, summaryY + line * CARD_LINE_HEIGHT, TEXT_MUTED, false)
        }

        val status = if (GeneralModule.INSTANCE.availabilityNotices().value()) module.inactiveReason() else null
        if (status != null && bounds.h >= 90) {
            val statusY = summaryY + minOf(lineLimit, summaryLines.size) * CARD_LINE_HEIGHT + 3
            if (keybind == null || statusY + TEXT_HEIGHT < keybind.y) {
                graphics.text(font, textFit(font, status, textWidth), textX, statusY, TEXT_WARN, false)
            }
        }

        if (keybind != null) {
            val binding = ModuleKeybindManager.isBinding(module)
            val unbound = !module.keybind().isBound()
            roundedRect(graphics, keybind.x, keybind.y, keybind.w, keybind.h, KEY_SQUARE_RADIUS,
                if (binding || keybindHovered) BUTTON_HOVER else GROUP_HEADER)
            val color = when {
                binding || keybindHovered -> TEXT_ON_ACCENT
                unbound -> TEXT_MUTED
                else -> TEXT_PRIMARY
            }
            ClickGuiScreenRenderBehavior.drawCenteredScaledText(
                graphics, font, textFit(font, keyLabel(module), KEY_LABEL_MAX_WIDTH), keybind.x + keybind.w / 2,
                keybind.y + (keybind.h - Math.round(TEXT_HEIGHT * KEY_TEXT_SCALE)) / 2, color, KEY_TEXT_SCALE,
            )
            if (keybindHovered && module === geiler.addons.client.module.impl.DungeonHelperModule.INSTANCE) {
                tooltip(graphics, "Press this key to open the Dungeon Guide editor.", mouseX, mouseY)
            }
        }
        if (module.showsToggleControl()) {
            GuiTheme.liquidSwitch(graphics, toggle.x, toggle.y, toggle.w, toggle.h, enabled, edges[0], edges[1], pressed)
        }
        renderFavoriteHeart(graphics, favorite, ClickGuiState.isFavorite(module), hoverable && hits[0]?.contains(mouseX, mouseY) == true)
        pose.popMatrix()
    }

    /** What a card's key square shows: the bound key, a dash when unbound, an ellipsis while listening. */
    private fun keyLabel(module: Module): String = when {
        ModuleKeybindManager.isBinding(module) -> "…"
        else -> keyBadgeLabel(module.keybind())
    }

    private fun categoryFavoriteSize(): Int = maxOf(20, minOf(26, categoryRowHeight() - 4))

    private fun categoryFavoriteRect(row: Rect): Rect {
        val hitSize = maxOf(20, minOf(26, row.h - 4))
        return Rect(row.x + row.w - hitSize - 4, row.y + (row.h - hitSize) / 2, hitSize, hitSize)
    }

    private fun moduleFavoriteRect(module: Module, card: Rect): Rect {
        val toggle = if (module.showsToggleControl()) cardSwitchRect(card) else null
        return moduleFavoriteHitRect(card, module.showsToggleControl(), toggle?.y ?: -1)
    }

    private fun renderFavoriteHeart(graphics: GuiGraphicsExtractor, bounds: Rect, favorite: Boolean, hovered: Boolean) {
        ClickGuiScreenRenderBehavior.renderFavoriteHeart(
            graphics, bounds.x, bounds.y, bounds.w, bounds.h, favorite, hovered,
            FAVORITE_COLOR, TEXT_PRIMARY, TEXT_MUTED, ICON_FAVORITE_FILLED, ICON_FAVORITE_OUTLINE,
        )
    }

    private fun cardGap(): Int = ClickGuiModuleGrid.CARD_GAP

    private fun keybindRect(module: Module, card: Rect): Rect = keySquareRect(
        card, module.showsToggleControl(), Math.round(minOf(font.width(keyLabel(module)), KEY_LABEL_MAX_WIDTH) * KEY_TEXT_SCALE),
    )

    /** Heart, switch and key click areas of one card (see [cardControlHitRects]). */
    private fun cardHitRects(module: Module, card: Rect): Array<Rect?> = cardControlHitRects(
        card, moduleFavoriteRect(module, card),
        if (module.showsToggleControl()) cardSwitchRect(card) else null,
        if (module.showsKeybindControl()) keybindRect(module, card) else null,
    )

    private fun cardSummaryLines(font: Font, module: Module, summary: String, width: Int): List<FormattedCharSequence> {
        val cached = cardSummaryLayouts[module]
        if (cached != null && cached.font === font && cached.source == summary && cached.width == width) return cached.lines
        val lines = java.util.List.copyOf(font.split(Component.literal(conciseSummary(summary)), width))
        cardSummaryLayouts[module] = CardSummaryLayout(font, summary, width, lines)
        return lines
    }

    private fun moduleTextWidth(pixels: Int): Int = maxOf(1, ceil(pixels / MODULE_TEXT_SCALE).toInt())

    private fun moduleText(graphics: GuiGraphicsExtractor, font: Font, text: String, x: Int, y: Int, color: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(MODULE_TEXT_SCALE, MODULE_TEXT_SCALE)
        graphics.text(font, text, 0, 0, color)
        pose.popMatrix()
    }

    private fun moduleText(graphics: GuiGraphicsExtractor, font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(MODULE_TEXT_SCALE, MODULE_TEXT_SCALE)
        graphics.text(font, text, 0, 0, color)
        pose.popMatrix()
    }

    private fun moduleCenteredText(graphics: GuiGraphicsExtractor, font: Font, text: String, centerX: Int, y: Int, color: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(centerX.toFloat(), y.toFloat())
        pose.scale(MODULE_TEXT_SCALE, MODULE_TEXT_SCALE)
        graphics.centeredText(font, text, 0, 0, color)
        pose.popMatrix()
    }

    private fun visibleSlice(font: Font, text: String, cursor: Int, width: Int): TextSlice {
        val slice = ClickGuiTextEditing.visibleSlice(font, text, cursor, width)
        return TextSlice(slice.text, slice.start, slice.end)
    }

    private fun cursorAtMouse(font: Font, text: String, mouseX: Double, textX: Int, width: Int, currentCursor: Int): Int =
        ClickGuiTextEditing.cursorAtMouse(font, text, mouseX, textX, width, currentCursor)

    private fun cursorAtMouse(
        font: Font, text: String, mouseX: Double, textX: Int, width: Int, currentCursor: Int, textScale: Float,
    ): Int = ClickGuiTextEditing.cursorAtMouse(font, text, mouseX, textX, width, currentCursor, textScale)

    private fun cardHeight(modules: List<Module>): Int = ClickGuiModuleGrid.cardHeightForPanelHeight(panelHeight())
    private fun cardColumns(): Int = ClickGuiModuleGrid.cardColumnsForModuleWidth(moduleWidth())

    private fun cardWidth(): Int {
        val inset = gridInset()
        val viewportWidth = maxOf(1, moduleWidth() - inset * 2)
        return cardWidthForGridViewport(viewportWidth, cardColumns(), cardGap())
    }

    private fun gridInset(): Int = ClickGuiModuleGrid.GRID_INSET

    private fun gridViewport(x: Int, panelY: Int): Rect {
        val inset = gridInset()
        return Rect(
            x + inset, contentTop(panelY) + inset, maxOf(1, moduleWidth() - inset * 2),
            maxOf(1, panelHeight() - headerHeight() - inset * 2),
        )
    }

    /** Subcategory chips (All + the category's children); none while searching or when the category has no children. */
    private fun chipFilters(category: Category): List<ModuleCategoryFilter> {
        if (searchQuery.isNotBlank()) return emptyList()
        val options = ModuleCategoryFilter.options(category)
        return if (options.size > 1) options else emptyList()
    }

    private fun chipHeight(): Int = 16
    private fun chipGap(): Int = 8

    private fun chipLayout(category: Category, viewportWidth: Int): ClickGuiModuleGrid.ChipLayout {
        val filters = chipFilters(category)
        if (filters.isEmpty()) return ClickGuiModuleGrid.chipLayout(emptyList(), viewportWidth, chipHeight(), chipGap(), chipGap())
        val widths = filters.map { font.width(it.label() ?: "") + PADDING * 2 }
        return ClickGuiModuleGrid.chipLayout(widths, viewportWidth - SCROLLBAR_GUTTER, chipHeight(), chipGap(), chipGap())
    }

    /** The card grid sits below the chip bar; settings views keep using the full [gridViewport]. */
    private fun gridCardViewport(x: Int, panelY: Int, category: Category): Rect {
        val base = gridViewport(x, panelY)
        val bar = chipLayout(category, base.w).height
        if (bar == 0) return base
        val shift = bar + cardGap()
        return Rect(base.x, base.y + shift, base.w, maxOf(1, base.h - shift))
    }

    private fun chipRects(category: Category, x: Int, panelY: Int): List<Rect> {
        val base = gridViewport(x, panelY)
        return chipLayout(category, base.w).chips.map { Rect(base.x + it.x, base.y + it.y, it.w, it.h) }
    }

    private fun activeFilterId(category: Category): String? =
        ModuleCategoryFilter.resolve(category, ClickGuiState.moduleFilter(category)).id()

    private fun renderFilterChips(
        graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int,
        category: Category, x: Int, panelY: Int, hoverable: Boolean,
    ) {
        val filters = chipFilters(category)
        if (filters.isEmpty()) return
        val rects = chipRects(category, x, panelY)
        val active = activeFilterId(category)
        for (i in filters.indices) {
            val rect = rects[i]
            val selected = filters[i].id() == active
            val hovered = hoverable && rect.contains(mouseX, mouseY)
            val background = if (selected) CARD_BG_ENABLED else if (hovered) CARD_BG_HOVER else CARD_BG
            roundedRect(graphics, rect.x, rect.y, rect.w, rect.h, componentRadius(), background)
            val label = filters[i].label() ?: ""
            val color = if (selected) SLIDER_FILL else if (hovered) TEXT_PRIMARY else TEXT_SECONDARY
            graphics.text(font, label, rect.x + (rect.w - font.width(label)) / 2, rect.y + (rect.h - TEXT_HEIGHT) / 2, color, false)
        }
    }

    private fun selectFilterChip(category: Category, filter: ModuleCategoryFilter) {
        val id = filter.id() ?: return
        if (id == activeFilterId(category)) return
        ClickGuiState.setModuleFilter(category, id)
        ClickGuiState.setGridScroll(category, 0)
        moduleSearch.invalidateVisibleModules()
        gridScroll = 0
        gridScrollVisual = 0.0
        playClick()
        persistView()
        logGuiDiagnostic("EVENT action=filter,filter=$id,${gridDiagnosticState(category)}")
    }

    private fun moduleSectionHeaderHeight(): Int = 16
    private fun moduleSectionHeaderGap(): Int = 8
    private fun moduleSectionGap(): Int = 16

    private fun moduleSections(category: Category, modules: List<Module>): List<ClickGuiModuleGrid.Section> {
        if (moduleSectionCacheCategory === category && moduleSectionCacheModules === modules && moduleSectionCacheQuery == searchQuery) {
            return moduleSectionCache
        }
        val sections = ClickGuiModuleGrid.sections(category, modules, searchQuery)
        moduleSectionCacheCategory = category
        moduleSectionCacheModules = modules
        moduleSectionCacheQuery = searchQuery
        moduleSectionCache = java.util.List.copyOf(sections)
        return moduleSectionCache
    }

    private fun moduleGridLayout(category: Category, modules: List<Module>): ClickGuiModuleGrid.Layout =
        ClickGuiModuleGrid.layout(moduleSections(category, modules), cardColumns(), cardHeight(modules), cardGap(),
            moduleSectionHeaderHeight(), moduleSectionHeaderGap(), moduleSectionGap())

    private fun gridContentHeight(category: Category, modules: List<Module>): Int = moduleGridLayout(category, modules).contentHeight()

    private fun cardRects(category: Category, modules: List<Module>, x: Int, panelY: Int, scroll: Double): List<ClickGuiModuleGrid.CardRect> {
        val viewport = gridCardViewport(x, panelY, category)
        val layout = moduleGridLayout(category, modules)
        val maxScroll = maxOf(0, layout.contentHeight() - viewport.h)
        val appliedScroll = maxOf(0, minOf(maxScroll, Math.round(scroll).toInt()))
        return ClickGuiModuleGrid.cardRects(layout, viewport.x, viewport.y, appliedScroll,
            cardColumns(), cardWidth(), cardHeight(modules), cardGap())
    }

    private fun visibleCardRects(layout: ClickGuiModuleGrid.Layout, viewport: Rect, appliedScroll: Int): List<ClickGuiModuleGrid.CardRect> =
        ClickGuiModuleGrid.visibleCardRects(layout, viewport.x, viewport.y, viewport.h, appliedScroll,
            cardColumns(), cardWidth(), cardHeight(emptyList()), cardGap())

    // ---- settings view ------------------------------------------------------------------

    private fun renderSettingsView(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        x: Int,
        y: Int,
        module: Module,
        scroll: Double,
        expandedColor: ColorSetting?,
        hoverable: Boolean,
        now: Long,
        motion: ClickGuiMotion,
    ) {
        // Flat header: the whole row is the back button; it only gains a tint on hover.
        val headerSurface = settingsHeaderSurfaceRect(x, y)
        val backHovered = hoverable && settingsBackRect(x, y).contains(mouseX, mouseY)
        if (backHovered) {
            roundedRect(graphics, headerSurface.x, headerSurface.y, headerSurface.w, headerSurface.h, componentRadius(), CATEGORY_HOVER)
        }
        graphics.text(
            font, textFit(font, "‹  " + module.name(), maxOf(1, headerSurface.w - 16)), headerSurface.x + PADDING,
            headerSurface.y + (headerSurface.h - TEXT_HEIGHT) / 2, if (backHovered) SLIDER_FILL else TEXT_PRIMARY, false,
        )
        val viewport = settingsViewport(x, y)
        val insideViewport = hoverable && viewport.contains(mouseX, mouseY)
        val rows = settingsRows(module, x, y, scroll, expandedColor)
        val appliedScroll = settingsAppliedScroll(module, viewport, scroll, expandedColor, x)
        graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h)
        renderPreview(graphics, font, module, viewport, appliedScroll)
        val layoutTransition = settingsLayoutTransition
        if (layoutTransition != null && layoutTransition.module === module && transitionFrom == null) {
            renderSettingsLayoutTransition(graphics, font, mouseX, mouseY, layoutTransition, viewport,
                settingsLayoutProgress(now, motion), now, motion)
        } else {
            renderSettingRows(graphics, font, mouseX, mouseY, rows, viewport, module, insideViewport, now, motion)
        }
        graphics.disableScissor()
        renderScrollbar(graphics, viewport, settingsContentHeight(module, x, expandedColor), Math.round(scroll).toInt())
    }

    private fun renderSettingRows(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        rows: List<Row>,
        viewport: Rect,
        module: Module,
        hoverable: Boolean,
        now: Long,
        motion: ClickGuiMotion,
    ) {
        for (row in rows) {
            if (intersection(viewport, row.getBounds()) == null) continue
            renderSettingRow(graphics, font, mouseX, mouseY, row, module, hoverable, now, motion)
        }
    }

    private fun renderSettingsLayoutTransition(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        transition: SettingsLayoutTransition,
        viewport: Rect,
        progress: Float,
        now: Long,
        motion: ClickGuiMotion,
    ) {
        val rows = ArrayList(transition.rows)
        rows.sortWith(compareBy<RowTransition> { transitionBounds(it, progress).y }.thenBy { it.order })
        for (row in rows) {
            val bounds = transitionBounds(row, progress)
            if (intersection(viewport, bounds) == null) continue
            val rendered = withBounds(transitionSource(row, progress), bounds)
            val reveal = transitionReveal(row, transition, progress)
            if (reveal == null) {
                renderSettingRow(graphics, font, mouseX, mouseY, rendered, transition.module, false, now, motion)
                continue
            }
            val clip = intersection(viewport, reveal)
            if (clip == null || clip.w <= 0 || clip.h <= 0) continue
            graphics.enableScissor(clip.x, clip.y, clip.x + clip.w, clip.y + clip.h)
            renderSettingRow(graphics, font, mouseX, mouseY, rendered, transition.module, false, now, motion)
            graphics.disableScissor()
        }
    }

    private fun renderSettingRow(
        graphics: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        row: Row,
        module: Module,
        hoverable: Boolean,
        now: Long,
        motion: ClickGuiMotion,
    ) {
        when (row) {
            is GroupRow -> renderGroupRow(graphics, font, mouseX, mouseY, row, module, hoverable, now, motion)
            is PreviewRow -> renderPreview(graphics, font, row.preview, row.bounds)
            is ColorRow -> renderColorRow(graphics, font, mouseX, mouseY, row, hoverable, now, motion)
            is NumberRow -> renderNumberRow(graphics, font, row, now, motion)
            is RangeRow -> renderRangeRow(graphics, font, row, now, motion)
            is ToggleRow -> renderToggleRow(graphics, font, mouseX, mouseY, row, hoverable, now, motion)
            is ChoiceRow -> renderChoiceRow(graphics, font, mouseX, mouseY, row, hoverable, now, motion)
            is TextRow -> renderTextRow(graphics, font, row, now, motion)
            is ActionRow -> renderActionRow(graphics, font, mouseX, mouseY, row, hoverable, now, motion)
        }
    }

    private fun transitionSource(transition: RowTransition, progress: Float): Row {
        val from = transition.from
        val to = transition.to
        return when {
            from == null -> to!!
            to == null -> from
            progress < 0.5f -> from
            else -> to
        }
    }

    private fun transitionBounds(transition: RowTransition, progress: Float): Rect {
        val from = transition.from?.getBounds() ?: transition.to!!.getBounds()
        val to = transition.to?.getBounds() ?: transition.from!!.getBounds()
        return Rect(
            Math.round(lerp(from.x, to.x, progress)), Math.round(lerp(from.y, to.y, progress)),
            maxOf(1, Math.round(lerp(from.w, to.w, progress))), maxOf(1, Math.round(lerp(from.h, to.h, progress))),
        )
    }

    private fun transitionReveal(row: RowTransition, transition: SettingsLayoutTransition, progress: Float): Rect? {
        if (row.from != null && row.to == null && transition.fromReveal != null) {
            val bounds = transition.fromReveal
            return Rect(bounds.x, bounds.y, bounds.w, maxOf(0, Math.round(bounds.h * (1.0f - progress))))
        }
        if (row.from == null && row.to != null && transition.toReveal != null) {
            val bounds = transition.toReveal
            return Rect(bounds.x, bounds.y, bounds.w, maxOf(0, Math.round(bounds.h * progress)))
        }
        return null
    }

    private fun withBounds(row: Row, bounds: Rect): Row {
        val original = row.getBounds()
        val dx = bounds.x - original.x
        val dy = bounds.y - original.y
        return when (row) {
            is GroupRow -> GroupRow(row.group, bounds, translateRect(row.toggle, dx, dy), row.depth, row.collapsed)
            is PreviewRow -> PreviewRow(row.preview, bounds)
            is ColorRow -> ColorRow(row.setting, bounds, translatePicker(row.picker, dx, dy))
            is NumberRow -> NumberRow(row.setting, bounds, translateRect(row.slider, dx, dy), translateRect(row.field, dx, dy))
            is RangeRow -> RangeRow(row.setting, bounds, translateRect(row.slider, dx, dy), translateRect(row.field, dx, dy))
            is ToggleRow -> ToggleRow(row.setting, bounds)
            is ChoiceRow -> ChoiceRow(row.setting, bounds, translateRect(row.field, dx, dy))
            is TextRow -> TextRow(row.setting, bounds, translateRect(row.field, dx, dy))
            is ActionRow -> ActionRow(row.action, bounds)
        }
    }

    private fun translateRect(rect: Rect?, dx: Int, dy: Int): Rect? =
        rect?.let { Rect(it.x + dx, it.y + dy, it.w, it.h) }

    private fun translatePicker(picker: Picker?, dx: Int, dy: Int): Picker? = picker?.let {
        Picker(translateRect(it.square, dx, dy)!!, translateRect(it.hue, dx, dy)!!,
            translateRect(it.alpha, dx, dy), translateRect(it.hex, dx, dy)!!)
    }

    private fun intersection(first: Rect, second: Rect): Rect? {
        val left = maxOf(first.x, second.x)
        val top = maxOf(first.y, second.y)
        val right = minOf(first.x + first.w, second.x + second.w)
        val bottom = minOf(first.y + first.h, second.y + second.h)
        return if (right <= left || bottom <= top) null else Rect(left, top, right - left, bottom - top)
    }

    private fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, module: Module, viewport: Rect, appliedScroll: Int) {
        val preview = module as? ModulePreview ?: return
        if (preview.previewGroupName() != null) return
        val bounds = Rect(viewport.x + PADDING, viewport.y + PADDING - appliedScroll,
            maxOf(1, viewport.w - PADDING * 2), previewHeight(module, maxOf(1, viewport.w - PADDING * 2), viewport.h))
        renderPreview(graphics, font, preview, bounds)
    }

    private fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, preview: ModulePreview, bounds: Rect) {
        roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL, CARD_BG, CARD_BG, CARD_BORDER)
        graphics.enableScissor(bounds.x, bounds.y, bounds.x + bounds.w, bounds.y + bounds.h)
        val contentX = bounds.x + PADDING
        val contentY = bounds.y + 5
        val contentWidth = maxOf(1, bounds.w - PADDING * 2)
        val contentHeight = maxOf(1, bounds.h - 8)
        var drawn = false
        // Title screen: previews that build ItemStacks throw until a world binds item components; skip them.
        val ran = HudManager.safePreview((preview as? Module)?.name() ?: "preview") {
            drawn = preview.drawPreview(graphics, font, contentX, contentY, contentWidth, contentHeight)
        }
        if (ran && !drawn) {
            var lineY = contentY
            for (line in preview.previewLines(font, contentWidth)) {
                for (wrapped in font.split(line, contentWidth)) {
                    if (lineY + LINE_HEIGHT > bounds.y + bounds.h - 3) break
                    moduleText(graphics, font, wrapped, contentX, lineY, TEXT_PRIMARY)
                    lineY += LINE_HEIGHT
                }
                if (lineY + LINE_HEIGHT > bounds.y + bounds.h - 3) break
            }
        }
        graphics.disableScissor()
    }

    private fun renderGroupRow(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int,
        groupRow: GroupRow, module: Module, hoverable: Boolean, now: Long, motion: ClickGuiMotion) {
        val toggle = groupRow.toggle
        val groupToggle = groupRow.group.toggle
        val pulse = interactionPulse(groupRow.group, now, motion)
        val on = groupToggle?.value() == true
        val edges = if (toggle == null || groupToggle == null) floatArrayOf(0.0f, 0.0f)
            else toggleEdges(groupToggle, on, now, motion)
        ClickGuiScreenRenderBehavior.renderGroupSettingRow(
            graphics, font, groupRow.bounds, toggle, mouseX, mouseY, hoverable, pulse,
            groupRow.group.name!!, groupRow.collapsed, groupRow.depth, on, edges[0], edges[1],
            MODULE_TEXT_SCALE, TEXT_HEIGHT, GROUP_INDENT, SWITCH_WIDTH, componentRadius(),
        )
    }

    private fun renderColorRow(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int,
        colorRow: ColorRow, hoverable: Boolean, now: Long, motion: ClickGuiMotion) {
        val bounds = colorRow.bounds
        val setting = colorRow.setting
        val pulse = interactionPulse(setting, now, motion)
        val hovered = hoverable && Rect(bounds.x, bounds.y, bounds.w, ROW_HEIGHT).contains(mouseX, mouseY)
        if (hovered || pulse > 0.0f) {
            val highlight = if (hovered) CATEGORY_HOVER else withOpacity(PANEL_HIGHLIGHT, pulse * 0.35f)
            roundedRect(graphics, bounds.x + 9, bounds.y, bounds.w - 18, ROW_HEIGHT - 2, componentRadius(), highlight)
        }
        moduleText(graphics, font, textFit(font, setting.displayName(), moduleTextWidth(bounds.w - VALUE_GUTTER - 28)),
            bounds.x + 16, bounds.y + (ROW_HEIGHT - TEXT_HEIGHT) / 2, TEXT_SECONDARY)
        val swatchSize = 12
        val swatchX = bounds.x + bounds.w - swatchSize - 14
        val swatchY = bounds.y + (ROW_HEIGHT - swatchSize) / 2
        ColorPickerPalette.checkerboard(graphics, swatchX, swatchY, swatchSize, swatchSize)
        roundedRectBordered(graphics, swatchX, swatchY, swatchSize, swatchSize, 3, setting.argb(), setting.argb(), BORDER)
        val picker = colorRow.picker ?: return
        val reveal = pickerReveal(setting, now, motion)
        val revealHeight = Math.round(pickerHeight(setting) * reveal)
        if (revealHeight <= 0) return
        val pose = graphics.pose()
        pose.pushMatrix()
        val translation = if (motion == ClickGuiMotion.EXPRESSIVE && reveal < 1.0f) Math.round((1.0f - reveal) * 7.0f) else 0
        if (translation != 0) pose.translate(0.0f, translation.toFloat())
        graphics.enableScissor(bounds.x, picker.square.y + translation, bounds.x + bounds.w, picker.square.y + translation + revealHeight)
        renderSaturationSquare(graphics, picker.square, setting)
        renderHueBar(graphics, picker.hue, setting)
        picker.alpha?.let { renderAlphaBar(graphics, it, setting) }
        renderHexField(graphics, font, picker.hex, setting)
        graphics.disableScissor()
        pose.popMatrix()
    }

    private fun renderSaturationSquare(graphics: GuiGraphicsExtractor, square: Rect, setting: ColorSetting) =
        ColorPickerPalette.saturationSquare(graphics, square.x, square.y, square.w, square.h, setting)
    private fun renderHueBar(graphics: GuiGraphicsExtractor, bar: Rect, setting: ColorSetting) =
        ColorPickerPalette.hueBar(graphics, bar.x, bar.y, bar.w, bar.h, setting)
    private fun renderAlphaBar(graphics: GuiGraphicsExtractor, bar: Rect, setting: ColorSetting) =
        ColorPickerPalette.alphaBar(graphics, bar.x, bar.y, bar.w, bar.h, setting)

    private fun renderHexField(graphics: GuiGraphicsExtractor, font: Font, field: Rect, setting: ColorSetting) {
        val focused = setting === focusedHexSetting
        renderField(graphics, field, focused)
        val value = if (focused) hexInput else setting.hex()
        val slice = visibleSlice(font, value, if (focused) hexCursor else value.length, maxOf(1, field.w - 8))
        val shown = slice.text
        val textY = field.y + (field.h - TEXT_HEIGHT) / 2
        if (focused) drawFieldSelection(graphics, font, slice, value, hexCursor, hexAnchor, field.x + 4, textY)
        graphics.text(font, shown, field.x + 4, textY, TEXT_PRIMARY)
        if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0L) {
            val cursorInSlice = clampCursor(shown, hexCursor - slice.start)
            val caretX = field.x + 4 + font.width(shown.substring(0, cursorInSlice))
            graphics.fill(caretX, textY - 1, caretX + 1, textY + TEXT_HEIGHT + 1, TEXT_PRIMARY)
        }
    }

    private fun renderNumberRow(graphics: GuiGraphicsExtractor, font: Font, numberRow: NumberRow,
        now: Long, motion: ClickGuiMotion) {
        val field = numberRow.field!!
        val controlWidth = field.w + 36
        moduleText(graphics, font, textFit(font, numberRow.setting.displayName(), moduleTextWidth(numberRow.bounds.w - controlWidth)),
            numberRow.bounds.x + 16, numberRow.bounds.y + 1, TEXT_SECONDARY)
        val slider = numberRow.slider!!
        val pulse = interactionPulse(numberRow.setting, now, motion)
        val track = if (pulse > 0.0f) lerpColor(SLIDER_TRACK, PANEL_HIGHLIGHT, pulse * 0.4f) else SLIDER_TRACK
        roundedRect(graphics, slider.x, slider.y, slider.w, slider.h, slider.h / 2, track)
        val fillWidth = Math.round(slider.w * numberRow.setting.fraction())
        if (fillWidth > 0) {
            val fill = if (pulse > 0.0f) lerpColor(SLIDER_FILL, TEXT_PRIMARY, pulse * 0.22f) else SLIDER_FILL
            roundedRect(graphics, slider.x, slider.y, fillWidth, slider.h, slider.h / 2, fill)
        }
        renderNumberField(graphics, font, numberRow, now, motion)
    }

    private fun renderRangeRow(graphics: GuiGraphicsExtractor, font: Font, rangeRow: RangeRow, now: Long, motion: ClickGuiMotion) {
        val field = rangeRow.field!!
        val setting = rangeRow.setting
        val controlWidth = field.w + 36
        moduleText(graphics, font, textFit(font, setting.displayName(), moduleTextWidth(rangeRow.bounds.w - controlWidth)),
            rangeRow.bounds.x + 16, rangeRow.bounds.y + 1, TEXT_SECONDARY)
        val slider = rangeRow.slider!!
        val pulse = interactionPulse(setting, now, motion)
        val track = if (pulse > 0.0f) lerpColor(SLIDER_TRACK, PANEL_HIGHLIGHT, pulse * 0.4f) else SLIDER_TRACK
        roundedRect(graphics, slider.x, slider.y, slider.w, slider.h, slider.h / 2, track)
        val lowX = slider.x + Math.round(slider.w * setting.lowFraction())
        val highX = slider.x + Math.round(slider.w * setting.highFraction())
        val fill = if (pulse > 0.0f) lerpColor(SLIDER_FILL, TEXT_PRIMARY, pulse * 0.22f) else SLIDER_FILL
        if (highX > lowX) roundedRect(graphics, lowX, slider.y, highX - lowX, slider.h, slider.h / 2, fill)
        for (thumbX in intArrayOf(lowX, highX)) {
            roundedRect(graphics, thumbX - 2, slider.y - 2, 5, slider.h + 4, 2, TEXT_PRIMARY)
        }
        renderField(graphics, field, false)
        val text = textFit(font, setting.display(), maxOf(1, field.w - 8))
        graphics.text(font, text, field.x + 4, field.y + (field.h - TEXT_HEIGHT) / 2, TEXT_PRIMARY)
    }

    private fun renderNumberField(graphics: GuiGraphicsExtractor, font: Font, numberRow: NumberRow,
        now: Long, motion: ClickGuiMotion) {
        val field = numberRow.field!!
        val focused = numberRow.setting === focusedNumberSetting
        renderField(graphics, field, focused)
        val value = if (focused) numberInput else numberRow.setting.display()
        val slice = visibleSlice(font, value, if (focused) numberCursor else value.length, maxOf(1, field.w - 8))
        val shown = slice.text
        val textY = field.y + (field.h - TEXT_HEIGHT) / 2
        if (focused) drawFieldSelection(graphics, font, slice, value, numberCursor, numberAnchor, field.x + 4, textY)
        graphics.text(font, shown, field.x + 4, textY, TEXT_PRIMARY)
        if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0L) {
            val cursorInSlice = clampCursor(shown, numberCursor - slice.start)
            val caretX = field.x + 4 + font.width(shown.substring(0, cursorInSlice))
            graphics.fill(caretX, textY - 1, caretX + 1, textY + TEXT_HEIGHT + 1, TEXT_PRIMARY)
        }
    }

    private fun renderToggleRow(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int,
        toggleRow: ToggleRow, hoverable: Boolean, now: Long, motion: ClickGuiMotion) {
        val pulse = interactionPulse(toggleRow.setting, now, motion)
        val on = toggleRow.setting.value()
        val edges = toggleEdges(toggleRow.setting, on, now, motion)
        ClickGuiScreenRenderBehavior.renderToggleSettingRow(graphics, font, toggleRow.bounds, mouseX, mouseY,
            hoverable, pulse, toggleRow.setting.displayName(), on, edges[0], edges[1], MODULE_TEXT_SCALE, TEXT_HEIGHT,
            SWITCH_WIDTH, SWITCH_HEIGHT, componentRadius())
    }

    private fun renderChoiceRow(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int,
        choiceRow: ChoiceRow, hoverable: Boolean, now: Long, motion: ClickGuiMotion) {
        ClickGuiScreenRenderBehavior.renderChoiceSettingRow(graphics, font, choiceRow.bounds, choiceRow.field!!,
            mouseX, mouseY, hoverable, interactionPulse(choiceRow.setting, now, motion),
            choiceRow.setting.displayName(), choiceRow.setting.displayValue(), MODULE_TEXT_SCALE, TEXT_HEIGHT, componentRadius())
    }

    private fun renderTextRow(graphics: GuiGraphicsExtractor, font: Font, textRow: TextRow,
        now: Long, motion: ClickGuiMotion) {
        val bounds = textRow.bounds
        val field = textRow.field!!
        val setting = textRow.setting
        val focused = setting === focusedTextSetting
        moduleText(graphics, font, textFit(font, setting.displayName(), moduleTextWidth(bounds.w - field.w - 36)),
            bounds.x + 16, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY)
        renderField(graphics, field, focused)
        val value = if (setting.keybindCapture()) {
            if (focused && focusedTextSetting === setting) KeybindCapture.HINT else keybindDisplayName(setting.value())
        } else setting.value()
        val inner = maxOf(1, field.w - 8)
        if (setting.keybindCapture()) {
            moduleCenteredText(graphics, font, textFit(font, value, inner), field.x + field.w / 2,
                field.y + (field.h - TEXT_HEIGHT) / 2, if (focused) TEXT_PRIMARY else TEXT_SECONDARY)
            return
        }
        val slice = visibleSlice(font, value, if (focused) textCursor else value.length, inner)
        val text = slice.text
        val textY = field.y + (field.h - TEXT_HEIGHT) / 2
        if (focused) drawFieldSelection(graphics, font, slice, value, textCursor, textAnchor, field.x + 4, textY)
        graphics.text(font, text, field.x + 4, textY, TEXT_PRIMARY)
        if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0L) {
            val cursorInSlice = clampCursor(text, textCursor - slice.start)
            val caretX = field.x + 4 + font.width(text.substring(0, cursorInSlice))
            graphics.fill(caretX, textY - 1, caretX + 1, textY + TEXT_HEIGHT + 1, TEXT_PRIMARY)
        }
    }

    /** Paints the selected part of the visible slice behind the text. */
    private fun drawFieldSelection(
        graphics: GuiGraphicsExtractor, font: Font, slice: TextSlice, value: String,
        cursor: Int, anchor: Int, textX: Int, textY: Int,
    ) {
        val range = selection(value, cursor, anchor)
        if (range.isEmpty() || slice.text.isEmpty()) return
        val from = clampCursor(slice.text, range.start - slice.start)
        val to = clampCursor(slice.text, range.end - slice.start)
        if (to <= from) return
        graphics.fill(
            textX + font.width(slice.text.substring(0, from)), textY - 1,
            textX + font.width(slice.text.substring(0, to)), textY + TEXT_HEIGHT + 1, SLIDER_FILL,
        )
    }

    private fun keybindDisplayName(serialized: String?): String {
        if (serialized.isNullOrBlank()) return "None"
        return try {
            val keyName = if (serialized.length == 1 && !serialized.contains('.')) "key.keyboard.$serialized" else serialized
            val key = InputConstants.getKey(keyName)
            if (key == null || key == InputConstants.UNKNOWN) "None" else key.displayName.string
        } catch (_: RuntimeException) { "None" }
    }

    private fun renderActionRow(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int,
        actionRow: ActionRow, hoverable: Boolean, now: Long, motion: ClickGuiMotion) {
        ClickGuiScreenRenderBehavior.renderActionSettingRow(graphics, font, actionRow.bounds, mouseX, mouseY,
            hoverable, interactionPulse(actionRow.action, now, motion), actionRow.action.label,
            MODULE_TEXT_SCALE, TEXT_HEIGHT, componentRadius())
    }

    /** Input fields are flat; only the focused one gets an accent outline. */
    private fun renderField(graphics: GuiGraphicsExtractor, field: Rect, focused: Boolean) {
        roundedRect(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_TRACK)
        if (focused) roundedRectOutline(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_FILL)
    }

    private fun renderScrollbar(graphics: GuiGraphicsExtractor, viewport: Rect, contentHeight: Int, scroll: Int) {
        if (contentHeight <= viewport.h) return
        // Thin and only while scrolling: fully visible for 700 ms after the last wheel notch, then a 250 ms fade.
        val idle = elapsedMillis(lastScrollNanos, System.nanoTime())
        val opacity = if (idle < 700L) 1.0f else 1.0f - (idle - 700L) / 250.0f
        if (opacity <= 0.0f) return
        val trackX = viewport.x + viewport.w - SCROLLBAR_WIDTH - 3
        val thumbHeight = maxOf(16, viewport.h * viewport.h / contentHeight)
        val travel = viewport.h - thumbHeight
        val maxScroll = contentHeight - viewport.h
        val appliedScroll = maxOf(0, minOf(maxScroll, scroll))
        val thumbY = viewport.y + travel * appliedScroll / maxScroll
        roundedRect(graphics, trackX, thumbY, SCROLLBAR_WIDTH, thumbHeight, 1, withOpacity(SCROLLBAR, opacity))
    }

    private fun settingsViewport(x: Int, panelY: Int): Rect {
        val viewport = gridViewport(x, panelY)
        val header = settingsHeaderHeight()
        return Rect(viewport.x, viewport.y + header, viewport.w, maxOf(1, viewport.h - header))
    }

    private fun settingsRows(module: Module, x: Int, panelY: Int): List<Row> =
        settingsRows(module, x, panelY, settingsScrollVisual, renderedExpandedColor(System.nanoTime(), motion()))

    private fun settingsRows(module: Module, x: Int, panelY: Int, scroll: Double, expandedColor: ColorSetting?): List<Row> {
        val viewport = settingsViewport(x, panelY)
        val appliedScroll = settingsAppliedScroll(module, viewport, scroll, expandedColor, x)
        val startY = viewport.y - appliedScroll
        val layout = settingsLayout(module, x, expandedColor)
        if (positionedSettingsLayout === layout && positionedSettingsStartY == startY && positionedSettingsRows != null) {
            return positionedSettingsRows!!
        }
        val positioned = ArrayList<Row>(layout.rows.size)
        for (row in layout.rows) {
            val bounds = row.getBounds()
            positioned.add(if (startY == 0) row else withBounds(row, Rect(bounds.x, bounds.y + startY, bounds.w, bounds.h)))
        }
        positionedSettingsLayout = layout
        positionedSettingsStartY = startY
        positionedSettingsRows = java.util.List.copyOf(positioned)
        return positionedSettingsRows!!
    }

    private fun settingsAppliedScroll(module: Module, viewport: Rect, scroll: Double, expandedColor: ColorSetting?, x: Int): Int {
        val maxScroll = maxOf(0, settingsContentHeight(module, x, expandedColor) - viewport.h)
        return maxOf(0, minOf(maxScroll, Math.round(scroll).toInt()))
    }

    private fun settingsLayout(module: Module, x: Int, expandedColor: ColorSetting?): SettingsLayoutCache {
        val rowX = x + gridInset() + PADDING
        val contentWidth = settingsContentWidth()
        val viewportHeight = settingsViewport(x, panelY()).h
        val previewOffset = ClickGuiSettingsLayout.previewOffset(module, contentWidth, viewportHeight)
        val debugEnabled = DebugState.enabled()
        val query = ClickGuiModuleSearchBehavior.normalizeQuery(searchQuery)
        val cached = settingsLayoutCache
        if (cached != null && cached.module === module && cached.rowX == rowX && cached.contentWidth == contentWidth &&
            cached.previewOffset == previewOffset && cached.expandedColor === expandedColor &&
            cached.revision == settingsLayoutRevision && cached.debugEnabled == debugEnabled && cached.query == query
        ) return cached
        val rows = ClickGuiSettingsLayout.layoutRows(module, rowX, previewOffset, expandedColor, contentWidth, viewportHeight, query)
        var contentHeight = previewOffset
        for (row in rows) {
            val bounds = row.getBounds()
            contentHeight = maxOf(contentHeight, bounds.y + bounds.h)
        }
        val updated = SettingsLayoutCache(module, rowX, contentWidth, previewOffset, expandedColor,
            settingsLayoutRevision, debugEnabled, rows, contentHeight, query)
        settingsLayoutCache = updated
        positionedSettingsLayout = null
        positionedSettingsRows = null
        positionedSettingsStartY = Int.MIN_VALUE
        return updated
    }

    private fun invalidateSettingsLayout() {
        settingsLayoutRevision++
        settingsLayoutCache = null
        positionedSettingsLayout = null
        positionedSettingsRows = null
        positionedSettingsStartY = Int.MIN_VALUE
    }

    private fun clampViewScroll() {
        clampCategoryScroll()
        val settingsModule = openSettingsModule
        if (settingsModule != null && ModuleManager.contains(selectedCategory, settingsModule)) {
            val x = panelX() + categoryWidth()
            val maxScroll = maxOf(0, settingsContentHeight(settingsModule, x, expandedColorSetting) - settingsViewport(x, panelY()).h)
            settingsScroll = settingsScroll.coerceIn(0, maxScroll)
            settingsScrollVisual = settingsScrollVisual.coerceIn(0.0, maxScroll.toDouble())
            return
        }
        val modules = visibleModules(selectedCategory)
        val maxScroll = maxOf(0, gridContentHeight(selectedCategory, modules) -
            gridCardViewport(panelX() + categoryWidth(), panelY(), selectedCategory).h)
        gridScroll = gridScroll.coerceIn(0, maxScroll)
        gridScrollVisual = gridScrollVisual.coerceIn(0.0, maxScroll.toDouble())
    }

    private fun settingsContentHeight(module: Module, x: Int): Int = settingsContentHeight(module, x, expandedColorSetting)
    private fun settingsContentHeight(module: Module, x: Int, expandedColor: ColorSetting?): Int =
        settingsLayout(module, x, expandedColor).contentHeight

    private fun inputSettled(): Boolean {
        if (closing || transitionFrom != null || settingsLayoutTransition != null || expansionFrom != null || expansionTo != null) return false
        val motion = motion()
        return motion == ClickGuiMotion.NONE || elapsedMillis(lifecycleStartedNanos, System.nanoTime()) >= motion.lifecycleMillis()
    }

    private fun selectCategoryEntry(entry: CategoryEntry?) {
        if (entry == null) return
        setSearchQuery("")
        val category = entry.category
        val filterId = requireNotNull(entry.filter.id())
        val selection = ClickGuiScreenCategoryBehavior.categorySelectionPlan(
            category, filterId, selectedCategory, ClickGuiState.moduleFilter(category),
        )
        val filterChanged = selection.filterChanged
        val categoryChanged = selection.categoryChanged
        if (filterChanged) {
            ClickGuiState.setModuleFilter(category, filterId)
            ClickGuiState.setGridScroll(category, 0)
            moduleSearch.invalidateVisibleModules()
            if (selectedCategory == category) {
                gridScroll = 0
                gridScrollVisual = 0.0
            }
        }
        persistView()
        val target = ViewState(category, null, if (filterChanged) 0 else ClickGuiState.gridScroll(category), 0, null)
        if (currentView() != target) {
            markInteraction(category.displayName())
            playClick()
            requestNavigation(target, Integer.compare(indexOf(category), indexOf(selectedCategory)))
        } else if (filterChanged) {
            markInteraction(category.displayName())
            playClick()
        }
        if (categoryChanged || filterChanged) logGuiDiagnostic("EVENT action=category,${gridDiagnosticState(category)}")
    }

    // Input arrives in GUI px; the handlers below work in design px like the layout.
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean =
        handleMouseClicked(toDesign(event), doubleClick)

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val scale = designPose()
        return handleMouseDragged(toDesign(event), dragX / scale, dragY / scale)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean = handleMouseReleased(toDesign(event))

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val scale = designPose()
        return handleMouseScrolled(mouseX / scale, mouseY / scale, scrollX, scrollY)
    }

    private fun toDesign(event: MouseButtonEvent): MouseButtonEvent {
        val scale = designPose()
        return MouseButtonEvent(event.x() / scale, event.y() / scale, event.buttonInfo())
    }

    private fun handleMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()
        if (!inputSettled()) {
            // Category navigation remains available during the slide so repeated clicks queue.
            if (event.button() == 0 && transitionFrom != null) {
                val queued = categoryEntryAt(mouseX.toDouble(), mouseY.toDouble(), panelX(), panelY())
                if (queued != null) selectCategoryEntry(queued)
            }
            return true
        }
        val panelX = panelX()
        val panelY = panelY()
        val rightX = panelX + categoryWidth()
        val left = event.button() == 0
        val right = event.button() == 1
        // A key-capture field also takes the middle and side mouse buttons; left and right still click away to cancel.
        val capturing = focusedTextSetting?.takeIf { it.keybindCapture() }
        if (capturing != null && event.button() >= 2) {
            capturing.setValue(InputConstants.Type.MOUSE.getOrCreate(event.button()).name)
            focusedTextSetting = null
            textCursor = 0
            textAnchor = -1
            invalidateSettingsLayout()
            ModConfig.markDirty()
            return true
        }
        if (!left && !right) return super.mouseClicked(event, doubleClick)

        blurTextField()
        if (changelogOpen) {
            if (!changelogPanelRect().contains(mouseX, mouseY)) closeChangelog()
            return true
        }

        val search = searchRect(panelX, panelY)
        if (left && search.contains(mouseX, mouseY)) {
            searchFocused = true
            searchCursor = cursorAtMouse(
                Minecraft.getInstance().font, searchQuery, mouseX.toDouble(),
                search.x + searchTextInsetForHeight(search.h), searchTextViewportWidth(search.w, search.h),
                searchCursor, HEADER_TEXT_SCALE,
            )
            if (doubleClick) {
                val all = selectAllState(searchQuery)
                searchCursor = all.cursor
                searchAnchor = all.anchor
            } else {
                searchAnchor = searchCursor
                selectingField = SelectingField.SEARCH
            }
            return true
        }
        if (left && UpdateChecker.newerVersion() != null) {
            if (updateDownloadRect(panelX, panelY).contains(mouseX, mouseY)) {
                ConfirmLinkScreen.confirmLinkNow(this, UpdateChecker.releaseUrl())
                return true
            }
            if (updateInfoRect(panelX, panelY).contains(mouseX, mouseY)) {
                playClick()
                openChangelog()
                return true
            }
        }
        if (left && transitionFrom == null && workspaceRect(panelX, panelY).contains(mouseX, mouseY)) {
            switchWorkspace()
            return true
        }
        if (left && githubRect(panelX, panelY).contains(mouseX, mouseY)) {
            ConfirmLinkScreen.confirmLinkNow(this, repositoryUrl())
            return true
        }
        if (left && settingsGearRect(panelX, panelY).contains(mouseX, mouseY)) {
            openModulePage(GeneralModule.INSTANCE, "General settings")
            return true
        }
        if (left && themeRect(panelX, panelY).contains(mouseX, mouseY)) {
            openModulePage(VisualModule.INSTANCE, "Theme settings")
            return true
        }
        if (left && cosmeticsRect(panelX, panelY).contains(mouseX, mouseY)) {
            persistView()
            markInteraction("Cosmetics")
            playClick()
            beginClose(CosmeticsScreen(this))
            return true
        }
        if (left && moveElementsRect(panelX, panelY).contains(mouseX, mouseY)) {
            persistView()
            markInteraction("Move Elements")
            playClick()
            beginClose(MoveUiScreen(this))
            return true
        }
        val settingsModule = openSettingsModule
        if (left && transitionFrom == null && settingsModule != null && settingsBackRect(rightX, panelY).contains(mouseX, mouseY)) {
            markInteraction("Back to modules")
            playClick()
            if (fromClassic) { returnToClassic(); return true }
            requestNavigation(ViewState(selectedCategory, null, gridScroll, 0, null), -1)
            return true
        }

        val clickedEntry = categoryEntryAt(mouseX.toDouble(), mouseY.toDouble(), panelX, panelY)
        if (left && clickedEntry != null) {
            val entries = categoryEntries()
            val fullRow = categoryRowBounds(panelX, panelY)[entries.indexOf(clickedEntry)]
            val visibleRow = Rect(fullRow.x, fullRow.y - categoryScroll, fullRow.w, fullRow.h)
            if (categoryHeartHitRect(visibleRow, categoryFavoriteRect(visibleRow)).contains(mouseX, mouseY)) {
                ClickGuiScreenCategoryBehavior.toggleCategoryFavorite(clickedEntry.category)
                favoriteStateChanged()
                playClick()
                ModConfig.markDirty()
                return true
            }
            selectCategoryEntry(clickedEntry)
            return true
        }

        val modules = visibleModules(selectedCategory)
        if (settingsModule != null && ModuleManager.contains(selectedCategory, settingsModule)) {
            return settingsClicked(settingsModule, mouseX, mouseY, rightX, panelY, left, right, doubleClick)
        }

        if (left) {
            val filters = chipFilters(selectedCategory)
            val chips = chipRects(selectedCategory, rightX, panelY)
            for (i in filters.indices) {
                if (!chips[i].contains(mouseX, mouseY)) continue
                selectFilterChip(selectedCategory, filters[i])
                return true
            }
        }

        for (card in cardRects(selectedCategory, modules, rightX, panelY,
            if (transitionFrom == null) gridScrollVisual else currentView().gridScroll.toDouble())) {
            val bounds = Rect(card.x(), card.y(), card.w(), card.h())
            val module = card.module()
            if (!bounds.contains(mouseX, mouseY)) continue
            if (left) pressedCard = module
            val hits = cardHitRects(module, bounds)
            if (left && hits[0]!!.contains(mouseX, mouseY)) {
                markInteraction(module)
                playClick()
                ClickGuiState.toggleFavorite(module)
                favoriteStateChanged()
                ModConfig.markDirty()
                return true
            }
            if (left && hits[2]?.contains(mouseX, mouseY) == true) {
                markInteraction(module)
                playClick()
                if (ModuleKeybindManager.isBinding(module)) ModuleKeybindManager.cancelBinding()
                else ModuleKeybindManager.beginBinding(module)
                return true
            }
            if (left && hits[1]?.contains(mouseX, mouseY) == true) {
                markInteraction(module)
                playClick()
                val wasEnabled = module.isEnabled()
                module.toggle()
                animateToggle(module, wasEnabled, module.isEnabled())
                persistView()
                ModConfig.markDirty()
            } else if (left && module.hasSettings()) {
                markInteraction(module)
                playClick()
                requestNavigation(ViewState(module.category(), module, ClickGuiState.gridScroll(module.category()), 0, null), 1)
            }
            return true
        }
        if (gridViewport(rightX, panelY).contains(mouseX, mouseY)) return true
        return super.mouseClicked(event, doubleClick)
    }

    private fun settingsClicked(module: Module, mouseX: Int, mouseY: Int, x: Int, panelY: Int, left: Boolean, right: Boolean,
        doubleClick: Boolean = false): Boolean {
        val viewport = settingsViewport(x, panelY)
        if ((!left && !right) || !viewport.contains(mouseX, mouseY)) return true
        for (row in settingsRows(module, x, panelY)) {
            when (row) {
                is GroupRow -> {
                    val toggleBounds = row.toggle
                    val groupToggle = row.group.toggle
                    if (toggleBounds != null && toggleBounds.contains(mouseX, mouseY) && groupToggle != null) {
                        markInteraction(row.group)
                        playClick()
                        val wasEnabled = groupToggle.value()
                        groupToggle.toggle()
                        invalidateSettingsLayout()
                        animateToggle(groupToggle, wasEnabled, groupToggle.value())
                        persistView()
                        ModConfig.markDirty()
                        return true
                    }
                    if (row.bounds.contains(mouseX, mouseY)) {
                        markInteraction(row.group)
                        playClick()
                        val before = settingsRows(module, x, panelY)
                        ClickGuiState.toggleCollapsed(module, row.group)
                        invalidateSettingsLayout()
                        clampViewScroll()
                        val after = settingsRows(module, x, panelY)
                        startSettingsLayoutTransition(module, before, after)
                        return true
                    }
                }
                is ColorRow -> {
                    val picker = row.picker
                    if (picker != null) {
                        if (picker.square.contains(mouseX, mouseY)) {
                            markInteraction(row.setting); playClick(); draggingPicker = PickerPart.SQUARE
                            applyPicker(row.setting, picker, mouseX, mouseY); return true
                        }
                        if (picker.hue.contains(mouseX, mouseY)) {
                            markInteraction(row.setting); playClick(); draggingPicker = PickerPart.HUE
                            applyPicker(row.setting, picker, mouseX, mouseY); return true
                        }
                        if (picker.alpha?.contains(mouseX, mouseY) == true) {
                            markInteraction(row.setting); playClick(); draggingPicker = PickerPart.ALPHA
                            applyPicker(row.setting, picker, mouseX, mouseY); return true
                        }
                        if (picker.hex.contains(mouseX, mouseY)) {
                            markInteraction(row.setting)
                            playClick()
                            focusedHexSetting = row.setting
                            hexInput = row.setting.hex()
                            hexCursor = cursorAtMouse(Minecraft.getInstance().font, hexInput, mouseX.toDouble(),
                                picker.hex.x + 4, picker.hex.w - 8, hexCursor)
                            hexAnchor = if (doubleClick) 0 else hexCursor
                            if (doubleClick) hexCursor = hexInput.length
                            return true
                        }
                    }
                    if (Rect(row.bounds.x, row.bounds.y, row.bounds.w, ROW_HEIGHT).contains(mouseX, mouseY)) {
                        markInteraction(row.setting)
                        playClick()
                        setExpandedColor(if (expandedColorSetting === row.setting) null else row.setting)
                        clampViewScroll()
                        persistView()
                        return true
                    }
                }
                is NumberRow -> {
                    val field = row.field
                    if (field != null && field.contains(mouseX, mouseY)) {
                        markInteraction(row.setting); playClick()
                        if (focusedNumberSetting === row.setting && !doubleClick) {
                            numberCursor = cursorAtMouse(Minecraft.getInstance().font, numberInput, mouseX.toDouble(),
                                field.x + 4, field.w - 8, numberCursor)
                            numberAnchor = numberCursor
                            selectingField = SelectingField.NUMBER
                        } else {
                            focusedNumberSetting = row.setting
                            numberInput = row.setting.display()
                            numberCursor = numberInput.length
                            numberAnchor = 0
                        }
                        return true
                    }
                    val slider = row.slider
                    if (slider != null && slider.contains(mouseX, mouseY)) {
                        markInteraction(row.setting); playClick()
                        draggingNumberSetting = row.setting
                        logGuiDiagnostic("EVENT action=slider_drag,phase=start,slider=${row.setting.name()}")
                        applyNumberSlider(row.setting, slider, mouseX)
                        return true
                    }
                }
                is RangeRow -> {
                    val slider = row.slider
                    if (slider != null && slider.contains(mouseX, mouseY)) {
                        markInteraction(row.setting); playClick()
                        val at = (mouseX - slider.x) / slider.w.toFloat()
                        val lowGap = Math.abs(at - row.setting.lowFraction())
                        val highGap = Math.abs(at - row.setting.highFraction())
                        // The nearer thumb follows; on a tie the side of the mouse decides, so stacked thumbs can part.
                        draggingRange = row.setting
                        draggingRangeHigh = if (highGap == lowGap) at > row.setting.highFraction() else highGap < lowGap
                        logGuiDiagnostic("EVENT action=slider_drag,phase=start,slider=${row.setting.name()},thumb=${if (draggingRangeHigh) "high" else "low"}")
                        applyRangeSlider(row.setting, draggingRangeHigh, slider, mouseX)
                        return true
                    }
                }
                is ToggleRow -> if (row.bounds.contains(mouseX, mouseY)) {
                    markInteraction(row.setting); playClick()
                    val wasEnabled = row.setting.value()
                    row.setting.toggle()
                    invalidateSettingsLayout()
                    animateToggle(row.setting, wasEnabled, row.setting.value())
                    persistView(); ModConfig.markDirty()
                    return true
                }
                is ChoiceRow -> if (row.bounds.contains(mouseX, mouseY)) {
                    markInteraction(row.setting); playClick()
                    if (left) row.setting.selectNext() else if (right) row.setting.selectPrevious()
                    invalidateSettingsLayout(); ModConfig.markDirty()
                    return true
                }
                is TextRow -> if (row.field!!.contains(mouseX, mouseY)) {
                    markInteraction(row.setting); playClick()
                    val field = row.field!!
                    focusedTextSetting = row.setting
                    if (row.setting.keybindCapture()) {
                        textCursor = 0
                        textAnchor = -1
                        return true
                    }
                    textCursor = cursorAtMouse(Minecraft.getInstance().font, row.setting.value(), mouseX.toDouble(),
                        field.x + 4, field.w - 8, row.setting.value().length)
                    if (doubleClick) {
                        val all = selectAllState(row.setting.value())
                        textCursor = all.cursor
                        textAnchor = all.anchor
                    } else {
                        textAnchor = textCursor
                        selectingField = SelectingField.TEXT
                    }
                    return true
                }
                is ActionRow -> if (row.bounds.contains(mouseX, mouseY)) {
                    markInteraction(row.action); playClick()
                    persistView()
                    row.action.onClick.run()
                    followSavedView()
                    invalidateSettingsLayout()
                    return true
                }
                is PreviewRow -> if (left && row.bounds.contains(mouseX, mouseY)) {
                    val content = previewContent(row.bounds)
                    if (row.preview.previewMousePressed(
                            mouseX - content.x, mouseY - content.y, content.w, content.h, 0, doubleClick, minecraft.hasShiftDown(),
                        )
                    ) {
                        draggingPreview = row.preview
                        markInteraction(row.preview)
                        return true
                    }
                } else if (right && row.bounds.contains(mouseX, mouseY)) {
                    val content = previewContent(row.bounds)
                    if (row.preview.previewMousePressed(
                            mouseX - content.x, mouseY - content.y, content.w, content.h, 1, doubleClick, minecraft.hasShiftDown(),
                        )
                    ) {
                        markInteraction(row.preview)
                        row.preview.previewMouseReleased(mouseX - content.x, mouseY - content.y, content.w, content.h, 1)
                        ModConfig.markDirty()
                        return true
                    }
                }
            }
        }
        return true
    }

    /** The area a preview draws its own content in: the same box [renderPreview] hands to the preview. */
    private fun previewContent(bounds: Rect): Rect =
        Rect(bounds.x + PADDING, bounds.y + 5, maxOf(1, bounds.w - PADDING * 2), maxOf(1, bounds.h - 8))

    /** Sends a drag or the release of a press a preview took to that preview; true when one was being dragged. */
    private fun routePreviewMouse(event: MouseButtonEvent, release: Boolean): Boolean {
        val preview = draggingPreview ?: return false
        val module = openSettingsModule
        if (module != null) {
            for (row in settingsRows(module, panelX() + categoryWidth(), panelY())) {
                if (row is PreviewRow && row.preview === preview) {
                    val content = previewContent(row.bounds)
                    val localX = event.x().toInt() - content.x
                    val localY = event.y().toInt() - content.y
                    if (release) preview.previewMouseReleased(localX, localY, content.w, content.h, event.button())
                    else preview.previewMouseDragged(localX, localY, content.w, content.h, event.button())
                    break
                }
            }
        }
        if (release) {
            draggingPreview = null
            persistView()
            ModConfig.markDirty()
        }
        return true
    }

    /** Moves the caret of the field being selected with the mouse; the anchor stays where the click began. */
    private fun dragSelection(mouseX: Int): Boolean {
        val target = selectingField ?: return false
        val font = Minecraft.getInstance().font
        when (target) {
            SelectingField.SEARCH -> {
                val search = searchRect(panelX(), panelY())
                searchCursor = cursorAtMouse(
                    font, searchQuery, mouseX.toDouble(), search.x + searchTextInsetForHeight(search.h),
                    searchTextViewportWidth(search.w, search.h), searchCursor, HEADER_TEXT_SCALE,
                )
                return true
            }
            SelectingField.TEXT, SelectingField.NUMBER -> {
                val module = openSettingsModule ?: return false
                for (row in settingsRows(module, panelX() + categoryWidth(), panelY())) {
                    if (target == SelectingField.TEXT && row is TextRow && row.setting === focusedTextSetting) {
                        val field = row.field ?: return false
                        textCursor = cursorAtMouse(font, row.setting.value(), mouseX.toDouble(), field.x + 4, field.w - 8, textCursor)
                        return true
                    }
                    if (target == SelectingField.NUMBER && row is NumberRow && row.setting === focusedNumberSetting) {
                        val field = row.field ?: return false
                        numberCursor = cursorAtMouse(font, numberInput, mouseX.toDouble(), field.x + 4, field.w - 8, numberCursor)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun handleMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (!inputSettled()) return true
        if (event.button() == 0 && dragSelection(event.x().toInt())) return true
        if (draggingPreview != null) return routePreviewMouse(event, release = false)
        val module = openSettingsModule ?: return super.mouseDragged(event, dragX, dragY)
        val rightX = panelX() + categoryWidth()
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()
        if (draggingPicker != null || draggingNumberSetting != null || draggingRange != null) {
            for (row in settingsRows(module, rightX, panelY())) {
                if (row is RangeRow && row.setting === draggingRange) {
                    row.slider?.let { applyRangeSlider(row.setting, draggingRangeHigh, it, mouseX); return true }
                }
                if (draggingPicker != null && row is ColorRow && row.setting === expandedColorSetting && row.picker != null) {
                    applyPicker(row.setting, row.picker!!, mouseX, mouseY)
                    return true
                }
                if (row is NumberRow && row.setting === draggingNumberSetting) {
                    val slider = row.slider
                    if (slider != null) {
                        applyNumberSlider(row.setting, slider, mouseX)
                        return true
                    }
                }
            }
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    private fun handleMouseReleased(event: MouseButtonEvent): Boolean {
        selectingField = null
        pressedCard = null
        if (draggingPreview != null) return routePreviewMouse(event, release = true)
        if (!inputSettled()) {
            draggingPicker = null
            draggingNumberSetting = null
            draggingRange = null
            return true
        }
        if (draggingPicker != null || draggingNumberSetting != null || draggingRange != null) {
            draggingPicker = null
            draggingNumberSetting = null
            draggingRange = null
            persistView()
            ModConfig.markDirty()
            return true
        }
        return super.mouseReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val codepoint = event.codepoint()
        if (searchFocused) {
            if (Character.isISOControl(codepoint)) return true
            val edit = insertReplacingSelection(searchQuery, searchCursor, searchAnchor, Character.toString(codepoint), SEARCH_MAX_LENGTH)
            if (edit.text != searchQuery) setSearchQuery(edit.text)
            searchCursor = edit.cursor
            searchAnchor = edit.anchor
            return true
        }
        if (Character.isISOControl(codepoint)) {
            return focusedTextSetting != null || focusedNumberSetting != null || focusedHexSetting != null || super.charTyped(event)
        }
        val textSetting = focusedTextSetting
        if (textSetting != null && textSetting.keybindCapture()) return true
        val hexSetting = focusedHexSetting
        if (hexSetting != null) {
            if (Character.digit(codepoint, 16) >= 0) applyHexEdit(hexSetting, Character.toString(codepoint))
            return true
        }
        if (textSetting != null) {
            applyTextEdit(textSetting, Character.toString(codepoint))
            return true
        }
        val numberSetting = focusedNumberSetting
        if (numberSetting != null) {
            applyNumberEdit(numberSetting, Character.toString(codepoint))
            return true
        }
        return super.charTyped(event)
    }

    private fun applyTextEdit(setting: TextSetting, inserted: String) {
        val edit = insertReplacingSelection(setting.value(), textCursor, textAnchor, inserted, setting.maxLength())
        if (edit.text != setting.value()) {
            setting.setValue(edit.text)
            invalidateSettingsLayout()
            ModConfig.markDirty()
        }
        textCursor = minOf(setting.value().length, edit.cursor)
        textAnchor = -1
    }

    private fun applyHexEdit(setting: ColorSetting, inserted: String) {
        val digits = inserted.filter { Character.digit(it, 16) >= 0 }.uppercase(Locale.ROOT)
        if (digits.isEmpty()) return
        val edit = insertReplacingSelection(hexInput, hexCursor, hexAnchor, digits, 8)
        hexInput = edit.text
        hexCursor = edit.cursor
        hexAnchor = -1
        setting.setHex(hexInput)
        ModConfig.markDirty()
    }

    private fun applyNumberEdit(setting: NumberSetting, inserted: String) {
        val allowed = inserted.map { if (it == ',') '.' else it }.filter {
            it.isDigit() || it == '-' || it == '+' || (!setting.isInteger() && it == '.')
        }.joinToString("")
        if (allowed.isEmpty()) return
        val edit = insertReplacingSelection(numberInput, numberCursor, numberAnchor, allowed, 16)
        numberInput = edit.text
        numberCursor = edit.cursor
        numberAnchor = -1
    }

    /**
     * Shared single-line shortcuts: select all, copy, cut, paste, and (shift-)navigation. Returns the new field
     * state, or null when the key is not an editing key so the caller can handle it.
     */
    private fun editKey(event: KeyEvent, text: String, cursor: Int, anchor: Int, maxLength: Int): ClickGuiTextEditResult? {
        val minecraft = Minecraft.getInstance()
        if (minecraft.hasControlDown()) {
            when (event.key()) {
                GLFW.GLFW_KEY_A -> return selectAllState(text)
                GLFW.GLFW_KEY_C -> {
                    val picked = selectedText(text, cursor, anchor)
                    if (picked.isNotEmpty()) minecraft.keyboardHandler.setClipboard(picked)
                    return ClickGuiTextEditResult(text, cursor, false, anchor)
                }
                GLFW.GLFW_KEY_X -> {
                    val picked = selectedText(text, cursor, anchor)
                    if (picked.isEmpty()) return ClickGuiTextEditResult(text, cursor, false, anchor)
                    minecraft.keyboardHandler.setClipboard(picked)
                    return deleteSelection(text, cursor, anchor)
                }
                GLFW.GLFW_KEY_V -> return insertReplacingSelection(
                    text, cursor, anchor, sanitizePaste(minecraft.keyboardHandler.getClipboard()), maxLength,
                )
            }
        }
        if (event.key() in EDIT_KEYS) {
            return applyKeyWithSelection(
                text, cursor, anchor, textEditKey(event.key()), (event.modifiers() and GLFW.GLFW_MOD_SHIFT) != 0,
            )
        }
        return null
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (changelogOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            closeChangelog()
            return true
        }
        if (searchFocused) {
            val edit = editKey(event, searchQuery, searchCursor, searchAnchor, SEARCH_MAX_LENGTH)
            if (edit != null) {
                if (edit.text != searchQuery) setSearchQuery(edit.text)
                searchCursor = edit.cursor
                searchAnchor = edit.anchor
                return true
            }
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE -> {
                    if (searchQuery.isNotEmpty()) { setSearchQuery(""); searchCursor = 0; searchAnchor = -1 } else blurTextField()
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField()
                else -> return true
            }
            return true
        }
        val textSetting = focusedTextSetting
        if (textSetting != null && textSetting.keybindCapture()) {
            val key = if (KeybindCapture.clearsBinding(event.key())) InputConstants.UNKNOWN else InputConstants.getKey(event)
            if (key != null) {
                textSetting.setValue(key.name)
                focusedTextSetting = null
                textCursor = 0
                textAnchor = -1
                invalidateSettingsLayout()
                ModConfig.markDirty()
            }
            return true
        }
        val hexSetting = focusedHexSetting
        if (hexSetting != null) {
            val edit = editKey(event, hexInput, hexCursor, hexAnchor, 8)
            if (edit != null) {
                val digits = edit.text.filter { Character.digit(it, 16) >= 0 }.uppercase(Locale.ROOT)
                hexCursor = minOf(edit.cursor, digits.length)
                hexAnchor = if (digits == edit.text) edit.anchor else -1
                if (digits != hexInput) {
                    hexInput = digits
                    hexSetting.setHex(hexInput)
                    ModConfig.markDirty()
                }
                return true
            }
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField()
                else -> return super.keyPressed(event)
            }
            return true
        }
        val numberSetting = focusedNumberSetting
        if (numberSetting != null) {
            val edit = editKey(event, numberInput, numberCursor, numberAnchor, 16)
            if (edit != null) {
                val allowed = edit.text.map { if (it == ',') '.' else it }.filter {
                    it.isDigit() || it == '-' || it == '+' || (!numberSetting.isInteger() && it == '.')
                }.joinToString("")
                numberInput = allowed
                numberCursor = minOf(edit.cursor, allowed.length)
                numberAnchor = if (allowed == edit.text) edit.anchor else -1
                return true
            }
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE -> {
                    focusedNumberSetting = null
                    numberInput = ""
                    numberAnchor = -1
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField()
                else -> return true
            }
            return true
        }
        if (textSetting != null) {
            val value = textSetting.value()
            val edit = editKey(event, value, textCursor, textAnchor, textSetting.maxLength())
            if (edit != null) {
                if (edit.text != value) {
                    textSetting.setValue(edit.text)
                    invalidateSettingsLayout()
                    ModConfig.markDirty()
                }
                textCursor = minOf(textSetting.value().length, edit.cursor)
                textAnchor = if (textSetting.value() == edit.text) edit.anchor else -1
                return true
            }
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField()
                else -> return super.keyPressed(event)
            }
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (draggingPicker != null) { draggingPicker = null; return true }
            if (draggingNumberSetting != null) { draggingNumberSetting = null; return true }
            if (draggingRange != null) { draggingRange = null; return true }
            if (expandedColorSetting != null) {
                expandedColorSetting = null
                invalidateSettingsLayout()
                persistView()
                return true
            }
            if (openSettingsModule != null) {
                if (fromClassic) { returnToClassic(); return true }
                requestNavigation(ViewState(selectedCategory, null, gridScroll, 0, null), -1)
                persistView()
                return true
            }
            return super.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    /** Leaves the settings page for the Classic menu this screen was opened from. */
    private fun returnToClassic() {
        openSettingsModule = null
        expandedColorSetting = null
        persistView()
        // The style may have been switched on the Theme page that is being left.
        beginClose(ClickGuiStyle.screen(VisualModule.INSTANCE.clickGuiStyle()))
    }

    private fun blurTextField() {
        if (focusedTextSetting == null && focusedNumberSetting == null && focusedHexSetting == null && !searchFocused) return
        commitNumberInput()
        focusedTextSetting = null
        textCursor = 0
        textAnchor = -1
        focusedNumberSetting = null
        numberInput = ""
        numberCursor = 0
        numberAnchor = -1
        focusedHexSetting = null
        hexCursor = 0
        hexAnchor = -1
        searchFocused = false
        searchCursor = clampCursor(searchQuery, searchCursor)
        searchAnchor = -1
        selectingField = null
        hexInput = ""
        ModConfig.markDirty()
    }

    private fun commitNumberInput() {
        val setting = focusedNumberSetting ?: return
        if (numberInput.isBlank()) return
        try {
            val previous = setting.value()
            setting.setValue(numberInput.trim().toFloat())
            if (setting.value() != previous) invalidateSettingsLayout()
        } catch (_: NumberFormatException) {
            // Preserve the last valid value when an unfinished number is left in the field.
        }
    }

    private fun handleMouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!inputSettled()) return true
        lastScrollNanos = System.nanoTime()
        val rightX = panelX() + categoryWidth()
        val scrollSpeed = if (Minecraft.getInstance().hasControlDown()) 4 else 1
        val notch = Math.round(scrollY * CHANNEL_ROW_HEIGHT * scrollSpeed).toInt()
        val panelY = panelY()
        val categoryHovered = mouseX >= panelX() && mouseX < rightX &&
            mouseY >= categoryViewportTop(panelY) && mouseY < categoryViewportTop(panelY) + categoryViewportHeight(panelY)
        val settingsArea = if (openSettingsModule == null) null else settingsViewport(rightX, panelY)
        val settingsHovered = settingsArea?.contains(mouseX, mouseY) == true
        val modules = if (openSettingsModule == null) visibleModules(selectedCategory) else emptyList()
        val gridArea = if (openSettingsModule == null) gridViewport(rightX, panelY) else null
        val gridHovered = gridArea?.contains(mouseX, mouseY) == true
        when (ClickGuiScreenCategoryBehavior.scrollTarget(
            changelogOpen, categoryHovered, openSettingsModule != null, settingsHovered, gridHovered,
        )) {
            ClickGuiScrollTarget.CHANGELOG -> {
                val viewport = changelogViewport(changelogPanelRect())
                val maxScroll = maxOf(0, changelogLines().size * LINE_HEIGHT - viewport.h)
                changelogScroll = (changelogScroll - notch).coerceIn(0.0, maxScroll.toDouble())
                return true
            }
            ClickGuiScrollTarget.CATEGORY -> {
                val previousScroll = categoryScroll
                categoryScroll = ClickGuiScreenCategoryBehavior.categoryScrollAfterWheel(
                    categoryScroll, notch, categoryMaxScrollCurrent(categoryViewportHeight(panelY)),
                )
                if (categoryScroll != previousScroll) {
                    logGuiDiagnostic("EVENT action=category_scroll,category=${selectedCategory.name},categoryScroll=$categoryScroll,categoryViewportHeight=${categoryViewportHeight(panelY)}")
                }
                persistView()
                return true
            }
            ClickGuiScrollTarget.SETTINGS -> {
                val area = settingsArea!!
                val module = openSettingsModule!!
                val maxScroll = maxOf(0, settingsContentHeight(module, rightX,
                    renderedExpandedColor(System.nanoTime(), motion())) - area.h)
                settingsScroll = (settingsScroll - notch).coerceIn(0, maxScroll)
                persistView()
                return true
            }
            ClickGuiScrollTarget.GRID -> {
                val area = gridCardViewport(rightX, panelY, selectedCategory)
                val maxScroll = maxOf(0, gridContentHeight(selectedCategory, modules) - area.h)
                val previousScroll = gridScroll
                gridScroll = (gridScroll - notch).coerceIn(0, maxScroll)
                if (gridScroll != previousScroll) logGuiDiagnostic("EVENT action=grid_scroll,${gridDiagnosticState(selectedCategory)}")
                persistView()
                return true
            }
            ClickGuiScrollTarget.NONE -> Unit
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    private fun applyPicker(setting: ColorSetting, picker: Picker, mouseX: Int, mouseY: Int) {
        when (draggingPicker) {
            PickerPart.SQUARE -> ColorPickerPalette.applySquare(
                setting, mouseX.toDouble(), mouseY.toDouble(),
                picker.square.x, picker.square.y, picker.square.w, picker.square.h,
            )
            PickerPart.HUE -> ColorPickerPalette.applyHue(setting, mouseX.toDouble(), picker.hue.x, picker.hue.w)
            PickerPart.ALPHA -> {
                val alpha = picker.alpha!!
                ColorPickerPalette.applyAlpha(setting, mouseX.toDouble(), alpha.x, alpha.w)
            }
            null -> return
        }
        if (focusedHexSetting === setting) hexInput = setting.hex()
    }

    /**
     * Follows the cursor every frame while the left button stays down on a slider or picker, so a drag works even
     * when the screen receives no drag event; the release is detected here as well.
     */
    private fun pollSliderDrag(mouseX: Int, mouseY: Int) {
        val slider = draggingNumberSetting
        val picker = draggingPicker
        val range = draggingRange
        if (slider == null && picker == null && range == null) return
        // GLFW, not the MouseHandler flag: vanilla only updates that flag while no screen is open.
        if (GLFW.glfwGetMouseButton(minecraft.window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            logGuiDiagnostic("EVENT action=slider_drag,phase=end,slider=${slider?.name() ?: range?.name() ?: "picker"}")
            draggingNumberSetting = null
            draggingRange = null
            draggingPicker = null
            persistView()
            ModConfig.markDirty()
            return
        }
        if (!inputSettled()) return
        val module = openSettingsModule ?: return
        for (row in settingsRows(module, panelX() + categoryWidth(), panelY())) {
            if (range != null && row is RangeRow && row.setting === range) {
                row.slider?.let { applyRangeSlider(range, draggingRangeHigh, it, mouseX) }
                return
            }
            if (slider != null && row is NumberRow && row.setting === slider) {
                row.slider?.let { applyNumberSlider(slider, it, mouseX) }
                return
            }
            if (picker != null && row is ColorRow && row.setting === expandedColorSetting) {
                row.picker?.let { applyPicker(row.setting, it, mouseX, mouseY) }
                return
            }
        }
    }

    /** A module action (for example "Edit Class Colors") may point the saved view at another module; open it. The view was saved just before the action ran, so any difference came from the action. */
    private fun followSavedView() {
        val category = ClickGuiState.category() ?: return
        val module = ClickGuiState.openModule()
        if (category == selectedCategory && module === openSettingsModule) return
        setSearchQuery("")
        requestNavigation(
            ViewState(category, module, ClickGuiState.gridScroll(category), 0, null),
            Integer.compare(indexOf(category), indexOf(selectedCategory)),
        )
        logGuiDiagnostic("EVENT action=follow_view,category=${category.name},module=${module?.name()}")
    }

    private fun applyNumberSlider(setting: NumberSetting, slider: Rect, mouseX: Int) {
        val fraction = (mouseX - slider.x) / slider.w.toFloat()
        val previous = setting.value()
        setting.setFraction(fraction)
        if (setting.value() != previous) invalidateSettingsLayout()
    }

    private fun applyRangeSlider(setting: RangeSetting, high: Boolean, slider: Rect, mouseX: Int) {
        val fraction = (mouseX - slider.x) / slider.w.toFloat()
        val before = setting.low() to setting.high()
        if (high) setting.setHighFraction(fraction) else setting.setLowFraction(fraction)
        if (before != (setting.low() to setting.high())) invalidateSettingsLayout()
    }

    private fun categoryRowBounds(panelX: Int, panelY: Int): List<Rect> {
        val rows = ArrayList<Rect>()
        val entries = categoryEntries()
        val width = categoryWidth()
        val rowHeight = categoryRowHeight()
        val top = contentTop(panelY) + categoryTopInset()
        for (i in entries.indices) rows.add(Rect(panelX, top + i * rowHeight, width, rowHeight))
        return rows
    }

    private fun categoryRowHeight(): Int = ClickGuiScreenGeometry.categoryRowHeightForPanelHeight(panelHeight())
    private fun categoryTopInset(): Int = maxOf(8, Math.round(panelHeight() * 0.034f))
    private fun categoryViewportTop(panelY: Int): Int = contentTop(panelY)

    /** Where the scrolling category rows end; an update notice reserves a line below them. */
    private fun categoryViewportBottom(panelY: Int): Int {
        val bottomReserve = if (UpdateChecker.bannerText() == null) gridInset() else CATEGORY_NOTICE_HEIGHT + gridInset()
        return panelY + panelHeight() - bottomReserve
    }

    private fun categoryViewportHeight(panelY: Int): Int =
        maxOf(1, categoryViewportBottom(panelY) - categoryViewportTop(panelY))

    /**
     * Swaps the whole screen to the other workspace. The view being left is written to its own saved state first and
     * the other workspace's saved view is loaded, so each workspace comes back exactly as it was left.
     */
    private fun switchWorkspace() {
        persistView()
        val target = ClickGuiState.workspace().otherWorkspace()
        markInteraction("Workspace " + target.name)
        playClick()
        ClickGuiState.setWorkspace(target)
        navigationQueue.clear()
        transitionFrom = null
        transitionTo = null
        expansionFrom = null
        expansionTo = null
        settingsLayoutTransition = null
        focusedTextSetting = null
        focusedNumberSetting = null
        focusedHexSetting = null
        selectingField = null
        draggingNumberSetting = null
        draggingRange = null
        draggingPicker = null
        draggingPreview = null
        setSearchQuery("")
        selectedCategory = ClickGuiState.category() ?: Category.of(target)[0]
        openSettingsModule = ClickGuiState.openModule()
        expandedColorSetting = ClickGuiState.expandedColor()
        settingsScroll = ClickGuiState.settingsScroll()
        categoryScroll = ClickGuiState.categoryScroll()
        gridScroll = ClickGuiState.gridScroll(selectedCategory)
        gridScrollVisual = gridScroll.toDouble()
        settingsScrollVisual = settingsScroll.toDouble()
        orderedCategorySnapshot = null
        moduleSearch.invalidate()
        invalidateSettingsLayout()
        favoriteStateChanged()
        clampCategoryScroll()
        ModConfig.markDirty()
        logGuiDiagnostic("EVENT action=workspace,workspace=${target.name},category=${selectedCategory.name}")
    }

    private fun categoryEntryAt(mouseX: Double, mouseY: Double, panelX: Int, panelY: Int): CategoryEntry? {
        if (mouseX < panelX || mouseX >= panelX + categoryWidth()) return null
        val top = categoryViewportTop(panelY)
        if (mouseY < top || mouseY >= top + categoryViewportHeight(panelY)) return null
        val rowHeight = categoryRowHeight()
        val rowsTop = contentTop(panelY) + categoryTopInset()
        val index = floor((mouseY - rowsTop + categoryScroll) / rowHeight).toInt()
        val entries = categoryEntries()
        if (index < 0 || index >= entries.size) return null
        val rowTop = rowsTop + index * rowHeight - categoryScroll
        if (mouseY < rowTop || mouseY >= rowTop + rowHeight) return null
        return entries[index]
    }

    private fun clampCategoryScroll(): Int {
        val maxScroll = categoryMaxScrollCurrent(categoryViewportHeight(panelY()))
        categoryScroll = categoryScroll.coerceIn(0, maxScroll)
        return categoryScroll
    }

    private fun categoryContentHeightCurrent(): Int = categoryTopInset() + categoryEntries().size * categoryRowHeight()
    private fun categoryMaxScrollCurrent(viewportHeight: Int): Int = maxOf(0, categoryContentHeightCurrent() - maxOf(1, viewportHeight))
    private fun indexOf(category: Category): Int {
        val entries = categoryEntries()
        for (i in entries.indices) if (entries[i].isParent() && entries[i].category === category) return i
        return 0
    }

    @JvmRecord
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double): Boolean = px >= x && px < x + w && py >= y && py < y + h
        fun contains(px: Int, py: Int): Boolean = contains(px.toDouble(), py.toDouble())
    }

    @JvmRecord
    data class CategoryEntry(val category: Category, val filter: ModuleCategoryFilter) {
        fun isParent(): Boolean = filter.id() == "all"
    }

    private data class TextSlice(val text: String, val start: Int, val end: Int)

    private enum class SelectingField { SEARCH, TEXT, NUMBER }
    private data class ViewState(
        val category: Category,
        val module: Module?,
        val gridScroll: Int,
        val settingsScroll: Int,
        val expandedColor: ColorSetting?,
    )
    private data class NavigationRequest(val view: ViewState, val direction: Int)
    private data class TogglePulse(val from: Boolean, val to: Boolean, val startedNanos: Long)
    private data class RowTransition(val identity: Any, val from: Row?, val to: Row?, val order: Int)
    private data class CardSummaryLayout(val font: Font, val source: String, val width: Int, val lines: List<FormattedCharSequence>)
    private data class SettingsLayoutCache(
        val module: Module,
        val rowX: Int,
        val contentWidth: Int,
        val previewOffset: Int,
        val expandedColor: ColorSetting?,
        val revision: Long,
        val debugEnabled: Boolean,
        val rows: List<Row>,
        val contentHeight: Int,
        val query: String,
    )

    private data class SettingsLayoutTransition(
        val module: Module,
        val rows: List<RowTransition>,
        val fromReveal: Rect?,
        val toReveal: Rect?,
        val startedNanos: Long,
    ) {
        companion object {
            fun create(module: Module, from: List<Row>, to: List<Row>, startedNanos: Long): SettingsLayoutTransition {
                val fromByIdentity = IdentityHashMap<Any, Row>()
                val toByIdentity = IdentityHashMap<Any, Row>()
                val identities = ArrayList<Any>()
                for (row in from) {
                    val identity = rowIdentity(row)
                    if (!fromByIdentity.containsKey(identity)) {
                        fromByIdentity[identity] = row
                        identities.add(identity)
                    }
                }
                for (row in to) {
                    val identity = rowIdentity(row)
                    if (!toByIdentity.containsKey(identity)) toByIdentity[identity] = row
                    if (!fromByIdentity.containsKey(identity)) identities.add(identity)
                }
                val transitions = ArrayList<RowTransition>(identities.size)
                for (index in identities.indices) {
                    val identity = identities[index]
                    transitions.add(RowTransition(identity, fromByIdentity[identity], toByIdentity[identity], index))
                }
                return SettingsLayoutTransition(module, transitions, revealBounds(transitions, true), revealBounds(transitions, false), startedNanos)
            }

            private fun revealBounds(rows: List<RowTransition>, fromSide: Boolean): Rect? {
                var left = Int.MAX_VALUE
                var top = Int.MAX_VALUE
                var right = Int.MIN_VALUE
                var bottom = Int.MIN_VALUE
                for (row in rows) {
                    val belongs = if (fromSide) row.from != null && row.to == null else row.from == null && row.to != null
                    if (!belongs) continue
                    val bounds = (if (fromSide) row.from else row.to)!!.getBounds()
                    left = minOf(left, bounds.x)
                    top = minOf(top, bounds.y)
                    right = maxOf(right, bounds.x + bounds.w)
                    bottom = maxOf(bottom, bounds.y + bounds.h)
                }
                return if (left == Int.MAX_VALUE) null else Rect(left, top, right - left, bottom - top)
            }
        }
    }

    enum class TopBarIcon { WORKSPACE, GITHUB, SETTINGS, THEME, COSMETICS, MOVE_ELEMENTS }
    private enum class PickerPart { SQUARE, HUE, ALPHA }

    companion object {
        private const val TOOLBAR_ICON_TEXTURE_SIZE = ClickGuiScreenRenderBehavior.TOOLBAR_ICON_TEXTURE_SIZE
        private val ICON_GITHUB = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-mark-github-16.png")
        private val ICON_SETTINGS = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-gear-16.png")
        private val ICON_THEME = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-paintbrush-16.png")
        /** A wing with a sparkle, drawn for this mod (source: icons/cosmetics-wing-16.svg). */
        private val ICON_COSMETICS = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/cosmetics-wing-16.png")
        private val ICON_MOVE = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/move-arrows-16.png")
        /** The Macros workspace button: a lightning bolt for "cheats", drawn for this mod. */
        private val ICON_CHEATS = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/cheats-bolt.png")
        private val ICON_FAVORITE_OUTLINE = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/favorite-heart-outline.png")
        private val ICON_FAVORITE_FILLED = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/favorite-heart-filled.png")
        private const val SEARCH_MAX_LENGTH = 64
        private const val SEARCH_TEXT_INSET = 8
        private const val SEARCH_TEXT_RIGHT_PADDING = 8
        private const val CHANNEL_ROW_HEIGHT = 14
        private const val SCROLLBAR_WIDTH = 2
        private const val SCROLLBAR_GUTTER = 9
        private const val CATEGORY_NOTICE_HEIGHT = 14
        private const val FAVORITE_COLOR = 0xFFFF4B5C.toInt()
        private const val GUI_DIAGNOSTIC_MODULE = "Click GUI"
        private const val GUI_DIAGNOSTIC_EVENT_LIMIT = 32
        private const val VALUE_GUTTER = 36
        private const val LINE_HEIGHT = 9
        private const val TEXT_HEIGHT = 8
        // Text scales are whole or half steps, so at the locked GUI scale 2 every font pixel lands on whole screen pixels.
        private const val MODULE_TEXT_SCALE = 1.0f
        private const val CATEGORY_TEXT_SCALE = 1.0f
        private const val HEADER_TEXT_SCALE = 1.0f
        private const val KEY_TEXT_SCALE = 1.0f
        private const val KEY_BOX_HEIGHT = 16
        /** Widest key label the badge shows; longer binds are cut with an ellipsis. */
        private const val KEY_LABEL_MAX_WIDTH = 56
        /** Visual rects of the card controls grow this far to the left for clicking. */
        private const val HIT_LEFT = 8
        private const val HIT_VERTICAL = 4
        private const val PANEL_RADIUS = 5
        private const val COMPONENT_RADIUS = 7
        private const val KEY_SQUARE_RADIUS = 4
        private const val CARD_PADDING = 8
        private const val CARD_LINE_HEIGHT = 10
        private const val CATEGORY_LABEL_X = 11
        private const val CATEGORY_MIN_WIDTH = 100
        private const val CARET_BLINK_MILLIS = 500L
        private const val REPOSITORY_URL = ProjectLinks.GITHUB_URL

        @Suppress("UNCHECKED_CAST")
        @JvmStatic fun categoryEntries(categories: List<Category>, expandedCategories: Set<Category>): List<CategoryEntry> =
            ClickGuiScreenCategoryBehavior.categoryEntries(categories, expandedCategories) as List<CategoryEntry>
        @JvmStatic fun categoryEntrySelected(entry: CategoryEntry, selectedCategory: Category, filterId: String?, expanded: Boolean): Boolean =
            ClickGuiScreenCategoryBehavior.categoryEntrySelected(entry, selectedCategory, filterId, expanded)
        @JvmStatic fun categoryEntryLabel(entry: CategoryEntry, filterId: String?, expanded: Boolean): String =
            ClickGuiScreenCategoryBehavior.categoryEntryLabel(entry, filterId, expanded)
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic fun searchTextInsetForHeight(fieldHeight: Int): Int = SEARCH_TEXT_INSET
        @JvmStatic fun searchTextViewportWidth(fieldWidth: Int, fieldHeight: Int): Int =
            maxOf(1, fieldWidth - searchTextInsetForHeight(fieldHeight) - SEARCH_TEXT_RIGHT_PADDING)
        @JvmStatic fun searchTextFontWidth(fieldWidth: Int, fieldHeight: Int): Int =
            maxOf(1, Math.round(searchTextViewportWidth(fieldWidth, fieldHeight) / HEADER_TEXT_SCALE))
        @JvmStatic fun textOffsetForMouse(mouseX: Double, textX: Int, textScale: Float): Double =
            ClickGuiTextEditing.textOffsetForMouse(mouseX, textX, textScale)
        @JvmStatic fun moduleFavoriteHitRect(card: Rect, showsToggleControl: Boolean, switchTop: Int): Rect {
            var hitSize = maxOf(20, minOf(26, Math.round(card.h * 0.23f)))
            val rightInset = maxOf(6, Math.round(card.w * 0.025f))
            val topInset = maxOf(3, Math.round(card.h * 0.06f))
            var top = card.y + topInset
            var left = card.x + card.w - rightInset - hitSize
            if (showsToggleControl) {
                val toggle = cardSwitchRect(card)
                hitSize = minOf(hitSize, maxOf(12, switchTop - card.y - 2))
                left = toggle.x + toggle.w / 2 - hitSize / 2
                top = maxOf(card.y, minOf(top, switchTop - hitSize - 2))
            }
            return Rect(left, top, hitSize, hitSize)
        }
        /** A compact pill in the iOS 1.65:1 ratio on the card's right side. */
        @JvmStatic fun cardSwitchRect(card: Rect): Rect {
            val height = maxOf(12, Math.round(card.h * 0.22f))
            val width = Math.round(height * 1.65f)
            val inset = maxOf(8, Math.round(card.w * 0.035f))
            return Rect(card.x + card.w - inset - width, card.y + Math.round(card.h * 0.40f) - height / 2, width, height)
        }
        /** The key box under the switch: a square that widens to fit [labelWidth] (already scaled) plus 4 px a side. */
        @JvmStatic fun keySquareRect(card: Rect, showsToggleControl: Boolean, labelWidth: Int): Rect {
            val height = KEY_BOX_HEIGHT
            val width = maxOf(height, labelWidth + 10)
            if (showsToggleControl) {
                val toggle = cardSwitchRect(card)
                // A long bind would stick out past the card's right edge when centred under the switch.
                val left = (toggle.x + (toggle.w - width) / 2).coerceIn(card.x + 4, maxOf(card.x + 4, card.x + card.w - 4 - width))
                return Rect(left, toggle.y + toggle.h + maxOf(2, Math.round(card.h * 0.035f)), width, height)
            }
            val inset = maxOf(8, Math.round(card.w * 0.035f))
            return Rect(card.x + card.w - inset - width, card.y + card.h - maxOf(4, Math.round(card.h * 0.10f)) - height, width, height)
        }
        /** Compact bind text for the key badge: modifier letters (C, S, A, W) then the key, so a combo stays short. */
        @JvmStatic fun keyBadgeLabel(bind: ModuleKeybind): String {
            if (!bind.isBound()) return "–"
            val mods = StringBuilder()
            if ((bind.modifiers() and InputConstants.MOD_CONTROL) != 0) mods.append("C+")
            if ((bind.modifiers() and InputConstants.MOD_SHIFT) != 0) mods.append("S+")
            if ((bind.modifiers() and InputConstants.MOD_ALT) != 0) mods.append("A+")
            if ((bind.modifiers() and InputConstants.MOD_SUPER) != 0) mods.append("W+")
            return mods.toString() + bind.key().displayName.string
        }

        /**
         * Click areas for the heart, switch and key square, in that order (null where a card has no such control): each
         * visual rect grows 8 px left, to the card's right edge and half way up or down to its neighbour, so the three never
         * overlap and stay inside the card.
         */
        @JvmStatic fun cardControlHitRects(card: Rect, heart: Rect, toggle: Rect?, key: Rect?): Array<Rect?> {
            val visuals = arrayOf<Rect?>(heart, toggle, key)
            val order = visuals.indices.filter { visuals[it] != null }.sortedBy { visuals[it]!!.y }
            val out = arrayOfNulls<Rect>(3)
            val right = card.x + card.w
            for ((n, index) in order.withIndex()) {
                val v = visuals[index]!!
                val top = if (n == 0) maxOf(card.y, v.y - HIT_VERTICAL)
                    else (visuals[order[n - 1]]!!.let { it.y + it.h } + v.y) / 2
                val bottom = if (n == order.size - 1) minOf(card.y + card.h, v.y + v.h + HIT_VERTICAL)
                    else (v.y + v.h + visuals[order[n + 1]]!!.y) / 2
                val left = maxOf(card.x, v.x - HIT_LEFT)
                out[index] = Rect(left, top, right - left, bottom - top)
            }
            return out
        }

        /** The category row heart's click area: wider to the left and to the row's edge, never taller. */
        @JvmStatic fun categoryHeartHitRect(row: Rect, heart: Rect): Rect {
            val left = maxOf(row.x, heart.x - HIT_LEFT)
            return Rect(left, heart.y, row.x + row.w - left, heart.h)
        }

        /** Where card text must stop: before the heart, and before the switch when there is one. */
        @JvmStatic fun cardTextRight(card: Rect, toggle: Rect?, heart: Rect): Int = minOf(
            heart.x - maxOf(5, Math.round(card.w * 0.015f)),
            if (toggle != null) toggle.x - maxOf(8, Math.round(card.w * 0.025f))
            else card.x + card.w - maxOf(8, Math.round(card.w * 0.035f)),
        )
        /** Category names as the menu shows them; only Miscellaneous is shortened. */
        @JvmStatic fun categoryLabel(category: Category): String =
            if (category == Category.MISCELLANEOUS) "Misc" else category.displayName()
        @JvmStatic fun scrollbarGutter(): Int = SCROLLBAR_GUTTER
        @JvmStatic fun gridCardContentWidth(viewportWidth: Int): Int = ClickGuiModuleGrid.gridCardContentWidth(viewportWidth, SCROLLBAR_GUTTER)
        @JvmStatic fun cardWidthForGridViewport(viewportWidth: Int, columns: Int, gap: Int): Int =
            ClickGuiModuleGrid.cardWidthForGridViewport(viewportWidth, columns, gap, SCROLLBAR_GUTTER)
        @JvmStatic fun categoryContentHeight(): Int = Category.of(Workspace.NORMAL).size * ClickGuiSettingsLayout.ROW_HEIGHT
        @JvmStatic fun categoryContentHeightForPanelHeight(panelHeight: Int): Int =
            ClickGuiScreenGeometry.categoryContentHeightForPanelHeight(panelHeight)
        @JvmStatic fun categoryContentHeightForPanelHeight(panelHeight: Int, rowCount: Int): Int =
            ClickGuiScreenGeometry.categoryContentHeightForPanelHeight(panelHeight, rowCount)
        @JvmStatic fun categoryMaxScroll(viewportHeight: Int): Int = maxOf(0, categoryContentHeight() - maxOf(1, viewportHeight))
        @JvmStatic fun categoryMaxScroll(panelHeight: Int, viewportHeight: Int): Int =
            ClickGuiScreenGeometry.categoryMaxScroll(panelHeight, viewportHeight)
        @JvmStatic fun categoryMaxScroll(panelHeight: Int, viewportHeight: Int, rowCount: Int): Int =
            ClickGuiScreenGeometry.categoryMaxScroll(panelHeight, viewportHeight, rowCount)

        private fun numberText(value: Float): String =
            if (value == Math.round(value).toFloat()) Math.round(value).toString() else value.toString()
        private fun oneLine(text: String?): String = text?.replace('\n', ' ')?.replace('\r', ' ')
            ?.replace(Regex("\\s+"), " ")?.trim() ?: ""
        private fun conciseSummary(text: String): String {
            val summary = oneLine(text)
            val sentenceEnd = summary.indexOf(". ")
            return if (sentenceEnd > 0) summary.substring(0, sentenceEnd + 1) else summary
        }
        private fun textFit(font: Font, text: String, width: Int): String = ClickGuiScreenRenderBehavior.textFit(font, text, width)
        private fun drawScaledText(graphics: GuiGraphicsExtractor, font: Font, text: String, x: Int, y: Int, color: Int, scale: Float) =
            ClickGuiScreenRenderBehavior.drawScaledText(graphics, font, text, x, y, color, scale)
        private fun elapsedMillis(startedNanos: Long, nowNanos: Long): Long =
            ClickGuiMotion.elapsedMillis(startedNanos, nowNanos)
        private fun clampCursor(text: String, cursor: Int): Int = ClickGuiTextEditing.clampCursor(text, cursor)
        private fun moveCursor(text: String, cursor: Int, direction: Int): Int = ClickGuiTextEditing.moveCursor(text, cursor, direction)
        private val EDIT_KEYS = setOf(
            GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END,
        )
        private fun isDeleteKey(key: Int): Boolean = key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE
        private fun textEditKey(key: Int): ClickGuiTextEditKey = when (key) {
            GLFW.GLFW_KEY_BACKSPACE -> ClickGuiTextEditKey.BACKSPACE
            GLFW.GLFW_KEY_DELETE -> ClickGuiTextEditKey.DELETE
            GLFW.GLFW_KEY_LEFT -> ClickGuiTextEditKey.LEFT
            GLFW.GLFW_KEY_RIGHT -> ClickGuiTextEditKey.RIGHT
            GLFW.GLFW_KEY_HOME -> ClickGuiTextEditKey.HOME
            GLFW.GLFW_KEY_END -> ClickGuiTextEditKey.END
            else -> throw IllegalArgumentException("Unsupported text edit key: $key")
        }
        private fun rowIdentity(row: Row): Any = when (row) {
            is GroupRow -> row.group
            is PreviewRow -> row.preview
            is ColorRow -> row.setting
            is NumberRow -> row.setting
            is RangeRow -> row.setting
            is ToggleRow -> row.setting
            is ChoiceRow -> row.setting
            is TextRow -> row.setting
            is ActionRow -> row.action
        }
        private fun lerp(from: Float, to: Float, progress: Float): Float = from + (to - from) * progress
        private fun lerp(from: Int, to: Int, progress: Float): Float = lerp(from.toFloat(), to.toFloat(), progress)
    }
}

internal fun ClickGuiScreen.Rect.x(): Int = x
internal fun ClickGuiScreen.Rect.y(): Int = y
internal fun ClickGuiScreen.Rect.w(): Int = w
internal fun ClickGuiScreen.Rect.h(): Int = h
internal fun ClickGuiScreen.CategoryEntry.category(): Category = category
internal fun ClickGuiScreen.CategoryEntry.filter(): ModuleCategoryFilter = filter
internal fun ClickGuiModuleGrid.Section.label(): String? = label
internal fun ClickGuiModuleGrid.Section.modules(): List<Module> = modules
internal fun ClickGuiModuleGrid.SectionLayout.section(): ClickGuiModuleGrid.Section = section
internal fun ClickGuiModuleGrid.SectionLayout.headingY(): Int = headingY
internal fun ClickGuiModuleGrid.Layout.sections(): List<ClickGuiModuleGrid.SectionLayout> = sections
internal fun ClickGuiModuleGrid.Layout.contentHeight(): Int = contentHeight
internal fun ClickGuiModuleGrid.CardRect.module(): Module = module
internal fun ClickGuiModuleGrid.CardRect.x(): Int = x
internal fun ClickGuiModuleGrid.CardRect.y(): Int = y
internal fun ClickGuiModuleGrid.CardRect.w(): Int = w
internal fun ClickGuiModuleGrid.CardRect.h(): Int = h
internal fun ClickGuiModuleGrid.CardRect.index(): Int = index
