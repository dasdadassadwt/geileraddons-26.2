package geiler.addons.client.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParseException
import geiler.addons.GeilerAddons
import geiler.addons.client.dungeon.DungeonRoomOverlaySettingsMigration
import geiler.addons.client.enchanting.AutoExperimentDelayRange
import geiler.addons.client.hud.HudManager
import geiler.addons.client.location.Island
import geiler.addons.client.macro.MacroDefinition
import geiler.addons.client.macro.MacroFunction
import geiler.addons.client.macro.MacroRunner
import geiler.addons.client.macro.MacroTriggerContext
import geiler.addons.client.macro.MacroValue
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleKeybind
import geiler.addons.client.module.ModuleManager
import geiler.addons.client.module.impl.AutoExperimentsModule
import geiler.addons.client.module.impl.BlockEspEntry
import geiler.addons.client.module.impl.BlockEspModule
import geiler.addons.client.module.impl.DungeonMobEspKeybindMigration
import geiler.addons.client.module.impl.DungeonMobEspModule
import geiler.addons.client.module.impl.DungeonRoomOverlayModule
import geiler.addons.client.module.impl.GardenPlotBordersModule
import geiler.addons.client.module.impl.GeneralModule
import geiler.addons.client.module.impl.InventoryButtonPlacement
import geiler.addons.client.module.impl.InventoryButtonsModule
import geiler.addons.client.module.impl.MacrosModule
import geiler.addons.client.module.impl.MobHighlight
import geiler.addons.client.module.impl.MobHighlightModule
import geiler.addons.client.module.impl.SlotIdsModule
import geiler.addons.client.module.impl.TreeTrackerModule
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.tree.TreeType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import com.mojang.blaze3d.platform.InputConstants
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.EnumSet
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.regex.Pattern
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Kotlin implementation behind the stable Java [ModConfig] facade. */
object ModConfigCore {
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    private val DIR: Path = FabricLoader.getInstance().configDir.resolve("geileraddons")
    private val PATH: Path = DIR.resolve("config.json")
    private val MACRO_DIR: Path = DIR.resolve("macros")
    private val FUNCTION_DIR: Path = DIR.resolve("functions")
    private val MACRO_INDEX_PATH: Path = DIR.resolve("macro-index.json")
    private const val MAX_MACRO_RECORD_BYTES = 1_048_576
    private const val MAX_MACRO_INDEX_BYTES = 65_536
    private val LEGACY_PATH: Path = FabricLoader.getInstance().configDir.resolve("geileraddons.json")
    @Volatile private var completedMigrations: Set<String> = emptySet()

    /** Reads General's current island-detection preference. */
    @JvmStatic
    fun hypixelModApi(): Boolean = GeneralModule.INSTANCE.islandDetectionApi().value()

    private const val FLUSH_INTERVAL_MILLIS = 60_000L
    private val SAVE_LOCK = Any()
    private val SAVE_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GeilerAddons config writer").apply { isDaemon = true }
    }

    @Volatile private var dirty = false
    @Volatile private var lastFlush = 0L
    @Volatile private var mutationVersion = 0L
    @Volatile private var separatedMacroLoadBlocked = false
    private var pendingSave: Future<*>? = null
    private var queuedVersion = -1L

    /** Reads General's current release-check preference. */
    @JvmStatic
    fun checkForUpdates(): Boolean = GeneralModule.INSTANCE.checkForUpdates().value()

    /** Saved enable values take precedence, including explicit false. */
    @JvmStatic
    fun resolveModuleEnabledState(savedEnabled: Boolean?, defaultEnabled: Boolean): Boolean =
        savedEnabled ?: defaultEnabled

    @JvmStatic
    fun resolveLegacyUtilityTimerHud(savedHud: Boolean?, oldEnabled: Boolean?, oldHud: Boolean?): Boolean? =
        ModConfigMigrations.resolveLegacyUtilityTimerHud(savedHud, oldEnabled, oldHud)

    @JvmStatic
    fun resolveLegacyUtilityTimerAlert(savedAlert: Boolean?, oldEnabled: Boolean?, oldAlert: Boolean?): Boolean? =
        ModConfigMigrations.resolveLegacyUtilityTimerAlert(savedAlert, oldEnabled, oldAlert)

    @JvmStatic
    fun load() {
        val data = readData()
        val migratedLegacyChatHud = ModConfigMigrations.migrateLegacyChatHud(data.enabled, data.toggles)
        val migratedDungeonMapSettings = DungeonRoomOverlaySettingsMigration.migrate(
            legacyMutableMapView(data.toggles), legacySetView(data.migrations),
            DungeonRoomOverlayModule.INSTANCE.configName(),
        )
        completedMigrations = java.util.Set.copyOf(data.migrations.filterNotNull())
        val migratedLegacyTimer = ModConfigMigrations.migrateLegacyUtilityTimer(
            data.enabled, data.toggles, data.texts, data.numbers,
        )
        val migratedLegacyPresentation = ModConfigMigrations.migrateLegacyPlayerPresentation(data.enabled, data.toggles)
        val migratedSecretSettings = ModConfigMigrations.migrateSecretSettingNames(
            data.toggles, data.colors, data.numbers, data.texts,
        )
        val migratedDungeonSplitTiming = migrateLegacyDungeonSplitTiming(data)
        val migratedRoomAlertSounds = ModConfigMigrations.migrateDungeonRoomAlertSoundSettings(data.texts) or
            ModConfigMigrations.migrateDungeonRoomAlertSharedSettings(data.toggles, data.numbers)
        val migratedBloodCampSettings = ModConfigMigrations.migrateLegacyBloodCampSettings(
            data.toggles, data.colors, data.numbers,
        )
        val migratedDungeonSettings = migrateLegacyDungeonFeatureSettings(data)
        val migratedDungeonHudPosition = ModConfigMigrations.migrateLegacyDungeonHudPosition(data.hudPositions)
        val migratedDefaultTheme = migrateLegacyDefaultTheme(data)
        val migratedIconColor = ThemeIconColorMigration.migrate(
            data.colors,
            settingKey(VisualModule.INSTANCE, "Icon color"),
            settingKey(GeneralModule.INSTANCE, "Icon color"),
        )
        for (module in ModuleManager.modules()) {
            val savedKeybind = data.keybinds[module.configName()]
            val savedKey = savedKeybind?.key
            if (!module.showsKeybindControl()) {
                module.setKeybind(ModuleKeybind.NONE)
            } else if (savedKeybind != null && savedKey != null) {
                try {
                    module.setKeybind(ModuleKeybind(InputConstants.getKey(savedKey), savedKeybind.modifiers))
                } catch (_: RuntimeException) {
                    // A removed or hand-edited key must not prevent the client from starting.
                }
            }
            for (setting in module.colorSettings()) {
                val rgba = settingValue(data.colors, module, setting.name())
                if (rgba != null && rgba.size == 4) setting.set(rgba[0], rgba[1], rgba[2], rgba[3])
            }
            for (setting in module.numberSettings()) {
                val value = settingValue(data.numbers, module, setting.name())
                if (value != null) setting.setValue(value)
            }
            for (setting in module.booleanSettings()) {
                val value = settingValue(data.toggles, module, setting.name())
                if (value != null) setting.setValue(value)
            }
            for (setting in module.choiceSettings()) {
                val value = settingValue(data.choices, module, setting.name())
                if (value != null) setting.setValue(value)
            }
            for (setting in module.textSettings()) {
                val value = settingValue(data.texts, module, setting.name())
                if (value != null) {
                    setting.setValue(value)
                } else {
                    // Keeps values when a numeric slider is deliberately replaced by a text input.
                    val legacyNumber = settingValue(data.numbers, module, setting.name())
                    if (legacyNumber != null) setting.setValue(formatLegacyNumber(legacyNumber))
                }
            }
            val savedEnabled = data.enabled[module.configName()]
            module.setEnabled(resolveModuleEnabledState(savedEnabled, module.defaultEnabled()))
        }
        val migratedDungeonMobEspKeybind = migrateLegacyDungeonMobEspKeybind(data)
        val migratedDungeonRoomMapStyle = DungeonRoomOverlayModule.INSTANCE.restoreLegacySettings(
            legacyMapView(data.toggles), legacyMapView(data.numbers), legacyMapView(data.choices),
        )
        DungeonMobEspModule.INSTANCE.restoreLegacySettings(
            legacyMapView(data.numbers), legacyMapView(data.toggles),
            legacyMapView(data.choices), legacyMapView(data.colors),
        )
        if (!data.enabled.containsKey(DungeonMobEspModule.INSTANCE.configName())) {
            val specialMobEspEnabled = data.enabled["Dungeon Special Mob ESP"]
            val oldGroupEnabled = data.enabled["Starred Mob ESP"] == true || data.enabled["Miniboss ESP"] == true
            if (specialMobEspEnabled == true || oldGroupEnabled) DungeonMobEspModule.INSTANCE.setEnabled(true)
        }
        val legacyFillOpacity = data.numbers["Garden Plot Borders.Fill Opacity"]
        if (legacyFillOpacity != null) {
            val alpha = Math.round(Math.max(0.0f, Math.min(100.0f, legacyFillOpacity)) * 2.55f)
            GardenPlotBordersModule.INSTANCE.fillColor().setChannel(ColorSetting.Channel.ALPHA, alpha)
        }
        val autoExperiments = AutoExperimentsModule.INSTANCE
        val legacyExperimentDelay = data.numbers["Auto Experiments.Click Delay (ms)"]
        val savedMinimumDelay = data.numbers[settingKey(autoExperiments, autoExperiments.minimumClickDelay().name())]
        val savedMaximumDelay = data.numbers[settingKey(autoExperiments, autoExperiments.maximumClickDelay().name())]
        val restoredDelay = AutoExperimentDelayRange.restore(
            legacyExperimentDelay,
            savedMinimumDelay,
            savedMaximumDelay,
            autoExperiments.minimumClickDelay().intValue(),
            autoExperiments.maximumClickDelay().intValue(),
        )
        autoExperiments.minimumClickDelay().setValue(restoredDelay.minimumMillis().toFloat())
        autoExperiments.maximumClickDelay().setValue(restoredDelay.maximumMillis().toFloat())
        // Slot IDs used to be a Debug toggle. Preserve it only if the standalone module has no saved state.
        if (!data.enabled.containsKey(SlotIdsModule.INSTANCE.name()) && data.toggles.containsKey("Debug.Show Slot IDs")) {
            SlotIdsModule.INSTANCE.setEnabled(data.toggles["Debug.Show Slot IDs"] == true)
        }
        val savedCheckForUpdates = data.checkForUpdates
        if (savedCheckForUpdates != null &&
            !data.toggles.containsKey(settingKey(GeneralModule.INSTANCE, GeneralModule.INSTANCE.checkForUpdates().name()))
        ) {
            GeneralModule.INSTANCE.checkForUpdates().setValue(savedCheckForUpdates)
        }
        val savedHypixelModApi = data.hypixelModApi
        if (savedHypixelModApi != null &&
            !data.toggles.containsKey(settingKey(GeneralModule.INSTANCE, GeneralModule.INSTANCE.islandDetectionApi().name()))
        ) {
            GeneralModule.INSTANCE.islandDetectionApi().setValue(savedHypixelModApi)
        }
        val legacyMacroColors = settingValue(data.toggles, VisualModule.INSTANCE, "Theme Macro Colors")
        if (legacyMacroColors != null &&
            !data.toggles.containsKey(settingKey(VisualModule.INSTANCE, VisualModule.INSTANCE.themeSurfaces().name()))
        ) {
            VisualModule.INSTANCE.themeSurfaces().setValue(legacyMacroColors)
        }
        loadTikiCoords(data)
        loadTikiFingerprints(data)
        val savedHudPositions = data.hudPositions
        if (savedHudPositions != null) HudManager.restore(savedHudPositions)
        loadTreeGifts(data)
        MobHighlightModule.INSTANCE.folders().restore(data.mobHighlightFolders)
        MacrosModule.INSTANCE.folders().restore(data.macroFolders)
        BlockEspModule.INSTANCE.folders().restore(data.blockEspFolders)
        loadMobHighlights(data)
        loadBlockEspEntries(data)
        MacrosModule.INSTANCE.globalVariables().restore(MacroVariableConfigCodec.decode(data.macroVariables))
        loadSeparatedMacroData(data)
        loadMacroFunctions(data)
        loadMacros(data)
        loadInventoryButtons(data)
        loadUiState(data)
        if (migratedLegacyChatHud || migratedDungeonMapSettings || migratedLegacyTimer || migratedLegacyPresentation ||
            migratedSecretSettings || migratedDungeonSettings || migratedBloodCampSettings || migratedDefaultTheme ||
            migratedDungeonSplitTiming || migratedDungeonMobEspKeybind || migratedRoomAlertSounds ||
            migratedDungeonRoomMapStyle || migratedDungeonHudPosition || migratedIconColor
        ) markDirty()
    }

    private fun migrateLegacyDungeonFeatureSettings(data: ModConfig.Data): Boolean =
        ModConfigMigrations.migrateLegacyDungeonFeatureSettings(
            data.enabled, data.toggles, data.numbers, data.colors, data.choices, data.texts,
        )

    @JvmStatic
    fun migrateSameNamedPuzzleSettings(
        toggles: MutableMap<String, Boolean?>?,
        numbers: MutableMap<String, Float?>?,
        colors: MutableMap<String, IntArray?>?,
        choices: MutableMap<String, String?>?,
        texts: MutableMap<String, String?>?,
        puzzles: Module?,
        legacyPrefix: String?,
    ): Boolean = ConfigSettingKeys.migrateSameNamedPuzzleSettings(
        toggles, numbers, colors, choices, texts, puzzles, legacyPrefix,
    )

    @JvmStatic
    fun migratePuzzleStrategy(
        toggles: MutableMap<String, Boolean?>?,
        choices: Map<String, String?>?,
        puzzles: Module?,
        oldName: String?,
        targetName: String?,
        translation: Map<String, Boolean?>?,
    ): Boolean = ConfigSettingKeys.migratePuzzleStrategy(toggles, choices, puzzles, oldName, targetName, translation)

    @JvmStatic
    fun migrateLegacyModuleEnabledState(
        enabled: MutableMap<String, Boolean?>?,
        legacyModuleName: String?,
        newModuleName: String?,
    ): Boolean = ConfigSettingKeys.migrateLegacyModuleEnabledState(enabled, legacyModuleName, newModuleName)

    private fun migrateLegacyDungeonSplitTiming(data: ModConfig.Data): Boolean =
        ModConfigMigrations.migrateLegacyDungeonSplitTiming(data.toggles, data.choices)

    private fun migrateLegacyDungeonMobEspKeybind(data: ModConfig.Data): Boolean {
        val module = DungeonMobEspModule.INSTANCE
        val currentData = data.keybinds[module.configName()]
        val current = parseKeybind(currentData)
        val currentPresent = currentData != null && currentData.key != null && current != null
        val migration = DungeonMobEspKeybindMigration.resolve(
            currentPresent,
            current,
            parseKeybind(data.keybinds["Starred Mob ESP"]),
            parseKeybind(data.keybinds["Miniboss ESP"]),
        )
        if (currentPresent) return false
        if (migration.conflict) {
            module.setKeybind(ModuleKeybind.NONE)
            GeilerAddons.LOGGER.warn(
                "Legacy Starred Mob ESP and Miniboss ESP keybinds conflict. " +
                    "Dungeon Mob ESP was left unbound; set its keybind in the Click GUI.",
            )
            return migration.migrated
        }
        if (!migration.migrated) return false
        module.setKeybind(migration.keybind)
        return true
    }

    private fun parseKeybind(saved: ModConfig.KeybindData?): ModuleKeybind? {
        val key = saved?.key ?: return null
        return try {
            ModuleKeybind(InputConstants.getKey(key), saved.modifiers)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun migrateLegacyDefaultTheme(data: ModConfig.Data): Boolean {
        val theme = VisualModule.INSTANCE
        var migrated = false
        migrated = migrateLegacyThemeColor(data, theme, "Background", intArrayOf(14, 14, 18, 230), intArrayOf(11, 18, 32, 129)) || migrated
        migrated = migrateLegacyThemeColor(data, theme, "Border", intArrayOf(255, 255, 255, 51), intArrayOf(25, 29, 36, 255)) || migrated
        migrated = migrateLegacyThemeColor(data, theme, "Accent", intArrayOf(207, 207, 214, 255), intArrayOf(59, 130, 246, 255)) || migrated
        migrated = migrateLegacyThemeColor(data, theme, "Text", intArrayOf(255, 255, 255, 255), intArrayOf(234, 242, 255, 255)) || migrated
        migrated = migrateLegacyThemeColor(data, theme, "Muted Text", intArrayOf(140, 140, 153, 255), intArrayOf(126, 143, 168, 255)) || migrated
        return migrated
    }

    private fun migrateLegacyThemeColor(
        data: ModConfig.Data,
        theme: VisualModule,
        setting: String,
        legacyDefault: IntArray,
        replacement: IntArray,
    ): Boolean {
        if (!java.util.Arrays.equals(settingValue(data.colors, theme, setting), legacyDefault)) return false
        data.colors[settingKey(theme, setting)] = replacement
        return true
    }

    private fun formatLegacyNumber(value: Float): String =
        if (value.toDouble() == Math.rint(value.toDouble())) value.toLong().toString() else java.lang.Float.toString(value)

    /** Java's generic config maps can contain null Gson values even when the legacy type omitted `?`. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> legacyMapView(values: Map<String, T?>): Map<String, T> = values as Map<String, T>

    /** Keeps the same backing map for migrations that insert a replacement setting. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> legacyMutableMapView(values: MutableMap<String, T?>): MutableMap<String, T> =
        values as MutableMap<String, T>

    /** The reader removes null migration markers before passing this Java-compatible set view. */
    @Suppress("UNCHECKED_CAST")
    private fun legacySetView(values: MutableSet<String?>): MutableSet<String> = values as MutableSet<String>

    /** Marks a deferred write without doing disk I/O on the client thread. */
    @JvmStatic
    fun markDirty() {
        synchronized(SAVE_LOCK) {
            dirty = true
            mutationVersion++
        }
    }

    /** Called once per client tick; writes only after a dirty change and the flush interval. */
    @JvmStatic
    fun flushIfDirty() {
        if (!dirty) return
        val now = System.currentTimeMillis()
        if (now - lastFlush < FLUSH_INTERVAL_MILLIS) return
        save()
    }

    /** Writes a pending change immediately, for shutdown when there is no next tick. */
    @JvmStatic
    fun flushNow(): Boolean {
        val future = if (dirty) {
            enqueueSave(snapshotData(), mutationVersion)
        } else {
            synchronized(SAVE_LOCK) { pendingSave }
        }
        return waitFor(future)
    }

    /** Waits for the already queued client-thread snapshot without snapshotting off-thread. */
    @JvmStatic
    fun flushPrepared(): Boolean {
        val future = synchronized(SAVE_LOCK) { pendingSave }
        val saved = waitFor(future)
        return synchronized(SAVE_LOCK) { saved && !dirty }
    }

    /** Queues an explicit client-thread snapshot so settings-screen closure never writes inline. */
    @JvmStatic
    fun save() {
        enqueueSave(snapshotData(), mutationVersion)
    }

    private fun enqueueSave(data: ModConfig.Data?, version: Long): Future<*>? {
        if (data == null) return null
        synchronized(SAVE_LOCK) {
            val existing = pendingSave
            if (existing != null && !existing.isDone && queuedVersion >= version) return existing
            pendingSave = SAVE_EXECUTOR.submit<Boolean> { writeSnapshot(data, version) }
            queuedVersion = version
            return pendingSave
        }
    }

    private fun waitFor(future: Future<*>?): Boolean {
        if (future == null) return true
        try {
            val result = future.get()
            return result !is Boolean || result
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            GeilerAddons.LOGGER.warn("Interrupted while saving GeilerAddons config")
            return false
        } catch (error: ExecutionException) {
            GeilerAddons.LOGGER.error("Config writer failed", error.cause)
            return false
        }
    }

    /** Applies declared defaults while respecting selected ESP exclusions. */
    @JvmStatic
    fun resetModuleDefaultsExcept(excludedConfigNames: Set<String>?) {
        val excluded = excludedConfigNames?.let { java.util.Set.copyOf(it) } ?: emptySet()
        for (module in ModuleManager.modules()) {
            if (excluded.contains(module.configName())) continue
            module.colorSettings().forEach { it.reset() }
            module.numberSettings().forEach { it.reset() }
            module.booleanSettings().forEach { it.reset() }
            module.choiceSettings().forEach { it.reset() }
            module.textSettings().forEach { it.reset() }
            module.setKeybind(ModuleKeybind.NONE)
            module.setEnabled(module.defaultEnabled())
        }
        markDirty()
    }

    /** Applies declared defaults only to the named modules. */
    @JvmStatic
    fun resetNamedModuleDefaults(configNames: Set<String>?) {
        if (configNames == null || configNames.isEmpty()) return
        for (module in ModuleManager.modules()) {
            if (!configNames.contains(module.configName())) continue
            module.colorSettings().forEach { it.reset() }
            module.numberSettings().forEach { it.reset() }
            module.booleanSettings().forEach { it.reset() }
            module.choiceSettings().forEach { it.reset() }
            module.textSettings().forEach { it.reset() }
            module.setKeybind(ModuleKeybind.NONE)
            module.setEnabled(module.defaultEnabled())
        }
        markDirty()
    }

    /** Resets every runtime collection before applying a full imported profile; client-thread only. */
    @JvmStatic
    fun resetRuntimeToFactoryDefaults() {
        resetModuleDefaultsExcept(emptySet())
        MacroRunner.cancel("configuration reset or import")
        MacrosModule.INSTANCE.restore(emptyList())
        MacrosModule.INSTANCE.restoreFunctions(emptyList())
        MacrosModule.INSTANCE.globalVariables().restore(emptyList())
        MacrosModule.INSTANCE.folders().restore(emptyList())
        MobHighlightModule.INSTANCE.restore(emptyList())
        MobHighlightModule.INSTANCE.folders().restore(emptyList())
        BlockEspModule.INSTANCE.restore(emptyList())
        BlockEspModule.INSTANCE.folders().restore(emptyList())
        InventoryButtonsModule.INSTANCE.restore(emptyList())
        TikiCoords.seedDefaults()
        TikiFingerprints.replaceAll(emptyMap())
        HudManager.resetPositions()
        for (type in TreeType.values()) TreeTrackerModule.INSTANCE.tracker().stats(type).restore(0, 0, 0, 0)
        ClickGuiState.setCategory(Category.values()[0])
        ClickGuiState.setOpenModule(null)
        ClickGuiState.setExpandedColor(null)
        ClickGuiState.setSettingsScroll(0)
        ClickGuiState.setCategoryScroll(0)
        for (category in Category.values()) ClickGuiState.setGridScroll(category, 0)
        ClickGuiState.setFavoriteCategoryIds(emptyList())
        ClickGuiState.setFavoriteModuleIds(emptyList())
    }

    /** Queues a current snapshot after a targeted reset changes live settings or collections. */
    @JvmStatic
    fun saveAfterReset() {
        markDirty()
        save()
    }

    private fun snapshotData(): ModConfig.Data {
        val data = ModConfig.Data()
        data.migrations.addAll(completedMigrations)
        for (module in ModuleManager.modules()) {
            data.enabled[module.configName()] = module.isEnabled()
            if (module.showsKeybindControl() && module.keybind().isBound()) {
                val savedKeybind = ModConfig.KeybindData()
                savedKeybind.key = module.keybind().key().getName()
                savedKeybind.modifiers = module.keybind().modifiers()
                data.keybinds[module.configName()] = savedKeybind
            }
            for (setting in module.colorSettings()) {
                data.colors[settingKey(module, setting.name())] =
                    intArrayOf(setting.red(), setting.green(), setting.blue(), setting.alpha())
            }
            for (setting in module.numberSettings()) data.numbers[settingKey(module, setting.name())] = setting.value()
            for (setting in module.booleanSettings()) {
                // Persist the raw value of Debug-only toggles while their effective value is hidden.
                data.toggles[settingKey(module, setting.name())] = setting.rawValue()
            }
            for (setting in module.choiceSettings()) data.choices[settingKey(module, setting.name())] = setting.value()
            for (setting in module.textSettings()) data.texts[settingKey(module, setting.name())] = setting.value()
        }
        val savedTikiCoords = ArrayList<IntArray>()
        data.tikiCoords = savedTikiCoords
        for (pos in TikiCoords.all()) savedTikiCoords.add(intArrayOf(pos.x, pos.y, pos.z))
        val savedFingerprints = HashMap<String, String?>()
        data.tikiFingerprints = savedFingerprints
        for ((pos, block) in TikiFingerprints.all()) savedFingerprints[coordKey(pos)] = TikiFingerprints.idOf(block)
        data.hudPositions = HashMap<String, FloatArray?>().apply { putAll(HudManager.snapshot()) }
        val savedTreeGifts = HashMap<String, LongArray?>()
        data.treeGifts = savedTreeGifts
        for (type in TreeType.values()) {
            val stats = TreeTrackerModule.INSTANCE.tracker().stats(type)
            savedTreeGifts[type.name] = longArrayOf(
                stats.rawSessionCount().toLong(), stats.rawSessionMillis(),
                stats.rawStoredCount().toLong(), stats.rawStoredMillis(),
            )
        }
        val savedHighlights = ArrayList<ModConfig.MobHighlightData>()
        data.mobHighlights = savedHighlights
        data.mobHighlightFolders = MobHighlightModule.INSTANCE.folders().folders()
        for (highlight in MobHighlightModule.INSTANCE.highlights()) {
            val saved = ModConfig.MobHighlightData()
            saved.id = highlight.id()
            saved.folderId = highlight.folderId()
            saved.enabled = highlight.enabled().rawValue()
            saved.matchName = highlight.matchName().rawValue()
            saved.matchText = highlight.matchText().value()
            saved.outlineColor = rgba(highlight.outlineColor())
            saved.fillColor = rgba(highlight.fillColor())
            saved.depthCheck = highlight.depthCheck().rawValue()
            saved.scanInterval = highlight.scanInterval().value()
            saved.displayName = highlight.displayName().value()
            val islands = LinkedHashMap<String, Boolean?>()
            for ((island, setting) in highlight.islands()) islands[island.name] = setting.rawValue()
            saved.islands = islands
            savedHighlights.add(saved)
        }
        data.blockEspFolders = BlockEspModule.INSTANCE.folders().folders()
        val savedBlockEntries = ArrayList<ModConfig.BlockEspData>()
        data.blockEspEntries = savedBlockEntries
        for (entry in BlockEspModule.INSTANCE.entries()) {
            val saved = ModConfig.BlockEspData()
            saved.id = entry.id()
            saved.blockId = entry.blockId()
            saved.folderId = entry.folderId()
            saved.enabled = entry.enabled().rawValue()
            saved.box = entry.box().rawValue()
            saved.fill = entry.fill().rawValue()
            saved.outline = entry.outline().rawValue()
            saved.showLabel = entry.showLabel().rawValue()
            saved.tracer = entry.tracer().rawValue()
            saved.connectTouching = entry.connectTouching().rawValue()
            saved.depthCheck = entry.depthCheck().rawValue()
            saved.useCustomRange = entry.useCustomRange().rawValue()
            saved.customRange = entry.customRange().intValue()
            saved.outlineColor = rgba(entry.outlineColor())
            saved.fillColor = rgba(entry.fillColor())
            saved.displayName = entry.displayName().value()
            val islands = LinkedHashMap<String, Boolean?>()
            for ((island, setting) in entry.islands()) islands[island.name] = setting.rawValue()
            saved.islands = islands
            savedBlockEntries.add(saved)
        }
        MacrosModule.INSTANCE.syncSettings()
        data.macroFiles = snapshotMacros()
        data.functionFiles = snapshotMacroFunctions()
        data.macros = null
        data.macroFunctions = null
        data.macroVariables = MacroVariableConfigCodec.encode(MacrosModule.INSTANCE.globalVariables().savedVariables())
        data.macroFolders = MacrosModule.INSTANCE.folders().folders()
        data.inventoryButtons = InventoryButtonConfigCodec.encode(InventoryButtonsModule.INSTANCE.placements())
        data.uiCategory = ClickGuiState.category()!!.name
        data.uiOpenModule = ClickGuiState.openModule()?.configName()
        data.uiExpandedColor = ClickGuiState.expandedColor()?.name()
        data.uiSettingsScroll = ClickGuiState.settingsScroll()
        data.uiCategoryScroll = ClickGuiState.categoryScroll()
        data.uiGridScrollByCategory = HashMap()
        for ((category, scroll) in ClickGuiState.gridScrolls()) data.uiGridScrollByCategory[category.name] = scroll
        data.uiFavoriteCategories = ClickGuiState.favoriteCategoryIds().stream().sorted().toList()
        data.uiFavoriteModules = ClickGuiState.favoriteModuleIds().stream().sorted().toList()
        data.checkForUpdates = GeneralModule.INSTANCE.checkForUpdates().value()
        data.hypixelModApi = GeneralModule.INSTANCE.islandDetectionApi().value()
        return data
    }

    private fun writeSnapshot(data: ModConfig.Data, version: Long): Boolean {
        var temporary: Path? = null
        var success = false
        try {
            Files.createDirectories(PATH.parent)
            writeSeparatedMacroData(data)
            val temp = Files.createTempFile(PATH.parent, "config-", ".tmp")
            temporary = temp
            Files.newBufferedWriter(temp).use { writer -> GSON.toJson(data, writer) }
            try {
                Files.move(temp, PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING)
            }
            temporary = null
            success = true
        } catch (error: IOException) {
            GeilerAddons.LOGGER.error("Failed to save GeilerAddons config to {}", PATH, error)
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.error("Failed to serialize GeilerAddons config to {}", PATH, error)
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary!!)
                } catch (cleanupError: IOException) {
                    GeilerAddons.LOGGER.debug("Failed to remove temporary config file {}", temporary, cleanupError)
                }
            }
            synchronized(SAVE_LOCK) {
                lastFlush = System.currentTimeMillis()
                if (!success || mutationVersion != version) dirty = true else dirty = false
            }
        }
        return success
    }

    private fun loadTikiCoords(data: ModConfig.Data) {
        val savedCoords = data.tikiCoords
        if (savedCoords == null) {
            TikiCoords.seedDefaults()
            return
        }
        val coords = ArrayList<BlockPos>()
        for (coord in savedCoords) if (coord != null && coord.size == 3) coords.add(BlockPos(coord[0], coord[1], coord[2]))
        TikiCoords.replaceAll(coords)
    }

    private fun loadTikiFingerprints(data: ModConfig.Data) {
        val savedFingerprints = data.tikiFingerprints ?: return
        val fingerprints = HashMap<BlockPos, Block>()
        for ((key, value) in savedFingerprints) {
            val pos = parseCoordKey(key)
            val block = TikiFingerprints.byId(value ?: continue)
            if (pos != null && block != null) fingerprints[pos] = block
        }
        TikiFingerprints.replaceAll(fingerprints)
    }

    /** Restores the tree counters and closes the saved session into lifetime totals. */
    private fun loadTreeGifts(data: ModConfig.Data) {
        val savedTreeGifts = data.treeGifts
        if (savedTreeGifts != null) {
            for (type in TreeType.values()) {
                val values = savedTreeGifts[type.name] ?: continue
                if (values.size != 4) continue
                TreeTrackerModule.INSTANCE.tracker().stats(type)
                    .restore(values[0].toInt(), values[1], values[2].toInt(), values[3])
            }
        }
        TreeTrackerModule.INSTANCE.rollOverLoadedSession()
    }

    /** Absent means the player has not made a highlight yet. */
    private fun loadMobHighlights(data: ModConfig.Data) {
        val savedHighlights = data.mobHighlights ?: return
        val restored = ArrayList<MobHighlight>()
        for (saved in savedHighlights) {
            if (restored.size >= MobHighlightModule.MAX_HIGHLIGHTS) break
            if (saved == null) continue
            val highlight = MobHighlightModule.INSTANCE.blank(saved.id)
            highlight.setFolderId(saved.folderId)
            val savedEnabled = saved.enabled
            val savedMatchName = saved.matchName
            val savedMatchText = saved.matchText
            val savedDepthCheck = saved.depthCheck
            val savedScanInterval = saved.scanInterval
            val savedDisplayName = saved.displayName
            if (savedEnabled != null) highlight.enabled().setValue(savedEnabled)
            if (savedMatchName != null) highlight.matchName().setValue(savedMatchName)
            if (savedMatchText != null) highlight.matchText().setValue(savedMatchText)
            applyColor(highlight.outlineColor(), saved.outlineColor)
            applyColor(highlight.fillColor(), saved.fillColor)
            if (savedDepthCheck != null) highlight.depthCheck().setValue(savedDepthCheck)
            if (savedScanInterval != null) highlight.scanInterval().setValue(savedScanInterval)
            if (savedDisplayName != null) highlight.displayName().setValue(savedDisplayName)
            applyIslands(highlight, saved.islands)
            restored.add(highlight)
        }
        MobHighlightModule.INSTANCE.restore(restored)
    }

    private fun loadMacros(data: ModConfig.Data) {
        val savedMacros = data.macros ?: return
        val restored = ArrayList<MacroDefinition>()
        val ids = HashSet<Int>()
        for (saved in savedMacros) {
            if (saved == null || saved.id < 0 || !ids.add(saved.id)) continue
            val macro = MacroDefinition(saved.id)
            macro.setFolderId(saved.folderId)
            val savedName = saved.name
            val savedEnabled = saved.enabled
            val savedKey = saved.key
            val savedTriggerContext = saved.triggerContext
            val savedIslandRestricted = saved.islandRestricted
            if (savedName != null) macro.setName(savedName)
            if (savedEnabled != null) macro.setEnabled(savedEnabled)
            if (savedKey != null) {
                try {
                    macro.setKeybind(ModuleKeybind(InputConstants.getKey(savedKey), saved.modifiers))
                } catch (_: RuntimeException) {
                    // Invalid hand-edited keys leave the macro safely unbound.
                }
            }
            try {
                if (savedTriggerContext != null) macro.setTriggerContext(MacroTriggerContext.valueOf(savedTriggerContext))
            } catch (_: IllegalArgumentException) {
                // Keep the safe default for a removed context name.
            }
            if (savedIslandRestricted != null) macro.setIslandRestricted(savedIslandRestricted)
            macro.setIslands(parseIslands(saved.islands))
            var savedZoom = saved.canvasZoom ?: 1.0f
            val savedZoomVersion = saved.canvasZoomVersion ?: 0
            if (savedZoomVersion < 1) savedZoom *= 100.0f
            if (savedZoomVersion < 2) savedZoom *= 100.0f
            macro.setCanvasView(saved.canvasPanX ?: 0.0f, saved.canvasPanY ?: 0.0f, savedZoom)
            // Legacy macro-level defaults are ignored; delays belong to individual workflow nodes.
            val scripts = saved.scripts
            if (scripts != null && !scripts.isEmpty) {
                macro.restoreScripts(MacroScriptConfigCodec.decode(scripts))
            } else {
                macro.steps().addAll(MacroStepConfigCodec.decode(saved.steps))
            }
            // Older configs have no loose-block list; retain their executable stacks unchanged.
            macro.restoreDetachedBlocks(MacroStepConfigCodec.decode(saved.detachedBlocks))
            restored.add(macro)
        }
        MacrosModule.INSTANCE.restore(restored)
    }

    /** Loads separated records when available; otherwise the inline legacy lists remain the source. */
    private fun loadSeparatedMacroData(data: ModConfig.Data) {
        if (!Files.isRegularFile(MACRO_INDEX_PATH)) return
        try {
            if (Files.size(MACRO_INDEX_PATH) > MAX_MACRO_INDEX_BYTES) throw IOException("macro index exceeds the size limit")
            val savedIndex = Files.newBufferedReader(MACRO_INDEX_PATH).use {
                GSON.fromJson(it, ModConfig.MacroIndex::class.java)
            } ?: throw IOException("macro index is malformed or exceeds the entry limit")
            val macroNames = savedIndex.macros
            val functionNames = savedIndex.functions
            if (savedIndex.version != 1 || macroNames == null || functionNames == null) {
                throw IOException("macro index is malformed or exceeds the entry limit")
            }
            if (macroNames.size > 2_000 || functionNames.size > MacroFunction.MAX_FUNCTIONS) {
                throw IOException("macro index is malformed or exceeds the entry limit")
            }
            val macros = readMacroRecords(macroNames, MACRO_DIR, ModConfig.MacroData::class.java)
            val functions = readMacroRecords(functionNames, FUNCTION_DIR, ModConfig.MacroFunctionData::class.java)
            data.macros = macros
            data.macroFunctions = functions
        } catch (error: IOException) {
            GeilerAddons.LOGGER.error("Could not load separated macro/function files from {}", DIR, error)
            separatedMacroLoadBlocked = true
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.error("Could not load separated macro/function files from {}", DIR, error)
            separatedMacroLoadBlocked = true
        }
    }

    private fun <T> readMacroRecords(files: List<String?>, directory: Path, type: Class<T>): List<T> {
        val result = ArrayList<T>()
        val seenFiles = HashSet<String>()
        for (file in files) {
            if (file == null || !Regex("[A-Za-z0-9._-]{1,128}\\.json").matches(file)) {
                throw IOException("macro index contains an invalid file name")
            }
            if (!seenFiles.add(file)) throw IOException("macro index contains a duplicate file reference")
            val path = directory.resolve(file).normalize()
            if (path.parent != directory.normalize() || !Files.isRegularFile(path)) {
                throw IOException("macro index references a missing or unsafe file")
            }
            if (Files.size(path) > MAX_MACRO_RECORD_BYTES) throw IOException("separated macro file exceeds the size limit")
            try {
                Files.newBufferedReader(path).use { reader ->
                    val record = GSON.fromJson(reader, type) ?: throw IOException("separated macro file has no value")
                    result.add(record)
                }
            } catch (error: RuntimeException) {
                throw IOException("could not read separated macro file ${path.fileName}", error)
            }
        }
        return result
    }

    /** Writes each workflow atomically and commits the manifest last so stale files are ignored. */
    private fun writeSeparatedMacroData(data: ModConfig.Data) {
        if (separatedMacroLoadBlocked) return
        val macros = data.macroFiles ?: return
        val functions = data.functionFiles ?: return
        if (macros.size > 2_000 || functions.size > MacroFunction.MAX_FUNCTIONS) {
            throw IOException("Macro catalog exceeds the supported limit (2,000 macros / ${MacroFunction.MAX_FUNCTIONS} functions)")
        }
        val macroFiles = ArrayList<String>()
        for (macro in macros) {
            val file = "${safeFilePart(macro.name)}-${macro.id}.json"
            validateSerializedSize(macro, MAX_MACRO_RECORD_BYTES, "macro ${macro.name}")
            macroFiles.add(file)
        }
        val functionFiles = ArrayList<String>()
        for (function in functions) {
            val file = "${safeFilePart(function.name)}-${safeFilePart(function.id)}-${Integer.toUnsignedString(function.id.hashCode(), 36)}.json"
            validateSerializedSize(function, MAX_MACRO_RECORD_BYTES, "function ${function.name}")
            functionFiles.add(file)
        }
        val previousMacroFiles = readMacroManifestFiles()
        val index = ModConfig.MacroIndex()
        index.version = 1
        index.macros = macroFiles
        index.functions = functionFiles
        validateSerializedSize(index, MAX_MACRO_INDEX_BYTES, "macro index")
        val migrating = !Files.isRegularFile(MACRO_INDEX_PATH) && Files.isRegularFile(PATH)
        Files.createDirectories(MACRO_DIR)
        Files.createDirectories(FUNCTION_DIR)
        if (migrating) {
            val backup = DIR.resolve("config.before-split.json")
            if (!Files.exists(backup)) Files.copy(PATH, backup)
        }
        for (i in macros.indices) writeJsonAtomic(MACRO_DIR.resolve(macroFiles[i]), macros[i])
        for (i in functions.indices) writeJsonAtomic(FUNCTION_DIR.resolve(functionFiles[i]), functions[i])
        writeJsonAtomic(MACRO_INDEX_PATH, index)
        deleteNewlyOrphanedMacroFiles(previousMacroFiles, macroFiles)
    }

    /** Reads only a bounded, trusted manifest to identify files previously owned by this writer. */
    private fun readMacroManifestFiles(): List<String> {
        if (!Files.isRegularFile(MACRO_INDEX_PATH, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        try {
            if (Files.size(MACRO_INDEX_PATH) > MAX_MACRO_INDEX_BYTES) return emptyList()
            val index = Files.newBufferedReader(MACRO_INDEX_PATH).use {
                GSON.fromJson(it, ModConfig.MacroIndex::class.java)
            } ?: return emptyList()
            val indexedMacros = index.macros ?: return emptyList()
            if (index.version != 1 || indexedMacros.size > 2_000) return emptyList()
            val result = ArrayList<String>()
            for (candidate in indexedMacros) {
                val file = candidate ?: continue
                if (isWriterOwnedMacroFile(file) && !result.contains(file)) result.add(file)
            }
            return java.util.List.copyOf(result)
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Could not inspect the previous macro manifest; leaving stale macro files untouched", error)
            return emptyList()
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Could not inspect the previous macro manifest; leaving stale macro files untouched", error)
            return emptyList()
        }
    }

    /** Deletes only old manifest entries omitted from the successfully committed replacement. */
    private fun deleteNewlyOrphanedMacroFiles(previousFiles: List<String>?, currentFiles: List<String>) {
        if (previousFiles.isNullOrEmpty() || !Files.isDirectory(MACRO_DIR, LinkOption.NOFOLLOW_LINKS)) return
        val macroDirectory = MACRO_DIR.toAbsolutePath().normalize()
        for (file in previousFiles) {
            if (!isWriterOwnedMacroFile(file) || currentFiles.contains(file)) continue
            val target = macroDirectory.resolve(file).normalize()
            if (target.parent != macroDirectory || Files.isSymbolicLink(target) ||
                !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
            ) continue
            try {
                Files.deleteIfExists(target)
            } catch (error: IOException) {
                GeilerAddons.LOGGER.warn("Could not delete orphaned macro file {}", target.fileName, error)
            }
        }
    }

    private fun isWriterOwnedMacroFile(file: String?): Boolean =
        file != null && Regex("[A-Za-z0-9._-]{1,48}-[0-9]+\\.json").matches(file)

    private fun validateSerializedSize(value: Any, maximumBytes: Int, label: String) {
        val size = GSON.toJson(value).toByteArray(StandardCharsets.UTF_8).size
        if (size > maximumBytes) throw IOException("$label exceeds the $maximumBytes byte persistence limit")
    }

    private fun writeJsonAtomic(path: Path, value: Any) {
        val temporary = Files.createTempFile(path.parent, "entry-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { writer -> GSON.toJson(value, writer) }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun safeFilePart(value: String?): String {
        if (value.isNullOrBlank()) return "unnamed"
        var safe = value.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-")
        while (safe.startsWith(".")) safe = safe.substring(1)
        if (safe.isBlank()) safe = "unnamed"
        return if (safe.length > 48) safe.substring(0, 48) else safe
    }

    private fun loadMacroFunctions(data: ModConfig.Data) {
        val restored = ArrayList<MacroFunction>()
        val ids = HashSet<String?>()
        val savedFunctions = data.macroFunctions
        if (savedFunctions != null) for (saved in savedFunctions) {
            if (saved == null || restored.size >= MacroFunction.MAX_FUNCTIONS || !ids.add(saved.id)) continue
            val function = MacroFunction(saved.id)
            function.setName(saved.name)
            val parameters = saved.parameters
            if (parameters != null) for (parameter in parameters) {
                if (function.parameters().size >= 32 || parameter == null) break
                function.parameters().add(
                    MacroFunction.Parameter(parameter.name, parseMacroValueType(parameter.type), parameter.defaultValue),
                )
            }
            function.steps().addAll(MacroStepConfigCodec.decode(saved.steps))
            restored.add(function)
        }
        MacrosModule.INSTANCE.restoreFunctions(restored)
    }

    private fun loadBlockEspEntries(data: ModConfig.Data) {
        val savedEntries = data.blockEspEntries ?: return
        val legacyScanRadius = data.numbers?.get("Block ESP.Scan Radius (blocks)")
        val restored = ArrayList<BlockEspEntry>()
        val ids = HashSet<Int>()
        for (saved in savedEntries) {
            if (saved == null || saved.id < 0 || !ids.add(saved.id)) continue
            if (restored.size >= BlockEspModule.MAX_ENTRIES) break
            val entry = BlockEspModule.INSTANCE.blank(saved.id)
            entry.setBlockId(saved.blockId)
            entry.setFolderId(saved.folderId)
            val savedEnabled = saved.enabled
            val savedBox = saved.box
            val savedFill = saved.fill
            val savedOutline = saved.outline
            val savedShowLabel = saved.showLabel
            val savedTracer = saved.tracer
            val savedConnectTouching = saved.connectTouching
            val savedDepthCheck = saved.depthCheck
            val savedUseCustomRange = saved.useCustomRange
            val savedCustomRange = saved.customRange
            val savedDisplayName = saved.displayName
            if (savedEnabled != null) entry.enabled().setValue(savedEnabled)
            if (savedBox != null) entry.box().setValue(savedBox)
            if (savedFill != null) entry.fill().setValue(savedFill)
            if (savedOutline != null) entry.outline().setValue(savedOutline)
            if (savedShowLabel != null) entry.showLabel().setValue(savedShowLabel)
            if (savedTracer != null) entry.tracer().setValue(savedTracer)
            if (savedConnectTouching != null) entry.connectTouching().setValue(savedConnectTouching)
            if (savedDepthCheck != null) entry.depthCheck().setValue(savedDepthCheck)
            if (savedUseCustomRange != null) entry.useCustomRange().setValue(savedUseCustomRange)
            if (savedCustomRange != null) entry.customRange().setValue(savedCustomRange.toFloat())
            else if (legacyScanRadius != null) entry.customRange().setValue(legacyScanRadius)
            applyColor(entry.outlineColor(), saved.outlineColor)
            applyColor(entry.fillColor(), saved.fillColor)
            if (savedDisplayName != null) entry.displayName().setValue(savedDisplayName)
            applyBlockIslands(entry, saved.islands)
            restored.add(entry)
        }
        BlockEspModule.INSTANCE.restore(restored)
    }

    private fun loadInventoryButtons(data: ModConfig.Data) {
        val restored = InventoryButtonConfigCodec.decode(
            data.inventoryButtons,
            { id -> MacrosModule.INSTANCE.macro(id) != null },
            { name -> InventoryButtonsModule.INSTANCE.isPngIconAllowed(name) },
        )
        InventoryButtonsModule.INSTANCE.restore(restored)
    }

    private fun applyBlockIslands(entry: BlockEspEntry, saved: Map<String, Boolean?>?) {
        if (saved == null) return
        for ((island, setting) in entry.islands()) {
            val wanted = saved[island.name]
            if (wanted != null) setting.setValue(wanted)
        }
    }

    private fun parseIslands(names: List<String?>?): EnumSet<Island> {
        val islands = EnumSet.noneOf(Island::class.java)
        if (names == null) return islands
        for (name in names) {
            if (name == null) continue
            try {
                val island = Island.valueOf(name)
                if (island.selectable()) islands.add(island)
            } catch (_: IllegalArgumentException) {
                // Removed island names do not make the remaining macro unusable.
            }
        }
        return islands
    }

    private fun snapshotMacros(): List<ModConfig.MacroData> {
        val result = ArrayList<ModConfig.MacroData>()
        for (macro in MacrosModule.INSTANCE.macros()) {
            val data = ModConfig.MacroData()
            data.id = macro.id()
            data.folderId = macro.folderId()
            data.name = macro.name()
            data.enabled = macro.enabled()
            data.key = if (macro.keybind().isBound()) macro.keybind().key().getName() else null
            data.modifiers = macro.keybind().modifiers()
            data.triggerContext = macro.triggerContext().name
            data.islandRestricted = macro.islandRestricted()
            val savedIslands = ArrayList<String>()
            data.islands = savedIslands
            for (island in macro.islands()) savedIslands.add(island.name)
            data.steps = MacroStepConfigCodec.encode(macro.steps())
            data.scripts = MacroScriptConfigCodec.encode(macro.scripts())
            data.detachedBlocks = MacroStepConfigCodec.encode(macro.detachedBlocks())
            data.canvasPanX = macro.canvasPanX()
            data.canvasPanY = macro.canvasPanY()
            data.canvasZoom = macro.canvasZoom()
            data.canvasZoomVersion = 2
            result.add(data)
        }
        return result
    }

    private fun snapshotMacroFunctions(): List<ModConfig.MacroFunctionData> {
        val result = ArrayList<ModConfig.MacroFunctionData>()
        for (function in MacrosModule.INSTANCE.functions()) {
            val data = ModConfig.MacroFunctionData()
            data.id = function.id()
            data.name = function.name()
            data.steps = MacroStepConfigCodec.encode(function.steps())
            val parameters = ArrayList<ModConfig.MacroFunctionParameterData>()
            data.parameters = parameters
            for (parameter in function.parameters()) {
                if (parameter == null) continue
                val saved = ModConfig.MacroFunctionParameterData()
                saved.name = parameter.name()
                saved.type = parameter.type().name
                saved.defaultValue = parameter.defaultValue()
                parameters.add(saved)
            }
            result.add(data)
        }
        return result
    }

    private fun parseMacroValueType(value: String?): MacroValue.Type = try {
        MacroValue.Type.valueOf((value ?: "TEXT").uppercase(java.util.Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        MacroValue.Type.TEXT
    }

    /** Restores the old all-islands behavior when an island filter was absent from a saved highlight. */
    private fun applyIslands(highlight: MobHighlight, saved: Map<String, Boolean?>?) {
        if (saved == null) {
            for (island in highlight.islands().values) island.setValue(true)
            return
        }
        for ((island, setting) in highlight.islands()) {
            val wanted = saved[island.name]
            if (wanted != null) setting.setValue(wanted)
        }
    }

    private fun applyColor(setting: ColorSetting, rgba: IntArray?) {
        if (rgba != null && rgba.size == 4) setting.set(rgba[0], rgba[1], rgba[2], rgba[3])
    }

    private fun rgba(setting: ColorSetting): IntArray =
        intArrayOf(setting.red(), setting.green(), setting.blue(), setting.alpha())

    private fun parseCoordKey(key: String): BlockPos? {
        val parts = Pattern.compile(",").split(key)
        if (parts.size != 3) return null
        return try {
            BlockPos(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun coordKey(pos: BlockPos): String = "${pos.x},${pos.y},${pos.z}"

    /** Resolves saved names back to live objects; anything that no longer exists is dropped. */
    private fun loadUiState(data: ModConfig.Data) {
        ClickGuiState.setCategoryScroll(data.uiCategoryScroll)
        ClickGuiState.setGridScrolls(data.uiGridScrollByCategory)
        ClickGuiState.setFavoriteCategoryIds(data.uiFavoriteCategories)
        ClickGuiState.setFavoriteModuleIds(data.uiFavoriteModules)
        if (data.uiCategory != null) {
            for (category in Category.values()) {
                if (category.name == data.uiCategory) {
                    ClickGuiState.setCategory(category)
                    break
                }
            }
        }
        if (data.uiOpenModule == null) return
        for (module in ModuleManager.modules()) {
            if (module.configName() != data.uiOpenModule || !module.hasSettings()) continue
            ClickGuiState.setOpenModule(module)
            ClickGuiState.setSettingsScroll(Math.max(0, data.uiSettingsScroll))
            if (data.uiExpandedColor != null) {
                for (setting in module.colorSettings()) {
                    if (setting.name() == data.uiExpandedColor) {
                        ClickGuiState.setExpandedColor(setting)
                        break
                    }
                }
            }
            return
        }
    }

    private fun readData(): ModConfig.Data {
        val legacy = !Files.exists(PATH) && Files.exists(LEGACY_PATH)
        val source = if (legacy) LEGACY_PATH else PATH
        if (!Files.exists(source)) return ModConfig.Data()

        val parsed: ModConfig.Data? = try {
            Files.newBufferedReader(source).use { GSON.fromJson(it, ModConfig.Data::class.java) }
        } catch (error: IOException) {
            GeilerAddons.LOGGER.error("Failed to load GeilerAddons config from {}, using defaults", source, error)
            return ModConfig.Data()
        } catch (error: JsonParseException) {
            GeilerAddons.LOGGER.error("Failed to load GeilerAddons config from {}, using defaults", source, error)
            return ModConfig.Data()
        }
        val data = parsed ?: ModConfig.Data()
        if (data.enabled == null) data.enabled = HashMap()
        if (data.colors == null) data.colors = HashMap()
        if (data.numbers == null) data.numbers = HashMap()
        if (data.toggles == null) data.toggles = HashMap()
        val migrations = data.migrations
        if (migrations == null) data.migrations = HashSet() else migrations.remove(null)
        if (data.choices == null) data.choices = HashMap()
        if (data.texts == null) data.texts = HashMap()
        if (data.keybinds == null) data.keybinds = HashMap()
        if (data.uiFavoriteCategories == null) data.uiFavoriteCategories = ArrayList()
        if (data.uiFavoriteModules == null) data.uiFavoriteModules = ArrayList()
        if (legacy) migrateLegacyFile()
        return data
    }

    /** Copies the original flat config bytes before this load has produced a complete snapshot. */
    private fun migrateLegacyFile() {
        try {
            Files.createDirectories(DIR)
            Files.copy(LEGACY_PATH, PATH, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(LEGACY_PATH)
            GeilerAddons.LOGGER.info("Migrated GeilerAddons config from {} to {}", LEGACY_PATH, PATH)
        } catch (error: IOException) {
            GeilerAddons.LOGGER.error("Failed to migrate GeilerAddons config from {} to {}", LEGACY_PATH, PATH, error)
        }
    }

    private fun settingKey(module: Module, settingName: String): String = ConfigSettingKeys.settingKey(module, settingName)

    private fun <T> settingValue(values: Map<String, T>, module: Module, settingName: String): T? =
        ConfigSettingKeys.settingValue(values, module, settingName)

    private fun legacySettingKey(module: Module, settingName: String): String? =
        ConfigSettingKeys.legacySettingKey(module, settingName)
}
