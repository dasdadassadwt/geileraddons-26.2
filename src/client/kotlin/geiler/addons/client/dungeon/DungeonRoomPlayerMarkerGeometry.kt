package geiler.addons.client.dungeon

import java.util.ArrayList
import java.util.LinkedHashSet
import kotlin.jvm.JvmRecord

/** Java-callable projection and sprite geometry for the dungeon map player marker. */
class DungeonRoomPlayerMarkerGeometry private constructor() {
    companion object {
        const val TELEPORT_SNAP_BLOCKS: Double = 16.0
        const val MAP_DECORATION_TEXTURE_SIZE: Int = 8
        const val MAP_DECORATION_U: Int = 2
        const val MAP_DECORATION_V: Int = 0
        const val MAP_DECORATION_WIDTH: Int = 5
        const val MAP_DECORATION_HEIGHT: Int = 7

        @JvmStatic fun shouldSnap(previousX: Double, previousZ: Double, currentX: Double, currentZ: Double): Boolean =
            MarkerGeometryCore.shouldSnap(previousX, previousZ, currentX, currentZ)
        @JvmStatic fun facingRotationRadians(yawDegrees: Float): Float =
            MarkerGeometryCore.facingRotationRadians(yawDegrees)
        @JvmStatic fun projectContinuousWorldPosition(worldX: Double, worldZ: Double): MapPosition? =
            MarkerGeometryCore.projectContinuousWorldPosition(worldX, worldZ)
        @JvmStatic fun fillPixels(size: Int): List<Pixel> = MarkerGeometryCore.fillPixels(size)
        @JvmStatic fun outlinePixels(size: Int): List<Pixel> = MarkerGeometryCore.outlinePixels(size)
    }

    @JvmRecord
    data class Pixel(val x: Int, val y: Int) {
        override fun toString(): String = recordToString("Pixel", "x" to x, "y" to y)
    }

    @JvmRecord
    data class MapPosition(val x: Double, val z: Double) {
        override fun toString(): String = recordToString("MapPosition", "x" to x, "z" to z)
    }
}

private fun recordToString(type: String, vararg components: Pair<String, Any?>): String =
    components.joinToString(prefix = "$type[", postfix = "]") { (name, value) -> "$name=$value" }

private object MarkerGeometryCore {
    /** Large position discontinuities should not be animated through intervening dungeon rooms. */
    fun shouldSnap(previousX: Double, previousZ: Double, currentX: Double, currentZ: Double): Boolean {
        if (!previousX.isFinite() || !previousZ.isFinite() || !currentX.isFinite() || !currentZ.isFinite()) return true
        val dx = currentX - previousX
        val dz = currentZ - previousZ
        val threshold = DungeonRoomPlayerMarkerGeometry.TELEPORT_SNAP_BLOCKS
        return dx * dx + dz * dz >= threshold * threshold
    }

    /** Odin keeps the grid fixed and rotates the map-decoration sprite with interpolated player yaw. */
    fun facingRotationRadians(yawDegrees: Float): Float {
        val safeYaw = if (yawDegrees.isFinite()) yawDegrees else 0.0f
        return Math.toRadians((180.0f + safeYaw).toDouble()).toFloat()
    }

    /**
     * Projects continuous world coordinates into cell-space map coordinates. One unit is one 32-block
     * dungeon stride, so the resulting position crosses the visual room gap without a discontinuity.
     * The bounds include every block from GRID_MIN through GRID_MAX, including connector blocks
     * between adjacent rooms that the room detector deliberately excludes from cell identity.
     */
    fun projectContinuousWorldPosition(worldX: Double, worldZ: Double): DungeonRoomPlayerMarkerGeometry.MapPosition? {
        if (!worldX.isFinite() || !worldZ.isFinite() ||
            worldX < DungeonGridRules.GRID_MIN || worldZ < DungeonGridRules.GRID_MIN ||
            worldX >= DungeonGridRules.GRID_MAX + 1.0 || worldZ >= DungeonGridRules.GRID_MAX + 1.0
        ) return null
        return DungeonRoomPlayerMarkerGeometry.MapPosition(
            (worldX - DungeonGridRules.GRID_MIN) / DungeonGridRules.CELL_STRIDE,
            (worldZ - DungeonGridRules.GRID_MIN) / DungeonGridRules.CELL_STRIDE,
        )
    }

    fun fillPixels(size: Int): List<DungeonRoomPlayerMarkerGeometry.Pixel> {
        if (size <= 0) return java.util.List.of()
        val headRows = maxOf(1, (size + 1) / 2)
        val stemWidth = maxOf(1, size / 3)
        val center = size / 2
        val pixels = ArrayList<DungeonRoomPlayerMarkerGeometry.Pixel>()
        for (y in 0 until size) {
            val width = if (y < headRows) minOf(size, 1 + y * 2) else stemWidth
            val left = center - width / 2
            for (x in left until left + width) {
                if (x >= 0 && x < size) pixels.add(DungeonRoomPlayerMarkerGeometry.Pixel(x, y))
            }
        }
        return java.util.List.copyOf(pixels)
    }

    /** One-pixel dark border, clipped to the marker's square allocation. */
    fun outlinePixels(size: Int): List<DungeonRoomPlayerMarkerGeometry.Pixel> {
        val fill = fillPixels(size)
        val filled = java.util.Set.copyOf(fill)
        val outline = LinkedHashSet<DungeonRoomPlayerMarkerGeometry.Pixel>()
        for (pixel in fill) {
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val neighbor = DungeonRoomPlayerMarkerGeometry.Pixel(pixel.x + dx, pixel.y + dy)
                if (neighbor.x >= 0 && neighbor.x < size && neighbor.y >= 0 && neighbor.y < size &&
                    !filled.contains(neighbor)
                ) outline.add(neighbor)
            }
        }
        return java.util.List.copyOf(outline)
    }

}


