package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.GyroWandModule;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gyro Wand: watches every particle the level is asked to add, at the point where its velocity is final. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelGyroWandMixin {
	@Inject(method = "doAddParticle", at = @At("HEAD"))
	private void geileraddons$observeParticle(ParticleOptions particle, boolean overrideLimiter, boolean alwaysShow,
		double x, double y, double z, double xd, double yd, double zd, CallbackInfo ci) {
		GyroWandModule.INSTANCE.onParticle(particle, y, xd, yd, zd);
	}
}
