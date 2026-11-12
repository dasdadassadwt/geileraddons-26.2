package geiler.addons.client.dungeon.odin;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Offline fixtures for the ported Odin map pixels and bundled room signatures. */
public final class OdinDungeonMapChecks {
	private OdinDungeonMapChecks() { }

	public static void run() {
		catalogContainsBundledRoomIdentity();
		independentMinibossMetadataUsesItsOwnRoomCategory();
		catalogMatchesPinnedOdinAssetAndCounts();
		roomRotationMatchesOdinProbeOrderAndShapes();
		roomRotationRetainsJavaAbi();
		mapGridKotlinValuesRetainTheirContract();
		mapLayoutGroupsRoomsAndReadsDoors();
		tabListPuzzleCountMatchesOdinFormat();
		playerMapRequiresLoadedIdentityMatch();
		playerMapPairsOrderedDecorationsWithLivingRoster();
		currentPlayerRosterUsesVisibleTabOrder();
		interpolatedLoadedPlayerCoordinatesProjectToMapGrid();
		scannerRoomCoreRetainsJavaRecordContract();
		playerRoomCoreScanIsPrioritized();
		confirmedCoreIdentitySurvivesConflictingRescans();
		loadedDoorKindsReachOdinExitProjection();
		trackerOnlyDoorsAttachOnlyToVisibleRooms();
		exactCorePartialFootprintsShowVerifiedTileData();
		worldFallbackKeepsPartialCoreOccupancyAndConfirmedDoors();
		mapRoomPixelsOverrideConflictingCatalogTypes();
		unknownRoomPixelsDoNotCreateOccupancy();
		borderDoorPixelsDoNotCreateRoomCells();
		hiddenRepeatedRoomIdentitiesStaySeparate();
		hiddenRoomGroupingRespectsShapeAndDoorEvidence();
		catalogIdentityRequiresMatchingFullRoomFootprint();
		ambiguousRoomColorFallsBackToNormalUnlessCatalogKnown();
		predictionRecordPreservesJavaContract();
		specialColumnAndOrdinaryPredictionsMatchOdinRules();
		unopenedRoomDoorRemainsVisibleFromConfirmedEvidence();
		walkedIntoRoomIsViewableAndPlayerOffsetsAreBounded();
		centerChunkCoordinatesMatchTheSixBySixFrame();
		roomLifecycleRetainsVisitsAndClearsRunState();
		roomLifecycleRecordPreservesJavaContract();
		invalidOrNonOdinBuffersFailClosed();
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		run();
	}

	private record ExpectedRoom(java.util.List<Integer> segments, OdinDungeonMapGrid.MapRoomType type,
		OdinDungeonMapGrid.Checkmark checkmark, String shape) { }
	private record ExpectedScanResult(boolean available, OdinDungeonMapGrid.Layout layout,
		java.util.List<OdinDungeonMapGrid.Tile> tiles, java.util.List<OdinDungeonMapGrid.Room> rooms,
		java.util.List<OdinDungeonMapGrid.Door> doors, String reason) { }
	private record ExpectedRoomCore(int hash, int highestBlock, OdinDungeonRoomCatalog.RoomData data) { }

	private static void scannerRoomCoreRetainsJavaRecordContract() {
		Class<?> type = OdinDungeonMapCoreScanner.RoomCore.class;
		require(type.isRecord(), "RoomCore remains a Java record");
		require(java.util.Arrays.stream(type.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName).toList()
			.equals(java.util.List.of("hash", "highestBlock", "data")),
			"RoomCore retains ordered record components");
		var data = new OdinDungeonRoomCatalog.RoomData("Map", "NORMAL", "1x1", 0, 1, 0);
		var core = new OdinDungeonMapCoreScanner.RoomCore(7, 90, data);
		var same = new OdinDungeonMapCoreScanner.RoomCore(7, 90, data);
		var expected = new ExpectedRoomCore(7, 90, data);
		require(core.equals(same) && same.equals(core) && core.hashCode() == expected.hashCode(),
			"RoomCore keeps structural equality and hash behavior");
		require(core.toString().equals(expected.toString().replaceFirst("^ExpectedRoomCore", "RoomCore")),
			"RoomCore keeps the Java record string form");
	}

	private static void mapGridKotlinValuesRetainTheirContract() {
		assertKotlinValueContract(OdinDungeonMapGrid.DungeonTabPlayer.class,
			new String[] { "name", "dungeonClass", "dead" }, String.class, String.class, boolean.class);
		assertKotlinValueContract(OdinDungeonMapGrid.Layout.class,
			new String[] { "available", "roomSize", "roomGap", "startX", "startY", "reason" },
			boolean.class, int.class, int.class, int.class, int.class, String.class);
		assertKotlinValueContract(OdinDungeonMapGrid.Tile.class,
			new String[] { "index", "x", "z", "type", "roomColor", "centerColor", "checkmark" },
			int.class, int.class, int.class, OdinDungeonMapGrid.MapRoomType.class, int.class, int.class,
			OdinDungeonMapGrid.Checkmark.class);
		assertKotlinValueContract(OdinDungeonMapGrid.Door.class,
			new String[] { "firstCell", "secondCell", "direction", "type", "color" },
			int.class, int.class, OdinDungeonMapGrid.Direction.class, OdinDungeonMapGrid.DoorType.class, int.class);
		assertKotlinValueContract(OdinDungeonMapGrid.Room.class,
			new String[] { "segments", "type", "checkmark", "shape" }, java.util.List.class,
			OdinDungeonMapGrid.MapRoomType.class, OdinDungeonMapGrid.Checkmark.class, String.class);
		assertKotlinValueContract(OdinDungeonMapGrid.ScanResult.class,
			new String[] { "available", "layout", "tiles", "rooms", "doors", "reason" }, boolean.class,
			OdinDungeonMapGrid.Layout.class, java.util.List.class, java.util.List.class, java.util.List.class, String.class);
		try {
			OdinDungeonMapGrid.class.getMethod("scan", byte[].class);
			OdinDungeonMapGrid.class.getMethod("puzzleCountFromTabLines", Iterable.class);
			OdinDungeonMapGrid.class.getMethod("dungeonClassFromTabLine", String.class);
			OdinDungeonMapGrid.class.getMethod("dungeonPlayerFromTabLine", String.class);
			OdinDungeonMapGrid.class.getMethod("detectLayout", byte[].class);
			OdinDungeonMapGrid.Room.class.getMethod("segments");
			OdinDungeonMapGrid.ScanResult.class.getMethod("tiles");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("Odin map grid lost a Java API member", error);
		}
		var layout = new OdinDungeonMapGrid.Layout(true, 16, 20, 22, 22, null);
		var tile = new OdinDungeonMapGrid.Tile(1, 1, 0, OdinDungeonMapGrid.MapRoomType.NORMAL,
			63, 30, OdinDungeonMapGrid.Checkmark.GREEN);
		var mutableSegments = new java.util.ArrayList<>(java.util.List.of(1, 2));
		var room = new OdinDungeonMapGrid.Room(mutableSegments, OdinDungeonMapGrid.MapRoomType.NORMAL,
			OdinDungeonMapGrid.Checkmark.GREEN, "1x2");
		mutableSegments.clear();
		var expectedRoom = new ExpectedRoom(java.util.List.of(1, 2), OdinDungeonMapGrid.MapRoomType.NORMAL,
			OdinDungeonMapGrid.Checkmark.GREEN, "1x2");
		var sameRoom = new OdinDungeonMapGrid.Room(java.util.List.of(1, 2),
			OdinDungeonMapGrid.MapRoomType.NORMAL, OdinDungeonMapGrid.Checkmark.GREEN, "1x2");
		require(room.segments().equals(java.util.List.of(1, 2)) && room.equals(sameRoom)
			&& sameRoom.equals(room) && room.hashCode() == expectedRoom.hashCode()
			&& room.toString().equals(expectedRoom.toString().replaceFirst("^ExpectedRoom", "Room")),
			"room values snapshot segments and retain record-style value semantics");
		require(new OdinDungeonMapGrid.Room(null, null, null, null).segments().isEmpty(),
			"room records normalize a null segment list to an immutable empty list");
		var mutableTiles = new java.util.ArrayList<>(java.util.List.of(tile));
		var scan = new OdinDungeonMapGrid.ScanResult(true, layout, mutableTiles,
			new java.util.ArrayList<>(java.util.List.of(room)), java.util.List.of(), null);
		mutableTiles.clear();
		var expectedScan = new ExpectedScanResult(true, layout, java.util.List.of(tile), java.util.List.of(room),
			java.util.List.of(), "");
		var sameScan = new OdinDungeonMapGrid.ScanResult(true, layout, java.util.List.of(tile),
			java.util.List.of(room), java.util.List.of(), "");
		require(scan.tiles().equals(java.util.List.of(tile)) && scan.equals(sameScan) && sameScan.equals(scan)
			&& scan.hashCode() == expectedScan.hashCode()
			&& scan.toString().equals(expectedScan.toString().replaceFirst("^ExpectedScanResult", "ScanResult")),
			"scan results snapshot lists and retain record-style value semantics");
		var normalized = new OdinDungeonMapGrid.ScanResult(false, null, null, null, null, null);
		require(normalized.tiles().isEmpty() && normalized.rooms().isEmpty() && normalized.doors().isEmpty()
			&& normalized.reason().isEmpty(), "null scan-result components keep the former empty defaults");
		require(tile.mapSeen() && !new OdinDungeonMapGrid.Tile(-1, -1, -1,
			OdinDungeonMapGrid.MapRoomType.UNDISCOVERED, 0, 0, OdinDungeonMapGrid.Checkmark.UNDISCOVERED).mapSeen(),
			"map-seen behavior remains tied to recognized room colors");
	}

	private static void catalogContainsBundledRoomIdentity() {
		OdinDungeonRoomCatalog.CatalogResult catalog = OdinDungeonRoomCatalog.bundled();
		require(catalog.available(), "bundled Odin room catalog loads");
		require(catalog.rows() == 140, "bundled room row count remains 140");
		require(catalog.byCore().size() == 272, "bundled room catalog retains all 272 unique core signatures");
		OdinDungeonRoomCatalog.RoomData archway = catalog.find(259768244);
		require(archway != null && archway.name().equals("Archway") && archway.shape().equals("1x2"),
			"room signature resolves to its exact name and shape");
		require(java.util.Map.of(
			OdinDungeonMapGrid.MapRoomType.ENTRANCE, 30,
			OdinDungeonMapGrid.MapRoomType.FAIRY, 82,
			OdinDungeonMapGrid.MapRoomType.NORMAL, 63,
			OdinDungeonMapGrid.MapRoomType.RARE, 63,
			OdinDungeonMapGrid.MapRoomType.BLOOD, 18,
			OdinDungeonMapGrid.MapRoomType.CHAMPION, 74,
			OdinDungeonMapGrid.MapRoomType.UNKNOWN, 85,
			OdinDungeonMapGrid.MapRoomType.PUZZLE, 66,
			OdinDungeonMapGrid.MapRoomType.TRAP, 62,
			OdinDungeonMapGrid.MapRoomType.UNDISCOVERED, -1).entrySet().stream()
			.allMatch(entry -> entry.getKey().mapColor() == entry.getValue()),
			"Odin room-type map palette values match the pinned RoomData enum");
	}

	private static void independentMinibossMetadataUsesItsOwnRoomCategory() {
		OdinDungeonRoomCatalog.CatalogResult catalog = OdinDungeonRoomCatalog.parse(
			"[{\"name\":\"Test miniboss room\",\"type\":\"MINIBOSS\",\"shape\":\"1x1\",\"cores\":[7]}]");
		require(catalog.available() && "MINIBOSS".equals(catalog.find(7).type()),
			"independently verified miniboss metadata remains separate from Yellow/Champion map pixels");
		var map = mapWithVisibleCells(74, new int[][]{{1, 1}}, new int[0][]);
		var currentCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-168, -168);
		var data = catalog.find(7);
		var projected = OdinDungeonMapProjection.project(map,
			java.util.Map.of(7, new OdinDungeonMapCoreScanner.RoomCore(7, 90, data)), currentCell,
			java.util.List.of(), 4, 1, false);
		require(projected.rooms().stream().anyMatch(room ->
			room.type() == geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.MINIBOSS),
			"verified miniboss catalog metadata reaches the distinct map color category");
	}

	private static void catalogMatchesPinnedOdinAssetAndCounts() {
		try (var input = OdinDungeonMapChecks.class.getResourceAsStream("/data/odin-rooms.json")) {
			require(input != null, "pinned Odin room JSON is on the offline test classpath");
			byte[] bytes = input.readAllBytes();
			String digest = java.util.HexFormat.of().formatHex(
				java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
			require(digest.equals("aea05d2eafc753c9b782652165ff3424da485d3a6ca0f62704c019e27cd70947"),
				"room JSON remains byte-identical to Odin's pinned assets/odin/rooms.json (SHA-256)");
			String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
			com.google.gson.JsonArray rows = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
			java.util.Map<String, Integer> types = new java.util.HashMap<>();
			java.util.Map<String, Integer> shapes = new java.util.HashMap<>();
			java.util.Set<Integer> signatures = new java.util.HashSet<>();
			int coreCount = 0;
			int cryptRows = 0;
			int maxSecretRows = 0;
			int trappedChestRows = 0;
			for (com.google.gson.JsonElement element : rows) {
				com.google.gson.JsonObject row = element.getAsJsonObject();
				types.merge(row.get("type").getAsString(), 1, Integer::sum);
				shapes.merge(row.get("shape").getAsString(), 1, Integer::sum);
				com.google.gson.JsonArray cores = row.getAsJsonArray("cores");
				coreCount += cores.size();
				for (com.google.gson.JsonElement core : cores) signatures.add(core.getAsInt());
				if (row.has("crypts")) cryptRows++;
				if (row.has("maxSecrets")) maxSecretRows++;
				if (row.has("trappedChests")) trappedChestRows++;
			}
			require(rows.size() == 140 && coreCount == 272 && signatures.size() == 272,
				"pinned asset contains 140 rooms and 272 unique core signatures");
			require(types.equals(java.util.Map.of("NORMAL", 109, "RARE", 11, "PUZZLE", 11, "CHAMPION", 4,
				"TRAP", 2, "ENTRANCE", 1, "FAIRY", 1, "BLOOD", 1)),
				"room type counts match the pinned Odin asset");
			require(shapes.equals(java.util.Map.of("1x1", 93, "1x2", 14, "1x3", 7, "1x4", 6,
				"2x2", 9, "L", 11)), "room shape counts match the pinned Odin asset");
			require(cryptRows == 86 && maxSecretRows == 122 && trappedChestRows == 5,
				"optional crypt, secret, and trapped-chest metadata counts match the pinned Odin asset");
		} catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
			throw new AssertionError("unable to verify pinned room data", error);
		}
	}

	private static void roomRotationMatchesOdinProbeOrderAndShapes() {
		var oneByOne = room("Marker room", "1x1");
		java.util.List<OdinDungeonRoomRotation.ProbePosition> probes = new java.util.ArrayList<>();
		var firstMatch = OdinDungeonRoomRotation.resolve(oneByOne, java.util.List.of(0), 90, probe -> {
			probes.add(probe);
			return OdinDungeonRoomRotation.ProbeState.BLUE_TERRACOTTA;
		});
		require(firstMatch.resolved() && firstMatch.rotation() == OdinDungeonRoomRotation.Rotation.NORTH
			&& probes.equals(java.util.List.of(new OdinDungeonRoomRotation.ProbePosition(-170, 90, -170))),
			"1x1 probes start at NORTH using Odin's world coordinates and first-match order");

		probes.clear();
		var secondMatch = OdinDungeonRoomRotation.resolve(oneByOne, java.util.List.of(0), 90, probe -> {
			probes.add(probe);
			return probes.size() == 1 ? OdinDungeonRoomRotation.ProbeState.OTHER
				: OdinDungeonRoomRotation.ProbeState.BLUE_TERRACOTTA;
		});
		require(secondMatch.resolved() && secondMatch.rotation() == OdinDungeonRoomRotation.Rotation.SOUTH
			&& probes.equals(java.util.List.of(new OdinDungeonRoomRotation.ProbePosition(-170, 90, -170),
				new OdinDungeonRoomRotation.ProbePosition(-200, 90, -200))),
			"1x1 probe order continues SOUTH only after NORTH is known not to match");

		probes.clear();
		var thirdMatch = OdinDungeonRoomRotation.resolve(oneByOne, java.util.List.of(0), 90, probe -> {
			probes.add(probe);
			return probes.size() < 3 ? OdinDungeonRoomRotation.ProbeState.OTHER
				: OdinDungeonRoomRotation.ProbeState.BLUE_TERRACOTTA;
		});
		require(thirdMatch.resolved() && thirdMatch.rotation() == OdinDungeonRoomRotation.Rotation.WEST
			&& probes.size() == 3 && probes.getLast().equals(new OdinDungeonRoomRotation.ProbePosition(-170, 90, -200)),
			"1x1 probe order reaches WEST only after NORTH and SOUTH are known not to match");

		probes.clear();
		var fourthMatch = OdinDungeonRoomRotation.resolve(oneByOne, java.util.List.of(0), 90, probe -> {
			probes.add(probe);
			return probes.size() < 4 ? OdinDungeonRoomRotation.ProbeState.OTHER
				: OdinDungeonRoomRotation.ProbeState.BLUE_TERRACOTTA;
		});
		require(fourthMatch.resolved() && fourthMatch.rotation() == OdinDungeonRoomRotation.Rotation.EAST
			&& probes.size() == 4 && probes.getLast().equals(new OdinDungeonRoomRotation.ProbePosition(-200, 90, -170)),
			"1x1 probe order checks EAST fourth and returns that orientation only on its match");

		probes.clear();
		var missingEvidence = OdinDungeonRoomRotation.resolve(oneByOne, java.util.List.of(0), 90, probe -> {
			probes.add(probe);
			return OdinDungeonRoomRotation.ProbeState.UNAVAILABLE;
		});
		require(!missingEvidence.resolved() && probes.size() == 1,
			"an unloaded earlier 1x1 probe remains unresolved instead of guessing from later blocks");
		probes.clear();
		var noMarker = OdinDungeonRoomRotation.resolve(oneByOne, java.util.List.of(0), 90, probe -> {
			probes.add(probe);
			return OdinDungeonRoomRotation.ProbeState.OTHER;
		});
		require(!noMarker.resolved() && probes.size() == 4,
			"all four known non-matching probes leave the room unresolved");
		var fairy = OdinDungeonRoomRotation.resolve(room("Fairy", "1x1"), java.util.List.of(0), 90, probe -> {
			throw new AssertionError("Odin's Fairy SOUTH fallback must not probe blocks");
		});
		require(fairy.resolved() && fairy.rotation() == OdinDungeonRoomRotation.Rotation.SOUTH
			&& fairy.clayPosition().equals(new OdinDungeonRoomRotation.ProbePosition(-200, 90, -200)),
			"Fairy uses Odin's unconditional SOUTH fallback");

		assertRotation("1x2 vertical", "1x2", java.util.List.of(0, 6), OdinDungeonRoomRotation.Rotation.WEST);
		assertRotation("1x2 horizontal", "1x2", java.util.List.of(0, 1), OdinDungeonRoomRotation.Rotation.SOUTH);
		assertRotation("1x3 vertical", "1x3", java.util.List.of(0, 6, 12), OdinDungeonRoomRotation.Rotation.WEST);
		assertRotation("1x3 horizontal", "1x3", java.util.List.of(0, 1, 2), OdinDungeonRoomRotation.Rotation.SOUTH);
		assertRotation("1x4 vertical", "1x4", java.util.List.of(0, 6, 12, 18), OdinDungeonRoomRotation.Rotation.WEST);
		assertRotation("1x4 horizontal", "1x4", java.util.List.of(0, 1, 2, 3), OdinDungeonRoomRotation.Rotation.SOUTH);
		assertRotation("2x2", "2x2", java.util.List.of(0, 1, 6, 7), OdinDungeonRoomRotation.Rotation.SOUTH);
		assertRotation("L missing bottom-right", "L", java.util.List.of(0, 1, 6), OdinDungeonRoomRotation.Rotation.WEST);
		assertRotation("L missing bottom-left", "L", java.util.List.of(0, 1, 7), OdinDungeonRoomRotation.Rotation.NORTH);
		assertRotation("L missing top-right", "L", java.util.List.of(0, 6, 7), OdinDungeonRoomRotation.Rotation.SOUTH);
		assertRotation("L missing top-left", "L", java.util.List.of(1, 6, 7), OdinDungeonRoomRotation.Rotation.NORTH);
		assertRotation("L missing top-left with reversed segment order", "L", java.util.List.of(6, 1, 7),
			OdinDungeonRoomRotation.Rotation.SOUTH);
		var vertical = OdinDungeonRoomRotation.resolve(room("Vertical", "1x2"), java.util.List.of(0, 6), 90, null);
		var horizontal = OdinDungeonRoomRotation.resolve(room("Horizontal", "1x2"), java.util.List.of(0, 1), 90, null);
		require(vertical.clayPosition().equals(new OdinDungeonRoomRotation.ProbePosition(-170, 90, -200))
			&& horizontal.clayPosition().equals(new OdinDungeonRoomRotation.ProbePosition(-200, 90, -200)),
			"multi-tile clay positions use Odin's shape-specific world transform");
		var badFootprint = OdinDungeonRoomRotation.resolve(room("Room", "1x3"), java.util.List.of(0, 1), 90, null);
		require(!badFootprint.resolved(), "incomplete room geometry never produces a guessed rotation");
		require(OdinDungeonRoomRotation.passesHeightGate(90, 100, false)
			&& !OdinDungeonRoomRotation.passesHeightGate(91, 100, false)
			&& OdinDungeonRoomRotation.passesHeightGate(150, 100, true),
			"room-entry height gate matches Odin's ten-block ceiling and single-player bypass");
	}

	private static void assertRotation(String label, String shape, java.util.List<Integer> tiles,
		OdinDungeonRoomRotation.Rotation expected) {
		var result = OdinDungeonRoomRotation.resolve(room(label, shape), tiles, 90, null);
		require(result.resolved() && result.rotation() == expected, label + " rotation matches Odin geometry");
	}

	private static void roomRotationRetainsJavaAbi() {
		try {
			var type = OdinDungeonRoomRotation.class;
			var resolve = type.getMethod("resolve", OdinDungeonRoomCatalog.RoomData.class, java.util.List.class,
				Integer.class, OdinDungeonRoomRotation.ProbeLookup.class);
			var heightGate = type.getMethod("passesHeightGate", int.class, int.class, boolean.class);
			require(resolve.getReturnType() == OdinDungeonRoomRotation.Result.class
				&& java.lang.reflect.Modifier.isStatic(resolve.getModifiers()),
				"room rotation retains its public static Java entrypoint");
			require(heightGate.getReturnType() == boolean.class
				&& java.lang.reflect.Modifier.isStatic(heightGate.getModifiers()),
				"room rotation retains its public static height gate");
			require(OdinDungeonRoomRotation.ProbePosition.class.isRecord()
				&& java.util.Arrays.stream(OdinDungeonRoomRotation.ProbePosition.class.getRecordComponents())
					.map(java.lang.reflect.RecordComponent::getName).toList().equals(java.util.List.of("x", "y", "z")),
				"probe positions retain Java record components and order");
			require(OdinDungeonRoomRotation.Result.class.isRecord()
				&& java.util.Arrays.stream(OdinDungeonRoomRotation.Result.class.getRecordComponents())
					.map(java.lang.reflect.RecordComponent::getName)
					.toList().equals(java.util.List.of("rotation", "clayPosition", "reason")),
				"results retain Java record components and order");
			require(java.util.Arrays.stream(OdinDungeonRoomRotation.Rotation.values())
				.map(Enum::name).toList().equals(java.util.List.of("NORTH", "SOUTH", "WEST", "EAST")),
				"room rotation enum order remains a Java-visible contract");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("room rotation Java ABI changed", error);
		}
	}

	private static OdinDungeonRoomCatalog.RoomData room(String name, String shape) {
		return new OdinDungeonRoomCatalog.RoomData(name, "NORMAL", shape, 0, 0, 0);
	}

	private static void mapLayoutGroupsRoomsAndReadsDoors() {
		byte[] colors = new byte[128 * 128];
		for (int x = 22; x < 40; x++) pixel(colors, x, 22, 30);
		pixel(colors, 44, 22, 63);
		pixel(colors, 44 + 9, 22 + 9, 34);
		pixel(colors, 66, 22, 63);
		pixel(colors, 66 + 9, 22 + 9, 30);
		pixel(colors, 64, 22, 63); // Odin's asymmetric east-neighbor room-connection pixel.
		pixel(colors, 64, 31, 119); // Horizontal wither-door pixel.

		OdinDungeonMapGrid.ScanResult scan = OdinDungeonMapGrid.scan(colors);
		require(scan.available(), "18-pixel entrance run initializes the 6x6 layout");
		require(scan.layout().roomSize() == 18 && scan.layout().roomGap() == 22
			&& scan.layout().startX() == 22 && scan.layout().startY() == 22,
			"room size and zero-modulo entrance offsets match Odin");
		require(scan.rooms().stream().anyMatch(room -> room.segments().equals(java.util.List.of(1, 2))
			&& room.checkmark() == OdinDungeonMapGrid.Checkmark.GREEN),
			"asymmetric gap BFS joins same-color room tiles and reads the green checkmark");
		require(scan.doors().stream().anyMatch(door -> door.firstCell() == 1 && door.secondCell() == 2
			&& door.direction() == OdinDungeonMapGrid.Direction.HORIZONTAL
			&& door.type() == OdinDungeonMapGrid.DoorType.WITHER),
			"side-check pixel rule identifies the wither door");
	}

	private static void invalidOrNonOdinBuffersFailClosed() {
		require(!OdinDungeonMapGrid.scan(new byte[64]).available(), "short map buffer is rejected");
		byte[] colors = new byte[128 * 128];
		pixel(colors, 10, 10, 30);
		require(!OdinDungeonMapGrid.scan(colors).available(), "a non-16/18-pixel entrance does not calibrate the map");
		byte[] savedRaster = mapColorsWithVisibleCells(18, 5, new int[][]{{1, 1}}, new int[0][]);
		pixel(savedRaster, 0, 0, 18);
		require(OdinDungeonMapGrid.scan(savedRaster).available(),
			"a complete saved raster is scanned even when pixel zero is non-empty");
	}

	private static void tabListPuzzleCountMatchesOdinFormat() {
		require(OdinDungeonMapGrid.puzzleCountFromTabLines(java.util.List.of(
			"[MVP+] Player", " Puzzles: (2) ", "Secrets Found: 5")) == 2,
			"tab-list parser reads Odin's Puzzles row");
		require(OdinDungeonMapGrid.puzzleCountFromTabLines(java.util.List.of("Puzzles: (x)")) == -1,
			"missing or malformed puzzle counter stays unknown");
		require("Mage".equals(OdinDungeonMapGrid.dungeonClassFromTabLine("[1] [MVP+] Alice (Mage L)"))
			&& "DEAD".equals(OdinDungeonMapGrid.dungeonClassFromTabLine("[2] Bob (DEAD)"))
			&& OdinDungeonMapGrid.dungeonClassFromTabLine("[3] Charlie (Archer) extra") == null,
			"dungeon roster parser accepts plus-suffixed ranks and keeps Odin's tab-row class/death markers");
		OdinDungeonPlayerRoster.reset();
		OdinDungeonPlayerRoster.updateRows(java.util.List.of("[1] [MVP+] Alice (Mage L)", "[2] Bob (Tank XL)"));
		OdinDungeonPlayerRoster.updateRows(java.util.List.of("[2] Bob (DEAD)"));
		var roster = OdinDungeonPlayerRoster.snapshot();
		require(roster.size() == 2 && roster.get(0).name().equals("Alice")
			&& roster.get(1).name().equals("Bob") && roster.get(1).dungeonClass().equals("Tank")
			&& roster.get(1).dead(),
			"player-info updates retain first-seen tab order and preserve a dead player's class");
		OdinDungeonPlayerRoster.updateRows(java.util.List.of("[2] Bob (Tank XL)"));
		require(!OdinDungeonPlayerRoster.snapshot().get(1).dead(),
			"later player-info rows clear the dead marker without reordering the roster");
		java.util.UUID aliceProfile = java.util.UUID.nameUUIDFromBytes("alice".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		OdinDungeonPlayerRoster.removeProfiles(java.util.List.of(aliceProfile));
		require(OdinDungeonPlayerRoster.snapshot().size() == 1
			&& OdinDungeonPlayerRoster.snapshot().getFirst().name().equals("Bob"),
			"player-info removal drops the matching teammate before map-decoration pairing");
		OdinDungeonPlayerRoster.onDisconnect();
		require(OdinDungeonPlayerRoster.snapshot().isEmpty(),
			"disconnect clears all teammate identities and ordering state");
		OdinDungeonPlayerRoster.reset();
		OdinDungeonPlayerRoster.updateRows(java.util.List.of(
			"[1] FirstDead (DEAD)", "[2] Existing (Tank XL)"));
		var firstDeadRoster = OdinDungeonPlayerRoster.snapshot();
		require(firstDeadRoster.size() == 2 && firstDeadRoster.get(0).name().equals("FirstDead")
			&& firstDeadRoster.get(0).dungeonClass().isEmpty() && firstDeadRoster.get(0).dead()
			&& firstDeadRoster.get(1).name().equals("Existing"),
			"a first-observed dead tab row is retained in its original position without inventing a class");
		OdinDungeonPlayerRoster.updateRows(java.util.List.of("[1] FirstDead (Archer)"));
		var revivedRoster = OdinDungeonPlayerRoster.snapshot();
		require(revivedRoster.size() == 2 && revivedRoster.get(0).name().equals("FirstDead")
			&& revivedRoster.get(0).dungeonClass().equals("Archer") && !revivedRoster.get(0).dead()
			&& revivedRoster.get(1).name().equals("Existing"),
			"a later live class row restores first-observed dead status without changing tab order");
		OdinDungeonPlayerRoster.reset();
	}

	private static void playerMapRequiresLoadedIdentityMatch() {
		OdinDungeonMapGrid.Layout layout = mapWithVisibleCells(new int[0][]).layout();
		var frame = new geiler.addons.client.dungeon.DungeonMapRoomDetector.SavedMapDecoration(0, 0, 0, true);
		var mageDecoration = new geiler.addons.client.dungeon.DungeonMapRoomDetector.SavedMapDecoration(-12, 66, 4, false);
		var tankDecoration = new geiler.addons.client.dungeon.DungeonMapRoomDetector.SavedMapDecoration(32, 22, 8, false);
		var roster = java.util.List.of(
			new OdinDungeonPlayerMap.TabPlayer("MageOne", "Mage", false),
			new OdinDungeonPlayerMap.TabPlayer("DeadOne", "DEAD", true),
			new OdinDungeonPlayerMap.TabPlayer("TankOne", "Tank", false));
		var loaded = java.util.List.of(
			new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.PlayerMarker(
				1, 1, "MageOne", false, 0.25, 0.75, "Mage", 10.0f),
			new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.PlayerMarker(
				0, 0, "Local", true, 0.5, 0.5, "Healer", 45.0f));
		var projection = OdinDungeonPlayerMap.projectWithDiagnostics(roster,
			java.util.List.of(frame, mageDecoration, tankDecoration), loaded, "Local");
		var markers = projection.players();
		require(markers.size() == 2, "frame icons and unresolved roster identities do not create player markers");
		var mage = markers.get(0);
		require(mage.name().equals("MageOne") && mage.x() == 1 && mage.z() == 1
			&& mage.offsetX() == 0.25 && mage.offsetZ() == 0.75 && mage.yawDegrees() == 10.0f,
			"a roster player uses the matching loaded entity position and yaw");
		require(markers.get(1).localPlayer() && markers.get(1).yawDegrees() == 45.0f,
			"the local marker remains entity-positioned and uses the player's view yaw");
		require(markers.stream().noneMatch(marker -> marker.name().equals("TankOne"))
			&& projection.diagnostics().contains("unresolvedRoster=1")
			&& projection.diagnostics().contains("decorationIdentity=unassigned"),
			"an unmatched startup decoration is suppressed instead of being assigned by roster position");
	}

	private static void playerMapPairsOrderedDecorationsWithLivingRoster() {
		var layout = new OdinDungeonMapGrid.Layout(true, 16, 20, 22, 22, "fixture");
		var frame = new geiler.addons.client.dungeon.DungeonMapRoomDetector.SavedMapDecoration(0, 0, 0, true);
		var firstIcon = new geiler.addons.client.dungeon.DungeonMapRoomDetector.SavedMapDecoration(-34, 16, 4, false);
		var secondIcon = new geiler.addons.client.dungeon.DungeonMapRoomDetector.SavedMapDecoration(80, 52, 8, false);
		var roster = java.util.List.of(
			new OdinDungeonPlayerMap.TabPlayer("Alice", "Mage", false),
			new OdinDungeonPlayerMap.TabPlayer("Downed", "", true),
			new OdinDungeonPlayerMap.TabPlayer("Bob", "Tank", false));
		var local = new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.PlayerMarker(
			5, 5, "Local", true, 0.5, 0.5, "Healer", 25.0f);

		var projection = OdinDungeonPlayerMap.projectWithDiagnostics(layout, roster,
			java.util.List.of(frame, firstIcon, secondIcon), java.util.List.of(local), "Local");
		var markers = projection.players();
		require(markers.size() == 3 && markers.get(0).name().equals("Alice")
			&& markers.get(0).x() == 1 && markers.get(0).z() == 2
			&& markers.get(1).name().equals("Bob") && markers.get(1).x() == 4 && markers.get(1).z() == 3,
			"ordered map icons pair to living teammate rows while skipping the dead row");
		require(markers.get(2).localPlayer() && markers.get(2).x() == 5
			&& projection.diagnostics().contains("pairing=ordered")
			&& projection.diagnostics().contains("paired=2"),
			"the local marker remains entity-positioned and pairing is reported");

		var loadedAlice = new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.PlayerMarker(
			1, 2, "Alice", false, 0.25, 0.75, "Mage", 10.0f);
		var mismatch = OdinDungeonPlayerMap.projectWithDiagnostics(layout,
			java.util.List.of(new OdinDungeonPlayerMap.TabPlayer("Alice", "Mage", false),
				new OdinDungeonPlayerMap.TabPlayer("Bob", "Tank", false)),
			java.util.List.of(secondIcon), java.util.List.of(loadedAlice, local), "Local");
		require(mismatch.players().size() == 2 && mismatch.players().getFirst().name().equals("Alice")
			&& mismatch.players().stream().noneMatch(marker -> marker.name().equals("Bob"))
			&& mismatch.diagnostics().contains("pairing=countMismatch")
			&& mismatch.diagnostics().contains("fallback=1"),
			"mismatched icon counts use only name-matched loaded-player fallback");
	}

	private static void currentPlayerRosterUsesVisibleTabOrder() {
		var cached = java.util.List.of(new OdinDungeonPlayerMap.TabPlayer("Alice", "Mage", false),
			new OdinDungeonPlayerMap.TabPlayer("Bob", "Tank", false));
		var current = OdinDungeonPlayerRoster.currentDungeonRows(java.util.List.of(
			"[1] [MVP+] Bob (DEAD)", "[2] Alice (Archer L)", "[3] Spectator (MVP++)"), cached);
		require(current.size() == 2 && current.get(0).name().equals("Bob") && current.get(0).dead()
			&& current.get(0).dungeonClass().equals("Tank") && current.get(1).name().equals("Alice")
			&& current.get(1).dungeonClass().equals("Archer"),
			"current tab order is preserved, dead class data is retained, and spectators are excluded");
	}

	private static void predictionRecordPreservesJavaContract() {
		var normalized = new OdinSpecialColumnPredictor.Prediction(2, 3, null);
		var components = normalized.getClass().getRecordComponents();
		var componentNames = java.util.Arrays.stream(components)
			.map(java.lang.reflect.RecordComponent::getName).toList();
		var componentTypes = java.util.Arrays.stream(components)
			.map(java.lang.reflect.RecordComponent::getType).toList();
		boolean emptyListIsImmutable = false;
		try {
			normalized.possibleTypes().add(geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.PUZZLE);
		} catch (UnsupportedOperationException expected) {
			emptyListIsImmutable = true;
		}
		require(normalized.getClass().isRecord()
			&& components.length == 3
			&& componentNames.equals(java.util.List.of("x", "z", "possibleTypes"))
			&& componentTypes.equals(java.util.List.of(int.class, int.class, java.util.List.class))
			&& normalized.possibleTypes().isEmpty() && emptyListIsImmutable,
			"Prediction keeps its Java record components and normalizes null types to an immutable empty list");

		var inputTypes = new java.util.ArrayList<>(java.util.List.of(
			geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.PUZZLE));
		var copied = new OdinSpecialColumnPredictor.Prediction(2, 3, inputTypes);
		inputTypes.clear();
		boolean copiedListIsImmutable = false;
		try {
			copied.possibleTypes().add(geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.TRAP);
		} catch (UnsupportedOperationException expected) {
			copiedListIsImmutable = true;
		}
		require(copiedListIsImmutable && copied.possibleTypes().equals(java.util.List.of(
			geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.PUZZLE)),
			"Prediction copies supplied types and exposes them as an immutable list");
	}

	private static void specialColumnAndOrdinaryPredictionsMatchOdinRules() {
		OdinDungeonMapGrid.ScanResult specialMap = mapWithVisibleCells(16, 5,
			new int[][]{{4, 0}}, new int[][]{{4, 5}});
		require(specialMap.rooms().size() == 2 && specialMap.rooms().stream().noneMatch(room ->
			room.type() == OdinDungeonMapGrid.MapRoomType.UNKNOWN
				|| room.type() == OdinDungeonMapGrid.MapRoomType.UNDISCOVERED),
			"empty map pixels stay null like Odin tiles instead of becoming undiscovered room objects");
		var special = OdinSpecialColumnPredictor.predict(specialMap, java.util.Map.of(), 4, 1, true);
		require(special.size() == 1 && special.get(0).x() == 5 && special.get(0).z() == 0
			&& special.get(0).possibleTypes().equals(java.util.List.of(
				geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.CHAMPION,
				geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.TRAP,
				geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.PUZZLE)),
			"F4-F6 special-column room uses Odin's three-color fallback");

		OdinDungeonMapGrid.ScanResult ordinaryMap = mapWithVisibleCells(new int[][]{
			{1, 1}, {3, 1}, {2, 0}, {2, 2}}, new int[][]{{7, 8}});
		var ordinary = OdinSpecialColumnPredictor.predict(ordinaryMap, java.util.Map.of(), 3, 1, true);
		require(ordinary.stream().anyMatch(value -> value.x() == 2 && value.z() == 1
			&& value.possibleTypes().equals(java.util.List.of(
				geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.PUZZLE,
				geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.TRAP,
				geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.CHAMPION))),
			"non-special prediction requires all four neighboring rooms and a confirmed door to the candidate");

		OdinDungeonMapGrid.ScanResult unconnectedOrdinary = mapWithVisibleCells(new int[][]{
			{1, 1}, {3, 1}, {2, 0}, {2, 2}});
		require(OdinSpecialColumnPredictor.predict(unconnectedOrdinary, java.util.Map.of(), 3, 1, true).stream()
			.noneMatch(value -> value.x() == 2 && value.z() == 1),
			"adjacent room pixels without a map-door edge do not create an unknown-room prediction");

		OdinDungeonMapGrid.ScanResult smallLayout = mapWithVisibleCells(18, 5,
			new int[][]{{4, 0}}, new int[][]{{4, 5}});
		require(smallLayout.layout().roomSize() == 18 && smallLayout.layout().startX() == 5,
			"smaller map fixture keeps the same pixel origin as the larger map while using larger room pixels");
		require(OdinSpecialColumnPredictor.predict(smallLayout, java.util.Map.of(), 4, 1, true).stream()
			.noneMatch(value -> value.x() == 5),
			"room size, rather than startX, keeps the non-special fifth-column edge out of a smaller map");

		var knownFreeRoom = new OdinDungeonRoomCatalog.RoomData("Free Room", "NORMAL", "1x1", 0, 0, 0);
		var knownRoomCores = java.util.Map.of(5,
			new OdinDungeonMapCoreScanner.RoomCore(9001, 90, knownFreeRoom));
		require(OdinSpecialColumnPredictor.predict(specialMap, knownRoomCores, 4, 1, true).stream()
			.noneMatch(value -> value.x() == 5 && value.z() == 0),
			"catalog identity for an unopened normal room suppresses a conflicting puzzle guess");

		byte[] unknownColorMap = mapColorsWithVisibleCells(16, 5, new int[][]{{4, 0}}, new int[][]{{4, 5}});
		paintMapTile(unknownColorMap, 16, 5, 5, 0, 85);
		OdinDungeonMapGrid.ScanResult authoritativeUnknown = OdinDungeonMapGrid.scan(unknownColorMap);
		require(OdinSpecialColumnPredictor.predict(authoritativeUnknown, java.util.Map.of(), 4, 1, true).stream()
			.noneMatch(value -> value.x() == 5 && value.z() == 0),
			"an explicit unknown map color is authoritative and is not overwritten by a puzzle prediction");
	}

	private static void unopenedRoomDoorRemainsVisibleFromConfirmedEvidence() {
		var playerCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-168, -168);
		var roomData = new OdinDungeonRoomCatalog.RoomData("Unopened Test Room", "NORMAL", "1x1", 0, 0, 0);
		var cores = java.util.Map.of(8, new OdinDungeonMapCoreScanner.RoomCore(9100, 90, roomData));
		var mapWithDoor = mapWithVisibleCells(new int[][]{{1, 1}}, new int[][]{{7, 8}});
		var snapshot = OdinDungeonMapProjection.project(mapWithDoor, cores, playerCell,
			java.util.List.of(), 3, 1, false);
		var hiddenRoom = snapshot.rooms().stream().filter(room -> room.name().equals("Unopened Test Room"))
			.findFirst().orElseThrow();
		require(hiddenRoom.visibility() == geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
			&& hiddenRoom.exits().stream().anyMatch(exit -> exit.direction()
				== geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.Direction.WEST),
			"map door pixels attach a confirmed exit to the unopened room that owns the hidden-side evidence");
		var hiddenDoorPlan = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(snapshot, false);
		require(hiddenDoorPlan.visibleCells().stream().noneMatch(cell -> cell.roomId().equals(hiddenRoom.id()))
			&& hiddenDoorPlan.exits().size() == 1 && hiddenDoorPlan.exits().stream().anyMatch(exit ->
				exit.cell().x() == 1 && exit.cell().z() == 1
					&& exit.direction() == geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.Direction.EAST),
			"the confirmed door is drawn from its visible room endpoint, not from its concealed endpoint");
		var revealDoorPlan = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(snapshot, true);
		require(revealDoorPlan.visibleCells().stream().anyMatch(cell -> cell.roomId().equals(hiddenRoom.id())),
			"reveal on adds the complete catalog room supported by the confirmed map door");

		var withoutDoor = mapWithVisibleCells(new int[][]{{1, 1}});
		var noEvidence = OdinDungeonMapProjection.project(withoutDoor, cores, playerCell,
			java.util.List.of(), 3, 1, false);
		var noDoorPlan = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(noEvidence, false);
		require(noDoorPlan.exits().isEmpty(),
			"room adjacency and hidden catalog identity alone do not fabricate a door cue");

		var orphanDoorMap = mapWithVisibleCells(new int[0][], new int[][]{{7, 8}});
		var orphanDoorSnapshot = OdinDungeonMapProjection.project(orphanDoorMap, java.util.Map.of(), playerCell,
			java.util.List.of(), 3, 1, false);
		require(orphanDoorSnapshot.cells().stream().noneMatch(cell -> cell.x() + cell.z() * 6 == 7
			|| cell.x() + cell.z() * 6 == 8), "a door pixel alone does not invent either adjacent room");
		for (boolean reveal : new boolean[]{false, true}) {
			var orphanPlan = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(orphanDoorSnapshot, reveal);
			require(orphanPlan.exits().isEmpty(),
				"door-only evidence is omitted from the render plan in reveal=" + reveal);
		}
	}

	private static void catalogIdentityRequiresMatchingFullRoomFootprint() {
		byte[] colors = mapColorsWithVisibleCells(18, 5, new int[][]{{1, 1}, {2, 1}}, new int[0][]);
		pixel(colors, 5 + 22 + 20, 22 + 22, 63);
		OdinDungeonMapGrid.ScanResult map = OdinDungeonMapGrid.scan(colors);
		require(map.available() && map.rooms().stream().anyMatch(room -> room.segments().equals(java.util.List.of(7, 8))),
			"fixture groups two connected map tiles into one room footprint");
		var playerCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-168, -168);
		var oneTileIdentity = new OdinDungeonRoomCatalog.RoomData("Wrong One-Tile Match", "NORMAL",
			"1x1", 0, 6, 0);
		var mismatched = OdinDungeonMapProjection.project(map,
			java.util.Map.of(7, new OdinDungeonMapCoreScanner.RoomCore(9200, 90, oneTileIdentity)),
			playerCell, java.util.List.of(), 3, 1, false);
		var unresolved = mismatched.rooms().stream().filter(room -> room.footprint().size() == 2).findFirst().orElseThrow();
		require("Unknown".equals(unresolved.name()) && unresolved.maxSecrets() == 0
			&& unresolved.type() == geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.ROOM
			&& "7,8".equals(unresolved.id())
			&& mismatched.rooms().stream().noneMatch(room -> room.id().startsWith("partial-core:")),
			"a signature whose catalog shape disagrees with the full map footprint cannot supply a wrong name or secret maximum");

		var twoTileIdentity = new OdinDungeonRoomCatalog.RoomData("Verified Two-Tile Room", "NORMAL",
			"1x2", 0, 6, 0);
		var matched = OdinDungeonMapProjection.project(map,
			java.util.Map.of(7, new OdinDungeonMapCoreScanner.RoomCore(9201, 90, twoTileIdentity),
				8, new OdinDungeonMapCoreScanner.RoomCore(9202, 90, twoTileIdentity)),
			playerCell, java.util.List.of(), 3, 1, false);
		require(matched.rooms().stream().anyMatch(room -> "Verified Two-Tile Room".equals(room.name())
			&& room.maxSecrets() == 6), "catalog name and secret maximum remain when every footprint segment agrees");
	}

	private static void interpolatedLoadedPlayerCoordinatesProjectToMapGrid() {
		var marker = OdinDungeonPlayerMap.projectLoadedPosition("Alice", false, "Mage",
			-166.0, -149.5, 33.0f);
		require(marker != null && marker.x() == 1 && marker.z() == 1
			&& Math.abs(marker.offsetX() - (2.0 / 32.0)) < 0.0001
			&& Math.abs(marker.offsetZ() - (18.5 / 32.0)) < 0.0001
			&& marker.yawDegrees() == 33.0f,
			"render-interpolated loaded coordinates retain sub-cell position and interpolated yaw");
		require(OdinDungeonPlayerMap.projectLoadedPosition("Outside", false, "", -201.0, -150.0, 0.0f) == null,
			"loaded players outside the supported dungeon grid do not become map markers");
	}

	private static void playerRoomCoreScanIsPrioritized() {
		require(OdinDungeonMapCoreScanner.preferredScanTile(35, 4, true) == 35,
			"a new loaded player-room signature is scanned before the round-robin background tile");
		require(OdinDungeonMapCoreScanner.preferredScanTile(35, 4, false) == 4,
			"an already-scanned or unloaded player-room sample does not stall background discovery");
		require(OdinDungeonMapCoreScanner.preferredScanTile(-1, 4, true) == 4,
			"an invalid player tile never replaces the valid background scan");
	}

	private static void confirmedCoreIdentitySurvivesConflictingRescans() {
		var confirmed = new OdinDungeonMapCoreScanner.RoomCore(700, 90,
			new OdinDungeonRoomCatalog.RoomData("Confirmed Trap", "TRAP", "1x1", 0, 5, 0));
		var conflicting = new OdinDungeonRoomCatalog.RoomData("Conflicting Puzzle", "PUZZLE", "1x1", 0, 5, 0);
		require(OdinDungeonMapCoreScanner.shouldRetainCatalogIdentity(confirmed, null)
			&& OdinDungeonMapCoreScanner.shouldRetainCatalogIdentity(confirmed, conflicting)
			&& !OdinDungeonMapCoreScanner.shouldRetainCatalogIdentity(confirmed, confirmed.data())
			&& !OdinDungeonMapCoreScanner.shouldRetainCatalogIdentity(null, conflicting),
			"a positive catalog identity stays stable across missing or conflicting reads, while matching reads update normally");
	}

	private static void loadedDoorKindsReachOdinExitProjection() {
		OdinDungeonMapGrid.ScanResult map = mapWithDoorPixelColor(63);
		var currentCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-168, -168);
		var palette = new geiler.addons.client.dungeon.DungeonRoomOverlayStyle.DoorColors(
			11, 12, 13, 14, 15, 16, 17);
		for (var kind : geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.DoorKind.values()) {
			var detected = new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.ObservedDoor(7, 8, kind);
			var projected = OdinDungeonMapProjection.project(map, java.util.Map.of(), currentCell,
				java.util.List.of(), 4, 1, false, java.util.List.of(detected));
			var exit = projected.rooms().stream().flatMap(room -> room.exits().stream()).findFirst().orElseThrow();
			var expectedKind = kind == geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.DoorKind.UNKNOWN
				? geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.DoorKind.NORMAL : kind;
			require(exit.kind() == expectedKind, kind + " refines only its matching map-pixel door edge");
			int expectedColor = switch (expectedKind) {
				case NORMAL -> 11;
				case GLASS -> 12;
				case START -> 13;
				case WITHER -> 14;
				case BLOOD -> 15;
				case FAIRY -> 16;
				case UNKNOWN -> 17;
			};
			require(geiler.addons.client.dungeon.DungeonRoomOverlayStyle.doorColor(exit.kind(), palette) == expectedColor,
				kind + " exit selects its configured map color");
		}
		var witherMap = mapWithDoorPixelColor(119);
		var conflictingTracker = new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.ObservedDoor(7, 8,
			geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.DoorKind.GLASS);
		var preservedMapKind = OdinDungeonMapProjection.project(witherMap, java.util.Map.of(), currentCell,
			java.util.List.of(), 4, 1, false, java.util.List.of(conflictingTracker));
		require(preservedMapKind.rooms().stream().flatMap(room -> room.exits().stream())
			.allMatch(exit -> exit.kind() == geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.DoorKind.WITHER),
			"an explicit map door color remains authoritative over a conflicting world-tracker kind");
	}

	private static void trackerOnlyDoorsAttachOnlyToVisibleRooms() {
		var map = mapWithVisibleCells(new int[][]{{1, 1}});
		var currentCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-168, -168);
		var trackerDoor = new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.ObservedDoor(7, 8,
			geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.DoorKind.WITHER);
		var projected = OdinDungeonMapProjection.project(map, java.util.Map.of(), currentCell,
			java.util.List.of(), 4, 1, false, java.util.List.of(trackerDoor));
		var roomExit = projected.rooms().stream().flatMap(room -> room.exits().stream()).findFirst().orElseThrow();
		require(roomExit.kind() == geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.DoorKind.WITHER
			&& geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(projected, false).exits().size() == 1
			&& projected.reason().contains("trackerOnlyAccepted=1"),
			"a positive tracker-only edge attaches to the visible room and is counted as accepted");
		var orphanTracker = OdinDungeonMapProjection.project(mapWithVisibleCells(new int[0][]), java.util.Map.of(),
			currentCell, java.util.List.of(), 4, 1, false, java.util.List.of(trackerDoor));
		require(orphanTracker.cells().stream().noneMatch(cell -> {
			int tile = cell.x() + cell.z() * 6;
			return tile == 7 || tile == 8;
		}) && orphanTracker.reason().contains("roomAttached=0")
			&& geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(orphanTracker, true).exits().isEmpty(),
			"a tracker-only door with no room occupancy evidence is retained diagnostically but never rendered");

		byte[] undiscoveredColors = mapColorsWithVisibleCells(18, 5, new int[][]{{1, 1}}, new int[0][]);
		pixel(undiscoveredColors, 5 + 22 + 9, 22 + 22 + 9, 0);
		OdinDungeonMapGrid.ScanResult undiscoveredMap = OdinDungeonMapGrid.scan(undiscoveredColors);
		var remotePlayerCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200);
		var concealed = OdinDungeonMapProjection.project(undiscoveredMap, java.util.Map.of(), remotePlayerCell,
			java.util.List.of(), 4, 1, false, java.util.List.of(trackerDoor));
		require(geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(concealed, false).exits().isEmpty()
			&& geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(concealed, true).exits().size() == 1,
			"a confirmed edge remains concealed until one of its room endpoints is visible in the selected mode");

		var rejected = OdinDungeonMapProjection.project(map, java.util.Map.of(), currentCell,
			java.util.List.of(), 4, 1, false, java.util.List.of(
				new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.ObservedDoor(7, 14)));
		require(rejected.reason().contains("trackerInvalidRejected=1")
			&& rejected.rooms().stream().allMatch(room -> room.exits().isEmpty()),
			"non-adjacent tracker coordinates are rejected instead of projected as doors");
	}

	private static void exactCorePartialFootprintsShowVerifiedTileData() {
		var map = mapWithVisibleCells(new int[0][]);
		var playerCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200);
		var multiTile = new OdinDungeonRoomCatalog.RoomData("Unconfirmed Multi", "NORMAL", "1x2", 0, 6, 0);
		var core = new OdinDungeonMapCoreScanner.RoomCore(9_201, 90, multiTile);
		var door = new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.ObservedDoor(7, 8,
			geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.DoorKind.NORMAL);
		var partialSnapshot = OdinDungeonMapProjection.project(map, java.util.Map.of(7, core), playerCell,
			java.util.List.of(), 4, 1, false, java.util.List.of(door));
		var partial = partialSnapshot.rooms().stream().filter(room -> room.id().startsWith("partial-core:"))
			.findFirst().orElseThrow();
		var partialCell = partial.footprint().getFirst();
		require(partial.footprint().size() == 1 && !partial.footprintKnown()
			&& partial.type() == geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.ROOM
			&& partial.name().equals(multiTile.name()) && partial.maxSecrets() == multiTile.maxSecrets()
			&& partialCell.type() == geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.ROOM
			&& partialCell.visibility()
				== geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED,
			"an incomplete multi-tile core shows exact type and data on its confirmed tile only");
		int neutralFill = 0xFF484C5B;
		var roomColors = new geiler.addons.client.dungeon.DungeonRoomOverlayStyle.RoomColors(
			1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		require(geiler.addons.client.dungeon.DungeonRoomOverlayStyle.roomFillColor(partialCell, roomColors,
			neutralFill, 0.5) == geiler.addons.client.dungeon.DungeonRoomOverlayStyle.darken(
				geiler.addons.client.dungeon.DungeonRoomOverlayStyle.roomColor(
					geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.ROOM, roomColors), 0.5),
			"an unopened partial core tile uses its verified type color with normal unopened darkening");
		require(partialSnapshot.cells().stream().noneMatch(cell -> cell.x() + cell.z() * 6 == 8)
			&& partialSnapshot.reason().contains("partialCoreCells=1")
			&& partialSnapshot.reason().contains("predictionIncompleteRejected=1"),
			"partial occupancy does not fill the neighboring door cell and diagnostics count the rejected footprint");
		require(!geiler.addons.client.dungeon.DungeonRoomOverlayStyle.predictionTargetAllowed(partialCell),
			"independent room predictions cannot add a guessed identity over an exact-core partial tile");
		var revealOn = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(partialSnapshot, true);
		var revealOff = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(partialSnapshot, false);
		require(revealOn.visibleCells().stream().anyMatch(cell -> cell.roomId().equals(partial.id()))
			&& revealOn.exits().size() == 1 && revealOn.footprintEdges().stream().noneMatch(edge ->
				edge.x() == partialCell.x() && edge.z() == partialCell.z())
			&& revealOff.visibleCells().stream().noneMatch(cell -> cell.roomId().equals(partial.id()))
			&& revealOff.exits().isEmpty(),
			"reveal-on draws the identified core and attached door without an outline; reveal-off hides it");

		var completedSnapshot = OdinDungeonMapProjection.project(map,
			java.util.Map.of(7, core, 8, new OdinDungeonMapCoreScanner.RoomCore(9_202, 90, multiTile)),
			playerCell, java.util.List.of(), 4, 1, false);
		require(completedSnapshot.rooms().stream().anyMatch(room -> room.name().equals(multiTile.name())
			&& room.footprintKnown() && room.footprint().size() == 2)
			&& completedSnapshot.rooms().stream().noneMatch(room -> room.id().startsWith("partial-core:")),
			"once all core tiles agree with the catalog footprint the neutral tiles become one identified room");

		var mapPixelRoom = mapWithVisibleCells(new int[][]{{1, 1}});
		var mappedPartial = OdinDungeonMapProjection.project(mapPixelRoom, java.util.Map.of(7, core),
			playerCell, java.util.List.of(), 4, 1, false);
		var mappedCore = mappedPartial.rooms().stream().filter(room -> room.id().equals("partial-core:7"))
			.findFirst().orElseThrow();
		require(mappedCore.name().equals(multiTile.name()) && mappedCore.maxSecrets() == 6
			&& mappedCore.footprint().size() == 1 && !mappedCore.footprintKnown()
			&& mappedPartial.cells().stream().noneMatch(cell -> cell.x() + cell.z() * 6 == 8),
			"map-visible tiles accept compatible exact core metadata without inventing the remaining footprint");
	}

	private static void worldFallbackKeepsPartialCoreOccupancyAndConfirmedDoors() {
		var multiTile = new OdinDungeonRoomCatalog.RoomData("Fallback Multi", "NORMAL", "1x2", 0, 6, 0);
		var core = new OdinDungeonMapCoreScanner.RoomCore(9_301, 90, multiTile);
		var door = new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.ObservedDoor(7, 8,
			geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.DoorKind.NORMAL);
		var playerCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200);
		var fallback = OdinDungeonWorldRoomProjection.project(java.util.Map.of(7, core), playerCell,
			java.util.List.of(), java.util.List.of(door));
		var partial = fallback.rooms().stream().filter(room -> room.id().equals("partial-core:7"))
			.findFirst().orElseThrow();
		var revealOn = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(fallback, true);
		var revealOff = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(fallback, false);
		require(partial.footprint().size() == 1 && !partial.footprintKnown()
			&& partial.type() == geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.ROOM
			&& partial.name().equals(multiTile.name()) && partial.maxSecrets() == multiTile.maxSecrets()
			&& fallback.cells().stream().noneMatch(cell -> cell.x() + cell.z() * 6 == 8)
			&& fallback.reason().contains("partialCoreCells=1"),
			"map-unavailable fallback displays exact partial type/data and does not infer a neighboring room");
		require(revealOn.visibleCells().stream().anyMatch(cell -> cell.roomId().equals(partial.id()))
			&& revealOn.exits().size() == 1 && revealOn.footprintEdges().isEmpty()
			&& revealOff.visibleCells().isEmpty() && revealOff.exits().isEmpty(),
			"fallback reveal-on draws the partial core and attached tracker door, while reveal-off hides both");
	}

	private static void ambiguousRoomColorFallsBackToNormalUnlessCatalogKnown() {
		require(OdinDungeonMapGrid.MapRoomType.fromMapColor(63) == OdinDungeonMapGrid.MapRoomType.RARE,
			"ambiguous Normal/Rare map color preserves Odin's parser classification");
		var map = mapWithVisibleCells(new int[][]{{1, 1}});
		var currentCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-168, -168);
		var withoutSignature = OdinDungeonMapProjection.project(map, java.util.Map.of(), currentCell,
			java.util.List.of(), 4, 1, false);
		require(withoutSignature.rooms().stream().flatMap(room -> room.footprint().stream())
			.anyMatch(cell -> cell.x() == 1 && cell.z() == 1
				&& cell.type() == geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.ROOM),
			"map color 63 alone does not falsely classify a normal room as Rare");
		var rareEntry = OdinDungeonRoomCatalog.bundled().byCore().entrySet().stream()
			.filter(entry -> "RARE".equals(entry.getValue().type())).findFirst().orElseThrow();
		var knownRare = OdinDungeonMapProjection.project(map,
			java.util.Map.of(7, new OdinDungeonMapCoreScanner.RoomCore(rareEntry.getKey(), 90, rareEntry.getValue())),
			currentCell, java.util.List.of(), 4, 1, false);
		require(knownRare.rooms().stream().anyMatch(room -> room.type()
			== geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.RARE),
			"a recognized room-core signature preserves the Rare classification");
	}

	private static void mapRoomPixelsOverrideConflictingCatalogTypes() {
		var trapMap = mapWithVisibleCells(62, new int[][]{{1, 1}}, new int[0][]);
		var wrongPuzzle = new OdinDungeonRoomCatalog.RoomData("Wrong Puzzle Name", "PUZZLE", "1x1", 0, 6, 0);
		var projected = OdinDungeonMapProjection.project(trapMap,
			java.util.Map.of(7, new OdinDungeonMapCoreScanner.RoomCore(9_101, 90, wrongPuzzle)),
			geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200), java.util.List.of(), 4, 1, false);
		var trap = projected.rooms().stream().filter(room -> room.footprint().stream()
			.anyMatch(cell -> cell.x() == 1 && cell.z() == 1)).findFirst().orElseThrow();
		require(trap.type() == geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.TRAP
			&& "Unknown".equals(trap.name()) && trap.maxSecrets() == 0,
			"Trap map color stays authoritative and mismatched Puzzle metadata supplies no name or secret count");
		require(projected.reason().contains("typeConflicts=1")
			&& projected.reason().contains("7:TRAP!=PUZZLE"),
			"bounded projection evidence records the conflicting map and catalog types");

		var puzzleMap = mapWithVisibleCells(66, new int[][]{{1, 1}}, new int[0][]);
		var matchingPuzzle = OdinDungeonMapProjection.project(puzzleMap,
			java.util.Map.of(7, new OdinDungeonMapCoreScanner.RoomCore(9_102,
				90, new OdinDungeonRoomCatalog.RoomData("Puzzle Room", "PUZZLE", "1x1", 0, 6, 0))),
			geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200), java.util.List.of(), 4, 1, false);
		require(matchingPuzzle.rooms().stream().anyMatch(room -> room.type()
			== geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.PUZZLE
			&& "Puzzle Room".equals(room.name())),
			"matching distinct map colors retain compatible catalog names and room types");
	}

	private static void unknownRoomPixelsDoNotCreateOccupancy() {
		var map = mapWithVisibleCells(85, new int[][]{{1, 1}}, new int[0][]);
		require(map.rooms().stream().noneMatch(room -> room.segments().contains(7))
			&& map.tiles().get(7).type() == OdinDungeonMapGrid.MapRoomType.UNKNOWN
			&& !map.tiles().get(7).mapSeen(),
			"an unknown-color pixel remains diagnostic data but cannot establish room occupancy");
		var projected = OdinDungeonMapProjection.project(map, java.util.Map.of(),
			geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200), java.util.List.of(), 4, 1, false);
		require(projected.cells().stream().noneMatch(cell -> cell.x() == 1 && cell.z() == 1)
			&& projected.reason().contains("unknownMapPixelsRejected=1"),
			"unknown-color pixels do not become overlay rooms and appear in bounded projection diagnostics");
		var catalogRoom = new OdinDungeonRoomCatalog.RoomData("Core Identified Room", "TRAP", "1x1", 0, 6, 0);
		var resolved = OdinDungeonMapProjection.project(map,
			java.util.Map.of(7, new OdinDungeonMapCoreScanner.RoomCore(9_103, 90, catalogRoom)),
			geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200), java.util.List.of(), 4, 1, false);
		var resolvedRoom = resolved.rooms().stream().filter(room -> room.footprint().stream()
			.anyMatch(cell -> cell.x() == 1 && cell.z() == 1))
			.findFirst().orElseThrow();
		require(resolvedRoom.type() == geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.TRAP
			&& resolvedRoom.name().equals("Core Identified Room") && resolvedRoom.maxSecrets() == 6
			&& resolved.reason().contains("exactCorePredictions=1")
			&& resolved.reason().contains("unknownMapPixelsRejected=1"),
			"an exact loaded core establishes a real room independently of the rejected unknown map pixel: "
				+ resolvedRoom + "; " + resolved.reason());
	}

	private static void borderDoorPixelsDoNotCreateRoomCells() {
		record BorderCase(String label, int x, int z, int first, int second, int missingCell) { }
		var cases = java.util.List.of(
			new BorderCase("left", 1, 1, 6, 7, 6),
			new BorderCase("right", 4, 1, 10, 11, 11),
			new BorderCase("top", 1, 1, 1, 7, 1),
			new BorderCase("bottom", 1, 4, 25, 31, 31));
		var playerCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200);
		for (BorderCase border : cases) {
			var map = mapWithVisibleCells(16, 5, new int[][]{{border.x(), border.z()}},
				new int[][]{{border.first(), border.second()}});
			var projected = OdinDungeonMapProjection.project(map, java.util.Map.of(), playerCell,
				java.util.List.of(), 4, 1, false);
			boolean phantomCell = projected.cells().stream().anyMatch(cell ->
				cell.x() + cell.z() * 6 == border.missingCell());
			require(!phantomCell, border.label() + "-border door cue does not invent a neighboring room cell");
			require(projected.reason().contains("borderCandidatesRejected=1"),
				border.label() + "-border rejection is captured in bounded map diagnostics");
		}
	}

	private static void hiddenRepeatedRoomIdentitiesStaySeparate() {
		var map = mapWithVisibleCells(16, 5, new int[][]{{0, 1}, {2, 1}},
			new int[][]{{6, 7}, {8, 9}});
		var playerCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200);
		OdinDungeonRoomCatalog.RoomData data = OdinDungeonRoomCatalog.bundled().byCore().values().stream()
			.filter(room -> room.shape().equals("1x1")).findFirst().orElseThrow();
		var repeatedData = new OdinDungeonMapCoreScanner.RoomCore(101, 90, data);
		var disconnected = OdinDungeonMapProjection.project(map,
			java.util.Map.of(7, repeatedData, 9, repeatedData), playerCell, java.util.List.of(), 4, 1, false);
		var hidden = disconnected.rooms().stream().filter(room -> room.name().equals(data.name())).toList();
		require(hidden.size() == 2 && hidden.stream().allMatch(room -> room.footprint().size() == 1),
			"same-name hidden room cores in disconnected tiles project as distinct room instances");
		require(hidden.get(0).id().equals("unopened:" + data.name() + ":7")
			&& hidden.get(1).id().equals("unopened:" + data.name() + ":9"),
			"disconnected hidden room instance ids remain stable and tile-specific");

		OdinDungeonRoomCatalog.RoomData multiTileData = OdinDungeonRoomCatalog.bundled().byCore().values().stream()
			.filter(room -> room.shape().equals("1x2")).findFirst().orElseThrow();
		var multiTileMap = mapWithVisibleCells(16, 5, new int[][]{{0, 1}}, new int[][]{{6, 7}});
		var connected = OdinDungeonMapProjection.project(multiTileMap,
			java.util.Map.of(7, new OdinDungeonMapCoreScanner.RoomCore(201, 90, multiTileData),
				8, new OdinDungeonMapCoreScanner.RoomCore(202, 90, multiTileData)),
			playerCell, java.util.List.of(), 4, 1, false);
		var multiTileRoom = connected.rooms().stream().filter(room -> room.name().equals(multiTileData.name())).toList();
		require(multiTileRoom.size() == 1 && multiTileRoom.getFirst().footprint().size() == 2,
			"adjacent matching signatures still combine into one multi-tile room instance");

		OdinDungeonRoomLifecycle lifecycle = new OdinDungeonRoomLifecycle();
		var entered = lifecycle.observe(new Object(), true, false, "cells:7", true, 7, java.util.List.of(7));
		var revisitedMap = OdinDungeonMapProjection.project(map,
			java.util.Map.of(7, repeatedData), playerCell, java.util.List.of(), 4, 1, false,
			java.util.List.of(), entered.walkedIntoTiles());
		var revisitedCell = revisitedMap.cells().stream().filter(cell -> cell.x() == 1 && cell.z() == 1)
			.findFirst().orElseThrow();
		require(revisitedCell.visibility() == geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED,
			"the map projection keeps a previously entered unopened room visible after leaving it");
	}

	private static void hiddenRoomGroupingRespectsShapeAndDoorEvidence() {
		var playerCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-200, -200);
		var oneByOne = OdinDungeonRoomCatalog.bundled().byCore().values().stream()
			.filter(room -> room.shape().equals("1x1")).findFirst().orElseThrow();
		var adjacentOneByOneCores = java.util.Map.of(
			7, new OdinDungeonMapCoreScanner.RoomCore(301, 90, oneByOne),
			9, new OdinDungeonMapCoreScanner.RoomCore(302, 90, oneByOne));
		var topologyMap = mapWithVisibleCells(16, 5, new int[][]{{0, 1}, {2, 1}},
			new int[][]{{6, 7}, {8, 9}});
		var adjacentOneByOne = OdinDungeonMapProjection.project(topologyMap,
			adjacentOneByOneCores, playerCell, java.util.List.of(), 4, 1, false);
		var oneByOneRooms = adjacentOneByOne.rooms().stream().filter(room -> room.name().equals(oneByOne.name())).toList();
		require(oneByOneRooms.size() == 2 && oneByOneRooms.stream().allMatch(room -> room.footprint().size() == 1),
			"complete 1x1 signatures remain separate when each has an independent map-door connection");
		var oneByOneVisited = OdinDungeonMapProjection.project(topologyMap,
			adjacentOneByOneCores, playerCell, java.util.List.of(), 4, 1, false, java.util.List.of(),
			java.util.Set.of(7));
		var visitedFirstOneByOne = oneByOneVisited.cells().stream()
			.filter(cell -> cell.x() == 1 && cell.z() == 1).findFirst().orElseThrow();
		var unvisitedSecondOneByOne = oneByOneVisited.cells().stream()
			.filter(cell -> cell.x() == 3 && cell.z() == 1).findFirst().orElseThrow();
		require(visitedFirstOneByOne.visibility()
				== geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
			&& unvisitedSecondOneByOne.visibility()
				== geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED,
			"visiting one adjacent same-name 1x1 room does not mark the other room observed");

		var twoTile = OdinDungeonRoomCatalog.bundled().byCore().values().stream()
			.filter(room -> room.shape().equals("1x2")).findFirst().orElseThrow();
		var adjacentTwoTileCores = java.util.Map.of(
			7, new OdinDungeonMapCoreScanner.RoomCore(401, 90, twoTile),
			8, new OdinDungeonMapCoreScanner.RoomCore(402, 90, twoTile));
		var doorMap = mapWithVisibleCells(16, 5, new int[][]{{0, 1}}, new int[][]{{6, 7}, {7, 8}});
		require(doorMap.doors().stream().anyMatch(door -> door.firstCell() == 7 && door.secondCell() == 8),
			"synthetic hidden-room fixture exposes the map door between adjacent tiles");
		var acrossDoor = OdinDungeonMapProjection.project(doorMap, adjacentTwoTileCores, playerCell,
			java.util.List.of(), 4, 1, false);
		var acrossDoorRooms = acrossDoor.rooms().stream()
			.filter(room -> room.name().equals(twoTile.name()) && room.footprintKnown()).toList();
		var partialRooms = acrossDoor.rooms().stream().filter(room -> room.id().startsWith("partial-core:")).toList();
		require(acrossDoorRooms.isEmpty() && partialRooms.size() == 2
			&& partialRooms.stream().allMatch(room -> !room.footprintKnown() && room.type()
				== geiler.addons.client.dungeon.DungeonMapGeometry.RoomType.ROOM
				&& room.name().equals(twoTile.name()) && room.maxSecrets() == twoTile.maxSecrets()),
			"a door splitting a multi-tile catalog shape reveals its exact core tiles with verified data; "
				+ "expected=" + twoTile.name() + ", rooms=" + acrossDoor.rooms().stream()
					.map(room -> room.id() + ":" + room.name() + ":" + room.footprint().size() + ":"
						+ room.footprintKnown() + ":" + room.type() + ":" + room.maxSecrets()).toList());
		var acrossDoorVisited = OdinDungeonMapProjection.project(doorMap, adjacentTwoTileCores, playerCell,
			java.util.List.of(), 4, 1, false, java.util.List.of(), java.util.Set.of(7));
		var visitedPartialTile = acrossDoorVisited.cells().stream()
			.filter(cell -> cell.x() + cell.z() * 6 == 7).findFirst().orElseThrow();
		var unopenedPartialTile = acrossDoorVisited.cells().stream()
			.filter(cell -> cell.x() + cell.z() * 6 == 8).findFirst().orElseThrow();
		require(visitedPartialTile.visibility()
				== geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED
			&& unopenedPartialTile.visibility()
				== geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
			&& acrossDoorVisited.rooms().stream().noneMatch(room -> room.name().equals(twoTile.name())
				&& room.footprintKnown()),
			"visiting one split core tile does not complete the footprint or reveal a guessed catalog identity");

		var oneByOneChain = OdinDungeonMapProjection.project(doorMap,
			java.util.Map.of(7, new OdinDungeonMapCoreScanner.RoomCore(501, 90, oneByOne),
				8, new OdinDungeonMapCoreScanner.RoomCore(502, 90, oneByOne)),
			playerCell, java.util.List.of(), 4, 1, false);
		var chainRooms = oneByOneChain.rooms().stream().filter(room -> room.name().equals(oneByOne.name())).toList();
		require(chainRooms.size() == 2 && oneByOneChain.reason().contains("verifiedPredictions=2"),
			"complete unopened rooms are revealed transitively through confirmed door-connected groups");

		var unsupported = OdinDungeonMapProjection.project(mapWithVisibleCells(new int[0][]),
			adjacentOneByOneCores, playerCell, java.util.List.of(), 4, 1, false);
		require(unsupported.rooms().stream().filter(room -> room.name().equals(oneByOne.name())).count() == 2
			&& unsupported.reason().contains("exactCorePredictions=2"),
			"exact loaded 1x1 room cores are predicted without requiring nearby map pixels or door topology");
		var revealOnPlan = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(unsupported, true);
		var revealOffPlan = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(unsupported, false);
		require(java.util.Set.of(7, 9).stream().allMatch(tile -> revealOnPlan.visibleCells().stream()
			.anyMatch(cell -> cell.x() + cell.z() * 6 == tile))
			&& java.util.Set.of(7, 9).stream().noneMatch(tile -> revealOffPlan.visibleCells().stream()
				.anyMatch(cell -> cell.x() + cell.z() * 6 == tile)),
			"reveal-on shows exact unopened core predictions while reveal-off keeps them concealed");
	}

	private static void centerChunkCoordinatesMatchTheSixBySixFrame() {
		for (int z = 0; z < 6; z++) for (int x = 0; x < 6; x++) {
			int chunkX = -12 + x * 2;
			int chunkZ = -12 + z * 2;
			require(OdinDungeonMapCoreScanner.tileForCenterChunk(chunkX, chunkZ) == x + z * 6,
				"Odin center chunk maps to 6x6 tile " + x + "," + z);
		}
		require(OdinDungeonMapCoreScanner.tileForCenterChunk(-11, -12) == -1
			&& OdinDungeonMapCoreScanner.tileForCenterChunk(-12, -11) == -1
			&& OdinDungeonMapCoreScanner.tileForCenterChunk(-13, -12) == -1
			&& OdinDungeonMapCoreScanner.tileForCenterChunk(0, -12) == -1,
			"odd connector, outside, and unsupported border chunks are not treated as room cores");
	}

	private static void walkedIntoRoomIsViewableAndPlayerOffsetsAreBounded() {
		var playerCell = geiler.addons.client.dungeon.DungeonGridRules.cellAtOrigin(-168, -168);
		byte[] colors = mapColorsWithVisibleCells(18, 5, new int[][]{{1, 1}, {3, 1}, {5, 1}},
			new int[0][], 62);
		// Current tile has no checkmark; the middle tile has Odin's white checkmark;
		// the last tile has an explicit undiscovered center pixel. Keep this row clear of
		// the entrance stripe used to calibrate the synthetic map layout.
		pixel(colors, 5 + 22 + 9, 22 + 22 + 9, 0);
		pixel(colors, 5 + 3 * 22 + 9, 22 + 22 + 9, 34);
		pixel(colors, 5 + 5 * 22 + 9, 22 + 22 + 9, 0);
		OdinDungeonMapGrid.ScanResult map = OdinDungeonMapGrid.scan(colors);
		require(map.available(), "checkmark fixture creates a valid Odin map");
		var snapshot = OdinDungeonMapProjection.project(map, java.util.Map.of(), playerCell,
			java.util.List.of(), 4, 1, true);
		var entrance = snapshot.cells().stream().filter(cell -> cell.x() == 1 && cell.z() == 1).findFirst().orElseThrow();
		require(entrance.visibility() == geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED,
			"the room the player walked into is viewable before a clear checkmark appears");
		var revealOff = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(snapshot, false);
		require(revealOff.visibleCells().size() == 2 && revealOff.visibleCells().stream()
			.allMatch(cell -> cell.visibility()
				== geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED),
			"reveal off renders the current room and every map room Odin marks viewable");
		var revealOn = geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.renderPlan(snapshot, true);
		require(revealOn.visibleCells().size() == 4 && revealOn.visibleCells().stream().anyMatch(cell ->
			cell.x() == 5 && cell.z() == 1 && cell.visibility()
				== geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED),
			"reveal on adds the map-observed but still undiscovered room: " + revealOn.visibleCells());
		var marker = new geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.PlayerMarker(
			2, 3, "Player", false, -0.5, 1.5, "Mage");
		require(marker.offsetX() == 0.0 && marker.offsetZ() == 1.0 && marker.dungeonClass().equals("Mage"),
			"in-cell player offsets stay bounded and class metadata is retained");
	}

	private static void roomLifecycleRetainsVisitsAndClearsRunState() {
		OdinDungeonRoomLifecycle lifecycle = new OdinDungeonRoomLifecycle();
		Object level = new Object();
		String firstRoom = OdinDungeonRoomLifecycle.roomId(java.util.List.of(7, 8));
		var entered = lifecycle.observe(level, true, false, firstRoom, true, 7, java.util.List.of(7, 8));
		require(entered.roomEntered() && entered.currentRoomId().equals(firstRoom)
			&& entered.walkedIntoTiles().equals(java.util.Set.of(7, 8)),
			"a ready room emits one entry and records all observed room tiles");
		var invalidPlayer = lifecycle.observe(level, true, false, "cells:36", true, 36, java.util.List.of(36));
		require(invalidPlayer.currentRoomId().equals(firstRoom)
			&& invalidPlayer.walkedIntoTiles().equals(java.util.Set.of(7, 8)),
			"an invalid player tile cannot replace the room or add an out-of-range visit");
		var blankRoom = lifecycle.observe(level, true, false, " \t ", true, 9, java.util.List.of(9));
		require(blankRoom.currentRoomId().equals(firstRoom) && !blankRoom.walkedIntoTiles().contains(9),
			"a blank candidate cannot replace the room or add a visit");
		var repeated = lifecycle.observe(level, true, false, firstRoom, true, 8,
			java.util.Arrays.asList(null, -1, 36));
		require(!repeated.roomEntered() && repeated.walkedIntoTiles().equals(java.util.Set.of(7, 8)),
			"staying in one room filters invalid room tiles without duplicating its entry");
		var unresolved = lifecycle.observe(level, true, false, null, false, 9, java.util.List.of());
		require(unresolved.currentRoomId().equals(firstRoom) && unresolved.walkedIntoTiles().contains(7),
			"an unresolved adjacent observation retains Odin-style current-room and visit state");
		var boss = lifecycle.observe(level, true, true, null, false, 9, java.util.List.of());
		require(boss.currentRoomId() == null && boss.walkedIntoTiles().contains(7),
			"boss entry clears current room while retaining already walked rooms for this floor");
		var secondRoom = lifecycle.observe(level, true, false, "cells:9", true, 9, java.util.List.of(9));
		require(secondRoom.roomEntered() && secondRoom.currentRoomId().equals("cells:9"),
			"the first ready room after the boss phase emits a new entry");
		var exited = lifecycle.observe(level, false, false, null, false, -1, java.util.List.of());
		require(!exited.inDungeon() && exited.currentRoomId() == null && exited.walkedIntoTiles().isEmpty(),
			"dungeon exit clears current-room and walked-into state");
		var nextRun = lifecycle.observe(level, true, false, firstRoom, true, 7, java.util.List.of(7, 8));
		require(nextRun.roomEntered() && nextRun.walkedIntoTiles().equals(java.util.Set.of(7, 8)),
			"a new dungeon run starts with an empty visited-room set");
		var noLevel = lifecycle.observe(null, true, false, "cells:11", true, 11, java.util.List.of(11));
		require(!noLevel.inDungeon() && noLevel.currentRoomId() == null && noLevel.walkedIntoTiles().isEmpty(),
			"a missing world resets dungeon and walked-into state");
		Object replacementLevel = new Object();
		var changedLevel = lifecycle.observe(replacementLevel, true, false, "cells:10", true, 10,
			java.util.List.of(10));
		require(changedLevel.roomEntered() && changedLevel.walkedIntoTiles().equals(java.util.Set.of(10)),
			"world replacement drops stale room visits");
		lifecycle.reset();
		var reset = lifecycle.snapshot();
		require(!reset.inDungeon() && !reset.inBoss() && reset.currentRoomId() == null
			&& reset.enteredRoomId() == null && reset.walkedIntoTiles().isEmpty(),
			"the public reset and snapshot methods clear and report all lifecycle state");
	}

	private static void roomLifecycleRecordPreservesJavaContract() {
		var snapshotType = OdinDungeonRoomLifecycle.Snapshot.class;
		require(snapshotType.isRecord(), "lifecycle Snapshot remains a Java record");
		var components = snapshotType.getRecordComponents();
		require(java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName).toList()
				.equals(java.util.List.of("inDungeon", "inBoss", "currentRoomId", "enteredRoomId", "walkedIntoTiles"))
			&& java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toList()
				.equals(java.util.List.of(boolean.class, boolean.class, String.class, String.class, java.util.Set.class)),
			"lifecycle Snapshot preserves the Java record component names, order, and types");

		var mutableTiles = new java.util.LinkedHashSet<>(java.util.List.of(7, 8));
		var copied = new OdinDungeonRoomLifecycle.Snapshot(true, false, "cells:7", "cells:7", mutableTiles);
		mutableTiles.add(9);
		require(copied.walkedIntoTiles().equals(java.util.Set.of(7, 8)),
			"Snapshot constructor defensively copies caller-owned tile sets");
		boolean immutable = false;
		try {
			copied.walkedIntoTiles().add(9);
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		require(immutable, "Snapshot exposes an immutable walked-into tile set");
		var equivalent = new OdinDungeonRoomLifecycle.Snapshot(true, false, "cells:7", "cells:7",
			new java.util.LinkedHashSet<>(java.util.List.of(8, 7)));
		require(copied.equals(equivalent) && copied.hashCode() == equivalent.hashCode(),
			"Snapshot retains structural record equality and hashing");
		String rendered = copied.toString();
		require(rendered.startsWith("Snapshot[inDungeon=true, inBoss=false, currentRoomId=cells:7, enteredRoomId=cells:7, walkedIntoTiles=")
			&& rendered.endsWith("]"), "Snapshot retains the standard Java record string form");
		var nullTiles = new OdinDungeonRoomLifecycle.Snapshot(false, false, null, null, null);
		require(nullTiles.walkedIntoTiles().isEmpty(), "Snapshot normalizes a null tile set to an empty immutable set");
		require(OdinDungeonRoomLifecycle.roomId(null).isEmpty()
			&& OdinDungeonRoomLifecycle.roomId(java.util.List.of()).isEmpty()
			&& OdinDungeonRoomLifecycle.roomId(java.util.Arrays.asList(8, 7, 8, null, -1, 36)).equals("cells:7,8"),
			"roomId keeps null and empty handling, sorting, deduplication, and 6x6 tile bounds");
	}

	private static OdinDungeonMapGrid.ScanResult mapWithVisibleCells(int[][] coordinates) {
		return mapWithVisibleCells(18, 5, coordinates, new int[0][]);
	}

	private static OdinDungeonMapGrid.ScanResult mapWithVisibleCells(int[][] coordinates, int[][] doorEdges) {
		return mapWithVisibleCells(18, 5, coordinates, doorEdges);
	}

	private static OdinDungeonMapGrid.ScanResult mapWithVisibleCells(int roomColor,
		int[][] coordinates, int[][] doorEdges) {
		OdinDungeonMapGrid.ScanResult scan = OdinDungeonMapGrid.scan(
			mapColorsWithVisibleCells(18, 5, coordinates, doorEdges, roomColor));
		require(scan.available(), "typed map fixture creates a valid Odin map");
		return scan;
	}

	private static OdinDungeonMapGrid.ScanResult mapWithVisibleCells(int roomSize, int startX,
		int[][] coordinates, int[][] doorEdges) {
		OdinDungeonMapGrid.ScanResult scan = OdinDungeonMapGrid.scan(
			mapColorsWithVisibleCells(roomSize, startX, coordinates, doorEdges));
		require(scan.available(), "prediction fixture creates a valid Odin map");
		return scan;
	}

	private static OdinDungeonMapGrid.ScanResult mapWithDoorPixelColor(int doorColor) {
		byte[] colors = mapColorsWithVisibleCells(18, 5, new int[][]{{1, 1}}, new int[][]{{7, 8}});
		pixel(colors, 47, 53, doorColor);
		OdinDungeonMapGrid.ScanResult scan = OdinDungeonMapGrid.scan(colors);
		require(scan.available() && scan.doors().stream().anyMatch(door ->
			door.firstCell() == 7 && door.secondCell() == 8), "door-color fixture contains map-pixel evidence");
		return scan;
	}

	private static byte[] mapColorsWithVisibleCells(int roomSize, int startX,
		int[][] coordinates, int[][] doorEdges) {
		return mapColorsWithVisibleCells(roomSize, startX, coordinates, doorEdges, 63);
	}

	private static byte[] mapColorsWithVisibleCells(int roomSize, int startX,
		int[][] coordinates, int[][] doorEdges, int roomColor) {
		byte[] colors = new byte[128 * 128];
		int gap = roomSize + 4;
		int startZ = roomSize == 16 ? 5 : gap;
		int halfRoom = roomSize / 2;
		for (int x = startX; x < startX + roomSize; x++) pixel(colors, x, startZ, 30);
		for (int[] coordinate : coordinates)
			paintMapTile(colors, roomSize, startX, coordinate[0], coordinate[1], roomColor);
		for (int[] edge : doorEdges) {
			int firstX = edge[0] % 6;
			int firstZ = edge[0] / 6;
			int secondX = edge[1] % 6;
			int secondZ = edge[1] / 6;
			int originX = startX + Math.min(firstX, secondX) * gap;
			int originZ = startZ + Math.min(firstZ, secondZ) * gap;
			if (firstZ == secondZ && Math.abs(firstX - secondX) == 1)
				pixel(colors, originX + roomSize + 2, originZ + halfRoom, 119);
			else if (firstX == secondX && Math.abs(firstZ - secondZ) == 1)
				pixel(colors, originX + halfRoom, originZ + roomSize + 2, 119);
			else throw new IllegalArgumentException("door fixture cells must share one grid edge");
		}
		return colors;
	}

	private static void paintMapTile(byte[] colors, int roomSize, int startX, int x, int z, int color) {
		int gap = roomSize + 4;
		int originX = startX + x * gap;
		int startZ = roomSize == 16 ? 5 : gap;
		int originZ = startZ + z * gap;
		pixel(colors, originX, originZ, color);
		pixel(colors, originX + roomSize / 2, originZ + roomSize / 2, color);
	}

	private static OdinDungeonMapGrid.ScanResult mapWithDoorBetween(int firstCell, int secondCell) {
		int firstX = firstCell % 6;
		int firstZ = firstCell / 6;
		int secondX = secondCell % 6;
		int secondZ = secondCell / 6;
		byte[] colors = new byte[128 * 128];
		for (int x = 5; x < 23; x++) pixel(colors, x, 22, 30);
		if (firstZ == secondZ && Math.abs(firstX - secondX) == 1) {
			int leftX = Math.min(firstX, secondX);
			pixel(colors, 5 + leftX * 22 + 20, 22 + firstZ * 22 + 9, 119);
		} else if (firstX == secondX && Math.abs(firstZ - secondZ) == 1) {
			int topZ = Math.min(firstZ, secondZ);
			pixel(colors, 5 + firstX * 22 + 9, 22 + topZ * 22 + 20, 119);
		} else {
			throw new IllegalArgumentException("door fixture cells must share one grid edge");
		}
		OdinDungeonMapGrid.ScanResult scan = OdinDungeonMapGrid.scan(colors);
		require(scan.available(), "door fixture creates a valid Odin map");
		return scan;
	}

	private static void pixel(byte[] colors, int x, int z, int value) { colors[z * 128 + x] = (byte) value; }
	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void assertKotlinValueContract(Class<?> type, String[] componentNames, Class<?>... componentTypes) {
		require(type.getAnnotation(kotlin.Metadata.class) != null,
			type.getSimpleName() + " retains Kotlin class metadata");
		require(componentNames.length == componentTypes.length,
			type.getSimpleName() + " component names and Java accessor types match");
		try {
			type.getConstructor(componentTypes);
			for (int index = 0; index < componentNames.length; index++) {
				require(type.getMethod(componentNames[index]).getReturnType() == componentTypes[index],
					type.getSimpleName() + " keeps Java accessor " + componentNames[index] + "() with its original type");
			}
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(type.getSimpleName() + " lost a Java constructor or component accessor", error);
		}
	}
}
