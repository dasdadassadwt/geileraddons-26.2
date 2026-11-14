package geiler.addons.client.macros.rotation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Rotator is one engine; these checks drive it frame by frame with seeded generators and test what makes a turn look
 * human: it arrives, it takes a time inside the range, its speed is not constant, its peak is not always in the middle,
 * turns differ from each other, it reacts late from rest, it stays level on level targets, it overshoots only when it may,
 * it follows a mover, it slows down instead of stopping dead, and it does the same at any frame rate.
 */
public final class RotatorChecks {
	private RotatorChecks() { }

	public static void run() {
		checkArrival();
		checkNotConstant();
		checkTurnsDiffer();
		checkReaction();
		checkLevelStaysLevel();
		checkOvershoot();
		checkPursuit();
		checkActivitySwitch();
		checkCoast();
		checkFrameRate();
		checkInstant();
		checkConfigRules();
		checkPresets();
	}

	private static final class Run {
		final List<double[]> positions = new ArrayList<>();
		double yaw;
		double pitch;
		double seconds;
	}

	/** Turns from (0,0) toward a fixed target at a fixed frame rate. */
	private static Run simulate(RotationConfig.Settings s, RotationConfig.Activity activity, double wantYaw, double wantPitch,
		long seed, int fps, double length) {
		Rotator rotator = new Rotator(new Random(seed));
		Run run = new Run();
		double dt = 1.0 / fps;
		int frames = (int) Math.round(length * fps);
		for (int i = 0; i < frames; i++) {
			double[] step = rotator.step(s, activity, run.yaw, run.pitch, wantYaw, wantPitch, 7.5, dt);
			run.yaw += step[0];
			run.pitch += step[1];
			run.seconds += dt;
			run.positions.add(new double[] {run.yaw, run.pitch});
		}
		return run;
	}

	/** Seconds until the view is within a degree of the target and stays there, or -1. */
	private static double arrival(Run run, double wantYaw, double wantPitch, int fps) {
		for (int i = 0; i < run.positions.size(); i++) {
			boolean stays = true;
			for (int j = i; j < run.positions.size(); j++) {
				double[] p = run.positions.get(j);
				if (Math.hypot(p[0] - wantYaw, p[1] - wantPitch) > 1.0) { stays = false; break; }
			}
			if (stays) return (i + 1) / (double) fps;
		}
		return -1;
	}

	private static RotationConfig.Settings clean() {
		RotationConfig.Settings s = new RotationConfig.Settings();
		s.humanized = false;
		return s;
	}

	private static void checkArrival() {
		for (RotationConfig.Activity activity : RotationConfig.Activity.values()) {
			for (long seed = 1; seed <= 5; seed++) {
				RotationConfig.Settings s = new RotationConfig.Settings();
				Run run = simulate(s, activity, 60, -25, seed, 60, 3.0);
				double[] end = run.positions.get(run.positions.size() - 1);
				check(Math.hypot(end[0] - 60, end[1] + 25) < 0.5, activity + " arrives within half a degree (seed " + seed + ", ended " + end[0] + "," + end[1] + ", t90=" + run.positions.get(90)[0] + "," + run.positions.get(90)[1] + ")");
				for (double[] p : run.positions) check(!Double.isNaN(p[0]) && !Double.isNaN(p[1]), "no NaN while turning");
			}
		}
		// Clean turn: the duration lies inside the time range.
		RotationConfig.Settings s = clean();
		double t = arrival(simulate(s, RotationConfig.Activity.GENERAL, 90, 0, 1, 60, 3.0), 90, 0, 60);
		check(t > s.timeMinMs / 1000.0 && t < s.timeMaxMs / 1000.0 * 1.4, "a clean 90 degree turn takes a time inside the range (" + t + " s)");
		s.timeMinMs = 400;
		s.timeMaxMs = 400;
		double fixed = arrival(simulate(s, RotationConfig.Activity.COMBAT, 20, 0, 1, 60, 3.0), 20, 0, 60);
		check(fixed > 0.3 && fixed < 0.75, "turn time min = max pins the turn near that time (" + fixed + " s)");
		// A tiny turn is not instant.
		RotationConfig.Settings tiny = clean();
		double small = arrival(simulate(tiny, RotationConfig.Activity.COMBAT, 4, 0, 1, 60, 2.0), 4, 0, 60);
		check(small >= 0.08, "even a 4 degree turn takes at least about the minimum time (" + small + " s)");
	}

	private static void checkNotConstant() {
		double[] peaks = new double[20];
		for (int seed = 0; seed < 20; seed++) {
			RotationConfig.Settings s = new RotationConfig.Settings();
			s.pausesPerSecond = 0;
			Run run = simulate(s, RotationConfig.Activity.GENERAL, 80, 0, 100 + seed, 120, 2.0);
			double best = 0;
			int bestAt = 0;
			double slowest = Double.MAX_VALUE;
			double previous = 0;
			for (int i = 0; i < run.positions.size(); i++) {
				double speed = (run.positions.get(i)[0] - previous) * 120;
				previous = run.positions.get(i)[0];
				if (speed > best) { best = speed; bestAt = i; }
			}
			// The speed in the middle third against the peak: not flat.
			double early = (run.positions.get(5)[0] - run.positions.get(0)[0]) * 120.0 / 5.0;
			check(early < best * 0.8, "the turn starts slower than its peak speed (seed " + seed + ")");
			double traveled = run.positions.get(bestAt)[0];
			peaks[seed] = traveled / 80.0;
		}
		double mean = 0;
		for (double p : peaks) mean += p / peaks.length;
		double variance = 0;
		for (double p : peaks) variance += (p - mean) * (p - mean) / peaks.length;
		check(Math.sqrt(variance) > 0.015, "the peak speed comes at a different point of the turn each time (spread " + Math.sqrt(variance) + ")");
		check(mean < 0.62, "the peak comes earlier than the end of the turn on average (" + mean + ")");
	}

	private static void checkTurnsDiffer() {
		double low = Double.MAX_VALUE;
		double high = 0;
		for (long seed = 0; seed < 30; seed++) {
			RotationConfig.Settings s = new RotationConfig.Settings();
			double t = arrival(simulate(s, RotationConfig.Activity.COMBAT, 70, -10, 500 + seed, 60, 3.0), 70, -10, 60);
			low = Math.min(low, t);
			high = Math.max(high, t);
		}
		check(high - low > 0.1, "the same turn takes different times (" + low + " to " + high + " s)");
	}

	private static void checkReaction() {
		RotationConfig.Settings s = new RotationConfig.Settings();
		s.reactionMinMs = 200;
		s.reactionMaxMs = 200;
		s.overshootChance = 0;
		Run run = simulate(s, RotationConfig.Activity.COMBAT, 50, 0, 7, 100, 1.0);
		double early = 0;
		for (int i = 0; i < 15; i++) early = Math.max(early, Math.abs(run.positions.get(i)[0]));
		check(early == 0.0, "no movement during the first 150 ms of a reaction delay of 200 ms");
		check(Math.abs(run.positions.get(60)[0]) > 1.0, "the turn has started by 600 ms");
		// A teleport lineup does not wait.
		Run warp = simulate(s, RotationConfig.Activity.ETHERWARP, 50, 0, 7, 100, 0.2);
		check(Math.abs(warp.positions.get(5)[0]) > 0.0, "an etherwarp turn starts at once");
	}

	private static void checkLevelStaysLevel() {
		for (long seed = 0; seed < 10; seed++) {
			RotationConfig.Settings s = new RotationConfig.Settings();
			s.overshootChance = 100;
			s.curveStrength = 3;
			s.verticalMovement = 2;
			Run run = simulate(s, RotationConfig.Activity.COMBAT, 85, 0, seed, 60, 3.0);
			double most = 0;
			for (double[] p : run.positions) most = Math.max(most, Math.abs(p[1]));
			check(most < 1.0E-9, "a level target on a level view needs no pitch at all, whatever the effects say (seed " + seed + ", " + most + ")");
		}
		// With pitch to turn, the pitch does turn.
		RotationConfig.Settings s = new RotationConfig.Settings();
		Run run = simulate(s, RotationConfig.Activity.COMBAT, 40, -30, 3, 60, 3.0);
		check(Math.abs(run.pitch + 30) < 0.5, "a target 30 degrees up is reached in pitch");
	}

	private static void checkOvershoot() {
		RotationConfig.Settings s = new RotationConfig.Settings();
		s.overshootChance = 100;
		s.overshootSize = 10;
		s.pausesPerSecond = 0;
		int overshoots = 0;
		for (long seed = 0; seed < 10; seed++) {
			Run run = simulate(s, RotationConfig.Activity.GENERAL, 90, 0, seed, 120, 3.0);
			double most = 0;
			for (double[] p : run.positions) most = Math.max(most, p[0]);
			if (most > 91.5) overshoots++;
			double[] end = run.positions.get(run.positions.size() - 1);
			check(Math.abs(end[0] - 90) < 0.5, "after an overshoot the view comes back to the target (seed " + seed + ")");
		}
		check(overshoots >= 7, "with 100 % overshoot chance most long turns go past the target (" + overshoots + " of 10)");
		for (long seed = 0; seed < 10; seed++) {
			Run run = simulate(s, RotationConfig.Activity.ETHERWARP, 90, 0, seed, 120, 3.0);
			double most = 0;
			for (double[] p : run.positions) most = Math.max(most, p[0]);
			check(most <= 90.0 + 1.0E-6, "an etherwarp lineup never passes its target (seed " + seed + ")");
			double[] end = run.positions.get(run.positions.size() - 1);
			check(Math.abs(end[0] - 90) < 0.02 && Math.abs(end[1]) < 0.02, "an etherwarp lineup lands within a fiftieth of a degree");
		}
	}

	private static void checkPursuit() {
		RotationConfig.Settings s = new RotationConfig.Settings();
		Rotator rotator = new Rotator(new Random(11));
		double yaw = 0;
		double pitch = 0;
		double worst = 0;
		double dt = 1.0 / 60;
		for (int i = 0; i < 240; i++) {
			double want = 40.0 * i * dt;
			double[] step = rotator.step(s, RotationConfig.Activity.COMBAT, yaw, pitch, want, 0, 6.0, dt);
			yaw += step[0];
			pitch += step[1];
			if (i > 90) worst = Math.max(worst, Math.abs(want - yaw));
		}
		check(worst < 6.0, "a target moving at 40 degrees a second is kept up with (worst lag " + worst + ")");
	}

	private static void checkActivitySwitch() {
		// The view alternating between a mob (Combat) and a point on the way (General) at the same angle is one turn, not a
		// new one with a new draw at every switch: that is what made the view look chaotic.
		RotationConfig.Settings s = new RotationConfig.Settings();
		java.util.List<String> lines = new ArrayList<>();
		Rotator.diagnostics = line -> {
			lines.add(line);
			return kotlin.Unit.INSTANCE;
		};
		try {
			Rotator rotator = new Rotator(new Random(3));
			double yaw = 0;
			double pitch = 0;
			double dt = 1.0 / 60;
			for (int i = 0; i < 120; i++) {
				RotationConfig.Activity activity = (i / 6) % 2 == 0 ? RotationConfig.Activity.COMBAT : RotationConfig.Activity.GENERAL;
				double[] step = rotator.step(s, activity, yaw, pitch, 60, 0, 7.5, dt);
				yaw += step[0];
				pitch += step[1];
			}
			long turns = lines.stream().filter(l -> l.startsWith("turn n=")).count();
			check(turns == 1, "switching the activity at the same angle is not a new turn (" + turns + " turns)");
			check(Math.abs(yaw - 60) < 0.5, "and the turn still arrives (" + yaw + ")");
			check(lines.stream().anyMatch(l -> l.contains("cause=first")), "a turn says why it began");

			// A real jump of the wanted angle is a new turn, whatever the activity.
			lines.clear();
			Rotator second = new Rotator(new Random(3));
			yaw = 0;
			pitch = 0;
			for (int i = 0; i < 90; i++) {
				double[] step = second.step(s, RotationConfig.Activity.COMBAT, yaw, pitch, i < 45 ? 60 : -80, 0, 7.5, dt);
				yaw += step[0];
				pitch += step[1];
			}
			check(lines.stream().filter(l -> l.startsWith("turn n=")).count() == 2, "a new angle far from the old one is a new turn");
			check(lines.stream().anyMatch(l -> l.contains("cause=jump")), "and says it was the jump");

			// A teleport's exact angles are never carried on from a turn that has a wander and an overshoot.
			lines.clear();
			Rotator precise = new Rotator(new Random(3));
			yaw = 0;
			pitch = 0;
			for (int i = 0; i < 40; i++) {
				RotationConfig.Activity activity = i < 20 ? RotationConfig.Activity.COMBAT : RotationConfig.Activity.TELEPORT;
				double[] step = precise.step(s, activity, yaw, pitch, 60, 0, Double.NaN, dt);
				yaw += step[0];
				pitch += step[1];
			}
			check(lines.stream().filter(l -> l.startsWith("turn n=")).count() == 2, "a switch to a precise activity starts a new turn");
		} finally {
			Rotator.diagnostics = null;
		}
	}

	private static void checkCoast() {
		RotationConfig.Settings s = new RotationConfig.Settings();
		s.pausesPerSecond = 0;
		Rotator rotator = new Rotator(new Random(5));
		double yaw = 0;
		double dt = 1.0 / 60;
		for (int i = 0; i < 20; i++) {
			yaw += rotator.step(s, RotationConfig.Activity.GENERAL, yaw, 0, 120, 0, 8.0, dt)[0];
		}
		double speed = rotator.speed();
		check(speed > 50.0, "the view is turning fast mid-turn (" + speed + ")");
		double previous = speed;
		double travelled = 0;
		int frames = 0;
		while (rotator.speed() > 0.0 && frames < 120) {
			double[] c = rotator.coast(dt);
			travelled += Math.abs(c[0]);
			check(rotator.speed() <= previous + 1.0E-9, "coasting only slows");
			previous = rotator.speed();
			frames++;
			if (c[0] == 0.0 && c[1] == 0.0) break;
		}
		check(frames > 6, "coasting takes several frames, not one (" + frames + ")");
		check(travelled < speed * 0.2, "and covers a short distance (" + travelled + " degrees)");
	}

	private static void checkFrameRate() {
		for (int fps : new int[] {30, 60, 144, 240}) {
			RotationConfig.Settings s = new RotationConfig.Settings();
			s.reactionMinMs = 0;
			s.reactionMaxMs = 0;
			s.overshootChance = 0;
			s.pausesPerSecond = 0;
			s.speedNoise = 0;
			s.aimWander = 0;
			double[] times = new double[6];
			for (int seed = 0; seed < 6; seed++) times[seed] = arrival(simulate(s, RotationConfig.Activity.COMBAT, 70, -10, seed, fps, 3.0), 70, -10, fps);
			double base = arrival(simulate(s, RotationConfig.Activity.COMBAT, 70, -10, 0, 60, 3.0), 70, -10, 60);
			check(Math.abs(times[0] - base) < 0.12, "the same turn takes the same time at " + fps + " fps (" + times[0] + " against " + base + ")");
		}
	}

	private static void checkInstant() {
		RotationConfig.Settings s = new RotationConfig.Settings();
		s.instant = true;
		double[] step = new Rotator(new Random(1)).step(s, RotationConfig.Activity.GENERAL, 10, 5, 100, -20, 5.0, 1.0 / 60);
		check(step[0] == 90.0 && step[1] == -25.0, "instant rotations land in one frame");
	}

	private static void checkConfigRules() {
		RotationConfig config = new RotationConfig();
		RotationConfig.Settings d = config.settings;
		check(d.humanized && !d.instant && d.effectIntensity == 1.0, "humanized rotations are on by default, instant off");
		check(RotationConfig.Activity.values().length == 4, "the activities are General, Etherwarp, Teleport and Combat");
		check(RotationConfig.Activity.ETHERWARP.getPrecise() && RotationConfig.Activity.TELEPORT.getPrecise()
			&& !RotationConfig.Activity.COMBAT.getPrecise() && !RotationConfig.Activity.GENERAL.getPrecise(), "only the teleports are precise");
		check(d.paceMin == 0.8 && d.paceMax == 1.2 && d.timeMinMs == 90 && d.timeMaxMs == 650 && d.reactionMinMs == 70
			&& d.reactionMaxMs == 230, "default speed, time and reaction ranges");
		check(d.distancePoints.size() == 6 && d.distancePoints.get(5)[0] == 20.0, "six default distance points");

		d.distanceScaling = true;
		d.distancePoints = new ArrayList<>(List.of(new double[] {2, 1.0}, new double[] {10, 1.5}));
		check(d.distanceFactor(1.0) == 1.0 && d.distanceFactor(6.0) == 1.25 && d.distanceFactor(50.0) == 1.5
			&& Double.isNaN(d.distanceFactor(Double.NaN) - 1.0) == false, "the distance factor is linear between points and flat beyond them");
		d.distanceScaling = false;
		check(d.distanceFactor(6.0) == 1.0, "with scaling off the factor is 1");

		RotationConfig.Settings wild = new RotationConfig.Settings();
		wild.paceMin = 9.0; wild.paceMax = 0.0; wild.timeMinMs = 9999; wild.timeMaxMs = 1; wild.reactionMinMs = 5000;
		wild.reactionMaxMs = 0; wild.effectIntensity = 9.0; wild.verticalMovement = -3; wild.overshootChance = -5;
		wild.newTurnDegrees = 99; wild.aimWander = 9;
		wild.distancePoints = new ArrayList<>(List.of(new double[] {50, 9.0}, new double[] {-1, 0.0}, new double[] {1}));
		wild.normalize();
		check(wild.paceMin == 3.0 && wild.paceMax == 3.0, "normalize clamps and orders the speed range");
		check(wild.timeMinMs == 3000.0 && wild.timeMaxMs == 3000.0, "normalize clamps and orders the time range");
		RotationConfig.Settings ranged = new RotationConfig.Settings();
		ranged.overshootChanceMin = 80.0; ranged.overshootChance = 30.0; ranged.aimWanderMin = 0.4; ranged.aimWander = 0.1;
		ranged.normalize();
		check(ranged.overshootChanceMin == 30.0 && ranged.aimWanderMin == 0.1, "normalize keeps the low end of an effect range under its high end");
		for (RotationConfig.Preset preset : RotationConfig.BUILT_IN) {
			RotationConfig.Settings p = preset.settings;
			p.normalize();
			check(p.overshootChanceMin <= p.overshootChance && p.overshootSizeMin <= p.overshootSize && p.curveStrengthMin <= p.curveStrength
				&& p.speedNoiseMin <= p.speedNoise && p.pausesPerSecondMin <= p.pausesPerSecond && p.aimWanderMin <= p.aimWander,
				preset.name + " has every effect range in order");
		}
		check(wild.reactionMinMs == 800.0 && wild.reactionMaxMs == 800.0, "normalize clamps and orders the reaction range");
		check(wild.effectIntensity == 2.0 && wild.verticalMovement == 0.0 && wild.overshootChance == 0.0
			&& wild.newTurnDegrees == 30.0 && wild.aimWander == 0.5, "normalize clamps every value into its range");
		check(wild.distancePoints.size() == 2 && wild.distancePoints.get(0)[0] == 0.0 && wild.distancePoints.get(0)[1] == 0.5
			&& wild.distancePoints.get(1)[0] == 30.0 && wild.distancePoints.get(1)[1] == 1.5,
			"normalize drops malformed points, clamps and sorts the rest");
	}

	private static void checkPresets() {
		RotationConfig presets = new RotationConfig();
		check(presets.presetNames().equals(List.of("Human", "Relaxed", "Fast", "Precise")), "four built-in presets, Human first");
		check(presets.find("fast") != null && presets.isBuiltIn("Fast") && !presets.isBuiltIn("Mine"), "built-in presets are found by name in any case");
		RotationConfig.Settings human = presets.find("Human").settings;
		RotationConfig.Settings fast = presets.find("Fast").settings;
		check(human.paceMin == new RotationConfig.Settings().paceMin && fast.paceMin > human.paceMax
			&& presets.find("Relaxed").settings.paceMax < human.paceMax, "the built-ins differ the way their names say");
		check(!presets.deletePreset(), "deleting with nothing saved chosen does nothing");

		presets.settings.effectIntensity = 1.5;
		presets.saveAs("");
		check("Preset 1".equals(presets.preset), "a blank name becomes Preset 1");
		presets.settings.effectIntensity = 0.5;
		presets.saveAs("preset 1");
		check(presets.presets.size() == 1 && presets.presets.get(0).settings.effectIntensity == 0.5,
			"saving under an existing name (any case) overwrites that preset");
		presets.saveAs("Second");
		check(presets.presets.size() == 2 && "Second".equals(presets.preset), "a new name appends and becomes active");
		check(presets.presetNames().size() == 6, "saved presets are listed after the built-in ones");

		presets.settings.effectIntensity = 0.1;
		presets.saveAs("fast");
		check(presets.find("Fast").settings.effectIntensity == 0.1 && !presets.isBuiltIn("Fast") && presets.presetNames().size() == 6,
			"saving under a built-in name shadows it without listing it twice");
		check(presets.deletePreset() && presets.find("Fast").settings.effectIntensity != 0.1 && presets.isBuiltIn("Fast"),
			"deleting the shadow brings the built-in preset back");
		presets.preset = "Human";
		check(!presets.deletePreset() && presets.find("Human") != null, "a built-in preset cannot be deleted");
		presets.preset = "Second";
		check(presets.deletePreset() && presets.presets.size() == 1 && "".equals(presets.preset),
			"deleting removes the chosen saved preset and leaves none chosen");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
