package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ThirdPersonCameraModule;
import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies configured collision distance and clipping at the camera's distance calculation. */
@Mixin(Camera.class)
public abstract class ThirdPersonCameraMixin {
	@ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
	private void geileraddons$applyFreeLookRotation(Args args) {
		if (!SpiritLeapPovRenderer.shouldApplyFreeLook(
			SpiritLeapPovRenderer.INSTANCE.isPovCamera((Camera) (Object) this))) return;
		args.set(0, ThirdPersonCameraModule.INSTANCE.freeLookCameraYaw((float) args.get(0)));
		args.set(1, ThirdPersonCameraModule.INSTANCE.freeLookCameraPitch((float) args.get(1)));
	}

	@ModifyVariable(method = "getMaxZoom(F)F", at = @At("HEAD"), argsOnly = true)
	private float geileraddons$setCameraDistance(float vanillaDistance) {
		return ThirdPersonCameraModule.INSTANCE.distance(vanillaDistance);
	}

	@Inject(method = "getMaxZoom(F)F", at = @At("HEAD"), cancellable = true)
	private void geileraddons$bypassCameraCollision(float vanillaDistance,
		CallbackInfoReturnable<Float> cir) {
		if (ThirdPersonCameraModule.INSTANCE.bypassCollisionClip()) {
			cir.setReturnValue(ThirdPersonCameraModule.INSTANCE.distance(vanillaDistance));
		}
	}
}
