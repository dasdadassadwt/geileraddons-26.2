package geiler.addons.client.dungeon.terminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The Auto Terms engine: it clicks only what the solver would accept, once per solution entry, never during first-click
 * protection, in the order a terminal forces, with the delays, slot choice and landing spots the recordings gave.
 */
public final class AutoTermsChecks {
	private AutoTermsChecks() { }

	private static final double SIZE = 43.2;
	private static final long FRAME = 8_000_000L;

	private static final class Run {
		final List<Long> times = new ArrayList<>();
		final List<Integer> slots = new ArrayList<>();
		final List<Integer> buttons = new ArrayList<>();
		final List<double[]> points = new ArrayList<>();
		final List<double[]> from = new ArrayList<>();
		final List<List<Integer>> before = new ArrayList<>();
		final List<String> events = new ArrayList<>();
		final List<double[]> trace = new ArrayList<>();
		final List<double[]> landings = new ArrayList<>();
		boolean moved;
		int clickedWhileProtected;
	}

	private static AutoBox box(int slot) {
		return new AutoBox(100 + (slot % 9) * SIZE + SIZE / 2, 60 + (slot / 9) * SIZE + SIZE / 2, SIZE);
	}

	private static Run play(TerminalType type, List<Integer> start, AutoTermsConfig config, long seed, long protectedUntilMs,
		long litAtMs, long untilMs) {
		return play(type, start, config, seed, protectedUntilMs, litAtMs, untilMs, 0.0);
	}

	private static Run play(TerminalType type, List<Integer> start, AutoTermsConfig config, long seed, long protectedUntilMs,
		long litAtMs, long untilMs, double pixel) {
		AutoTermsEngine engine = new AutoTermsEngine(config, new Random(seed));
		List<Integer> remaining = new ArrayList<>(start);
		List<Integer> hidden = new ArrayList<>();
		if (litAtMs > 0 && remaining.size() > 3) {
			hidden.addAll(remaining.subList(3, remaining.size()));
			remaining = new ArrayList<>(remaining.subList(0, 3));
		}
		Run run = new Run();
		double cx = 500, cy = 300;
		for (long now = 0; now <= untilMs * 1_000_000L && !remaining.isEmpty(); now += FRAME) {
			if (!hidden.isEmpty() && now >= litAtMs * 1_000_000L) {
				remaining.addAll(hidden);
				hidden.clear();
			}
			boolean prot = now < protectedUntilMs * 1_000_000L;
			final List<Integer> view = remaining;
			AutoTermsView v = new AutoTermsView(type, new ArrayList<>(view), AutoTermsChecks::box,
				slot -> slot == 21 && type == TerminalType.RUBIX ? 1 : 0, prot, cx, cy, pixel);
			AutoStep step = engine.step(v, now);
			if (step.getMove()) {
				run.moved = true;
				cx = step.getX();
				cy = step.getY();
				run.trace.add(new double[] { now / 1_000_000.0, cx, cy });
			}
			String event;
			while ((event = engine.pollEvent()) != null) {
				run.events.add(event);
				if (event.startsWith("plan slot=")) {
					String[] parts = event.split(" ");
					int slot = Integer.parseInt(parts[1].substring(5));
					String[] landing = event.substring(event.indexOf("landing=") + 8).split(" ")[0].split(",");
					run.landings.add(new double[] { box(slot).getCx() + Double.parseDouble(landing[0]) * SIZE,
						box(slot).getCy() + Double.parseDouble(landing[1]) * SIZE });
				}
			}
			if (step.getClicks()) {
				if (prot) run.clickedWhileProtected++;
				run.from.add(new double[] { cx, cy });
				run.times.add(now / 1_000_000L);
				run.slots.add(step.getClickSlot());
				run.buttons.add(step.getClickButton());
				run.points.add(new double[] { step.getX(), step.getY() });
				run.before.add(new ArrayList<>(remaining));
				remaining.remove(Integer.valueOf(step.getClickSlot()));
			}
		}
		return run;
	}

	private static double median(List<Double> values) {
		List<Double> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		return sorted.get(sorted.size() / 2);
	}

	public static void run() {
		checkValidClicks();
		checkProtection();
		checkTimingsFromTheRecording();
		checkForcedOrders();
		checkChoice();
		checkMelody();
		checkOptions();
		checkSpeedPresets();
		checkRubixBursts();
		checkMelodyPreAim();
		checkHumanCursor();
	}

	private static void checkValidClicks() {
		AutoTermsConfig config = new AutoTermsConfig();
		for (long seed = 1; seed <= 60; seed++) {
			List<Integer> board = Arrays.asList(10, 11, 13, 19, 21, 29, 31, 32);
			Run run = play(TerminalType.PANES, board, config, seed, 0, 0, 30_000);
			check(run.slots.size() == board.size(), "every solution slot is clicked once (seed " + seed + ": " + run.slots + ")");
			for (int i = 0; i < run.slots.size(); i++) {
				check(run.before.get(i).contains(run.slots.get(i)), "a click is never on a slot that is not left");
				AutoBox box = box(run.slots.get(i));
				double[] at = run.points.get(i);
				check(Math.abs(at[0] - box.getCx()) <= 0.47 * SIZE && Math.abs(at[1] - box.getCy()) <= 0.47 * SIZE,
					"the click lands inside the slot, not on its edge");
			}
		}
		Run a = play(TerminalType.PANES, Arrays.asList(10, 11, 13, 19, 21), config, 7, 0, 0, 30_000);
		Run b = play(TerminalType.PANES, Arrays.asList(10, 11, 13, 19, 21), config, 7, 0, 0, 30_000);
		check(a.times.equals(b.times) && a.slots.equals(b.slots), "the same seed plays the same terminal");
		check(a.events.stream().anyMatch(e -> e.startsWith("plan slot=")), "each plan is described for the log");
	}

	private static void checkProtection() {
		AutoTermsConfig config = new AutoTermsConfig();
		for (long seed = 1; seed <= 30; seed++) {
			Run run = play(TerminalType.PANES, Arrays.asList(10, 11, 12), config, seed, 900, 0, 30_000);
			check(run.clickedWhileProtected == 0 && run.times.get(0) >= 900, "nothing is clicked while first-click protection is on");
			check(run.slots.size() == 3, "the terminal is still finished after the protection ends");
		}
	}

	private static void checkTimingsFromTheRecording() {
		AutoTermsConfig config = new AutoTermsConfig();
		List<Double> first = new ArrayList<>();
		List<Double> gaps = new ArrayList<>();
		double minGap = Double.MAX_VALUE;
		for (long seed = 1; seed <= 200; seed++) {
			Run run = play(TerminalType.PANES, Arrays.asList(10, 11, 12, 19, 20, 21, 28, 29), config, seed, 0, 0, 30_000);
			first.add((double) run.times.get(0));
			for (int i = 1; i < run.times.size(); i++) {
				double gap = run.times.get(i) - run.times.get(i - 1);
				gaps.add(gap);
				minGap = Math.min(minGap, gap);
			}
		}
		double firstMedian = median(first);
		check(firstMedian > 380 && firstMedian < 520, "the first click comes about 450 ms in (" + firstMedian + ")");
		double gapMedian = median(gaps);
		check(gapMedian > 110 && gapMedian < 280, "clicks follow each other about 150-250 ms apart (" + gapMedian + ")");
		check(minGap >= 50, "the gap never goes under the minimum (" + minGap + ")");
		double spread = Collections.max(gaps) - Collections.min(gaps);
		check(spread > 150, "the gaps vary like a person's (" + spread + ")");

		AutoTermsConfig slow = new AutoTermsConfig();
		slow.setTimeScale(2.0);
		List<Double> slowFirst = new ArrayList<>();
		for (long seed = 1; seed <= 100; seed++) {
			slowFirst.add((double) play(TerminalType.PANES, Arrays.asList(10, 11), slow, seed, 0, 0, 30_000).times.get(0));
		}
		check(median(slowFirst) > 1.7 * firstMedian, "Time Scale slows every delay");
	}

	private static void checkForcedOrders() {
		AutoTermsConfig config = new AutoTermsConfig();
		Run numbers = play(TerminalType.NUMBERS, Arrays.asList(24, 11, 20, 13, 29), config, 3, 0, 0, 30_000);
		check(numbers.slots.equals(Arrays.asList(24, 11, 20, 13, 29)), "numbers are clicked in the solver's order");

		Run rubix = play(TerminalType.RUBIX, Arrays.asList(12, 21, 21, 22, 22), config, 4, 0, 0, 30_000);
		check(rubix.slots.size() == 5, "rubix clicks every entry, repeats included");
		for (int i = 0; i < rubix.slots.size(); i++) {
			check(rubix.buttons.get(i) == (rubix.slots.get(i) == 21 ? 1 : 0), "rubix uses the button the slot accepts");
		}
	}

	private static void checkChoice() {
		List<Integer> board = Arrays.asList(10, 11, 12, 13, 19, 20, 21, 22);
		AutoTermsConfig nearest = new AutoTermsConfig();
		nearest.setNearestChance(1.0);
		nearest.setFirstReadingChance(0.0);
		int picks = 0;
		int hits = 0;
		for (long seed = 1; seed <= 60; seed++) {
			Run run = play(TerminalType.PANES, board, nearest, seed, 0, 0, 30_000);
			for (int i = 0; i < run.slots.size(); i++) {
				double[] spot = i == 0 ? new double[] { 500, 300 } : run.landings.get(i - 1);
				int best = -1;
				double bestDistance = Double.MAX_VALUE;
				for (int s : run.before.get(i)) {
					double d = Math.hypot(box(s).getCx() - spot[0], box(s).getCy() - spot[1]);
					if (d < bestDistance) { bestDistance = d; best = s; }
				}
				picks++;
				if (best == run.slots.get(i)) hits++;
			}
		}
		check(hits == picks, "with Nearest Slot Chance at 100% every pick is the nearest (" + hits + "/" + picks + ")");

		AutoTermsConfig never = new AutoTermsConfig();
		never.setNearestChance(0.0);
		never.setFirstReadingChance(0.0);
		int tested = 0;
		int nearestPicks = 0;
		for (long seed = 1; seed <= 60; seed++) {
			Run run = play(TerminalType.PANES, board, never, seed, 0, 0, 30_000);
			for (int i = 0; i < run.slots.size(); i++) {
				if (run.before.get(i).size() < 3) continue;
				double[] spot = i == 0 ? new double[] { 500, 300 } : run.landings.get(i - 1);
				int best = -1;
				double bestDistance = Double.MAX_VALUE;
				for (int s : run.before.get(i)) {
					double d = Math.hypot(box(s).getCx() - spot[0], box(s).getCy() - spot[1]);
					if (d < bestDistance) { bestDistance = d; best = s; }
				}
				tested++;
				if (best == run.slots.get(i)) nearestPicks++;
			}
		}
		check(tested > 100 && nearestPicks == 0, "with Nearest Slot Chance at 0% it takes the second or third nearest (" + nearestPicks + "/" + tested + ")");

		AutoTermsConfig reading = new AutoTermsConfig();
		reading.setFirstReadingChance(1.0);
		for (long seed = 1; seed <= 30; seed++) {
			check(play(TerminalType.PANES, board, reading, seed, 0, 0, 30_000).slots.get(0) == 10,
				"the first click can be the first slot in reading order");
		}
	}

	private static void checkMelody() {
		AutoTermsConfig config = new AutoTermsConfig();
		List<Double> reaction = new ArrayList<>();
		for (long seed = 1; seed <= 80; seed++) {
			Run run = play(TerminalType.MELODY, Arrays.asList(19, 7, 43, 25), config, seed, 0, 1000, 8_000);
			check(run.times.size() == 1 && run.slots.get(0) == 25, "melody clicks the lit button once it appears");
			check(run.times.get(0) > 1000, "and not before it is lit");
			reaction.add(run.times.get(0) - 1000.0);
		}
		double median = median(reaction);
		check(median > 170 && median < 280, "after about the reaction time (" + median + ")");
		Run unlit = play(TerminalType.MELODY, Arrays.asList(7, 16, 52), config, 1, 0, 0, 4_000);
		check(unlit.slots.isEmpty(), "with no lit button nothing is clicked");
	}

	private static void checkSpeedPresets() {
		List<Double> slowFirst = new ArrayList<>();
		List<Double> fastFirst = new ArrayList<>();
		List<Double> veryFirst = new ArrayList<>();
		List<Double> slowGaps = new ArrayList<>();
		List<Double> veryGaps = new ArrayList<>();
		AutoTermsConfig slow = new AutoTermsConfig();
		AutoTermsConfig fast = new AutoTermsConfig();
		fast.setTimeScale(0.8);
		AutoTermsConfig very = new AutoTermsConfig();
		very.setTimeScale(0.5);
		List<Integer> board = Arrays.asList(10, 11, 12, 19, 20, 21, 28, 29);
		for (long seed = 1; seed <= 150; seed++) {
			Run a = play(TerminalType.PANES, board, slow, seed, 0, 0, 30_000);
			Run b = play(TerminalType.PANES, board, fast, seed, 0, 0, 30_000);
			Run c = play(TerminalType.PANES, board, very, seed, 0, 0, 30_000);
			slowFirst.add((double) a.times.get(0));
			fastFirst.add((double) b.times.get(0));
			veryFirst.add((double) c.times.get(0));
			for (int i = 1; i < a.times.size(); i++) slowGaps.add((double) (a.times.get(i) - a.times.get(i - 1)));
			for (int i = 1; i < c.times.size(); i++) veryGaps.add((double) (c.times.get(i) - c.times.get(i - 1)));
		}
		double ratioFast = median(fastFirst) / median(slowFirst);
		double ratioVery = median(veryFirst) / median(slowFirst);
		check(ratioFast > 0.74 && ratioFast < 0.86, "Fast is about 20% quicker (" + ratioFast + ")");
		check(ratioVery > 0.44 && ratioVery < 0.56, "Very Fast is about 50% quicker (" + ratioVery + ")");
		double gapRatio = median(veryGaps) / median(slowGaps);
		check(gapRatio > 0.42 && gapRatio < 0.62, "Very Fast shortens the gaps between clicks too (" + gapRatio + ")");
	}

	private static boolean tilesInOrder(List<Integer> slots) {
		int changes = 0;
		for (int i = 1; i < slots.size(); i++) if (!slots.get(i).equals(slots.get(i - 1))) changes++;
		return changes == 2;
	}

	private static void checkRubixBursts() {
		AutoTermsConfig config = new AutoTermsConfig();
		config.setFinishTileChance(1.0);
		List<Double> within = new ArrayList<>();
		List<Double> between = new ArrayList<>();
		for (long seed = 1; seed <= 80; seed++) {
			Run run = play(TerminalType.RUBIX, Arrays.asList(12, 12, 30, 30, 40, 40, 40), config, seed, 0, 0, 30_000);
			check(run.slots.size() == 7 && tilesInOrder(run.slots), "a rubix tile is finished before the next one starts (" + run.slots + ")");
			for (int i = 1; i < run.slots.size(); i++) {
				double gap = run.times.get(i) - run.times.get(i - 1);
				(run.slots.get(i).equals(run.slots.get(i - 1)) ? within : between).add(gap);
			}
		}
		check(median(within) < 130, "repeat clicks on one tile come quickly (" + median(within) + ")");
		check(median(between) > 1.4 * median(within), "moving to the next tile takes longer than repeating (" + median(between) + ")");

		AutoTermsConfig never = new AutoTermsConfig();
		never.setFinishTileChance(0.0);
		boolean burst = false;
		for (long seed = 1; seed <= 20; seed++) {
			burst |= play(TerminalType.RUBIX, Arrays.asList(12, 12, 30, 30), never, seed, 0, 0, 30_000).events.stream()
				.anyMatch(e -> e.contains("burst=true"));
		}
		check(!burst, "with Finish Rubix Tile At Once at 0% no burst is planned");

		int identical = 0;
		for (long seed = 1; seed <= 40; seed++) {
			Run run = play(TerminalType.RUBIX, Arrays.asList(12, 12, 12, 12), config, seed, 0, 0, 30_000, 1.0);
			if (run.points.stream().map(a -> a[0] + "," + a[1]).distinct().count() == 1) identical++;
		}
		check(identical <= 4, "the clicks of a burst do not land on one identical spot (" + identical + "/40 runs)");
	}

	private static void checkMelodyPreAim() {
		AutoTermsConfig config = new AutoTermsConfig();
		for (long seed = 1; seed <= 40; seed++) {
			Run run = play(TerminalType.MELODY, Arrays.asList(19, 7, 43, 25), config, seed, 0, 3000, 8_000);
			check(run.slots.size() == 1 && run.times.get(0) > 3000, "melody still waits for the lit button");
			AutoBox target = box(25);
			double[] before = null;
			for (double[] at : run.trace) if (at[0] <= 2900) before = at;
			check(before != null && Math.abs(before[1] - target.getCx()) <= 0.5 * SIZE && Math.abs(before[2] - target.getCy()) <= 0.5 * SIZE,
				"the cursor waits on the right button before it lights (seed " + seed + ")");
		}
	}

	private static double deviation(double[] a, double[] b, double[] p) {
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double length = Math.hypot(dx, dy);
		return Math.abs(dx * (a[1] - p[1]) - dy * (a[0] - p[0])) / length;
	}

	private static void checkHumanCursor() {
		AutoTermsConfig config = new AutoTermsConfig();
		config.setCorrectionChance(1.0);
		int longMoves = 0;
		int bent = 0;
		for (long seed = 1; seed <= 60; seed++) {
			Run run = play(TerminalType.PANES, Arrays.asList(10, 17, 46, 53, 14, 49), config, seed, 0, 0, 30_000, 1.0);
			for (double[] at : run.trace) {
				check(Math.abs(at[1] - Math.rint(at[1])) < 1e-9 && Math.abs(at[2] - Math.rint(at[2])) < 1e-9,
					"the cursor is always on a whole physical pixel");
			}
			for (int i = 1; i < run.times.size(); i++) {
				double[] from = run.points.get(i - 1);
				double[] to = run.points.get(i);
				if (Math.hypot(to[0] - from[0], to[1] - from[1]) < 150) continue;
				longMoves++;
				double worst = 0;
				for (double[] at : run.trace) {
					if (at[0] > run.times.get(i - 1) + 20 && at[0] < run.times.get(i) - 20) {
						worst = Math.max(worst, deviation(from, to, new double[] { at[1], at[2] }));
					}
				}
				if (worst > 1.5) bent++;
			}
		}
		check(longMoves > 40 && bent >= 0.7 * longMoves, "long moves are curved, not ruler straight (" + bent + "/" + longMoves + ")");

		boolean corrected = false;
		AutoTermsConfig plain = new AutoTermsConfig();
		plain.setCorrectionChance(0.0);
		plain.setOvershootChance(0.0);
		for (long seed = 1; seed <= 20; seed++) {
			corrected |= play(TerminalType.PANES, Arrays.asList(10, 53, 14, 46), config, seed, 0, 0, 30_000).events.stream()
				.anyMatch(e -> e.contains("corr=1") || e.contains("corr=2"));
			check(play(TerminalType.PANES, Arrays.asList(10, 53, 14, 46), plain, seed, 0, 0, 30_000).events.stream()
				.noneMatch(e -> e.contains("corr=1") || e.contains("corr=2")), "no correction when both chances are 0");
		}
		check(corrected, "a long move can miss and be corrected");

		Run resting = play(TerminalType.PANES, Arrays.asList(10, 11), config, 3, 6_000, 0, 5_500);
		List<double[]> still = new ArrayList<>();
		for (double[] at : resting.trace) if (at[0] > 2000 && at[0] < 5000) still.add(at);
		long distinct = still.stream().map(a -> a[1]).distinct().count();
		double span = 0;
		for (double[] at : still) span = Math.max(span, Math.hypot(at[1] - still.get(0)[1], at[2] - still.get(0)[2]));
		check(distinct > 10 && span < 8, "waiting on a slot the hand still trembles and drifts a little (" + distinct + ", " + span + ")");
	}

	private static void checkOptions() {
		AutoTermsConfig still = new AutoTermsConfig();
		still.setMoveCursor(false);
		Run run = play(TerminalType.PANES, Arrays.asList(10, 20), still, 1, 0, 0, 30_000);
		check(!run.moved && run.slots.size() == 2, "with Move Cursor off it only clicks");

		AutoTermsConfig always = new AutoTermsConfig();
		always.setOvershootChance(1.0);
		AutoTermsConfig none = new AutoTermsConfig();
		none.setOvershootChance(0.0);
		boolean overshot = false;
		for (long seed = 1; seed <= 20; seed++) {
			overshot |= play(TerminalType.PANES, Arrays.asList(10, 14, 46, 50), always, seed, 0, 0, 30_000).events.stream()
				.anyMatch(e -> e.contains("overshoot=true"));
			check(play(TerminalType.PANES, Arrays.asList(10, 14, 46, 50), none, seed, 0, 0, 30_000).events.stream()
				.noneMatch(e -> e.contains("overshoot=true")), "no overshoot when the chance is 0");
		}
		check(overshot, "a long move can overshoot");

		AutoTermsEngine engine = new AutoTermsEngine(new AutoTermsConfig(), new Random(1));
		AutoTermsView view = new AutoTermsView(TerminalType.PANES, Arrays.asList(10, 11), AutoTermsChecks::box, s -> 0, false, 500, 300);
		engine.step(view, 0L);
		engine.pause();
		boolean clicked = false;
		for (long now = FRAME; now < 5_000_000_000L; now += FRAME) clicked |= engine.step(view, now).getClicks();
		check(engine.isPaused() && !clicked, "a paused engine does nothing");
		engine.reset();
		check(!engine.isPaused(), "a new terminal resumes");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError("AutoTermsChecks: " + message);
	}
}
