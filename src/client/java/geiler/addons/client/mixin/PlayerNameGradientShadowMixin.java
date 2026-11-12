package geiler.addons.client.mixin;

import geiler.addons.client.presentation.PlayerNameGradientRenderState;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps Minecraft's original glyph shadow while the style color carries a gradient marker. */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public abstract class PlayerNameGradientShadowMixin {
	@Shadow
	@Final
	private int color;

	@Shadow
	@Final
	private boolean drawShadow;

	@Inject(method = "getShadowColor(Lnet/minecraft/network/chat/Style;I)I", at = @At("HEAD"), cancellable = true)
	private void geileraddons$restoreOriginalShadow(Style style, int textColor,
		CallbackInfoReturnable<Integer> cir) {
		Integer shadow = PlayerNameGradientRenderState.shadowColorFor(style, color, drawShadow);
		if (shadow != null) cir.setReturnValue(shadow);
	}
}
