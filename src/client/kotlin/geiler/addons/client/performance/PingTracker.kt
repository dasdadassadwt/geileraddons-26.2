package geiler.addons.client.performance

/**
 * Round-trip ping from `ServerboundPingRequest`/`ClientboundPongResponse` (Odin's approach). Hypixel does
 * not fill the tab-list latency, so that value always reads 0. Requests echo `System.nanoTime()`; pongs
 * echoing another clock (vanilla's F3 pinger sends millis) give an implausible round trip and are ignored.
 */
object PingTracker {
    private const val SEND_INTERVAL_NANOS = 2_000_000_000L
    private const val MAX_RTT_NANOS = 10_000_000_000L
    private const val STALE_NANOS = 10_000_000_000L

    private var lastSentNanos = Long.MIN_VALUE
    private var latestRttNanos = -1L
    private var latestAtNanos = Long.MIN_VALUE

    /** True (and marks the send) when a new ping request is due. */
    @JvmStatic
    @Synchronized
    fun shouldSend(nowNanos: Long): Boolean {
        if (lastSentNanos != Long.MIN_VALUE && nowNanos - lastSentNanos < SEND_INTERVAL_NANOS) return false
        lastSentNanos = nowNanos
        return true
    }

    @JvmStatic
    @Synchronized
    fun onPong(echoedNanos: Long, nowNanos: Long) {
        val rtt = nowNanos - echoedNanos
        if (rtt < 0L || rtt > MAX_RTT_NANOS) return
        latestRttNanos = rtt
        latestAtNanos = nowNanos
    }

    /** Latest round trip in ms, or null when none arrived in the last 10 s. */
    @JvmStatic
    @Synchronized
    fun latestMs(nowNanos: Long): Int? {
        if (latestRttNanos < 0L || nowNanos - latestAtNanos > STALE_NANOS) return null
        return (latestRttNanos / 1_000_000L).toInt()
    }

    @JvmStatic
    @Synchronized
    fun reset() {
        lastSentNanos = Long.MIN_VALUE
        latestRttNanos = -1L
        latestAtNanos = Long.MIN_VALUE
    }
}
