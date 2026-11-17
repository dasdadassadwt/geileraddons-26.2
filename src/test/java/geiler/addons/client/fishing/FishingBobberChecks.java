package geiler.addons.client.fishing;

/** Offline checks for the Fishing Bobber Timer rules: when the timer starts, resets, and how it reads. */
public final class FishingBobberChecks {
	private static final long SECOND = 1_000_000_000L;
	private static final long INACTIVE = FishingBobberRules.INACTIVE;

	private FishingBobberChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkStartOnCast();
		checkStartOnLiquidTouch();
		checkReset();
		checkFormat();
		System.out.println("Fishing bobber checks passed");
	}

	private static void checkStartOnCast() {
		long t = FishingBobberRules.step(INACTIVE, false, true, false, false, 5 * SECOND);
		require(t == 5 * SECOND, "a new bobber starts the timer at the cast when liquid touch is off");
		require(FishingBobberRules.step(t, true, true, true, false, 9 * SECOND) == t,
			"touching liquid does not restart a cast-based timer");
	}

	private static void checkStartOnLiquidTouch() {
		long t = FishingBobberRules.step(INACTIVE, false, true, false, true, 5 * SECOND);
		require(t == INACTIVE, "liquid-touch mode waits while the bobber is in the air");
		t = FishingBobberRules.step(t, true, true, false, true, 6 * SECOND);
		require(t == INACTIVE, "liquid-touch mode keeps waiting until the bobber touches liquid");
		t = FishingBobberRules.step(t, true, true, true, true, 7 * SECOND);
		require(t == 7 * SECOND, "the timer starts the moment the bobber touches liquid");
		require(FishingBobberRules.step(t, true, true, true, true, 9 * SECOND) == t,
			"staying in liquid does not restart the timer");
		require(FishingBobberRules.step(INACTIVE, false, true, true, true, 3 * SECOND) == 3 * SECOND,
			"a bobber first seen already in liquid starts at once");
	}

	private static void checkReset() {
		require(FishingBobberRules.step(5 * SECOND, true, false, false, false, 8 * SECOND) == INACTIVE,
			"the timer resets when the bobber disappears");
		require(FishingBobberRules.step(INACTIVE, false, false, false, false, 8 * SECOND) == INACTIVE,
			"no bobber keeps the timer inactive");
	}

	private static void checkFormat() {
		require("0.0s".equals(FishingBobberRules.format(-5 * SECOND)), "negative elapsed time reads as zero");
		require("0.4s".equals(FishingBobberRules.format(400_000_000L)), "sub-second time shows tenths");
		require("5.3s".equals(FishingBobberRules.format(5_300_000_000L)), "seconds show one decimal");
		require("1m 15.0s".equals(FishingBobberRules.format(75 * SECOND)), "a minute or more adds the minutes");
		require("5.9s".equals(FishingBobberRules.format(5_999_000_000L)), "tenths truncate rather than round up");
		require("§aBobber: §f5.3s".equals(FishingBobberRules.text(5_300_000_000L)),
			"HUD text is a green label and a white value");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
