package geiler.addons.client.macro

/** Immutable condition tree used by If/Else and Wait-Until steps. */
sealed interface MacroCondition {
    data class Always(private val expectedValue: Boolean) : MacroCondition {
        fun expected(): Boolean = expectedValue

        override fun toString(): String = macroRecordString("Always", "expected" to expected())
    }

    data class Screen(
        private val titleValue: String?,
        private val mustBeOpenValue: Boolean,
        private val containsValue: Boolean,
    ) : MacroCondition {
        fun title(): String? = titleValue
        fun mustBeOpen(): Boolean = mustBeOpenValue
        fun contains(): Boolean = containsValue

        override fun toString(): String = macroRecordString(
            "Screen", "title" to title(), "mustBeOpen" to mustBeOpen(), "contains" to contains(),
        )
    }

    data class Slot(private val slotIdValue: Int, private val mustExistValue: Boolean) : MacroCondition {
        fun slotId(): Int = slotIdValue
        fun mustExist(): Boolean = mustExistValue

        override fun toString(): String = macroRecordString("Slot", "slotId" to slotId(), "mustExist" to mustExist())
    }

    enum class ItemScope {
        CONTAINER,
        PLAYER_INVENTORY,
        CONTAINER_AND_PLAYER;

        fun serializedName(): String = MacroConditionSupport.itemScopeSerializedName(this)

        fun includesPlayerSlot(playerSlot: Boolean): Boolean =
            MacroConditionSupport.includesPlayerSlot(this, playerSlot)

        companion object {
            @JvmStatic
            fun fromSerialized(value: String?, fallback: ItemScope?): ItemScope? =
                MacroConditionSupport.itemScopeFromSerialized(value, fallback)

            @JvmStatic
            fun fromLegacy(includePlayerInventory: Boolean): ItemScope =
                MacroConditionSupport.itemScopeFromLegacy(includePlayerInventory)
        }
    }

    data class Item private constructor(
        private val nameValue: String?,
        private val mustExistValue: Boolean,
        private val containsValue: Boolean,
        private val scopeValue: ItemScope,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) : MacroCondition {
        constructor(name: String?, mustExist: Boolean, contains: Boolean, scope: ItemScope?) : this(
            name, mustExist, contains, MacroConditionSupport.itemScope(scope), Unit,
        )

        /** Keeps source callers using the former boolean option compatible. */
        constructor(name: String?, mustExist: Boolean, contains: Boolean, includePlayerInventory: Boolean) : this(
            name, mustExist, contains, MacroConditionSupport.itemScopeFromLegacy(includePlayerInventory), Unit,
        )

        fun name(): String? = nameValue
        fun mustExist(): Boolean = mustExistValue
        fun contains(): Boolean = containsValue
        fun scope(): ItemScope = scopeValue
        fun matches(found: Boolean): Boolean = MacroConditionSupport.itemMatches(mustExist(), found)

        override fun toString(): String = macroRecordString(
            "Item", "name" to name(), "mustExist" to mustExist(), "contains" to contains(), "scope" to scope(),
        )
    }

    /** Compares a live count from carried inventory slots against a numeric threshold. */
    data class InventoryCount private constructor(
        private val skyBlockItemIdValue: String,
        private val vanillaItemIdValue: String,
        private val operatorValue: Variable.Operator,
        private val thresholdValue: Int,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) : MacroCondition {
        constructor(
            skyBlockItemId: String?,
            vanillaItemId: String?,
            operator: Variable.Operator?,
            threshold: Int,
        ) : this(
            MacroConditionSupport.cleanId(skyBlockItemId),
            MacroConditionSupport.cleanId(vanillaItemId),
            MacroConditionSupport.inventoryOperator(operator),
            MacroConditionSupport.inventoryThreshold(threshold),
            Unit,
        )

        fun skyBlockItemId(): String = skyBlockItemIdValue
        fun vanillaItemId(): String = vanillaItemIdValue
        fun operator(): Variable.Operator = operatorValue
        fun threshold(): Int = thresholdValue

        override fun toString(): String = macroRecordString(
            "InventoryCount", "skyBlockItemId" to skyBlockItemId(), "vanillaItemId" to vanillaItemId(),
            "operator" to operator(), "threshold" to threshold(),
        )
    }

    data class Chat(private val textValue: String?, private val containsValue: Boolean) : MacroCondition {
        fun text(): String? = textValue
        fun contains(): Boolean = containsValue

        override fun toString(): String = macroRecordString("Chat", "text" to text(), "contains" to contains())
    }

    data class World(private val mustBeInWorldValue: Boolean) : MacroCondition {
        fun mustBeInWorld(): Boolean = mustBeInWorldValue

        override fun toString(): String = macroRecordString("World", "mustBeInWorld" to mustBeInWorld())
    }

    data class Variable private constructor(
        private val nameValue: String,
        private val operatorValue: Operator,
        private val valueValue: MacroValue,
        private val globalVariableIdValue: String?,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) : MacroCondition {
        enum class Operator { EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL }

        constructor(name: String?, operator: Operator?, value: MacroValue?) : this(name, operator, value, null)

        constructor(name: String?, operator: Operator?, value: MacroValue?, globalVariableId: String?) : this(
            MacroConditionSupport.variableName(name),
            MacroConditionSupport.variableOperator(operator),
            MacroConditionSupport.variableValue(value),
            MacroConditionSupport.globalVariableId(globalVariableId),
            Unit,
        )

        fun name(): String = nameValue
        fun operator(): Operator = operatorValue
        fun value(): MacroValue = valueValue
        fun globalVariableId(): String? = globalVariableIdValue
        fun comparesGlobal(): Boolean = MacroConditionSupport.comparesGlobal(globalVariableId())

        override fun toString(): String = macroRecordString(
            "Variable", "name" to name(), "operator" to operator(), "value" to value(),
            "globalVariableId" to globalVariableId(),
        )
    }

    data class Hypixel private constructor(
        private val fieldValue: Field,
        private val operatorValue: Operator,
        private val expectedValue: String,
        private val argumentValue: String,
        private val plotIdValue: Int,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) : MacroCondition {
        enum class Field {
            ISLAND, DUNGEON_FLOOR, PARTY_MEMBER, PARTY_HAS_MEMBER, PARTY_SIZE, PARTY_LEADER,
            GARDEN_PEST_STATUS, GARDEN_PEST_COUNT,
        }

        enum class Operator { EQUALS, NOT_EQUALS, CONTAINS, GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL }

        constructor(field: Field?, operator: Operator?, expected: String?) : this(field, operator, expected, "", -1)

        constructor(field: Field?, operator: Operator?, expected: String?, argument: String?, plotId: Int) : this(
            MacroConditionSupport.hypixelField(field),
            MacroConditionSupport.hypixelOperator(operator),
            MacroConditionSupport.hypixelExpected(expected),
            MacroConditionSupport.hypixelArgument(argument),
            MacroConditionSupport.hypixelPlotId(plotId),
            Unit,
        )

        fun field(): Field = fieldValue
        fun operator(): Operator = operatorValue
        fun expected(): String = expectedValue
        fun argument(): String = argumentValue
        fun plotId(): Int = plotIdValue

        override fun toString(): String = macroRecordString(
            "Hypixel", "field" to field(), "operator" to operator(), "expected" to expected(),
            "argument" to argument(), "plotId" to plotId(),
        )
    }

    /**
     * Facts about the local player: where the view points, which block the player is in, and which block is where.
     * [argument] is the angle tolerance (YAW, PITCH) or the `x y z` position (BLOCK_AT).
     */
    data class Player private constructor(
        private val fieldValue: Field,
        private val operatorValue: Hypixel.Operator,
        private val expectedValue: String,
        private val argumentValue: String,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) : MacroCondition {
        enum class Field { YAW, PITCH, X, Y, Z, STANDING_BLOCK, BLOCK_AT }

        constructor(field: Field?, operator: Hypixel.Operator?, expected: String?, argument: String?) : this(
            field ?: Field.YAW,
            operator ?: Hypixel.Operator.EQUALS,
            MacroConditionSupport.hypixelExpected(expected),
            MacroConditionSupport.playerArgument(argument),
            Unit,
        )

        fun field(): Field = fieldValue
        fun operator(): Hypixel.Operator = operatorValue
        fun expected(): String = expectedValue
        fun argument(): String = argumentValue

        override fun toString(): String = macroRecordString(
            "Player", "field" to field(), "operator" to operator(), "expected" to expected(),
            "argument" to argument(),
        )
    }

    data class All private constructor(
        private val childrenValue: List<MacroCondition>,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) : MacroCondition {
        constructor(children: List<MacroCondition>?) : this(MacroConditionSupport.immutableChildren(children), Unit)

        fun children(): List<MacroCondition> = childrenValue

        override fun toString(): String = macroRecordString("All", "children" to children())
    }

    data class Any private constructor(
        private val childrenValue: List<MacroCondition>,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) : MacroCondition {
        constructor(children: List<MacroCondition>?) : this(MacroConditionSupport.immutableChildren(children), Unit)

        fun children(): List<MacroCondition> = childrenValue

        override fun toString(): String = macroRecordString("Any", "children" to children())
    }

    data class Not(private val childValue: MacroCondition?) : MacroCondition {
        fun child(): MacroCondition? = childValue

        override fun toString(): String = macroRecordString("Not", "child" to child())
    }
}
