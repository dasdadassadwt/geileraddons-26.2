package geiler.addons.client.module.impl

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Camera-relative extents for the complete entity AABB submitted by Mob Highlight. */
internal object MobHighlightGeometry {
    @JvmStatic
    fun relativeBounds(entityBounds: AABB, cameraPosition: Vec3): RelativeBounds = RelativeBounds(
        entityBounds.minX - cameraPosition.x,
        entityBounds.minY - cameraPosition.y,
        entityBounds.minZ - cameraPosition.z,
        entityBounds.maxX - cameraPosition.x,
        entityBounds.maxY - cameraPosition.y,
        entityBounds.maxZ - cameraPosition.z,
    )

    @JvmRecord
    data class RelativeBounds(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
    ) {
        fun sizeX(): Double = maxX - minX

        fun sizeY(): Double = maxY - minY

        fun sizeZ(): Double = maxZ - minZ
    }
}
