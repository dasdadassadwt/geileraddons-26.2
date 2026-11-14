package geiler.addons.client.macros.module

import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.macros.rotation.RotationConfig
import geiler.addons.client.module.ModulePreview
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.util.Util
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The curve of Distance-Based Scaling: turn speed multiplier over the distance to the target. A double click adds a
 * point, a drag moves one, a right click deletes it (two always stay), and a drag with shift held moves them all up or
 * down. Between two points the multiplier goes in a straight line; before the first and after the last it stays.
 */
class DistanceGraphPreview(
    private val settings: () -> RotationConfig.Settings,
    private val enabled: () -> Boolean,
    private val groupName: String,
) : ModulePreview {
    private var dragging = -1
    private var draggingAll = false

    override fun previewGroupName(): String = groupName

    override fun previewHeight(width: Int, availableHeight: Int): Int = HEIGHT + 8

    private fun points(): MutableList<DoubleArray> = settings().distancePoints

    // Geometry in the content box: x0, y0 is its top-left corner.
    private fun left(x0: Int) = x0 + PAD_LEFT
    private fun right(x0: Int, width: Int) = x0 + width - PAD_RIGHT
    private fun top(y0: Int) = y0 + PAD_TOP
    private fun bottom(y0: Int, height: Int) = y0 + height - PAD_BOTTOM

    private fun pixelX(distance: Double, x0: Int, width: Int): Double =
        left(x0) + distance / RotationConfig.DISTANCE_MAX * (right(x0, width) - left(x0))

    private fun pixelY(factor: Double, y0: Int, height: Int): Double {
        val share = (factor - RotationConfig.DISTANCE_FACTOR_LOW) / (RotationConfig.DISTANCE_FACTOR_HIGH - RotationConfig.DISTANCE_FACTOR_LOW)
        return bottom(y0, height) - share * (bottom(y0, height) - top(y0))
    }

    private fun distanceAt(x: Double, x0: Int, width: Int): Double =
        Mth.clamp((x - left(x0)) / (right(x0, width) - left(x0)) * RotationConfig.DISTANCE_MAX, 0.0, RotationConfig.DISTANCE_MAX)

    private fun factorAt(y: Double, y0: Int, height: Int): Double {
        val share = (bottom(y0, height) - y) / (bottom(y0, height) - top(y0))
        return Mth.clamp(
            RotationConfig.DISTANCE_FACTOR_LOW + share * (RotationConfig.DISTANCE_FACTOR_HIGH - RotationConfig.DISTANCE_FACTOR_LOW),
            RotationConfig.DISTANCE_FACTOR_LOW, RotationConfig.DISTANCE_FACTOR_HIGH,
        )
    }

    /** The point under the pointer, or -1. */
    private fun pointAt(x: Double, y: Double, x0: Int, y0: Int, width: Int, height: Int): Int {
        val points = points()
        var found = -1
        var best = GRAB_PIXELS * GRAB_PIXELS
        for (i in points.indices) {
            val dx = pixelX(points[i][0], x0, width) - x
            val dy = pixelY(points[i][1], y0, height) - y
            if (dx * dx + dy * dy <= best) {
                best = dx * dx + dy * dy
                found = i
            }
        }
        return found
    }

    override fun drawPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, width: Int, height: Int): Boolean {
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        val mouseX = minecraft.mouseHandler.xpos() * window.guiScaledWidth / window.screenWidth
        val mouseY = minecraft.mouseHandler.ypos() * window.guiScaledHeight / window.screenHeight
        val inside = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
        val dim = if (enabled()) 1.0f else 0.45f
        val muted = GuiTheme.withOpacity(GuiTheme.TEXT_MUTED, dim)

        GuiTheme.roundedRect(graphics, x, y, width, height, 3, GuiTheme.CARD_BG_HOVER)
        var factor = RotationConfig.DISTANCE_FACTOR_LOW
        while (factor <= RotationConfig.DISTANCE_FACTOR_HIGH + 1.0E-6) {
            val line = pixelY(factor, y, height).roundToInt()
            graphics.fill(left(x), line, right(x, width), line + 1, GRID)
            val label = String.format(Locale.ROOT, "%.1fx", factor)
            graphics.text(font, label, left(x) - 4 - font.width(label), line - 4, muted)
            factor += 0.2
        }
        val baseline = pixelY(1.0, y, height).roundToInt()
        graphics.fill(left(x), baseline, right(x, width), baseline + 1, BASELINE)
        var distance = 0
        while (distance <= RotationConfig.DISTANCE_MAX.toInt()) {
            val gx = pixelX(distance.toDouble(), x, width).roundToInt()
            graphics.fill(gx, top(y), gx + 1, bottom(y, height), GRID)
            val label = distance.toString() + if (distance == RotationConfig.DISTANCE_MAX.toInt()) " m" else ""
            graphics.text(font, label, gx - font.width(label) / 2, bottom(y, height) + 5, muted)
            distance += 5
        }

        // The line, a pixel column at a time.
        val settings = settings()
        val lineColor = GuiTheme.withOpacity(LINE, dim)
        var previous = pixelY(settings.curveFactor(0.0), y, height).roundToInt()
        for (column in left(x)..right(x, width)) {
            val row = pixelY(settings.curveFactor(distanceAt(column.toDouble(), x, width)), y, height).roundToInt()
            graphics.fill(column, min(previous, row), column + 1, max(previous, row) + 1, lineColor)
            previous = row
        }

        val hovered = if (inside) pointAt(mouseX, mouseY, x, y, width, height) else -1
        val points = points()
        for (i in points.indices) {
            val px = pixelX(points[i][0], x, width).roundToInt()
            val py = pixelY(points[i][1], y, height).roundToInt()
            val size = if (i == dragging || i == hovered) 3 else 2
            val color = if (i == dragging) GuiTheme.SLIDER_FILL else 0xFFF4F6F8.toInt()
            graphics.fill(px - size, py - size, px + size + 1, py + size + 1, GuiTheme.withOpacity(color, dim))
        }
        if (hovered >= 0 || dragging in points.indices) {
            val point = points[if (dragging >= 0) dragging else hovered]
            val label = String.format(Locale.ROOT, "%.1f m · %.2fx", point[0], point[1])
            graphics.text(font, label, right(x, width) - font.width(label), y + 3, GuiTheme.TEXT_PRIMARY)
        }
        return true
    }

    override fun previewLines(font: Font, width: Int): List<Component> = emptyList()

    override fun previewMousePressed(
        localX: Int, localY: Int, width: Int, height: Int, button: Int, doubleClick: Boolean, shift: Boolean,
    ): Boolean {
        val points = points()
        var index = pointAt(localX.toDouble(), localY.toDouble(), 0, 0, width, height)
        if (button == 1) {
            if (index >= 0 && points.size > 2) points.removeAt(index)
            return true
        }
        if (index < 0 && doubleClick) {
            val point = doubleArrayOf(distanceAt(localX.toDouble(), 0, width), factorAt(localY.toDouble(), 0, height))
            points.add(point)
            points.sortWith { a, b -> a[0].compareTo(b[0]) }
            index = points.indexOf(point)
        }
        dragging = index
        draggingAll = index >= 0 && shift
        return index >= 0
    }

    override fun previewMouseDragged(localX: Int, localY: Int, width: Int, height: Int, button: Int) {
        val points = points()
        if (dragging < 0 || dragging >= points.size) return
        if (draggingAll) {
            val change = factorAt(localY.toDouble(), 0, height) - points[dragging][1]
            for (point in points) {
                point[1] = Mth.clamp(point[1] + change, RotationConfig.DISTANCE_FACTOR_LOW, RotationConfig.DISTANCE_FACTOR_HIGH)
            }
            return
        }
        val point = points[dragging]
        val low = if (dragging > 0) points[dragging - 1][0] + MIN_GAP else 0.0
        val high = if (dragging < points.size - 1) points[dragging + 1][0] - MIN_GAP else RotationConfig.DISTANCE_MAX
        point[0] = Math.round(Mth.clamp(distanceAt(localX.toDouble(), 0, width), low, max(low, high)) * 10.0) / 10.0
        point[1] = Math.round(factorAt(localY.toDouble(), 0, height) * 100.0) / 100.0
    }

    override fun previewMouseReleased(localX: Int, localY: Int, width: Int, height: Int, button: Int) {
        dragging = -1
        draggingAll = false
    }

    companion object {
        const val HEIGHT = 128
        private const val PAD_LEFT = 30
        private const val PAD_RIGHT = 10
        private const val PAD_TOP = 8
        private const val PAD_BOTTOM = 18
        private const val GRAB_PIXELS = 6.0
        private const val MIN_GAP = 0.25
        private val LINE = 0xFFD7DBE2.toInt()
        private val BASELINE = 0x40FFFFFF
        private val GRID = 0x14FFFFFF
    }
}

/**
 * A switch for something risky: turning it on takes holding the button down until the bar has filled, turning it off one
 * click. Letting go early does nothing.
 */
class HoldSwitchPreview(
    private val on: () -> Boolean,
    private val setter: (Boolean) -> Unit,
    private val groupName: String,
    private val onText: String,
    private val offText: String,
) : ModulePreview {
    private var pressedAt = 0L

    override fun previewGroupName(): String = groupName

    override fun previewHeight(width: Int, availableHeight: Int): Int = BUTTON_HEIGHT + 8

    override fun drawPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, width: Int, height: Int): Boolean {
        val enabled = on()
        var progress = 0.0f
        if (pressedAt != 0L && !enabled) {
            progress = min(1.0f, (Util.getMillis() - pressedAt) / HOLD_MILLIS.toFloat())
            if (progress >= 1.0f) {
                setter(true)
                pressedAt = 0L
            }
        }
        GuiTheme.roundedRect(graphics, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, 3,
            GuiTheme.withOpacity(DANGER, if (enabled) 0.25f else 0.06f))
        if (progress > 0.0f) {
            val fillRight = x + (BUTTON_WIDTH * progress).roundToInt()
            GuiTheme.roundedRect(graphics, x, y, max(2, fillRight - x), BUTTON_HEIGHT, 3, GuiTheme.withOpacity(DANGER, 0.35f))
        }
        GuiTheme.roundedRectOutline(graphics, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, 3, GuiTheme.withOpacity(DANGER, 0.7f))
        val label = if (enabled) "Click to turn off" else if (progress > 0.0f) "Keep holding..." else "Hold to enable"
        graphics.text(font, label, x + (BUTTON_WIDTH - font.width(label)) / 2, y + (BUTTON_HEIGHT - 8) / 2, DANGER)
        val status = if (enabled) onText else offText
        graphics.text(font, status, x + BUTTON_WIDTH + 8, y + (BUTTON_HEIGHT - 8) / 2, if (enabled) DANGER else GuiTheme.TEXT_MUTED)
        return true
    }

    override fun previewLines(font: Font, width: Int): List<Component> = emptyList()

    override fun previewMousePressed(
        localX: Int, localY: Int, width: Int, height: Int, button: Int, doubleClick: Boolean, shift: Boolean,
    ): Boolean {
        if (button != 0 || localX < 0 || localX >= BUTTON_WIDTH || localY < 0 || localY >= BUTTON_HEIGHT) return false
        if (on()) setter(false) else pressedAt = Util.getMillis()
        return true
    }

    override fun previewMouseReleased(localX: Int, localY: Int, width: Int, height: Int, button: Int) {
        pressedAt = 0L
    }

    companion object {
        private const val HOLD_MILLIS = 1500L
        private const val BUTTON_WIDTH = 110
        private const val BUTTON_HEIGHT = 20
        private val DANGER = 0xFFE5484D.toInt()
    }
}
