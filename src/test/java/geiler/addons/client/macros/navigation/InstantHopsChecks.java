package geiler.addons.client.macros.navigation;

import geiler.addons.client.macros.sim.EtherwarpMath;

/**
 * Instant Transmission hops toward a place the feet cannot walk to: a fixed-length jump along the view, tried in a fan of directions,
 * that lands on ground nearer the goal. The pit with a platform four blocks up is the case: look up steeply and the hop lands above it.
 */
public final class InstantHopsChecks {
	private InstantHopsChecks() { }

	public static void run() {
		checkClimbOutOfPit();
		checkFlatStretch();
		checkNothingToGain();
		checkNoRoom();
		checkDirectionIsALook();
	}

	/** Pit floor at y < 0 for x < 3, a wall from x = 3 whose top is the platform at y = 4 (solid below it). */
	private static final EtherwarpMath.Probe PIT = (x, y, z) -> {
		if (x < 3) return y < 0 ? EtherwarpMath.SOLID : EtherwarpMath.PASSABLE;
		return y < 4 ? EtherwarpMath.SOLID : EtherwarpMath.PASSABLE;
	};

	private static void checkClimbOutOfPit() {
		InstantHops.Hop hop = InstantHops.plan(PIT, 0.5, 0.0, 0.5, 6.5, 4.0, 0.5, 8.0, 6.0, (x, y, z) -> true);
		check(hop != null, "a platform four blocks up is reached by a hop");
		check(hop.landX > 3.0 && hop.standY == 4.0, "which lands on the platform (" + hop.landX + ", " + hop.standY + ")");
		check(hop.pitch < -40.0, "looking steeply up, past the edge (" + hop.pitch + ")");
		check(hop.fall <= 6.0, "and not further down than a fall allows");
		check(hop.length > 2.5 && hop.length <= 8.0 + 1.0E-9, "no further than the item carries (" + hop.length + ")");
		InstantHops.Hop refused = InstantHops.plan(PIT, 0.5, 0.0, 0.5, 6.5, 4.0, 0.5, 8.0, 6.0, (x, y, z) -> true, h -> false);
		check(refused == null, "a hop the caller does not accept (not walk-connected to the goal) is not returned");
	}

	private static void checkFlatStretch() {
		EtherwarpMath.Probe flat = (x, y, z) -> y < 0 ? EtherwarpMath.SOLID : EtherwarpMath.PASSABLE;
		InstantHops.Hop hop = InstantHops.plan(flat, 0.5, 0.0, 0.5, 0.5, 0.0, 30.5, 8.0, 6.0, (x, y, z) -> true);
		check(hop != null, "twenty blocks of flat ground: a hop gets nearer");
		check(Math.abs(hop.yaw) < 15.0 || Math.abs(Math.abs(hop.yaw) - 360.0) < 15.0, "toward the goal, south (" + hop.yaw + ")");
		check(hop.landZ > 6.0, "about its length along (" + hop.landZ + ")");
	}

	private static void checkNothingToGain() {
		EtherwarpMath.Probe flat = (x, y, z) -> y < 0 ? EtherwarpMath.SOLID : EtherwarpMath.PASSABLE;
		check(InstantHops.plan(flat, 0.5, 0.0, 0.5, 0.5, 0.0, 4.5, 8.0, 6.0, (x, y, z) -> true) == null,
			"a goal four blocks away is walked to: a hop of eight overshoots it");
	}

	private static void checkNoRoom() {
		EtherwarpMath.Probe closed = (x, y, z) -> (x == 0 && z == 0 && y >= 0 && y <= 1) ? EtherwarpMath.PASSABLE : EtherwarpMath.SOLID;
		check(InstantHops.plan(closed, 0.5, 0.0, 0.5, 0.5, 0.0, 30.5, 8.0, 6.0, (x, y, z) -> true) == null,
			"shut in on all sides there is no hop of the item's length");
		EtherwarpMath.Probe tunnel = (x, y, z) -> (y < 0 || y > 1 || x < 0 || x > 0) ? EtherwarpMath.SOLID : EtherwarpMath.PASSABLE;
		check(InstantHops.plan(tunnel, 0.5, 0.0, 0.5, 0.5, 0.0, 30.5, 8.0, 6.0, (x, y, z) -> true) != null,
			"a straight tunnel of the item's height does allow one along it");
		check(InstantHops.plan(tunnel, 0.5, 0.0, 0.5, 0.5, 0.0, 30.5, 8.0, 6.0, (x, y, z) -> z < 2) == null,
			"unless the landing is outside the region the macro may use");
	}

	private static void checkDirectionIsALook() {
		InstantHops.Hop hop = InstantHops.plan(PIT, 0.5, 0.0, 0.5, 6.5, 4.0, 0.5, 8.0, 6.0, (x, y, z) -> true);
		double yaw = Math.toRadians(hop.yaw);
		double pitch = Math.toRadians(hop.pitch);
		double dx = -Math.sin(yaw) * Math.cos(pitch);
		double dy = -Math.sin(pitch);
		double dz = Math.cos(yaw) * Math.cos(pitch);
		check(Math.abs(dx - hop.dirX) < 1.0E-6 && Math.abs(dy - hop.dirY) < 1.0E-6 && Math.abs(dz - hop.dirZ) < 1.0E-6,
			"yaw and pitch are the game's: 0 south, positive pitch looks down");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
