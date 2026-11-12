package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import geiler.addons.client.render.CameraSpiritLeapAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the remote feed in first person without changing the local camera option. */
@Mixin(Camera.class)
public abstract class CameraSpiritLeapMixin implements CameraSpiritLeapAccess {
	@Shadow private float eyeHeight;
	@Shadow private float eyeHeightOld;

	@Unique
	@Override
	public void geiler$primeEyeHeightHistory(float eyeHeight) {
		this.eyeHeightOld = eyeHeight;
		this.eyeHeight = eyeHeight;
	}

	@Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/Options;getCameraType()Lnet/minecraft/client/CameraType;"))
	private CameraType geiler$forceLeapFeedFirstPerson(Options options) {
		Camera camera = (Camera) (Object) this;
		boolean povCamera = SpiritLeapPovRenderer.INSTANCE.isPovCamera(camera);
		return SpiritLeapPovRenderer.cameraTypeFor(povCamera, options.getCameraType());
	}

	@Redirect(method = "alignWithEntity", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
	private float geiler$rotateFixedCameraProbe(Entity entity, float partialTick) {
		float entityYaw = entity.getViewYRot(partialTick);
		Camera camera = (Camera) (Object) this;
		if (!SpiritLeapPovRenderer.INSTANCE.isPovCamera(camera)
			|| camera.entity() != Minecraft.getInstance().player) return entityYaw;
		boolean fixedProbe;
		try {
			fixedProbe = "fixed-camera".equals(System.getProperty("geileraddons.spiritLeapPovProbe"));
		} catch (SecurityException ignored) {
			fixedProbe = false;
		}
		return fixedProbe ? Mth.wrapDegrees(entityYaw + 90.0f) : entityYaw;
	}
}
