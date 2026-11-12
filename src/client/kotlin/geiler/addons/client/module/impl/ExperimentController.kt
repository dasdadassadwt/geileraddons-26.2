package geiler.addons.client.module.impl

import geiler.addons.client.enchanting.ExperimentClickGate
import geiler.addons.client.enchanting.ExperimentSolverEngine
import geiler.addons.client.enchanting.ExperimentType
import geiler.addons.client.enchanting.SolverView
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.AbstractContainerMenu

/** Stable Java and Kotlin API over the Kotlin-backed experiment menu controller. */
class ExperimentController private constructor() {
    private val core = ExperimentControllerCore()

    fun engine(): ExperimentSolverEngine = core.engine()

    fun view(): SolverView = core.view()

    fun session(): ExperimentSolverModule.Session? = core.session()

    fun sessionGeneration(): Long = core.sessionGeneration()

    fun isDispatchingClick(): Boolean = core.isDispatchingClick()

    fun tick() = core.tick()

    fun onScreenOpened(screen: AbstractContainerScreen<*>?) = core.onScreenOpened(screen)

    fun observeScreen(screen: AbstractContainerScreen<*>?) = core.observeScreen(screen)

    fun onOwnerDisabled() = core.onOwnerDisabled()

    fun onScreenClosed() = core.onScreenClosed()

    fun onContainerUpdated(menu: AbstractContainerMenu?) = core.onContainerUpdated(menu, -1)

    fun onContainerUpdated(menu: AbstractContainerMenu?, updatedSlotId: Int) =
        core.onContainerUpdated(menu, updatedSlotId)

    fun menuUpdateRevision(menu: AbstractContainerMenu?): Long = core.menuUpdateRevision(menu)

    fun menuUpdateRevision(menu: AbstractContainerMenu?, slotId: Int): Long =
        core.menuUpdateRevision(menu, slotId)

    fun observesMenu(menu: AbstractContainerMenu?): Boolean = core.observesMenu(menu)

    fun isAutoEligibleScreen(screen: AbstractContainerScreen<*>?): Boolean = core.isAutoEligibleScreen(screen)

    fun isDispatchingCustomClick(): Boolean = core.isDispatchingCustomClick()

    fun isExpectedStepClick(slotId: Int): Boolean = core.isExpectedStepClick(slotId)

    fun dispatchSequenceClick(
        screen: AbstractContainerScreen<*>?,
        slotId: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean = core.dispatchSequenceClick(screen, slotId, dispatcher)

    fun dispatchManualSequenceClick(
        screen: AbstractContainerScreen<*>?,
        slotId: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): ExperimentClickGate.SequenceDispatchResult = core.dispatchManualSequenceClick(screen, slotId, dispatcher)

    fun dispatchAutoSequenceClick(
        screen: AbstractContainerScreen<*>?,
        sequenceIndex: Int,
        slotId: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean = core.dispatchAutoSequenceClick(screen, sequenceIndex, slotId, dispatcher)

    fun dispatchAutoUltrasequencerClick(
        screen: AbstractContainerScreen<*>?,
        slotId: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean = core.dispatchAutoUltrasequencerClick(screen, slotId, dispatcher)

    fun dispatchSuperpairsClick(
        screen: AbstractContainerScreen<*>?,
        slotId: Int,
        button: Int,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?,
    ): Boolean = core.dispatchSuperpairsClick(screen, slotId, button, dispatcher)

    fun hasActiveOwner(type: ExperimentType?): Boolean = core.hasActiveOwner(type)

    companion object {
        @JvmField
        val INSTANCE = ExperimentController()

        @JvmStatic
        fun hasActiveOwner(
            type: ExperimentType?,
            solverEnabled: Boolean,
            solverSupports: Boolean,
            autoEnabled: Boolean,
            autoSupports: Boolean,
        ): Boolean = ExperimentControllerCore.hasActiveOwner(
            type, solverEnabled, solverSupports, autoEnabled, autoSupports,
        )
    }
}
