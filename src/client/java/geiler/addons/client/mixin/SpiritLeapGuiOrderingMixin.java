package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapGuiOrdering;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Expires a previous frame's early-render marker before the next GUI state is extracted. */
@Mixin(Gui.class)
abstract class SpiritLeapGuiOrderingMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V", at = @At("HEAD"))
	private void geileraddons$beginGuiExtraction(net.minecraft.client.DeltaTracker deltaTracker,
		boolean renderHud, boolean renderScreen, CallbackInfo ci) {
		SpiritLeapGuiOrdering.beginGuiExtraction();
	}
}
