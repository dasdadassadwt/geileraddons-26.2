package geiler.addons.client.gui

import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.presentation.PlayerSizeOverrideStore
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.Locale

/** Local editor for case-insensitive per-player model-size overrides. */
class PlayerSizeEditorScreen(private val parent: Screen?) : Screen(Component.literal("Local Player Sizes")) {
    private lateinit var playerName: EditBox
    private lateinit var sizeX: EditBox
    private lateinit var sizeY: EditBox
    private lateinit var sizeZ: EditBox
    private var selectedName: String? = null
    private var entryEnabled = true
    private var notice = "Only entries enabled here override hosted sizes."
    private var scroll = 0

    override fun init() {
        val panel = panel()
        playerName = addRenderableWidget(field(
            panel.x + 12, panel.y + 63, profileNameWidth(panel), "Minecraft username", 16,
        ))
        val x = axisField(panel, 0)
        val y = axisField(panel, 1)
        val z = axisField(panel, 2)
        sizeX = addRenderableWidget(field(x.x, x.y, x.w, "0.1–4.0", 16))
        sizeY = addRenderableWidget(field(y.x, y.y, y.w, "0.1–4.0", 16))
        sizeZ = addRenderableWidget(field(z.x, z.y, z.w, "0.1–4.0", 16))
        loadSelection()
    }

    override fun isPauseScreen(): Boolean = false

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        VisualModule.INSTANCE.refreshTheme()
        val panel = panel()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(
            graphics,
            panel.x,
            panel.y,
            panel.w,
            panel.h,
            GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP,
            GuiTheme.PANEL_BOTTOM,
            GuiTheme.BORDER,
        )
        graphics.text(font, "Player Size · local overrides", panel.x + 12, panel.y + 11, GuiTheme.TEXT_PRIMARY)
        graphics.text(
            font,
            fit("Local entries take priority over hosted sizes for matching names.", panel.w - 24),
            panel.x + 12,
            panel.y + 31,
            GuiTheme.TEXT_MUTED,
        )
        graphics.text(font, "Username", panel.x + 12, panel.y + 48, GuiTheme.TEXT_MUTED)
        graphics.text(font, "X", axisField(panel, 0).x - 15, panel.y + 48, GuiTheme.TEXT_MUTED)
        graphics.text(font, "Y", axisField(panel, 1).x - 15, panel.y + 48, GuiTheme.TEXT_MUTED)
        graphics.text(font, "Z", axisField(panel, 2).x - 15, panel.y + 48, GuiTheme.TEXT_MUTED)
        button(graphics, if (entryEnabled) "ON" else "OFF", enabledRect(panel), mouseX, mouseY, false)
        button(graphics, if (selectedName == null) "Add" else "Save", saveRect(panel), mouseX, mouseY, false)
        button(graphics, "New", newRect(panel), mouseX, mouseY, false)
        button(graphics, "Delete", deleteRect(panel), mouseX, mouseY, true)
        graphics.text(
            font,
            "Saved local players (" + PlayerSizeOverrideStore.INSTANCE.entries().size +
                "/" + PlayerSizeOverrideStore.MAX_ENTRIES + ")",
            panel.x + 12,
            panel.y + 112,
            GuiTheme.TEXT_SECONDARY,
        )
        val viewport = listRect(panel)
        val entries = PlayerSizeOverrideStore.INSTANCE.entries()
        val visible = maxOf(0, viewport.h / ROW_HEIGHT)
        scroll = scroll.coerceIn(0, maxOf(0, entries.size - visible))
        graphics.enableScissor(viewport.x, viewport.y, viewport.x + viewport.w, viewport.y + viewport.h)
        if (entries.isEmpty()) {
            graphics.text(font, "No local player overrides.", viewport.x + 5, viewport.y + 5, GuiTheme.TEXT_MUTED)
        }
        for (row in 0 until visible) {
            val index = scroll + row
            if (index >= entries.size) break
            val entry = entries[index]
            val y = viewport.y + row * ROW_HEIGHT
            val hovered = mouseX >= viewport.x && mouseX < viewport.x + viewport.w &&
                mouseY >= y && mouseY < y + ROW_HEIGHT
            if (hovered || entry.playerName.equals(selectedName ?: "", ignoreCase = true)) {
                GuiTheme.roundedRect(
                    graphics,
                    viewport.x + 1,
                    y,
                    viewport.w - 2,
                    ROW_HEIGHT - 1,
                    GuiTheme.RADIUS_SMALL,
                    if (hovered) GuiTheme.CATEGORY_HOVER else GuiTheme.CARD_BG_HOVER,
                )
            }
            val text = entry.playerName + "  ·  X " + axis(entry.sizeX) +
                "  Y " + axis(entry.sizeY) + "  Z " + axis(entry.sizeZ) +
                if (entry.enabled) "" else "  [off]"
            graphics.text(
                font,
                fit(text, viewport.w - 12),
                viewport.x + 6,
                y + 7,
                if (entry.enabled) GuiTheme.TEXT_PRIMARY else GuiTheme.TEXT_MUTED,
            )
        }
        graphics.disableScissor()
        button(graphics, "Done", doneRect(panel), mouseX, mouseY, false)
        graphics.text(
            font,
            fit(notice, maxOf(1, panel.w - 118)),
            panel.x + 12,
            panel.y + panel.h - 19,
            if (notice.startsWith("Saved") || notice.startsWith("Removed")) {
                GuiTheme.TEXT_SECONDARY
            } else {
                GuiTheme.TEXT_WARN
            },
        )
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick)
        val x = event.x().toInt()
        val y = event.y().toInt()
        val panel = panel()
        if (enabledRect(panel).contains(x, y)) {
            entryEnabled = !entryEnabled
            notice = if (entryEnabled) {
                "This entry will override hosted size."
            } else {
                "This entry is saved but inactive."
            }
            return true
        }
        if (saveRect(panel).contains(x, y)) {
            saveEntry()
            return true
        }
        if (newRect(panel).contains(x, y)) {
            selectedName = null
            playerName.setValue("")
            setAxisValues("1.0", "1.0", "1.0")
            entryEnabled = true
            notice = "Enter a username and a size from 0.1 to 4.0."
            return true
        }
        if (deleteRect(panel).contains(x, y)) {
            val name = selectedName
            if (name != null && PlayerSizeOverrideStore.INSTANCE.remove(name)) {
                notice = "Removed local override."
                selectedName = null
                playerName.setValue("")
                setAxisValues("1.0", "1.0", "1.0")
            } else {
                notice = "Select a saved player first."
            }
            return true
        }
        if (doneRect(panel).contains(x, y)) {
            minecraft.gui.setScreen(parent)
            return true
        }
        val viewport = listRect(panel)
        if (viewport.contains(x, y)) {
            val index = scroll + (y - viewport.y) / ROW_HEIGHT
            val entries = PlayerSizeOverrideStore.INSTANCE.entries()
            if (index >= 0 && index < entries.size) {
                val selected = entries[index]
                selectedName = selected.playerName
                playerName.setValue(selected.playerName)
                setAxisValues(
                    selected.sizeX.toString(),
                    selected.sizeY.toString(),
                    selected.sizeZ.toString(),
                )
                entryEnabled = selected.enabled
                notice = "Editing " + selected.playerName + "."
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val viewport = listRect(panel())
        if (!viewport.contains(mouseX.toInt(), mouseY.toInt())) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }
        val visible = maxOf(0, viewport.h / ROW_HEIGHT)
        val count = PlayerSizeOverrideStore.INSTANCE.entries().size
        scroll = (scroll - Math.signum(scrollY).toInt()).coerceIn(0, maxOf(0, count - visible))
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            minecraft.gui.setScreen(parent)
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER && this::playerName.isInitialized && playerName.isFocused) {
            saveEntry()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft?.gui?.setScreen(parent)
    }

    private fun saveEntry() {
        val input = trimInput(playerName.value)
        val parsedSizes = try {
            Triple(
                trimInput(sizeX.value).toFloat(),
                trimInput(sizeY.value).toFloat(),
                trimInput(sizeZ.value).toFloat(),
            )
        } catch (invalid: RuntimeException) {
            notice = "Enter X, Y, and Z sizes from 0.1 to 4.0."
            return
        }
        if (!PlayerSizeOverrideStore.INSTANCE.put(input, parsedSizes.first, parsedSizes.second, parsedSizes.third, entryEnabled)) {
            notice = "Use a Minecraft username and X, Y, Z sizes from 0.1 to 4.0; the local list holds 128 players."
            return
        }
        selectedName = input
        notice = "Saved local override for " + input + "."
    }

    private fun loadSelection() {
        val name = selectedName
        if (name == null) {
            setAxisValues("1.0", "1.0", "1.0")
            return
        }
        val entry = PlayerSizeOverrideStore.INSTANCE.find(name)
        if (entry == null) {
            selectedName = null
            return
        }
        playerName.setValue(entry.playerName)
        setAxisValues(entry.sizeX.toString(), entry.sizeY.toString(), entry.sizeZ.toString())
        entryEnabled = entry.enabled
    }

    private fun setAxisValues(x: String, y: String, z: String) {
        if (this::sizeX.isInitialized) sizeX.setValue(x)
        if (this::sizeY.isInitialized) sizeY.setValue(y)
        if (this::sizeZ.isInitialized) sizeZ.setValue(z)
    }

    private fun trimInput(value: String?): String = value?.trim { it <= ' ' } ?: ""

    private fun axis(value: Float): String = String.format(Locale.ROOT, "%.2f×", value)

    private fun field(x: Int, y: Int, fieldWidth: Int, hint: String, maxLength: Int): EditBox {
        val edit = EditBox(font, x, y, fieldWidth, 19, Component.literal(hint))
        edit.setMaxLength(maxLength)
        edit.setHint(Component.literal(hint))
        return edit
    }

    private fun panel(): Panel {
        val w = minOf(620, maxOf(1, width - 20))
        val h = minOf(420, maxOf(1, height - 20))
        return Panel((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun profileNameWidth(panel: Panel): Int {
        val innerWidth = maxOf(1, panel.w - 24)
        return maxOf(88, minOf(250, Math.round(innerWidth * 0.42f)))
    }

    private fun axisField(panel: Panel, axis: Int): GuiRect {
        val innerWidth = maxOf(1, panel.w - 24)
        val nameWidth = profileNameWidth(panel)
        val axisWidth = maxOf(1, (innerWidth - nameWidth) / 3)
        val cellX = panel.x + 12 + nameWidth + axis * axisWidth
        val cellRight = if (axis == 2) panel.x + panel.w - 12 else cellX + axisWidth
        val labelWidth = minOf(16, maxOf(1, cellRight - cellX))
        return GuiRect(cellX + labelWidth, panel.y + 63, maxOf(50, cellRight - cellX - labelWidth), 19)
    }

    private fun saveRect(panel: Panel): GuiRect = GuiRect(panel.x + 12, panel.y + 88, 66, 20)

    private fun newRect(panel: Panel): GuiRect = GuiRect(panel.x + 86, panel.y + 88, 62, 20)

    private fun deleteRect(panel: Panel): GuiRect = GuiRect(panel.x + 156, panel.y + 88, 62, 20)

    private fun enabledRect(panel: Panel): GuiRect = GuiRect(panel.x + 226, panel.y + 88, 52, 20)

    private fun listRect(panel: Panel): GuiRect =
        GuiRect(panel.x + 12, panel.y + 134, panel.w - 24, maxOf(1, panel.h - 168))

    private fun doneRect(panel: Panel): GuiRect = GuiRect(panel.x + panel.w - 94, panel.y + panel.h - 30, 82, 20)

    private fun button(
        graphics: GuiGraphicsExtractor,
        label: String,
        rect: GuiRect,
        mouseX: Int,
        mouseY: Int,
        danger: Boolean,
    ) {
        val hovered = rect.contains(mouseX, mouseY)
        val color = if (danger) {
            if (hovered) GuiTheme.DANGER_HOVER else GuiTheme.DANGER_BG
        } else {
            if (hovered) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG
        }
        GuiTheme.roundedRect(graphics, rect.x, rect.y, rect.w, rect.h, GuiTheme.RADIUS_SMALL, color)
        graphics.centeredText(
            font,
            label,
            rect.x + rect.w / 2,
            rect.y + 6,
            if (hovered) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
        )
    }

    private fun fit(text: String, maxWidth: Int): String = GuiTheme.fit(font, text, maxWidth)

    private data class Panel(val x: Int, val y: Int, val w: Int, val h: Int)


    companion object {
        private const val ROW_HEIGHT = 23
    }
}
