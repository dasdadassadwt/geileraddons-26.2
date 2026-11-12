package geiler.addons.client.config

import com.google.gson.JsonArray
import geiler.addons.client.collections.FolderTree
import geiler.addons.client.module.Module
import geiler.addons.client.module.impl.MobHighlight
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

/** Stable configuration entry point and Gson DTOs for the existing on-disk schema. */
class ModConfig private constructor() {
    /** Field names and types here are the existing on-disk JSON schema. */
    class Data {
        @JvmField var enabled: MutableMap<String, Boolean?> = HashMap()
        @JvmField var colors: MutableMap<String, IntArray?> = HashMap()
        @JvmField var numbers: MutableMap<String, Float?> = HashMap()
        @JvmField var toggles: MutableMap<String, Boolean?> = HashMap()
        @JvmField var migrations: MutableSet<String?> = HashSet()
        @JvmField var choices: MutableMap<String, String?> = HashMap()
        @JvmField var texts: MutableMap<String, String?> = HashMap()
        @JvmField var keybinds: MutableMap<String, KeybindData?> = HashMap()
        @JvmField var tikiCoords: List<IntArray?>? = null
        @JvmField var tikiFingerprints: MutableMap<String, String?>? = null
        @JvmField var hudPositions: MutableMap<String, FloatArray?>? = null
        @JvmField var treeGifts: MutableMap<String, LongArray?>? = null
        @JvmField var mobHighlights: List<MobHighlightData?>? = null
        @JvmField var mobHighlightFolders: List<FolderTree.Folder?>? = null
        @JvmField var blockEspEntries: List<BlockEspData?>? = null
        @JvmField var blockEspFolders: List<FolderTree.Folder?>? = null
        @JvmField var uiCategory: String? = null
        @JvmField var uiOpenModule: String? = null
        @JvmField var uiExpandedColor: String? = null
        @JvmField var uiSettingsScroll: Int = 0
        @JvmField var uiCategoryScroll: Int = 0
        @JvmField var uiGridScrollByCategory: MutableMap<String, Int?> = HashMap()
        @JvmField var uiFavoriteCategories: List<String?> = ArrayList()
        @JvmField var uiFavoriteModules: List<String?> = ArrayList()
        @JvmField var checkForUpdates: Boolean? = null
        @JvmField var hypixelModApi: Boolean? = null
        @JvmField var macros: List<MacroData?>? = null
        @JvmField var macroFunctions: List<MacroFunctionData?>? = null
        @JvmField @Transient var macroFiles: List<MacroData>? = null
        @JvmField @Transient var functionFiles: List<MacroFunctionData>? = null
        @JvmField var macroVariables: JsonArray? = null
        @JvmField var macroFolders: List<FolderTree.Folder?>? = null
        @JvmField var inventoryButtons: JsonArray? = null
    }

    class MobHighlightData {
        @JvmField var id: Int = 0
        @JvmField var enabled: Boolean? = null
        @JvmField var matchName: Boolean? = null
        @JvmField var matchText: String? = null
        @JvmField var outlineColor: IntArray? = null
        @JvmField var fillColor: IntArray? = null
        @JvmField var depthCheck: Boolean? = null
        @JvmField var scanInterval: Float? = null
        @JvmField var displayName: String? = null
        @JvmField var islands: MutableMap<String, Boolean?>? = null
        @JvmField var folderId: String? = null
    }

    class BlockEspData {
        @JvmField var id: Int = 0
        @JvmField var blockId: String? = null
        @JvmField var folderId: String? = null
        @JvmField var enabled: Boolean? = null
        @JvmField var box: Boolean? = null
        @JvmField var fill: Boolean? = null
        @JvmField var outline: Boolean? = null
        @JvmField var showLabel: Boolean? = null
        @JvmField var tracer: Boolean? = null
        @JvmField var connectTouching: Boolean? = null
        @JvmField var depthCheck: Boolean? = null
        @JvmField var useCustomRange: Boolean? = null
        @JvmField var customRange: Int? = null
        @JvmField var outlineColor: IntArray? = null
        @JvmField var fillColor: IntArray? = null
        @JvmField var displayName: String? = null
        @JvmField var islands: MutableMap<String, Boolean?>? = null
    }

    class KeybindData {
        @JvmField var key: String? = null
        @JvmField var modifiers: Int = 0
    }

    class MacroData {
        @JvmField var id: Int = 0
        @JvmField var name: String? = null
        @JvmField var enabled: Boolean? = null
        @JvmField var key: String? = null
        @JvmField var modifiers: Int = 0
        @JvmField var triggerContext: String? = null
        @JvmField var islandRestricted: Boolean? = null
        @JvmField var islands: List<String?>? = null
        @JvmField var steps: JsonArray? = null
        @JvmField var scripts: JsonArray? = null
        @JvmField var detachedBlocks: JsonArray? = null
        @JvmField var canvasPanX: Float? = null
        @JvmField var canvasPanY: Float? = null
        @JvmField var canvasZoom: Float? = null
        @JvmField var canvasZoomVersion: Int? = null
        @JvmField var folderId: String? = null
    }

    class MacroFunctionData {
        @JvmField var id: String? = null
        @JvmField var name: String? = null
        @JvmField var parameters: List<MacroFunctionParameterData?>? = null
        @JvmField var steps: JsonArray? = null
    }

    class MacroFunctionParameterData {
        @JvmField var name: String? = null
        @JvmField var type: String? = null
        @JvmField var defaultValue: String? = null
    }

    class MacroIndex {
        @JvmField var version: Int = 0
        @JvmField var macros: List<String?>? = null
        @JvmField var functions: List<String?>? = null
    }

    companion object {
        @JvmStatic fun hypixelModApi(): Boolean = ModConfigCore.hypixelModApi()
        @JvmStatic fun checkForUpdates(): Boolean = ModConfigCore.checkForUpdates()
        @JvmStatic fun resolveModuleEnabledState(savedEnabled: Boolean?, defaultEnabled: Boolean): Boolean =
            ModConfigCore.resolveModuleEnabledState(savedEnabled, defaultEnabled)

        @JvmStatic
        fun resolveLegacyUtilityTimerHud(savedHud: Boolean?, oldEnabled: Boolean?, oldHud: Boolean?): Boolean? =
            ModConfigCore.resolveLegacyUtilityTimerHud(savedHud, oldEnabled, oldHud)

        @JvmStatic
        fun resolveLegacyUtilityTimerAlert(savedAlert: Boolean?, oldEnabled: Boolean?, oldAlert: Boolean?): Boolean? =
            ModConfigCore.resolveLegacyUtilityTimerAlert(savedAlert, oldEnabled, oldAlert)

        @JvmStatic fun load() = ModConfigCore.load()

        @JvmStatic
        fun migrateSameNamedPuzzleSettings(
            toggles: MutableMap<String, Boolean?>,
            numbers: MutableMap<String, Float?>,
            colors: MutableMap<String, IntArray?>,
            choices: MutableMap<String, String?>,
            texts: MutableMap<String, String?>,
            puzzles: Module,
            legacyPrefix: String,
        ): Boolean = ModConfigCore.migrateSameNamedPuzzleSettings(
            toggles, numbers, colors, choices, texts, puzzles, legacyPrefix,
        )

        @JvmStatic
        fun migratePuzzleStrategy(
            toggles: MutableMap<String, Boolean?>,
            choices: MutableMap<String, String?>,
            puzzles: Module,
            oldName: String,
            targetName: String,
            translation: Map<String, Boolean?>,
        ): Boolean = ModConfigCore.migratePuzzleStrategy(
            toggles, choices, puzzles, oldName, targetName, translation,
        )

        @JvmStatic
        fun migrateLegacyModuleEnabledState(
            enabled: MutableMap<String, Boolean?>,
            legacyModuleName: String,
            newModuleName: String,
        ): Boolean = ModConfigCore.migrateLegacyModuleEnabledState(enabled, legacyModuleName, newModuleName)

        @JvmStatic fun markDirty() = ModConfigCore.markDirty()
        @JvmStatic fun flushIfDirty() = ModConfigCore.flushIfDirty()
        @JvmStatic fun flushNow(): Boolean = ModConfigCore.flushNow()
        @JvmStatic fun flushPrepared(): Boolean = ModConfigCore.flushPrepared()
        @JvmStatic fun save() = ModConfigCore.save()
        @JvmStatic fun resetModuleDefaultsExcept(excludedConfigNames: Set<String>) =
            ModConfigCore.resetModuleDefaultsExcept(excludedConfigNames)

        @JvmStatic fun resetNamedModuleDefaults(configNames: Set<String>) =
            ModConfigCore.resetNamedModuleDefaults(configNames)

        @JvmStatic fun resetRuntimeToFactoryDefaults() = ModConfigCore.resetRuntimeToFactoryDefaults()
        @JvmStatic fun saveAfterReset() = ModConfigCore.saveAfterReset()
    }
}
