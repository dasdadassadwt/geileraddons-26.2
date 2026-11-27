package geiler.addons.client.foraging

/**
 * Turns the server ticks at which the Tune Frequency target pane moved into a speed. The first move only starts the
 * clock (the time before it is a partial interval); after that the median of the recent intervals drops lag spikes.
 */
class TunerSpeedMeter {
    private var lastMove = -1L
    private val intervals = ArrayDeque<Long>()

    fun reset() {
        lastMove = -1L
        intervals.clear()
    }

    /** The speed (1..5) after a move seen at [serverTick], or -1 while unknown. */
    fun move(serverTick: Long): Int {
        val previous = lastMove
        lastMove = serverTick
        if (previous < 0 || serverTick <= previous) return -1
        intervals.addLast(serverTick - previous)
        if (intervals.size > SAMPLES) intervals.removeFirst()
        val sorted = intervals.sorted()
        val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0
        val close = intervals.filter { it >= median * 0.8 && it <= median * 1.2 }
        return if (close.isEmpty()) -1 else ForagingRules.speedFromTicks(close.average())
    }

    private companion object {
        const val SAMPLES = 10
    }
}
