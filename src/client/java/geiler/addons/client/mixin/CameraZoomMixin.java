package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ZoomModule;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Divides the camera's field of view by the Zoom module's current zoom. */
@Mixin(Camera.class)
abstract class CameraZoomMixin {
	@Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true)
	private void geileraddons$zoomFov(float partialTick, CallbackInfoReturnable<Float> cir) {
		double scale = ZoomModule.INSTANCE.fovScale();
		if (scale != 1.0) cir.setReturnValue((float) (cir.getReturnValue() * scale));
	}
}
