package geiler.addons.client.farming

import java.util.ArrayDeque
import java.util.Deque

/** Rolling pest spawn interval average with a configurable AFK-gap exclusion. */
class PestSpawnAverage {
    private var lastSpawnNanos = 0L
    private var lastCropActionNanos = 0L
    private var maximumCropActionGapNanos = 0L
    private var hasLastSpawn = false
    private var hasCropAction = false
    private var lastSpawnEligible = false
    private val intervalsNanos: Deque<Long> = ArrayDeque()

    /** Records crop activity and the longest idle gap during the current spawn interval. */
    fun onCropAction(nowNanos: Long) {
        if (!hasCropAction && hasLastSpawn && nowNanos > lastSpawnNanos) {
            maximumCropActionGapNanos = maxOf(maximumCropActionGapNanos, nowNanos - lastSpawnNanos)
        } else if (hasCropAction && nowNanos > lastCropActionNanos) {
            maximumCropActionGapNanos = maxOf(maximumCropActionGapNanos, nowNanos - lastCropActionNanos)
        }
        lastCropActionNanos = nowNanos
        hasCropAction = true
    }

    /**
     * Adds the pest interval when crop activity remained recent throughout it. Pest cooldowns normally
     * exceed the AFK threshold, so the threshold applies to gaps between crop actions, not to the pest
     * interval itself. The current spawn becomes the next anchor even when its sample is excluded.
     */
    fun onSpawn(nowNanos: Long, afkGapLimitNanos: Long, excludeAfk: Boolean): Boolean =
        onSpawn(nowNanos, afkGapLimitNanos, excludeAfk, true)

    /** An offline spawn updates the anchor but cannot contribute an active-farming sample. */
    fun onSpawn(
        nowNanos: Long,
        afkGapLimitNanos: Long,
        excludeAfk: Boolean,
        eligible: Boolean,
    ): Boolean {
        if (!hasLastSpawn) {
            setSpawnAnchor(nowNanos, eligible)
            return false
        }
        val previous = lastSpawnNanos
        val previousEligible = lastSpawnEligible
        val intervalHasCropAction = hasCropAction
        val intervalLastCropActionNanos = lastCropActionNanos
        val intervalMaximumCropActionGapNanos = maximumCropActionGapNanos
        setSpawnAnchor(nowNanos, eligible)
        if (nowNanos <= previous || !eligible || !previousEligible) return false
        val interval = nowNanos - previous
        val limit = maxOf(1L, afkGapLimitNanos)
        if (excludeAfk && (!intervalHasCropAction || intervalLastCropActionNanos > nowNanos ||
                nowNanos - intervalLastCropActionNanos > limit || intervalMaximumCropActionGapNanos > limit)
        ) return false
        intervalsNanos.addLast(interval)
        while (intervalsNanos.size > MAX_SAMPLES) intervalsNanos.removeFirst()
        return true
    }

    private fun setSpawnAnchor(nowNanos: Long, eligible: Boolean) {
        lastSpawnNanos = nowNanos
        hasLastSpawn = true
        lastSpawnEligible = eligible
        lastCropActionNanos = 0L
        hasCropAction = false
        maximumCropActionGapNanos = 0L
    }

    fun averageSeconds(): Double {
        if (intervalsNanos.isEmpty()) return 0.0
        var sum = 0.0
        for (interval in intervalsNanos) sum += interval.toDouble()
        return sum / intervalsNanos.size / 1_000_000_000.0
    }

    fun sampleCount(): Int = intervalsNanos.size

    fun lastSpawnNanos(): Long = lastSpawnNanos

    fun lastCropActionNanos(): Long = lastCropActionNanos

    fun hasSpawn(): Boolean = hasLastSpawn

    fun reset() {
        lastSpawnNanos = 0L
        lastCropActionNanos = 0L
        maximumCropActionGapNanos = 0L
        hasLastSpawn = false
        hasCropAction = false
        lastSpawnEligible = false
        intervalsNanos.clear()
    }

    companion object {
        private const val MAX_SAMPLES = 12
    }
}
