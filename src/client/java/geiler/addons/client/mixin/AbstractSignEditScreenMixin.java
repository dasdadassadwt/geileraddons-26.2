package geiler.addons.client.mixin;

import geiler.addons.client.gui.FancySignOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fancy Auction House: a Hypixel sign prompt shown as the themed dialog draws nothing of its own (no sign, title or
 * vanilla Done button). The dialog is drawn after the screen; typing still goes into the real sign.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideVanillaSign(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (FancySignOverlay.hides(this)) ci.cancel();
	}
}
