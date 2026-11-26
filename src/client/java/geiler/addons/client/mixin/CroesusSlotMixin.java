package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.CroesusModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Croesus: hides claimed runs and tints slots (state, best chests, Kismet) behind their items. */
@Mixin(AbstractContainerScreen.class)
public abstract class CroesusSlotMixin {
	@Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
	private void geileraddons$croesusSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY,
		CallbackInfo ci) {
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		if (CroesusModule.INSTANCE.onExtractSlot(graphics, screen, slot)) ci.cancel();
	}

	/** The profit list goes above the chest screen, which would cover or dim the normal HUD layer. */
	@Inject(method = "extractTooltip", at = @At("HEAD"))
	private void geileraddons$croesusList(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		CroesusModule.INSTANCE.drawOverScreen(graphics, (AbstractContainerScreen<?>) (Object) this);
	}
}
