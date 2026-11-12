package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.enchanting.ChronomatronEvent
import geiler.addons.client.enchanting.ExperimentBoardGeometry
import geiler.addons.client.enchanting.ExperimentClickGate
import geiler.addons.client.enchanting.ExperimentSnapshot
import geiler.addons.client.enchanting.ExperimentSolverEngine
import geiler.addons.client.enchanting.ExperimentTier
import geiler.addons.client.enchanting.ExperimentType
import geiler.addons.client.enchanting.SolverView
import geiler.addons.client.module.Category
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import java.util.ArrayList
import java.util.HashMap

/** The single owner of experiment observation, menu listeners, and solver state. */
class ExperimentControllerCore {
    private val engine = ExperimentSolverEngine()
    private val clickGate = ExperimentClickGate()
    private var session: ExperimentSolverModule.Session? = null
    private var view = SolverView.idle()
    private var sessionGeneration = 0L
    private var menuUpdateRevision = 0L
    private var fullMenuUpdateRevision = 0L
    private val slotUpdateRevisions = HashMap<Int, Long>()

    /** Last dispatch rejection already reported, so a held mouse button cannot flood the log. */
    private var reportedRejection = ""

    fun engine(): ExperimentSolverEngine {
        engine.configure(ExperimentSolverModule.INSTANCE.configuration())
        return engine
    }

    fun view(): SolverView = view

    fun session(): ExperimentSolverModule.Session? = session

    fun sessionGeneration(): Long = sessionGeneration

    fun isDispatchingClick(): Boolean = clickGate.dispatching()

    /** Called once on the client tick; exactly one observer feeds the shared engine. */
    fun tick() {
        val minecraft = Minecraft.getInstance()
        val currentScreen = minecraft.gui.screen()
        if (currentScreen is AbstractContainerScreen<*> && isEligibleScreen(currentScreen)) {
            observe(currentScreen)
            val activeSession = session
            if (activeSession != null && activeSession.type == ExperimentType.ULTRASEQUENCER) {
                view = engine.observe(activeSession.snapshot(currentScreen, false))
            }
        } else if (session != null || view.type() != null) {
            reset()
        }
    }

    fun onScreenOpened(screen: AbstractContainerScreen<*>?) {
        if (isEligibleScreen(screen)) observe(screen!!) else reset()
    }

    fun observeScreen(screen: AbstractContainerScreen<*>?) {
        if (isEligibleScreen(screen)) observe(screen!!)
    }

    fun onOwnerDisabled() {
        val activeSession = session
        if (activeSession != null && !hasActiveOwner(activeSession.type)) reset()
    }

    fun onScreenClosed() {
        reset()
    }

    /** Called after client-side server-menu mutations, before the menu rebroadcasts its listeners. */
    fun onContainerUpdated(menu: AbstractContainerMenu?, updatedSlotId: Int) {
        val activeSession = session
        if (activeSession == null || activeSession.attachedMenu !== menu || !hasActiveOwner(activeSession.type)) return
        if (menuUpdateRevision < Long.MAX_VALUE) menuUpdateRevision++
        if (updatedSlotId < 0) {
            fullMenuUpdateRevision = menuUpdateRevision
        } else {
            slotUpdateRevisions[updatedSlotId] = menuUpdateRevision
        }
        if (activeSession.type == ExperimentType.ULTRASEQUENCER) {
            val colors = ArrayList<String>()
            for (slot in menu!!.slots) {
                if (slot.index < 9 || slot.index > 44) continue
                val color = ExperimentSolverModule.Session.ultrasequencerPaneColor(slot.getItem())
                if (color != null) colors.add(color)
            }
            engine.markUltrasequencerDirty(colors)
            view = engine.view()
        }
    }

    /** Revision of the latest clientbound menu mutation for this active experiment menu. */
    fun menuUpdateRevision(menu: AbstractContainerMenu?): Long {
        val activeSession = session ?: return -1L
        return if (activeSession.attachedMenu === menu && activeSession.type != null) {
            menuUpdateRevision
        } else {
            -1L
        }
    }

    /** Latest server-menu revision that includes this slot, or a full-content snapshot. */
    fun menuUpdateRevision(menu: AbstractContainerMenu?, slotId: Int): Long {
        val activeSession = session ?: return -1L
        if (activeSession.attachedMenu !== menu || slotId < 0) return -1L
        return maxOf(fullMenuUpdateRevision, slotUpdateRevisions[slotId] ?: 0L)
    }

    fun observesMenu(menu: AbstractContainerMenu?): Boolean {
        val activeSession = session
        return activeSession != null && activeSession.attachedMenu === menu && hasActiveOwner(activeSession.type)
    }

    fun isAutoEligibleScreen(screen: AbstractContainerScreen<*>?): Boolean {
        if (screen == null || !AutoExperimentsModule.INSTANCE.isEnabled()) return false
        val type = ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null)
        return type != null && AutoExperimentsModule.INSTANCE.supports(type)
    }

    fun isDispatchingCustomClick(): Boolean = isDispatchingClick()

    /** Whether a click on this slot is the step the solver is currently asking for. */
    fun isExpectedStepClick(slotId: Int): Boolean =
        view.current().map { step -> step.containsSlot(slotId) }.orElse(false)

    /** Validates the exact solver step and then dispatches through vanilla's normal PICKUP path. */
    fun dispatchSequenceClick(
        screen: AbstractContainerScreen<*>?,
        slotId: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean = dispatchSequenceClickResult(screen, -1, slotId, dispatcher).dispatched()

    fun dispatchManualSequenceClick(
        screen: AbstractContainerScreen<*>?,
        slotId: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): ExperimentClickGate.SequenceDispatchResult =
        dispatchSequenceClickResult(screen, -1, slotId, dispatcher)

    private fun dispatchSequenceClickResult(
        screen: AbstractContainerScreen<*>?,
        expectedSequenceIndex: Int,
        slotId: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): ExperimentClickGate.SequenceDispatchResult {
        if (screen == null || dispatcher == null || slotId < 0) {
            logDispatchRejection("invalid request")
            return ExperimentClickGate.SequenceDispatchResult.REJECTED
        }
        val minecraft = Minecraft.getInstance()
        if (!minecraft.isSameThread()) {
            logDispatchRejection("not on the client thread")
            return ExperimentClickGate.SequenceDispatchResult.REJECTED
        }
        if (minecraft.gui.screen() !== screen) {
            logDispatchRejection("screen replaced")
            return ExperimentClickGate.SequenceDispatchResult.REJECTED
        }
        if (!isEligibleScreen(screen)) {
            logDispatchRejection("no active experiment owner")
            return ExperimentClickGate.SequenceDispatchResult.REJECTED
        }
        observe(screen)
        val activeSession = session
        if (!isCurrentSession(screen) || activeSession == null) {
            logDispatchRejection("session replaced")
            return ExperimentClickGate.SequenceDispatchResult.REJECTED
        }
        if (!hasActiveOwner(activeSession.type)) {
            logDispatchRejection("module disabled during dispatch")
            return ExperimentClickGate.SequenceDispatchResult.REJECTED
        }
        if (activeSession.type != ExperimentType.CHRONOMATRON
            && activeSession.type != ExperimentType.ULTRASEQUENCER
        ) {
            logDispatchRejection("experiment is not a sequence game")
            return ExperimentClickGate.SequenceDispatchResult.REJECTED
        }
        val slot = boardSlot(screen.getMenu(), activeSession.type, activeSession.tier, slotId)
        if (slot == null) {
            logDispatchRejection("slot $slotId is not a live board slot")
            return ExperimentClickGate.SequenceDispatchResult.REJECTED
        }
        engine.configure(ExperimentSolverModule.INSTANCE.configuration())
        val dispatchSequenceIndex = if (expectedSequenceIndex >= 0) {
            expectedSequenceIndex
        } else {
            view.current().map { step -> step.index() }.orElse(-1)
        }
        val result = clickGate.dispatchSequenceClickResult(
            engine,
            dispatchSequenceIndex,
            slotId,
            isCurrentSession(screen) && hasActiveOwner(activeSession.type),
        ) { id -> dispatcher.dispatch(slot, id, 0, ContainerInput.PICKUP) }
        if (result.dispatched()) {
            view = engine.view()
            if (result == ExperimentClickGate.SequenceDispatchResult.UNCERTAIN) {
                logDispatchRejection("vanilla click ran, but solver progress was not confirmed safely; do not resend")
            }
        } else {
            logDispatchRejection(
                "click gate rejected index $dispatchSequenceIndex slot $slotId at ${view.phase()} index " +
                    "${view.visualIndex()}/${view.sequence().size}",
            )
        }
        return result
    }

    /** Records the first gate that refused an automated click; silent rejections are undiagnosable. */
    private fun logDispatchRejection(reason: String) {
        if (!AutoExperimentsModule.INSTANCE.debugAutomation()) {
            reportedRejection = ""
            return
        }
        if (reason == reportedRejection) return
        reportedRejection = reason
        GeilerAddonsLog.write(
            Category.ENCHANTING,
            AutoExperimentsModule.INSTANCE.name(),
            0L,
            "dispatch rejected: $reason",
        )
    }

    /** Chronomatron-only Auto entry point; Ultrasequencer uses its one-pass executor below. */
    fun dispatchAutoSequenceClick(
        screen: AbstractContainerScreen<*>?,
        sequenceIndex: Int,
        slotId: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean {
        if (!isAutoEligibleScreen(screen)
            || ExperimentType.fromTitle(screen!!.getTitle().getString()).orElse(null) != ExperimentType.CHRONOMATRON
        ) return false
        return dispatchSequenceClickResult(screen, sequenceIndex, slotId, dispatcher).dispatched()
    }

    /** Dispatches an Auto Ultrasequencer slot through vanilla without advancing solver progress. */
    fun dispatchAutoUltrasequencerClick(
        screen: AbstractContainerScreen<*>?,
        slotId: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean {
        if (screen == null || dispatcher == null || slotId < 0
            || !isAutoEligibleScreen(screen)
            || ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null) != ExperimentType.ULTRASEQUENCER
        ) {
            logDispatchRejection("Ultrasequencer request is no longer eligible")
            return false
        }
        val minecraft = Minecraft.getInstance()
        if (!minecraft.isSameThread() || minecraft.gui.screen() !== screen) {
            logDispatchRejection("Ultrasequencer screen changed before dispatch")
            return false
        }
        val activeSession = session
        if (!isCurrentSession(screen) || activeSession == null
            || activeSession.type != ExperimentType.ULTRASEQUENCER
            || !hasActiveOwner(ExperimentType.ULTRASEQUENCER)
        ) {
            logDispatchRejection("Ultrasequencer session changed before dispatch")
            return false
        }
        val slot = boardSlot(screen.getMenu(), activeSession.type, activeSession.tier, slotId)
        if (slot == null) {
            logDispatchRejection("Ultrasequencer slot $slotId is not part of the active menu board")
            return false
        }
        if (clickGate.dispatching()) {
            logDispatchRejection("another custom experiment click is already dispatching")
            return false
        }
        clickGate.dispatchVanilla(Runnable { dispatcher.dispatch(slot, slotId, 0, ContainerInput.PICKUP) })
        return true
    }

    /** Superpairs is manual-only, but its intentional custom click still passes through vanilla. */
    fun dispatchSuperpairsClick(
        screen: AbstractContainerScreen<*>?,
        slotId: Int,
        button: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean {
        if (screen == null || dispatcher == null || slotId < 0) return false
        val minecraft = Minecraft.getInstance()
        if (!minecraft.isSameThread() || minecraft.gui.screen() !== screen || !isEligibleScreen(screen)) return false
        observe(screen)
        val activeSession = session
        if (!isCurrentSession(screen) || activeSession == null
            || activeSession.type != ExperimentType.SUPERPAIRS
            || !ExperimentSolverModule.INSTANCE.isEnabled()
            || !ExperimentSolverModule.INSTANCE.supports(ExperimentType.SUPERPAIRS)
        ) return false
        val slot = boardSlot(screen.getMenu(), activeSession.type, activeSession.tier, slotId)
        if (slot == null) return false
        val decision = engine.onClick(slotId)
        if (!decision.expected() || !decision.visualStateChanged()) return false
        clickGate.dispatchVanilla(Runnable { dispatcher.dispatch(slot, slotId, button, ContainerInput.PICKUP) })
        view = engine.view()
        return true
    }

    private fun isEligibleScreen(screen: AbstractContainerScreen<*>?): Boolean {
        if (screen == null) return false
        val type = ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null)
        return type != null && hasActiveOwner(type)
    }

    /** Whether any module currently owns this experiment; also reported by the Auto diagnostics. */
    fun hasActiveOwner(type: ExperimentType?): Boolean {
        if (type == null) return false
        val solver = ExperimentSolverModule.INSTANCE
        val automatic = AutoExperimentsModule.INSTANCE
        return hasActiveOwner(
            type,
            solver.isEnabled(),
            solver.supports(type),
            automatic.isEnabled(),
            type != ExperimentType.SUPERPAIRS && automatic.supports(type),
        )
    }

    private fun isCurrentSession(screen: AbstractContainerScreen<*>?): Boolean {
        val activeSession = session ?: return false
        return screen != null && activeSession.attachedMenu === screen.getMenu()
            && screen.getMenu().containerId == activeSession.menuId
            && ExperimentType.fromTitle(screen.getTitle().getString()).orElse(null) == activeSession.type
    }

    private fun boardSlot(
        menu: AbstractContainerMenu,
        type: ExperimentType,
        tier: ExperimentTier,
        slotId: Int,
    ): Slot? {
        if (!ExperimentBoardGeometry.forExperiment(type, tier).containsSlot(slotId)) return null
        for (slot in menu.slots) {
            if (slot.index == slotId && slot.container !is Inventory) return slot
        }
        return null
    }

    private fun observe(screen: AbstractContainerScreen<*>) {
        val title = screen.getTitle().getString()
        val type = ExperimentType.fromTitle(title).orElse(null)
        val tier = ExperimentTier.fromTitle(title).orElse(ExperimentTier.UNKNOWN)
        val menuId = screen.getMenu().containerId
        var activeSession = session
        if (activeSession == null || activeSession.type != type || activeSession.tier != tier
            || activeSession.menuId != menuId || activeSession.attachedMenu !== screen.getMenu()
        ) {
            activeSession?.close()
            activeSession = ExperimentSolverModule.Session(type, tier, menuId)
            session = activeSession
            menuUpdateRevision = 0L
            fullMenuUpdateRevision = 0L
            slotUpdateRevisions.clear()
            engine.reset()
            view = SolverView.idle()
            sessionGeneration++
        }
        if (!activeSession.observe(screen)) return
        engine.configure(ExperimentSolverModule.INSTANCE.configuration())
        val superpairsChanged = activeSession.type == ExperimentType.SUPERPAIRS
            && activeSession.takeSuperpairsObservationDirty()
        for (update in activeSession.drainSlotUpdates()) {
            if (activeSession.type == ExperimentType.ULTRASEQUENCER) continue
            val event: ChronomatronEvent? = if (activeSession.type == ExperimentType.CHRONOMATRON) {
                update.chronomatronEvent()
            } else {
                null
            }
            view = engine.observe(activeSession.snapshot(screen, false, update), event)
        }
        if (activeSession.type == ExperimentType.ULTRASEQUENCER && view.type() == null) {
            // Establish the model before mutation callbacks; capture the memory only on a tick.
            view = engine.observe(ExperimentSnapshot(title, "", emptyList()))
        } else if (activeSession.type != ExperimentType.ULTRASEQUENCER) {
            if (activeSession.type != ExperimentType.SUPERPAIRS || superpairsChanged || view.type() == null) {
                view = engine.observe(activeSession.snapshot(screen, false))
            }
        }
        // Superpairs values are learned from revealed menu stacks. Rebuild only after callbacks drain.
        activeSession.refreshRenderSlots(screen)
    }

    private fun reset() {
        val hadSession = session != null || view.type() != null
        session?.close()
        session = null
        menuUpdateRevision = 0L
        fullMenuUpdateRevision = 0L
        slotUpdateRevisions.clear()
        view = engine.reset()
        if (hadSession) sessionGeneration++
    }

    companion object {
        @JvmStatic
        fun hasActiveOwner(
            type: ExperimentType?,
            solverEnabled: Boolean,
            solverSupports: Boolean,
            autoEnabled: Boolean,
            autoSupports: Boolean,
        ): Boolean = type != null && (
            solverEnabled && solverSupports
                || type != ExperimentType.SUPERPAIRS && autoEnabled && autoSupports
            )
    }
}
