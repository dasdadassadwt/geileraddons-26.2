package geiler.addons.client.dungeon.f7;

import java.util.Random;

/** Offline checks for the Lever Aim Assist rules: angles, target pick, the stop-short pull and the trigger delay. */
public final class LeverAimChecks {
	private LeverAimChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkAngles();
		checkPick();
		checkDelay();
		checkPull();
		checkTrigger();
		checkLeverBlockSearch();
	}

	private static void checkAngles() {
		close(LeverAimRules.yawTo(0, 1), 0, "south is yaw 0");
		close(LeverAimRules.yawTo(-1, 0), 90, "west is yaw 90");
		close(LeverAimRules.yawTo(1, 0), -90, "east is yaw -90");
		close(LeverAimRules.pitchTo(0, -1, 0), 90, "straight down is pitch 90");
		close(LeverAimRules.pitchTo(0, 0, 5), 0, "level is pitch 0");
		close(LeverAimRules.pitchTo(0, 1, 1), -45, "up at 45 degrees is pitch -45");
		close(LeverAimRules.wrap(190), -170, "190 wraps to -170");
		close(LeverAimRules.wrap(-190), 170, "-190 wraps to 170");
		close(LeverAimRules.angularDistance(179, 0, -179, 0), 2, "yaw distance wraps around 180");
		close(LeverAimRules.angularDistance(0, 0, 3, 4), 5, "yaw and pitch combine");
	}

	private static void checkPick() {
		require(LeverAimRules.pick(new double[] {10, 3, 5}, 4) == 1, "the nearest lever inside the cone wins");
		require(LeverAimRules.pick(new double[] {10, 3, 5}, 2) == -1, "no lever inside the cone is -1");
		require(LeverAimRules.pick(new double[] {}, 30) == -1, "no levers is -1");
		require(LeverAimRules.pick(new double[] {4, 4}, 5) == 0, "a tie keeps the first lever");
	}

	private static void checkDelay() {
		require(LeverAimRules.drawDelayMs(40, 120, 0.0) == 40 && LeverAimRules.drawDelayMs(40, 120, 1.0) == 120,
			"the delay is drawn between its two values");
		require(LeverAimRules.drawDelayMs(120, 40, 0.5) == 80, "swapped values are put in order");
		require(LeverAimRules.drawDelayMs(-5, -1, 0.5) == 0, "a negative delay is 0");
	}

	/** Runs the pull for [seconds] at 60 frames a second, turning an error of ([yaw], [pitch]) as the view would. */
	private static double[] simulate(LeverAimRules.Pull pull, long key, double yaw, double pitch, double strength,
		double seconds) {
		double[] err = {yaw, pitch};
		for (int i = 0; i < seconds * 60; i++) {
			double[] step = pull.step(key, err[0], err[1], 1.0 / 60.0, strength, 8.0);
			err[0] -= step[0];
			err[1] -= step[1];
		}
		return err;
	}

	private static void checkPull() {
		LeverAimRules.Pull pull = new LeverAimRules.Pull();
		double[] left = simulate(pull, 1, 10, 0, 0.5, 3);
		close(left[0], 5, 0.1, "a 50 % pull stops about halfway");
		close(left[1], 0, 1e-9, "a pure yaw error stays pure yaw");

		pull = new LeverAimRules.Pull();
		left = simulate(pull, 1, 6, -8, 0.5, 3);
		close(left[0], 3, 0.1, "yaw share of a mixed error is halved");
		close(left[1], -4, 0.1, "pitch share of a mixed error is halved");

		pull = new LeverAimRules.Pull();
		left = simulate(pull, 1, 10, 0, 0.0, 3);
		close(left[0], 10, 1e-9, "strength 0 never turns the view");

		pull = new LeverAimRules.Pull();
		left = simulate(pull, 1, 10, 0, 1.0, 5);
		require(left[0] >= 0 && left[0] < 0.2, "strength 100 % closes the error without passing the lever");

		pull = new LeverAimRules.Pull();
		simulate(pull, 1, 10, 0, 0.5, 3);
		double[] hold = pull.step(1, 5.0, 0, 1.0 / 60.0, 0.5, 8.0);
		require(Math.abs(hold[0]) < 0.01, "a spent pull stays spent while the lever is held");

		// The player turns away: the error grows past where the pull began, so a fresh pull starts.
		double[] again = pull.step(1, 20.0, 0, 1.0 / 60.0, 0.5, 8.0);
		require(again[0] > 0.0, "turning away starts a new pull");

		// Another lever starts a new pull as well.
		double[] other = pull.step(2, 6.0, 0, 1.0 / 60.0, 0.5, 8.0);
		require(other[0] > 0.0, "a different lever starts a new pull");

		pull.reset();
		double[] fresh = pull.step(2, 6.0, 0, 1.0 / 60.0, 0.5, 8.0);
		require(fresh[0] > 0.0, "reset forgets the spent pull");

		double[] zero = new LeverAimRules.Pull().step(1, 0.0, 0.0, 1.0 / 60.0, 0.5, 8.0);
		require(zero[0] == 0.0 && zero[1] == 0.0, "no error is no pull");
	}

	private static void checkTrigger() {
		LeverAimRules.Trigger t = new LeverAimRules.Trigger(new Random(7));
		long none = LeverAimRules.NO_KEY;
		require(!t.update(1, true, true, 0, 100, 100), "the first sight only arms the delay");
		require(!t.update(1, true, true, 99, 100, 100), "the click waits for the delay");
		require(!t.update(1, true, false, 100, 100, 100), "the click waits until the crosshair is on the lever");
		require(t.update(1, true, true, 101, 100, 100), "delay over and aimed clicks");
		require(!t.update(1, true, true, 102, 100, 100), "a lever just clicked is not clicked again");
		require(!t.update(1, true, true, 1700, 100, 100), "after the cooldown the delay starts over");
		require(t.update(1, true, true, 1801, 100, 100), "and clicks again once it has passed");

		t = new LeverAimRules.Trigger(new Random(7));
		require(!t.update(1, true, true, 0, 100, 100), "armed on lever 1");
		require(!t.update(2, true, true, 90, 100, 100), "switching lever restarts the delay");
		require(!t.update(2, true, true, 150, 100, 100), "and counts from the switch");
		require(t.update(2, true, true, 190, 100, 100), "lever 2 clicks 100 ms after the switch");

		t = new LeverAimRules.Trigger(new Random(7));
		require(!t.update(1, true, true, 0, 100, 100), "armed");
		require(!t.update(none, false, true, 50, 100, 100), "no lever clears the arming");
		require(!t.update(1, true, true, 120, 100, 100), "a lever coming back starts the delay again");
		require(t.update(1, true, true, 221, 100, 100), "and clicks after it");

		t = new LeverAimRules.Trigger(new Random(7));
		require(!t.update(1, false, true, 0, 0, 0), "outside the cone nothing is armed");
		for (int i = 0; i < 200; i++) {
			long now = 100_000L * i;
			t.update(1000 + i, true, true, now, 10, 20);
			require(t.update(1000 + i, true, true, now + 25, 10, 20), "a delay of at most 20 ms has passed after 25 ms");
		}
		require(t.cooldownEntries() <= 16, "the cooldown list stays small");
		long drawn = t.lastDelayMs();
		require(drawn >= 10 && drawn <= 20, "the drawn delay is inside the two values");
	}

	private static void checkLeverBlockSearch() {
		// Stand at the block's centre: lever is that block. Stand offset by up to a block: nearest lever block wins.
		int[] pick = LeverAimRules.nearestBlock(10.5, 124.0, 113.5, new int[][] {{10, 124, 113}, {11, 124, 113}});
		require(pick != null && pick[0] == 10, "the lever block under the stand wins");
		pick = LeverAimRules.nearestBlock(10.5, 124.0, 113.5, new int[][] {{12, 124, 113}});
		require(pick == null, "a lever block two blocks away is not the stand's");
		pick = LeverAimRules.nearestBlock(10.5, 124.0, 113.5, new int[][] {});
		require(pick == null, "no lever blocks is no match");
	}

	private static void close(double actual, double expected, String message) {
		close(actual, expected, 1e-9, message);
	}

	private static void close(double actual, double expected, double tolerance, String message) {
		require(Math.abs(actual - expected) <= tolerance, message + " (got " + actual + ", expected " + expected + ")");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError("LeverAimChecks: " + message);
	}
}
