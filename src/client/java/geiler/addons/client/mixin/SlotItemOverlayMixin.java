package geiler.addons.client.mixin;

import geiler.addons.client.gui.SlotItemOverlays;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws item backgrounds behind, and cooldown fills over, each container slot's item. */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotItemOverlayMixin {
	@Inject(method = "extractSlots", at = @At("HEAD"))
	private void geileraddons$slotBackgrounds(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		SlotItemOverlays.beforeSlots(graphics, ((AbstractContainerScreen<?>) (Object) this).getMenu().slots);
	}

	@Inject(method = "extractSlot", at = @At("TAIL"))
	private void geileraddons$slotFill(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
		CallbackInfo ci) {
		SlotItemOverlays.afterItem(graphics, slot.getItem(), slot.x, slot.y);
	}
}
