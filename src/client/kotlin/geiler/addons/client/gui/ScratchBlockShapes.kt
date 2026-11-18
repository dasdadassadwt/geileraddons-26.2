package geiler.addons.client.gui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Vector outlines for the Scratch-style macro blocks: a notch on top, a matching bump below, C-shaped control
 * blocks, hat blocks with a dome, and hexagon condition chips. Corners are filleted arcs, and [Shape.mesh] turns an
 * outline into an outline ring, an inset fill and 1px alpha fringes so the GUI draws smooth, antialiased edges.
 */
object ScratchBlockShapes {
    const val NOTCH_X = 9
    const val NOTCH_WIDTH = 16
    const val NOTCH_DEPTH = 3
    const val ARM_WIDTH = 12
    const val HAT_DOME = 10
    const val HAT_DOME_WIDTH = 64

    /** Mesh vertex roles: solid and fully transparent outline color, solid and transparent fill color. */
    const val ROLE_OUTLINE = 0f
    const val ROLE_OUTLINE_CLEAR = 1f
    const val ROLE_FILL = 2f
    const val ROLE_FILL_CLEAR = 3f

    private const val CORNER = 3f
    private const val NOTCH_CORNER = 1.5f
    private const val INNER_CORNER = 3f
    private const val MAX_CACHED_SHAPES = 512
    private const val EPS = 1e-4f

    private val shapes = object : LinkedHashMap<String, Shape>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Shape>?): Boolean = size > MAX_CACHED_SHAPES
    }

    /** A closed outline in block-local GUI units (x, y pairs), clockwise on screen. */
    class Shape internal constructor(val points: FloatArray) {
        val width: Float = (points.indices step 2).maxOf { points[it] }
        val height: Float = (1 until points.size step 2).maxOf { points[it] }
        val area: Float = abs(signedArea(points)) / 2
        private var meshKey = Float.NaN
        private var meshCache = FloatArray(0)

        /** Even-odd point test; sample pixel centers (x + 0.5) to avoid landing exactly on an edge. */
        fun covers(x: Float, y: Float): Boolean {
            var inside = false
            val n = points.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = points[i * 2]; val yi = points[i * 2 + 1]
                val xj = points[j * 2]; val yj = points[j * 2 + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        /**
         * Quads of (x, y, role) vertices: the outline ring, the inset fill, then alpha fringes [fringe] wide outside
         * the outline and over the ring's inner edge. The last mesh is kept, keyed by both widths.
         */
        fun mesh(outlineWidth: Float, fringe: Float): FloatArray {
            val key = outlineWidth * 1000f + fringe
            if (key == meshKey) return meshCache
            val inner = offset(points, -outlineWidth)
            val out = FloatList()
            addTrapezoids(out, trapezoids(listOf(points, inner)), ROLE_OUTLINE)
            addTrapezoids(out, trapezoids(listOf(inner)), ROLE_FILL)
            addFringe(out, points, fringe, ROLE_OUTLINE, ROLE_OUTLINE_CLEAR)
            addFringe(out, inner, fringe, ROLE_FILL, ROLE_FILL_CLEAR)
            meshCache = out.toArray()
            meshKey = key
            return meshCache
        }
    }

    /** A plain stack block; [cap] drops the bump (nothing can follow it). */
    @JvmStatic
    fun stack(w: Int, h: Int, cap: Boolean): Shape = cBlock(w, intArrayOf(h), IntArray(0), cap)

    /**
     * A control block: [bars] are the full-width bars (top, any middle bars such as else, bottom) and [inners]
     * the gaps between them where only the left arm is drawn; `bars.size == inners.size + 1`.
     */
    @JvmStatic
    fun cBlock(w: Int, bars: IntArray, inners: IntArray, cap: Boolean): Shape {
        require(bars.size == inners.size + 1) { "a C-block needs one more bar than gaps" }
        return cached("c:$w:${bars.joinToString(",")}:${inners.joinToString(",")}:$cap") {
            val p = Path()
            val right = w.toFloat()
            p.v(0f, 0f, CORNER)
            p.notch(NOTCH_X.toFloat(), 0f)
            p.v(right, 0f, CORNER)
            var y = maxOf(NOTCH_DEPTH + 4, bars[0]).toFloat()
            for (k in inners.indices) {
                val arm = ARM_WIDTH.toFloat()
                p.v(right, y, CORNER)
                p.bump(arm + NOTCH_X, y)
                p.v(arm, y, INNER_CORNER)
                y += maxOf(NOTCH_DEPTH + 4, inners[k])
                p.v(arm, y, INNER_CORNER)
                p.notch(arm + NOTCH_X, y)
                p.v(right, y, CORNER)
                y += maxOf(NOTCH_DEPTH + 4, bars[k + 1])
            }
            p.v(right, y, CORNER)
            if (!cap) p.bump(NOTCH_X.toFloat(), y)
            p.v(0f, y, CORNER)
            p.build()
        }
    }

    /** An event hat: a dome over the top-left, a flat top elsewhere, and a bump for the first block. */
    @JvmStatic
    fun hat(w: Int, h: Int): Shape = cached("h:$w:$h") {
        val width = maxOf(w, HAT_DOME_WIDTH + 8).toFloat()
        val dome = HAT_DOME.toFloat()
        val domeW = HAT_DOME_WIDTH.toFloat()
        val bottom = maxOf(h, HAT_DOME + 8).toFloat()
        val p = Path()
        // Cubic dome from (0, dome) to (domeW, dome), shaped like Scratch's hat curve.
        val steps = 16
        for (s in 0 until steps) {
            val t = s / steps.toFloat()
            val u = 1 - t
            val x = 3 * u * u * t * 0.26f * domeW + 3 * u * t * t * 0.74f * domeW + t * t * t * domeW
            val yy = u * u * u * dome + 3 * u * u * t * (-0.1f * dome) + 3 * u * t * t * (-0.1f * dome) + t * t * t * dome
            p.v(x, yy, 0f)
        }
        p.v(domeW, dome, 2f)
        p.v(width, dome, CORNER)
        p.v(width, bottom, CORNER)
        p.bump(NOTCH_X.toFloat(), bottom)
        p.v(0f, bottom, CORNER)
        p.build()
    }

    /** A condition chip with 45-degree pointed ends. */
    @JvmStatic
    fun hexagon(w: Int, h: Int): Shape = cached("x:$w:$h") {
        val half = h / 2f
        val p = Path()
        p.v(0f, half, 0f)
        p.v(half, 0f, 0f)
        p.v(w - half, 0f, 0f)
        p.v(w.toFloat(), half, 0f)
        p.v(w - half, h.toFloat(), 0f)
        p.v(half, h.toFloat(), 0f)
        p.build()
    }

    /**
     * Even-odd trapezoid decomposition of one or more closed contours, as (y0, y1, left0, right0, left1, right1)
     * per trapezoid: each slab between vertex heights pairs up the edges that cross it.
     */
    @JvmStatic
    fun trapezoids(contours: List<FloatArray>): FloatArray {
        val edges = FloatList()
        val heights = ArrayList<Float>()
        for (c in contours) {
            val n = c.size / 2
            for (i in 0 until n) {
                val x0 = c[i * 2]; val y0 = c[i * 2 + 1]
                val x1 = c[(i + 1) % n * 2]; val y1 = c[(i + 1) % n * 2 + 1]
                heights.add(y0)
                if (abs(y1 - y0) < EPS) continue
                if (y0 < y1) edges.add(x0, y0, x1, y1) else edges.add(x1, y1, x0, y0)
            }
        }
        heights.sort()
        val e = edges.toArray()
        val out = FloatList()
        val crossing = ArrayList<FloatArray>()
        var k = 0
        while (k < heights.size - 1) {
            val top = heights[k]
            var next = k + 1
            while (next < heights.size && heights[next] - top < EPS) next++
            if (next >= heights.size) break
            val bottom = heights[next]
            val mid = (top + bottom) / 2
            crossing.clear()
            for (i in e.indices step 4) {
                if (e[i + 1] > top + EPS || e[i + 3] < bottom - EPS) continue
                val slope = (e[i + 2] - e[i]) / (e[i + 3] - e[i + 1])
                crossing.add(floatArrayOf(e[i] + slope * (top - e[i + 1]), e[i] + slope * (bottom - e[i + 1]),
                    e[i] + slope * (mid - e[i + 1])))
            }
            crossing.sortBy { it[2] }
            for (j in 0 until crossing.size - 1 step 2) {
                val l = crossing[j]
                val r = crossing[j + 1]
                out.add(top, bottom, l[0], r[0])
                out.add(l[1], r[1])
            }
            k = next
        }
        return out.toArray()
    }

    /** Moves every vertex along its mitred normal; positive [distance] grows the outline, negative insets it. */
    @JvmStatic
    fun offset(points: FloatArray, distance: Float): FloatArray {
        val n = points.size / 2
        val sign = if (signedArea(points) > 0) 1f else -1f
        val out = FloatArray(points.size)
        for (i in 0 until n) {
            val p = (i - 1 + n) % n
            val q = (i + 1) % n
            val (n1x, n1y) = normal(points, p, i, sign)
            val (n2x, n2y) = normal(points, i, q, sign)
            var mx = n1x + n2x
            var my = n1y + n2y
            val len = hypot(mx, my)
            if (len < EPS) { mx = n1x; my = n1y } else { mx /= len; my /= len }
            val scale = distance / max(0.35f, mx * n1x + my * n1y)
            out[i * 2] = points[i * 2] + mx * scale
            out[i * 2 + 1] = points[i * 2 + 1] + my * scale
        }
        return out
    }

    private fun normal(points: FloatArray, a: Int, b: Int, sign: Float): Pair<Float, Float> {
        val dx = points[b * 2] - points[a * 2]
        val dy = points[b * 2 + 1] - points[a * 2 + 1]
        val len = max(EPS, hypot(dx, dy))
        return Pair(sign * dy / len, -sign * dx / len)
    }

    private fun signedArea(points: FloatArray): Float {
        val n = points.size / 2
        var sum = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += points[i * 2] * points[j * 2 + 1] - points[j * 2] * points[i * 2 + 1]
        }
        return sum
    }

    private fun addTrapezoids(out: FloatList, t: FloatArray, role: Float) {
        for (i in t.indices step 6) {
            quad(out, t[i + 2], t[i], t[i + 4], t[i + 1], t[i + 5], t[i + 1], t[i + 3], t[i], role, role, role, role)
        }
    }

    private fun addFringe(out: FloatList, points: FloatArray, fringe: Float, solid: Float, clear: Float) {
        if (fringe <= 0f) return
        val grown = offset(points, fringe)
        val n = points.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            quad(out, points[i * 2], points[i * 2 + 1], grown[i * 2], grown[i * 2 + 1],
                grown[j * 2], grown[j * 2 + 1], points[j * 2], points[j * 2 + 1], solid, clear, clear, solid)
        }
    }

    /** Adds one quad, reordered so every quad winds the same way as Minecraft's own GUI rectangles. */
    private fun quad(out: FloatList, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float,
        dx: Float, dy: Float, ra: Float, rb: Float, rc: Float, rd: Float) {
        val area = (ax * by - bx * ay) + (bx * cy - cx * by) + (cx * dy - dx * cy) + (dx * ay - ax * dy)
        if (abs(area) < EPS * EPS) return
        if (area < 0) {
            out.add(ax, ay, ra); out.add(bx, by, rb); out.add(cx, cy, rc); out.add(dx, dy, rd)
        } else {
            out.add(dx, dy, rd); out.add(cx, cy, rc); out.add(bx, by, rb); out.add(ax, ay, ra)
        }
    }

    private fun cached(key: String, build: () -> Shape): Shape = shapes.getOrPut(key, build)

    /** Polyline builder; each vertex carries a fillet radius that [build] rounds into an arc. */
    private class Path {
        private val xs = ArrayList<Float>()
        private val ys = ArrayList<Float>()
        private val rs = ArrayList<Float>()

        fun v(x: Float, y: Float, r: Float) {
            xs.add(x); ys.add(y); rs.add(r)
        }

        /** A notch cut downward into a top edge that runs left to right. */
        fun notch(x: Float, y: Float) {
            val d = NOTCH_DEPTH.toFloat()
            v(x, y, NOTCH_CORNER)
            v(x + d, y + d, NOTCH_CORNER)
            v(x + NOTCH_WIDTH - d, y + d, NOTCH_CORNER)
            v(x + NOTCH_WIDTH, y, NOTCH_CORNER)
        }

        /** A bump hanging below a bottom edge that runs right to left. */
        fun bump(x: Float, y: Float) {
            val d = NOTCH_DEPTH.toFloat()
            v(x + NOTCH_WIDTH, y, NOTCH_CORNER)
            v(x + NOTCH_WIDTH - d, y + d, NOTCH_CORNER)
            v(x + d, y + d, NOTCH_CORNER)
            v(x, y, NOTCH_CORNER)
        }

        fun build(): Shape {
            val out = FloatList()
            val n = xs.size
            for (i in 0 until n) {
                val p = (i - 1 + n) % n
                val q = (i + 1) % n
                val vx = xs[i]; val vy = ys[i]
                val ax = xs[p] - vx; val ay = ys[p] - vy
                val bx = xs[q] - vx; val by = ys[q] - vy
                val la = hypot(ax, ay)
                val lb = hypot(bx, by)
                val theta = if (la < EPS || lb < EPS) PI.toFloat()
                    else acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f))
                if (rs[i] <= 0f || theta > PI.toFloat() - 0.01f) {
                    out.addPoint(vx, vy)
                    continue
                }
                val ux = ax / la; val uy = ay / la
                val wx = bx / lb; val wy = by / lb
                val t = min(rs[i] / tan(theta / 2), min(la, lb) / 2)
                val radius = t * tan(theta / 2)
                val bisX = ux + wx; val bisY = uy + wy
                val bisLen = hypot(bisX, bisY)
                val centerDistance = radius / sin(theta / 2)
                val cx = vx + bisX / bisLen * centerDistance
                val cy = vy + bisY / bisLen * centerDistance
                val a1 = atan2(vy + uy * t - cy, vx + ux * t - cx)
                var delta = atan2(vy + wy * t - cy, vx + wx * t - cx) - a1
                if (delta > PI) delta -= (2 * PI).toFloat()
                if (delta < -PI) delta += (2 * PI).toFloat()
                val steps = max(2, ceil(abs(delta) / (PI / 12)).toInt())
                for (s in 0..steps) {
                    val a = a1 + delta * s / steps
                    out.addPoint(cx + radius * cos(a), cy + radius * sin(a))
                }
            }
            return Shape(out.toPolygon())
        }
    }

    /** A growable float array; addPoint skips a point that repeats the previous one. */
    private class FloatList {
        private var data = FloatArray(64)
        private var size = 0

        fun add(vararg values: Float) {
            if (size + values.size > data.size) data = data.copyOf(max(data.size * 2, size + values.size))
            values.copyInto(data, size)
            size += values.size
        }

        fun addPoint(x: Float, y: Float) {
            if (size >= 2 && abs(data[size - 2] - x) < EPS && abs(data[size - 1] - y) < EPS) return
            add(x, y)
        }

        fun toArray(): FloatArray = data.copyOf(size)

        /** The outline points, without a closing point that repeats the first one. */
        fun toPolygon(): FloatArray {
            val closed = size >= 4 && abs(data[size - 2] - data[0]) < EPS && abs(data[size - 1] - data[1]) < EPS
            return data.copyOf(if (closed) size - 2 else size)
        }
    }
}
