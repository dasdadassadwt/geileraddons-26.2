package geiler.addons.client.fishing

/**
 * Bobber timer rules, after SkyHanni's FishingBobberTimer: when the clock starts and resets, and how it reads.
 * Pure and time-injected so offline checks cover them without a client.
 */
object FishingBobberRules {
    /** Sentinel for "no running timer". */
    const val INACTIVE = Long.MIN_VALUE

    /**
     * The next deploy time. A bobber that just appeared is a cast; with [startOnLiquidTouch] the clock waits for
     * liquid contact instead. A gone bobber (caught, reeled in, world change) resets the timer.
     */
    @JvmStatic
    fun step(
        deployNanos: Long,
        wasPresent: Boolean,
        present: Boolean,
        inLiquid: Boolean,
        startOnLiquidTouch: Boolean,
        nowNanos: Long,
    ): Long {
        if (!present) return INACTIVE
        if (!wasPresent) return if (!startOnLiquidTouch || inLiquid) nowNanos else INACTIVE
        if (startOnLiquidTouch && deployNanos == INACTIVE && inLiquid) return nowNanos
        return deployNanos
    }

    /** `5.3s` or `1m 15.0s`; tenths truncate, matching SkyHanni's millisecond duration format. */
    @JvmStatic
    fun format(elapsedNanos: Long): String {
        val tenths = (elapsedNanos.coerceAtLeast(0L) / 100_000_000L)
        val minutes = tenths / 600
        val seconds = (tenths % 600) / 10
        val tenth = tenths % 10
        return if (minutes > 0) "${minutes}m $seconds.${tenth}s" else "$seconds.${tenth}s"
    }

    @JvmStatic
    fun text(elapsedNanos: Long): String = "§aBobber: §f" + format(elapsedNanos)
}
