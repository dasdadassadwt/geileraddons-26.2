package geiler.addons.client.location;

import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Offline state and stale-event checks for the Hypixel Mod API island tracker. */
public final class HypixelModApiStateChecks {
	private HypixelModApiStateChecks() { }

	public static void run() {
		checkIslandContract();
		checkSafariBiomeContract();
		HypixelIslandState state = new HypixelIslandState();
		long firstEpoch = state.epoch();
		check(state.currentIsland() == Island.NONE && !state.hasLocation(),
			"the initial state has no location");

		state.applyLocation(firstEpoch, true, "sKyBlOcK", "garden");
		check(state.currentIsland() == Island.GARDEN && state.hasLocation(),
			"a SkyBlock location event resolves its known mode case-insensitively");

		state.applyLocation(state.epoch(), true, null, "garden");
		check(state.currentIsland() == Island.OTHER && state.hasLocation(),
			"a missing server type is not treated as proof of SkyBlock");

		state.applyLocation(state.epoch(), true, "LOBBY", "garden");
		check(state.currentIsland() == Island.OTHER && state.hasLocation(),
			"a non-SkyBlock event is a named other location");

		long staleEpoch = state.epoch();
		state.reset();
		check(state.epoch() == staleEpoch + 1 && state.currentIsland() == Island.NONE && !state.hasLocation(),
			"reset advances the connection epoch and clears the location");
		state.applyLocation(staleEpoch, true, "SKYBLOCK", "garden");
		check(state.currentIsland() == Island.NONE && !state.hasLocation(),
			"a queued event from an earlier connection is discarded");

		state.applyLocation(state.epoch(), false, "SKYBLOCK", "garden");
		check(state.currentIsland() == Island.NONE && !state.hasLocation(),
			"a queued event is discarded after island detection is disabled");
	}

	private static void checkIslandContract() {
		check(Arrays.equals(Island.values(), new Island[] {
			Island.PRIVATE_ISLAND, Island.HUB, Island.DARK_AUCTION, Island.WINTER,
			Island.FARMING_ISLANDS, Island.GARDEN,
			Island.GOLD_MINE, Island.DEEP_CAVERNS, Island.DWARVEN_MINES, Island.CRYSTAL_HOLLOWS, Island.MINESHAFT,
			Island.BACKWATER_BAYOU, Island.LOTUS_ATOLL,
			Island.THE_PARK, Island.GALATEA, Island.TORRHUS_CANYON,
			Island.SPIDERS_DEN, Island.THE_END, Island.CRIMSON_ISLE,
			Island.DUNGEON_HUB, Island.CATACOMBS, Island.KUUDRA,
			Island.THE_RIFT, Island.SAFARI, Island.OTHER, Island.NONE,
		}), "island names and iteration order remain stable for filters and saved data");
		check(Island.fromMode("DYNAMIC") == Island.PRIVATE_ISLAND
			&& Island.fromMode("garden") == Island.GARDEN
			&& Island.fromMode("foraging_3") == Island.TORRHUS_CANYON,
			"known API modes resolve case-insensitively, including Torrhus Canyon's real mode");
		check(Island.fromMode(null) == Island.OTHER && Island.fromMode("") == Island.OTHER
			&& Island.fromMode("unknown") == Island.OTHER && !Island.OTHER.selectable() && !Island.NONE.selectable()
			&& Island.GARDEN.selectable(), "unknown modes fail closed and no-answer states are not selectable");
		check("Private Island".equals(Island.PRIVATE_ISLAND.label())
			&& "your Private Island".equals(Island.PRIVATE_ISLAND.displayName())
			&& "Jerry's Workshop".equals(Island.WINTER.displayName()),
			"list labels and idle explanation names retain their separate values");
		try {
			var label = Island.class.getMethod("label");
			var displayName = Island.class.getMethod("displayName");
			var selectable = Island.class.getMethod("selectable");
			var fromMode = Island.class.getMethod("fromMode", String.class);
			check(label.getReturnType() == String.class && Modifier.isPublic(label.getModifiers())
				&& !Modifier.isStatic(label.getModifiers()), "label remains a public instance Java method");
			check(displayName.getReturnType() == String.class && Modifier.isPublic(displayName.getModifiers())
				&& !Modifier.isStatic(displayName.getModifiers()),
				"displayName remains a public instance Java method");
			check(selectable.getReturnType() == boolean.class && Modifier.isPublic(selectable.getModifiers())
				&& !Modifier.isStatic(selectable.getModifiers()), "selectable remains a public instance Java method");
			check(fromMode.getReturnType() == Island.class && Modifier.isPublic(fromMode.getModifiers())
				&& Modifier.isStatic(fromMode.getModifiers()), "fromMode remains a public static Java method");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("Island Java caller ABI changed", exception);
		}
	}

	private static void checkSafariBiomeContract() {
		check(Arrays.equals(SafariBiome.values(), new SafariBiome[] {
			SafariBiome.FOREST, SafariBiome.CAVERN, SafariBiome.ICY, SafariBiome.HAUNTED,
		}), "Safari biome identities and iteration order remain stable");
		check("Forest".equals(SafariBiome.FOREST.displayName())
			&& "Cavern".equals(SafariBiome.CAVERN.displayName())
			&& "Icy".equals(SafariBiome.ICY.displayName())
			&& "Haunted".equals(SafariBiome.HAUNTED.displayName()),
			"Safari biome labels remain unchanged");
		SafariBiome.tick();
		check(SafariBiome.current() == null,
			"off-Safari tick clears the cached biome before any Minecraft world read");
		try {
			var displayName = SafariBiome.class.getMethod("displayName");
			var current = SafariBiome.class.getMethod("current");
			var tick = SafariBiome.class.getMethod("tick");
			check(displayName.getReturnType() == String.class && Modifier.isPublic(displayName.getModifiers())
				&& !Modifier.isStatic(displayName.getModifiers()), "displayName remains a public instance Java method");
			check(current.getReturnType() == SafariBiome.class && Modifier.isPublic(current.getModifiers())
				&& Modifier.isStatic(current.getModifiers()), "current remains a public static Java method");
			check(tick.getReturnType() == void.class && Modifier.isPublic(tick.getModifiers())
				&& Modifier.isStatic(tick.getModifiers()), "tick remains a public static Java method");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("SafariBiome Java caller ABI changed", exception);
		}
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
