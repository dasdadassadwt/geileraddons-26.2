package geiler.addons.client.macros.killer

/**
 * Which mobs are worth walking to by what the walk costs against the line to them: a mob five blocks away across a wall that needs a
 * hundred blocks of walking is not near, whatever the line says. Plain arithmetic, so checks can run it.
 */
object DetourRule {
    /** A walk over this many times the straight line is a detour, if it is also over [MIN_BLOCKS] long. */
    const val RATIO = 2.5
    const val MIN_BLOCKS = 25.0

    /**
     * For each mob whether it stays: [walkCosts] are the walks in blocks (NaN when there is no map to say, infinity when there is no
     * way), [straights] the straight distances. A mob with no cost stays. When every mob is a detour the cheapest of those that
     * can be walked to stays, so something is tried; when none can be, none stays and a warp may still do it.
     */
    @JvmStatic
    fun keep(walkCosts: DoubleArray, straights: DoubleArray): BooleanArray {
        val keep = BooleanArray(walkCosts.size)
        var any = false
        for (i in walkCosts.indices) {
            val cost = walkCosts[i]
            keep[i] = cost.isNaN() || (cost.isFinite() && !(cost > RATIO * straights[i] && cost > MIN_BLOCKS))
            if (keep[i]) any = true
        }
        if (any) return keep
        var best = -1
        for (i in walkCosts.indices) {
            if (walkCosts[i].isFinite() && (best < 0 || walkCosts[i] < walkCosts[best])) best = i
        }
        if (best >= 0) keep[best] = true
        return keep
    }
}

/** How long a mob that could not be got to is left alone, by how many times in a row that has happened to it. */
object IgnoreRule {
    private val STEPS = longArrayOf(6_000L, 15_000L, 40_000L)

    @JvmStatic
    fun millis(streak: Int): Long = STEPS[streak.coerceIn(0, STEPS.size - 1)]
}
