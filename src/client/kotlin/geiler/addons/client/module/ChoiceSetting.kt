package geiler.addons.client.module

import java.util.Collections

/** A setting whose value must be one of a small, fixed set of named choices. */
class ChoiceSetting private constructor(
    private val settingName: String,
    defaultValue: String,
    initialDisplayValues: Map<String, String>?,
    choices: Array<out String>,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : Setting {
    private var availableChoices: List<String> = emptyList()
    private var displayValues: Map<String, String> = emptyMap()
    private val defaultValue = defaultValue
    private var selectedValue: String? = null

    constructor(name: String, defaultValue: String, vararg choices: String) :
        this(name, defaultValue, emptyMap(), choices, Unit)

    /** Creates a choice with stable persisted values and optional player-facing labels. */
    constructor(
        name: String,
        defaultValue: String,
        displayValues: Map<String, String>?,
        vararg choices: String,
    ) : this(name, defaultValue, displayValues, choices, Unit)

    init {
        setChoices(initialDisplayValues, *choices)
        require(availableChoices.contains(defaultValue)) { "Default choice is not in the choice list" }
        selectedValue = defaultValue
    }

    override fun name(): String = settingName

    fun choices(): List<String> = availableChoices

    fun value(): String = selectedValue ?: defaultValue

    /** The UI label; persistence continues to use value(). */
    fun displayValue(): String = displayValues[value()] ?: value()

    fun index(): Int = availableChoices.indexOf(value())

    /** Replaces a dynamic catalog while keeping the selected value if it remains available. */
    fun setChoices(displayValues: Map<String, String>?, vararg choices: String) {
        require(choices.isNotEmpty()) { "A choice setting needs at least one choice" }
        val nextChoices = choices.toList()
        val nextDisplayValues = displayValues?.toMap() ?: emptyMap()
        require(nextDisplayValues.all { (key, label) ->
            key in nextChoices && !isJavaBlank(label)
        }) { "Display labels must name an existing choice and be non-empty" }
        require(nextChoices.all { !isJavaBlank(it) } && nextChoices.distinct().size == nextChoices.size) {
            "Choice names must be non-empty and unique"
        }
        availableChoices = Collections.unmodifiableList(nextChoices)
        this.displayValues = Collections.unmodifiableMap(nextDisplayValues)
        val current = selectedValue
        if (current != null && current !in nextChoices) {
            selectedValue = if (defaultValue in nextChoices) defaultValue else nextChoices.first()
        }
    }

    fun setValue(value: String?) {
        if (value != null && value in availableChoices) selectedValue = value
    }

    fun selectNext() = select(index() + 1)

    fun selectPrevious() = select(index() - 1)

    fun reset() {
        selectedValue = defaultValue
    }

    private fun select(requested: Int) {
        selectedValue = availableChoices[Math.floorMod(requested, availableChoices.size)]
    }

    private fun isJavaBlank(value: String?): Boolean =
        value == null || value.all { Character.isWhitespace(it.code) }
}
