package geiler.addons.client.gui

import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.function.IntConsumer

/** Shared HSV and alpha color picker for screens that edit colors outside Click GUI settings. */
class GuiColorPickerScreen(
    private val parent: Screen?,
    label: String?,
    argb: Int,
    private val alphaEditable: Boolean,
    onApply: IntConsumer?,
) : Screen(Component.literal("Color Picker")) {
    constructor(parent: Screen?, label: String?, argb: Int, onApply: IntConsumer?) :
        this(parent, label, argb, true, onApply)

    private val label = if (label.isNullOrBlank()) "Color" else label
    private val color = if (alphaEditable) {
        ColorSetting("picker", "picker", (argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF,
            argb and 0xFF, argb ushr 24)
    } else {
        ColorSetting("picker", "picker", (argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)
    }
    private val onApply = onApply ?: IntConsumer { _ -> }
    private var hex: EditBox? = null
    private var dragging: PickerPart? = null

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        val bounds = pickerBounds()
        hex = addRenderableWidget(EditBox(font, bounds.x + 43, bounds.hexY, 82, 17,
            Component.literal("RRGGBBAA")))
        hex!!.setMaxLength(if (alphaEditable) 8 else 6)
        hex!!.setValue(color.hex())
        hex!!.setTooltip(Tooltip.create(Component.literal(
            if (alphaEditable) "Hex color in RRGGBBAA order, including alpha." else "Hex color in RRGGBB order.",
        )))
        hex!!.setResponder { value -> color.setHex(value); Unit }
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        VisualModule.INSTANCE.refreshTheme()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val bounds = pickerBounds()
        GuiTheme.roundedRectBordered(
            graphics, bounds.x, bounds.y, bounds.w, bounds.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        graphics.centeredText(font, label, width / 2, bounds.y + 10, GuiTheme.TEXT_PRIMARY)
        graphics.centeredText(
            font, if (alphaEditable) "Choose a color · hue · opacity" else "Choose a color · hue",
            width / 2, bounds.y + 23, GuiTheme.TEXT_MUTED,
        )
        renderSaturationSquare(graphics, bounds.square!!)
        renderHueBar(graphics, bounds.hue!!)
        if (alphaEditable) renderAlphaBar(graphics, bounds.alpha!!)
        graphics.text(font, "Hex", bounds.x + 18, bounds.hexY + 5, GuiTheme.TEXT_MUTED)
        val preview = Rect(bounds.x + 139, bounds.hexY, 34, 17)
        ColorPickerPalette.checkerboard(graphics, preview.x, preview.y, preview.w, preview.h)
        GuiTheme.roundedRectBordered(
            graphics, preview.x, preview.y, preview.w, preview.h, 3,
            color.argb(), color.argb(), GuiTheme.BORDER,
        )
        drawButton(graphics, bounds.cancel!!, "Cancel", mouseX, mouseY)
        drawButton(graphics, bounds.done!!, "Apply", mouseX, mouseY)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == 0) {
            val bounds = pickerBounds()
            if (bounds.square!!.contains(event.x(), event.y())) {
                dragging = PickerPart.SQUARE
                applyPicker(event.x(), event.y())
                return true
            }
            if (bounds.hue!!.contains(event.x(), event.y())) {
                dragging = PickerPart.HUE
                applyPicker(event.x(), event.y())
                return true
            }
            if (alphaEditable && bounds.alpha!!.contains(event.x(), event.y())) {
                dragging = PickerPart.ALPHA
                applyPicker(event.x(), event.y())
                return true
            }
            if (bounds.done!!.contains(event.x(), event.y())) {
                onApply.accept(color.argb())
                minecraft!!.gui.setScreen(parent)
                return true
            }
            if (bounds.cancel!!.contains(event.x(), event.y())) {
                minecraft!!.gui.setScreen(parent)
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (dragging != null && event.button() == 0) {
            applyPicker(event.x(), event.y())
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (dragging != null) {
            dragging = null
            return true
        }
        return super.mouseReleased(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            minecraft!!.gui.setScreen(parent)
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft!!.gui.setScreen(parent)
    }

    private fun applyPicker(mouseX: Double, mouseY: Double) {
        val bounds = pickerBounds()
        val square = bounds.square!!
        val hue = bounds.hue!!
        val alpha = bounds.alpha!!
        when (dragging!!) {
            PickerPart.SQUARE -> ColorPickerPalette.applySquare(
                color, mouseX, mouseY, square.x, square.y, square.w, square.h,
            )
            PickerPart.HUE -> ColorPickerPalette.applyHue(color, mouseX, hue.x, hue.w)
            PickerPart.ALPHA -> ColorPickerPalette.applyAlpha(color, mouseX, alpha.x, alpha.w)
        }
        hex?.setValue(color.hex())
    }

    private fun renderSaturationSquare(graphics: GuiGraphicsExtractor, square: Rect) {
        ColorPickerPalette.saturationSquare(graphics, square.x, square.y, square.w, square.h, color)
    }

    private fun renderHueBar(graphics: GuiGraphicsExtractor, bar: Rect) {
        ColorPickerPalette.hueBar(graphics, bar.x, bar.y, bar.w, bar.h, color)
    }

    private fun renderAlphaBar(graphics: GuiGraphicsExtractor, bar: Rect) {
        ColorPickerPalette.alphaBar(graphics, bar.x, bar.y, bar.w, bar.h, color)
    }

    private fun drawButton(graphics: GuiGraphicsExtractor, rect: Rect, text: String, mouseX: Int, mouseY: Int) {
        val fill = if (rect.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG
        GuiTheme.roundedRectBordered(
            graphics, rect.x, rect.y, rect.w, rect.h, GuiTheme.RADIUS_SMALL,
            fill, fill, GuiTheme.BORDER,
        )
        graphics.centeredText(
            font, text, rect.x + rect.w / 2,
            rect.y + (rect.h - font.lineHeight) / 2, GuiTheme.TEXT_ON_ACCENT,
        )
    }

    private fun pickerBounds(): Rect {
        val x = (width - PANEL_WIDTH) / 2
        val y = (height - PANEL_HEIGHT) / 2
        val innerX = x + 20
        val innerW = PANEL_WIDTH - 40
        val square = Rect(innerX, y + 40, innerW, 64)
        val hue = Rect(innerX, y + 109, innerW, 8)
        val alpha = Rect(innerX, y + 123, innerW, 8)
        val hexY = y + if (alphaEditable) 140 else 126
        val cancel = Rect(x + 20, y + 169, (PANEL_WIDTH - 48) / 2, 21)
        val done = Rect(x + 28 + (PANEL_WIDTH - 48) / 2, y + 169, (PANEL_WIDTH - 48) / 2, 21)
        return Rect(x, y, PANEL_WIDTH, PANEL_HEIGHT, square, hue, alpha, hexY, cancel, done)
    }

    private enum class PickerPart { SQUARE, HUE, ALPHA }

    private data class Rect(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val square: Rect? = null,
        val hue: Rect? = null,
        val alpha: Rect? = null,
        val hexY: Int = 0,
        val cancel: Rect? = null,
        val done: Rect? = null,
    ) {
        fun contains(px: Double, py: Double): Boolean = px >= x && px < x + w && py >= y && py < y + h
    }

    private companion object {
        const val PANEL_WIDTH = 304
        const val PANEL_HEIGHT = 202
    }
}
