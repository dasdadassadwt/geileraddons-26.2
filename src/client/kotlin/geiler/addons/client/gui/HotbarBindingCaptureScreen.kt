package geiler.addons.client.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/** Legacy entry point retained for compatibility; links now use a hold-and-drag gesture. */
class HotbarBindingCaptureScreen(parent: Screen?, oneBasedSlot: Int) :
    Screen(Component.literal("Inventory-to-Hotbar Link")) {
    private val parent = parent
    private val slot = oneBasedSlot.coerceIn(1, 9)

    override fun isPauseScreen(): Boolean = false

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val boxWidth = minOf(390, width - 16)
        val boxHeight = 112
        val x = (width - boxWidth) / 2
        val y = (height - boxHeight) / 2
        GuiTheme.roundedRectBordered(
            graphics, x, y, boxWidth, boxHeight, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        graphics.centeredText(font, "Hotbar slot $slot", x + boxWidth / 2, y + 15, GuiTheme.TEXT_PRIMARY)
        graphics.centeredText(
            font, "Hold the configured bind key over an inventory slot,",
            x + boxWidth / 2, y + 42, GuiTheme.TEXT_SECONDARY,
        )
        graphics.centeredText(
            font, "then release it over this hotbar slot to create a link.",
            x + boxWidth / 2, y + 61, GuiTheme.TEXT_MUTED,
        )
        graphics.centeredText(
            font, "Escape closes this help screen.", x + boxWidth / 2, y + 83, GuiTheme.TEXT_MUTED,
        )
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return true
    }

    override fun onClose() {
        minecraft!!.gui.setScreen(parent)
    }
}
