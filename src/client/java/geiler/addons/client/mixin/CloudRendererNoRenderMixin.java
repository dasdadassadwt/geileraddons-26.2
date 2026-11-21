package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render: skips drawing the sky clouds. The cloud pass stays in the frame graph, it just draws nothing. */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererNoRenderMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void geileraddons$noClouds(int color, CloudStatus status, float height, int distance, Vec3 cameraPos,
		long gameTime, float partialTick, CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.active(Flag.CLOUDS)) ci.cancel();
	}
}
