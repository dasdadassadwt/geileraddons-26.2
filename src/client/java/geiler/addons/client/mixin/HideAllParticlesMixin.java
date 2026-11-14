package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.HideAllParticlesModule;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drops new particles: all of them with Hide All, or explosion types with the Explosions switch. */
@Mixin(ParticleEngine.class)
public abstract class HideAllParticlesMixin {
	@Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideNewParticles(Particle particle, CallbackInfo ci) {
		if (HideAllParticlesModule.INSTANCE.shouldCancelNewParticle()) ci.cancel();
	}

	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideExplosionParticles(ParticleOptions options, double x, double y, double z,
		double xd, double yd, double zd, CallbackInfoReturnable<Particle> cir) {
		if (HideAllParticlesModule.INSTANCE.shouldCancelCreatedParticle(options)) cir.setReturnValue(null);
	}
}
