package geiler.addons.client.module.impl

import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc

/** Camera-relative tracer endpoints whose start stays on the camera's crosshair ray. */
object BlockEspTracerGeometry {
    @JvmStatic
    fun endpoints(cameraPosition: Vec3, cameraForward: Vector3fc, target: Vec3, startDistance: Double): Endpoints {
        val lengthSquared = cameraForward.x() * cameraForward.x() +
            cameraForward.y() * cameraForward.y() +
            cameraForward.z() * cameraForward.z()
        val length = kotlin.math.sqrt(lengthSquared.toDouble())
        if (!length.isFinite() || length <= 1.0e-9) {
            return Endpoints(Vec3.ZERO, target.subtract(cameraPosition))
        }
        val distance = if (startDistance.isFinite()) startDistance.coerceAtLeast(0.0) else 0.0
        val start = Vec3(
            cameraForward.x() / length * distance,
            cameraForward.y() / length * distance,
            cameraForward.z() / length * distance,
        )
        return Endpoints(start, target.subtract(cameraPosition))
    }

    /** Java record accessors are kept for the existing module callers. */
    @JvmRecord
    data class Endpoints(val start: Vec3, val end: Vec3)
}
