package geiler.addons.client.utility

/** Monotonic, pauseable countdown state. All times passed to this class are `nanoTime` values. */
class CountdownTimerState {
    private var initialDurationNanos = 0L
    private var storedRemainingNanos = 0L
    private var startedAtNanos = 0L
    private var configured = false
    private var running = false
    private var completionEmitted = false

    fun setSeconds(seconds: Long, nowNanos: Long) {
        if (seconds < 0L || seconds > TimerDurationParser.MAX_SECONDS) {
            throw IllegalArgumentException("seconds outside supported timer range")
        }
        initialDurationNanos = seconds * 1_000_000_000L
        storedRemainingNanos = initialDurationNanos
        startedAtNanos = nowNanos
        configured = true
        running = true
        completionEmitted = false
    }

    fun pause(nowNanos: Long): Boolean {
        if (!running) return false
        storedRemainingNanos = remainingNanos(nowNanos)
        running = false
        return true
    }

    fun resume(nowNanos: Long): Boolean {
        if (!configured || running || storedRemainingNanos <= 0L) return false
        startedAtNanos = nowNanos
        running = true
        completionEmitted = false
        return true
    }

    /** Restores the original duration and leaves the timer paused. */
    fun reset(): Boolean {
        if (!configured) return false
        storedRemainingNanos = initialDurationNanos
        running = false
        completionEmitted = false
        return true
    }

    /** Returns true exactly once when a running countdown reaches zero. */
    fun tick(nowNanos: Long): Boolean {
        if (!running || remainingNanos(nowNanos) > 0L) return false
        storedRemainingNanos = 0
        running = false
        if (completionEmitted) return false
        completionEmitted = true
        return true
    }

    fun isConfigured(): Boolean = configured

    fun isRunning(): Boolean = running

    fun isComplete(): Boolean = configured && !running && storedRemainingNanos == 0L

    fun initialDurationSeconds(): Long = ceilSeconds(initialDurationNanos)

    fun remainingSeconds(nowNanos: Long): Long = ceilSeconds(remainingNanos(nowNanos))

    fun remainingNanos(nowNanos: Long): Long {
        if (!configured) return 0L
        if (!running) return storedRemainingNanos
        val elapsed = nowNanos - startedAtNanos
        if (elapsed <= 0L) return storedRemainingNanos
        return Math.max(0L, storedRemainingNanos - Math.min(storedRemainingNanos, elapsed))
    }

    fun status(nowNanos: Long): String {
        if (!configured) return "No timer set"
        val state = if (running) "running" else if (isComplete()) "complete" else "paused"
        return "Timer ${TimerDurationParser.formatSeconds(remainingSeconds(nowNanos))} ($state)"
    }

    private companion object {
        @JvmStatic
        private fun ceilSeconds(nanos: Long): Long {
            if (nanos <= 0L) return 0L
            val whole = nanos / 1_000_000_000L
            return if (nanos % 1_000_000_000L == 0L) whole else whole + 1
        }
    }
}
