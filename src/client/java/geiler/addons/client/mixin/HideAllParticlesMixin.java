package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.HideAllParticlesModule;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops newly submitted particles while the single hide-all toggle is enabled. */
@Mixin(ParticleEngine.class)
public abstract class HideAllParticlesMixin {
	@Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideNewParticles(Particle particle, CallbackInfo ci) {
		if (HideAllParticlesModule.shouldCancelNewParticle(HideAllParticlesModule.INSTANCE.isEnabled())) ci.cancel();
	}
}
