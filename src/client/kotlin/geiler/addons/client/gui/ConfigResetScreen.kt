package geiler.addons.client.gui

import geiler.addons.client.config.ConfigScreenGeometry
import geiler.addons.client.config.ConfigScreenGeometry.Rect
import geiler.addons.client.config.ConfigTransferService
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.ArrayList
import java.util.EnumSet

/** Two-stage, recoverable configuration reset flow. */
class ConfigResetScreen(private val parent: Screen?) : Screen(
    Component.literal("Reset GeilerAddons Configuration"),
) {
    private val selected = EnumSet.allOf(ConfigTransferService.ResetArea::class.java)
    private var review = false
    private var running = false
    private var finished = false
    private var scroll = 0
    private var status = ""
    private var recoveryPath: Path? = null

    override fun isPauseScreen(): Boolean = false

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        VisualModule.INSTANCE.refreshTheme()
        val layout = layout()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(
            graphics, layout.x, layout.y, layout.w, layout.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        graphics.centeredText(
            font, if (review) "Review Reset" else "Reset Configuration",
            layout.x + layout.w / 2, layout.y + 10, GuiTheme.TEXT_PRIMARY,
        )
        if (!review) {
            graphics.centeredText(
                font,
                fit("Choose areas; scroll for more. Unchecked areas are preserved.", layout.w - 20),
                layout.x + layout.w / 2, layout.y + 27, GuiTheme.TEXT_MUTED,
            )
            val viewport = checklistViewport(layout)
            val visible = ConfigScreenGeometry.visibleRows(viewport, ROW)
            scroll = maxOf(
                0,
                minOf(
                    scroll,
                    ConfigScreenGeometry.maxScroll(ConfigTransferService.ResetArea.values().size, visible),
                ),
            )
            if (viewport.height > 0) {
                graphics.enableScissor(viewport.x, viewport.y, viewport.right(), viewport.bottom())
            }
            for (row in 0 until visible) {
                val index = scroll + row
                if (index >= ConfigTransferService.ResetArea.values().size) break
                val area = ConfigTransferService.ResetArea.values()[index]
                val y = viewport.y + row * ROW
                val checked = selected.contains(area)
                val hovered = !running && mouseX >= layout.x + 12 && mouseX < layout.x + layout.w - 12 &&
                    mouseY >= y && mouseY < y + ROW - 2
                GuiTheme.roundedRectBordered(
                    graphics, layout.x + 12, y, layout.w - 24, ROW - 2, GuiTheme.RADIUS_SMALL,
                    if (hovered) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG,
                    GuiTheme.CARD_BG, GuiTheme.BORDER,
                )
                GuiTheme.roundedRectBordered(
                    graphics, layout.x + 20, y + 3, 15, 15, 3,
                    if (checked) GuiTheme.BUTTON_HOVER else GuiTheme.SLIDER_TRACK,
                    if (checked) GuiTheme.BUTTON_HOVER else GuiTheme.SLIDER_TRACK,
                    GuiTheme.BORDER,
                )
                if (checked) {
                    graphics.centeredText(font, "✓", layout.x + 27, y + 6, GuiTheme.TEXT_ON_ACCENT)
                }
                graphics.text(font, fit(area.label(), layout.w - 66), layout.x + 42, y + 6, GuiTheme.TEXT_PRIMARY)
                if (hovered) graphics.setTooltipForNextFrame(Component.literal(area.label()), mouseX, mouseY)
            }
            if (viewport.height > 0) graphics.disableScissor()
            button(graphics, "Cancel", layout.x + 12, layout.y + layout.h - 29, 74, 20, mouseX, mouseY, running)
            button(
                graphics, "Review Reset…", layout.x + layout.w - 122, layout.y + layout.h - 29,
                110, 20, mouseX, mouseY, running || selected.isEmpty(),
            )
        } else {
            val viewport = reviewViewport(layout)
            val lines = reviewLines(layout)
            val visible = ConfigScreenGeometry.visibleRows(viewport, 10)
            scroll = maxOf(0, minOf(scroll, ConfigScreenGeometry.maxScroll(lines.size, visible)))
            if (viewport.height > 0) {
                graphics.enableScissor(viewport.x, viewport.y, viewport.right(), viewport.bottom())
            }
            for (row in 0 until visible) {
                val index = scroll + row
                if (index >= lines.size) break
                val line = lines[index]
                graphics.text(font, line.text, viewport.x + 3, viewport.y + row * 10, line.color)
            }
            if (viewport.height > 0) graphics.disableScissor()
            if (!finished) {
                graphics.centeredText(
                    font, "A full recovery profile is saved before applying this reset.",
                    layout.x + layout.w / 2, layout.y + layout.h - 53, GuiTheme.TEXT_WARN,
                )
            }
            val footer = ConfigScreenGeometry.resetFooter(rect(layout))
            button(
                graphics, if (finished) "Done" else "Back",
                footer.back.x, footer.back.y, footer.back.width, footer.back.height,
                mouseX, mouseY, running,
            )
            button(
                graphics,
                if (finished) "Done" else if (running) "Resetting…" else "RESET SELECTED DATA",
                footer.action.x, footer.action.y, footer.action.width, footer.action.height,
                mouseX, mouseY, running || (!finished && selected.isEmpty()),
            )
        }
        if (status.isNotBlank()) {
            graphics.centeredText(
                font, fit(status, layout.w - 24), layout.x + layout.w / 2,
                layout.y + layout.h - (if (recoveryPath == null) 43 else 39), GuiTheme.TEXT_MUTED,
            )
        }
        recoveryPath?.let {
            button(graphics, "Open recovery folder", layout.x + 12, layout.y + layout.h - 58, 138, 16, mouseX, mouseY, running)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != 0 || running) return true
        val layout = layout()
        val x = event.x().toInt()
        val y = event.y().toInt()
        recoveryPath?.let { path ->
            if (inside(x, y, layout.x + 12, layout.y + layout.h - 58, 138, 16)) {
                openFolder(path)
                return true
            }
        }
        if (!review) {
            val viewport = checklistViewport(layout)
            val visible = ConfigScreenGeometry.visibleRows(viewport, ROW)
            for (row in 0 until visible) {
                val index = scroll + row
                if (index >= ConfigTransferService.ResetArea.values().size) break
                val area = ConfigTransferService.ResetArea.values()[index]
                val rowY = viewport.y + row * ROW
                if (inside(x, y, layout.x + 12, rowY, layout.w - 24, ROW - 2)) {
                    if (!selected.remove(area)) selected.add(area)
                    return true
                }
            }
            if (inside(x, y, layout.x + 12, layout.y + layout.h - 29, 74, 20)) {
                close()
                return true
            }
            if (inside(x, y, layout.x + layout.w - 122, layout.y + layout.h - 29, 110, 20) && selected.isNotEmpty()) {
                review = true
                scroll = 0
                status = ""
                return true
            }
        } else {
            recoveryPath?.let { path ->
                if (inside(x, y, layout.x + 12, layout.y + layout.h - 58, 138, 16)) {
                    openFolder(path)
                    return true
                }
            }
            val footer = ConfigScreenGeometry.resetFooter(rect(layout))
            if (inside(x, y, footer.back.x, footer.back.y, footer.back.width, footer.back.height)) {
                if (finished) {
                    close()
                } else {
                    review = false
                    scroll = 0
                }
                return true
            }
            if (inside(x, y, footer.action.x, footer.action.y, footer.action.width, footer.action.height)) {
                if (finished) {
                    close()
                    return true
                }
                running = true
                status = "Saving recovery profile…"
                ConfigTransferService.reset(ConfigTransferService.ResetSelection(selected)) { result ->
                    running = false
                    status = result.message().orEmpty()
                    recoveryPath = result.recoveryPath()
                    finished = true
                }
                return true
            }
        }
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (running) return true
            if (review && !finished) review = false else close()
            return true
        }
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val layout = layout()
        val viewport = if (review) reviewViewport(layout) else checklistViewport(layout)
        if (mouseX < viewport.x || mouseX >= viewport.right() || mouseY < viewport.y || mouseY >= viewport.bottom()) {
            return true
        }
        val rows = if (review) linesFor(layout).size else ConfigTransferService.ResetArea.values().size
        val visible = ConfigScreenGeometry.visibleRows(viewport, if (review) 10 else ROW)
        scroll = maxOf(
            0,
            minOf(
                ConfigScreenGeometry.maxScroll(rows, visible),
                scroll - java.lang.Math.signum(scrollY).toInt(),
            ),
        )
        return true
    }

    override fun onClose() = close()

    private fun close() {
        if (!running) minecraft.gui.setScreen(parent)
    }

    private fun join(areas: Set<ConfigTransferService.ResetArea>): String =
        areas.joinToString(" · ") { it.label() }.ifEmpty { "None" }

    private fun openFolder(path: Path) = ConfigTransferService.openFolder(path.toAbsolutePath().parent)

    private fun button(
        graphics: GuiGraphicsExtractor,
        label: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        mouseX: Int,
        mouseY: Int,
        disabled: Boolean,
    ) {
        val hover = !disabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        GuiTheme.roundedRect(
            graphics, x, y, w, h, GuiTheme.RADIUS_SMALL,
            if (disabled) GuiTheme.SLIDER_TRACK else if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG,
        )
        graphics.centeredText(
            font, fit(label, w - 8), x + w / 2, y + (h - font.lineHeight) / 2,
            if (disabled) GuiTheme.TEXT_MUTED else if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
        )
    }

    private fun checklistViewport(layout: Layout): Rect = ConfigScreenGeometry.content(rect(layout), 41, 38)

    private fun reviewViewport(layout: Layout): Rect = ConfigScreenGeometry.content(rect(layout), 37, 63)

    private fun linesFor(layout: Layout): List<ReviewLine> = reviewLines(layout)

    private fun reviewLines(layout: Layout): List<ReviewLine> {
        val lines = ArrayList<ReviewLine>()
        val maxWidth = maxOf(1, layout.w - 39)
        lines.add(ReviewLine("Will reset:", GuiTheme.TEXT_PRIMARY))
        appendAreas(lines, selected, "• ", GuiTheme.TEXT_ON_ACCENT, maxWidth)
        val kept = EnumSet.allOf(ConfigTransferService.ResetArea::class.java)
        kept.removeAll(selected)
        lines.add(ReviewLine("Will keep:", GuiTheme.TEXT_PRIMARY))
        appendAreas(lines, kept, "• ", GuiTheme.TEXT_MUTED, maxWidth)
        return java.util.List.copyOf(lines)
    }

    private fun appendAreas(
        lines: MutableList<ReviewLine>,
        areas: Set<ConfigTransferService.ResetArea>,
        firstPrefix: String,
        color: Int,
        maxWidth: Int,
    ) {
        if (areas.isEmpty()) {
            lines.add(ReviewLine(firstPrefix + "None", GuiTheme.TEXT_MUTED))
            return
        }
        for (area in areas) {
            var remainder = area.label()
            var prefix = firstPrefix
            while (remainder.isNotBlank()) {
                val line = StringBuilder(prefix)
                val words = remainder.split(Regex("\\s+"))
                var consumed = 0
                for (word in words) {
                    val candidate = line.toString() + (if (line.length == prefix.length) "" else " ") + word
                    if (font.width(candidate) > maxWidth && line.length > prefix.length) break
                    if (font.width(candidate) > maxWidth) break
                    if (line.length > prefix.length) line.append(' ')
                    line.append(word)
                    consumed++
                }
                if (consumed == 0) {
                    lines.add(ReviewLine(fit(prefix + words[0], maxWidth), color))
                    remainder = words.drop(1).joinToString(" ")
                } else {
                    lines.add(ReviewLine(line.toString(), color))
                    remainder = words.drop(consumed).joinToString(" ")
                }
                prefix = "  "
            }
        }
    }

    private fun rect(layout: Layout): Rect = Rect(layout.x, layout.y, layout.w, layout.h)

    private fun layout(): Layout {
        val panel = ConfigScreenGeometry.panel(width, height, 620, 460)
        return Layout(panel.x, panel.y, panel.width, panel.height)
    }

    private fun inside(x: Int, y: Int, boxX: Int, boxY: Int, boxWidth: Int, boxHeight: Int): Boolean =
        x >= boxX && x < boxX + boxWidth && y >= boxY && y < boxY + boxHeight

    private fun fit(value: String, maxWidth: Int): String = GuiTheme.fit(font, value, maxWidth)

    private data class Layout(val x: Int, val y: Int, val w: Int, val h: Int)
    private data class ReviewLine(val text: String, val color: Int)

    companion object {
        private const val ROW = 22
    }
}
