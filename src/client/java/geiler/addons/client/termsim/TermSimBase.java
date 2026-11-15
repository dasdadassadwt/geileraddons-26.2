package geiler.addons.client.termsim;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

/**
 * Java base of the simulator screen: vanilla calls {@code slotClicked} with a null slot for clicks outside the
 * window, which a Kotlin override of the non-null Java signature could not accept. It never calls {@code super},
 * the only place vanilla builds a container click packet.
 */
public abstract class TermSimBase extends ContainerScreen {
	protected TermSimBase(ChestMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	protected final void slotClicked(@Nullable Slot slot, int slotId, int button, ContainerInput input) {
		simSlotClicked(slot, slotId, button);
	}

	protected abstract void simSlotClicked(@Nullable Slot slot, int slotId, int button);
}
