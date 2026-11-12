package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.odin.DungeonSplitTracker;

import java.util.List;

/** Focused offline checks for the Run Splits server-tick estimate and Time Lost column. */
public final class DungeonRunSplitsServerTickChecks {
	private DungeonRunSplitsServerTickChecks() { }

	public static void run() {
		DungeonRunSplitsModule module = DungeonRunSplitsModule.INSTANCE;
		boolean oldShowSplits = DungeonRunSplitsModule.showSplits.value();
		boolean oldShowCurrent = DungeonRunSplitsModule.showCurrentSplit.value();
		String oldSecondDisplay = DungeonRunSplitsModule.secondTimeDisplay.value();
		try {
			DungeonRunSplitsModule.showSplits.setValue(true);
			DungeonRunSplitsModule.showCurrentSplit.setValue(true);
			module.publishSplitRows(DungeonFloor.F7, List.of(new DungeonSplitTracker.Split(
				"BLOOD", "Blood", 1_200_000_000L, 200L, 50L, 20L, true)));

			DungeonRunSplitsModule.secondTimeDisplay.setValue("CLIENT_TICK_ESTIMATE");
			var serverTickRow = module.buildRows(false).getFirst();
			require(serverTickRow.label().equals("Blood") && serverTickRow.primaryValue().equals("1.20s")
				&& serverTickRow.secondaryValue().equals("1.00s")
				&& DungeonRunSplitsModule.secondTimeDisplay.displayValue().equals("Srv Ticks"),
				"20 accepted server ticks estimate one second independently of 200 client ticks and 50 ping events");

			DungeonRunSplitsModule.secondTimeDisplay.setValue("TIME_LOST");
			var timeLostRow = module.buildRows(false).getFirst();
			require(timeLostRow.primaryValue().equals("1.20s") && timeLostRow.secondaryValue().equals("+0.20s")
				&& timeLostRow.secondaryColor() == 0xFFFF5555,
				"Time Lost compares wall-clock duration against the accepted server-tick estimate");
			require(DungeonRunSplitsModule.timeLostNanos(1_249_000_000L, 20L) == 200_000_000L
				&& DungeonRunSplitsModule.timeLostNanos(900_000_000L, 20L) == 0L
				&& DungeonRunSplitsModule.timeLostNanos(2_000_000_000L, -1L) == 2_000_000_000L,
				"Time Lost floors to 50 ms, clamps negative loss, and ignores invalid negative tick deltas");
			require(DungeonRunSplitsModule.serverTickEstimateNanos(Long.MAX_VALUE) == Long.MAX_VALUE,
				"server-tick conversion saturates instead of overflowing");
		} finally {
			module.clearSnapshots();
			DungeonRunSplitsModule.showSplits.setValue(oldShowSplits);
			DungeonRunSplitsModule.showCurrentSplit.setValue(oldShowCurrent);
			DungeonRunSplitsModule.secondTimeDisplay.setValue(oldSecondDisplay);
		}
	}

	public static void main(String[] args) { run(); }

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
