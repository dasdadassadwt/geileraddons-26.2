package geiler.addons.client.macros.pathing

/** How much of the search budget a slice may use: more with nothing to walk on, less while a good route is being walked. */
object SearchBudget {
    /** A route with more than this many blocks left is good enough to walk while a search for the next runs gently. */
    private const val WALKING_MIN_REMAINING = 8.0

    @JvmStatic
    fun scale(walking: Boolean, remaining: Double): Double {
        if (!walking) return IDLE_SCALE
        if (remaining.isNaN()) return 1.0
        return if (remaining > WALKING_MIN_REMAINING) WALKING_SCALE else 1.0
    }

    private const val IDLE_SCALE = 1.5
    private const val WALKING_SCALE = 0.6
}
