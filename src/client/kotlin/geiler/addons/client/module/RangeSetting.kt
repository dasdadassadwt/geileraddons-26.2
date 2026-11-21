package geiler.addons.client.module

import java.util.Random

/**
 * A slider with two thumbs: a low and a high end. It is saved through two [NumberSetting] parts that keep their own keys
 * (so a pair of old "min" / "max" settings, or a former single value, loads unchanged); [Module.numberSettings] lists the
 * parts. The parts may load out of order from a file, so [low] and [high] always read them in order.
 */
class RangeSetting @JvmOverloads constructor(
    private val label: String,
    lowKey: String,
    highKey: String,
    min: Float,
    max: Float,
    defaultLow: Float,
    defaultHigh: Float,
    integer: Boolean = false,
) : Setting {
    val lowPart = NumberSetting(lowKey, "$label (low)", min, max, minOf(defaultLow, defaultHigh), integer, false)
    val highPart = NumberSetting(highKey, "$label (high)", min, max, maxOf(defaultLow, defaultHigh), integer, false)

    override fun name(): String = label

    fun min(): Float = lowPart.min()

    fun max(): Float = lowPart.max()

    fun isInteger(): Boolean = lowPart.isInteger()

    /**
     * The low end is never above the high end. A saved low end above the high end is a leftover of the old single setting
     * (which became the high end): the high end is the one the player last set, so it wins and the range is the point.
     */
    fun low(): Float = minOf(lowPart.value(), highPart.value())

    fun high(): Float = highPart.value()

    /** The low end follows the value but stops at the high end. */
    fun setLow(value: Float) {
        if (!value.isFinite()) return
        lowPart.setValue(minOf(value, high()))
    }

    /** The high end follows the value but stops at the low end. */
    fun setHigh(value: Float) {
        if (!value.isFinite()) return
        highPart.setValue(maxOf(value, low()))
    }

    fun lowFraction(): Float = fraction(low())

    fun highFraction(): Float = fraction(high())

    fun setLowFraction(fraction: Float) {
        if (fraction.isFinite()) setLow(at(fraction))
    }

    fun setHighFraction(fraction: Float) {
        if (fraction.isFinite()) setHigh(at(fraction))
    }

    /** One draw between the ends, for every event that wants its own value. */
    fun random(rng: Random): Double {
        val lo = low().toDouble()
        val hi = high().toDouble()
        return if (hi <= lo) lo else lo + rng.nextDouble() * (hi - lo)
    }

    /** Sets both ends; they are put in order. */
    fun set(first: Float, second: Float) {
        if (!first.isFinite() || !second.isFinite()) return
        lowPart.setValue(minOf(first, second))
        highPart.setValue(maxOf(first, second))
    }

    fun reset() {
        lowPart.reset()
        highPart.reset()
    }

    /** Read-out text for the row, "3 – 4.5". */
    fun display(): String {
        fun text(v: Float) = if (isInteger()) Math.round(v).toString() else String.format(java.util.Locale.ROOT, "%.2f", v)
        return "${text(low())} – ${text(high())}"
    }

    private fun fraction(value: Float): Float = if (max() == min()) 0f else (value - min()) / (max() - min())

    private fun at(fraction: Float): Float = min() + (max() - min()) * fraction.coerceIn(0f, 1f)
}
