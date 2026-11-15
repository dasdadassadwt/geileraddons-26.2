package geiler.addons.client.macros.killer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * How the Area Killer picks the next mob in situations the player meets: written down as scenarios, each with the choice a careful
 * player would make. The player stands at 0, 0 looking along +z (yaw 0) with the Human pattern and a 70 degree view cone, as the macro's
 * defaults do. The scenarios are in docs/project/area-killer-scenarios.md.
 */
public final class TargetScenarioChecks {
	private TargetScenarioChecks() { }

	public static void run() {
		neighbourRightAfterAKill();
		mobBehindAWall();
		mobsOnAPlatformAbove();
		ringOfMobs();
		lineOfMobs();
		oneFarManyNear();
		twoSegments();
		mobBehindThePlayer();
		everyPatternCoversEveryMob();
	}

	private static TargetPlanner.Mob<String> mob(String name, double x, double z) {
		return new TargetPlanner.Mob<>(name, x, z);
	}

	private static TargetPlanner.Mob<String> mob(String name, double x, double z, double y, double walk, int segment) {
		return new TargetPlanner.Mob<>(name, x, z, y, walk, segment);
	}

	private static List<String> order(TargetPattern pattern, List<TargetPlanner.Mob<String>> mobs, double yaw, int segmentNow) {
		List<String> names = new ArrayList<>();
		for (TargetPlanner.Mob<String> m : TargetPlanner.plan(pattern, mobs, 0.0, 0.0, yaw, new Random(1), null, 70.0, 0.0, segmentNow).order) names.add(m.item);
		return names;
	}

	private static List<String> human(List<TargetPlanner.Mob<String>> mobs) {
		return order(TargetPattern.HUMAN, mobs, 0.0, -1);
	}

	/** The mob right beside the one just killed is next, not one across the room. */
	private static void neighbourRightAfterAKill() {
		List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(mob("neighbour", 2, 4), mob("next to it", 3, 5), mob("across the room", -12, 14)));
		List<String> got = human(mobs);
		check(got.get(0).equals("neighbour") && got.get(1).equals("next to it") && got.get(2).equals("across the room"),
			"neighbour first, then the one next to it, then the far one: " + got);
	}

	/** A mob close by across a wall (a long walk) waits for one that is close and open. */
	private static void mobBehindAWall() {
		List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(mob("behind a wall", 0, 6, 0, 60, -1), mob("open", 6, 3, 0, 7, -1)));
		check(human(mobs).get(0).equals("open"), "the mob that can be walked to goes before the one behind a wall");
	}

	/** A mob up on a platform is not gone to while there are mobs on the player's level: the macro filters by level before it plans. */
	private static void mobsOnAPlatformAbove() {
		java.util.List<Integer> kept = ClusterRules.onLevel(0.0, java.util.List.of(6.0, 0.0, 0.5));
		check(kept.equals(java.util.List.of(1, 2)), "the platform mob waits while two are on this level: " + kept);
		List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(mob("on the platform", 0, 5, 14, Double.NaN, -1), mob("flat", 4, 10, 0, Double.NaN, -1)));
		check(human(mobs).get(0).equals("flat"), "and a mob fourteen blocks up is last even to the planner: the climb is too dear");
	}

	/** Six mobs on a ring round the player: the order goes round, never across. */
	private static void ringOfMobs() {
		List<TargetPlanner.Mob<String>> mobs = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			double angle = Math.toRadians(60.0 * i);
			mobs.add(mob("m" + i, Math.sin(angle) * 8, Math.cos(angle) * 8));
		}
		List<String> got = human(mobs);
		check(got.size() == 6, "all six are in the order");
		// Consecutive mobs of the first four are neighbours on the ring (index distance 1 or 5 of 6).
		for (int i = 1; i < 4; i++) {
			int a = Integer.parseInt(got.get(i - 1).substring(1));
			int b = Integer.parseInt(got.get(i).substring(1));
			int gap = Math.abs(a - b);
			check(gap == 1 || gap == 5, "the ring is worked round, not across: " + got);
		}
	}

	/** A line of mobs along the way ahead is worked from the near end to the far end. */
	private static void lineOfMobs() {
		List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(mob("z16", 0, 16), mob("z4", 0, 4), mob("z12", 0, 12), mob("z8", 0, 8)));
		check(human(mobs).equals(List.of("z4", "z8", "z12", "z16")), "a line ahead goes near to far");
	}

	/** One mob far off and a few close: the far one is last. */
	private static void oneFarManyNear() {
		List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(mob("far", 5, 40), mob("a", 3, 8), mob("b", 4, 10), mob("c", 2, 12), mob("d", 5, 9)));
		List<String> got = human(mobs);
		check(got.get(got.size() - 1).equals("far"), "the far mob is last: " + got);
	}

	/** Two stretches of ground: the one the player is on is cleared before the other is gone to. */
	private static void twoSegments() {
		List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(
			mob("here 1", 0, 10, 0, Double.NaN, 0), mob("here 2", 3, 13, 0, Double.NaN, 0), mob("there", -9, 4, 0, Double.NaN, 1)));
		List<String> got = order(TargetPattern.HUMAN, mobs, 0.0, 0);
		check(got.get(got.size() - 1).equals("there"), "the other segment waits while this one has mobs: " + got);
		List<TargetPlanner.Mob<String>> alone = new ArrayList<>(List.of(mob("there", -9, 4, 0, Double.NaN, 1), mob("here 1", 0, 10, 0, Double.NaN, 0)));
		check(order(TargetPattern.HUMAN, alone, 0.0, 1).get(0).equals("there"), "and a segment that is the player's own with one mob is cleared first");
		check(TargetPlanner.nearestFirst(mobs, 0.0, 0.0, 0.0, 1, 0).get(0).item.startsWith("here"), "the three searched are of the own segment first");
	}

	/** Mobs behind the player wait while one is in the view; they are not forgotten. */
	private static void mobBehindThePlayer() {
		List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(mob("behind", 0, -7), mob("ahead", 3, 14)));
		List<String> got = human(mobs);
		check(got.get(0).equals("ahead") && got.get(1).equals("behind"), "the mob in view first, the one behind after it: " + got);
	}

	private static void everyPatternCoversEveryMob() {
		for (TargetPattern pattern : TargetPattern.values()) {
			List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(mob("a", 0, 5), mob("b", 4, 8), mob("c", -4, 9), mob("d", 10, -3), mob("e", -2, -9)));
			check(order(pattern, mobs, 0.0, -1).size() == 5, pattern + " keeps every mob");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
