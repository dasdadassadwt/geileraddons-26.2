package geiler.addons.client.gui

import geiler.addons.client.config.ModConfig
import geiler.addons.client.config.TikiCoords
import geiler.addons.client.module.impl.TikiHelperModule
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.lwjgl.glfw.GLFW

/** Constructor-free parser shared by the screen and its offline compatibility checks. */
internal object TikiCoordinateParser {
    @JvmStatic
    fun parse(value: String?): Int? = value?.trim { it.code <= 0x20 }?.toIntOrNull()
}

/** Scrollable Tiki waypoint list with delete and add-from-crosshair controls. */
open class TikiCoordManagerScreen(private val parent: Screen?) : Screen(Component.literal("Tiki Coordinates")) {
    private var scroll = 0
    private var addMode = false
    private var error: String? = null

    // Keep editor contents outside widgets so resizing and init() do not discard typed values.
    private var pendingX = "0"
    private var pendingY = "0"
    private var pendingZ = "0"

    private var xField: EditBox? = null
    private var yField: EditBox? = null
    private var zField: EditBox? = null

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        if (!addMode) return
        val dialog = dialogRect()
        val fieldWidth = fieldWidth()
        val fieldX = dialog.x + (dialog.w - (fieldWidth * 3 + FIELD_GAP * 2)) / 2
        val fieldY = dialog.y + 34
        val x = addField(fieldX, fieldY, pendingX, "X") { pendingX = it }
        xField = x
        yField = addField(fieldX + fieldWidth + FIELD_GAP, fieldY, pendingY, "Y") { pendingY = it }
        zField = addField(fieldX + 2 * (fieldWidth + FIELD_GAP), fieldY, pendingZ, "Z") { pendingZ = it }
        setInitialFocus(x)
    }

    private fun addField(x: Int, y: Int, value: String, label: String, responder: (String) -> Unit): EditBox {
        val box = EditBox(font, x, y, fieldWidth(), FIELD_HEIGHT, Component.literal(label))
        box.setMaxLength(8)
        box.setValue(value)
        box.setResponder(responder)
        return addRenderableWidget(box)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        renderList(graphics, mouseX, mouseY)
        if (addMode) renderAddDialog(graphics, mouseX, mouseY)
        // Extract text widgets last so they stay above the dialog surface and share its clipping.
        if (addMode) {
            val dialog = dialogRect()
            graphics.enableScissor(dialog.x, dialog.y, dialog.x + dialog.w, dialog.y + dialog.h)
            super.extractRenderState(graphics, mouseX, mouseY, partialTick)
            graphics.disableScissor()
        } else {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        }
    }

    private fun renderList(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val x = panelX()
        val y = panelY()
        val panelWidth = panelWidth()
        val panelHeight = panelHeight()
        val hoverable = !addMode
        graphics.enableScissor(x, y, x + panelWidth, y + panelHeight)
        GuiTheme.roundedRectBordered(graphics, x, y, panelWidth, panelHeight, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)

        val coords = TikiCoords.all()
        graphics.text(font, "Tiki Coordinates", x + PADDING + 4, y + 9, GuiTheme.TEXT_PRIMARY)
        val count = coords.size.toString() + if (coords.size == 1) " entry" else " entries"
        graphics.text(font, count, x + panelWidth - PADDING - 4 - font.width(count), y + 9, GuiTheme.TEXT_MUTED)
        graphics.fill(x + PADDING, y + HEADER_HEIGHT - 3, x + panelWidth - PADDING, y + HEADER_HEIGHT - 2, GuiTheme.BORDER)

        val viewport = listViewport()
        clampScroll()
        graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h)
        if (coords.isEmpty()) graphics.text(font, "No coordinates yet.", viewport.x + 10, viewport.y + 6, GuiTheme.TEXT_MUTED)
        for (index in coords.indices) {
            val row = rowRect(index)
            val rowHovered = hoverable && row.contains(mouseX.toDouble(), mouseY.toDouble()) &&
                viewport.contains(mouseX.toDouble(), mouseY.toDouble())
            if (rowHovered) GuiTheme.roundedRect(graphics, row.x, row.y, row.w, row.h - 2, 3,
                GuiTheme.CATEGORY_HOVER, GuiTheme.CATEGORY_HOVER)
            val pos = coords[index]
            graphics.text(font, "${index + 1}.", row.x + 6, row.y + 5, GuiTheme.TEXT_MUTED)
            graphics.text(font, "${pos.x}, ${pos.y}, ${pos.z}", row.x + 28, row.y + 5, GuiTheme.TEXT_SECONDARY)

            val delete = deleteRect(row)
            val deleteHovered = hoverable && delete.contains(mouseX.toDouble(), mouseY.toDouble()) &&
                viewport.contains(mouseX.toDouble(), mouseY.toDouble())
            val deleteBackground = if (deleteHovered) GuiTheme.DANGER_HOVER else GuiTheme.DANGER_BG
            GuiTheme.roundedRect(graphics, delete.x, delete.y, delete.w, delete.h, 3, deleteBackground, deleteBackground)
            graphics.centeredText(font, "X", delete.x + delete.w / 2, delete.y + 3, GuiTheme.TEXT_PRIMARY)
        }
        graphics.disableScissor()

        renderScrollbar(graphics, viewport, coords.size * ROW_HEIGHT)
        button(graphics, font, addButtonRect(), "Add Coordinate", mouseX, mouseY, hoverable, GuiTheme.BUTTON_BG, GuiTheme.BUTTON_HOVER)
        button(graphics, font, doneButtonRect(), "Done", mouseX, mouseY, hoverable, GuiTheme.BUTTON_BG, GuiTheme.BUTTON_HOVER)
        graphics.disableScissor()
    }

    private fun renderAddDialog(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val dialog = dialogRect()
        graphics.enableScissor(dialog.x, dialog.y, dialog.x + dialog.w, dialog.y + dialog.h)
        GuiTheme.roundedRectBordered(graphics, dialog.x, dialog.y, dialog.w, dialog.h, GuiTheme.RADIUS,
            GuiTheme.MODULE_PANEL_TOP, GuiTheme.MODULE_PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.centeredText(font, "Add Coordinate", dialog.x + dialog.w / 2, dialog.y + 9, GuiTheme.TEXT_PRIMARY)

        val fieldWidth = fieldWidth()
        val fieldX = dialog.x + (dialog.w - (fieldWidth * 3 + FIELD_GAP * 2)) / 2
        val labels = arrayOf("X", "Y", "Z")
        for (index in labels.indices) {
            graphics.centeredText(font, labels[index], fieldX + index * (fieldWidth + FIELD_GAP) + fieldWidth / 2,
                dialog.y + 24, GuiTheme.TEXT_MUTED)
        }
        error?.let { graphics.centeredText(font, it, dialog.x + dialog.w / 2, dialog.y + 56, GuiTheme.TEXT_ERROR) }
        button(graphics, font, confirmButtonRect(), "Confirm", mouseX, mouseY, true, GuiTheme.BUTTON_BG, GuiTheme.BUTTON_HOVER)
        button(graphics, font, cancelButtonRect(), "Cancel", mouseX, mouseY, true, GuiTheme.DANGER_BG, GuiTheme.DANGER_HOVER)
        graphics.disableScissor()
    }

    private fun button(
        graphics: GuiGraphicsExtractor,
        font: Font,
        rect: GuiRect,
        label: String,
        mouseX: Int,
        mouseY: Int,
        hoverable: Boolean,
        background: Int,
        hoverBackground: Int,
    ) {
        val hovered = hoverable && rect.contains(mouseX.toDouble(), mouseY.toDouble())
        val color = if (hovered) hoverBackground else background
        GuiTheme.roundedRect(graphics, rect.x, rect.y, rect.w, rect.h, 4, color, color)
        graphics.centeredText(font, label, rect.x + rect.w / 2, rect.y + (rect.h - 8) / 2,
            if (hovered) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY)
    }

    private fun renderScrollbar(graphics: GuiGraphicsExtractor, viewport: GuiRect, contentHeight: Int) {
        if (contentHeight <= viewport.h) return
        val trackX = viewport.x + viewport.w - SCROLLBAR_WIDTH
        val thumbHeight = maxOf(16, viewport.h * viewport.h / contentHeight)
        val maxScroll = contentHeight - viewport.h
        val thumbY = viewport.y + (viewport.h - thumbHeight) * scroll.coerceIn(0, maxScroll) / maxScroll
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, GuiTheme.SCROLLBAR)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (addMode) {
            // Text widgets claim focus first; dialog controls get the remaining left clicks.
            if (super.mouseClicked(event, doubleClick)) return true
            if (confirmButtonRect().contains(event.x(), event.y())) confirmAdd()
            else if (cancelButtonRect().contains(event.x(), event.y())) exitAddMode()
            return true
        }
        if (event.button() != 0) return super.mouseClicked(event, doubleClick)
        if (addButtonRect().contains(event.x(), event.y())) {
            enterAddMode()
            return true
        }
        if (doneButtonRect().contains(event.x(), event.y())) {
            onClose()
            return true
        }

        val viewport = listViewport()
        if (viewport.contains(event.x(), event.y())) {
            for (index in 0 until TikiCoords.size()) {
                val row = rowRect(index)
                if (!row.contains(event.x(), event.y())) continue
                if (deleteRect(row).contains(event.x(), event.y())) {
                    TikiCoords.remove(index)
                    ModConfig.markDirty()
                    TikiHelperModule.INSTANCE.requestScan()
                }
                return true
            }
            return true
        }
        if (panelRect().contains(event.x(), event.y())) return true
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (!addMode && listViewport().contains(mouseX, mouseY)) {
            scroll -= Math.round(scrollY * ROW_HEIGHT).toInt()
            clampScroll()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (addMode) {
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE -> {
                    exitAddMode()
                    return true
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    confirmAdd()
                    return true
                }
            }
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private fun enterAddMode() {
        val looking = lookedAtBlock()
        pendingX = looking.x.toString()
        pendingY = looking.y.toString()
        pendingZ = looking.z.toString()
        error = null
        addMode = true
        rebuildWidgets()
    }

    private fun exitAddMode() {
        addMode = false
        error = null
        xField = null
        yField = null
        zField = null
        rebuildWidgets()
    }

    private fun confirmAdd() {
        val x = parse(xField)
        val y = parse(yField)
        val z = parse(zField)
        if (x == null || y == null || z == null) {
            error = "X, Y and Z must be whole numbers"
            return
        }
        if (!TikiCoords.add(BlockPos(x, y, z))) {
            error = "That coordinate is already in the list"
            return
        }
        ModConfig.markDirty()
        TikiHelperModule.INSTANCE.requestScan()
        scroll = Int.MAX_VALUE
        exitAddMode()
    }

    private fun parse(field: EditBox?): Int? = TikiCoordinateParser.parse(field?.value)

    /** Uses the vanilla six-block reach; without a block hit, defaults to the player's block. */
    private fun lookedAtBlock(): BlockPos {
        val player: LocalPlayer = Minecraft.getInstance().player ?: return BlockPos.ZERO
        val hit = player.pick(PICK_RANGE, 0.0f, false)
        if (hit.type == HitResult.Type.BLOCK) return (hit as BlockHitResult).blockPos
        return player.blockPosition()
    }

    private fun clampScroll() {
        val maxScroll = maxOf(0, TikiCoords.size() * ROW_HEIGHT - listViewport().h)
        scroll = scroll.coerceIn(0, maxScroll)
    }

    private fun panelX(): Int = (width - panelWidth()) / 2
    private fun panelY(): Int = (height - panelHeight()) / 2
    private fun panelWidth(): Int = maxOf(1, minOf(PANEL_WIDTH, width - 12))
    private fun panelHeight(): Int = maxOf(1, minOf(PANEL_HEIGHT, height - 12))
    private fun panelRect(): GuiRect = GuiRect(panelX(), panelY(), panelWidth(), panelHeight())

    private fun listViewport(): GuiRect {
        val top = panelY() + HEADER_HEIGHT
        val viewportHeight = panelHeight() - HEADER_HEIGHT - FOOTER_HEIGHT
        return GuiRect(panelX() + PADDING, top, maxOf(1, panelWidth() - 2 * PADDING), maxOf(1, viewportHeight))
    }

    private fun rowRect(index: Int): GuiRect {
        val viewport = listViewport()
        return GuiRect(viewport.x, viewport.y + index * ROW_HEIGHT - scroll, viewport.w, ROW_HEIGHT)
    }

    private fun deleteRect(row: GuiRect): GuiRect =
        GuiRect(row.x + row.w - DELETE_SIZE - 6, row.y + (ROW_HEIGHT - DELETE_SIZE) / 2, DELETE_SIZE, DELETE_SIZE)

    private fun addButtonRect(): GuiRect {
        val y = panelY() + panelHeight() - FOOTER_HEIGHT + 4
        val footerWidth = maxOf(1, panelWidth() - 2 * PADDING)
        val doneWidth = minOf(56, maxOf(1, (footerWidth - 4) / 3))
        val addWidth = maxOf(1, footerWidth - doneWidth - 4)
        return GuiRect(panelX() + PADDING, y, addWidth, 18)
    }

    private fun doneButtonRect(): GuiRect {
        val y = panelY() + panelHeight() - FOOTER_HEIGHT + 4
        val footerWidth = maxOf(1, panelWidth() - 2 * PADDING)
        val doneWidth = minOf(56, maxOf(1, (footerWidth - 4) / 3))
        return GuiRect(panelX() + panelWidth() - PADDING - doneWidth, y, doneWidth, 18)
    }

    private fun dialogRect(): GuiRect {
        val dialogWidth = dialogWidth()
        val dialogHeight = dialogHeight()
        return GuiRect((width - dialogWidth) / 2, (height - dialogHeight) / 2, dialogWidth, dialogHeight)
    }

    private fun confirmButtonRect(): GuiRect {
        val dialog = dialogRect()
        val buttonWidth = maxOf(1, (dialog.w - 34) / 2)
        return GuiRect(dialog.x + 12, dialog.y + dialog.h - 28, buttonWidth, 18)
    }

    private fun cancelButtonRect(): GuiRect {
        val dialog = dialogRect()
        val buttonWidth = maxOf(1, (dialog.w - 34) / 2)
        return GuiRect(dialog.x + dialog.w - 12 - buttonWidth, dialog.y + dialog.h - 28, buttonWidth, 18)
    }

    private fun dialogWidth(): Int = maxOf(1, minOf(DIALOG_WIDTH, minOf(width - 12, panelWidth() - 12)))
    private fun dialogHeight(): Int = maxOf(1, minOf(DIALOG_HEIGHT, minOf(height - 12, panelHeight() - 12)))
    private fun fieldWidth(): Int = maxOf(1, minOf(FIELD_WIDTH, (dialogWidth() - 24 - FIELD_GAP * 2) / 3))


    companion object {
        private const val PANEL_WIDTH = 250
        private const val PANEL_HEIGHT = 220
        private const val HEADER_HEIGHT = 24
        private const val FOOTER_HEIGHT = 26
        private const val ROW_HEIGHT = 18
        private const val PADDING = 6
        private const val DELETE_SIZE = 14
        private const val SCROLLBAR_WIDTH = 3
        private const val DIALOG_WIDTH = 210
        private const val DIALOG_HEIGHT = 100
        private const val FIELD_WIDTH = 56
        private const val FIELD_HEIGHT = 16
        private const val FIELD_GAP = 8
        private const val PICK_RANGE = 6.0
    }
}
