package geiler.addons.client.macros.pathing;

/**
 * A door one block wide in a wall across the way: a route that crosses the wall at an angle gets a lead-in in line with the door, so
 * the player goes through it straight instead of scraping the frame.
 */
public final class GapChecks {
	private GapChecks() { }

	/** A wall along z = 5 for x outside 2..3 (a one-block door at x = 2), the room either side of it open. */
	private static boolean solid(double x, double z) {
		return z >= 5.0 && z < 6.0 && (x < 2.0 || x >= 3.0);
	}

	/** Marching a body of half-width 0.3 from (x, z) in a direction until it would touch a wall: the room it has. */
	private static final GapRules.Room ROOM = (x, z, dx, dz) -> {
		double reached = 0.0;
		for (double moved = 0.1; moved <= GapRules.REACH + 1.0E-9; moved += 0.1) {
			double cx = x + dx * moved;
			double cz = z + dz * moved;
			if (solid(cx - 0.3, cz - 0.3) || solid(cx + 0.3, cz - 0.3) || solid(cx - 0.3, cz + 0.3) || solid(cx + 0.3, cz + 0.3)) break;
			reached = moved;
		}
		return reached;
	};

	public static void run() {
		// From the left of the door, across it at an angle.
		GapRules.Lead lead = GapRules.find(0.5, 1.0, 4.5, 9.0, ROOM, (a, b, c, d) -> true);
		check(lead != null, "an angled crossing of a one-block door gets a lead-in");
		check(Math.abs(lead.gapX - 2.5) < 0.2, "through the middle of the door (" + lead.gapX + ")");
		check(Math.abs(lead.leadX - lead.gapX) < 1.0E-6 && lead.leadZ < lead.gapZ, "back from it along its axis, same x (" + lead.leadX + ", " + lead.leadZ + ")");
		check(GapRules.find(2.5, 1.0, 2.5, 9.0, ROOM, (a, b, c, d) -> true) == null, "straight through the middle: nothing to add");
		check(GapRules.find(0.5, 1.0, 0.5, 9.0, ROOM, (a, b, c, d) -> true) == null, "a route that does not meet a door: nothing");
		check(GapRules.find(0.5, 1.0, 4.5, 9.0, ROOM, (a, b, c, d) -> false) == null, "no walkable lead-in point: nothing");
		GapRules.Lead shorter = GapRules.find(0.5, 3.2, 4.0, 8.0, ROOM, (a, b, c, d) -> Math.hypot(c - a, d - b) < 2.0);
		check(shorter == null || Math.hypot(shorter.leadX - shorter.gapX, shorter.leadZ - shorter.gapZ) <= 1.5 + 1.0E-9, "a shorter lead-in is tried when the long one does not walk");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
