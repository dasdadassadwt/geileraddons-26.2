package geiler.addons.client.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import geiler.addons.client.macro.MacroJavaStrings
import geiler.addons.client.macro.MacroValue
import geiler.addons.client.macro.MacroVariableStore
import java.util.Locale

/** Bounded local-config codec for global variable definitions and opted-in values. */
object MacroVariableConfigCodec {
    @JvmStatic
    fun encode(variables: List<MacroVariableStore.SavedVariable?>?): JsonArray {
        val result = JsonArray()
        if (variables == null) return result
        for (saved in variables) {
            if (result.size() >= MacroVariableStore.MAX_VARIABLES) break
            val definition = saved?.definition() ?: continue
            val objectValue = JsonObject()
            objectValue.addProperty("id", definition.id())
            objectValue.addProperty("name", definition.name())
            objectValue.addProperty("type", definition.type().name)
            objectValue.addProperty("persistValue", definition.persistValue())
            if (definition.persistValue() && saved.savedValue() != null) {
                objectValue.addProperty("savedValue", saved.savedValue())
            }
            result.add(objectValue)
        }
        return result
    }

    @JvmStatic
    fun decode(source: JsonArray?): MutableList<MacroVariableStore.SavedVariable> {
        val result = ArrayList<MacroVariableStore.SavedVariable>()
        if (source == null) return result
        for (entry in source) {
            if (result.size >= MacroVariableStore.MAX_VARIABLES) break
            if (entry == null || !entry.isJsonObject) continue
            val objectValue = entry.asJsonObject
            val id = string(objectValue, "id", "")
            val name = string(objectValue, "name", "")
            if (MacroJavaStrings.isBlank(id) || id.length > 64 || MacroJavaStrings.isBlank(name)) continue
            val definition = MacroVariableStore.Definition(id, name,
                parseType(string(objectValue, "type", "TEXT")), bool(objectValue, "persistValue", false))
            val value = if (definition.persistValue() && objectValue.has("savedValue")) {
                string(objectValue, "savedValue", "")
            } else {
                null
            }
            result.add(MacroVariableStore.SavedVariable(definition, value))
        }
        return result
    }

    private fun parseType(value: String?): MacroValue.Type = try {
        MacroValue.Type.valueOf((value ?: "").uppercase(Locale.ROOT))
    } catch (_: RuntimeException) {
        MacroValue.Type.TEXT
    }

    private fun string(objectValue: JsonObject, name: String, fallback: String): String {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try { value.asString } catch (_: RuntimeException) { fallback }
    }

    private fun bool(objectValue: JsonObject, name: String, fallback: Boolean): Boolean {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try { value.asBoolean } catch (_: RuntimeException) { fallback }
    }
}
