package geiler.addons.client.macros.navigation;

/** The wait for a teleport click is reported and ends. */
public final class HopWaitChecks {
	private HopWaitChecks() { }

	public static void run() {
		check(HopWait.action(1) == HopWait.Action.NONE, "the first tick of a wait says nothing");
		check(HopWait.action(10) == HopWait.Action.REPORT, "ten ticks in, the reason is logged");
		check(HopWait.action(20) == HopWait.Action.REPORT && HopWait.action(30) == HopWait.Action.REPORT, "and every ten after");
		check(HopWait.action(39) == HopWait.Action.NONE, "no report between");
		check(HopWait.action(40) == HopWait.Action.GIVE_UP, "forty ticks without a click is given up");
		check(HopWait.action(400) == HopWait.Action.GIVE_UP, "and stays given up");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
