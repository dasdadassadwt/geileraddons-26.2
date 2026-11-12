package geiler.addons.client.render

import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType

/** Custom backend-neutral render types for visible and depth-tested ESP geometry. */
object GeilerAddonsRenderTypes {
    private val espQuadsPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation("pipeline/geileraddons_esp_quads")
            .withCull(false)
            .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .build(),
    )

    private val espLinesPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation("pipeline/geileraddons_esp_lines")
            .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .build(),
    )

    /** Depth is tested but not written, so translucent overlays cannot hide later overlays. */
    private val depthQuadsPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation("pipeline/geileraddons_depth_quads")
            .withCull(false)
            .withDepthStencilState(ReverseZDepth.testOnly())
            .build(),
    )

    private val depthLinesPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation("pipeline/geileraddons_depth_lines")
            .withDepthStencilState(ReverseZDepth.testOnly())
            .build(),
    )

    @JvmField
    val ESP_QUADS: RenderType = RenderType.create(
        "geileraddons_esp_quads",
        RenderSetup.builder(espQuadsPipeline).sortOnUpload().createRenderSetup(),
    )

    @JvmField
    val ESP_LINES: RenderType = RenderType.create(
        "geileraddons_esp_lines",
        RenderSetup.builder(espLinesPipeline).createRenderSetup(),
    )

    @JvmField
    val DEPTH_QUADS: RenderType = RenderType.create(
        "geileraddons_depth_quads",
        RenderSetup.builder(depthQuadsPipeline).sortOnUpload().createRenderSetup(),
    )

    @JvmField
    val DEPTH_LINES: RenderType = RenderType.create(
        "geileraddons_depth_lines",
        RenderSetup.builder(depthLinesPipeline).createRenderSetup(),
    )

    @JvmStatic
    fun quads(depthTested: Boolean): RenderType = if (depthTested) DEPTH_QUADS else ESP_QUADS

    @JvmStatic
    fun lines(depthTested: Boolean): RenderType = if (depthTested) DEPTH_LINES else ESP_LINES

    /** Flush every pair because a renderer can fill depth-tested and visible batches in one pass. */
    @JvmStatic
    fun endBatches(bufferSource: WorldRenderBuffer.BufferSource) {
        bufferSource.endBatch(ESP_QUADS)
        bufferSource.endBatch(ESP_LINES)
        bufferSource.endBatch(DEPTH_QUADS)
        bufferSource.endBatch(DEPTH_LINES)
    }
}
