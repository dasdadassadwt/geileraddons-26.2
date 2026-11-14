package geiler.addons.client.macros.killer;

/** The walk quality counters and the stand watch that make a bad walk visible in the log. */
public final class WalkStatsChecks {
	private WalkStatsChecks() { }

	public static void run() {
		checkWindow();
		checkMedian();
		checkSession();
		checkStandWatch();
	}

	private static void checkWindow() {
		WalkStats stats = new WalkStats();
		for (int i = 0; i < 99; i++) stats.sample(true, true, false, false, 10.0);
		check(stats.poll() == null, "no window before 100 ticks");
		stats.sample(true, true, false, false, 10.0);
		WalkStats.Window window = stats.poll();
		check(window != null && window.walkTicks == 100, "a window of 100 ticks closes");
		check(window.noKeyShare() == 0.0 && window.strafeShare() == 0.0, "all forward: no standing, no strafe");
		check(stats.poll() == null, "the next window starts empty");

		for (int i = 0; i < 40; i++) stats.sample(true, false, false, true, 120.0);
		for (int i = 0; i < 20; i++) stats.sample(true, true, true, false, 60.0);
		for (int i = 0; i < 30; i++) stats.sample(true, true, false, false, 5.0);
		for (int i = 0; i < 10; i++) stats.sample(false, false, false, false, Double.NaN);
		WalkStats.Window mixed = stats.poll();
		check(mixed != null && mixed.walkTicks == 90, "ticks that are not walking are left out of the walk counts");
		check(Math.abs(mixed.noKeyShare() - 40.0 / 90.0) < 1.0E-9, "the share of walking ticks with no key");
		check(Math.abs(mixed.strafeShare() - 20.0 / 90.0) < 1.0E-9, "the share with a sideways key");
		check(mixed.holdTicks == 40, "ticks held back to turn");
		check(Math.abs(mixed.over45Share() - 60.0 / 90.0) < 1.0E-9, "the share of samples more than 45 degrees off the view");
		check(mixed.describe().contains("noKeys="), "the line says what it counted");
	}

	private static void checkMedian() {
		WalkStats stats = new WalkStats();
		double[] offs = {-10, 20, -30, 40, 170};
		for (int i = 0; i < 100; i++) stats.sample(true, true, false, false, offs[i % offs.length]);
		WalkStats.Window window = stats.poll();
		check(window != null && window.medianOff == 30.0, "the median of the angles is of their size, not their sign (" + window.medianOff + ")");
		WalkStats none = new WalkStats();
		for (int i = 0; i < 100; i++) none.sample(true, false, false, false, Double.NaN);
		WalkStats.Window blank = none.poll();
		check(blank != null && blank.medianOff == 0.0, "with no angle at all the median is 0");
	}

	private static void checkSession() {
		WalkStats stats = new WalkStats();
		for (int round = 0; round < 3; round++) {
			for (int i = 0; i < 100; i++) stats.sample(true, i % 2 == 0, false, false, 50.0);
			stats.lookSwitch();
			stats.replan();
			stats.replan();
			check(stats.poll() != null, "window " + round);
		}
		WalkStats.Window total = stats.total();
		check(total.walkTicks == 300 && total.lookSwitches == 3 && total.replans == 6, "the session adds the windows up");
		check(Math.abs(total.noKeyShare() - 0.5) < 1.0E-9, "and keeps the shares");
		stats.reset();
		check(stats.total().walkTicks == 0, "reset empties it");
	}

	private static void checkStandWatch() {
		StandWatch watch = new StandWatch();
		for (int i = 0; i < 19; i++) check(!watch.tick(true), "not yet at tick " + i);
		check(watch.tick(true), "a second of standing is reported");
		for (int i = 0; i < 99; i++) check(!watch.tick(true), "then not again for five seconds");
		check(watch.tick(true), "but once the five seconds have gone, again");
		check(!watch.tick(false), "moving clears it");
		for (int i = 0; i < 19; i++) watch.tick(true);
		check(!watch.tick(false) && !watch.tick(true), "a break starts the count over");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
