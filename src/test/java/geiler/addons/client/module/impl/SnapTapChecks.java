package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;

/** Behavior checks for Snap Tap's last-input-priority resolution and module identity. */
public final class SnapTapChecks {
	private static final int P = SnapTapAxis.POSITIVE;
	private static final int N = SnapTapAxis.NEGATIVE;

	private SnapTapChecks() { }

	public static void main(String[] args) {
		SnapTapModule module = SnapTapModule.INSTANCE;
		check("Snap Tap".equals(module.name()) && module.category() == Category.MISCELLANEOUS
			&& !module.isEnabled() && module.description().contains("server rules"),
			"Snap Tap is a default-off Miscellaneous module whose description warns about server rules");

		SnapTapAxis axis = new SnapTapAxis();
		check(axis.resolve(false, false) == 0, "no keys held resolves to no movement");
		check(axis.resolve(false, true) == N && !axis.getOverriding(), "a single held key passes through");
		check(axis.resolve(true, true) == P && axis.getOverriding() && axis.getWinner() == P,
			"pressing the opposite key while one is held makes the newer key win");
		check(axis.resolve(true, true) == P, "the winner persists while both stay held");
		check(axis.resolve(true, false) == P && !axis.getOverriding(),
			"releasing the older key leaves the newer key active");

		axis.reset();
		axis.resolve(true, false);
		axis.resolve(true, true); // negative pressed later -> negative wins
		check(axis.getWinner() == N && axis.resolve(true, true) == N, "later negative press wins");
		check(axis.resolve(false, true) == N, "releasing the loser keeps the winner");
		check(axis.resolve(true, true) == P, "re-pressing the released key takes priority again");
		check(axis.resolve(false, true) == N, "releasing the newest key resumes the still-held key");
		check(axis.resolve(false, false) == 0, "releasing everything clears movement");

		axis.reset();
		check(axis.resolve(true, true) == (P | N) && !axis.getOverriding(),
			"both keys already held with no history keep vanilla cancel behavior");
		axis.resolve(false, false);
		check(axis.resolve(true, true) == (P | N),
			"pressing both in the same tick keeps vanilla cancel behavior until one is pressed later");
		check(axis.resolve(true, false) == P && axis.resolve(true, true) == N,
			"after a same-tick press, releasing one key and pressing it again gives that key priority");

		// Module disabled passes raw input through with the documented bit layout.
		int raw = module.resolveMovement(true, true, false, true);
		check(raw == (SnapTapModule.FORWARD | SnapTapModule.BACKWARD | SnapTapModule.RIGHT),
			"a disabled Snap Tap returns raw movement keys unchanged");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
