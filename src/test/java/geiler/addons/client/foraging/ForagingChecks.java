package geiler.addons.client.foraging;

import java.util.List;
import java.util.Map;

/** Offline checks for the pure Foraging rules (timer, highlights, temple puzzles, tuner, sweep). */
public final class ForagingChecks {
	private ForagingChecks() {
	}

	public static void run() {
		checkContestTimer();
		checkHighlightRules();
		checkTemplePuzzles();
		checkTuner();
		checkSweep();
		System.out.println("Foraging checks passed");
	}

	private static void checkContestTimer() {
		long epochMs = ForagingRules.SKYBLOCK_EPOCH_SECONDS * 1000L;
		assertEq(1200, ForagingRules.secondsToNextDay(epochMs), "exact day start waits a full day");
		assertEq(1, ForagingRules.secondsToNextDay(epochMs + 1199_000L), "last second of a day");
		assertEq(600, ForagingRules.secondsToNextDay(epochMs + 5 * 1200_000L + 600_000L), "mid-day");
		assertEq(1200, ForagingRules.secondsToNextDay(epochMs - 1L + 1L), "epoch itself");
		assertEq(1, ForagingRules.secondsToNextDay(epochMs - 1_000L), "before the epoch still counts down");
		// Hypixel wiki: SkyBlock days start at :55, :15 and :35 past the hour (epoch 1560275700).
		long dayStart = java.time.Instant.parse("2026-01-01T00:55:00Z").toEpochMilli();
		assertEq(1200, ForagingRules.secondsToNextDay(dayStart), "a day starts at :55 past the hour");
		assertEq(1, ForagingRules.secondsToNextDay(dayStart - 1_000L), "one second before :55");
		assertEq(1200, ForagingRules.secondsToNextDay(java.time.Instant.parse("2026-01-01T01:15:00Z").toEpochMilli()), "a day starts at :15");
		assertEq(676, ForagingRules.secondsToNextDay(java.time.Instant.parse("2026-11-18T15:43:44Z").toEpochMilli()), "15:43:44 is 11:16 before the :55 day");
		assertTrue(!ForagingRules.recollect(true, 3, 10) && ForagingRules.recollect(false, 0, 10) && ForagingRules.recollect(true, 10, 10),
			"the sweep targets are recomputed on a new target, or after the refresh interval, not every tick");
		assertTrue(ForagingRules.seaLumiesActive(true, true) && !ForagingRules.seaLumiesActive(true, false) && !ForagingRules.seaLumiesActive(false, true),
			"Sea Lumies are shown only while enabled and the player is in water");
		double[][] anchors = {{0, 0, 0}, {20, 0, 0}};
		assertTrue(ForagingRules.withinAny(9, 0, 0, anchors, 10.0) && ForagingRules.withinAny(25, 0, 0, anchors, 10.0)
			&& !ForagingRules.withinAny(10, 0, 15, anchors, 10.0), "a temple counts as near within the range of any of its puzzle points");
		assertEq("Starts in: 7m 35s", ForagingRules.agathaContest(java.util.List.of("Area: Galatea", "§aAgatha's Contest:", " §fStarts in: 7m 35s", "Players (2)")),
			"the rows under the Agatha's Contest header are joined until the next widget");
		assertEq("7m 35s", ForagingRules.agathaContest(java.util.List.of("Agatha's Contest: 7m 35s", "Players (2)")), "text after the header colon counts");
		assertTrue(ForagingRules.agathaContest(java.util.List.of("Area: Galatea", "Players (2)")) == null && ForagingRules.agathaContest(java.util.List.of("Agatha's Contest:", "Players (2)")) == null,
			"no widget, or an empty one, is no contest line");
		assertEq("20:00", ForagingRules.clock(1200), "clock max");
		assertEq("00:05", ForagingRules.clock(5), "clock pads");
		assertEq("00:00", ForagingRules.clock(-3), "clock never negative");
	}

	private static void checkHighlightRules() {
		assertTrue(ForagingRules.seaPickleQualifies(3, 3), "3 pickles at min 3");
		assertTrue(!ForagingRules.seaPickleQualifies(2, 3), "2 pickles at min 3");
		assertTrue(ForagingRules.seaPickleQualifies(1, 1), "min 1 shows lone pickles");
		assertTrue(ForagingRules.honeyhiveQualifies(5, false), "full honey, no fence");
		assertTrue(!ForagingRules.honeyhiveQualifies(4, false), "not full yet");
		assertTrue(!ForagingRules.honeyhiveQualifies(5, true), "nest under a birch fence is a tree hive");
	}

	private static void checkTemplePuzzles() {
		// 0 north, 1 east, 2 south, 3 west (clockwise).
		assertEq(3, ForagingRules.forestFloorTarget(0), "north wall -> west floor");
		assertEq(2, ForagingRules.forestFloorTarget(1), "east wall -> south floor");
		assertEq(1, ForagingRules.forestFloorTarget(2), "south wall -> east floor");
		assertEq(0, ForagingRules.forestFloorTarget(3), "west wall -> north floor");
		assertEq(0, ForagingRules.rotations(1, 1), "already correct");
		assertEq(1, ForagingRules.rotations(0, 1), "one clockwise");
		assertEq(2, ForagingRules.rotations(0, 2), "half turn counts clockwise");
		assertEq(-1, ForagingRules.rotations(0, 3), "one counter-clockwise is shown negative");
		assertEq(-1, ForagingRules.rotations(3, 2), "west to south is counter-clockwise");

		// Fewest glass blocks first; ties keep colour order so the numbering never flickers.
		List<Integer> order = ForagingRules.buttonOrder(Map.of(5, 9, 2, 3, 7, 3, 1, 20));
		assertTrue(order.equals(List.of(2, 7, 5, 1)), "button order " + order);
	}

	private static void checkTuner() {
		assertEq(0, ForagingRules.cycleClicks(4, 4, 13), "same");
		assertEq(2, ForagingRules.cycleClicks(0, 2, 13), "forward");
		assertEq(-2, ForagingRules.cycleClicks(2, 0, 13), "backward");
		assertEq(-1, ForagingRules.cycleClicks(0, 12, 13), "wraps backward");
		assertEq(1, ForagingRules.cycleClicks(12, 0, 13), "wraps forward");
		assertEq(0, ForagingRules.colorIndex("magenta_dye"), "first dye");
		assertEq(12, ForagingRules.colorIndex("orange_dye"), "last dye");
		assertEq(7, ForagingRules.colorIndex("lapis_lazuli"), "lapis is the blue dye");
		assertEq(7, ForagingRules.paneIndex("blue_stained_glass_pane"), "blue pane lines up with lapis");
		assertEq(-1, ForagingRules.paneIndex("stone"), "not a pane");
		assertEq(5, ForagingRules.speedFromTicks(12), "fastest");
		assertEq(4, ForagingRules.speedFromTicks(19), "19 is nearer 22 than 12");
		assertEq(1, ForagingRules.speedFromTicks(52), "slowest");
		assertEq(3, ForagingRules.speedFromTicks(33), "middle");
		assertEq(-1, ForagingRules.speedFromTicks(65), "out of range");
		assertEq(-1, ForagingRules.speedFromTicks(3), "too fast");
		checkTunerSpeedMeter();
		assertEq(0, ForagingRules.pitchIndex(0.0952381f), "low");
		assertEq(2, ForagingRules.pitchIndex(1.4920635f), "high");
		assertEq(-1, ForagingRules.pitchIndex(1.0f), "unknown pitch");
		assertEq(1, ForagingRules.pitchFromLore("Pitch: Normal"), "pitch from lore");
		assertEq(4, ForagingRules.speedFromLore("Speed: 4"), "speed from lore");
		assertEq(3, ForagingRules.speedFromLore("Current speed: 3"), "lower-case speed line");
		assertEq(0, ForagingRules.speedFromLore("Speed: 9"), "bad speed");
	}

	private static void checkTunerSpeedMeter() {
		TunerSpeedMeter meter = new TunerSpeedMeter();
		assertEq(-1, meter.move(5L), "the first move only starts the clock");
		assertEq(4, meter.move(27L), "one full interval");
		assertEq(4, meter.move(49L), "steady");
		assertEq(4, meter.move(109L), "one lag spike is ignored by the median");
		assertEq(4, meter.move(130L), "jitter of one tick");
		meter.reset();
		assertEq(-1, meter.move(500L), "reset restarts the clock");
		assertEq(5, meter.move(512L), "after reset");
	}

	private static void checkSweep() {
		List<Integer> fig = ForagingRules.FIG_SWEEP;
		assertEq(0, ForagingRules.maxLogs(6f, fig), "below the first threshold");
		assertEq(1, ForagingRules.maxLogs(7f, fig), "first threshold");
		assertEq(20, ForagingRules.maxLogs(5000f, fig), "capped by the table");
		assertEq(36, ForagingRules.basicMaxLogs(100f), "hub cap");
		assertEq(4, ForagingRules.basicMaxLogs(3f), "hub scales with sweep");
		assertEq(5, ForagingRules.thrownMaxLogs(10), "thrown axes cut half");
	}

	private static void assertEq(int expected, int actual, String what) {
		if (expected != actual) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
	}

	private static void assertEq(String expected, String actual, String what) {
		if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
	}

	private static void assertTrue(boolean condition, String what) {
		if (!condition) throw new AssertionError(what);
	}
}
