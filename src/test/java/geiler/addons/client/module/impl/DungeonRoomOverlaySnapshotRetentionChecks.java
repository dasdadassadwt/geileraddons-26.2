package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonMapGeometry;
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry;

import java.lang.reflect.Modifier;
import java.util.List;

/** Focused checks for run-local positive map evidence retention. */
public final class DungeonRoomOverlaySnapshotRetentionChecks {
	private DungeonRoomOverlaySnapshotRetentionChecks() { }

	public static void run() {
		checkJavaCallSurface();

		var oldCell = new DungeonRoomOverlayGeometry.Cell(1, 1, DungeonMapGeometry.RoomType.TRAP, "cells:7",
			DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		var oldRoom = new DungeonRoomOverlayGeometry.Room("cells:7", "Trap Room",
			DungeonMapGeometry.RoomType.TRAP, List.of(oldCell), List.of(), false, true,
			DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED, 6, 2, "GREEN");
		var oldPlayer = new DungeonRoomOverlayGeometry.PlayerMarker(1, 1, "OldTeammate", false);
		var previous = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(oldCell), List.of(oldRoom), null,
			List.of(oldPlayer), "valid map evidence");
		var unavailable = DungeonRoomOverlayGeometry.Snapshot.unavailable("temporary map gap");
		var retainedAfterGap = DungeonRoomOverlaySnapshotRetention.merge(unavailable, previous);
		require(retainedAfterGap == previous && retainedAfterGap.rooms().size() == 1
			&& retainedAfterGap.cells().equals(List.of(oldCell)),
			"a temporary invalid map scan retains a previously confirmed room");
		require(DungeonRoomOverlaySnapshotRetention.merge(null, previous) == previous
			&& DungeonRoomOverlaySnapshotRetention.merge(null, null) == null
			&& DungeonRoomOverlaySnapshotRetention.merge(unavailable, null) == null
			&& DungeonRoomOverlaySnapshotRetention.merge(null, unavailable) == unavailable,
			"null and unavailable fast paths preserve the exact returned snapshot reference");

		var currentCell = new DungeonRoomOverlayGeometry.Cell(1, 1, DungeonMapGeometry.RoomType.TRAP, "cells:7",
			DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED);
		var currentRoom = new DungeonRoomOverlayGeometry.Room("cells:7", "Unknown", DungeonMapGeometry.RoomType.TRAP,
			List.of(currentCell), List.of(), false, true, DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED,
			6, -1, "UNDISCOVERED");
		var currentPlayer = new DungeonRoomOverlayGeometry.PlayerMarker(2, 1, "CurrentTeammate", false);
		var current = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(currentCell), List.of(currentRoom), null,
			List.of(currentPlayer), "map refreshed");
		require(DungeonRoomOverlaySnapshotRetention.merge(current, null) == current,
			"a valid current snapshot is returned by identity when prior evidence is absent");
		var retained = DungeonRoomOverlaySnapshotRetention.merge(current, previous);
		require(retained.cells().getFirst().visibility() == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
			&& retained.playerCell() == null && retained.rooms().getFirst().name().equals("Trap Room")
			&& retained.rooms().getFirst().foundSecrets() == 2
			&& retained.rooms().getFirst().checkmark().equals("GREEN")
			&& retained.players().equals(List.of(currentPlayer)) && retained != current && retained != previous,
			"map refreshes retain room evidence and progress while using current player markers and position");

		checkMergedOrdering();

		var coreCell = new DungeonRoomOverlayGeometry.Cell(1, 1, DungeonMapGeometry.RoomType.TRAP, "loaded-core:7",
			DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED);
		var coreRoom = new DungeonRoomOverlayGeometry.Room("loaded-core:7", "Trap Room",
			DungeonMapGeometry.RoomType.TRAP, List.of(coreCell), List.of(), false, true,
			DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED, 6, -1, "UNDISCOVERED");
		var coreFallback = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(coreCell), List.of(coreRoom), null,
			"loaded core fallback");
		var retainedCore = DungeonRoomOverlaySnapshotRetention.merge(coreFallback, previous);
		require(retainedCore.rooms().size() == 1 && retainedCore.rooms().getFirst().id().equals("loaded-core:7")
			&& retainedCore.rooms().getFirst().checkmark().equals("GREEN")
			&& retainedCore.rooms().getFirst().foundSecrets() == 2 && retainedCore.players().isEmpty(),
			"same complete footprint carries room progress across IDs without retaining absent player markers");

		var conflictingCell = new DungeonRoomOverlayGeometry.Cell(1, 1, DungeonMapGeometry.RoomType.PUZZLE, "cells:7",
			DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		var conflict = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(conflictingCell), List.of(
			new DungeonRoomOverlayGeometry.Room("cells:7", "Puzzle", DungeonMapGeometry.RoomType.PUZZLE,
				List.of(conflictingCell), List.of(), false, true)), null, "map type changed");
		var conflictResult = DungeonRoomOverlaySnapshotRetention.merge(conflict, previous);
		require(conflictResult.cells().getFirst().type() == DungeonMapGeometry.RoomType.PUZZLE
			&& conflictResult.rooms().getFirst().name().equals("Puzzle"),
			"a new distinct map type overrides a stale retained catalog identity");

		var partialCell = new DungeonRoomOverlayGeometry.Cell(1, 1, DungeonMapGeometry.RoomType.UNKNOWN,
			"partial-core:7", DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED);
		var partialExit = new DungeonRoomOverlayGeometry.ObservedExit(partialCell,
			DungeonRoomOverlayGeometry.Direction.EAST);
		var partialRoom = new DungeonRoomOverlayGeometry.Room("partial-core:7", "Unknown",
			DungeonMapGeometry.RoomType.UNKNOWN, List.of(partialCell), List.of(partialExit), false, false,
			DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED, 0, -1, "UNDISCOVERED");
		var partialFallback = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(partialCell),
			List.of(partialRoom), null, "partial loaded core");
		var mergedMapAndPartial = DungeonRoomOverlaySnapshotRetention.merge(previous, partialFallback);
		var mergedMapRoom = mergedMapAndPartial.rooms().getFirst();
		require(mergedMapAndPartial.cells().getFirst().roomId().equals("cells:7")
			&& mergedMapAndPartial.cells().getFirst().type() == DungeonMapGeometry.RoomType.TRAP
			&& mergedMapAndPartial.rooms().size() == 1 && mergedMapRoom.id().equals("cells:7")
			&& mergedMapRoom.exits().equals(List.of(partialExit)),
			"map-confirmed room identity stays authoritative while a core-only tracker door attaches to its visible room");

		var emptyMap = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(), List.of(), null, "map cells pending");
		var partialOnly = DungeonRoomOverlaySnapshotRetention.merge(emptyMap, partialFallback);
		var partialPlan = DungeonRoomOverlayGeometry.renderPlan(partialOnly, true);
		require(partialOnly.rooms().size() == 1 && partialPlan.visibleCells().size() == 1
			&& partialPlan.exits().size() == 1 && partialPlan.footprintEdges().isEmpty(),
			"a neutral partial-core room and its attached door survive retention when map pixels are absent");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void checkJavaCallSurface() {
		try {
			var method = DungeonRoomOverlaySnapshotRetention.class.getDeclaredMethod("merge",
				DungeonRoomOverlayGeometry.Snapshot.class, DungeonRoomOverlayGeometry.Snapshot.class);
			require(Modifier.isStatic(method.getModifiers()), "Kotlin adapter exposes merge as a Java static method");
			require(method.getReturnType() == DungeonRoomOverlayGeometry.Snapshot.class,
				"Kotlin adapter keeps the Java Snapshot return type");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("Kotlin adapter keeps merge(Snapshot, Snapshot)", exception);
		}
	}

	private static void checkMergedOrdering() {
		var cellZ1 = new DungeonRoomOverlayGeometry.Cell(0, 1, DungeonMapGeometry.RoomType.TRAP, "retained",
			DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		var cellZ0 = new DungeonRoomOverlayGeometry.Cell(1, 0, DungeonMapGeometry.RoomType.TRAP, "retained",
			DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		var retainedRoom = new DungeonRoomOverlayGeometry.Room("retained", "Retained",
			DungeonMapGeometry.RoomType.TRAP, List.of(cellZ1, cellZ0), List.of(), false, true,
			DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED, 0, 0, "UNDISCOVERED");
		var currentFirst = new DungeonRoomOverlayGeometry.Room("current-first", "First",
			DungeonMapGeometry.RoomType.UNKNOWN, List.of(), List.of(), false, false);
		var currentSecond = new DungeonRoomOverlayGeometry.Room("current-second", "Second",
			DungeonMapGeometry.RoomType.UNKNOWN, List.of(), List.of(), false, false);
		var current = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(), List.of(currentFirst, currentSecond),
			null, "current order");
		var previous = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(cellZ1, cellZ0), List.of(retainedRoom),
			null, "previous order");
		var merged = DungeonRoomOverlaySnapshotRetention.merge(current, previous);
		require(merged.cells().equals(List.of(cellZ0, cellZ1)), "merged cells remain ordered by z then x");
		require(merged.rooms().stream().map(DungeonRoomOverlayGeometry.Room::id).toList()
			.equals(List.of("current-first", "current-second", "retained")),
			"current room order is preserved and retained prior rooms follow it");
	}
}
