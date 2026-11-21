package geiler.addons.client.farming;

/** Offline checks for the Garden Plots overlay's menu mapping, commands and hit testing. */
public final class GardenPlotsRulesChecks {
	private GardenPlotsRulesChecks() { }

	public static void run() {
		check(GardenPlotsRules.menuIndex(2) == 0 && GardenPlotsRules.menuIndex(6) == 4 && GardenPlotsRules.menuIndex(11) == 5
			&& GardenPlotsRules.menuIndex(42) == 24, "menu slots in columns 3-7 of five rows map row-major to the 5x5 grid");
		check(GardenPlotsRules.menuIndex(22) == -1, "the barn slot is skipped");
		check(GardenPlotsRules.menuIndex(0) == -1 && GardenPlotsRules.menuIndex(7) == -1 && GardenPlotsRules.menuIndex(45) == -1
			&& GardenPlotsRules.menuIndex(-1) == -1, "border columns and rows outside the grid are not plots");

		check("4".equals(GardenPlotsRules.plotName("§aPlot §b- §e4")), "the plot name follows the first dash, formatting stripped");
		check("12".equals(GardenPlotsRules.plotName("Plot - 12")), "a plain hover name works");
		check(GardenPlotsRules.plotName("Garden Plots") == null && GardenPlotsRules.plotName("Plot - ") == null,
			"names without a dash or a value are rejected");
		check(GardenPlotsRules.plotName("Plot - 1; /op") == null && GardenPlotsRules.plotName("Plot - " + "x".repeat(40)) == null,
			"a name that could carry a second command or is far too long is rejected");

		check(GardenPlotsRules.gridIndexOfPlot(21) == 0 && GardenPlotsRules.gridIndexOfPlot(1) == 7 && GardenPlotsRules.gridIndexOfPlot(0) == 12
			&& GardenPlotsRules.gridIndexOfPlot(24) == 24 && GardenPlotsRules.gridIndexOfPlot(99) == -1,
			"a server plot id lands on the same grid cell as in the Configure Plots menu");

		check("plottp barn".equals(GardenPlotsRules.command(GardenPlotsRules.BARN, "x")) && "plottp 4".equals(GardenPlotsRules.command(3, "4")),
			"plots teleport by name and the barn by its own name");
		check("desk".equals(GardenPlotsRules.buttonCommand(0)) && "warp garden".equals(GardenPlotsRules.buttonCommand(1))
			&& "setspawn".equals(GardenPlotsRules.buttonCommand(2)) && GardenPlotsRules.buttonCommand(3) == null,
			"the three buttons run /desk, /warp garden and /setspawn");

		check(GardenPlotsRules.cellAt(GardenPlotsRules.GRID_X, GardenPlotsRules.GRID_Y) == 0
			&& GardenPlotsRules.cellAt(GardenPlotsRules.GRID_X + 4 * GardenPlotsRules.CELL + 17, GardenPlotsRules.GRID_Y + 4 * GardenPlotsRules.CELL + 17) == 24
			&& GardenPlotsRules.cellAt(GardenPlotsRules.GRID_X - 1, GardenPlotsRules.GRID_Y) == -1
			&& GardenPlotsRules.cellAt(GardenPlotsRules.GRID_X, GardenPlotsRules.GRID_Y + 5 * GardenPlotsRules.CELL) == -1,
			"a point maps to the cell under it and nothing outside the grid");
		check(GardenPlotsRules.buttonAt(GardenPlotsRules.buttonX(0), GardenPlotsRules.BUTTON_Y) == 0
			&& GardenPlotsRules.buttonAt(GardenPlotsRules.buttonX(2) + 19, GardenPlotsRules.BUTTON_Y + 19) == 2
			&& GardenPlotsRules.buttonAt(GardenPlotsRules.buttonX(1) - 1, GardenPlotsRules.BUTTON_Y) == -1
			&& GardenPlotsRules.buttonAt(GardenPlotsRules.buttonX(0), GardenPlotsRules.BUTTON_Y - 1) == -1, "a point maps to the button under it");
		check(GardenPlotsRules.buttonX(0) + GardenPlotsRules.BUTTON <= GardenPlotsRules.buttonX(1)
			&& GardenPlotsRules.buttonX(1) + GardenPlotsRules.BUTTON <= GardenPlotsRules.buttonX(2)
			&& GardenPlotsRules.buttonX(2) + GardenPlotsRules.BUTTON <= GardenPlotsRules.WIDTH, "buttons do not overlap and stay inside the panel");

		check(GardenPlotsRules.originX(100, 176, 400) == 100 + 176 + GardenPlotsRules.SPACING, "the panel sits right of the inventory");
		check(GardenPlotsRules.originX(250, 176, 400) == 250 - GardenPlotsRules.SPACING - GardenPlotsRules.WIDTH,
			"with no room on the right it moves to the left of the inventory");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
