package geiler.addons.client.macros.killer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Standing to fight a crowd, and killing what is on the way before a teleport skips it. */
public final class ClusterChecks {
	private ClusterChecks() { }

	public static void run() {
		check(!ClusterRules.standing(1, false), "one mob near: walk to it");
		check(ClusterRules.standing(2, false), "two near: stand and fight");
		check(ClusterRules.standing(1, true), "a crowd down to one is finished where it is");
		check(!ClusterRules.standing(0, true), "none left: walk on");

		List<double[]> tight = Arrays.asList(new double[] {0, 0, 2}, new double[] {1.5, 0, 2.5});
		check(ClusterRules.crowdSize(tight, Arrays.asList(true, false)) == 2, "two mobs a step apart, one in reach: a crowd of two");
		List<double[]> loose = Arrays.asList(new double[] {0, 0, 2}, new double[] {5, 0, 2});
		check(ClusterRules.crowdSize(loose, Arrays.asList(true, true)) == 1, "five blocks apart is not a crowd");
		check(ClusterRules.crowdSize(tight, Arrays.asList(false, false)) == 0, "nothing in reach: no crowd to stand in front of");
		check(!ClusterRules.giveUp(9) && ClusterRules.giveUp(10), "a stand with nothing to hit ends after ten ticks");

		List<double[]> stretch = Arrays.asList(new double[] {0, 0, 0}, new double[] {4, 0, 0}, new double[] {8, 0, 0});
		check(ClusterRules.mobsOnStretch(stretch, Collections.singletonList(new double[] {4, 1, 5})), "a mob five blocks to the side is on the way");
		check(!ClusterRules.mobsOnStretch(stretch, Collections.singletonList(new double[] {4, 0, 9})), "nine blocks off is not");
		check(!ClusterRules.mobsOnStretch(stretch, Collections.singletonList(new double[] {4, 6, 1})), "a mob six blocks up on a balcony is not");
		check(!ClusterRules.mobsOnStretch(stretch, Collections.emptyList()), "no mobs, nothing on the way");

		check(ClusterRules.onLevel(64.0, Arrays.asList(65.0, 71.0, 63.0)).equals(Arrays.asList(0, 2)), "mobs on the floor first, the balcony waits");
		check(ClusterRules.onLevel(64.0, Arrays.asList(71.0, 80.0)).equals(Arrays.asList(0, 1)), "nothing on the floor: all of them");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
