package geiler.addons.client.dungeon.helpers;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonGridRules;

/** Focused acceptance checks for exact-room AUTO solver activation. */
public final class DungeonPuzzleAutoSelectionChecks {
	private DungeonPuzzleAutoSelectionChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		check("WATER_BOARD".equals(DungeonPuzzleAutoSelectionRules.selectedMode("Water Board", true)),
			"AUTO recognizes the exact Water Board room");
		check("TP_MAZE".equals(DungeonPuzzleAutoSelectionRules.selectedMode("Teleport Maze", true)),
			"AUTO recognizes Teleport Maze without a global mode selector");
		check(DungeonPuzzleAutoSelectionRules.solverActiveForRoom("Teleport Maze", "TP_MAZE", true)
			&& !DungeonPuzzleAutoSelectionRules.solverActiveForRoom("Teleport Maze", "TP_MAZE", false)
			&& !DungeonPuzzleAutoSelectionRules.solverActiveForRoom("Boulder", "TP_MAZE", true),
			"TP Maze correction and render gates use exact room identity and the solver toggle before HUD guidance exists");
		check("ICE_FILL".equals(DungeonPuzzleAutoSelectionRules.selectedMode("Ice Fill", true)),
			"AUTO recognizes Ice Fill");
		check("BLAZE".equals(DungeonPuzzleAutoSelectionRules.selectedMode("Lower Blaze", true))
			&& "BLAZE".equals(DungeonPuzzleAutoSelectionRules.selectedMode("Higher Blaze", false)),
			"AUTO recognizes both Blaze room variants without a position selector");
		check("CREEPER_BEAMS".equals(
			DungeonPuzzleAutoSelectionRules.selectedMode("Creeper Beams", false)),
			"AUTO recognizes Creeper Beams independently of its removed legacy toggle");
		check("THREE_WEIRDOS".equals(DungeonPuzzleAutoSelectionRules.selectedMode("Three Weirdos", true)),
			"AUTO recognizes Three Weirdos");
		check("QUIZ".equals(DungeonPuzzleAutoSelectionRules.selectedMode("Quiz", true)),
			"AUTO recognizes Quiz");
		check("BOULDER".equals(DungeonPuzzleAutoSelectionRules.selectedMode("Boulder", true)),
			"AUTO recognizes Boulder");
		check("TIC_TAC_TOE".equals(DungeonPuzzleAutoSelectionRules.selectedMode("Tic Tac Toe", true))
			&& DungeonPuzzleAutoSelectionRules.solverActiveForRoom("Tic Tac Toe", "TIC_TAC_TOE", true)
			&& !DungeonPuzzleAutoSelectionRules.solverActiveForRoom("Tic Tac Toe", "TIC_TAC_TOE", false)
			&& !DungeonPuzzleAutoSelectionRules.solverActiveForRoom("Tic Tac Toe", "BOULDER", true),
			"Tic Tac Toe uses its exact room and independent toggle gate");
		check(DungeonPuzzleAutoSelectionRules.selectedMode("Creeper beam", true) == null
			&& DungeonPuzzleAutoSelectionRules.selectedMode("Unknown room", true) == null,
			"near-matching and unknown room names do not activate a solver");
		var mapCell = new DungeonGridRules.Cell(4, 2, -72, -136);
		check("F7|puzzle-map-signature|4,2".equals(DungeonRoomIdentity.mapIdentity(
			DungeonFloor.F7, "puzzle-map-signature", mapCell)),
			"the map-confirmed Puzzle cell has a stable identity before its catalog room name is available");
		check("unknown||4,2".equals(DungeonRoomIdentity.mapIdentity(null, null, mapCell))
			&& DungeonRoomIdentity.mapIdentity(DungeonFloor.F7, "signature", null) == null,
			"map identity tolerates missing optional metadata but never invents a cell");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
