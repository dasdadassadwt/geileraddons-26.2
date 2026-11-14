package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.impl.BlockOverlayModule;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla block outline while Block Overlay draws its own. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererBlockOverlayMixin {
	@Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideVanillaOutline(PoseStack poseStack, SubmitNodeCollector collector,
		LevelRenderState state, CallbackInfo ci) {
		if (BlockOverlayModule.INSTANCE.cancelsVanillaOutline()) ci.cancel();
	}
}
