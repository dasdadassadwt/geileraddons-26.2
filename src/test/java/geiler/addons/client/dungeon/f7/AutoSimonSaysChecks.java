package geiler.addons.client.dungeon.f7;

import java.util.Random;

/** Offline checks for Auto Simon Says: modes, trigger keys, aim point and the SS skip presses. */
public final class AutoSimonSaysChecks {
	private AutoSimonSaysChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkModes();
		checkKeys();
		checkAim();
		checkSkip();
	}

	private static void checkModes() {
		require(AutoSimonSaysRules.mode("AUTO") == AutoSimonSaysRules.Mode.AUTO, "AUTO parses");
		require(AutoSimonSaysRules.mode("nonsense") == AutoSimonSaysRules.Mode.TRIGGER, "an unknown mode is the trigger");
		require(AutoSimonSaysRules.turns(AutoSimonSaysRules.Mode.AUTO), "auto turns the view");
		require(!AutoSimonSaysRules.turns(AutoSimonSaysRules.Mode.TRIGGER), "the trigger never turns the view");
		require(!AutoSimonSaysRules.clicks(AutoSimonSaysRules.Mode.AIM_ASSIST), "the aim assist never clicks");
		require(AutoSimonSaysRules.clicks(AutoSimonSaysRules.Mode.TRIGGER), "the trigger clicks");
	}

	private static void checkKeys() {
		long button = SimonSaysState.pack(110, 121, 93);
		long first = AutoSimonSaysRules.key(button, 0, 1);
		require(first != AutoSimonSaysRules.key(button, 0, 2), "the same button in the next round has another key");
		require(first != AutoSimonSaysRules.key(button, 1, 2), "the same button at another step has another key");
		require(first == AutoSimonSaysRules.key(button, 0, 1), "the key is stable");
		require(first != LeverAimRules.NO_KEY, "a key is never the no-key marker");
	}

	private static void checkAim() {
		double[] p = AutoSimonSaysRules.aimPoint(110, 121, 93);
		require(p[0] > 110.875 && p[0] < 111.0, "aim is on the button, which sticks out of the wall");
		require(p[1] == 121.5 && p[2] == 93.5, "aim is the middle of the face");
	}

	private static void checkSkip() {
		AutoSimonSaysRules.Skip skip = new AutoSimonSaysRules.Skip(new Random(1));
		require(!skip.active() && !skip.due(0), "an idle skip presses nothing");
		skip.start(1_000, 2, 100, 150);
		require(skip.active() && skip.left() == 2, "two extra presses are planned");
		require(!skip.due(1_099), "nothing before the shortest gap");
		int pressed = 0;
		for (long t = 1_000; t <= 1_400; t += 5) if (skip.due(t)) pressed++;
		require(pressed == 2, "both presses happen within two of the longest gaps, got " + pressed);
		require(!skip.active(), "the skip ends after its presses");
		skip.start(0, 2, 100, 100);
		skip.cancel();
		require(!skip.due(1_000), "a cancelled skip presses nothing");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
