package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Small modelling helpers for the wing, tail and horn models in [CosmeticModelsA] and [CosmeticModelsATails].
 * Everything here works in grid (voxel) units, so a model built for `mesh(0.5f)` is in half pixels.
 */
internal object VoxelKit {
    /** Shades snap to 4% steps so neighboring voxels of one tone still merge into big quads. */
    fun q(shade: Float): Float = (shade * 25f).roundToInt() / 25f

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)

    fun p(x: Float, y: Float, z: Float = 0f): FloatArray = floatArrayOf(x, y, z)

    /** Small deterministic hash in [0, 1). */
    fun hash01(a: Int, b: Int = 0, c: Int = 0): Float {
        var h = a * 73856093 xor b * 19349663 xor c * 83492791
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0xFFFF) / 65536f
    }

    /**
     * A tapered round bar from [a] to [b] ([x, y, z] in voxel units), radius [ra] -> [rb]. [shade] gets the
     * position along the bar (0..1) and the distance from its axis as a fraction of the radius (0 = axis).
     * [clip], when set, limits the bar to the (x, y) places where it returns true.
     */
    fun capsule(
        grid: VoxelGrid, a: FloatArray, b: FloatArray, ra: Float, rb: Float, channel: Int,
        shade: (Float, Float) -> Float, onlyEmpty: Boolean = false, clip: ((Float, Float) -> Boolean)? = null,
    ) {
        val reach = max(ra, rb) + 1f
        val abx = b[0] - a[0]
        val aby = b[1] - a[1]
        val abz = b[2] - a[2]
        val length2 = abx * abx + aby * aby + abz * abz
        for (x in floor(min(a[0], b[0]) - reach).toInt()..ceil(max(a[0], b[0]) + reach).toInt()) {
            for (y in floor(min(a[1], b[1]) - reach).toInt()..ceil(max(a[1], b[1]) + reach).toInt()) {
                if (clip != null && !clip(x + 0.5f, y + 0.5f)) continue
                for (z in floor(min(a[2], b[2]) - reach).toInt()..ceil(max(a[2], b[2]) + reach).toInt()) {
                    val px = x + 0.5f - a[0]
                    val py = y + 0.5f - a[1]
                    val pz = z + 0.5f - a[2]
                    val t = if (length2 == 0f) 0f else ((px * abx + py * aby + pz * abz) / length2).coerceIn(0f, 1f)
                    val ex = px - abx * t
                    val ey = py - aby * t
                    val ez = pz - abz * t
                    val d = sqrt(ex * ex + ey * ey + ez * ez)
                    val r = ra + (rb - ra) * t
                    if (d > r) continue
                    val s = q(shade(t, d / r))
                    if (onlyEmpty) grid.setIfEmpty(x, y, z, channel, s) else grid.set(x, y, z, channel, s)
                }
            }
        }
    }

    /**
     * A bar along a 2D polyline lying on a surface: the depth at each point comes from [zOf]. The polyline is
     * walked in short steps so the bar follows a curved surface.
     */
    fun tube2d(
        grid: VoxelGrid, points: List<FloatArray>, ra: Float, rb: Float, channel: Int,
        zOf: (Float, Float) -> Float, shade: (Float, Float) -> Float, onlyEmpty: Boolean = false,
    ) {
        var total = 0f
        for (i in 1 until points.size) total += hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
        if (total <= 0f) return
        var walked = 0f
        for (i in 1 until points.size) {
            val ax = points[i - 1][0]
            val ay = points[i - 1][1]
            val bx = points[i][0]
            val by = points[i][1]
            val length = hypot(bx - ax, by - ay)
            val steps = max(1, ceil(length / 2f).toInt())
            for (s in 0 until steps) {
                val t0 = s / steps.toFloat()
                val t1 = (s + 1) / steps.toFloat()
                val x0 = lerp(ax, bx, t0)
                val y0 = lerp(ay, by, t0)
                val x1 = lerp(ax, bx, t1)
                val y1 = lerp(ay, by, t1)
                val f0 = (walked + length * t0) / total
                val f1 = (walked + length * t1) / total
                capsule(
                    grid, p(x0, y0, zOf(x0, y0)), p(x1, y1, zOf(x1, y1)), lerp(ra, rb, f0), lerp(ra, rb, f1), channel,
                    { t, d -> shade(lerp(f0, f1, t), d) }, onlyEmpty,
                )
            }
            walked += length
        }
    }

    /** Point at fraction [s] of a 2D polyline's length (also returns the direction in [out] 2..3 when given). */
    fun along(points: List<FloatArray>, s: Float, dir: FloatArray? = null): FloatArray {
        var total = 0f
        for (i in 1 until points.size) total += hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
        var target = s.coerceIn(0f, 1f) * total
        for (i in 1 until points.size) {
            val length = hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
            if (target <= length || i == points.size - 1) {
                val t = if (length == 0f) 0f else (target / length).coerceIn(0f, 1f)
                val dx = points[i][0] - points[i - 1][0]
                val dy = points[i][1] - points[i - 1][1]
                if (dir != null && length > 0f) { dir[0] = dx / length; dir[1] = dy / length }
                return floatArrayOf(points[i - 1][0] + dx * t, points[i - 1][1] + dy * t)
            }
            target -= length
        }
        return points.last()
    }

    fun inside(polygon: List<FloatArray>, x: Float, y: Float): Boolean {
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

    /** Distance from (x, y) to the polygon's outline. */
    fun edgeDistance(polygon: List<FloatArray>, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        var j = polygon.size - 1
        for (i in polygon.indices) {
            best = min(best, distanceToSegment(x, y, polygon[j][0], polygon[j][1], polygon[i][0], polygon[i][1]))
            j = i
        }
        return best
    }

    /**
     * The mean surface direction around an axis along y at ([cx], [cz]) with half sizes [hx], [hz], used as the
     * smooth-normal source of round bars: the face center's offset from the axis, scaled by the cross section.
     */
    fun radialNormal(x: Float, z: Float, cx: Float, cz: Float, hx: Float, hz: Float): FloatArray {
        val nx = (x - cx) / (hx * hx)
        val nz = (z - cz) / (hz * hz)
        val length = sqrt(nx * nx + nz * nz).takeIf { it > 1e-5f } ?: return floatArrayOf(0f, 0f, -1f)
        return floatArrayOf(nx / length, 0f, nz / length)
    }

    const val TAU = (2.0 * PI).toFloat()

    fun angle(y: Float, x: Float): Float = atan2(y, x)
}
