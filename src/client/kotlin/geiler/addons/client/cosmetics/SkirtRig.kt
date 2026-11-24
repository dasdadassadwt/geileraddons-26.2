package geiler.addons.client.cosmetics

import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Places and animates the SKIRT slot. The cloth is one baked mesh in rest pose; every frame its vertices are
 * moved into a reused buffer (no mesh building, no allocation) by one smooth field:
 * - the waistband follows the body shape like the shaped torso and the bent armor (rows narrow by
 *   [BodyShapeGeometry.waistIn] / [BodyShapeGeometry.depthIn], so the gap to skin and leggings stays constant);
 * - below the waist the cloth swings about the hips with the legs: the front lifts with the forward leg and the
 *   back with the backward leg, blending left to right between the two legs (a slit skirt blends sharply, so the
 *   slit opens with the stride);
 * - the hem lifts, flares and ripples with the stride, sways when idle and trails when running or falling.
 * Leg angles come from the live model ([setLegs], set by the render layer) or, without one (menu, previews),
 * from the walk phase. Loose parts (kilt pouch, bow tails) are rigid placements hung at the deformed anchors.
 * Render thread only.
 */
object SkirtRig {
    private const val TAU = (2.0 * PI).toFloat()
    private const val HIP = 12f
    private const val MAX_LEG = 1.4f
    private const val BUFFERS = 3

    private var liveRight = 0f
    private var liveLeft = 0f
    private var live = false

    /** The model's leg pitch (radians, positive = foot backward) for the skirt about to be placed. */
    @JvmStatic
    fun setLegs(right: Float, left: Float) {
        liveRight = right.coerceIn(-MAX_LEG, MAX_LEG)
        liveLeft = left.coerceIn(-MAX_LEG, MAX_LEG)
        live = true
    }

    @JvmStatic
    fun clearLegs() {
        live = false
    }

    /**
     * One per (player, style): the rest mesh's views and the per-row numbers of the current frame. The mesh is
     * drawn later in the frame than it is placed, so [BUFFERS] position buffers take turns.
     */
    class Entry(val model: SkirtModel) {
        private val rest = model.mesh.positions
        private val views = Array(BUFFERS) {
            VoxelMesh(
                rest.copyOf(), model.mesh.directions, model.mesh.channels, model.mesh.shades,
                model.mesh.smoothNormals, model.mesh.palette,
            )
        }
        private var next = 0

        // Per half-pixel row: waist hug, radial scale, follow weight and offsets.
        val fx = FloatArray(SkirtGeometry.ROWS)
        val fz = FloatArray(SkirtGeometry.ROWS)
        val rf = FloatArray(SkirtGeometry.ROWS)
        val hw = FloatArray(SkirtGeometry.ROWS)
        val dx = FloatArray(SkirtGeometry.ROWS)
        val dy = FloatArray(SkirtGeometry.ROWS)
        val dz = FloatArray(SkirtGeometry.ROWS)
        var thetaRight = 0f
        var thetaLeft = 0f
        var follow = 0f
        var blend = 1f

        /** The mesh of this frame: rest vertices moved by the field. */
        fun deformed(): VoxelMesh {
            val view = views[next]
            next = (next + 1) % BUFFERS
            val out = view.positions
            val point = FloatArray(4)
            var i = 0
            while (i < rest.size) {
                warp(rest[i], rest[i + 1], rest[i + 2], point)
                out[i] = point[0]
                out[i + 1] = point[1]
                out[i + 2] = point[2]
                i += 3
            }
            return view
        }

        /** Moves a rest point. [out] gets x, y, z and the swing angle applied there (radians). */
        fun warp(x0: Float, y0: Float, z0: Float, out: FloatArray) {
            val r = ((y0 * 2f + 0.5f).toInt() - SkirtGeometry.ROW0).coerceIn(0, SkirtGeometry.ROWS - 1)
            val scale = rf[r]
            var x = x0 * fx[r] * scale
            var z = z0 * fz[r] * scale
            var y = y0
            var angle = 0f
            val w = hw[r]
            if (w > 0f && (thetaRight != 0f || thetaLeft != 0f)) {
                val tx = smooth((x0 + blend) / (2f * blend))
                val theta = thetaRight + (thetaLeft - thetaRight) * tx
                val front = smooth((ZB - z0) / (2f * ZB))
                angle = w * follow * (front * min(0f, theta) + (1f - front) * max(0f, theta))
                if (angle != 0f) {
                    val a2 = angle * angle
                    val s = angle * (1f - a2 / 6f * (1f - a2 / 20f))
                    val c = 1f - a2 / 2f * (1f - a2 / 12f)
                    val ry = y - HIP
                    y = HIP + ry * c - z * s
                    z = ry * s + z * c
                }
            }
            out[0] = x + dx[r]
            out[1] = y + dy[r]
            out[2] = z + dz[r]
            out[3] = angle
        }

        /** Fills the row tables for one frame. */
        fun pose(piece: CosmeticPiece, motion: CosmeticRig.Motion, shape: BodyShape?) {
            val look = model.look
            val speed = piece.speed.coerceAtLeast(0f)
            val on = speed > 0f
            val moving = if (on) motion.moving else 0f
            val air = if (on) motion.air else 0f
            val phase = motion.swayPhase * speed
            val t = motion.seconds * speed
            val seedPhase = ((motion.seed * 0.6180339f) % 1f) * TAU
            if (!on) {
                thetaRight = 0f
                thetaLeft = 0f
            } else if (live) {
                thetaRight = liveRight
                thetaLeft = liveLeft
            } else {
                // No model to read: a stride from the walk phase, legs opposite each other.
                val stride = 0.75f * moving * sin(phase)
                thetaRight = stride
                thetaLeft = -stride
            }
            follow = if (on) look.follow else 0f
            blend = look.blend
            val sep = abs(thetaRight - thetaLeft) / 2.4f
            val swayX = look.sway * 0.5f * sin(t * 1.1f + seedPhase)
            val swayZ = look.sway * 0.4f * sin(t * 0.8f + seedPhase * 1.3f)
            for (r in 0 until SkirtGeometry.ROWS) {
                val y = (SkirtGeometry.ROW0 + r) * 0.5f
                val a = model.rowA[r]
                val b = model.rowB[r]
                val wi = if (shape == null) 0f else BodyShapeGeometry.waistIn(shape, y)
                val di = if (shape == null) 0f else BodyShapeGeometry.depthIn(shape, y)
                fx[r] = (a - wi) / a
                fz[r] = (b - di) / b
                val h = smooth((y - look.yStart) / (model.yHem - look.yStart))
                hw[r] = h
                val ripple = look.ripple * moving * sin(phase * 2f - y * 0.8f) * h
                rf[r] = 1f + ripple + (look.flare * sep + 0.06f * air) * h
                dy[r] = -(look.bounce * sep + 0.9f * air) * h
                val h2 = h * h
                dx[r] = swayX * h2 + look.flow * moving * sin(phase - y * 0.4f) * h2
                dz[r] = swayZ * h2 + 0.5f * moving * h2
            }
        }
    }

    private const val ZB = 2.2f

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private val entries = HashMap<Long, Entry>()

    private fun entry(style: CosmeticStyle, seed: Int): Entry {
        val key = seed.toLong() * 64L + style.ordinal
        return entries.getOrPut(key) {
            if (entries.size > 48) entries.clear()
            Entry(SkirtGeometry.model(style))
        }
    }

    /** The posed entry for tests: row tables are filled for [piece] at [motion]. */
    @JvmStatic
    fun posed(piece: CosmeticPiece, motion: CosmeticRig.Motion, shape: BodyShape?): Entry =
        Entry(SkirtGeometry.model(piece.style)).also { it.pose(piece, motion, shape) }

    @JvmStatic
    fun place(piece: CosmeticPiece, motion: CosmeticRig.Motion, shape: BodyShape?, out: MutableList<CosmeticRig.Placement>) {
        val e = entry(piece.style, motion.seed)
        e.pose(piece, motion, shape)
        // Height and depth sliders move the skirt; size scales it about the waist so the band keeps its place.
        val base = Matrix4f().translate(0f, -piece.up, piece.back).translate(0f, SkirtGeometry.BAND_TOP, 0f)
            .scale(piece.size).translate(0f, -SkirtGeometry.BAND_TOP, 0f)
        out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, e.deformed(), Matrix4f(base), piece.color, piece.color2))
        when (piece.style) {
            CosmeticStyle.KILT -> pouch(piece, motion, e, base, out)
            CosmeticStyle.MAID_SKIRT -> bow(piece, motion, e, base, out)
            else -> Unit
        }
    }

    private fun stride(e: Entry): Float = (e.thetaLeft - e.thetaRight) * 0.5f

    /** Slope of the skirt's front or back surface at height [y] (radians from vertical, outward positive). */
    private fun slope(e: Entry, y: Float, front: Boolean): Float {
        val r0 = ((y * 2f).toInt() - SkirtGeometry.ROW0).coerceIn(0, SkirtGeometry.ROWS - 2)
        val rows = if (front) e.model.rowB else e.model.rowB
        return kotlin.math.atan2((rows[min(r0 + 6, SkirtGeometry.ROWS - 1)] - rows[r0]), 3f)
    }

    private fun pouch(piece: CosmeticPiece, motion: CosmeticRig.Motion, e: Entry, base: Matrix4f, out: MutableList<CosmeticRig.Placement>) {
        val y = 9.35f
        val r = ((y * 2f).toInt() - SkirtGeometry.ROW0).coerceIn(0, SkirtGeometry.ROWS - 1)
        val point = FloatArray(4)
        e.warp(SkirtGeometry.POUCH_X, y, -(e.model.rowB[r] - 0.1f), point)
        val t = motion.seconds * piece.speed
        val swing = if (piece.speed > 0f) 0.12f * stride(e) + 0.04f * sin(t * 1.9f + motion.seed) else 0f
        val m = Matrix4f(base).translate(point[0], point[1], point[2])
            .rotateX(-slope(e, y, true) + point[3] + swing)
        out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, CosmeticModelsE.POUCH, m, piece.color, piece.color2))
    }

    private fun bow(piece: CosmeticPiece, motion: CosmeticRig.Motion, e: Entry, base: Matrix4f, out: MutableList<CosmeticRig.Placement>) {
        val y = 9.3f
        val r = ((y * 2f).toInt() - SkirtGeometry.ROW0).coerceIn(0, SkirtGeometry.ROWS - 1)
        val point = FloatArray(4)
        e.warp(0f, y, e.model.rowB[r] - 0.1f, point)
        val knot = Matrix4f(base).translate(point[0], point[1], point[2])
        out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, CosmeticModelsE.BOW, knot, piece.color, piece.color2))
        val t = motion.seconds * piece.speed
        val moving = if (piece.speed > 0f) motion.moving else 0f
        for (side in intArrayOf(-1, 1)) {
            val flutter = if (piece.speed > 0f) (0.05f + 0.12f * moving) * sin(t * 3.3f + side * 1.7f + motion.seed) else 0f
            val m = Matrix4f(knot).translate(side * 0.9f, 1.0f, 0.4f)
                .scale(side.toFloat(), 1f, 1f)
                .rotateX(slope(e, y, false) + 0.25f * moving + flutter)
                .rotateZ(0.2f + 0.5f * flutter)
            // The tail mesh hangs from y = 0 along +y and sticks out toward +z; mirrored for the left tail.
            out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, CosmeticModelsE.BOW_TAIL, m, piece.color, piece.color2))
        }
    }
}
