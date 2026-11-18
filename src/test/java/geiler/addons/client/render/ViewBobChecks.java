package geiler.addons.client.render;

/** Offline checks for the view-bob transform labels use to follow the bobbing world. */
public final class ViewBobChecks {
	private ViewBobChecks() { }

	public static void run() {
		double[] none = {1.0, 2.0, -3.0};
		ViewBob.apply(none, 0.7f, 0.0f);
		check(none[0] == 1.0 && none[1] == 2.0 && none[2] == -3.0, "no bob leaves a point where it is");

		double[] origin = {0.0, 0.0, 0.0};
		ViewBob.apply(origin, 0.0f, 1.0f);
		check(near(origin[0], 0.0) && near(origin[1], -1.0) && near(origin[2], 0.0),
			"at step zero the view only drops by the bob amount (the origin is untouched by the rotations)");

		double[] far = {0.0, 0.0, -10.0};
		ViewBob.apply(far, 0.0f, 1.0f);
		check(far[1] > -1.0 && far[1] < 0.0 && Math.abs(far[2] + 10.0) < 0.2, "a point ahead is lifted by the view pitch (so it drops less than the bob) and stays about as far away");

		double[] side = {0.0, 0.0, 0.0};
		ViewBob.apply(side, 0.5f, 1.0f);
		check(near(side[0], 0.5) && near(side[1], 0.0), "half a step along, the view has swung sideways and is back at height");

		double[] stale = {1.0, 1.0, -1.0};
		ViewBob.apply(stale);
		check(stale[0] == 1.0 && stale[1] == 1.0 && stale[2] == -1.0, "with no recent capture the transform is skipped");
	}

	private static boolean near(double a, double b) {
		return Math.abs(a - b) < 1.0e-5;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
