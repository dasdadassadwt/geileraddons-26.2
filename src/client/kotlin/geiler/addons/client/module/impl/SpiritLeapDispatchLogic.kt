package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.helpers.SpiritLeapSelection
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Items
import java.util.Optional

/** Shared Spirit Leap dispatch rules used by the adapter and its Java regression checks. */
object SpiritLeapDispatchLogic {
    @JvmStatic
    fun isLoadedMemberDead(alive: Boolean, health: Float): Boolean = !alive || health <= 0.0f

    @JvmStatic
    fun dispatchTarget(
        menu: AbstractContainerMenu?,
        target: Optional<SpiritLeapSelection.Target>?,
        dispatcher: ExperimentSolverModule.SlotClickDispatcher?
    ): Boolean {
        if (menu == null || target == null || target.isEmpty || dispatcher == null) return false

        val slotId = target.orElseThrow().containerSlot
        val slot: Slot = menu.slots.firstOrNull { candidate -> candidate.index == slotId } ?: return false
        if (!slot.getItem().`is`(Items.PLAYER_HEAD)) return false

        dispatcher.dispatch(slot, slotId, 0, ContainerInput.PICKUP)
        return true
    }
}
