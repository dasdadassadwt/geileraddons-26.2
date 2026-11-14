package geiler.addons.client.macros.etw

import com.google.gson.JsonObject
import kotlin.math.sqrt

/** The arithmetic of the recorder, kept free of game state. */
object EtwMath {
    /**
     * Every block offset from a centre block inside a true sphere: dx^2 + dy^2 + dz^2 <= radius^2 (123 positions at
     * radius 3, the centre included), never the cube around it.
     */
    @JvmStatic
    fun sphereOffsets(radius: Int): List<IntArray> {
        val limit = radius * radius
        val offsets = ArrayList<IntArray>()
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    if (dx * dx + dy * dy + dz * dz <= limit) offsets.add(intArrayOf(dx, dy, dz))
                }
            }
        }
        return offsets
    }

    /** Where on its block a hit lies: the hit minus the block's minimum corner, unrounded. */
    @JvmStatic
    fun relativeHit(hit: Xyz, block: IntXyz): Xyz = Xyz(hit.x - block.x, hit.y - block.y, hit.z - block.z)

    /** An angle brought into -180 (included) to 180 (excluded) degrees, like Minecraft's own wrapping. */
    @JvmStatic
    fun wrapDegrees(degrees: Double): Double {
        var wrapped = degrees % 360.0
        if (wrapped >= 180.0) wrapped -= 360.0
        if (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }

    @JvmStatic
    fun positionDiff(from: Xyz, to: Xyz): JsonObject {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val json = JsonObject()
        json.addProperty("dx", dx)
        json.addProperty("dy", dy)
        json.addProperty("dz", dz)
        json.addProperty("horizontal", sqrt(dx * dx + dz * dz))
        json.addProperty("total", sqrt(dx * dx + dy * dy + dz * dz))
        return json
    }
}
