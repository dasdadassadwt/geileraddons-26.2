package geiler.addons.client.cosmetics

import geiler.addons.client.cosmetics.CosmeticRig.Anchor
import geiler.addons.client.cosmetics.CosmeticRig.Kind
import geiler.addons.client.cosmetics.CosmeticRig.Placement
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Places and animates the pets (PET slot) and the storm cloud, flame ring and heart auras. Pure math like
 * [CosmeticRig]; the per-frame work is building matrices for baked meshes. The pet's position and facing come
 * from [PetFollow] (carried in `motion.pet`); cycle animations use the integrated `pet.phase` with whole-number
 * multipliers, idle life (bob, blink, gaze) uses the clock, and both are scaled by the slot's speed setting.
 */
object CosmeticRigD {
    private const val TAU = (2.0 * PI).toFloat()
    private const val V = 0.5f // pixels per voxel

    /** The aura sizes were tuned for 1.25; that is the model's native size. */
    private const val AURA_NATIVE = 1.25f
    private const val AURA_UP = 3.85f
    private const val AURA_BACK = -1.9f

    const val RAIN_DROPS = 18
    const val FLAME_TONGUES = 14
    const val EMBERS = 12
    const val HEARTS = 14

    // ---- idle life -----------------------------------------------------------------------------------

    private fun hash(a: Int, b: Int): Int {
        var h = a * 73856093 xor b * 19349663
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return h
    }

    private fun unit(a: Int, b: Int): Float = (hash(a, b) and 0xFFFF) / 65535f

    /** 0 = eyes open, 1 = shut: a quick blink every few seconds, desynced per player; 0 at speed 0. */
    @JvmStatic
    fun blink(seconds: Float, seed: Int, speed: Float): Float {
        if (speed <= 0f) return 0f
        val period = 3.4f
        val local = seconds * speed + unit(seed, 11) * period
        val cycle = floor(local / period).toInt()
        val t = local - cycle * period
        val duration = 0.2f
        // Some cycles blink twice in a row.
        val double = unit(cycle, seed) < 0.25f
        val a = if (t < duration) sin(PI.toFloat() * t / duration) else 0f
        val b = if (double && t > 0.3f && t < 0.3f + duration) sin(PI.toFloat() * (t - 0.3f) / duration) else 0f
        return max(a, b)
    }

    private val GAZE_YAW = floatArrayOf(0f, 0.75f, -0.5f, 0.3f, 0.95f, -0.25f, 0f)
    private val GAZE_PITCH = floatArrayOf(0f, -0.12f, 0.14f, -0.22f, 0.06f)

    /** Where a pet looks: yaw (positive turns toward the player) or pitch, easing between a new target every few seconds. */
    @JvmStatic
    fun gaze(seconds: Float, seed: Int, speed: Float, pitch: Boolean): Float {
        if (speed <= 0f) return 0f
        val period = 3.8f
        val local = seconds * speed + unit(seed, 5) * period * 4f
        val cycle = floor(local / period).toInt()
        val t = local - cycle * period
        val table = if (pitch) GAZE_PITCH else GAZE_YAW
        val from = table[((hash(cycle - 1, seed + if (pitch) 7 else 0) ushr 8) % table.size + table.size) % table.size]
        val to = table[((hash(cycle, seed + if (pitch) 7 else 0) ushr 8) % table.size + table.size) % table.size]
        val x = (t / 0.8f).coerceIn(0f, 1f)
        val ease = x * x * (3f - 2f * x)
        return from + (to - from) * ease + sin(local * 0.9f + (if (pitch) 1.7f else 0f)) * 0.05f
    }

    private fun bump(u: Float, center: Float, width: Float): Float {
        var d = abs(u - center)
        d = min(d, 1f - d)
        return if (d >= width) 0f else 0.5f * (1f + cos(PI.toFloat() * d / width))
    }

    private class Ctx(val piece: CosmeticPiece, val motion: CosmeticRig.Motion, val out: MutableList<Placement>) {
        val pose = motion.pet
        val speed = piece.speed
        val t = motion.seconds * speed
        val phase = pose.phase * speed
        val moving = motion.moving
        val blink = blink(motion.seconds, motion.seed, speed)
        val gazeYaw = gaze(motion.seconds, motion.seed, speed, false)
        val gazePitch = gaze(motion.seconds, motion.seed, speed, true)

        /** Pet frame: the follow position, the user's offsets, facing, lean and bank, then the size. */
        fun root(extraX: Float, bob: Float): Matrix4f = Matrix4f()
            .translate(pose.x + extraX, pose.y - piece.up + bob, pose.z + piece.back)
            .rotateY(pose.yaw).rotateX(pose.lean).rotateZ(pose.bank)
            .scale(piece.size)

        fun add(mesh: VoxelMesh, m: Matrix4f, kind: Kind = Kind.SOLID, alpha: Int = 255, color: Int = piece.color, color2: Int = piece.color2) {
            out.add(Placement(Anchor.BODY, mesh, m, color, color2, kind, alpha))
        }

        /** An eye slab at (x, y, z) voxels in [frame], squashed shut by the blink. */
        fun eye(frame: Matrix4f, eye: CosmeticModelsD.Eye, side: Int, blink: Float) {
            val m = Matrix4f(frame).translate(side * eye.x * V, eye.y * V, (eye.z + 0.5f) * V).scale(side.toFloat(), 1f - blink * 0.92f, 1f)
            add(eye.mesh, m)
        }
    }

    // ---- entry points ----------------------------------------------------------------------------------

    @JvmStatic
    fun pet(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<Placement>) {
        val c = Ctx(piece, motion, out)
        when (piece.style) {
            CosmeticStyle.BABY_DRAGON -> dragon(c)
            CosmeticStyle.GHOST -> ghost(c)
            CosmeticStyle.BEE -> bee(c)
            CosmeticStyle.SLIME_CUBE -> slime(c)
            CosmeticStyle.AXOLOTL -> axolotl(c)
            CosmeticStyle.OWL -> owl(c)
            else -> {}
        }
    }

    /** Places the auras of this file; false when [piece] is another aura. */
    @JvmStatic
    fun aura(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<Placement>): Boolean {
        when (piece.style) {
            CosmeticStyle.STORM_CLOUD -> stormCloud(piece, motion, out)
            CosmeticStyle.FLAME_RING -> flameRing(piece, motion, out)
            CosmeticStyle.HEART_AURA -> hearts(piece, motion, out)
            else -> return false
        }
        return true
    }

    // ---- baby dragon -----------------------------------------------------------------------------------

    private fun dragon(c: Ctx) {
        val d = CosmeticModelsD.DRAGON
        val ph = c.phase
        val mv = c.moving
        val flap = sin(2f * ph)
        val amp = 0.75f - 0.5f * mv
        val bob = sin(c.t * 2.1f) * 0.5f + flap * 0.6f * (1f - mv)
        val pitch = 0.28f - mv * 0.3f
        val body = c.root(2.5f, bob).rotateX(pitch).rotateZ(sin(c.t * 1.3f) * 0.06f)
        c.add(d.body, body)
        // Head and neck: look around, stay level against the body's pitch, nod a beat behind the wings.
        val head = Matrix4f(body)
            .translate(CosmeticModelsD.DRAGON_NECK_X * V, CosmeticModelsD.DRAGON_NECK_Y * V, CosmeticModelsD.DRAGON_NECK_Z * V)
            .rotateY(c.gazeYaw * 0.8f)
            .rotateX(-pitch * 0.6f + c.gazePitch * 0.5f + sin(2f * ph - 1f) * 0.07f)
        c.add(d.head, head)
        for (side in intArrayOf(1, -1)) {
            c.eye(head, d.eye, side, c.blink)
            val s = side.toFloat()
            val arm = Matrix4f(body)
                .translate(side * CosmeticModelsD.DRAGON_SHOULDER_X * V, CosmeticModelsD.DRAGON_SHOULDER_Y * V, CosmeticModelsD.DRAGON_SHOULDER_Z * V)
                .scale(s, 1f, 1f)
                .rotateZ(-0.12f - 0.1f * sin(2f * ph + s))
                .rotateX(-0.3f + sin(2f * ph + 0.8f * s + 0.6f) * 0.45f)
            c.add(d.arm, arm)
            val leg = Matrix4f(body)
                .translate(side * CosmeticModelsD.DRAGON_HIP_X * V, CosmeticModelsD.DRAGON_HIP_Y * V, CosmeticModelsD.DRAGON_HIP_Z * V)
                .scale(s, 1f, 1f)
                .rotateX(0.35f + sin(2f * ph + if (side > 0) 0f else PI.toFloat()) * 0.3f)
            c.add(d.leg, leg)
            // Wings: the arm lifts and falls, the fingers trail a beat behind; flying on, they sweep back and flutter.
            val sweep = -(0.3f + mv * 0.5f)
            val angle = -(0.1f + amp * 0.6f * flap) + mv * 0.1f
            val inner = Matrix4f(body)
                .translate(side * CosmeticModelsD.DRAGON_WING_X * V, CosmeticModelsD.DRAGON_WING_Y * V, CosmeticModelsD.DRAGON_WING_Z * V)
                .scale(s, 1f, 1f)
                .rotateY(sweep)
                .rotateZ(angle)
            c.add(d.wingInner, inner)
            val outer = Matrix4f(inner)
                .translate(CosmeticModelsD.DRAGON_WRIST_X * V, CosmeticModelsD.DRAGON_WRIST_Y * V, 0f)
                .rotateZ(-0.25f * (amp / 0.75f) * sin(2f * ph - 0.9f) + 0.04f * sin(6f * ph) * mv)
                .rotateY(0.12f * sin(2f * ph - 1.4f))
            c.add(d.wingOuter, outer)
        }
        // Tail: a chain, each segment swaying a little after the one before it.
        val tail = Matrix4f(body).translate(0f, CosmeticModelsD.DRAGON_TAIL_Y * V, CosmeticModelsD.DRAGON_TAIL_Z * V)
        for ((i, mesh) in d.tail.withIndex()) {
            tail.rotateY(sin(ph - i * 0.8f) * 0.22f)
                .rotateX(-0.05f - c.pose.lean * 0.3f + sin(2f * ph - 0.8f - i * 0.4f) * 0.08f)
            c.add(mesh, Matrix4f(tail))
            tail.translate(0f, 0f, CosmeticModelsD.DRAGON_TAIL_LENGTHS[i] * V)
        }
    }

    // ---- ghost -----------------------------------------------------------------------------------------

    private fun ghost(c: Ctx) {
        val g = CosmeticModelsD.GHOST
        val ph = c.phase
        val bob = sin(c.t * 1.7f) * 0.9f
        val body = c.root(0.5f, bob)
            .rotateZ(sin(c.t * 1.1f) * 0.07f)
            .rotateY(sin(c.t * 0.7f) * 0.15f)
            .translate(0f, 1f * V, 0f)
        c.add(g.body, body)
        // The skirt ripples: each tab rides its own wave and trails away from the direction of travel.
        for (i in 0 until CosmeticModelsD.GHOST_TABS) {
            val wave = sin(3f * ph - i * 0.95f)
            val long = CosmeticModelsD.GHOST_TAB_LENGTHS[i] > 3
            val m = Matrix4f(body)
                .translate((-7 + i * 2) * V + c.pose.dragX * 0.12f, CosmeticModelsD.GHOST_SKIRT_Y * V + wave * 0.4f * V, c.pose.dragZ * 0.2f)
                .scale(1f, 1f + wave * 0.22f, 1f)
                .rotateX(sin(2f * ph - i * 0.7f) * 0.04f)
            c.add(if (long) g.tabLong else g.tabShort, m)
        }
        for (side in intArrayOf(1, -1)) c.eye(body, g.eye, side, c.blink)
    }

    // ---- bee -------------------------------------------------------------------------------------------

    private fun bee(c: Ctx) {
        val b = CosmeticModelsD.BEE
        val ph = c.phase
        val mv = c.moving
        val bob = sin(c.t * 3.1f) * 0.7
        val body = c.root(0f, bob.toFloat())
            .rotateX(0.18f + mv * 0.25f + sin(c.t * 2.3f) * 0.05f)
            .rotateZ(sin(c.t * 1.9f) * 0.06f)
        c.add(b.body, body)
        for (side in intArrayOf(1, -1)) {
            val s = side.toFloat()
            c.eye(body, b.eye, side, c.blink)
            c.add(b.antenna, Matrix4f(body).translate(s * 1.5f * V, -4.5f * V, -6f * V).scale(s, 1f, 1f)
                .rotateZ(sin(c.t * 5f + s) * 0.15f).rotateX(sin(c.t * 4f + s * 2f) * 0.12f))
            // Six legs paddling gently, front and back out of step.
            for ((row, z) in intArrayOf(-3, 0, 3).withIndex()) {
                c.add(b.leg, Matrix4f(body).translate(s * 3f * V, 4.2f * V, z * V).scale(s, 1f, 1f)
                    .rotateX(sin(2f * ph + row * 2.1f + if (side > 0) 0f else PI.toFloat()) * 0.45f - 0.1f)
                    .rotateZ(-0.2f - row * 0.1f))
            }
            // Wings: a fast buzz drawn as the wing plus two fainter copies either side, so it reads as a blur.
            val buzz = sin(16f * ph)
            val base = -(0.5f + 0.4f * buzz)
            val root = Matrix4f(body).translate(s * 1.5f * V, -5f * V, -2f * V).scale(s, 1f, 1f).rotateY(-0.5f)
            c.add(b.wing, Matrix4f(root).rotateZ(base), Kind.TRANSLUCENT, 150)
            c.add(b.wing, Matrix4f(root).rotateZ(base + 0.28f), Kind.TRANSLUCENT, 60)
            c.add(b.wing, Matrix4f(root).rotateZ(base - 0.28f), Kind.TRANSLUCENT, 60)
        }
        c.add(b.stinger, Matrix4f(body).translate(0f, 1f * V, 7f * V)
            .rotateX(sin(c.t * 6f) * 0.25f).rotateY(sin(c.t * 4.3f) * 0.2f))
    }

    // ---- slime -----------------------------------------------------------------------------------------

    /** Hop lift in 0..1 at cycle position [u]: on the ground until the push-off, then a parabola. */
    @JvmStatic
    fun hopLift(u: Float): Float {
        val w = (u - 0.22f) / 0.78f
        return if (w <= 0f || w >= 1f) 0f else 4f * w * (1f - w)
    }

    /** Vertical scale at cycle position [u]: crouch before the jump, stretch in the air, squash on landing. */
    @JvmStatic
    fun hopScale(u: Float): Float {
        val w = ((u - 0.22f) / 0.78f).coerceIn(0f, 1f)
        return 1f - 0.28f * bump(u, 0.1f, 0.13f) + 0.2f * sin(PI.toFloat() * w) - 0.22f * bump(u, 0.99f, 0.07f)
    }

    private fun fract(v: Float): Float = v - floor(v)

    private fun slime(c: Ctx) {
        val s = CosmeticModelsD.SLIME
        val u = fract(c.phase / TAU)
        val height = 3.5f + c.moving * 2f
        val lift = hopLift(u) * height
        val sy = hopScale(u)
        val sxz = 1f / kotlin.math.sqrt(sy)
        val outer = c.root(0.5f, 0f)
            .translate(0f, -lift, 0f)
            .translate(0f, 3.5f, 0f).scale(sxz, sy, sxz).translate(0f, -3.5f, 0f)
        c.add(s.outer, outer, Kind.TRANSLUCENT, 105)
        c.add(s.frame, Matrix4f(outer), Kind.TRANSLUCENT, 175)
        // The core lags the shell: it squashes a beat later and hangs behind when the pet moves.
        val u2 = fract(u - 0.08f)
        val lag = (hopLift(u2) - hopLift(u)) * height
        val sy2 = hopScale(u2)
        val sxz2 = 1f / kotlin.math.sqrt(sy2)
        val core = c.root(0.5f, 0f)
            .translate(c.pose.dragX * 0.4f, -lift - lag + c.pose.dragY * 0.4f + 0.3f, c.pose.dragZ * 0.4f)
            .translate(0f, 2f, 0f).scale(sxz2, sy2, sxz2).translate(0f, -2f, 0f)
        c.add(s.core, core)
        for (side in intArrayOf(1, -1)) c.eye(core, s.eye, side, c.blink)
    }

    // ---- axolotl ---------------------------------------------------------------------------------------

    private fun axolotl(c: Ctx) {
        val a = CosmeticModelsD.AXOLOTL
        val ph = c.phase
        val bob = sin(c.t * 1.9f) * 0.8f
        val root = c.root(0f, bob).translate(0f, 0f, -11f * V)
        val swim = sin(2f * ph)
        val body = Matrix4f(root).rotateY(swim * 0.1f).rotateZ(sin(c.t * 1.2f) * 0.05f).rotateX(sin(2f * ph - 0.5f) * 0.04f)
        c.add(a.body, body)
        val head = Matrix4f(body).rotateY(-swim * 0.08f + c.gazeYaw * 0.45f).rotateX(c.gazePitch * 0.35f)
        c.add(a.head, head)
        for (side in intArrayOf(1, -1)) {
            val s = side.toFloat()
            c.eye(head, a.eye, side, c.blink)
            // Three feathery gills a side, each waving on its own.
            for (i in 0 until 3) {
                val fan = floatArrayOf(-0.75f, 0f, 0.7f)[i]
                c.add(a.gill, Matrix4f(head).translate(s * 5f * V, (-0.5f + i * 0.2f) * V, -2.5f * V).scale(s, 1f, 1f)
                    .rotateY(-0.55f + sin(3f * ph + i) * 0.15f)
                    .rotateZ(fan + sin(4f * ph + i * 1.3f + s * 0.7f) * 0.25f))
            }
            // Legs paddle as if swimming: front and rear pairs out of step, left and right opposite.
            for ((row, z) in floatArrayOf(2f, 8f).withIndex()) {
                val offset = row * PI.toFloat() + if (side > 0) 0f else PI.toFloat()
                c.add(a.leg, Matrix4f(body).translate(s * 3.5f * V, 2f * V, z * V).scale(s, 1f, 1f)
                    .rotateY(sin(2f * ph + offset) * 0.6f)
                    .rotateZ(0.15f + sin(2f * ph + offset + 1.2f) * 0.2f))
            }
        }
        // The tail undulates: a wave runs down it.
        val tail = Matrix4f(body).translate(0f, 0f, CosmeticModelsD.AXOLOTL_BODY * V)
        for ((i, mesh) in a.tail.withIndex()) {
            tail.rotateY(sin(2f * ph - (i + 1) * 0.9f) * (0.28f + i * 0.08f) + c.pose.dragX * 0.01f)
            c.add(mesh, Matrix4f(tail))
            tail.translate(0f, 0f, CosmeticModelsD.AXOLOTL_TAIL * V)
        }
    }

    // ---- owl -------------------------------------------------------------------------------------------

    private fun owl(c: Ctx) {
        val o = CosmeticModelsD.OWL
        val ph = c.phase
        val mv = c.moving
        val flap = sin(3f * ph)
        val bob = sin(c.t * 2.3f) * 0.6f - flap * 0.5f
        val body = c.root(0f, bob).translate(0f, -1f * V, 0f).rotateX(0.1f - mv * 0.1f).rotateZ(sin(c.t * 1.4f) * 0.05f)
        c.add(o.body, body)
        // The head swivels well past the shoulders and tilts as it looks.
        val head = Matrix4f(body).rotateZ(sin(c.t * 0.8f) * 0.1f - c.gazeYaw * 0.14f)
            .rotateY((c.gazeYaw * 1.5f).coerceIn(-1.3f, 1.3f)).rotateX(c.gazePitch * 0.4f)
        c.add(o.head, head)
        c.add(o.beak, Matrix4f(head).translate(0f, -3.5f * V, (o.beakZ + 0.5f) * V))
        for (side in intArrayOf(1, -1)) {
            val s = side.toFloat()
            c.eye(head, o.eye, side, c.blink)
            val spread = 0.15f + (0.5f + 0.15f * mv) * (0.5f + 0.5f * flap)
            c.add(o.wing, Matrix4f(body).translate(s * 5f * V, 1.5f * V, 0.3f * V).scale(s, 1f, 1f)
                .rotateZ(-spread).rotateY(0.25f * sin(3f * ph - 1f)).rotateX(0.1f))
            c.add(o.foot, Matrix4f(body).translate(s * 2.5f * V, 10f * V, -0.5f * V).scale(s, 1f, 1f)
                .rotateX(0.3f + sin(c.t * 2f + s) * 0.15f))
        }
        c.add(o.tail, Matrix4f(body).translate(0f, 8f * V, 4f * V).rotateX(0.4f + sin(3f * ph - 1.2f) * 0.12f + mv * 0.2f))
    }

    // ---- auras -----------------------------------------------------------------------------------------

    private fun scaleOf(piece: CosmeticPiece): Float = piece.size / AURA_NATIVE

    private val CLOUD_LOW: Float by lazy { lowest(CosmeticModelsD.CLOUD) }
    private val PUFF_LOW: Float by lazy { lowest(CosmeticModelsD.CLOUD_PUFF) }

    private fun lowest(mesh: VoxelMesh): Float {
        var low = 0f
        for (i in 1 until mesh.positions.size step 3) low = max(low, mesh.positions[i])
        return low
    }

    /**
     * The storm cloud raised (via its Height) so the cloud and its wisps stay at least a pixel above [headTop]
     * (head frame, y down; the bare head top is -8), or [piece] itself when it already clears it.
     */
    @JvmStatic
    fun clearHead(piece: CosmeticPiece, headTop: Float): CosmeticPiece {
        val s = scaleOf(piece)
        val cy = -13f - (piece.up - AURA_UP) + 0.6f * s
        val bottom = max(cy + CLOUD_LOW * s * 1.3f * 1.02f, cy + 0.5f + 0.8f * s + PUFF_LOW * s)
        val extra = bottom - (headTop - 1f)
        return if (extra > 0f) piece.copy(up = piece.up + extra) else piece
    }

    private fun stormCloud(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<Placement>) {
        val t = motion.seconds * piece.speed
        val s = scaleOf(piece)
        val up = piece.up - AURA_UP
        val back = piece.back - AURA_BACK
        val cx = sin(t * 0.4f + motion.seed) * 1.6f * s
        val cz = back + cos(t * 0.33f) * 1.2f * s
        val cy = -13f - up + sin(t * 0.9f) * 0.6f * s
        val breathe = 1f + 0.02f * sin(t * 1.3f)
        val cloud = Matrix4f().translate(cx, cy, cz).scale(s * breathe * 1.3f)
        out.add(Placement(Anchor.BODY, CosmeticModelsD.CLOUD, cloud, piece.color, piece.color2))
        // Wisps drift round the main cloud on their own slow orbits.
        for (i in 0 until 2) {
            val a = t * (0.35f + i * 0.17f) + i * 2.4f
            val m = Matrix4f().translate(cx + cos(a) * 10f * s, cy + 0.5f + sin(a * 1.3f) * 0.8f * s, cz + sin(a) * 6f * s)
                .scale(s * (0.9f + 0.1f * sin(t + i)))
            out.add(Placement(Anchor.BODY, CosmeticModelsD.CLOUD_PUFF, m, piece.color, piece.color2))
        }
        // Rain falls from the cloud's base and fades; the drops share the cloud's footprint.
        val base = cy + 4f * s
        val feet = 24f
        for (i in 0 until RAIN_DROPS) {
            val life = fract(t * 0.55f + unit(i, 3) + motion.seed * 0.017f)
            val fade = min(1f, life * 10f) * min(1f, (1f - life) * 3f)
            if (fade < 0.04f) continue
            val x = cx + (unit(i, 5) - 0.5f) * 11f * s
            val z = cz + (unit(i, 9) - 0.5f) * 7f * s
            val y = base + (feet - base) * life
            val m = Matrix4f().translate(x, y, z).scale(s)
            out.add(Placement(Anchor.BODY, CosmeticModelsD.RAIN, m, 0, 0, Kind.TRANSLUCENT, (210 * fade).toInt()))
        }
        // Lightning: a double flash every few seconds lights the cloud and draws a bolt below it.
        val period = 5.4f
        val local = t + unit(motion.seed, 2) * period
        val cycle = floor(local / period).toInt()
        val u = local - cycle * period
        val flash = max(0f, 1f - abs(u - 0.06f) / 0.07f).let { max(it, 0.7f * max(0f, 1f - abs(u - 0.26f) / 0.1f)) }
        if (flash > 0.03f) {
            val glow = Matrix4f(cloud).scale(1.03f)
            out.add(Placement(Anchor.BODY, CosmeticModelsD.CLOUD, glow, 0xE8F0FF, 0xBFD0FF, Kind.GLOW, (150 * flash).toInt()))
            val bolt = CosmeticModelsD.BOLTS[Math.floorMod(hash(cycle, motion.seed), CosmeticModelsD.BOLTS.size)]
            val bx = cx + (unit(cycle, motion.seed) - 0.5f) * 8f * s
            val m = Matrix4f().translate(bx, base - 0.5f * s, cz).scale(s * 1.1f).translate(0f, 0f, -0.5f)
            out.add(Placement(Anchor.BODY, bolt, m, 0, 0, Kind.GLOW, (255 * min(1f, flash * 1.4f)).toInt()))
        }
    }

    private fun flameRing(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<Placement>) {
        val t = motion.seconds * piece.speed
        val s = scaleOf(piece)
        val up = piece.up - AURA_UP
        val back = piece.back - AURA_BACK
        val radius = 9.5f * s
        val floorY = 23.5f - up
        for (i in 0 until FLAME_TONGUES) {
            val a = i * TAU / FLAME_TONGUES + t * 0.35f
            val flicker = 1f + 0.22f * sin(t * 9.3f + i * 1.7f) + 0.1f * sin(t * 15f + i * 3.3f)
            val width = 1f + 0.12f * sin(t * 7f + i * 2.1f)
            val m = Matrix4f()
                .translate(cos(a) * radius, floorY, sin(a) * radius + back)
                .rotateY(PI.toFloat() / 2f - a)
                .rotateZ(0.12f * sin(t * 5f + i * 1.3f))
                .scale(s * 1.2f * width, s * 1.6f * flicker, s)
                .translate(0f, 0f, -0.5f * V)
            out.add(Placement(Anchor.BODY, CosmeticModelsD.FLAMES[i % CosmeticModelsD.FLAMES.size], m, piece.color, piece.color2, Kind.GLOW, 235))
        }
        // Embers lift off the ring and cool as they rise.
        for (i in 0 until EMBERS) {
            val life = fract(t * 0.4f + unit(i, 21) + motion.seed * 0.011f)
            val fade = sin(PI.toFloat() * life)
            if (fade < 0.05f) continue
            val a = unit(i, 23) * TAU + t * 0.5f
            val r = radius * (0.4f + 0.7f * unit(i, 25)) + sin(t * 2f + i) * 0.8f
            val size = (0.5f + 0.5f * unit(i, 27)) * (1f - 0.5f * life) * s
            val m = Matrix4f().translate(cos(a) * r, floorY - 1f - life * 27f * s, sin(a) * r + back).scale(size).translate(-0.5f, -0.5f, -0.5f)
            val color = if (i % 3 == 0) piece.color else piece.color2
            out.add(Placement(Anchor.BODY, CosmeticModels.CUBE, m, color, color, Kind.GLOW, (240 * fade).toInt()))
        }
    }

    private fun hearts(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<Placement>) {
        val t = motion.seconds * piece.speed
        val s = scaleOf(piece)
        val up = piece.up - AURA_UP
        val back = piece.back - AURA_BACK
        for (i in 0 until HEARTS) {
            val life = fract(t * 0.14f + unit(i, 31) + motion.seed * 0.013f)
            val fade = sin(PI.toFloat() * life).pow(0.7f)
            if (fade < 0.05f) continue
            val a = i * 2.39996f + t * 0.3f
            val radius = (9.5f + 2.5f * sin(i * 1.7f)) * s
            val size = (1.15f - 0.7f * life) * (0.85f + 0.4f * unit(i, 33)) * 1.25f * s
            val x = cos(a) * radius + sin(t * 1.3f + i * 2f) * 1.2f * s
            val y = 23f - life * 36f * s - up
            val z = sin(a) * radius + back
            // Face outward, wobbling a little, like they drift on a breeze.
            val facing = atan2(-cos(a), -sin(a))
            val m = Matrix4f().translate(x, y, z)
                .rotateY(facing + sin(t * 0.9f + i) * 0.5f)
                .rotateZ(sin(t * 1.1f + i * 1.3f) * 0.25f)
                .scale(size)
            val color = if (i % 2 == 0) piece.color else piece.color2
            val solid = fade > 0.95f
            out.add(Placement(Anchor.BODY, CosmeticModelsD.HEART, m, color, color, if (solid) Kind.SOLID else Kind.TRANSLUCENT, if (solid) 255 else (255 * fade).toInt()))
        }
    }
}
