package geiler.addons.client.dungeon.f7

/** F7 Phase 3 terminal anchors per section (from NoammAddons' Terminal ESP, CC0). */
object TerminalEspRules {
    private val SECTIONS: Array<Array<IntArray>> = arrayOf(
        arrayOf(intArrayOf(110, 113, 73), intArrayOf(110, 119, 79), intArrayOf(90, 112, 92), intArrayOf(90, 122, 101)),
        arrayOf(intArrayOf(68, 109, 122), intArrayOf(59, 119, 123), intArrayOf(47, 109, 122), intArrayOf(39, 108, 142), intArrayOf(40, 124, 123)),
        arrayOf(intArrayOf(-2, 109, 112), intArrayOf(-2, 119, 93), intArrayOf(18, 123, 93), intArrayOf(-2, 109, 77)),
        arrayOf(intArrayOf(41, 109, 30), intArrayOf(44, 121, 30), intArrayOf(67, 109, 30), intArrayOf(72, 114, 47)),
    )

    const val ANCHOR_RADIUS_SQUARED = 1.5

    /** Section index of an exact anchor block, or -1. */
    @JvmStatic
    fun sectionOf(x: Int, y: Int, z: Int): Int =
        SECTIONS.indexOfFirst { group -> group.any { it[0] == x && it[1] == y && it[2] == z } }

    /** Section whose anchor lies within the anchor radius of an armor stand at the given position, or -1. */
    @JvmStatic
    fun terminalSection(x: Double, y: Double, z: Double): Int =
        SECTIONS.indexOfFirst { group -> group.any { distanceSquared(x, y, z, it) <= ANCHOR_RADIUS_SQUARED } }

    /** The section whose closest terminal is nearest the player. */
    @JvmStatic
    fun playerSection(x: Double, y: Double, z: Double): Int {
        var best = -1
        var bestDistance = Double.MAX_VALUE
        for ((index, group) in SECTIONS.withIndex()) {
            val d = group.minOf { distanceSquared(x, y, z, it) }
            if (d < bestDistance) {
                bestDistance = d
                best = index
            }
        }
        return best
    }

    private fun distanceSquared(x: Double, y: Double, z: Double, anchor: IntArray): Double {
        val dx = x - anchor[0]
        val dy = y - anchor[1]
        val dz = z - anchor[2]
        return dx * dx + dy * dy + dz * dz
    }
}
