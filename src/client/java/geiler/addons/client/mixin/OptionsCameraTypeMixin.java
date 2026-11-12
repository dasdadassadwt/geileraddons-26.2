package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ThirdPersonCameraModule;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents front-facing perspective while the camera module is enabled. */
@Mixin(Options.class)
public abstract class OptionsCameraTypeMixin {
	@ModifyVariable(method = "setCameraType(Lnet/minecraft/client/CameraType;)V", at = @At("HEAD"), argsOnly = true)
	private CameraType geileraddons$skipFrontFacingPerspective(CameraType requested) {
		return ThirdPersonCameraModule.INSTANCE.constrainCameraTypeRequest(requested);
	}

	@Inject(method = "getCameraType()Lnet/minecraft/client/CameraType;", at = @At("RETURN"), cancellable = true)
	private void geileraddons$chooseConfiguredCameraType(CallbackInfoReturnable<CameraType> cir) {
		CameraType type = ThirdPersonCameraModule.INSTANCE.cameraType(cir.getReturnValue());
		if (ThirdPersonCameraModule.INSTANCE.holdingFreeLook()) type = CameraType.THIRD_PERSON_BACK;
		cir.setReturnValue(type);
	}
}
