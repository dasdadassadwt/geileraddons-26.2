package geiler.addons.client.gui

import com.mojang.blaze3d.platform.NativeImage
import geiler.addons.GeilerAddons
import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.ModuleDiagnostics
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.spin.SpinEntry
import geiler.addons.client.spin.SpinWheelRules
import geiler.addons.client.spin.SpinWheelStore
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** `/ga spin`: a lucky wheel with its entry editor. Click the wheel to spin. */
class SpinWheelScreen : Screen(Component.literal("Lucky Wheel")) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { "Spin Wheel" }, 8, 100L, 120)
    private val data = SpinWheelStore.load()
    private val entries: MutableList<SpinEntry> = data.entries

    private var rotation = 0.0
    private var spinFrom = 0.0
    private var spinTo = 0.0
    private var spinStart = 0L
    private var winner = -1
    private var result = -1
    private var scroll = 0

    private var editRow = -1
    private var editChance = false
    private val field = TextFieldModel(SpinWheelRules.MAX_NAME)

    private var wheelTexture: Identifier? = null
    private var hubTexture: Identifier? = null
    private var textureKey = ""

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        diagnostics.enable("entries=${entries.size} style=${data.style}")
    }

    private fun spinning(): Boolean = winner >= 0

    private fun pastel(): Boolean = data.style != CLICK_GUI

    // ---- layout ----

    private class Layout(val panelX: Int, val panelY: Int, val panelH: Int, val centerX: Int, val centerY: Int, val radius: Int)

    private fun layout(): Layout {
        val panelH = minOf(height - 20, 260)
        val room = width - PANEL_W - 40
        val radius = minOf(room / 2 - 8, (height - 50) / 2).coerceIn(50, 150)
        val total = PANEL_W + 16 + radius * 2 + 16
        val panelX = maxOf(6, (width - total) / 2)
        return Layout(panelX, (height - panelH) / 2, panelH, panelX + PANEL_W + 16 + radius, height / 2, radius)
    }

    private fun visibleRows(layout: Layout): Int = maxOf(1, (layout.panelH - 66) / ROW)

    // ---- drawing ----

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        diagnostics.advance()
        val layout = layout()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        advanceSpin()
        drawPanel(graphics, layout, mouseX, mouseY)
        drawWheel(graphics, layout)
    }

    private fun advanceSpin() {
        if (!spinning()) return
        val progress = (System.currentTimeMillis() - spinStart) / SPIN_MS.toDouble()
        rotation = spinFrom + (spinTo - spinFrom) * SpinWheelRules.ease(progress)
        if (progress < 1.0) return
        rotation = spinTo
        result = winner
        winner = -1
        diagnostics.event("LANDED index=$result pointer=${SpinWheelRules.entryAtPointer(entries, rotation)}")
    }

    private fun drawPanel(graphics: GuiGraphicsExtractor, layout: Layout, mouseX: Int, mouseY: Int) {
        val x = layout.panelX
        val y = layout.panelY
        GuiTheme.roundedRectBordered(graphics, x, y, PANEL_W, layout.panelH, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, "Entries (${entries.size}/${SpinWheelRules.MAX_ENTRIES})", x + 8, y + 8, GuiTheme.TEXT_PRIMARY)
        val rows = visibleRows(layout)
        scroll = scroll.coerceIn(0, maxOf(0, entries.size - rows))
        for (row in 0 until minOf(rows, entries.size - scroll)) {
            val index = scroll + row
            val top = y + 22 + row * ROW
            drawField(graphics, x + 6, top, NAME_W, index, false, entries[index].name)
            drawField(graphics, x + CHANCE_X, top, CHANCE_W, index, true, percent(entries[index].chance))
            button(graphics, "x", x + REMOVE_X, top, 14, 14, mouseX, mouseY)
        }
        button(graphics, "Add Entry", x + 6, y + layout.panelH - 40, PANEL_W - 12, 16, mouseX, mouseY)
        button(graphics, "Style: ${data.style}", x + 6, y + layout.panelH - 21, PANEL_W - 12, 16, mouseX, mouseY)
    }

    private fun drawField(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, index: Int, chance: Boolean, value: String) {
        val focused = editRow == index && editChance == chance
        GuiTheme.roundedRectBordered(graphics, x, y, w, 14, 3, GuiTheme.CARD_BG, GuiTheme.CARD_BG,
            if (focused) GuiTheme.SELECTION_OUTLINE else GuiTheme.BORDER)
        if (focused) {
            TextFieldInput.draw(graphics, font, field, x + 3, y + 3, w - 6, GuiTheme.TEXT_PRIMARY, SELECTION, true)
        } else {
            graphics.text(font, GuiTheme.fit(font, value, w - 6), x + 3, y + 3, GuiTheme.TEXT_PRIMARY, false)
        }
    }

    private fun button(graphics: GuiGraphicsExtractor, label: String, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val hover = !spinning() && inside(mouseX.toDouble(), mouseY.toDouble(), x, y, w, h)
        GuiTheme.roundedRect(graphics, x, y, w, h, GuiTheme.RADIUS_SMALL, if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
        graphics.centeredText(font, GuiTheme.fit(font, label, w - 4), x + w / 2, y + (h - font.lineHeight) / 2 + 1,
            if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY)
    }

    private fun segmentColors(): IntArray = if (pastel()) PASTEL else intArrayOf(
        opaque(GuiTheme.SWITCH_ON), opaque(GuiTheme.CARD_BG), opaque(GuiTheme.BUTTON_BG), opaque(GuiTheme.PANEL_TOP))

    private fun opaque(color: Int): Int = color or (0xFF shl 24)

    private fun drawWheel(graphics: GuiGraphicsExtractor, layout: Layout) {
        val cx = layout.centerX
        val cy = layout.centerY
        val radius = layout.radius
        if (entries.isEmpty()) {
            graphics.centeredText(font, "Add an entry to start", cx, cy, TEXT_WHITE)
            return
        }
        ensureTextures(radius)
        val wheel = wheelTexture ?: return
        val size = radius * 2
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(cx.toFloat(), cy.toFloat())
        pose.rotate(Math.toRadians(rotation.mod(360.0)).toFloat())
        graphics.blit(RenderPipelines.GUI_TEXTURED, wheel, -radius, -radius, 0f, 0f, size, size, size, size)
        pose.popMatrix()

        val textColor = if (pastel()) PASTEL_TEXT else opaque(GuiTheme.TEXT_PRIMARY)
        val inner = (radius * HUB_SHARE).toInt()
        for ((index, entry) in entries.withIndex()) {
            // A segment too thin for a line of text stays unlabeled.
            if (Math.toRadians(entry.chance * 3.6) * radius * 0.65 < font.lineHeight * 0.75) continue
            val middle = SpinWheelRules.startAngle(entries, index) + entry.chance * 1.8 + rotation
            pose.pushMatrix()
            pose.translate(cx.toFloat(), cy.toFloat())
            pose.rotate(Math.toRadians(middle.mod(360.0)).toFloat())
            val label = GuiTheme.fit(font, entry.name, radius - inner - 10)
            graphics.text(font, label, inner + (radius - inner - font.width(label)) / 2, -font.lineHeight / 2 + 1, textColor, false)
            pose.popMatrix()
        }

        hubTexture?.let { graphics.blit(RenderPipelines.GUI_TEXTURED, it, cx - radius, cy - radius, 0f, 0f, size, size, size, size) }
        drawPointer(graphics, cx + radius, cy, maxOf(7, radius / 9))
        if (spinning()) {
            val under = SpinWheelRules.entryAtPointer(entries, rotation)
            if (under >= 0) graphics.centeredText(font, entries[under].name, cx, cy + radius + 6, TEXT_WHITE)
        } else {
            drawCurvedHint(graphics, cx, cy, radius * 0.5)
            entries.getOrNull(result)?.let { graphics.centeredText(font, "Winner: ${it.name}", cx, cy + radius + 6, TEXT_WHITE) }
        }
    }

    /** A triangle on the right, pointing into the wheel. */
    private fun drawPointer(graphics: GuiGraphicsExtractor, edge: Int, cy: Int, size: Int) {
        val color = if (pastel()) POINTER else opaque(GuiTheme.TEXT_PRIMARY)
        for (dy in -size..size) {
            val half = size - kotlin.math.abs(dy)
            val tip = edge - size + kotlin.math.abs(dy)
            graphics.fill(tip, cy + dy, tip + half + size / 2 + 3, cy + dy + 1, color)
        }
    }

    /** "Tap to spin" along an arc over the top half of the wheel. */
    private fun drawCurvedHint(graphics: GuiGraphicsExtractor, cx: Int, cy: Int, radius: Double) {
        val text = "Tap to spin"
        val color = if (pastel()) PASTEL_TEXT else opaque(GuiTheme.TEXT_PRIMARY)
        val halfArc = font.width(text) * HINT_SCALE / 2.0 / radius
        var angle = -Math.PI / 2 - halfArc
        val pose = graphics.pose()
        for (char in text) {
            val letter = char.toString()
            val step = font.width(letter) * HINT_SCALE / radius
            val at = angle + step / 2
            pose.pushMatrix()
            pose.translate((cx + kotlin.math.cos(at) * radius).toFloat(), (cy + kotlin.math.sin(at) * radius).toFloat())
            pose.rotate((at + Math.PI / 2).toFloat())
            pose.scale(HINT_SCALE.toFloat(), HINT_SCALE.toFloat())
            graphics.text(font, letter, -font.width(letter) / 2, -font.lineHeight / 2, color, false)
            pose.popMatrix()
            angle += step
        }
    }

    // ---- textures ----

    private fun ensureTextures(radius: Int) {
        val guiScale = minecraft.window.guiScale.toDouble()
        val pixels = (radius * 2 * guiScale).roundToInt().coerceIn(64, 1024)
        val colors = segmentColors()
        val hubColor = if (pastel()) WHITE else opaque(GuiTheme.PANEL_BOTTOM)
        val key = "$pixels/${colors.joinToString()}/$hubColor/${entries.joinToString { it.chance.toString() }}"
        if (key == textureKey && wheelTexture != null) return
        releaseTextures()
        textureKey = key
        val ends = DoubleArray(entries.size)
        var total = 0.0
        for ((index, entry) in entries.withIndex()) {
            total += entry.chance * 3.6
            ends[index] = total
        }
        val used = IntArray(entries.size) { colors[it % colors.size] }
        // With 5, 9, ... entries the last would match its neighbour, the first.
        if (entries.size > 1 && (entries.size - 1) % colors.size == 0) used[entries.size - 1] = colors[1]
        wheelTexture = register("spin_wheel", disc(pixels) { dx, dy, distance ->
            if (distance > pixels / 2.0) return@disc 0
            val angle = Math.toDegrees(atan2(dy, dx)).mod(360.0)
            var index = 0
            while (index < ends.size - 1 && angle >= ends[index]) index++
            used[index]
        })
        val hubRadius = pixels / 2.0 * HUB_SHARE
        hubTexture = register("spin_hub", disc(pixels) { _, _, distance -> if (distance <= hubRadius) hubColor else 0 })
    }

    /** A square image sampled 2x2 per pixel, so circle and segment edges are smooth. */
    private fun disc(size: Int, color: (Double, Double, Double) -> Int): NativeImage {
        val image = NativeImage(size, size, true)
        val half = size / 2.0
        for (y in 0 until size) for (x in 0 until size) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var hits = 0
            for (sample in 0 until 4) {
                val dx = x + 0.25 + 0.5 * (sample % 2) - half
                val dy = y + 0.25 + 0.5 * (sample / 2) - half
                val argb = color(dx, dy, hypot(dx, dy))
                if (argb == 0) continue
                hits++
                a += argb ushr 24
                r += argb shr 16 and 0xFF
                g += argb shr 8 and 0xFF
                b += argb and 0xFF
            }
            if (hits > 0) {
                image.setPixel(x, y, (a / 4 shl 24) or (r / hits shl 16) or (g / hits shl 8) or (b / hits))
            }
        }
        return image
    }

    private fun register(name: String, image: NativeImage): Identifier {
        val id = GeilerAddons.id("dynamic/$name")
        minecraft.textureManager.register(id, DynamicTexture({ "GeilerAddons $name" }, image))
        return id
    }

    private fun releaseTextures() {
        wheelTexture?.let { minecraft.textureManager.release(it) }
        hubTexture?.let { minecraft.textureManager.release(it) }
        wheelTexture = null
        hubTexture = null
        textureKey = ""
    }

    // ---- input ----

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != 0 || spinning()) return true
        val layout = layout()
        val mouseX = event.x()
        val mouseY = event.y()
        val x = layout.panelX
        val y = layout.panelY
        val rows = visibleRows(layout)
        for (row in 0 until minOf(rows, entries.size - scroll)) {
            val index = scroll + row
            val top = y + 22 + row * ROW
            if (inside(mouseX, mouseY, x + 6, top, NAME_W, 14)) return focus(index, false, event, x + 9, NAME_W - 6)
            if (inside(mouseX, mouseY, x + CHANCE_X, top, CHANCE_W, 14)) {
                return focus(index, true, event, x + CHANCE_X + 3, CHANCE_W - 6)
            }
            if (inside(mouseX, mouseY, x + REMOVE_X, top, 14, 14)) {
                commit()
                SpinWheelRules.remove(entries, index)
                changed("REMOVE")
                return true
            }
        }
        commit()
        if (inside(mouseX, mouseY, x + 6, y + layout.panelH - 40, PANEL_W - 12, 16)) {
            if (SpinWheelRules.add(entries, "Entry ${entries.size + 1}")) {
                scroll = maxOf(0, entries.size - rows)
                changed("ADD")
            }
        } else if (inside(mouseX, mouseY, x + 6, y + layout.panelH - 21, PANEL_W - 12, 16)) {
            data.style = if (pastel()) CLICK_GUI else PASTEL_STYLE
            SpinWheelStore.save(data)
            diagnostics.event("STYLE ${data.style}")
        } else if (hypot(mouseX - layout.centerX, mouseY - layout.centerY) <= layout.radius) {
            spin()
        }
        return true
    }

    private fun focus(index: Int, chance: Boolean, event: MouseButtonEvent, textX: Int, textWidth: Int): Boolean {
        if (editRow != index || editChance != chance) {
            commit()
            editRow = index
            editChance = chance
            field.reset(if (chance) percent(entries[index].chance).removeSuffix("%") else entries[index].name)
        }
        TextFieldInput.mousePressed(field, font, event.x(), event.y(), textX, textWidth, false)
        return true
    }

    /** Writes the focused field into its entry and drops the focus. */
    private fun commit() {
        val index = editRow
        editRow = -1
        val entry = entries.getOrNull(index) ?: return
        if (editChance) {
            val value = field.text.trim().removeSuffix("%").replace(',', '.').toDoubleOrNull() ?: return
            if (percent(value) == percent(entry.chance)) return
            SpinWheelRules.setChance(entries, index, value)
        } else {
            val name = field.text.trim()
            if (name.isEmpty() || name == entry.name) return
            entry.name = name
        }
        changed(if (editChance) "CHANCE" else "NAME")
    }

    private fun changed(what: String) {
        result = -1
        SpinWheelStore.save(data)
        diagnostics.event("$what entries=${entries.size} chances=${entries.joinToString(",") { percent(it.chance) }}")
    }

    private fun spin() {
        val random = ThreadLocalRandom.current()
        val picked = SpinWheelRules.pick(entries, random.nextDouble())
        if (picked < 0) return
        result = -1
        spinFrom = rotation.mod(360.0)
        spinTo = SpinWheelRules.landingRotation(entries, picked, spinFrom, random.nextDouble(), SPIN_TURNS)
        spinStart = System.currentTimeMillis()
        winner = picked
        diagnostics.event("SPIN winner=$picked of=${entries.size}")
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (editRow >= 0) {
            when (event.key()) {
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_ESCAPE -> commit()
                else -> TextFieldInput.handleKey(field, event, "spin-wheel")
            }
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (editRow < 0) return false
        TextFieldInput.handleChar(field, event, "spin-wheel")
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        commit()
        scroll -= Math.signum(scrollY).toInt()
        return true
    }

    override fun removed() {
        commit()
        releaseTextures()
        diagnostics.disable()
    }

    private fun inside(mouseX: Double, mouseY: Double, x: Int, y: Int, w: Int, h: Int): Boolean =
        mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

    private fun percent(chance: Double): String = String.format(Locale.ROOT, "%.1f%%", chance)

    private companion object {
        const val PANEL_W = 168
        const val ROW = 17
        const val NAME_W = 98
        const val CHANCE_X = 107
        const val CHANCE_W = 40
        const val REMOVE_X = 149
        const val SPIN_MS = 5_000L
        const val SPIN_TURNS = 6
        const val HUB_SHARE = 0.24
        const val HINT_SCALE = 1.3
        const val PASTEL_STYLE = "Pastel"
        const val CLICK_GUI = "Click GUI"
        const val SELECTION = 0x804080FF.toInt()
        const val TEXT_WHITE = -1
        const val WHITE = -1
        const val PASTEL_TEXT = 0xFF2B2B38.toInt()
        const val POINTER = 0xFF3A3A4A.toInt()
        // Blue, green, yellow, purple.
        val PASTEL = intArrayOf(0xFFA9CDF2.toInt(), 0xFFB6E7B0.toInt(), 0xFFFBEFA3.toInt(), 0xFFD2BBF2.toInt())
    }
}
