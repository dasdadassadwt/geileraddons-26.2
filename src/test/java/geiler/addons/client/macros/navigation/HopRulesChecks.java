package geiler.addons.client.macros.navigation;

/** A teleport landing is ground to stand on near the route, not the far side of a ledge; and the gap between two clicks. */
public final class HopRulesChecks {
	private HopRulesChecks() { }

	public static void run() {
		check(!HopRules.landingOk(70.1, 58.0, Double.NaN, 4.0), "a landing over a twelve block drop is refused");
		check(HopRules.landingOk(70.1, 68.0, Double.NaN, 4.0), "a two block step down is fine");
		check(HopRules.landingOk(70.1, 70.0, 70.0, 4.0), "a landing on the route is fine");
		check(!HopRules.landingOk(73.1, 73.0, 70.0, 4.0), "a landing three above the route is another floor");
		check(!HopRules.landingOk(70.1, Double.NaN, 70.0, 4.0), "no ground at all is refused");

		check(TeleportGap.minMillis(true, 3, 8, 0.0) > TeleportGap.minMillis(false, 3, 8, 0.0), "etherwarp waits longer than the plain item");
		check(TeleportGap.minMillis(false, 3, 8, 0.0) == 150L, "three ticks is 150 ms");
		check(TeleportGap.minMillis(true, 3, 8, 1.0) == 500L, "eight ticks and the most jitter is 500 ms");
		check(TeleportGap.minMillis(true, 3, 0, 0.7) == 0L && TeleportGap.minMillis(false, 0, 8, 0.7) == 0L, "no ticks, no gap");
		check(TeleportGap.minMillis(false, 3, 8, 5.0) == TeleportGap.minMillis(false, 3, 8, 1.0), "the random number is held to 0..1");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
