package geiler.addons.client.macros.killer

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Splits the mobs in range into the stretches of ground they stand on, so one stretch is cleared before the macro walks to the next:
 * ground cut into pieces by a gap, a wall or a drop is worked one piece at a time, not hopped between by whichever mob is nearest. Plain
 * arithmetic, free of game state so checks can run it.
 *
 * Two mobs are on the same piece when they are near each other across and not far apart in height, and the walks to them from the player
 * do not differ by more than the way between them would explain: a mob 5 blocks from another across a ravine has a walk that is 40
 * blocks longer, which a straight line would never say.
 */
object SegmentRules {
    /** Mobs further apart than this across are not linked directly. */
    const val LINK_BLOCKS = 10.0

    /** Mobs further apart than this in height are not linked: another floor. */
    const val LINK_HEIGHT = 3.0

    /** The walks to two linked mobs differ by at most this many times the line between them, plus [WALK_SLACK]. */
    private const val WALK_RATIO = 1.5
    private const val WALK_SLACK = 6.0

    /**
     * The piece each mob is on, numbered from 0 in the order the pieces are first met. [walks] are the walks from the player in blocks
     * (NaN when there is no map to say, infinity when there is no way); where either of two mobs has none, the line and the height decide alone.
     */
    @JvmStatic
    fun cluster(x: DoubleArray, y: DoubleArray, z: DoubleArray, walks: DoubleArray?): IntArray {
        val count = x.size
        val parent = IntArray(count) { it }
        fun find(i: Int): Int {
            var at = i
            while (parent[at] != at) {
                parent[at] = parent[parent[at]]
                at = parent[at]
            }
            return at
        }
        for (i in 0 until count) for (j in i + 1 until count) {
            val line = hypot(x[i] - x[j], z[i] - z[j])
            if (line > LINK_BLOCKS || abs(y[i] - y[j]) > LINK_HEIGHT) continue
            if (walks != null) {
                val a = walks[i]
                val b = walks[j]
                // No way to one of them is a piece of its own, whatever the line says.
                if (a.isInfinite() != b.isInfinite()) continue
                if (a.isFinite() && b.isFinite() && abs(a - b) > line * WALK_RATIO + WALK_SLACK) continue
            }
            parent[find(i)] = find(j)
        }
        val ids = IntArray(count)
        val numbered = HashMap<Int, Int>()
        for (i in 0 until count) ids[i] = numbered.getOrPut(find(i)) { numbered.size }
        return ids
    }
}
