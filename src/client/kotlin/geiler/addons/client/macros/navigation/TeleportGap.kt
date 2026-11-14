package geiler.addons.client.macros.navigation

/**
 * The least time between two teleport clicks: a few ticks for the plain item, more for etherwarp, and a little random on top so the
 * clicks do not come at one beat. Plain arithmetic, so checks can run it.
 */
object TeleportGap {
    private const val TICK_MILLIS = 50L

    /** Ticks of random drawn on top of the gap. */
    const val JITTER_TICKS = 2.0

    /** The gap in milliseconds for a click of this kind; [jitter01] is a random number from 0 to 1. A gap of 0 ticks means none at all. */
    @JvmStatic
    fun minMillis(warp: Boolean, hopTicks: Int, warpTicks: Int, jitter01: Double): Long {
        val base = if (warp) warpTicks else hopTicks
        if (base <= 0) return 0L
        return ((base + jitter01.coerceIn(0.0, 1.0) * JITTER_TICKS) * TICK_MILLIS).toLong()
    }
}
