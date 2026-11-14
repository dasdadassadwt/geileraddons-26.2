package geiler.addons.client.macros.input;

/** A jump key pressed twice within seven ticks turns flight on in creative; the guard keeps a walk from doing that. */
public final class JumpGuardChecks {
	private JumpGuardChecks() { }

	public static void run() {
		JumpGuard flying = new JumpGuard();
		check(flying.filter(true, true), "the first press goes through");
		check(!flying.filter(false, true), "released");
		check(!flying.filter(true, true), "a second press two ticks later is held back");
		for (int i = 0; i < 4; i++) flying.filter(false, true);
		boolean allowed = false;
		for (int i = 0; i < 10 && !allowed; i++) allowed = flying.filter(true, true);
		check(allowed, "a later press goes through again");

		JumpGuard walking = new JumpGuard();
		check(walking.filter(true, false) && !walking.filter(false, false) && walking.filter(true, false), "no flight, no hold back");

		JumpGuard held = new JumpGuard();
		for (int i = 0; i < 20; i++) check(held.filter(true, true), "a key that stays down stays down");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
