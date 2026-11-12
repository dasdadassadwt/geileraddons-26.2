package geiler.addons.client.macro

/** A typed literal, inventory value, or reference to a local or globally shared macro variable. */
class MacroValue(
    type: Type?,
    variableReference: Boolean,
    value: String?,
    scope: Scope?,
    skyblockItemId: String?,
    vanillaItemId: String?,
) {
    enum class Type { NUMBER, TEXT, BOOLEAN, ITEM_COUNT }
    enum class Scope { NONE, LOCAL, GLOBAL }

    private val typeValue = MacroValueSupport.normalizeType(type)
    private val variableReferenceValue = variableReference
    private val valueValue = MacroValueSupport.normalizeValue(value)
    private val scopeValue = MacroValueSupport.normalizeScope(variableReference, scope)
    private val skyblockItemIdValue = MacroValueSupport.cleanItemId(skyblockItemId)
    private val vanillaItemIdValue = MacroValueSupport.cleanItemId(vanillaItemId)

    /** Keeps the pre-global-variable constructor source and config format compatible. */
    constructor(type: Type?, variableReference: Boolean, value: String?) : this(
        type,
        variableReference,
        value,
        if (variableReference) Scope.LOCAL else Scope.NONE,
        "",
        "",
    )

    /** Keeps existing four-argument callers source compatible. */
    constructor(type: Type?, variableReference: Boolean, value: String?, scope: Scope?) : this(
        type,
        variableReference,
        value,
        scope,
        "",
        "",
    )

    fun type(): Type = typeValue
    fun variableReference(): Boolean = variableReferenceValue
    fun value(): String = valueValue
    fun scope(): Scope = scopeValue
    fun skyblockItemId(): String = skyblockItemIdValue
    fun vanillaItemId(): String = vanillaItemIdValue

    fun resolve(variables: Map<String, Any?>?): Any = MacroValueSupport.resolve(this, variables, null)

    fun resolve(variables: Map<String, Any?>?, globals: MacroVariableStore?): Any =
        MacroValueSupport.resolve(this, variables, globals)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MacroValue) return false
        return typeValue == other.typeValue
            && variableReferenceValue == other.variableReferenceValue
            && valueValue == other.valueValue
            && scopeValue == other.scopeValue
            && skyblockItemIdValue == other.skyblockItemIdValue
            && vanillaItemIdValue == other.vanillaItemIdValue
    }

    override fun hashCode(): Int {
        var result = typeValue.hashCode()
        result = 31 * result + (if (variableReferenceValue) 1231 else 1237)
        result = 31 * result + valueValue.hashCode()
        result = 31 * result + scopeValue.hashCode()
        result = 31 * result + skyblockItemIdValue.hashCode()
        result = 31 * result + vanillaItemIdValue.hashCode()
        return result
    }

    override fun toString(): String =
        "MacroValue[type=$typeValue, variableReference=$variableReferenceValue, value=$valueValue, " +
            "scope=$scopeValue, skyblockItemId=$skyblockItemIdValue, vanillaItemId=$vanillaItemIdValue]"

    companion object {
        @JvmStatic
        fun literal(type: Type?, value: String?): MacroValue = MacroValueSupport.literal(type, value)

        @JvmStatic
        fun variable(type: Type?, name: String?): MacroValue = MacroValueSupport.variable(type, name)

        /** References a global variable by stable id, so renaming it cannot break saved blocks. */
        @JvmStatic
        fun globalVariable(type: Type?, id: String?): MacroValue = MacroValueSupport.globalVariable(type, id)

        /** A live count of the matching item in the carried player inventory. */
        @JvmStatic
        fun itemCount(skyblockItemId: String?, vanillaItemId: String?): MacroValue =
            MacroValueSupport.itemCount(skyblockItemId, vanillaItemId)

        @JvmStatic
        fun defaultValue(type: Type?): Any = MacroValueSupport.defaultValue(type)
    }
}
