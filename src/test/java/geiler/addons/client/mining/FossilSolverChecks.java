package geiler.addons.client.mining;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline checks for the Fossil Excavator solver. */
public final class FossilSolverChecks {
	private FossilSolverChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		for (FossilSolver.Fossil fossil : FossilSolver.Fossil.values()) {
			require(fossil.getShape().getTiles().size() == fossil.tileCount(), fossil + " counts its tiles");
		}
		require(FossilSolver.Fossil.TUSK.tileCount() == 8 && FossilSolver.Fossil.UGLY.tileCount() == 16, "fossil sizes match their names");

		Set<Integer> all = new HashSet<>();
		for (int slot = 0; slot < 54; slot++) all.add(slot);

		// A fresh board: nothing mined, so the solver opens with its fixed first chisel.
		FossilSolver.Result open = FossilSolver.INSTANCE.solve(Set.of(), all, null, 18);
		require(open.getState() == FossilSolver.State.NEXT && open.getSlot() == 4 + 2 * 9 && open.getPlacements() == 404, "the opening chisel is (4,2)");
		FossilSolver.Result openRisky = FossilSolver.INSTANCE.solve(Set.of(), all, null, 12);
		require(openRisky.getSlot() == open.getSlot(), "both openings start at the same tile");

		// One placement left: seven tusk tiles are found, the eighth is still covered, everything else was empty.
		FossilSolver.Shape placed = FossilSolver.Fossil.TUSK.getShape().moveTo(2, 1);
		FossilSolver.Tile hidden = placed.getTiles().get(3);
		Set<Integer> found = new HashSet<>();
		Set<Integer> covered = new HashSet<>();
		for (FossilSolver.Tile tile : placed.getTiles()) {
			if (tile.equals(hidden)) covered.add(tile.slot());
			else found.add(tile.slot());
		}
		FossilSolver.Result last = FossilSolver.INSTANCE.solve(found, covered, "12.5%", 18);
		require(last.getState() == FossilSolver.State.NEXT && last.getSlot() == hidden.slot(), "the last hidden tile is the next chisel");
		require(last.getChance() == 1.0, "a single placement is certain");
		require(last.getPossible().equals(Set.of(FossilSolver.Fossil.TUSK)), "the progress text names the fossil");

		// Everything of the fossil is revealed.
		Set<Integer> done = new HashSet<>();
		for (FossilSolver.Tile tile : placed.getTiles()) done.add(tile.slot());
		FossilSolver.Result finished = FossilSolver.INSTANCE.solve(done, Set.of(), "12.5%", 18);
		require(finished.getState() == FossilSolver.State.COMPLETED, "a fully revealed fossil is complete");

		// A board no fossil can fit.
		FossilSolver.Result broken = FossilSolver.INSTANCE.solve(Set.of(), Set.of(0), null, 18);
		require(broken.getState() == FossilSolver.State.IMPOSSIBLE, "a board with no possible fossil is impossible");

		// Rotation keeps the tile count and a double flip is the identity.
		FossilSolver.Shape shape = FossilSolver.Fossil.CLAW.getShape();
		require(shape.rotate(90).getTiles().size() == shape.getTiles().size(), "rotating keeps every tile");
		Set<Integer> original = new HashSet<>();
		for (FossilSolver.Tile tile : shape.getTiles()) original.add(tile.slot());
		Set<Integer> flippedTwice = new HashSet<>();
		for (FossilSolver.Tile tile : shape.flip().flip().getTiles()) flippedTwice.add(tile.slot());
		require(original.equals(flippedTwice), "flipping twice is the identity");
		System.out.println("Fossil solver checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
