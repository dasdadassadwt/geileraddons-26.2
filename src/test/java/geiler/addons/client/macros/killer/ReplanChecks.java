package geiler.addons.client.macros.killer;

import geiler.addons.client.macros.pathing.SearchBudget;

import java.util.ArrayList;
import java.util.List;

/** The Round 10 rules: when a moving mob is searched for again, how many candidates the search gets, and how long a slice may run. */
public final class ReplanChecks {
	private ReplanChecks() { }

	public static void run() {
		checkMoveLimit();
		checkNearest();
		checkBudget();
		checkCrowdBonus();
		check(TargetPlanner.REPLAN_GAIN == 0.4, "a new tour has to be 40 percent cheaper to replace the kept one");
	}

	private static void checkMoveLimit() {
		check(ReplanRules.moveLimit(4.0) == 1.0, "a near mob restarts the search after a block");
		check(Math.abs(ReplanRules.moveLimit(20.0) - 2.0) < 1.0E-9, "a mob 20 blocks away has to move 2 blocks");
		check(ReplanRules.moveLimit(500.0) == 3.0, "never more than 3 blocks");
		check(ReplanRules.moveLimit(-5.0) == 1.0 && ReplanRules.moveLimit(Double.NaN) == 1.0, "nonsense falls back to one block");
		check(ReplanRules.moveLimit(10.0) <= ReplanRules.moveLimit(30.0), "farther is never stricter");
	}

	private static void checkNearest() {
		double[] distances = {9.0, 2.0, 30.0, 4.0, 4.0, 1.0};
		int[] three = ReplanRules.nearest(distances, 3);
		check(three.length == 3 && three[0] == 5 && three[1] == 1 && (three[2] == 3 || three[2] == 4), "the three nearest, nearest first");
		check(ReplanRules.nearest(distances, 10).length == 6, "fewer mobs than the pool: all of them");
		check(ReplanRules.nearest(new double[0], 8).length == 0, "no mobs, no indices");
		check(ReplanRules.nearest(distances, 0).length == 0, "a pool of zero is empty");
	}

	private static void checkBudget() {
		check(SearchBudget.scale(false, 0.0) == 1.5, "no route walked: search harder");
		check(SearchBudget.scale(true, 30.0) == 0.6, "a long route walked: search gently");
		check(SearchBudget.scale(true, 8.0) == 1.0 && SearchBudget.scale(true, 3.0) == 1.0, "a short route left: the plain budget");
		check(SearchBudget.scale(true, Double.NaN) == 1.0, "nonsense is the plain budget");
	}

	private static TargetPlanner.Mob<String> at(String name, double x, double z) {
		return new TargetPlanner.Mob<>(name, x, z);
	}

	private static void checkCrowdBonus() {
		// A pair at 10 blocks against a loner 0.8 blocks nearer: the pair wins, the first kill is followed by a second at once.
		List<TargetPlanner.Mob<String>> pair = new ArrayList<>(List.of(at("loner", -9.2, 0), at("p1", 0, 10), at("p2", 1, 10)));
		check(TargetPlanner.nearestFirst(pair, 0, 0, 0, 1).get(0).item.startsWith("p"), "a mob with a neighbour beats a loner 0.8 nearer");
		// 4 blocks nearer: the loner still wins.
		List<TargetPlanner.Mob<String>> far = new ArrayList<>(List.of(at("loner", -6, 0), at("p1", 0, 10), at("p2", 1, 10)));
		check(TargetPlanner.nearestFirst(far, 0, 0, 0, 1).get(0).item.equals("loner"), "a loner 4 nearer still wins");
		// The bonus is capped: a crowd of six is worth no more than three neighbours.
		List<TargetPlanner.Mob<String>> crowd = new ArrayList<>();
		for (int i = 0; i < 6; i++) crowd.add(at("c" + i, i * 0.3, 20));
		crowd.add(at("loner", 0, 15.5));
		check(TargetPlanner.nearestFirst(crowd, 0, 0, 0, 1).get(0).item.equals("loner"), "the crowd bonus is capped at three neighbours");
		check(TargetPlanner.nearestFirst(crowd, 0, 0, 0, 3).size() == 3, "asked for three, three come back");

		// The plan's first mob agrees with the helper for the nearest pattern.
		check(TargetPlanner.plan(TargetPattern.NEAREST, pair, 0, 0, 0, new java.util.Random(1), null).order.get(0).item.startsWith("p"),
			"the nearest pattern takes the pair first too");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
