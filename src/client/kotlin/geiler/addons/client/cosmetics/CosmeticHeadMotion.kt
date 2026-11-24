package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Per-player springs for loose head pieces (witch tip, ears, antlers, hats): they lag behind head turns and nods and
 * bounce with steps and jumps. Fed once per rendered frame by the render layer; the rig reads [sway]. Unknown ids
 * (menu pictures, previews) read [REST]. Render thread only. All outputs are continuous: they are spring states,
 * never a function of a changing rate times the age.
 */
object CosmeticHeadMotion {
    /** Twist: lag of the head's yaw (rad, same sign convention as the head's rotateY). Fore: pitch lag (rad). Bob: px, up positive. */
    class Sway {
        @JvmField var twist = 0f
        @JvmField var fore = 0f
        @JvmField var bob = 0f
    }

    private class State(var seconds: Float, var yaw: Float, var pitch: Float, var air: Float) {
        val sway = Sway()
        var yawRate = 0f
        var pitchRate = 0f
        var twistV = 0f
        var foreV = 0f
        var bobV = 0f
        var step = 0f
    }

    @JvmField
    val REST = Sway()

    private val states = HashMap<Int, State>()
    private var lastPrune = 0f
    private const val OMEGA = 15f
    private const val ZETA = 0.3f
    private const val BOB_OMEGA = 18f
    private const val BOB_ZETA = 0.22f
    private const val SUBSTEP = 1f / 120f
    private const val DEG = (PI / 180.0).toFloat()

    @JvmStatic
    fun sway(id: Int): Sway = states[id]?.sway ?: REST

    @JvmStatic
    fun size(): Int = states.size

    /**
     * @param headYaw world head yaw in degrees (body yaw + head turn), [headPitch] degrees
     * @param moving smoothed walk speed 0..1, [air] smoothed airborne 0..1 (from [CosmeticMotionTracker])
     */
    @JvmStatic
    fun update(id: Int, seconds: Float, headYaw: Float, headPitch: Float, moving: Float, air: Float) {
        val s = states.getOrPut(id) { State(seconds, headYaw, headPitch, air) }
        var dt = seconds - s.seconds
        if (dt < 0f || dt > 1f) {
            // Clock reset or the player was out of sight for a while: start calm.
            s.seconds = seconds; s.yaw = headYaw; s.pitch = headPitch; s.air = air
            return
        }
        if (dt < 1e-4f) return
        s.seconds = seconds
        val yawDelta = wrapDegrees(headYaw - s.yaw) * DEG
        val pitchDelta = (headPitch - s.pitch) * DEG
        s.yaw = headYaw
        s.pitch = headPitch
        val follow = 1f - kotlin.math.exp(-dt * 25f)
        s.yawRate += ((yawDelta / dt).coerceIn(-25f, 25f) - s.yawRate) * follow
        s.pitchRate += ((pitchDelta / dt).coerceIn(-25f, 25f) - s.pitchRate) * follow
        // A jump or landing kicks the bounce spring.
        s.bobV += -(air - s.air) * 16f
        s.air = air
        s.step += dt * (9f + 7f * moving)
        val twistTarget = (-s.yawRate * 0.05f).coerceIn(-0.6f, 0.6f)
        val foreTarget = (-s.pitchRate * 0.05f).coerceIn(-0.6f, 0.6f)
        val bobTarget = moving * 0.6f * sin(s.step)
        dt = min(dt, 0.1f)
        val w = s.sway
        var left = dt
        while (left > 0f) {
            val h = min(left, SUBSTEP)
            left -= h
            s.twistV += (OMEGA * OMEGA * (twistTarget - w.twist) - 2f * ZETA * OMEGA * s.twistV) * h
            w.twist += s.twistV * h
            s.foreV += (OMEGA * OMEGA * (foreTarget - w.fore) - 2f * ZETA * OMEGA * s.foreV) * h
            w.fore += s.foreV * h
            s.bobV += (BOB_OMEGA * BOB_OMEGA * (bobTarget - w.bob) - 2f * BOB_ZETA * BOB_OMEGA * s.bobV) * h
            w.bob += s.bobV * h
        }
        w.twist = w.twist.coerceIn(-0.8f, 0.8f)
        w.fore = w.fore.coerceIn(-0.8f, 0.8f)
        w.bob = w.bob.coerceIn(-1.5f, 1.5f)
        if (seconds - lastPrune > 30f || seconds < lastPrune) prune(seconds)
    }

    private fun prune(now: Float) {
        lastPrune = now
        states.values.removeIf { now - it.seconds > 30f || it.seconds - now > 30f }
    }

    private fun wrapDegrees(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }
}
