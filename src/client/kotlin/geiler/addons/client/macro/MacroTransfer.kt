package geiler.addons.client.macro

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.config.MacroScriptConfigCodec
import geiler.addons.client.config.MacroStepConfigCodec
import geiler.addons.client.location.Island
import geiler.addons.client.module.ModuleKeybind
import java.util.ArrayList
import java.util.EnumSet
import java.util.HashMap
import java.util.Locale

/** Safe, versioned clipboard representation for one or more user-authored macros. */
object MacroTransfer {
    private val GSON: Gson = GsonBuilder().disableHtmlEscaping().create()
    private const val FORMAT = "geileraddons-macros"
    /** 7 changes canvas zoom; 8 adds carried-inventory item-count values and conditions. */
    private const val VERSION = 8
    private const val MAX_PAYLOAD_LENGTH = 512_000
    private const val MAX_MACROS = 64
    const val MAX_FUNCTIONS: Int = MacroFunction.MAX_FUNCTIONS
    private const val MAX_STEPS = 512
    private const val MAX_DEPTH = MacroTreeRules.MAX_DEPTH
    private const val MAX_TEXT_LENGTH = 4_096

    /** Encodes the selected macros without their internal ids, so imports never overwrite existing data. */
    @JvmStatic
    fun encode(source: Iterable<MacroDefinition>?): String = encode(source, emptyList())

    @JvmStatic
    fun encode(source: Iterable<MacroDefinition>?, functions: Iterable<MacroFunction>?): String =
        encode(source, functions, emptyList())

    /** Encodes only the referenced global variable definitions, never their runtime values. */
    @JvmStatic
    fun encode(
        source: Iterable<MacroDefinition>?,
        functions: Iterable<MacroFunction>?,
        globalVariables: Iterable<MacroVariableStore.Definition>?,
    ): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        val macros = JsonArray()
        if (source != null) for (macro in source) {
            if (macro == null) continue
            if (macros.size() >= MAX_MACROS) throw IllegalArgumentException(
                "A macro package can contain at most $MAX_MACROS macros.",
            )
            macros.add(writeMacro(macro))
        }
        root.add("macros", macros)
        val functionEntries = JsonArray()
        if (functions != null) for (function in functions) {
            if (function == null) continue
            if (functionEntries.size() >= MAX_FUNCTIONS) throw IllegalArgumentException(
                "A macro package can contain at most $MAX_FUNCTIONS reusable functions.",
            )
            functionEntries.add(writeFunction(function))
        }
        root.add("functions", functionEntries)
        val globalEntries = JsonArray()
        if (globalVariables != null) for (definition in globalVariables) {
            if (globalEntries.size() >= MacroVariableStore.MAX_VARIABLES) break
            if (definition == null) continue
            val value = JsonObject()
            value.addProperty("id", limited(definition.id(), 64))
            value.addProperty("name", limited(definition.name(), 32))
            value.addProperty("type", definition.type().name)
            globalEntries.add(value)
        }
        root.add("globalVariables", globalEntries)
        return GSON.toJson(root)
    }

    /**
     * Decodes clipboard data and assigns fresh ids starting at [firstId]. Invalid input never
     * changes module state; callers can show the returned error directly to the user.
     */
    @JvmStatic
    fun decode(payload: String?, firstId: Int): ImportResult {
        if (payload == null || MacroJavaStrings.isBlank(payload)) return failure("Clipboard is empty.")
        if (payload.length > MAX_PAYLOAD_LENGTH) return failure("Clipboard data is too large.")
        try {
            val parsed = JsonParser.parseString(payload)
            if (!parsed.isJsonObject) return failure("Clipboard data is not a macro package.")
            val root = parsed.asJsonObject
            if (FORMAT != string(root, "format", "")) return failure("Unknown macro package format.")
            val version = integer(root, "version", -1)
            if (version < 1 || version > VERSION) return failure("Unsupported macro package version.")
            val entries = array(root, "macros")
            if (entries == null || entries.size() == 0) return failure("The macro package contains no macros.")

            val result = ArrayList<MacroDefinition>()
            val macroIds = HashMap<Int, Int>()
            for (entry in entries) {
                if (result.size >= MAX_MACROS) break
                if (!entry.isJsonObject) continue
                val value = entry.asJsonObject
                val newId = firstId + result.size
                val macro = readMacro(value, newId, version)
                if (macro != null) {
                    val oldId = integer(value, "id", -1)
                    if (oldId >= 0) macroIds[oldId] = newId
                    result.add(macro)
                }
            }
            if (result.isEmpty()) return failure("The macro package contains no valid macros.")
            val importedFunctions = ArrayList<MacroFunction>()
            val functionIds = HashMap<String, String>()
            val functionSource = array(root, "functions")
            if (functionSource != null) {
                if (functionSource.size() > MAX_FUNCTIONS) {
                    return failure("The macro package has more than $MAX_FUNCTIONS reusable functions.")
                }
                for (entry in functionSource) {
                    if (!entry.isJsonObject) continue
                    val functionImport = readFunction(entry.asJsonObject) ?: continue
                    importedFunctions.add(functionImport.function)
                    functionIds[functionImport.sourceId] = functionImport.function.id()
                }
            }
            for (macro in result) for (script in macro.scripts()) {
                remapReferences(script.steps(), macroIds, functionIds, 0)
            }
            for (macro in result) remapReferences(macro.detachedBlocks(), macroIds, functionIds, 0)
            for (function in importedFunctions) remapReferences(function.steps(), macroIds, functionIds, 0)
            val importedGlobals = readGlobalVariables(if (version >= 4) array(root, "globalVariables") else null)
            return ImportResult(java.util.List.copyOf(result), java.util.List.copyOf(importedFunctions),
                java.util.List.copyOf(importedGlobals), null)
        } catch (_: RuntimeException) {
            return failure("The clipboard does not contain valid macro data.")
        }
    }

    private fun failure(message: String): ImportResult =
        ImportResult(emptyList(), emptyList(), emptyList(), message)

    private fun readGlobalVariables(source: JsonArray?): List<MacroVariableStore.Definition> {
        val result = ArrayList<MacroVariableStore.Definition>()
        if (source == null) return result
        for (entry in source) {
            if (result.size >= MacroVariableStore.MAX_VARIABLES) break
            if (!entry.isJsonObject) continue
            val value = entry.asJsonObject
            val id = string(value, "id", "")
            val name = limited(string(value, "name", ""), 32)
            if (MacroJavaStrings.isBlank(id) || id.length > 64 || MacroJavaStrings.isBlank(name)) continue
            result.add(MacroVariableStore.Definition(id, name, valueType(string(value, "type", "TEXT")), false))
        }
        return result
    }

    private fun writeMacro(macro: MacroDefinition): JsonObject {
        val result = JsonObject()
        result.addProperty("id", macro.id())
        result.addProperty("name", macro.name())
        result.addProperty("enabled", macro.enabled())
        if (macro.keybind().isBound()) {
            result.addProperty("key", macro.keybind().key().name)
            result.addProperty("modifiers", macro.keybind().modifiers())
        }
        result.addProperty("triggerContext", macro.triggerContext().name)
        result.addProperty("islandRestricted", macro.islandRestricted())
        val islands = JsonArray()
        for (island in macro.islands()) islands.add(island.name)
        result.add("islands", islands)
        result.add("steps", MacroStepConfigCodec.encode(macro.steps()))
        result.add("scripts", MacroScriptConfigCodec.encode(macro.scripts()))
        result.add("detachedBlocks", MacroStepConfigCodec.encode(macro.detachedBlocks()))
        result.addProperty("canvasPanX", macro.canvasPanX())
        result.addProperty("canvasPanY", macro.canvasPanY())
        result.addProperty("canvasZoom", macro.canvasZoom())
        result.addProperty("canvasZoomVersion", 2)
        return result
    }

    private fun writeFunction(function: MacroFunction): JsonObject {
        val result = JsonObject()
        result.addProperty("id", function.id())
        result.addProperty("name", function.name())
        result.addProperty("canvasX", function.canvasX())
        result.addProperty("canvasY", function.canvasY())
        val parameters = JsonArray()
        for (parameter in function.parameters()) {
            if (parameters.size() >= 32) break
            if (parameter == null) throw NullPointerException()
            val value = JsonObject()
            value.addProperty("name", parameter.name())
            value.addProperty("type", parameter.type().name)
            value.addProperty("defaultValue", parameter.defaultValue())
            parameters.add(value)
        }
        result.add("parameters", parameters)
        result.add("steps", MacroStepConfigCodec.encode(function.steps()))
        return result
    }

    private fun readMacro(value: JsonObject, id: Int, version: Int): MacroDefinition {
        val result = MacroDefinition(id)
        result.setName(limited(string(value, "name", result.name()), 48))
        result.setEnabled(booleanValue(value, "enabled", true))
        result.setTriggerContext(triggerContext(string(value, "triggerContext", MacroTriggerContext.ANY_NON_TEXT_SCREEN.name)))
        result.setIslandRestricted(booleanValue(value, "islandRestricted", false))
        result.setIslands(readIslands(array(value, "islands")))
        val key = string(value, "key", "")
        if (!MacroJavaStrings.isBlank(key)) try {
            result.setKeybind(ModuleKeybind(InputConstants.getKey(key), integer(value, "modifiers", 0)))
        } catch (_: RuntimeException) {
            // A bad shared key is safer as unbound than as an import failure.
        }
        if (version >= 3 && array(value, "scripts") != null) {
            result.restoreScripts(MacroScriptConfigCodec.decode(array(value, "scripts")))
        } else {
            val steps = MacroStepConfigCodec.decode(array(value, "steps"))
            if (steps.size > MAX_STEPS) throw IllegalArgumentException("Macro package contains too many steps")
            result.steps().addAll(steps)
        }
        result.restoreDetachedBlocks(MacroStepConfigCodec.decode(array(value, "detachedBlocks")))
        var canvasZoom = decimal(value, "canvasZoom", 1f)
        val canvasZoomVersion = integer(value, "canvasZoomVersion", 0)
        if (canvasZoomVersion < 1) canvasZoom *= 100.0f
        if (canvasZoomVersion < 2) canvasZoom *= 100.0f
        result.setCanvasView(decimal(value, "canvasPanX", 0f), decimal(value, "canvasPanY", 0f), canvasZoom)
        return result
    }

    private fun readFunction(value: JsonObject): FunctionImport {
        val sourceId = string(value, "id", "")
        val function = MacroFunction()
        function.setName(limited(string(value, "name", "My Block"), 48))
        function.setCanvasPosition(decimal(value, "canvasX", 0f), decimal(value, "canvasY", 0f))
        val parameters = array(value, "parameters")
        if (parameters != null) for (entry in parameters) {
            if (function.parameters().size >= 32) break
            if (!entry.isJsonObject) continue
            val item = entry.asJsonObject
            function.parameters().add(MacroFunction.Parameter(string(item, "name", "value"),
                valueType(string(item, "type", "TEXT")), limited(string(item, "defaultValue", ""), 128)))
        }
        function.steps().addAll(MacroStepConfigCodec.decode(array(value, "steps")))
        return FunctionImport(sourceId, function)
    }

    private fun remapReferences(
        steps: List<MacroStep?>?,
        macroIds: Map<Int, Int>,
        functionIds: Map<String, String>,
        depth: Int,
    ) {
        if (steps == null || depth > MAX_DEPTH) return
        for (step in steps) when (step) {
            is MacroStep.MacroCall -> step.setMacroId(macroIds[step.macroId()] ?: -1)
            is MacroStep.FunctionCall -> step.setFunctionId(functionIds[step.functionId()] ?: "")
            is MacroStep.IfElse -> {
                remapReferences(step.thenSteps(), macroIds, functionIds, depth + 1)
                remapReferences(step.elseSteps(), macroIds, functionIds, depth + 1)
            }
            is MacroStep.Repeat -> remapReferences(step.steps(), macroIds, functionIds, depth + 1)
            is MacroStep.RepeatUntil -> remapReferences(step.steps(), macroIds, functionIds, depth + 1)
            is MacroStep.Switch -> {
                for (branch in step.cases()) remapReferences(branch.steps(), macroIds, functionIds, depth + 1)
                remapReferences(step.defaultSteps(), macroIds, functionIds, depth + 1)
            }
            else -> Unit
        }
    }

    private fun valueType(raw: String?): MacroValue.Type = try {
        MacroValue.Type.valueOf((raw ?: "TEXT").uppercase(Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        MacroValue.Type.TEXT
    }

    private fun readIslands(source: JsonArray?): List<Island> {
        if (source == null) return emptyList()
        val result = EnumSet.noneOf(Island::class.java)
        for (entry in source) try {
            val island = Island.valueOf(entry.asString)
            if (island.selectable()) result.add(island)
        } catch (_: RuntimeException) {
            // Unknown islands are ignored so imports survive newer/older island lists.
        }
        return java.util.List.copyOf(result)
    }

    private fun triggerContext(value: String): MacroTriggerContext = try {
        MacroTriggerContext.valueOf(value)
    } catch (_: IllegalArgumentException) {
        MacroTriggerContext.ANY_NON_TEXT_SCREEN
    }

    private fun array(objectValue: JsonObject, name: String): JsonArray? {
        val value = objectValue.get(name)
        return if (value != null && value.isJsonArray) value.asJsonArray else null
    }

    private fun string(objectValue: JsonObject, name: String, fallback: String): String {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try {
            value.asString
        } catch (_: RuntimeException) {
            fallback
        }
    }

    private fun integer(objectValue: JsonObject, name: String, fallback: Int): Int {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try {
            value.asInt
        } catch (_: RuntimeException) {
            fallback
        }
    }

    private fun decimal(objectValue: JsonObject, name: String, fallback: Float): Float {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try {
            value.asFloat
        } catch (_: RuntimeException) {
            fallback
        }
    }

    private fun booleanValue(objectValue: JsonObject, name: String, fallback: Boolean): Boolean {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try {
            value.asBoolean
        } catch (_: RuntimeException) {
            fallback
        }
    }

    private fun limited(value: String?, max: Int): String {
        if (value == null) return ""
        return if (value.length <= max) value else value.substring(0, max)
    }

    data class ImportResult private constructor(
        private val macrosValue: List<MacroDefinition>,
        private val functionsValue: List<MacroFunction>,
        private val globalVariablesValue: List<MacroVariableStore.Definition>,
        private val errorValue: String?,
        @Suppress("unused") private val normalizedMarker: Unit,
    ) {
        constructor(
            macros: List<MacroDefinition>?,
            functions: List<MacroFunction>?,
            globalVariables: List<MacroVariableStore.Definition>?,
            error: String?,
        ) : this(
            java.util.List.copyOf(macros ?: emptyList()),
            java.util.List.copyOf(functions ?: emptyList()),
            java.util.List.copyOf(globalVariables ?: emptyList()),
            error,
            Unit,
        )

        constructor(macros: List<MacroDefinition>?, functions: List<MacroFunction>?, error: String?) :
            this(macros, functions, emptyList(), error)

        constructor(macros: List<MacroDefinition>?, error: String?) :
            this(macros, emptyList(), emptyList(), error)

        fun macros(): List<MacroDefinition> = macrosValue
        fun functions(): List<MacroFunction> = functionsValue
        fun globalVariables(): List<MacroVariableStore.Definition> = globalVariablesValue
        fun error(): String? = errorValue
        fun success(): Boolean = errorValue == null && macrosValue.isNotEmpty()

        override fun toString(): String = macroRecordString(
            "ImportResult", "macros" to macros(), "functions" to functions(),
            "globalVariables" to globalVariables(), "error" to error(),
        )
    }

    private data class FunctionImport(val sourceId: String, val function: MacroFunction)
}
