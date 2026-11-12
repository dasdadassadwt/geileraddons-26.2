package geiler.addons.client.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure round-trip, bounds, phase and trigger checks for the player-authored dungeon features. */
public final class DungeonFeatureChecks {
	private DungeonFeatureChecks() { }

	public static void run() {
		checkDungeonContextSignals();
		checkDungeonFloorSources();
		checkGridRules();
		checkMapGeometry();
		checkRoomGeometryValueType();
		checkMapIdFallback();
		checkMapDetectorValueTypes();
		checkDoorBoundsAndKeys();
		checkDungeonKeyRulesAbi();
		checkDoorTrackerAbi();
		SecretWaypointChecks.run();
		DungeonKeyAlertChecks.run();
		checkBloodTracerTarget();
		checkDoorTracerRoomGateAndGrace();
		checkGuidePackages();
		checkGuideMigration();
		checkGuideFlush();
		checkGuideProgress();
	}

	private static void checkDungeonKeyRulesAbi() {
		try {
			java.lang.reflect.RecordComponent[] components = DungeonKeyRules.KeyOwnership.class.getRecordComponents();
			if (components == null || components.length != 2 || !components[0].getName().equals("wither") ||
				components[0].getType() != boolean.class || !components[1].getName().equals("blood") ||
				components[1].getType() != boolean.class) {
				throw new AssertionError("KeyOwnership's Java record shape changed");
			}
			java.lang.reflect.Field none = DungeonKeyRules.KeyOwnership.class.getField("NONE");
			if (!java.lang.reflect.Modifier.isStatic(none.getModifiers()) ||
				!none.get(null).equals(new DungeonKeyRules.KeyOwnership(false, false))) {
				throw new AssertionError("KeyOwnership.NONE is missing or changed");
			}
			DungeonKeyRules.class.getMethod("combineOwnership", boolean.class, boolean.class, boolean.class, boolean.class);
			DungeonKeyRules.class.getMethod("isMatchingKey", String.class, DungeonDoorTracker.Type.class);
			DungeonKeyRules.class.getMethod("isPickupForPlayer", String.class, String.class, DungeonDoorTracker.Type.class);
			DungeonKeyRules.class.getMethod("isDoorOpenedByPlayer", String.class, String.class, DungeonDoorTracker.Type.class);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("A shared Java-facing dungeon key API changed", error);
		}

		assertFalse(DungeonKeyRules.isMatchingKey(null, null), "null key inputs remain non-matches");
		assertFalse(DungeonKeyRules.isPickupForPlayer(null, "Alice", DungeonDoorTracker.Type.WITHER),
			"missing pickup chat remains a non-match");
		assertFalse(DungeonKeyRules.isDoorOpenedByPlayer("Alice opened a blood door!", "Alice", null),
			"an unknown door type remains a non-match");
		assertTrue(DungeonKeyRules.isPickupForPlayer("\u0000Alice has obtained Wither Key!\u0000", "Alice",
			DungeonDoorTracker.Type.WITHER), "Java trim's control-character boundary is preserved");
		assertFalse(DungeonKeyRules.isPickupForPlayer("\u00a0 has obtained Wither Key!", "\u00a0",
			DungeonDoorTracker.Type.WITHER),
			"chat normalization trims an NBSP-only actor before it can match, as in the frozen Java parser");
	}

	private static void checkDoorTrackerAbi() {
		try {
			java.lang.reflect.Method[] publicMethods = {
				DungeonDoorTracker.class.getMethod("tick", net.minecraft.client.Minecraft.class),
				DungeonDoorTracker.class.getMethod("doors"),
				DungeonDoorTracker.class.getMethod("requestPromptRescan",
					net.minecraft.client.multiplayer.ClientLevel.class, int.class, int.class),
				DungeonDoorTracker.class.getMethod("promptScanDecision",
					boolean.class, boolean.class, boolean.class, long.class, long.class),
				DungeonDoorTracker.class.getMethod("startDiagnosticsSummary"),
				DungeonDoorTracker.class.getMethod("dungeonSessionGeneration"),
				DungeonDoorTracker.class.getMethod("startDoorCaptureFrame",
					net.minecraft.client.multiplayer.ClientLevel.class),
				DungeonDoorTracker.class.getMethod("firstLoadedPlayer"),
				DungeonDoorTracker.class.getMethod("onBlockChange", BlockPos.class),
				DungeonDoorTracker.class.getMethod("orderedWitherDoors", BlockPos.class, int.class),
				DungeonDoorTracker.class.getMethod("nextWitherDoor", BlockPos.class),
				DungeonDoorTracker.class.getMethod("nextBloodDoor", BlockPos.class),
				DungeonDoorTracker.class.getMethod("touchesCell", DungeonDoorTracker.Door.class,
					DungeonGridRules.Cell.class),
				DungeonDoorTracker.class.getMethod("promptEdgeIndex", int.class, int.class),
			};
			Class<?>[] publicReturnTypes = {
				void.class, List.class, DungeonDoorTracker.PromptScanResult.class,
				DungeonDoorTracker.PromptScanResult.class, String.class, long.class,
				DungeonDoorTracker.StartDoorCaptureFrame.class, DungeonDoorTracker.SpawnReference.class,
				void.class, List.class, DungeonDoorTracker.Door.class, DungeonDoorTracker.Door.class,
				boolean.class, int.class,
			};
			for (int i = 0; i < publicMethods.length; i++) {
				java.lang.reflect.Method method = publicMethods[i];
				assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()),
					"the tracker keeps each public Java entrypoint static: " + method.getName());
				assertTrue(method.getReturnType() == publicReturnTypes[i],
					"the tracker keeps each public Java return type stable: " + method.getName());
			}

			java.lang.reflect.Method[] migratedHelpers = {
				DungeonDoorTracker.class.getDeclaredMethod("isSpawnStartForwardDistanceInRange", double.class),
				DungeonDoorTracker.class.getDeclaredMethod("centeredDoor", DungeonDoorTracker.Type.class,
					int.class, int.class, BlockPos.class),
				DungeonDoorTracker.class.getDeclaredMethod("centeredDoor", DungeonDoorTracker.Type.class,
					int.class, int.class, BlockPos.class, List.class),
				DungeonDoorTracker.class.getDeclaredMethod("centeredDoor", DungeonDoorTracker.Type.class,
					int.class, int.class, BlockPos.class, List.class, String.class),
			};
			Class<?>[] helperReturnTypes = {
				boolean.class, DungeonDoorTracker.Door.class, DungeonDoorTracker.Door.class,
				DungeonDoorTracker.Door.class,
			};
			for (int i = 0; i < migratedHelpers.length; i++) {
				java.lang.reflect.Method method = migratedHelpers[i];
				int modifiers = method.getModifiers();
				assertTrue(java.lang.reflect.Modifier.isStatic(modifiers) &&
					java.lang.reflect.Modifier.isPublic(modifiers),
					"Kotlin keeps the former package helper's Java descriptor callable: " + method.getName());
				assertTrue(method.getReturnType() == helperReturnTypes[i],
					"the tracker keeps its Java return type stable: " + method.getName());
			}

			assertFalse(DungeonDoorTracker.Door.class.isRecord(),
				"door values keep normalized construction and Java-style accessors as a Kotlin value class");
			checkRecordShape(DungeonDoorTracker.DoorIdentity.class,
				new String[] {"firstCell", "secondCell"}, new Class<?>[] {int.class, int.class});
			checkRecordShape(DungeonDoorTracker.SpawnReference.class,
				new String[] {"x", "y", "z", "yaw", "blockPosition", "tick"},
				new Class<?>[] {double.class, double.class, double.class, float.class, BlockPos.class, long.class});
			checkRecordShape(DungeonDoorTracker.StartDoorCaptureFrame.class,
				new String[] {"ready", "rejectionReason", "runGeneration", "mapId", "entranceCell", "entranceCellX",
					"entranceCellZ", "entranceBaseX", "entranceBaseZ", "diagnosticSummary"},
				new Class<?>[] {boolean.class, String.class, long.class, String.class, int.class, int.class, int.class,
					int.class, int.class, String.class});
			DungeonDoorTracker.Door.class.getConstructor(DungeonDoorTracker.Type.class,
				int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class,
				List.class, String.class);
			DungeonDoorTracker.Door.class.getConstructor(DungeonDoorTracker.Type.class,
				int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class, List.class);
			DungeonDoorTracker.Door.class.getConstructor(DungeonDoorTracker.Type.class,
				int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
			DungeonDoorTracker.Door.class.getMethod("identity");
			DungeonDoorTracker.Door.class.getMethod("withSourcePositions", List.class);
			DungeonDoorTracker.Door.class.getMethod("withSourcePositions", List.class, String.class);
			DungeonDoorTracker.Door.class.getMethod("centerX");
			DungeonDoorTracker.Door.class.getMethod("centerY");
			DungeonDoorTracker.Door.class.getMethod("centerZ");
			for (String accessor : List.of("type", "firstCell", "secondCell", "x", "y", "z", "width", "height",
				"depth", "sourcePositions", "sourceDiagnostics")) {
				DungeonDoorTracker.Door.class.getMethod(accessor);
			}
			DungeonDoorTracker.DoorIdentity.class.getConstructor(int.class, int.class);
			DungeonDoorTracker.SpawnReference.class.getConstructor(
				double.class, double.class, double.class, float.class, BlockPos.class, long.class);
			DungeonDoorTracker.StartDoorCaptureFrame.class.getConstructor(boolean.class, String.class, long.class,
				String.class, int.class, int.class, int.class, int.class, int.class, String.class);
			assertTrue(java.util.Arrays.equals(DungeonDoorTracker.Type.values(),
				new DungeonDoorTracker.Type[] {DungeonDoorTracker.Type.NORMAL, DungeonDoorTracker.Type.GLASS,
					DungeonDoorTracker.Type.START, DungeonDoorTracker.Type.WITHER, DungeonDoorTracker.Type.BLOOD}),
				"door type names and order remain Java-visible");
			assertTrue(java.util.Arrays.equals(DungeonDoorTracker.PromptScanResult.values(),
				new DungeonDoorTracker.PromptScanResult[] {DungeonDoorTracker.PromptScanResult.QUEUED,
					DungeonDoorTracker.PromptScanResult.ALREADY_QUEUED, DungeonDoorTracker.PromptScanResult.RECENTLY_SCANNED,
					DungeonDoorTracker.PromptScanResult.EDGE_UNLOADED,
					DungeonDoorTracker.PromptScanResult.TRACKER_NOT_READY,
					DungeonDoorTracker.PromptScanResult.INVALID_EDGE}),
				"prompt scan results retain their Java-visible names and order");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("The dungeon door tracker lost a Java-facing ABI member", error);
		}

		assertEquals(0, DungeonDoorTracker.promptEdgeIndex(0, 1),
			"the first horizontal edge keeps its deterministic index");
		assertEquals(1, DungeonDoorTracker.promptEdgeIndex(0, 6),
			"the first vertical edge keeps its deterministic index");
		assertEquals(DungeonDoorTracker.promptEdgeIndex(0, 1), DungeonDoorTracker.promptEdgeIndex(1, 0),
			"prompt edge lookup is symmetric");
		assertEquals(-1, DungeonDoorTracker.promptEdgeIndex(0, 7),
			"diagonal cells are not a valid prompt edge");
		assertEquals(DungeonDoorTracker.PromptScanResult.TRACKER_NOT_READY,
			DungeonDoorTracker.promptScanDecision(false, true, false, Long.MIN_VALUE, 10),
			"prompt scans require a ready tracker");
		assertEquals(DungeonDoorTracker.PromptScanResult.EDGE_UNLOADED,
			DungeonDoorTracker.promptScanDecision(true, false, false, Long.MIN_VALUE, 10),
			"prompt scans require already-loaded samples");
		assertEquals(DungeonDoorTracker.PromptScanResult.ALREADY_QUEUED,
			DungeonDoorTracker.promptScanDecision(true, true, true, Long.MIN_VALUE, 10),
			"a queued edge is not queued twice");
		assertEquals(DungeonDoorTracker.PromptScanResult.RECENTLY_SCANNED,
			DungeonDoorTracker.promptScanDecision(true, true, false, 100, 119),
			"the prompt rescan cooldown remains twenty ticks");
		assertEquals(DungeonDoorTracker.PromptScanResult.QUEUED,
			DungeonDoorTracker.promptScanDecision(true, true, false, 100, 120),
			"an edge can be queued again at the cooldown boundary");
		assertTrue(DungeonDoorTracker.isSpawnStartForwardDistanceInRange(16.0) &&
			DungeonDoorTracker.isSpawnStartForwardDistanceInRange(20.0) &&
			!DungeonDoorTracker.isSpawnStartForwardDistanceInRange(15.0) &&
			!DungeonDoorTracker.isSpawnStartForwardDistanceInRange(21.0),
			"spawn Start selection retains its inclusive 16-to-20 block forward interval");

		BlockPos source = new BlockPos(40, 70, -9);
		List<BlockPos> mutableSources = new ArrayList<>(List.of(source));
		DungeonDoorTracker.Door door = DungeonDoorTracker.centeredDoor(DungeonDoorTracker.Type.WITHER,
			1, 2, source, mutableSources, "source-evidence");
		mutableSources.clear();
		assertEquals(List.of(source), door.sourcePositions(), "door source positions remain defensively copied");
		assertEquals("source-evidence", door.sourceDiagnostics(), "door source diagnostics remain attached");
		assertEquals(new DungeonDoorTracker.DoorIdentity(1, 2), door.identity(),
			"door identity retains its two-cell Java record value");
		DungeonDoorTracker.Door emptySources = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.NORMAL,
			1, 2, 39, 70, -10, 3, 4, 3, null, null);
		assertEquals(List.of(), emptySources.sourcePositions(), "a null source list keeps the empty-list fallback");
		assertEquals("", emptySources.sourceDiagnostics(), "null source diagnostics keep the empty-string fallback");
		DungeonDoorTracker.Door nullableHelper = DungeonDoorTracker.centeredDoor(null,
			1, 2, source, null, null);
		assertEquals(null, nullableHelper.type(), "the package helper retains nullable door types");
		assertEquals(List.of(), nullableHelper.sourcePositions(), "the package helper retains null source-list fallback");
		assertEquals("", nullableHelper.sourceDiagnostics(), "the package helper retains null diagnostics fallback");
		try {
			new DungeonDoorTracker.Door(DungeonDoorTracker.Type.NORMAL, 1, 2, 39, 70, -10, 3, 4, 3,
				java.util.Arrays.asList(new BlockPos(0, 0, 0), null), "");
			throw new AssertionError("door source snapshots must reject null elements");
		} catch (NullPointerException expected) { }
	}

	private static void checkRecordShape(Class<?> record, String[] names, Class<?>[] types) {
		java.lang.reflect.RecordComponent[] components = record.getRecordComponents();
		assertTrue(components != null && components.length == names.length && names.length == types.length,
			"Java record component count remains stable for " + record.getSimpleName());
		for (int i = 0; i < names.length; i++) {
			assertTrue(names[i].equals(components[i].getName()) && types[i] == components[i].getType(),
				"Java record component " + i + " remains stable for " + record.getSimpleName());
		}
	}

	private static void checkDoorBoundsAndKeys() {
		DungeonDoorTracker.Door door = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 1, 2, new BlockPos(40, 70, -9));
		var expectedDoor = new ExpectedDoor(DungeonDoorTracker.Type.WITHER, 1, 2, 39, 70, -10,
			3, 4, 3, List.of(), "");
		var equivalentDoor = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.WITHER, 1, 2, 39, 70, -10,
			3, 4, 3);
		assertTrue(door.equals(equivalentDoor) && equivalentDoor.equals(door),
			"Kotlin door values preserve symmetric record-style equality");
		assertEquals(expectedDoor.hashCode(), door.hashCode(), "door helper retains Java record hash behavior");
		assertEquals(expectedDoor.hashCode(), equivalentDoor.hashCode(), "door overload retains Java record hash behavior");
		String expectedDoorText = "Door[type=WITHER, firstCell=1, secondCell=2, x=39, y=70, z=-10, width=3, " +
			"height=4, depth=3, sourcePositions=[], sourceDiagnostics=]";
		assertEquals(expectedDoorText, door.toString(), "door helper retains Java record text behavior");
		assertEquals(expectedDoorText, equivalentDoor.toString(), "door overload retains Java record text behavior");
		assertEquals(39, door.x(), "the door highlight extends one block before the doorway plane");
		assertEquals(-10, door.z(), "the door highlight extends one block behind the doorway plane");
		assertEquals(3, door.width(), "door highlight spans three blocks across");
		assertEquals(4, door.height(), "door highlight spans four blocks high");
		assertEquals(3, door.depth(), "door highlight spans three blocks deep");
		assertEquals(40.5, door.centerX(), "door bounds remain centered across the doorway plane");
		assertEquals(-8.5, door.centerZ(), "door bounds remain centered along the doorway plane");

		assertTrue(DungeonKeyRules.isMatchingKey("§cWither\uE000 Key", DungeonDoorTracker.Type.WITHER),
			"formatting and invisible private-use name characters do not hide a matching Wither Key");
		assertTrue(DungeonKeyRules.isMatchingKey("Blood Key", DungeonDoorTracker.Type.BLOOD),
			"the Blood Key matches its own door type");
		assertFalse(DungeonKeyRules.isMatchingKey("Blood Key", DungeonDoorTracker.Type.WITHER),
			"a Blood Key never enables the Wither palette");
		assertFalse(DungeonKeyRules.isMatchingKey("Wither Key", DungeonDoorTracker.Type.BLOOD),
			"a Wither Key never enables the Blood palette");
		assertFalse(DungeonKeyRules.isMatchingKey("Wither Keychain", DungeonDoorTracker.Type.WITHER),
			"partial key-name matches do not change a door color");
		assertTrue(DungeonKeyRules.isPickupForPlayer("Alice has obtained Wither Key!", "Alice", DungeonDoorTracker.Type.WITHER),
			"a local Wither Key pickup can immediately update its door palette");
		assertFalse(DungeonKeyRules.isPickupForPlayer("Bob has obtained Wither Key!", "Alice", DungeonDoorTracker.Type.WITHER),
			"another player's key pickup cannot enable the local door palette");
		assertFalse(DungeonKeyRules.isPickupForPlayer("A Wither Key was picked up!", "Alice", DungeonDoorTracker.Type.WITHER),
			"a pickup line without player identity cannot override local inventory state");
		assertTrue(DungeonKeyRules.isDoorOpenedByPlayer("Alice opened a WITHER door!", "Alice", DungeonDoorTracker.Type.WITHER),
			"using a local Wither Key clears the corresponding chat fallback state");
		assertFalse(DungeonKeyRules.isDoorOpenedByPlayer("Bob opened a WITHER door!", "Alice", DungeonDoorTracker.Type.WITHER),
			"another player's door does not consume the local key fallback state");

	}

	private static void checkBloodTracerTarget() {
		DungeonDoorTracker.Door bloodDoor = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.BLOOD, 7, 8, new BlockPos(40, 70, -9));
		BlockPos closedPlaneBlock = new BlockPos(40, 70, -9);
		assertTrue(BloodDoorTraceRules.hasClosedBloodDoorBlocks(bloodDoor, closedPlaneBlock::equals),
			"a tracked Blood doorway with a live red door block remains a valid target");
		assertFalse(BloodDoorTraceRules.hasClosedBloodDoorBlocks(bloodDoor, ignored -> false),
			"a tracked Blood doorway stops being valid after its door blocks disappear");
		DungeonGridRules.Cell adjacentRoom = new DungeonGridRules.Cell(1, 1, -168, -168);
		List<DungeonRoomTracker.CellKey> singleRoom = List.of(new DungeonRoomTracker.CellKey(1, 1));
		assertTrue(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"the tracer follows a closed Blood doorway only from its directly connected current room");
		assertFalse(BloodDoorTraceRules.shouldTrace(false, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"disabling the Blood tracer suppresses its target");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, null, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"the Blood tracer stays hidden without a tracked target");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, false, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"the Blood tracer stays hidden when the tracked doorway is open or invalid");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ENTRANCE, "1x1", adjacentRoom, singleRoom, true),
			"the Blood tracer never appears at the map Entrance/spawn tile");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, false),
			"the tracer stays hidden when the player is outside the detected physical room footprint");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, false,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"uncalibrated map cells cannot authorize the tracer");
		assertFalse(BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", new DungeonGridRules.Cell(4, 4, -72, -72),
			List.of(new DungeonRoomTracker.CellKey(4, 4)), true),
			"an unrelated current room does not trace a distant Blood doorway");
		assertFalse(BloodDoorTraceRules.isBoundaryOfShape(
			DungeonDoorTracker.centeredDoor(DungeonDoorTracker.Type.BLOOD, 7, 14, new BlockPos(40, 70, -9)), singleRoom),
			"diagonal/nonadjacent endpoint pairs do not identify a valid boundary");
		assertFalse(BloodDoorTraceRules.isBoundaryOfShape(
			DungeonDoorTracker.centeredDoor(DungeonDoorTracker.Type.BLOOD, 5, 6, new BlockPos(40, 70, -9)),
			List.of(new DungeonRoomTracker.CellKey(5, 0))),
			"linear index wrap between opposite grid edges is not cell adjacency");
		List<DungeonRoomTracker.CellKey> twoTileShape = List.of(
			new DungeonRoomTracker.CellKey(1, 1), new DungeonRoomTracker.CellKey(2, 1));
		DungeonGridRules.Cell secondTile = new DungeonGridRules.Cell(2, 1, -136, -168);
		DungeonDoorTracker.Door outsideShapeBloodDoor = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.BLOOD, 8, 9, new BlockPos(40, 70, -9));
		assertTrue(BloodDoorTraceRules.shouldTrace(true, outsideShapeBloodDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x2", secondTile, twoTileShape, true),
			"a player in any occupied component of the connected multi-cell room traces its exact outer Blood edge");
		assertFalse(BloodDoorTraceRules.isBoundaryOfShape(bloodDoor, twoTileShape),
			"a tracked Blood doorway internal to a multi-cell room is not an exit boundary");
		DungeonDoorTracker.Door bloodDoorAlongZ = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.BLOOD, 7, 13, new BlockPos(40, 70, -9));
		assertTrue(BloodDoorTraceRules.hasClosedBloodDoorBlocks(bloodDoorAlongZ, closedPlaneBlock::equals),
			"the live doorway check handles a Blood entrance along either grid axis");
		DungeonDoorTracker.Door witherDoor = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 7, 8, new BlockPos(40, 70, -9));
		assertFalse(BloodDoorTraceRules.shouldTrace(true, witherDoor, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"a Wither doorway cannot become a Blood tracer target");
		assertFalse(BloodDoorTraceRules.bloodSequenceAllowsTrace(witherDoor),
			"an actually targeted closed Wither suppresses the downstream Blood tracer");
		assertTrue(BloodDoorTraceRules.bloodSequenceAllowsTrace(null),
			"opening the Wither clears its trace so Blood can proceed through the existing adjacency gate");
		DungeonDoorTracker.Door unrelatedWither = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 22, 23, new BlockPos(200, 70, -9));
		assertTrue(unrelatedWither.type() == DungeonDoorTracker.Type.WITHER
			&& BloodDoorTraceRules.bloodSequenceAllowsTrace(null)
			&& BloodDoorTraceRules.shouldTrace(true, bloodDoor, true, true,
				DungeonMapGeometry.RoomType.ROOM, "1x1", adjacentRoom, singleRoom, true),
			"an unrelated closed Wither elsewhere is not the active trace and cannot suppress a directly connected Blood door");
	}

	private static void checkDoorTracerRoomGateAndGrace() {
		List<DungeonRoomTracker.CellKey> oneRoom = List.of(new DungeonRoomTracker.CellKey(1, 1));
		DungeonGridRules.Cell current = new DungeonGridRules.Cell(1, 1, -168, -168);
		DungeonDoorTracker.Door wither = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 7, 8, new BlockPos(40, 70, -9));
		assertTrue(BloodDoorTraceRules.eligibleConnectedRoom(true, wither, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", current, oneRoom, true),
			"a closed Wither door traces only from its validated connected room");
		assertFalse(BloodDoorTraceRules.eligibleConnectedRoom(true, wither, true, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", new DungeonGridRules.Cell(4, 4, -72, -72),
			List.of(new DungeonRoomTracker.CellKey(4, 4)), true),
			"an unrelated room cannot authorize a Wither door tracer");
		assertFalse(BloodDoorTraceRules.eligibleConnectedRoom(true, wither, false, true,
			DungeonMapGeometry.RoomType.ROOM, "1x1", current, oneRoom, true),
			"an opened Wither door is not eligible for grace");
		assertTrue(DungeonDoorAdjacency.bordersCurrentRoom(wither, current, null),
			"physical player cell and adjacent door cells suffice when identity, rotation, and map shape are unavailable");
		assertFalse(DungeonDoorAdjacency.bordersCurrentRoom(wither,
			new DungeonGridRules.Cell(4, 4, -72, -72), null),
			"a physically unrelated player cell cannot trace this door");
		assertFalse(DungeonDoorAdjacency.bordersCurrentRoom(
			DungeonDoorTracker.centeredDoor(DungeonDoorTracker.Type.WITHER, 5, 6, new BlockPos(40, 70, -9)),
			current, null), "linear-index wrap is not accepted as geometric door adjacency");
		Set<Integer> twoCellShape = DungeonDoorAdjacency.footprint(current, List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0)));
		assertEquals(Set.of(7, 8), twoCellShape,
			"multi-cell map geometry expands from the player's local cell without a recognized room identity");
		assertFalse(DungeonDoorAdjacency.bordersCurrentRoom(wither, current, List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0))),
			"a doorway internal to the current multi-cell room is not considered its boundary");
		assertEquals(Set.of(7), DungeonDoorAdjacency.footprint(current,
			List.of(new DungeonMapGeometry.CellOffset(40, 0))),
			"malformed map footprint falls back to the reliable local player cell");

		assertEquals(200L, DungeonDoorTraceGrace.GRACE_TICKS,
			"the grace duration remains available as a public Java constant");
		DungeonDoorTraceGrace grace = new DungeonDoorTraceGrace();
		Object world = new Object();
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, wither, true, true, 100),
			"a currently eligible closed door begins its trace");
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, wither, true, false, 299),
			"the selected target remains visible during the ten-second room-leave grace");
		assertEquals(null, grace.update(true, world, DungeonFloor.F1, wither, true, false, 300),
			"the target is hidden when ten seconds have elapsed outside the eligible room");
		assertEquals(null, grace.update(true, world, DungeonFloor.F1, wither, false, false, 301),
			"an opened selected door clears its retained target immediately");
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, wither, true, true, 302),
			"a closed connected target can begin tracing again after returning");
		assertEquals(null, grace.update(true, new Object(), DungeonFloor.F1, wither, true, false, 303),
			"a world change cannot carry a previous door target into the new world");
		assertEquals(null, grace.update(true, world, DungeonFloor.F1, wither, true, false, 304),
			"a newly selected target is not traced before the player enters its connected room");

		DungeonDoorTracker.Door another = DungeonDoorTracker.centeredDoor(
			DungeonDoorTracker.Type.WITHER, 8, 9, new BlockPos(72, 70, -9));
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, wither, true, true, 305),
			"re-entering the connected room refreshes the eligible trace target");
		assertEquals(wither, grace.update(true, world, DungeonFloor.F1, another, true, false, 306),
			"a newly selected but non-adjacent door does not erase the previous target's ten-second grace");

		assertEquals(BloodDoorTraceRules.DoorPresence.UNKNOWN, BloodDoorTraceRules.doorPresence(wither,
			DungeonDoorTracker.Type.WITHER, ignored -> false, ignored -> false),
			"unloaded door samples are unknown, not treated as proof the doorway opened");
		assertEquals(BloodDoorTraceRules.DoorPresence.OPEN, BloodDoorTraceRules.doorPresence(wither,
			DungeonDoorTracker.Type.WITHER, ignored -> true, ignored -> false),
			"a fully loaded door plane with no Wither block confirms it opened");
	}























	private static void checkDungeonContextSignals() {
		DungeonContextTracker.Signal floor = DungeonContextTracker.readSidebarText("The Catacombs\nF7");
		assertTrue(floor.inDungeon(), "the Catacombs scoreboard establishes dungeon context");
		assertEquals(DungeonFloor.F7, floor.floor(), "the detected floor comes from the scoreboard");
		DungeonContextTracker.Signal master = DungeonContextTracker.readSidebarText("Dungeon: Master Mode F7");
		assertTrue(master.inDungeon(), "an explicit dungeon floor establishes context without a Catacombs title");
		assertEquals(DungeonFloor.M7, master.floor(), "Master Mode upgrades the detected floor");
		DungeonContextTracker.Signal entrance = DungeonContextTracker.readSidebarText("Dungeon: Entrance");
		assertTrue(entrance.inDungeon(), "Entrance is accepted as a dungeon context");
		assertEquals(DungeonFloor.ENTRANCE, entrance.floor(), "Entrance resolves to the shared floor value");
		assertFalse(DungeonContextTracker.readSidebarText("Dungeon Finder\nSearch for a party").inDungeon(),
			"the party finder scoreboard does not activate dungeon context");
	}

	private static void checkDungeonFloorSources() {
		assertEquals("Cooldown: 5s", geiler.addons.client.tree.ChatText.stripFormattingCodes("§7Cooldown: &a5s"),
			"section-sign and ampersand colour codes are stripped, other text is kept");
		assertEquals(7, DungeonFloor.romanFloor("vii"), "roman floor numerals parse case-insensitively");
		assertEquals(4, DungeonFloor.romanFloor("4"), "arabic floor digits parse");
		assertEquals(0, DungeonFloor.romanFloor("VIII"), "floors above 7 are not accepted");
		assertEquals(0, DungeonFloor.romanFloor(null), "a missing floor parses to zero");
		assertEquals(DungeonFloor.F7, DungeonFloor.parse("\u0001F7\u0001"),
			"shared floor parsing retains Java String.trim control-character behavior");
		assertEquals(null, DungeonFloor.parse("\u00a0F7\u00a0"),
			"shared floor parsing does not trim non-breaking spaces like Java String.trim");
		DungeonContextTracker.FloorSelection absent = DungeonContextTracker.floorSelection(false,
			null, null, DungeonFloor.F1);
		assertEquals(null, absent.floor(), "a queue floor cannot invent dungeon presence or silently select F1");
		assertEquals(DungeonContextTracker.FloorSource.UNKNOWN, absent.source(),
			"missing dungeon context has an explicit unknown floor source");
		DungeonContextTracker.FloorSelection queue = DungeonContextTracker.floorSelection(true,
			null, null, DungeonFloor.M7);
		assertEquals(DungeonFloor.M7, queue.floor(), "queue floor is retained as a separate validated fallback source");
		assertEquals(DungeonContextTracker.FloorSource.QUEUE, queue.source(), "queue floor provenance is explicit");
		DungeonContextTracker.FloorSelection sidebar = DungeonContextTracker.floorSelection(true,
			null, DungeonFloor.F6, DungeonFloor.M7);
		assertEquals(DungeonFloor.F6, sidebar.floor(), "scoreboard floor wins over queue fallback");
		assertEquals(DungeonContextTracker.FloorSource.SIDEBAR, sidebar.source(), "scoreboard provenance is explicit");
		DungeonContextTracker.FloorSelection manual = DungeonContextTracker.floorSelection(true,
			DungeonFloor.M5, DungeonFloor.F6, DungeonFloor.M7);
		assertEquals(DungeonFloor.M5, manual.floor(), "explicit manual floor has the highest floor-source priority");
		assertEquals(DungeonContextTracker.FloorSource.MANUAL, manual.source(), "manual provenance is explicit");
	}

	private static void checkMapGeometry() {
		byte[] colors = new byte[DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE];
		fillMapTile(colors, 60, 60, 8, DungeonMapGeometry.ENTRANCE_COLOR);
		fillMapTile(colors, 72, 61, 8, 63);
		fillMapTile(colors, 84, 61, 8, 63);
		// The pinned source tests the first shared-gap pixel along the neighbor's north/west edge.
		colors[(72 + 8) + ((60 + 1) << 7)] = (byte) 63;
		fillMapTile(colors, 108, 61, 8, 63);
		fillMapTile(colors, 72, 73, 8, 66);
		DungeonMapGeometry.MapPoint marker = new DungeonMapGeometry.MapPoint(75, 64);
		DungeonMapGeometry.Calibration calibration = DungeonMapGeometry.calibrate(colors, marker);
		assertTrue(calibration.calibrated(), "the nearest complete entrance tile calibrates map geometry");
		assertEquals(new DungeonMapGeometry.MapPoint(60, 61), calibration.entrance(),
			"calibration retains the pinned source's one-pixel entrance inset");
		assertEquals(8, calibration.roomPixelSize(), "calibration records the measured tile size");
		DungeonMapGeometry.RoomGeometry room = DungeonMapGeometry.roomAt(colors, marker, calibration);
		assertEquals(DungeonMapGeometry.RoomType.ROOM, room.roomType(), "the player marker maps to a normal room color");
		assertEquals("1x2", room.shape(), "connected same-color map tiles form a multi-cell shape");
		assertEquals(List.of(new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0)),
			room.connectedCells(), "only orthogonally connected normal-room cells are included");
		assertEquals(DungeonMapGeometry.RoomClearStatus.UNCLEARED,
			DungeonMapGeometry.roomClearStatus(colors, room),
			"a room without an upstream checkmark remains uncleared");
		byte[] whiteCheckmark = colors.clone();
		whiteCheckmark[76 + (65 << 7)] = 34;
		assertEquals(DungeonMapGeometry.RoomClearStatus.CLEARED_WHITE,
			DungeonMapGeometry.roomClearStatus(whiteCheckmark, room),
			"the upstream white checkmark marks a cleared room with remaining secrets");
		assertEquals(room.connectedCells(), DungeonMapGeometry.roomAt(whiteCheckmark, marker, calibration).connectedCells(),
			"a map checkmark color update does not revise the active room footprint");
		assertEquals(room.roomType(), DungeonMapGeometry.roomAt(whiteCheckmark, marker, calibration).roomType(),
			"a map checkmark color update does not revise the active source room type");
		assertTrue(DungeonMapGeometry.roomClearStatus(whiteCheckmark, room).cleared(),
			"both checkmarked room states signal a room clear to dependent tracking");
		byte[] greenCheckmark = colors.clone();
		greenCheckmark[76 + (65 << 7)] = 30;
		assertEquals(DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN,
			DungeonMapGeometry.roomClearStatus(greenCheckmark, room),
			"the upstream green checkmark marks a room cleared with secrets");
		byte[] redCheckmark = colors.clone();
		redCheckmark[76 + (65 << 7)] = 18;
		assertEquals(DungeonMapGeometry.RoomClearStatus.FAILED_RED,
			DungeonMapGeometry.roomClearStatus(redCheckmark, room),
			"the upstream red failed-room mark is not misreported as cleared");
		DungeonMapGeometry.RoomGeometry puzzle = DungeonMapGeometry.roomAt(colors,
			new DungeonMapGeometry.MapPoint(75, 76), calibration);
		assertEquals(DungeonMapGeometry.RoomType.PUZZLE, puzzle.roomType(), "puzzle map colors remain distinct from normal rooms");
		assertFalse(DungeonMapGeometry.calibrate(new byte[128], marker).calibrated(),
			"a truncated map buffer is rejected rather than guessed");
		assertFalse(DungeonMapGeometry.calibrate(new byte[128 * 128], marker).calibrated(),
			"missing entrance pixels produce an explicit calibration failure");
		assertEquals(new DungeonMapGeometry.WorldCell(-168, -200),
			DungeonMapGeometry.physicalCellAt(-169.0, -200.0), "physical positions map through the calibrated 32-block transform");
		assertEquals(new DungeonMapGeometry.WorldCell(-200, -200),
			DungeonMapGeometry.physicalCellAt(-170.0, -200.0), "the transform preserves negative-coordinate boundary behavior");
		assertEquals(new DungeonMapGeometry.WorldCell(-200, -168),
			DungeonMapGeometry.physicalCellAt(-200.0, -169.0), "the negative-grid south edge follows the same source boundary rounding");
		assertEquals(new DungeonMapGeometry.WorldCell(-200, -200),
			DungeonMapGeometry.physicalCellAt(-200.0, -170.0), "the last block of the south segment stays in that segment");
		assertEquals(new DungeonGridRules.Cell(1, 0, -168, -200), DungeonGridRules.cellAtOrigin(-168, -200),
			"a valid map room origin maps to its corresponding grid cell");
		assertEquals(null, DungeonGridRules.cellAtOrigin(-167, -200),
			"non-aligned map origins do not get rounded into another room");

		byte[] adjacentButSeparate = new byte[DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE];
		fillMapTile(adjacentButSeparate, 60, 60, 8, DungeonMapGeometry.ENTRANCE_COLOR);
		fillMapTile(adjacentButSeparate, 72, 61, 8, 63);
		fillMapTile(adjacentButSeparate, 84, 61, 8, 63);
		DungeonMapGeometry.RoomGeometry separate = DungeonMapGeometry.roomAt(adjacentButSeparate, marker, calibration);
		assertEquals("1x1", separate.shape(),
			"adjacent same-color tile centers remain separate when the map has no same-color connector in their gap");

		byte[] horizontalEntrance = new byte[DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE];
		for (int x = 50; x < 56; x++) horizontalEntrance[x + (50 << 7)] = (byte) DungeonMapGeometry.ENTRANCE_COLOR;
		DungeonMapGeometry.Calibration lineCalibration = DungeonMapGeometry.calibrate(horizontalEntrance,
			new DungeonMapGeometry.MapPoint(45, 50));
		assertTrue(lineCalibration.calibrated(),
			"Skyblocker-compatible entrance sizing uses the horizontal run over six pixels without imposing a square scan");
		assertEquals(6, lineCalibration.roomPixelSize(), "the measured horizontal entrance run is the map tile size");
	}

	private static void checkRoomGeometryValueType() {
		assertKotlinValueContract(DungeonMapGeometry.MapPoint.class, new String[] { "x", "z" },
			int.class, int.class);
		assertKotlinValueContract(DungeonMapGeometry.WorldCell.class, new String[] { "baseX", "baseZ" },
			int.class, int.class);
		assertKotlinValueContract(DungeonMapGeometry.CellOffset.class, new String[] { "x", "z" },
			int.class, int.class);
		assertKotlinValueContract(DungeonMapGeometry.Calibration.class,
			new String[] { "calibrated", "entrance", "roomPixelSize", "reason" },
			boolean.class, DungeonMapGeometry.MapPoint.class, int.class, String.class);
		assertKotlinValueContract(DungeonMapGeometry.RoomGeometry.class,
			new String[] { "calibrated", "calibration", "currentMapCell", "mapColor", "roomType", "connectedCells",
				"shape", "fallbackReason" },
			boolean.class, DungeonMapGeometry.Calibration.class, DungeonMapGeometry.MapPoint.class, byte.class,
			DungeonMapGeometry.RoomType.class, List.class, String.class, String.class);
		var sourceCells = new ArrayList<DungeonMapGeometry.CellOffset>();
		sourceCells.add(new DungeonMapGeometry.CellOffset(0, 0));
		var geometry = new DungeonMapGeometry.RoomGeometry(true, null, null, (byte) 63,
			DungeonMapGeometry.RoomType.ROOM, sourceCells, "1x1", null);
		sourceCells.clear();
		var equivalent = new DungeonMapGeometry.RoomGeometry(true, null, null, (byte) 63,
			DungeonMapGeometry.RoomType.ROOM, List.of(new DungeonMapGeometry.CellOffset(0, 0)), "1x1", null);
		var expected = new ExpectedRoomGeometry(true, null, null, (byte) 63,
			DungeonMapGeometry.RoomType.ROOM, List.of(new DungeonMapGeometry.CellOffset(0, 0)), "1x1", null);
		assertEquals(List.of(new DungeonMapGeometry.CellOffset(0, 0)), geometry.connectedCells(),
			"room geometry defensively copies connected cells");
		assertTrue(geometry.equals(equivalent) && equivalent.equals(geometry),
			"room geometry retains symmetric record-style equality");
		assertEquals(expected.hashCode(), geometry.hashCode(), "room geometry retains Java record hash calculation");
		assertEquals("RoomGeometry[calibrated=true, calibration=null, currentMapCell=null, mapColor=63, " +
			"roomType=ROOM, connectedCells=[CellOffset[x=0, z=0]], shape=1x1, fallbackReason=null]",
			geometry.toString(), "room geometry retains Java record text formatting");
		var unavailable = new DungeonMapGeometry.RoomGeometry(false, null, null, (byte) -1,
			DungeonMapGeometry.RoomType.UNKNOWN, null, "unknown", "missing");
		assertEquals(List.of(), unavailable.connectedCells(), "a null connected-cell list normalizes to an immutable empty list");
		try {
			unavailable.connectedCells().add(new DungeonMapGeometry.CellOffset(0, 0));
			throw new AssertionError("room geometry exposes its copied cells as immutable");
		} catch (UnsupportedOperationException expectedImmutable) { }
	}

	private static void checkMapIdFallback() {
		MapId hotbarId = new MapId(2048);
		MapId cachedId = new MapId(3072);
		DungeonMapRoomDetector.MapIdResolution held = DungeonMapRoomDetector.resolveMapId(true, hotbarId, cachedId);
		assertEquals(hotbarId, held.mapId(), "a filled map in hotbar slot nine remains the authoritative map ID");
		assertEquals(DungeonMapRoomDetector.MapIdSource.HOTBAR, held.source(), "the live hotbar map source is explicit");
		DungeonMapRoomDetector.MapIdResolution cached = DungeonMapRoomDetector.resolveMapId(false, null, cachedId);
		assertEquals(cachedId, cached.mapId(), "a missing hotbar map reuses the last observed map ID");
		assertEquals(DungeonMapRoomDetector.MapIdSource.CACHED, cached.source(), "the cached map source is explicit");
		DungeonMapRoomDetector.MapIdResolution fallback = DungeonMapRoomDetector.resolveMapId(false, null, null);
		assertEquals(new MapId(1024), fallback.mapId(), "map ID 1024 is the final Skyblocker-compatible fallback");
		assertEquals(DungeonMapRoomDetector.MapIdSource.DEFAULT, fallback.source(), "the default map source is explicit");
		DungeonMapGeometry.MapPoint roomCell = new DungeonMapGeometry.MapPoint(38, 51);
		assertFalse(DungeonMapRoomDetector.shouldRefreshGeometry(true, true, roomCell, roomCell, 1, false),
			"unchanged map-room geometry is reused between bounded refreshes");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(false, true, roomCell, roomCell, 1, false),
			"a changed map data object invalidates cached room geometry");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(true, false, roomCell, roomCell, 1, false),
			"room geometry is calculated when there is no cached result");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(true, true, roomCell,
			new DungeonMapGeometry.MapPoint(52, 51), 1, false),
			"entering another map cell refreshes the room footprint immediately");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(true, true, roomCell, roomCell, 20, false),
			"periodic refreshes observe newly revealed adjacent map tiles");
		assertTrue(DungeonMapRoomDetector.shouldRefreshGeometry(true, true, roomCell, roomCell, 1, true),
			"unknown geometry retries without waiting for the periodic refresh");
	}

	private static void checkMapDetectorValueTypes() {
		try {
			var type = DungeonMapRoomDetector.class;
			type.getMethod("onMapItemData", net.minecraft.network.protocol.game.ClientboundMapItemDataPacket.class);
			type.getMethod("tick", net.minecraft.client.Minecraft.class);
			type.getMethod("current");
			type.getMethod("overlaySnapshot", net.minecraft.client.Minecraft.class);
			type.getMethod("savedMapColors", net.minecraft.client.Minecraft.class);
			type.getMethod("savedMapDecorations", net.minecraft.client.Minecraft.class);
			type.getMethod("currentRoomCleared");
			type.getMethod("currentRoomClearStatus");
			type.getMethod("diagnostics");
			assertKotlinValueContract(DungeonMapRoomDetector.MapIdResolution.class, new String[] { "mapId", "source" },
				MapId.class, DungeonMapRoomDetector.MapIdSource.class);
			assertKotlinValueContract(DungeonMapRoomDetector.Reading.class,
				new String[] { "status", "mapId", "mapIdSource", "playerMapPosition", "calibration", "geometry",
					"currentCell", "fallbackReason" },
				DungeonMapRoomDetector.Status.class, MapId.class, DungeonMapRoomDetector.MapIdSource.class,
				DungeonMapGeometry.MapPoint.class, DungeonMapGeometry.Calibration.class,
				DungeonMapGeometry.RoomGeometry.class, DungeonGridRules.Cell.class, String.class);
			assertTrue(java.util.Arrays.equals(DungeonMapRoomDetector.Status.values(),
				new DungeonMapRoomDetector.Status[] { DungeonMapRoomDetector.Status.NOT_SCANNED,
					DungeonMapRoomDetector.Status.NO_CLIENT_WORLD, DungeonMapRoomDetector.Status.MAP_ITEM_MISSING,
					DungeonMapRoomDetector.Status.MAP_ID_MISSING, DungeonMapRoomDetector.Status.MAP_DATA_MISSING,
					DungeonMapRoomDetector.Status.PLAYER_MARKER_MISSING,
					DungeonMapRoomDetector.Status.CALIBRATION_UNAVAILABLE,
					DungeonMapRoomDetector.Status.CURRENT_CELL_UNAVAILABLE,
					DungeonMapRoomDetector.Status.ROOM_TYPE_UNAVAILABLE, DungeonMapRoomDetector.Status.AVAILABLE }),
				"map detector status values keep their order");
			assertTrue(java.util.Arrays.equals(DungeonMapRoomDetector.MapIdSource.values(),
				new DungeonMapRoomDetector.MapIdSource[] { DungeonMapRoomDetector.MapIdSource.UNKNOWN,
					DungeonMapRoomDetector.MapIdSource.HOTBAR, DungeonMapRoomDetector.MapIdSource.CACHED,
					DungeonMapRoomDetector.MapIdSource.DEFAULT }), "map ID source values keep their order");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("map detector Java API changed", error);
		}

		var decoration = new DungeonMapRoomDetector.SavedMapDecoration(-200, 200, -1, true);
		var expectedDecoration = new DungeonMapRoomDetector.SavedMapDecoration(-128, 127, 15, true);
		var expectedRecord = new ExpectedSavedMapDecoration(-128, 127, 15, true);
		assertKotlinValueContract(DungeonMapRoomDetector.SavedMapDecoration.class,
			new String[] { "x", "z", "rotation", "frame" }, int.class, int.class, int.class, boolean.class);
		assertEquals(-128, decoration.x(), "saved map decoration clamps the lower coordinate bound");
		assertEquals(127, decoration.z(), "saved map decoration clamps the upper coordinate bound");
		assertEquals(15, decoration.rotation(), "saved map decoration wraps rotation into the map range");
		assertTrue(decoration.equals(expectedDecoration) && expectedDecoration.equals(decoration)
			&& decoration.hashCode() == expectedRecord.hashCode()
			&& decoration.toString().equals("SavedMapDecoration[x=-128, z=127, rotation=15, frame=true]"),
			"saved map decoration values preserve normalized record behavior");

		byte[] sourceColors = new byte[] { 1, 2, 3 };
		var snapshot = new DungeonMapRoomDetector.OverlaySnapshot(sourceColors, null, null, null, null);
		assertKotlinValueContract(DungeonMapRoomDetector.OverlaySnapshot.class,
			new String[] { "colors", "mapId", "calibration", "currentRoom", "currentCell" }, byte[].class, MapId.class,
			DungeonMapGeometry.Calibration.class, DungeonMapGeometry.RoomGeometry.class, DungeonGridRules.Cell.class);
		sourceColors[0] = 9;
		byte[] returnedColors = snapshot.colors();
		returnedColors[1] = 9;
		assertTrue(java.util.Arrays.equals(snapshot.colors(), new byte[] { 1, 2, 3 })
			&& snapshot.equals(snapshot)
			&& !snapshot.equals(new DungeonMapRoomDetector.OverlaySnapshot(new byte[] { 1, 2, 3 }, null, null, null, null)),
			"overlay snapshots clone bytes on input/output and retain array-reference record equality");
		assertTrue(new DungeonMapRoomDetector.OverlaySnapshot(null, null, null, null, null).colors().length == 0,
			"null overlay pixels normalize to an empty array");
	}

	private static void assertRecordComponents(Class<?> type, String... expectedNames) {
		var components = type.getRecordComponents();
		assertTrue(type.isRecord() && components != null && components.length == expectedNames.length,
			type.getSimpleName() + " retains its Java record metadata");
		for (int index = 0; index < expectedNames.length; index++) {
			assertEquals(expectedNames[index], components[index].getName(),
				type.getSimpleName() + " component " + index + " remains in its original position");
		}
	}

	private static void assertKotlinValueContract(Class<?> type, String[] componentNames, Class<?>... componentTypes) {
		assertTrue(type.getAnnotation(kotlin.Metadata.class) != null,
			type.getSimpleName() + " retains Kotlin class metadata");
		assertEquals(componentNames.length, componentTypes.length,
			type.getSimpleName() + " component names and Java accessor types match");
		try {
			type.getConstructor(componentTypes);
			for (int index = 0; index < componentNames.length; index++) {
				var accessor = type.getMethod(componentNames[index]);
				assertEquals(componentTypes[index], accessor.getReturnType(),
					type.getSimpleName() + " keeps Java accessor " + componentNames[index] + "() with its original type");
			}
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(type.getSimpleName() + " lost a Java constructor or component accessor", error);
		}
	}

	private static void assertRecordContract(Class<?> type, String[] expectedNames, Class<?>... expectedTypes) {
		assertRecordComponents(type, expectedNames);
		var actualTypes = java.util.Arrays.stream(type.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getType).toList();
		assertEquals(List.of(expectedTypes), actualTypes,
			type.getSimpleName() + " retains record component types in order");
		try {
			type.getConstructor(expectedTypes);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(type.getSimpleName() + " lost its canonical public constructor", error);
		}
	}

	private static void fillMapTile(byte[] colors, int left, int top, int size, int color) {
		for (int z = top; z < top + size; z++) for (int x = left; x < left + size; x++) {
			colors[x + (z << 7)] = (byte) color;
		}
	}


	private static void checkGridRules() {
		assertRecordComponents(DungeonGridRules.Cell.class, "componentX", "componentZ", "baseX", "baseZ");
		assertRecordComponents(DungeonGridRules.Position.class, "x", "z");
		assertRecordComponents(DungeonGridRules.BlockSample.class, "x", "y", "z", "blockId");
		assertRecordComponents(DungeonGridRules.Fingerprint.class, "hash", "rotation", "sampleCount");
		assertRecordComponents(DungeonRoomOrientationRules.Candidate.class, "rotation", "markerX", "markerY", "markerZ");
		assertRecordComponents(DungeonRoomOrientationRules.Position.class, "x", "y", "z");
		try {
			DungeonGridRules.class.getMethod("cellAt", int.class, int.class);
			DungeonGridRules.class.getMethod("cellAtOrigin", int.class, int.class);
			DungeonGridRules.class.getMethod("toCanonical", int.class, int.class, int.class);
			DungeonGridRules.class.getMethod("toWorld", int.class, int.class, int.class);
			DungeonGridRules.class.getMethod("fingerprint", List.class);
			DungeonRoomOrientationRules.class.getMethod("candidates", List.class, String.class, int.class);
			DungeonRoomOrientationRules.class.getMethod("uniqueMarkedCandidate", List.class,
				java.util.function.Predicate.class);
			DungeonRoomOrientationRules.class.getMethod("toWorld", DungeonRoomOrientationRules.Candidate.class,
				int.class, int.class, int.class);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("dungeon grid/orientation Java API changed", error);
		}
		assertEquals(-200, DungeonGridRules.GRID_MIN, "the grid minimum remains a Java static constant");
		assertEquals(-10, DungeonGridRules.GRID_MAX, "the grid maximum remains a Java static constant");

		DungeonGridRules.Cell first = DungeonGridRules.cellAt(-200, -200);
		assertEquals(0, first.componentX(), "the dungeon grid starts at component zero");
		assertEquals(-200, first.baseX(), "the first room starts at the documented grid minimum");
		DungeonGridRules.Cell last = DungeonGridRules.cellAt(-10, -10);
		assertEquals(5, last.componentX(), "the final valid grid coordinate maps to the sixth component");
		assertEquals(-40, last.baseX(), "the final room uses the fixed 32-block stride");
		assertEquals(null, DungeonGridRules.cellAt(-169, -200), "the one-block gap between room cells is not another room");
		assertEquals(null, DungeonGridRules.cellAt(-9, -10), "coordinates outside the dungeon grid are rejected");

		for (int rotation = 0; rotation < 4; rotation++) {
			for (int x : List.of(0, 3, 17, 30)) for (int z : List.of(1, 11, 29)) {
				DungeonGridRules.Position world = DungeonGridRules.toWorld(rotation, x, z);
				assertEquals(new DungeonGridRules.Position(x, z),
					DungeonGridRules.toCanonical(rotation, world.x(), world.z()),
					"room coordinate transforms round trip for every rotation");
			}
		}

		List<DungeonGridRules.BlockSample> room = List.of(
			new DungeonGridRules.BlockSample(1, 0, 4, "minecraft:stone_bricks"),
			new DungeonGridRules.BlockSample(8, 3, 2, "minecraft:gold_block"),
			new DungeonGridRules.BlockSample(29, 9, 24, "minecraft:oak_planks"),
			new DungeonGridRules.BlockSample(12, 17, 6, "minecraft:spawner"));
		long expected = DungeonGridRules.fingerprint(room).hash();
		for (int rotation = 1; rotation < 4; rotation++) {
			int orientation = rotation;
			List<DungeonGridRules.BlockSample> rotated = room.stream().map(sample -> {
				DungeonGridRules.Position position = DungeonGridRules.toCanonical(orientation, sample.x(), sample.z());
				return new DungeonGridRules.BlockSample(position.x(), sample.y(), position.z(), sample.blockId());
			}).toList();
			assertEquals(expected, DungeonGridRules.fingerprint(rotated).hash(),
				"room fingerprints remain stable under cell rotation");
		}
	}

	private static void checkGuidePackages() {
		DungeonGuideNode node = new DungeonGuideNode(DungeonFloor.M7, "BOSS", 4, -12, 70, 33);
		node.id = "route-step";
		node.label = "Enter the dragon arena";
		node.shape = DungeonGuideNode.Shape.BOX;
		node.rotation = 45.0f;
		node.size = 2.5f;
		node.trigger = DungeonGuideNode.Trigger.CHAT_EVENT;
		node.eventText = "The gate has opened";
		node.color = 0xFFABCDEF;
		String encoded = DungeonPackageCodec.encodeGuides(List.of(node));
		var decoded = DungeonPackageCodec.decodeGuides(encoded);
		assertTrue(decoded.success(), "guide package decodes");
		DungeonGuideNode restored = decoded.entries().getFirst();
		assertEquals("M7", restored.floor, "guide packages preserve master floors");
		assertEquals("BOSS", restored.phase, "guide packages preserve stable phase ids");
		assertEquals(45.0f, restored.rotation, "guide packages preserve rotation");
		assertEquals(DungeonGuideNode.Trigger.CHAT_EVENT, restored.trigger, "guide packages preserve event triggers");
		assertEquals("The gate has opened", restored.eventText, "guide packages preserve configured event phrases");
		assertFalse(DungeonPackageCodec.decodeGuides(encoded.replace("\"version\": 1", "\"version\": 7")).success(),
			"unknown guide package versions are rejected");
		assertFalse(DungeonPackageCodec.decodeGuides("{\"nodes\":[]}").success(),
			"guide packages without an explicit version are rejected");
		assertFalse(DungeonPackageCodec.decodeGuides("[]").success(), "non-object package roots are rejected");
		assertFalse(DungeonPackageCodec.decodeGuides(null).success(), "missing package input is rejected");
		String duplicateIds = "{\"version\":1,\"nodes\":[null,{\"id\":\"duplicate\",\"label\":\"first\"},{\"id\":\"duplicate\",\"label\":\"second\"}]}";
		var sanitized = DungeonPackageCodec.decodeGuides(duplicateIds);
		assertTrue(sanitized.success(), "null nodes are skipped while valid nodes are imported");
		assertEquals(2, sanitized.entries().size(), "valid nodes survive null entries");
		assertFalse(sanitized.entries().get(0).id.equals(sanitized.entries().get(1).id),
			"duplicate imported ids are replaced");
		assertFalse(DungeonPackageCodec.decodeGuides("{\"value\":\"" + "x".repeat(ClientJsonFile.MAX_PACKAGE_BYTES) + "\"}").success(),
			"package imports enforce the UTF-8 byte bound");
		List<DungeonGuideNode> tooMany = new ArrayList<>();
		for (int i = 0; i <= ClientJsonFile.MAX_ENTRIES; i++) tooMany.add(new DungeonGuideNode(DungeonFloor.F1, "ENTRY", i, 0, 0, 0));
		assertEquals(null, DungeonPackageCodec.encodeGuides(tooMany), "guide export enforces the entry bound");
		DungeonGuideNode oversizedNode = new DungeonGuideNode(DungeonFloor.F1, "ENTRY", 0, 0, 0, 0);
		oversizedNode.label = "x".repeat(ClientJsonFile.MAX_PACKAGE_BYTES);
		assertEquals(null, DungeonPackageCodec.encodeGuides(List.of(oversizedNode)),
			"guide exports enforce the UTF-8 byte bound");
		assertEquals(15, DungeonFloor.values().length, "the route model contains Entrance, F1-F7 and M1-M7");
	}

	private static void checkGuideProgress() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F7, 1_000_000_000L);
		DungeonGuideNode entry = new DungeonGuideNode(DungeonFloor.F7, "ENTRY", 0, 0, 0, 0);
		DungeonGuideNode bloodEvent = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 0, 0, 0, 0);
		bloodEvent.eventText = "Watcher defeated";
		bloodEvent.trigger = DungeonGuideNode.Trigger.CHAT_EVENT;
		DungeonGuideNode bloodNext = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 1, 1, 0, 0);
		DungeonGuideNode otherFloor = new DungeonGuideNode(DungeonFloor.M7, "BLOOD", 0, 2, 0, 0);
		List<DungeonGuideNode> all = List.of(entry, bloodEvent, bloodNext, otherFloor);
		assertEquals(3, progress.group(all).size(), "one route includes every phase on the confirmed floor");
		assertEquals("ENTRY", progress.group(all).get(0).phase, "built-in phases appear before unknown legacy phases");
		progress.next(all, 2_000_000_000L);
		assertEquals(1, progress.index(), "manual next advances the first ordered step");
		assertTrue(progress.observeConfiguredEvent("Watcher defeated", all, 2_500_000_000L),
			"a unique configured phrase remains identifiable for editor/phase tooling");
		assertEquals(1, progress.index(), "an event on a later step never skips the active step");
		assertEquals(2, progress.remaining(all), "remaining count covers all phases in route order");
		assertEquals("BLOOD", progress.group(all).get(progress.index()).phase,
			"the next waypoint is revealed only after the previous ordered step passes");
		progress.previous(3_000_000_000L);
		assertEquals(0, progress.index(), "previous control returns to the preceding route step");
		progress.next(progress.group(all), 4_000_000_000L);
		assertEquals(1, progress.index(), "next control advances the active route");
		checkGuideRevisionProgress();

		DungeonGuideNode delay = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 2, 0, 0, 0);
		delay.conditions = EnumSet.of(DungeonGuideNode.Condition.AFTER_SECONDS);
		delay.updateLegacyTrigger();
		delay.triggerSeconds = 3;
		assertFalse(DungeonGuideProgress.triggerReady(delay, 2_999_000_000L, Double.POSITIVE_INFINITY),
			"timer trigger waits until its configured duration");
		assertTrue(DungeonGuideProgress.triggerReady(delay, 3_000_000_000L, Double.POSITIVE_INFINITY),
			"timer trigger opens at its configured duration");
		DungeonGuideNode radius = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 3, 0, 0, 0);
		radius.conditions = EnumSet.of(DungeonGuideNode.Condition.ENTER_RADIUS);
		radius.updateLegacyTrigger();
		radius.triggerRadius = 2;
		assertTrue(DungeonGuideProgress.triggerReady(radius, 0, 3.9), "enter-radius trigger advances inside its configured radius");
		assertFalse(DungeonGuideProgress.triggerReady(radius, 0, 4.1), "enter-radius trigger stays inactive outside its radius");
		DungeonGuideNode combined = new DungeonGuideNode(DungeonFloor.F7, "CLEAR", 4, 0, 0, 0);
		combined.conditions = EnumSet.of(DungeonGuideNode.Condition.ENTER_RADIUS, DungeonGuideNode.Condition.AFTER_SECONDS);
		combined.conditionRule = DungeonGuideNode.ConditionRule.ALL;
		combined.triggerRadius = 2;
		combined.triggerSeconds = 3;
		assertFalse(DungeonGuideProgress.triggerReady(combined, 3_000_000_000L, 4.1),
			"ALL waits while any enabled condition remains false");
		assertTrue(DungeonGuideProgress.triggerReady(combined, 3_000_000_000L, 3.9),
			"ALL advances after every enabled condition is true");
		combined.conditionRule = DungeonGuideNode.ConditionRule.ANY;
		assertTrue(DungeonGuideProgress.triggerReady(combined, 3_000_000_000L, 4.1),
			"ANY advances when at least one enabled condition is true");

		DungeonGuideNode duplicatePhrase = new DungeonGuideNode(DungeonFloor.F7, "COMPLETE", 0, 0, 0, 0);
		duplicatePhrase.eventText = "Watcher defeated";
		assertFalse(progress.observeConfiguredEvent("Watcher defeated", List.of(bloodEvent, duplicatePhrase), 5_000_000_000L),
			"duplicate configured event phrases are treated as ambiguous");
		assertEquals("ENTRY", progress.phase(), "ambiguous chat does not change the active phase");

		DungeonGuideProgress ambiguousProgress = new DungeonGuideProgress();
		ambiguousProgress.setFloor(DungeonFloor.F7, 5_000_000_000L);
		DungeonGuideNode activeChatStep = new DungeonGuideNode(DungeonFloor.F7, "ENTRY", 0, 0, 0, 0);
		activeChatStep.conditions = EnumSet.of(DungeonGuideNode.Condition.CHAT_EVENT);
		activeChatStep.trigger = DungeonGuideNode.Trigger.CHAT_EVENT;
		activeChatStep.eventText = "Watcher defeated";
		DungeonGuideNode conflictingChatStep = new DungeonGuideNode(DungeonFloor.F7, "COMPLETE", 0, 0, 0, 0);
		conflictingChatStep.conditions = EnumSet.of(DungeonGuideNode.Condition.CHAT_EVENT);
		conflictingChatStep.trigger = DungeonGuideNode.Trigger.CHAT_EVENT;
		conflictingChatStep.eventText = activeChatStep.eventText;
		List<DungeonGuideNode> ambiguousSteps = List.of(activeChatStep, conflictingChatStep);
		assertEquals(0, ambiguousProgress.index(), "the duplicate-event trace starts on its chat-triggered step");
		assertFalse(ambiguousProgress.observeConfiguredEvent("Watcher defeated", ambiguousSteps, 5_000_000_001L),
			"an ambiguous phrase is rejected before it can trigger the active chat step");
		assertEquals(0, ambiguousProgress.index(), "ambiguous configured chat leaves route progress unchanged");
	}

	private static void checkGuideRevisionProgress() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F7, 100);
		DungeonGuideNode before = guideStep("before", 0);
		DungeonGuideNode active = guideStep("active", 1);
		DungeonGuideNode after = guideStep("after", 2);
		List<DungeonGuideNode> initial = List.of(before, active, after);
		progress.snapshot(initial, 1, 100);
		progress.next(initial, 200);
		progress.snapshot(initial, 1, 200);

		// Removing a preceding node must not leave the integer index pointing at the next waypoint.
		active.order = 0;
		after.order = 1;
		DungeonGuideProgress.RouteSnapshot removedBefore = progress.snapshot(List.of(active, after), 2, 250);
		assertEquals(0, removedBefore.stepIndex(), "removing a preceding guide node reanchors the same active id");
		assertEquals("active", removedBefore.current().id(), "removal before the active guide does not retarget it");

		// Reorder the active node behind another entry, then insert a new node before it.
		active.order = 2;
		after.order = 0;
		DungeonGuideProgress.RouteSnapshot reordered = progress.snapshot(List.of(active, after), 3, 300);
		assertEquals(1, reordered.stepIndex(), "reordering reanchors the active guide at its new position");
		assertEquals("active", reordered.current().id(), "reordering preserves the active guide id");
		DungeonGuideNode inserted = guideStep("inserted", 1);
		DungeonGuideProgress.RouteSnapshot insertedBefore = progress.snapshot(
			List.of(active, after, inserted), 4, 350);
		assertEquals(2, insertedBefore.stepIndex(), "inserting before the active guide reanchors its new index");
		assertEquals("active", insertedBefore.current().id(), "insertion does not silently skip to a new guide");
		assertEquals(150L, progress.elapsedNanos(350), "an unchanged active guide keeps its trigger timer");

		DungeonGuideProgress deletedProgress = new DungeonGuideProgress();
		deletedProgress.setFloor(DungeonFloor.F7, 100);
		DungeonGuideNode first = guideStep("first", 0);
		DungeonGuideNode deleted = guideStep("deleted", 1);
		DungeonGuideNode shifted = guideStep("shifted", 2);
		List<DungeonGuideNode> oldRoute = List.of(first, deleted, shifted);
		deletedProgress.snapshot(oldRoute, 1, 100);
		deletedProgress.next(oldRoute, 200);
		deletedProgress.snapshot(oldRoute, 1, 200);
		shifted.order = 0;
		DungeonGuideProgress.RouteSnapshot activeDeleted = deletedProgress.snapshot(List.of(shifted), 2, 250);
		assertEquals("guide-data-reset", activeDeleted.rebuildReason(),
			"deleting the active guide reports the explicit restart policy");
		assertEquals(0, activeDeleted.stepIndex(), "deleting the active guide restarts at the route beginning");
		assertEquals("shifted", activeDeleted.current().id(), "restart selects the new first guide deliberately");
		assertFalse(activeDeleted.complete(), "active deletion does not turn the shifted route into completion");
		assertEquals(0L, deletedProgress.elapsedNanos(250), "restart resets the new first guide timer");

		DungeonGuideProgress emptiedProgress = new DungeonGuideProgress();
		emptiedProgress.setFloor(DungeonFloor.F7, 100);
		List<DungeonGuideNode> oneStep = List.of(guideStep("only", 0));
		emptiedProgress.snapshot(oneStep, 1, 100);
		emptiedProgress.next(oneStep, 200);
		DungeonGuideProgress.RouteSnapshot completed = emptiedProgress.snapshot(oneStep, 1, 200);
		assertTrue(completed.complete(), "a non-empty route reports completion after its last step");
		DungeonGuideProgress.RouteSnapshot emptied = emptiedProgress.snapshot(List.of(), 2, 300);
		assertEquals(0, emptied.stepIndex(), "deleting every guide clears stale progress");
		assertEquals(0, emptied.remaining(), "an empty route has no remaining steps");
		assertFalse(emptied.complete(), "deleting every guide is not reported as route completion");
	}

	private static DungeonGuideNode guideStep(String id, int order) {
		DungeonGuideNode node = new DungeonGuideNode(DungeonFloor.F7, "ENTRY", order, 0, 0, 0);
		node.id = id;
		return node;
	}

	private static void checkGuideMigration() {
		List<DungeonGuideNode> nodes = new ArrayList<>();
		DungeonGuideNode lateName = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 0, 0, 0, 0);
		lateName.id = "legacy-zulu";
		lateName.routeName = "Zulu route";
		DungeonGuideNode earlyOrder = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 8, 0, 0, 0);
		earlyOrder.id = "legacy-alpha-first";
		earlyOrder.routeName = "Alpha route";
		DungeonGuideNode secondOrder = new DungeonGuideNode(DungeonFloor.F7, "BLOOD", 9, 0, 0, 0);
		secondOrder.id = "legacy-alpha-second";
		secondOrder.routeName = "Alpha route";
		DungeonGuideNode firstPhase = new DungeonGuideNode(DungeonFloor.F7, "ENTRY", 4, 0, 0, 0);
		firstPhase.id = "legacy-entry";
		firstPhase.routeName = "Zulu route";
		DungeonGuideNode soleRoute = new DungeonGuideNode(DungeonFloor.F6, "ENTRY", 2, 0, 0, 0);
		soleRoute.routeName = "My saved route";
		nodes.addAll(List.of(lateName, earlyOrder, secondOrder, soleRoute, firstPhase));
		Map<String, String> routeNames = new java.util.LinkedHashMap<>();

		DungeonGuideStore.normalizeRouteNamesAndOrder(nodes, routeNames);

		assertEquals("Combined Route", routeNames.get("F7"), "multiple legacy route names combine under one floor route");
		assertEquals("My saved route", routeNames.get("F6"), "a sole legacy route name is preserved");
		assertTrue(nodes.stream().filter(node -> node.floor.equals("F7"))
			.allMatch(node -> node.routeName.equals("Combined Route")), "every migrated phase shares the same route name");
		List<DungeonGuideNode> f7 = nodes.stream().filter(node -> node.floor.equals("F7")).toList();
		assertEquals("ENTRY", f7.get(0).phase, "migration sorts built-in phases before later phases");
		assertEquals("legacy-alpha-first", f7.get(1).id, "same-phase routes sort by old route name then old order");
		assertEquals(0, f7.get(1).order, "migration renumbers the first step in its phase");
		assertEquals(1, f7.get(2).order, "migration retains order inside a legacy route");
		assertEquals("legacy-zulu", f7.get(3).id, "later legacy route follows earlier route in the same phase");
	}

	private static void checkGuideFlush() {
		final class PendingSnapshot implements DungeonGuideFlush.Writer {
			private final long mutationVersion = 2;
			private long pendingVersion = 1;
			private long completedVersion;
			private boolean dirty = true;
			private boolean pending = true;
			private int writes;
			private long lastWrittenVersion;

			@Override public boolean awaitPending() {
				if (pending) {
					completedVersion = pendingVersion;
					pending = false;
					dirty = completedVersion != mutationVersion;
				}
				return true;
			}
			@Override public boolean dirty() { return dirty; }
			@Override public boolean persistenceBlocked() { return false; }
			@Override public boolean hasPending() { return pending; }
			@Override public void writeSnapshot() {
				pendingVersion = mutationVersion;
				lastWrittenVersion = pendingVersion;
				writes++;
				pending = true;
			}
		}
		PendingSnapshot store = new PendingSnapshot();
		DungeonGuideFlush.drain(store);
		assertEquals(1, store.writes, "flush schedules a fresh snapshot after an older pending version completes");
		assertEquals(2L, store.lastWrittenVersion, "flush writes the newest mutation version, not the stale completion");
		assertEquals(2L, store.completedVersion, "flush waits for the replacement snapshot to complete");
		assertFalse(store.dirty, "a successful latest-version completion clears the dirty state");
	}

	private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
	private static void assertFalse(boolean value, String message) { if (value) throw new AssertionError(message); }
	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private record ExpectedRoomGeometry(boolean calibrated, DungeonMapGeometry.Calibration calibration,
		DungeonMapGeometry.MapPoint currentMapCell, byte mapColor, DungeonMapGeometry.RoomType roomType,
		List<DungeonMapGeometry.CellOffset> connectedCells, String shape, String fallbackReason) { }
	private record ExpectedSavedMapDecoration(int x, int z, int rotation, boolean frame) { }
	private record ExpectedDoor(DungeonDoorTracker.Type type, int firstCell, int secondCell, int x, int y, int z,
		int width, int height, int depth, List<BlockPos> sourcePositions, String sourceDiagnostics) { }
}
