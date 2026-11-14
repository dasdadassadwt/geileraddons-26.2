package geiler.addons.client.macros.killer;

import geiler.addons.client.macros.navigation.CameraAim;
import geiler.addons.client.macros.navigation.LookHeight;

/**
 * What keeps a run from stalling on real ground: the view looks along the way the keys push (a view on a point 150 degrees from the
 * heading deadlocked the turn-first hold), the view's height does not follow every slab, a stall is reported once it lasts, and a rest
 * point is only used when nothing is left to hit.
 */
public final class RecoveryChecks {
	private RecoveryChecks() { }

	public static void run() {
		checkCameraAlongHeading();
		checkLookHeightEases();
		checkStallWatch();
		checkRestPoint();
		checkVerticalPlanning();
		checkRunOn();
		checkJumpCost();
	}

	private static void checkJumpCost() {
		double one = geiler.addons.client.macros.pathing.Terrain.jumpExtra(1.0, 1.0);
		double three = geiler.addons.client.macros.pathing.Terrain.jumpExtra(3.0, 1.0);
		check(three > one * 2.0, "a jump of three blocks costs well over twice a one-block jump (" + one + " / " + three + ")");
		// Stairs two blocks to the side (about 7 ticks of walking each way round) must beat a three-block jump up.
		check(three > 7.0 * 2.0, "so a detour of two blocks to stairs is cheaper than jumping up three");
		check(geiler.addons.client.macros.pathing.Terrain.jumpExtra(3.0, 2.0) == 2.0 * three, "the setting scales it");
		check(geiler.addons.client.macros.pathing.Terrain.jumpExtra(0.5, 1.0) == one, "a step under a block costs a plain jump, not less");
	}

	private static void checkVerticalPlanning() {
		// Two mobs at the same flat distance: the one on the player's own level goes first, whatever the pattern.
		for (TargetPattern pattern : TargetPattern.values()) {
			if (pattern == TargetPattern.NEAR_RANDOM) continue; // draws one of the best tours at random, by design
			java.util.List<TargetPlanner.Mob<String>> list = new java.util.ArrayList<>(java.util.List.of(
				new TargetPlanner.Mob<>("up the wall", 0.0, 8.0, 14.0), new TargetPlanner.Mob<>("flat", 6.0, 8.0, 0.0)));
			TargetPlanner.Plan<String> plan = TargetPlanner.plan(pattern, list, 0.0, 0.0, 0.0, new java.util.Random(3), null, 0.0, 0.0);
			check("flat".equals(plan.order.get(0).item), pattern + ": the mob on the flat goes before the one up a wall");
		}
		// A step up is free: a block of height does not change the order.
		java.util.List<TargetPlanner.Mob<String>> near = java.util.List.of(
			new TargetPlanner.Mob<>("step", 0.0, 5.0, 1.0), new TargetPlanner.Mob<>("far", 0.0, 12.0, 0.0));
		check("step".equals(TargetPlanner.plan(TargetPattern.NEAREST, near, 0.0, 0.0, 0.0, new java.util.Random(1), null, 0.0, 0.0).order.get(0).item),
			"a mob one block up is not penalised");
		check(TargetPlanner.heightCost(0.0, 1.0) == 0.0 && TargetPlanner.heightCost(0.0, 11.0) > 20.0, "ten blocks of climb are a long walk");
	}

	private static void checkRunOn() {
		check(!geiler.addons.client.macros.input.TurnFirst.runsOn(20.0, 100.0), "a plain correction keeps the sector mapping");
		check(geiler.addons.client.macros.input.TurnFirst.runsOn(60.0, 100.0), "60 degrees off: W alone while the view turns");
		check(geiler.addons.client.macros.input.TurnFirst.runsOn(-99.0, 100.0), "to either side");
		check(!geiler.addons.client.macros.input.TurnFirst.runsOn(150.0, 100.0), "further than the limit is the hold, not a run");
		check(!geiler.addons.client.macros.input.TurnFirst.runsOn(60.0, 0.0), "off means off");
	}

	private static void checkCameraAlongHeading() {
		// The player at the origin, steering at the point south of it (yaw 0): a camera point to the south is along it, one to the
		// north - the route bends back on itself - is not.
		check(CameraAim.along(0.0, 0.0, 0.0, 5.0, 0.0), "a point straight along the heading is looked at");
		check(CameraAim.along(0.0, 0.0, 2.0, 5.0, 0.0), "and one 22 degrees off it");
		check(!CameraAim.along(0.0, 0.0, 0.0, -5.0, 0.0), "a point behind the heading is not");
		check(!CameraAim.along(0.0, 0.0, 5.0, 0.0, 0.0), "nor one a quarter turn off it");
		// West is yaw 90: the heading is west, the camera point is west and a little south.
		check(CameraAim.along(0.0, 0.0, -5.0, 2.0, 90.0), "yaw 90 is west");
		check(!CameraAim.along(0.0, 0.0, 5.0, 0.0, 90.0), "and east is the other way");
		check(CameraAim.MAX_OFF_HEADING_DEGREES >= 30.0 && CameraAim.MAX_OFF_HEADING_DEGREES <= 70.0, "the limit is a plain turn of the head");
		// Standing on the point: no bearing, so it is not a reason to turn.
		check(CameraAim.along(1.0, 1.0, 1.0, 1.0, 0.0), "a point on the player is along any heading");
	}

	private static void checkLookHeightEases() {
		LookHeight height = new LookHeight();
		check(height.filter(0.25) == 0.25, "the first value is taken as it is");
		// A slab: the point the view follows is half a block higher, all at once.
		double first = height.filter(0.75);
		check(first > 0.25 && first < 0.40, "a half block step moves the view's height by a fraction of it at first (" + first + ")");
		double last = first;
		for (int tick = 0; tick < 60; tick++) {
			double next = height.filter(0.75);
			check(next >= last - 1.0E-9, "and keeps going up, never back (" + next + " after " + last + ")");
			last = next;
		}
		check(Math.abs(last - 0.75) < 0.01, "and gets there in a couple of seconds (" + last + ")");
		// Bumpy ground: up and down every other tick. The view barely moves.
		LookHeight bumpy = new LookHeight();
		bumpy.filter(0.0);
		double low = 0.0;
		double high = 0.0;
		for (int tick = 0; tick < 80; tick++) {
			double v = bumpy.filter(tick % 2 == 0 ? 0.5 : 0.0);
			if (tick > 20) {
				low = Math.min(low == 0.0 ? v : low, v);
				high = Math.max(high, v);
			}
		}
		check(high - low < 0.12, "slabs every other tick move the view's height by a tenth of a block at most (" + (high - low) + ")");
		bumpy.reset();
		check(bumpy.filter(1.5) == 1.5, "after a reset the next value is taken as it is");
	}

	private static void checkStallWatch() {
		StallWatch watch = new StallWatch();
		for (int tick = 0; tick < StallWatch.AFTER_TICKS - 1; tick++) {
			check(!watch.tick(true, 0.1), "no report before the stall has lasted");
		}
		check(watch.tick(true, 0.1), "reported when it has lasted " + StallWatch.AFTER_TICKS + " ticks");
		int reports = 0;
		for (int tick = 0; tick < 200; tick++) {
			if (watch.tick(true, 0.0)) reports++;
		}
		check(reports >= 5 && reports <= 12, "and then now and then, not every tick (" + reports + " in 200)");
		check(watch.ticks() > 200, "the length of the stall is counted");
		check(!watch.tick(true, 8.0), "moving ends it");
		check(watch.ticks() == 0, "and the count starts over");
		StallWatch idle = new StallWatch();
		for (int tick = 0; tick < 100; tick++) check(!idle.tick(false, 0.0), "standing still when nothing asked to move is no stall");
	}

	private static void checkRestPoint() {
		check(!RestPoint.due(0L, 4.0), "no rest the moment the last mob is gone");
		check(!RestPoint.due(3_999L, 4.0), "nor just before the delay");
		check(RestPoint.due(4_000L, 4.0), "at the delay");
		check(RestPoint.inside(10.0, 10.0, 12.0, 11.0, 4.0), "within the radius is at the rest point");
		check(!RestPoint.inside(10.0, 10.0, 20.0, 10.0, 4.0), "outside it is not");
		java.util.Random random = new java.util.Random(5);
		double near = 0.0;
		for (int i = 0; i < 2000; i++) {
			double[] spot = RestPoint.spot(100.0, -40.0, 4.0, random.nextDouble(), random.nextDouble());
			double d = Math.hypot(spot[0] - 100.0, spot[1] + 40.0);
			check(d <= 4.0 + 1.0E-9, "a wander spot is inside the radius (" + d + ")");
			if (d < 2.0) near++;
		}
		check(near > 400 && near < 600, "and spread over the disc, a quarter of them in the inner half (" + near + " of 2000)");
		for (double r = 0.0; r <= 1.0; r += 0.1) {
			long pause = RestPoint.pauseMillis(r);
			check(pause >= 2_500L && pause <= 7_000L, "a pause at the rest point is a few seconds (" + pause + ")");
		}
		check(!RestPoint.configured(new int[0]) && !RestPoint.configured(new int[] {1, 2}), "no point or a broken one is not configured");
		check(RestPoint.configured(new int[] {1, 2, 3}), "three numbers are");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
