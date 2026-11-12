package geiler.addons.client.module.impl

import geiler.addons.client.access.AbstractContainerScreenAccess
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.enchanting.AutoExperimentAutomation
import geiler.addons.client.enchanting.AutoExperimentDelayRange
import geiler.addons.client.enchanting.ExperimentBoardGeometry
import geiler.addons.client.enchanting.ExperimentPhase
import geiler.addons.client.enchanting.ExperimentTier
import geiler.addons.client.enchanting.ExperimentType
import geiler.addons.client.enchanting.SolverView
import geiler.addons.client.enchanting.UltrasequencerSequenceExecutor
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import java.util.Optional
import java.util.concurrent.ThreadLocalRandom

/** Automatically replays the shared Chronomatron and Ultrasequencer solutions. */
class AutoExperimentsModule private constructor(
    settings: Settings = Settings(),
) : Module(
    "Auto Experiments",
    "Automatically solves Chronomatron and Ultrasequencer.",
    Category.ENCHANTING,
    *settings.allSettings(),
) {
    companion object {
        @JvmField
        val INSTANCE = AutoExperimentsModule()

        private const val DIAGNOSTIC_STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 512

        /** Safe recognition only; normal parsing still controls observation and click eligibility. */
        @JvmStatic
        fun hintedSequenceType(title: String?): ExperimentType? {
            val normalized = ExperimentPhase.normalizeStatus(title)
            return when {
                normalized.startsWith("chronomatron (") -> ExperimentType.CHRONOMATRON
                normalized.startsWith("ultrasequencer (") -> ExperimentType.ULTRASEQUENCER
                else -> null
            }
        }

        private fun isBoardSlot(title: String, slot: Slot?, slotId: Int): Boolean {
            if (slot == null || slot.index != slotId || slot.container is Inventory) return false
            val type = ExperimentType.fromTitle(title).orElse(null) ?: return false
            if (type != ExperimentType.CHRONOMATRON && type != ExperimentType.ULTRASEQUENCER) return false
            val tier = ExperimentTier.fromTitle(title).orElse(ExperimentTier.UNKNOWN)
            return ExperimentBoardGeometry.forExperiment(type, tier).containsSlot(slotId)
        }

        private fun showMessage(message: String) {
            val minecraft = Minecraft.getInstance()
            val gui = minecraft.gui
            if (gui != null) {
                gui.hud.getChat().addClientSystemMessage(Component.literal("[GeilerAddons] $message"))
            }
        }
    }

    private class Settings {
        val chronomatron = BooleanSetting("Chronomatron", true)
        val ultrasequencer = BooleanSetting("Ultrasequencer", true)
        val firstClickDelay = NumberSetting("First Click Delay (ms)", 0f, 2_000f, 500f, true)
        val minimumClickDelay = NumberSetting(
            "Minimum Click Delay (ms)",
            0f,
            2_000f,
            AutoExperimentDelayRange.DEFAULT_MINIMUM_MILLIS.toFloat(),
            true,
        )
        val maximumClickDelay = NumberSetting(
            "Maximum Click Delay (ms)",
            0f,
            2_000f,
            AutoExperimentDelayRange.DEFAULT_MAXIMUM_MILLIS.toFloat(),
            true,
        )
        val debug = BooleanSetting("Debug Automation", false)

        fun allSettings(): Array<Setting> = arrayOf(
            chronomatron,
            ultrasequencer,
            firstClickDelay,
            minimumClickDelay,
            maximumClickDelay,
            debug,
        )
    }

    private val chronomatron = settings.chronomatron
    private val ultrasequencer = settings.ultrasequencer
    private val firstClickDelay = settings.firstClickDelay
    private val minimumClickDelay = settings.minimumClickDelay
    private val maximumClickDelay = settings.maximumClickDelay
    private val debug = settings.debug
    private val automation = AutoExperimentAutomation()
    private val ultrasequencerAutomation = UltrasequencerSequenceExecutor()
    private var reportedDecision = ""
    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE

    init {
        group(
            SettingGroup("Experiments", chronomatron, ultrasequencer),
            SettingGroup("Timing", firstClickDelay, minimumClickDelay, maximumClickDelay),
            SettingGroup("Diagnostics", debug),
        )
    }

    fun supports(type: ExperimentType): Boolean = when (type) {
        ExperimentType.CHRONOMATRON -> chronomatron.value()
        ExperimentType.ULTRASEQUENCER -> ultrasequencer.value()
        ExperimentType.SUPERPAIRS -> false
    }

    fun chronomatron(): BooleanSetting = chronomatron

    fun ultrasequencer(): BooleanSetting = ultrasequencer

    fun firstClickDelay(): NumberSetting = firstClickDelay

    fun minimumClickDelay(): NumberSetting = minimumClickDelay

    fun maximumClickDelay(): NumberSetting = maximumClickDelay

    /** Read-only Auto cursor for the matching live Ultrasequencer screen and solver solution. */
    fun ultrasequencerPresentationProgress(
        screen: AbstractContainerScreen<*>?,
        solverView: SolverView?,
    ): Optional<UltrasequencerSequenceExecutor.ExecutionProgress> {
        val controller = ExperimentController.INSTANCE
        if (!isEnabled() || !supports(ExperimentType.ULTRASEQUENCER)
            || screen == null || Minecraft.getInstance().gui.screen() !== screen
            || !controller.isAutoEligibleScreen(screen)
        ) return Optional.empty()
        val session = controller.session()
        if (session == null || session.type != ExperimentType.ULTRASEQUENCER
            || session.attachedMenu !== screen.menu || screen.menu.containerId != session.menuId
        ) return Optional.empty()
        return ultrasequencerAutomation.presentationProgress(
            screen,
            screen.menu,
            controller.sessionGeneration(),
            solverView,
        )
    }

    /** Runs after the shared controller has observed the current client tick. */
    fun tick() {
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        val enabled = isEnabled()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.ENCHANTING, name(), enabled || debug.value())
        if (!enabled) {
            automation.reset()
            ultrasequencerAutomation.reset()
            reportInactive()
            return
        }

        val minecraft = Minecraft.getInstance()
        val screen = minecraft.gui.screen() as? AbstractContainerScreen<*>
        val screenType = screen?.let { ExperimentType.fromTitle(it.title.string).orElse(null) }
        if (screenType == ExperimentType.ULTRASEQUENCER && supports(ExperimentType.ULTRASEQUENCER)) {
            automation.reset()
            tickUltrasequencer(screen)
            return
        }

        ultrasequencerAutomation.reset()
        val menu = screen?.menu
        val snapshot = snapshot(screen)
        val firstDelayNanos = firstClickDelay.intValue().toLong() * 1_000_000L
        val awaitingSlot = automation.awaitingMenuSlotId()
        val observedMenuRevision = if (awaitingSlot >= 0) {
            ExperimentController.INSTANCE.menuUpdateRevision(menu, awaitingSlot)
        } else {
            ExperimentController.INSTANCE.menuUpdateRevision(menu)
        }
        val decision = automation.tickWithMenuRevision(
            snapshot,
            System.nanoTime(),
            firstDelayNanos,
            observedMenuRevision,
        )
        applyDecision(screen, menu, decision)
        if (decision.action() != AutoExperimentAutomation.Action.CLICK || screen == null) {
            reportDecision(screen, snapshot, decision, "none", "")
            return
        }

        var dispatched = false
        var failure = ""
        val menuRevisionBeforeClick =
            ExperimentController.INSTANCE.menuUpdateRevision(menu, decision.slotId())
        try {
            dispatched = ExperimentController.INSTANCE.dispatchAutoSequenceClick(
                screen,
                decision.sequenceIndex(),
                decision.slotId(),
            ) { slot, slotId, button, input ->
                (screen as Any as AbstractContainerScreenAccess)
                    .`geileraddons$invokeSlotClicked`(slot, slotId, button, input)
            }
        } catch (exception: RuntimeException) {
            // The click gate already prevents confirmation if vanilla dispatch failed. Do not retry.
            failure = exception.javaClass.simpleName + (exception.message?.let { ": $it" } ?: "")
        }
        val delayRange = AutoExperimentDelayRange(minimumClickDelay.intValue(), maximumClickDelay.intValue())
        val betweenClickDelayNanos = delayRange.sampleNanos { bound -> ThreadLocalRandom.current().nextLong(bound) }
        val afterScreen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        val result = automation.clickResultWithMenuRevision(
            dispatched,
            snapshot(afterScreen),
            System.nanoTime(),
            betweenClickDelayNanos,
            menuRevisionBeforeClick,
            decision.slotId(),
        )
        applyDecision(screen, menu, result)
        reportDecision(screen, snapshot, result, if (dispatched) "dispatched" else "rejected", failure)
    }

    /** True while verbose automation diagnostics are switched on; the controller reuses this gate. */
    fun debugAutomation(): Boolean = debug.value()

    /** Records why automation is not switching on when the opt-in diagnostic is enabled. */
    private fun reportInactive() {
        if (!debug.value()) {
            reportedDecision = ""
            return
        }
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        val title = screen?.title?.string.orEmpty()
        val type = ExperimentType.fromTitle(title).orElse(null)
        if (type == null) {
            reportedDecision = ""
            return
        }
        val fingerprint = "inactive|$title|$type"
        if (!shouldWriteDiagnostic(fingerprint)) return
        writeDiagnostic("auto inactive: module disabled, screen=$title, type=$type, supports=${supports(type)}")
    }

    /** Writes a changed status immediately and a bounded heartbeat while Auto is enabled. */
    private fun reportDecision(
        screen: AbstractContainerScreen<*>?,
        snapshot: AutoExperimentAutomation.Snapshot,
        decision: AutoExperimentAutomation.Decision,
        dispatch: String,
        failure: String,
    ) {
        if (!debug.value() && !isEnabled()) {
            reportedDecision = ""
            return
        }

        val type = snapshot.type()
        if (screen == null || type == null) {
            val status = if (screen == null) "no-screen" else "unrecognized-screen"
            val fingerprint = "$status|${decision.action()}|$dispatch|$failure"
            if (!shouldWriteDiagnostic(fingerprint)) return
            writeDiagnostic(
                "auto: enabled=${isEnabled()}, screen=$status" +
                    ", decision=${decision.action()}, dispatch=$dispatch" +
                    ", timing=${timingSummary()}" +
                    (if (failure.isEmpty()) "" else ", failure=$failure") +
                    (if (decision.explanation().isBlank()) "" else ", reason=${decision.explanation()}"),
            )
            return
        }

        val controller = ExperimentController.INSTANCE
        val view = controller.view()
        val pendingMenuSlot = automation.awaitingMenuSlotId()
        val menuRevision = if (pendingMenuSlot >= 0) {
            controller.menuUpdateRevision(screen.menu, pendingMenuSlot)
        } else {
            controller.menuUpdateRevision(screen.menu)
        }
        val fingerprint = listOf(
            type.name,
            snapshot.tier().toString(),
            snapshot.contextValid().toString(),
            snapshot.gameEnabled().toString(),
            controller.isAutoEligibleScreen(screen).toString(),
            controller.hasActiveOwner(type).toString(),
            controller.sessionGeneration().toString(),
            menuRevision.toString(),
            pendingMenuSlot.toString(),
            view.phase().toString(),
            "${snapshot.currentIndex()}/${snapshot.sequenceLength()}",
            snapshot.expectedSlotId().toString(),
            decision.action().toString(),
            dispatch,
            failure,
        ).joinToString("|")
        if (!shouldWriteDiagnostic(fingerprint)) return
        writeDiagnostic(
            "auto: screen=${screen.title.string}, type=$type, tier=${snapshot.tier()}" +
                ", enabled=${isEnabled()}, supports=${supports(type)}" +
                ", eligible=${controller.isAutoEligibleScreen(screen)}" +
                ", owner=${controller.hasActiveOwner(type)}" +
                ", generation=${controller.sessionGeneration()}" +
                ", menuRevision=$menuRevision, awaitingSlot=$pendingMenuSlot" +
                ", engine=${view.phase()} ${snapshot.currentIndex()}/${snapshot.sequenceLength()}" +
                " expected=${snapshot.expectedSlotId()}, decision=${decision.action()}, dispatch=$dispatch" +
                ", timing=${timingSummary()}" +
                (if (failure.isEmpty()) "" else ", failure=$failure") +
                (if (decision.explanation().isBlank()) "" else ", reason=${decision.explanation()}"),
        )
    }

    private fun tickUltrasequencer(screen: AbstractContainerScreen<*>) {
        val controller = ExperimentController.INSTANCE
        val solverView = controller.view()
        val session = controller.session()
        val sessionValid = controller.isAutoEligibleScreen(screen) && session != null
            && session.attachedMenu === screen.menu && session.type == ExperimentType.ULTRASEQUENCER
            && screen.menu.containerId == session.menuId
        val awaitingSlot = ultrasequencerAutomation.awaitingMenuSlotId()
        val observedMenuRevision = if (awaitingSlot >= 0) {
            controller.menuUpdateRevision(screen.menu, awaitingSlot)
        } else {
            controller.menuUpdateRevision(screen.menu)
        }
        val decision = ultrasequencerAutomation.tickWithMenuRevision(
            screen,
            screen.menu,
            controller.sessionGeneration(),
            sessionValid,
            solverView,
            System.nanoTime(),
            firstClickDelay.intValue().toLong() * 1_000_000L,
            observedMenuRevision,
        )
        var pauseNotice = ultrasequencerAutomation.consumePauseNotice()
        if (!pauseNotice.isBlank()) showMessage(pauseNotice)

        if (decision.action() == UltrasequencerSequenceExecutor.Action.CLOSE_MENU) {
            applyDecision(
                screen,
                screen.menu,
                AutoExperimentAutomation.Decision(AutoExperimentAutomation.Action.CLOSE_MENU, -1, -1, ""),
            )
            reportUltrasequencer(
                screen,
                solverView,
                ultrasequencerAutomation.reason(),
                "milestone reached; close requested",
            )
            return
        }
        if (decision.action() != UltrasequencerSequenceExecutor.Action.CLICK) {
            if (decision.action() == UltrasequencerSequenceExecutor.Action.PAUSED) {
                applyDecision(
                    screen,
                    screen.menu,
                    AutoExperimentAutomation.Decision(AutoExperimentAutomation.Action.PAUSED, -1, -1, ""),
                )
            }
            reportUltrasequencer(screen, solverView, decision.explanation(), "not attempted")
            return
        }

        var dispatched = false
        var failure = ""
        val menuRevisionBeforeClick = controller.menuUpdateRevision(screen.menu, decision.slotId())
        try {
            dispatched = controller.dispatchAutoUltrasequencerClick(screen, decision.slotId()) { slot, slotId, button, input ->
                (screen as Any as AbstractContainerScreenAccess)
                    .`geileraddons$invokeSlotClicked`(slot, slotId, button, input)
            }
        } catch (exception: RuntimeException) {
            // The executor has consumed this position, so an uncertain dispatch can never be resent.
            failure = exception.javaClass.simpleName + (exception.message?.let { ": $it" } ?: "")
        }

        val completion = ultrasequencerAutomation.dispatchFinishedWithMenuRevision(
            dispatched,
            System.nanoTime(),
            sampleBetweenClickDelayNanos(),
            menuRevisionBeforeClick,
        )
        pauseNotice = ultrasequencerAutomation.consumePauseNotice()
        if (!pauseNotice.isBlank()) showMessage(pauseNotice)
        if (completion.action() == UltrasequencerSequenceExecutor.Action.CLOSE_MENU) {
            applyDecision(
                screen,
                screen.menu,
                AutoExperimentAutomation.Decision(AutoExperimentAutomation.Action.CLOSE_MENU, -1, -1, ""),
            )
            reportUltrasequencer(
                screen,
                controller.view(),
                ultrasequencerAutomation.reason(),
                "milestone reached; close requested",
            )
            return
        }

        val dispatch = if (failure.isEmpty()) {
            if (dispatched) "vanilla dispatched" else "session guard rejected"
        } else {
            "dispatch threw; paused without resend ($failure)"
        }
        reportUltrasequencer(screen, controller.view(), ultrasequencerAutomation.reason(), dispatch)
    }

    private fun reportUltrasequencer(
        screen: AbstractContainerScreen<*>?,
        view: SolverView?,
        decision: String,
        dispatch: String,
    ) {
        if (!debug.value() && !isEnabled()) {
            reportedDecision = ""
            return
        }
        val controller = ExperimentController.INSTANCE
        val solverView = view ?: SolverView.idle()
        val current = solverView.current()
            .map { step -> "${step.index()}:${step.value()}@${step.slotIds()}" }
            .orElse("none")
        val solverOwner = ExperimentSolverModule.INSTANCE.isEnabled()
            && ExperimentSolverModule.INSTANCE.supports(ExperimentType.ULTRASEQUENCER)
        val pendingMenuSlot = ultrasequencerAutomation.awaitingMenuSlotId()
        val menuRevision = if (pendingMenuSlot >= 0) {
            controller.menuUpdateRevision(screen?.menu, pendingMenuSlot)
        } else {
            controller.menuUpdateRevision(screen?.menu)
        }
        val line = "ultrasequencer: screen=${screen?.title?.string ?: "none"}" +
            ", decision=$decision" +
            ", solutionCursor=${ultrasequencerAutomation.cursor()}/${ultrasequencerAutomation.sequenceLength()}" +
            ", solver=${solverView.phase()}, current=$current" +
            ", visual=${solverView.visualIndex()}/${solverView.sequence().size}" +
            ", paused=${ultrasequencerAutomation.paused()}" +
            ", pauseReason=${ultrasequencerAutomation.pauseReason()}" +
            ", owners=auto:${isEnabled() && supports(ExperimentType.ULTRASEQUENCER)}" +
            ",solver:$solverOwner,active:${controller.hasActiveOwner(ExperimentType.ULTRASEQUENCER)}" +
            ", generation=${controller.sessionGeneration()}" +
            ", menuRevision=$menuRevision, awaitingSlot=$pendingMenuSlot" +
            ", pendingDelay=${ultrasequencerAutomation.pendingDelay()}, dispatch=$dispatch" +
            ", timing=${timingSummary()}"
        val fingerprint = "ultra|$line"
        if (shouldWriteDiagnostic(fingerprint)) writeDiagnostic(line)
    }

    /** Cancels a queued click and pauses until the user toggles the module off and on. */
    fun pauseAfterManualInput(screen: AbstractContainerScreen<*>?) {
        if (!isEnabled() || screen == null || !ExperimentController.INSTANCE.isAutoEligibleScreen(screen)) return
        val type = ExperimentType.fromTitle(screen.title.string).orElse(null)
        val explanation = if (type == ExperimentType.ULTRASEQUENCER) {
            ultrasequencerAutomation.pauseForManualInput(
                screen,
                screen.menu,
                ExperimentController.INSTANCE.sessionGeneration(),
            )
        } else {
            automation.pauseForManualInput()
        }
        if (explanation.isNotBlank()) showMessage(explanation)
    }

    /** Intercepts Chronomatron clicks when the Solver replacement surface is disabled. */
    fun handleVanillaSlotClick(
        screen: AbstractContainerScreen<*>?,
        clickedSlot: Slot?,
        slotId: Int,
        button: Int,
        input: ContainerInput?,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean {
        if (ExperimentController.INSTANCE.isDispatchingCustomClick()
            || !ExperimentController.INSTANCE.isAutoEligibleScreen(screen)
            || screen == null
            || !isBoardSlot(screen.title.string, clickedSlot, slotId)
        ) return false

        val type = ExperimentType.fromTitle(screen.title.string).orElse(null)
        if (type == ExperimentType.ULTRASEQUENCER) {
            pauseAfterManualInput(screen)
            return false
        }
        val correctStep = button == 0 && input == ContainerInput.PICKUP
            && ExperimentController.INSTANCE.isExpectedStepClick(slotId)
        // A correct expected click is the player doing the same thing Auto would do, not a takeover.
        if (!correctStep) pauseAfterManualInput(screen)
        if (button == 0 && input == ContainerInput.PICKUP) {
            val result = ExperimentController.INSTANCE.dispatchManualSequenceClick(screen, slotId, dispatcher)
            if (correctStep) {
                if (result.progressAdvanced()) {
                    val synchronizedStep = automation.synchronizeAfterManualProgress(
                        snapshot(screen),
                        System.nanoTime(),
                        sampleBetweenClickDelayNanos(),
                    )
                    applyDecision(screen, screen.menu, synchronizedStep)
                } else {
                    pauseAfterManualInput(screen)
                }
            }
        }
        return true
    }

    override fun onEnable() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.ENCHANTING, name(), true)
        automation.reset()
        ultrasequencerAutomation.reset()
        // Saved module state is restored during Minecraft construction, before its Gui is assigned.
        val gui: net.minecraft.client.gui.Gui? = Minecraft.getInstance().gui
        val screen = gui?.screen() as? AbstractContainerScreen<*>
        if (screen != null) ExperimentController.INSTANCE.onScreenOpened(screen)
    }

    override fun onDisable() {
        automation.reset()
        ultrasequencerAutomation.reset()
        ExperimentController.INSTANCE.onOwnerDisabled()
        writeDiagnostic("STATUS enabled=false;automation=reset;owner=released")
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.ENCHANTING, name(), debug.value())
    }

    private fun snapshot(screen: AbstractContainerScreen<*>?): AutoExperimentAutomation.Snapshot {
        val controller = ExperimentController.INSTANCE
        if (screen == null) {
            return AutoExperimentAutomation.Snapshot(
                isEnabled(), false, false,
                null, null, null, ExperimentTier.UNKNOWN,
                ExperimentPhase.IDLE, "", false, 0, 0, -1, -1, false,
            )
        }

        val title = screen.title.string
        val type = ExperimentType.fromTitle(title).orElse(null)
        if (type == null) {
            val hintedType = hintedSequenceType(title)
            if (hintedType != null && supports(hintedType)) {
                // Recognize malformed titles only to pause safely; never make them click-eligible.
                return AutoExperimentAutomation.Snapshot(
                    isEnabled(), true, true,
                    screen, screen.menu, hintedType, ExperimentTier.UNKNOWN, ExperimentPhase.IDLE,
                    "", false, 0, 0, -1, -1, false,
                )
            }
            return AutoExperimentAutomation.Snapshot(
                isEnabled(), false, false,
                screen, screen.menu, null, ExperimentTier.UNKNOWN,
                ExperimentPhase.IDLE, "", false, 0, 0, -1, -1, false,
            )
        }
        if (!controller.isAutoEligibleScreen(screen)) {
            return AutoExperimentAutomation.Snapshot(
                isEnabled(), false, false,
                screen, screen.menu, type, ExperimentTier.UNKNOWN,
                ExperimentPhase.IDLE, "", false, 0, 0, -1, -1, false,
            )
        }

        val tier = ExperimentTier.fromTitle(title).orElse(ExperimentTier.UNKNOWN)
        val session = controller.session()
        val view = controller.view()
        val sameSession = session != null && session.attachedMenu === screen.menu
            && session.type == type && screen.menu.containerId == session.menuId
        val sameView = view.type() == type
        val expectedSlot = if (sameView) {
            view.current().flatMap { step -> step.slotIds().stream().findFirst() }.orElse(-1)
        } else {
            -1
        }
        return AutoExperimentAutomation.Snapshot(
            isEnabled(), supports(type), true,
            screen, screen.menu, type, tier,
            if (sameView) view.phase() else ExperimentPhase.IDLE,
            if (sameSession) session.instruction else "",
            sameView && view.sequence().isNotEmpty(),
            if (sameView) view.visualIndex() else 0,
            if (sameView) view.sequence().size else 0,
            expectedSlot,
            if (sameView) view.completedRounds() else -1,
            sameView && view.milestoneReached(),
        )
    }

    private fun sampleBetweenClickDelayNanos(): Long =
        AutoExperimentDelayRange(minimumClickDelay.intValue(), maximumClickDelay.intValue())
            .sampleNanos { bound -> ThreadLocalRandom.current().nextLong(bound) }

    private fun timingSummary(): String =
        "first=${firstClickDelay.intValue()}ms,between=${minimumClickDelay.intValue()}..${maximumClickDelay.intValue()}ms"

    private fun showPause(decision: AutoExperimentAutomation.Decision?) {
        if (decision?.action() == AutoExperimentAutomation.Action.PAUSED
            && !decision.explanation().isBlank()
        ) showMessage(decision.explanation())
    }

    private fun applyDecision(
        screen: AbstractContainerScreen<*>?,
        expectedMenu: AbstractContainerMenu?,
        decision: AutoExperimentAutomation.Decision?,
    ) {
        showPause(decision)
        if (decision?.action() != AutoExperimentAutomation.Action.CLOSE_MENU) return
        val minecraft = Minecraft.getInstance()
        if (screen == null || expectedMenu == null || !minecraft.isSameThread()
            || minecraft.gui.screen() !== screen || screen.menu !== expectedMenu
            || !ExperimentController.INSTANCE.isAutoEligibleScreen(screen)
        ) return
        screen.onClose()
    }

    private fun shouldWriteDiagnostic(fingerprint: String): Boolean {
        val changed = fingerprint != reportedDecision
        val heartbeatDue = lastDiagnosticTick == Long.MIN_VALUE
            || diagnosticTick - lastDiagnosticTick >= DIAGNOSTIC_STATUS_INTERVAL_TICKS
        if (!changed && !heartbeatDue) return false
        reportedDecision = fingerprint
        lastDiagnosticTick = diagnosticTick
        return true
    }

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(
            Category.ENCHANTING,
            name(),
            diagnosticTick.coerceAtLeast(0L),
            line.take(MAX_DIAGNOSTIC_LINE_LENGTH),
        )
    }
}
