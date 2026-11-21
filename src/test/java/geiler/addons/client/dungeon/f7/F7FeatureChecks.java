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
		require(s.remaining().size() == 2, "a lit lantern is recorded once");
		long first = s.next();
		require(first == SimonSaysState.pack(110, 121, 93), "the next button is one block west of the first lantern");
		require(s.buttonClicked(110, 121, 93) == SimonSaysState.CORRECT && s.remaining().size() == 1,
			"clicking the next button advances");
		require(s.buttonClicked(110, 120, 92) == SimonSaysState.WRONG && s.remaining().size() == 1,
			"clicking another button is wrong and changes nothing");
		require(s.buttonClicked(110, 122, 94) == SimonSaysState.CORRECT && s.next() == null, "the last click empties the list");
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
	}

	/** SS skip: in the first phase the lit order is not the click order (adapted from Odin's Simon Says). */
	private static void checkSimonSaysFirstPhase() {
		SimonSaysState s = new SimonSaysState();
		long a = SimonSaysState.pack(110, 120, 92);
		long b = SimonSaysState.pack(110, 121, 93);
		long c = SimonSaysState.pack(110, 122, 94);
		s.lanternOff(111, 120, 92);
		require(s.remaining().equals(java.util.List.of(a)), "the first lantern is recorded as is");
		s.lanternOff(111, 121, 93);
		require(s.remaining().equals(java.util.List.of(b, a)), "the second lantern reverses the list in the first phase");
		s.lanternOff(111, 122, 94);
		require(s.remaining().equals(java.util.List.of(b, c)), "the third lantern drops the one before it in the first phase: " + s.remaining());

		SimonSaysState later = new SimonSaysState();
		later.endFirstPhase();
		later.lanternOff(111, 120, 92);
		later.lanternOff(111, 121, 93);
		later.lanternOff(111, 122, 94);
		require(later.remaining().equals(java.util.List.of(a, b, c)), "after the first phase lanterns are kept in lit order");

		SimonSaysState done = new SimonSaysState();
		done.lanternOff(111, 120, 92);
		require(done.getFirstPhase() && done.buttonClicked(110, 120, 92) == SimonSaysState.CORRECT && !done.getFirstPhase(),
			"finishing the sequence ends the first phase");
		done.restart();
		require(done.getFirstPhase() && done.remaining().isEmpty(), "a start-button press restarts the first phase");

		// Grid reset: no lantern for more than ten ticks while the stone buttons are back ends the first phase.
		SimonSaysState grid = new SimonSaysState();
		for (int i = 0; i < 12; i++) grid.tick(0);
		require(grid.getFirstPhase(), "no buttons on the grid never ends the first phase");
		grid.tick(16);
		require(!grid.getFirstPhase(), "buttons back after ten idle ticks end the first phase");
		SimonSaysState lit = new SimonSaysState();
		for (int i = 0; i < 8; i++) lit.tick(16);
		lit.lanternLit(111, 120, 92);
		for (int i = 0; i < 8; i++) lit.tick(16);
		require(lit.getFirstPhase(), "a lantern lighting restarts the idle count");

		// Start button: clicks past the limit are blocked in the first phase unless sneaking.
		SimonSaysState start = new SimonSaysState();
		require(!start.startClicked(2, false) && !start.startClicked(2, false), "the allowed start clicks pass");
		require(start.startClicked(2, false), "a click past the limit is blocked");
		require(!start.startClicked(2, true), "sneaking overrides the block");
		start.endFirstPhase();
		require(!start.startClicked(2, false), "outside the first phase start clicks are never blocked");
		start.restart();
		require(!start.startClicked(2, false), "ending the first phase clears the start click count");
	}

	/**
	 * Double and triple skips: several lanterns at once, a click ahead of the head, repeated or out-of-list skip events.
	 * Pointer model after Odin's Simon Says; skip observation by position after NoFrills.
	 */
	private static void checkSimonSaysSkips() {
		long a = SimonSaysState.pack(110, 120, 92);
		long b = SimonSaysState.pack(110, 121, 93);
		long c = SimonSaysState.pack(110, 122, 94);
		long d = SimonSaysState.pack(110, 123, 95);

		// Clicking ahead completes everything up to and including the button, so the skipped steps stop being shown.
		SimonSaysState ahead = new SimonSaysState();
		ahead.endFirstPhase();
		ahead.lanternOff(111, 120, 92);
		ahead.lanternOff(111, 121, 93);
		ahead.lanternOff(111, 122, 94);
		ahead.lanternOff(111, 123, 95);
		require(ahead.peek(110, 122, 94) == SimonSaysState.AHEAD && ahead.remaining().size() == 4, "peek does not change the list");
		require(ahead.buttonClicked(110, 122, 94) == SimonSaysState.AHEAD, "a later button in the list is a click ahead");
		require(ahead.remaining().equals(java.util.List.of(d)), "a click ahead completes the buttons before it: " + ahead.remaining());
		require(ahead.buttonClicked(110, 122, 94) == SimonSaysState.WRONG, "a completed button is wrong when clicked again");
		require(ahead.buttonClicked(110, 123, 95) == SimonSaysState.CORRECT && ahead.remaining().isEmpty(), "the last button finishes");

		// A skip seen from the world removes that button only; repeats and unknown buttons change nothing.
		SimonSaysState skip = new SimonSaysState();
		skip.endFirstPhase();
		skip.lanternOff(111, 120, 92);
		skip.lanternOff(111, 121, 93);
		skip.lanternOff(111, 122, 94);
		skip.removeButton(110, 120, 92);
		skip.removeButton(110, 120, 92);
		require(skip.remaining().equals(java.util.List.of(b, c)), "a repeated skip event removes the button once: " + skip.remaining());
		skip.removeButton(110, 123, 95);
		require(skip.remaining().equals(java.util.List.of(b, c)), "a skip of a button outside the list changes nothing");
		skip.removeButton(110, 121, 93);
		skip.removeButton(110, 122, 94);
		require(skip.remaining().isEmpty() && skip.next() == null, "a triple skip empties exactly the skipped buttons");

		// The server's powered-button update confirms a click and is idempotent after the client click.
		SimonSaysState powered = new SimonSaysState();
		powered.endFirstPhase();
		powered.lanternOff(111, 120, 92);
		powered.lanternOff(111, 121, 93);
		require(powered.buttonClicked(110, 120, 92) == SimonSaysState.CORRECT, "client click");
		powered.buttonConfirmed(110, 120, 92);
		require(powered.remaining().equals(java.util.List.of(b)), "the confirmation after a click removes nothing more");
		powered.buttonConfirmed(110, 121, 93);
		require(powered.remaining().isEmpty(), "a confirmation alone completes a button the client did not see");

		// Repeated lantern events never duplicate; a new round clears the old list but keeps the phase.
		SimonSaysState round = new SimonSaysState();
		round.endFirstPhase();
		round.lanternOff(111, 120, 92);
		round.lanternOff(111, 120, 92);
		require(round.remaining().size() == 1, "a lantern turning off twice is recorded once");
		round.clearSequence();
		require(round.remaining().isEmpty() && !round.getFirstPhase(), "a grid reset clears the list without restarting the first phase");

		// Triple skip in the first phase: three lanterns, click order b then c (Odin: reverse, then drop the one before the last).
		SimonSaysState triple = new SimonSaysState();
		triple.lanternOff(111, 120, 92);
		triple.lanternOff(111, 121, 93);
		triple.lanternOff(111, 122, 94);
		require(triple.remaining().equals(java.util.List.of(b, c)), "triple skip click order: " + triple.remaining());
		triple.lanternOff(111, 123, 95);
		require(triple.remaining().equals(java.util.List.of(b, c, d)), "a fourth lantern in the first phase keeps its place: " + triple.remaining());
		require(triple.buttonClicked(110, 122, 94) == SimonSaysState.AHEAD && triple.remaining().equals(java.util.List.of(d)),
			"clicking the second button of a triple skip first completes both");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
