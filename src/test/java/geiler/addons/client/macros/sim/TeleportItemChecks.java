package geiler.addons.client.macros.sim;

import java.util.Arrays;

/** Which item teleports, how far, and which of a hotbar's the macro should take. */
public final class TeleportItemChecks {
	private TeleportItemChecks() { }

	public static void run() {
		TeleportItem.Info aotv = TeleportItem.classify("ASPECT_OF_THE_VOID", 0, 4);
		check(aotv != null && aotv.instant && !aotv.ether && aotv.instantReach == 12.0, "a tuned Aspect of the Void hops 12");
		TeleportItem.Info merged = TeleportItem.classify("ASPECT_OF_THE_VOID", 1, 2);
		check(merged != null && merged.instant && merged.ether && merged.instantReach == 10.0 && merged.etherReach == 59.0, "with ethermerge both: 10 and 59");
		TeleportItem.Info conduit = TeleportItem.classify("ETHERWARP_CONDUIT", 0, 0);
		check(conduit != null && conduit.ether && conduit.conduit && !conduit.instant, "the conduit only warps");
		check(TeleportItem.classify("HYPERION", 0, 0) == null, "a sword does not teleport");
		check(TeleportItem.classify(null, 0, 0) == null, "no id, no teleport");
		TeleportItem.Info sim = TeleportItem.simulated(4);
		check(sim.instant && sim.ether && sim.instantReach == 12.0 && sim.etherReach == EtherwarpMath.range(4), "the sim's shovel does both");
		int best = TeleportItem.best(Arrays.asList(null, conduit, aotv, merged, aotv));
		check(best == 3, "the one that does both is taken (" + best + ")");
		check(TeleportItem.best(Arrays.asList(aotv, null, aotv)) == 0, "the lowest slot wins a tie");
		check(TeleportItem.best(Arrays.asList(null, null)) == -1, "none: -1");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
