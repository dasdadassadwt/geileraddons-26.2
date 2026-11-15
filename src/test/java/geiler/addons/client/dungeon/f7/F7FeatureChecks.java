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
	}

	private static void checkSimonSays() {
		SimonSaysState s = new SimonSaysState();
		require(s.next() == null && s.remaining().isEmpty(), "a fresh state has no solution");
		s.lanternLit(111, 121, 93);
		s.lanternLit(111, 121, 93);
		s.lanternLit(111, 122, 94);
		require(s.remaining().size() == 2, "a lit lantern is recorded once");
		long first = s.next();
		require(first == SimonSaysState.pack(110, 121, 93), "the next button is one block west of the first lantern");
		require(s.buttonClicked(110, 121, 93) == SimonSaysState.CORRECT && s.remaining().size() == 1,
			"clicking the next button advances");
		require(s.buttonClicked(110, 120, 92) == SimonSaysState.WRONG && s.remaining().size() == 1,
			"clicking another button is wrong and changes nothing");
		require(s.buttonClicked(110, 122, 94) == SimonSaysState.CORRECT && s.next() == null, "the last click empties the list");
		s.lanternLit(111, 120, 92);
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

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
