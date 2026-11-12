package geiler.addons.client.gui

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** Manual floor override picker used by Dungeon Guide. */
class DungeonFloorPickerScreen(private val parent: Screen?) : Screen(Component.literal("Dungeon Floor")) {
    private var notice = ""

    override fun isPauseScreen(): Boolean = false

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val layout = layout()
        GuiTheme.roundedRectBordered(
            graphics, layout.x, layout.y, layout.w, layout.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        graphics.centeredText(font, "Dungeon Floor", layout.x + layout.w / 2, layout.y + 12, GuiTheme.TEXT_PRIMARY)
        val status = if (DungeonContextTracker.inDungeon()) {
            val manualFloor = DungeonContextTracker.manualFloor()
            val floorStatus = if (manualFloor == null) "automatic" else "manual " + manualFloor.displayName()
            "Detected dungeon · " + floorStatus
        } else {
            "Enter a detected dungeon before choosing a manual floor"
        }
        graphics.centeredText(font, status, layout.x + layout.w / 2, layout.y + 28, GuiTheme.TEXT_MUTED)

        val floors = DungeonFloor.values()
        val gap = 5
        val columns = 4
        val buttonWidth = (layout.w - 20 - gap * (columns - 1)) / columns
        val buttonHeight = 24
        val top = layout.y + 52
        for (index in floors.indices) {
            val row = index / columns
            val column = index % columns
            val x = layout.x + 10 + column * (buttonWidth + gap)
            val y = top + row * (buttonHeight + gap)
            button(graphics, x, y, buttonWidth, buttonHeight, floors[index].displayName(), mouseX, mouseY)
        }

        val rowCount = (floors.size + columns - 1) / columns
        val actionY = top + rowCount * (buttonHeight + gap) + 4
        val half = (layout.w - 25) / 2
        button(graphics, layout.x + 10, actionY, half, 21, "Automatic floor", mouseX, mouseY)
        button(graphics, layout.x + 15 + half, actionY, half, 21, "Back", mouseX, mouseY)
        if (notice.isNotBlank()) {
            graphics.centeredText(font, notice, layout.x + layout.w / 2, layout.y + layout.h - 13, GuiTheme.TEXT_MUTED)
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun button(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        label: String,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        GuiTheme.roundedRect(
            graphics, x, y, w, h, GuiTheme.RADIUS_SMALL,
            if (hover) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG,
        )
        graphics.centeredText(font, label, x + w / 2, y + (h - font.lineHeight) / 2, GuiTheme.TEXT_PRIMARY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != 0) return false
        val layout = layout()
        val floors = DungeonFloor.values()
        val gap = 5
        val columns = 4
        val buttonWidth = (layout.w - 20 - gap * (columns - 1)) / columns
        val buttonHeight = 24
        val top = layout.y + 52
        for (index in floors.indices) {
            val row = index / columns
            val column = index % columns
            val x = layout.x + 10 + column * (buttonWidth + gap)
            val y = top + row * (buttonHeight + gap)
            if (inside(event.x(), event.y(), x, y, buttonWidth, buttonHeight)) {
                if (DungeonContextTracker.setManualFloor(floors[index])) onClose()
                else notice = "A dungeon must be detected first"
                return true
            }
        }

        val rowCount = (floors.size + columns - 1) / columns
        val actionY = top + rowCount * (buttonHeight + gap) + 4
        val half = (layout.w - 25) / 2
        if (inside(event.x(), event.y(), layout.x + 10, actionY, half, 21)) {
            DungeonContextTracker.clearManualFloor()
            onClose()
            return true
        }
        if (inside(event.x(), event.y(), layout.x + 15 + half, actionY, half, 21)) {
            onClose()
            return true
        }
        return false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft?.gui?.setScreen(parent)
    }

    private fun layout(): Layout {
        val w = maxOf(1, minOf(520, width - 16))
        val h = 52 + 4 * 29 + 21 + 28
        val x = (width - w) / 2
        val y = (height - h) / 2
        return Layout(x, y, w, h)
    }

    private fun inside(x: Double, y: Double, bx: Int, by: Int, bw: Int, bh: Int): Boolean =
        x >= bx && x < bx + bw && y >= by && y < by + bh

    private data class Layout(val x: Int, val y: Int, val w: Int, val h: Int)
}
