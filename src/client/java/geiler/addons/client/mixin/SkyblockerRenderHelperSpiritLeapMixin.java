package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Omits Skyblocker primitive collection from private POV frames only. */
@Mixin(targets = "de.hysky.skyblocker.utils.render.RenderHelper", remap = false)
public abstract class SkyblockerRenderHelperSpiritLeapMixin {
	@Inject(method = "startExtraction", at = @At("HEAD"), cancellable = true, remap = false)
	private static void geiler$skipPrivatePovExtraction(LevelExtractionContext context, CallbackInfo ci) {
		if (context != null && SpiritLeapPovRenderer.INSTANCE.isSecondary(context.levelRenderer())) ci.cancel();
	}

	@Inject(method = "dispatchSubmits", at = @At("HEAD"), cancellable = true, remap = false)
	private static void geiler$skipPrivatePovDispatch(LevelRenderContext context, CallbackInfo ci) {
		if (context != null && SpiritLeapPovRenderer.INSTANCE.isSecondary(context.levelRenderer())) ci.cancel();
	}
}
