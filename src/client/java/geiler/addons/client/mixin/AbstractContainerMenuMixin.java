package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ExperimentSolverModule;
import geiler.addons.client.enchanting.ExperimentMenuUpdates;
import geiler.addons.client.inventory.PlayerInventoryControls;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Client inventory setters do not themselves notify ContainerListeners. */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	@Inject(method = "moveItemStackTo", at = @At("HEAD"), cancellable = true)
	private void geileraddons$guardOrderedLockedSlotTransfer(ItemStack stack, int start, int end,
		boolean reverse, CallbackInfoReturnable<Boolean> cir) {
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		if (PlayerInventoryControls.INSTANCE.blocksMoveItemStackTo(Minecraft.getInstance(), menu,
			stack, start, end, reverse)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "setItem", at = @At("RETURN"))
	private void geileraddons$slotUpdated(int slot, int stateId, ItemStack stack, CallbackInfo ci) {
		geileraddons$notifySolver(slot);
	}
	@Inject(method = "initializeContents", at = @At("RETURN"))
	private void geileraddons$contentsUpdated(int stateId, List<ItemStack> items, ItemStack carried,
		CallbackInfo ci) {
		geileraddons$notifySolver(-1);
	}
	@org.spongepowered.asm.mixin.Unique
	private void geileraddons$notifySolver(int updatedSlotId) {
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		ExperimentSolverModule solver = ExperimentSolverModule.INSTANCE;
		if (!solver.observesMenu(menu)) return;
		ExperimentMenuUpdates.afterServerUpdate(menu, () -> solver.onContainerUpdated(menu, updatedSlotId));
	}
}
