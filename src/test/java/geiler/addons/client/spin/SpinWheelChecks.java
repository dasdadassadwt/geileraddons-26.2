package geiler.addons.client.spin;

import java.util.ArrayList;
import java.util.List;

/** Offline checks for the /ga spin chance rules and landing angle. */
public final class SpinWheelChecks {
	private SpinWheelChecks() {
	}

	public static void run() {
		List<SpinEntry> entries = new ArrayList<>();
		SpinWheelRules.add(entries, "a");
		chances(entries, 100);
		SpinWheelRules.add(entries, "b");
		chances(entries, 50, 50);
		SpinWheelRules.setChance(entries, 0, 80);
		chances(entries, 80, 20);
		SpinWheelRules.add(entries, "c");
		chances(entries, 160.0 / 3, 40.0 / 3, 100.0 / 3);
		SpinWheelRules.setChance(entries, 2, 100);
		chances(entries, 0, 0, 100);
		SpinWheelRules.setChance(entries, 2, 40);
		chances(entries, 30, 30, 40);
		SpinWheelRules.setChance(entries, 0, 250);
		chances(entries, 100, 0, 0);
		SpinWheelRules.setChance(entries, 0, -5);
		chances(entries, 0, 50, 50);
		SpinWheelRules.remove(entries, 1);
		chances(entries, 0, 100);
		SpinWheelRules.remove(entries, 1);
		chances(entries, 100);
		SpinWheelRules.setChance(entries, 0, 10);
		chances(entries, 100);

		List<SpinEntry> loaded = new ArrayList<>(List.of(new SpinEntry("x", Double.NaN), new SpinEntry("y", -4), new SpinEntry("z", 7)));
		SpinWheelRules.normalize(loaded);
		chances(loaded, 0, 0, 100);

		List<SpinEntry> wheel = new ArrayList<>(List.of(new SpinEntry("a", 25), new SpinEntry("never", 0), new SpinEntry("b", 75)));
		check(SpinWheelRules.pick(wheel, 0.0) == 0, "first");
		check(SpinWheelRules.pick(wheel, 0.2499) == 0, "end of first");
		check(SpinWheelRules.pick(wheel, 0.25) == 2, "a zero chance never wins");
		check(SpinWheelRules.pick(wheel, 0.999999) == 2, "last");
		check(SpinWheelRules.pick(new ArrayList<>(), 0.5) == -1, "empty");

		for (int winner : new int[] {0, 2}) {
			for (double inside : new double[] {0.0, 0.5, 1.0}) {
				double from = 1234.5;
				double end = SpinWheelRules.landingRotation(wheel, winner, from, inside, 5);
				check(end - from >= 5 * 360 && end - from < 6 * 360, "turns " + (end - from));
				check(SpinWheelRules.entryAtPointer(wheel, end) == winner, "lands on the winner " + winner + " " + inside);
			}
		}
		near(0.0, SpinWheelRules.ease(0.0), "ease start");
		near(1.0, SpinWheelRules.ease(1.0), "ease end");
		check(SpinWheelRules.ease(0.5) > 0.5, "ease slows down");
		System.out.println("Spin wheel checks passed");
	}

	private static void chances(List<SpinEntry> entries, double... expected) {
		check(entries.size() == expected.length, "size " + entries.size());
		for (int i = 0; i < expected.length; i++) near(expected[i], entries.get(i).getChance(), "chance " + i);
	}

	private static void near(double expected, double actual, String what) {
		if (Math.abs(expected - actual) > 1e-6) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
	}

	private static void check(boolean condition, String what) {
		if (!condition) throw new AssertionError(what);
	}
}
