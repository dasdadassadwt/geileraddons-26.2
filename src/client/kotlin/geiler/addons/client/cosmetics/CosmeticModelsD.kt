package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Voxel art for the pets and the storm cloud, flame ring and heart auras, in half-pixel voxels (see [Sculpt]).
 * Colors: channel 0 is the player's Color and channel 1 the Second Color (the red and black areas of the
 * reference sheet); only small accents (eyes, beak, rain, lightning) keep a fixed color. Every part is a
 * separate rigid mesh with its joint at the mesh origin, so [CosmeticRigD] only builds matrices per frame.
 * Frames: +x is the pet's left, y grows down, z grows backward (the pet faces -z); left-side parts are
 * modelled once and mirrored for the right side. All numbers below are voxels (1 voxel = 0.5 pixel).
 */
object CosmeticModelsD {
    private const val DARK = 0x1B1317
    private const val WHITE = 0xFFFFFF

    /** A slab that blinks: its mesh plus where it sits on the face it belongs to (voxels). */
    class Eye(@JvmField val mesh: VoxelMesh, @JvmField val x: Float, @JvmField val y: Float, @JvmField val z: Float)

    // ---- baby dragon -------------------------------------------------------------------------------

    class DragonParts(
        @JvmField val body: VoxelMesh,
        @JvmField val head: VoxelMesh,
        @JvmField val arm: VoxelMesh,
        @JvmField val leg: VoxelMesh,
        @JvmField val tail: List<VoxelMesh>,
        @JvmField val wingInner: VoxelMesh,
        @JvmField val wingOuter: VoxelMesh,
        @JvmField val eye: Eye,
    )

    // Joints in the body frame and the wing frame (voxels).
    const val DRAGON_NECK_X = 0f
    const val DRAGON_NECK_Y = -3f
    const val DRAGON_NECK_Z = -3.5f
    const val DRAGON_SHOULDER_X = 4.2f
    const val DRAGON_SHOULDER_Y = 0f
    const val DRAGON_SHOULDER_Z = -3f
    const val DRAGON_HIP_X = 2.8f
    const val DRAGON_HIP_Y = 6.5f
    const val DRAGON_HIP_Z = 1.5f
    const val DRAGON_TAIL_Y = 5.5f
    const val DRAGON_TAIL_Z = 5.5f
    const val DRAGON_WING_X = 3.4f
    const val DRAGON_WING_Y = -3.2f
    const val DRAGON_WING_Z = 2.4f
    const val DRAGON_WRIST_X = 8f
    const val DRAGON_WRIST_Y = -6f
    val DRAGON_TAIL_LENGTHS = floatArrayOf(5.5f, 5f, 4.5f, 4f)

    val DRAGON: DragonParts by lazy { dragon() }

    private fun dragon(): DragonParts {
        // Body: chubby egg, lighter belly, dark spines down the back.
        val body = Sculpt()
        body.ellipsoid(0f, 2f, 1f, 5f, 6f, 6f, 0, 1f)
        body.tone { _, y, z -> (if (z < 1 && y > 0) 0.1f else 0f) + (if (z > 3) -0.05f else 0f) }
        for ((y0, z0, y1, z1) in listOf(
            floatArrayOf(-3.4f, -1f, -5.8f, 0.2f), floatArrayOf(-3.7f, 2f, -6.3f, 3.4f),
            floatArrayOf(-2.4f, 5f, -4.4f, 6.8f), floatArrayOf(0.5f, 6.6f, 1.6f, 9f),
        )) body.capsule(0f, y0, z0, 0f, y1, z1, 1.0f, 0.15f, 1, 0.95f)
        // Head and neck, pivoting at the base of the neck.
        val head = Sculpt()
        head.capsule(0f, 0.5f, 1f, 0f, -3f, -1.5f, 2.4f, 2.1f, 0, 1f)
        head.rbox(-4f, -9f, -7f, 4f, -3f, 0f, 1.8f, 0, 1.02f)
        head.rbox(-2.6f, -6.8f, -10.5f, 2.6f, -3.2f, -5.5f, 1.2f, 0, 1.0f)
        val dark = head.fixed(DARK)
        head.paintFront(-2, -6, 1, 0.7f)
        head.paintFront(1, -6, 1, 0.7f)
        for (x in -2..1) head.paintFront(x, -4, dark, 0.95f)
        for (side in intArrayOf(1, -1)) {
            head.capsule(side * 2.7f, -8.8f, -1.5f, side * 3.7f, -12.4f, 0.8f, 1.0f, 0.25f, 1, 0.95f)
            head.capsule(side * 4f, -6f, -0.5f, side * 6.6f, -7.2f, 1.6f, 0.8f, 0.2f, 1, 0.9f)
        }
        // Limbs: a small clawed arm and a thick leg with a foot, modelled for the left side.
        val arm = Sculpt()
        arm.capsule(0f, 0f, 0f, 0.8f, 5.5f, -1.5f, 1.5f, 1.1f, 0, 1f)
        arm.ellipsoid(0.8f, 5.8f, -1.7f, 1.4f, 1.3f, 1.4f, 0, 1f)
        for (x in floatArrayOf(-0.1f, 0.8f, 1.7f)) arm.capsule(x, 6.7f, -2.1f, x, 7.9f, -3.0f, 0.5f, 0.3f, 1, 0.9f)
        val leg = Sculpt()
        leg.capsule(0f, 0f, 0f, 0.4f, 4.2f, 0f, 2.1f, 1.7f, 0, 1f)
        leg.rbox(-1.9f, 3.4f, -3.6f, 2.1f, 5.6f, 1.6f, 0.9f, 0, 0.98f)
        for (x in floatArrayOf(-0.9f, 0.2f, 1.3f)) leg.capsule(x, 4.8f, -3.3f, x, 5.2f, -4.5f, 0.5f, 0.3f, 1, 0.9f)
        // Tail: four tapering segments with a spine each and a spade at the tip.
        val radii = floatArrayOf(3.0f, 2.3f, 1.7f, 1.2f, 0.55f)
        val tail = ArrayList<VoxelMesh>()
        for (i in 0 until 4) {
            val s = Sculpt()
            val len = DRAGON_TAIL_LENGTHS[i]
            s.capsule(0f, 0f, 0f, 0f, 0.4f, len + 0.5f, radii[i], radii[i + 1], 0, 1f - i * 0.02f)
            if (i < 3) s.capsule(0f, -radii[i] + 0.4f, len * 0.35f, 0f, -radii[i] - 1.6f, len * 0.6f, 0.9f - i * 0.12f, 0.15f, 1, 0.95f)
            if (i == 3) {
                for (z in 2..8) {
                    val half = if (z < 5) 0.9f + (z - 2) * 0.5f else max(0f, 2.4f - (z - 5) * 0.8f)
                    for (x in floor(-half).toInt()..floor(half - 0.01f).toInt()) {
                        s.set(x, -1, z, 1, 0.95f)
                        if (abs(x + 0.5f) < half - 0.6f) s.set(x, 0, z, 1, 0.9f)
                    }
                }
            }
            tail.add(s.bake(smooth = i < 3))
        }
        val eye = eye(2, 2, 0f)
        return DragonParts(
            body.bake(smooth = true), head.bake(smooth = true), arm.bake(), leg.bake(), tail,
            dragonWing(false), dragonWing(true), Eye(eye, 2.8f, -7.6f, -7f),
        )
    }

    /** Wing outline, left side, origin at the shoulder; y grows down. */
    private val DRAGON_WING_POINTS = arrayOf(
        floatArrayOf(0f, 0f), floatArrayOf(8f, -6f), floatArrayOf(21f, -5f), floatArrayOf(20f, 5f), floatArrayOf(13f, 12f), floatArrayOf(3f, 9f),
    )

    /**
     * The inner part is the arm bone and the membrane near the body; the outer part is the fingers and the
     * membrane between them, hinged at the wrist. The two overlap by a few voxels in the same flat color,
     * so the hinge never opens a gap.
     */
    private fun dragonWing(outer: Boolean): VoxelMesh {
        val s = Sculpt()
        val p = DRAGON_WING_POINTS
        val ox = if (outer) DRAGON_WRIST_X.toInt() else 0
        val oy = if (outer) DRAGON_WRIST_Y.toInt() else 0
        val cx = p.sumOf { it[0].toDouble() }.toFloat() / p.size
        val cy = p.sumOf { it[1].toDouble() }.toFloat() / p.size
        val edges = arrayOf(arrayOf(p[2], p[3]), arrayOf(p[3], p[4]), arrayOf(p[4], p[5]))
        for (v in -10..16) for (u in -1..23) {
            val x = u + 0.5f
            val y = v + 0.5f
            if (!insidePolygon(p, x, y)) continue
            var cut = false
            for (e in edges) {
                val t = projectT(e[0], e[1], x, y)
                if (t <= 0f || t >= 1f) continue
                if (signedDistance(e[0], e[1], x, y, cx, cy) < 2.6f * sin(PI.toFloat() * t)) cut = true
            }
            if (cut) continue
            // Positive toward the shoulder side of the line from the wrist to the third finger tip.
            val side = signedDistance(p[1], p[4], x, y, 0f, 0f)
            if (if (outer) side > 3f else side < -3f) continue
            val shade = 0.9f - (y + 6f) * 0.006f + CosmeticModels.jitter(u, v, 0, 0.03f)
            s.setFlat(u - ox, v - oy, 0, 1, shade)
        }
        fun bone(a: FloatArray, b: FloatArray, ra: Float, rb: Float) {
            s.capsule(a[0] - ox, a[1] - oy, 0.5f, b[0] - ox, b[1] - oy, 0.5f, ra, rb, 0, 1f)
        }
        if (outer) {
            bone(p[1], p[2], 1.1f, 0.5f)
            bone(p[1], p[3], 1.1f, 0.5f)
            bone(p[1], p[4], 1.1f, 0.45f)
            s.ellipsoid(0f, 0f, 0.5f, 1.8f, 1.8f, 1.8f, 0, 0.95f)
        } else {
            bone(p[0], p[1], 1.7f, 1.25f)
            bone(p[1], floatArrayOf(7f, -10.5f), 0.7f, 0.2f)
            // A short bone along the trailing membrane to the body.
            bone(p[0], p[5], 0.9f, 0.6f)
        }
        // The arm bone and fingers read as ridges; the membrane stays flat, so it keeps its own shade.
        return s.bake(smooth = false, jitter = 0.02f)
    }

    // ---- ghost -------------------------------------------------------------------------------------

    class GhostParts(
        @JvmField val body: VoxelMesh,
        @JvmField val tabLong: VoxelMesh,
        @JvmField val tabShort: VoxelMesh,
        @JvmField val eye: Eye,
    )

    const val GHOST_TABS = 7

    /** Tab lengths (voxels below the body bottom), left to right. */
    val GHOST_TAB_LENGTHS = intArrayOf(4, 2, 4, 2, 4, 2, 4)

    /** Where the skirt tabs start (voxel y). */
    const val GHOST_SKIRT_Y = 3

    val GHOST: GhostParts by lazy { ghost() }

    private fun ghostShell(s: Sculpt, ch: Int) {
        s.rbox(-7f, -9f, -6f, 7f, 15f, 6f, 3.2f, ch, 1f)
        s.tone { _, y, z -> (if (z < -2) 0.04f else 0f) - (max(0, y) * 0.012f) }
    }

    private fun ghost(): GhostParts {
        val body = Sculpt()
        ghostShell(body, 0)
        for (x in -7..6) for (y in GHOST_SKIRT_Y..15) for (z in -6..5) body.clear(x, y, z)
        val cheek = 1
        val dark = body.fixed(DARK)
        for (side in intArrayOf(1, -1)) for (x in 0 until 2) body.paintFront(if (side > 0) 4 + x else -6 + x, 0, cheek, 1.0f)
        for (x in -1..0) body.paintFront(x, 1, dark, 0.9f)
        body.paintFront(-2, 0, dark, 0.9f)
        body.paintFront(1, 0, dark, 0.9f)
        // Each tab is sliced from the same rounded block, so corners match the body.
        fun tab(index: Int, length: Int): VoxelMesh {
            val full = Sculpt()
            ghostShell(full, 0)
            val x0 = -7 + index * 2
            for (x in -8..8) for (y in -10..16) for (z in -7..7) {
                val keep = x >= x0 && x < x0 + 2 && y >= GHOST_SKIRT_Y - 2 && y < GHOST_SKIRT_Y + length
                if (!keep) full.clear(x, y, z)
            }
            // Re-center on the tab so it can be moved on its own.
            val centered = Sculpt()
            for (x in x0..x0 + 1) for (y in GHOST_SKIRT_Y - 2 until GHOST_SKIRT_Y + length) for (z in -6..5) {
                if (full.has(x, y, z)) centered.set(x - x0, y - GHOST_SKIRT_Y, z, 0, 1f - max(0, y - GHOST_SKIRT_Y) * 0.04f)
            }
            return centered.bake(jitter = 0.012f)
        }
        return GhostParts(body.bake(smooth = true, jitter = 0.012f), tab(0, 4), tab(1, 2), Eye(eye(2, 3, 0f), 3f, -2.5f, -6f))
    }

    // ---- bee ---------------------------------------------------------------------------------------

    class BeeParts(
        @JvmField val body: VoxelMesh,
        @JvmField val antenna: VoxelMesh,
        @JvmField val stinger: VoxelMesh,
        @JvmField val leg: VoxelMesh,
        @JvmField val wing: VoxelMesh,
        @JvmField val eye: Eye,
    )

    val BEE: BeeParts by lazy { bee() }

    private fun bee(): BeeParts {
        val body = Sculpt()
        body.ellipsoid(0f, 0f, 0f, 5.5f, 5f, 7.5f, 0, 1f, 3f)
        body.paint { _, _, z -> if (z >= 6 || (z >= -4 && Math.floorMod(z + 4, 4) < 2)) 1 else -1 }
        body.tone { _, y, _ -> -y * 0.012f }
        val eyeZ = body.frontZ(3, -1) ?: -7
        val antenna = Sculpt()
        antenna.capsule(0f, 0f, 0f, 1.8f, -4f, -3f, 0.55f, 0.45f, 1, 0.95f)
        antenna.ellipsoid(1.8f, -4.3f, -3.3f, 0.95f, 0.95f, 0.95f, 1, 1f)
        val stinger = Sculpt()
        stinger.capsule(0f, 0f, 0f, 0f, 0.5f, 5f, 1.4f, 0.1f, stinger.fixed(0x3A2B25), 0.95f)
        val leg = Sculpt()
        leg.capsule(0f, 0f, 0f, 0.5f, 4f, 0.3f, 0.65f, 0.5f, 1, 0.95f)
        return BeeParts(
            body.bake(smooth = true), antenna.bake(), stinger.bake(), leg.bake(), beeWing(), Eye(eye(2, 3, 0f, 0x14151F), 3f, -1f, eyeZ.toFloat()),
        )
    }

    /** One bee wing, left, origin at its root: a flat see-through ellipse with a brighter rim and vein. */
    private fun beeWing(): VoxelMesh {
        val s = Sculpt()
        val wing = s.fixed(0xE4F1FA)
        val cells = HashSet<Long>()
        for (x in 0..13) for (z in -3..5) {
            val dx = (x + 0.5f - 6.5f) / 6.5f
            val dz = (z + 0.5f - 1f) / 3.6f
            val d = abs(dx).pow(2.4f) + abs(dz).pow(2.4f)
            if (d <= 1f) cells.add(x.toLong() * 100 + z + 50)
        }
        for (k in cells) {
            val x = (k / 100).toInt()
            val z = (k % 100).toInt() - 50
            fun has(a: Int, b: Int) = cells.contains(a.toLong() * 100 + b + 50)
            val rim = !has(x - 1, z) || !has(x + 1, z) || !has(x, z - 1) || !has(x, z + 1)
            val vein = z == 1 && x in 1..9
            s.setFlat(x, 0, z, wing, if (rim) 1.12f else if (vein) 1.0f else 0.86f)
        }
        return s.bake()
    }

    // ---- slime -------------------------------------------------------------------------------------

    class SlimeParts(
        @JvmField val outer: VoxelMesh,
        @JvmField val frame: VoxelMesh,
        @JvmField val core: VoxelMesh,
        @JvmField val eye: Eye,
    )

    val SLIME: SlimeParts by lazy { slime() }

    private fun slime(): SlimeParts {
        val outer = Sculpt()
        outer.rbox(-7f, -7f, -7f, 7f, 7f, 7f, 1.6f, 0, 1f)
        // The twelve edges, a touch brighter, like the opaque border of the vanilla slime shell.
        val frame = Sculpt()
        for (x in -7..6) for (y in -7..6) for (z in -7..6) {
            val edge = (abs(x + 0.5f) > 6f).toInt() + (abs(y + 0.5f) > 6f).toInt() + (abs(z + 0.5f) > 6f).toInt()
            if (edge >= 2 && outer.has(x, y, z)) frame.set(x, y, z, 0, 1.08f)
        }
        val core = Sculpt()
        core.rbox(-4f, -4f, -4f, 4f, 4f, 4f, 1.2f, 1, 0.92f)
        core.tone { x, y, z -> (if (z < -1) 0.04f else 0f) - y * 0.01f + (if (x < -2) -0.03f else 0f) }
        val dark = core.fixed(DARK)
        for (x in -1..0) core.paintFront(x, 1, dark, 0.9f)
        core.paintFront(-2, 0, dark, 0.9f)
        core.paintFront(1, 0, dark, 0.9f)
        return SlimeParts(outer.bake(), frame.bake(jitter = 0f), core.bake(smooth = true), Eye(eye(2, 2, 0f), 2f, -1.5f, -4f))
    }

    // ---- axolotl -----------------------------------------------------------------------------------

    class AxolotlParts(
        @JvmField val head: VoxelMesh,
        @JvmField val body: VoxelMesh,
        @JvmField val tail: List<VoxelMesh>,
        @JvmField val gill: VoxelMesh,
        @JvmField val leg: VoxelMesh,
        @JvmField val eye: Eye,
    )

    const val AXOLOTL_BODY = 9f
    const val AXOLOTL_TAIL = 5f

    val AXOLOTL: AxolotlParts by lazy { axolotl() }

    private fun axolotl(): AxolotlParts {
        val head = Sculpt()
        head.rbox(-5.5f, -3.5f, -8.5f, 5.5f, 2.5f, 0.5f, 2.2f, 0, 1f)
        head.tone { _, y, _ -> if (y > 0) 0.06f else 0f }
        val dark = head.fixed(DARK)
        for (x in -2..1) head.paintFront(x, 1, dark, 0.9f)
        head.paintFront(-3, 0, dark, 0.9f)
        head.paintFront(2, 0, dark, 0.9f)
        val eyeZ = head.frontZ(4, -2) ?: -8
        val body = Sculpt()
        body.rbox(-4f, -3f, 0f, 4f, 3f, AXOLOTL_BODY, 2.2f, 0, 1f)
        body.tone { _, y, _ -> if (y > 1) 0.06f else 0f }
        for (z in 1..(AXOLOTL_BODY.toInt() - 1)) {
            val h = if (z < 3) 2 else 3
            for (y in -3 - h..-4) body.set(0, y, z, 1, 0.92f)
            body.set(-1, -4, z, 1, 0.9f)
        }
        val tails = ArrayList<VoxelMesh>()
        val widths = floatArrayOf(3f, 2.2f, 1.4f)
        val tops = floatArrayOf(2.5f, 2f, 1.5f)
        val bottoms = floatArrayOf(3f, 2.5f, 2f)
        for (i in 0 until 3) {
            val t = Sculpt()
            val len = AXOLOTL_TAIL + 0.5f
            t.rbox(-widths[i], -tops[i], 0f, widths[i], bottoms[i], len, 1.5f, 0, 1f - i * 0.02f)
            val fin = (3 - i)
            for (z in 0 until len.toInt()) {
                for (y in -(tops[i].toInt() + fin)..-tops[i].toInt() - 1) t.set(0, y, z, 1, 0.92f)
                if (i == 2 && z >= len.toInt() - 2) for (y in -2..2) t.set(0, y, z + 1, 1, 0.9f)
            }
            if (i == 2) for (y in -3..3) t.set(0, y, len.toInt(), 1, 0.9f)
            tails.add(t.bake(smooth = true))
        }
        val gill = Sculpt()
        gill.capsule(0f, 0f, 0f, 6f, 0f, 0f, 0.9f, 0.55f, 1, 0.95f)
        for (i in 0 until 3) {
            val x = 2f + i * 1.6f
            for (side in intArrayOf(1, -1)) gill.capsule(x, 0f, 0f, x + 1.4f, side * (2.4f - i * 0.4f), 0.2f, 0.45f, 0.3f, 1, 1.0f)
        }
        val leg = Sculpt()
        leg.capsule(0f, 0f, 0f, 2f, 2f, 0.3f, 1.3f, 1.0f, 0, 1f)
        leg.rbox(1.5f, 1.6f, -1.5f, 4f, 2.8f, 2f, 0.5f, 0, 0.96f)
        for (z in floatArrayOf(-1f, 0.5f, 2f)) leg.capsule(3.6f, 2.2f, z, 4.6f, 2.4f, z, 0.4f, 0.3f, 1, 0.9f)
        return AxolotlParts(head.bake(smooth = true), body.bake(smooth = true), tails, gill.bake(), leg.bake(), Eye(eye(2, 2, 0f), 4f, -2f, eyeZ.toFloat()))
    }

    // ---- owl ---------------------------------------------------------------------------------------

    class OwlParts(
        @JvmField val head: VoxelMesh,
        @JvmField val body: VoxelMesh,
        @JvmField val wing: VoxelMesh,
        @JvmField val tail: VoxelMesh,
        @JvmField val foot: VoxelMesh,
        @JvmField val beak: VoxelMesh,
        @JvmField val eye: Eye,
        @JvmField val beakZ: Float,
    )

    val OWL: OwlParts by lazy { owl() }

    private fun owl(): OwlParts {
        val head = Sculpt()
        head.rbox(-5.5f, -8f, -4.5f, 5.5f, 0.5f, 4.5f, 2.6f, 0, 1f)
        head.tone { x, y, z ->
            var t = 0f
            if (z < -2) for (side in intArrayOf(1, -1)) {
                val dx = x + 0.5f - side * 3f
                val dy = y + 0.5f + 4f
                if (dx * dx + dy * dy < 3.6f * 3.6f) t = 0.13f
            }
            t - (if (y > -2 && z > 0) 0.03f else 0f)
        }
        for (side in intArrayOf(1, -1)) {
            head.capsule(side * 3.8f, -7.2f, -0.6f, side * 5.3f, -11f, 0.4f, 1.25f, 0.3f, 1, 0.95f)
        }
        val eyeZ = head.frontZ(3, -4) ?: -5
        val beakZ = (head.frontZ(0, -3) ?: -5).toFloat()
        val body = Sculpt()
        body.rbox(-5f, -1f, -3.8f, 5f, 10f, 4.8f, 2.8f, 0, 0.95f)
        body.tone { x, y, z ->
            var t = if (z < -1) 0.12f else 0f
            if (z < -1 && y % 3 == 0 && Math.floorMod(x + (y / 3) * 2, 4) == 0) t -= 0.14f
            if (z > 1 && y < 5) t -= 0.05f
            t
        }
        val wing = Sculpt()
        for (y in 0..9) {
            val zEnd = floor(4f - 0.3f * y).toInt()
            for (z in -3..zEnd) for (x in 0..2) {
                val tip = y >= 7
                val stripe = (z + y) % 4 == 0 && !tip
                wing.set(x, y, z, if (tip) 1 else 0, (if (stripe) 0.8f else 0.9f) - x * 0.03f)
            }
        }
        val tail = Sculpt()
        for (y in 0..5) for (x in -3..2) {
            if (abs(x + 0.5f) > 3.2f - y * 0.4f) continue
            for (z in 0..1) tail.set(x, y, z, 1, 0.95f - y * 0.03f)
        }
        val foot = Sculpt()
        val orange = foot.fixed(0xF0A53A)
        foot.capsule(0f, 0f, 0f, 0f, 1.6f, 0f, 0.7f, 0.7f, orange, 1f)
        for (x in floatArrayOf(-0.9f, 0f, 0.9f)) foot.capsule(0f, 1.6f, 0f, x, 2.6f, -2f, 0.45f, 0.3f, orange, 1f)
        val beak = Sculpt()
        val bc = beak.fixed(0xF0A53A)
        beak.box(-1, 0, 0, 1, 2, 1, bc, 1f)
        beak.box(-1, 0, -1, 1, 1, 0, bc, 1.05f)
        beak.set(0, 1, -1, bc, 0.9f)
        return OwlParts(
            head.bake(smooth = true), body.bake(smooth = true), wing.bake(), tail.bake(), foot.bake(), beak.bake(),
            Eye(owlEye(), 3f, -4f, eyeZ.toFloat()), beakZ,
        )
    }

    private fun owlEye(): VoxelMesh {
        val s = Sculpt()
        val yellow = s.fixed(0xFFC21A)
        val dark = s.fixed(DARK)
        for (x in -3..2) for (y in -3..2) {
            val dx = x + 0.5f
            val dy = y + 0.5f
            val r = sqrt(dx * dx + dy * dy)
            if (r > 2.7f) continue
            s.set(x, y, -1, if (r < 1.3f) dark else yellow, if (r < 1.3f) 0.95f else 1.0f - r * 0.05f)
        }
        s.set(-1, -1, -1, s.fixed(WHITE), 1.0f)
        return s.bake(jitter = 0f)
    }

    // ---- shared pieces -----------------------------------------------------------------------------

    /** A face slab (dark with a white glint at its upper corner), [w] x [h] voxels, centered, one voxel deep (z -1..0). */
    private fun eye(w: Int, h: Int, @Suppress("UNUSED_PARAMETER") unused: Float, rgb: Int = DARK): VoxelMesh {
        val s = Sculpt()
        val dark = s.fixed(rgb)
        val white = s.fixed(WHITE)
        for (x in 0 until w) for (y in 0 until h) s.set(x - w / 2, y - h / 2, -1, dark, 1f)
        if (w >= 2 && h >= 2) s.set(-w / 2, -h / 2, -1, white, 1.0f)
        return s.bake(jitter = 0f)
    }

    // ---- auras -------------------------------------------------------------------------------------

    /** The storm cloud, centered, about 26 x 10 x 16 voxels; the underside (channel 1) is darker. */
    val CLOUD: VoxelMesh by lazy {
        val s = Sculpt()
        s.ellipsoid(0f, 0.5f, 0f, 12f, 5f, 8f, 0, 1f, 2.4f)
        s.ellipsoid(-8f, -1.5f, 1f, 5f, 3.5f, 4.5f, 0, 1f)
        s.ellipsoid(8f, -1f, -1f, 5.5f, 3.5f, 4.5f, 0, 1f)
        s.ellipsoid(-3f, -3f, 0f, 6f, 3.5f, 5f, 0, 1.02f)
        s.ellipsoid(4f, -3.5f, 1f, 5.5f, 3.5f, 4.5f, 0, 1.02f)
        s.ellipsoid(0f, -2.5f, -3f, 5f, 3f, 3.5f, 0, 1.02f)
        s.paint { _, y, _ -> if (y >= 1) 1 else -1 }
        s.tone { _, y, _ -> -y * 0.025f }
        s.bake(smooth = true, jitter = 0.04f)
    }

    /** A small drifting wisp of cloud. */
    val CLOUD_PUFF: VoxelMesh by lazy {
        val s = Sculpt()
        s.ellipsoid(0f, 0f, 0f, 4f, 2.8f, 3.4f, 0, 1f)
        s.paint { _, y, _ -> if (y >= 1) 1 else -1 }
        s.bake(smooth = true)
    }

    /** A raindrop: half a pixel wide, one pixel tall. */
    val RAIN: VoxelMesh by lazy {
        val s = Sculpt()
        val blue = s.fixed(0x4DA3FF)
        s.box(0, 0, 0, 1, 2, 1, blue, 1f)
        s.bake(jitter = 0f)
    }

    /** Three lightning bolts, zigzagging down from the cloud (voxels, y down from the origin). */
    val BOLTS: List<VoxelMesh> by lazy {
        val shapes = listOf(
            floatArrayOf(0f, 0f, -2f, 5f, 1.5f, 9f, -3f, 15f, 0f, 21f, -4f, 29f),
            floatArrayOf(0f, 0f, 2.5f, 6f, -1f, 11f, 2f, 17f, -2f, 24f, 1f, 30f),
            floatArrayOf(0f, 0f, -3f, 4f, 0f, 9f, -4f, 14f, -1f, 20f, -5f, 26f),
        )
        shapes.map { pts ->
            val s = Sculpt()
            val yellow = s.fixed(0xFFE24A)
            val white = s.fixed(0xFFF8C8)
            var i = 0
            while (i + 3 < pts.size) {
                s.capsule(pts[i], pts[i + 1], 0f, pts[i + 2], pts[i + 3], 0f, 1.1f, 1.0f, yellow, 1f)
                s.capsule(pts[i], pts[i + 1], 0f, pts[i + 2], pts[i + 3], 0f, 0.5f, 0.5f, white, 1.15f)
                i += 2
            }
            s.bake(jitter = 0f)
        }
    }

    /** Flame tongues of different shapes: (half width, height) in voxels, thin plates that stand upright. */
    val FLAMES: List<VoxelMesh> by lazy {
        listOf(floatArrayOf(4f, 13f, 0f), floatArrayOf(3.5f, 17f, 1.2f), floatArrayOf(3f, 10f, -1f)).map { flame(it[0], it[1], it[2]) }
    }

    private fun flame(half: Float, height: Float, lean: Float): VoxelMesh {
        val s = Sculpt()
        val w = half.toInt()
        for (x in -w until w) {
            val xc = x + 0.5f
            val edge = abs(xc) / half
            val sway = lean * (1f - edge)
            val h = height * (1f - edge.pow(1.7f)) * (0.88f + 0.12f * cos(xc * 1.3f)) + sway
            val top = floor(h).toInt()
            for (k in 0 until top) {
                val r = k / max(1f, h)
                val core = edge < 0.55f && r < 0.5f
                val ch = if (core || r < 0.18f && edge < 0.8f) 1 else 0
                val shade = if (ch == 1) 1.1f else if (r < 0.6f) 1.05f else 0.85f
                for (z in 0..1) s.setFlat(x, -k - 1, z, ch, shade - (if (z == 1) 0.04f else 0f))
            }
        }
        return s.bake(jitter = 0f)
    }

    /** A pixel heart, 7 x 6 voxels and three deep with rounded sides (so about half as thick as wide). */
    val HEART: VoxelMesh by lazy {
        val rows = arrayOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...")
        val s = Sculpt()
        fun filled(x: Int, y: Int) = y in rows.indices && x in 0..6 && rows[y][x] == 'X'
        for (y in rows.indices) for (x in 0..6) {
            if (!filled(x, y)) continue
            val inner = filled(x - 1, y) && filled(x + 1, y) && filled(x, y - 1) && filled(x, y + 1)
            s.set(x - 3, y - 3, 0, 0, 1f)
            if (inner) {
                s.set(x - 3, y - 3, -1, 0, 1.04f)
                s.set(x - 3, y - 3, 1, 0, 0.96f)
            }
        }
        s.tone { x, y, _ -> if (x < -1 && y < -2) 0.12f else 0f }
        s.bake(smooth = true, jitter = 0.02f)
    }

    // ---- geometry helpers --------------------------------------------------------------------------

    private fun insidePolygon(polygon: Array<FloatArray>, x: Float, y: Float): Boolean {
        var result = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i][0]
            val yi = polygon[i][1]
            val xj = polygon[j][0]
            val yj = polygon[j][1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result
            j = i
        }
        return result
    }

    /** Position of the point's projection along the segment, 0 at [a] to 1 at [b]. */
    private fun projectT(a: FloatArray, b: FloatArray, x: Float, y: Float): Float {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        return ((x - a[0]) * dx + (y - a[1]) * dy) / (dx * dx + dy * dy)
    }

    /** Distance from the line through [a] and [b], positive on the side where ([sx], [sy]) lies. */
    private fun signedDistance(a: FloatArray, b: FloatArray, x: Float, y: Float, sx: Float, sy: Float): Float {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val len = sqrt(dx * dx + dy * dy)
        val d = ((x - a[0]) * dy - (y - a[1]) * dx) / len
        val ref = ((sx - a[0]) * dy - (sy - a[1]) * dx) / len
        return if (ref >= 0f) d else -d
    }

    private fun Boolean.toInt(): Int = if (this) 1 else 0

    @Suppress("unused")
    private val UNUSED = min(0, 0)
}
