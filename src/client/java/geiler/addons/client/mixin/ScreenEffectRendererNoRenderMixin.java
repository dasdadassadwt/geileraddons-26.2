package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render: the first-person fire overlay. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererNoRenderMixin {
	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void geileraddons$noFireOverlay(PoseStack poseStack, SubmitNodeCollector collector,
		TextureAtlasSprite sprite, CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.active(Flag.FIRE_OVERLAY)) ci.cancel();
	}
}
