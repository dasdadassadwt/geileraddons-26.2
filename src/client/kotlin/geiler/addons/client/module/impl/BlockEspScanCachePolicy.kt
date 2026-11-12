package geiler.addons.client.module.impl

/** Owns the bounded lifetime of a completed Block ESP snapshot and immediate cache invalidation. */
internal class BlockEspScanCachePolicy {
    private var ticksUntilRefresh = 0
    private var refreshReason = "startup"
    private var invalidationCount = 0L

    /** Returns true while the completed snapshot may be reused for this tick. */
    fun consumeWaitTick(): Boolean {
        if (ticksUntilRefresh <= 0) return false
        ticksUntilRefresh--
        return ticksUntilRefresh > 0
    }

    fun invalidate(reason: String) {
        ticksUntilRefresh = 0
        refreshReason = reason
        invalidationCount = (invalidationCount + 1).coerceAtMost(MAX_INVALIDATIONS)
    }

    fun completedScan() {
        ticksUntilRefresh = MAX_RESCAN_INTERVAL_TICKS
        refreshReason = "rescan-interval"
    }

    fun failedScan() {
        ticksUntilRefresh = MAX_RESCAN_INTERVAL_TICKS
        refreshReason = "snapshot-retry"
    }

    fun ticksUntilRefresh(): Int = ticksUntilRefresh

    fun refreshReason(): String = refreshReason

    fun invalidationCount(): Long = invalidationCount

    fun reset() {
        ticksUntilRefresh = 0
        refreshReason = "startup"
        invalidationCount = 0
    }

    companion object {
        const val MAX_RESCAN_INTERVAL_TICKS = 100
        private const val MAX_INVALIDATIONS = 1_000_000L
    }
}
