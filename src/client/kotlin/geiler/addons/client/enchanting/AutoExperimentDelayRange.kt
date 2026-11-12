package geiler.addons.client.enchanting

import java.util.function.LongUnaryOperator

/** Normalized, inclusive delay range used between successful Auto Experiments clicks. */
class AutoExperimentDelayRange private constructor(
    private val minimumMillisValue: Int,
    private val maximumMillisValue: Int,
    @Suppress("UNUSED_PARAMETER") normalized: Unit,
) {
    constructor(minimumMillis: Int, maximumMillis: Int) : this(
        AutoExperimentDelayRangeSupport.normalize(minimumMillis, maximumMillis),
        Unit,
    )

    private constructor(normalized: IntArray, marker: Unit) : this(normalized[0], normalized[1], marker)

    fun minimumMillis(): Int = minimumMillisValue

    fun maximumMillis(): Int = maximumMillisValue

    /** Selects one value from the normalized inclusive range. */
    fun sampleMillis(boundedRandom: LongUnaryOperator?): Int =
        AutoExperimentDelayRangeSupport.sampleMillis(minimumMillisValue, maximumMillisValue, boundedRandom)

    fun sampleNanos(boundedRandom: LongUnaryOperator?): Long =
        AutoExperimentDelayRangeSupport.sampleNanos(minimumMillisValue, maximumMillisValue, boundedRandom)

    override fun equals(other: Any?): Boolean = other is AutoExperimentDelayRange
        && minimumMillisValue == other.minimumMillisValue
        && maximumMillisValue == other.maximumMillisValue

    override fun hashCode(): Int = 31 * minimumMillisValue + maximumMillisValue

    override fun toString(): String =
        "AutoExperimentDelayRange[minimumMillis=$minimumMillisValue, maximumMillis=$maximumMillisValue]"

    companion object {
        const val MINIMUM_MILLIS: Int = 0
        const val MAXIMUM_MILLIS: Int = 2_000
        const val DEFAULT_MINIMUM_MILLIS: Int = 250
        const val DEFAULT_MAXIMUM_MILLIS: Int = 350

        /** Preserves an old single delay as the lower endpoint and widens it by 70 ms. */
        @JvmStatic
        fun fromLegacyDelay(legacyDelayMillis: Float): AutoExperimentDelayRange =
            AutoExperimentDelayRangeSupport.fromLegacyDelay(legacyDelayMillis)

        /** Restores new endpoint values first, using an old saved single delay for missing endpoints. */
        @JvmStatic
        fun restore(
            legacyDelayMillis: Float?,
            savedMinimumMillis: Float?,
            savedMaximumMillis: Float?,
            currentMinimumMillis: Int,
            currentMaximumMillis: Int,
        ): AutoExperimentDelayRange = AutoExperimentDelayRangeSupport.restore(
            legacyDelayMillis,
            savedMinimumMillis,
            savedMaximumMillis,
            currentMinimumMillis,
            currentMaximumMillis,
        )
    }
}
