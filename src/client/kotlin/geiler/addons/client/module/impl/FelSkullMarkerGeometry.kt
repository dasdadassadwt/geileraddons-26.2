package geiler.addons.client.module.impl

import net.minecraft.world.entity.LivingEntity

/** Floor marker bounds ported from SkyHanni's stationary-Fel filled waypoint geometry. */
internal object FelSkullMarkerGeometry {
    @JvmStatic
    fun bounds(body: LivingEntity?): DungeonMobTargetMemory.Bounds {
        if (body == null) throw IllegalArgumentException("Fel body is required")
        return bounds(body.getX(), body.getY(), body.getZ())
    }

    @JvmStatic
    fun bounds(bodyX: Double, bodyY: Double, bodyZ: Double): DungeonMobTargetMemory.Bounds {
        if (!bodyX.isFinite() || !bodyY.isFinite() || !bodyZ.isFinite()) {
            throw IllegalArgumentException("Invalid Fel body position")
        }
        // The marker entity is one block below the visible skull, so retain the source
        // 0.6-block footprint and raise the rendered anchor to the skull's block.
        val markerY = bodyY + 1.0
        return DungeonMobTargetMemory.Bounds(
            bodyX - 0.3, markerY - 0.03, bodyZ - 0.3,
            bodyX + 0.3, markerY + 0.57, bodyZ + 0.3,
        )
    }
}
