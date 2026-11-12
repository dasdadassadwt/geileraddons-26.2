package geiler.addons.client.macro

/** User-authored reusable block stack. Each call receives an isolated variable frame. */
class MacroFunction {
    private val implementation: MacroFunctionSupport

    constructor() : this(null)

    constructor(id: String?) {
        implementation = MacroFunctionSupport(id)
    }

    fun id(): String = implementation.id()
    fun name(): String = implementation.name()
    fun setName(value: String?) = implementation.setName(value)
    fun parameters(): MutableList<Parameter?> = implementation.parameters()
    fun steps(): MutableList<MacroStep> = implementation.steps()

    /** Builds isolated, type-coerced locals for one function invocation. */
    fun bindArguments(arguments: List<MacroValue?>?, callerVariables: Map<String, Any?>?): MutableMap<String, Any?> =
        implementation.bindArguments(arguments, callerVariables)

    /** Builds function locals while allowing arguments to read the shared global-variable store. */
    fun bindArguments(
        arguments: List<MacroValue?>?,
        callerVariables: Map<String, Any?>?,
        globals: MacroVariableStore?,
    ): MutableMap<String, Any?> = implementation.bindArguments(arguments, callerVariables, globals)

    fun canvasX(): Float = implementation.canvasX()
    fun canvasY(): Float = implementation.canvasY()
    fun setCanvasPosition(x: Float, y: Float) = implementation.setCanvasPosition(x, y)

    data class Parameter private constructor(
        private val nameValue: String,
        private val typeValue: MacroValue.Type,
        private val defaultValueValue: String,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) {
        constructor(name: String?, type: MacroValue.Type?, defaultValue: String?) : this(
            MacroFunctionSupport.normalizeParameterName(name),
            MacroFunctionSupport.normalizeParameterType(type),
            MacroFunctionSupport.normalizeParameterDefault(defaultValue),
            Unit,
        )

        fun name(): String = nameValue
        fun type(): MacroValue.Type = typeValue
        fun defaultValue(): String = defaultValueValue

        override fun toString(): String = macroRecordString(
            "Parameter", "name" to name(), "type" to type(), "defaultValue" to defaultValue(),
        )
    }

    companion object {
        const val MAX_FUNCTIONS: Int = 256
    }
}
