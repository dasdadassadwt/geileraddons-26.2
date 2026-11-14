package geiler.addons.client.macros.killer

/** When a moving mob is searched for again and how many candidates a search is given. Plain arithmetic, so checks can run it. */
object ReplanRules {
    /** The nearest candidates a search gets; the rest stay in the plan. Every candidate costs work on every stand the search looks at. */
    const val GOAL_POOL = 8

    /** How far a mob has to move before the route to it is searched for again, in blocks, for a mob [distance] blocks away. A far mob moves less in the way. */
    @JvmStatic
    fun moveLimit(distance: Double): Double {
        if (distance.isNaN() || distance < 0.0) return MIN_MOVE
        return (distance * MOVE_PER_BLOCK).coerceIn(MIN_MOVE, MAX_MOVE)
    }

    /** The indices of the [count] smallest of [distances], smallest first. */
    @JvmStatic
    fun nearest(distances: DoubleArray, count: Int): IntArray {
        if (count <= 0 || distances.isEmpty()) return IntArray(0)
        return distances.indices.sortedBy { distances[it] }.take(count).toIntArray()
    }

    private const val MIN_MOVE = 1.0
    private const val MAX_MOVE = 3.0
    private const val MOVE_PER_BLOCK = 0.1
}
