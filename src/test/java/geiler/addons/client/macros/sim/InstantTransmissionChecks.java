package geiler.addons.client.macros.sim;

/**
 * The Aspect of the Void's Instant Transmission (right click, no sneak): 8 blocks along the look direction, one more for each
 * Transmission Tuner up to four, stopping short of whatever is in the way. Landing is where the body still fits.
 */
public final class InstantTransmissionChecks {
	private InstantTransmissionChecks() { }

	/** A world of air with a floor of solid blocks below y = 0 and optional walls. */
	private static EtherwarpMath.Probe world(java.util.function.IntBinaryOperator... unused) {
		return (x, y, z) -> y < 0 ? EtherwarpMath.SOLID : EtherwarpMath.PASSABLE;
	}

	public static void run() {
		checkDistance();
		checkOpenAir();
		checkWall();
		checkFloor();
		checkBlockedAtOnce();
		checkCentred();
	}

	private static void checkDistance() {
		check(InstantTransmission.distance(0) == 8.0, "8 blocks with no tuner");
		check(InstantTransmission.distance(4) == 12.0, "12 with four");
		check(InstantTransmission.distance(9) == 12.0, "four is the most");
		check(InstantTransmission.distance(-3) == 8.0, "and never fewer than 8");
	}

	private static void checkOpenAir() {
		InstantTransmission.Result r = InstantTransmission.trace(world(), 0.5, 5.0, 0.5, 0.0, 0.0, 1.0, 8.0);
		check(r.moved, "open air: moves");
		check(Math.abs(r.distance - 8.0) < 0.11, "all the way, 8 blocks (" + r.distance + ")");
		check(Math.abs(r.z - 8.5) < 0.11 && Math.abs(r.x - 0.5) < 1.0E-9 && Math.abs(r.y - 5.0) < 1.0E-9, "straight along the look direction");
		InstantTransmission.Result tuned = InstantTransmission.trace(world(), 0.5, 5.0, 0.5, 0.0, 0.0, 1.0, InstantTransmission.distance(4));
		check(Math.abs(tuned.distance - 12.0) < 0.11, "12 with four tuners");
		InstantTransmission.Result diagonal = InstantTransmission.trace(world(), 0.5, 5.0, 0.5, 1.0, 1.0, 0.0, 8.0);
		check(Math.abs(diagonal.x - 0.5 - diagonal.y + 5.0) < 0.75, "up at 45 degrees goes up as far as it goes along (block middle aside)");
	}

	private static void checkCentred() {
		InstantTransmission.Result r = InstantTransmission.trace(world(), 0.3, 5.0, 0.8, 0.3, 0.0, 1.0, 8.0);
		double fx = r.x - Math.floor(r.x);
		double fz = r.z - Math.floor(r.z);
		check(r.moved && Math.abs(fx - 0.5) < 1.0E-9 && Math.abs(fz - 0.5) < 1.0E-9, "the player lands in the middle of a block (" + r.x + ", " + r.z + ")");
		// A one-wide slot: the body only fits at the middle, and the middle is where it goes.
		EtherwarpMath.Probe slot = (x, y, z) -> (y < 0 || ((x < 0 || x > 0) && z >= 3)) ? EtherwarpMath.SOLID : EtherwarpMath.PASSABLE;
		InstantTransmission.Result in = InstantTransmission.trace(slot, 0.4, 5.0, 0.5, 0.0, 0.0, 1.0, 8.0);
		check(in.moved && Math.abs(in.x - 0.5) < 1.0E-9, "in a one-block slot the middle of it (" + in.x + ")");
	}

	private static void checkWall() {
		EtherwarpMath.Probe wall = (x, y, z) -> y < 0 || z >= 5 ? EtherwarpMath.SOLID : EtherwarpMath.PASSABLE;
		InstantTransmission.Result r = InstantTransmission.trace(wall, 0.5, 5.0, 0.5, 0.0, 0.0, 1.0, 8.0);
		check(r.moved, "a wall in the way: still moves");
		check(r.z <= 5.0 - 0.3 + 1.0E-9, "but stops with the body clear of it (" + r.z + ")");
		check(r.z > 4.0, "close to it (" + r.z + ")");
	}

	private static void checkFloor() {
		InstantTransmission.Result r = InstantTransmission.trace(world(), 0.5, 3.0, 0.5, 0.0, -1.0, 1.0, 12.0);
		check(r.moved, "looking down at the floor moves");
		check(r.y >= 0.0 - 1.0E-9, "and does not end in the floor (" + r.y + ")");
		// Standing on the floor and looking 25 degrees down: the player is carried forward along it, not refused.
		double pitch = Math.toRadians(25.0);
		InstantTransmission.Result onFloor = InstantTransmission.trace(world(), 0.5, 0.0, 0.5, 0.0, -Math.sin(pitch), Math.cos(pitch), 12.0);
		check(onFloor.moved, "on the floor, a look a little down still teleports");
		check(onFloor.z > 2.0 && onFloor.y >= 0.0 - 1.0E-9 && onFloor.y < 2.1, "forward along it (" + onFloor.z + ", " + onFloor.y + ")");
		InstantTransmission.Result steeper = InstantTransmission.trace(world(), 0.5, 0.0, 0.5, 0.0, -1.0, 0.3, 12.0);
		check(steeper.moved || steeper.reason.equals("no room"), "straight down is allowed to do nothing, but not to crash");
	}

	private static void checkBlockedAtOnce() {
		EtherwarpMath.Probe box = (x, y, z) -> (x != 0 || z != 0 || y < 0 || y > 1) ? EtherwarpMath.SOLID : EtherwarpMath.PASSABLE;
		InstantTransmission.Result r = InstantTransmission.trace(box, 0.5, 0.0, 0.5, 0.0, 0.0, 1.0, 8.0);
		check(!r.moved, "no room to go anywhere: no teleport");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
