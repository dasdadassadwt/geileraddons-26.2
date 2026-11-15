package geiler.addons.client.macros.navigation;

/** The distance a player still covers after a tick's push, and when the ground under it is not enough. */
public final class EdgeGuardChecks {
	private EdgeGuardChecks() { }

	public static void run() {
		checkTravel();
		checkLookahead();
		checkBrake();
	}

	private static void checkTravel() {
		check(EdgeGuard.travel(0.0, 0.0) == 0.0, "a player at rest with no keys goes nowhere");
		double plain = EdgeGuard.travel(0.118, 0.1);
		check(plain > 0.4 && plain < 0.6, "at the plain walking speed the slide is about half a block (" + plain + ")");
		double fast = EdgeGuard.travel(0.61, 0.51);
		check(fast > 2.3 && fast < 2.6, "at four times the speed it is two and a half blocks (" + fast + ")");
		check(fast > 4 * plain, "and grows faster than the speed");
	}

	private static void checkLookahead() {
		check(EdgeGuard.lookahead(0.0, 0.0) == EdgeGuard.MIN_LOOKAHEAD, "a standing player looks a block and a half ahead, as before");
		check(EdgeGuard.lookahead(0.61, 0.51) > EdgeGuard.MIN_LOOKAHEAD, "a fast one looks further");
		check(EdgeGuard.lookahead(0.61, 0.51) > EdgeGuard.travel(0.61, 0.51), "and past the end of the slide");
	}

	private static void checkBrake() {
		check(EdgeGuard.brake(1.0, 2.5, false), "ground for one block and a slide of two and a half: brake");
		check(!EdgeGuard.brake(2.5, 2.5, false), "ground for the whole slide: no brake");
		check(!EdgeGuard.brake(1.0, 2.5, true), "a planned jump or drop is walked off on purpose");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
