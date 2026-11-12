package geiler.addons.client.macro

/** Client-owned definitions and live values for variables shared by every macro run. */
class MacroVariableStore {
    private val implementation: MacroVariableStoreSupport

    constructor() : this(null)

    constructor(onConfigChanged: Runnable?) {
        implementation = MacroVariableStoreSupport(onConfigChanged)
    }

    @Synchronized
    fun definitions(): List<Definition> = implementation.definitions()

    @Synchronized
    fun definition(id: String?): Definition? = implementation.definition(id)

    @Synchronized
    fun create(name: String?, type: MacroValue.Type?): Definition? = implementation.create(name, type)

    @Synchronized
    fun remove(id: String?): Boolean = implementation.remove(id)

    @Synchronized
    fun rename(id: String?, name: String?): Boolean = implementation.rename(id, name)

    @Synchronized
    fun setType(id: String?, type: MacroValue.Type?): Boolean = implementation.setType(id, type)

    @Synchronized
    fun setPersistValue(id: String?, persist: Boolean): Boolean = implementation.setPersistValue(id, persist)

    @Synchronized
    fun value(id: String?): Any? = implementation.value(id)

    @Synchronized
    fun setValue(id: String?, value: Any?): Boolean = implementation.setValue(id, value)

    @Synchronized
    fun savedVariables(): List<SavedVariable> = implementation.savedVariables()

    @Synchronized
    fun restore(saved: Collection<SavedVariable?>?) = implementation.restore(saved)

    @Synchronized
    fun importDefinitions(imported: Collection<Definition?>?) = implementation.importDefinitions(imported)

    data class Definition private constructor(
        private val idValue: String,
        private val nameValue: String,
        private val typeValue: MacroValue.Type,
        private val persistValueValue: Boolean,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) {
        constructor(id: String?, name: String?, type: MacroValue.Type?, persistValue: Boolean) : this(
            kotlinPlatformValue(id), kotlinPlatformValue(name),
            MacroVariableStoreSupport.normalizeDefinitionType(type), persistValue, Unit,
        )

        fun id(): String = idValue
        fun name(): String = nameValue
        fun type(): MacroValue.Type = typeValue
        fun persistValue(): Boolean = persistValueValue

        override fun toString(): String = macroRecordString(
            "Definition", "id" to id(), "name" to name(), "type" to type(), "persistValue" to persistValue(),
        )
    }

    data class SavedVariable(private val definitionValue: Definition?, private val savedValueValue: String?) {
        fun definition(): Definition? = definitionValue
        fun savedValue(): String? = savedValueValue

        override fun toString(): String = macroRecordString(
            "SavedVariable", "definition" to definition(), "savedValue" to savedValue(),
        )
    }

    companion object {
        const val MAX_VARIABLES: Int = 256
    }
}
