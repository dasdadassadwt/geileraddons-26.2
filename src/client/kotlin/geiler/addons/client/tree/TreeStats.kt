package geiler.addons.client.tree

/**
 * One tree type's gift counters, for the current session and for every session before it.
 * Active time is accumulated from capped gaps between gifts; the current gap is displayed live
 * until its pause timeout and is never persisted as farming time.
 */
class TreeStats {
    private var sessionCountValue = 0
    private var sessionMillisValue = 0L
    private var storedCountValue = 0
    private var storedMillisValue = 0L
    private var lastGift = 0L
    private var started = false

    fun onGift(now: Long, timeoutMillis: Long) {
        if (started) sessionMillisValue += minOf(maxOf(0L, now - lastGift), timeoutMillis)
        started = true
        lastGift = now
        sessionCountValue++
    }

    fun sessionCount(): Int = sessionCountValue

    fun sessionMillis(now: Long, timeoutMillis: Long): Long =
        sessionMillisValue + openGap(now, timeoutMillis)

    /** Session plus every session before it - what the totals view shows. */
    fun totalCount(): Int = storedCountValue + sessionCountValue

    fun totalMillis(now: Long, timeoutMillis: Long): Long =
        storedMillisValue + sessionMillis(now, timeoutMillis)

    fun paused(now: Long, timeoutMillis: Long): Boolean = started && now - lastGift >= timeoutMillis

    /** Whether a gift was already counted this recently - i.e. this is the same event again. */
    fun countedWithin(now: Long, windowMillis: Long): Boolean = started && now - lastGift < windowMillis

    fun hasData(): Boolean = sessionCountValue > 0 || storedCountValue > 0

    /** Folds the current session into the stored totals and starts a fresh one. */
    fun rollOver(now: Long, timeoutMillis: Long) {
        storedMillisValue += sessionMillis(now, timeoutMillis)
        storedCountValue += sessionCountValue
        sessionMillisValue = 0L
        sessionCountValue = 0
        started = false
    }

    fun rawSessionCount(): Int = sessionCountValue

    fun rawSessionMillis(): Long = sessionMillisValue

    fun rawStoredCount(): Int = storedCountValue

    fun rawStoredMillis(): Long = storedMillisValue

    /** Restored data begins without an active gap from the previous game session. */
    fun restore(sessionCount: Int, sessionMillis: Long, storedCount: Int, storedMillis: Long) {
        sessionCountValue = maxOf(0, sessionCount)
        sessionMillisValue = maxOf(0L, sessionMillis)
        storedCountValue = maxOf(0, storedCount)
        storedMillisValue = maxOf(0L, storedMillis)
        started = false
    }

    private fun openGap(now: Long, timeoutMillis: Long): Long =
        if (!started) 0L else minOf(maxOf(0L, now - lastGift), timeoutMillis)

    companion object {
        /** Gifts per hour over the time given, or -1 when there isn't enough of it to divide by. */
        @JvmStatic
        fun ratePerHour(count: Int, millis: Long): Int {
            if (millis <= 0) return -1
            return Math.round(count * 3_600_000.0 / millis).toInt()
        }
    }
}
