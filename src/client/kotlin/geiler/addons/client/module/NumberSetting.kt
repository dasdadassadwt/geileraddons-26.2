package geiler.addons.client.module

import kotlin.math.roundToInt

class NumberSetting private constructor(
    private val settingName: String,
    private val label: String,
    private val minimum: Float,
    private val maximum: Float,
    defaultValue: Float,
    private val integer: Boolean,
    private val textInput: Boolean,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : Setting {
    private val defaultValue = if (defaultValue.isFinite()) clamp(defaultValue) else minimum
    private var currentValue = this.defaultValue

    init {
        require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum) {
            "Invalid number setting range"
        }
    }

    constructor(name: String, min: Float, max: Float, defaultValue: Float) :
        this(name, name, min, max, defaultValue, false, max > 50.0f, Unit)

    constructor(name: String, min: Float, max: Float, defaultValue: Float, integer: Boolean) :
        this(name, name, min, max, defaultValue, integer, max > 50.0f, Unit)

    constructor(name: String, displayName: String, min: Float, max: Float, defaultValue: Float) :
        this(name, displayName, min, max, defaultValue, false, max > 50.0f, Unit)

    constructor(
        name: String,
        displayName: String,
        min: Float,
        max: Float,
        defaultValue: Float,
        integer: Boolean,
    ) : this(name, displayName, min, max, defaultValue, integer, max > 50.0f, Unit)

    constructor(
        name: String,
        displayName: String,
        min: Float,
        max: Float,
        defaultValue: Float,
        integer: Boolean,
        textInput: Boolean,
    ) : this(name, displayName, checkedMin(min, max), checkedMax(min, max), defaultValue, integer, textInput, Unit)

    override fun name(): String = settingName

    override fun displayName(): String = label

    fun value(): Float = currentValue

    fun intValue(): Int = currentValue.roundToInt()

    fun isInteger(): Boolean = integer

    fun min(): Float = minimum

    fun max(): Float = maximum

    fun reset() {
        currentValue = defaultValue
    }

    /** Large ranges are faster and more precise to edit directly than to drag across a slider. */
    fun prefersTextInput(): Boolean = textInput

    fun setValue(value: Float) {
        if (!value.isFinite()) return
        currentValue = clamp(value)
    }

    /** 0-1 position along the slider track. */
    fun fraction(): Float = if (maximum == minimum) 0f else (currentValue - minimum) / (maximum - minimum)

    fun setFraction(fraction: Float) {
        if (!fraction.isFinite()) return
        val bounded = fraction.coerceIn(0f, 1f)
        currentValue = clamp(minimum + (maximum - minimum) * bounded)
    }

    /** Slider read-out text - whole-number settings avoid a decimal suffix. */
    fun display(): String = if (integer) intValue().toString() else String.format("%.2f", currentValue)

    private fun clamp(value: Float): Float = value.coerceIn(minimum, maximum).let {
        if (integer) it.roundToInt().toFloat() else it
    }

    companion object {
        private fun checkedMin(min: Float, max: Float): Float {
            require(min.isFinite() && max.isFinite() && min <= max) { "Invalid number setting range" }
            return min
        }

        private fun checkedMax(min: Float, max: Float): Float {
            require(min.isFinite() && max.isFinite() && min <= max) { "Invalid number setting range" }
            return max
        }
    }
}
