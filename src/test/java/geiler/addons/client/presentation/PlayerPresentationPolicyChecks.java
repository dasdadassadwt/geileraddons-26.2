package geiler.addons.client.presentation;

/** Pure default-migration and size-source checks that do not initialize Minecraft client state. */
public final class PlayerPresentationPolicyChecks {
	private PlayerPresentationPolicyChecks() { }

	public static void run() {
		require(PlayerPresentationPolicy.splitModuleEnable(null, true) == null,
			"a legacy enabled combined module does not enable a missing split-module key");
		require(Boolean.FALSE.equals(PlayerPresentationPolicy.splitModuleEnable(false, true)),
			"an explicit saved off value wins over the legacy combined enabled state");
		require(Boolean.TRUE.equals(PlayerPresentationPolicy.splitModuleEnable(true, false)),
			"an explicit saved on value remains enabled");

		require(PlayerPresentationPolicy.sizeMultiplier("Self", "self", true, 1.7f,
			true, 1.2f) == 1.7f,
			"the local self-size override wins over a hosted value for the local player");
		require(PlayerPresentationPolicy.sizeMultiplier("Other", "Self", true, 1.7f,
			true, 1.2f) == 1.2f,
			"the local self-size value never changes another player's hosted size");
		require(PlayerPresentationPolicy.sizeMultiplier("Other", "Self", true, 1.7f,
			false, 1.2f) == 1.0f,
			"hosted other-player sizing follows its own toggle");
		require(PlayerPresentationPolicy.sizeMultiplier("Self", "Self", false, 1.7f,
			false, 1.2f) == 1.0f,
			"disabling hosted sizing and the local self override leaves vanilla size");
		require(PlayerPresentationPolicy.sizeMultiplier("Other", "Self", false, Float.NaN,
			true, 9.0f) == 1.0f,
			"invalid hosted sizes are ignored");
		require(PlayerPresentationPolicy.sizeMultiplier(null, "Self", true, 1.7f,
			true, 1.2f) == 1.0f,
			"a missing requested player keeps vanilla size");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
