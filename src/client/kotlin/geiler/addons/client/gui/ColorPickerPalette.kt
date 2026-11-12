package geiler.addons.client.gui

import geiler.addons.client.module.ColorSetting
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Shared painting and pointer behavior for every HSV/alpha color picker in the mod. */
object ColorPickerPalette {
    private const val CHECKER_SIZE = 4

    @JvmStatic
    fun saturationSquare(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        setting: ColorSetting,
    ) {
        val pure = hueColor(setting.hue())
        for (i in 0 until width) {
            val top = GuiTheme.lerpColor(0xFFFFFFFF.toInt(), pure, i / max(1, width - 1).toFloat())
            graphics.fillGradient(x + i, y, x + i + 1, y + height, top, 0xFF000000.toInt())
        }
        crosshair(
            graphics,
            x + Math.round(setting.saturation() * (width - 1)),
            y + Math.round((1 - setting.brightness()) * (height - 1)),
        )
    }

    @JvmStatic
    fun hueBar(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        setting: ColorSetting,
    ) {
        for (i in 0 until width) graphics.fill(x + i, y, x + i + 1, y + height, hueColor(i / width.toFloat()))
        marker(graphics, x, y, height, Math.round(setting.hue() * (width - 1)))
    }

    @JvmStatic
    fun alphaBar(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        setting: ColorSetting,
    ) {
        checkerboard(graphics, x, y, width, height)
        val opaque = setting.opaqueArgb()
        for (i in 0 until width) {
            val alpha = Math.round(255 * i / max(1, width - 1).toFloat())
            graphics.fill(x + i, y, x + i + 1, y + height, (alpha shl 24) or (opaque and 0x00FFFFFF))
        }
        marker(graphics, x, y, height, Math.round(setting.alpha() / 255.0f * (width - 1)))
    }

    @JvmStatic
    fun checkerboard(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        for (row in 0 until height step CHECKER_SIZE) {
            for (column in 0 until width step CHECKER_SIZE) {
                val light = ((row / CHECKER_SIZE) + (column / CHECKER_SIZE)) % 2 == 0
                graphics.fill(
                    x + column,
                    y + row,
                    min(x + column + CHECKER_SIZE, x + width),
                    min(y + row + CHECKER_SIZE, y + height),
                    if (light) 0xFF9A9A9A.toInt() else 0xFF5E5E5E.toInt(),
                )
            }
        }
    }

    @JvmStatic
    fun applySquare(setting: ColorSetting, mouseX: Double, mouseY: Double, x: Int, y: Int, width: Int, height: Int) {
        setting.setSaturationBrightness(fraction(mouseX, x, width), 1 - fraction(mouseY, y, height))
    }

    @JvmStatic
    fun applyHue(setting: ColorSetting, mouseX: Double, x: Int, width: Int) {
        setting.setHue(fraction(mouseX, x, width))
    }

    @JvmStatic
    fun applyAlpha(setting: ColorSetting, mouseX: Double, x: Int, width: Int) {
        setting.setChannel(ColorSetting.Channel.ALPHA, Math.round(fraction(mouseX, x, width) * 255))
    }

    private fun fraction(position: Double, start: Int, size: Int): Float =
        max(0.0, min(1.0, (position - start) / max(1, size - 1))).toFloat()

    private fun crosshair(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        graphics.fill(x - 3, y - 1, x - 1, y, 0xFF000000.toInt())
        graphics.fill(x + 2, y - 1, x + 4, y, 0xFF000000.toInt())
        graphics.fill(x - 1, y - 3, x, y - 1, 0xFF000000.toInt())
        graphics.fill(x - 1, y + 2, x, y + 4, 0xFF000000.toInt())
        graphics.fill(x - 2, y - 2, x + 3, y - 1, 0xFFFFFFFF.toInt())
        graphics.fill(x - 2, y + 1, x + 3, y + 2, 0xFFFFFFFF.toInt())
        graphics.fill(x - 2, y - 1, x - 1, y + 1, 0xFFFFFFFF.toInt())
        graphics.fill(x + 2, y - 1, x + 3, y + 1, 0xFFFFFFFF.toInt())
    }

    private fun marker(graphics: GuiGraphicsExtractor, x: Int, y: Int, height: Int, offset: Int) {
        val markerX = x + offset
        graphics.fill(markerX - 1, y - 2, markerX + 2, y + height + 2, 0xFF000000.toInt())
        graphics.fill(markerX, y - 1, markerX + 1, y + height + 1, 0xFFFFFFFF.toInt())
    }

    private fun hueColor(hue: Float): Int {
        val sector = ((hue - floor(hue)) * 6.0f)
        val rising = sector % 1
        val up = Math.round(rising * 255)
        val down = 255 - up
        return when (sector.toInt()) {
            0 -> 0xFFFF0000.toInt() or (up shl 8)
            1 -> 0xFF00FF00.toInt() or (down shl 16)
            2 -> 0xFF00FF00.toInt() or up
            3 -> 0xFF0000FF.toInt() or (down shl 8)
            4 -> 0xFF0000FF.toInt() or (up shl 16)
            else -> 0xFFFF0000.toInt() or down
        }
    }
}
