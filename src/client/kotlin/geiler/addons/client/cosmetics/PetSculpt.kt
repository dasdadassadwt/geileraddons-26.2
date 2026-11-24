package geiler.addons.client.cosmetics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A small voxel sculpting kit for the pet and aura models (half-pixel voxels, 1 voxel = 0.5 model pixel).
 * Shapes are added with [ellipsoid], [rbox] and [capsule]; [bake] then adds the baked shade variation (lighter
 * on top, darker underneath and in creases, a small pixel-art jitter) and builds the mesh, optionally with
 * smooth normals taken from the blurred shape so curved parts light like a surface and not a staircase.
 * Coordinates are voxel units; a voxel (x, y, z) covers [x, x + 1) in each axis.
 */
internal class Sculpt {
    private class Cell(@JvmField var channel: Int, @JvmField var shade: Float, @JvmField val flat: Boolean = false)

    private val cells = HashMap<Long, Cell>()
    private val fixedColors = ArrayList<Int>()

    /** A channel that keeps its own color (eyes, beak, rain...); the same mapping is replayed at [bake]. */
    fun fixed(rgb: Int): Int {
        val color = rgb and 0xFFFFFF
        var index = fixedColors.indexOf(color)
        if (index < 0) {
            fixedColors.add(color)
            index = fixedColors.size - 1
        }
        return FIXED_BASE + index
    }

    fun set(x: Int, y: Int, z: Int, channel: Int, shade: Float) {
        cells[key(x, y, z)] = Cell(channel, shade)
    }

    /** Like [set] but the baked shade is kept exactly (no light, crease or jitter), for parts that must match across meshes. */
    fun setFlat(x: Int, y: Int, z: Int, channel: Int, shade: Float) {
        cells[key(x, y, z)] = Cell(channel, shade, true)
    }

    fun clear(x: Int, y: Int, z: Int) {
        cells.remove(key(x, y, z))
    }

    fun has(x: Int, y: Int, z: Int): Boolean = cells.containsKey(key(x, y, z))

    fun size(): Int = cells.size

    fun box(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, channel: Int, shade: Float) {
        for (x in x0 until x1) for (y in y0 until y1) for (z in z0 until z1) set(x, y, z, channel, shade)
    }

    /** Fills a (super)ellipsoid; [power] 2 is round, larger is boxier. */
    fun ellipsoid(
        cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, channel: Int, shade: Float, power: Float = 2f,
    ) {
        for (x in floorInt(cx - rx)..floorInt(cx + rx)) for (y in floorInt(cy - ry)..floorInt(cy + ry)) {
            for (z in floorInt(cz - rz)..floorInt(cz + rz)) {
                val dx = abs(x + 0.5f - cx) / rx
                val dy = abs(y + 0.5f - cy) / ry
                val dz = abs(z + 0.5f - cz) / rz
                val d = if (power == 2f) dx * dx + dy * dy + dz * dz
                else Math.pow(dx.toDouble(), power.toDouble()).toFloat() + Math.pow(dy.toDouble(), power.toDouble()).toFloat() +
                    Math.pow(dz.toDouble(), power.toDouble()).toFloat()
                if (d <= 1f) set(x, y, z, channel, shade)
            }
        }
    }

    /** A box with rounded edges ([round] voxels of radius). */
    fun rbox(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, round: Float, channel: Int, shade: Float,
    ) {
        val hx = (x1 - x0) / 2f
        val hy = (y1 - y0) / 2f
        val hz = (z1 - z0) / 2f
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val cz = (z0 + z1) / 2f
        val r = min(round, min(hx, min(hy, hz)))
        for (x in floorInt(x0)..floorInt(x1)) for (y in floorInt(y0)..floorInt(y1)) for (z in floorInt(z0)..floorInt(z1)) {
            val qx = abs(x + 0.5f - cx) - (hx - r)
            val qy = abs(y + 0.5f - cy) - (hy - r)
            val qz = abs(z + 0.5f - cz) - (hz - r)
            val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f) + max(qz, 0f) * max(qz, 0f))
            val inside = min(max(qx, max(qy, qz)), 0f)
            if (outside + inside - r <= 0f) set(x, y, z, channel, shade)
        }
    }

    /** A tapered tube from A (radius [ra]) to B (radius [rb]) with round caps. */
    fun capsule(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, ra: Float, rb: Float, channel: Int, shade: Float,
    ) {
        val rMax = max(ra, rb)
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val len2 = dx * dx + dy * dy + dz * dz
        for (x in floorInt(min(ax, bx) - rMax)..floorInt(max(ax, bx) + rMax)) {
            for (y in floorInt(min(ay, by) - rMax)..floorInt(max(ay, by) + rMax)) {
                for (z in floorInt(min(az, bz) - rMax)..floorInt(max(az, bz) + rMax)) {
                    val px = x + 0.5f - ax
                    val py = y + 0.5f - ay
                    val pz = z + 0.5f - az
                    val t = if (len2 == 0f) 0f else ((px * dx + py * dy + pz * dz) / len2).coerceIn(0f, 1f)
                    val ex = px - dx * t
                    val ey = py - dy * t
                    val ez = pz - dz * t
                    val r = ra + (rb - ra) * t
                    if (ex * ex + ey * ey + ez * ez <= r * r) set(x, y, z, channel, shade)
                }
            }
        }
    }

    /** Recolors every voxel for which [pick] returns a channel (>= 0); -1 keeps it. */
    fun paint(pick: (Int, Int, Int) -> Int) {
        for ((k, cell) in cells) {
            val channel = pick(ux(k), uy(k), uz(k))
            if (channel >= 0) cell.channel = channel
        }
    }

    /** Adds [delta] to the shade of every voxel (large-scale light and dark areas). */
    fun tone(delta: (Int, Int, Int) -> Float) {
        for ((k, cell) in cells) cell.shade += delta(ux(k), uy(k), uz(k))
    }

    /** Recolors the voxel at the front (smallest z) of column (x, y), for painting faces onto a baked body. */
    fun paintFront(x: Int, y: Int, channel: Int, shade: Float? = null) {
        var best = Int.MAX_VALUE
        for (k in cells.keys) if (ux(k) == x && uy(k) == y) best = min(best, uz(k))
        if (best == Int.MAX_VALUE) return
        val cell = cells[key(x, y, best)]!!
        cell.channel = channel
        if (shade != null) cell.shade = shade
    }

    /** Smallest z in column (x, y), or null when the column is empty. */
    fun frontZ(x: Int, y: Int): Int? {
        var best = Int.MAX_VALUE
        for (k in cells.keys) if (ux(k) == x && uy(k) == y) best = min(best, uz(k))
        return if (best == Int.MAX_VALUE) null else best
    }

    fun bake(smooth: Boolean = false, jitter: Float = 0.03f): VoxelMesh {
        val grid = VoxelGrid()
        for (rgb in fixedColors) grid.fixed(rgb)
        for ((k, cell) in cells) {
            val x = ux(k)
            val y = uy(k)
            val z = uz(k)
            grid.set(x, y, z, cell.channel, finalShade(x, y, z, cell, jitter))
        }
        if (!smooth) return grid.mesh(0.5f)
        val density = Density(cells.keys)
        return grid.mesh(0.5f) { x, y, z -> density.normal(x, y, z) }
    }

    private fun finalShade(x: Int, y: Int, z: Int, cell: Cell, jitter: Float): Float {
        if (cell.flat) return ((cell.shade.coerceIn(0.3f, 1.25f) * 25f).roundToInt()) / 25f
        var s = cell.shade + CosmeticModels.jitter(x, y, z, jitter)
        if (!has(x, y - 1, z)) s += 0.05f
        if (!has(x, y + 1, z)) s -= 0.06f
        var n = 0
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) if ((dx != 0 || dy != 0 || dz != 0) && has(x + dx, y + dy, z + dz)) n++
        val d = n - 17
        s -= if (d > 0) d * 0.008f else d * 0.003f
        return ((s.coerceIn(0.3f, 1.25f) * 25f).roundToInt()) / 25f
    }

    /** The shape blurred into a small density field; its gradient is the smooth surface direction. */
    private class Density(keys: Set<Long>) {
        private val x0: Int
        private val y0: Int
        private val z0: Int
        private val nx: Int
        private val ny: Int
        private val nz: Int
        private val data: FloatArray

        init {
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var minZ = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var maxZ = Int.MIN_VALUE
            for (k in keys) {
                minX = min(minX, ux(k)); maxX = max(maxX, ux(k))
                minY = min(minY, uy(k)); maxY = max(maxY, uy(k))
                minZ = min(minZ, uz(k)); maxZ = max(maxZ, uz(k))
            }
            x0 = minX - PAD; y0 = minY - PAD; z0 = minZ - PAD
            nx = maxX - minX + 1 + PAD * 2
            ny = maxY - minY + 1 + PAD * 2
            nz = maxZ - minZ + 1 + PAD * 2
            var field = FloatArray(nx * ny * nz)
            for (k in keys) field[index(ux(k) - x0, uy(k) - y0, uz(k) - z0)] = 1f
            repeat(2) {
                field = blur(field, 1, nz)
                field = blur(field, nz, ny)
                field = blur(field, ny * nz, nx)
            }
            data = field
        }

        private fun index(i: Int, j: Int, k: Int): Int = (i * ny + j) * nz + k

        /** [1 2 1] / 4 along one axis; [stride] steps along it, [count] is its length. */
        private fun blur(src: FloatArray, stride: Int, count: Int): FloatArray {
            val out = FloatArray(src.size)
            for (i in src.indices) {
                // Position along this axis.
                val along = (i / stride) % count
                val a = if (along > 0) src[i - stride] else src[i]
                val c = if (along < count - 1) src[i + stride] else src[i]
                out[i] = (a + src[i] * 2f + c) * 0.25f
            }
            return out
        }

        private fun at(i: Int, j: Int, k: Int): Float =
            if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) 0f else data[index(i, j, k)]

        /** Trilinear sample at a point in voxel units (voxel centers sit at +0.5). */
        private fun sample(px: Float, py: Float, pz: Float): Float {
            val u = px - 0.5f - x0
            val v = py - 0.5f - y0
            val w = pz - 0.5f - z0
            val i = floorInt(u)
            val j = floorInt(v)
            val k = floorInt(w)
            val fu = u - i
            val fv = v - j
            val fw = w - k
            var sum = 0f
            for (a in 0..1) for (b in 0..1) for (c in 0..1) {
                sum += at(i + a, j + b, k + c) * (if (a == 0) 1 - fu else fu) * (if (b == 0) 1 - fv else fv) * (if (c == 0) 1 - fw else fw)
            }
            return sum
        }

        fun normal(x: Float, y: Float, z: Float): FloatArray {
            val gx = sample(x + 1f, y, z) - sample(x - 1f, y, z)
            val gy = sample(x, y + 1f, z) - sample(x, y - 1f, z)
            val gz = sample(x, y, z + 1f) - sample(x, y, z - 1f)
            val length = sqrt(gx * gx + gy * gy + gz * gz)
            if (length < 1e-4f) return floatArrayOf(0f, 0f, 0f)
            return floatArrayOf(-gx / length, -gy / length, -gz / length)
        }

        private companion object {
            const val PAD = 4
        }
    }

    private companion object {
        const val FIXED_BASE = 2
        const val BIAS = 4096

        fun key(x: Int, y: Int, z: Int): Long = ((x + BIAS).toLong() shl 32) or ((y + BIAS).toLong() shl 16) or (z + BIAS).toLong()
        fun ux(k: Long): Int = (k shr 32).toInt() - BIAS
        fun uy(k: Long): Int = ((k shr 16) and 0xFFFF).toInt() - BIAS
        fun uz(k: Long): Int = (k and 0xFFFF).toInt() - BIAS
        fun floorInt(v: Float): Int = Math.floor(v.toDouble()).toInt()
    }
}
