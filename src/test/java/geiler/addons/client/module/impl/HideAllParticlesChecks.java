package geiler.addons.client.module.impl;

/** Focused behavior checks for Hide Particles' enable clear and particle submission gate. */
public final class HideAllParticlesChecks {
	private HideAllParticlesChecks() { }

	public static void main(String[] args) {
		int[] clearCalls = {0};
		check(HideAllParticlesModule.clearExistingParticlesOnEnable(() -> clearCalls[0]++)
			&& clearCalls[0] == 1,
			"enabling Hide Particles clears existing particles once when the engine is available");
		check(!HideAllParticlesModule.clearExistingParticlesOnEnable(null) && clearCalls[0] == 1,
			"enabling Hide Particles safely skips the clear when the engine is unavailable");
		check(HideAllParticlesModule.shouldCancelNewParticle(true, true)
			&& !HideAllParticlesModule.shouldCancelNewParticle(false, true)
			&& !HideAllParticlesModule.shouldCancelNewParticle(true, false),
			"the ParticleEngine add hook cancels new particles only while Hide Particles and Hide All are on");
		HideAllParticlesModule particles = HideAllParticlesModule.INSTANCE;
		check(particles.booleanSettings().size() == 3
			&& particles.booleanSettings().get(0).value()
			&& !particles.booleanSettings().get(1).value() && !particles.booleanSettings().get(2).value(),
			"Hide All defaults on, so an already enabled module keeps hiding everything; the new switches default off");
		check(!particles.shouldCancelNewParticle() && !particles.shouldCancelBlockBreaking()
			&& !particles.shouldCancelCreatedParticle(net.minecraft.core.particles.ParticleTypes.EXPLOSION),
			"a disabled Hide Particles module cancels nothing");
		check(HideAllParticlesModule.isExplosionParticle(net.minecraft.core.particles.ParticleTypes.EXPLOSION)
			&& HideAllParticlesModule.isExplosionParticle(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER)
			&& HideAllParticlesModule.isExplosionParticle(net.minecraft.core.particles.ParticleTypes.GUST)
			&& HideAllParticlesModule.isExplosionParticle(net.minecraft.core.particles.ParticleTypes.GUST_EMITTER_LARGE)
			&& !HideAllParticlesModule.isExplosionParticle(net.minecraft.core.particles.ParticleTypes.POOF),
			"the Explosions switch covers exactly the four explosion and gust particle types");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
