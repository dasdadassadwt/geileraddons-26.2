package geiler.addons.client.dungeon;

import java.util.List;

/** Standalone offline checks for geometry-only room footprints and their lifecycle. */
public final class DungeonRoomFootprintChecks {
	private DungeonRoomFootprintChecks() { }

	public static void run() {
		checksShapeAndPhysicalCells();
		checksRetainedFootprintDuringMapLoss();
		checksTransitionExitAndWorldClear();
		checksActivationConditions();
		checksJavaBoundary();
	}

	private static void checksShapeAndPhysicalCells() {
		List<DungeonMapGeometry.CellOffset> line = List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0),
			new DungeonMapGeometry.CellOffset(2, 0));
		List<DungeonMapGeometry.CellOffset> lShape = List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0),
			new DungeonMapGeometry.CellOffset(0, 1));
		check("1x3".equals(DungeonMapGeometry.shape(line)), "map shape remains a horizontal line of three cells");
		check("L".equals(DungeonMapGeometry.shape(lShape)), "map shape distinguishes an L footprint");

		DungeonRoomTracker.MapRoomFootprint footprint = new DungeonRoomTracker.MapRoomFootprint(null, List.of(
			new DungeonRoomTracker.CellKey(2, 3), new DungeonRoomTracker.CellKey(3, 3),
			new DungeonRoomTracker.CellKey(2, 4)));
		int baseX = DungeonGridRules.GRID_MIN + 2 * DungeonGridRules.CELL_STRIDE;
		int baseZ = DungeonGridRules.GRID_MIN + 3 * DungeonGridRules.CELL_STRIDE;
		check(footprint.contains(baseX + 10, baseZ + 10), "the current physical cell is occupied");
		check(footprint.contains(baseX + DungeonGridRules.CELL_STRIDE + 10, baseZ + 10),
			"a horizontal connected cell is occupied");
		check(footprint.contains(baseX + 10, baseZ + DungeonGridRules.CELL_STRIDE + 10),
			"a vertical connected cell is occupied");
		check(!footprint.contains(baseX + DungeonGridRules.CELL_STRIDE + 10,
			baseZ + DungeonGridRules.CELL_STRIDE + 10), "a diagonal cell outside the L is excluded");
		check(!footprint.contains(baseX + DungeonGridRules.ROOM_SIZE, baseZ + 10),
			"the one-block corridor gap is excluded from room occupancy");
		check(footprint.footprintKey().startsWith("cells:"), "the stable key contains only physical cell coordinates");
		checkValueSemantics();
	}

	private static void checkValueSemantics() {
		var source = new java.util.ArrayList<DungeonRoomTracker.CellKey>();
		source.add(new DungeonRoomTracker.CellKey(2, 3));
		source.add(new DungeonRoomTracker.CellKey(2, 3));
		source.add(new DungeonRoomTracker.CellKey(-1, 0));
		source.add(null);
		var normalized = new DungeonRoomTracker.MapRoomFootprint("ignored", source);
		source.clear();
		var equivalent = footprint(2, 3);
		check(normalized.cells().equals(List.of(new DungeonRoomTracker.CellKey(2, 3)))
			&& normalized.footprintKey().equals("cells:2,3"),
			"footprint construction normalizes and defensively copies cell input");
		check(normalized.equals(equivalent) && normalized.hashCode() == equivalent.hashCode()
			&& normalized.toString().equals("MapRoomFootprint[footprintKey=cells:2,3, cells=[CellKey[x=2, z=3]]]")
			&& equivalent.equals(normalized)
			&& normalized.hashCode() == new ExpectedFootprint("cells:2,3",
				List.of(new DungeonRoomTracker.CellKey(2, 3))).hashCode(),
			"footprints retain record-style equality, hash and text behavior");
	}

	private static void checksRetainedFootprintDuringMapLoss() {
		Object world = new Object();
		DungeonRoomTracker.CellKey playerCell = new DungeonRoomTracker.CellKey(2, 3);
		DungeonRoomTracker.MapRoomFootprint confirmed = footprint(2, 3, 3, 3);
		DungeonRoomTracker.TrackerState previous = new DungeonRoomTracker.TrackerState(world, confirmed);

		DungeonRoomTracker.TrackerState afterLoss = DungeonRoomTrackerLogic.observe(previous, true, world,
			playerCell, null);
		check(afterLoss.footprint() != null && afterLoss.footprint().cells().equals(confirmed.cells()),
			"transient map-data loss retains the shape while the player stays in the physical room");

		DungeonRoomTracker.MapRoomFootprint conflictingObservation = footprint(2, 3);
		DungeonRoomTracker.TrackerState afterStaleShape = DungeonRoomTrackerLogic.observe(previous, true, world,
			playerCell, conflictingObservation);
		check(afterStaleShape.footprint() != null && afterStaleShape.footprint().cells().equals(confirmed.cells()),
			"a temporary different map shape does not replace the footprint under the player");
	}

	private static void checksTransitionExitAndWorldClear() {
		Object world = new Object();
		Object nextWorld = new Object();
		DungeonRoomTracker.MapRoomFootprint first = footprint(2, 3);
		DungeonRoomTracker.TrackerState previous = new DungeonRoomTracker.TrackerState(world, first);

		DungeonRoomTracker.CellKey nextCell = new DungeonRoomTracker.CellKey(3, 3);
		DungeonRoomTracker.MapRoomFootprint next = footprint(3, 3, 4, 3);
		DungeonRoomTracker.TrackerState transitioned = DungeonRoomTrackerLogic.observe(previous, true, world,
			nextCell, next);
		check(transitioned.footprint() != null && transitioned.footprint().cells().equals(next.cells()),
			"a confirmed physical room transition adopts the new calibrated footprint");

		DungeonRoomTracker.TrackerState exited = DungeonRoomTrackerLogic.observe(previous, true, world,
			new DungeonRoomTracker.CellKey(4, 3), null);
		check(exited.footprint() == null, "leaving all retained physical room cells clears the footprint");

		DungeonRoomTracker.TrackerState changedWorld = DungeonRoomTrackerLogic.observe(previous, true, nextWorld,
			new DungeonRoomTracker.CellKey(2, 3), null);
		check(changedWorld.world() == nextWorld && changedWorld.footprint() == null,
			"a world change never carries the previous room footprint forward");

		DungeonRoomTracker.TrackerState dungeonExit = DungeonRoomTrackerLogic.observe(previous, false, world,
			new DungeonRoomTracker.CellKey(2, 3), first);
		check(dungeonExit.world() == null && dungeonExit.footprint() == null,
			"leaving Catacombs context clears the retained footprint");
	}

	private static void checksActivationConditions() {
		check(DungeonRoomTrackerLogic.shouldTrack(true, true, true, true),
			"tracker runs with Catacombs, enabled module, world and player");
		check(!DungeonRoomTrackerLogic.shouldTrack(false, true, true, true), "tracker stays dormant outside Catacombs");
		check(!DungeonRoomTrackerLogic.shouldTrack(true, false, true, true), "tracker stays dormant while Mob ESP is disabled");
		check(!DungeonRoomTrackerLogic.shouldTrack(true, true, false, true), "tracker needs a client world");
		check(!DungeonRoomTrackerLogic.shouldTrack(true, true, true, false), "tracker needs a local player");
	}

	private static void checksJavaBoundary() {
		try {
			var type = DungeonRoomTracker.class;
			check(type.getMethod("tick", net.minecraft.client.Minecraft.class, boolean.class).getReturnType() == void.class,
				"tick remains available to the Java client hook");
			check(type.getMethod("currentMapFootprint", net.minecraft.client.Minecraft.class).getReturnType()
				== DungeonRoomTracker.MapRoomFootprint.class,
				"map footprint observation keeps its public Java signature");
			check(type.getMethod("currentFootprint").getReturnType() == DungeonRoomTracker.MapRoomFootprint.class
				&& type.getMethod("confirmedPhysicalRoomCells").getReturnType() == List.class
				&& type.getMethod("diagnostics").getReturnType() == String.class,
				"public tracker readers keep their Java return types");
			check(type.getMethod("clear").getReturnType() == void.class,
				"clear remains available to Java lifecycle callers");
			check(DungeonRoomTracker.CellKey.class.isRecord()
				&& java.util.Arrays.stream(DungeonRoomTracker.CellKey.class.getRecordComponents())
					.map(java.lang.reflect.RecordComponent::getName).toList().equals(List.of("x", "z")),
				"cell keys retain Java record metadata and component order");
			check(DungeonRoomTracker.TrackerState.class.isRecord()
				&& java.util.Arrays.stream(DungeonRoomTracker.TrackerState.class.getRecordComponents())
					.map(java.lang.reflect.RecordComponent::getName).toList().equals(List.of("world", "footprint")),
				"tracker state retains Java record metadata and component order");
			check(!DungeonRoomTracker.MapRoomFootprint.class.isRecord()
				&& DungeonRoomTracker.MapRoomFootprint.class.getConstructor(String.class, List.class).getParameterCount() == 2
				&& DungeonRoomTracker.MapRoomFootprint.class.getMethod("footprintKey").getReturnType() == String.class
				&& DungeonRoomTracker.MapRoomFootprint.class.getMethod("cells").getReturnType() == List.class,
				"normalized footprints keep their Java constructor and accessors as Kotlin value classes");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("dungeon room tracker Java boundary changed", error);
		}
	}

	private static DungeonRoomTracker.MapRoomFootprint footprint(int... coordinates) {
		List<DungeonRoomTracker.CellKey> cells = new java.util.ArrayList<>();
		for (int index = 0; index + 1 < coordinates.length; index += 2) {
			cells.add(new DungeonRoomTracker.CellKey(coordinates[index], coordinates[index + 1]));
		}
		return new DungeonRoomTracker.MapRoomFootprint(null, cells);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record ExpectedFootprint(String footprintKey, List<DungeonRoomTracker.CellKey> cells) { }
}
