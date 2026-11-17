package geiler.addons.client.fishing;

import geiler.addons.client.location.Island;

import java.util.HashMap;
import java.util.Map;

/** Offline checks for when Lava Replacement renders lava as water, and for the model hook never re-entering itself. */
public final class LavaReplacementChecks {
	private LavaReplacementChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkIslandRules();
		checkModelDecision();
		checkHookDoesNotRecurse();
		System.out.println("Lava replacement checks passed");
	}

	private static void checkIslandRules() {
		boolean everywhereOff = false;
		check(!LavaReplacementRules.shouldReplace(false, Island.NONE, true, true, true, true),
			"lava is never replaced off SkyBlock, even with every switch on");
		check(LavaReplacementRules.shouldReplace(true, Island.HUB, true, false, false, false),
			"everywhere replaces lava on any SkyBlock island");
		check(LavaReplacementRules.shouldReplace(true, Island.CRIMSON_ISLE, everywhereOff, true, false, false),
			"the Crimson Isle switch replaces lava there");
		check(!LavaReplacementRules.shouldReplace(true, Island.CRIMSON_ISLE, everywhereOff, false, true, true),
			"the Crimson Isle stays off when only the other islands are on");
		check(LavaReplacementRules.shouldReplace(true, Island.KUUDRA, everywhereOff, false, true, false),
			"the Kuudra switch replaces lava in Kuudra");
		check(LavaReplacementRules.shouldReplace(true, Island.CATACOMBS, everywhereOff, false, false, true),
			"the Dungeons switch replaces lava in the Catacombs");
		check(!LavaReplacementRules.shouldReplace(true, Island.HUB, everywhereOff, true, true, true),
			"an island with no switch keeps its lava");
	}

	private static void checkModelDecision() {
		check(!LavaReplacementRules.useWater(false, true, true), "a non-lava state is untouched");
		check(!LavaReplacementRules.useWater(true, false, true), "lava with the module off is untouched");
		check(!LavaReplacementRules.useWater(true, true, false), "lava with no water model to borrow is untouched");
		check(LavaReplacementRules.useWater(true, true, true), "lava with the module on takes the water model");
	}

	/** A stand-in model set whose lookup runs the same hook shape the mixin uses: it reads the map, never get(). */
	private static final class FakeModelSet {
		final Map<String, String> byFluid = new HashMap<>();
		final boolean replacing;
		int depth;
		int maxDepth;

		FakeModelSet(boolean replacing) {
			this.replacing = replacing;
			byFluid.put("water", "water-model");
			byFluid.put("lava", "lava-model");
		}

		String get(String fluid) {
			depth++;
			maxDepth = Math.max(maxDepth, depth);
			try {
				if (depth > 4) throw new StackOverflowError("get re-entered itself");
				String original = byFluid.get(fluid);
				boolean lava = fluid.equals("lava");
				String water = byFluid.get("water");
				return LavaReplacementRules.useWater(lava, replacing, water != null) ? water + "-opaque" : original;
			} finally {
				depth--;
			}
		}
	}

	private static void checkHookDoesNotRecurse() {
		FakeModelSet on = new FakeModelSet(true);
		check("water-model-opaque".equals(on.get("lava")), "lava with the module on returns the opaque water model");
		check("water-model".equals(on.get("water")), "water itself is never replaced");
		check(on.maxDepth == 1, "the hook never calls get() again, so lookups nest one level deep");
		FakeModelSet off = new FakeModelSet(false);
		check("lava-model".equals(off.get("lava")) && off.maxDepth == 1, "lava with the module off is returned as is");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
