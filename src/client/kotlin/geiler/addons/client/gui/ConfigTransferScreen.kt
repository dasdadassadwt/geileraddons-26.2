package geiler.addons.client.gui

import geiler.addons.client.config.ConfigScreenGeometry
import geiler.addons.client.config.ConfigScreenGeometry.Footer
import geiler.addons.client.config.ConfigScreenGeometry.Rect
import geiler.addons.client.config.ConfigTransferService
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.ArrayList
import java.util.Locale

/** Lists self-contained config profiles and requires a preview plus a separate import confirmation. */
class ConfigTransferScreen(private val parent: Screen?) : Screen(
    Component.literal("GeilerAddons Configuration Profiles"),
) {
    private val profiles = ArrayList<ConfigTransferService.Profile>()
    private lateinit var exportName: EditBox
    private var selected: ConfigTransferService.Profile? = null
    private var previewed: ConfigTransferService.Profile? = null
    private var loading = true
    private var running = false
    private var scroll = 0
    private var notice = ""
    private var lastResultPath: Path? = null

    init {
        refreshProfiles()
    }

    public override fun init() {
        val layout = layout()
        val name = toolbar(layout).name
        exportName = addRenderableWidget(
            EditBox(font, name.x, name.y, name.width, name.height, Component.literal("Profile name")),
        )
        exportName.setMaxLength(64)
        exportName.setHint(Component.literal("Profile name (optional)"))
    }

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
        val compact = ConfigScreenGeometry.compactProfilePanel(rect(layout))
        graphics.centeredText(
            font, "Configuration Profiles", layout.x + layout.w / 2,
            layout.y + (if (compact) 5 else 9), GuiTheme.TEXT_PRIMARY,
        )
        if (!compact) {
            graphics.text(
                font,
                fit("Export includes settings and split local collections; imports replace the full active profile.", layout.w - 24),
                layout.x + 12, layout.y + 29, GuiTheme.TEXT_MUTED,
            )
        }
        val toolbar = toolbar(layout)
        if (previewed == null) {
            val exportLabel = if (compact) "Export" else "Export Snapshot"
            val refreshLabel = if (compact) {
                if (loading) "…" else "Refresh"
            } else {
                if (loading) "Loading…" else "Refresh"
            }
            button(
                graphics, exportLabel, toolbar.export.x, toolbar.export.y,
                toolbar.export.width, toolbar.export.height, mouseX, mouseY, running,
            )
            button(
                graphics, refreshLabel, toolbar.refresh.x, toolbar.refresh.y,
                toolbar.refresh.width, toolbar.refresh.height, mouseX, mouseY, loading || running,
            )
        }
        exportName.visible = previewed == null
        val viewport = if (previewed == null) profileViewport(layout) else previewViewport(layout)
        val rowHeight = 26
        val visible = if (previewed == null) ConfigScreenGeometry.visibleRows(viewport, rowHeight) else 0
        scroll = maxOf(0, minOf(scroll, ConfigScreenGeometry.maxScroll(profiles.size, visible)))
        if (profiles.isEmpty() && !loading && previewed == null) {
            graphics.centeredText(
                font, fit("No valid exported profiles yet.", layout.w - 24),
                layout.x + layout.w / 2, viewport.y + 16, GuiTheme.TEXT_MUTED,
            )
        }
        if (viewport.height > 0) graphics.enableScissor(viewport.x, viewport.y, viewport.right(), viewport.bottom())
        val needsScroll = previewed == null && profiles.size > visible
        val cardWidth = maxOf(0, layout.w - 20 - (if (needsScroll) 8 else 0))
        for (row in 0 until visible) {
            val index = scroll + row
            if (index >= profiles.size) break
            val profile = profiles[index]
            val y = viewport.y + row * rowHeight
            val active = selected != null && profile.path().equals(selected?.path())
            val hover = Rect(layout.x + 10, y, cardWidth, rowHeight - 2).contains(mouseX, mouseY)
            GuiTheme.roundedRectBordered(
                graphics, layout.x + 10, y, cardWidth, rowHeight - 2, GuiTheme.RADIUS_SMALL,
                if (active) GuiTheme.CARD_BG_ENABLED else if (hover) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG,
                if (active) GuiTheme.CARD_BG_ENABLED else GuiTheme.CARD_BG,
                if (active) GuiTheme.CARD_BORDER_ENABLED else GuiTheme.BORDER,
            )
            graphics.text(font, fit(profile.name(), layout.w - 158), layout.x + 18, y + 4, GuiTheme.TEXT_PRIMARY)
            val detail = "${profile.entries()} files · ${formatBytes(profile.bytes())} · ${profile.createdAt()}"
            graphics.text(font, fit(detail, layout.w - 158), layout.x + 18, y + 14, GuiTheme.TEXT_MUTED)
        }
        previewed?.let { profile ->
            graphics.text(font, "IMPORT PREVIEW", viewport.x + 3, viewport.y + 2, GuiTheme.TEXT_PRIMARY)
            graphics.text(
                font, fit("Will replace all active settings and split data.", viewport.width - 6),
                viewport.x + 3, viewport.y + 13, GuiTheme.TEXT_WARN,
            )
            graphics.text(
                font, fit("${profile.name()} · ${profile.entries()} files · ${formatBytes(profile.bytes())}", viewport.width - 6),
                viewport.x + 3, viewport.y + 24, GuiTheme.TEXT_MUTED,
            )
            graphics.text(font, fit(profile.createdAt(), viewport.width - 6), viewport.x + 3, viewport.y + 35, GuiTheme.TEXT_MUTED)
        }
        if (viewport.height > 0) graphics.disableScissor()
        if (needsScroll && viewport.height > 0) {
            val trackX = viewport.right() - 3
            graphics.fill(trackX, viewport.y, trackX + 2, viewport.bottom(), GuiTheme.SLIDER_TRACK)
            val maxScroll = ConfigScreenGeometry.maxScroll(profiles.size, visible)
            val thumbHeight = maxOf(12, viewport.height * visible / profiles.size)
            val thumbY = viewport.y +
                (if (maxScroll == 0) 0 else (viewport.height - thumbHeight) * scroll / maxScroll)
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + minOf(thumbHeight, viewport.height), GuiTheme.SCROLLBAR)
        }
        val footer = ConfigScreenGeometry.transferFooter(rect(layout), previewed != null)
        button(
            graphics, if (previewed != null) "Back" else if (lastResultPath == null) "Folder" else "Result",
            footer.folder.x, footer.folder.y, footer.folder.width, footer.folder.height,
            mouseX, mouseY, running,
        )
        button(
            graphics, if (previewed == null) "Preview Import…" else "CONFIRM IMPORT",
            footer.action.x, footer.action.y, footer.action.width, footer.action.height,
            mouseX, mouseY, running || selected == null || (previewed == null && selected == null),
        )
        button(
            graphics, "Done", footer.done.x, footer.done.y, footer.done.width, footer.done.height,
            mouseX, mouseY, running,
        )
        if (notice.isNotBlank() && previewed == null) {
            graphics.text(
                font, fit(notice, layout.w - 20), layout.x + 12,
                if (compact) layout.y + 45 else footer.action.y - 13, GuiTheme.TEXT_MUTED,
            )
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != 0 || running) return true
        val layout = layout()
        val x = event.x().toInt()
        val y = event.y().toInt()
        val toolbar = toolbar(layout)
        val footer = ConfigScreenGeometry.transferFooter(rect(layout), previewed != null)
        val viewport = if (previewed == null) profileViewport(layout) else previewViewport(layout)
        val rowHeight = 26
        val visible = if (previewed == null) ConfigScreenGeometry.visibleRows(viewport, rowHeight) else 0
        val needsScroll = previewed == null && profiles.size > visible
        val cardWidth = maxOf(0, layout.w - 20 - (if (needsScroll) 8 else 0))
        for (row in 0 until visible) {
            val index = scroll + row
            if (index >= profiles.size) break
            val rowY = viewport.y + row * rowHeight
            if (Rect(layout.x + 10, rowY, cardWidth, rowHeight - 2).contains(x, y)) {
                selected = profiles[index]
                previewed = null
                notice = ""
                return true
            }
        }
        if (previewed == null && toolbar.refresh.contains(x, y)) {
            refreshProfiles()
            return true
        }
        if (previewed == null && toolbar.export.contains(x, y)) {
            running = true
            notice = "Saving and exporting…"
            ConfigTransferService.exportProfile(exportName.value) { result ->
                running = false
                notice = result.message().orEmpty()
                lastResultPath = result.path()
                previewed = null
                refreshProfiles()
            }
            return true
        }
        if (footer.folder.contains(x, y) && previewed != null) {
            previewed = null
            return true
        }
        if (footer.folder.contains(x, y)) {
            val open = if (lastResultPath == null) ConfigTransferService.configDirectory()
            else lastResultPath!!.toAbsolutePath().parent
            ConfigTransferService.openFolder(open)
            return true
        }
        if (footer.done.contains(x, y)) {
            onClose()
            return true
        }
        if (previewed == null && selected != null && footer.action.contains(x, y)) {
            running = true
            notice = "Validating profile before showing confirmation…"
            ConfigTransferService.preview(selected!!.path()) { result ->
                running = false
                if (result.success()) {
                    previewed = result.profile()
                    notice = "Review the replacement warning, then confirm once more."
                } else {
                    notice = "Import blocked: ${result.message()}"
                }
            }
            return true
        }
        if (previewed != null && footer.action.contains(x, y)) {
            running = true
            notice = "Creating recovery copy and importing…"
            ConfigTransferService.importProfile(previewed!!.path()) { result ->
                running = false
                notice = result.message().orEmpty()
                lastResultPath = result.recoveryPath()
                previewed = null
                if (result.success()) refreshProfiles()
            }
            return true
        }
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val layout = layout()
        if (previewed != null) return true
        val viewport = profileViewport(layout)
        if (!viewport.contains(mouseX.toInt(), mouseY.toInt())) return true
        val visible = ConfigScreenGeometry.visibleRows(viewport, 26)
        scroll = maxOf(
            0,
            minOf(
                ConfigScreenGeometry.maxScroll(profiles.size, visible),
                scroll - java.lang.Math.signum(scrollY).toInt(),
            ),
        )
        return true
    }

    override fun onClose() {
        if (!running) minecraft.gui.setScreen(parent)
    }

    private fun refreshProfiles() {
        loading = true
        ConfigTransferService.listProfiles { found ->
            profiles.clear()
            profiles.addAll(found)
            loading = false
            selected?.let { previous ->
                selected = profiles.firstOrNull { profile -> profile.path().equals(previous.path()) }
            }
        }
    }

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

    private fun fit(value: String, maxWidth: Int): String = GuiTheme.fit(font, value, maxWidth)

    private fun profileViewport(layout: Layout): Rect = ConfigScreenGeometry.profileViewport(rect(layout))

    private fun previewViewport(layout: Layout): Rect = ConfigScreenGeometry.previewViewport(rect(layout))

    private fun toolbar(layout: Layout): Toolbar {
        if (ConfigScreenGeometry.compactProfilePanel(rect(layout))) {
            val inner = maxOf(1, layout.w - 24)
            val refreshWidth = if (layout.w < 280) 56 else 64
            val exportWidth = if (layout.w < 280) 78 else 104
            val fieldWidth = maxOf(1, inner - refreshWidth - exportWidth - 8)
            val y = layout.y + 23
            val name = Rect(layout.x + 12, y, fieldWidth, 20)
            val export = Rect(name.right() + 4, y, exportWidth, 20)
            val refresh = Rect(export.right() + 4, y, refreshWidth, 20)
            return Toolbar(name, export, refresh)
        }
        val fieldWidth = maxOf(60, minOf(layout.w - 260, layout.w - 152))
        return Toolbar(
            Rect(layout.x + 12, layout.y + 43, fieldWidth, 20),
            Rect(layout.x + layout.w - 132, layout.y + 43, 120, 20),
            Rect(layout.x + layout.w - 86, layout.y + 70, 74, 18),
        )
    }

    private fun rect(layout: Layout): Rect = Rect(layout.x, layout.y, layout.w, layout.h)

    private fun layout(): Layout {
        val panel = ConfigScreenGeometry.panel(width, height, 680, 470)
        return Layout(panel.x, panel.y, panel.width, panel.height)
    }

    private fun formatBytes(bytes: Long): String = if (bytes < 1024 * 1024) {
        "${bytes / 1024} KiB"
    } else {
        String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
    }

    private data class Layout(val x: Int, val y: Int, val w: Int, val h: Int)
    private data class Toolbar(val name: Rect, val export: Rect, val refresh: Rect)
}
