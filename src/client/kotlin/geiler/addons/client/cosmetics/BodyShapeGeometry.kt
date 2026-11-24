package geiler.addons.client.cosmetics

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The player's torso rebuilt from the skin texture so it can take a shape: the body box (8 x 12 x 4 pixels)
 * is cut into 1-pixel rows whose width and depth narrow toward the waist, and a bust of two boxes sits on
 * the chest. Texture coordinates follow the vanilla skin layout (64 x 64), so the player's own skin is used.
 *
 * Output: per quad 4 corners of (x, y, z, u, v) in model pixels and skin pixels, then the normal (3 floats).
 */
object BodyShapeGeometry {
    const val FLOATS_PER_QUAD = 4 * 5 + 3
    const val SKIN_SIZE = 64f

    /** Body texture origin and the jacket (outer layer) origin in the skin. */
    private const val BODY_V = 16f
    private const val JACKET_V = 32f

    /** How much narrower the waist gets at waist = 1, per side (pixels). */
    private const val WAIST_IN = 0.9f
    private const val WAIST_ROW = 8f

    private val cache = HashMap<Pair<BodyShape, Boolean>, FloatArray>()

    /** Cached [torso]: shapes change rarely, so each frame reuses the built quads. */
    @JvmStatic
    fun cached(shape: BodyShape, jacket: Boolean): FloatArray = synchronized(cache) {
        if (cache.size > 64) cache.clear()
        cache.getOrPut(shape to jacket) { torso(shape, jacket) }
    }

    @JvmStatic
    fun torso(shape: BodyShape, jacket: Boolean): FloatArray {
        val out = ArrayList<Float>(64 * FLOATS_PER_QUAD)
        val inflate = if (jacket) 0.25f else 0f
        val v0 = if (jacket) JACKET_V else BODY_V
        val rows = 12
        fun halfWidth(y: Float) = 4f + inflate - shape.waist * WAIST_IN * profile(y)
        fun halfDepth(y: Float) = 2f + inflate - shape.waist * 0.35f * profile(y)
        val top = -inflate
        val bottom = 12f + inflate
        for (row in 0 until rows) {
            val ya = if (row == 0) top else row.toFloat()
            val yb = if (row == rows - 1) bottom else row + 1f
            val wa = halfWidth(row.toFloat())
            val wb = halfWidth(row + 1f)
            val da = halfDepth(row.toFloat())
            val db = halfDepth(row + 1f)
            val va = v0 + 4f + row
            val vb = va + 1f
            // Front (-z): u 20..28 from the player's right (-x) to left (+x).
            quad(out, -wa, ya, -da, 20f, va, wa, ya, -da, 28f, va, wb, yb, -db, 28f, vb, -wb, yb, -db, 20f, vb)
            // Back (+z): u 32..40 from the player's left (+x) to right (-x).
            quad(out, wa, ya, da, 32f, va, -wa, ya, da, 40f, va, -wb, yb, db, 40f, vb, wb, yb, db, 32f, vb)
            // Player's right side (-x): u 16..20 from back to front.
            quad(out, -wa, ya, da, 16f, va, -wa, ya, -da, 20f, va, -wb, yb, -db, 20f, vb, -wb, yb, db, 16f, vb)
            // Player's left side (+x): u 28..32 from front to back.
            quad(out, wa, ya, -da, 28f, va, wa, ya, da, 32f, va, wb, yb, db, 32f, vb, wb, yb, -db, 28f, vb)
        }
        val wt = halfWidth(0f)
        val dt = halfDepth(0f)
        quad(out, -wt, top, dt, 20f, v0, wt, top, dt, 28f, v0, wt, top, -dt, 28f, v0 + 4f, -wt, top, -dt, 20f, v0 + 4f)
        val wbm = halfWidth(12f)
        val dbm = halfDepth(12f)
        quad(out, -wbm, bottom, -dbm, 28f, v0, wbm, bottom, -dbm, 36f, v0, wbm, bottom, dbm, 36f, v0 + 4f, -wbm, bottom, dbm, 28f, v0 + 4f)
        if (shape.chest > 0.001f) {
            bust(out, shape, inflate, v0, 1f)
            bust(out, shape, inflate, v0, -1f)
        }
        return out.toFloatArray()
    }

    /** How far each side moves in at height [y] (pixels): the same for skin, jacket and armor, so they stay nested. */
    @JvmStatic
    fun waistIn(shape: BodyShape, y: Float): Float = shape.waist * WAIST_IN * profile(y)

    /** How far the front and back move in at height [y] (pixels). */
    @JvmStatic
    fun depthIn(shape: BodyShape, y: Float): Float = shape.waist * 0.35f * profile(y)

    /**
     * How far the jacket-layer bust stands out in front of the unshaped jacket front (z = -2.25) at height [y].
     * The bust is a tilted box, so this is its outline seen from the side.
     */
    @JvmStatic
    fun bustOut(shape: BodyShape, y: Float): Float {
        if (shape.chest <= 0.001f) return 0f
        val inflate = 0.25f
        val depth = 0.6f + 1.8f * shape.chest
        val tilt = 0.15f + 0.35f * shape.chest
        val yTop = 2f - inflate
        val height = 4f + inflate * 2f
        val zBack = -2f - inflate
        val c = kotlin.math.cos(tilt)
        val s = kotlin.math.sin(tilt)
        // Side outline of the box: back-top, front-top, front-bottom, back-bottom as (y, z).
        val ys = floatArrayOf(yTop, yTop + depth * s, yTop + height * c + depth * s, yTop + height * c)
        val zs = floatArrayOf(zBack, zBack - depth * c, zBack + height * s - depth * c, zBack + height * s)
        var front = Float.MAX_VALUE
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val y0 = ys[i]
            val y1 = ys[j]
            if (y0 == y1 || y < kotlin.math.min(y0, y1) || y > kotlin.math.max(y0, y1)) continue
            front = kotlin.math.min(front, zs[i] + (zs[j] - zs[i]) * (y - y0) / (y1 - y0))
        }
        return if (front == Float.MAX_VALUE) 0f else kotlin.math.max(0f, -front - 2.25f)
    }

    /**
     * Moves a point of a torso-sized box (body pixels, inflated by [inflate]) onto the shaped body: sides and
     * front and back pull in at the waist like the skin torso, and the front bulges over the bust with a small
     * gap, fading to nothing at the back so the side faces stay joined. Writes the result into [out].
     */
    @JvmStatic
    fun warp(shape: BodyShape, inflate: Float, x: Float, y: Float, z: Float, out: FloatArray) {
        val halfW = 4f + inflate
        val halfD = 2f + inflate
        val wy = y.coerceIn(0f, 12f)
        out[0] = x * (halfW - waistIn(shape, wy)) / halfW
        var nz = z * (halfD - depthIn(shape, wy)) / halfD
        // The bulge is the larger of the bust just above and below, so a half-pixel row never cuts into it.
        val bust = kotlin.math.max(bustOut(shape, y), kotlin.math.max(bustOut(shape, y - 0.5f), bustOut(shape, y + 0.5f)))
        if (bust > 0f) {
            // The front clears the jacket bust by ARMOR_GAP plus half the layer's own inflation, so the
            // leggings belt (0.5) stays under the chestplate (1.0) over the chest instead of flickering with it.
            val bulge = kotlin.math.max(0f, bust + ARMOR_GAP + 0.125f - 0.5f * inflate + depthIn(shape, wy))
            val front = ((halfD - z) / (2f * halfD)).coerceIn(0f, 1f)
            nz -= bulge * front * front
        }
        out[1] = y
        out[2] = nz
    }

    private const val ARMOR_GAP = 0.12f

    /** 0 at the shoulders and hips, 1 at the waist. */
    private fun profile(y: Float): Float {
        val d = (y - WAIST_ROW) / 3.2f
        return exp(-d * d)
    }

    /**
     * One side of the bust: a box over chest rows 2..6, tilted down from its top edge, textured with the
     * front of the shirt at those rows. [side] is +1 for the player's left, -1 for the right.
     */
    private fun bust(out: MutableList<Float>, shape: BodyShape, inflate: Float, v0: Float, side: Float) {
        val depth = 0.6f + 1.8f * shape.chest
        val tilt = 0.15f + 0.35f * shape.chest
        val x0 = side * 0.15f
        val x1 = side * (3.9f + inflate)
        val yTop = 2f - inflate
        val height = 4f + inflate * 2f
        val zBack = -2f - inflate + 0.01f
        val cosT = kotlin.math.cos(tilt)
        val sinT = kotlin.math.sin(tilt)
        // Points of the tilted box: rotate (y, z) about the top back edge.
        fun p(x: Float, dy: Float, dz: Float): FloatArray = floatArrayOf(x, yTop + dy * cosT - dz * sinT, zBack + dy * sinT + dz * cosT)
        val uIn = if (side > 0) 24f else 24f
        val uOut = if (side > 0) 28f else 20f
        val vTop = v0 + 4f + 2f
        val vBottom = vTop + 4f
        val frontTop0 = p(x0, 0f, -depth)
        val frontTop1 = p(x1, 0f, -depth)
        val frontBottom0 = p(x0, height, -depth)
        val frontBottom1 = p(x1, height, -depth)
        val backBottom0 = p(x0, height, 0f)
        val backBottom1 = p(x1, height, 0f)
        val backTop0 = p(x0, 0f, 0f)
        val backTop1 = p(x1, 0f, 0f)
        quadP(out, frontTop0, uIn, vTop, frontTop1, uOut, vTop, frontBottom1, uOut, vBottom, frontBottom0, uIn, vBottom)
        quadP(out, frontBottom0, uIn, vBottom - 1f, frontBottom1, uOut, vBottom - 1f, backBottom1, uOut, vBottom, backBottom0, uIn, vBottom)
        quadP(out, backTop0, uIn, vTop, backTop1, uOut, vTop, frontTop1, uOut, vTop + 1f, frontTop0, uIn, vTop + 1f)
        quadP(out, frontTop1, uOut - side * 1f, vTop, backTop1, uOut, vTop, backBottom1, uOut, vBottom, frontBottom1, uOut - side * 1f, vBottom)
        quadP(out, backTop0, uIn, vTop, frontTop0, uIn + side * 1f, vTop, frontBottom0, uIn + side * 1f, vBottom, backBottom0, uIn, vBottom)
    }

    private fun quadP(
        out: MutableList<Float>, a: FloatArray, ua: Float, va: Float, b: FloatArray, ub: Float, vb: Float,
        c: FloatArray, uc: Float, vc: Float, d: FloatArray, ud: Float, vd: Float,
    ) = quad(out, a[0], a[1], a[2], ua, va, b[0], b[1], b[2], ub, vb, c[0], c[1], c[2], uc, vc, d[0], d[1], d[2], ud, vd)

    private fun quad(
        out: MutableList<Float>,
        ax: Float, ay: Float, az: Float, au: Float, av: Float,
        bx: Float, by: Float, bz: Float, bu: Float, bv: Float,
        cx: Float, cy: Float, cz: Float, cu: Float, cv: Float,
        dx: Float, dy: Float, dz: Float, du: Float, dv: Float,
    ) {
        out.addAll(listOf(ax, ay, az, au, av, bx, by, bz, bu, bv, cx, cy, cz, cu, cv, dx, dy, dz, du, dv))
        // Normal from the diagonals, pointing out of the body (away from the torso's center line).
        var nx = (cy - ay) * (dz - bz) - (cz - az) * (dy - by)
        var ny = (cz - az) * (dx - bx) - (cx - ax) * (dz - bz)
        var nz = (cx - ax) * (dy - by) - (cy - ay) * (dx - bx)
        val length = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
        nx /= length
        ny /= length
        nz /= length
        val mx = (ax + bx + cx + dx) / 4f
        val my = (ay + by + cy + dy) / 4f - 6f
        val mz = (az + bz + cz + dz) / 4f
        if (nx * mx + ny * my + nz * mz < 0f) {
            nx = -nx
            ny = -ny
            nz = -nz
        }
        out.add(nx)
        out.add(ny)
        out.add(nz)
    }
}
