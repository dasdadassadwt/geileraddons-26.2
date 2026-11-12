package geiler.addons.client.chat

import java.util.ArrayDeque
import java.util.Locale

/**
 * Estimates tick rate from observed game-time deltas and monotonic packet-receive times.
 *
 * The set-time packet is not assumed to arrive periodically. A value is reported only when the
 * observed interval is plausible and fresh; rolling values additionally require continuous samples
 * spanning the entire window. Boundary intervals are apportioned at their observed average rate, so
 * these remain client-side estimates rather than an authoritative server TPS measurement.
 */
object ChatTpsTracker {
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val TEN_SECONDS = 10L * NANOS_PER_SECOND
    private const val ONE_MINUTE = 60L * NANOS_PER_SECOND
    private const val MAX_SAMPLE_AGE = 65L * NANOS_PER_SECOND
    private const val MAX_SAMPLE_GAP = 5L * NANOS_PER_SECOND
    private const val MIN_SAMPLE_INTERVAL = 250_000_000L
    private const val MAX_TPS = 20.0
    private const val TICK_JITTER_ALLOWANCE = 2.0

    private val samples = ArrayDeque<Sample>()
    private var previousGameTime = Long.MIN_VALUE
    private var previousNanos = Long.MIN_VALUE

    /** Records one packet's level game time and monotonic receipt timestamp. */
    @JvmStatic
    @Synchronized
    fun record(gameTime: Long, receivedAtNanos: Long) {
        if (previousGameTime == Long.MIN_VALUE) {
            setPrevious(gameTime, receivedAtNanos)
            return
        }

        val elapsedNanos = receivedAtNanos - previousNanos
        val ticks = gameTime - previousGameTime
        if (elapsedNanos <= 0L || ticks <= 0L || elapsedNanos > MAX_SAMPLE_GAP) {
            reset()
            setPrevious(gameTime, receivedAtNanos)
            return
        }

        if (elapsedNanos < MIN_SAMPLE_INTERVAL) {
            // Keep the older anchor so closely spaced packets can form one less noisy interval.
            return
        }

        val elapsedSeconds = elapsedNanos / NANOS_PER_SECOND.toDouble()
        val maximumPlausibleTicks = kotlin.math.ceil(MAX_TPS * elapsedSeconds + TICK_JITTER_ALLOWANCE)
        if (ticks > maximumPlausibleTicks) {
            reset()
            setPrevious(gameTime, receivedAtNanos)
            return
        }

        val tps = minOf(MAX_TPS, ticks / elapsedSeconds)
        samples.addLast(Sample(previousNanos, receivedAtNanos, ticks, tps))
        setPrevious(gameTime, receivedAtNanos)
        prune(receivedAtNanos)
    }

    /** Clears observations on login, respawn, or connection close. */
    @JvmStatic
    @Synchronized
    fun reset() {
        samples.clear()
        previousGameTime = Long.MIN_VALUE
        previousNanos = Long.MIN_VALUE
    }

    /** Returns latest and trailing-window values as of the newest fresh observation. */
    @JvmStatic
    @Synchronized
    fun metrics(nowNanos: Long): Metrics {
        prune(nowNanos)
        val latest = samples.peekLast()
        if (latest == null) {
            return Metrics(
                null, null, null,
                "waiting for samples", "need full 10s coverage", "need full 60s coverage",
            )
        }

        val ageNanos = nowNanos - latest.endNanos
        if (ageNanos < 0L || ageNanos > MAX_SAMPLE_GAP) {
            return Metrics(null, null, null, "stale samples", "stale samples", "stale samples")
        }

        val tenSeconds = averageOver(latest.endNanos, TEN_SECONDS)
        val oneMinute = averageOver(latest.endNanos, ONE_MINUTE)
        return Metrics(
            latest.tps,
            tenSeconds,
            oneMinute,
            null,
            if (tenSeconds == null) "need full 10s coverage" else null,
            if (oneMinute == null) "need full 60s coverage" else null,
        )
    }

    private fun setPrevious(gameTime: Long, receivedAtNanos: Long) {
        previousGameTime = gameTime
        previousNanos = receivedAtNanos
    }

    private fun prune(nowNanos: Long) {
        val cutoff = nowNanos - MAX_SAMPLE_AGE
        while (samples.isNotEmpty() && samples.peekFirst().endNanos < cutoff) {
            samples.removeFirst()
        }
    }

    /** Integrates observed per-interval rates, refusing any unobserved time in the requested window. */
    private fun averageOver(endNanos: Long, durationNanos: Long): Double? {
        val startNanos = endNanos - durationNanos
        var coveredUntil = startNanos
        var estimatedTicks = 0.0
        val iterator = samples.iterator()
        while (iterator.hasNext() && coveredUntil < endNanos) {
            val sample = iterator.next()
            if (sample.endNanos <= coveredUntil) continue
            if (sample.startNanos > coveredUntil) return null

            val overlapStart = maxOf(coveredUntil, sample.startNanos)
            val overlapEnd = minOf(endNanos, sample.endNanos)
            if (overlapEnd <= overlapStart) continue

            val intervalNanos = sample.endNanos - sample.startNanos
            val overlapFraction = (overlapEnd - overlapStart) / intervalNanos.toDouble()
            estimatedTicks += sample.ticks * overlapFraction
            coveredUntil = overlapEnd
        }

        if (coveredUntil < endNanos) return null
        return minOf(MAX_TPS, estimatedTicks / (durationNanos / NANOS_PER_SECOND.toDouble()))
    }

    private fun format(value: Double?, unavailableReason: String?): String =
        if (value == null) "N/A ($unavailableReason)" else String.format(Locale.ROOT, "%.1f", value)

    private data class Sample(val startNanos: Long, val endNanos: Long, val ticks: Long, val tps: Double)

    /** Java record boundary used by chat command replies and the existing regression harness. */
    @JvmRecord
    data class Metrics(
        val currentTps: Double?,
        val tenSecondTps: Double?,
        val oneMinuteTps: Double?,
        val currentUnavailableReason: String?,
        val tenSecondUnavailableReason: String?,
        val oneMinuteUnavailableReason: String?,
    ) {
        fun format(): String = "TPS estimate (client-observed): current " +
            ChatTpsTracker.format(currentTps, currentUnavailableReason) +
            " | 10s " + ChatTpsTracker.format(tenSecondTps, tenSecondUnavailableReason) +
            " | 1m " + ChatTpsTracker.format(oneMinuteTps, oneMinuteUnavailableReason)
    }
}
