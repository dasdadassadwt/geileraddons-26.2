package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapGuiOrdering;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Extracts the replacement Leap surface before vanilla HUD elements are queued. */
@Mixin(Hud.class)
abstract class SpiritLeapHudOrderingMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
		at = @At("HEAD"))
	private void geileraddons$extractSpiritLeapBeforeHud(GuiGraphicsExtractor graphics,
		DeltaTracker deltaTracker, CallbackInfo ci) {
		SpiritLeapGuiOrdering.renderBeforeHud(graphics);
	}
}
