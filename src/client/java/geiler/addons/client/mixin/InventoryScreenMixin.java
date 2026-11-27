package geiler.addons.client.mixin;

import geiler.addons.client.gui.InventoryButtonOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the button strip above the complete vanilla player-inventory surface. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At("TAIL"))
	private void geileraddons$renderInventoryButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
		float delta, CallbackInfo ci) {
		InventoryButtonOverlay.render((InventoryScreen) (Object) this, graphics, mouseX, mouseY);
		geiler.addons.client.rename.RenamePaintbrush.render((InventoryScreen) (Object) this, graphics, mouseX, mouseY);
		geiler.addons.client.module.impl.GardenPlotsOverlayModule.INSTANCE.renderOnInventory((InventoryScreen) (Object) this, graphics, mouseX, mouseY);
		geiler.addons.client.module.impl.EquipmentInInventoryModule.INSTANCE.renderOnInventory((InventoryScreen) (Object) this, graphics, mouseX, mouseY);
	}

	@Inject(method = "getRecipeBookButtonPosition", at = @At("RETURN"), cancellable = true)
	private void geileraddons$recipeButtonClearOfOffhand(CallbackInfoReturnable<ScreenPosition> cir) {
		int shift = geiler.addons.client.module.impl.EquipmentInInventoryModule.INSTANCE.recipeButtonShift();
		if (shift != 0) cir.setReturnValue(new ScreenPosition(cir.getReturnValue().x() + shift, cir.getReturnValue().y()));
	}

	@Inject(method = "mouseReleased", at = @At("TAIL"))
	private void geileraddons$releaseInventoryButtonDrag(MouseButtonEvent event,
		CallbackInfoReturnable<Boolean> cir) {
		InventoryButtonOverlay.mouseReleased((InventoryScreen) (Object) this);
	}
}
