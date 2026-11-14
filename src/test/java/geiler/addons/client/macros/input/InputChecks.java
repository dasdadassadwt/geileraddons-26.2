package geiler.addons.client.macros.input;

import java.util.Random;

/** Parity of the ported click schedule with the original (bit for bit, same seeded generator) and its stated bounds. */
public final class InputChecks {
	private InputChecks() { }

	public static void run() {
		checkGoldenSchedules();
		checkScheduleRules();
	}

	private static void checkGoldenSchedules() {
		double[][] scenarios = {{8, 12, 0}, {8, 12, 60}, {1, 20, 100}, {6, 10, 30}, {12, 8, 60}};
		for (int s = 0; s < scenarios.length; s++) {
			double[] sc = scenarios[s];
			Random random = new Random(200 + s);
			ClickSchedule schedule = new ClickSchedule(() -> sc[0], () -> sc[1], () -> sc[2], random::nextDouble);
			StringBuilder digits = new StringBuilder();
			long now = 1_000_000_000L;
			for (int tick = 0; tick < 400; tick++) {
				now += tick == 150 ? 400_000_000L : 50_000_000L;
				digits.append(schedule.due(now, 3));
			}
			check(digits.toString().equals(ClickScheduleGoldenVectors.DUE[s]),
				"scenario " + s + " matches the original click schedule");
		}
	}

	private static void checkScheduleRules() {
		ClickSchedule schedule = new ClickSchedule(() -> 10, () -> 10, () -> 0, () -> 0.5);
		check(schedule.due(5_000_000_000L, 3) == 1, "the first call after a reset has a click due at once");
		check(schedule.due(5_000_000_000L, 3) == 0, "and none again until the gap has passed");
		check(schedule.due(5_100_000_000L, 3) == 1, "at ten clicks a second the next is due 100 ms later");
		schedule.reset();
		check(schedule.due(9_000_000_000L, 3) == 1, "a reset starts with a click right away");
		check(schedule.due(20_000_000_000L, 3) == 1,
			"after a long stall the piled-up clicks are dropped instead of fired in a burst");
		ClickSchedule fast = new ClickSchedule(() -> 20, () -> 20, () -> 0, () -> 0.5);
		fast.due(1_000_000_000L, 3);
		check(fast.due(1_400_000_000L, 3) <= 3, "no tick carries more clicks than the cap");
		ClickSchedule slow = new ClickSchedule(() -> 0, () -> 0, () -> 0, () -> 0.5);
		slow.due(1_000_000_000L, 3);
		check(slow.due(2_500_000_000L, 3) == 0 && slow.due(3_100_000_000L, 3) == 1,
			"a rate of zero is held at half a click a second, never a division by zero");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
