package geiler.addons.client.gui

import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.config.ModConfig
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.impl.GeneralModule
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.update.ProjectLinks
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

/**
 * The "Classic" Click GUI: one column per category with its modules listed underneath, a search field at the bottom
 * centre and the toolbar buttons beside it. Left click toggles a module (accent colour = on). Right click, or a left
 * click on a module that has no toggle, opens that module's settings in the Modern screen, which returns here.
 */
class ClickGuiClassicScreen : Screen(Component.literal("GeilerAddons")) {
    private val searchField = TextFieldModel(SEARCH_MAX_LENGTH).also { it.reset(ClickGuiState.recalledSearch()) }
    private val query: String get() = searchField.text
    private var searchDragging = false
    private var favoriteRevision = 0L
    private val search = ClickGuiModuleSearch()
    private val scrolls: MutableMap<String, Int> = HashMap()

    /** [key] identifies the column for scrolling; one category can fill more than one column (see [ClickGuiClassicSplit]). */
    private class Column(val key: String, val title: String, val modules: List<Module>, val x: Int, val y: Int, val width: Int, val listHeight: Int)

    private class Entry(val key: String, val title: String, val modules: List<Module>)

    private enum class Button(val tooltip: String) {
        GITHUB("Open GitHub repository"),
        SETTINGS("General settings"),
        THEME("Theme settings"),
        MOVE("Move HUD elements"),
    }

    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        ClickGuiState.rememberSearch(query)
        ModConfig.save()
        super.removed()
    }

    // ---- layout ---------------------------------------------------------------------------------------------

    private fun categories(): List<Category> = ClickGuiScreenCategoryBehavior
        .orderFavoriteCategories(Category.of(ClickGuiState.workspace()).toTypedArray())

    private fun columns(): List<Column> {
        val shown = categories().flatMap { entries(it, search.visibleModules(it, query, favoriteRevision)) }
            .filter { it.modules.isNotEmpty() }
        val plan = ClickGuiClassicLayout.plan(width, height, shown.size)
        return plan.columns.map { slot ->
            val entry = shown[slot.index]
            Column(entry.key, entry.title, entry.modules, slot.x, slot.y, slot.width, ClickGuiClassicLayout.listHeight(entry.modules.size, plan.maxListHeight))
        }
    }

    /** A category's own column, followed by its split-out columns; a search keeps one column per category. */
    private fun entries(category: Category, modules: List<Module>): List<Entry> {
        val groups = if (query.isBlank()) ClickGuiClassicSplit.groups().filter { it.category == category } else emptyList()
        var rest = modules
        val extra = ArrayList<Entry>()
        for (group in groups) {
            val (own, others) = ClickGuiClassicSplit.partition(rest, group.modules) { it.name() }
            extra.add(Entry(category.name + ":" + group.label, group.label, own))
            rest = others
        }
        return listOf(Entry(category.name, category.displayName(), rest)) + extra
    }

    private fun searchRect(): GuiRect = GuiRect((width - SEARCH_WIDTH) / 2, height - 32, SEARCH_WIDTH, BUTTON_SIZE)

    private fun buttonRect(index: Int): GuiRect {
        val search = searchRect()
        return GuiRect(search.x + search.w + 6 + index * (BUTTON_SIZE + 4), search.y, BUTTON_SIZE, BUTTON_SIZE)
    }

    private fun workspaceRect(): GuiRect {
        val last = buttonRect(BUTTON_COUNT - 1)
        return GuiRect(last.x + last.w + 6, last.y, WORKSPACE_BUTTON_WIDTH, BUTTON_SIZE)
    }

    // ---- rendering ------------------------------------------------------------------------------------------

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        graphics.fill(0, 0, width, height, BACKDROP)
        val columns = columns()
        var tooltip: Module? = null
        for (column in columns) {
            clampScroll(column)
            drawColumn(graphics, column, mouseX, mouseY)
            val hovered = rowAt(column, mouseY)
            if (hovered >= 0 && mouseX >= column.x && mouseX < column.x + column.width) tooltip = column.modules[hovered]
        }
        if (columns.isEmpty()) {
            graphics.centeredText(font, "No modules match", width / 2, height / 2 - 4, GuiTheme.TEXT_MUTED)
        }
        drawBottomBar(graphics, mouseX, mouseY)
        if (tooltip != null && tooltip.description().isNotBlank() && searchRect().let { !it.contains(mouseX, mouseY) }) {
            graphics.setTooltipForNextFrame(font, font.split(Component.literal(tooltip.description()), 200), mouseX, mouseY)
        }
    }

    private fun drawColumn(graphics: GuiGraphicsExtractor, column: Column, mouseX: Int, mouseY: Int) {
        val top = column.y + ClickGuiClassicLayout.TITLE_HEIGHT
        GuiTheme.roundedRect(graphics, column.x, column.y, column.width, ClickGuiClassicLayout.TITLE_HEIGHT + column.listHeight, GuiTheme.RADIUS_SMALL, solid(GuiTheme.MODULE_PANEL_BOTTOM))
        val title = Component.literal(column.title).withStyle(ChatFormatting.BOLD)
        graphics.centeredText(font, title, column.x + column.width / 2, column.y + (ClickGuiClassicLayout.TITLE_HEIGHT - font.lineHeight) / 2 + 1, GuiTheme.TEXT_PRIMARY)
        val scroll = scrolls[column.key] ?: 0
        graphics.enableScissor(column.x, top, column.x + column.width, top + column.listHeight)
        val first = scroll / ClickGuiClassicLayout.ROW_HEIGHT
        val last = minOf(column.modules.size, (scroll + column.listHeight) / ClickGuiClassicLayout.ROW_HEIGHT + 1)
        for (index in first until last) {
            val module = column.modules[index]
            val rowY = top + index * ClickGuiClassicLayout.ROW_HEIGHT - scroll
            val hovered = mouseX >= column.x && mouseX < column.x + column.width && rowAt(column, mouseY) == index
            val on = module.showsToggleControl() && module.isEnabled()
            val fill = when {
                on -> solid(GuiTheme.CATEGORY_SELECTED, 0xF0)
                hovered -> solid(GuiTheme.CATEGORY_HOVER, 0xB0)
                else -> 0
            }
            if (fill != 0) graphics.fill(column.x, rowY, column.x + column.width, rowY + ClickGuiClassicLayout.ROW_HEIGHT, fill)
            val label = GuiTheme.fit(font, module.name(), column.width - 8)
            graphics.centeredText(
                font, label, column.x + column.width / 2, rowY + (ClickGuiClassicLayout.ROW_HEIGHT - font.lineHeight) / 2 + 1,
                if (on) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
            )
        }
        graphics.disableScissor()
        val maxScroll = ClickGuiClassicLayout.maxScroll(column.modules.size, column.listHeight)
        if (maxScroll > 0) {
            val barHeight = maxOf(8, column.listHeight * column.listHeight / (column.modules.size * ClickGuiClassicLayout.ROW_HEIGHT))
            val barY = top + (column.listHeight - barHeight) * scroll / maxScroll
            graphics.fill(column.x + column.width - 2, barY, column.x + column.width, barY + barHeight, GuiTheme.SCROLLBAR)
        }
    }

    private fun drawBottomBar(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val box = searchRect()
        GuiTheme.roundedRectBordered(graphics, box.x, box.y, box.w, box.h, 5, solid(GuiTheme.MODULE_PANEL_BOTTOM), solid(GuiTheme.MODULE_PANEL_BOTTOM), GuiTheme.PANEL_HIGHLIGHT)
        val textY = box.y + (box.h - font.lineHeight) / 2 + 1
        if (query.isEmpty()) {
            graphics.centeredText(font, "Search...", box.x + box.w / 2, textY, GuiTheme.TEXT_MUTED)
        } else {
            TextFieldInput.draw(graphics, font, searchField, box.x + 8, textY, box.w - 16,
                GuiTheme.TEXT_PRIMARY, GuiTheme.CATEGORY_SELECTED or (0xFF shl 24), true)
        }
        for (button in Button.values()) {
            val rect = buttonRect(button.ordinal)
            val hovered = rect.contains(mouseX, mouseY)
            val surface = if (hovered) GuiTheme.BUTTON_HOVER else solid(GuiTheme.MODULE_PANEL_BOTTOM)
            GuiTheme.roundedRectBordered(graphics, rect.x, rect.y, rect.w, rect.h, 5, surface, surface, if (hovered) GuiTheme.PANEL_HIGHLIGHT else GuiTheme.BORDER)
            val iconSize = BUTTON_SIZE - 6
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate((rect.x + 3).toFloat(), (rect.y + 3).toFloat())
            pose.scale(iconSize / ICON_TEXTURE_SIZE.toFloat(), iconSize / ICON_TEXTURE_SIZE.toFloat())
            graphics.blit(
                RenderPipelines.GUI_TEXTURED, icon(button), 0, 0, 0.0f, 0.0f,
                ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE, ICON_TEXTURE_SIZE,
                if (hovered) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
            )
            pose.popMatrix()
            if (hovered) graphics.setTooltipForNextFrame(Component.literal(button.tooltip), mouseX, mouseY)
        }
        val workspace = workspaceRect()
        val workspaceHovered = workspace.contains(mouseX, mouseY)
        val surface = if (workspaceHovered) GuiTheme.BUTTON_HOVER else solid(GuiTheme.MODULE_PANEL_BOTTOM)
        GuiTheme.roundedRectBordered(graphics, workspace.x, workspace.y, workspace.w, workspace.h, 5, surface, surface, if (workspaceHovered) GuiTheme.PANEL_HIGHLIGHT else GuiTheme.BORDER)
        graphics.centeredText(
            font, ClickGuiState.workspace().switchLabel(), workspace.x + workspace.w / 2,
            workspace.y + (workspace.h - font.lineHeight) / 2 + 1, if (workspaceHovered) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
        )
    }

    // ---- input ----------------------------------------------------------------------------------------------

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()
        val left = event.button() == 0
        val right = event.button() == 1
        if (!left && !right) return super.mouseClicked(event, doubleClick)
        if (left) {
            for (button in Button.values()) {
                if (buttonRect(button.ordinal).contains(mouseX, mouseY)) {
                    playClick()
                    press(button)
                    return true
                }
            }
            if (workspaceRect().contains(mouseX, mouseY)) {
                playClick()
                ClickGuiState.setWorkspace(ClickGuiState.workspace().otherWorkspace())
                searchField.reset("")
                ModConfig.markDirty()
                return true
            }
            val box = searchRect()
            if (box.contains(mouseX, mouseY)) {
                TextFieldInput.mousePressed(searchField, font, event.x(), event.y(), box.x + 8, box.w - 16, minecraft.hasShiftDown())
                searchDragging = true
                return true
            }
        }
        for (column in columns()) {
            if (mouseX < column.x || mouseX >= column.x + column.width) continue
            val row = rowAt(column, mouseY)
            if (row < 0) continue
            val module = column.modules[row]
            playClick()
            if (left && module.showsToggleControl()) {
                module.toggle()
                ModConfig.markDirty()
            } else if (module.hasSettings()) {
                openSettings(module)
            }
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun press(button: Button) {
        when (button) {
            Button.GITHUB -> ConfirmLinkScreen.confirmLinkNow(this, repositoryUrl())
            Button.SETTINGS -> openSettings(GeneralModule.INSTANCE)
            Button.THEME -> openSettings(VisualModule.INSTANCE)
            Button.MOVE -> minecraft.gui.setScreen(MoveUiScreen(this))
        }
    }

    /** Settings live in the Modern screen; it is told to come back here instead of showing its module grid. */
    private fun openSettings(module: Module) {
        ClickGuiState.setCategory(module.category())
        ClickGuiState.setOpenModule(module)
        ClickGuiState.setExpandedColor(null)
        ClickGuiState.setSettingsScroll(0)
        ModConfig.markDirty()
        minecraft.gui.setScreen(ClickGuiScreen(true))
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        for (column in columns()) {
            if (mouseX < column.x || mouseX >= column.x + column.width) continue
            if (mouseY < column.y || mouseY >= column.y + ClickGuiClassicLayout.TITLE_HEIGHT + column.listHeight) continue
            val max = ClickGuiClassicLayout.maxScroll(column.modules.size, column.listHeight)
            val step = if (scrollY > 0) -1 else 1
            scrolls[column.key] = ((scrolls[column.key] ?: 0) + step * ClickGuiClassicLayout.ROW_HEIGHT * 2).coerceIn(0, max)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (!searchDragging || event.button() != 0) return super.mouseDragged(event, dragX, dragY)
        val box = searchRect()
        TextFieldInput.mouseDragged(searchField, font, event.x(), box.x + 8, box.w - 16)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (searchDragging && event.button() == 0) {
            searchDragging = false
            return true
        }
        return super.mouseReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (TextFieldInput.handleChar(searchField, event, "classic-search") == TextFieldInput.Outcome.CHANGED) scrolls.clear()
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val outcome = TextFieldInput.handleKey(searchField, event, "classic-search")
        if (outcome != TextFieldInput.Outcome.IGNORED) {
            if (outcome == TextFieldInput.Outcome.CHANGED) scrolls.clear()
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (query.isNotEmpty()) {
                searchField.replaceAll("")
                scrolls.clear()
            } else onClose()
            return true
        }
        return super.keyPressed(event)
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private fun rowAt(column: Column, mouseY: Int): Int = ClickGuiClassicLayout.rowAt(
        mouseY, column.y + ClickGuiClassicLayout.TITLE_HEIGHT, scrolls[column.key] ?: 0, column.modules.size, column.listHeight,
    )

    private fun clampScroll(column: Column) {
        val max = ClickGuiClassicLayout.maxScroll(column.modules.size, column.listHeight)
        val current = scrolls[column.key] ?: return
        if (current > max) scrolls[column.key] = max
    }

    private fun playClick() {
        minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    private fun repositoryUrl(): String = FabricLoader.getInstance().getModContainer("geileraddons")
        .flatMap { it.metadata.contact.get("sources") }
        .orElse(ProjectLinks.GITHUB_URL)

    /** The theme's translucent surface colours with enough opacity to read text on the blurred world. */
    private fun solid(color: Int, alpha: Int = SURFACE_ALPHA): Int = (alpha shl 24) or (color and 0x00FFFFFF)

    private fun icon(button: Button): Identifier = when (button) {
        Button.GITHUB -> ICON_GITHUB
        Button.SETTINGS -> ICON_SETTINGS
        Button.THEME -> ICON_THEME
        Button.MOVE -> ICON_MOVE
    }

    private companion object {
        const val SEARCH_WIDTH = 190
        const val SEARCH_MAX_LENGTH = 48
        const val BUTTON_SIZE = 22
        const val BUTTON_COUNT = 4
        const val WORKSPACE_BUTTON_WIDTH = 56
        const val SURFACE_ALPHA = 0xE6
        const val BACKDROP = 0x88000000.toInt()
        const val CARET_BLINK_MILLIS = 500L
        const val ICON_TEXTURE_SIZE = ClickGuiScreenRenderBehavior.TOOLBAR_ICON_TEXTURE_SIZE
        val ICON_GITHUB: Identifier = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-mark-github-16.png")
        val ICON_SETTINGS: Identifier = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-gear-16.png")
        val ICON_THEME: Identifier = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-paintbrush-16.png")
        val ICON_MOVE: Identifier = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/move-arrows-16.png")
    }
}
