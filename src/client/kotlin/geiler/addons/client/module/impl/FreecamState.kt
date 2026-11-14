package geiler.addons.client.module.impl

import kotlin.math.cos
import kotlin.math.sin

/**
 * Pose and flight of the detached Freecam camera. Pure state: the module feeds it mouse turns and the held movement
 * keys, and reads the pose back once per rendered frame. Yaw and pitch are in degrees with Minecraft's convention
 * (yaw 0 faces +Z, positive pitch looks down).
 */
class FreecamState {
    var seeded = false
        private set
    var x = 0.0
        private set
    var y = 0.0
        private set
    var z = 0.0
        private set
    var yaw = 0.0f
        private set
    var pitch = 0.0f
        private set
    private var keys = 0

    fun seed(x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        this.x = x
        this.y = y
        this.z = z
        this.yaw = yaw
        this.pitch = clampPitch(pitch)
        seeded = true
    }

    fun clear() {
        seeded = false
        keys = 0
    }

    /** Applies a raw mouse turn with the same 0.15 degree scale as the player's own turn. */
    fun turn(yawDelta: Double, pitchDelta: Double) {
        yaw += yawDelta.toFloat() * TURN_SCALE
        pitch = clampPitch(pitch + pitchDelta.toFloat() * TURN_SCALE)
    }

    fun setKeys(forward: Boolean, backward: Boolean, left: Boolean, right: Boolean, up: Boolean, down: Boolean) {
        keys = mask(forward, backward, left, right, up, down)
    }

    fun heldKeys(): Int = keys

    /** Moves the camera for [ticks] game ticks at [speed] blocks per tick; opposite keys cancel. */
    fun advance(ticks: Float, speed: Float) {
        if (!seeded || ticks <= 0f || speed <= 0f) return
        val forward = axis(FORWARD, BACKWARD)
        val strafe = axis(RIGHT, LEFT)
        val vertical = axis(UP, DOWN)
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val lookX = -sin(yawRad) * cos(pitchRad)
        val lookY = -sin(pitchRad)
        val lookZ = cos(yawRad) * cos(pitchRad)
        val rightX = -cos(yawRad)
        val rightZ = -sin(yawRad)
        var dx = lookX * forward + rightX * strafe
        var dy = lookY * forward + vertical
        var dz = lookZ * forward + rightZ * strafe
        val length = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1.0E-6) return
        val step = speed.toDouble() * ticks / length
        dx *= step
        dy *= step
        dz *= step
        x += dx
        y += dy
        z += dz
    }

    private fun axis(positive: Int, negative: Int): Double =
        (if (keys and positive != 0) 1.0 else 0.0) - (if (keys and negative != 0) 1.0 else 0.0)

    companion object {
        const val FORWARD = 1
        const val BACKWARD = 2
        const val LEFT = 4
        const val RIGHT = 8
        const val UP = 16
        const val DOWN = 32
        const val TURN_SCALE = 0.15f

        @JvmStatic
        fun mask(forward: Boolean, backward: Boolean, left: Boolean, right: Boolean, up: Boolean, down: Boolean): Int =
            (if (forward) FORWARD else 0) or (if (backward) BACKWARD else 0) or (if (left) LEFT else 0) or
                (if (right) RIGHT else 0) or (if (up) UP else 0) or (if (down) DOWN else 0)

        @JvmStatic
        fun clampPitch(pitch: Float): Float = Math.max(-90.0f, Math.min(90.0f, pitch))
    }
}
