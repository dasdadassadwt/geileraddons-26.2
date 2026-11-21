package geiler.addons.client.dungeon.helpers;

/** The suggested O square must stay until a new best move is found, not flash between scans. */
public final class DungeonTicTacToeSuggestionHoldChecks {
	private DungeonTicTacToeSuggestionHoldChecks() { }

	public static void run() {
		DungeonTicTacToeSuggestionHold<String> hold = new DungeonTicTacToeSuggestionHold<>();
		check(hold.current() == null, "nothing is shown before the first suggestion");
		hold.suggest("row 2 col 2");
		hold.scanWithoutSuggestion();
		hold.scanWithoutSuggestion();
		check("row 2 col 2".equals(hold.current()), "scans without a suggestion (X's turn, unloaded chunk) keep the marker");
		hold.suggest("row 1 col 3");
		check("row 1 col 3".equals(hold.current()), "a new best move replaces the held one");
		hold.clear();
		check(hold.current() == null, "finishing the game or leaving the room clears the marker");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
