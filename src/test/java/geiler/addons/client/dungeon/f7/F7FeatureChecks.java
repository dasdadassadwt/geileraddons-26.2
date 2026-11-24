package geiler.addons.client.dungeon.f7;

import java.util.List;
import java.util.Map;

/** Offline checks for the pure rules behind Blessing Display, Terminal ESP, Melody Message and the F7 device solvers. */
public final class F7FeatureChecks {
	private F7FeatureChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkBlessings();
		checkTerminalSections();
		checkMelody();
		checkArrowAlign();
		checkSimonSays();
		checkSimonSaysFirstPhase();
		checkSimonSaysSkips();
	}

	private static void checkBlessings() {
		require(BlessingRules.roman("XIV") == 14 && BlessingRules.roman("V") == 5 && BlessingRules.roman("XIX") == 19
			&& BlessingRules.roman("") == 0 && BlessingRules.roman("abc") == 0, "roman numerals parse, junk is 0");
		String footer = "Active Effects\nBlessing of Wisdom V\n§s\nBlessing of Power XIV\nBlessing of Life II\nRanks & MORE!";
		Map<BlessingRules.Blessing, Integer> parsed = BlessingRules.parseFooter(footer);
		require(parsed.size() == 3 && parsed.get(BlessingRules.Blessing.WISDOM) == 5
			&& parsed.get(BlessingRules.Blessing.POWER) == 14 && parsed.get(BlessingRules.Blessing.LIFE) == 2,
			"footer blessings parse by name and level");
		require(BlessingRules.parseFooter("§r§dBlessing of Time §aXII§r").get(BlessingRules.Blessing.TIME) == 12,
			"colour codes are ignored");
		require(BlessingRules.parseFooter(null).isEmpty() && BlessingRules.parseFooter("nothing").isEmpty(),
			"no blessings parse as empty");
		List<String> lines = BlessingRules.lines(Map.of(BlessingRules.Blessing.POWER, 14, BlessingRules.Blessing.LIFE, 2),
			true, false, false, true, false);
		require(lines.size() == 2 && lines.get(0).contains("Power") && lines.get(0).contains("14") && lines.get(1).contains("Life"),
			"enabled, active blessings become lines in a fixed order");
		require(BlessingRules.lines(Map.of(BlessingRules.Blessing.POWER, 14), false, false, false, false, false).isEmpty(),
			"disabled blessings are hidden");
	}

	private static void checkTerminalSections() {
		require(TerminalEspRules.sectionOf(110, 113, 73) == 0 && TerminalEspRules.sectionOf(44, 121, 30) == 3,
			"known terminal positions belong to their section");
		require(TerminalEspRules.terminalSection(110.5, 113.0, 73.4) == 0
			&& TerminalEspRules.terminalSection(300, 100, 300) == -1,
			"an armor stand next to a terminal position matches, a far one does not");
		require(TerminalEspRules.playerSection(105, 115, 80) == 0 && TerminalEspRules.playerSection(60, 110, 125) == 1
			&& TerminalEspRules.playerSection(0, 110, 100) == 2 && TerminalEspRules.playerSection(60, 110, 35) == 3,
			"the player's section is the one with the closest terminal");
	}

	private static void checkMelody() {
		// slots in order; 0 other, 1 red, 2 lime; -1 is an empty slot
		require(MelodyRules.progress(new int[] {0, 0, 1, 2, 0}, 1) == null,
			"a lime pane found with count >= lastCount sends nothing");
		MelodyRules.Progress p67 = MelodyRules.progress(new int[] {0, 1, 1, 2, 0, 0, 0}, 3);
		require(p67 != null && "67%".equals(p67.getPercent()) && p67.getCount() == 1,
			"lime as the last terracotta is 67%");
		MelodyRules.Progress p33 = MelodyRules.progress(new int[] {0, 1, 2, 1, 0, 0, 0}, 3);
		require(p33 != null && "33%".equals(p33.getPercent()) && p33.getCount() == 2,
			"lime as the second-to-last terracotta is 33%");
		require(MelodyRules.progress(new int[] {0, 1, 2, 1, 0, 0, 0}, 2) == null,
			"the same progress is not sent twice");
		require(MelodyRules.progress(new int[] {-1, 2, 1, 0, 0}, 3) == null, "an unfinished (empty slot) screen is ignored");
		require("/pc Melody 67%".equals(MelodyRules.format("/pc Melody {percent}", "67%")), "percent placeholder is replaced");
		require(MelodyRules.isMelodyTitle("Click the button on time!") && !MelodyRules.isMelodyTitle("Correct all the panes!"),
			"the melody terminal title is recognised");
	}

	private static void checkArrowAlign() {
		require(ArrowAlignRules.neededClicks(0, 1) == 1 && ArrowAlignRules.neededClicks(7, 1) == 2
			&& ArrowAlignRules.neededClicks(3, 3) == 0 && ArrowAlignRules.neededClicks(1, 7) == 6,
			"clicks needed wrap around 8 rotations");
		require(ArrowAlignRules.frameIndex(-2, 120, 75) == 0 && ArrowAlignRules.frameIndex(-2, 124, 79) == 24
			&& ArrowAlignRules.frameIndex(-2, 121, 75) == 1 && ArrowAlignRules.frameIndex(-2, 120, 76) == 5
			&& ArrowAlignRules.frameIndex(-1, 120, 75) == -1 && ArrowAlignRules.frameIndex(-2, 125, 75) == -1,
			"frame positions map onto the 5x5 grid");
		int[] solution = ArrowAlignRules.SOLUTIONS.get(0);
		int[] current = new int[25];
		for (int i = 0; i < 25; i++) current[i] = solution[i] == -1 ? -1 : 0;
		int[] found = ArrowAlignRules.solve(current);
		require(found != null && found.length == 25, "frames laid out like a known solution solve");
		int[] clicks = ArrowAlignRules.clicks(current, found);
		boolean any = false;
		for (int i = 0; i < 25; i++) {
			if (found[i] == -1) require(clicks[i] == 0, "an unused frame needs no clicks");
			else { require(clicks[i] == ArrowAlignRules.neededClicks(0, found[i]), "needed clicks per frame"); any = true; }
		}
		require(any, "at least one frame needs clicking");
		int[] wrongShape = new int[25];
		require(ArrowAlignRules.solve(wrongShape) == null, "all frames present matches no known pattern");
		require(ArrowAlignRules.solve(new int[] {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1}) == null,
			"no frames present matches nothing");
		require(ArrowAlignRules.SOLUTIONS.size() == 9, "nine known patterns");
		require(Math.abs(ArrowAlignRules.labelScale(6.0) - 1.0f) < 1.0e-6f && Math.abs(ArrowAlignRules.labelScale(12.0) - 0.5f) < 1.0e-6f
			&& Math.abs(ArrowAlignRules.labelScale(3.0) - 2.0f) < 1.0e-6f,
			"click labels keep a fixed world size: scale is inverse to distance");
		require(ArrowAlignRules.labelScale(0.0) == 4.0f && ArrowAlignRules.labelScale(100.0) == 0.25f
			&& ArrowAlignRules.labelScale(Double.NaN) == 1.0f,
			"label scale is clamped and survives bad distances");
	}

	private static void checkSimonSays() {
		SimonSaysState s = new SimonSaysState();
		require(s.next() == null && s.remaining().isEmpty(), "a fresh state has no solution");
		require(s.getFirstPhase(), "a fresh state starts in the first (skip) phase");
		s.endFirstPhase();
		s.lanternOff(111, 121, 93);
		s.lanternOff(111, 121, 93);
		s.lanternOff(111, 122, 94);
		require(s.remaining().size() == 2 && s.size() == 2, "a lantern is recorded once");
		long first = s.next();
		require(first == SimonSaysState.pack(110, 121, 93), "the next button is one block west of the first lantern");
		require(!s.isLast(110, 121, 93) && s.isLast(110, 122, 94), "the last button of the round is known");
		require(s.isNext(110, 121, 93) && !s.isNext(110, 122, 94) && !s.isNext(110, 120, 92), "only the pointed button is next");
		s.buttonPowered(110, 121, 93);
		require(s.remaining().size() == 1 && s.size() == 2, "a pressed button moves the pointer and keeps the round list");
		s.buttonPowered(110, 122, 94);
		require(s.next() == null && s.size() == 0, "the last press finishes the round and clears it (Odin's resetSolution)");
		s.lanternOff(111, 120, 92);
		s.reset();
		require(s.remaining().isEmpty(), "reset clears the sequence");
		require(SimonSaysState.inLights(111, 123, 95) && !SimonSaysState.inLights(111, 124, 95) && !SimonSaysState.inLights(112, 121, 93)
			&& SimonSaysState.inButtons(110, 120, 92) && !SimonSaysState.inButtons(110, 120, 96),
			"light and button areas match the device bounds");
		// An unpressed stone button on the east face is 2/16 deep; pressed it is 1/16 deep. The box follows the real shape.
		double[] open = SimonSaysState.highlightBox(110, 121, 93, 0.0, 0.375, 0.3125, 0.125, 0.625, 0.6875);
		double[] pressed = SimonSaysState.highlightBox(110, 121, 93, 0.0, 0.375, 0.3125, 0.0625, 0.625, 0.6875);
		double e = SimonSaysState.HIGHLIGHT_MARGIN;
		require(Math.abs(open[0] - (110.0 - e)) < 1e-9 && Math.abs(open[3] - (110.125 + e)) < 1e-9
			&& Math.abs(open[1] - (121.375 - e)) < 1e-9 && Math.abs(open[5] - (93.6875 + e)) < 1e-9,
			"the highlight is the button shape placed at its block, inflated by a hair");
		require(pressed[3] < open[3] && Math.abs(pressed[3] - (110.0625 + e)) < 1e-9, "a pressed button gets a shallower box");
		double[] spot = SimonSaysState.buttonSpotBox(110, 121, 93);
		require(Math.abs(spot[0] - (110.875 - e)) < 1e-9 && Math.abs(spot[3] - (111.0 + e)) < 1e-9
			&& Math.abs(spot[1] - (121.375 - e)) < 1e-9 && Math.abs(spot[5] - (93.6875 + e)) < 1e-9,
			"the fallback box is an unpressed button against the lantern wall");
	}

	/** SS skip: in the first phase the lit order is not the click order (Odin's Simon Says, line for line). */
	private static void checkSimonSaysFirstPhase() {
		SimonSaysState s = new SimonSaysState();
		long a = SimonSaysState.pack(110, 120, 92);
		long b = SimonSaysState.pack(110, 121, 93);
		long c = SimonSaysState.pack(110, 122, 94);
		long d = SimonSaysState.pack(110, 123, 95);
		s.lanternOff(111, 120, 92);
		require(s.remaining().equals(java.util.List.of(a)), "the first lantern is recorded as is");
		s.lanternOff(111, 121, 93);
		require(s.remaining().equals(java.util.List.of(b, a)), "the second lantern reverses the list in the first phase");
		s.lanternOff(111, 122, 94);
		require(s.remaining().equals(java.util.List.of(b, c)), "the third lantern drops the one before it in the first phase: " + s.remaining());
		s.lanternOff(111, 123, 95);
		require(s.remaining().equals(java.util.List.of(b, d)), "a list back at three drops the one before the last again: " + s.remaining());

		SimonSaysState later = new SimonSaysState();
		later.endFirstPhase();
		later.lanternOff(111, 120, 92);
		later.lanternOff(111, 121, 93);
		later.lanternOff(111, 122, 94);
		require(later.remaining().equals(java.util.List.of(a, b, c)), "after the first phase lanterns are kept in lit order");

		SimonSaysState done = new SimonSaysState();
		done.lanternOff(111, 120, 92);
		done.buttonPowered(110, 120, 92);
		require(!done.getFirstPhase(), "finishing the sequence ends the first phase");
		require(done.startPowered(), "a start press after the first phase is a device reset");
		require(done.getFirstPhase() && done.remaining().isEmpty(), "a start-button press restarts the first phase");
		require(!done.startPowered(), "a start press during the first phase (the skip) is not a reset");

		// A press while no solution is known: Odin's pointer 0 >= size 0 ends the first phase.
		SimonSaysState empty = new SimonSaysState();
		empty.buttonPowered(110, 120, 92);
		require(!empty.getFirstPhase(), "a button press with an empty list ends the first phase");

		// Grid reset: no lantern turned off for more than ten server ticks while the stone buttons are back.
		SimonSaysState grid = new SimonSaysState();
		for (int i = 0; i < 13; i++) grid.serverTick(0);
		require(grid.getFirstPhase(), "no buttons on the grid never ends the first phase");
		grid.serverTick(16);
		require(!grid.getFirstPhase(), "buttons back after the idle ticks end the first phase");
		SimonSaysState fresh = new SimonSaysState();
		for (int i = 0; i < 12; i++) fresh.serverTick(16);
		require(fresh.getFirstPhase(), "the idle count starts at -1: twelve ticks are not enough");
		fresh.serverTick(16);
		require(!fresh.getFirstPhase(), "the thirteenth idle tick ends the first phase");
		SimonSaysState off = new SimonSaysState();
		for (int i = 0; i < 8; i++) off.serverTick(16);
		off.lanternOff(111, 120, 92);
		for (int i = 0; i < 11; i++) off.serverTick(16);
		require(off.getFirstPhase(), "a lantern turning off restarts the idle count at 0");
		off.serverTick(16);
		require(!off.getFirstPhase(), "eleven more ticks after a lantern end the first phase");

		// Start button: clicks past the limit are blocked in the first phase unless sneaking.
		SimonSaysState start = new SimonSaysState();
		require(!start.startClicked(2, false) && !start.startClicked(2, false), "the allowed start clicks pass");
		require(start.startClicked(2, false), "a click past the limit is blocked");
		require(!start.startClicked(2, true), "sneaking overrides the block");
		start.resetStartClicks();
		require(!start.startClicked(2, false), "Goldor's entry line clears the start click count");
		start.endFirstPhase();
		require(!start.startClicked(2, false), "outside the first phase start clicks are never blocked");
		start.startPowered();
		require(!start.startClicked(2, false), "a grid reset clears the start click count");

		// Odin keeps the start count when a round is finished by a press (only the grid-reset tick clears it).
		SimonSaysState kept = new SimonSaysState();
		kept.startClicked(1, false);
		kept.lanternOff(111, 120, 92);
		kept.buttonPowered(110, 120, 92);
		kept.startPowered();
		require(kept.startClicked(1, false), "finishing a round does not clear the start click count");
	}

	/** Pointer moves, repeated events and a new round (Odin's pointer: index of the pressed button + 1). */
	private static void checkSimonSaysSkips() {
		long b = SimonSaysState.pack(110, 121, 93);
		long c = SimonSaysState.pack(110, 122, 94);
		long d = SimonSaysState.pack(110, 123, 95);

		SimonSaysState ahead = new SimonSaysState();
		ahead.endFirstPhase();
		ahead.lanternOff(111, 120, 92);
		ahead.lanternOff(111, 121, 93);
		ahead.lanternOff(111, 122, 94);
		ahead.lanternOff(111, 123, 95);
		ahead.buttonPowered(110, 122, 94);
		require(ahead.remaining().equals(java.util.List.of(d)), "a press ahead completes the buttons before it: " + ahead.remaining());
		ahead.lanternOff(111, 120, 92);
		require(ahead.remaining().equals(java.util.List.of(d)), "a button of this round is not recorded again");
		ahead.buttonPowered(110, 121, 93);
		require(ahead.remaining().equals(java.util.List.of(c, d)), "an earlier button moves the pointer back (Odin): " + ahead.remaining());
		ahead.buttonPowered(110, 120, 95);
		require(ahead.remaining().size() == 4, "a button outside the list moves the pointer to the start (Odin)");
		require(!SimonSaysState.inButtons(111, 120, 92), "a lantern is not a button");

		// Repeated lantern events never duplicate; a new round clears the old list but keeps the phase.
		SimonSaysState round = new SimonSaysState();
		round.endFirstPhase();
		round.lanternOff(111, 120, 92);
		round.lanternOff(111, 120, 92);
		require(round.remaining().size() == 1, "a lantern turning off twice is recorded once");
		round.gridAir(8);
		require(round.remaining().size() == 1, "eight air buttons are not a grid reset");
		round.gridAir(9);
		require(round.remaining().isEmpty() && !round.getFirstPhase(), "more than eight air buttons clear the list without restarting the first phase");

		// Triple skip in the first phase: three lanterns, click order b then c; pressing c first completes both.
		SimonSaysState triple = new SimonSaysState();
		triple.lanternOff(111, 120, 92);
		triple.lanternOff(111, 121, 93);
		triple.lanternOff(111, 122, 94);
		require(triple.remaining().equals(java.util.List.of(b, c)), "triple skip click order: " + triple.remaining());
		triple.buttonPowered(110, 122, 94);
		require(triple.remaining().isEmpty() && !triple.getFirstPhase(), "pressing the last button completes the round and the first phase");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
