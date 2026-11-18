package geiler.addons.client.mining

/**
 * Smooths a polyline for drawing only (the route data is untouched): a uniform Catmull-Rom spline through every
 * point, with the end points repeated so the curve starts and ends exactly on the first and last point.
 */
object PathSmoothing {
    /** Points per input segment; the result has `(points - 1) * STEPS + 1` entries. */
    const val STEPS = 6

    /** Each point is `[x, y, z]`. Fewer than three points are returned unchanged. */
    @JvmStatic
    fun smooth(points: List<DoubleArray>): List<DoubleArray> {
        if (points.size < 3) return points
        val out = ArrayList<DoubleArray>((points.size - 1) * STEPS + 1)
        for (i in 0 until points.size - 1) {
            val p0 = points[maxOf(i - 1, 0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[minOf(i + 2, points.size - 1)]
            for (s in 0 until STEPS) {
                val t = s.toDouble() / STEPS
                out.add(DoubleArray(3) { axis -> spline(p0[axis], p1[axis], p2[axis], p3[axis], t) })
            }
        }
        out.add(points.last())
        return out
    }

    private fun spline(a: Double, b: Double, c: Double, d: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
    }
}
