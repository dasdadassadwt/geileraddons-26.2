package geiler.addons.client.module

class TextSetting(
    private val settingName: String,
    private val label: String,
    defaultValue: String?,
    maxLength: Int,
    private val keybindCapture: Boolean,
) : Setting {
    private val defaultValue = defaultValue ?: ""
    private val maxLength = maxLength.coerceAtLeast(0)
    private var currentValue = this.defaultValue.take(this.maxLength)

    constructor(name: String, defaultValue: String?, maxLength: Int) :
        this(name, name, defaultValue, maxLength, false)

    constructor(name: String, displayName: String, defaultValue: String?, maxLength: Int) :
        this(name, displayName, defaultValue, maxLength, false)

    override fun name(): String = settingName

    override fun displayName(): String = label

    fun value(): String = currentValue

    fun maxLength(): Int = maxLength

    fun keybindCapture(): Boolean = keybindCapture

    fun setValue(value: String?) {
        if (value != null) currentValue = value.take(maxLength)
    }

    fun intValue(fallback: Int): Int = currentValue.trim { it <= ' ' }.toIntOrNull() ?: fallback

    fun longValue(fallback: Long): Long = currentValue.trim { it <= ' ' }.toLongOrNull() ?: fallback

    fun doubleValue(fallback: Double): Double =
        currentValue.trim { it <= ' ' }.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fallback

    fun reset() {
        currentValue = defaultValue.take(maxLength)
    }
}
