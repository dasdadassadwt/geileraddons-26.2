package geiler.addons.client.render

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc
import kotlin.math.tan

/**
 * Projects a world position onto GUI coordinates, so labels can be drawn with the real font on the
 * HUD instead of as in-world geometry.
 *
 * Projection uses the camera's own basis vectors and field of view rather than multiplying through
 * a projection matrix. The cache follows the active camera and framebuffer dimensions.
 */
class WorldToScreen private constructor() {
    companion object {
        /** Anything nearer than this along the view axis is level with or behind the eye. */
        private const val MIN_DEPTH = 0.05

        private val viewScratch = DoubleArray(3)
        private var cachedCamera: Camera? = null
        private var cachedFov = Float.NaN
        private var cachedWidth = 0
        private var cachedHeight = 0
        private var cachedTanHalfFov = 0.0
        private var cachedAspect = 0.0

        /** @return GUI-scaled `{x, y}` or null if the point is behind the camera. */
        @JvmStatic
        fun project(camera: Camera?, target: Vec3?): FloatArray? {
            val projected = FloatArray(2)
            return if (projectInto(camera, target, projected)) projected else null
        }

        /** Projects into caller-owned storage to avoid one array allocation per rendered label. */
        @JvmStatic
        fun projectInto(camera: Camera?, target: Vec3?, projected: FloatArray?): Boolean {
            if (camera == null || target == null || projected == null || projected.size < 2) return false
            val minecraft = Minecraft.getInstance()
            val cameraPosition = camera.position()
            val relativeX = target.x - cameraPosition.x
            val relativeY = target.y - cameraPosition.y
            val relativeZ = target.z - cameraPosition.z

            val forward: Vector3fc = camera.forwardVector()
            val up: Vector3fc = camera.upVector()
            val left: Vector3fc = camera.leftVector()

            val straightDepth = relativeX * forward.x() + relativeY * forward.y() + relativeZ * forward.z()
            if (straightDepth < MIN_DEPTH) return false

            var vertical = relativeX * up.x() + relativeY * up.y() + relativeZ * up.z()
            // leftVector points left, so negating it gives the screen-right axis.
            var horizontal = -(relativeX * left.x() + relativeY * left.y() + relativeZ * left.z())
            // The world pass bobs the view while walking; follow it so a label stays on the box it names.
            val view = viewScratch
            view[0] = horizontal
            view[1] = vertical
            view[2] = -straightDepth
            ViewBob.apply(view)
            horizontal = view[0]
            vertical = view[1]
            val depth = -view[2]
            if (depth < MIN_DEPTH) return false
            val window = minecraft.window
            val framebufferWidth = window.width
            val framebufferHeight = window.height
            val fov = camera.getFov()
            if (camera !== cachedCamera || framebufferWidth != cachedWidth || framebufferHeight != cachedHeight ||
                fov != cachedFov
            ) {
                cachedCamera = camera
                cachedWidth = framebufferWidth
                cachedHeight = framebufferHeight
                cachedFov = fov
                cachedTanHalfFov = tan(Math.toRadians(fov.toDouble()) / 2.0)
                cachedAspect = framebufferWidth.toDouble() / maxOf(1, framebufferHeight)
            }
            if (cachedTanHalfFov <= 0.0) return false
            // Aspect comes from the real framebuffer; the GUI-scaled size is rounded to whole pixels
            // and would skew x slightly.
            val ndcX = (horizontal / depth) / (cachedTanHalfFov * cachedAspect)
            val ndcY = (vertical / depth) / cachedTanHalfFov

            projected[0] = ((ndcX * 0.5 + 0.5) * window.guiScaledWidth).toFloat()
            projected[1] = ((1.0 - (ndcY * 0.5 + 0.5)) * window.guiScaledHeight).toFloat()
            return projected[0].isFinite() && projected[1].isFinite()
        }
    }
}
