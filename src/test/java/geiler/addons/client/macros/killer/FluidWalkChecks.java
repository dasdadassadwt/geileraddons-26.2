package geiler.addons.client.macros.killer;

import geiler.addons.client.macros.input.TurnFirst;

/** The view leads and the keys follow it: what the camera is on while walking, and which keys a heading off the view asks for. */
public final class FluidWalkChecks {
	private FluidWalkChecks() { }

	public static void run() {
		checkLookPlan();
		checkKeyDecision();
		checkCameraLead();
	}

	private static void checkLookPlan() {
		check(LookPlan.onMob(10.0, true, false, 2000L, false), "a mob nearly along the heading, seen, long after the last switch: look at it");
		check(!LookPlan.onMob(30.0, true, false, 2000L, false), "a mob 30 degrees off the heading is outside the cone: the route is looked at");
		check(LookPlan.onMob(30.0, true, true, 0L, false), "but one already looked at is kept up to 40 degrees");
		check(!LookPlan.onMob(45.0, true, true, 0L, false), "and let go beyond that");
		check(!LookPlan.onMob(10.0, false, true, 0L, false), "a mob that cannot be seen is never looked at, a wall is not what to stare at");
		check(!LookPlan.onMob(10.0, true, false, 300L, false), "going onto a mob waits out the dwell time after the last switch");
		check(LookPlan.onMob(10.0, true, false, LookPlan.DWELL_MILLIS, false), "and is done once it has passed");
		check(LookPlan.onMob(170.0, false, false, 0L, true), "in a fight the target is looked at whatever the heading");
		check(LookPlan.onMob(-20.0, true, false, 5000L, false), "to either side of the heading");
	}

	private static void checkKeyDecision() {
		// precise: the old rules; loose: the view leads and the keys only push forward.
		check(TurnFirst.decide(10.0, 100.0, true, false, false, 0) == TurnFirst.Decision.FORWARD, "a small angle is forward alone");
		check(TurnFirst.decide(35.0, 100.0, true, false, false, 0) == TurnFirst.Decision.FORWARD, "no sideways keys at 35 degrees on a loose line");
		check(TurnFirst.decide(70.0, 100.0, true, false, false, 0) == TurnFirst.Decision.FORWARD, "nor at 70 while the view catches up");
		check(TurnFirst.decide(120.0, 100.0, true, false, false, 0) == TurnFirst.Decision.HOLD, "past the limit the keys are let go of to turn");
		check(TurnFirst.decide(120.0, 100.0, true, false, false, TurnFirst.MAX_HOLD_TICKS) == TurnFirst.Decision.FORWARD,
			"a hold that has lasted its longest gives way to forward, not to running backwards");
		check(TurnFirst.decide(120.0, 100.0, false, false, false, 0) == TurnFirst.Decision.FORWARD, "in the air there is no hold");
		check(TurnFirst.decide(120.0, 0.0, true, false, false, 0) == TurnFirst.Decision.FORWARD, "a limit of zero is off");
		check(TurnFirst.decide(20.0, 100.0, true, false, true, 0) == TurnFirst.Decision.MIX, "a precise line keeps the sector mix for a small angle");
		check(TurnFirst.decide(60.0, 100.0, true, false, true, 0) == TurnFirst.Decision.FORWARD, "and still runs on while the view turns");
		check(TurnFirst.decide(20.0, 100.0, true, true, false, 0) == TurnFirst.Decision.MIX, "a step aside asked for is sideways keys");
		check(TurnFirst.MAX_HOLD_TICKS >= 10 && TurnFirst.MAX_HOLD_TICKS <= 40, "the hold stays between half a second and two");
	}

	private static void checkCameraLead() {
		// The camera point is a little past the point the keys are steered at, not far beyond it.
		check(CameraLead.ahead(20.0, 4.0, 5.5) <= 4.0 + CameraLead.LEAD, "the camera leads the steer point by a little");
		check(CameraLead.ahead(20.0, 4.0, 5.5) > 4.0, "but does lead it");
		check(CameraLead.ahead(20.0, 12.0, 5.5) == 5.5, "never past the old look-ahead");
		check(CameraLead.ahead(20.0, Double.NaN, 5.5) == 5.5, "with no steer point, the old look-ahead");
		check(CameraLead.ahead(1.0, 4.0, 5.5) >= 0.0, "short routes do not give a negative look-ahead");
		check(CameraLead.ahead(3.0, 0.5, 5.5) >= CameraLead.MIN, "and keep the least look-ahead");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
