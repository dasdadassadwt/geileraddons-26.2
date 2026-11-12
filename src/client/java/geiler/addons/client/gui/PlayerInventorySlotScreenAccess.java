package geiler.addons.client.gui;

import net.minecraft.world.inventory.Slot;

/** Public bridge implemented by the container-screen mixin for the keyboard gesture handler. */
public interface PlayerInventorySlotScreenAccess {
	Slot geileraddons$hoveredSlot();
}
