package geiler.addons.client.module

class BooleanSetting private constructor(
    private val settingName: String,
    private val label: String,
    private val defaultValue: Boolean,
    private val debugOnly: Boolean,
) : Setting {
    private var selected = defaultValue

    constructor(name: String, defaultValue: Boolean) : this(name, name, defaultValue, false)

    constructor(name: String, displayName: String, defaultValue: Boolean) :
        this(name, displayName, defaultValue, false)

    override fun name(): String = settingName

    override fun displayName(): String = label

    override fun isDebugOnly(): Boolean = debugOnly

    /** The effective value consumed by modules. */
    fun value(): Boolean = selected && (!debugOnly || DebugState.enabled())

    /** The user-selected value, unaffected by the diagnostic visibility gate. */
    fun rawValue(): Boolean = selected

    fun reset() {
        selected = defaultValue
    }

    fun setValue(value: Boolean) {
        selected = value
    }

    fun toggle() {
        selected = !selected
    }

    companion object {
        /** Creates a persisted toggle that is visible and effective only while Dev Debug is enabled. */
        @JvmStatic
        fun debug(name: String, defaultValue: Boolean): BooleanSetting =
            BooleanSetting(name, name, defaultValue, true)
    }
}
