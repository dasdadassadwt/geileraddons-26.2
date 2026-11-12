package geiler.addons.client.gui

import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.function.Consumer

/** Small text editor for label appearance; input is never treated as a texture identifier. */
class InventoryButtonTextScreen(
    private val parent: Screen?,
    private val dialogTitle: String,
    private val hint: String,
    initial: String?,
    private val selected: Consumer<String>,
) : Screen(Component.literal(dialogTitle)) {
    private val initial = initial ?: ""
    private var text: EditBox? = null
    private var panel = GuiRect(0, 0, 1, 1)

    constructor(parent: Screen?, initial: String?, selected: Consumer<String>) : this(
        parent,
        "Inventory Button Text",
        "Text appears inside an 18×18 button; hover shows its full label.",
        initial,
        selected,
    )

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        panel = panel()
        val field = EditBox(font, panel.x + 12, panel.y + 48, panel.w - 24, 20, Component.literal("Button label"))
        field.setMaxLength(256)
        field.setValue(initial)
        text = addRenderableWidget(field)
        setInitialFocus(field)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        VisualModule.INSTANCE.refreshTheme()
        panel = panel()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(
            graphics, panel.x, panel.y, panel.w, panel.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        graphics.text(font, trim(dialogTitle, panel.w - 24), panel.x + 12, panel.y + 12, GuiTheme.TEXT_PRIMARY)
        graphics.text(font, trim(hint, panel.w - 24), panel.x + 12, panel.y + 27, GuiTheme.TEXT_MUTED)
        val buttonWidth = minOf(70, maxOf(48, (panel.w - 36) / 2))
        drawButton(graphics, "Save", panel.x + 12, panel.y + 82, buttonWidth, 20, mouseX, mouseY)
        drawButton(
            graphics, "Cancel", panel.x + panel.w - 12 - buttonWidth, panel.y + 82,
            buttonWidth, 20, mouseX, mouseY,
        )
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().toInt()
        val y = event.y().toInt()
        if (event.button() == 0 && y >= panel.y + 82 && y < panel.y + 102) {
            val buttonWidth = minOf(70, maxOf(48, (panel.w - 36) / 2))
            if (x >= panel.x + 12 && x < panel.x + 12 + buttonWidth) {
                minecraft?.gui?.setScreen(parent)
                selected.accept(text?.value ?: "")
                return true
            }
            if (x >= panel.x + panel.w - 12 - buttonWidth && x < panel.x + panel.w - 12) {
                minecraft?.gui?.setScreen(parent)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun onClose() {
        minecraft?.gui?.setScreen(parent)
    }

    private fun panel(): GuiRect {
        val panelWidth = minOf(300, maxOf(1, width - 20))
        val panelHeight = minOf(124, maxOf(1, height - 20))
        return GuiRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight)
    }

    private fun trim(value: String, maxWidth: Int): String = GuiTheme.fit(font, value, maxWidth)

    private fun drawButton(
        graphics: GuiGraphicsExtractor,
        label: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        GuiTheme.roundedRect(
            graphics, x, y, w, h, GuiTheme.RADIUS_SMALL,
            if (hovered) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG,
        )
        graphics.centeredText(
            font, label, x + w / 2, y + 6,
            if (hovered) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
        )
    }

}
