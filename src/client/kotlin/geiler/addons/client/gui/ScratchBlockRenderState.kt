package geiler.addons.client.gui

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import geiler.addons.client.gui.access.GuiGraphicsExtractorRenderStateAccess
import geiler.addons.client.gui.access.GuiGraphicsExtractorScissorAccess
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2f
import kotlin.math.roundToInt

/**
 * One Scratch block outline submitted as a single GUI element: arbitrary quads with per-vertex color, so the
 * diagonal and curved edges are drawn at screen resolution with a 1-screen-pixel alpha fringe instead of GUI-pixel steps.
 */
internal class ScratchBlockRenderState private constructor(
    graphics: GuiGraphicsExtractor,
    private val x: Int,
    private val y: Int,
    private val mesh: FloatArray,
    private val colors: IntArray,
    width: Int,
    height: Int,
) : GuiElementRenderState {
    private val pose = Matrix3x2f(graphics.pose())
    private val scissor = (graphics as GuiGraphicsExtractorScissorAccess).`geileraddons$getScissorArea`()
    private val elementBounds: ScreenRectangle? =
        ScreenRectangle(x - 2, y - 2, width + 4, height + 4).transformMaxBounds(pose).let { uncut ->
            scissor?.intersection(uncut) ?: if (scissor == null) uncut else null
        }

    override fun buildVertices(consumer: VertexConsumer) {
        var i = 0
        while (i < mesh.size) {
            consumer.addVertexWith2DPose(pose, x + mesh[i], y + mesh[i + 1]).setColor(colors[mesh[i + 2].toInt()])
            i += 3
        }
    }

    override fun pipeline(): RenderPipeline = RenderPipelines.GUI

    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()

    override fun scissorArea(): ScreenRectangle? = scissor

    override fun bounds(): ScreenRectangle? = elementBounds

    companion object {
        /** Draws [shape] at ([x], [y]) with a one-GUI-unit [outline] ring around [fill]. */
        fun draw(graphics: GuiGraphicsExtractor, shape: ScratchBlockShapes.Shape, x: Int, y: Int, fill: Int, outline: Int) {
            val pose = graphics.pose()
            val guiScale = Minecraft.getInstance().window.guiScale.toDouble()
            val poseScale = Math.sqrt(Math.hypot(pose.m00.toDouble(), pose.m01.toDouble()) *
                Math.hypot(pose.m10.toDouble(), pose.m11.toDouble()))
            val pixelsPerUnit = (guiScale * poseScale).coerceAtLeast(1e-3)
            // One screen pixel of fringe, quantized so zooming reuses meshes; capped at one GUI unit when zoomed far out.
            val fringe = ((1.0 / pixelsPerUnit).coerceAtMost(1.0) * 32).roundToInt().coerceAtLeast(1) / 32f
            val mesh = shape.mesh(1f, fringe)
            if (mesh.isEmpty()) return
            val colors = intArrayOf(outline, outline and 0x00FFFFFF, fill, fill and 0x00FFFFFF)
            val renderState = (graphics as GuiGraphicsExtractorRenderStateAccess).`geileraddons$getGuiRenderState`()
            renderState.addGuiElement(ScratchBlockRenderState(graphics, x, y, mesh, colors,
                Math.ceil(shape.width.toDouble()).toInt(), Math.ceil(shape.height.toDouble()).toInt()))
        }
    }
}
