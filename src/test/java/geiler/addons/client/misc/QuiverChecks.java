package geiler.addons.client.misc;

import java.util.List;

/** Offline checks for the Quiver Display lore parsing and text. */
public final class QuiverChecks {
	private QuiverChecks() { }

	public static void run() {
		require(Integer.valueOf(1234).equals(QuiverRules.arrowsRemaining(List.of("§7Stats", "Arrows Remaining: 1,234")))
			&& Integer.valueOf(0).equals(QuiverRules.arrowsRemaining(List.of("Arrows Remaining: 0"))),
			"the count is read from the Arrows Remaining line, commas removed");
		require(Integer.valueOf(5).equals(QuiverRules.arrowsRemaining(List.of("§7Arrows Remaining: §f5"))),
			"formatting codes are ignored");
		require(QuiverRules.arrowsRemaining(List.of("Arrows Remaining: lots", "Arrows: 3")) == null
			&& QuiverRules.arrowsRemaining(List.of()) == null, "an item without a number has no count");
		require(Integer.valueOf(7).equals(QuiverRules.arrowsRemaining(List.of("Arrows Remaining: 7", "Arrows Remaining: 9"))),
			"the first matching line wins");
		require(QuiverRules.arrowsRemaining(List.of("Arrows Remaining: 99999999999")) == null,
			"a number too large for an int is rejected instead of overflowing");
		require("§fFlint Arrow §a1,234".equals(QuiverRules.text("Flint Arrow", 1234)), "the line is the name then the green count");
		require("§fFlint Arrow §a0".equals(QuiverRules.text("Flint Arrow", 0)), "an empty quiver shows 0");
		require(QuiverRules.isArrowItem("minecraft:arrow") && QuiverRules.isArrowItem("minecraft:feather")
			&& !QuiverRules.isArrowItem("minecraft:bow"), "only arrows and feathers hold the count");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError("QuiverChecks: " + message);
	}
}
