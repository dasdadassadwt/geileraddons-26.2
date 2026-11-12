package geiler.addons.client.dungeon;

/**
 * The start-door selector locks on the first loaded player position. On a fresh dungeon world that
 * position is the pre-teleport default (0,100,0), outside the grid, and it was never replaced, so the
 * start door was never found. Only in-grid positions may become the spawn anchor.
 */
public final class DungeonSpawnAnchorChecks {
	private DungeonSpawnAnchorChecks() { }

	public static void run() {
		check(!DungeonDoorTrackerLogic.isUsableSpawnAnchor(0, 0), "the pre-teleport default position is not a spawn anchor");
		check(DungeonDoorTrackerLogic.isUsableSpawnAnchor(-121, -21), "the logged working spawn is usable");
		for (int cellX = 0; cellX < 6; cellX++) {
			for (int cellZ = 0; cellZ < 6; cellZ++) {
				int x = DungeonGridRules.GRID_MIN + cellX * DungeonGridRules.CELL_STRIDE + 15;
				int z = DungeonGridRules.GRID_MIN + cellZ * DungeonGridRules.CELL_STRIDE + 15;
				check(DungeonDoorTrackerLogic.isUsableSpawnAnchor(x, z), "every cell centre is usable: " + x + "," + z);
			}
		}
		check(!DungeonDoorTrackerLogic.isUsableSpawnAnchor(500, 500), "positions far outside the grid are rejected");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
