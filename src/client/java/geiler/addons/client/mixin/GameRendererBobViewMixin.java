package geiler.addons.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import geiler.addons.client.render.ViewBob;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands the view bob the world pass is about to apply to the label projection, so HUD labels bob with their boxes. */
@Mixin(GameRenderer.class)
public abstract class GameRendererBobViewMixin {
	@Inject(method = "bobView", at = @At("HEAD"))
	private void geileraddons$captureViewBob(CameraRenderState state, PoseStack poseStack, CallbackInfo ci) {
		CameraEntityRenderState entity = state.entityRenderState;
		if (entity != null && entity.isPlayer) ViewBob.capture(entity.backwardsInterpolatedWalkDistance, entity.bob);
	}
}
