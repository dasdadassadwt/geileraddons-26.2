package geiler.addons.client.dungeon.helpers;

import java.util.List;

/** Focused, dependency-light acceptance checks for observed Ice Fill route rendering. */
public final class IceFillRenderGeometryChecks {
	private IceFillRenderGeometryChecks() { }

	public static void main(String[] args) {
		var route = List.of(new DungeonPuzzleLogic.Cell(2, 3), new DungeonPuzzleLogic.Cell(3, 3),
			new DungeonPuzzleLogic.Cell(3, 4));
		check(IceFillRenderGeometry.shouldRender(true, true, true, true, true, route),
			"a complete current loaded route can be rendered");
		List<IceFillRenderGeometry.Point> worldPoints = IceFillRenderGeometry.worldPoints(route, 100, 200, 70);
		check(worldPoints.equals(List.of(
			new IceFillRenderGeometry.Point(102.5, 70 + 1.02, 203.5),
			new IceFillRenderGeometry.Point(103.5, 70 + 1.02, 203.5),
			new IceFillRenderGeometry.Point(103.5, 70 + 1.02, 204.5))),
			"room-relative route cells project to block centers just over the floor surface");
		boolean immutableWorldPoints = false;
		try {
			worldPoints.add(new IceFillRenderGeometry.Point(0.0, 0.0, 0.0));
		} catch (UnsupportedOperationException expected) {
			immutableWorldPoints = true;
		}
		check(immutableWorldPoints, "projected world points are immutable");
		check(!IceFillRenderGeometry.shouldRender(false, true, true, true, true, route),
			"partial floor scans cannot render a route");
		check(!IceFillRenderGeometry.shouldRender(true, false, true, true, true, route),
			"a world change hides a stale route");
		check(!IceFillRenderGeometry.shouldRender(true, true, false, true, true, route),
			"a room change hides a stale route");
		check(!IceFillRenderGeometry.shouldRender(true, true, true, false, true, route),
			"a different floor height hides a stale route");
		check(!IceFillRenderGeometry.shouldRender(true, true, true, true, false, route),
			"route cells that are no longer loaded ice hide the path");
		check(!IceFillRenderGeometry.shouldRender(true, true, true, true, true, route.subList(0, 1)),
			"a one-point path cannot render as a route");
		check(IceFillRenderGeometry.worldPoints(List.of(new DungeonPuzzleLogic.Cell(-1, 0),
			new DungeonPuzzleLogic.Cell(0, 0)), 0, 0, 70).isEmpty(),
			"out-of-room cells fail closed");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
