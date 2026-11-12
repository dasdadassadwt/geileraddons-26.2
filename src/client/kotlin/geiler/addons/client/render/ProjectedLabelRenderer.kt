package geiler.addons.client.render

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.Vec3
import java.util.BitSet

/** Shared themed label treatment and per-frame placement for labels projected from the world. */
object ProjectedLabelRenderer {
    private const val CELL_SIZE = 16
    private val verticalOffsets = intArrayOf(0, -1, 1, -2, 2, -3, 3, -4, 4)
    private val projection = ThreadLocal.withInitial { FloatArray(2) }
    private val occupied = BitSet()
    private var cellsWide = 0
    private var screenWidth = 0
    private var screenHeight = 0

    /** Called once before the mod's projected label elements are drawn each HUD frame. */
    @JvmStatic
    fun beginFrame() {
        val minecraft = Minecraft.getInstance()
        screenWidth = minecraft.window.guiScaledWidth
        screenHeight = minecraft.window.guiScaledHeight
        cellsWide = Math.max(1, (screenWidth + CELL_SIZE - 1) / CELL_SIZE)
        occupied.clear()
    }

    /** Draws a centered, unboxed world label; the supplied color remains its semantic cue. */
    @JvmStatic
    fun draw(
        graphics: GuiGraphicsExtractor,
        camera: Camera?,
        font: Font?,
        world: Vec3?,
        text: String?,
        signalColor: Int,
        requestedScale: Float,
    ): Boolean {
        if (text.isNullOrBlank() || camera == null || font == null || world == null) return false
        if (screenWidth <= 0 || screenHeight <= 0) beginFrame()
        val projected = projection.get()
        if (!WorldToScreen.projectInto(camera, world, projected)) return false

        val scale = if (requestedScale.isFinite()) Math.max(0.5f, Math.min(3.0f, requestedScale)) else 1.0f
        val maxWidth = Math.max(1, screenWidth - 8)
        val shown = fit(font, text, Math.max(1, (maxWidth / scale).toInt()))
        val width = Math.min(maxWidth, Math.ceil((font.width(shown) * scale).toDouble()).toInt())
        val height = Math.max(1, Math.ceil((font.lineHeight * scale).toDouble()).toInt())
        if (width >= screenWidth || height >= screenHeight) return false
        if (projected[0] < -width || projected[0] > screenWidth + width ||
            projected[1] < -height || projected[1] > screenHeight + height
        ) return false

        val left = clamp(
            Math.round(projected[0] - width / 2.0f),
            2,
            Math.max(2, screenWidth - width - 2),
        )
        val top = Math.round(projected[1] - height - 2)
        for (offset in verticalOffsets) {
            val candidateTop = clamp(
                top + offset * (height + 2),
                2,
                Math.max(2, screenHeight - height - 2),
            )
            if (!isFree(left, candidateTop, width, height)) continue
            drawText(graphics, font, shown, left, candidateTop, scale, signalColor)
            occupy(left, candidateTop, width, height)
            return true
        }
        return false
    }

    /** Draws unboxed text centered exactly on a projected world position. */
    @JvmStatic
    fun drawFloatingText(
        graphics: GuiGraphicsExtractor,
        camera: Camera?,
        font: Font?,
        world: Vec3?,
        text: String?,
        color: Int,
        requestedScale: Float,
    ): Boolean {
        if (text.isNullOrBlank() || camera == null || font == null || world == null) return false
        val minecraft = Minecraft.getInstance()
        val width = minecraft.window.guiScaledWidth
        val height = minecraft.window.guiScaledHeight
        if (width <= 0 || height <= 0) return false
        val projected = projection.get()
        if (!WorldToScreen.projectInto(camera, world, projected)) return false

        val scale = if (requestedScale.isFinite()) Math.max(0.55f, Math.min(2.0f, requestedScale)) else 1.0f
        val textWidth = font.width(text) * scale
        val textHeight = font.lineHeight * scale
        if (projected[0] + textWidth / 2 < 0 || projected[0] - textWidth / 2 > width ||
            projected[1] + textHeight / 2 < 0 || projected[1] - textHeight / 2 > height
        ) return false

        graphics.pose().pushMatrix()
        graphics.pose().translate(projected[0] - textWidth / 2, projected[1] - textHeight / 2)
        graphics.pose().scale(scale, scale)
        graphics.text(font, text, 0, 0, color)
        graphics.pose().popMatrix()
        return true
    }

    private fun drawText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Int,
        y: Int,
        scale: Float,
        color: Int,
    ) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), y.toFloat())
        graphics.pose().scale(scale, scale)
        graphics.text(font, text, 0, 0, color)
        graphics.pose().popMatrix()
    }

    private fun isFree(x: Int, y: Int, width: Int, height: Int): Boolean {
        val minX = Math.max(0, x / CELL_SIZE)
        val maxX = Math.max(minX, (x + width - 1) / CELL_SIZE)
        val minY = Math.max(0, y / CELL_SIZE)
        val maxY = Math.max(minY, (y + height - 1) / CELL_SIZE)
        for (cellY in minY..maxY) {
            val base = cellY * cellsWide
            for (cellX in minX..maxX) {
                if (occupied.get(base + cellX)) return false
            }
        }
        return true
    }

    private fun occupy(x: Int, y: Int, width: Int, height: Int) {
        val minX = Math.max(0, x / CELL_SIZE)
        val maxX = Math.max(minX, (x + width - 1) / CELL_SIZE)
        val minY = Math.max(0, y / CELL_SIZE)
        val maxY = Math.max(minY, (y + height - 1) / CELL_SIZE)
        for (cellY in minY..maxY) {
            val base = cellY * cellsWide
            for (cellX in minX..maxX) occupied.set(base + cellX)
        }
    }

    private fun fit(font: Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        val ellipsis = "…"
        val prefixWidth = Math.max(0, maxWidth - font.width(ellipsis))
        return font.plainSubstrByWidth(text, prefixWidth) + ellipsis
    }

    private fun clamp(value: Int, min: Int, max: Int): Int = Math.max(min, Math.min(max, value))
}
