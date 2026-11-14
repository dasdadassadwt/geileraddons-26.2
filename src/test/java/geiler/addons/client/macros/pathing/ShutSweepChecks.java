package geiler.addons.client.macros.pathing;

import geiler.addons.client.macros.area.Paint;
import geiler.addons.client.macros.area.PaintedArea;

/** A teleport may not pass through what is shut: the sweep along its line, the heights the body fills, and the paint under it. */
public final class ShutSweepChecks {
	private ShutSweepChecks() { }

	public static void run() {
		// A wall column at x = 5, z = 0 whatever the height.
		ShutSweep.Shut column = (x, y, z) -> x == 5 && z == 0;
		check(ShutSweep.crosses(0.5, 64, 0.5, 10.5, 64, 0.5, 0.3, 1.8, column), "a straight line through the column is a crossing");
		check(!ShutSweep.crosses(0.5, 64, 3.5, 10.5, 64, 3.5, 0.3, 1.8, column), "a line two blocks beside it is not");
		check(ShutSweep.crosses(0.5, 64, 1.2, 10.5, 64, 1.2, 0.6, 1.8, column), "the width of the body counts: a hair beside it grazes it");
		check(ShutSweep.crosses(0.5, 70, 0.5, 10.5, 90, 0.5, 0.3, 1.8, column), "a line that climbs over the same column is still through it");

		// A single forbidden cell at y = 65: the line passes at the feet at 64, but the head is at 65 and the body fills both.
		ShutSweep.Shut cell = (x, y, z) -> x == 5 && y == 65 && z == 0;
		check(ShutSweep.crosses(0.5, 64, 0.5, 10.5, 64, 0.5, 0.3, 1.8, cell), "the head height is looked at too");
		check(!ShutSweep.crosses(0.5, 60, 0.5, 10.5, 60, 0.5, 0.3, 1.8, cell), "a line well under it is clear");

		check(!ShutSweep.crosses(0.5, 64, 0.5, 0.5, 64, 0.5, 0.3, 1.8, column), "no distance, no crossing of something elsewhere");
		check(ShutSweep.crosses(5.5, 64, 0.5, 5.5, 64, 0.5, 0.3, 1.8, column), "standing in it is a crossing");
		check(ShutSweep.crosses(10.5, 64, 0.5, 0.5, 64, 0.5, 0.3, 1.8, column), "the direction does not matter");

		PaintedArea area = new PaintedArea("test");
		area.put(5, 64, 0, Paint.FORBIDDEN, 0);
		check(area.shutAt(5, 64, 0) && !area.shutAt(5, 65, 0), "forbidden shuts its own cell only");
		area.put(7, 64, 2, Paint.WALL, 0);
		check(area.shutAt(7, 64, 2) && area.shutAt(7, 90, 2) && area.shutAt(7, -5, 2), "a wall shuts its whole column");
		area.put(9, 64, 2, Paint.ALLOWED, 0);
		check(!area.shutAt(9, 64, 2) && !area.shutAt(20, 64, 20), "allowed ground and unpainted ground are not shut");
		area.erase(7, 64, 2);
		check(!area.shutAt(7, 64, 2), "an erased wall is open again");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
