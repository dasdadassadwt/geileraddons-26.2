package geiler.addons.client.macros.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** `/spawnmob` inside a painted area: only on the area's cells, every cell before any is used twice, each mob inside its cell. */
public final class SpawnPlacementChecks {
	private SpawnPlacementChecks() { }

	public static void run() {
		checkDistinctCells();
		checkMoreMobsThanCells();
		checkNoCells();
		checkSlabHeight();
	}

	private static List<int[]> cells(int count) {
		List<int[]> cells = new ArrayList<>();
		for (int i = 0; i < count; i++) cells.add(new int[] {10 + i, 64, -5, 0});
		return cells;
	}

	private static void checkDistinctCells() {
		List<double[]> spots = SpawnPlacement.pick(cells(5), 3, new Random(1));
		check(spots.size() == 3, "as many positions as mobs");
		Set<Integer> used = new HashSet<>();
		for (double[] spot : spots) {
			int cell = (int) Math.floor(spot[0]);
			check(cell >= 10 && cell < 15 && Math.floor(spot[2]) == -5, "each on one of the cells");
			check(used.add(cell), "and no two in the same cell while a free one is left");
			check(spot[1] == 64.0, "at the floor of the cell");
		}
	}

	private static void checkMoreMobsThanCells() {
		List<double[]> spots = SpawnPlacement.pick(cells(2), 8, new Random(2));
		check(spots.size() == 8, "more mobs than cells still get a place each");
		for (double[] spot : spots) {
			int cell = (int) Math.floor(spot[0]);
			check(cell == 10 || cell == 11, "all of them on the cells");
		}
	}

	private static void checkNoCells() {
		check(SpawnPlacement.pick(new ArrayList<>(), 4, new Random(3)).isEmpty(), "no cells: no positions");
		check(SpawnPlacement.pick(cells(3), 0, new Random(3)).isEmpty(), "no mobs: no positions");
	}

	private static void checkSlabHeight() {
		List<int[]> one = new ArrayList<>();
		one.add(new int[] {0, 64, 0, 4});
		check(SpawnPlacement.pick(one, 1, new Random(4)).get(0)[1] == 64.5, "a cell with a slab floor puts the mob on top of the slab");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
