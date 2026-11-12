package geiler.addons.client.render

import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.platform.CompareOp

/**
 * Minecraft 26.2 renders with reverse-Z depth on every backend, so "in front of" means a greater
 * depth value. Taking the compare op from vanilla's default keeps world ESP correct on OpenGL and
 * Vulkan instead of hard-coding the old LESS_THAN_OR_EQUAL.
 */
object ReverseZDepth {
    @JvmStatic
    fun compareOp(): CompareOp = DepthStencilState.DEFAULT.depthTest()

    /** Depth is tested but never written, so translucent overlays cannot hide later overlays. */
    @JvmStatic
    fun testOnly(): DepthStencilState = DepthStencilState(compareOp(), false)
}
