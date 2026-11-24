package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a pet is relative to the player's body this frame, as produced by [PetFollow].
 * [x], [y], [z] are the pet's anchor in the body's model frame (pixels, y down, z backward, +x the player's
 * left); at rest that is the shoulder spot ([REST_X], [REST_Y], [REST_Z]). [yaw] is the pet's facing relative
 * to the body (rad, 0 = same way as the player, positive = toward the player's right). [lean] pitches the nose
 * down and [bank] rolls it into turns. [dragX], [dragY], [dragZ] is how far a loosely hanging part (slime core,
 * ghost skirt, tails) trails behind the pet, in the pet's own frame (pixels). [phase] is an integrated cycle
 * phase (rad) whose speed rises with moving and air, so animations never jump when the player starts or stops.
 */
@JvmRecord
data class PetPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val yaw: Float,
    val lean: Float,
    val bank: Float,
    val dragX: Float,
    val dragY: Float,
    val dragZ: Float,
    val phase: Float,
) {
    companion object {
        const val REST_X = 10f
        const val REST_Y = -3.5f
        const val REST_Z = 0.5f

        /** Base cycle rate (cycles per second) before moving and air speed it up. */
        const val BASE_RATE = 0.85f

        /** Big enough that `phase * speed` stays continuous when the phase wraps, for any speed step of 0.001. */
        const val WRAP = (2.0 * PI * 1000.0).toFloat()

        @JvmField
        val REST = PetPose(REST_X, REST_Y, REST_Z, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

        /** The pet at rest with the cycle phase of a player who has stood still for [seconds]. */
        @JvmStatic
        fun standing(seconds: Float): PetPose =
            REST.copy(phase = ((seconds * BASE_RATE * 2.0 * PI) % (2.0 * PI * 1000.0)).toFloat())
    }
}

/**
 * Per-player pet follow state. The pet is a critically-damped spring in WORLD space chasing a rest spot
 * beside the player's shoulder, so it trails behind when the player walks, swings round when the player turns
 * and drifts back when the player stops. Everything is integrated in small fixed steps (never a rate times an
 * age), so no input can make it jump; a teleport snaps it to the rest spot.
 *
 * State is kept per entity id in two slots: the menu preview draws the same player at a different place
 * than the world does, and each of them keeps its own pet.
 *
 * With [roam] the rest spot is not fixed: every [SPOT_SECONDS] the pet hops to the next spot of a tour (both
 * shoulders, on the head, the floor beside and in front of the player), see [spot]. In the air it holds on to
 * the shoulder.
 */
class PetFollow(private val roam: Boolean = true) {
    private class State(
        var seconds: Float,
        var lastX: Double, var lastY: Double, var lastZ: Double,
        // Pet position and velocity in world blocks.
        var wx: Double, var wy: Double, var wz: Double,
        var vx: Double, var vy: Double, var vz: Double,
        // A looser follower of the pet (for hanging parts) and its velocity.
        var sx: Double, var sy: Double, var sz: Double,
        var svx: Double, var svy: Double, var svz: Double,
        var heading: Float, var headVel: Float, var lean: Float, var bank: Float, var phase: Float,
    )

    private class Entry(var a: State?, var b: State?)

    private val entries = HashMap<Int, Entry>()
    private var lastPrune = 0f
    private val spotBuffer = FloatArray(3)

    /**
     * @param seconds animation clock (entity age in seconds, partial ticks included)
     * @param x y z the player's world position (interpolated)
     * @param bodyYaw the body yaw in degrees (Minecraft convention, as in the render state)
     * @param lookYaw the head yaw in degrees
     * @param scale the player's model scale (1 for a normal player)
     * @param moving smoothed 0..1 walk speed, [air] smoothed 0..1 airborne (from [CosmeticMotionTracker])
     * @param headFree false while a hat or head piece is worn: the roaming pet then skips the head spot
     */
    @JvmOverloads
    fun update(
        id: Int, seconds: Float, x: Double, y: Double, z: Double, bodyYaw: Float, lookYaw: Float,
        scale: Float, moving: Float, air: Float, headFree: Boolean = true,
    ): PetPose {
        val entry = entries.getOrPut(id) { Entry(null, null) }
        val s = scale.coerceIn(0.1f, 8f)
        val bodyRad = Math.toRadians(bodyYaw.toDouble())
        val c = cos(bodyRad)
        val sn = sin(bodyRad)
        val target = spotBuffer
        if (roam) {
            spot(seconds, id, headFree, target)
            // Airborne, it holds on to the shoulder whatever spot it had.
            val hold = air.coerceIn(0f, 1f)
            target[0] += (PetPose.REST_X - target[0]) * hold
            target[1] += (PetPose.REST_Y - target[1]) * hold
            target[2] += (PetPose.REST_Z - target[2]) * hold
        } else {
            target[0] = PetPose.REST_X; target[1] = PetPose.REST_Y; target[2] = PetPose.REST_Z
        }
        // Rest spot in the world: the body-frame offset turned by the body yaw, in blocks.
        val mx = target[0] / 16.0 * s
        val my = target[1] / 16.0 * s
        val mz = target[2] / 16.0 * s
        val tx = x + mx * c + mz * sn
        val ty = y - my
        val tz = z + mx * sn - mz * c
        val look = Math.toRadians(lookYaw.toDouble()).toFloat()

        var st = pick(entry, x, y, z)
        if (st == null || seconds < st.seconds - 1f || hypot(st.wx - tx, st.wz - tz) > SNAP_BLOCKS * s || Math.abs(st.wy - ty) > SNAP_BLOCKS * s) {
            st = State(seconds, x, y, z, tx, ty, tz, 0.0, 0.0, 0.0, tx, ty, tz, 0.0, 0.0, 0.0, look, 0f, 0f, 0f, 0f)
            if (entry.a == null || (entry.b != null && entry.a!!.seconds <= entry.b!!.seconds)) entry.a = st else entry.b = st
        }
        val dt = (seconds - st.seconds).coerceIn(0f, MAX_STEP)
        st.lastX = x; st.lastY = y; st.lastZ = z
        if (dt > 0f) {
            st.seconds = seconds
            integrate(st, dt.toDouble(), tx, ty, tz, s.toDouble())
            val speed = hypot(st.vx, st.vz)
            val target = if (speed > MOVE_FACE * s) atan2(-st.vx, st.vz).toFloat() else look
            val before = st.heading
            // The facing is a critically damped spring on the angle with a speed limit: it starts and stops
            // softly and never spins faster than TURN_MAX, however far it has to go.
            val turnSteps = ceil(dt / STEP).toInt().coerceAtLeast(1)
            val turnH = (dt / turnSteps).toFloat()
            repeat(turnSteps) {
                st.headVel += (TURN_K * wrapPi(target - st.heading) - TURN_C * st.headVel) * turnH
                st.headVel = st.headVel.coerceIn(-TURN_MAX, TURN_MAX)
                st.heading += st.headVel * turnH
            }
            val forward = -st.vx * sin(st.heading.toDouble()) + st.vz * cos(st.heading.toDouble())
            val leanTarget = (forward / s * 0.075).coerceIn(-0.3, 0.45).toFloat()
            st.lean += (leanTarget - st.lean) * (1f - exp(-dt * 6f))
            val yawRate = wrapPi(st.heading - before) / dt
            st.bank += ((-yawRate * 0.12f).coerceIn(-0.45f, 0.45f) - st.bank) * (1f - exp(-dt * 6f))
            val rate = PetPose.BASE_RATE * (1f + moving.coerceIn(0f, 1f) * 0.7f + air.coerceIn(0f, 1f) * 0.6f)
            st.phase = (st.phase + dt * rate * 2f * PI.toFloat()).let { if (it > PetPose.WRAP) it - PetPose.WRAP else it }
        } else if (seconds < st.seconds - 1f) {
            st.seconds = seconds
        }
        if (seconds - lastPrune > PRUNE_SECONDS || seconds < lastPrune) prune(seconds)
        return pose(st, x, y, z, c, sn, s, bodyRad.toFloat())
    }

    fun size(): Int = entries.values.sumOf { (if (it.a != null) 1 else 0) + (if (it.b != null) 1 else 0) }

    private fun pick(entry: Entry, x: Double, y: Double, z: Double): State? {
        val a = entry.a
        val b = entry.b
        val da = if (a == null) Double.MAX_VALUE else dist(a, x, y, z)
        val db = if (b == null) Double.MAX_VALUE else dist(b, x, y, z)
        val best = if (da <= db) a else b
        val d = minOf(da, db)
        return if (best != null && d < PICK_BLOCKS) best else null
    }

    private fun dist(st: State, x: Double, y: Double, z: Double): Double =
        sqrt((st.lastX - x) * (st.lastX - x) + (st.lastY - y) * (st.lastY - y) + (st.lastZ - z) * (st.lastZ - z))

    /** Fixed small steps of a damped spring toward the rest spot, then a looser follower behind it. */
    private fun integrate(st: State, dt: Double, tx: Double, ty: Double, tz: Double, s: Double) {
        val steps = ceil(dt / STEP).toInt().coerceAtLeast(1)
        val h = dt / steps
        val kh = OMEGA_H * OMEGA_H
        val ch = 2.0 * ZETA_H * OMEGA_H
        val kv = OMEGA_V * OMEGA_V
        val cv = 2.0 * ZETA_V * OMEGA_V
        val ks = OMEGA_S * OMEGA_S
        val cs = 2.0 * OMEGA_S
        repeat(steps) {
            st.vx += (kh * (tx - st.wx) - ch * st.vx) * h
            st.vz += (kh * (tz - st.wz) - ch * st.vz) * h
            st.vy += (kv * (ty - st.wy) - cv * st.vy) * h
            st.wx += st.vx * h
            st.wy += st.vy * h
            st.wz += st.vz * h
            st.svx += (ks * (st.wx - st.sx) - cs * st.svx) * h
            st.svy += (ks * (st.wy - st.sy) - cs * st.svy) * h
            st.svz += (ks * (st.wz - st.sz) - cs * st.svz) * h
            st.sx += st.svx * h
            st.sy += st.svy * h
            st.sz += st.svz * h
        }
        // A leash: however fast the player flies, the pet stays within reach of its rest spot.
        val dx = st.wx - tx
        val dz = st.wz - tz
        val horizontal = hypot(dx, dz)
        val limit = LEASH_BLOCKS * s
        if (horizontal > limit) {
            st.wx = tx + dx * limit / horizontal
            st.wz = tz + dz * limit / horizontal
        }
        st.wy = ty + (st.wy - ty).coerceIn(-limit, limit)
        val sdx = st.sx - st.wx
        val sdz = st.sz - st.wz
        val sh = hypot(sdx, sdz)
        val sLimit = DRAG_BLOCKS * s
        if (sh > sLimit) {
            st.sx = st.wx + sdx * sLimit / sh
            st.sz = st.wz + sdz * sLimit / sh
        }
        st.sy = st.wy + (st.sy - st.wy).coerceIn(-sLimit, sLimit)
    }

    private fun pose(st: State, px: Double, py: Double, pz: Double, c: Double, sn: Double, s: Float, bodyRad: Float): PetPose {
        // World offset of the pet from the player, back into the body's model frame (pixels).
        val dx = st.wx - px
        val dy = st.wy - py
        val dz = st.wz - pz
        val k = 16.0 / s
        val mx = (dx * c + dz * sn) * k
        val my = -dy * k
        val mz = (dx * sn - dz * c) * k
        // The trailing follower, in the pet's own frame.
        val ph = st.heading.toDouble()
        val hc = cos(ph)
        val hs = sin(ph)
        val ex = st.sx - st.wx
        val ey = st.sy - st.wy
        val ez = st.sz - st.wz
        return PetPose(
            mx.toFloat(), my.toFloat(), mz.toFloat(), wrapPi(st.heading - bodyRad), st.lean, st.bank,
            ((ex * hc + ez * hs) * k).toFloat(), (-ey * k).toFloat(), ((ex * hs - ez * hc) * k).toFloat(), st.phase,
        )
    }

    private fun prune(now: Float) {
        lastPrune = now
        entries.values.removeIf { e ->
            if (e.a != null && (now - e.a!!.seconds > PRUNE_SECONDS || e.a!!.seconds - now > PRUNE_SECONDS)) e.a = null
            if (e.b != null && (now - e.b!!.seconds > PRUNE_SECONDS || e.b!!.seconds - now > PRUNE_SECONDS)) e.b = null
            e.a == null && e.b == null
        }
    }

    companion object {
        private const val STEP = 1.0 / 120.0
        private const val MAX_STEP = 0.25f
        private const val OMEGA_H = 13.0
        private const val ZETA_H = 0.85
        private const val OMEGA_V = 10.0
        private const val ZETA_V = 0.7
        private const val OMEGA_S = 6.5
        private const val TURN_K = 25f
        private const val TURN_C = 10f
        private const val TURN_MAX = 5f
        private const val MOVE_FACE = 0.6
        private const val SNAP_BLOCKS = 4.0
        private const val PICK_BLOCKS = 8.0
        private const val LEASH_BLOCKS = 1.4
        private const val DRAG_BLOCKS = 0.5
        private const val PRUNE_SECONDS = 30f

        /** How long the roaming pet stays at one spot, and how long its hop to the next one takes. */
        const val SPOT_SECONDS = 10f
        private const val HOP_SECONDS = 1.1f
        private const val HOP_HEIGHT = 7f
        private const val HEAD_SPOT = 2

        /**
         * Body-frame spots (pixels; the pet anchor sits 3.5 px above what it stands on): left and right shoulder,
         * the head top, the floor left, right and in front.
         */
        private val SPOTS = arrayOf(
            floatArrayOf(PetPose.REST_X, PetPose.REST_Y, PetPose.REST_Z),
            floatArrayOf(-PetPose.REST_X, PetPose.REST_Y, PetPose.REST_Z),
            floatArrayOf(0f, -8f + PetPose.REST_Y, 0.5f),
            floatArrayOf(14f, 24f + PetPose.REST_Y, 2f),
            floatArrayOf(-14f, 24f + PetPose.REST_Y, 4f),
            floatArrayOf(7f, 24f + PetPose.REST_Y, -12f),
        )

        /** The tour: every spot once, never the same one twice in a row, shoulders between head and floor. */
        private val TOUR = intArrayOf(0, 3, 2, 4, 1, 5)

        /**
         * Writes the roaming pet's target spot (body frame pixels) at [seconds] into [out]: it holds a spot, then
         * hops to the next along an eased arc. Pure function of time and [seed], so it never jumps.
         */
        @JvmStatic
        fun spot(seconds: Float, seed: Int, headFree: Boolean, out: FloatArray) {
            val local = seconds + ((seed * 97) and 0xFF) / 255f * SPOT_SECONDS
            val cycle = kotlin.math.floor(local / SPOT_SECONDS).toInt()
            val to = SPOTS[pick(cycle, seed, headFree)]
            val from = SPOTS[pick(cycle - 1, seed, headFree)]
            val u = ((local - cycle * SPOT_SECONDS) / HOP_SECONDS).coerceIn(0f, 1f)
            val e = u * u * (3f - 2f * u)
            for (i in 0..2) out[i] = from[i] + (to[i] - from[i]) * e
            if (from !== to) out[1] -= sin(u * PI.toFloat()) * HOP_HEIGHT
        }

        private fun pick(cycle: Int, seed: Int, headFree: Boolean): Int {
            val i = TOUR[Math.floorMod(cycle + seed, TOUR.size)]
            return if (!headFree && i == HEAD_SPOT) 0 else i
        }

        /** Wraps an angle to [-pi, pi]. */
        @JvmStatic
        fun wrapPi(angle: Float): Float {
            var a = angle
            val tau = 2f * PI.toFloat()
            a %= tau
            if (a > PI) a -= tau
            if (a < -PI) a += tau
            return a
        }
    }
}
