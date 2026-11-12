package geiler.addons.client.mixin;

import geiler.addons.client.gui.PlayerInventorySlotOverlay;
import geiler.addons.client.gui.PlayerInventorySlotScreenAccess;
import geiler.addons.client.inventory.PlayerInventoryControls;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws slot links after the inventory contents and clears held gestures when a menu closes. */
@Mixin(AbstractContainerScreen.class)
public abstract class PlayerInventorySlotOverlayMixin implements PlayerInventorySlotScreenAccess {
	@Shadow protected int leftPos;
	@Shadow protected int topPos;

	@Override
	@Accessor("hoveredSlot")
	public abstract Slot geileraddons$hoveredSlot();

	@Inject(method = "extractSlots", at = @At("TAIL"))
	private void geileraddons$renderSlotLinks(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
		CallbackInfo ci) {
		PlayerInventorySlotOverlay.render((AbstractContainerScreen<?>) (Object) this, graphics);
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void geileraddons$resetHeldSlotGesture(CallbackInfo ci) {
		PlayerInventoryControls.INSTANCE.resetScreenGestures();
	}
}
