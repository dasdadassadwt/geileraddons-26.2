package geiler.addons.client.misc;

import geiler.addons.client.location.Island;

/** Offline checks for the Speed Display text and the Ghost Vision detection rules. */
public final class SpeedGhostChecks {
	private SpeedGhostChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		require(SpeedRules.speed(0.4) == 400 && SpeedRules.speed(0.1) == 100 && SpeedRules.speed(0.0) == 0
			&& SpeedRules.speed(0.4004) == 400 && SpeedRules.speed(0.4006) == 401 && SpeedRules.speed(5.0) == 5000,
			"the base movement speed is shown times 1000, rounded");
		require("§f400✦".equals(SpeedRules.text(400)) && "§f0✦".equals(SpeedRules.text(0)),
			"the speed text is white with the speed star");
		require(SpeedRules.text(SpeedRules.speed(0.4)).equals(SpeedRules.PREVIEW), "preview matches a 400 speed");
		require(SpeedRules.PREVIEW.contains("400"), "the editor preview shows a sample speed");

		require(GhostRules.isGhost(Island.DWARVEN_MINES, true, 80.0), "a low creeper in the Dwarven Mines is a ghost");
		require(!GhostRules.isGhost(Island.DWARVEN_MINES, true, 100.0) && !GhostRules.isGhost(Island.DWARVEN_MINES, true, 150.0),
			"a creeper at Y 100 or higher is not a ghost");
		require(!GhostRules.isGhost(Island.HUB, true, 50.0) && !GhostRules.isGhost(Island.NONE, true, 50.0),
			"creepers outside the Dwarven Mines are not ghosts");
		require(!GhostRules.isGhost(Island.DWARVEN_MINES, false, 50.0), "only creepers can be ghosts");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
