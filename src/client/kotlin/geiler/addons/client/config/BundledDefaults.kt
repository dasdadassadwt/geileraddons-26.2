package geiler.addons.client.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import geiler.addons.client.hud.HudManager
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleManager
import geiler.addons.client.module.Workspace

/**
 * Settings and HUD positions a new player starts with, laid over the code defaults. They are used only when there is no
 * config file yet and after a reset to defaults; a saved config is never changed by them. Enabled states, texts and
 * keybinds are never part of the file.
 */
object BundledDefaults {
    private const val RESOURCE = "/geileraddons/default-config.json"
    private const val MAX_BYTES = 262_144

    private val root: JsonObject by lazy {
        try {
            BundledDefaults::class.java.getResourceAsStream(RESOURCE)?.use {
                JsonParser.parseString(String(it.readNBytes(MAX_BYTES), Charsets.UTF_8)).asJsonObject
            } ?: JsonObject()
        } catch (error: RuntimeException) {
            // A broken defaults file must not stop the client; the code defaults still work.
            GeilerAddons.LOGGER.error("Could not read the bundled default settings", error)
            JsonObject()
        }
    }

    @JvmStatic
    fun isFreshInstall(configExists: Boolean, legacyConfigExists: Boolean): Boolean = !configExists && !legacyConfigExists

    fun sections(): Set<String> = root.keySet()

    fun keys(section: String): Set<String> = root.getAsJsonObject(section)?.keySet() ?: emptySet()

    private fun value(section: String, module: Module, setting: String) =
        root.getAsJsonObject(section)?.get(ConfigSettingKeys.settingKey(module, setting))

    /** Lays the bundled values over [module]'s settings. */
    fun apply(module: Module) {
        for (setting in module.colorSettings()) {
            val rgba = value("colors", module, setting.name())?.asJsonArray ?: continue
            if (rgba.size() == 4) setting.set(rgba[0].asInt, rgba[1].asInt, rgba[2].asInt, rgba[3].asInt)
        }
        for (setting in module.numberSettings()) {
            value("numbers", module, setting.name())?.let { setting.setValue(it.asFloat) }
        }
        for (setting in module.booleanSettings()) {
            value("toggles", module, setting.name())?.let { setting.setValue(it.asBoolean) }
        }
        for (setting in module.choiceSettings()) {
            value("choices", module, setting.name())?.let { setting.setValue(it.asString) }
        }
    }

    fun applyHudPositions() {
        val positions = root.getAsJsonObject("hudPositions") ?: return
        HudManager.restore(
            positions.entrySet().associate { (id, value) ->
                id to value.asJsonArray.map { it.asFloat }.toFloatArray()
            },
        )
    }

    /** Everything, for a first start. */
    fun applyAll() {
        for (module in ModuleManager.modules()) {
            if (module.category().workspace == Workspace.NORMAL) apply(module)
        }
        applyHudPositions()
    }
}
