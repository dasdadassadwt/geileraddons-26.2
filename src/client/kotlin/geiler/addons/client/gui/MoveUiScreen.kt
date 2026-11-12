package geiler.addons.client.gui

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.hud.HudElement
import geiler.addons.client.hud.HudManager
import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.LinkedHashMap

/** Drag-to-place editor for the mod's HUD panels. */
open class MoveUiScreen(private val parent: Screen?) : Screen(Component.literal("Move Elements")) {
    private var dragging: HudElement? = null
    private var selectedCategory: Category? = null
    private var categoryPopupOpen = false
    private var categoryScrollOffset = 0
    private var diagnosticEventTick = Long.MIN_VALUE
    private var diagnosticEventsThisTick = 0
    private var suppressedDiagnosticEvents = 0

    /** Where inside the panel it was grabbed, so it doesn't jump to the cursor on the first move. */
    private var grabX = 0
    private var grabY = 0

    override fun isPauseScreen(): Boolean = false

    protected override fun init() {
        super.init()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, "Move Elements", true)
        GeilerAddonsLog.write(
            Category.MISCELLANEOUS, "Move Elements", 0,
            "STATUS action=open,elements=${HudManager.elements().size}," +
                "categories=${HudManager.categories().size}," +
                "selector=${if (categoryPopupOpen) "open" else "closed"},selected=${selectedCategoryName()}," +
                "viewport=${width}x$height,previewModes=${previewModesSummary()}",
        )
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        VisualModule.INSTANCE.refreshTheme()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val currentFont = font
        val elements = visibleElements()
        if (elements.isEmpty()) {
            graphics.centeredText(
                currentFont, "No movable elements in this category.", width / 2, height / 2 - 4,
                GuiTheme.TEXT_MUTED,
            )
        }

        // Keep the larger selection outline inside the viewport even when HUD positions are clamped.
        graphics.enableScissor(0, 0, width, height)
        for (element in elements) {
            val x = HudManager.previewX(element, currentFont, width)
            val y = HudManager.previewY(element, currentFont, height)
            val w = HudManager.scaledPreviewWidth(element, currentFont)
            val h = HudManager.scaledPreviewHeight(element, currentFont)
            val hovered = dragging === element ||
                (dragging == null && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h)
            if (hovered) {
                GuiTheme.roundedRectOutline(
                    graphics, x - OUTLINE_PADDING, y - OUTLINE_PADDING,
                    w + OUTLINE_PADDING * 2, h + OUTLINE_PADDING * 2,
                    GuiTheme.RADIUS_SMALL, GuiTheme.CARD_BORDER_ENABLED,
                )
            }
            HudManager.renderElement(element, graphics, currentFont, x, y, true)
        }
        graphics.disableScissor()
        renderCategorySelector(graphics, mouseX, mouseY)
        graphics.centeredText(
            currentFont, "Drag to move; scroll panels to resize or the selector list to browse.",
            width / 2, 8, GuiTheme.TEXT_SECONDARY,
        )
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()
        val selector = categorySelectorLayout()
        if (event.button() != 0) {
            if (categoryPopupOpen && !selector.containsButton(mouseX, mouseY) && !selector.containsPopup(mouseX, mouseY)) {
                categoryPopupOpen = false
                logInteraction("CATEGORY_MENU state=closed,reason=outside,selected=${selectedCategoryName()}")
            }
            return super.mouseClicked(event, doubleClick)
        }
        if (selector.containsButton(mouseX, mouseY)) {
            setCategoryPopupOpen(!categoryPopupOpen, "button")
            return true
        }
        if (categoryPopupOpen) {
            val row = selector.rowAt(mouseX, mouseY)
            if (row >= 0) {
                val option = selector.firstVisibleRow + row
                if (option < selector.totalRows) {
                    selectedCategory = if (option == 0) null else HudManager.categories()[option - 1]
                    categoryPopupOpen = false
                    logInteraction("CATEGORY selected=${selectedCategoryName()},selector=closed")
                    return true
                }
            }
            if (selector.containsPopup(mouseX, mouseY)) return true
            categoryPopupOpen = false
            logInteraction("CATEGORY_MENU state=closed,reason=outside,selected=${selectedCategoryName()}")
        }
        val currentFont = font

        // Traverse back to front so the topmost panel receives the drag.
        val elements = visibleElements()
        for (index in elements.indices.reversed()) {
            val element = elements[index]
            val x = HudManager.previewX(element, currentFont, width)
            val y = HudManager.previewY(element, currentFont, height)
            if (mouseX < x || mouseX >= x + HudManager.scaledPreviewWidth(element, currentFont) ||
                mouseY < y || mouseY >= y + HudManager.scaledPreviewHeight(element, currentFont)
            ) continue
            dragging = element
            grabX = mouseX - x
            grabY = mouseY - y
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val element = dragging ?: return super.mouseDragged(event, dragX, dragY)
        HudManager.setPreviewPosition(element, event.x().toInt() - grabX, event.y().toInt() - grabY, font, width, height)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val element = dragging ?: return super.mouseReleased(event)
        logInteraction("MOVE element=${element.id()},category=${HudManager.category(element)}," +
            "pointer=${event.x().toInt()},${event.y().toInt()}")
        dragging = null
        ModConfig.markDirty()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val selector = categorySelectorLayout()
        if (selector.containsButton(mouseX.toInt(), mouseY.toInt())) return true
        if (categoryPopupOpen && selector.containsPopup(mouseX.toInt(), mouseY.toInt())) {
            val next = maxOf(0, minOf(selector.maxScrollRows, categoryScrollOffset + (if (scrollY > 0) -1 else 1)))
            if (next != categoryScrollOffset) {
                categoryScrollOffset = next
                logInteraction("CATEGORY_SCROLL offset=$categoryScrollOffset,visibleRows=${selector.visibleRows}")
            }
            return true
        }
        val currentFont = font
        val elements = visibleElements()
        for (index in elements.indices.reversed()) {
            val element = elements[index]
            val x = HudManager.previewX(element, currentFont, width)
            val y = HudManager.previewY(element, currentFont, height)
            if (mouseX < x || mouseX >= x + HudManager.scaledPreviewWidth(element, currentFont) ||
                mouseY < y || mouseY >= y + HudManager.scaledPreviewHeight(element, currentFont)
            ) continue
            HudManager.setPreviewScalePreservingTopLeft(
                element, HudManager.scale(element) + (if (scrollY > 0) 0.1f else -0.1f),
                currentFont, width, height,
            )
            logInteraction("RESIZE element=${element.id()},category=${HudManager.category(element)},scale=${HudManager.scale(element)}")
            ModConfig.markDirty()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    private fun visibleElements(): List<HudElement> = HudManager.elements().filter { element ->
        selectedCategory == null || HudManager.category(element) == selectedCategory
    }

    private fun setCategoryPopupOpen(open: Boolean, reason: String) {
        if (categoryPopupOpen == open) return
        categoryPopupOpen = open
        if (open) {
            var selector = categorySelectorLayout()
            val selectedRow = if (selectedCategory == null) 0 else HudManager.categories().indexOf(selectedCategory) + 1
            if (selectedRow < categoryScrollOffset) {
                categoryScrollOffset = selectedRow
            } else if (selectedRow >= categoryScrollOffset + selector.visibleRows) {
                categoryScrollOffset = maxOf(0, selectedRow - maxOf(0, selector.visibleRows - 1))
            }
            selector = categorySelectorLayout()
            logInteraction(
                "CATEGORY_MENU state=open,reason=$reason,selected=${selectedCategoryName()}," +
                    "firstRow=${selector.firstVisibleRow},visibleRows=${selector.visibleRows}",
            )
        } else {
            logInteraction("CATEGORY_MENU state=closed,reason=$reason,selected=${selectedCategoryName()}")
        }
    }

    private fun renderCategorySelector(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val selector = categorySelectorLayout()
        val categories = HudManager.categories()
        val buttonLabel = selectedCategoryName()
        val buttonHovered = selector.containsButton(mouseX, mouseY)
        val buttonBackground = if (categoryPopupOpen || buttonHovered) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG
        GuiTheme.roundedRectBordered(
            graphics, selector.buttonX, selector.buttonY, selector.buttonWidth, SELECTOR_HEIGHT,
            GuiTheme.RADIUS_SMALL, buttonBackground, buttonBackground,
            if (categoryPopupOpen || buttonHovered) GuiTheme.CARD_BORDER_ENABLED else GuiTheme.CARD_BORDER,
        )
        graphics.text(font, fit(buttonLabel, selector.buttonWidth - 31), selector.buttonX + 9, selector.buttonY + 6,
            GuiTheme.TEXT_PRIMARY)
        graphics.text(font, if (categoryPopupOpen) "▴" else "▾", selector.buttonX + selector.buttonWidth - 14,
            selector.buttonY + 6, GuiTheme.TEXT_SECONDARY)
        if (!categoryPopupOpen || selector.visibleRows == 0) return

        graphics.enableScissor(
            selector.popupX, selector.popupY,
            selector.popupX + selector.popupWidth, selector.popupY + selector.popupHeight,
        )
        GuiTheme.roundedRect(
            graphics, selector.popupX, selector.popupY, selector.popupWidth, selector.popupHeight,
            GuiTheme.RADIUS_SMALL, GuiTheme.CARD_BG, GuiTheme.CARD_BG,
        )
        for (visibleRow in 0 until selector.visibleRows) {
            val optionIndex = selector.firstVisibleRow + visibleRow
            if (optionIndex >= selector.totalRows) break
            val top = selector.popupY + visibleRow * POPUP_ROW_PITCH
            val selected = if (optionIndex == 0) selectedCategory == null
            else selectedCategory === categories[optionIndex - 1]
            val hovered = selector.rowAt(mouseX, mouseY) == visibleRow
            val rowBackground = if (selected || hovered) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG
            GuiTheme.roundedRectBordered(
                graphics, selector.popupX, top, selector.popupWidth, POPUP_ROW_HEIGHT,
                GuiTheme.RADIUS_SMALL, rowBackground, rowBackground,
                if (selected || hovered) GuiTheme.CARD_BORDER_ENABLED else GuiTheme.CARD_BORDER,
            )
            graphics.text(
                font, fit(optionLabel(optionIndex), selector.popupWidth - 16), selector.popupX + 8, top + 5,
                if (selected) GuiTheme.TEXT_PRIMARY else GuiTheme.TEXT_SECONDARY,
            )
        }
        graphics.disableScissor()
    }

    private fun categorySelectorLayout(): CategorySelectorLayout {
        val categories = HudManager.categories()
        val totalRows = categories.size + 1
        val maxWidth = maxOf(1, width - SELECTOR_SIDE_MARGIN * 2)
        var widestLabel = font.width(selectedCategoryName())
        for (category in categories) widestLabel = maxOf(widestLabel, font.width(category.displayName()))
        val buttonWidth = minOf(maxWidth, maxOf(88, font.width(selectedCategoryName()) + 33))
        val popupWidth = minOf(maxWidth, maxOf(buttonWidth, widestLabel + 20))
        val buttonX = clamp((width - buttonWidth) / 2, 0, maxOf(0, width - buttonWidth))
        val buttonY = clamp(height - SELECTOR_BOTTOM_MARGIN - SELECTOR_HEIGHT, 0, maxOf(0, height - SELECTOR_HEIGHT))
        val availablePopupHeight = maxOf(0, buttonY - POPUP_GAP - POPUP_TOP_MARGIN)
        val visibleRows = if (availablePopupHeight < POPUP_ROW_HEIGHT) 0
        else minOf(totalRows, 1 + (availablePopupHeight - POPUP_ROW_HEIGHT) / POPUP_ROW_PITCH)
        val popupHeight = if (visibleRows == 0) 0 else (visibleRows - 1) * POPUP_ROW_PITCH + POPUP_ROW_HEIGHT
        val popupX = clamp((width - popupWidth) / 2, 0, maxOf(0, width - popupWidth))
        val popupY = buttonY - POPUP_GAP - popupHeight
        val maxScrollRows = maxOf(0, totalRows - visibleRows)
        categoryScrollOffset = maxOf(0, minOf(categoryScrollOffset, maxScrollRows))
        return CategorySelectorLayout(
            buttonX, buttonY, buttonWidth, popupX, popupY, popupWidth, popupHeight,
            totalRows, visibleRows, categoryScrollOffset, maxScrollRows,
        )
    }

    private fun selectedCategoryName(): String = selectedCategory?.displayName() ?: "All"

    private fun optionLabel(optionIndex: Int): String =
        if (optionIndex == 0) "All" else HudManager.categories()[optionIndex - 1].displayName()

    private fun fit(text: String, maxWidth: Int): String = GuiTheme.fit(font, text, maxWidth)

    private fun previewModesSummary(): String {
        val modes = LinkedHashMap<String, Int>()
        for (element in HudManager.elements()) {
            val mode = element.previewMode()
            modes[mode] = (modes[mode] ?: 0) + 1
        }
        if (modes.isEmpty()) return "none"
        return modes.entries.joinToString("|") { entry -> "${entry.key}:${entry.value}" }
    }

    private fun logInteraction(event: String) {
        val tick = minecraft.level?.gameTime ?: 0L
        if (diagnosticEventTick != tick) {
            diagnosticEventTick = tick
            diagnosticEventsThisTick = 0
        }
        if (diagnosticEventsThisTick >= 16) {
            suppressedDiagnosticEvents++
            return
        }
        diagnosticEventsThisTick++
        GeilerAddonsLog.write(
            Category.MISCELLANEOUS, "Move Elements", tick,
            "$event,suppressedEvents=$suppressedDiagnosticEvents",
        )
        suppressedDiagnosticEvents = 0
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun removed() {
        GeilerAddonsLog.write(
            Category.MISCELLANEOUS, "Move Elements", 0,
            "STATUS action=close,selector=${if (categoryPopupOpen) "open" else "closed"}," +
                "selected=${selectedCategoryName()},suppressedEvents=$suppressedDiagnosticEvents",
        )
        if (suppressedDiagnosticEvents > 0) {
            GeilerAddonsLog.write(
                Category.MISCELLANEOUS, "Move Elements", 0,
                "EVENTS_SUPPRESSED count=$suppressedDiagnosticEvents",
            )
        }
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, "Move Elements", false)
        super.removed()
    }

    private data class CategorySelectorLayout(
        val buttonX: Int,
        val buttonY: Int,
        val buttonWidth: Int,
        val popupX: Int,
        val popupY: Int,
        val popupWidth: Int,
        val popupHeight: Int,
        val totalRows: Int,
        val visibleRows: Int,
        val firstVisibleRow: Int,
        val maxScrollRows: Int,
    ) {
        fun containsButton(x: Int, y: Int): Boolean =
            x >= buttonX && x < buttonX + buttonWidth && y >= buttonY && y < buttonY + SELECTOR_HEIGHT

        fun containsPopup(x: Int, y: Int): Boolean = visibleRows > 0 && x >= popupX && x < popupX + popupWidth &&
            y >= popupY && y < popupY + popupHeight

        fun rowAt(x: Int, y: Int): Int {
            if (!containsPopup(x, y)) return -1
            val row = (y - popupY) / POPUP_ROW_PITCH
            return if (row < visibleRows && (y - popupY) % POPUP_ROW_PITCH < POPUP_ROW_HEIGHT) row else -1
        }
    }

    companion object {
        private const val OUTLINE_PADDING = 2
        private const val SELECTOR_HEIGHT = 22
        private const val SELECTOR_BOTTOM_MARGIN = 8
        private const val SELECTOR_SIDE_MARGIN = 8
        private const val POPUP_GAP = 5
        private const val POPUP_ROW_HEIGHT = 19
        private const val POPUP_ROW_PITCH = 21
        private const val POPUP_TOP_MARGIN = 28

        private fun clamp(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(max, value))
    }
}
