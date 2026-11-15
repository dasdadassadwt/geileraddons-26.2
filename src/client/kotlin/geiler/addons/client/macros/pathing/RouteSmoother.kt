package geiler.addons.client.macros.pathing

/**
 * Rounds the corners of a polyline: each pass cuts every corner at a quarter of the way along both its sides (Chaikin's corner
 * cutting), so a route made of grid steps and a waypoint every few blocks is drawn as the smooth curve it is walked as. The ends stay.
 * Used for drawing; the walker steers at points ahead on the route, which is the same thing done by the body. Plain arithmetic, so checks
 * can run it.
 */
object RouteSmoother {
    /** Passes of corner cutting: each doubles the points, and two are enough to read as a curve. */
    const val PASSES = 2

    /** The most points a smoothed line may have: a long route is not made longer than this. */
    const val MAX_POINTS = 600

    /**
     * [points] as x, y, z, smoothed [passes] times. Fewer than three points are returned as they are, and so is any line that would
     * grow past [MAX_POINTS].
     */
    @JvmStatic
    @JvmOverloads
    fun smooth(points: List<DoubleArray>, passes: Int = PASSES): List<DoubleArray> {
        var line = points
        repeat(passes) {
            if (line.size < 3 || line.size * 2 > MAX_POINTS) return line
            val next = ArrayList<DoubleArray>(line.size * 2)
            next.add(line.first())
            for (i in 0 until line.size - 1) {
                val a = line[i]
                val b = line[i + 1]
                if (i > 0) next.add(mix(a, b, 0.25))
                if (i < line.size - 2) next.add(mix(a, b, 0.75))
            }
            next.add(line.last())
            line = next
        }
        return line
    }

    private fun mix(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray =
        doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
}
