package geiler.addons.client.macros.killer;

import geiler.addons.client.macros.pathing.DijkstraCosts;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Mob selection by the real walk: costs over the map of the ground, the detour guard, the Human order, escalating ignore times. */
public final class SelectionChecks {
	private SelectionChecks() { }

	private static final double INF = Double.POSITIVE_INFINITY;

	public static void run() {
		checkDijkstra();
		checkDetour();
		checkIgnore();
		checkPlannerCost();
		checkHuman();
	}

	private static void checkDijkstra() {
		// 0 -> 1 (1), 1 -> 2 (1), 0 -> 2 (5), 2 -> 3 (1); 4 has no edge in.
		int[] first = {0, 2, 3, 4, 4, 4};
		// Edge order is by node: node 0 has edges 0 and 1 (to 1 and 2), node 1 has edge 2 (to 2), node 2 has edge 3 (to 3).
		int[] to = {1, 2, 2, 3};
		float[] cost = {1f, 5f, 1f, 1f};
		DijkstraCosts dijkstra = new DijkstraCosts();
		float[] all = dijkstra.run(first, to, cost, 0, new int[0], Float.POSITIVE_INFINITY);
		check(all[0] == 0f && all[1] == 1f && all[2] == 2f && all[3] == 3f, "the cheapest way, not the fewest edges: 0,1,2,3");
		check(Float.isInfinite(all[4]), "a place nothing leads to is infinite");
		float[] limited = new DijkstraCosts().run(first, to, cost, 0, new int[0], 2.5f);
		check(limited[2] == 2f && Float.isInfinite(limited[3]), "nothing past the limit is recorded");
		float[] aimed = new DijkstraCosts().run(first, to, cost, 0, new int[] {1}, Float.POSITIVE_INFINITY);
		check(aimed[1] == 1f, "with a target the run may stop once it is settled");
		float[] reuse = dijkstra.run(first, to, cost, 3, new int[0], Float.POSITIVE_INFINITY);
		check(reuse[3] == 0f && Float.isInfinite(reuse[0]), "a second run on the same object starts clean");
	}

	private static void checkDetour() {
		double[] cost = {10.0, 60.0, INF, Double.NaN};
		double[] straight = {8.0, 12.0, 9.0, 20.0};
		boolean[] keep = DetourRule.keep(cost, straight);
		check(keep[0] && !keep[1] && !keep[2] && keep[3], "a near walk stays, a 5 times detour goes, unreachable goes, no map says nothing");
		// All of them detours: the cheapest of the finite ones is kept so something is tried.
		boolean[] all = DetourRule.keep(new double[] {80.0, 60.0, INF}, new double[] {10.0, 10.0, 10.0});
		check(!all[0] && all[1] && !all[2], "when every one is a detour the cheapest is kept");
		boolean[] none = DetourRule.keep(new double[] {INF, INF}, new double[] {5.0, 5.0});
		check(!none[0] && !none[1], "when none can be walked to none is kept: a warp may still do it");
		boolean[] shortWalk = DetourRule.keep(new double[] {20.0}, new double[] {5.0});
		check(shortWalk[0], "a walk under 25 blocks is never a detour, however long next to the straight line");
		boolean[] edge = DetourRule.keep(new double[] {30.0}, new double[] {12.0});
		check(edge[0], "exactly 2.5 times is still fine");
	}

	private static void checkIgnore() {
		check(IgnoreRule.millis(0) == 3000L && IgnoreRule.millis(1) == 8000L && IgnoreRule.millis(2) == 20000L && IgnoreRule.millis(3) == 40000L, "3 s, 8 s, 20 s, 40 s");
		check(IgnoreRule.millis(9) == 40000L && IgnoreRule.millis(-3) == 3000L, "capped, and never negative");
	}

	private static TargetPlanner.Mob<String> at(String name, double x, double z, double cost) {
		return new TargetPlanner.Mob<>(name, x, z, 0.0, cost);
	}

	private static void checkPlannerCost() {
		// Two mobs ahead, one each side: A is 5 away but the way there is 60 blocks, B is 5 away and the way is 5.
		for (TargetPattern pattern : new TargetPattern[] {TargetPattern.NEAREST, TargetPattern.LEAST_TURN, TargetPattern.SWEEP, TargetPattern.CLUSTER, TargetPattern.HUMAN}) {
			List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(at("A", -3, 4, 60.0), at("B", 3, 4, 5.0)));
			check(TargetPlanner.plan(pattern, mobs, 0, 0, 0, new Random(1), null).order.get(0).item.equals("B"), pattern + ": the mob whose walk is short comes first");
		}
		List<TargetPlanner.Mob<String>> same = new ArrayList<>(List.of(at("A", 0, 5, Double.NaN), at("B", 0, 9, Double.NaN)));
		check(TargetPlanner.plan(TargetPattern.HUMAN, same, 0, 0, 0, new Random(1), null).order.get(0).item.equals("A"), "without costs the straight distance decides, as before");
		// A cost under the straight distance is no bonus: the walk is never shorter than the line.
		List<TargetPlanner.Mob<String>> low = new ArrayList<>(List.of(at("A", 0, 5, 1.0), at("B", 0, 9, 9.0)));
		check(TargetPlanner.plan(TargetPattern.HUMAN, low, 0, 0, 0, new Random(1), null).order.get(0).item.equals("A"), "a cost under the line is taken as the line");
	}

	private static void checkHuman() {
		// A mob ahead and one behind at the same walk: the one ahead, the way it is going.
		List<TargetPlanner.Mob<String>> mobs = new ArrayList<>(List.of(at("behind", 0, -8, 8.0), at("ahead", 0, 8, 8.0)));
		check(TargetPlanner.plan(TargetPattern.HUMAN, mobs, 0, 0, 0, new Random(1), null).order.get(0).item.equals("ahead"), "ahead of the heading beats behind it");
		// A crowd of three a little farther than a loner: the crowd.
		List<TargetPlanner.Mob<String>> crowd = new ArrayList<>(List.of(at("loner", 6, 6, 8.5), at("c1", -6, 8, 10.0), at("c2", -7, 8, 10.0), at("c3", -6, 9, 10.0)));
		String firstPick = TargetPlanner.plan(TargetPattern.HUMAN, crowd, 0, 0, 0, new Random(1), null).order.get(0).item;
		check(firstPick.startsWith("c"), "a crowd a little farther is worth the walk (" + firstPick + ")");
		check(TargetPattern.valueOf("HUMAN").getLabel().equals("Human"), "the pattern has its name");
		// Every mob once, whatever the pattern.
		List<TargetPlanner.Mob<String>> many = new ArrayList<>();
		Random random = new Random(3);
		for (int i = 0; i < 30; i++) many.add(at("m" + i, random.nextDouble() * 40 - 20, random.nextDouble() * 40 - 20, random.nextBoolean() ? Double.NaN : 30 + random.nextDouble() * 40));
		TargetPlanner.Plan<String> plan = TargetPlanner.plan(TargetPattern.HUMAN, many, 0, 0, 0, new Random(1), null);
		check(plan.order.size() == 30 && new java.util.HashSet<>(plan.order.stream().map(m -> m.item).toList()).size() == 30, "no mob is lost or doubled");
		check(plan.evaluations <= 1680, "and the work stays bounded");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
