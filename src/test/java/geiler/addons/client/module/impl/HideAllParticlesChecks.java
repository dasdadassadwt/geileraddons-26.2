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
		check(HideAllParticlesModule.shouldCancelNewParticle(true)
			&& !HideAllParticlesModule.shouldCancelNewParticle(false),
			"the ParticleEngine add hook cancels new particles only while Hide Particles is enabled");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
