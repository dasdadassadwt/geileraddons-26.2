package geiler.addons.client.access;

import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/** Runtime bridge implemented by the container-screen mixins. */
public interface AbstractContainerScreenAccess {
	void geileraddons$invokeSlotClicked(Slot slot, int slotId, int button, ContainerInput input);
	int geileraddons$getLeftPos();
	int geileraddons$getTopPos();
	int geileraddons$getImageWidth();
	int geileraddons$getImageHeight();
}
