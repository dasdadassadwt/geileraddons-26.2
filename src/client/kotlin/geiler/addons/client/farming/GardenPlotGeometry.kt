package geiler.addons.client.farming

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure world-space border geometry, separated for offline validation. */
object GardenPlotGeometry {
    private const val MIN_BOX_HEIGHT = 0.05

    const val NO_DASH_BUDGET = 0

    @JvmStatic
    fun perimeter(plot: GardenPlotGrid.Plot?, y: Double, inset: Double): List<Segment> {
        if (plot == null || !y.isFinite() || !inset.isFinite()) return emptyList()
        val padding = clamp(inset, 0.0, GardenPlotGrid.PLOT_SIZE / 2.0 - 0.01)
        return ring(plot.minX + padding, plot.maxX() - padding, plot.minZ + padding, plot.maxZ() - padding, y)
    }

    /**
     * The twelve edges of a plot box between two fixed world heights.
     *
     * Nothing here is relative to the player: a border is a landmark, so it has to stay where it
     * was put while the player walks, jumps and changes floors. The two heights are the box's floor
     * and ceiling, and a degenerate pair still yields a visible sliver rather than nothing.
     */
    @JvmStatic
    fun boxEdges(plot: GardenPlotGrid.Plot?, bottomY: Double, topY: Double, inset: Double): List<Segment> {
        if (plot == null || !bottomY.isFinite() || !topY.isFinite() || !inset.isFinite()) return emptyList()

        val low = min(bottomY, topY)
        var high = max(bottomY, topY)
        if (high - low < MIN_BOX_HEIGHT) high = low + MIN_BOX_HEIGHT
        val padding = clamp(inset, 0.0, GardenPlotGrid.PLOT_SIZE / 2.0 - 0.01)
        val minX = plot.minX + padding
        val maxX = plot.maxX() - padding
        val minZ = plot.minZ + padding
        val maxZ = plot.maxZ() - padding
        val edges = ArrayList<Segment>(12)

        // The four verticals first, so a dashed box still shows its corners before its rings.
        edges.add(Segment(Point(minX, low, minZ), Point(minX, high, minZ)))
        edges.add(Segment(Point(maxX, low, minZ), Point(maxX, high, minZ)))
        edges.add(Segment(Point(maxX, low, maxZ), Point(maxX, high, maxZ)))
        edges.add(Segment(Point(minX, low, maxZ), Point(minX, high, maxZ)))

        // Build rings from the rectangle above instead of applying the inset a second time.
        edges.addAll(ring(minX, maxX, minZ, maxZ, high))
        edges.addAll(ring(minX, maxX, minZ, maxZ, low))
        return java.util.List.copyOf(edges)
    }

    /** Dashes any list of edges, so a flat perimeter and a box share one dash rule. */
    @JvmStatic
    fun dashedSegments(edges: List<Segment?>?, dashLength: Double, gapLength: Double): List<Segment> =
        dashedSegments(edges, dashLength, gapLength, NO_DASH_BUDGET)

    /**
     * Dashes a list of edges without letting the segment count run away.
     *
     * Each edge is dashed on its own. The budget lengthens dashes when needed to keep the segment
     * count bounded while leaving an unknown outline visible.
     */
    @JvmStatic
    fun dashedSegments(
        edges: List<Segment?>?,
        dashLength: Double,
        gapLength: Double,
        maxDashesPerEdge: Int,
    ): List<Segment> {
        if (edges == null || !dashLength.isFinite() || !gapLength.isFinite() ||
            dashLength <= 0.0 || gapLength < 0.0
        ) {
            return emptyList()
        }
        val budget = if (maxDashesPerEdge <= 0) Int.MAX_VALUE else maxDashesPerEdge
        val dashes = ArrayList<Segment>()
        for (edge in edges) {
            if (edge == null) continue
            val length = edge.length()
            if (length <= 0.0) continue

            // Counting dashes rather than advancing a truncated offset keeps the budget exact.
            var count = min(budget.toDouble(), ceil(length / (dashLength + gapLength))).toInt()
            if (count < 1) count = 1
            val step = length / count
            val dash = min(dashLength, step)
            for (index in 0 until count) {
                val start = index * step
                val end = min(length, start + dash)
                dashes.add(Segment(pointAt(edge, start / length), pointAt(edge, end / length)))
            }
        }
        return java.util.List.copyOf(dashes)
    }

    private fun ring(minX: Double, maxX: Double, minZ: Double, maxZ: Double, y: Double): List<Segment> {
        val northWest = Point(minX, y, minZ)
        val northEast = Point(maxX, y, minZ)
        val southEast = Point(maxX, y, maxZ)
        val southWest = Point(minX, y, maxZ)
        return java.util.List.of(
            Segment(northWest, northEast),
            Segment(northEast, southEast),
            Segment(southEast, southWest),
            Segment(southWest, northWest),
        )
    }

    private fun pointAt(segment: Segment, fraction: Double): Point {
        val from = segment.from
        val to = segment.to
        return Point(
            from.x + (to.x - from.x) * fraction,
            from.y + (to.y - from.y) * fraction,
            from.z + (to.z - from.z) * fraction,
        )
    }

    private fun clamp(value: Double, lower: Double, upper: Double): Double = max(lower, min(upper, value))

    @JvmRecord
    data class Point(val x: Double, val y: Double, val z: Double) {
        override fun toString(): String = "Point[x=$x, y=$y, z=$z]"
    }

    @JvmRecord
    data class Segment(val from: Point, val to: Point) {
        fun length(): Double = sqrt(
            square(to.x - from.x) + square(to.y - from.y) + square(to.z - from.z),
        )

        override fun toString(): String = "Segment[from=$from, to=$to]"
    }
}

private fun square(value: Double): Double = value * value
