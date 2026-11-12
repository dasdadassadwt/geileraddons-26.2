package geiler.addons.client.enchanting

import net.minecraft.world.inventory.AbstractContainerMenu

/** Shared boundary for both single-slot and full-content server updates. */
object ExperimentMenuUpdates {
    @JvmStatic
    fun afterServerUpdate(menu: AbstractContainerMenu?, markDirty: Runnable?) {
        markDirty!!.run()
        // broadcastChanges diffs lastSlots, making a second broadcaster (e.g. Skyblocker) harmless.
        menu!!.broadcastChanges()
    }
}
