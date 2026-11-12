package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.DungeonGridRules;
import geiler.addons.client.dungeon.DungeonMapGeometry;
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

/** Focused offline checks for room cores scanned from already-loaded world chunks. */
public final class OdinDungeonWorldRoomProjectionChecks {
	private OdinDungeonWorldRoomProjectionChecks() { }

	public static void run() {
		preservesJavaProjectionAbi();
		projectsCataloguedRoomsAndUnopenedState();
		omitsUnknownAndIncompleteRooms();
		combinesOnlyCompleteMultiTileFootprints();
		omitsAmbiguousFootprintMatches();
		usesOnlyObservedDoorEvidence();
		retainsPlayerMarkerWithoutRoomMatches();
	}

	private static void preservesJavaProjectionAbi() {
		try {
			var method = OdinDungeonWorldRoomProjection.class.getMethod("project", Map.class,
				DungeonGridRules.Cell.class, List.class, List.class);
			require(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
				&& method.getReturnType() == DungeonRoomOverlayGeometry.Snapshot.class,
				"the Kotlin implementation retains the Java-callable static projection API");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("the Java projection API is preserved", error);
		}
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		run();
	}

	private static void projectsCataloguedRoomsAndUnopenedState() {
		var data = room("Verified room", "NORMAL", "1x1", 5);
		var player = cell(0);
		var snapshot = project(Map.of(0, core(100, data), 17, core(101, data)), player, List.of(), List.of());
		require(snapshot.available() && "verified-loaded-catalog-core-scan".equals(snapshot.reason()),
			"a processed core scan yields an available loaded-chunk snapshot");
		require(snapshot.cells().size() == 2 && snapshot.rooms().size() == 2,
			"separate catalogued 1x1 cores project as distinct rooms");
		var current = snapshot.rooms().stream().filter(room -> room.footprint().getFirst().x() == 0
			&& room.footprint().getFirst().z() == 0).findFirst().orElseThrow();
		var unopened = snapshot.rooms().stream().filter(room -> room.footprint().getFirst().x() == 5
			&& room.footprint().getFirst().z() == 2).findFirst().orElseThrow();
		require(current.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED,
			"the player-containing room is observed");
		require(unopened.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
			&& unopened.maxSecrets() == 5 && unopened.foundSecrets() == -1,
			"other catalogued rooms stay verified-unopened and retain their secret maximum");
	}

	private static void omitsUnknownAndIncompleteRooms() {
		var longRoom = room("Long room", "NORMAL", "1x3", 7);
		var unknownCore = core(102, null);
		var snapshot = project(Map.of(0, core(103, longRoom), 1, core(104, longRoom), 2, unknownCore),
			cell(0), List.of(), List.of());
		require(snapshot.cells().isEmpty() && snapshot.rooms().isEmpty(),
			"incomplete multi-tile matches and uncatalogued cores remain omitted");
	}

	private static void combinesOnlyCompleteMultiTileFootprints() {
		var large = room("Large room", "TRAP", "2x2", 4);
		var cores = Map.of(7, core(105, large), 8, core(106, large), 13, core(107, large), 14, core(108, large));
		var snapshot = project(cores, cell(7), List.of(), List.of());
		require(snapshot.cells().size() == 4 && snapshot.rooms().size() == 1,
			"a complete 2x2 catalog shape becomes one room");
		var combined = snapshot.rooms().getFirst();
		require(combined.footprintKnown() && combined.footprint().size() == 4
			&& combined.type() == DungeonMapGeometry.RoomType.TRAP
			&& combined.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED,
			"the complete multi-tile shape and player-room state are represented");

		var incomplete = project(Map.of(7, core(109, large), 8, core(110, large), 13, core(111, large)),
			cell(7), List.of(), List.of());
		require(incomplete.cells().isEmpty() && incomplete.rooms().isEmpty(),
			"a 2x2 missing one scanned core stays omitted rather than drawing a false partial room");
	}

	private static void omitsAmbiguousFootprintMatches() {
		var data = room("Ambiguous room", "NORMAL", "1x2", 3);
		var snapshot = project(Map.of(0, core(115, data), 1, core(116, data), 2, core(117, data)),
			cell(0), List.of(), List.of());
		require(snapshot.cells().isEmpty() && snapshot.rooms().isEmpty(),
			"overlapping same-signature shape matches stay omitted until door evidence disambiguates them");
		var separated = project(Map.of(0, core(120, data), 1, core(121, data),
			2, core(122, data), 3, core(123, data)), cell(0), List.of(),
			List.of(new DungeonRoomOverlayGeometry.ObservedDoor(1, 2)));
		require(separated.cells().size() == 4 && separated.rooms().size() == 2
			&& separated.rooms().stream().allMatch(room -> room.exits().size() == 1),
			"a confirmed boundary door resolves adjacent identical catalog shapes into separate rooms");
	}

	private static void usesOnlyObservedDoorEvidence() {
		var data = room("Door room", "NORMAL", "1x1", 2);
		var roomOnly = project(Map.of(7, core(112, data)), cell(7), List.of(), List.of());
		require(roomOnly.rooms().getFirst().exits().isEmpty(),
			"neighboring cells alone never create a door exit");

		var observed = project(Map.of(7, core(113, data)), cell(7), List.of(),
			List.of(new DungeonRoomOverlayGeometry.ObservedDoor(7, 8,
				DungeonRoomOverlayGeometry.DoorKind.WITHER)));
		var exit = observed.rooms().getFirst().exits().getFirst();
		require(exit.cell().x() == 1 && exit.cell().z() == 1
			&& exit.direction() == DungeonRoomOverlayGeometry.Direction.EAST
			&& exit.kind() == DungeonRoomOverlayGeometry.DoorKind.WITHER,
			"a confirmed door is shown from the known room even while its other side is unresolved");

		var multi = room("Two-tile room", "NORMAL", "1x2", 4);
		var interruptedShape = project(Map.of(7, core(118, multi), 8, core(119, multi)), cell(7), List.of(),
			List.of(new DungeonRoomOverlayGeometry.ObservedDoor(7, 8)));
		require(interruptedShape.cells().isEmpty() && interruptedShape.rooms().isEmpty(),
			"an observed door inside a catalogued multi-tile footprint prevents false room combination");

		var doorOnly = project(Map.of(), cell(7), List.of(),
			List.of(new DungeonRoomOverlayGeometry.ObservedDoor(7, 8)));
		require(doorOnly.rooms().isEmpty() && doorOnly.cells().isEmpty(),
			"door evidence without a catalogued room does not invent room cells or topology");
	}

	private static void retainsPlayerMarkerWithoutRoomMatches() {
		var marker = new DungeonRoomOverlayGeometry.PlayerMarker(13, 26, "Local", true, 0.4, 0.6, "Mage", 90.0f);
		var snapshot = project(Map.of(3, core(114, null)), cell(3), List.of(marker), List.of());
		require(snapshot.available() && snapshot.cells().isEmpty() && snapshot.rooms().isEmpty(),
			"unknown core signatures do not create false rooms");
		require(snapshot.players().equals(List.of(marker)) && snapshot.playerCell().equals(cell(3)),
			"player markers and the current cell remain available while room identity is unresolved");
	}

	private static DungeonRoomOverlayGeometry.Snapshot project(
		Map<Integer, OdinDungeonMapCoreScanner.RoomCore> cores, DungeonGridRules.Cell playerCell,
		List<DungeonRoomOverlayGeometry.PlayerMarker> players,
		List<DungeonRoomOverlayGeometry.ObservedDoor> doors) {
		return OdinDungeonWorldRoomProjection.project(cores, playerCell, players, doors);
	}

	private static DungeonGridRules.Cell cell(int index) {
		return DungeonGridRules.cellAtOrigin(-200 + index % 6 * 32, -200 + index / 6 * 32);
	}

	private static OdinDungeonMapCoreScanner.RoomCore core(int hash, OdinDungeonRoomCatalog.RoomData data) {
		return new OdinDungeonMapCoreScanner.RoomCore(hash, 90, data);
	}

	private static OdinDungeonRoomCatalog.RoomData room(String name, String type, String shape, int maxSecrets) {
		return new OdinDungeonRoomCatalog.RoomData(name, type, shape, 0, maxSecrets, 0);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
