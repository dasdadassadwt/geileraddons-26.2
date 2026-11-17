package geiler.addons.client.dungeon.f7

/** F7 Phase 3 terminal anchors per section (from NoammAddons' Terminal ESP, CC0). */
object TerminalEspRules {
    val SECTIONS: Array<Array<IntArray>> = arrayOf(
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

/**
 * Every Phase 3 terminal as a marker, so far sections can be drawn before their armor stands load. A marker ends when
 * a loaded stand disappears nearby or when the chat line for a finished terminal names a player standing next to it.
 */
class TerminalMarkerBoard {
    class Marker(val section: Int, val x: Int, val y: Int, val z: Int) {
        var done = false
        var loaded = false
    }

    val markers: List<Marker> = TerminalEspRules.SECTIONS.withIndex()
        .flatMap { (section, group) -> group.map { Marker(section, it[0], it[1], it[2]) } }

    fun reset() = markers.forEach {
        it.done = false
        it.loaded = false
    }

    /** Index of the marker an armor stand at the position stands on, or -1. */
    fun anchorAt(x: Double, y: Double, z: Double): Int =
        markers.indices.filter { distanceSquared(x, y, z, markers[it]) <= TerminalEspRules.ANCHOR_RADIUS_SQUARED }
            .minByOrNull { distanceSquared(x, y, z, markers[it]) } ?: -1

    /** Marks the unfinished terminal nearest the position as done, if one is within [REACH]; returns its index or -1. */
    fun markDoneNear(x: Double, y: Double, z: Double): Int {
        val best = markers.indices.filter { !markers[it].done && distanceSquared(x, y, z, markers[it]) <= REACH * REACH }
            .minByOrNull { distanceSquared(x, y, z, markers[it]) } ?: return -1
        markers[best].done = true
        return best
    }

    private fun distanceSquared(x: Double, y: Double, z: Double, marker: Marker): Double {
        val dx = x - marker.x
        val dy = y - marker.y
        val dz = z - marker.z
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        /** A player clicks a terminal from within a few blocks; nobody farther than this finished it. */
        const val REACH = 9.0
        private val TERMINAL_LINE = Regex("^(\\w{1,16}) activated a terminal! \\(\\d+/\\d+\\)$")

        /** The player named by Hypixel's terminal-finished line, or null for any other line. */
        @JvmStatic
        fun terminalPlayer(line: String): String? = TERMINAL_LINE.matchEntire(line.trim())?.groupValues?.get(1)
    }
}
