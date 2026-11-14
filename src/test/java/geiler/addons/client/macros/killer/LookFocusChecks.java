package geiler.addons.client.macros.killer;

/**
 * The view's focus while hunting: the target is looked at, at any distance, whenever it is visible and inside the view cone,
 * and the cone has hysteresis so a mob at its edge does not make the view flip between the mob and the route.
 */
public final class LookFocusChecks {
	private LookFocusChecks() { }

	public static void run() {
		checkCone();
		checkHysteresis();
		checkBearing();
		checkIdlePoint();
	}

	private static void checkCone() {
		LookFocus focus = new LookFocus();
		check(focus.update(0.0, true), "a mob straight ahead is the focus, however far");
		focus.reset();
		check(focus.update(60.0, true), "a mob inside the cone is the focus");
		focus.reset();
		check(!focus.update(120.0, true), "a mob well to the side of the back is not");
		focus.reset();
		check(!focus.update(170.0, true), "a mob behind the player is not");
		focus.reset();
		check(!focus.update(0.0, false), "a mob that cannot be seen is not, however well placed");
	}

	private static void checkHysteresis() {
		LookFocus focus = new LookFocus();
		check(!focus.update(LookFocus.ENTER_DEGREES + 5.0, true), "just outside the cone: not taken");
		check(focus.update(LookFocus.ENTER_DEGREES - 5.0, true), "just inside: taken");
		check(focus.update(LookFocus.ENTER_DEGREES + 15.0, true), "once taken it is held a little past the edge");
		check(!focus.update(LookFocus.LEAVE_DEGREES + 5.0, true), "and let go beyond the leave angle");
		check(!focus.update(LookFocus.ENTER_DEGREES + 5.0, true), "after that it needs the enter angle again");
		focus.update(10.0, true);
		check(!focus.update(10.0, false), "losing sight lets it go at once");
		check(LookFocus.ENTER_DEGREES < LookFocus.LEAVE_DEGREES, "the cone leaves later than it enters");
		// Jitter around the enter angle gives few flips, not one per frame.
		LookFocus jitter = new LookFocus();
		java.util.Random random = new java.util.Random(4);
		int flips = 0;
		boolean last = false;
		for (int i = 0; i < 400; i++) {
			boolean now = jitter.update(LookFocus.ENTER_DEGREES + random.nextGaussian() * 6.0, true);
			if (now != last) flips++;
			last = now;
		}
		check(flips <= 6, "a mob at the edge of the cone does not flip the focus every frame (" + flips + " flips)");
	}

	private static void checkBearing() {
		// Looking south (yaw 0, toward +z): a mob straight south is 0 off, one to the west (+x is west in yaw terms: yaw 90 is west, -x)
		check(Math.abs(LookFocus.offDegrees(0.0, 0.0, 0.0, 0.0, 10.0)) < 1.0E-9, "straight ahead is 0 degrees off");
		check(Math.abs(LookFocus.offDegrees(0.0, 0.0, 0.0, -10.0, 0.0) - 90.0) < 1.0E-6, "to the west is 90 degrees off when facing south");
		check(Math.abs(LookFocus.offDegrees(0.0, 0.0, 0.0, 0.0, -10.0) - 180.0) < 1.0E-6, "behind is 180 degrees off");
		check(Math.abs(LookFocus.offDegrees(350.0, 0.0, 0.0, 0.0, 10.0) - 10.0) < 1.0E-6, "the angle wraps around 360");
		check(LookFocus.offDegrees(0.0, 0.0, 0.0, 0.0, 0.0) == 0.0, "a mob on the player is 0 off, not NaN");
	}

	private static void checkIdlePoint() {
		// The idle heading drifts around a base heading and the pitch stays level to a little up, never down at the ground or at the sky.
		java.util.Random random = new java.util.Random(7);
		for (int i = 0; i < 500; i++) {
			double[] look = IdleLook.angles(100.0, random.nextDouble(), random.nextDouble());
			check(Math.abs(look[0] - 100.0) <= IdleLook.MAX_DRIFT_DEGREES + 1.0E-9, "the idle heading stays near the base heading (" + look[0] + ")");
			check(look[1] >= 0.0 && look[1] <= IdleLook.MAX_PITCH_DEGREES + 1.0E-9, "the idle pitch is level to a little up (" + look[1] + ")");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
