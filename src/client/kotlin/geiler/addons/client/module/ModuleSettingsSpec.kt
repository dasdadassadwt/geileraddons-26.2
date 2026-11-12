package geiler.addons.client.module

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Module settings and their groups declared as data (`geileraddons/module-settings.json`). The Click GUI
 * already renders any [Setting]/[SettingGroup]; this builds them from JSON so a module declares its
 * page by listing entries and only binds behaviour to setting ids in Kotlin.
 *
 * Persisted keys are `"<module config name>.<setting name>"`, so `name` must stay stable when migrating
 * an existing module. Types: toggle, slider, dropdown, color, text.
 */
object ModuleSettingsSpec {
    private const val RESOURCE = "/geileraddons/module-settings.json"
    private const val MAX_BYTES = 262_144
    private const val MAX_SETTINGS_PER_MODULE = 200
    private const val MAX_GROUP_DEPTH = 4

    /** The settings and groups of one module, plus typed lookup by id for binding behaviour. */
    class Spec internal constructor(
        val settings: List<Setting>,
        val groups: List<SettingGroup>,
        private val byId: Map<String, Setting>,
    ) {
        private inline fun <reified T : Setting> typed(id: String): T {
            val setting = byId[id] ?: throw IllegalStateException("No setting with id '$id' in this module's data")
            return setting as? T
                ?: throw IllegalStateException("Setting '$id' is a ${setting::class.simpleName}, not ${T::class.simpleName}")
        }

        fun toggle(id: String): BooleanSetting = typed(id)
        fun slider(id: String): NumberSetting = typed(id)
        fun choice(id: String): ChoiceSetting = typed(id)
        fun color(id: String): ColorSetting = typed(id)
        fun text(id: String): TextSetting = typed(id)
    }

    private val loaded: Map<String, JsonObject> by lazy {
        val stream = ModuleSettingsSpec::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("Missing resource $RESOURCE")
        val json = stream.use { String(it.readNBytes(MAX_BYTES), Charsets.UTF_8) }
        modulesOf(json)
    }

    /** Builds a fresh [Spec] for [moduleName] (each module instance needs its own setting objects). */
    @JvmStatic
    fun of(moduleName: String): Spec {
        val entry = loaded[moduleName] ?: throw IllegalStateException("No settings data for module '$moduleName'")
        return build(moduleName, entry)
    }

    /** Parses and builds every module entry; throws [IllegalArgumentException] on any invalid data. */
    @JvmStatic
    fun parse(json: String): Map<String, Spec> =
        modulesOf(json).mapValues { (name, entry) -> build(name, entry) }

    private fun modulesOf(json: String): Map<String, JsonObject> {
        try {
            val modules = JsonParser.parseString(json).asJsonObject.getAsJsonObject("modules")
                ?: throw IllegalArgumentException("Missing \"modules\" object")
            return modules.entrySet().associate { it.key to it.value.asJsonObject }
        } catch (error: IllegalStateException) {
            throw IllegalArgumentException("Invalid module settings data: ${error.message}", error)
        } catch (error: RuntimeException) {
            if (error is IllegalArgumentException) throw error
            throw IllegalArgumentException("Invalid module settings data: ${error.message}", error)
        }
    }

    private fun build(module: String, entry: JsonObject): Spec {
        val declared = entry.getAsJsonArray("settings") ?: JsonArray()
        if (declared.size() > MAX_SETTINGS_PER_MODULE) fail(module, "more than $MAX_SETTINGS_PER_MODULE settings")
        val byId = LinkedHashMap<String, Setting>()
        for (element in declared) {
            val item = element.asObject(module)
            val id = item.string("id", module)
            if (byId.containsKey(id)) fail(module, "duplicate setting id '$id'")
            byId[id] = createSetting(module, id, item)
        }
        val groups = entry.getAsJsonArray("groups")?.map { group(module, it.asObject(module), byId, 0) } ?: emptyList()
        return Spec(byId.values.toList(), groups, byId)
    }

    private fun createSetting(module: String, id: String, item: JsonObject): Setting {
        val name = item.string("name", module)
        val label = item.get("label")?.asString ?: name
        try {
            return when (val type = item.string("type", module)) {
                "toggle" -> BooleanSetting(name, label, item.get("default")?.asBoolean ?: false)
                "slider" -> NumberSetting(
                    name, label, item.float("min", module), item.float("max", module), item.float("default", module),
                    item.get("integer")?.asBoolean ?: false,
                )
                "dropdown" -> {
                    val choices = item.getAsJsonArray("choices")?.map { it.asString }
                        ?: fail(module, "dropdown '$id' needs choices")
                    val labels = item.getAsJsonObject("labels")?.entrySet()?.associate { it.key to it.value.asString }
                    ChoiceSetting(name, item.string("default", module), labels, *choices.toTypedArray())
                }
                "color" -> {
                    val rgba = item.getAsJsonArray("default")?.map { it.asInt }
                        ?: fail(module, "color '$id' needs a default [r,g,b(,a)]")
                    when (rgba.size) {
                        3 -> ColorSetting(name, label, rgba[0], rgba[1], rgba[2])
                        4 -> ColorSetting(name, label, rgba[0], rgba[1], rgba[2], rgba[3])
                        else -> fail(module, "color '$id' default needs 3 or 4 numbers")
                    }
                }
                "text" -> TextSetting(
                    name, label, item.get("default")?.asString ?: "", item.get("maxLength")?.asInt ?: 64,
                    item.get("keybind")?.asBoolean ?: false,
                )
                else -> fail(module, "unknown setting type '$type' for '$id'")
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: RuntimeException) {
            fail(module, "setting '$id' is invalid: ${error.message}")
        }
    }

    private fun group(module: String, item: JsonObject, byId: Map<String, Setting>, depth: Int): SettingGroup {
        if (depth > MAX_GROUP_DEPTH) fail(module, "groups nest deeper than $MAX_GROUP_DEPTH levels")
        fun lookup(id: String): Setting = byId[id] ?: fail(module, "group references unknown setting '$id'")
        val toggle = item.get("toggle")?.asString?.let { lookup(it) as? BooleanSetting
            ?: fail(module, "group toggle '$it' is not a toggle") }
        val settings = item.getAsJsonArray("settings")?.map { lookup(it.asString) } ?: emptyList()
        val children = item.getAsJsonArray("groups")?.map { group(module, it.asObject(module), byId, depth + 1) }
            ?: emptyList()
        return SettingGroup(
            item.get("name")?.asString, toggle, item.get("folded")?.asBoolean ?: false, settings, children,
            item.get("debugOnly")?.asBoolean ?: false, item.get("stateKey")?.asString,
        )
    }

    private fun JsonElement.asObject(module: String): JsonObject =
        if (isJsonObject) asJsonObject else fail(module, "expected an object entry")

    private fun JsonObject.string(key: String, module: String): String =
        get(key)?.asString ?: fail(module, "missing \"$key\"")

    private fun JsonObject.float(key: String, module: String): Float =
        get(key)?.asFloat ?: fail(module, "missing \"$key\"")

    private fun fail(module: String, message: String): Nothing =
        throw IllegalArgumentException("Module '$module' settings data: $message")
}
