package geiler.addons.client.macro

import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

/** Kotlin state and behavior behind MacroVariableStore's Java record/API surface. */
class MacroVariableStoreSupport(onConfigChanged: Runnable?) {
    companion object {
        const val MAX_VARIABLES = 256
        const val MAX_ID_LENGTH = 64
        const val MAX_NAME_LENGTH = 32

        @JvmStatic
        fun normalizeDefinitionType(type: MacroValue.Type?): MacroValue.Type = type ?: MacroValue.Type.TEXT
    }

    private val onConfigChangedValue = onConfigChanged ?: Runnable { }
    private val definitionsValue = LinkedHashMap<String, MacroVariableStore.Definition>()
    private val valuesValue = LinkedHashMap<String, Any>()

    fun definitions(): List<MacroVariableStore.Definition> = java.util.List.copyOf(definitionsValue.values)

    fun definition(id: String?): MacroVariableStore.Definition? = if (id == null) null else definitionsValue[id]

    /** Creates a globally shared variable with a stable id and the type's default live value. */
    fun create(name: String?, type: MacroValue.Type?): MacroVariableStore.Definition? {
        if (definitionsValue.size >= MAX_VARIABLES) return null
        val cleanName = cleanName(name)
        if (cleanName.isEmpty() || nameExists(cleanName, null)) return null
        val definition = MacroVariableStore.Definition(UUID.randomUUID().toString(), cleanName, type, false)
        definitionsValue[definition.id()] = definition
        valuesValue[definition.id()] = MacroValue.defaultValue(definition.type())
        onConfigChangedValue.run()
        return definition
    }

    fun remove(id: String?): Boolean {
        if (id == null || definitionsValue.remove(id) == null) return false
        valuesValue.remove(id)
        onConfigChangedValue.run()
        return true
    }

    fun rename(id: String?, name: String?): Boolean {
        val current = definition(id)
        val cleanName = cleanName(name)
        if (current == null || cleanName.isEmpty() || nameExists(cleanName, id)) return false
        definitionsValue[id!!] = MacroVariableStore.Definition(current.id(), cleanName, current.type(), current.persistValue())
        onConfigChangedValue.run()
        return true
    }

    fun setType(id: String?, type: MacroValue.Type?): Boolean {
        val current = definition(id) ?: return false
        val cleanType = type ?: MacroValue.Type.TEXT
        if (current.type() == cleanType) return true
        definitionsValue[id!!] = MacroVariableStore.Definition(current.id(), current.name(), cleanType, current.persistValue())
        valuesValue[id] = MacroValue.defaultValue(cleanType)
        onConfigChangedValue.run()
        return true
    }

    fun setPersistValue(id: String?, persist: Boolean): Boolean {
        val current = definition(id) ?: return false
        if (current.persistValue() != persist) {
            definitionsValue[id!!] = MacroVariableStore.Definition(current.id(), current.name(), current.type(), persist)
            onConfigChangedValue.run()
        }
        return true
    }

    fun value(id: String?): Any? {
        val definition = definition(id) ?: return null
        return valuesValue[id] ?: MacroValue.defaultValue(definition.type())
    }

    /** Stores a type-coerced value; local and imported definitions live only in client memory. */
    fun setValue(id: String?, value: Any?): Boolean {
        val definition = definition(id) ?: return false
        val cleanValue = coerce(definition.type(), value)
        val previous = valuesValue.put(id!!, cleanValue)
        if (definition.persistValue() && cleanValue != previous) onConfigChangedValue.run()
        return true
    }

    /** Returns only opted-in values, keeping all other runtime values out of local config. */
    fun savedVariables(): List<MacroVariableStore.SavedVariable> {
        val result = ArrayList<MacroVariableStore.SavedVariable>(definitionsValue.size)
        for (definition in definitionsValue.values) {
            val savedValue = if (definition.persistValue()) value(definition.id())!!.toString() else null
            result.add(MacroVariableStore.SavedVariable(definition, savedValue))
        }
        return java.util.List.copyOf(result)
    }

    /** Replaces all definitions from local config. Non-persistent values restart at their type defaults. */
    fun restore(saved: Collection<MacroVariableStore.SavedVariable?>?) {
        definitionsValue.clear()
        valuesValue.clear()
        if (saved == null) return
        for (entry in saved) {
            if (definitionsValue.size >= MAX_VARIABLES || entry == null || entry.definition() == null) break
            var definition = sanitize(entry.definition()) ?: continue
            val id = definition.id()
            if (definitionsValue.containsKey(id)) continue
            if (nameExists(definition.name(), null)) {
                definition = MacroVariableStore.Definition(id, uniqueName(definition.name()), definition.type(), definition.persistValue())
            }
            definitionsValue[id] = definition
            val value = if (definition.persistValue() && entry.savedValue() != null) {
                coerce(definition.type(), entry.savedValue())
            } else {
                MacroValue.defaultValue(definition.type())
            }
            valuesValue[id] = value
        }
    }

    /** Adds package definitions without importing any live values or persistence preference. */
    fun importDefinitions(imported: Collection<MacroVariableStore.Definition?>?) {
        if (imported == null) return
        for (candidate in imported) {
            if (definitionsValue.size >= MAX_VARIABLES) break
            val clean = sanitize(candidate) ?: continue
            val id = clean.id()
            if (definitionsValue.containsKey(id)) continue
            val name = if (nameExists(clean.name(), null)) uniqueName(clean.name()) else clean.name()
            val definition = MacroVariableStore.Definition(id, name, clean.type(), false)
            definitionsValue[id] = definition
            valuesValue[id] = MacroValue.defaultValue(definition.type())
            onConfigChangedValue.run()
        }
    }

    private fun nameExists(name: String, exceptId: String?): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        for (definition in definitionsValue.values) {
            if (definition.id() != exceptId && definition.name().lowercase(Locale.ROOT) == normalized) return true
        }
        return false
    }

    private fun uniqueName(base: String): String {
        for (suffix in 2 until 10_000) {
            val end = " $suffix"
            val candidate = base.substring(0, minOf(base.length, MAX_NAME_LENGTH - end.length)) + end
            if (!nameExists(candidate, null)) return candidate
        }
        return UUID.randomUUID().toString().substring(0, MAX_NAME_LENGTH)
    }

    private fun cleanName(value: String?): String {
        val clean = MacroJavaStrings.strip(value) ?: return ""
        return clean.substring(0, minOf(MAX_NAME_LENGTH, clean.length))
    }

    private fun sanitize(source: MacroVariableStore.Definition?): MacroVariableStore.Definition? {
        val id = source?.id()
        if (MacroJavaStrings.isBlank(id) || id!!.length > MAX_ID_LENGTH) return null
        val name = cleanName(source.name())
        if (name.isEmpty()) return null
        return MacroVariableStore.Definition(id, name, source.type(), source.persistValue())
    }

    private fun coerce(type: MacroValue.Type?, value: Any?): Any {
        if (value == null) return MacroValue.defaultValue(type)
        return when (type ?: MacroValue.Type.TEXT) {
            MacroValue.Type.NUMBER, MacroValue.Type.ITEM_COUNT -> {
                val number = try {
                    if (value is Number) value.toDouble() else java.lang.Double.parseDouble(MacroJavaStrings.trim(value.toString())!!)
                } catch (_: RuntimeException) {
                    0.0
                }
                if (number.isFinite()) number else 0.0
            }
            MacroValue.Type.BOOLEAN -> if (value is Boolean) value else value.toString().toBoolean()
            MacroValue.Type.TEXT -> value.toString().substring(0, minOf(256, value.toString().length))
        }
    }

}
