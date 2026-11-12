package geiler.addons.client.farming

import java.util.ArrayDeque

/** Bounded mean of the latest valid, observed boss spawn-to-kill durations. */
class SlayerKillTimeAverage(maxSamples: Int = DEFAULT_MAX_SAMPLES) {
    private val sampleLimit = maxSamples.coerceIn(1, MAX_SAMPLES)
    private val samples = ArrayDeque<Long>(sampleLimit)
    private var totalNanos = 0L

    /** Adds a nonnegative measured duration, capping extreme stale-session values at 24 hours. */
    fun record(durationNanos: Long): Boolean {
        if (durationNanos < 0L) return false
        if (samples.size >= sampleLimit) totalNanos -= samples.removeFirst()
        val sample = minOf(durationNanos, MAX_SAMPLE_NANOS)
        samples.addLast(sample)
        totalNanos += sample
        return true
    }

    fun sampleCount(): Int = samples.size

    fun averageNanos(): Long = if (samples.isEmpty()) -1L else totalNanos / samples.size

    fun reset() {
        samples.clear()
        totalNanos = 0L
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES = 16
        private const val MAX_SAMPLES = 64
        private const val MAX_SAMPLE_NANOS = 86_400_000_000_000L
    }
}
