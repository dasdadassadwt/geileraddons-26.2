package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.FreecamModule;
import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the pose of the main camera with the Freecam pose. The camera entity is left as the player, so vanilla block
 * and entity picking still use the player. The pose is applied right after the vanilla alignment and before the view
 * matrix and culling frustum are built from it.
 */
@Mixin(Camera.class)
public abstract class CameraFreecamMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "update", at = @At("HEAD"))
	private void geileraddons$freecamFrame(DeltaTracker deltaTracker, CallbackInfo ci) {
		FreecamModule.INSTANCE.beginFrame(deltaTracker.getRealtimeDeltaTicks());
	}

	@Inject(method = "alignWithEntity", at = @At("RETURN"))
	private void geileraddons$freecamPose(float partialTick, CallbackInfo ci) {
		Camera self = (Camera) (Object) this;
		if (!FreecamModule.INSTANCE.isActive() || SpiritLeapPovRenderer.INSTANCE.isPovCamera(self)) return;
		FreecamModule.INSTANCE.applyToCamera(self, (x, y, z, yaw, pitch) -> {
			this.setPosition(x, y, z);
			this.setRotation(yaw, pitch);
			this.detached = true;
		});
	}
}
