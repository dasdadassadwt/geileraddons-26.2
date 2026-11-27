package geiler.addons.client.dungeon.f7;

/** Offline checks for Rare Drop Rat: which drops count and the grow, hold and fade curve. */
public final class RareDropRatChecks {
	private RareDropRatChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		require("Necron's Handle".equals(RareDropRatRules.drop("Shiny Necron's Handle")), "a shiny handle counts");
		require("Implosion".equals(RareDropRatRules.drop("Implosion")), "the Implosion scroll counts");
		require("Fifth Master Star".equals(RareDropRatRules.drop("Fifth Master Star")), "the fifth star counts");
		require(RareDropRatRules.drop("Fourth Master Star") == null, "the fourth star does not");
		require(RareDropRatRules.drop("Ability: Wither Shield") == null, "an ability line is not a drop");
		require(RareDropRatRules.drop("Wither Catalyst") == null, "other loot does not count");
		require("Shadow Warp".equals(RareDropRatRules.chatDrop("RARE DROP! Shadow Warp (+300% ✯ Magic Find)")), "a RARE DROP line counts");
		require(RareDropRatRules.chatDrop("Party > Bob: Necron's Handle") == null, "someone naming it in chat does not");

		close(RareDropRatRules.scale(0, 1000, 500, 300), 0, "starts at zero size");
		require(RareDropRatRules.scale(500, 1000, 500, 300) > 0.5, "eases out: past half size at half the grow time");
		close(RareDropRatRules.scale(1200, 1000, 500, 300), 1, "full size while held");
		close(RareDropRatRules.scale(1650, 1000, 500, 300), 0.5, "halfway through the fade is half size");
		close(RareDropRatRules.alpha(1200, 1000, 500, 300), 1, "fully opaque until the fade");
		require(RareDropRatRules.done(1800, 1000, 500, 300) && !RareDropRatRules.done(1799, 1000, 500, 300), "done after the fade");
		double[] still = RareDropRatRules.shake(123, 6, 0);
		close(still[0], 0, "no size, no shake");
		double[] big = RareDropRatRules.shake(123, 6, 1);
		require(Math.abs(big[0]) <= 6 && Math.abs(big[1]) <= 6, "the shake stays within its amplitude");
	}

	private static void close(double actual, double expected, String message) {
		if (Math.abs(actual - expected) > 1e-9) throw new AssertionError(message + ": " + actual);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
