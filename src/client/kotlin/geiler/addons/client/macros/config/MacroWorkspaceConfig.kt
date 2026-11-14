package geiler.addons.client.macros.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.GeilerAddons
import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleKeybind
import geiler.addons.client.module.ModuleManager
import geiler.addons.client.module.Workspace
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The separate JSON file of the Macros workspace, `<config>/geileraddons/macros-workspace.json`.
 *
 * It holds what `config.json` holds for the Normal workspace - each module's enabled state, key and settings, and the
 * Click GUI view state - but only for modules of the Macros workspace, plus the active workspace and one top-level
 * key for every [Section] a macro feature registers for state that is not a module setting (rotation presets, the
 * kill list, the gate, statistics). `config.json` is never written for any of it, so the existing file keeps its shape.
 *
 * A file that exists but cannot be read, or that was written by a newer version, is left alone: loading falls back to
 * defaults and saving is switched off for the session, so a bad file is never overwritten.
 */
object MacroWorkspaceConfig {
    const val VERSION = 1
    const val FILE_NAME = "macros-workspace.json"
    private const val MAX_FILE_BYTES = 1_048_576L

    /** State of one macro feature that does not live in a module setting. */
    interface Section {
        /** The top-level key the state is saved under. */
        fun name(): String

        /** The state to save; null saves nothing. Called on the client thread. */
        fun save(): JsonElement?

        /** The saved state, or null when none was saved: the feature must then fall back to its defaults. */
        fun load(element: JsonElement?)
    }

    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private val sections = ArrayList<Section>()
    private val SAVE_LOCK = Any()
    private val SAVE_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GeilerAddons macros config writer").apply { isDaemon = true }
    }
    private var pendingSave: Future<*>? = null

    /** Set when an existing file could not be used; saving then never replaces it. */
    @Volatile private var saveBlocked = false

    private fun path(): Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve(FILE_NAME)

    /** Registers a feature's state; call once while the mod starts, before [load]. */
    @JvmStatic
    fun registerSection(section: Section) {
        require(sections.none { it.name() == section.name() }) { "Macros section already registered: ${section.name()}" }
        require(section.name() !in RESERVED_KEYS) { "Macros section name is reserved: ${section.name()}" }
        sections.add(section)
    }

    private val RESERVED_KEYS = setOf("version", "activeWorkspace", "ui", "modules")

    private fun macroModules(): List<Module> =
        ModuleManager.modules().filter { it.category().workspace == Workspace.MACROS }

    /** True when the file is of a version this build can read. */
    fun isSupported(document: JsonObject): Boolean {
        val version = document.get("version")
        return version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asInt == VERSION
    }

    /** One module's saved state: enabled, key and every setting by name. */
    fun encodeModule(module: Module): JsonObject {
        val state = JsonObject()
        state.addProperty("enabled", module.isEnabled())
        if (module.showsKeybindControl() && module.keybind().isBound()) {
            val key = JsonObject()
            key.addProperty("key", module.keybind().key().name)
            key.addProperty("modifiers", module.keybind().modifiers())
            state.add("keybind", key)
        }
        val settings = JsonObject()
        for (setting in module.numberSettings()) settings.addProperty(setting.name(), setting.value())
        for (setting in module.booleanSettings()) settings.addProperty(setting.name(), setting.rawValue())
        for (setting in module.choiceSettings()) settings.addProperty(setting.name(), setting.value())
        for (setting in module.textSettings()) settings.addProperty(setting.name(), setting.value())
        for (setting in module.colorSettings()) {
            val rgba = JsonArray()
            rgba.add(setting.red())
            rgba.add(setting.green())
            rgba.add(setting.blue())
            rgba.add(setting.alpha())
            settings.add(setting.name(), rgba)
        }
        state.add("settings", settings)
        return state
    }

    /** Applies a saved state; anything missing, of the wrong type or outside its catalog keeps the current value. */
    fun decodeModule(module: Module, state: JsonObject) {
        val keybind = state.get("keybind")
        if (!module.showsKeybindControl()) {
            module.setKeybind(ModuleKeybind.NONE)
        } else if (keybind != null && keybind.isJsonObject) {
            val key = keybind.asJsonObject.get("key")
            val modifiers = keybind.asJsonObject.get("modifiers")
            if (key != null && key.isJsonPrimitive && key.asJsonPrimitive.isString) {
                try {
                    module.setKeybind(
                        ModuleKeybind(
                            InputConstants.getKey(key.asString),
                            if (modifiers != null && modifiers.isJsonPrimitive && modifiers.asJsonPrimitive.isNumber) modifiers.asInt else 0,
                        ),
                    )
                } catch (_: RuntimeException) {
                    // A removed or hand-edited key must not prevent the client from starting.
                }
            }
        }
        val settings = state.get("settings")
        if (settings != null && settings.isJsonObject) {
            val values = settings.asJsonObject
            for (setting in module.numberSettings()) number(values, setting.name())?.let { setting.setValue(it) }
            for (setting in module.booleanSettings()) bool(values, setting.name())?.let { setting.setValue(it) }
            for (setting in module.choiceSettings()) {
                val value = string(values, setting.name())
                if (value != null && setting.choices().contains(value)) setting.setValue(value)
            }
            for (setting in module.textSettings()) string(values, setting.name())?.let { setting.setValue(it) }
            for (setting in module.colorSettings()) {
                val rgba = values.get(setting.name())
                if (rgba != null && rgba.isJsonArray && rgba.asJsonArray.size() == 4 &&
                    rgba.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ) {
                    val parts = rgba.asJsonArray.map { it.asInt }
                    setting.set(parts[0], parts[1], parts[2], parts[3])
                }
            }
        }
        val enabled = state.get("enabled")
        val savedEnabled = if (enabled != null && enabled.isJsonPrimitive && enabled.asJsonPrimitive.isBoolean) enabled.asBoolean else null
        module.setEnabled(savedEnabled ?: module.defaultEnabled())
    }

    private fun number(values: JsonObject, name: String): Float? {
        val element = values.get(name)
        return if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asFloat else null
    }

    private fun bool(values: JsonObject, name: String): Boolean? {
        val element = values.get(name)
        return if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) element.asBoolean else null
    }

    private fun string(values: JsonObject, name: String): String? {
        val element = values.get(name)
        return if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
    }

    /** The whole file as it would be written now. Client thread. */
    @JvmOverloads
    fun encodeDocument(extra: List<Section> = sections): JsonObject {
        val document = JsonObject()
        document.addProperty("version", VERSION)
        document.addProperty("activeWorkspace", ClickGuiState.workspace().name)
        val ui = JsonObject()
        ClickGuiState.runIn(Workspace.MACROS) {
            ui.addProperty("category", (ClickGuiState.category() ?: Category.of(Workspace.MACROS)[0]).name)
            ClickGuiState.openModule()?.let { ui.addProperty("openModule", it.configName()) }
            ClickGuiState.expandedColor()?.let { ui.addProperty("expandedColor", it.name()) }
            ui.addProperty("settingsScroll", ClickGuiState.settingsScroll())
            ui.addProperty("categoryScroll", ClickGuiState.categoryScroll())
            val grid = JsonObject()
            for ((category, scroll) in ClickGuiState.gridScrolls().toSortedMap(compareBy<Category> { it.name })) grid.addProperty(category.name, scroll)
            ui.add("gridScrollByCategory", grid)
            ui.add("favoriteCategories", ClickGuiState.favoriteCategoryIds().sorted().toJsonArray())
            ui.add("favoriteModules", ClickGuiState.favoriteModuleIds().sorted().toJsonArray())
        }
        document.add("ui", ui)
        val modules = JsonObject()
        for (module in macroModules()) modules.add(module.configName(), encodeModule(module))
        document.add("modules", modules)
        for (section in extra) section.save()?.let { document.add(section.name(), it) }
        return document
    }

    private fun Collection<String>.toJsonArray(): JsonArray {
        val array = JsonArray()
        for (value in this) array.add(JsonPrimitive(value))
        return array
    }

    /** Applies a read file (or an empty one, for defaults) to the live state. Client thread. */
    @JvmOverloads
    fun applyDocument(document: JsonObject, extra: List<Section> = sections) {
        val active = document.get("activeWorkspace")
        ClickGuiState.setWorkspace(
            Workspace.fromName(if (active != null && active.isJsonPrimitive) active.asString else null),
        )
        val modules = macroModules()
        val saved = document.get("modules")?.takeIf { it.isJsonObject }?.asJsonObject
        for (module in modules) {
            val state = saved?.get(module.configName())?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            decodeModule(module, state)
        }
        val ui = document.get("ui")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        ClickGuiState.runIn(Workspace.MACROS) {
            val categories = Category.of(Workspace.MACROS)
            ClickGuiState.setCategory(categories.firstOrNull { it.name == legacyCategory(string(ui, "category")) } ?: categories[0])
            val openName = string(ui, "openModule")
            val open = modules.firstOrNull { it.configName() == openName }
            ClickGuiState.setOpenModule(open)
            val colorName = string(ui, "expandedColor")
            ClickGuiState.setExpandedColor(open?.colorSettings()?.firstOrNull { it.name() == colorName })
            ClickGuiState.setSettingsScroll(maxOf(0, number(ui, "settingsScroll")?.toInt() ?: 0))
            ClickGuiState.setCategoryScroll(number(ui, "categoryScroll")?.toInt() ?: 0)
            val grid = HashMap<String, Int?>()
            ui.get("gridScrollByCategory")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) grid[key] = value.asInt
            }
            ClickGuiState.setGridScrolls(grid)
            ClickGuiState.setFavoriteCategoryIds(strings(ui.get("favoriteCategories")))
            ClickGuiState.setFavoriteModuleIds(strings(ui.get("favoriteModules")))
        }
        for (section in extra) section.load(document.get(section.name()))
    }

    /** Rotation and Area Killer were one category each before they became Engine. */
    private fun legacyCategory(name: String?): String? = if (name == "ROTATION" || name == "PATHFINDER") Category.ENGINE.name else name

    private fun strings(element: JsonElement?): List<String> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }.map { it.asString }
    }

    /** Null for a missing, unreadable or oversized file. */
    fun readDocument(file: Path): JsonObject? {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) return null
            val element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
            return if (element.isJsonObject) element.asJsonObject else null
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
    }

    @Throws(IOException::class)
    fun writeAtomically(file: Path, document: JsonObject) {
        Files.createDirectories(file.parent)
        val text = GSON.toJson(document)
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_FILE_BYTES) {
            throw IOException("Macros workspace config exceeds the $MAX_FILE_BYTES byte persistence limit")
        }
        val temporary = Files.createTempFile(file.parent, "macros-workspace-", ".tmp")
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Reads the file and applies it; called once the macro modules and sections are registered. Client thread. */
    @JvmStatic
    fun load() {
        val file = path()
        val document = readDocument(file)
        saveBlocked = false
        if (document == null) {
            if (Files.exists(file)) {
                saveBlocked = true
                GeilerAddons.LOGGER.error("Could not read {}; using Macros defaults and leaving the file untouched", file)
            }
            applyDocument(JsonObject())
        } else if (!isSupported(document)) {
            saveBlocked = true
            GeilerAddons.LOGGER.error("{} is of an unsupported version; using Macros defaults and leaving the file untouched", file)
            applyDocument(JsonObject())
        } else {
            applyDocument(document)
        }
    }

    /** Snapshots now (client thread) and writes in the background. */
    @JvmStatic
    fun save() {
        if (saveBlocked) return
        val document = encodeDocument()
        val file = path()
        synchronized(SAVE_LOCK) {
            pendingSave = SAVE_EXECUTOR.submit<Unit> {
                try {
                    writeAtomically(file, document)
                } catch (error: IOException) {
                    GeilerAddons.LOGGER.error("Failed to save the Macros workspace config to {}", file, error)
                } catch (error: RuntimeException) {
                    GeilerAddons.LOGGER.error("Failed to serialize the Macros workspace config to {}", file, error)
                }
            }
        }
    }

    /** Waits for a queued write, for shutdown. */
    @JvmStatic
    fun flushNow(): Boolean {
        val future = synchronized(SAVE_LOCK) { pendingSave } ?: return true
        return try {
            future.get()
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (error: ExecutionException) {
            GeilerAddons.LOGGER.error("Macros config writer failed", error.cause)
            false
        }
    }

    /** Puts every Macros module, section and view state back to factory defaults. Client thread. */
    @JvmStatic
    fun resetRuntime() {
        for (module in macroModules()) {
            module.colorSettings().forEach { it.reset() }
            module.numberSettings().forEach { it.reset() }
            module.booleanSettings().forEach { it.reset() }
            module.choiceSettings().forEach { it.reset() }
            module.textSettings().forEach { it.reset() }
            module.setKeybind(ModuleKeybind.NONE)
            module.setEnabled(module.defaultEnabled())
        }
        for (section in sections) section.load(null)
        ClickGuiState.setWorkspace(Workspace.NORMAL)
        ClickGuiState.runIn(Workspace.MACROS) {
            ClickGuiState.setCategory(Category.of(Workspace.MACROS)[0])
            ClickGuiState.setOpenModule(null)
            ClickGuiState.setExpandedColor(null)
            ClickGuiState.setSettingsScroll(0)
            ClickGuiState.setCategoryScroll(0)
            ClickGuiState.setGridScrolls(emptyMap())
            ClickGuiState.setFavoriteCategoryIds(emptyList())
            ClickGuiState.setFavoriteModuleIds(emptyList())
        }
    }
}
