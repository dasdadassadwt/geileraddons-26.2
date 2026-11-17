package geiler.addons.client.fishing;

/** Offline checks for the Fishing Hook Display rules: which armor stand names count as the hook text. */
public final class FishingHookChecks {
	private FishingHookChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		require(FishingHookRules.isHookText("2.4"), "a countdown in seconds is hook text");
		require(FishingHookRules.isHookText(" 0.8 "), "surrounding spaces are ignored");
		require(FishingHookRules.isHookText("!!!"), "the bite marker is hook text");
		require(FishingHookRules.isHookText("GO !!! NOW"), "the marker inside other text is hook text");
		require(!FishingHookRules.isHookText("12.45"), "a longer number is not the countdown");
		require(!FishingHookRules.isHookText("[Lv600] Lord Jawbus"), "a mob name is not hook text");
		require(!FishingHookRules.isHookText(""), "blank is not hook text");
		require(!FishingHookRules.isHookText(null), "null is not hook text");
		require(FishingHookRules.isBite("!!!") && !FishingHookRules.isBite("2.4"), "only the marker is a bite");
		require(!FishingHookRules.isBite(null), "null is not a bite");
		require(FishingHookRules.withinHook(1.5) && !FishingHookRules.withinHook(1.6), "the stand must sit at the hook");
		System.out.println("Fishing hook checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
