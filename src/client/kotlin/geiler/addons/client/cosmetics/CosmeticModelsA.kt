package geiler.addons.client.cosmetics

import geiler.addons.client.cosmetics.VoxelKit.along
import geiler.addons.client.cosmetics.VoxelKit.capsule
import geiler.addons.client.cosmetics.VoxelKit.edgeDistance
import geiler.addons.client.cosmetics.VoxelKit.hash01
import geiler.addons.client.cosmetics.VoxelKit.hypot
import geiler.addons.client.cosmetics.VoxelKit.inside
import geiler.addons.client.cosmetics.VoxelKit.lerp
import geiler.addons.client.cosmetics.VoxelKit.p
import geiler.addons.client.cosmetics.VoxelKit.q
import geiler.addons.client.cosmetics.VoxelKit.tube2d
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Wing models of the second cosmetics batch: dragon, fairy and phoenix (the mechanical and crystal wings, the
 * tails and the unicorn horn are in [CosmeticModelsA2]). Half-pixel voxels, built once and cached. Like the
 * older wings, every model describes the player's LEFT side in its own frame (x outward, y down, z backward)
 * and is split into a few rigid meshes that [CosmeticRigA] moves against each other. Pivots are in pixels.
 */
object CosmeticModelsA {
    private const val FINE = 0.5f
    private const val GOLD = 0xF2B93B

    // ---- dragon wing --------------------------------------------------------------------------------------

    /** Dragon wing: arm (index 0) then the leading finger and four fingers, each with the membrane beside it. */
    val DRAGON_WING: Array<VoxelMesh> by lazy { dragonWing().map { it.mesh(FINE) }.toTypedArray() }

    const val DRAGON_ROOT_Y = 8f
    const val DRAGON_WRIST_X = 6f
    const val DRAGON_WRIST_Y = 1.5f

    private val DW = floatArrayOf(12f, 3f)
    private val DR_ROOT = floatArrayOf(0f, 16f)
    private val DR_TIPS = arrayOf(
        floatArrayOf(43f, 19f), floatArrayOf(40f, 34f), floatArrayOf(31f, 45f), floatArrayOf(19f, 50f), floatArrayOf(7f, 46f),
    )
    private val DR_LEAD = listOf(DW, floatArrayOf(24f, 4f), floatArrayOf(35f, 9f), DR_TIPS[0])

    private fun dragonBow(x: Float, y: Float): Float = 7.5f * (max(0f, x) / 43f).pow(1.4f) + 1.5f * (y / 50f)

    private fun leadY(x: Float): Float {
        for (i in 1 until DR_LEAD.size) {
            val a = DR_LEAD[i - 1]
            val b = DR_LEAD[i]
            if (x <= b[0]) return lerp(a[1], b[1], ((x - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f))
        }
        return DR_LEAD.last()[1]
    }

    private fun dragonWing(): Array<VoxelGrid> {
        val grids = Array(6) { VoxelGrid() }
        val gold = IntArray(6) { grids[it].fixed(GOLD) }
        val chain = DR_TIPS.toList() + listOf(DR_ROOT)
        val psi = FloatArray(6) { atan2(chain[it][1] - DW[1], chain[it][0] - DW[0]) }
        val depths = floatArrayOf(5f, 6f, 6f, 5f, 1.5f)
        val boneLines = ArrayList<FloatArray>()
        boneLines.add(floatArrayOf(DR_ROOT[0], DR_ROOT[1], DW[0], DW[1]))
        for (i in 1 until DR_LEAD.size) boneLines.add(floatArrayOf(DR_LEAD[i - 1][0], DR_LEAD[i - 1][1], DR_LEAD[i][0], DR_LEAD[i][1]))
        for (k in 1..4) boneLines.add(floatArrayOf(DW[0], DW[1], DR_TIPS[k][0], DR_TIPS[k][1]))
        // part index of a voxel from its angle at the wrist: 1 = leading finger, 2..5 = fingers 1..4, 0 = arm.
        val band = { ang: Float -> var n = 0; for (i in 0 until 5) if (ang > psi[i]) n++; n }
        val partOf = { b: Int -> if (b == 5) 0 else b + 1 }

        for (v in -4..56) for (u in -2..46) {
            val x = u + 0.5f
            val y = v + 0.5f
            val dx = x - DW[0]
            val dy = y - DW[1]
            val ang = atan2(dy, dx)
            var edge = 0f
            val inMembrane: Boolean
            if (ang < psi[0]) {
                inMembrane = x >= DW[0] && x <= DR_TIPS[0][0] && y >= leadY(x)
                edge = y - leadY(x)
            } else if (ang <= psi[5]) {
                var i = 0
                while (i < 4 && ang > psi[i + 1]) i++
                val a = chain[i]
                val b = chain[i + 1]
                val ex = b[0] - a[0]
                val ey = b[1] - a[1]
                val dirX = cos(ang)
                val dirY = sin(ang)
                val cross = dirX * ey - dirY * ex
                val lambda = if (cross == 0f) 0f else ((a[0] - DW[0]) * ey - (a[1] - DW[1]) * ex) / cross
                val t = ((ang - psi[i]) / (psi[i + 1] - psi[i])).coerceIn(0f, 1f)
                val rEdge = lambda - depths[i] * sin(PI.toFloat() * t)
                val r = hypot(dx, dy)
                inMembrane = r <= rEdge
                edge = rEdge - r
            } else {
                inMembrane = false
            }
            if (!inMembrane) continue
            var nearest = Float.MAX_VALUE
            for (line in boneLines) nearest = min(nearest, distanceToSegment(x, y, line[0], line[1], line[2], line[3]))
            val sag = 3f * sin(min(1f, nearest / 7f) * PI.toFloat() / 2f)
            val z = (dragonBow(x, y) + sag).roundToInt()
            var tone = 1.04f - (y / 50f) * 0.2f + (if (nearest < 3.5f) 0.05f else 0f) - sag * 0.015f +
                CosmeticModels.jitter(u, v, 3, 0.02f)
            if (edge < 1.4f) tone -= 0.13f
            val shade = q(tone)
            val b = band(ang)
            grids[partOf(b)].set(u, v, z, 1, shade)
            // A rim of the band below overlaps under the next one so folding the fingers never opens a gap.
            if (b < 5 && ang > psi[b] - 0.105f) grids[partOf(b + 1)].setIfEmpty(u, v, z + 2, 1, shade)
        }

        val boneShade = { _: Float, d: Float -> 1.08f - 0.18f * d * d }
        val zBone = { x: Float, y: Float -> dragonBow(x, y) + 0.5f }
        // arm
        tube2d(grids[0], listOf(DR_ROOT, DW), 2.5f, 2.1f, 0, zBone, boneShade)
        capsule(grids[0], p(DW[0], DW[1], dragonBow(DW[0], DW[1]) + 0.5f), p(DW[0], DW[1], dragonBow(DW[0], DW[1]) + 0.5f),
            3.1f, 3.1f, 0, { _, d -> 1.08f - 0.2f * d * d })
        // leading finger and fingers 1..4
        tube2d(grids[1], DR_LEAD, 2.1f, 1.0f, 0, zBone, boneShade)
        for (k in 1..4) {
            tube2d(grids[k + 1], listOf(DW, DR_TIPS[k]), 1.8f, 0.8f, 0, zBone, boneShade)
            val j = along(listOf(DW, DR_TIPS[k]), 0.55f)
            capsule(grids[k + 1], p(j[0], j[1], zBone(j[0], j[1])), p(j[0], j[1], zBone(j[0], j[1])), 1.9f, 1.9f, 0,
                { _, d -> 1.06f - 0.2f * d * d })
        }
        // spikes along the leading edge and the arm (second color), leaning toward the tip
        val dir = FloatArray(2)
        for (s in floatArrayOf(0.17f, 0.42f, 0.64f, 0.86f)) {
            val at = along(DR_LEAD, s, dir)
            spike(grids[1], at[0] + dir[1] * 1.2f, at[1] - dir[0] * 1.2f, at[0] + dir[1] * 6.5f + dir[0] * 3f,
                at[1] - dir[0] * 6.5f + dir[1] * 3f, 1.7f)
        }
        val arm = listOf(DR_ROOT, DW)
        for (s in floatArrayOf(0.34f, 0.7f)) {
            val at = along(arm, s, dir)
            spike(grids[0], at[0] + dir[1] * 1.4f, at[1] - dir[0] * 1.4f, at[0] + dir[1] * 5.5f + dir[0] * 2f,
                at[1] - dir[0] * 5.5f + dir[1] * 2f, 1.6f)
        }
        // gold claw on the wrist and on every fingertip
        val clawShade = { t: Float, d: Float -> 1.1f - 0.25f * d - 0.1f * t }
        val wz = zBone(DW[0], DW[1])
        capsule(grids[0], p(DW[0], DW[1] - 1f, wz), p(DW[0] - 0.5f, DW[1] - 6f, wz), 1.7f, 1.1f, gold[0], clawShade)
        capsule(grids[0], p(DW[0] - 0.5f, DW[1] - 6f, wz), p(DW[0] - 3.5f, DW[1] - 10.5f, wz), 1.1f, 0.2f, gold[0], clawShade)
        for (k in 0..4) {
            val tip = DR_TIPS[k]
            val from = if (k == 0) DR_LEAD[2] else DW
            val dx = tip[0] - from[0]
            val dy = tip[1] - from[1]
            val len = hypot(dx, dy)
            val ux = dx / len
            val uy = dy / len
            val z = zBone(tip[0], tip[1])
            val grid = grids[k + 1]
            capsule(grid, p(tip[0] - ux, tip[1] - uy, z), p(tip[0] + ux * 2.6f, tip[1] + uy * 2.6f, z), 1.3f, 0.8f, gold[k + 1], clawShade)
            capsule(grid, p(tip[0] + ux * 2.6f, tip[1] + uy * 2.6f, z),
                p(tip[0] + ux * 4.6f - uy * 1.2f, tip[1] + uy * 4.6f + ux * 1.2f, z), 0.8f, 0.15f, gold[k + 1], clawShade)
        }
        return grids
    }

    /** A black (second color) thorn from ([bx], [by]) to ([tx], [ty]) on the wing's surface. */
    private fun spike(grid: VoxelGrid, bx: Float, by: Float, tx: Float, ty: Float, radius: Float) {
        val zb = dragonBow(bx, by) + 0.5f
        val zt = dragonBow(tx, ty) + 0.5f
        capsule(grid, p(bx, by, zb), p(tx, ty, zt), radius, 0.1f, 1, { t, d -> 1.08f - 0.2f * d - 0.1f * t })
    }

    // ---- fairy wings ------------------------------------------------------------------------------------

    /** Fairy lobes: upper membrane, upper veins, lower membrane, lower veins (veins sit in front of the membrane). */
    val FAIRY_WING: Array<VoxelMesh> by lazy {
        val upper = fairyLobe(FAIRY_UPPER, FAIRY_UPPER_TRUNK, 5)
        val lower = fairyLobe(FAIRY_LOWER, FAIRY_LOWER_TRUNK, 3)
        arrayOf(upper[0].mesh(FINE), upper[1].mesh(FINE), lower[0].mesh(FINE), lower[1].mesh(FINE))
    }

    const val FAIRY_ROOT_Y = 15.5f

    /** Sparkle spawn points (pixels, wing frame): the upper lobe tip and the lower lobe tip. */
    val FAIRY_TIPS: Array<FloatArray> = arrayOf(floatArrayOf(17.5f, -1f), floatArrayOf(14.5f, 22f))

    private val FAIRY_UPPER = listOf(
        floatArrayOf(0f, 30f), floatArrayOf(2f, 22f), floatArrayOf(7f, 11f), floatArrayOf(15f, 3f), floatArrayOf(25f, -1f),
        floatArrayOf(34f, -3f), floatArrayOf(36f, 3f), floatArrayOf(34f, 12f), floatArrayOf(28f, 20f), floatArrayOf(19f, 26f),
        floatArrayOf(9f, 30f),
    )
    private val FAIRY_LOWER = listOf(
        floatArrayOf(0f, 32f), floatArrayOf(10f, 29f), floatArrayOf(20f, 31f), floatArrayOf(27f, 37f), floatArrayOf(29f, 44f),
        floatArrayOf(24f, 49f), floatArrayOf(16f, 50f), floatArrayOf(8f, 46f), floatArrayOf(3f, 40f),
    )
    private val FAIRY_UPPER_TRUNK = listOf(floatArrayOf(1f, 29f), floatArrayOf(8f, 18f), floatArrayOf(18f, 7f), floatArrayOf(32f, -1f))
    private val FAIRY_LOWER_TRUNK = listOf(floatArrayOf(1f, 32f), floatArrayOf(10f, 36f), floatArrayOf(19f, 42f), floatArrayOf(27f, 46f))

    private fun fairyLobe(polygon: List<FloatArray>, trunk: List<FloatArray>, dots: Int): Array<VoxelGrid> {
        val membrane = VoxelGrid()
        val veins = VoxelGrid()
        val zOf = { x: Float, y: Float -> (3.5f * (hypot(x, y - 31f) / 48f).pow(1.4f)).roundToInt() }
        for (v in -6..54) for (u in -2..40) {
            val x = u + 0.5f
            val y = v + 0.5f
            if (!inside(polygon, x, y)) continue
            val edge = edgeDistance(polygon, x, y)
            val tone = 1.02f - hypot(x, y - 31f) / 48f * 0.1f + CosmeticModels.jitter(u, v, 5, 0.015f) - (if (edge < 1.3f) 0.2f else 0f)
            membrane.set(u, v, zOf(x, y), 1, q(tone))
        }
        val clip = { x: Float, y: Float -> inside(polygon, x, y) && edgeDistance(polygon, x, y) > 1.4f }
        val veinShade = { t: Float, d: Float -> 1.04f - 0.2f * d - 0.12f * t }
        val zFront = { x: Float, y: Float -> (zOf(x, y) - 1).toFloat() + 0.5f }
        val dir = FloatArray(2)
        fun vein(a: FloatArray, b: FloatArray, ra: Float, rb: Float) =
            capsule(veins, p(a[0], a[1], zFront(a[0], a[1])), p(b[0], b[1], zFront(b[0], b[1])), ra, rb, 0, veinShade, clip = clip)
        for (i in 1 until trunk.size) vein(trunk[i - 1], trunk[i], 1.1f, if (i == trunk.size - 1) 0.5f else 0.9f)
        val branchAt = floatArrayOf(0.22f, 0.38f, 0.54f, 0.7f)
        for ((n, s) in branchAt.withIndex()) {
            val at = along(trunk, s, dir)
            for (side in intArrayOf(1, -1)) {
                val a = 0.85f * side
                val bx = dir[0] * cos(a) - dir[1] * sin(a)
                val by = dir[0] * sin(a) + dir[1] * cos(a)
                val len = 13f - n * 2.2f
                vein(at, floatArrayOf(at[0] + bx * len, at[1] + by * len), 0.8f, 0.4f)
            }
        }
        for (i in 0 until dots) {
            val x = lerp(4f, 33f, hash01(i, polygon.size, 1))
            val y = lerp(-1f, 46f, hash01(i, polygon.size, 2))
            if (inside(polygon, x, y) && edgeDistance(polygon, x, y) > 3f) veins.set(floor(x).toInt(), floor(y).toInt(), zOf(x, y) - 1, 0, 0.3f)
        }
        return arrayOf(membrane, veins)
    }

    /** Four-point star for sparkles and embers (glowing, tinted by the rig). */
    val SPARK: VoxelMesh by lazy {
        val g = VoxelGrid()
        g.set(0, 0, 0, 0, 1.15f)
        for (d in intArrayOf(-1, 1)) {
            g.set(d, 0, 0, 0, 0.95f)
            g.set(0, d, 0, 0, 0.95f)
            g.set(0, 0, d, 0, 0.85f)
        }
        g.set(2, 0, 0, 0, 0.7f); g.set(-2, 0, 0, 0, 0.7f); g.set(0, 2, 0, 0, 0.7f); g.set(0, -2, 0, 0, 0.7f)
        g.mesh(0.25f)
    }

    // ---- phoenix wings ----------------------------------------------------------------------------------

    /** Phoenix wing: base (arm, secondaries, coverts), primaries, then three flame groups of the primaries and the secondaries' flames. */
    val PHOENIX_WING: Array<VoxelMesh> by lazy { phoenixWing().map { it.mesh(FINE) }.toTypedArray() }

    const val PHOENIX_ROOT_Y = 16f
    const val PHOENIX_WRIST_X = 10f
    const val PHOENIX_WRIST_Y = 6f

    /** Primary feather tips (pixels, wing frame) where embers leave. */
    val PHOENIX_TIPS: Array<FloatArray> by lazy {
        Array(PH_PRIMARIES) { i ->
            val root = along(PH_ARM, 0.3f + 0.08f * i)
            val a = 0.12f + 0.145f * i
            floatArrayOf((root[0] + sin(a) * (PH_LEN[i] + 3f)) / 2f, (root[1] + cos(a) * (PH_LEN[i] + 3f)) / 2f)
        }
    }

    private const val PH_PRIMARIES = 9
    private val PH_ARM = listOf(floatArrayOf(0f, 32f), floatArrayOf(9f, 20f), floatArrayOf(21f, 12f), floatArrayOf(33f, 9f), floatArrayOf(43f, 13f))
    private val PH_LEN = floatArrayOf(30f, 35f, 40f, 44f, 46f, 44f, 40f, 35f, 29f)

    private fun phoenixBow(u: Float, v: Float): Float = 5.5f * (max(0f, u) / 44f).pow(1.5f) + 1.2f * (v / 50f)

    private fun phoenixWing(): Array<VoxelGrid> {
        val base = VoxelGrid()
        val primaries = VoxelGrid()
        val flames = Array(3) { VoxelGrid() }
        val secondaryFlames = VoxelGrid()
        val dir = FloatArray(2)
        for (i in 0 until PH_PRIMARIES) {
            val root = along(PH_ARM, 0.3f + 0.08f * i)
            phoenixFeather(primaries, flames[i % 3], root, 0.12f + 0.145f * i, PH_LEN[i], 6f, 0, 3f + (i % 2) * 2f, true, i)
        }
        val secondaries = floatArrayOf(24f, 27f, 29f, 30f, 29f, 27f)
        for ((j, length) in secondaries.withIndex()) {
            val root = along(PH_ARM, 0.02f + 0.055f * j)
            phoenixFeather(base, secondaryFlames, root, -0.12f + 0.045f * j, length, 6f, 1, 3f + (j % 2) * 2f, true, 20 + j)
        }
        var s = 0.04f
        var n = 0
        while (s < 0.98f) {
            val root = along(PH_ARM, s, dir)
            phoenixFeather(base, base, root, 0.12f + s * 0.5f, if (n % 2 == 0) 13f else 10f, 5f, 2, 0f, false, 40 + n)
            s += 0.075f
            n++
        }
        tube2d(base, PH_ARM, 2.4f, 1.5f, 0, { x, y -> phoenixBow(x, y) + 3f }, { _, d -> 1.1f - 0.18f * d * d })
        return arrayOf(base, primaries, flames[0], flames[1], flames[2], secondaryFlames)
    }

    /**
     * One feather: [length] along [angle] (0 = straight down, positive = outward) from [root]. Deep red at the
     * root, bright in the middle, then second color toward the tip; the part past 66% goes to [flame] (drawn
     * glowing) when [split] is set. [extra] lengthens the flame tip so neighbors end at different heights.
     */
    private fun phoenixFeather(
        body: VoxelGrid, flame: VoxelGrid, root: FloatArray, angle: Float, length: Float, width: Float, layer: Int,
        extra: Float, pointed: Boolean, seed: Int,
    ) {
        val dx = sin(angle)
        val dy = cos(angle)
        val nx = -dy
        val ny = dx
        val full = length + extra
        val reach = full + width
        for (u in floor(root[0] - reach).toInt()..(root[0] + reach).toInt()) {
            for (v in floor(root[1] - 2).toInt()..(root[1] + reach).toInt()) {
                val px = u + 0.5f - root[0]
                val py = v + 0.5f - root[1]
                val t = px * dx + py * dy
                val s = px * nx + py * ny
                if (t < 0f || t > full) continue
                val tipZone = if (pointed) 7f else width
                val half = width / 2f * min(1f, max(0.2f, (full - t) / tipZone))
                if (s < -half || s > half) continue
                val tt = t / full
                val z = (phoenixBow(u.toFloat(), v.toFloat()) + layer).roundToInt()
                val trailing = s > half - 1.1f
                val shaft = s > -0.6f && s < 0.4f && t < full - 3f
                val ditherLimit = floatArrayOf(0.125f, 0.625f, 0.875f, 0.375f)[(u and 1) + (v and 1) * 2]
                val toTip = ((tt - 0.5f) / 0.16f).coerceIn(0f, 1f)
                var channel = if (toTip > ditherLimit) 1 else 0
                var shade = when {
                    tt < 0.22f -> 0.7f + 0.12f * tt / 0.22f
                    tt < 0.5f -> 0.84f + 0.33f * (tt - 0.22f) / 0.28f
                    else -> 1.08f
                }
                if (shaft) shade += 0.06f
                if (trailing) shade -= 0.1f
                shade += CosmeticModels.jitter(u, v, seed, 0.015f)
                val isFlame = tt >= 0.66f && flame !== body
                if (isFlame) {
                    channel = 1
                    shade = 1.0f + (1f - tt) * 0.5f + (if (shaft) 0.1f else 0f) - (if (trailing) 0.08f else 0f)
                    flame.set(u, v, z, channel, q(shade))
                } else {
                    body.set(u, v, z, channel, q(shade))
                }
            }
        }
    }
}
