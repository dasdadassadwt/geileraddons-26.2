package geiler.addons.client.waypoint

/** Ordered-route progression, after Skyblocker's ordered waypoint groups. */
object OrderedWaypointRules {
    const val NONE = 0
    const val PREVIOUS = 1
    const val CURRENT = 2
    const val NEXT = 3

    /**
     * Returns the new current index. Each waypoint the player stands within [radius] of (measured to the block
     * centre) becomes current if the skipping and backwards switches allow it.
     * [positions] holds `{x, y, z}` block coordinates in route order.
     */
    @JvmStatic
    fun advance(
        currentIndex: Int,
        px: Double,
        py: Double,
        pz: Double,
        positions: Array<IntArray>,
        radius: Double,
        allowBackwards: Boolean,
        allowSkipping: Boolean,
    ): Int {
        val count = positions.size
        if (count == 0) return 0
        var current = currentIndex.coerceIn(0, count - 1)
        val radiusSquared = radius * radius
        for (i in 0 until count) {
            val next = (current + 1) % count
            val previous = (current - 1 + count) % count
            val notBackwards = allowBackwards || i == next
            val notSkipping = allowSkipping || i == next || i == previous
            if (!notBackwards || !notSkipping) continue
            val p = positions[i]
            val dx = px - (p[0] + 0.5)
            val dy = py - (p[1] + 0.5)
            val dz = pz - (p[2] + 0.5)
            if (dx * dx + dy * dy + dz * dz < radiusSquared) current = i
        }
        return current
    }

    /** The role of waypoint [index] around [current]: previous (red), current (white), next (green) or hidden. */
    @JvmStatic
    fun relative(index: Int, current: Int, count: Int): Int {
        if (count <= 0) return NONE
        val previous = (current - 1 + count) % count
        val next = (current + 1) % count
        return when (index) {
            previous -> PREVIOUS
            next -> NEXT
            current -> CURRENT
            else -> NONE
        }
    }
}
