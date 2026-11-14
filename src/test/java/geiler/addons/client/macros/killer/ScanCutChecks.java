package geiler.addons.client.macros.killer;

/** The cut of the mobs found keeps the near ones and some of the far ones. */
public final class ScanCutChecks {
	private ScanCutChecks() { }

	public static void run() {
		check(ScanCut.pick(10, 16, 8).length == 10, "few mobs: all of them");
		int[] cut = ScanCut.pick(40, 16, 8);
		check(cut.length == 24, "a crowd is cut to near plus far");
		check(cut[0] == 0 && cut[15] == 15, "the sixteen nearest are kept");
		check(cut[23] == 39, "the farthest mob is kept");
		for (int i = 1; i < cut.length; i++) check(cut[i] > cut[i - 1], "indices are ascending and not repeated");
		check(ScanCut.pick(25, 16, 8).length == 24 && ScanCut.pick(25, 16, 8)[23] == 24, "just over the cap drops one and keeps the farthest");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
