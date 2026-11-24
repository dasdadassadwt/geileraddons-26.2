package geiler.addons.client.collection

/**
 * Counts and active time of one tracker, for the session and across sessions. Rates divide by active time, which
 * only runs while the tracker is active (allowed island and a tracked gain within the idle window), so AFK time and
 * time elsewhere do not dilute items per hour.
 */
class CollectionStats {
    private val session = LinkedHashMap<String, Long>()
    private val total = LinkedHashMap<String, Long>()
    var sessionActiveMs = 0L
        private set
    var totalActiveMs = 0L
        private set
    private var lastTickMs = 0L

    fun record(itemId: String, amount: Long) {
        if (amount <= 0) return
        session.merge(itemId, amount, Long::plus)
        total.merge(itemId, amount, Long::plus)
    }

    /** Advances active time by the wall-clock time since the last call when [active]; gaps over 5 s are not counted. */
    fun tick(active: Boolean, nowMs: Long) {
        val last = lastTickMs
        lastTickMs = nowMs
        if (!active || last == 0L) return
        val step = nowMs - last
        if (step in 1..MAX_STEP_MS) {
            sessionActiveMs += step
            totalActiveMs += step
        }
    }

    fun count(itemId: String, totals: Boolean): Long = (if (totals) total[itemId] else session[itemId]) ?: 0L

    fun activeMs(totals: Boolean): Long = if (totals) totalActiveMs else sessionActiveMs

    /** Items per hour, or null before a minute of active time (the 30 s sack batches make earlier rates jumpy). */
    fun perHour(itemId: String, totals: Boolean): Double? = perHour(count(itemId, totals).toDouble(), activeMs(totals))

    fun resetSession() {
        session.clear()
        sessionActiveMs = 0L
    }

    fun resetTotal() {
        resetSession()
        total.clear()
        totalActiveMs = 0L
    }

    fun totals(): Map<String, Long> = LinkedHashMap(total)

    fun restoreTotal(counts: Map<String, Long>?, activeMs: Long) {
        total.clear()
        counts?.forEach { (id, n) -> if (n > 0) total[id] = n }
        totalActiveMs = activeMs.coerceAtLeast(0L)
    }

    companion object {
        const val MIN_RATE_MS = 60_000L
        private const val MAX_STEP_MS = 5_000L

        @JvmStatic
        fun perHour(amount: Double, activeMs: Long): Double? =
            if (activeMs < MIN_RATE_MS) null else amount * 3_600_000.0 / activeMs
    }
}
