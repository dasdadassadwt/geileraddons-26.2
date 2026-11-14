package geiler.addons.client.macros.killer;

/**
 * The crouch rule of the Area Killer: one draw per mob, held until that mob is gone, never carried over to the next mob,
 * to a target that is gone, or to the next time the macro starts. The macro presses the key from this rule in every state,
 * so a crouch that was drawn for the last mob cannot go on through the walk to the next one.
 */
public final class SneakRuleChecks {
	private SneakRuleChecks() { }

	public static void run() {
		checkDrawPerMob();
		checkNoCarryOver();
		checkChanceBounds();
	}

	private static void checkDrawPerMob() {
		SneakRule rule = new SneakRule();
		Object first = new Object();
		check(!rule.now(first), "nothing is crouched before a draw");
		check(rule.roll(first, 100.0, 0.5), "the first call for a mob draws");
		check(rule.now(first), "a certain draw crouches for that mob");
		check(!rule.roll(first, 0.0, 0.5), "the same mob is not drawn again");
		check(rule.now(first), "and the crouch holds until the mob is gone");
	}

	private static void checkNoCarryOver() {
		SneakRule rule = new SneakRule();
		Object first = new Object();
		Object second = new Object();
		rule.roll(first, 100.0, 0.1);
		check(rule.now(first), "crouched for the first mob");
		check(!rule.now(second), "not for another mob");
		check(!rule.now(null), "not with no target at all");
		rule.clear();
		check(!rule.now(first), "a cleared rule crouches for nothing");
		rule.roll(second, 0.0, 0.0);
		check(!rule.now(second), "a chance of zero never crouches, whatever the draw");
	}

	private static void checkChanceBounds() {
		int crouched = 0;
		java.util.Random random = new java.util.Random(9);
		for (int i = 0; i < 4000; i++) {
			SneakRule rule = new SneakRule();
			Object mob = new Object();
			rule.roll(mob, 25.0, random.nextDouble());
			if (rule.now(mob)) crouched++;
		}
		check(crouched > 800 && crouched < 1200, "a chance of 25 percent crouches for about a quarter of the mobs (" + crouched + " of 4000)");
		SneakRule certain = new SneakRule();
		Object mob = new Object();
		certain.roll(mob, 100.0, 0.999999);
		check(certain.now(mob), "a chance of 100 percent crouches for every mob");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
