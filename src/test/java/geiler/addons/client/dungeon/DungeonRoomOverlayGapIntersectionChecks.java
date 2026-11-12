package geiler.addons.client.dungeon;

import java.util.List;

/** Focused checks for internal multi-tile room gap fills. */
public final class DungeonRoomOverlayGapIntersectionChecks {
	private DungeonRoomOverlayGapIntersectionChecks() { }

	public static void run() {
		fillsOnlyCompleteTwoByTwoIntersections();
		leavesPartialAndLShapedFootprintsUnfilled();
		preservesOuterGapsAndDoorGeometry();
	}

	public static void main(String[] args) { run(); }

	private static void fillsOnlyCompleteTwoByTwoIntersections() {
		var twoByTwo = List.of(cell(1, 2), cell(2, 2), cell(1, 3), cell(2, 3));
		require(DungeonRoomOverlayLayout.internalGapIntersections(twoByTwo, 16, 4).equals(
			List.of(new DungeonRoomOverlayLayout.GapRect(36, 56, 4, 4))),
			"a complete 2-by-2 room fills exactly its internal gap intersection");

		var twoByThree = List.of(cell(1, 2), cell(2, 2), cell(3, 2),
			cell(1, 3), cell(2, 3), cell(3, 3));
		require(DungeonRoomOverlayLayout.internalGapIntersections(twoByThree, 16, 4).equals(List.of(
			new DungeonRoomOverlayLayout.GapRect(36, 56, 4, 4),
			new DungeonRoomOverlayLayout.GapRect(56, 56, 4, 4))),
			"a wider footprint fills each complete internal 2-by-2 intersection in row-major order");

		var edgeBlock = List.of(cell(4, 4), cell(5, 4), cell(4, 5), cell(5, 5));
		require(DungeonRoomOverlayLayout.internalGapIntersections(edgeBlock, 16, 4).equals(
			List.of(new DungeonRoomOverlayLayout.GapRect(96, 96, 4, 4))),
			"the final in-grid 2-by-2 block is handled without extending beyond the grid");
	}

	private static void leavesPartialAndLShapedFootprintsUnfilled() {
		var lShape = List.of(cell(0, 0), cell(1, 0), cell(0, 1));
		require(DungeonRoomOverlayLayout.internalGapIntersections(lShape, 16, 4).isEmpty(),
			"an L-shaped room does not fill a missing fourth cell");
		require(DungeonRoomOverlayLayout.internalGapIntersections(
			List.of(cell(0, 0), cell(1, 0), cell(1, 1)), 16, 4).isEmpty(),
			"a partial 2-by-2 footprint remains open");
		require(DungeonRoomOverlayLayout.internalGapIntersections(
			List.of(cell(0, 0), cell(1, 0), cell(0, 1), cell(1, 1), cell(1, 1)), 16, 4).size() == 1,
			"duplicate footprint cells do not duplicate a fill");
		require(DungeonRoomOverlayLayout.internalGapIntersections(
			List.of(cell(0, 0), cell(1, 0), cell(0, 1), cell(1, 1)), 16, 0).isEmpty(),
			"a zero-width configured gap produces no degenerate fill rectangle");
	}

	private static void preservesOuterGapsAndDoorGeometry() {
		var north = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 4,
			DungeonRoomOverlayGeometry.Direction.NORTH, 1.0f);
		var east = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 4,
			DungeonRoomOverlayGeometry.Direction.EAST, 1.0f);
		var south = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 4,
			DungeonRoomOverlayGeometry.Direction.SOUTH, 1.0f);
		var west = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 4,
			DungeonRoomOverlayGeometry.Direction.WEST, 1.0f);

		var fill = DungeonRoomOverlayLayout.internalGapIntersections(
			List.of(cell(1, 1), cell(2, 1), cell(1, 2), cell(2, 2)), 16, 4);
		require(fill.equals(List.of(new DungeonRoomOverlayLayout.GapRect(36, 36, 4, 4))),
			"internal fills occupy only the configured cell gap");
		require(north.equals(new DungeonRoomOverlayLayout.DoorRect(104, 96, 8, 4))
			&& east.equals(new DungeonRoomOverlayLayout.DoorRect(116, 104, 4, 8))
			&& south.equals(new DungeonRoomOverlayLayout.DoorRect(104, 116, 8, 4))
			&& west.equals(new DungeonRoomOverlayLayout.DoorRect(96, 104, 4, 8)),
			"the external 8-by-4 door rectangles remain unchanged in all orientations");
	}

	private static DungeonRoomOverlayGeometry.Cell cell(int x, int z) {
		return new DungeonRoomOverlayGeometry.Cell(x, z, DungeonMapGeometry.RoomType.ROOM, "room");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
