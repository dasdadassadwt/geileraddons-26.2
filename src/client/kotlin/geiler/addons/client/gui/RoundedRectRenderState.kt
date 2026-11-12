package geiler.addons.client.gui

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import geiler.addons.client.gui.access.GuiGraphicsExtractorRenderStateAccess
import geiler.addons.client.gui.access.GuiGraphicsExtractorScissorAccess
import org.joml.Matrix3x2f
import java.util.Collections
import java.util.LinkedHashMap

/**
 * A cached rounded rectangle submitted as one GUI render element instead of one element per edge pixel.
 *
 * The geometry stores the same row spans and antialias coverage as [GuiTheme], so gradients,
 * corner coverage, scissoring, pose transforms and draw order continue through Minecraft's GUI renderer.
 */
internal class RoundedRectRenderState private constructor(
    graphics: GuiGraphicsExtractor,
    private val x: Int,
    private val y: Int,
    width: Int,
    height: Int,
    radius: Int,
    private val colorTop: Int,
    private val colorBottom: Int,
    outline: Boolean,
) : GuiElementRenderState {
    private val geometry = geometry(width, height, radius, outline)
    private val pose = Matrix3x2f(graphics.pose())
    private val scissor = (graphics as GuiGraphicsExtractorScissorAccess).`geileraddons$getScissorArea`()
    private val elementBounds: ScreenRectangle? =
        ScreenRectangle(x, y, width, height).transformMaxBounds(pose).let { uncut ->
            scissor?.intersection(uncut) ?: if (scissor == null) uncut else null
        }

    override fun buildVertices(consumer: VertexConsumer) {
        for (quad in geometry.quads) {
            val top = withAlphaScale(lerpColor(colorTop, colorBottom, quad.topFraction), quad.alphaScale)
            val bottom = withAlphaScale(lerpColor(colorTop, colorBottom, quad.bottomFraction), quad.alphaScale)
            consumer.addVertexWith2DPose(pose, (x + quad.x0).toFloat(), (y + quad.y0).toFloat()).setColor(top)
            consumer.addVertexWith2DPose(pose, (x + quad.x0).toFloat(), (y + quad.y1).toFloat()).setColor(bottom)
            consumer.addVertexWith2DPose(pose, (x + quad.x1).toFloat(), (y + quad.y1).toFloat()).setColor(bottom)
            consumer.addVertexWith2DPose(pose, (x + quad.x1).toFloat(), (y + quad.y0).toFloat()).setColor(top)
        }
    }

    override fun pipeline(): RenderPipeline = PIPELINE

    override fun textureSetup(): TextureSetup = TEXTURE_SETUP

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle? = elementBounds

    private data class GeometryKey(val width: Int, val height: Int, val radius: Int, val outline: Boolean)

    private data class Geometry(val quads: List<Quad>)

    private data class Quad(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val topFraction: Float,
        val bottomFraction: Float,
        val alphaScale: Double,
    )

    companion object {
        const val MAX_CACHED_SHAPES = 128
        private val geometries = LinkedHashMap<GeometryKey, Geometry>(64, 0.75f, true)
        val PIPELINE: RenderPipeline = RenderPipelines.GUI
        val TEXTURE_SETUP: TextureSetup = TextureSetup.noTexture()

        fun fill(
            graphics: GuiGraphicsExtractor,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            radius: Int,
            colorTop: Int,
            colorBottom: Int,
        ) {
            submit(graphics, x, y, width, height, radius, colorTop, colorBottom, outline = false)
        }

        fun outline(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, radius: Int, color: Int) {
            submit(graphics, x, y, width, height, radius, color, color, outline = true)
        }

        private fun submit(
            graphics: GuiGraphicsExtractor,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            radius: Int,
            colorTop: Int,
            colorBottom: Int,
            outline: Boolean,
        ) {
            if (width <= 0 || height <= 0) return
            val extractor = graphics as GuiGraphicsExtractorRenderStateAccess
            val renderState: GuiRenderState = extractor.`geileraddons$getGuiRenderState`()
            renderState.addGuiElement(
                RoundedRectRenderState(graphics, x, y, width, height, radius, colorTop, colorBottom, outline)
            )
        }

        private fun geometry(width: Int, height: Int, radius: Int, outline: Boolean): Geometry {
            val key = GeometryKey(width, height, radius, outline)
            geometries[key]?.let { return it }

            val built = buildGeometry(width, height, radius, outline)
            geometries[key] = built
            if (geometries.size > MAX_CACHED_SHAPES) {
                val oldest = geometries.keys.iterator().next()
                geometries.remove(oldest)
            }
            return built
        }

        private fun buildGeometry(width: Int, height: Int, radius: Int, outline: Boolean): Geometry {
            val quads = ArrayList<Quad>()
            if (outline) {
                add(quads, radius, 0, width - radius, 1, 0.0f, 0.0f, 1.0)
                add(quads, radius, height - 1, width - radius, height, 0.0f, 0.0f, 1.0)
                add(quads, 0, radius, 1, height - radius, 0.0f, 0.0f, 1.0)
                add(quads, width - 1, radius, width, height - radius, 0.0f, 0.0f, 1.0)
                for (row in 0 until radius) {
                    for (px in 0 until radius) {
                        val coverage = java.lang.Math.max(
                            0.0,
                            coverage(px, row, radius) - coverage(px - 1, row - 1, radius - 1),
                        )
                        if (coverage <= 0.0) continue
                        val right = width - 1 - px
                        val bottom = height - 1 - row
                        add(quads, px, row, px + 1, row + 1, 0.0f, 0.0f, coverage)
                        add(quads, right, row, right + 1, row + 1, 0.0f, 0.0f, coverage)
                        add(quads, px, bottom, px + 1, bottom + 1, 0.0f, 0.0f, coverage)
                        add(quads, right, bottom, right + 1, bottom + 1, 0.0f, 0.0f, coverage)
                    }
                }
                return Geometry(Collections.unmodifiableList(ArrayList(quads)))
            }

            if (height - 2 * radius > 0) {
                add(quads, 0, radius, width, height - radius,
                    radius / height.toFloat(), (height - radius) / height.toFloat(), 1.0)
            }
            for (row in 0 until radius) {
                val topY = row
                val bottomY = height - 1 - row
                val dy = radius - row - 0.5
                val halfExtent = kotlin.math.sqrt(kotlin.math.max(0.0, radius * radius.toDouble() - dy * dy))
                val firstSolid = kotlin.math.ceil(radius - halfExtent).toInt()
                val topFraction = row / height.toFloat()
                val bottomFraction = (height - 1 - row) / height.toFloat()

                // Preserve the original submission order: center band, then each row's solid spans and edge pixels.
                add(quads, firstSolid, topY, width - firstSolid, topY + 1, topFraction, topFraction, 1.0)
                add(quads, firstSolid, bottomY, width - firstSolid, bottomY + 1, bottomFraction, bottomFraction, 1.0)
                for (px in 0 until firstSolid) {
                    val coverage = coverage(px, row, radius)
                    if (coverage <= 0.0) continue
                    val right = width - 1 - px
                    add(quads, px, topY, px + 1, topY + 1, topFraction, topFraction, coverage)
                    add(quads, right, topY, right + 1, topY + 1, topFraction, topFraction, coverage)
                    add(quads, px, bottomY, px + 1, bottomY + 1, bottomFraction, bottomFraction, coverage)
                    add(quads, right, bottomY, right + 1, bottomY + 1, bottomFraction, bottomFraction, coverage)
                }
            }
            return Geometry(Collections.unmodifiableList(ArrayList(quads)))
        }

        private fun add(
            quads: MutableList<Quad>,
            x0: Int,
            y0: Int,
            x1: Int,
            y1: Int,
            topFraction: Float,
            bottomFraction: Float,
            alphaScale: Double,
        ) {
            if (x1 <= x0 || y1 <= y0) return
            quads.add(Quad(x0, y0, x1, y1, topFraction, bottomFraction, alphaScale))
        }

        private fun coverage(px: Int, row: Int, radius: Int): Double {
            if (radius <= 0) return 0.0
            val dx = radius - px - 0.5
            val dy = radius - row - 0.5
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            return java.lang.Math.max(0.0, kotlin.math.min(1.0, radius - distance + 0.5))
        }

        private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
            val clamped = java.lang.Math.max(0.0f, java.lang.Math.min(1.0f, fraction))
            val a1 = (from ushr 24) and 0xFF
            val r1 = (from ushr 16) and 0xFF
            val g1 = (from ushr 8) and 0xFF
            val b1 = from and 0xFF
            val a2 = (to ushr 24) and 0xFF
            val r2 = (to ushr 16) and 0xFF
            val g2 = (to ushr 8) and 0xFF
            val b2 = to and 0xFF
            val a = (a1 + (a2 - a1) * clamped).toInt()
            val r = (r1 + (r2 - r1) * clamped).toInt()
            val g = (g1 + (g2 - g1) * clamped).toInt()
            val b = (b1 + (b2 - b1) * clamped).toInt()
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun withAlphaScale(color: Int, scale: Double): Int {
            val alpha = java.lang.Math.round(((color ushr 24) and 0xFF) * scale).toInt()
            return (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
        }
    }
}
