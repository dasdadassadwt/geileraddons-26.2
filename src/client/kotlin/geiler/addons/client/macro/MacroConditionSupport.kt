package geiler.addons.client.macro

import java.util.Locale

/** Kotlin normalization and helper behavior behind MacroCondition's Java record ABI. */
object MacroConditionSupport {
    @JvmStatic
    fun itemScope(scope: MacroCondition.ItemScope?): MacroCondition.ItemScope =
        scope ?: MacroCondition.ItemScope.CONTAINER

    @JvmStatic
    fun includesPlayerSlot(scope: MacroCondition.ItemScope, playerSlot: Boolean): Boolean = when (scope) {
        MacroCondition.ItemScope.CONTAINER -> !playerSlot
        MacroCondition.ItemScope.PLAYER_INVENTORY -> playerSlot
        MacroCondition.ItemScope.CONTAINER_AND_PLAYER -> true
    }

    @JvmStatic
    fun itemScopeSerializedName(scope: MacroCondition.ItemScope): String = when (scope) {
        MacroCondition.ItemScope.CONTAINER -> "container"
        MacroCondition.ItemScope.PLAYER_INVENTORY -> "player"
        MacroCondition.ItemScope.CONTAINER_AND_PLAYER -> "all"
    }

    @JvmStatic
    fun itemScopeFromSerialized(value: String?, fallback: MacroCondition.ItemScope?): MacroCondition.ItemScope? {
        if (value == null) return fallback
        val normalized = value.lowercase(Locale.ROOT)
        return MacroCondition.ItemScope.values().firstOrNull { it.serializedName() == normalized } ?: fallback
    }

    @JvmStatic
    fun itemScopeFromLegacy(includePlayerInventory: Boolean): MacroCondition.ItemScope =
        if (includePlayerInventory) MacroCondition.ItemScope.CONTAINER_AND_PLAYER else MacroCondition.ItemScope.CONTAINER

    @JvmStatic
    fun itemMatches(mustExist: Boolean, found: Boolean): Boolean = found == mustExist

    @JvmStatic
    fun cleanId(value: String?): String {
        val clean = MacroJavaStrings.strip(value) ?: return ""
        return clean.substring(0, minOf(128, clean.length))
    }

    @JvmStatic
    fun inventoryOperator(value: MacroCondition.Variable.Operator?): MacroCondition.Variable.Operator =
        value ?: MacroCondition.Variable.Operator.GREATER_OR_EQUAL

    @JvmStatic
    fun inventoryThreshold(value: Int): Int = value.coerceIn(0, 999_999)

    @JvmStatic
    fun variableName(value: String?): String = if (value == null) "value" else MacroJavaStrings.strip(value)!!

    @JvmStatic
    fun variableOperator(value: MacroCondition.Variable.Operator?): MacroCondition.Variable.Operator =
        value ?: MacroCondition.Variable.Operator.EQUALS

    @JvmStatic
    fun variableValue(value: MacroValue?): MacroValue = value ?: MacroValue.literal(MacroValue.Type.TEXT, "")

    @JvmStatic
    fun globalVariableId(value: String?): String? = if (MacroJavaStrings.isBlank(value)) null
        else value!!.substring(0, minOf(64, value.length))

    @JvmStatic
    fun comparesGlobal(id: String?): Boolean = id != null

    @JvmStatic
    fun hypixelField(value: MacroCondition.Hypixel.Field?): MacroCondition.Hypixel.Field =
        value ?: MacroCondition.Hypixel.Field.ISLAND

    @JvmStatic
    fun hypixelOperator(value: MacroCondition.Hypixel.Operator?): MacroCondition.Hypixel.Operator =
        value ?: MacroCondition.Hypixel.Operator.EQUALS

    @JvmStatic
    fun hypixelExpected(value: String?): String = value?.substring(0, minOf(128, value.length)) ?: ""

    @JvmStatic
    fun hypixelArgument(value: String?): String = value?.substring(0, minOf(32, value.length)) ?: ""

    @JvmStatic
    fun playerArgument(value: String?): String = value?.substring(0, minOf(64, value.length)) ?: ""

    @JvmStatic
    fun hypixelPlotId(value: Int): Int = if (value < -1 || value > 24) -1 else value

    @JvmStatic
    fun immutableChildren(children: List<MacroCondition>?): List<MacroCondition> =
        java.util.List.copyOf(children ?: emptyList())
}
