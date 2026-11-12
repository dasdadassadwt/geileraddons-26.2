package geiler.addons.client.enchanting;

/** The Superpairs click budget comes from chest slot 4, named "Remaining Clicks: N" with stack count N. */
public final class SuperpairsClicksChecks {
	private SuperpairsClicksChecks() { }

	public static void run() {
		check(SuperpairsClicks.SLOT == 4, "the budget item lives in slot 4");
		check(SuperpairsClicks.parse("Remaining Clicks: 26", 26) == 26, "plain name amount");
		check(SuperpairsClicks.parse("Remaining Clicks: 26", 64) == 26, "the name wins over a stack count");
		check(SuperpairsClicks.parse("Remaining Clicks", 12) == 12, "the stack count is the fallback");
		check(SuperpairsClicks.parse("Remaining Clicks: 0", 1) == 0, "zero clicks left is reported");
		check(SuperpairsClicks.parse("Experiment Info", 5) == -1, "other items are not the budget");
		check(SuperpairsClicks.parse(null, 5) == -1, "a missing name is not the budget");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
