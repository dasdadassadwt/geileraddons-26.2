package geiler.addons.client.module.impl

import geiler.addons.client.collections.FolderTree
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.gui.FolderManagerScreen
import geiler.addons.client.gui.MacroShareScreen
import geiler.addons.client.gui.ScratchMacroEditorScreen
import geiler.addons.client.location.Island
import geiler.addons.client.macro.MacroCondition
import geiler.addons.client.macro.MacroDefinition
import geiler.addons.client.macro.MacroFunction
import geiler.addons.client.macro.MacroJavaStrings
import geiler.addons.client.macro.MacroRunner
import geiler.addons.client.macro.MacroScript
import geiler.addons.client.macro.MacroStep
import geiler.addons.client.macro.MacroTransfer
import geiler.addons.client.macro.MacroTriggerContext
import geiler.addons.client.macro.MacroValue
import geiler.addons.client.macro.MacroVariableStore
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.ModuleKeybindManager
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.EnumMap
import java.util.EnumSet
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.function.BiConsumer
import java.util.function.Supplier

/** Stores and exposes user-authored keyboard workflows. */
class MacrosModule private constructor(
    settings: Settings = Settings(),
) : Module(
    "Macros",
    "Build workflows from Minecraft inputs. Each node has its own optional delay and start context.",
    Category.MISCELLANEOUS,
    *settings.allSettings(),
) {
    companion object {
        private const val MAX_EXPORTED_MACROS = 64

        private val ACTIONS_NODE_COLOR = ColorSetting("Node Color · Actions", 76, 151, 255, 255)
        private val CONTROL_NODE_COLOR = ColorSetting("Node Color · Control", 255, 171, 25, 255)
        private val INPUT_NODE_COLOR = ColorSetting("Node Color · Player Input", 15, 189, 140, 255)
        private val INVENTORY_NODE_COLOR = ColorSetting("Node Color · Inventory", 125, 119, 245, 255)
        private val DISPLAY_NODE_COLOR = ColorSetting("Node Color · Display", 46, 184, 212, 255)
        private val DATA_NODE_COLOR = ColorSetting("Node Color · Data", 226, 196, 50, 255)
        private val REUSABLE_NODE_COLOR = ColorSetting("Node Color · Reusable", 255, 102, 128, 255)
        private val WORLD_NODE_COLOR = ColorSetting("Node Color · World", 102, 142, 61, 255)
        private val CONTEXT_CHOICES = arrayOf(
            "World only (no screen)",
            "Container/inventory screens",
            "World or container",
            "Any non-text screen",
        )

        @JvmField
        val INSTANCE = MacrosModule()

        private fun context(value: String?): MacroTriggerContext = when (value ?: "") {
            "World only", "World only (no screen)" -> MacroTriggerContext.WORLD_ONLY
            "Container only", "Container/inventory screens" -> MacroTriggerContext.CONTAINER_ONLY
            "World + containers", "World or container" -> MacroTriggerContext.WORLD_AND_CONTAINER
            else -> MacroTriggerContext.ANY_NON_TEXT_SCREEN
        }

        private fun contextLabel(context: MacroTriggerContext): String = context.label()

        /** Client-side note for a refusal the player asked about; the same wording the runner uses. */
        private fun message(text: String) {
            val minecraft = Minecraft.getInstance()
            if (minecraft != null && minecraft.gui != null) {
                minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal("[Macros] $text"))
            }
        }
    }

    private class Settings {
        val systemEnabled = BooleanSetting("Enable Macro System", true)
        val replayBlocked = ModuleAction(
            "Replay Last Blocked Macro",
            "Starts the chat-triggered macro stack that was most recently refused.",
            Runnable { MacroRunner.replayLastBlocked() },
        )
        val create = ModuleAction(
            "Create Macro",
            "Create a new macro workflow.",
            Runnable { INSTANCE.create() },
        )
        val copyShared = ModuleAction(
            "Share Macros",
            "Opens a list of all macros; switch on the ones to share, then press Copy.",
            Runnable { INSTANCE.openShare() },
        )
        val paste = ModuleAction(
            "Paste Macros",
            "Appends the macros in a shared package from the clipboard as new entries.",
            Runnable { INSTANCE.pasteShared() },
        )
        val foldersAction = ModuleAction(
            "Manage Folders",
            "Organize macros into nested folders.",
            Runnable { INSTANCE.openFolders() },
        )

        fun allSettings(): Array<Setting> = arrayOf(
            systemEnabled,
            replayBlocked,
            create,
            copyShared,
            paste,
            foldersAction,
            ACTIONS_NODE_COLOR,
            CONTROL_NODE_COLOR,
            INPUT_NODE_COLOR,
            INVENTORY_NODE_COLOR,
            DISPLAY_NODE_COLOR,
            DATA_NODE_COLOR,
            REUSABLE_NODE_COLOR,
            WORLD_NODE_COLOR,
        )
    }

    private val macros = ArrayList<MacroDefinition>()
    private val functions = ArrayList<MacroFunction>()
    private val globalVariables = MacroVariableStore(Runnable { ModConfig.markDirty() })
    private val controls = HashMap<Int, Controls>()
    private val systemEnabled = settings.systemEnabled
    private val replayBlocked = settings.replayBlocked
    private val createAction = settings.create
    private val copySharedAction = settings.copyShared
    private val pasteAction = settings.paste
    private val foldersAction = settings.foldersAction
    private val folders = FolderTree()
    private var nextId = 0

    fun macros(): List<MacroDefinition> {
        syncSettings()
        return Collections.unmodifiableList(ArrayList(macros))
    }

    fun macro(id: Int): MacroDefinition? {
        for (macro in macros) if (macro.id() == id) return macro
        return null
    }

    fun functions(): List<MacroFunction> = Collections.unmodifiableList(ArrayList(functions))
    fun actionsNodeColor(): Int = ACTIONS_NODE_COLOR.argb()
    fun controlNodeColor(): Int = CONTROL_NODE_COLOR.argb()
    fun inputNodeColor(): Int = INPUT_NODE_COLOR.argb()
    fun inventoryNodeColor(): Int = INVENTORY_NODE_COLOR.argb()
    fun displayNodeColor(): Int = DISPLAY_NODE_COLOR.argb()
    fun dataNodeColor(): Int = DATA_NODE_COLOR.argb()
    fun reusableNodeColor(): Int = REUSABLE_NODE_COLOR.argb()
    fun worldNodeColor(): Int = WORLD_NODE_COLOR.argb()

    /** Shared variable definitions and values, available to all macros and function calls. */
    fun globalVariables(): MacroVariableStore = globalVariables

    fun function(id: String?): MacroFunction? {
        if (id == null) return null
        for (function in functions) if (function.id() == id) return function
        return null
    }

    fun createFunction(): MacroFunction? {
        if (functions.size >= MacroFunction.MAX_FUNCTIONS) {
            val minecraft = Minecraft.getInstance()
            if (minecraft.gui != null) {
                minecraft.gui.hud.getChat().addClientSystemMessage(
                    Component.literal("[Macros] Limit ${MacroFunction.MAX_FUNCTIONS} reusable functions reached."),
                )
            }
            return null
        }
        val function = MacroFunction()
        function.setName("My Block ${functions.size + 1}")
        functions.add(function)
        ModConfig.markDirty()
        return function
    }

    fun restoreFunctions(restored: List<MacroFunction>?) {
        functions.clear()
        if (restored != null) {
            val ids = HashSet<String>()
            for (function in restored) {
                if (functions.size >= MacroFunction.MAX_FUNCTIONS) break
                if (function != null && ids.add(function.id())) functions.add(function)
            }
        }
    }

    fun folders(): FolderTree = folders

    private fun create() {
        createMacro()
    }

    /** Creates and registers a blank macro so another editor can immediately open its workflow. */
    fun createMacro(): MacroDefinition {
        val macro = MacroDefinition(nextId++)
        macros.add(macro)
        controls[macro.id()] = Controls(macro)
        ModConfig.markDirty()
        return macro
    }

    private fun remove(macro: MacroDefinition) {
        MacroRunner.cancelMacro(macro, "macro deleted")
        macros.remove(macro)
        controls.remove(macro.id())
        shareMarks.remove(macro.id())
        InventoryButtonsModule.INSTANCE.unassignMacro(macro.id())
        ModConfig.markDirty()
    }

    fun restore(restored: List<MacroDefinition>?) {
        macros.clear()
        controls.clear()
        if (restored != null) macros.addAll(restored)
        nextId = 0
        for (macro in macros) {
            nextId = maxOf(nextId, macro.id() + 1)
            controls[macro.id()] = Controls(macro)
        }
    }

    override fun groups(): List<SettingGroup> {
        syncSettings()
        val groups = ArrayList<SettingGroup>(3)
        groups.add(SettingGroup(null, systemEnabled, replayBlocked, createAction, copySharedAction, pasteAction, foldersAction))
        val library = ArrayList<SettingGroup>(macros.size + folders.folders().size)
        for (folder in folders.childrenOf(null)) library.add(folderGroup(folder))
        for (macro in macros) if (!folders.contains(macro.folderId())) library.add(macroGroup(macro))
        groups.add(SettingGroup("Macro Library", null, true, emptyList(), library, false, "macro-library"))
        groups.add(
            SettingGroup.folded(
                "Macro Node Colors",
                ACTIONS_NODE_COLOR,
                CONTROL_NODE_COLOR,
                INPUT_NODE_COLOR,
                INVENTORY_NODE_COLOR,
                DISPLAY_NODE_COLOR,
                DATA_NODE_COLOR,
                REUSABLE_NODE_COLOR,
                WORLD_NODE_COLOR,
            ).keyed("macro-node-colors"),
        )
        return groups
    }

    private fun folderGroup(folder: FolderTree.Folder): SettingGroup {
        val children = ArrayList<SettingGroup>()
        for (child in folders.childrenOf(folder.id)) children.add(folderGroup(child))
        for (macro in macros) {
            if (folder.id == macro.folderId()) children.add(macroGroup(macro))
        }
        return SettingGroup(folder.name, null, false, emptyList(), children, false, "macro-folder:${folder.id}")
    }

    private fun macroGroup(macro: MacroDefinition): SettingGroup {
        val currentControls = controlsById(macro)
        val captureLabel = if (ModuleKeybindManager.bindingMacro() === macro) {
            "Listening… click to cancel"
        } else {
            "Hotkey: ${macro.keybind().displayName()}"
        }
        val capture = ModuleAction(
            captureLabel,
            "Click, then press and release a key or key combination. Press Escape to clear it; click again to cancel listening.",
            Runnable {
                if (ModuleKeybindManager.bindingMacro() === macro) ModuleKeybindManager.cancelBinding()
                else ModuleKeybindManager.beginMacroBinding(macro)
            },
        )
        val edit = ModuleAction(
            "Edit Workflow",
            "Open the node editor to add inputs, set node delays, edit conditions, and build examples.",
            Runnable { openEditor(macro) },
        )
        val delete = ModuleAction(
            "Delete Macro",
            "Remove this macro and its workflow. A running instance is stopped first.",
            Runnable { remove(macro) },
        )
        val macroGroup = SettingGroup(
            "Macro ${macro.id()} • ${macro.name()}",
            currentControls.enabled,
            currentControls.name,
            currentControls.context,
            currentControls.islandRestricted,
            capture,
            edit,
            delete,
        )
        // Keyed per macro: the heading text is the same for every macro, so without this the fold
        // state of one macro's Islands section would apply to all of them.
        val islands = SettingGroup.folded("Islands", *currentControls.islands.values.toTypedArray())
            .keyed("macro-islands:${macro.id()}")
        return macroGroup.containing(islands).keyed("macro-entry:${macro.id()}")
    }

    private fun controlsById(macro: MacroDefinition): Controls =
        controls.computeIfAbsent(macro.id()) { Controls(macro) }

    private fun openEditor(macro: MacroDefinition) {
        val minecraft = Minecraft.getInstance()
        minecraft.gui.setScreen(ScratchMacroEditorScreen(minecraft.gui.screen(), macro))
    }

    /** Ids switched on in the Share list; lives only for this session. */
    private val shareMarks = HashSet<Int>()

    fun isMarkedForShare(id: Int): Boolean = shareMarks.contains(id)

    fun setMarkedForShare(id: Int, marked: Boolean) {
        if (marked) shareMarks.add(id) else shareMarks.remove(id)
    }

    /** Macros switched on in the Share list. */
    fun sharedMacros(): List<MacroDefinition> = macros.filter { shareMarks.contains(it.id()) }

    private fun openShare() {
        val minecraft = Minecraft.getInstance()
        minecraft.gui.setScreen(MacroShareScreen(minecraft.gui.screen()))
    }

    /** Copies the switched-on macros and returns the line the Share screen shows. */
    fun copyMarkedMacros(): String {
        val selected = sharedMacros()
        if (selected.isEmpty()) return "Switch on at least one macro first."
        return try {
            Minecraft.getInstance().keyboardHandler.setClipboard(exportEncoded(selected))
            "Copied ${selected.size} macro(s) to the clipboard."
        } catch (invalid: IllegalArgumentException) {
            invalid.message ?: "The selected macros exceed the sharing limits."
        }
    }

    private fun pasteShared() {
        val result = importEncoded(Minecraft.getInstance().keyboardHandler.getClipboard())
        note(if (result.success()) "Pasted ${result.macros().size} macro(s) as new entries." else result.error()!!)
    }

    private fun note(text: String) {
        Minecraft.getInstance().gui?.hud?.getChat()?.addClientSystemMessage(Component.literal("[Macros] $text"))
    }

    private fun openFolders() {
        val minecraft = Minecraft.getInstance()
        minecraft.gui.setScreen(
            FolderManagerScreen(
                minecraft.gui.screen(),
                "Macros",
                folders,
                Supplier {
                    Collections.unmodifiableList(macros.map { macro ->
                        FolderManagerScreen.Entry(
                            macro.id().toString(),
                            "Macro ${macro.id()} • ${macro.name()}",
                            macro.folderId(),
                        )
                    })
                },
                BiConsumer<String?, String?> { entryId, folderId ->
                    entryId?.toIntOrNull()?.let { id -> macro(id)?.setFolderId(folderId) }
                },
                Runnable { ModConfig.markDirty() },
            ),
        )
    }

    /** Appends imported macros with fresh ids and leaves all existing macros untouched. */
    fun importEncoded(payload: String): MacroTransfer.ImportResult {
        syncSettings()
        val result = MacroTransfer.decode(payload, nextId)
        if (!result.success()) return result
        val resultingFunctionIds = HashSet<String>()
        for (function in functions) resultingFunctionIds.add(function.id())
        for (function in result.functions()) resultingFunctionIds.add(function.id())
        if (resultingFunctionIds.size > MacroFunction.MAX_FUNCTIONS) {
            return MacroTransfer.ImportResult(
                emptyList(),
                emptyList(),
                emptyList(),
                "Import would exceed the ${MacroFunction.MAX_FUNCTIONS} reusable-function limit.",
            )
        }
        for (macro in result.macros()) {
            macros.add(macro)
            controls[macro.id()] = Controls(macro)
        }
        for (function in result.functions()) {
            if (this.function(function.id()) == null) functions.add(function)
        }
        globalVariables.importDefinitions(result.globalVariables())
        nextId += result.macros().size
        ModConfig.markDirty()
        return result
    }

    /** Exports selected macros together with every referenced macro and reusable function. */
    fun exportEncoded(selected: Iterable<MacroDefinition>?): String {
        val knownMacros = HashMap<Int, MacroDefinition>()
        for (macro in macros()) knownMacros[macro.id()] = macro
        val knownFunctions = HashMap<String, MacroFunction>()
        for (function in functions()) knownFunctions[function.id()] = function
        val includedMacros = LinkedHashMap<Int, MacroDefinition>()
        val includedFunctions = LinkedHashMap<String, MacroFunction>()
        val globalVariableIds = LinkedHashSet<String>()
        val macroQueue = ArrayDeque<Int>()
        val functionQueue = ArrayDeque<String>()
        if (selected != null) {
            for (macro in selected) if (macro != null) macroQueue.add(macro.id())
        }
        while (macroQueue.isNotEmpty() || functionQueue.isNotEmpty()) {
            if (macroQueue.isNotEmpty()) {
                val id = macroQueue.removeFirst()
                val macro = knownMacros[id]
                if (macro == null || includedMacros.containsKey(id)) continue
                if (includedMacros.size >= MAX_EXPORTED_MACROS) {
                    throw IllegalArgumentException(
                        "Export needs more than ${MAX_EXPORTED_MACROS} linked macros; choose fewer macros or remove some calls.",
                    )
                }
                includedMacros[id] = macro
                for (script in macro.scripts()) {
                    collectReferences(script.steps(), macroQueue, functionQueue, globalVariableIds, 0)
                }
            } else {
                val id = functionQueue.removeFirst()
                val function = knownFunctions[id]
                if (function == null || includedFunctions.containsKey(id)) continue
                if (includedFunctions.size >= MacroFunction.MAX_FUNCTIONS) {
                    throw IllegalArgumentException(
                        "Export needs more than ${MacroFunction.MAX_FUNCTIONS} linked reusable functions; choose fewer macros or remove some calls.",
                    )
                }
                includedFunctions[id] = function
                collectReferences(function.steps(), macroQueue, functionQueue, globalVariableIds, 0)
            }
        }
        val includedVariables = ArrayList<MacroVariableStore.Definition>()
        for (id in globalVariableIds) {
            val definition = globalVariables.definition(id)
            if (definition != null) includedVariables.add(definition)
        }
        return MacroTransfer.encode(includedMacros.values, includedFunctions.values, includedVariables)
    }

    private fun collectReferences(
        steps: List<MacroStep>?,
        macroQueue: ArrayDeque<Int>,
        functionQueue: ArrayDeque<String>,
        globalVariableIds: MutableSet<String>,
        depth: Int,
    ) {
        if (steps == null || depth > 32) return
        for (step in steps) {
            when (step) {
                is MacroStep.Command -> collectGlobalTemplateReferences(step.command(), globalVariableIds)
                is MacroStep.Chat -> collectGlobalTemplateReferences(step.message(), globalVariableIds)
                is MacroStep.FunctionCall -> {
                    if (!MacroJavaStrings.isBlank(step.functionId())) functionQueue.add(step.functionId())
                    for (argument in step.arguments()) collectValueReference(argument, globalVariableIds)
                }
                is MacroStep.MacroCall -> {
                    if (step.macroId() >= 0) macroQueue.add(step.macroId())
                    // A gated call can reference a global variable inside its condition, which an
                    // import has to carry along or the imported copy would read a variable that does
                    // not exist on the receiving client.
                    collectConditionReference(step.condition(), globalVariableIds)
                }
                is MacroStep.IfElse -> {
                    collectConditionReference(step.condition(), globalVariableIds)
                    collectReferences(step.thenSteps(), macroQueue, functionQueue, globalVariableIds, depth + 1)
                    collectReferences(step.elseSteps(), macroQueue, functionQueue, globalVariableIds, depth + 1)
                }
                is MacroStep.Repeat -> collectReferences(step.steps(), macroQueue, functionQueue, globalVariableIds, depth + 1)
                is MacroStep.RepeatUntil -> {
                    collectConditionReference(step.condition(), globalVariableIds)
                    collectReferences(step.steps(), macroQueue, functionQueue, globalVariableIds, depth + 1)
                }
                is MacroStep.WaitUntil -> collectConditionReference(step.condition(), globalVariableIds)
                is MacroStep.Wait -> if (step.mode() == MacroStep.Wait.Mode.CONDITION) {
                    collectConditionReference(step.condition(), globalVariableIds)
                }
                is MacroStep.UpdateVariable -> {
                    step.globalVariableId()?.let(globalVariableIds::add)
                    collectValueReference(step.value(), globalVariableIds)
                }
                is MacroStep.Switch -> {
                    step.globalVariableId()?.let(globalVariableIds::add)
                    for (branch in step.cases()) {
                        collectReferences(branch.steps(), macroQueue, functionQueue, globalVariableIds, depth + 1)
                    }
                    collectReferences(step.defaultSteps(), macroQueue, functionQueue, globalVariableIds, depth + 1)
                }
                else -> Unit
            }
        }
    }

    private fun collectConditionReference(condition: MacroCondition?, globalVariableIds: MutableSet<String>) {
        when (condition) {
            is MacroCondition.Variable -> {
                condition.globalVariableId()?.let(globalVariableIds::add)
                collectValueReference(condition.value(), globalVariableIds)
            }
            is MacroCondition.All -> {
                for (child in condition.children()) collectConditionReference(child, globalVariableIds)
            }
            is MacroCondition.Any -> {
                for (child in condition.children()) collectConditionReference(child, globalVariableIds)
            }
            is MacroCondition.Not -> collectConditionReference(condition.child(), globalVariableIds)
            else -> Unit
        }
    }

    private fun collectValueReference(value: MacroValue?, globalVariableIds: MutableSet<String>) {
        if (value != null && value.variableReference() && value.scope() == MacroValue.Scope.GLOBAL) {
            globalVariableIds.add(value.value())
        }
    }

    private fun collectGlobalTemplateReferences(text: String?, globalVariableIds: MutableSet<String>) {
        if (text == null) return
        val templatePrefix = "\$" + "{global:"
        var start = text.indexOf(templatePrefix)
        while (start >= 0) {
            val valueStart = start + templatePrefix.length
            val end = text.indexOf('}', valueStart)
            if (end < 0) return
            val id = text.substring(valueStart, end)
            if (!MacroJavaStrings.isBlank(id) && id.length <= 64) globalVariableIds.add(id)
            start = text.indexOf(templatePrefix, end + 1)
        }
    }

    /** Copies the flat settings rows back to their richer macro objects before a config snapshot. */
    fun syncSettings() {
        for (macro in macros) {
            val current = controls[macro.id()] ?: continue
            macro.setEnabled(current.enabled.value())
            macro.setName(current.name.value())
            macro.setTriggerContext(context(current.context.value()))
            macro.setIslandRestricted(current.islandRestricted.value())
            val selected = EnumSet.noneOf(Island::class.java)
            for ((island, setting) in current.islands) {
                if (setting.value()) selected.add(island)
            }
            macro.setIslands(selected)
        }
    }

    override fun settingTooltip(setting: Setting): String? = when {
        setting === systemEnabled ->
            "Master toggle for all macro hotkeys and running workflows. The module card must also be enabled."
        setting is BooleanSetting && setting.displayName() == "Macro Enabled" ->
            "Allow this macro to start from its hotkey without deleting its workflow."
        setting is TextSetting && setting.displayName() == "Rename Macro" ->
            "Type the macro name to show in the macro list, hotkey banner, editor, and logs. Press Enter to finish."
        setting is ChoiceSetting && setting.name() == "Trigger Context" ->
            MacroTriggerContext.values().firstOrNull { it.label() == setting.value() }?.description()
                ?: "Choose where the macro hotkey is allowed to start."
        else -> null
    }

    override fun onDisable() {
        MacroRunner.cancel("module disabled")
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, "Macros", false)
    }

    override fun isActive(): Boolean = isEnabled() && systemEnabled.value()

    override fun inactiveReason(): String? =
        if (isEnabled() && !systemEnabled.value()) "Macro System is disabled." else null

    fun tick() {
        syncSettings()
        if (!isActive()) {
            if (MacroRunner.isRunning()) MacroRunner.cancel("macro system disabled")
            GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, "Macros", false)
            return
        }
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, "Macros", true)
        MacroRunner.tick()
    }

    private class Controls(macro: MacroDefinition) {
        val enabled = BooleanSetting("Macro Enabled", macro.enabled())
        val name = TextSetting("Macro Name", "Rename Macro", macro.name(), 48)
        val context = ChoiceSetting("Trigger Context", contextLabel(macro.triggerContext()), *CONTEXT_CHOICES)
        val islandRestricted = BooleanSetting("Island Filter", macro.islandRestricted())
        val islands = EnumMap<Island, BooleanSetting>(Island::class.java)

        init {
            for (island in Island.values()) {
                if (island.selectable()) {
                    islands[island] = BooleanSetting(island.label(), macro.islands().contains(island))
                }
            }
        }
    }
}
