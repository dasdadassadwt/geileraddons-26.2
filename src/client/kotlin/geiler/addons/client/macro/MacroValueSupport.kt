package geiler.addons.client.macro

/** Shared Kotlin normalization, factory, and resolution rules for macro values. */
object MacroValueSupport {
    @JvmStatic
    fun normalizeType(type: MacroValue.Type?): MacroValue.Type = type ?: MacroValue.Type.TEXT

    @JvmStatic
    fun normalizeValue(value: String?): String = value?.substring(0, minOf(256, value.length)) ?: ""

    @JvmStatic
    fun normalizeScope(variableReference: Boolean, scope: MacroValue.Scope?): MacroValue.Scope = when {
        !variableReference -> MacroValue.Scope.NONE
        scope == null || scope == MacroValue.Scope.NONE -> MacroValue.Scope.LOCAL
        else -> scope
    }

    @JvmStatic
    fun cleanItemId(value: String?): String {
        val clean = MacroJavaStrings.strip(value) ?: return ""
        return clean.substring(0, minOf(128, clean.length))
    }

    @JvmStatic
    fun literal(type: MacroValue.Type?, value: String?): MacroValue = MacroValue(type, false, value)

    @JvmStatic
    fun variable(type: MacroValue.Type?, name: String?): MacroValue = MacroValue(type, true, name)

    /** References a global variable by stable id, so renaming it cannot break saved blocks. */
    @JvmStatic
    fun globalVariable(type: MacroValue.Type?, id: String?): MacroValue =
        MacroValue(type, true, id, MacroValue.Scope.GLOBAL)

    /** A live count of the matching item in the carried player inventory. */
    @JvmStatic
    fun itemCount(skyblockItemId: String?, vanillaItemId: String?): MacroValue =
        MacroValue(MacroValue.Type.ITEM_COUNT, false, "", MacroValue.Scope.NONE, skyblockItemId, vanillaItemId)

    @JvmStatic
    fun defaultValue(type: MacroValue.Type?): Any = when (normalizeType(type)) {
        MacroValue.Type.NUMBER -> 0.0
        MacroValue.Type.BOOLEAN -> false
        MacroValue.Type.TEXT -> ""
        MacroValue.Type.ITEM_COUNT -> 0.0
    }

    @JvmStatic
    fun resolve(value: MacroValue, variables: Map<String, Any?>?, globals: MacroVariableStore?): Any {
        var raw = value.value()
        if (value.variableReference()) {
            val found = if (value.scope() == MacroValue.Scope.GLOBAL) globals?.value(raw)
            else variables?.get(raw)
            if (found == null) return defaultValue(value.type())
            raw = found.toString()
        }
        return when (value.type()) {
            MacroValue.Type.NUMBER -> parseNumber(raw)
            MacroValue.Type.BOOLEAN -> raw.toBoolean()
            MacroValue.Type.TEXT -> raw
            // The runtime resolves literal ITEM_COUNT values against the current carried inventory.
            MacroValue.Type.ITEM_COUNT -> if (value.variableReference()) parseNumber(raw) else 0.0
        }
    }

    private fun parseNumber(value: String?): Double {
        return try {
            val parsed = java.lang.Double.parseDouble(MacroJavaStrings.trim(value) ?: "")
            if (parsed.isFinite()) parsed else 0.0
        } catch (_: NumberFormatException) {
            0.0
        }
    }
}
