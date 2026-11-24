package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voxel art for every cosmetic, drawn after the owner's reference sheets. Wings and horns use half-pixel
 * voxels ([FINE]) so curves and feathers read smoothly; ears, halo and tails use whole pixels like a skin.
 * Built once and cached. Local frames, in model pixels:
 * - wings and head pieces describe the player's LEFT side: x grows outward, y grows down, z grows backward;
 *   the right side is the same mesh mirrored.
 * - the halo is centered on the origin; a tail segment runs from y = 0 up to y = -[TAIL_SEGMENT_LENGTH].
 */
object CosmeticModels {
    const val TAIL_SEGMENT_LENGTH = 2
    const val TAIL_SEGMENTS = 9

    /** Half-pixel voxels for the detailed models. */
    private const val FINE = 0.5f

    /** Where each wing meets the back, in the wing's own frame (pixels). */
    const val ANGEL_ROOT_Y = 6f
    const val DEVIL_ROOT_Y = 7f

    val ANGEL_WING: VoxelMesh by lazy { angelWing().mesh(FINE) }
    val DEVIL_WING: VoxelMesh by lazy { devilWing().mesh(FINE) }
    val CAT_EAR: VoxelMesh by lazy { catEar().mesh() }
    val DEMON_HORN: VoxelMesh by lazy { demonHorn().mesh() }
    val CUTE_HORN: VoxelMesh by lazy { cuteHorn().mesh() }
    val RAM_HORN: VoxelMesh by lazy { ramHorn().mesh() }
    val HALO: VoxelMesh by lazy { halo().mesh() }

    /** One unit voxel (aura sparks and haze puffs, scaled by the rig). */
    val CUBE: VoxelMesh by lazy { VoxelGrid().apply { set(0, 0, 0, 0, 1f) }.mesh() }

    /** A fly for the stink aura: dark body (color2) with pale wings, in quarter-pixel voxels. */
    val FLY: VoxelMesh by lazy { fly().mesh(0.25f) }

    private val tails = java.util.EnumMap<CosmeticStyle, List<VoxelMesh>>(CosmeticStyle::class.java)

    @JvmStatic
    fun tail(style: CosmeticStyle): List<VoxelMesh> = synchronized(tails) {
        tails.getOrPut(style) { tailSegments(style).map { it.mesh() } }
    }

    /** Small deterministic shade step (-amount, 0 or +amount) so flat areas read as pixel art, not plastic. */
    @JvmStatic
    fun jitter(x: Int, y: Int, z: Int, amount: Float): Float {
        var h = x * 73856093 xor y * 19349663 xor z * 83492791
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return ((h and 0xFF) % 3 - 1) * amount
    }

    // ---- angel wing -----------------------------------------------------------------------------

    /**
     * Angel wing in half pixels (about 38 wide, 50 tall): a rounded arm along the top, two rows of short coverts,
     * secondaries hanging from the inner arm and long primaries fanning outward from the outer arm.
     * Each layer sits one voxel further back than the one under it and the whole wing bows backward
     * toward the tip, so it reads as a layered 3D wing rather than a flat plane.
     */
    private fun angelWing(): VoxelGrid {
        val grid = VoxelGrid()
        val arm = arrayOf(floatArrayOf(0f, 12f), floatArrayOf(9f, 6f), floatArrayOf(19f, 1.5f), floatArrayOf(28f, 0f), floatArrayOf(36f, 5f))
        val bow = { u: Float, v: Float -> 6f * (max(0f, u) / 36f).pow(1.5f) + 1.5f * (v / 46f) }
        // Primaries: long, fanning outward from straight down to ~35 degrees, longest in the middle.
        val primaries = floatArrayOf(34f, 39f, 43f, 45f, 44f, 40f, 34f)
        for ((i, length) in primaries.withIndex()) {
            val s = 0.48f + i * 0.085f
            val angle = 0.08f + i * 0.085f
            feather(grid, along(arm, s), angle, length, 5f, layer = 0, bow = bow, shade = 1.0f - i % 2 * 0.04f)
        }
        // Secondaries: from the inner arm, hanging straight down, slightly shorter toward the body.
        val secondaries = floatArrayOf(22f, 25f, 27f, 29f, 30f, 30f)
        for ((i, length) in secondaries.withIndex()) {
            val s = 0.03f + i * 0.085f
            feather(grid, along(arm, s), -0.06f + i * 0.02f, length, 5.5f, layer = 1, bow = bow, shade = 1.02f - i % 2 * 0.04f)
        }
        // Coverts: two rows of short rounded feathers under the arm.
        for (row in 0 until 2) {
            var s = 0.02f + row * 0.03f
            while (s < 0.98f) {
                feather(grid, along(arm, s), 0.1f, if (row == 0) 12f else 7f, 4.5f, layer = 2 + row, bow = bow,
                    shade = 1.06f - row * 0.03f, pointed = false)
                s += 0.075f
            }
        }
        // The arm: a rounded ridge along the top edge.
        tubeAlong2d(grid, arm, 2.4f, 1.6f, bow, zOffset = 3f, shade = 1.08f)
        return grid
    }

    /**
     * One feather: a strip from [root] at [angle] (0 = straight down, positive = outward), tapered to a
     * point (or rounded) at the end, one voxel thick. Its trailing edge is shaded in color2 and a faint
     * shaft runs down the middle.
     */
    private fun feather(
        grid: VoxelGrid, root: FloatArray, angle: Float, length: Float, width: Float, layer: Int,
        bow: (Float, Float) -> Float, shade: Float, pointed: Boolean = true,
    ) {
        val dx = sin(angle)
        val dy = cos(angle)
        val nx = -dy
        val ny = dx
        val reach = length + width
        for (u in floor(root[0] - reach).toInt()..(root[0] + reach).toInt()) {
            for (v in floor(root[1] - 2).toInt()..(root[1] + reach).toInt()) {
                val px = u + 0.5f - root[0]
                val py = v + 0.5f - root[1]
                val t = px * dx + py * dy
                val s = px * nx + py * ny
                if (t < 0f || t > length) continue
                val tipZone = if (pointed) 6f else width
                val half = width / 2f * min(1f, max(0.25f, (length - t) / tipZone))
                if (s < -half || s > half) continue
                val trailing = s > half - 1.1f
                val shaft = s > -0.6f && s < 0.4f && t < length - 3f
                val tone = when {
                    trailing -> 0.97f
                    shaft -> shade - 0.07f
                    else -> shade - (t / length) * 0.05f
                }
                val z = (bow(u.toFloat(), v.toFloat()) + layer).roundToInt()
                grid.set(u, v, z, if (trailing) 1 else 0, tone)
            }
        }
    }

    private fun tubeAlong2d(
        grid: VoxelGrid, points: Array<FloatArray>, startRadius: Float, endRadius: Float,
        bow: (Float, Float) -> Float, zOffset: Float, shade: Float,
    ) {
        val steps = 80
        for (i in 0..steps) {
            val s = i / steps.toFloat()
            val p = along(points, s)
            val r = startRadius + (endRadius - startRadius) * s
            grid.ball(p[0], p[1], bow(p[0], p[1]) + zOffset, r, 0, shade + jitter(i / 6, 0, 0, 0.02f))
        }
    }

    /** Point at fraction [s] of a polyline's length. */
    private fun along(points: Array<FloatArray>, s: Float): FloatArray {
        val lengths = FloatArray(points.size)
        for (i in 1 until points.size) {
            lengths[i] = lengths[i - 1] + hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
        }
        val target = s.coerceIn(0f, 1f) * lengths.last()
        for (i in 1 until points.size) {
            if (target <= lengths[i] || i == points.size - 1) {
                val segment = lengths[i] - lengths[i - 1]
                val t = if (segment == 0f) 0f else ((target - lengths[i - 1]) / segment).coerceIn(0f, 1f)
                return floatArrayOf(
                    points[i - 1][0] + (points[i][0] - points[i - 1][0]) * t,
                    points[i - 1][1] + (points[i][1] - points[i - 1][1]) * t,
                )
            }
        }
        return points.last()
    }

    // ---- devil wing -----------------------------------------------------------------------------

    /**
     * Devil (bat) wing in half pixels (38 wide, 44 tall). An arm bone rises from the back to the wrist, four
     * finger bones fan out from it, and the membrane between them sags backward like a sail (deepest
     * halfway between two bones), with a scalloped trailing edge. The whole wing bows backward toward the
     * tip and a claw points up from the wrist.
     */
    private fun devilWing(): VoxelGrid {
        val grid = VoxelGrid()
        val root = floatArrayOf(0f, 14f)
        val wrist = floatArrayOf(10f, 2f)
        val lead1 = floatArrayOf(21f, 4f)
        val lead2 = floatArrayOf(31f, 8f)
        val outer = floatArrayOf(38f, 15f)
        val tips = arrayOf(floatArrayOf(3f, 40f), floatArrayOf(14f, 44f), floatArrayOf(26f, 41f), floatArrayOf(36f, 33f))
        val bones = arrayOf(
            arrayOf(wrist, lead1), arrayOf(lead1, lead2), arrayOf(lead2, outer), arrayOf(outer, tips[3]),
            arrayOf(wrist, tips[0]), arrayOf(wrist, tips[1]), arrayOf(wrist, tips[2]),
        )
        val bow = { u: Float, v: Float -> 7f * (max(0f, u) / 38f).pow(1.4f) + 1.5f * (v / 44f) }
        val outline = arrayOf(wrist, lead1, lead2, outer, tips[3], tips[2], tips[1], tips[0])
        for (v in -2..45) for (u in 0..39) {
            val x = u + 0.5f
            val y = v + 0.5f
            if (!inside(outline, x, y) || belowScallop(tips, x, y)) continue
            var nearest = Float.MAX_VALUE
            for (bone in bones) nearest = min(nearest, distanceToSegment(x, y, bone[0][0], bone[0][1], bone[1][0], bone[1][1]))
            // The sail: sags back away from the bones, up to 3 voxels.
            val sag = 3f * min(1f, nearest / 7f).let { sin(it * PI.toFloat() / 2f) }
            val tone = 1.04f - (y / 44f) * 0.22f + (if (nearest < 3f) 0.04f else 0f)
            grid.set(u, v, (bow(x, y) + sag).roundToInt(), 1, (tone * 20f).roundToInt() / 20f)
        }
        // Bones: rounded, thick at the wrist and thin at the tips, sitting on the membrane.
        val bone = { a: FloatArray, b: FloatArray, ra: Float, rb: Float ->
            val steps = (hypot(b[0] - a[0], b[1] - a[1]) * 2f).toInt().coerceAtLeast(1)
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val x = a[0] + (b[0] - a[0]) * t
                val y = a[1] + (b[1] - a[1]) * t
                grid.ball(x, y, bow(x, y) + 0.5f, ra + (rb - ra) * t, 0, 1.0f)
            }
        }
        bone(root, wrist, 2.2f, 1.8f)
        bone(wrist, lead1, 1.8f, 1.5f)
        bone(lead1, lead2, 1.5f, 1.3f)
        bone(lead2, outer, 1.3f, 1.1f)
        bone(outer, tips[3], 1.1f, 0.7f)
        for (tip in tips.take(3)) bone(wrist, tip, 1.4f, 0.7f)
        // Knuckle and claw.
        grid.ball(wrist[0], wrist[1], bow(wrist[0], wrist[1]) + 0.5f, 2.6f, 0, 0.9f)
        bone(wrist, floatArrayOf(wrist[0] - 1.5f, wrist[1] - 5f), 1.2f, 0.4f)
        return grid
    }

    private fun belowScallop(tips: Array<FloatArray>, x: Float, y: Float): Boolean {
        for (i in 0 until tips.size - 1) {
            val a = tips[i]
            val b = tips[i + 1]
            if (x <= a[0] || x >= b[0]) continue
            val t = (x - a[0]) / (b[0] - a[0])
            val edge = a[1] + (b[1] - a[1]) * t - 6.5f * sin(PI.toFloat() * t)
            return y > edge
        }
        return false
    }

    // ---- head -----------------------------------------------------------------------------------

    /** Cat ear: a 4-wide triangle tipped outward, 2 deep, pink inside on the front face. */
    private fun catEar(): VoxelGrid {
        val grid = VoxelGrid()
        // Rows from the base upward: [x0, x1) per row.
        val rows = arrayOf(intArrayOf(0, 4), intArrayOf(0, 4), intArrayOf(1, 4), intArrayOf(2, 4))
        for ((index, span) in rows.withIndex()) {
            val y = -1 - index
            for (x in span[0] until span[1]) {
                grid.set(x, y, 0, 0, 1.0f + jitter(x, y, 4, 0.04f))
                val inner = index < 3 && x > span[0] && x < span[1] - 1
                grid.set(x, y, -1, if (inner) 1 else 0, if (inner) 1.0f else 0.95f)
            }
        }
        return grid
    }

    /**
     * Demon horn: rises from the head, curls forward, loops over the top and sweeps back to a point behind
     * the head. Ridged: darker rings (color2) every pixel and a half along its length.
     */
    private fun demonHorn(): Horn = horn(
        arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0.3f, -2.3f, -1.9f), floatArrayOf(0.8f, -4.6f, -2.0f),
            floatArrayOf(1.3f, -5.9f, -0.2f), floatArrayOf(1.9f, -5.4f, 2.4f), floatArrayOf(2.5f, -4.0f, 4.6f),
            floatArrayOf(3.1f, -2.8f, 6.6f),
        ),
        startRadius = 1.55f, endRadius = 0.2f, ridge = 2.0f,
    )

    /** Cute horn: a short rounded cone leaning back. */
    private fun cuteHorn(): Horn = horn(
        arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.15f, -1.4f, 0.3f), floatArrayOf(0.3f, -2.6f, 1.0f)),
        startRadius = 1.05f, endRadius = 0.3f, ridge = 0f,
    )

    /**
     * Ram (sheep) horn: leaves the top of the head going back, spirals down behind and below the ear and
     * comes forward to a tip beside the cheek, drifting outward so it clears the head.
     */
    private fun ramHorn(): Horn = horn(
        arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0.8f, -1.2f, 1.5f), floatArrayOf(1.6f, -1.0f, 3.4f),
            floatArrayOf(2.4f, 0.8f, 4.5f), floatArrayOf(3.0f, 3.0f, 3.8f), floatArrayOf(3.4f, 4.5f, 1.7f),
            floatArrayOf(3.6f, 3.9f, -0.4f), floatArrayOf(3.6f, 2.3f, -1.3f),
        ),
        startRadius = 1.6f, endRadius = 0.45f, ridge = 1.6f,
    )

    /** A voxelized tube plus its curve, so faces can be lit as a smooth round surface. */
    private class Horn(val grid: VoxelGrid, val curve: List<FloatArray>) {
        fun mesh(): VoxelMesh = grid.mesh(FINE) { x, y, z ->
            var best = curve[0]
            var bestDistance = Float.MAX_VALUE
            for (p in curve) {
                val d = (x - p[0] / FINE).pow(2) + (y - p[1] / FINE).pow(2) + (z - p[2] / FINE).pow(2)
                if (d < bestDistance) { bestDistance = d; best = p }
            }
            val nx = x - best[0] / FINE
            val ny = y - best[1] / FINE
            val nz = z - best[2] / FINE
            val length = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-4f } ?: 1f
            floatArrayOf(nx / length, ny / length, nz / length)
        }
    }

    /** A tapered tube along a smooth curve through [points] (pixels), in half-pixel voxels. */
    private fun horn(points: Array<FloatArray>, startRadius: Float, endRadius: Float, ridge: Float): Horn {
        val grid = VoxelGrid()
        val samples = ArrayList<FloatArray>()
        for (i in 0 until points.size - 1) {
            for (step in 0 until 12) samples.add(catmullRom(points, i, step / 12f))
        }
        samples.add(points.last())
        val travelled = FloatArray(samples.size)
        for (index in 1 until samples.size) {
            val p = samples[index]
            val q = samples[index - 1]
            travelled[index] = travelled[index - 1] + sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2))
        }
        // Each voxel takes its color from the nearest point on the curve, so the ridge rings stay clean bands.
        val nearest = HashMap<Long, FloatArray>() // voxel -> (distance / radius, sample index)
        for ((index, p) in samples.withIndex()) {
            val t = index / (samples.size - 1f)
            val radius = (startRadius + (endRadius - startRadius) * t) / FINE
            val cx = p[0] / FINE
            val cy = p[1] / FINE
            val cz = p[2] / FINE
            for (x in floor(cx - radius).toInt()..(cx + radius).toInt() + 1) {
                for (y in floor(cy - radius).toInt()..(cy + radius).toInt() + 1) {
                    for (z in floor(cz - radius).toInt()..(cz + radius).toInt() + 1) {
                        val d = sqrt((x + 0.5f - cx).pow(2) + (y + 0.5f - cy).pow(2) + (z + 0.5f - cz).pow(2)) / radius
                        if (d > 1f) continue
                        val key = (x.toLong() + 4096 shl 32) or ((y.toLong() + 4096) shl 16) or (z.toLong() + 4096)
                        val best = nearest[key]
                        if (best == null || d < best[0]) nearest[key] = floatArrayOf(d, index.toFloat())
                    }
                }
            }
        }
        for ((key, best) in nearest) {
            val x = (key shr 32).toInt() - 4096
            val y = ((key shr 16) and 0xFFFF).toInt() - 4096
            val z = (key and 0xFFFF).toInt() - 4096
            val index = best[1].toInt()
            val t = index / (samples.size - 1f)
            // Grooves at least a voxel wide; thinner bands alias into speckles.
            val groove = ridge > 0f && travelled[index] % ridge < 0.6f
            grid.set(x, y, z, if (groove) 1 else 0, 1.02f - t * 0.08f)
        }
        return Horn(grid, samples)
    }

    private fun catmullRom(points: Array<FloatArray>, segment: Int, t: Float): FloatArray {
        val p0 = points[max(0, segment - 1)]
        val p1 = points[segment]
        val p2 = points[segment + 1]
        val p3 = points[min(points.size - 1, segment + 2)]
        val t2 = t * t
        val t3 = t2 * t
        return FloatArray(3) { i ->
            0.5f * (2f * p1[i] + (-p0[i] + p2[i]) * t + (2f * p0[i] - 5f * p1[i] + 4f * p2[i] - p3[i]) * t2 +
                (-p0[i] + 3f * p1[i] - 3f * p2[i] + p3[i]) * t3)
        }
    }

    /** Halo: a 10-wide ring, one pixel thick, bright outer band (color) and darker inner rim (color2). */
    private fun halo(): VoxelGrid {
        val grid = VoxelGrid()
        for (x in -5 until 5) for (z in -5 until 5) {
            val cx = x + 0.5f
            val cz = z + 0.5f
            val distance = sqrt(cx * cx + cz * cz)
            if (distance > 5.2f || distance < 3.2f) continue
            val inner = distance < 4.0f
            grid.set(x, 0, z, if (inner) 1 else 0, 1.0f + jitter(x, 0, z, 0.05f))
        }
        return grid
    }

    private fun fly(): VoxelGrid {
        val grid = VoxelGrid()
        grid.box(-1, -1, -1, 2, 1, 2, 1, 0.9f)
        grid.set(0, -1, 2, 1, 0.8f)
        grid.box(-3, -2, 0, -1, -1, 1, 0, 1.0f)
        grid.box(2, -2, 0, 4, -1, 1, 0, 1.0f)
        return grid
    }

    // ---- tails ----------------------------------------------------------------------------------

    private fun tailSegments(style: CosmeticStyle): List<VoxelGrid> {
        val segments = ArrayList<VoxelGrid>(TAIL_SEGMENTS)
        for (index in 0 until TAIL_SEGMENTS) {
            val grid = VoxelGrid()
            val fromTip = TAIL_SEGMENTS - 1 - index
            when (style) {
                CosmeticStyle.FLUFFY_TAIL -> {
                    val width = if (index < 2) 3 else 4
                    val white = fromTip < 3
                    segment(grid, width, white, index)
                    // Tufts sticking out on alternating sides.
                    for (y in -TAIL_SEGMENT_LENGTH until 0) {
                        val side = if ((index + y) and 1 == 0) 1 else -1
                        val tuft = width / 2 * side + if (side > 0) 0 else -1
                        grid.set(tuft, y, (index % 3) - 1, if (white) 1 else 0, 0.9f)
                    }
                    if (fromTip == 0) grid.box(-2, -TAIL_SEGMENT_LENGTH - 2, -2, 2, -TAIL_SEGMENT_LENGTH, 2, 1, 1.05f)
                }
                CosmeticStyle.CURLED_TAIL -> {
                    segment(grid, 2, fromTip < 2, index)
                    if (fromTip == 0) grid.box(-2, -TAIL_SEGMENT_LENGTH - 2, -1, 2, -TAIL_SEGMENT_LENGTH + 1, 2, 1, 1.05f)
                }
                else -> {
                    segment(grid, 2, fromTip < 2, index)
                    if (fromTip == 0) grid.box(-1, -TAIL_SEGMENT_LENGTH - 1, -1, 1, -TAIL_SEGMENT_LENGTH, 1, 1, 1.08f)
                }
            }
            segments.add(grid)
        }
        return segments
    }

    private fun segment(grid: VoxelGrid, width: Int, white: Boolean, index: Int) {
        val half = width / 2
        val x0 = -half
        val x1 = width - half
        for (y in -TAIL_SEGMENT_LENGTH until 0) for (x in x0 until x1) for (z in x0 until x1) {
            grid.set(x, y, z, if (white) 1 else 0, 1.0f - (index % 2) * 0.05f + jitter(x, y + index * 2, z, 0.04f))
        }
    }

    // ---- raster helpers -------------------------------------------------------------------------

    private fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)

    private fun inside(polygon: Array<FloatArray>, x: Float, y: Float): Boolean {
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
}
