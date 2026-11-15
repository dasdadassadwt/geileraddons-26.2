package geiler.addons.client.dungeon.f7

/**
 * Arrow Align device rules: the nine known patterns (Odin's table, BSD 3-Clause) over the 5x5 item-frame grid at
 * x = -2, y 120..124, z 75..79. Index = (y - 120) + (z - 75) * 5; -1 marks a frame that holds no arrow.
 */
object ArrowAlignRules {
    const val CORNER_X = -2
    const val CORNER_Y = 120
    const val CORNER_Z = 75

    @JvmField
    val SOLUTIONS: List<IntArray> = listOf(
        intArrayOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1),
        intArrayOf(-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1),
        intArrayOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3),
        intArrayOf(5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1),
        intArrayOf(5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
        intArrayOf(7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
        intArrayOf(-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
        intArrayOf(-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1),
        intArrayOf(-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
    )

    @JvmStatic
    fun neededClicks(current: Int, target: Int): Int = (8 - current + target) % 8

    /** Grid index of an arrow frame at a block position, or -1 outside the device. */
    @JvmStatic
    fun frameIndex(x: Int, y: Int, z: Int): Int {
        if (x != CORNER_X || y !in CORNER_Y..CORNER_Y + 4 || z !in CORNER_Z..CORNER_Z + 4) return -1
        return (y - CORNER_Y) + (z - CORNER_Z) * 5
    }

    /** Block position of grid index [index]. */
    @JvmStatic
    fun framePosition(index: Int): IntArray = intArrayOf(CORNER_X, CORNER_Y + index % 5, CORNER_Z + index / 5)

    /**
     * [current] holds each frame's rotation (0..7) or -1 where there is no arrow frame. A pattern matches when
     * it uses exactly the frames that exist. Returns the matching pattern, or null.
     */
    @JvmStatic
    fun solve(current: IntArray): IntArray? {
        if (current.size != 25) return null
        var found: IntArray? = null
        for (pattern in SOLUTIONS) {
            var matches = true
            for (i in 0 until 25) {
                if ((pattern[i] == -1 || current[i] == -1) && pattern[i] != current[i]) {
                    matches = false
                    break
                }
            }
            if (matches) found = pattern
        }
        return found
    }

    /** Clicks still needed per frame (0 where the frame is unused or already right). */
    @JvmStatic
    fun clicks(current: IntArray, solution: IntArray): IntArray =
        IntArray(25) { i -> if (solution[i] == -1 || current[i] == -1) 0 else neededClicks(current[i], solution[i]) }
}
