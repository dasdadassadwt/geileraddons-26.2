package geiler.addons.client.render

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.BitSet

/** Shared themed label treatment and per-frame placement for labels projected from the world. */
object ProjectedLabelRenderer {
    private const val CELL_SIZE = 16
    private val verticalOffsets = intArrayOf(0, -1, 1, -2, 2, -3, 3, -4, 4)
    private val projection = ThreadLocal.withInitial { FloatArray(2) }
    private val occupied = BitSet()
    const val NO_OFFSET = Int.MIN_VALUE
    private const val MAX_REMEMBERED = 512

    /** Time constant of the glide between stacking slots; about a tenth of a second to settle. */
    private const val EASE_MS = 60.0
    private const val MAX_FRAME_MS = 100f

    /** Where a label sat last frame: its slot and the pixel offset actually drawn (which glides toward the slot). */
    private class Placed(val slot: Int, val px: Float)

    private var lastPlaced = HashMap<Long, Placed>()
    private var thisFramePlaced = HashMap<Long, Placed>()
    private var cellsWide = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var lastFrameNanos = 0L
    private var frameMs = 16f

    /** Times a label moved to another stacking slot, saturating; for the owning module's diagnostics line. */
    @JvmStatic
    var slotChanges = 0L
        private set

    /** Called once before the mod's projected label elements are drawn each HUD frame. */
    @JvmStatic
    fun beginFrame() {
        val minecraft = Minecraft.getInstance()
        screenWidth = minecraft.window.guiScaledWidth
        screenHeight = minecraft.window.guiScaledHeight
        cellsWide = Math.max(1, (screenWidth + CELL_SIZE - 1) / CELL_SIZE)
        occupied.clear()
        val now = System.nanoTime()
        frameMs = if (lastFrameNanos == 0L) 16f else Math.min(MAX_FRAME_MS, (now - lastFrameNanos) / 1_000_000f)
        lastFrameNanos = now
        // What each label used this frame is what it prefers next frame, so a label only moves when something now sits on its slot.
        val previous = lastPlaced
        lastPlaced = thisFramePlaced
        thisFramePlaced = previous
        thisFramePlaced.clear()
    }

    /** Moves [current] toward [target] by the share of the gap [elapsedMs] covers; a gap under a quarter pixel just lands. */
    @JvmStatic
    fun ease(current: Float, target: Float, elapsedMs: Float): Float {
        if (!current.isFinite() || !target.isFinite()) return target
        val gap = target - current
        if (Math.abs(gap) < 0.25f) return target
        val share = 1.0 - Math.exp(-Math.max(0f, elapsedMs) / EASE_MS)
        return current + gap * share.toFloat()
    }

    /** The box an entity is drawn in this frame (its tick box moved to the interpolated position), so labels follow it as smoothly as the camera. */
    @JvmStatic
    fun entityBox(entity: Entity): AABB {
        val partial = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(true)
        return entity.boundingBox.move(entity.getPosition(partial).subtract(entity.position()))
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

        // Whole-pixel rounding made labels step 2-4 real pixels at a time as the camera moved; keep the fraction.
        val left = clampF(projected[0] - width / 2.0f, 2f, Math.max(2, screenWidth - width - 2).toFloat())
        val top = projected[1] - height - 2f
        val slotHeight = (height + 2).toFloat()
        val key = labelKey(world.x, world.y, world.z)
        val minTop = 2f
        val maxTop = Math.max(2, screenHeight - height - 2).toFloat()
        fun topFor(offset: Int) = clampF(top + offset * slotHeight, minTop, maxTop)
        val previous = lastPlaced[key]
        val offset = pickOffset(previous?.slot ?: NO_OFFSET) { isFree(left.toInt(), topFor(it).toInt(), width, height) } ?: return false
        if (previous != null && previous.slot != offset && slotChanges < Long.MAX_VALUE) slotChanges++
        // The slot is what other labels are tested against; the drawn height glides there so a changed slot is not a jump.
        val slotPx = offset * slotHeight
        val shownPx = if (previous == null) slotPx else ease(previous.px, slotPx, frameMs)
        val drawTop = clampF(top + shownPx, minTop, maxTop)
        drawText(graphics, font, shown, left, drawTop, scale, signalColor)
        occupy(left.toInt(), topFor(offset).toInt(), width, height)
        if (thisFramePlaced.size < MAX_REMEMBERED) thisFramePlaced[key] = Placed(offset, shownPx)
        return true
    }

    /** The slot to use: the one this label had last frame while it is still free, else the first free one in scan order. */
    @JvmStatic
    fun pickOffset(preferred: Int, isFree: (Int) -> Boolean): Int? {
        if (preferred in verticalOffsets && isFree(preferred)) return preferred
        for (offset in verticalOffsets) if (isFree(offset)) return offset
        return null
    }

    /** Identity of a label across frames: its world block. The text is no help, it carries a distance that changes every step. */
    @JvmStatic
    fun labelKey(x: Double, y: Double, z: Double): Long =
        (Math.floor(x).toLong() * 73856093L) xor (Math.floor(y).toLong() * 19349663L) xor (Math.floor(z).toLong() * 83492791L)

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
        pixelOffsetY: Float = 0f,
        minScale: Float = 0.55f,
        maxScale: Float = 2.0f,
    ): Boolean {
        if (text.isNullOrBlank() || camera == null || font == null || world == null) return false
        val minecraft = Minecraft.getInstance()
        val width = minecraft.window.guiScaledWidth
        val height = minecraft.window.guiScaledHeight
        if (width <= 0 || height <= 0) return false
        val projected = projection.get()
        if (!WorldToScreen.projectInto(camera, world, projected)) return false

        val scale = if (requestedScale.isFinite()) Math.max(minScale, Math.min(maxScale, requestedScale)) else 1.0f
        val textWidth = font.width(text) * scale
        val textHeight = font.lineHeight * scale
        if (projected[0] + textWidth / 2 < 0 || projected[0] - textWidth / 2 > width ||
            projected[1] + textHeight / 2 < 0 || projected[1] - textHeight / 2 > height
        ) return false

        graphics.pose().pushMatrix()
        graphics.pose().translate(projected[0] - textWidth / 2, projected[1] - textHeight / 2 + pixelOffsetY)
        graphics.pose().scale(scale, scale)
        graphics.text(font, text, 0, 0, color)
        graphics.pose().popMatrix()
        return true
    }

    private fun drawText(
        graphics: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Float,
        y: Float,
        scale: Float,
        color: Int,
    ) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x, y)
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

    private fun clampF(value: Float, min: Float, max: Float): Float = Math.max(min, Math.min(max, value))
}
