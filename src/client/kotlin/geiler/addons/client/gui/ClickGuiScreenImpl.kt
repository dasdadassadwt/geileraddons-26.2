package geiler.addons.client.gui

import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.macro.MacroTriggerContext
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.DebugState
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.ModuleCategoryFilter
import geiler.addons.client.module.ModuleKeybindManager
import geiler.addons.client.module.ModuleManager
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.module.impl.GeneralModule
import geiler.addons.client.module.impl.VisualModule
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
import geiler.addons.client.gui.GuiTheme.RADIUS
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

/** The module and settings screen, rendered through Minecraft's client-thread extractor. */
class ClickGuiScreen : Screen(Component.literal("GeilerAddons")) {
    private var selectedCategory: Category = ClickGuiState.category() ?: Category.values()[0]
    private var openSettingsModule: Module? = ClickGuiState.openModule()
    private var expandedColorSetting: ColorSetting? = ClickGuiState.expandedColor()
    private var draggingPicker: PickerPart? = null
    private var draggingNumberSetting: NumberSetting? = null
    private var focusedTextSetting: TextSetting? = null
    private var textCursor = 0
    private var textSelectAll = false
    private var focusedNumberSetting: NumberSetting? = null
    private var numberInput = ""
    private var numberCursor = 0
    private var numberSelectAll = false
    /** The colour whose hex field has focus, and what has been typed into it so far. */
    private var focusedHexSetting: ColorSetting? = null
    private var hexInput = ""
    private var hexCursor = 0
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
    private var lifecycleStartedNanos = openedAtNanos
    private var closing = false
    private var pendingScreen: Screen? = null
    private var lastFrameNanos = openedAtNanos
    private var searchQuery = ""
    private var searchCursor = 0
    private var searchFocused = false
    private var searchSelectAll = false

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
    }

    override fun init() {
        super.init()
        if (guiDiagnosticsActive) return
        guiDiagnosticsActive = true
        guiDiagnosticEvents = 0
        suppressedGuiDiagnosticEvents = 0
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, GUI_DIAGNOSTIC_MODULE, true)
        logGuiDiagnostic("STATUS action=open,${gridDiagnosticState(selectedCategory)}")
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
        val viewport = gridViewport(panelX() + categoryWidth(), panelY())
        val sectionSummary = StringBuilder()
        for (section in layout.sections()) {
            if (sectionSummary.isNotEmpty()) sectionSummary.append('|')
            val label = section.section().label()
            sectionSummary.append(label?.replace(' ', '_')?.replace('&', '+') ?: "all")
                .append(':').append(section.section().modules().size)
        }
        val scroll = if (category == selectedCategory) gridScroll else ClickGuiState.gridScroll(category)
        return "category=${category.name},queryActive=${searchQuery.isNotBlank()},cards=${modules.size},sections=$sectionSummary" +
            ",categoryScroll=$categoryScroll,categoryViewportHeight=${categoryViewportHeight(panelY())}" +
            ",viewport=${viewport.w}x${viewport.h},contentHeight=${layout.contentHeight()},scroll=$scroll" +
            ",maxScroll=${maxOf(0, layout.contentHeight() - viewport.h)}"
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

    private fun togglePosition(target: Any, on: Boolean, now: Long, motion: ClickGuiMotion): Float {
        val pulse = togglePulses[target] ?: return if (on) 1.0f else 0.0f
        val progress = ClickGuiMotion.smoothToggle(elapsedMillis(pulse.startedNanos, now).toFloat() / 180.0f)
        if (progress >= 1.0f) {
            togglePulses.remove(target)
            return if (pulse.to) 1.0f else 0.0f
        }
        return lerp(if (pulse.from) 1.0f else 0.0f, if (pulse.to) 1.0f else 0.0f, progress)
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

    private fun panelWidth(): Int = ClickGuiHeaderGeometry.panelWidthForScreen(width)

    private fun panelHeight(): Int = ClickGuiHeaderGeometry.panelHeightForScreen(height)

    private fun categoryWidth(): Int {
        val panelWidth = panelWidth()
        val panelHeight = panelHeight()
        if (categoryWidthCachedFont === font && categoryWidthCachedPanelWidth == panelWidth &&
            categoryWidthCachedPanelHeight == panelHeight
        ) return categoryWidthCachedValue

        var longestLabel = 0
        for (category in Category.values()) longestLabel = maxOf(longestLabel, font.width(category.displayName()))
        val logoRoom = ClickGuiHeaderGeometry.categoryBrandRoom(panelWidth, panelHeight)
        val labelRoom = Math.round(longestLabel * CATEGORY_TEXT_SCALE) + 24
        categoryWidthCachedFont = font
        categoryWidthCachedPanelWidth = panelWidth
        categoryWidthCachedPanelHeight = panelHeight
        categoryWidthCachedValue = minOf(maxOf(1, panelWidth - 1), maxOf(labelRoom, logoRoom))
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

    private fun headerButtonGap(): Int = ClickGuiHeaderGeometry.headerButtonGapForPanelWidth(panelWidth())

    private fun profileHeaderRect(panelX: Int, panelY: Int): Rect =
        ClickGuiHeaderGeometry.profileHeaderRectForLayout(panelX, panelY, panelWidth(), panelHeight())

    private fun versionRect(panelX: Int, panelY: Int): Rect =
        ClickGuiHeaderGeometry.versionBadgeRectForLayout(panelX, panelY, panelWidth(), panelHeight())

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
            download.x + download.w + maxOf(3, headerButtonGap() / 3), download.y,
            maxOf(30, Math.round(panelWidth() * 0.028f)), download.h,
        )
    }

    private fun searchRect(panelX: Int, panelY: Int): Rect =
        ClickGuiHeaderGeometry.searchRectForLayout(panelX, panelY, panelWidth(), panelHeight(), categoryWidth())

    private fun githubRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.GITHUB)
    private fun settingsGearRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.SETTINGS)
    private fun themeRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.THEME)
    private fun moveElementsRect(panelX: Int, panelY: Int): Rect = topBarRect(panelX, panelY, TopBarIcon.MOVE_ELEMENTS)
    private fun settingsBackRect(x: Int, panelY: Int): Rect = settingsHeaderSurfaceRect(x, panelY)

    private fun settingsBackLabelRect(x: Int, panelY: Int): Rect {
        val surface = settingsHeaderSurfaceRect(x, panelY)
        val height = minOf(18, maxOf(1, surface.h - 4))
        return Rect(
            surface.x + 4, surface.y + (surface.h - height) / 2,
            minOf(58, maxOf(1, surface.w - 8)), height,
        )
    }

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
        moduleSearch.visibleModules(category, searchQuery, favoriteRevision)

    private fun orderedCategories(): List<Category> {
        val snapshot = orderedCategorySnapshot
        if (snapshot != null && orderedCategoryFavoritesRevision == favoriteRevision) return snapshot
        val ordered = ClickGuiScreenCategoryBehavior.orderFavoriteCategories(Category.values())
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
    }

    private fun openGeneralSettings() {
        val general = GeneralModule.INSTANCE
        if (!ModuleManager.contains(general.category(), general)) return
        setSearchQuery("")
        val target = ViewState(general.category(), general, ClickGuiState.gridScroll(general.category()), 0, null)
        if (currentView() == target) return
        markInteraction("General settings")
        playClick()
        requestNavigation(target, Integer.compare(indexOf(general.category()), indexOf(selectedCategory)))
    }

    private fun openThemeSettings() {
        val theme = VisualModule.INSTANCE
        if (!ModuleManager.contains(theme.category(), theme)) return
        setSearchQuery("")
        val target = ViewState(theme.category(), theme, ClickGuiState.gridScroll(theme.category()), 0, null)
        if (currentView() == target) return
        markInteraction("Theme settings")
        playClick()
        requestNavigation(target, Integer.compare(indexOf(theme.category()), indexOf(selectedCategory)))
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
        val logo = profileHeaderRect(panelX, panelY)
        val versionBounds = versionRect(panelX, panelY)
        val search = searchRect(panelX, panelY)
        val repository = githubRect(panelX, panelY)
        val settings = settingsGearRect(panelX, panelY)
        val theme = themeRect(panelX, panelY)
        val moveElements = moveElementsRect(panelX, panelY)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(logo.x.toFloat(), logo.y.toFloat())
        pose.scale(logo.w / 512.0f, logo.h / 512.0f)
        graphics.blit(RenderPipelines.GUI_TEXTURED, MOD_LOGO, 0, 0, 0.0f, 0.0f, 512, 512, 512, 512, 0xFFFFFFFF.toInt())
        pose.popMatrix()
        var version = UpdateChecker.currentVersion()
        if (version.isBlank()) version = "unknown"
        roundedRectBordered(
            graphics, versionBounds.x, versionBounds.y, versionBounds.w, versionBounds.h,
            maxOf(5, Math.round(versionBounds.h * 0.22f)), GROUP_HEADER, GROUP_HEADER, BORDER,
        )
        val versionText = textFit(font, "v$version", maxOf(1, Math.round((versionBounds.w - 12) / HEADER_TEXT_SCALE)))
        val versionTextWidth = Math.round(font.width(versionText) * HEADER_TEXT_SCALE)
        val versionTextHeight = Math.round(font.lineHeight * HEADER_TEXT_SCALE)
        drawScaledText(
            graphics, font, versionText,
            versionBounds.x + (versionBounds.w - versionTextWidth) / 2,
            versionBounds.y + (versionBounds.h - versionTextHeight) / 2,
            TEXT_PRIMARY, HEADER_TEXT_SCALE,
        )

        if (search.w > 0) {
            roundedRectBordered(
                graphics, search.x, search.y, search.w, search.h, maxOf(6, Math.round(search.h * 0.22f)),
                if (searchFocused) BUTTON_BG else GROUP_HEADER,
                if (searchFocused) BUTTON_BG else GROUP_HEADER,
                if (searchFocused) PANEL_HIGHLIGHT else BORDER,
            )
        }

        renderTopBarButton(graphics, repository, TopBarIcon.GITHUB, "Open GitHub repository", mouseX, mouseY)
        renderTopBarButton(graphics, settings, TopBarIcon.SETTINGS, "General settings", mouseX, mouseY)
        renderTopBarButton(graphics, theme, TopBarIcon.THEME, "Theme settings", mouseX, mouseY)
        renderTopBarButton(graphics, moveElements, TopBarIcon.MOVE_ELEMENTS, "Move HUD elements", mouseX, mouseY)
        val newer = UpdateChecker.newerVersion()
        if (newer != null) {
            renderUpdateButton(graphics, font, updateDownloadRect(panelX, panelY), "Download", mouseX, mouseY)
            renderUpdateButton(graphics, font, updateInfoRect(panelX, panelY), "Info", mouseX, mouseY)
        }

        if (search.w > 0) {
            val queryText = if (searchQuery.isEmpty()) "Search modules..." else searchQuery
            val searchTextInset = searchTextInsetForHeight(search.h)
            val searchTextX = search.x + searchTextInset
            val searchTextWidth = searchTextFontWidth(search.w, search.h)
            val searchTextPixels = searchTextViewportWidth(search.w, search.h)
            var queryColor = if (searchQuery.isEmpty()) TEXT_MUTED else TEXT_PRIMARY
            val querySlice = if (searchQuery.isEmpty()) TextSlice(textFit(font, queryText, searchTextWidth), 0, 0)
                else visibleSlice(font, searchQuery, searchCursor, searchTextWidth)
            val searchTextY = search.y + (search.h - Math.round(TEXT_HEIGHT * HEADER_TEXT_SCALE)) / 2
            graphics.enableScissor(searchTextX, search.y + 2, searchTextX + searchTextPixels, search.y + search.h - 2)
            if (searchFocused && searchSelectAll && searchQuery.isNotEmpty() && querySlice.text.isNotEmpty()) {
                val selectionWidth = minOf(searchTextPixels, Math.round(font.width(querySlice.text) * HEADER_TEXT_SCALE))
                graphics.fill(
                    searchTextX, searchTextY - 1, searchTextX + selectionWidth,
                    searchTextY + Math.round(TEXT_HEIGHT * HEADER_TEXT_SCALE) + 1, CATEGORY_SELECTED,
                )
                queryColor = TEXT_ON_ACCENT
            }
            drawScaledText(graphics, font, querySlice.text, searchTextX, searchTextY, queryColor, HEADER_TEXT_SCALE)
            if (searchFocused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0L && !searchSelectAll) {
                val cursorInSlice = clampCursor(querySlice.text, searchCursor - querySlice.start)
                val caretX = searchTextX + Math.round(
                    font.width(querySlice.text.substring(0, cursorInSlice)) * HEADER_TEXT_SCALE,
                )
                graphics.fill(
                    caretX, search.y + maxOf(3, search.h / 5), caretX + 1,
                    search.y + search.h - maxOf(3, search.h / 5), TEXT_PRIMARY,
                )
            }
            graphics.disableScissor()
            renderSearchIcon(graphics, search, GuiTheme.searchIconColor())
        }
    }

    private fun renderTopBarButton(
        graphics: GuiGraphicsExtractor,
        bounds: Rect,
        icon: TopBarIcon,
        tooltip: String,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = bounds.contains(mouseX, mouseY)
        val surface = if (hovered) BUTTON_HOVER else GROUP_HEADER
        roundedRectBordered(
            graphics, bounds.x, bounds.y, bounds.w, bounds.h,
            maxOf(6, Math.round(bounds.w * 0.24f)), surface, surface, if (hovered) PANEL_HIGHLIGHT else BORDER,
        )
        val iconColor = if (hovered) TEXT_ON_ACCENT else TEXT_PRIMARY
        val texture = when (icon) {
            TopBarIcon.GITHUB -> ICON_GITHUB
            TopBarIcon.SETTINGS -> ICON_SETTINGS
            TopBarIcon.THEME -> ICON_THEME
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
        if (hovered) graphics.setTooltipForNextFrame(Component.literal(tooltip), mouseX, mouseY)
    }

    private fun renderSearchIcon(graphics: GuiGraphicsExtractor, bounds: Rect, color: Int) {
        val size = searchIconSize(bounds.h)
        val x = bounds.x + searchIconOffset(bounds.h)
        val y = bounds.y + (bounds.h - size) / 2
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(size / TOOLBAR_ICON_TEXTURE_SIZE.toFloat(), size / TOOLBAR_ICON_TEXTURE_SIZE.toFloat())
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, ICON_SEARCH, 0, 0, 0.0f, 0.0f,
            TOOLBAR_ICON_TEXTURE_SIZE, TOOLBAR_ICON_TEXTURE_SIZE, TOOLBAR_ICON_TEXTURE_SIZE,
            TOOLBAR_ICON_TEXTURE_SIZE, color,
        )
        pose.popMatrix()
    }

    // ---- rendering ----------------------------------------------------------------------

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // Refresh before drawing: a theme colour drag updates the menu on the same frame.
        VisualModule.INSTANCE.refreshTheme()
        val now = System.nanoTime()
        val motion = motion()
        updateAnimationState(now, motion)
        val settingsModule = openSettingsModule
        if (settingsModule != null && !ModuleManager.contains(selectedCategory, settingsModule)) {
            openSettingsModule = null
            expandedColorSetting = null
        }
        clampViewScroll()
        if (closing && elapsedMillis(lifecycleStartedNanos, now) >= motion.closeMillis()) return

        val lifecycle = lifecycleProgress(now, motion)
        val visible = if (closing) 1.0f - lifecycle else lifecycle
        val shade = if (motion == ClickGuiMotion.EXPRESSIVE) 0.78f else 0.66f
        graphics.fill(0, 0, width, height, withOpacity(0xC0000000.toInt(), visible * shade))

        val panelX = panelX()
        val panelY = panelY()
        val panelWidth = panelWidth()
        val categoryWidth = categoryWidth()
        val panelHeight = panelHeight()
        val rightX = panelX + categoryWidth
        val font = this.font

        val pose = graphics.pose()
        pose.pushMatrix()
        val scale = when {
            closing -> motion.closeScale(lifecycle)
            motion == ClickGuiMotion.NONE -> 1.0f
            motion == ClickGuiMotion.REDUCED -> 0.90f + 0.10f * lifecycle
            else -> 0.72f + 0.28f * motion.spring(lifecycle)
        }
        pose.translate(width / 2.0f, height / 2.0f)
        pose.scale(scale, scale)
        pose.translate(-width / 2.0f, -height / 2.0f)

        roundedRect(
            graphics, panelX - 2, panelY + 3, panelWidth + 4, panelHeight + 2, panelRadius() + 2,
            withOpacity(0xFF000000.toInt(), 0.32f),
        )
        roundedRect(graphics, panelX, panelY, panelWidth, panelHeight, panelRadius(), PANEL_TOP, PANEL_BOTTOM)
        graphics.fill(rightX - 1, panelY, rightX, panelY + panelHeight, withOpacity(BORDER, 0.9f))
        graphics.fill(
            panelX, panelY + headerHeight() - 1, panelX + panelWidth, panelY + headerHeight(),
            withOpacity(BORDER, 0.9f),
        )
        roundedRectOutline(graphics, panelX, panelY, panelWidth, panelHeight, panelRadius(), BORDER)

        graphics.enableScissor(panelX, panelY, panelX + panelWidth, panelY + panelHeight)
        renderHeader(graphics, font, mouseX, mouseY, panelX, panelY, rightX, now, motion)
        renderCategories(graphics, font, mouseX, mouseY, panelX, panelY, now, motion)
        renderView(graphics, font, mouseX, mouseY, rightX, panelY, now, motion, lifecycle)
        graphics.disableScissor()
        if (visible < 1.0f && motion != ClickGuiMotion.NONE) {
            val fade = (1.0f - visible) * if (motion == ClickGuiMotion.EXPRESSIVE) 0.42f else 0.20f
            roundedRect(graphics, panelX, panelY, panelWidth, panelHeight, RADIUS, withOpacity(0xFF000000.toInt(), fade))
        }
        pose.popMatrix()
        renderHoverTooltip(graphics, font, mouseX, mouseY)
        renderBindingNotice(graphics, font)
        renderChangelogOverlay(graphics, font, mouseX, mouseY)
    }

    private fun panelRadius(): Int = maxOf(8, Math.round(panelWidth() * 0.014f))
    private fun componentRadius(): Int = maxOf(5, Math.round(panelWidth() * 0.008f))

    private fun renderUpdateButton(
        graphics: GuiGraphicsExtractor,
        font: Font,
        bounds: Rect,
        label: String,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = bounds.contains(mouseX, mouseY)
        val surface = if (hovered) BUTTON_HOVER else BUTTON_BG
        roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, RADIUS_SMALL, surface, surface, BORDER)
        graphics.centeredText(
            font, textFit(font, label, maxOf(1, bounds.w - 8)), bounds.x + bounds.w / 2,
            bounds.y + (bounds.h - TEXT_HEIGHT) / 2, if (hovered) TEXT_ON_ACCENT else TEXT_PRIMARY,
        )
    }

    private fun renderChangelogOverlay(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int) {
        if (!changelogOpen) return
        val panel = changelogPanelRect()
        graphics.fill(0, 0, width, height, withOpacity(0xFF000000.toInt(), 0.45f))
        roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, RADIUS, PANEL_TOP, PANEL_BOTTOM, BORDER)
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
        val panelW = minOf(maxOf(240, panelWidth() - 40), maxOf(200, width - 24))
        val panelH = minOf(maxOf(120, panelHeight() + 40), maxOf(100, height - 24))
        return Rect((width - panelW) / 2, (height - panelH) / 2, panelW, panelH)
    }

    private fun changelogViewport(panel: Rect): Rect =
        Rect(panel.x + 10, panel.y + 32, maxOf(1, panel.w - 20), maxOf(1, panel.h - 50))

    private fun changelogLines(): List<String> {
        val notes = UpdateChecker.releaseNotes()
        val viewport = changelogViewport(changelogPanelRect())
        val key = "$width\u0078$height|${UpdateChecker.newerVersion()}|${notes.length}"
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
        val boxW = minOf(420, maxOf(220, width - 24))
        val boxH = 30
        val boxX = (width - boxW) / 2
        val boxY = height - boxH - 12
        roundedRectBordered(graphics, boxX, boxY, boxW, boxH, RADIUS_SMALL, BUTTON_HOVER, BUTTON_HOVER, TEXT_PRIMARY)
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
            if (!description.isNullOrBlank()) graphics.setTooltipForNextFrame(Component.literal(description), mouseX, mouseY)
            return
        }
    }

    private fun settingTooltip(module: Module, row: Row): String? = when (row) {
        is GroupRow -> "Click to expand or collapse ${row.group.name}."
        is PreviewRow -> null
        is ColorRow -> "Click a colour to open its picker; drag the controls or enter a hex value."
        is NumberRow -> "Drag the slider or click the value field to enter a number between " +
            "${numberText(row.setting.min())} and ${numberText(row.setting.max())}. Press Enter to apply a typed value."
        is ToggleRow -> module.settingTooltip(row.setting) ?: "Toggle ${row.setting.displayName()}."
        is ChoiceRow -> module.settingTooltip(row.setting)
            ?: "Click to choose the next value; right-click chooses the previous value."
        is TextRow -> if (row.setting.keybindCapture()) {
            "Click the field, then press the key to bind. Press Backspace or Delete to clear it."
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
                graphics, font, row.x, row.y, row.w, row.h, entry.category.displayName(),
                selected, !moving && selected, hovered, favorite.x, favorite.y, favorite.w, favorite.h,
                ClickGuiState.isFavorite(entry.category), favorite.contains(mouseX, mouseY),
                componentRadius(), TEXT_HEIGHT, CATEGORY_TEXT_SCALE, FAVORITE_COLOR,
                ICON_FAVORITE_FILLED, ICON_FAVORITE_OUTLINE,
            )
        }
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
            true, true, openedAtNanos, now, motion)
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
        val viewport = gridViewport(x, panelY)
        val layout = moduleGridLayout(category, modules)
        val maxScroll = maxOf(0, layout.contentHeight() - viewport.h)
        val appliedScroll = maxOf(0, minOf(maxScroll, Math.round(scroll).toInt()))
        val cards = visibleCardRects(layout, viewport, appliedScroll)
        val insideViewport = viewport.contains(mouseX, mouseY)
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
        val titleX = viewport.x + maxOf(2, PADDING / 2)
        val titleWidth = Math.round(font.width(heading) * CATEGORY_TEXT_SCALE)
        val titleY = y + (moduleSectionHeaderHeight() - Math.round(TEXT_HEIGHT * CATEGORY_TEXT_SCALE)) / 2
        drawScaledText(graphics, font, heading, titleX, titleY, TEXT_PRIMARY, CATEGORY_TEXT_SCALE)
        val lineLeft = titleX + titleWidth + PADDING
        val lineRight = viewport.x + viewport.w - SCROLLBAR_GUTTER
        if (lineLeft < lineRight) {
            val lineY = y + moduleSectionHeaderHeight() / 2
            graphics.fill(lineLeft, lineY, lineRight, lineY + 1, withOpacity(BORDER, 0.92f))
        }
    }

    private fun cardEntrance(index: Int, entranceStart: Long, now: Long, motion: ClickGuiMotion): Float {
        if (motion == ClickGuiMotion.NONE) return 1.0f
        val staggerIndex = minOf(index, 5)
        val delayed = elapsedMillis(entranceStart, now) - staggerIndex * motion.cardStaggerMillis()
        return motion.ease(delayed.toFloat() / maxOf(1, motion.transitionMillis()))
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
        val toggle = cardSwitchRect(bounds)
        val keybind = if (module.showsKeybindControl()) keybindRect(module, bounds) else null
        val keybindHovered = hoverable && keybind != null && keybind.contains(mouseX, mouseY)
        val pose = graphics.pose()
        pose.pushMatrix()

        val togglePosition = togglePosition(module, enabled, now, motion)
        var background = lerpColor(CARD_BG, CARD_BG_ENABLED, togglePosition)
        val hoverBackground = lerpColor(CARD_BG_HOVER, CARD_BG_ENABLED, togglePosition)
        var border = lerpColor(CARD_BORDER, CARD_BORDER_ENABLED, togglePosition)
        if (hovered) {
            background = lerpColor(background, hoverBackground, 0.85f)
            border = lerpColor(border, PANEL_HIGHLIGHT, 0.82f)
        }
        val pulse = interactionPulse(module, now, motion)
        if (pulse > 0.0f) {
            background = lerpColor(background, CARD_BG_HOVER, pulse * 0.65f)
            border = lerpColor(border, PANEL_HIGHLIGHT, pulse)
        }
        roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, componentRadius(), background, background, border)

        val inset = cardInset()
        val iconSize = maxOf(16, minOf(Math.round(bounds.h * 0.70f), Math.round(bounds.w * 0.24f)))
        val iconX = bounds.x + inset + maxOf(1, Math.round(bounds.w * 0.02f))
        val icon = Rect(iconX, bounds.y + (bounds.h - iconSize) / 2, iconSize, iconSize)
        val moduleIcon = ModuleIconTextures.forModule(module.name())
        val iconTexture = moduleIcon ?: ICON_MODULE_PLACEHOLDER
        val iconSourceSize = ModuleIconTextures.sourceSize(module.name())
        val itemPose = graphics.pose()
        itemPose.pushMatrix()
        itemPose.translate(icon.x.toFloat(), icon.y.toFloat())
        itemPose.scale(iconSize / iconSourceSize.toFloat(), iconSize / iconSourceSize.toFloat())
        if (moduleIcon != null && ModuleIconTextures.isTintable(module.name())) {
            graphics.blit(
                RenderPipelines.GUI_TEXTURED, iconTexture, 0, 0, 0.0f, 0.0f,
                iconSourceSize, iconSourceSize, iconSourceSize, iconSourceSize,
                VisualModule.INSTANCE.iconColorSetting().argb(),
            )
        } else {
            graphics.blit(RenderPipelines.GUI_TEXTURED, iconTexture, 0, 0, 0.0f, 0.0f,
                iconSourceSize, iconSourceSize, iconSourceSize, iconSourceSize)
        }
        itemPose.popMatrix()

        val textX = icon.x + icon.w + maxOf(8, Math.round(bounds.w * 0.022f))
        val favorite = moduleFavoriteRect(module, bounds)
        val textRight = minOf(
            favorite.x - maxOf(5, Math.round(bounds.w * 0.015f)),
            if (module.showsToggleControl()) toggle.x - maxOf(8, Math.round(bounds.w * 0.025f))
            else bounds.x + bounds.w - maxOf(8, Math.round(bounds.w * 0.035f)),
        )
        val textWidth = maxOf(1, textRight - textX)
        val titleY = bounds.y + maxOf(8, Math.round(bounds.h * 0.17f))
        val showWipBadge = module.isWorkInProgress()
        var badgeWidth = if (showWipBadge) font.width(WIP_BADGE_LABEL) + 8 else 0
        badgeWidth = minOf(badgeWidth, maxOf(0, textWidth - 1))
        val badgeGap = if (showWipBadge) minOf(5, maxOf(0, textWidth - badgeWidth - 1)) else 0
        val titleWidth = if (showWipBadge) maxOf(0, textWidth - badgeWidth - badgeGap) else textWidth
        val titleScale = cardTitleScale(font, module.name(), titleWidth)
        cardTitleFit(graphics, font, module.name(), textX, titleY, titleWidth, TEXT_PRIMARY)
        val titleHeight = maxOf(TEXT_HEIGHT, ceil(LINE_HEIGHT * titleScale).toInt())
        if (showWipBadge && badgeWidth > 0) {
            val badgeHeight = TEXT_HEIGHT + 4
            val renderedTitleWidth = if (titleWidth <= 0) 0 else minOf(titleWidth, ceil(font.width(module.name()) * titleScale).toInt())
            val badgeX = textX + renderedTitleWidth + badgeGap
            val badgeY = titleY + maxOf(0, (titleHeight - badgeHeight) / 2)
            val badgeTextWidth = maxOf(1, badgeWidth - 6)
            val badgeSurface = withOpacity(TEXT_WARN, 0.18f)
            roundedRectBordered(
                graphics, badgeX, badgeY, badgeWidth, badgeHeight, minOf(RADIUS_SMALL, badgeHeight / 2),
                badgeSurface, badgeSurface, withOpacity(TEXT_WARN, 0.72f),
            )
            graphics.centeredText(font, textFit(font, WIP_BADGE_LABEL, badgeTextWidth),
                badgeX + badgeWidth / 2, badgeY + (badgeHeight - TEXT_HEIGHT) / 2, TEXT_WARN)
        }
        val summary = if (searchQuery.isBlank()) module.description()
            else module.category().displayName() + " · " + module.description()
        val summaryLines = cardSummaryLines(font, module, summary, cardTextWidth(textWidth))
        val lineLimit = if (bounds.h >= 90) 2 else 1
        val summaryY = titleY + titleHeight + 4
        val summaryLineHeight = maxOf(9, Math.round(bounds.h * 0.095f))
        for (line in 0 until minOf(lineLimit, summaryLines.size)) {
            cardText(graphics, font, summaryLines[line], textX, summaryY + line * summaryLineHeight, TEXT_MUTED)
        }

        val status = if (GeneralModule.INSTANCE.availabilityNotices().value()) module.inactiveReason() else null
        if (status != null && bounds.h >= 90) {
            val statusY = summaryY + minOf(lineLimit, summaryLines.size) * summaryLineHeight + 3
            if (keybind == null || statusY + TEXT_HEIGHT < keybind.y) {
                cardTextFit(graphics, font, status, textX, statusY, cardTextWidth(textWidth), TEXT_WARN)
            }
        }

        if (module.showsKeybindControl()) {
            val keybindBounds = keybind!!
            val binding = ModuleKeybindManager.isBinding(module)
            val unbound = !module.keybind().isBound()
            val keybindBackground = if (binding || keybindHovered) BUTTON_HOVER else if (unbound) GROUP_HEADER else BUTTON_BG
            roundedRectBordered(
                graphics, keybindBounds.x, keybindBounds.y, keybindBounds.w, keybindBounds.h, componentRadius(),
                keybindBackground, keybindBackground, if (binding || keybindHovered) PANEL_HIGHLIGHT else BORDER,
            )
            val label = if (binding) "…" else if (unbound) "none" else module.keybind().displayName()
            moduleCenteredText(
                graphics, font, textFit(font, label, keybindBounds.w - 4),
                keybindBounds.x + keybindBounds.w / 2,
                keybindBounds.y + (keybindBounds.h - TEXT_HEIGHT) / 2,
                if (unbound && !binding) TEXT_SECONDARY else TEXT_PRIMARY,
            )
            if (keybindHovered && module === geiler.addons.client.module.impl.DungeonHelperModule.INSTANCE) {
                graphics.setTooltipForNextFrame(
                    Component.literal("Press this key to open the Dungeon Guide editor."), mouseX, mouseY,
                )
            }
        }
        if (module.showsToggleControl()) toggleSwitch(graphics, toggle.x, toggle.y, toggle.w, toggle.h, togglePosition)
        renderFavoriteHeart(graphics, favorite, ClickGuiState.isFavorite(module), hoverable && favorite.contains(mouseX, mouseY))
        pose.popMatrix()
    }

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

    private fun cardInset(): Int = maxOf(8, Math.round(panelWidth() * CARD_INSET_FRACTION))
    private fun cardGap(): Int = ClickGuiModuleGrid.cardGapForPanelWidth(panelWidth())

    private fun keybindControlWidth(module: Module): Int =
        if (module.keybind().isBound() || ModuleKeybindManager.isBinding(module)) KEYBIND_WIDTH else SET_BIND_WIDTH

    private fun keybindRect(module: Module, card: Rect): Rect {
        val controlWidth = keybindControlWidth(module)
        val scaledWidth = minOf(controlWidth, maxOf(1, Math.round(card.w * 0.18f)))
        val height = maxOf(14, minOf(KEYBIND_HEIGHT, Math.round(card.h * 0.19f)))
        if (module.showsToggleControl()) {
            val toggle = cardSwitchRect(card)
            return Rect(
                toggle.x + (toggle.w - scaledWidth) / 2,
                toggle.y + toggle.h + maxOf(2, Math.round(card.h * 0.035f)), scaledWidth, height,
            )
        }
        val inset = maxOf(8, Math.round(card.w * 0.035f))
        return Rect(card.x + card.w - inset - scaledWidth,
            card.y + card.h - maxOf(4, Math.round(card.h * 0.10f)) - height, scaledWidth, height)
    }

    private fun cardSummaryLines(font: Font, module: Module, summary: String, width: Int): List<FormattedCharSequence> {
        val cached = cardSummaryLayouts[module]
        if (cached != null && cached.font === font && cached.source == summary && cached.width == width) return cached.lines
        val lines = java.util.List.copyOf(font.split(Component.literal(conciseSummary(summary)), width))
        cardSummaryLayouts[module] = CardSummaryLayout(font, summary, width, lines)
        return lines
    }

    private fun cardTextFit(
        graphics: GuiGraphicsExtractor, font: Font, text: String, x: Int, y: Int, availableWidth: Int, color: Int,
    ) {
        if (text.isEmpty() || availableWidth <= 0) return
        val scale = minOf(CARD_TEXT_SCALE, availableWidth / maxOf(1, font.width(text)).toFloat())
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        graphics.text(font, text, 0, 0, color)
        pose.popMatrix()
    }

    private fun cardTitleFit(graphics: GuiGraphicsExtractor, font: Font, text: String, x: Int, y: Int, availableWidth: Int, color: Int) {
        if (text.isEmpty() || availableWidth <= 0) return
        val scale = cardTitleScale(font, text, availableWidth)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        graphics.text(font, text, 0, 0, color)
        pose.popMatrix()
    }

    private fun cardTitleScale(font: Font, text: String, availableWidth: Int): Float =
        minOf(CARD_TITLE_SCALE, availableWidth / maxOf(1, font.width(text)).toFloat())

    private fun cardTextWidth(pixels: Int): Int = maxOf(1, ceil(pixels / CARD_TEXT_SCALE).toInt())

    private fun cardText(graphics: GuiGraphicsExtractor, font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(CARD_TEXT_SCALE, CARD_TEXT_SCALE)
        graphics.text(font, text, 0, 0, color)
        pose.popMatrix()
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

    private fun gridInset(): Int = ClickGuiModuleGrid.gridInsetForPanelWidth(panelWidth())

    private fun gridViewport(x: Int, panelY: Int): Rect {
        val inset = gridInset()
        return Rect(
            x + inset, contentTop(panelY) + inset, maxOf(1, moduleWidth() - inset * 2),
            maxOf(1, panelHeight() - headerHeight() - inset * 2),
        )
    }

    private fun moduleSectionHeaderHeight(): Int = maxOf(16, Math.round(panelHeight() * 0.028f))
    private fun moduleSectionHeaderGap(): Int = maxOf(4, Math.round(panelHeight() * 0.012f))
    private fun moduleSectionGap(): Int = maxOf(8, Math.round(panelHeight() * 0.018f))

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
        val viewport = gridViewport(x, panelY)
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
        val headerSurface = settingsHeaderSurfaceRect(x, y)
        val back = settingsBackRect(x, y)
        val backLabel = settingsBackLabelRect(x, y)
        val backHovered = hoverable && back.contains(mouseX, mouseY)
        val headerColor = if (backHovered) BUTTON_HOVER else GROUP_HEADER
        roundedRectBordered(
            graphics, headerSurface.x, headerSurface.y, headerSurface.w, headerSurface.h,
            componentRadius(), headerColor, headerColor, if (backHovered) PANEL_HIGHLIGHT else BORDER,
        )
        graphics.centeredText(
            font, textFit(font, "Back", maxOf(1, backLabel.w - 8)), backLabel.x + backLabel.w / 2,
            backLabel.y + (backLabel.h - TEXT_HEIGHT) / 2, if (backHovered) TEXT_ON_ACCENT else TEXT_PRIMARY,
        )
        val titleDividerX = backLabel.x + backLabel.w + 8
        val dividerTop = headerSurface.y + 4
        val dividerBottom = headerSurface.y + headerSurface.h - 4
        graphics.fill(titleDividerX, dividerTop, titleDividerX + 1, maxOf(dividerTop + 1, dividerBottom),
            if (backHovered) TEXT_ON_ACCENT else BORDER)
        val titleX = titleDividerX + 9
        val titleWidth = maxOf(0, headerSurface.x + headerSurface.w - 8 - titleX)
        graphics.text(font, textFit(font, module.name(), titleWidth), titleX,
            headerSurface.y + (headerSurface.h - TEXT_HEIGHT) / 2, if (backHovered) TEXT_ON_ACCENT else TEXT_PRIMARY)
        val horizontalDividerY = headerSurface.y + headerSurface.h + 2
        graphics.fill(headerSurface.x + 3, horizontalDividerY, headerSurface.x + headerSurface.w - 3,
            horizontalDividerY + 1, BORDER)
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
        if (!preview.drawPreview(graphics, font, contentX, contentY, contentWidth, contentHeight)) {
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
        val animatedTogglePosition = if (toggle == null || groupToggle == null) 0.0f
            else togglePosition(groupToggle, groupToggle.value(), now, motion)
        ClickGuiScreenRenderBehavior.renderGroupSettingRow(
            graphics, font, groupRow.bounds, toggle, mouseX, mouseY, hoverable, pulse,
            groupRow.group.name!!, groupRow.collapsed, groupRow.depth, animatedTogglePosition,
            MODULE_TEXT_SCALE, TEXT_HEIGHT, GROUP_INDENT, SWITCH_WIDTH, RADIUS_SMALL,
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
            roundedRect(graphics, bounds.x + 9, bounds.y, bounds.w - 18, ROW_HEIGHT - 2, RADIUS_SMALL, highlight)
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
        roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_TRACK, SLIDER_TRACK,
            if (focused) SLIDER_FILL else BORDER)
        val value = if (focused) hexInput else setting.hex()
        val slice = visibleSlice(font, value, if (focused) hexCursor else value.length, maxOf(1, field.w - 8))
        val shown = slice.text
        val textY = field.y + (field.h - TEXT_HEIGHT) / 2
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

    private fun renderNumberField(graphics: GuiGraphicsExtractor, font: Font, numberRow: NumberRow,
        now: Long, motion: ClickGuiMotion) {
        val field = numberRow.field!!
        val focused = numberRow.setting === focusedNumberSetting
        val pulse = interactionPulse(numberRow.setting, now, motion)
        val border = if (focused) SLIDER_FILL else if (pulse > 0.0f) lerpColor(BORDER, PANEL_HIGHLIGHT, pulse) else BORDER
        roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_TRACK, SLIDER_TRACK, border)
        val value = if (focused) numberInput else numberRow.setting.display()
        val slice = visibleSlice(font, value, if (focused) numberCursor else value.length, maxOf(1, field.w - 8))
        val shown = slice.text
        val textY = field.y + (field.h - TEXT_HEIGHT) / 2
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
        val animated = togglePosition(toggleRow.setting, toggleRow.setting.value(), now, motion)
        ClickGuiScreenRenderBehavior.renderToggleSettingRow(graphics, font, toggleRow.bounds, mouseX, mouseY,
            hoverable, pulse, toggleRow.setting.displayName(), animated, MODULE_TEXT_SCALE, TEXT_HEIGHT,
            SWITCH_WIDTH, SWITCH_HEIGHT, RADIUS_SMALL)
    }

    private fun renderChoiceRow(graphics: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int,
        choiceRow: ChoiceRow, hoverable: Boolean, now: Long, motion: ClickGuiMotion) {
        ClickGuiScreenRenderBehavior.renderChoiceSettingRow(graphics, font, choiceRow.bounds, choiceRow.field!!,
            mouseX, mouseY, hoverable, interactionPulse(choiceRow.setting, now, motion),
            choiceRow.setting.displayName(), choiceRow.setting.displayValue(), MODULE_TEXT_SCALE, TEXT_HEIGHT, RADIUS_SMALL)
    }

    private fun renderTextRow(graphics: GuiGraphicsExtractor, font: Font, textRow: TextRow,
        now: Long, motion: ClickGuiMotion) {
        val bounds = textRow.bounds
        val field = textRow.field!!
        val setting = textRow.setting
        val focused = setting === focusedTextSetting
        moduleText(graphics, font, textFit(font, setting.displayName(), moduleTextWidth(bounds.w - field.w - 36)),
            bounds.x + 16, bounds.y + (bounds.h - TEXT_HEIGHT) / 2, TEXT_SECONDARY)
        val pulse = interactionPulse(setting, now, motion)
        val fieldBorder = if (focused) SLIDER_FILL else if (pulse > 0.0f) lerpColor(BORDER, PANEL_HIGHLIGHT, pulse) else BORDER
        roundedRectBordered(graphics, field.x, field.y, field.w, field.h, 3, SLIDER_TRACK, SLIDER_TRACK, fieldBorder)
        val value = if (setting.keybindCapture()) {
            if (focused && focusedTextSetting === setting) "Press a key…" else keybindDisplayName(setting.value())
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
        graphics.text(font, text, field.x + 4, textY, TEXT_PRIMARY)
        if (focused && (System.currentTimeMillis() / CARET_BLINK_MILLIS) % 2 == 0L) {
            val cursorInSlice = clampCursor(text, textCursor - slice.start)
            val caretX = field.x + 4 + font.width(text.substring(0, cursorInSlice))
            graphics.fill(caretX, textY - 1, caretX + 1, textY + TEXT_HEIGHT + 1, TEXT_PRIMARY)
        }
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
            MODULE_TEXT_SCALE, TEXT_HEIGHT, RADIUS_SMALL)
    }

    private fun renderScrollbar(graphics: GuiGraphicsExtractor, viewport: Rect, contentHeight: Int, scroll: Int) {
        if (contentHeight <= viewport.h) return
        val trackX = viewport.x + viewport.w - SCROLLBAR_WIDTH - 3
        val thumbHeight = maxOf(16, viewport.h * viewport.h / contentHeight)
        val travel = viewport.h - thumbHeight
        val maxScroll = contentHeight - viewport.h
        val appliedScroll = maxOf(0, minOf(maxScroll, scroll))
        val thumbY = viewport.y + travel * appliedScroll / maxScroll
        roundedRect(graphics, trackX, thumbY, SCROLLBAR_WIDTH, thumbHeight, SCROLLBAR_WIDTH / 2, SCROLLBAR)
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
        val cached = settingsLayoutCache
        if (cached != null && cached.module === module && cached.rowX == rowX && cached.contentWidth == contentWidth &&
            cached.previewOffset == previewOffset && cached.expandedColor === expandedColor &&
            cached.revision == settingsLayoutRevision && cached.debugEnabled == debugEnabled
        ) return cached
        val rows = ClickGuiSettingsLayout.layoutRows(module, rowX, previewOffset, expandedColor, contentWidth, viewportHeight)
        var contentHeight = previewOffset
        for (row in rows) {
            val bounds = row.getBounds()
            contentHeight = maxOf(contentHeight, bounds.y + bounds.h)
        }
        val updated = SettingsLayoutCache(module, rowX, contentWidth, previewOffset, expandedColor,
            settingsLayoutRevision, debugEnabled, rows, contentHeight)
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
        val maxScroll = maxOf(0, gridContentHeight(selectedCategory, modules) - gridViewport(panelX() + categoryWidth(), panelY()).h)
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

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
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
        if (!left && !right) return super.mouseClicked(event, doubleClick)

        blurTextField()
        if (changelogOpen) {
            if (!changelogPanelRect().contains(mouseX, mouseY)) closeChangelog()
            return true
        }

        val search = searchRect(panelX, panelY)
        if (left && search.contains(mouseX, mouseY)) {
            searchFocused = true
            searchSelectAll = false
            searchCursor = cursorAtMouse(
                Minecraft.getInstance().font, searchQuery, mouseX.toDouble(),
                search.x + searchTextInsetForHeight(search.h), searchTextViewportWidth(search.w, search.h),
                searchCursor, HEADER_TEXT_SCALE,
            )
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
        if (left && githubRect(panelX, panelY).contains(mouseX, mouseY)) {
            ConfirmLinkScreen.confirmLinkNow(this, repositoryUrl())
            return true
        }
        if (left && settingsGearRect(panelX, panelY).contains(mouseX, mouseY)) {
            openGeneralSettings()
            return true
        }
        if (left && themeRect(panelX, panelY).contains(mouseX, mouseY)) {
            openThemeSettings()
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
            requestNavigation(ViewState(selectedCategory, null, gridScroll, 0, null), -1)
            return true
        }

        val clickedEntry = categoryEntryAt(mouseX.toDouble(), mouseY.toDouble(), panelX, panelY)
        if (left && clickedEntry != null) {
            val entries = categoryEntries()
            val fullRow = categoryRowBounds(panelX, panelY)[entries.indexOf(clickedEntry)]
            val visibleRow = Rect(fullRow.x, fullRow.y - categoryScroll, fullRow.w, fullRow.h)
            if (categoryFavoriteRect(visibleRow).contains(mouseX, mouseY)) {
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
            return settingsClicked(settingsModule, mouseX, mouseY, rightX, panelY, left, right)
        }

        for (card in cardRects(selectedCategory, modules, rightX, panelY,
            if (transitionFrom == null) gridScrollVisual else currentView().gridScroll.toDouble())) {
            val bounds = Rect(card.x(), card.y(), card.w(), card.h())
            val module = card.module()
            if (!bounds.contains(mouseX, mouseY)) continue
            if (left && moduleFavoriteRect(module, bounds).contains(mouseX, mouseY)) {
                markInteraction(module)
                playClick()
                ClickGuiState.toggleFavorite(module)
                favoriteStateChanged()
                ModConfig.markDirty()
                return true
            }
            val toggle = cardSwitchRect(bounds)
            if (left && module.showsKeybindControl() && keybindRect(module, bounds).contains(mouseX, mouseY)) {
                markInteraction(module)
                playClick()
                if (ModuleKeybindManager.isBinding(module)) ModuleKeybindManager.cancelBinding()
                else ModuleKeybindManager.beginBinding(module)
                return true
            }
            if (left && module.showsToggleControl() && toggle.contains(mouseX, mouseY)) {
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

    private fun settingsClicked(module: Module, mouseX: Int, mouseY: Int, x: Int, panelY: Int, left: Boolean, right: Boolean): Boolean {
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
                        focusedNumberSetting = row.setting
                        numberInput = row.setting.display()
                        numberCursor = numberInput.length
                        numberSelectAll = true
                        return true
                    }
                    val slider = row.slider
                    if (slider != null && slider.contains(mouseX, mouseY)) {
                        markInteraction(row.setting); playClick()
                        draggingNumberSetting = row.setting
                        applyNumberSlider(row.setting, slider, mouseX)
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
                        textSelectAll = false
                        return true
                    }
                    textCursor = cursorAtMouse(Minecraft.getInstance().font, row.setting.value(), mouseX.toDouble(),
                        field.x + 4, field.w - 8, row.setting.value().length)
                    textSelectAll = false
                    return true
                }
                is ActionRow -> if (row.bounds.contains(mouseX, mouseY)) {
                    markInteraction(row.action); playClick()
                    row.action.onClick.run()
                    invalidateSettingsLayout()
                    return true
                }
                is PreviewRow -> Unit
            }
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (!inputSettled()) return true
        val module = openSettingsModule ?: return super.mouseDragged(event, dragX, dragY)
        val rightX = panelX() + categoryWidth()
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()
        if (draggingPicker != null || draggingNumberSetting != null) {
            for (row in settingsRows(module, rightX, panelY())) {
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

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (!inputSettled()) {
            draggingPicker = null
            draggingNumberSetting = null
            return true
        }
        if (draggingPicker != null || draggingNumberSetting != null) {
            draggingPicker = null
            draggingNumberSetting = null
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
            if (searchSelectAll) {
                setSearchQuery("")
                searchCursor = 0
                searchSelectAll = false
            }
            val inserted = Character.toString(codepoint)
            if (searchQuery.length + inserted.length <= SEARCH_MAX_LENGTH) {
                val edit = ClickGuiTextEditing.insertAtCursor(searchQuery, searchCursor, false, inserted)
                setSearchQuery(edit.text)
                searchCursor = edit.cursor
            }
            return true
        }
        if (Character.isISOControl(codepoint)) {
            return focusedTextSetting != null || focusedNumberSetting != null || focusedHexSetting != null || super.charTyped(event)
        }
        val textSetting = focusedTextSetting
        if (textSetting != null && textSetting.keybindCapture()) return true
        val hexSetting = focusedHexSetting
        if (hexSetting != null) {
            if (Character.digit(codepoint, 16) >= 0 && hexInput.length < 8) {
                val inserted = Character.toString(codepoint).uppercase(Locale.ROOT)
                val edit = ClickGuiTextEditing.insertAtCursor(hexInput, hexCursor, false, inserted)
                hexInput = edit.text
                hexCursor = edit.cursor
                hexSetting.setHex(hexInput)
                ModConfig.markDirty()
            }
            return true
        }
        if (textSetting != null) {
            val inserted = Character.toString(codepoint)
            val edit = ClickGuiTextEditing.insertAtCursor(textSetting.value(), textCursor, textSelectAll, inserted)
            textSetting.setValue(edit.text)
            invalidateSettingsLayout()
            textCursor = minOf(textSetting.value().length, edit.cursor)
            textSelectAll = edit.selectAll
            ModConfig.markDirty()
            return true
        }
        val numberSetting = focusedNumberSetting
        if (numberSetting != null) {
            if (numberSelectAll) {
                numberInput = ""
                numberCursor = 0
                numberSelectAll = false
            }
            val allowed = Character.isDigit(codepoint) || codepoint == '-'.code || codepoint == '+'.code ||
                (!numberSetting.isInteger() && (codepoint == '.'.code || codepoint == ','.code))
            if (allowed && numberInput.length < 16) {
                val inserted = if (codepoint == ','.code) "." else Character.toString(codepoint)
                val edit = ClickGuiTextEditing.insertAtCursor(numberInput, numberCursor, false, inserted)
                numberInput = edit.text
                numberCursor = edit.cursor
            }
            return true
        }
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (changelogOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            closeChangelog()
            return true
        }
        if (searchFocused) {
            if ((event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0 && event.key() == GLFW.GLFW_KEY_A) {
                searchSelectAll = true
                return true
            }
            when (event.key()) {
                in EDIT_KEYS -> {
                    val edit = ClickGuiTextEditing.applyKey(searchQuery, searchCursor, searchSelectAll, textEditKey(event.key()))
                    if (edit.text != searchQuery || (searchSelectAll && isDeleteKey(event.key()))) setSearchQuery(edit.text)
                    searchCursor = edit.cursor
                    searchSelectAll = edit.selectAll
                }
                GLFW.GLFW_KEY_ESCAPE -> {
                    if (searchQuery.isNotEmpty()) { setSearchQuery(""); searchCursor = 0 } else blurTextField()
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField()
                else -> return true
            }
            return true
        }
        val textSetting = focusedTextSetting
        if (textSetting != null && textSetting.keybindCapture()) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                focusedTextSetting = null
                textCursor = 0
                textSelectAll = false
                return true
            }
            val key = if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE)
                InputConstants.UNKNOWN else InputConstants.getKey(event)
            if (key != null) {
                textSetting.setValue(key.name)
                focusedTextSetting = null
                textCursor = 0
                textSelectAll = false
                invalidateSettingsLayout()
                ModConfig.markDirty()
            }
            return true
        }
        val hexSetting = focusedHexSetting
        if (hexSetting != null) {
            when (event.key()) {
                in EDIT_KEYS -> {
                    val edit = ClickGuiTextEditing.applyKey(hexInput, hexCursor, false, textEditKey(event.key()))
                    hexCursor = edit.cursor
                    if (edit.text != hexInput) {
                        hexInput = edit.text
                        hexSetting.setHex(hexInput)
                        ModConfig.markDirty()
                    }
                }
                GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField()
                else -> return super.keyPressed(event)
            }
            return true
        }
        val numberSetting = focusedNumberSetting
        if (numberSetting != null) {
            when (event.key()) {
                in EDIT_KEYS -> {
                    val edit = ClickGuiTextEditing.applyKey(numberInput, numberCursor, numberSelectAll, textEditKey(event.key()))
                    numberInput = edit.text; numberCursor = edit.cursor; numberSelectAll = edit.selectAll
                }
                GLFW.GLFW_KEY_ESCAPE -> {
                    focusedNumberSetting = null
                    numberInput = ""
                    numberSelectAll = false
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField()
                GLFW.GLFW_KEY_A -> if ((event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0) numberSelectAll = true
                else -> return true
            }
            return true
        }
        if (textSetting != null) {
            when (event.key()) {
                in EDIT_KEYS -> {
                    val value = textSetting.value()
                    val edit = ClickGuiTextEditing.applyKey(value, textCursor, textSelectAll, textEditKey(event.key()))
                    if (edit.text != value || (textSelectAll && isDeleteKey(event.key()))) {
                        textSetting.setValue(edit.text)
                        invalidateSettingsLayout()
                        ModConfig.markDirty()
                    }
                    textCursor = edit.cursor
                    textSelectAll = edit.selectAll
                }
                GLFW.GLFW_KEY_A -> if ((event.modifiers() and GLFW.GLFW_MOD_CONTROL) != 0) textSelectAll = true
                GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> blurTextField()
                else -> return super.keyPressed(event)
            }
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (draggingPicker != null) { draggingPicker = null; return true }
            if (draggingNumberSetting != null) { draggingNumberSetting = null; return true }
            if (expandedColorSetting != null) {
                expandedColorSetting = null
                invalidateSettingsLayout()
                persistView()
                return true
            }
            if (openSettingsModule != null) {
                requestNavigation(ViewState(selectedCategory, null, gridScroll, 0, null), -1)
                persistView()
                return true
            }
            return super.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    private fun blurTextField() {
        if (focusedTextSetting == null && focusedNumberSetting == null && focusedHexSetting == null && !searchFocused) return
        commitNumberInput()
        focusedTextSetting = null
        textCursor = 0
        textSelectAll = false
        focusedNumberSetting = null
        numberInput = ""
        numberCursor = 0
        numberSelectAll = false
        focusedHexSetting = null
        hexCursor = 0
        searchFocused = false
        searchCursor = clampCursor(searchQuery, searchCursor)
        searchSelectAll = false
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

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!inputSettled()) return true
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
                val area = gridArea!!
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

    private fun applyNumberSlider(setting: NumberSetting, slider: Rect, mouseX: Int) {
        val fraction = (mouseX - slider.x) / slider.w.toFloat()
        val previous = setting.value()
        setting.setFraction(fraction)
        if (setting.value() != previous) invalidateSettingsLayout()
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

    private fun categoryViewportHeight(panelY: Int): Int {
        val bottomReserve = if (UpdateChecker.bannerText() == null) gridInset() else CATEGORY_NOTICE_HEIGHT + gridInset()
        val bottom = panelY + panelHeight() - bottomReserve
        return maxOf(1, bottom - categoryViewportTop(panelY))
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

    enum class TopBarIcon { GITHUB, SETTINGS, THEME, MOVE_ELEMENTS }
    private enum class PickerPart { SQUARE, HUE, ALPHA }

    companion object {
        private val MOD_LOGO = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/geileraddons-logo.png")
        private const val TOOLBAR_ICON_TEXTURE_SIZE = 256
        private val ICON_GITHUB = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-mark-github-16.png")
        private val ICON_SETTINGS = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-gear-16.png")
        private val ICON_THEME = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-paintbrush-16.png")
        private val ICON_MOVE = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/move-arrows-16.png")
        private val ICON_SEARCH = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-search-16.png")
        private val ICON_FAVORITE_OUTLINE = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/favorite-heart-outline.png")
        private val ICON_FAVORITE_FILLED = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/favorite-heart-filled.png")
        private val ICON_MODULE_PLACEHOLDER = Identifier.fromNamespaceAndPath("minecraft", "textures/item/barrier.png")
        private const val SEARCH_MAX_LENGTH = 64
        private const val SEARCH_TEXT_ICON_GAP = 6
        private const val SEARCH_TEXT_RIGHT_PADDING = 8
        private const val CHANNEL_ROW_HEIGHT = 14
        private const val SCROLLBAR_WIDTH = 3
        private const val SCROLLBAR_GUTTER = SCROLLBAR_WIDTH + 6
        private const val CATEGORY_NOTICE_HEIGHT = 14
        private const val FAVORITE_COLOR = 0xFFFF4B5C.toInt()
        private const val WIP_BADGE_LABEL = "WIP"
        private const val GUI_DIAGNOSTIC_MODULE = "Click GUI"
        private const val GUI_DIAGNOSTIC_EVENT_LIMIT = 32
        private const val VALUE_GUTTER = 36
        private const val LINE_HEIGHT = 9
        private const val TEXT_HEIGHT = 8
        private const val MODULE_TEXT_SCALE = 0.76f
        private const val CARD_TEXT_SCALE = 0.82f
        private const val CATEGORY_TEXT_SCALE = 0.90f
        private const val HEADER_TEXT_SCALE = 0.76f
        private const val CARD_TITLE_SCALE = 1.50f
        private const val CARD_INSET_FRACTION = 0.018f
        private const val SWITCH_WIDTH_FRACTION = 0.12f
        private const val SWITCH_HEIGHT_FRACTION = 0.25f
        private const val KEYBIND_WIDTH = 18
        private const val KEYBIND_HEIGHT = 15
        private const val SET_BIND_WIDTH = 46
        private const val CARET_BLINK_MILLIS = 500L
        private const val REPOSITORY_URL = "https://github.com/dasdadassadwt/geileraddons-26.2"

        @Suppress("UNCHECKED_CAST")
        @JvmStatic fun categoryEntries(categories: List<Category>, expandedCategories: Set<Category>): List<CategoryEntry> =
            ClickGuiScreenCategoryBehavior.categoryEntries(categories, expandedCategories) as List<CategoryEntry>
        @JvmStatic fun categoryEntrySelected(entry: CategoryEntry, selectedCategory: Category, filterId: String?, expanded: Boolean): Boolean =
            ClickGuiScreenCategoryBehavior.categoryEntrySelected(entry, selectedCategory, filterId, expanded)
        @JvmStatic fun categoryEntryLabel(entry: CategoryEntry, filterId: String?, expanded: Boolean): String =
            ClickGuiScreenCategoryBehavior.categoryEntryLabel(entry, filterId, expanded)
        @JvmStatic fun searchTextInsetForHeight(fieldHeight: Int): Int =
            ClickGuiHeaderGeometry.searchIconOffset(fieldHeight) + ClickGuiHeaderGeometry.searchIconSize(fieldHeight) + SEARCH_TEXT_ICON_GAP
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
        @JvmStatic fun cardSwitchRect(card: Rect): Rect {
            val width = cardSwitchWidth(card)
            val height = cardSwitchHeight(card)
            val inset = maxOf(8, Math.round(card.w * 0.035f))
            return Rect(card.x + card.w - inset - width, card.y + Math.round(card.h * 0.40f) - height / 2, width, height)
        }
        @JvmStatic fun cardSwitchWidth(card: Rect): Int = maxOf(28, Math.round(card.w * 0.10f))
        @JvmStatic fun cardSwitchHeight(card: Rect): Int = maxOf(12, Math.round(card.h * 0.21f))
        @JvmStatic fun gridCardContentWidth(viewportWidth: Int): Int = ClickGuiModuleGrid.gridCardContentWidth(viewportWidth, SCROLLBAR_GUTTER)
        @JvmStatic fun cardWidthForGridViewport(viewportWidth: Int, columns: Int, gap: Int): Int =
            ClickGuiModuleGrid.cardWidthForGridViewport(viewportWidth, columns, gap, SCROLLBAR_GUTTER)
        @JvmStatic fun categoryContentHeight(): Int = Category.values().size * ClickGuiSettingsLayout.ROW_HEIGHT
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
        private fun searchIconSize(fieldHeight: Int): Int = ClickGuiHeaderGeometry.searchIconSize(fieldHeight)
        private fun searchIconOffset(fieldHeight: Int): Int = ClickGuiHeaderGeometry.searchIconOffset(fieldHeight)
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
