package geiler.addons.client.macros.rotation

import net.minecraft.world.phys.Vec3

/** Computes the yaw/pitch that looks from one point at another. Actual turning is done by [LookControl]. */
object SmoothRotation {
    @JvmStatic
    fun lookAt(from: Vec3, to: Vec3): LookAngles {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val horizontalDistance = Math.sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(Math.atan2(dz, dx)).toFloat() - 90.0f
        val pitch = (-Math.toDegrees(Math.atan2(dy, horizontalDistance))).toFloat()
        return LookAngles(yaw, pitch)
    }

    @JvmRecord
    data class LookAngles(val yaw: Float, val pitch: Float)
}
