package geiler.addons.client.macros.input;

/**
 * The keys the macros hold. A person runs on W alone and turns the view to where they run; they do not strafe along a straight
 * line, and they do not back-run at a mob behind them - they turn first. These checks cover the mapping from the angle between
 * the view and the wanted heading to a key direction, and the "turn first" hold.
 */
public final class WalkKeysChecks {
	private WalkKeysChecks() { }

	public static void run() {
		checkStraightIsForwardOnly();
		checkMixBetweenSectors();
		checkHoldUntilTurned();
	}

	private static void checkStraightIsForwardOnly() {
		SectorMixer mixer = new SectorMixer();
		for (double off = -12.0; off <= 12.0; off += 0.5) {
			for (int tick = 0; tick < 40; tick++) {
				int sector = Math.floorMod(mixer.next(off / 45.0), 8);
				check(sector == 0, "within 12 degrees of the heading only W is held (" + off + " gave sector " + sector + ")");
			}
		}
		// View noise of a few degrees around the heading never makes an A or D tick.
		java.util.Random random = new java.util.Random(2);
		int strafes = 0;
		for (int tick = 0; tick < 2000; tick++) {
			double off = random.nextGaussian() * 3.0;
			if (Math.floorMod(mixer.next(off / 45.0), 8) != 0) strafes++;
		}
		check(strafes == 0, "3 degrees of view noise gives no strafe ticks (" + strafes + ")");
	}

	private static void checkMixBetweenSectors() {
		SectorMixer mixer = new SectorMixer();
		int forward = 0;
		int diagonal = 0;
		for (int tick = 0; tick < 400; tick++) {
			int sector = Math.floorMod(mixer.next(0.5), 8);
			if (sector == 0) forward++;
			if (sector == 1) diagonal++;
			check(sector == 0 || sector == 1, "halfway between W and W+D only those two are used");
		}
		check(forward > 150 && diagonal > 150, "halfway between them both get about half the ticks (" + forward + "/" + diagonal + ")");
		SectorMixer right = new SectorMixer();
		check(Math.floorMod(right.next(2.0), 8) == 2, "a quarter turn to the right is D");
		check(Math.floorMod(right.next(-2.0), 8) == 6, "a quarter turn to the left is A");
		check(Math.floorMod(right.next(4.0), 8) == 4, "straight behind is S");
	}

	private static void checkHoldUntilTurned() {
		check(TurnFirst.hold(90.0, 60.0, 0), "90 degrees off with a 60 degree limit holds the keys");
		check(TurnFirst.hold(-120.0, 60.0, 5), "to either side");
		check(!TurnFirst.hold(50.0, 60.0, 0), "inside the limit walks");
		check(!TurnFirst.hold(170.0, 0.0, 0), "a limit of zero is off");
		check(!TurnFirst.hold(170.0, 60.0, TurnFirst.MAX_HOLD_TICKS), "a hold that has lasted the longest it may gives way");
		check(TurnFirst.MAX_HOLD_TICKS >= 10 && TurnFirst.MAX_HOLD_TICKS <= 40, "and that is between half a second and two");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
