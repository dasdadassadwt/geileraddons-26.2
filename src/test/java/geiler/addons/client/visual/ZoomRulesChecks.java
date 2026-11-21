package geiler.addons.client.visual;

/** Offline checks for the Zoom module's scroll, easing and FOV maths. */
public final class ZoomRulesChecks {
	private ZoomRulesChecks() { }

	public static void run() {
		double in = ZoomRules.scroll(4.0, 1.0, 3.0, 30.0);
		check(in > 4.0 && Math.abs(in - 4.0 * 1.3) < 1e-9, "a scroll notch up multiplies the zoom by 1 + 10% of the speed");
		check(Math.abs(ZoomRules.scroll(in, -1.0, 3.0, 30.0) - 4.0) < 1e-9, "scrolling back down undoes a notch");
		check(ZoomRules.scroll(29.0, 5.0, 10.0, 30.0) == 30.0, "zoom stops at the maximum");
		check(ZoomRules.scroll(1.2, -5.0, 10.0, 30.0) == 1.0, "zoom stops at no zoom");
		check(ZoomRules.scroll(4.0, 0.0, 3.0, 30.0) == 4.0, "no scroll changes nothing");
		check(ZoomRules.scroll(4.0, Double.NaN, 3.0, 30.0) == 4.0, "a non-finite scroll is ignored");

		check(ZoomRules.step(1.0, 8.0, 10.0, 0.016, false) == 8.0, "instant mode jumps to the target");
		double eased = ZoomRules.step(1.0, 8.0, 10.0, 0.016, true);
		check(eased > 1.0 && eased < 8.0, "smooth mode moves toward the target without reaching it in one frame");
		check(ZoomRules.step(1.0, 8.0, 10.0, 0.0, true) == 1.0, "no elapsed time means no movement");
		double now = 1.0;
		for (int i = 0; i < 600; i++) now = ZoomRules.step(now, 8.0, 10.0, 0.016, true);
		check(now == 8.0, "smooth mode lands exactly on the target");
		double out = 8.0;
		for (int i = 0; i < 600; i++) out = ZoomRules.step(out, 1.0, 10.0, 0.016, true);
		check(out == 1.0, "smooth mode zooms back out to exactly no zoom");
		check(ZoomRules.step(1.0, 8.0, 10.0, 100.0, true) < 8.0 || ZoomRules.step(1.0, 8.0, 10.0, 100.0, true) == 8.0,
			"a long frame never overshoots");
		check(ZoomRules.step(1.0, 8.0, 10.0, -1.0, true) == 1.0, "a negative frame time is ignored");

		check(ZoomRules.fovScale(4.0) == 0.25 && ZoomRules.fovScale(1.0) == 1.0 && ZoomRules.fovScale(0.2) == 1.0
			&& ZoomRules.fovScale(Double.NaN) == 1.0, "the field of view is divided by the zoom, never widened");
		check(ZoomRules.initial(0.5, 30.0) == 1.0 && ZoomRules.initial(50.0, 30.0) == 30.0 && ZoomRules.initial(4.0, 30.0) == 4.0,
			"the starting zoom stays between no zoom and the maximum");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
