package geiler.addons.client.slayer

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.module.impl.BlockEspTracerGeometry
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Shared drawing helpers for the Slayer modules. Client thread only. */
object SlayerRender {
    private const val TRACER_START_DISTANCE = 0.5
    const val TRACER_WIDTH = 2.0f

    fun partialTick(): Float = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(true)

    /** The entity's box at the interpolated render position, so highlights move smoothly between 20 TPS ticks. */
    fun interpolatedBox(entity: Entity, partialTick: Float): AABB {
        val now = entity.position()
        val shown = entity.getPosition(partialTick)
        return entity.boundingBox.move(shown.x - now.x, shown.y - now.y, shown.z - now.z)
    }

    /** A line from the crosshair to [target] (world coordinates); the caller flushes the buffers. */
    fun tracer(
        poseStack: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        target: Vec3,
        color: Int,
        depthTested: Boolean,
    ) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val endpoints = BlockEspTracerGeometry.endpoints(camera.position(), camera.forwardVector(), target, TRACER_START_DISTANCE)
        EspRenderer.renderLine(
            poseStack, buffers, endpoints.start.x, endpoints.start.y, endpoints.start.z,
            endpoints.end.x, endpoints.end.y, endpoints.end.z, color, TRACER_WIDTH, depthTested,
        )
    }
}
