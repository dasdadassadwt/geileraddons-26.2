package geiler.addons.client.collection

/**
 * Items that appeared in the player's own inventory, after SkyHanni's `ItemAddManager` (LGPL-2.1). Counts per item id
 * are compared between snapshots; only increases count. A snapshot taken while a container is open, or right after
 * one closes, becomes the new baseline instead, so items moved from chests, storage, trades, shops or crafting are
 * not counted as collected.
 */
class InventoryGains {
    private var baseline: Map<String, Long>? = null
    private var suppressUntilMs = 0L

    /** A container opened or closed, or the world changed: re-baseline for [graceMs] after now. */
    fun suppress(nowMs: Long, graceMs: Long = CLOSE_GRACE_MS) {
        baseline = null
        suppressUntilMs = maxOf(suppressUntilMs, nowMs + graceMs)
    }

    /** Positive per-item differences since the last snapshot; empty while suppressed or on the first snapshot. */
    fun observe(counts: Map<String, Long>, nowMs: Long): Map<String, Long> {
        val previous = baseline
        baseline = counts
        if (previous == null || nowMs < suppressUntilMs) return emptyMap()
        val gains = HashMap<String, Long>()
        for ((id, n) in counts) {
            val gained = n - (previous[id] ?: 0L)
            if (gained > 0) gains[id] = gained
        }
        return gains
    }

    companion object {
        const val CLOSE_GRACE_MS = 1_000L
    }
}
