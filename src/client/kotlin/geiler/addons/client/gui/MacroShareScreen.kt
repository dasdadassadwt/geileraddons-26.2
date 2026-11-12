package geiler.addons.client.gui

import geiler.addons.client.config.ConfigScreenGeometry
import geiler.addons.client.config.ConfigScreenGeometry.Rect
import geiler.addons.client.module.impl.MacrosModule
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Lists every macro with a switch; the Copy button at the top copies the switched-on macros, with the macros and
 * functions they call, to the clipboard. Settings and workflows stay out of this list.
 */
class MacroShareScreen(private val parent: Screen?) : Screen(Component.literal("Share Macros")) {
    private var scroll = 0
    private var status = ""

    override fun isPauseScreen(): Boolean = false

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        val panel = panel()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(
            graphics, panel.x, panel.y, panel.width, panel.height, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        val macros = MacrosModule.INSTANCE.macros()
        val selected = macros.count { MacrosModule.INSTANCE.isMarkedForShare(it.id()) }

        graphics.text(font, "Share Macros", panel.x + 12, panel.y + 12, GuiTheme.TEXT_PRIMARY)
        val copy = copyButton(panel)
        button(graphics, "Copy ($selected)", copy, mouseX, mouseY, selected == 0)
        val selectAll = selectAllButton(panel)
        button(graphics, if (selected == macros.size && macros.isNotEmpty()) "Select none" else "Select all",
            selectAll, mouseX, mouseY, macros.isEmpty())

        val viewport = viewport(panel)
        val visible = ConfigScreenGeometry.visibleRows(viewport, ROW)
        scroll = scroll.coerceIn(0, ConfigScreenGeometry.maxScroll(macros.size, visible))
        if (macros.isEmpty()) {
            graphics.centeredText(font, "No macros yet. Create one first.", panel.x + panel.width / 2,
                viewport.y + 12, GuiTheme.TEXT_MUTED)
        }
        graphics.enableScissor(viewport.x, viewport.y, viewport.right(), viewport.bottom())
        for (row in 0 until visible) {
            val index = scroll + row
            if (index >= macros.size) break
            val macro = macros[index]
            val y = viewport.y + row * ROW
            val on = MacrosModule.INSTANCE.isMarkedForShare(macro.id())
            val hovered = mouseX in viewport.x until viewport.right() && mouseY >= y && mouseY < y + ROW - 2
            GuiTheme.roundedRectBordered(
                graphics, viewport.x, y, viewport.width, ROW - 2, GuiTheme.RADIUS_SMALL,
                if (hovered) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG, GuiTheme.CARD_BG, GuiTheme.BORDER,
            )
            graphics.text(font, GuiTheme.fit(font, macro.name(), viewport.width - 58), viewport.x + 8,
                y + (ROW - 2 - font.lineHeight) / 2, GuiTheme.TEXT_PRIMARY)
            GuiTheme.toggleSwitch(graphics, viewport.right() - 36, y + (ROW - 2 - 12) / 2, 28, 12, on)
        }
        graphics.disableScissor()

        if (status.isNotBlank()) {
            graphics.centeredText(font, GuiTheme.fit(font, status, panel.width - 24), panel.x + panel.width / 2,
                panel.y + panel.height - 38, GuiTheme.TEXT_MUTED)
        }
        button(graphics, "Done", doneButton(panel), mouseX, mouseY, false)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != 0) return true
        val x = event.x().toInt()
        val y = event.y().toInt()
        val panel = panel()
        val macros = MacrosModule.INSTANCE.macros()
        if (doneButton(panel).contains(x, y)) {
            onClose()
            return true
        }
        if (copyButton(panel).contains(x, y)) {
            status = MacrosModule.INSTANCE.copyMarkedMacros()
            return true
        }
        if (selectAllButton(panel).contains(x, y) && macros.isNotEmpty()) {
            val all = macros.all { MacrosModule.INSTANCE.isMarkedForShare(it.id()) }
            for (macro in macros) MacrosModule.INSTANCE.setMarkedForShare(macro.id(), !all)
            return true
        }
        val viewport = viewport(panel)
        if (viewport.contains(x, y)) {
            val index = scroll + (y - viewport.y) / ROW
            if (index in macros.indices && (y - viewport.y) % ROW < ROW - 2) {
                val id = macros[index].id()
                MacrosModule.INSTANCE.setMarkedForShare(id, !MacrosModule.INSTANCE.isMarkedForShare(id))
            }
        }
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val viewport = viewport(panel())
        val visible = ConfigScreenGeometry.visibleRows(viewport, ROW)
        val max = ConfigScreenGeometry.maxScroll(MacrosModule.INSTANCE.macros().size, visible)
        scroll = (scroll - Math.signum(scrollY).toInt()).coerceIn(0, max)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) onClose()
        return true
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private fun panel(): Rect = ConfigScreenGeometry.panel(width, height, 360, 360)

    private fun viewport(panel: Rect): Rect = ConfigScreenGeometry.content(panel, 62, 46)

    private fun copyButton(panel: Rect) = Rect(panel.right() - 12 - 90, panel.y + 8, 90, 22)

    private fun selectAllButton(panel: Rect) = Rect(panel.x + 12, panel.y + 34, 90, 18)

    private fun doneButton(panel: Rect) = Rect(panel.right() - 12 - 70, panel.bottom() - 28, 70, 20)

    private fun button(graphics: GuiGraphicsExtractor, label: String, box: Rect, mouseX: Int, mouseY: Int, disabled: Boolean) {
        val hover = !disabled && box.contains(mouseX, mouseY)
        GuiTheme.roundedRect(
            graphics, box.x, box.y, box.width, box.height, GuiTheme.RADIUS_SMALL,
            if (disabled) GuiTheme.SLIDER_TRACK else if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG,
        )
        graphics.centeredText(
            font, GuiTheme.fit(font, label, box.width - 8), box.x + box.width / 2, box.y + (box.height - font.lineHeight) / 2,
            if (disabled) GuiTheme.TEXT_MUTED else if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
        )
    }

    private companion object {
        private const val ROW = 22
    }
}
