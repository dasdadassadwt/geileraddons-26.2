package geiler.addons.client.macros.killer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * The target planner: it orders the nearest mobs as a short tour. Checks the first pick of each pattern, that a curved line
 * of mobs is worked as one smooth sweep, that a plan does not flip on a small move or at an angle boundary, that the work
 * is bounded, and that no pattern loses or duplicates a mob.
 */
public final class TargetPlannerChecks {
	private TargetPlannerChecks() { }

	private static TargetPlanner.Mob<String> at(String name, double x, double z) {
		return new TargetPlanner.Mob<>(name, x, z);
	}

	private static TargetPlanner.Plan<String> plan(TargetPattern pattern, List<TargetPlanner.Mob<String>> list, double yaw, long seed) {
		return TargetPlanner.plan(pattern, list, 0.0, 0.0, yaw, new Random(seed), null);
	}

	private static List<String> names(TargetPlanner.Plan<String> plan) {
		List<String> out = new ArrayList<>();
		for (TargetPlanner.Mob<String> mob : plan.order) out.add(mob.item);
		return out;
	}

	public static void run() {
		checkFirstPicks();
		checkSmoothSweep();
		checkStability();
		checkBounded();
		checkEveryMobOnce();
		checkViewCone();
	}

	private static void checkFirstPicks() {
		// The player at the origin looking south (+z). "ahead" is 8 away straight ahead, "near" is 3 away to the east
		// (a quarter turn), "behind" is 6 away behind, "far" is 14 away ahead.
		List<TargetPlanner.Mob<String>> list = new ArrayList<>(List.of(at("ahead", 0, 8), at("near", 3, 0), at("behind", 0, -6), at("far", 0, 14)));
		check(names(plan(TargetPattern.NEAREST, list, 0, 1)).get(0).equals("near"), "nearest picks the closest mob first");
		check(names(plan(TargetPattern.NEAREST, list, 0, 1)).equals(List.of("near", "behind", "ahead", "far")),
			"nearest is a chain: each next mob is the one closest to the last");

		// A mob a little off the view, and one close but to the side: least turn takes the first, nearest the second.
		List<TargetPlanner.Mob<String>> two = new ArrayList<>(List.of(at("straight", 2, 10), at("side", 5, 0)));
		check(names(plan(TargetPattern.LEAST_TURN, two, 0, 1)).get(0).equals("straight"), "least turn picks the mob that needs the least turning");
		check(names(plan(TargetPattern.NEAREST, two, 0, 1)).get(0).equals("side"), "nearest still picks the close one");
		check(names(plan(TargetPattern.LEAST_TURN, two, -90, 1)).get(0).equals("side"),
			"least turn follows the view: facing the other way the side mob is ahead");

		// A crowd: three mobs close together at (10, 10), one loner at (2, 0).
		List<TargetPlanner.Mob<String>> crowd = new ArrayList<>(List.of(at("loner", 2, 0), at("c1", 10, 10), at("c2", 11, 10), at("c3", 10, 11)));
		check(names(plan(TargetPattern.NEAREST, crowd, 0, 1)).get(0).equals("loner"), "nearest takes the loner");
		check(names(plan(TargetPattern.CLUSTER, crowd, 0, 1)).get(0).startsWith("c"), "cluster starts in the crowd");

		// Near random draws among the best tours: different seeds give different first picks, the same seed the same.
		java.util.Set<String> seen = new HashSet<>();
		for (long seed = 0; seed < 60; seed++) seen.add(names(plan(TargetPattern.NEAR_RANDOM, list, 0, seed)).get(0));
		check(seen.size() >= 2, "near random varies with the seed (" + seen + ")");
		check(names(plan(TargetPattern.NEAR_RANDOM, list, 0, 9)).equals(names(plan(TargetPattern.NEAR_RANDOM, list, 0, 9))),
			"near random is the same for the same seed");
	}

	private static void checkSmoothSweep() {
		// Four mobs in two columns ahead: a zig-zag between the columns is what a nearest chain does.
		List<TargetPlanner.Mob<String>> columns = new ArrayList<>(List.of(
			at("L1", -3, 5), at("R1", 3, 6), at("L2", -3, 11), at("R2", 3, 12)));
		TargetPlanner.Plan<String> nearest = plan(TargetPattern.NEAREST, columns, 0, 1);
		TargetPlanner.Plan<String> sweep = plan(TargetPattern.SWEEP, columns, 0, 1);
		check(sweep.turnDegrees <= nearest.turnDegrees, "a sweep turns no more than the nearest chain (" + sweep.turnDegrees + " vs " + nearest.turnDegrees + ")");
		check(reversals(sweep) <= 1, "a sweep keeps one turning direction, apart from the turn back (" + names(sweep) + ")");

		// A curve of mobs ahead, left to right: the plan walks it from one end, never back and forth.
		List<TargetPlanner.Mob<String>> arc = new ArrayList<>(List.of(
			at("a", -8, 4), at("b", -5, 9), at("c", 0, 11), at("d", 5, 9), at("e", 8, 4)));
		for (TargetPattern pattern : new TargetPattern[] {TargetPattern.SWEEP, TargetPattern.LEAST_TURN}) {
			List<TargetPlanner.Mob<String>> first4 = plan(pattern, arc, 0, 1).order.subList(0, 4);
			boolean up = true;
			boolean down = true;
			for (int i = 1; i < first4.size(); i++) {
				up &= first4.get(i).x > first4.get(i - 1).x;
				down &= first4.get(i).x < first4.get(i - 1).x;
			}
			check(up || down, pattern + " walks an arc of mobs from one end to the other, not back and forth (" + names(plan(pattern, arc, 0, 1)) + ")");
		}
	}

	/** How often the signed turn changes direction along the plan's first legs, ignoring turns under 15 degrees. */
	private static int reversals(TargetPlanner.Plan<String> plan) {
		double px = 0.0;
		double pz = 0.0;
		double heading = 0.0;
		double lastSign = 0.0;
		int count = 0;
		for (int i = 0; i < Math.min(4, plan.order.size()); i++) {
			TargetPlanner.Mob<String> mob = plan.order.get(i);
			double to = Math.toDegrees(Math.atan2(-(mob.x - px), mob.z - pz));
			double turn = ((to - heading) % 360.0 + 540.0) % 360.0 - 180.0;
			if (Math.abs(turn) > 15.0) {
				double sign = Math.signum(turn);
				if (lastSign != 0.0 && sign != lastSign) count++;
				lastSign = sign;
			}
			heading = to;
			px = mob.x;
			pz = mob.z;
		}
		return count;
	}

	private static void checkStability() {
		// A plan that is passed back in is kept while the mobs only jitter.
		Random jitter = new Random(5);
		double[][] base = {{-3, 5}, {3, 6}, {-3, 11}, {3, 12}, {0, 20}, {8, 8}};
		List<String> previous = null;
		List<String> first = null;
		for (int round = 0; round < 60; round++) {
			List<TargetPlanner.Mob<String>> mobs = new ArrayList<>();
			for (int i = 0; i < base.length; i++) {
				mobs.add(at("m" + i, base[i][0] + jitter.nextGaussian() * 0.3, base[i][1] + jitter.nextGaussian() * 0.3));
			}
			// The same objects every round, as the macro's entities are.
			if (previous != null) {
				List<TargetPlanner.Mob<String>> same = new ArrayList<>();
				for (String name : previous) same.add(mobs.get(Integer.parseInt(name.substring(1))));
				mobs = same;
			}
			TargetPlanner.Plan<String> plan = TargetPlanner.plan(TargetPattern.SWEEP, mobs, 0.0, 0.0, 0.0, new Random(1), previous);
			previous = names(plan);
			if (first == null) first = previous;
			else check(previous.equals(first), "the plan does not flip under jitter (round " + round + ": " + previous + " vs " + first + ")");
		}

		// A mob that leaves or joins replans.
		List<TargetPlanner.Mob<String>> three = new ArrayList<>(List.of(at("a", 0, 5), at("b", 4, 8), at("c", -4, 9)));
		TargetPlanner.Plan<String> before = TargetPlanner.plan(TargetPattern.NEAREST, three, 0, 0, 0, new Random(1), null);
		List<TargetPlanner.Mob<String>> four = new ArrayList<>(three);
		four.add(at("d", 0, 2));
		TargetPlanner.Plan<String> after = TargetPlanner.plan(TargetPattern.NEAREST, four, 0, 0, 0, new Random(1), names(before));
		check(names(after).get(0).equals("d") && !after.kept, "a mob that joins is planned in");

		// No flip at an angle boundary: a mob moving across 45 degrees does not change the plan the way a hard slice would.
		for (TargetPattern pattern : new TargetPattern[] {TargetPattern.SWEEP, TargetPattern.LEAST_TURN, TargetPattern.CLUSTER}) {
			List<String> a = names(plan(pattern, boundary(44.9), 0, 1));
			List<String> b = names(plan(pattern, boundary(45.1), 0, 1));
			check(a.equals(b), pattern + " does not flip when a mob crosses 45 degrees (" + a + " vs " + b + ")");
		}
	}

	private static List<TargetPlanner.Mob<String>> boundary(double degrees) {
		double r = Math.toRadians(degrees);
		return new ArrayList<>(List.of(at("edge", -Math.sin(r) * 9.0, Math.cos(r) * 9.0), at("other", 6, 7), at("third", -7, 3)));
	}

	private static void checkBounded() {
		List<TargetPlanner.Mob<String>> many = new ArrayList<>();
		Random random = new Random(3);
		for (int i = 0; i < 40; i++) many.add(at("m" + i, random.nextDouble() * 60 - 30, random.nextDouble() * 60 - 30));
		for (TargetPattern pattern : TargetPattern.values()) {
			TargetPlanner.Plan<String> plan = plan(pattern, many, 0, 1);
			check(plan.evaluations <= 1680, pattern + " scores at most 1680 tours (" + plan.evaluations + ")");
			check(plan.order.size() == 40, pattern + " still lists every mob");
		}
	}

	private static void checkViewCone() {
		// The player looks south (yaw 0, toward +z). "back" is the nearest mob but behind; "front" is further, ahead.
		List<TargetPlanner.Mob<String>> list = new ArrayList<>(List.of(at("back", 0, -8), at("front", 2, 9), at("front2", -2, 14), at("side", 20, 0)));
		for (TargetPattern pattern : TargetPattern.values()) {
			if (pattern == TargetPattern.NEAR_RANDOM) continue;
			check(names(TargetPlanner.plan(pattern, list, 0.0, 0.0, 0.0, new Random(1), null, 70.0)).get(0).startsWith("front"),
				pattern + " with a view cone starts with a mob in front, not the nearer one behind");
		}
		check(names(TargetPlanner.plan(TargetPattern.NEAREST, list, 0.0, 0.0, 0.0, new Random(1), null, 0.0)).get(0).equals("back"),
			"with the cone off the nearest mob is first, wherever it is");
		check(names(TargetPlanner.plan(TargetPattern.NEAREST, list, 0.0, 0.0, 0.0, new Random(1), null)).get(0).equals("back"),
			"the call without a cone is the old one");

		// Behind comes after, nearest first, and nothing is lost.
		List<String> order = names(TargetPlanner.plan(TargetPattern.SWEEP, list, 0.0, 0.0, 0.0, new Random(1), null, 70.0));
		check(order.size() == 4 && new HashSet<>(order).size() == 4, "every mob is kept once with a cone");
		check(order.indexOf("back") > order.indexOf("front") && order.indexOf("back") > order.indexOf("front2"), "the mob behind comes after the ones in front");

		// Nothing in front: plan over all of them as before.
		List<TargetPlanner.Mob<String>> behind = new ArrayList<>(List.of(at("b1", 0, -6), at("b2", 3, -9)));
		check(names(TargetPlanner.plan(TargetPattern.NEAREST, behind, 0.0, 0.0, 0.0, new Random(1), null, 70.0)).equals(List.of("b1", "b2")),
			"with everything behind the nearest is still first");
		// A single mob in front and the rest behind.
		List<TargetPlanner.Mob<String>> one = new ArrayList<>(List.of(at("far front", 0, 25), at("b1", 0, -9), at("b2", 4, -10)));
		List<String> got = names(TargetPlanner.plan(TargetPattern.NEAREST, one, 0.0, 0.0, 0.0, new Random(1), null, 70.0));
		check(got.get(0).equals("far front") && got.size() == 3, "one mob in front is first however far, the rest follow (" + got + ")");

		// A mob right next to the player counts as in front whatever side it is on: a person kills what is beside them before
		// running on, and a macro that skips it looks like one.
		List<TargetPlanner.Mob<String>> beside = new ArrayList<>(List.of(at("beside", -3, -1), at("front", 2, 9), at("front2", -2, 14)));
		check(names(TargetPlanner.plan(TargetPattern.NEAREST, beside, 0.0, 0.0, 0.0, new Random(1), null, 70.0)).get(0).equals("beside"),
			"a mob within " + TargetPlanner.NEAR_BLOCKS + " blocks is not left behind by the cone");
		List<TargetPlanner.Mob<String>> farSide = new ArrayList<>(List.of(at("far side", -9, -1), at("front", 2, 9)));
		check(names(TargetPlanner.plan(TargetPattern.NEAREST, farSide, 0.0, 0.0, 0.0, new Random(1), null, 70.0)).get(0).equals("front"),
			"one further off to the side still waits for the ones in front");

		// A kept plan whose first mob has gone behind the view is not kept while one is in front.
		TargetPlanner.Plan<String> first = TargetPlanner.plan(TargetPattern.NEAREST, list, 0.0, 0.0, 0.0, new Random(1), null, 0.0);
		TargetPlanner.Plan<String> again = TargetPlanner.plan(TargetPattern.NEAREST, list, 0.0, 0.0, 0.0, new Random(1), names(first), 70.0);
		check(!again.kept && names(again).get(0).startsWith("front"), "a plan that starts behind the view is replaced by one that starts in front");
		// And one that already starts in front is kept under jitter.
		TargetPlanner.Plan<String> fronted = TargetPlanner.plan(TargetPattern.SWEEP, list, 0.0, 0.0, 0.0, new Random(1), null, 70.0);
		TargetPlanner.Plan<String> kept = TargetPlanner.plan(TargetPattern.SWEEP, list, 0.0, 0.0, 0.0, new Random(1), names(fronted), 70.0);
		check(names(kept).equals(names(fronted)), "a plan that starts in front is kept");
	}

	private static void checkEveryMobOnce() {
		List<TargetPlanner.Mob<String>> crowd = new ArrayList<>(List.of(at("loner", 2, 0), at("c1", 10, 10), at("c2", 11, 10), at("c3", 10, 11)));
		for (TargetPattern pattern : TargetPattern.values()) {
			List<String> ordered = names(plan(pattern, crowd, 90, 4));
			check(ordered.size() == 4 && new HashSet<>(ordered).size() == 4, pattern + " keeps every mob exactly once");
			check(plan(pattern, new ArrayList<TargetPlanner.Mob<String>>(), 0, 4).order.isEmpty(), pattern + " of nothing is nothing");
			check(plan(pattern, List.of(at("only", 3, 3)), 0, 4).order.size() == 1, pattern + " of one mob is that mob");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
