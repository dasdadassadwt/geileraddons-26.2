package geiler.addons.client.dungeon

import java.util.concurrent.atomic.AtomicBoolean

/** Bounds outstanding profile work and spaces the start of successive player lookups. */
class DungeonRequestLimiter(
    private val maximumOutstanding: Int,
    private val minimumStartIntervalNanos: Long,
) {
    private var outstandingCount = 0
    private var nextStartNanos = Long.MIN_VALUE

    init {
        require(maximumOutstanding >= 1) { "maximumOutstanding must be positive" }
        require(minimumStartIntervalNanos >= 0L) { "minimumStartIntervalNanos cannot be negative" }
    }

    @Synchronized
    fun tryAcquire(): Ticket? {
        if (outstandingCount >= maximumOutstanding) return null
        outstandingCount++
        return Ticket.create(this)
    }

    /** Reserves a paced start slot and returns how long the caller should wait. */
    @Synchronized
    fun reserveStartDelayNanos(nowNanos: Long): Long {
        val reservedStart = if (nextStartNanos == Long.MIN_VALUE) nowNanos else maxOf(nowNanos, nextStartNanos)
        nextStartNanos = reservedStart + minimumStartIntervalNanos
        return reservedStart - nowNanos
    }

    @Synchronized
    fun outstanding(): Int = outstandingCount

    @Synchronized
    private fun release() {
        if (outstandingCount > 0) outstandingCount--
    }

    class Ticket private constructor(private val owner: DungeonRequestLimiter) : AutoCloseable {
        private val closed = AtomicBoolean()

        companion object {
            @JvmStatic
            @JvmSynthetic
            internal fun create(owner: DungeonRequestLimiter): Ticket = Ticket(owner)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.release()
        }
    }
}
