package geiler.addons.client.macros.pathing;

import java.util.Random;

/** The bisection that finds a place along a route gives what the plain scan gave. */
public final class StepIndexChecks {
	private StepIndexChecks() { }

	public static void run() {
		Random random = new Random(7);
		for (int round = 0; round < 200; round++) {
			int size = random.nextInt(40);
			double[] distances = new double[size];
			double at = 0.0;
			for (int i = 0; i < size; i++) {
				at += random.nextInt(4) == 0 ? 0.0 : random.nextDouble() * 3.0;
				distances[i] = at;
			}
			for (int probe = 0; probe < 20; probe++) {
				double value = random.nextDouble() * (at + 2.0) - 1.0;
				int expected = size;
				for (int i = 0; i < size; i++) {
					if (distances[i] >= value) {
						expected = i;
						break;
					}
				}
				int got = StepIndex.firstAtLeast(size, i -> distances[i], value);
				check(got == expected, "round " + round + " value " + value + ": " + got + " instead of " + expected);
			}
		}
		check(StepIndex.firstAtLeast(0, i -> 0.0, 1.0) == 0, "no steps: index 0 of 0");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
