package geiler.addons.client.macro

import java.util.HashMap
import java.util.UUID

/** Kotlin implementation behind MacroFunction's preserved Java API. */
class MacroFunctionSupport(id: String?) {
    private val idValue: String = if (MacroJavaStrings.isBlank(id)) UUID.randomUUID().toString() else id!!
    private var nameValue = "My Block"
    private val parametersValue = ArrayList<MacroFunction.Parameter?>()
    private val stepsValue = ArrayList<MacroStep>()
    private var canvasXValue = 0.0f
    private var canvasYValue = 0.0f

    fun id(): String = idValue
    fun name(): String = nameValue

    fun setName(value: String?) {
        val clean = MacroJavaStrings.strip(value) ?: ""
        nameValue = if (clean.isEmpty()) "My Block" else clean.substring(0, minOf(48, clean.length))
    }

    fun parameters(): MutableList<MacroFunction.Parameter?> = parametersValue
    fun steps(): MutableList<MacroStep> = stepsValue

    /** Builds isolated, type-coerced locals for one function invocation. */
    fun bindArguments(arguments: List<MacroValue?>?, callerVariables: Map<String, Any?>?): MutableMap<String, Any?> =
        bindArguments(arguments, callerVariables, null)

    /** Builds function locals while allowing arguments to read the shared global-variable store. */
    fun bindArguments(
        arguments: List<MacroValue?>?,
        callerVariables: Map<String, Any?>?,
        globals: MacroVariableStore?,
    ): MutableMap<String, Any?> {
        val locals = HashMap<String, Any?>()
        for (index in parametersValue.indices) {
            val parameter = parametersValue[index] ?: continue
            val argument = arguments?.getOrNull(index) ?: MacroValue.literal(parameter.type(), parameter.defaultValue())
            val typedArgument = MacroValue(parameter.type(), argument.variableReference(), argument.value(), argument.scope())
            locals[parameter.name()] = typedArgument.resolve(callerVariables, globals)
        }
        return locals
    }

    fun canvasX(): Float = canvasXValue
    fun canvasY(): Float = canvasYValue

    fun setCanvasPosition(x: Float, y: Float) {
        canvasXValue = if (x.isFinite()) x.coerceIn(-100_000.0f, 100_000.0f) else 0.0f
        canvasYValue = if (y.isFinite()) y.coerceIn(-100_000.0f, 100_000.0f) else 0.0f
    }

    companion object {
        @JvmStatic
        fun normalizeParameterName(name: String?): String {
            val clean = if (name == null) "value" else MacroJavaStrings.strip(name)!!
            val bounded = clean.substring(0, minOf(32, clean.length))
            return if (bounded.isEmpty()) "value" else bounded
        }

        @JvmStatic
        fun normalizeParameterType(type: MacroValue.Type?): MacroValue.Type = type ?: MacroValue.Type.TEXT

        @JvmStatic
        fun normalizeParameterDefault(value: String?): String = value?.substring(0, minOf(128, value.length)) ?: ""
    }
}
