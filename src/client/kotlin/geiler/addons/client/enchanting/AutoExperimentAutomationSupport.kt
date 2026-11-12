package geiler.addons.client.enchanting

import geiler.addons.client.enchanting.AutoExperimentAutomation.Action
import java.util.function.LongUnaryOperator

/** Kotlin validation used by the Java-facing constructors retained for caller compatibility. */
object AutoExperimentAutomationSupport {
    @JvmStatic
    fun normalizeStatus(status: String?): String = status?.trim { it <= ' ' } ?: ""

    @JvmStatic
    fun nonNegative(value: Int): Int = maxOf(0, value)

    @JvmStatic
    fun atLeastMinusOne(value: Int): Int = maxOf(-1, value)

    @JvmStatic
    fun requireAction(action: Action?): Action =
        action ?: throw IllegalArgumentException("action must not be null")

    @JvmStatic
    fun normalizeExplanation(explanation: String?): String = ExperimentTextSupport.emptyIfNull(explanation)
}

/** Delay-range implementation behind the Java-compatible public API. */
object AutoExperimentDelayRangeSupport {
    private const val LEGACY_RANGE_MILLIS = 70

    /** Clamps endpoints, then orders them before the public value stores them. */
    @JvmStatic
    fun normalize(minimumMillis: Int, maximumMillis: Int): IntArray {
        var minimum = clamp(minimumMillis)
        var maximum = clamp(maximumMillis)
        if (minimum > maximum) {
            val swap = minimum
            minimum = maximum
            maximum = swap
        }
        return intArrayOf(minimum, maximum)
    }

    /** Preserves an old single delay as the lower endpoint and widens it by 70 ms. */
    @JvmStatic
    fun fromLegacyDelay(legacyDelayMillis: Float): AutoExperimentDelayRange {
        if (!legacyDelayMillis.isFinite()) {
            return AutoExperimentDelayRange(
                AutoExperimentDelayRange.DEFAULT_MINIMUM_MILLIS,
                AutoExperimentDelayRange.DEFAULT_MAXIMUM_MILLIS,
            )
        }
        val minimum = clamp(Math.round(legacyDelayMillis))
        val maximum = minOf(AutoExperimentDelayRange.MAXIMUM_MILLIS, minimum + LEGACY_RANGE_MILLIS)
        return AutoExperimentDelayRange(minimum, maximum)
    }

    /** Restores new endpoint values first, using an old saved single delay for missing endpoints. */
    @JvmStatic
    fun restore(
        legacyDelayMillis: Float?,
        savedMinimumMillis: Float?,
        savedMaximumMillis: Float?,
        currentMinimumMillis: Int,
        currentMaximumMillis: Int,
    ): AutoExperimentDelayRange {
        val fallback = if (legacyDelayMillis == null) {
            val current = normalize(currentMinimumMillis, currentMaximumMillis)
            AutoExperimentDelayRange(current[0], current[1])
        } else {
            fromLegacyDelay(legacyDelayMillis)
        }
        val minimum = restoredValue(savedMinimumMillis, fallback.minimumMillis())
        val maximum = restoredValue(savedMaximumMillis, fallback.maximumMillis())
        return AutoExperimentDelayRange(minimum, maximum)
    }

    /** Selects one value from the normalized inclusive range. */
    @JvmStatic
    fun sampleMillis(minimumMillis: Int, maximumMillis: Int, boundedRandom: LongUnaryOperator?): Int {
        if (boundedRandom == null) throw IllegalArgumentException("boundedRandom must not be null")
        val width = maximumMillis.toLong() - minimumMillis + 1L
        val offset = boundedRandom.applyAsLong(width)
        if (offset < 0 || offset >= width) {
            throw IllegalArgumentException("random offset must be within the requested bound")
        }
        return minimumMillis + offset.toInt()
    }

    @JvmStatic
    fun sampleNanos(minimumMillis: Int, maximumMillis: Int, boundedRandom: LongUnaryOperator?): Long =
        sampleMillis(minimumMillis, maximumMillis, boundedRandom) * 1_000_000L

    private fun clamp(value: Int): Int =
        maxOf(AutoExperimentDelayRange.MINIMUM_MILLIS, minOf(AutoExperimentDelayRange.MAXIMUM_MILLIS, value))

    private fun restoredValue(savedValue: Float?, fallback: Int): Int =
        if (savedValue == null || !savedValue.isFinite()) fallback else clamp(Math.round(savedValue))
}
