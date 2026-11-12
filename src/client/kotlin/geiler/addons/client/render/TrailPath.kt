package geiler.addons.client.render

/** A bounded list of recent foot positions that fades with age and is cut to a maximum path length. */
class TrailPath {
    @JvmRecord
    data class Point(val x: Double, val y: Double, val z: Double, val tick: Long)

    private val points = ArrayList<Point>()

    fun size(): Int = points.size

    fun points(): List<Point> = points

    fun clear() = points.clear()

    /** Adds a point once the position moved [minSpacing] from the last one; a jump over 16 blocks restarts the trail. */
    fun add(x: Double, y: Double, z: Double, tick: Long, minSpacing: Double): Boolean {
        val last = points.lastOrNull()
        if (last != null) {
            val distance = distance(last, x, y, z)
            if (distance < minSpacing) return false
            if (distance > MAX_JUMP) points.clear()
        }
        points.add(Point(x, y, z, tick))
        if (points.size > MAX_POINTS) points.subList(0, points.size - MAX_POINTS).clear()
        return true
    }

    /** Drops points that reached the decay age and any beyond [rangeBlocks] of path length behind the newest point. */
    fun prune(nowTick: Long, decayTicks: Int, rangeBlocks: Double) {
        points.removeAll { nowTick - it.tick >= decayTicks }
        var length = 0.0
        var keepFrom = 0
        for (index in points.size - 1 downTo 1) {
            length += distance(points[index - 1], points[index].x, points[index].y, points[index].z)
            if (length > rangeBlocks) {
                keepFrom = index
                break
            }
        }
        if (keepFrom > 0) points.subList(0, keepFrom).clear()
    }

    companion object {
        const val MAX_POINTS = 512
        private const val MAX_JUMP = 16.0

        /** Point spacing for a density of points per block. */
        @JvmStatic
        fun spacing(density: Int): Double = 1.0 / maxOf(1, density)

        /** 1 for a new point, fading linearly to 0 at the decay age. */
        @JvmStatic
        fun alpha(nowTick: Long, bornTick: Long, decayTicks: Int): Float {
            if (decayTicks <= 0) return 0f
            return (1f - (nowTick - bornTick).toFloat() / decayTicks).coerceIn(0f, 1f)
        }

        /** The color with its alpha scaled by [factor] (0..1). */
        @JvmStatic
        fun fadedArgb(argb: Int, factor: Float): Int {
            val alpha = Math.round(((argb ushr 24) and 0xFF) * factor.coerceIn(0f, 1f))
            return (alpha shl 24) or (argb and 0x00FFFFFF)
        }

        private fun distance(from: Point, x: Double, y: Double, z: Double): Double {
            val dx = x - from.x
            val dy = y - from.y
            val dz = z - from.z
            return Math.sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
