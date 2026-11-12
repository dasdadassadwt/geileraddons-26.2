package geiler.addons.client.macro

import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.access.AbstractContainerScreenAccess
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.gui.ClickGuiScreen
import geiler.addons.client.gui.InventoryButtonPickerScreen
import geiler.addons.client.gui.InventoryButtonTextScreen
import geiler.addons.client.gui.ScratchMacroEditorScreen
import geiler.addons.client.hud.MacroTitleOverlay
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.ModuleManager
import geiler.addons.client.module.impl.GeneralModule
import geiler.addons.client.module.impl.InventoryButtonRules
import geiler.addons.client.module.impl.MacrosModule
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Deque
import java.util.HashMap
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.Locale
import java.util.SplittableRandom

/** Client-thread workflow executor for the Macros module. */
object MacroRunner {
    private const val FAILURE_TIMEOUT_NANOS = 5_000_000_000L
    private const val MAX_STRUCTURAL_STEPS = 32
    private const val MAX_CHAT_LENGTH = 256
    private const val MAX_CHAT_HISTORY = 128
    private const val MAX_RUNTIME_DIAGNOSTICS_PER_BUCKET = 16
    private const val DIAGNOSTIC_BUCKET_NANOS = 50_000_000L
    private val RANDOM = SplittableRandom()
    private var lastChat = ""
    private var chatSequence = 0L
    private var lastChatSequence = 0L
    private val chatHistory: Deque<ChatLine> = ArrayDeque()
    private var lastLevel: Level? = null
    private val activeRuns: MutableList<Run> = ArrayList()
    private val inputBlockOwners: MutableSet<Run> = HashSet()
    private val heldKeyOwners: MutableMap<Int, MutableSet<Run>> = HashMap()
    private val worldTriggerStates: MutableMap<String, WorldTriggerState> = HashMap()
    private val chatTriggerStarts: MutableMap<String, Long> = HashMap()
    /** Chat this client sent from a macro recently enough that its echo could still arrive. */
    private var sentChatLines: List<MacroChatTrigger.SentLine> = java.util.List.of<MacroChatTrigger.SentLine>()
    /** The last chat-triggered stack that matched a line but could not start. */
    private var lastBlockedChatTrigger: MacroChatTrigger.Blocked? = null
    private val finishedScripts: MutableMap<String, Long> = HashMap()
    private var triggerWorldKey = ""
    private var triggerLevel: Level? = null
    private var worldInstanceSequence = 0L
    private var applyingSyntheticInput = false
    private var diagnosticBucket = Long.MIN_VALUE
    private var diagnosticsInBucket = 0
    private var suppressedDiagnostics = 0L
    private var lastStatusTick = Long.MIN_VALUE
    private const val MAX_ACTIVE_RUNS = 64
    private const val MAX_CALL_DEPTH = 16

    private enum class ConditionResult {
        TRUE, FALSE, UNKNOWN;

        fun not(): ConditionResult = if (this == TRUE) FALSE else if (this == FALSE) TRUE else UNKNOWN

        companion object {
            fun of(value: Boolean): ConditionResult = if (value) TRUE else FALSE
        }
    }

    @JvmStatic
    fun isRunning(): Boolean = activeRuns.isNotEmpty()

    /** Runs a Dungeon Guide link through the target macro's On Call stack when idle. */
    @JvmStatic
    fun triggerGuideMacro(macroId: Int): Boolean {
        val minecraft: Minecraft? = Minecraft.getInstance()
        if (activeRuns.isNotEmpty() || minecraft == null || minecraft.level == null || minecraft.player == null ||
            !MacrosModule.INSTANCE.isActive()) return false
        val macro = MacrosModule.INSTANCE.macro(macroId) ?: return false
        if (!macro.enabled() || !macro.allowsIsland(HypixelModApi.currentIsland())) return false
        for (script in macro.scripts()) if (script.trigger() == MacroScript.Trigger.ON_CALL) return start(macro, script)
        return false
    }

    /** Starts matching Garden event stacks once for a signal emitted by the Garden feature modules. */
    @JvmStatic
    fun triggerExternalEvent(eventTrigger: MacroScript.Trigger?): Int {
        if (eventTrigger != MacroScript.Trigger.PEST_SPAWNED &&
            eventTrigger != MacroScript.Trigger.PEST_COOLDOWN_READY) return 0
        val minecraft: Minecraft? = Minecraft.getInstance()
        if (minecraft == null) return 0
        if (!minecraft.isSameThread()) {
            minecraft.execute { triggerExternalEvent(eventTrigger) }
            return 0
        }
        if (minecraft.level == null || minecraft.player == null || !HypixelModApi.onGarden() ||
            !MacrosModule.INSTANCE.isActive()) return 0
        val island = HypixelModApi.currentIsland()
        var started = 0
        for (macro in MacrosModule.INSTANCE.macros()) {
            if (!macro.enabled() || !macro.allowsIsland(island)) continue
            for (script in macro.scripts()) {
                if (script.trigger() != eventTrigger || isScriptRunning(script.id())) continue
                if (start(macro, script)) started++
            }
        }
        return started
    }

    /** Supplies held macro keys to vanilla input polling while a workflow owns the hold. */
    @JvmStatic
    fun isSyntheticKeyDown(keyCode: Int): Boolean {
        val now = System.nanoTime()
        for (run in activeRuns) if (run.isSyntheticKeyDown(keyCode, now)) return true
        return false
    }

    @JvmStatic
    fun activeMacro(): MacroDefinition? = if (activeRuns.isEmpty()) null else activeRuns.first().macro

    @JvmStatic fun isPlayerInputBlocked(): Boolean = inputBlockOwners.isNotEmpty()
    @JvmStatic fun isApplyingSyntheticInput(): Boolean = applyingSyntheticInput

    @JvmStatic
    fun isScriptRunning(scriptId: String?): Boolean {
        if (scriptId == null) return false
        for (run in activeRuns) if (run.isExecutingScript(scriptId)) return true
        return false
    }

    enum class RunState { IDLE, RUNNING, WAITING, PAUSED, FINISHED }

    @JvmStatic
    fun scriptState(scriptId: String?): RunState {
        if (scriptId == null) return RunState.IDLE
        for (run in activeRuns) if (run.isExecutingScript(scriptId)) {
            if (run.isPaused()) return RunState.PAUSED
            return if (run.isWaiting()) RunState.WAITING else RunState.RUNNING
        }
        val finished = finishedScripts[scriptId]
        return if (finished != null && System.nanoTime() - finished < 1_500_000_000L) RunState.FINISHED else RunState.IDLE
    }

    @JvmStatic
    fun pausedReason(scriptId: String?): String {
        if (scriptId == null) return ""
        for (run in activeRuns) if (run.isExecutingScript(scriptId) && run.isPaused()) return run.pausedReason()
        return ""
    }

    @JvmStatic
    fun canResumePausedScript(scriptId: String?): Boolean {
        if (scriptId == null) return false
        for (run in activeRuns) if (run.isExecutingScript(scriptId) && run.isPaused()) return run.canResumeAfterUnknownResolution()
        return false
    }

    /** Resumes only after the exact unsupported node that paused the run has been removed or replaced. */
    @JvmStatic
    fun resumePausedScript(scriptId: String?): Boolean {
        if (scriptId == null) return false
        for (run in activeRuns) if (run.isExecutingScript(scriptId) && run.isPaused()) return run.resumeAfterUnknownResolution()
        return false
    }

    @JvmStatic
    fun cancelPausedScript(scriptId: String?, reason: String?): Boolean {
        if (scriptId == null) return false
        for (run in java.util.List.copyOf(activeRuns)) if (run.isExecutingScript(scriptId) && run.isPaused()) {
            finishRun(run, reason ?: "paused run cancelled", false)
            return true
        }
        return false
    }

    @JvmStatic
    fun wasPausedUnknownStepResolved(activeSteps: List<MacroStep>?, pausedStep: MacroStep.Unknown?): Boolean {
        if (pausedStep == null || activeSteps == null) return false
        for (candidate in activeSteps) if (candidate === pausedStep) return false
        return true
    }

    @JvmStatic
    fun activeStep(scriptId: String?): MacroStep? {
        if (scriptId == null) return null
        for (run in activeRuns) if (run.isExecutingScript(scriptId)) return run.activeStepFor(scriptId)
        return null
    }

    /** Invoked on the client thread before Minecraft forwards a raw key event. */
    @JvmStatic
    fun handleKeyEvent(minecraft: Minecraft?, action: Int, event: KeyEvent?): Boolean {
        if (action != InputConstants.PRESS || minecraft == null || minecraft.level == null || minecraft.player == null ||
            !MacrosModule.INSTANCE.isActive()) return false
        if (isTextOrEditorScreen(minecraft.gui.screen())) return false
        val matches = ArrayList<MacroScriptOwner>()
        for (macro in MacrosModule.INSTANCE.macros()) {
            if (!macro.enabled() || !contextAllowed(macro.triggerContext(), minecraft.gui.screen()) ||
                !macro.allowsIsland(HypixelModApi.currentIsland())) continue
            for (script in macro.scripts()) {
                val hotkeyed = script.trigger() == MacroScript.Trigger.KEY_PRESS || script.trigger() == MacroScript.Trigger.CHAT
                if (hotkeyed && event != null && script.keybind().matches(event) && !isScriptRunning(script.id())) {
                    matches.add(MacroScriptOwner(macro, script))
                }
            }
        }
        if (matches.isEmpty()) return false
        for (module in ModuleManager.modules()) if (module.showsKeybindControl() && event != null && module.keybind().matches(event)) {
            message(minecraft, "Macro hotkey conflicts with module '" + module.name() + "'; nothing started.")
            return true
        }
        for (owner in matches) start(owner.macro, owner.script)
        return true
    }

    /** Mouse hotkeys share the keyboard trigger rules and can use every GLFW mouse button. */
    @JvmStatic
    fun handleMouseButtonEvent(minecraft: Minecraft?, action: Int, event: MouseButtonInfo?): Boolean {
        if (action != InputConstants.PRESS || event == null || minecraft == null || minecraft.level == null ||
            minecraft.player == null || !MacrosModule.INSTANCE.isActive()) return false
        if (isTextOrEditorScreen(minecraft.gui.screen())) return false
        val matches = ArrayList<MacroScriptOwner>()
        for (macro in MacrosModule.INSTANCE.macros()) {
            if (!macro.enabled() || !contextAllowed(macro.triggerContext(), minecraft.gui.screen()) ||
                !macro.allowsIsland(HypixelModApi.currentIsland())) continue
            for (script in macro.scripts()) {
                val hotkeyed = script.trigger() == MacroScript.Trigger.KEY_PRESS || script.trigger() == MacroScript.Trigger.CHAT
                if (hotkeyed && script.keybind().matchesMouse(event.button(), event.modifiers()) && !isScriptRunning(script.id())) {
                    matches.add(MacroScriptOwner(macro, script))
                }
            }
        }
        if (matches.isEmpty()) return false
        for (module in ModuleManager.modules()) if (module.showsKeybindControl() &&
            module.keybind().matchesMouse(event.button(), event.modifiers())) {
            message(minecraft, "Macro hotkey conflicts with module '" + module.name() + "'; nothing started.")
            return true
        }
        for (owner in matches) start(owner.macro, owner.script)
        return true
    }

    @JvmStatic
    fun tick() {
        val minecraft = Minecraft.getInstance()
        if (lastLevel !== minecraft.level) {
            if (lastLevel != null) for (run in java.util.List.copyOf(activeRuns)) run.markLevelChanged()
            lastLevel = minecraft.level
            lastChat = ""
            lastChatSequence = chatSequence
            chatHistory.clear()
            // Repeat delays belong to the world they started in; a fresh session is not still cooling down.
            chatTriggerStarts.clear()
        }
        for (run in java.util.List.copyOf(activeRuns)) {
            val disabledMacroId = run.disabledMacroId()
            if (disabledMacroId != null) {
                finishRun(run, "macro $disabledMacroId disabled", false)
                continue
            }
            if (MacroFlowRules.contextEnded(MacrosModule.INSTANCE.isActive(), minecraft.level != null,
                    minecraft.player != null, run.waitingForWorldSwitch())) {
                finishRun(run, "context ended", false)
                continue
            }
            val currentIsland = HypixelModApi.currentIsland()
            if (!run.macro.allowsIsland(currentIsland) &&
                !(run.waitingForWorldSwitch() && currentIsland == Island.NONE)) {
                finishRun(run, "context no longer allowed", false)
                continue
            }
            run.tick(minecraft)
        }
        tickWorldTriggers(minecraft)
        finishedScripts.entries.removeIf { System.nanoTime() - it.value > 2_000_000_000L }
        sentChatLines = MacroChatTrigger.pruneSent(sentChatLines, System.nanoTime())
        logRuntimeStatus(minecraft)
    }

    @JvmStatic
    fun onChatMessage(content: String?) {
        if (content == null) return
        val normalized = ChatText.stripForMatch(content)
        lastChatSequence = ++chatSequence
        lastChat = if (normalized.length > MAX_CHAT_LENGTH) normalized.substring(normalized.length - MAX_CHAT_LENGTH) else normalized
        chatHistory.addLast(ChatLine(lastChatSequence, lastChat))
        while (chatHistory.size > MAX_CHAT_HISTORY) chatHistory.removeFirst()
        tickChatTriggers(normalized)
    }

    /** Starts every chat-triggered stack whose pattern matches a received line. */
    private fun tickChatTriggers(normalized: String) {
        val minecraft = Minecraft.getInstance()
        val now = System.nanoTime()
        sentChatLines = MacroChatTrigger.pruneSent(sentChatLines, now)
        if (minecraft.level == null || minecraft.player == null || !MacrosModule.INSTANCE.isActive()) return
        if (MacroChatTrigger.isOwnEcho(normalized, sentChatLines, now)) {
            chatDebug(minecraft, "a chat trigger ignored this macro's own sent line")
            return
        }
        val island = HypixelModApi.currentIsland()
        val typing = isTextOrEditorScreen(minecraft.gui.screen())
        val heldBack = if (typing && !GeneralModule.INSTANCE.chatTriggersInTextScreens().value())
            "a text or editor screen is open" else null
        for (macro in MacrosModule.INSTANCE.macros()) {
            if (!macro.enabled() || !macro.allowsIsland(island)) continue
            for (script in macro.scripts()) {
                if (script.trigger() != MacroScript.Trigger.CHAT ||
                    !MacroChatTrigger.matches(script.chatPattern(), script.chatContains(), normalized)) continue
                if (heldBack != null) {
                    blockChatTrigger(macro, script, heldBack, now, minecraft)
                    continue
                }
                if (isScriptRunning(script.id())) {
                    blockChatTrigger(macro, script, "that stack is already running", now, minecraft)
                    continue
                }
                val lastStart = chatTriggerStarts.getOrDefault(script.id(), 0L)
                if (!MacroChatTrigger.cooldownElapsed(lastStart, now, script.chatCooldownMillis())) {
                    blockChatTrigger(macro, script, "its repeat delay has not elapsed", now, minecraft)
                    continue
                }
                if (!start(macro, script)) {
                    blockChatTrigger(macro, script, "the macro runner refused the start", now, minecraft)
                    continue
                }
                chatTriggerStarts[script.id()] = System.nanoTime()
            }
        }
    }

    /** Starts the chat-triggered stack that was most recently refused. */
    @JvmStatic
    fun replayLastBlocked() {
        val minecraft: Minecraft? = Minecraft.getInstance()
        val blocked = lastBlockedChatTrigger
        if (blocked == null) {
            message(minecraft, "No blocked macro to replay.")
            return
        }
        val macro = MacrosModule.INSTANCE.macro(blocked.macroId)
        if (macro == null) {
            lastBlockedChatTrigger = null
            message(minecraft, "That blocked macro no longer exists.")
            return
        }
        var script: MacroScript? = null
        for (candidate in macro.scripts()) if (candidate.id() == blocked.scriptId) script = candidate
        if (script == null) {
            lastBlockedChatTrigger = null
            message(minecraft, "That blocked event stack no longer exists.")
            return
        }
        if (!macro.enabled()) {
            message(minecraft, "That macro is disabled. Enable it before replaying the stack.")
            return
        }
        if (!MacrosModule.INSTANCE.isActive()) {
            message(minecraft, "The macro system is switched off.")
            return
        }
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            message(minecraft, "Join a world before replaying a macro.")
            return
        }
        if (!macro.allowsIsland(HypixelModApi.currentIsland())) {
            message(minecraft, "That macro is not allowed on this island.")
            return
        }
        if (isTextOrEditorScreen(minecraft.gui.screen()) && !GeneralModule.INSTANCE.chatTriggersInTextScreens().value()) {
            message(minecraft, "Close the screen first: that is what blocked this macro.")
            return
        }
        val lastStart = chatTriggerStarts.getOrDefault(script.id(), 0L)
        if (!MacroChatTrigger.cooldownElapsed(lastStart, System.nanoTime(), script.chatCooldownMillis())) {
            message(minecraft, "Macro '" + macro.name() + "' is still inside its repeat delay.")
            return
        }
        if (!start(macro, script)) return
        chatTriggerStarts[script.id()] = System.nanoTime()
        log(macro, "REPLAY after being blocked by " + blocked.reason)
        lastBlockedChatTrigger = null
    }

    @JvmStatic
    fun cancel(reason: String?) {
        for (run in java.util.List.copyOf(activeRuns)) finishRun(run, reason, false)
    }

    @JvmStatic
    fun cancelMacro(macro: MacroDefinition?, reason: String?) {
        if (macro == null) return
        for (run in java.util.List.copyOf(activeRuns)) if (run.macro === macro) finishRun(run, reason, false)
    }

    private fun start(macro: MacroDefinition?) {
        if (macro == null) return
        for (script in macro.scripts()) if (script.trigger() == MacroScript.Trigger.KEY_PRESS && script === macro.primaryKeyScript()) {
            start(macro, script)
            return
        }
    }

    private fun start(macro: MacroDefinition?, script: MacroScript?): Boolean {
        if (macro == null || !macro.enabled() || script == null) return false
        if (script.steps().isEmpty()) {
            message(Minecraft.getInstance(), "Macro '" + macro.name() + "' has no blocks in this event stack.")
            return false
        }
        if (!MacroRuntimeRules.canStart(isScriptRunning(script.id()), activeRuns.size, MAX_ACTIVE_RUNS)) {
            if (activeRuns.size >= MAX_ACTIVE_RUNS) {
                message(Minecraft.getInstance(), "Macro runner is at its 64-stack safety limit; this trigger was ignored.")
            }
            return false
        }
        finishedScripts.remove(script.id())
        activeRuns.add(Run(macro, script))
        log(macro, "START trigger=" + script.trigger() + " activeRuns=" + activeRuns.size)
        return true
    }

    private fun finishRun(run: Run?, reason: String?, completed: Boolean) {
        if (run == null || !activeRuns.remove(run)) return
        run.releaseAllInputs()
        if (completed) finishedScripts[run.script.id()] = System.nanoTime()
        if (run.script.trigger() == MacroScript.Trigger.WORLD_REGION) {
            worldTriggerStates[run.script.id()]?.lastCompletedNanos = System.nanoTime()
        }
        val durationMillis = maxOf(0L, System.nanoTime() - run.startedNanos) / 1_000_000L
        log(run.macro, (if (completed) "COMPLETE" else "STOP") + " durationMs=" + durationMillis +
            (if (MacroJavaStrings.isBlank(reason)) "" else " reason=" + reason))
    }

    private fun acquireHeldKey(owner: Run?, key: InputConstants.Key?) {
        if (owner == null || key == null || key == InputConstants.UNKNOWN) return
        val owners = heldKeyOwners.getOrPut(key.value) { HashSet() }
        if (owners.add(owner)) setKeyDown(key, true)
    }

    private fun releaseHeldKey(owner: Run?, key: InputConstants.Key?) {
        if (owner == null || key == null) return
        val owners = heldKeyOwners[key.value] ?: return
        owners.remove(owner)
        if (owners.isEmpty()) {
            heldKeyOwners.remove(key.value)
            setKeyDown(key, false)
        }
    }

    private fun setKeyDown(key: InputConstants.Key, down: Boolean) {
        val previous = applyingSyntheticInput
        applyingSyntheticInput = true
        try { KeyMapping.set(key, down) } finally { applyingSyntheticInput = previous }
    }

    private fun clickSyntheticKey(key: InputConstants.Key) {
        val previous = applyingSyntheticInput
        applyingSyntheticInput = true
        try { KeyMapping.click(key) } finally { applyingSyntheticInput = previous }
    }

    private fun clearPhysicalGameInputs() {
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.screen() != null) return
        val names = arrayOf(
            "key.forward", "key.back", "key.left", "key.right", "key.jump", "key.sneak", "key.sprint",
            "key.attack", "key.use", "key.pickItem", "key.hotbar.1", "key.hotbar.2", "key.hotbar.3",
            "key.hotbar.4", "key.hotbar.5", "key.hotbar.6", "key.hotbar.7", "key.hotbar.8", "key.hotbar.9",
        )
        for (name in names) KeyMapping.get(name)?.setDown(false)
    }

    private fun tickWorldTriggers(minecraft: Minecraft?) {
        if (!MacrosModule.INSTANCE.isActive() || minecraft == null || minecraft.level == null || minecraft.player == null) {
            if (triggerLevel != null) {
                triggerLevel = null
                triggerWorldKey = ""
                worldTriggerStates.clear()
                worldInstanceSequence++
            }
            return
        }
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val worldKey = currentWorldKey(minecraft)
        if (triggerLevel !== level || triggerWorldKey != worldKey) {
            triggerLevel = level
            triggerWorldKey = worldKey
            worldTriggerStates.clear()
            worldInstanceSequence++
        }
        if (minecraft.gui.screen() != null) return
        val now = System.nanoTime()
        val island = HypixelModApi.currentIsland()
        for (macro in MacrosModule.INSTANCE.macros()) {
            if (!macro.enabled() || !macro.allowsIsland(island)) continue
            for (script in macro.scripts()) {
                if (script.trigger() != MacroScript.Trigger.WORLD_REGION) continue
                val region = script.worldRegion()
                val state = worldTriggerStates.getOrPut(script.id()) { WorldTriggerState() }
                val inside = region.contains(worldKey, player.x, player.y, player.z)
                if (!inside) {
                    state.inside = false
                    continue
                }
                val entered = !state.inside
                state.inside = true
                val shouldStart = MacroRuntimeRules.shouldStartWorldRun(true, entered, isScriptRunning(script.id()),
                    region.oncePerWorld(), state.firedThisWorld, state.lastCompletedNanos, now,
                    region.repeatDelayMillis() * 1_000_000L)
                if (shouldStart && start(macro, script) && region.oncePerWorld()) state.firedThisWorld = true
            }
        }
    }

    @JvmStatic
    fun currentWorldKey(): String = currentWorldKey(Minecraft.getInstance())

    private fun currentWorldKey(minecraft: Minecraft?): String {
        if (minecraft == null) return ""
        val level = minecraft.level ?: return ""
        val owner = when {
            minecraft.currentServer != null -> minecraft.currentServer!!.ip
            minecraft.singleplayerServer != null -> "singleplayer:" + minecraft.singleplayerServer!!.worldData.levelName
            else -> "singleplayer"
        }
        return owner + "|" + level.dimension().identifier()
    }

    @JvmStatic
    fun captureWorldRegion(script: MacroScript?): Boolean {
        val minecraft = Minecraft.getInstance()
        if (script == null) return false
        val player = minecraft.player ?: return false
        if (minecraft.level == null) return false
        script.worldRegion().place(currentWorldKey(minecraft), player.x, player.y, player.z)
        return script.worldRegion().placed()
    }

    @JvmStatic
    fun renderWorld(context: LevelRenderContext?) {
        val minecraft = Minecraft.getInstance()
        if (context == null || !MacrosModule.INSTANCE.isActive() || minecraft.level == null || minecraft.player == null) return
        val current = currentWorldKey(minecraft)
        val camera = minecraft.gameRenderer.mainCamera().position()
        var drew = false
        for (macro in MacrosModule.INSTANCE.macros()) {
            if (!macro.enabled()) continue
            for (script in macro.scripts()) {
                if (script.trigger() != MacroScript.Trigger.WORLD_REGION) continue
                val region = script.worldRegion()
                if (!region.placed() || region.worldKey() != current) continue
                renderWorldRegion(context, camera, region)
                drew = true
            }
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(WorldRenderBuffer.current())
    }

    private fun renderWorldRegion(context: LevelRenderContext, camera: Vec3, region: MacroWorldRegion) {
        if (region.fillOpacity() > 0) {
            val innerRadius = if (region.shape() == MacroWorldRegion.Shape.RING) region.innerSize() else 0.0
            EspRenderer.renderFlatArea(context.poseStack(), WorldRenderBuffer.current(),
                region.x() - camera.x, region.y() + 0.008 - camera.y, region.z() - camera.z,
                region.size(), innerRadius, region.shape() == MacroWorldRegion.Shape.SQUARE,
                MacroRuntimeRules.colorWithOpacity(region.color(), region.fillOpacity()), true)
        }
        val y = region.y() + 0.015
        val half = region.size()
        if (region.shape() == MacroWorldRegion.Shape.SQUARE) {
            val x0 = region.x() - half
            val x1 = region.x() + half
            val z0 = region.z() - half
            val z1 = region.z() + half
            worldLine(context, camera, x0, y, z0, x1, y, z0, region)
            worldLine(context, camera, x1, y, z0, x1, y, z1, region)
            worldLine(context, camera, x1, y, z1, x0, y, z1, region)
            worldLine(context, camera, x0, y, z1, x0, y, z0, region)
            return
        }
        drawCircle(context, camera, region, half)
        if (region.shape() == MacroWorldRegion.Shape.RING) drawCircle(context, camera, region, region.innerSize())
    }

    private fun drawCircle(context: LevelRenderContext, camera: Vec3, region: MacroWorldRegion, radius: Double) {
        if (radius <= 0) return
        val segments = 64
        for (index in 0 until segments) {
            val a = Math.PI * 2 * index / segments
            val b = Math.PI * 2 * (index + 1) / segments
            worldLine(context, camera, region.x() + Math.cos(a) * radius, region.y() + 0.015,
                region.z() + Math.sin(a) * radius, region.x() + Math.cos(b) * radius,
                region.y() + 0.015, region.z() + Math.sin(b) * radius, region)
        }
    }

    private fun worldLine(context: LevelRenderContext, camera: Vec3, x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double, region: MacroWorldRegion) {
        EspRenderer.renderLine(context.poseStack(), WorldRenderBuffer.current(), x0 - camera.x, y0 - camera.y,
            z0 - camera.z, x1 - camera.x, y1 - camera.y, z1 - camera.z, region.color(), region.lineWidth(), true)
    }

    private class WorldTriggerState {
        var inside = false
        var firedThisWorld = false
        var lastCompletedNanos = 0L
    }

    private data class MacroScriptOwner(val macro: MacroDefinition, val script: MacroScript)
    private data class ChatLine(val sequence: Long, val text: String)

    /** Applies the same live macro, trigger-context and island gates to an inventory button. */
    @JvmStatic
    fun inventoryButtonEligibility(macro: MacroDefinition?, minecraft: Minecraft?, screen: Screen?): InventoryButtonRules.Eligibility {
        val client = minecraft ?: Minecraft.getInstance()
        val exists = macro != null && MacrosModule.INSTANCE.macro(macro.id()) === macro
        val inPlayerContext = client.player != null && client.level != null
        val active = MacrosModule.INSTANCE.isActive() && inPlayerContext
        val triggerAllowed = screen is InventoryScreen && macro != null && contextAllowed(macro.triggerContext(), screen)
        val islandAllowed = macro != null && macro.allowsIsland(HypixelModApi.currentIsland())
        return InventoryButtonRules.evaluate(active, exists, macro?.enabled() == true,
            macro != null && macro.steps().isNotEmpty(), triggerAllowed, islandAllowed)
    }

    /** Starts a button-bound macro only after all current module and macro rules pass. */
    @JvmStatic
    fun activateInventoryButton(macroId: Int): InventoryButtonRules.Eligibility {
        val minecraft = Minecraft.getInstance()
        MacrosModule.INSTANCE.macros() // Pull the latest Click GUI setting values before the click is evaluated.
        val macro = MacrosModule.INSTANCE.macro(macroId)
        val eligibility = inventoryButtonEligibility(macro, minecraft, minecraft.gui.screen())
        if (eligibility.eligible) start(macro)
        return eligibility
    }

    private fun contextAllowed(context: MacroTriggerContext, screen: Screen?): Boolean {
        if (isTextOrEditorScreen(screen)) return false
        val world = screen == null
        val container = screen is AbstractContainerScreen<*>
        return when (context) {
            MacroTriggerContext.WORLD_ONLY -> world
            MacroTriggerContext.CONTAINER_ONLY -> container
            MacroTriggerContext.WORLD_AND_CONTAINER -> world || container
            MacroTriggerContext.ANY_NON_TEXT_SCREEN -> true
        }
    }

    private fun isTextOrEditorScreen(screen: Screen?): Boolean = screen is ChatScreen || screen is ClickGuiScreen ||
        screen is ScratchMacroEditorScreen || screen is InventoryButtonPickerScreen ||
        screen is InventoryButtonTextScreen

    private fun message(minecraft: Minecraft?, text: String?) {
        if (minecraft != null && minecraft.gui != null && text != null) {
            minecraft.gui.hud.chat.addClientSystemMessage(Component.literal("[Macros] $text"))
        }
    }

    private fun log(macro: MacroDefinition?, text: String?) {
        if (macro == null || text == null) return
        writeRuntimeDiagnostic(Minecraft.getInstance(), "macroId=${macro.id()} $text")
    }

    /** Remembers a refused chat trigger so the replay key can start exactly that stack later. */
    private fun blockChatTrigger(macro: MacroDefinition, script: MacroScript, reason: String,
        nowNanos: Long, minecraft: Minecraft) {
        lastBlockedChatTrigger = MacroChatTrigger.Blocked(macro.id(), script.id(), reason, nowNanos)
        log(macro, "CHAT_TRIGGER blocked contains=${script.chatContains()} patternLength=" +
            (script.chatPattern()?.length ?: 0) + " reason=$reason")
    }

    private fun chatDebug(minecraft: Minecraft, text: String) = writeRuntimeDiagnostic(minecraft, text)

    private fun writeRuntimeDiagnostic(minecraft: Minecraft?, text: String?) {
        val bucket = System.nanoTime() / DIAGNOSTIC_BUCKET_NANOS
        if (bucket != diagnosticBucket) {
            diagnosticBucket = bucket
            diagnosticsInBucket = 0
            val suppressed = suppressedDiagnostics
            suppressedDiagnostics = 0
            if (suppressed > 0) {
                writeRuntimeDiagnosticLine(minecraft, "SUPPRESSED events=$suppressed")
                diagnosticsInBucket++
            }
        }
        if (diagnosticsInBucket >= MAX_RUNTIME_DIAGNOSTICS_PER_BUCKET) {
            suppressedDiagnostics++
            return
        }
        writeRuntimeDiagnosticLine(minecraft, text)
        diagnosticsInBucket++
    }

    private fun writeRuntimeDiagnosticLine(minecraft: Minecraft?, text: String?) {
        if (text == null) return
        var line = text.replace('\r', ' ').replace('\n', ' ')
        if (line.length > 320) line = line.substring(0, 317) + "…"
        GeilerAddonsLog.write(Category.MISCELLANEOUS, "Macros", tickOf(minecraft), line)
    }

    private fun logRuntimeStatus(minecraft: Minecraft?) {
        if (minecraft == null || minecraft.level == null) return
        val tick = tickOf(minecraft)
        if (lastStatusTick != Long.MIN_VALUE && tick >= lastStatusTick && tick - lastStatusTick < 100) return
        lastStatusTick = tick
        val enabledMacros = MacrosModule.INSTANCE.macros().count { it.enabled() }
        var waitingRuns = 0
        var pausedRuns = 0
        for (run in activeRuns) {
            if (run.isPaused()) pausedRuns++ else if (run.isWaiting()) waitingRuns++
        }
        writeRuntimeDiagnostic(minecraft, "STATUS macros=${MacrosModule.INSTANCE.macros().size} enabledMacros=$enabledMacros " +
            "activeRuns=${activeRuns.size} waitingRuns=$waitingRuns pausedRuns=$pausedRuns chatHistory=${chatHistory.size}")
    }

    /** Records chat this client just sent, so its echo cannot retrigger the stack that sent it. */
    private fun rememberSentChat(value: String) {
        val now = System.nanoTime()
        val updated = ArrayList(sentChatLines)
        updated.add(MacroChatTrigger.SentLine(value, now))
        sentChatLines = MacroChatTrigger.pruneSent(updated, now)
    }

    private fun tickOf(minecraft: Minecraft?): Long {
        val level = minecraft?.level ?: return 0
        return level.gameTime
    }

    private fun parseKey(value: String?): InputConstants.Key {
        if (MacroJavaStrings.isBlank(value)) return InputConstants.UNKNOWN
        return try {
            val trimmedValue = MacroJavaStrings.trim(value)!!
            var key: InputConstants.Key? = InputConstants.getKey(trimmedValue)
            if (key == null || key == InputConstants.UNKNOWN) {
                val shorthand = trimmedValue.lowercase(Locale.ROOT)
                if (shorthand.length == 1 || !shorthand.contains('.')) key = InputConstants.getKey("key.keyboard.$shorthand")
            }
            key ?: InputConstants.UNKNOWN
        } catch (_: RuntimeException) {
            InputConstants.UNKNOWN
        }
    }

    private fun expandVariables(source: String?, variables: Map<String, Any?>?): String {
        if (source.isNullOrEmpty()) return source ?: ""
        val result = StringBuilder(minOf(MacroFlowRules.MAX_CHAT_LENGTH, source.length + 32))
        var index = 0
        while (index < source.length && result.length < MacroFlowRules.MAX_CHAT_LENGTH) {
            if (source[index] == '$' && index + 1 < source.length && source[index + 1] == '{') {
                val end = source.indexOf('}', index + 2)
                if (end > index + 2) {
                    val name = source.substring(index + 2, end)
                    val value = if (name.startsWith("global:")) {
                        MacrosModule.INSTANCE.globalVariables().value(name.substring("global:".length))
                    } else {
                        variables?.get(name)
                    }
                    result.append(value?.toString() ?: source.substring(index, end + 1))
                    index = end + 1
                    continue
                }
            }
            result.append(source[index++])
        }
        return result.toString()
    }

    private fun itemMatches(stack: ItemStack?, wanted: String?, contains: Boolean): Boolean {
        if (stack == null || stack.isEmpty) return false
        val actual = ChatText.stripForMatch(stack.hoverName.string)
        val needle = ChatText.stripForMatch(wanted ?: "")
        return MacroFlowRules.itemNameMatchesAny(actual, needle, contains)
    }

    private fun condition(condition: MacroCondition?, minecraft: Minecraft, minimumChatSequence: Long): ConditionResult =
        condition(condition, minecraft, minimumChatSequence, emptyMap())

    private fun condition(condition: MacroCondition?, minecraft: Minecraft, minimumChatSequence: Long,
        variables: Map<String, Any?>?): ConditionResult =
        condition(condition, minecraft, minimumChatSequence, variables, false)

    private fun condition(condition: MacroCondition?, minecraft: Minecraft, minimumChatSequence: Long,
        variables: Map<String, Any?>?, matchAnyChatSinceSequence: Boolean): ConditionResult {
        when (condition) {
            null -> return ConditionResult.TRUE
            is MacroCondition.Always -> return ConditionResult.of(condition.expected())
            is MacroCondition.World -> return ConditionResult.of((minecraft.level != null && minecraft.player != null) == condition.mustBeInWorld())
            is MacroCondition.Screen -> {
                val open = minecraft.gui.screen() != null
                val screenTitle = condition.title()
                if (open != condition.mustBeOpen()) return ConditionResult.FALSE
                if (!open || MacroJavaStrings.isBlank(screenTitle)) return ConditionResult.TRUE
                val title = ChatText.stripForMatch(minecraft.gui.screen()!!.title.string).lowercase(Locale.ROOT)
                val wanted = ChatText.stripForMatch(screenTitle ?: "").lowercase(Locale.ROOT)
                return ConditionResult.of(if (condition.contains()) title.contains(wanted) else title == wanted)
            }
            is MacroCondition.Slot -> {
                val exists = findSlot(minecraft.gui.screen(), condition.slotId()) != null
                return ConditionResult.of(exists == condition.mustExist())
            }
            is MacroCondition.Item -> {
                val found = hasItem(minecraft, condition.name(), condition.contains(), condition.scope())
                return ConditionResult.of(condition.matches(found))
            }
            is MacroCondition.InventoryCount -> {
                if (minecraft.player == null) return ConditionResult.UNKNOWN
                val actual = MacroCarriedInventory.count(minecraft, condition.skyBlockItemId(), condition.vanillaItemId())
                return ConditionResult.of(compareNumeric(actual.toDouble(), condition.threshold().toDouble(), condition.operator()))
            }
            is MacroCondition.Chat -> {
                val wanted = ChatText.stripForMatch(condition.text() ?: "").lowercase(Locale.ROOT)
                if (matchAnyChatSinceSequence) {
                    for (line in chatHistory) {
                        if (line.sequence <= minimumChatSequence) continue
                        val actual = line.text.lowercase(Locale.ROOT)
                        if (if (condition.contains()) actual.contains(wanted) else actual == wanted) return ConditionResult.TRUE
                    }
                    return ConditionResult.FALSE
                }
                if (lastChatSequence <= minimumChatSequence) return ConditionResult.FALSE
                val actual = lastChat.lowercase(Locale.ROOT)
                return ConditionResult.of(if (condition.contains()) actual.contains(wanted) else actual == wanted)
            }
            is MacroCondition.Variable -> {
                val globals = MacrosModule.INSTANCE.globalVariables()
                val leftValue: Any? = if (condition.comparesGlobal()) globals.value(condition.globalVariableId()) else variables?.get(condition.name())
                val right = resolveValue(condition.value(), variables, globals, minecraft)
                val left = leftValue ?: MacroValue.defaultValue(condition.value().type())
                val comparison = MacroFlowRules.compareValues(left, right)
                return ConditionResult.of(when (condition.operator()) {
                    MacroCondition.Variable.Operator.EQUALS -> comparison == 0
                    MacroCondition.Variable.Operator.NOT_EQUALS -> comparison != 0
                    MacroCondition.Variable.Operator.GREATER_THAN -> comparison > 0
                    MacroCondition.Variable.Operator.GREATER_OR_EQUAL -> comparison >= 0
                    MacroCondition.Variable.Operator.LESS_THAN -> comparison < 0
                    MacroCondition.Variable.Operator.LESS_OR_EQUAL -> comparison <= 0
                })
            }
            is MacroCondition.Hypixel -> {
                val value = HypixelConditionData.read(condition, minecraft)
                if (!value.available) return ConditionResult.UNKNOWN
                return compareHypixel(condition, value.value)
            }
            is MacroCondition.All -> {
                var unknown = false
                for (child in condition.children()) {
                    val result = condition(child, minecraft, minimumChatSequence, variables, matchAnyChatSinceSequence)
                    if (result == ConditionResult.FALSE) return ConditionResult.FALSE
                    if (result == ConditionResult.UNKNOWN) unknown = true
                }
                return if (unknown) ConditionResult.UNKNOWN else ConditionResult.TRUE
            }
            is MacroCondition.Any -> {
                var unknown = false
                for (child in condition.children()) {
                    val result = condition(child, minecraft, minimumChatSequence, variables, matchAnyChatSinceSequence)
                    if (result == ConditionResult.TRUE) return ConditionResult.TRUE
                    if (result == ConditionResult.UNKNOWN) unknown = true
                }
                return if (unknown) ConditionResult.UNKNOWN else ConditionResult.FALSE
            }
            is MacroCondition.Not -> return condition(condition.child(), minecraft, minimumChatSequence,
                variables, matchAnyChatSinceSequence).not()
        }
    }

    private fun compareHypixel(condition: MacroCondition.Hypixel, actual: Any?): ConditionResult {
        val expected = MacroJavaStrings.trim(condition.expected())!!
        val comparison: Int
        when (actual) {
            is Number -> comparison = try { java.lang.Double.compare(actual.toDouble(), expected.toDouble()) }
                catch (_: NumberFormatException) { return ConditionResult.UNKNOWN }
            is Boolean -> {
                if (!expected.equals("true", true) && !expected.equals("false", true)) return ConditionResult.UNKNOWN
                comparison = java.lang.Boolean.compare(actual, expected.toBoolean())
            }
            else -> {
                val value = actual.toString()
                comparison = value.compareTo(expected, ignoreCase = true)
                if (condition.operator() == MacroCondition.Hypixel.Operator.CONTAINS) {
                    return ConditionResult.of(value.lowercase(Locale.ROOT).contains(expected.lowercase(Locale.ROOT)))
                }
            }
        }
        return ConditionResult.of(when (condition.operator()) {
            MacroCondition.Hypixel.Operator.EQUALS -> comparison == 0
            MacroCondition.Hypixel.Operator.NOT_EQUALS -> comparison != 0
            MacroCondition.Hypixel.Operator.GREATER_THAN -> comparison > 0
            MacroCondition.Hypixel.Operator.GREATER_OR_EQUAL -> comparison >= 0
            MacroCondition.Hypixel.Operator.LESS_THAN -> comparison < 0
            MacroCondition.Hypixel.Operator.LESS_OR_EQUAL -> comparison <= 0
            MacroCondition.Hypixel.Operator.CONTAINS -> false
        })
    }

    private fun switchCaseMatches(value: Any?, caseValue: String?): Boolean {
        val expectedText = caseValue ?: ""
        if (value is Number) {
            return try {
                val actual = value.toDouble()
                val expected = MacroJavaStrings.trim(expectedText)!!.toDouble()
                actual.isFinite() && expected.isFinite() && actual == expected
            } catch (_: NumberFormatException) { false }
        }
        return (value?.toString() ?: "") == expectedText
    }

    private fun compareNumeric(left: Double, right: Double, operator: MacroCondition.Variable.Operator): Boolean {
        val comparison = java.lang.Double.compare(left, right)
        return when (operator) {
            MacroCondition.Variable.Operator.EQUALS -> comparison == 0
            MacroCondition.Variable.Operator.NOT_EQUALS -> comparison != 0
            MacroCondition.Variable.Operator.GREATER_THAN -> comparison > 0
            MacroCondition.Variable.Operator.GREATER_OR_EQUAL -> comparison >= 0
            MacroCondition.Variable.Operator.LESS_THAN -> comparison < 0
            MacroCondition.Variable.Operator.LESS_OR_EQUAL -> comparison <= 0
        }
    }

    private fun resolveValue(value: MacroValue?, variables: Map<String, Any?>?,
        globals: MacroVariableStore?, minecraft: Minecraft): Any? {
        if (value != null && value.type() == MacroValue.Type.ITEM_COUNT && !value.variableReference()) {
            return MacroCarriedInventory.count(minecraft, value.skyblockItemId(), value.vanillaItemId()).toDouble()
        }
        return value?.resolve(variables, globals) ?: MacroValue.defaultValue(MacroValue.Type.TEXT)
    }

    private fun findSlot(screen: Screen?, slotId: Int): Slot? {
        if (screen !is AbstractContainerScreen<*>) return null
        for (slot in screen.menu.slots) if (slot.index == slotId) return slot
        return null
    }

    private fun findItem(minecraft: Minecraft, name: String?, contains: Boolean, scope: String?, occurrence: Int): Slot? {
        val screen = minecraft.gui.screen()
        if (screen !is AbstractContainerScreen<*>) return null
        val playerInventory = minecraft.player?.inventory
        val searchScope = MacroCondition.ItemScope.fromSerialized(scope, MacroCondition.ItemScope.CONTAINER_AND_PLAYER)
        var found = 0
        for (slot in screen.menu.slots) {
            val playerSlot = playerInventory != null && slot.container === playerInventory
            if (searchScope?.includesPlayerSlot(playerSlot) != true || !itemMatches(slot.item, name, contains)) continue
            if (found++ == maxOf(0, occurrence)) return slot
        }
        return null
    }

    private fun hasItem(minecraft: Minecraft?, name: String?, contains: Boolean, scope: MacroCondition.ItemScope?): Boolean {
        if (minecraft == null || scope == null) return false
        val playerInventory: Inventory? = minecraft.player?.inventory
        if (scope != MacroCondition.ItemScope.CONTAINER && playerInventory != null) {
            for (slot in 0 until playerInventory.containerSize) if (itemMatches(playerInventory.getItem(slot), name, contains)) return true
        }
        val screen = minecraft.gui.screen()
        if (scope == MacroCondition.ItemScope.PLAYER_INVENTORY || screen !is AbstractContainerScreen<*>) return false
        for (slot in screen.menu.slots) {
            if (playerInventory != null && slot.container === playerInventory) continue
            if (itemMatches(slot.item, name, contains)) return true
        }
        return false
    }

    private fun delay(min: Int, max: Int): Int = MacroFlowRules.randomDelay(min, max, RANDOM)

    private class Run(val macro: MacroDefinition, val script: MacroScript) {
        private val frames: Deque<Frame> = ArrayDeque()
        private val waitChatSequenceFloors: MutableMap<MacroStep, Long> = IdentityHashMap()
        val startedNanos = System.nanoTime()
        private val chatStartSequence = chatSequence
        private var nextActionNanos = 0L
        private var blockedSinceNanos = 0L
        private var blockedReason: String? = null
        private val heldKeyState = MacroKeyHoldState()
        private var heldKeyInput: InputConstants.Key? = null
        private var heldKeyScreen: Screen? = null
        private var heldKeyDeliveredToScreen = false
        private var heldMouseInput: InputConstants.Key? = null
        private var heldMouseUntilNanos = 0L
        private var inputBlockUntilNanos = 0L
        private var indefiniteInputBlock = false
        private var pausedAtUnknown: MacroStep.Unknown? = null
        private var pausedFrame: Frame? = null
        private var pausedReason = ""
        /** The node whose explicit pre-node delay is currently being waited out. */
        private var pendingDelayStep: MacroStep? = null
        private var worldSwitchFrame: Frame? = null
        private var worldSwitchTarget: Island? = null
        private var levelChanged = false
        private var structuralYieldLogged = false
        private val notices: MutableSet<String> = HashSet()

        init {
            frames.push(Frame(script.steps(), 0, 1, false, null, HashMap(), java.util.Set.of<String>(), java.util.Set.of(macro.id()),
                0, script.id(), null))
            nextActionNanos = System.nanoTime()
        }

        fun markLevelChanged() {
            releaseAllInputs()
            levelChanged = true
        }

        fun isSyntheticKeyDown(keyCode: Int, nowNanos: Long): Boolean =
            (heldKeyInput != null && heldKeyInput!!.value == keyCode && heldKeyState.isDown(keyCode, nowNanos)) ||
                (heldMouseInput != null && heldMouseInput!!.value == keyCode && nowNanos < heldMouseUntilNanos)

        fun waitingForWorldSwitch(): Boolean = worldSwitchFrame != null || levelChanged

        fun isWaiting(): Boolean {
            val now = System.nanoTime()
            return isPaused() || blockedReason != null || now < nextActionNanos || heldKeyState.isScheduled() || heldMouseInput != null
        }

        fun isPaused(): Boolean = pausedAtUnknown != null
        fun pausedReason(): String = pausedReason

        fun disabledMacroId(): Int? {
            for (frame in frames) for (macroId in frame.macroPath) {
                val dependency = MacrosModule.INSTANCE.macro(macroId)
                if (dependency == null || !dependency.enabled()) return macroId
            }
            return null
        }

        fun canResumeAfterUnknownResolution(): Boolean = isPaused() && wasPausedUnknownStepResolved(
            pausedFrame?.steps, pausedAtUnknown)

        fun resumeAfterUnknownResolution(): Boolean {
            if (!canResumeAfterUnknownResolution()) return false
            pausedAtUnknown = null
            pausedFrame = null
            pausedReason = ""
            clearBlocked()
            nextActionNanos = System.nanoTime()
            return true
        }

        fun isExecutingScript(scriptId: String): Boolean {
            for (frame in frames) if (scriptId == frame.eventScriptId) return true
            return false
        }

        fun activeStepFor(scriptId: String): MacroStep? {
            if (isPaused()) return pausedAtUnknown
            for (frame in frames) {
                if (scriptId != frame.eventScriptId) continue
                if (frame.pendingCall != null) return frame.pendingCall
                if (frame.index >= 0 && frame.index < frame.steps.size) return frame.steps[frame.index]
            }
            return null
        }

        private fun callFunction(call: MacroStep.FunctionCall, caller: Frame, minecraft: Minecraft): Boolean {
            val function = MacrosModule.INSTANCE.function(call.functionId())
            if (function == null) {
                message(minecraft, "Macro '" + macro.name() + "' stopped: a called function is missing.")
                abort("missing function " + call.functionId())
                return false
            }
            if (!MacroRuntimeRules.canEnterCall(caller.callDepth, MAX_CALL_DEPTH, caller.functionPath.contains(function.id()))) {
                message(minecraft, "Macro '" + macro.name() + "' stopped: recursive function call limit reached.")
                abort("function recursion limit")
                return false
            }
            val arguments = MacroFlowRules.materializeItemCountArguments(call.arguments()) {
                MacroCarriedInventory.count(minecraft, it.skyblockItemId(), it.vanillaItemId())
            }
            val locals = function.bindArguments(arguments, caller.variables, MacrosModule.INSTANCE.globalVariables())
            val functionPath = HashSet(caller.functionPath)
            functionPath.add(function.id())
            caller.index++
            if (function.steps().isNotEmpty()) {
                caller.pendingCall = call
                frames.push(Frame(function.steps(), 0, 1, false, null, locals, java.util.Set.copyOf(functionPath),
                    caller.macroPath, caller.callDepth + 1, null, caller))
            }
            return true
        }

        private fun callMacro(call: MacroStep.MacroCall, caller: Frame, minecraft: Minecraft): Boolean {
            val target = MacrosModule.INSTANCE.macro(call.macroId())
            if (target == null || !target.enabled()) {
                message(minecraft, "Macro '" + macro.name() + "' stopped: the called macro is missing or disabled.")
                abort("missing or disabled macro " + call.macroId())
                return false
            }
            if (!MacroRuntimeRules.canEnterCall(caller.callDepth, MAX_CALL_DEPTH, caller.macroPath.contains(target.id()))) {
                message(minecraft, "Macro '" + macro.name() + "' stopped: recursive macro call limit reached.")
                abort("macro recursion limit")
                return false
            }
            if (!target.allowsIsland(HypixelModApi.currentIsland())) {
                message(minecraft, "Macro '" + macro.name() + "' stopped: the called macro is not allowed on this island.")
                abort("called macro island restriction")
                return false
            }
            var onCall: MacroScript? = null
            for (candidate in target.scripts()) if (candidate.trigger() == MacroScript.Trigger.ON_CALL) {
                onCall = candidate
                break
            }
            if (onCall == null) {
                message(minecraft, "Macro '" + macro.name() + "' stopped: target macro has no On Call stack.")
                abort("missing On Call stack")
                return false
            }
            if (onCall.steps().isNotEmpty() && isScriptRunning(onCall.id())) {
                message(minecraft, "Macro '" + macro.name() + "' skipped a call because that On Call stack is already running.")
                caller.index++
                return true
            }
            caller.index++
            val macroPath = HashSet(caller.macroPath)
            macroPath.add(target.id())
            if (onCall.steps().isNotEmpty()) {
                caller.pendingCall = call
                frames.push(Frame(onCall.steps(), 0, 1, false, null, HashMap(), caller.functionPath,
                    java.util.Set.copyOf(macroPath), caller.callDepth + 1, onCall.id(), caller))
            }
            return true
        }

        fun tick(minecraft: Minecraft) {
            if (isPaused()) return
            if (levelChanged) {
                if (worldSwitchFrame == null) {
                    abort("world changed without a World Switch node")
                    return
                }
                val current = HypixelModApi.currentIsland()
                if (current == Island.NONE) {
                    waitIndefinitely(minecraft, "world switch to ${worldSwitchTarget!!.label()}")
                    return
                }
                if (current != worldSwitchTarget) {
                    abort("world changed to ${current.label()}, expected ${worldSwitchTarget!!.label()}")
                    return
                }
                val step = worldSwitchFrame!!.steps[worldSwitchFrame!!.index]
                worldSwitchFrame!!.index++
                worldSwitchFrame = null
                worldSwitchTarget = null
                levelChanged = false
                clearBlocked()
                pendingDelayStep = null
                nextActionNanos = System.nanoTime()
                return
            }
            val now = System.nanoTime()
            if (inputBlockUntilNanos > 0 && now >= inputBlockUntilNanos) releaseInputBlock()
            if (heldMouseInput != null) {
                if (MacroRuntimeRules.shouldReleaseHeldMouse(minecraft.gui.screen() != null, now, heldMouseUntilNanos)) {
                    releaseHeldMouse()
                    nextActionNanos = now
                    return
                }
                return
            }
            if (heldKeyState.isScheduled()) {
                if (heldKeyScreen !== minecraft.gui.screen()) {
                    releaseHeldKey()
                    nextActionNanos = now
                } else if (!heldKeyState.isDue(now)) {
                    return
                } else {
                    releaseHeldKey()
                    nextActionNanos = now
                    return
                }
            }
            if (now < nextActionNanos) return

            var structural = 0
            while (structural++ < MAX_STRUCTURAL_STEPS) {
                val cursor = nextCursor(minecraft)
                if (cursor == null) {
                    finishRun(this, "", true)
                    return
                }
                val step = cursor.step
                if (step is MacroStep.Unknown) {
                    pauseForUnknownStep(minecraft, step, cursor.frame)
                    return
                }
                if (step is MacroStep.Comment) {
                    cursor.frame.index++
                    clearBlocked()
                    continue
                }
                if (step is MacroStep.WaitUntil) {
                    val chatFloor = waitChatSequenceFloors.getOrPut(step) { chatSequence }
                    val result = condition(step.condition(), minecraft, chatFloor, cursor.frame.variables, true)
                    if (result == ConditionResult.UNKNOWN) {
                        waitIndefinitely(minecraft, "Wait Until condition data unavailable")
                        return
                    }
                    if (result == ConditionResult.FALSE) {
                        waitIndefinitely(minecraft, "Wait Until condition")
                        return
                    }
                }
                if (step is MacroStep.Wait && step.mode() == MacroStep.Wait.Mode.CONDITION) {
                    val chatFloor = waitChatSequenceFloors.getOrPut(step) { chatSequence }
                    val result = condition(step.condition(), minecraft, chatFloor, cursor.frame.variables, true)
                    if (result == ConditionResult.UNKNOWN) {
                        waitIndefinitely(minecraft, "Wait condition data unavailable")
                        return
                    }
                    if (result == ConditionResult.FALSE) {
                        waitIndefinitely(minecraft, "Wait condition")
                        return
                    }
                }
                if (step is MacroStep.WorldSwitch && worldSwitchFrame == null) {
                    if (!delayReady(step, now)) return
                    when (MacroFlowRules.worldSwitchStart(ModConfig.hypixelModApi(), HypixelModApi.currentIsland(), step.target())) {
                        MacroFlowRules.WorldSwitchStart.DETECTION_OFF -> {
                            message(minecraft, "Macro '" + macro.name() + "' stopped: World Switch needs island detection (Hypixel Mod API) turned on.")
                            abort("world switch needs island detection")
                            return
                        }
                        MacroFlowRules.WorldSwitchStart.COMPLETE -> {
                            log(macro, "WORLD_SWITCH already on target=${step.target().label()}")
                            cursor.frame.index++
                            clearBlocked()
                            return
                        }
                        MacroFlowRules.WorldSwitchStart.WAIT -> Unit
                    }
                    worldSwitchFrame = cursor.frame
                    worldSwitchTarget = step.target()
                }
                if (step !is MacroStep.WorldSwitch && step !is MacroStep.RepeatUntil &&
                    !(step is MacroStep.Repeat && step.mode() == MacroStep.Repeat.Mode.UNTIL) && !delayReady(step, now)) return
                if (step is MacroStep.IfElse) {
                    val result = condition(step.condition(), minecraft, chatStartSequence, cursor.frame.variables)
                    if (result == ConditionResult.UNKNOWN) {
                        stopForUnknownCondition(minecraft, "If / else")
                        return
                    }
                    cursor.frame.index++
                    val selected = if (result == ConditionResult.TRUE) step.thenSteps()
                        else if (step.elseEnabled()) step.elseSteps() else java.util.List.of<MacroStep>()
                    log(macro, "BRANCH if result=$result selected=" +
                        (if (result == ConditionResult.TRUE) "then" else if (step.elseEnabled()) "else" else "none"))
                    if (selected.isNotEmpty()) frames.push(Frame(selected, 0, 1, false, null, cursor.frame))
                    continue
                }
                if (step is MacroStep.Switch) {
                    val current = if (step.targetsGlobal()) MacrosModule.INSTANCE.globalVariables().value(step.globalVariableId())
                        else cursor.frame.variables[step.name()]
                    var selected: MacroStep.SwitchCase? = null
                    var selectedIndex = -1
                    for (caseIndex in step.cases().indices) {
                        val branch = step.cases()[caseIndex]
                        if (!switchCaseMatches(current, branch.value())) continue
                        selected = branch
                        selectedIndex = caseIndex
                        break
                    }
                    val child = selected?.steps() ?: step.defaultSteps()
                    log(macro, "SWITCH matchedCase=$selectedIndex default=${selectedIndex < 0}")
                    cursor.frame.index++
                    if (child.isNotEmpty()) frames.push(Frame(child, 0, 1, false, null, cursor.frame))
                    continue
                }
                if (step is MacroStep.Repeat) {
                    if (step.mode() == MacroStep.Repeat.Mode.UNTIL) {
                        val result = condition(step.condition(), minecraft, chatStartSequence, cursor.frame.variables, true)
                        if (result == ConditionResult.UNKNOWN) {
                            stopForUnknownCondition(minecraft, "Repeat Until")
                            return
                        }
                        val decision = MacroFlowRules.repeatUntil(result == ConditionResult.TRUE, step.steps().isNotEmpty())
                        if (decision == MacroFlowRules.UntilDecision.EXIT) {
                            cursor.frame.index++
                            clearBlocked()
                            continue
                        }
                        if (decision == MacroFlowRules.UntilDecision.EMPTY_BODY) {
                            abort("Repeat Until has no steps")
                            return
                        }
                        if (!delayReady(step, now)) return
                        cursor.frame.index++
                        frames.push(Frame(step.steps(), 0, 1, false, step, cursor.frame))
                        continue
                    }
                    cursor.frame.index++
                    if (step.steps().isNotEmpty()) frames.push(Frame(step.steps(), 0,
                        if (step.mode() == MacroStep.Repeat.Mode.FOREVER) -1 else step.count(), true, null, cursor.frame))
                    continue
                }
                if (step is MacroStep.RepeatUntil) {
                    val result = condition(step.condition(), minecraft, chatStartSequence, cursor.frame.variables, true)
                    if (result == ConditionResult.UNKNOWN) {
                        stopForUnknownCondition(minecraft, "Repeat Until")
                        return
                    }
                    val decision = MacroFlowRules.repeatUntil(result == ConditionResult.TRUE, step.steps().isNotEmpty())
                    if (decision == MacroFlowRules.UntilDecision.EXIT) {
                        cursor.frame.index++
                        clearBlocked()
                        continue
                    }
                    if (decision == MacroFlowRules.UntilDecision.EMPTY_BODY) {
                        abort("Repeat Until has no steps")
                        return
                    }
                    if (!delayReady(step, now)) return
                    cursor.frame.index++
                    frames.push(Frame(step.steps(), 0, 1, false, step, cursor.frame))
                    continue
                }
                if (step is MacroStep.FunctionCall) {
                    if (!callFunction(step, cursor.frame, minecraft)) return
                    continue
                }
                if (step is MacroStep.MacroCall) {
                    if (step.condition() != null) {
                        val result = condition(step.condition(), minecraft, chatStartSequence, cursor.frame.variables)
                        if (result == ConditionResult.UNKNOWN) {
                            stopForUnknownCondition(minecraft, "Call Macro")
                            return
                        }
                        if (result == ConditionResult.FALSE) {
                            cursor.frame.index++
                            clearBlocked()
                            log(macro, "SKIP call to macro ${step.macroId()} (condition false)")
                            continue
                        }
                    }
                    if (!callMacro(step, cursor.frame, minecraft)) return
                    continue
                }
                if (step is MacroStep.WaitUntil) {
                    cursor.frame.index++
                    waitChatSequenceFloors.remove(step)
                    clearBlocked()
                    return
                }
                if (step is MacroStep.Wait && step.mode() == MacroStep.Wait.Mode.CONDITION) {
                    cursor.frame.index++
                    waitChatSequenceFloors.remove(step)
                    clearBlocked()
                    return
                }
                if (step is MacroStep.WorldSwitch) {
                    waitIndefinitely(minecraft, "world switch to ${worldSwitchTarget!!.label()}")
                    return
                }

                log(macro, "STEP type=${step.type()}")
                if (!execute(step, minecraft, now, cursor.frame.variables)) {
                    if (step is MacroStep.InventoryClick && step.target() == MacroStep.InventoryClick.Target.ITEM &&
                        finishRepeatUntilWhenItemIsMissing(minecraft)
                    ) continue
                    if (step is MacroStep.Key) {
                        message(minecraft, "Macro '" + macro.name() + "' stopped: could not apply key '" + keyDisplayName(step.key()) + "'.")
                        abort("could not apply key " + keyDisplayName(step.key()))
                        return
                    }
                    blocked(minecraft, "step ${step.type()}")
                    return
                }
                cursor.frame.index++
                clearBlocked()
                if (step is MacroStep.StopRun) {
                    finishRun(this, "stopped by Stop Run block", true)
                    return
                }
                return
            }
            if (!structuralYieldLogged) {
                log(macro, "YIELD after=$MAX_STRUCTURAL_STEPS structural steps; continuing next tick")
                structuralYieldLogged = true
            }
            nextActionNanos = System.nanoTime()
        }

        private fun nextCursor(minecraft: Minecraft): Cursor? {
            while (frames.isNotEmpty()) {
                val frame = frames.peek()
                if (frame.index < frame.steps.size) return Cursor(frame, frame.steps[frame.index])
                if (frame.repeatUntil != null) {
                    val result = condition(untilCondition(frame.repeatUntil), minecraft, chatStartSequence, frame.variables, true)
                    if (result == ConditionResult.UNKNOWN) {
                        stopForUnknownCondition(minecraft, "Repeat Until")
                        return null
                    }
                    val decision = MacroFlowRules.repeatUntil(result == ConditionResult.TRUE, frame.steps.isNotEmpty())
                    if (decision == MacroFlowRules.UntilDecision.EXIT) {
                        popFrame(true)
                        clearBlocked()
                        continue
                    }
                    if (decision == MacroFlowRules.UntilDecision.EMPTY_BODY) {
                        abort("Repeat Until has no steps")
                        return null
                    }
                    frame.index = 0
                    continue
                }
                if (frame.repeatFrame && (frame.repeatsRemaining < 0 || frame.repeatsRemaining > 1)) {
                    if (frame.repeatsRemaining > 1) frame.repeatsRemaining--
                    frame.index = 0
                    continue
                }
                popFrame(true)
            }
            return null
        }

        /** A missing Click Item is the normal final iteration when the Repeat Until stop condition is item-missing. */
        private fun finishRepeatUntilWhenItemIsMissing(minecraft: Minecraft): Boolean {
            for (frame in frames) {
                if (frame.repeatUntil == null) continue
                val result = condition(untilCondition(frame.repeatUntil), minecraft, chatStartSequence, frame.variables, true)
                if (result == ConditionResult.UNKNOWN) {
                    stopForUnknownCondition(minecraft, "Repeat Until")
                    return true
                }
                if (!MacroFlowRules.missingItemEndsUntil(true, result == ConditionResult.TRUE)) return false
                while (frames.peek() !== frame) popFrame(false)
                popFrame(false)
                pendingDelayStep = null
                clearBlocked()
                nextActionNanos = System.nanoTime()
                return true
            }
            return false
        }

        private fun untilCondition(step: MacroStep?): MacroCondition? = when (step) {
            is MacroStep.Repeat -> step.condition()
            is MacroStep.RepeatUntil -> step.condition()
            else -> MacroCondition.Always(false)
        }

        private fun popFrame(completed: Boolean) {
            val popped = frames.pop()
            val caller = popped.returnCaller ?: return
            caller.pendingCall = null
            if (completed && popped.eventScriptId != null) finishedScripts[popped.eventScriptId!!] = System.nanoTime()
        }

        private fun execute(step: MacroStep, minecraft: Minecraft, now: Long, variables: MutableMap<String, Any?>): Boolean {
            if (step is MacroStep.Command) {
                val player = minecraft.player ?: return false
                if (MacroJavaStrings.isBlank(step.command())) {
                    log(macro, "SKIP step=command reason=empty")
                    return true
                }
                val value = expandVariables(MacroJavaStrings.strip(step.command())!!, variables)
                player.connection.sendCommand(if (value.startsWith("/")) value.substring(1) else value)
                log(macro, "COMMAND submitted length=${value.length}")
                return true
            }
            if (step is MacroStep.Chat) {
                val player = minecraft.player ?: return false
                if (MacroJavaStrings.isBlank(step.message())) {
                    log(macro, "SKIP step=chat reason=empty")
                    return true
                }
                val value = expandVariables(step.message(), variables)
                val commandText = MacroJavaStrings.stripLeading(value)!!
                if (commandText.startsWith("/")) {
                    val command = commandText.substring(1)
                    if (MacroJavaStrings.isBlank(command)) return false
                    player.connection.sendCommand(command)
                    log(macro, "COMMAND submitted length=${command.length}")
                } else {
                    player.connection.sendChat(value)
                    rememberSentChat(value)
                    log(macro, "CHAT submitted length=${value.length}")
                }
                return true
            }
            if (step is MacroStep.Title) {
                MacroTitleOverlay.show(step)
                return true
            }
            if (step is MacroStep.Sound) {
                val soundId = step.soundId()
                val id = Identifier.tryParse(soundId)
                val event: SoundEvent? = if (id == null || !BuiltInRegistries.SOUND_EVENT.keySet().contains(id)) null
                    else BuiltInRegistries.SOUND_EVENT.getValue(id)
                if (event == null) {
                    message(minecraft, "Macro '" + macro.name() + "' stopped: sound ID is invalid or unavailable.")
                    log(macro, "SOUND rejected idLength=${soundId.length}")
                    abort("invalid or unavailable sound ID")
                    return true
                }
                minecraft.soundManager.play(SimpleSoundInstance.forUI(event, 1.0f, 1.0f))
                log(macro, "SOUND submitted id=$id")
                return true
            }
            if (step is MacroStep.Wait) {
                if (step.mode() == MacroStep.Wait.Mode.CONDITION) return true
                nextActionNanos = now + delay(step.minMillis(), step.maxMillis()) * 1_000_000L
                return true
            }
            if (step is MacroStep.Comment || step is MacroStep.StopRun) return true
            if (step is MacroStep.Scroll) return executeScroll(step, minecraft)
            if (step is MacroStep.Key) return when (step.inputMode()) {
                MacroStep.Key.InputMode.KEYBOARD -> pressKey(step, minecraft, now)
                MacroStep.Key.InputMode.MOUSE -> pressMouse(step.mouseButton(), step.hold(),
                    delay(step.holdMinMillis(), step.holdMaxMillis()), minecraft, now)
                MacroStep.Key.InputMode.HOTBAR -> selectHotbarSlot(step.hotbarSlot(), minecraft)
            }
            if (step is MacroStep.SelectHotbarSlot) return selectHotbarSlot(step.slot(), minecraft)
            if (step is MacroStep.MouseButton) return pressMouse(step, minecraft, now)
            if (step is MacroStep.BlockPlayerInput) {
                beginTimedInputBlock(now + step.durationMillis() * 1_000_000L)
                return true
            }
            if (step is MacroStep.StartBlockPlayerInput) {
                beginIndefiniteInputBlock()
                return true
            }
            if (step is MacroStep.StopBlockPlayerInput) {
                releaseInputBlock()
                return true
            }
            if (step is MacroStep.UpdateVariable) {
                val globals = MacrosModule.INSTANCE.globalVariables()
                if (step.operation() == MacroStep.UpdateVariable.Operation.SET) {
                    val value = resolveValue(step.value(), variables, globals, minecraft)
                    // DATA-3: a deleted global makes the step fail (5 s retry, then a visible stop) instead of doing nothing.
                    if (step.targetsGlobal()) return globals.setValue(step.globalVariableId(), value)
                    variables[step.name()] = value
                    return true
                }
                if (step.targetsGlobal() && globals.definition(step.globalVariableId()) == null) return false
                val current = if (step.targetsGlobal()) globals.value(step.globalVariableId()) else variables[step.name()]
                val number = when {
                    current is Number -> current.toDouble()
                    current == null || MacroJavaStrings.isBlank(current.toString()) -> 0.0
                    else -> MacroFlowRules.parsePlainNumber(current.toString()) ?: return false
                }
                val delta = if (step.operation() == MacroStep.UpdateVariable.Operation.ADD) step.amount() else -step.amount()
                if (step.targetsGlobal()) return globals.setValue(step.globalVariableId(), number + delta)
                variables[step.name()] = number + delta
                return true
            }
            if (step is MacroStep.InventoryClick) {
                val screen = minecraft.gui.screen() as? AbstractContainerScreen<*> ?: return false
                val slot = if (step.target() == MacroStep.InventoryClick.Target.SLOT) {
                    findSlot(screen, step.slotId())
                } else {
                    findItem(minecraft, step.name(), step.contains(), step.scope(), step.occurrence())
                }
                return slot != null && dispatchClick(screen, slot, step.button(), step.shift())
            }
            if (step is MacroStep.CloseScreen) {
                if (minecraft.gui.screen() == null) return true
                val old = minecraft.gui.screen()!!
                if (!old.keyPressed(KeyEvent(InputConstants.KEY_ESCAPE, 0, 0))) minecraft.gui.setScreen(null)
                return true
            }
            return false
        }

        private fun pressKey(step: MacroStep.Key, minecraft: Minecraft, now: Long): Boolean {
            val key = parseKey(step.key())
            if (key == InputConstants.UNKNOWN) return false
            val event = KeyEvent(key.value, 0, modifierMask(key))
            val screen = minecraft.gui.screen()
            if (screen != null) {
                if (step.hold()) {
                    val delivered = screen.keyPressed(event)
                    if (!delivered && modifierMask(key) == 0) return false
                    beginHeldKey(screen, key, delivered, now, step)
                } else {
                    if (!screen.keyPressed(event)) return false
                    (screen as GuiEventListener).keyReleased(event)
                }
            } else if (step.hold()) {
                beginHeldKey(null, key, false, now, step)
            } else {
                clickSyntheticKey(key)
            }
            return true
        }

        private fun selectHotbarSlot(slot: Int, minecraft: Minecraft): Boolean {
            val player = minecraft.player ?: return false
            if (!MacroRuntimeRules.canSelectHotbar(minecraft.gui.screen() is AbstractContainerScreen<*>)) {
                if (notices.add("hotbar-screen")) message(minecraft, "Hotbar selection skipped while an inventory or container is open.")
                return true
            }
            player.inventory.setSelectedSlot(maxOf(1, minOf(9, slot)) - 1)
            return true
        }

        private fun executeScroll(scroll: MacroStep.Scroll, minecraft: Minecraft): Boolean {
            val amount = if (scroll.direction() == MacroStep.Scroll.Direction.UP) scroll.amount().toDouble() else -scroll.amount().toDouble()
            val screen = minecraft.gui.screen()
            if (screen != null) {
                screen.mouseScrolled(minecraft.mouseHandler.getScaledXPos(minecraft.window),
                    minecraft.mouseHandler.getScaledYPos(minecraft.window), 0.0, amount)
                return true
            }
            val player = minecraft.player ?: return false
            val direction = Math.signum(amount).toInt()
            val selected = player.inventory.selectedSlot
            player.inventory.setSelectedSlot(Math.floorMod(selected - direction * scroll.amount(), 9))
            return true
        }

        private fun beginHeldKey(screen: Screen?, key: InputConstants.Key, delivered: Boolean,
            now: Long, step: MacroStep.Key) {
            val holdMillis = delay(step.holdMinMillis(), step.holdMaxMillis())
            heldKeyInput = key
            heldKeyScreen = screen
            heldKeyDeliveredToScreen = delivered
            if (screen == null) acquireHeldKey(this, key)
            heldKeyState.begin(key.value, now + holdMillis * 1_000_000L)
        }

        private fun pressMouse(step: MacroStep.MouseButton, minecraft: Minecraft, now: Long): Boolean =
            pressMouse(step.button(), step.hold(), step.holdMillis(), minecraft, now)

        private fun pressMouse(button: MacroStep.MouseButton.Button, hold: Boolean, holdMillis: Int,
            minecraft: Minecraft, now: Long): Boolean {
            if (minecraft.player == null || minecraft.gui.screen() != null) {
                if (notices.add("mouse-screen")) message(minecraft, "Mouse macro blocks run only in the world; close the current screen first.")
                return true
            }
            val mouseButton = when (button) {
                MacroStep.MouseButton.Button.LEFT -> 0
                MacroStep.MouseButton.Button.RIGHT -> 1
                MacroStep.MouseButton.Button.MIDDLE -> 2
                MacroStep.MouseButton.Button.BUTTON_4 -> 3
                MacroStep.MouseButton.Button.BUTTON_5 -> 4
                MacroStep.MouseButton.Button.BUTTON_6 -> 5
                MacroStep.MouseButton.Button.BUTTON_7 -> 6
                MacroStep.MouseButton.Button.BUTTON_8 -> 7
            }
            val key = InputConstants.Type.MOUSE.getOrCreate(mouseButton)
            if (hold) {
                heldMouseInput = key
                heldMouseUntilNanos = now + maxOf(1, holdMillis) * 1_000_000L
                acquireHeldKey(this, key)
            } else clickSyntheticKey(key)
            return true
        }

        private fun keyDisplayName(value: String?): String {
            val key = parseKey(value)
            return if (key == InputConstants.UNKNOWN) java.lang.String.valueOf(value) else key.displayName.string
        }

        private fun modifierMask(key: InputConstants.Key): Int = when (key.value) {
            InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> InputConstants.MOD_SHIFT
            InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> InputConstants.MOD_CONTROL
            InputConstants.KEY_LALT, InputConstants.KEY_RALT -> InputConstants.MOD_ALT
            InputConstants.KEY_LSUPER, InputConstants.KEY_RSUPER -> InputConstants.MOD_SUPER
            else -> 0
        }

        private fun dispatchClick(screen: AbstractContainerScreen<*>, slot: Slot, button: Int, shift: Boolean): Boolean {
            val access = screen as AbstractContainerScreenAccess
            val left = access.`geileraddons$getLeftPos`()
            val top = access.`geileraddons$getTopPos`()
            val modifiers = if (shift) InputConstants.MOD_SHIFT else 0
            val event = MouseButtonEvent(left.toDouble() + slot.x + 8.0, top.toDouble() + slot.y + 8.0, MouseButtonInfo(button, modifiers))
            val accepted = screen.mouseClicked(event, false)
            screen.mouseReleased(event)
            return accepted
        }

        private fun blocked(minecraft: Minecraft, reason: String): Boolean {
            val now = System.nanoTime()
            if (reason != blockedReason) {
                blockedReason = reason
                blockedSinceNanos = now
                log(macro, "RETRY start timeoutMs=${FAILURE_TIMEOUT_NANOS / 1_000_000L} reason=$reason")
            }
            if (now - blockedSinceNanos < FAILURE_TIMEOUT_NANOS) return false
            message(minecraft, "Macro '" + macro.name() + "' stopped after 5 seconds: $reason.")
            abort(reason)
            return true
        }

        private fun waitIndefinitely(minecraft: Minecraft, reason: String) {
            val now = System.nanoTime()
            if (reason != blockedReason) {
                blockedReason = reason
                blockedSinceNanos = now
                log(macro, "WAIT indefinite reason=$reason")
            }
            if (MacroRuntimeRules.inputBlockWaitExpired(indefiniteInputBlock, blockedSinceNanos, now)) {
                message(minecraft, "Macro '" + macro.name() + "' stopped: waited 60 seconds with player input blocked ($reason).")
                abort("input block held during wait: $reason")
            }
        }

        private fun clearBlocked() {
            val reason = blockedReason
            val waitedNanos = if (blockedSinceNanos == 0L) 0L else maxOf(0L, System.nanoTime() - blockedSinceNanos)
            blockedReason = null
            blockedSinceNanos = 0
            if (reason != null) log(macro, "RESUME waitedMs=${waitedNanos / 1_000_000L} reason=$reason")
        }

        private fun beginTimedInputBlock(untilNanos: Long) {
            if (inputBlockOwners.isEmpty()) clearPhysicalGameInputs()
            inputBlockOwners.add(this)
            indefiniteInputBlock = false
            inputBlockUntilNanos = maxOf(System.nanoTime() + 1_000_000L, untilNanos)
            nextActionNanos = inputBlockUntilNanos
        }

        private fun beginIndefiniteInputBlock() {
            if (inputBlockOwners.isEmpty()) clearPhysicalGameInputs()
            inputBlockOwners.add(this)
            indefiniteInputBlock = true
            inputBlockUntilNanos = 0
        }

        private fun releaseInputBlock() {
            inputBlockOwners.remove(this)
            inputBlockUntilNanos = 0
            indefiniteInputBlock = false
        }

        private fun delayReady(step: MacroStep, now: Long): Boolean {
            if (pendingDelayStep !== step) {
                pendingDelayStep = step
                nextActionNanos = now + delay(step.delayMin(), step.delayMax()) * 1_000_000L
                return false
            }
            if (now < nextActionNanos) return false
            pendingDelayStep = null
            return true
        }

        private fun releaseHeldKey() {
            if (!heldKeyState.isScheduled()) return
            val key = heldKeyInput
            val screen = heldKeyScreen
            val delivered = heldKeyDeliveredToScreen
            heldKeyState.clear()
            heldKeyInput = null
            heldKeyScreen = null
            heldKeyDeliveredToScreen = false
            if (key == null) return
            val event = KeyEvent(key.value, 0, modifierMask(key))
            if (screen != null && delivered) (screen as GuiEventListener).keyReleased(event)
            if (screen == null) MacroRunner.releaseHeldKey(this, key)
        }

        private fun releaseHeldMouse() {
            val key = heldMouseInput
            heldMouseInput = null
            heldMouseUntilNanos = 0
            if (key != null) MacroRunner.releaseHeldKey(this, key)
        }

        fun releaseAllInputs() {
            releaseHeldKey()
            releaseHeldMouse()
            releaseInputBlock()
        }

        private fun abort(reason: String) = finishRun(this, reason, false)

        private fun stopForUnknownCondition(minecraft: Minecraft, nodeName: String) {
            val notice = "$nodeName needs current client data; this macro was stopped because the value is missing or stale."
            message(minecraft, "Macro '" + macro.name() + "' stopped: $notice")
            log(macro, "STOPPED: unknown condition in $nodeName")
            abort("unknown condition in $nodeName")
        }

        private fun pauseForUnknownStep(minecraft: Minecraft, step: MacroStep.Unknown, frame: Frame) {
            val type = step.originalType()
            pausedReason = if (step.depthLimited()) "a block exceeds the supported nesting depth"
                else "this build does not recognize block type '$type'"
            pausedAtUnknown = step
            pausedFrame = frame
            releaseAllInputs()
            clearBlocked()
            message(minecraft, "Macro '" + macro.name() + "' paused at a preserved block: $pausedReason. " +
                "Open the editor to inspect its raw data, then remove or replace it before resuming.")
            log(macro, "PAUSED: preserved unknown step $type")
        }
    }

    private class Frame(
        val steps: List<MacroStep>,
        var index: Int,
        var repeatsRemaining: Int,
        val repeatFrame: Boolean,
        val repeatUntil: MacroStep?,
        val variables: MutableMap<String, Any?>,
        val functionPath: Set<String>,
        val macroPath: Set<Int>,
        val callDepth: Int,
        val eventScriptId: String?,
        val returnCaller: Frame?,
    ) {
        var pendingCall: MacroStep? = null

        constructor(steps: List<MacroStep>, index: Int, repeatsRemaining: Int, repeatFrame: Boolean) :
            this(steps, index, repeatsRemaining, repeatFrame, null, HashMap(), java.util.Set.of<String>(), java.util.Set.of<Int>(), 0, null, null)

        constructor(steps: List<MacroStep>, index: Int, repeatsRemaining: Int, repeatFrame: Boolean,
            repeatUntil: MacroStep?) :
            this(steps, index, repeatsRemaining, repeatFrame, repeatUntil, HashMap(), java.util.Set.of<String>(), java.util.Set.of<Int>(), 0, null, null)

        constructor(steps: List<MacroStep>, index: Int, repeatsRemaining: Int, repeatFrame: Boolean,
            repeatUntil: MacroStep?, parent: Frame) :
            this(steps, index, repeatsRemaining, repeatFrame, repeatUntil, parent.variables, parent.functionPath,
                parent.macroPath, parent.callDepth, parent.eventScriptId, null)

        init {
            // The primary constructor mirrors the full Java frame state used for nested macro/function calls.
        }
    }

    private data class Cursor(val frame: Frame, val step: MacroStep)
}








