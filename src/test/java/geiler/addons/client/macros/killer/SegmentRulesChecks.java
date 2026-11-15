package geiler.addons.client.macros.killer;

/** Mobs are split into the stretches of ground they stand on: a gap, a wall or another floor cuts one from the next. */
public final class SegmentRulesChecks {
	private SegmentRulesChecks() { }

	public static void run() {
		checkNearMobsShareASegment();
		checkAGapSplits();
		checkAnotherFloorSplits();
		checkAWallThatTheWalkShows();
		checkNoWayIsItsOwnSegment();
		checkChainsLink();
	}

	private static int[] cluster(double[] x, double[] y, double[] z, double[] walks) {
		return SegmentRules.cluster(x, y, z, walks);
	}

	private static void checkNearMobsShareASegment() {
		int[] ids = cluster(new double[] {0, 3, 5}, new double[] {64, 64, 64}, new double[] {0, 2, 4}, null);
		check(ids[0] == ids[1] && ids[1] == ids[2], "mobs a few blocks apart on one floor are one segment");
	}

	private static void checkAGapSplits() {
		int[] ids = cluster(new double[] {0, 2, 30}, new double[] {64, 64, 64}, new double[] {0, 0, 0}, null);
		check(ids[0] == ids[1] && ids[2] != ids[0], "a group 30 blocks off is another segment");
	}

	private static void checkAnotherFloorSplits() {
		int[] ids = cluster(new double[] {0, 1}, new double[] {64, 70}, new double[] {0, 0}, null);
		check(ids[0] != ids[1], "mobs six blocks apart in height are on different floors");
	}

	private static void checkAWallThatTheWalkShows() {
		// Four blocks apart across a ravine: the walk to one is 12, to the other 55.
		int[] ids = cluster(new double[] {0, 4}, new double[] {64, 64}, new double[] {5, 5}, new double[] {12, 55});
		check(ids[0] != ids[1], "a short line with a long walk between is a ravine, so two segments");
		int[] same = cluster(new double[] {0, 4}, new double[] {64, 64}, new double[] {5, 5}, new double[] {12, 15});
		check(same[0] == same[1], "and a short line with walks that agree is one");
	}

	private static void checkNoWayIsItsOwnSegment() {
		int[] ids = cluster(new double[] {0, 2}, new double[] {64, 64}, new double[] {5, 5}, new double[] {10, Double.POSITIVE_INFINITY});
		check(ids[0] != ids[1], "a mob with no way to it is not on the segment of one that has");
	}

	private static void checkChainsLink() {
		int[] ids = cluster(new double[] {0, 9, 18, 27}, new double[] {64, 64, 64, 64}, new double[] {0, 0, 0, 0}, null);
		check(ids[0] == ids[3], "a row of mobs nine blocks apart is one segment through the links");
		check(ids[0] == 0, "and the first mob's segment is number 0");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
