package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;

/** Offline checks for the Freecam flight state and module identity. */
public final class FreecamChecks {
	private static final double EPS = 1.0E-5;

	private FreecamChecks() { }

	public static void run() {
		FreecamModule module = FreecamModule.INSTANCE;
		check("Freecam".equals(module.name()) && module.category() == Category.VISUAL && !module.isEnabled()
			&& module.numberSettings().size() == 1 && module.numberSettings().get(0).value() == 1.0f,
			"Freecam is a default-off Visual module with one speed setting");
		check(!module.captureTurn(5, 5), "a disabled Freecam does not consume mouse turns");

		FreecamState state = new FreecamState();
		check(!state.getSeeded(), "a fresh state is not seeded");
		state.seed(10, 64, -5, 0f, 0f);
		state.setKeys(true, false, false, false, false, false);
		state.advance(1f, 1f);
		check(near(state.getX(), 10) && near(state.getY(), 64) && near(state.getZ(), -4),
			"forward at yaw 0 pitch 0 moves +Z by speed*ticks");
		state.seed(0, 0, 0, 90f, 0f);
		state.setKeys(true, false, false, false, false, false);
		state.advance(2f, 1f);
		check(near(state.getX(), -2) && near(state.getZ(), 0), "forward at yaw 90 moves -X");
		state.seed(0, 0, 0, 0f, 90f);
		state.setKeys(true, false, false, false, false, false);
		state.advance(1f, 1f);
		check(near(state.getY(), -1) && near(state.getZ(), 0), "forward while looking straight down moves down");
		state.seed(0, 0, 0, 0f, 0f);
		state.setKeys(false, false, false, true, false, false);
		state.advance(1f, 1f);
		check(near(state.getX(), -1), "strafing right at yaw 0 moves -X");
		state.seed(0, 0, 0, 0f, 0f);
		state.setKeys(false, false, false, false, true, false);
		state.advance(1f, 3f);
		check(near(state.getY(), 3), "jump flies straight up at the configured speed");
		state.setKeys(false, false, false, false, false, true);
		state.advance(1f, 3f);
		check(near(state.getY(), 0), "shift flies straight down");
		state.seed(0, 0, 0, 0f, 0f);
		state.setKeys(true, true, true, true, true, true);
		state.advance(1f, 1f);
		check(near(state.getX(), 0) && near(state.getY(), 0) && near(state.getZ(), 0), "opposite keys cancel out");
		state.seed(0, 0, 0, 0f, 0f);
		state.setKeys(true, false, false, true, false, false);
		state.advance(1f, 1f);
		check(near(Math.sqrt(state.getX() * state.getX() + state.getZ() * state.getZ()), 1),
			"diagonal flight keeps the configured speed");
		state.seed(0, 0, 0, 0f, 0f);
		state.turn(100, 1000);
		check(near(state.getYaw(), 15f) && near(state.getPitch(), 90f), "mouse turns use 0.15 degrees and clamp pitch");
		state.turn(0, -10000);
		check(near(state.getPitch(), -90f), "pitch clamps at straight up");
		state.setKeys(true, false, false, false, false, false);
		state.clear();
		state.seed(0, 0, 0, 0f, 0f);
		state.advance(1f, 1f);
		check(near(state.getZ(), 0), "clear drops the held keys");
		state.seed(0, 0, 0, 0f, 0f);
		state.setKeys(true, false, false, false, false, false);
		state.advance(0f, 1f);
		state.advance(1f, 0f);
		check(near(state.getZ(), 0), "zero ticks or zero speed does not move the camera");
		check(FreecamState.mask(true, false, false, false, true, false) == (FreecamState.FORWARD | FreecamState.UP),
			"key mask packs the held keys");
	}

	private static boolean near(double a, double b) {
		return Math.abs(a - b) < EPS;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("FreecamChecks: " + message);
	}
}
