package geiler.addons.client.module

import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.config.ModConfig
import geiler.addons.client.macro.MacroDefinition
import geiler.addons.client.macro.MacroRunner
import geiler.addons.client.macro.MacroScript
import geiler.addons.client.macro.MacroStep
import geiler.addons.client.module.impl.DungeonHelperModule
import geiler.addons.client.module.impl.MacrosModule
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/** Client-thread owner of module keybind capture and activation. */
object ModuleKeybindManager {
    private var bindingModule: Module? = null
    private var bindingMacro: MacroDefinition? = null
    private var bindingKeyStep: MacroStep.Key? = null
    private var bindingScript: MacroScript? = null
    private var pendingBind: ModuleKeybind? = null

    @JvmStatic
    fun bindingModule(): Module? = bindingModule

    @JvmStatic
    fun bindingMacro(): MacroDefinition? = bindingMacro

    @JvmStatic
    fun bindingKeyStep(): MacroStep.Key? = bindingKeyStep

    @JvmStatic
    fun bindingScript(): MacroScript? = bindingScript

    @JvmStatic
    fun isBinding(module: Module?): Boolean = bindingModule === module

    @JvmStatic
    fun beginBinding(module: Module?) {
        if (module == null || !ModuleManager.modules().contains(module)) return
        bindingMacro = null
        bindingKeyStep = null
        bindingScript = null
        bindingModule = module
        pendingBind = null
    }

    @JvmStatic
    fun cancelBinding() {
        bindingModule = null
        bindingMacro = null
        bindingKeyStep = null
        bindingScript = null
        pendingBind = null
    }

    @JvmStatic
    fun beginMacroBinding(macro: MacroDefinition?) {
        if (macro == null) return
        bindingModule = null
        bindingKeyStep = null
        bindingScript = null
        bindingMacro = macro
        pendingBind = null
    }

    @JvmStatic
    fun beginScriptBinding(script: MacroScript?) {
        // A chat stack's hotkey is its manual replay key, so both event kinds are bindable.
        if (script == null || (script.trigger() != MacroScript.Trigger.KEY_PRESS &&
                script.trigger() != MacroScript.Trigger.CHAT)
        ) return
        bindingModule = null
        bindingMacro = null
        bindingKeyStep = null
        bindingScript = script
        pendingBind = null
    }

    @JvmStatic
    fun beginKeyStepBinding(keyStep: MacroStep.Key?) {
        if (keyStep == null) return
        bindingModule = null
        bindingMacro = null
        bindingScript = null
        bindingKeyStep = keyStep
        pendingBind = null
    }

    /** Handles raw keyboard actions before Minecraft forwards them to a screen or KeyMapping. */
    @JvmStatic
    fun handleKeyEvent(minecraft: Minecraft, action: Int, event: KeyEvent): Boolean {
        bindingKeyStep?.let { keyStep ->
            if (action == InputConstants.PRESS && isEscape(event)) {
                keyStep.setKey("")
                ModConfig.markDirty()
                cancelBinding()
                return true
            }
            if (action == InputConstants.PRESS && isUsableMacroStepKey(event)) {
                pendingBind = ModuleKeybind.from(event)
                return true
            }
            val pending = pendingBind
            if (action == InputConstants.RELEASE && pending != null && InputConstants.getKey(event) == pending.key()) {
                keyStep.setKey(pending.key().getName())
                ModConfig.markDirty()
                cancelBinding()
                return true
            }
            return true
        }
        bindingScript?.let { script ->
            if (action == InputConstants.PRESS && isEscape(event)) {
                script.setKeybind(ModuleKeybind.NONE)
                ModConfig.markDirty()
                cancelBinding()
                return true
            }
            if (action == InputConstants.PRESS && isUsable(event)) {
                pendingBind = ModuleKeybind.from(event)
                return true
            }
            val pending = pendingBind
            if (action == InputConstants.RELEASE && pending != null && InputConstants.getKey(event) == pending.key()) {
                script.setKeybind(pending)
                reportConflicts(pending)
                ModConfig.markDirty()
                cancelBinding()
                return true
            }
            return true
        }
        bindingMacro?.let { macro ->
            if (action == InputConstants.PRESS && isEscape(event)) {
                macro.setKeybind(ModuleKeybind.NONE)
                ModConfig.markDirty()
                cancelBinding()
                return true
            }
            if (action == InputConstants.PRESS && isUsable(event)) {
                pendingBind = ModuleKeybind.from(event)
                return true
            }
            val pending = pendingBind
            if (action == InputConstants.RELEASE && pending != null && InputConstants.getKey(event) == pending.key()) {
                macro.setKeybind(pending)
                reportConflicts(pending)
                ModConfig.markDirty()
                cancelBinding()
                return true
            }
            return true
        }
        bindingModule?.let { module ->
            if (action == InputConstants.PRESS && isEscape(event)) {
                module.setKeybind(ModuleKeybind.NONE)
                ModConfig.markDirty()
                cancelBinding()
                return true
            }
            if (action == InputConstants.PRESS && isUsable(event)) {
                pendingBind = ModuleKeybind.from(event)
                return true
            }
            val pending = pendingBind
            if (action == InputConstants.RELEASE && pending != null && InputConstants.getKey(event) == pending.key()) {
                module.setKeybind(pending)
                reportConflicts(pending)
                ModConfig.markDirty()
                cancelBinding()
                return true
            }
            return true
        }

        if (action == InputConstants.PRESS && MacroRunner.handleKeyEvent(minecraft, action, event)) return true
        if (action != InputConstants.PRESS || minecraft.gui.screen() != null) return false
        var matched = false
        for (module in ModuleManager.modules()) {
            if (!module.showsKeybindControl()) continue
            if (!module.keybind().matches(event)) continue
            if (module === DungeonHelperModule.INSTANCE) DungeonHelperModule.INSTANCE.openEditorFromKeybind()
            else if (module.togglesOnKeybind()) {
                module.toggle()
                reportModuleToggle(module)
            }
            matched = true
        }
        if (matched) ModConfig.markDirty()
        return matched
    }

    /** Handles mouse binds before Minecraft forwards the click, including buttons 4 through 8. */
    @JvmStatic
    fun handleMouseButtonEvent(minecraft: Minecraft?, action: Int, event: MouseButtonInfo?): Boolean {
        if (event == null) return false
        if (isCapturing()) {
            if (action != InputConstants.PRESS) return true
            val button = event.button()
            if (button < 0 || button > 7) return true
            bindingKeyStep?.let { keyStep ->
                keyStep.setKey(InputConstants.Type.MOUSE.getOrCreate(button).getName())
                ModConfig.markDirty()
                cancelBinding()
                return true
            }
            val bound = ModuleKeybind.fromMouse(button, event.modifiers())
            val target = bindingModule
            val macro = bindingMacro
            val script = bindingScript
            when {
                target != null -> target.setKeybind(bound)
                macro != null -> macro.setKeybind(bound)
                script != null -> script.setKeybind(bound)
                else -> return true
            }
            reportConflicts(bound)
            ModConfig.markDirty()
            cancelBinding()
            return true
        }
        if (action != InputConstants.PRESS || minecraft == null || minecraft.gui.screen() != null) return false
        if (MacroRunner.handleMouseButtonEvent(minecraft, action, event)) return true
        var matched = false
        for (module in ModuleManager.modules()) {
            if (!module.showsKeybindControl()) continue
            if (!module.keybind().matchesMouse(event.button(), event.modifiers())) continue
            if (module === DungeonHelperModule.INSTANCE) DungeonHelperModule.INSTANCE.openEditorFromKeybind()
            else if (module.togglesOnKeybind()) {
                module.toggle()
                reportModuleToggle(module)
            }
            matched = true
        }
        if (matched) ModConfig.markDirty()
        return matched
    }

    private fun isCapturing(): Boolean =
        bindingModule != null || bindingMacro != null || bindingKeyStep != null || bindingScript != null

    private fun reportModuleToggle(module: Module?) {
        val minecraft = Minecraft.getInstance()
        if (module == null || minecraft.gui == null) return
        val state = if (module.isEnabled()) "enabled" else "disabled"
        minecraft.gui.hud.getChat().addClientSystemMessage(
            Component.literal("[GeilerAddons] ${module.name()} is now $state."),
        )
    }

    /**
     * Names the other owner of a freshly bound key, so a shadowed hotkey is never a silent loss.
     *
     * Macro hotkeys are consulted before module keybinds and every matching module is toggled, so a
     * collision used to mean one of two things happened invisibly: the macro swallowed the module's
     * key, or one press flipped several modules at once. Reported rather than resolved - which of the
     * two the player meant is their choice.
     */
    private fun reportConflicts(bound: ModuleKeybind?) {
        if (bound == null || !bound.isBound()) return
        val others = ArrayList<String>()
        for (module in ModuleManager.modules()) {
            if (module.showsKeybindControl() && module !== bindingModule && module.keybind().conflictsWith(bound)) {
                others.add(module.name())
            }
        }
        for (macro in MacrosModule.INSTANCE.macros()) {
            if (macro !== bindingMacro && macro.keybind().conflictsWith(bound)) {
                others.add("the macro \"${macro.name()}\"")
            }
        }
        if (others.isEmpty()) return
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui == null) return
        val owner = when {
            bindingModule != null -> bindingModule!!.name()
            bindingMacro != null -> "the macro \"${bindingMacro!!.name()}\""
            else -> "the new hotkey"
        }
        minecraft.gui.hud.getChat().addClientSystemMessage(
            Component.literal(
                "[GeilerAddons] ${bound.displayName()} is also bound to " +
                    "${others.joinToString(", ")}, so $owner will not be the only thing it triggers.",
            ),
        )
    }

    private fun isEscape(event: KeyEvent): Boolean = event.key() == InputConstants.KEY_ESCAPE

    private fun isUsable(event: KeyEvent): Boolean =
        event.key() != GLFW.GLFW_KEY_UNKNOWN && event.key() != InputConstants.KEY_ESCAPE

    /** Macro Key nodes may press a modifier by itself; activation hotkeys still require a main key. */
    private fun isUsableMacroStepKey(event: KeyEvent): Boolean = canBindMacroStepKey(event.key())

    @JvmStatic
    fun canBindMacroStepKey(keyCode: Int): Boolean =
        keyCode != GLFW.GLFW_KEY_UNKNOWN && keyCode != InputConstants.KEY_ESCAPE
}
