package geiler.addons.client.dungeon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.gui.SecretWaypointEditorScreen;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Focused bounded-package and confirmed-collection checks for Secret Waypoints. */
public final class SecretWaypointChecks {
	private SecretWaypointChecks() { }

	public static void run() {
		checkEditorScreenConstructorAbi();
		checkClickFeedbackJavaApi();
		checkConfirmedRoomSignal();
		checkBundledCatalog();
		checkRoomRelativeCatalog();
		checkCategoryVisibility();
		checkPendingPickupResolution();
		checkPackageRoundTripAndBounds();
		checkStoreAbiAndPersistence();
	}

	private static void checkClickFeedbackJavaApi() {
		assertTrue(Modifier.isFinal(SecretWaypointClickFeedbackRules.class.getModifiers()),
			"the Java-facing click rules remain final");
		assertEquals(1_000, SecretWaypointClickFeedbackRules.DEFAULT_WINDOW_MILLIS,
			"the default confirmation window remains unchanged");
		assertEquals(100, SecretWaypointClickFeedbackRules.MIN_WINDOW_MILLIS,
			"the minimum confirmation window remains unchanged");
		assertEquals(10_000, SecretWaypointClickFeedbackRules.MAX_WINDOW_MILLIS,
			"the maximum confirmation window remains unchanged");
		assertRecordComponents(SecretWaypointClickFeedbackRules.Selection.class,
			new Class<?>[] {boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class},
			"chests", "droppedItems", "essenceDrops", "bats", "redstoneKeys", "levers");
		assertEquals("Selection[chests=true, droppedItems=false, essenceDrops=true, bats=false, "
			+ "redstoneKeys=true, levers=false]",
			new SecretWaypointClickFeedbackRules.Selection(true, false, true, false, true, false).toString(),
			"the Java record keeps its standard component-form string representation");
		assertStaticMethod("fromCandidateLabel", SecretWaypointClickFeedbackRules.Type.class, String.class);
		assertStaticMethod("selected", boolean.class,
			SecretWaypointClickFeedbackRules.Type.class, SecretWaypointClickFeedbackRules.Selection.class);
		assertStaticMethod("clickAction", SecretWaypointClickFeedbackRules.Action.class,
			SecretWaypointClickFeedbackRules.Type.class, SecretWaypointClickFeedbackRules.Selection.class,
			boolean.class, boolean.class);
		assertStaticMethod("isSecretBlock", boolean.class, SecretWaypointClickFeedbackRules.Type.class);
		assertStaticMethod("requiresPickupConfirmation", boolean.class, SecretWaypointClickFeedbackRules.Type.class);
		assertStaticMethod("confirmsPickupPacket", boolean.class,
			int.class, int.class, boolean.class, SecretWaypointClickFeedbackRules.Type.class);
		assertStaticMethod("confirmsPendingItemPickup", boolean.class,
			int.class, int.class, int.class, int.class, SecretWaypointClickFeedbackRules.Type.class, boolean.class);
		assertStaticMethod("confirmsBatDeathSound", boolean.class, String.class);
		assertStaticMethod("pickupConfirmed", boolean.class,
			SecretWaypointClickFeedbackRules.Type.class, boolean.class, int.class, int.class, boolean.class);
		assertStaticMethod("deadlineNanos", long.class, long.class, int.class);
		assertStaticMethod("confirmationWindowTicks", int.class, int.class);
		assertStaticMethod("expired", boolean.class, long.class, long.class);
	}

	private static void assertStaticMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			Method method = SecretWaypointClickFeedbackRules.class.getDeclaredMethod(name, parameterTypes);
			assertEquals(returnType, method.getReturnType(), name + " keeps its Java return type");
			assertTrue(Modifier.isPublic(method.getModifiers()), name + " remains public");
			assertTrue(Modifier.isStatic(method.getModifiers()), name + " remains static");
		} catch (NoSuchMethodException error) {
			throw new AssertionError("the Java-facing click-feedback method " + name + " remains present", error);
		}
	}

	private static void checkEditorScreenConstructorAbi() {
		assertTrue(Modifier.isFinal(SecretWaypointEditorScreen.class.getModifiers()),
			"the Java-facing waypoint editor screen remains final");
		try {
			assertTrue(Modifier.isPublic(SecretWaypointEditorScreen.class
				.getConstructor(Screen.class, String.class, List.class).getModifiers()),
				"the Java-facing waypoint editor constructor remains public");
		} catch (NoSuchMethodException error) {
			throw new AssertionError("the waypoint editor constructor keeps its Java-facing parameter types", error);
		}
	}

	private static void checkCategoryVisibility() {
		var allEnabled = new SecretWaypointClickFeedbackRules.Selection(true, true, true, true, true, true);
		var allDisabled = new SecretWaypointClickFeedbackRules.Selection(false, false, false, false, false, false);
		var categories = List.of(
			new CategoryFixture("chest", SecretWaypointCategoryRules.Category.CHEST),
			new CategoryFixture("item", SecretWaypointCategoryRules.Category.ITEM),
			new CategoryFixture("essence", SecretWaypointCategoryRules.Category.ESSENCE),
			new CategoryFixture("bat", SecretWaypointCategoryRules.Category.BAT),
			new CategoryFixture("redstone", SecretWaypointCategoryRules.Category.REDSTONE_KEY),
			new CategoryFixture("lever", SecretWaypointCategoryRules.Category.LEVER));
		List<SecretWaypointStore.Waypoint> markers = categories.stream()
			.map(fixture -> roomMarker(fixture.idCategory(), fixture.idCategory())).toList();
		for (int disabledIndex = 0; disabledIndex < categories.size(); disabledIndex++) {
			boolean[] enabled = {true, true, true, true, true, true};
			enabled[disabledIndex] = false;
			var selection = new SecretWaypointClickFeedbackRules.Selection(enabled[0], enabled[1],
				enabled[2], enabled[3], enabled[4], enabled[5]);
			assertFalse(SecretWaypointCategoryRules.visible(markers.get(disabledIndex).id(), selection),
				"disabling " + categories.get(disabledIndex).expected() + " hides that built-in room marker");
			String candidateLabel = candidateLabel(categories.get(disabledIndex).idCategory());
			assertFalse(SecretWaypointCategoryRules.candidateVisible(candidateLabel, selection),
				"disabling " + categories.get(disabledIndex).expected() + " prunes its already-discovered candidate");
			for (int otherIndex = 0; otherIndex < markers.size(); otherIndex++) {
				if (otherIndex == disabledIndex) continue;
				assertTrue(SecretWaypointCategoryRules.visible(markers.get(otherIndex).id(), selection),
					"disabling one category preserves enabled " + categories.get(otherIndex).expected() + " markers");
			}
		}
		var itemsOff = new SecretWaypointClickFeedbackRules.Selection(true, false, true, true, true, true);
		assertFalse(SecretWaypointCategoryRules.candidateVisible("Item frame candidate", itemsOff),
			"the Dropped Items toggle prunes a previously discovered item-frame candidate");
		assertFalse(SecretWaypointClickFeedbackRules.selected(SecretWaypointClickFeedbackRules.Type.ITEM_FRAME, itemsOff),
			"item-frame click feedback follows the Dropped Items toggle");
		assertTrue(SecretWaypointCategoryRules.candidateVisible("Item frame candidate", allEnabled),
			"item-frame candidates remain available while Dropped Items is enabled");
		var essenceOff = new SecretWaypointClickFeedbackRules.Selection(true, true, false, true, true, true);
		assertEquals(null, SecretWaypointCategoryRules.itemCandidateLabel("ancient essence", essenceOff),
			"the generic dropped-item switch cannot bypass a disabled essence category");
		var redstoneOff = new SecretWaypointClickFeedbackRules.Selection(true, true, true, true, false, true);
		assertEquals(null, SecretWaypointCategoryRules.itemCandidateLabel("redstone key", redstoneOff),
			"the generic dropped-item switch cannot bypass a disabled redstone-key category");
		assertEquals("Dropped item candidate", SecretWaypointCategoryRules.itemCandidateLabel("unclassified drop", allEnabled),
			"unclassified item candidates continue to follow the broad dropped-item toggle");
		var genericOff = new SecretWaypointClickFeedbackRules.Selection(true, false, true, true, true, true);
		assertEquals("Essence drop candidate", SecretWaypointCategoryRules.itemCandidateLabel("ancient essence", genericOff),
			"enabled essence candidates do not depend on the broad dropped-item toggle");
		for (int index = 0; index < categories.size(); index++) {
			assertEquals(categories.get(index).expected(), SecretWaypointCategoryRules.categoryOf(markers.get(index).id()),
				"the encoded room-catalog ID identifies " + categories.get(index).expected());
			assertTrue(SecretWaypointCategoryRules.visible(markers.get(index).id(), allEnabled),
				"enabled " + categories.get(index).expected() + " markers remain visible");
			assertFalse(SecretWaypointCategoryRules.visible(markers.get(index).id(), allDisabled),
				"all-disabled selection hides " + categories.get(index).expected() + " markers");
		}
		SecretWaypointStore.Waypoint renamedOverride = roomMarker("chest", "Player renamed this marker");
		assertEquals(SecretWaypointCategoryRules.Category.CHEST,
			SecretWaypointCategoryRules.categoryOf(renamedOverride.id()),
			"renaming a local override does not discard its stable built-in category");
		assertFalse(SecretWaypointCategoryRules.visible(renamedOverride.id(), allDisabled),
			"a renamed built-in override still follows its category toggle");
		SecretWaypointStore.Waypoint personal = new SecretWaypointStore.Waypoint(
			"personal:123e4567-e89b-12d3-a456-426614174000", "F1", "cell:0,0", "Chest", 0, 70, 0, -1);
		SecretWaypointStore.Waypoint unknownFixed = new SecretWaypointStore.Waypoint(
			"catalog:custom", "F1", "cell:0,0", "Chest", 0, 70, 0, -1);
		assertTrue(SecretWaypointCategoryRules.visible(personal.id(), allDisabled),
			"custom personal markers remain visible even when their names resemble a category");
		assertTrue(SecretWaypointCategoryRules.visible(unknownFixed.id(), allDisabled),
			"untyped legacy or user-authored built-ins remain visible");
		assertTrue(SecretWaypointCategoryRules.visible(roomMarker("item", "Custom item name").id(), allEnabled),
			"built-in category matching does not depend on the editable display name");
	}

	private static void checkPendingPickupResolution() {
		var type = SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY;
		assertTrue(SecretWaypointClickFeedbackRules.confirmsPendingItemPickup(7, 7, 41, 41, type, true),
			"the local player's packet can confirm the exact clicked item after vanilla removes its entity");
		assertFalse(SecretWaypointClickFeedbackRules.confirmsPendingItemPickup(8, 7, 41, 41, type, true),
			"another player's item pickup cannot confirm the local clicked marker");
		assertFalse(SecretWaypointClickFeedbackRules.confirmsPendingItemPickup(7, 7, 42, 41, type, true),
			"a different item entity cannot confirm the pending clicked marker");
		assertFalse(SecretWaypointClickFeedbackRules.confirmsPendingItemPickup(7, 7, 41, 41,
			SecretWaypointClickFeedbackRules.Type.BAT, true),
			"an item pickup packet cannot confirm a bat candidate");
		assertFalse(SecretWaypointClickFeedbackRules.confirmsPendingItemPickup(7, 7, 41, 41, type, false),
			"a removed entity without a retained collection target remains unconfirmed");
	}

	private static SecretWaypointStore.Waypoint roomMarker(String category, String name) {
		String id = SecretWaypointRoomCatalog.instanceMarkerId("F1", 0, 0,
			"devonian:category-fixture:" + category + ":0");
		assertTrue(id != null, "the room marker fixture ID is valid");
		return new SecretWaypointStore.Waypoint(id, "F1", "cell:0,0", name, 0, 70, 0, -1);
	}

	private static String candidateLabel(String category) {
		return switch (category) {
			case "chest" -> "Chest candidate";
			case "item" -> "Dropped item candidate";
			case "essence" -> "Essence drop candidate";
			case "bat" -> "Bat candidate";
			case "redstone" -> "Redstone key candidate";
			case "lever" -> "Lever candidate";
			default -> throw new AssertionError("unknown test category " + category);
		};
	}

	private record CategoryFixture(String idCategory, SecretWaypointCategoryRules.Category expected) { }
	private record ExpectedDevonianCatalogResult(boolean available,
		List<DungeonWaypointCatalog.Marker> markers, int roomCount,
		Map<String, List<DungeonWaypointCatalog.Marker>> byRoom, String reason) { }

	private static void checkRoomRelativeCatalog() {
		assertJavaStaticMethod(SecretWaypointRoomCatalog.class, "materialize", List.class,
			String.class, List.class, String.class, DungeonRoomOrientationRules.Candidate.class, List.class);
		assertJavaStaticMethod(SecretWaypointRoomCatalog.class, "instanceMarkerId", String.class,
			String.class, int.class, int.class, String.class);
		assertJavaStaticMethod(SecretWaypointRoomCatalog.class, "isRoomInstanceMarkerId", boolean.class, String.class);
		assertJavaStaticMethod(SecretWaypointRoomCatalog.class, "roomInstanceIdentity",
			SecretWaypointRoomCatalog.RoomInstanceIdentity.class, String.class);
		assertJavaStaticMethod(SecretWaypointRoomCatalog.class, "markerCategory", String.class, String.class);
		assertRecordComponents(SecretWaypointRoomCatalog.RoomInstanceIdentity.class,
			new Class<?>[] {DungeonFloor.class, String.class, String.class},
			"floor", "anchorRoomId", "templateRecordId");
		var nullIdentity = new SecretWaypointRoomCatalog.RoomInstanceIdentity(null, null, null);
		assertEquals(null, nullIdentity.floor(), "the Java identity record continues to accept a null floor component");
		assertEquals(null, nullIdentity.anchorRoomId(), "the Java identity record continues to accept a null anchor component");
		assertEquals(null, nullIdentity.templateRecordId(), "the Java identity record continues to accept a null template component");
		var nullIdentityCopy = new SecretWaypointRoomCatalog.RoomInstanceIdentity(null, null, null);
		assertEquals(nullIdentityCopy, nullIdentity, "room identities retain record value equality");
		assertEquals(nullIdentityCopy.hashCode(), nullIdentity.hashCode(), "equal room identities keep equal hash codes");
		assertEquals("RoomInstanceIdentity[floor=null, anchorRoomId=null, templateRecordId=null]",
			nullIdentity.toString(), "room identities keep the Java record string format");

		DungeonWaypointCatalog.CatalogResult catalog = DungeonWaypointCatalog.bundled();
		assertRecordComponents(DungeonWaypointCatalog.Marker.class,
			new Class<?>[] {String.class, String.class, String.class, int.class, int.class, int.class},
			"id", "roomName", "category", "x", "y", "z");
		assertKotlinValueShape(DungeonWaypointCatalog.CatalogResult.class,
			new Class<?>[] {boolean.class, List.class, int.class, Map.class, String.class},
			"available", "markers", "roomCount", "byRoom", "reason");
		assertJavaStaticMethod(DungeonWaypointCatalog.class, "bundled",
			DungeonWaypointCatalog.CatalogResult.class);
		assertJavaStaticMethod(DungeonWaypointCatalog.class, "parse",
			DungeonWaypointCatalog.CatalogResult.class, String.class);
		try {
			DungeonWaypointCatalog.CatalogResult.class.getMethod("find", String.class, String.class);
		} catch (NoSuchMethodException error) {
			throw new AssertionError("Devonian catalog lookup keeps its Java API", error);
		}
		assertEquals(null, new DungeonWaypointCatalog.Marker(null, null, null, 0, 0, 0).roomName(),
			"the Java record boundary still permits null marker components");
		assertTrue(Modifier.isPublic(DungeonWaypointCatalog.CatalogResult.class.getModifiers())
			&& Modifier.isStatic(DungeonWaypointCatalog.CatalogResult.class.getModifiers()),
			"the Kotlin catalog value remains public and statically nested");
		var fixtureMarker = new DungeonWaypointCatalog.Marker("fixture:chest:0", "Fixture", "chest", 1, 70, 2);
		var sourceMarkers = new ArrayList<>(List.of(fixtureMarker));
		var sourceRoomMarkers = new ArrayList<>(List.of(fixtureMarker));
		var sourceByRoom = new LinkedHashMap<String, List<DungeonWaypointCatalog.Marker>>();
		sourceByRoom.put("fixture", sourceRoomMarkers);
		var value = new DungeonWaypointCatalog.CatalogResult(true, sourceMarkers, 1, sourceByRoom, null);
		var expectedValue = new ExpectedDevonianCatalogResult(true, List.of(fixtureMarker), 1,
			Map.of("fixture", List.of(fixtureMarker)), "");
		var equivalentValue = new DungeonWaypointCatalog.CatalogResult(true, List.of(fixtureMarker), 1,
			Map.of("fixture", List.of(fixtureMarker)), "");
		sourceMarkers.clear();
		sourceRoomMarkers.clear();
		sourceByRoom.clear();
		assertEquals(List.of(fixtureMarker), value.markers(), "Devonian catalog result snapshots its marker list");
		assertEquals(Map.of("fixture", List.of(fixtureMarker)), value.byRoom(), "Devonian catalog result snapshots each room list and index");
		assertTrue(value.equals(equivalentValue) && equivalentValue.equals(value)
			&& value.hashCode() == expectedValue.hashCode()
			&& value.toString().equals(expectedValue.toString().replaceFirst("^ExpectedDevonianCatalogResult", "CatalogResult")),
			"Devonian catalog result retains record-style equality, hash, and text behavior");
		var emptyValue = new DungeonWaypointCatalog.CatalogResult(false, null, 0, null, null);
		assertEquals(List.of(), emptyValue.markers(), "null catalog markers normalize to an empty immutable list");
		assertEquals(Map.of(), emptyValue.byRoom(), "null room indexes normalize to an empty immutable map");
		assertEquals("", emptyValue.reason(), "null catalog reasons normalize to an empty string");
		assertTrue(catalog.available(), "the bundled Devonian room waypoint resource passes its bounded loader");
		assertEquals(121, catalog.roomCount(), "all verified source joins and the Blaze split are bundled");
		assertEquals(452, catalog.markers().size(), "all supported category coordinates are retained");
		assertEquals(108L, catalog.markers().stream().filter(marker -> marker.category().equals("chest"))
			.map(DungeonWaypointCatalog.Marker::roomName).distinct().count(),
			"chest catalog covers the source rooms that have chest coordinates");
		assertEquals(238L, catalog.markers().stream().filter(marker -> marker.category().equals("chest")).count(),
			"chest records use the shared source catalog");
		assertEquals(74L, catalog.markers().stream().filter(marker -> marker.category().equals("item")).count(),
			"item records use the independent item category");
		assertEquals(35L, catalog.markers().stream().filter(marker -> marker.category().equals("essence")).count(),
			"essence records remain distinct from redstone keys");
		assertEquals(49L, catalog.markers().stream().filter(marker -> marker.category().equals("bat")).count(),
			"bat records use the independent bat category");
		assertEquals(4L, catalog.markers().stream().filter(marker -> marker.category().equals("redstone")).count(),
			"the source's four redstone-key records remain distinct");
		assertEquals(52L, catalog.markers().stream().filter(marker -> marker.category().equals("lever")).count(),
			"lever records use the independent lever category");
		assertEquals(new DungeonWaypointCatalog.Marker("devonian:waterfall:chest:0",
			"Waterfall", "chest", 34, 84, 23), catalog.find(" waterfall ", "chest").getFirst(),
			"room lookup keeps the pinned canonical source coordinate");
		assertTrue(catalog.find("Criss-Cross", "chest").contains(new DungeonWaypointCatalog.Marker(
			"devonian:criss-cross:chest:0", "Criss-Cross", "chest", 15, 66, 6))
			&& catalog.find("Criss-Cross", "lever").contains(new DungeonWaypointCatalog.Marker(
				"devonian:criss-cross:lever:0", "Criss-Cross", "lever", 15, 75, 29)),
			"the punctuation-only Devonian Criss Cross name maps to the unique local Criss-Cross room");
		assertEquals(List.of(new DungeonWaypointCatalog.Marker("devonian:silver-sword:chest:0",
			"Silver Sword", "chest", 18, 69, 13)), catalog.find("Silver Sword", "chest"),
			"the corroborated Silvers Sword alias retains the pinned chest coordinate");
		assertEquals(List.of(new DungeonWaypointCatalog.Marker("devonian:rail-track:chest:0",
			"Rail Track", "chest", 28, 63, 7)), catalog.find("Rail Track", "chest"),
			"the corroborated Mini Rail Track alias retains the pinned chest coordinate");
		assertEquals(List.of(new DungeonWaypointCatalog.Marker("devonian:rare-pillars:lever:0",
			"Rare Pillars", "lever", 24, 88, 3)), catalog.find("Rare Pillars", "lever"),
			"the corroborated Pillars alias retains the pinned lever coordinate");
		assertEquals(List.of(new DungeonWaypointCatalog.Marker("devonian:higher-blaze:chest:0",
			"Higher Blaze", "chest", 2, 68, 27)), catalog.find("Higher Blaze", "chest"),
			"the independent Higher Blaze record selects the exact lower-height chest coordinate");
		assertEquals(List.of(new DungeonWaypointCatalog.Marker("devonian:lower-blaze:chest:0",
			"Lower Blaze", "chest", 2, 118, 27)), catalog.find("Lower Blaze", "chest"),
			"the independent Lower Blaze record selects the exact upper-height chest coordinate");
		assertTrue(catalog.find("Blaze", "chest").isEmpty(),
			"the generic source Blaze name is not exposed as an ambiguous local room");
		assertTrue(catalog.find("Spider", "wither").isEmpty(),
			"unsupported category aliases are not introduced");

		String header = "{\"version\":1,\"sourceProject\":\"Synnerz/devonian\",\"license\":\"GPL-3.0\","
			+ "\"sourceRevision\":\"" + DungeonWaypointCatalog.SOURCE_REVISION + "\",\"waypoints\":[";
		String validMarker = "{\"id\":\"devonian:test-room:chest:0\",\"roomName\":\"Test Room\","
			+ "\"category\":\"chest\",\"x\":1,\"y\":70,\"z\":2}";
		assertTrue(DungeonWaypointCatalog.parse(header + validMarker + "]}").available(),
			"a bounded row with exact provenance and stable ID parses");
		assertFalse(DungeonWaypointCatalog.parse(header + validMarker.replace("\"x\":1", "\"x\":1.5") + "]}").available(),
			"fractional source coordinates are rejected");
		assertFalse(DungeonWaypointCatalog.parse(header + validMarker.replace("chest", "wither") + "]}").available(),
			"unknown source categories are rejected");
		assertFalse(DungeonWaypointCatalog.parse(header + validMarker + "," + validMarker + "]}").available(),
			"duplicate stable marker IDs are rejected");

		var firstCell = DungeonGridRules.cellAtOrigin(DungeonGridRules.GRID_MIN, DungeonGridRules.GRID_MIN);
		var secondCell = DungeonGridRules.cellAtOrigin(DungeonGridRules.GRID_MIN + DungeonGridRules.CELL_STRIDE,
			DungeonGridRules.GRID_MIN);
		var thirdCell = DungeonGridRules.cellAtOrigin(DungeonGridRules.GRID_MIN + DungeonGridRules.CELL_STRIDE * 2,
			DungeonGridRules.GRID_MIN);
		var fourthCell = DungeonGridRules.cellAtOrigin(DungeonGridRules.GRID_MIN + DungeonGridRules.CELL_STRIDE * 3,
			DungeonGridRules.GRID_MIN);
		var orientation = new DungeonRoomOrientationRules.Candidate(0, firstCell.baseX(), 70, firstCell.baseZ());
		var materialized = SecretWaypointRoomCatalog.materialize("F1",
			List.of(firstCell, secondCell, thirdCell, fourthCell), "Waterfall", orientation,
			catalog.find("Waterfall", "chest"));
		assertEquals(5, materialized.size(), "canonical coordinates materialize only inside the detected footprint");
		assertEquals("cell:1,0", materialized.getFirst().roomId(), "multi-cell coordinates retain their physical cell identity");
		assertEquals("Chest", materialized.getFirst().name(), "category labels remain editable waypoint names");
		var identity = SecretWaypointRoomCatalog.roomInstanceIdentity(materialized.getFirst().id());
		assertTrue(identity != null,
			"materialized marker IDs retain per-floor room-cell identity for saved edits");
		assertEquals(DungeonFloor.parse("F1"), identity.floor(), "room identity preserves its floor");
		assertEquals("cell:0,0", identity.anchorRoomId(), "room identity preserves its canonical anchor cell");
		assertEquals("devonian:waterfall:chest:0", identity.templateRecordId(),
			"room identity preserves the exact source template record");
		var redstone = new DungeonWaypointCatalog.Marker("devonian:waterfall:redstone:0",
			"Waterfall", "redstone", 34, 84, 23);
		assertEquals("Redstone Key", SecretWaypointRoomCatalog.materialize("F1",
			List.of(firstCell, secondCell, thirdCell, fourthCell), "Waterfall", orientation, List.of(redstone))
			.getFirst().name(), "Devonian redstone markers use the existing redstone-key label");
		assertTrue(SecretWaypointRoomCatalog.materialize("F1", List.of(firstCell), "Waterfall", orientation,
			catalog.find("Waterfall", "chest")).isEmpty(), "coordinates outside the footprint fail closed");
	}

	private static void checkBundledCatalog() {
		assertJavaStaticMethod(SecretWaypointCatalogCodec.class, "decode",
			SecretWaypointCatalogCodec.DecodeResult.class, String.class);
		String validEntry = "{\"id\":\"catalog:crypt\",\"floor\":\"F1\",\"roomId\":\"cell:1,2\","
			+ "\"name\":\"Crypt chest\",\"x\":-167,\"y\":70,\"z\":-135,\"color\":-1}";
		SecretWaypointCatalogCodec.DecodeResult parsed = SecretWaypointCatalogCodec.decode(
			"{\"version\":1,\"waypoints\":[" + validEntry + "]}");
		assertTrue(parsed.success(), "a valid built-in marker catalog loads");
		assertKotlinValueShape(SecretWaypointCatalogCodec.DecodeResult.class,
			new Class<?>[] {boolean.class, List.class, String.class}, "success", "waypoints", "error");
		var normalized = new SecretWaypointCatalogCodec.DecodeResult(false, null, null);
		assertEquals(List.of(), normalized.waypoints(), "catalog value normalizes a null marker list");
		assertEquals("", normalized.error(), "catalog value normalizes a null error");
		var normalizedEquivalent = new SecretWaypointCatalogCodec.DecodeResult(false, List.of(), "");
		assertEquals(normalizedEquivalent, normalized, "normalized catalog values compare by their stored components");
		assertEquals(normalizedEquivalent.hashCode(), normalized.hashCode(),
			"equal normalized catalog values have equal hash codes");
		assertEquals("DecodeResult[success=false, waypoints=[], error=]", normalized.toString(),
			"the Kotlin value keeps the former Java record string format");
		List<SecretWaypointStore.Waypoint> mutableWaypoints = new ArrayList<>(parsed.waypoints());
		var copied = new SecretWaypointCatalogCodec.DecodeResult(true, mutableWaypoints, null);
		mutableWaypoints.clear();
		assertEquals(parsed.waypoints(), copied.waypoints(), "catalog value defensively copies its marker list");
		assertUnsupported(() -> copied.waypoints().clear(), "catalog value marker lists remain immutable");
		var copiedEquivalent = new SecretWaypointCatalogCodec.DecodeResult(true, parsed.waypoints(), "");
		assertEquals(copiedEquivalent, copied, "copied catalog values compare by their normalized components");
		assertEquals(copiedEquivalent.hashCode(), copied.hashCode(), "copied catalog value hash codes remain stable");
		assertEquals(new SecretWaypointStore.Waypoint("catalog:crypt", "F1", "cell:1,2",
			"Crypt chest", -167, 70, -135, -1), parsed.waypoints().getFirst(),
			"catalog markers preserve their authored floor, room cell, and world coordinates");
		assertTrue(SecretWaypointCatalogCodec.decode("{\"version\":1,\"waypoints\":[]}").success(),
			"an empty but valid catalog loads");
		assertFalse(SecretWaypointCatalogCodec.decode("{\"version\":1,\"waypoints\":[" + validEntry
			.replace("cell:1,2", "cell:1,1") + "]}").success(),
			"catalog coordinates must map to their declared dungeon-grid cell");
		assertFalse(SecretWaypointCatalogCodec.decode("{\"version\":1,\"waypoints\":[" + validEntry
			.replace("catalog:crypt", "personal:123e4567-e89b-12d3-a456-426614174000") + "]}").success(),
			"bundled entries must use the catalog ID namespace");
		assertFalse(SecretWaypointCatalogCodec.decode("{\"version\":1.5,\"waypoints\":[]}").success(),
			"catalog versions must be integers");
		assertFalse(SecretWaypointCatalogCodec.decode("{\"version\":1,\"waypoints\":[],\"extra\":true}").success(),
			"unsupported catalog fields are rejected");

		try (InputStream resource = SecretWaypointChecks.class.getResourceAsStream(
			"/data/geileraddons/secret-waypoints.json")) {
			assertTrue(resource != null, "the packaged built-in catalog resource exists");
			byte[] bytes = resource.readNBytes(ClientJsonFile.MAX_PACKAGE_BYTES + 1);
			assertTrue(bytes.length <= ClientJsonFile.MAX_PACKAGE_BYTES, "the packaged catalog stays within the input bound");
			SecretWaypointCatalogCodec.DecodeResult bundled = SecretWaypointCatalogCodec.decode(
				new String(bytes, StandardCharsets.UTF_8));
			assertTrue(bundled.success(), "the packaged catalog passes the runtime loader");
			assertEquals(0, bundled.waypoints().size(), "the bundled catalog contains no unverified coordinates");
		} catch (IOException error) {
			throw new AssertionError("the packaged catalog can be read", error);
		}
	}

	private static void checkConfirmedRoomSignal() {
		assertTrue(SecretWaypointCollectionRules.confirmsAllRoomSecrets(DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN),
			"the green map checkmark confirms completion for room markers");
		assertFalse(SecretWaypointCollectionRules.confirmsAllRoomSecrets(DungeonMapGeometry.RoomClearStatus.CLEARED_WHITE),
			"a white cleared-room checkmark does not confirm that all secrets were found");
		assertFalse(SecretWaypointCollectionRules.confirmsAllRoomSecrets(DungeonMapGeometry.RoomClearStatus.UNCLEARED),
			"an uncleared room does not confirm marker collection");
		assertFalse(SecretWaypointCollectionRules.confirmsAllRoomSecrets(DungeonMapGeometry.RoomClearStatus.UNKNOWN),
			"unknown map state does not confirm marker collection");
		assertFalse(SecretWaypointClickFeedbackRules.pickupConfirmed(
			SecretWaypointClickFeedbackRules.Type.BAT, true, 0, 0, false),
			"bat despawn alone is not confirmed death or collection");
		assertFalse(SecretWaypointClickFeedbackRules.pickupConfirmed(
			SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM, true, 1, 1, false),
			"dropped-item despawn alone is not confirmed pickup");
		assertTrue(SecretWaypointClickFeedbackRules.confirmsPickupPacket(4, 4, true,
			SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM), "local-player pickup packet confirms dropped-item pickup");
		assertFalse(SecretWaypointClickFeedbackRules.confirmsPickupPacket(5, 4, true,
			SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM), "another player's pickup packet cannot confirm this client marker");
		assertFalse(SecretWaypointClickFeedbackRules.confirmsPickupPacket(4, 4, false,
			SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM), "a non-item entity cannot confirm item pickup");
		assertFalse(SecretWaypointClickFeedbackRules.confirmsPickupPacket(4, 4, true,
			SecretWaypointClickFeedbackRules.Type.BAT), "bat collection does not use item pickup packets");
		assertTrue(SecretWaypointClickFeedbackRules.confirmsBatDeathSound("minecraft:entity.bat.death"),
			"the exact bat-death sound confirms bat collection");
		assertFalse(SecretWaypointClickFeedbackRules.confirmsBatDeathSound("minecraft:entity.bat.hurt"),
			"a bat hurt sound does not confirm bat collection");
	}

	private static void checkPackageRoundTripAndBounds() {
		SecretWaypointStore.Waypoint personal = new SecretWaypointStore.Waypoint(
			"personal:123e4567-e89b-12d3-a456-426614174000", "F1", "cell:1,2", "Crypt chest", -167, 70, -135, 0xFF38CAFF);
		SecretWaypointStore.WaypointOverride hidden = new SecretWaypointStore.WaypointOverride("catalog:crypt", true, null);
		SecretWaypointStore.Waypoint replacement = new SecretWaypointStore.Waypoint(
			"catalog:visible", "F1", "cell:1,2", "Visible replacement", -167, 70, -135, 0xFF38CAFF);
		SecretWaypointStore.WaypointOverride visible =
			new SecretWaypointStore.WaypointOverride(replacement.id(), false, replacement);
		SecretWaypointStore.CollectedWaypoint collected = new SecretWaypointStore.CollectedWaypoint(
			"F1", "cell:1,2", personal.id());
		String encoded = SecretWaypointPackageCodec.encode(List.of(personal), List.of(hidden, visible), List.of(collected));
		assertTrue(encoded != null, "a valid waypoint package encodes within the output bound");
		SecretWaypointPackageCodec.DecodeResult decoded = SecretWaypointPackageCodec.decode(encoded);
		assertTrue(decoded.success(), "an exported waypoint package decodes");
		assertEquals(List.of(personal), decoded.personal(), "personal marker coordinates survive the package round trip");
		assertEquals(List.of(hidden, visible), decoded.overrides(), "hidden and replacement overrides survive the package round trip");
		assertEquals(List.of(collected), decoded.collected(), "confirmed collection state survives the package round trip");
		String emptyVersionOnePackage = "{\n  \"version\": 1,\n  \"personal\": [],\n  \"overrides\": [],\n  \"collected\": []\n}";
		assertEquals(emptyVersionOnePackage, SecretWaypointPackageCodec.encode(List.of(), List.of(), List.of()),
			"the empty package retains the exact version-one JSON bytes and field order");
		assertTrue(SecretWaypointPackageCodec.decode(emptyVersionOnePackage).success(),
			"version-one package exports remain readable by the import decoder");
		JsonObject packageJson = JsonParser.parseString(encoded).getAsJsonObject();
		assertEquals(1, packageJson.get("version").getAsInt(),
			"non-empty package exports keep the version-one package schema");
		assertEquals(Set.of("version", "personal", "overrides", "collected"), packageJson.keySet(),
			"package exports retain the four version-one top-level field names");
		assertEquals(Set.of("id", "floor", "roomId", "name", "x", "y", "z", "color"),
			packageJson.getAsJsonArray("personal").get(0).getAsJsonObject().keySet(),
			"package waypoint records retain their established field names");
		JsonObject overrideJson = packageJson.getAsJsonArray("overrides").get(0).getAsJsonObject();
		assertEquals(Set.of("id", "hidden", "replacement"), overrideJson.keySet(),
			"package overrides retain the explicit replacement field");
		assertTrue(overrideJson.get("replacement").isJsonNull(),
			"package serialization continues to include null replacements");
		assertEquals(Set.of("id", "floor", "roomId", "name", "x", "y", "z", "color"),
			packageJson.getAsJsonArray("overrides").get(1).getAsJsonObject().getAsJsonObject("replacement").keySet(),
			"replacement override waypoints preserve their serialized field names");
		assertEquals(Set.of("floor", "roomId", "markerId"),
			packageJson.getAsJsonArray("collected").get(0).getAsJsonObject().keySet(),
			"package collection records retain their established field names");
		checkPackageCodecAbi(decoded);
		assertFalse(SecretWaypointPackageCodec.decode("[]").success(), "the package root must be a JSON object");
		assertEquals("Waypoint JSON is missing or too large.", SecretWaypointPackageCodec.decode(null).error(),
			"missing package input keeps its established error message");
		assertEquals("Waypoint JSON must be an object.", SecretWaypointPackageCodec.decode("[]").error(),
			"non-object package roots keep their established error message");
		assertEquals("Waypoint JSON has an unsupported shape or numeric value.",
			SecretWaypointPackageCodec.decode("{}").error(), "invalid raw schema shapes keep their established error message");
		assertEquals("Waypoint JSON is invalid.", SecretWaypointPackageCodec.decode("{").error(),
			"bounded-parser failures keep their established error message");
		assertFalse(SecretWaypointPackageCodec.decode(encoded.replace("\"x\": -167", "\"x\": 4294967129")).success(),
			"coordinates outside the integer range are rejected before deserialization");
		assertFalse(SecretWaypointPackageCodec.decode("{" + " ".repeat(ClientJsonFile.MAX_PACKAGE_BYTES) + "}").success(),
			"oversized pasted JSON is rejected before schema parsing");
		String oversizedUtf8 = "\"" + "é".repeat(ClientJsonFile.MAX_PACKAGE_BYTES / 2) + "\"";
		assertTrue(oversizedUtf8.length() < ClientJsonFile.MAX_PACKAGE_BYTES,
			"the UTF-8 package-bound fixture remains below the character limit");
		assertEquals("Waypoint JSON is missing or too large.", SecretWaypointPackageCodec.decode(oversizedUtf8).error(),
			"the input bound is enforced on encoded UTF-8 bytes as well as Java characters");
		String tooDeep = "[".repeat(9) + "0" + "]".repeat(9);
		assertEquals("Waypoint JSON is invalid.", SecretWaypointPackageCodec.decode(tooDeep).error(),
			"the bounded JSON parser keeps its nesting-depth limit");
		String tooManyNodes = "[" + "0,".repeat(99_999) + "0]";
		assertTrue(tooManyNodes.length() <= ClientJsonFile.MAX_PACKAGE_BYTES,
			"the JSON-node-bound fixture remains below the byte limit");
		assertEquals("Waypoint JSON is invalid.", SecretWaypointPackageCodec.decode(tooManyNodes).error(),
			"the bounded JSON parser keeps its node-count limit");
		assertEquals("Waypoint JSON has an unsupported shape or numeric value.",
			SecretWaypointPackageCodec.decode("{\"version\":1,\"personal\":[],\"overrides\":[],\"collected\":[],\"extra\":true}").error(),
			"unknown top-level fields retain the established raw-shape error");
		assertEquals("Waypoint JSON has an unsupported schema or invalid entries.",
			SecretWaypointPackageCodec.decode("{\"version\":1,\"personal\":[{\"id\":\"catalog:custom\",\"floor\":\"F1\",\"roomId\":\"cell:0,0\",\"name\":\"Custom\",\"x\":-200,\"y\":70,\"z\":-200,\"color\":-1}],\"overrides\":[],\"collected\":[]}").error(),
			"semantically invalid entries retain the established schema-validation error");
		String unknownVersionOnePackage = "{\"version\":1,\"personal\":[],\"overrides\":["
			+ "{\"id\":\"future:override\",\"hidden\":true,\"replacement\":null}],\"collected\":["
			+ "{\"floor\":\"F1\",\"roomId\":\"cell:1,2\",\"markerId\":\"future:marker\"}]}";
		SecretWaypointPackageCodec.DecodeResult unknownEntries =
			SecretWaypointPackageCodec.decode(unknownVersionOnePackage);
		assertTrue(unknownEntries.success(),
			"the version-one codec preserves bounded catalog-unknown entries for the store to resolve");
		assertEquals("future:override", unknownEntries.overrides().getFirst().id(),
			"unknown package overrides are retained by decoding rather than silently rewritten");
		assertEquals("future:marker", unknownEntries.collected().getFirst().markerId(),
			"unknown package collection markers are retained by decoding rather than silently rewritten");
		assertFalse(SecretWaypointPackageCodec.encode(List.of(new SecretWaypointStore.Waypoint(
			personal.id(), "F1", "cell:2,2", personal.name(), -167, 70, -135, personal.color())), List.of(), List.of()) != null,
			"a personal marker cannot claim a cell different from its world coordinates");
		assertFalse(SecretWaypointPackageCodec.encode(List.of(personal), List.of(), List.of(
			new SecretWaypointStore.CollectedWaypoint("F1", "cell:1,3", personal.id()))) != null,
			"a collection record cannot claim a different room than its personal marker");
		assertFalse(SecretWaypointPackageCodec.encode(List.of(personal), List.of(), List.of(
			new SecretWaypointStore.CollectedWaypoint("F2", "cell:1,2", personal.id()))) != null,
			"a collection record cannot claim a different floor than its personal marker");
		assertFalse(SecretWaypointPackageCodec.encode(List.of(personal), List.of(), List.of(
			new SecretWaypointStore.CollectedWaypoint("F1", "cell:1,2", "personal:123e4567-e89b-12d3-a456-426614174001"))) != null,
			"a package cannot mark a missing personal waypoint as collected");
		assertFalse(SecretWaypointPackageCodec.validRoomId("cell:01,2"),
			"room-cell identifiers use one canonical representation");
		assertFalse(SecretWaypointPackageCodec.encode(java.util.Collections.nCopies(
			ClientJsonFile.MAX_ENTRIES + 1, personal), List.of(), List.of()) != null,
			"entry limits are enforced on export");
		assertEquals(null, SecretWaypointPackageCodec.encode(null, List.of(), List.of()),
			"null export collections retain the null result contract");
		List<SecretWaypointStore.Waypoint> nullMarkerEntry = new ArrayList<>();
		nullMarkerEntry.add(null);
		assertEquals(null, SecretWaypointPackageCodec.encode(nullMarkerEntry, List.of(), List.of()),
			"null collection entries retain the invalid-package result contract");
	}

	private static void checkPackageCodecAbi(SecretWaypointPackageCodec.DecodeResult decoded) {
		assertTrue(Modifier.isFinal(SecretWaypointPackageCodec.class.getModifiers()),
			"the Java-facing package codec remains final");
		assertPackageCodecMethod("encode", String.class, List.class, List.class, List.class);
		assertPackageCodecMethod("decode", SecretWaypointPackageCodec.DecodeResult.class, String.class);
		assertPackageCodecMethod("validWaypoint", boolean.class,
			SecretWaypointStore.Waypoint.class, boolean.class);
		assertPackageCodecMethod("validRoomId", boolean.class, String.class);
		assertPackageCodecMethod("matchesRoom", boolean.class, String.class, int.class, int.class);
		assertEquals(5, SecretWaypointPackageCodec.class.getDeclaredMethods().length,
			"the Java facade exposes only its two codec methods and three validation helpers");
		assertRecordComponents(SecretWaypointPackageCodec.DecodeResult.class,
			new Class<?>[] {boolean.class, List.class, List.class, List.class, String.class},
			"success", "personal", "overrides", "collected", "error");
		try {
			Method encode = SecretWaypointPackageCodec.class.getDeclaredMethod(
				"encode", List.class, List.class, List.class);
			Class<?>[] listElements = {SecretWaypointStore.Waypoint.class,
				SecretWaypointStore.WaypointOverride.class, SecretWaypointStore.CollectedWaypoint.class};
			for (int index = 0; index < listElements.length; index++) {
				assertListGenericType(encode.getGenericParameterTypes()[index], listElements[index],
					"the Java package export API keeps list generic parameter " + index);
			}
			RecordComponent[] components = SecretWaypointPackageCodec.DecodeResult.class.getRecordComponents();
			assertListGenericType(components[1].getGenericType(), SecretWaypointStore.Waypoint.class,
				"the package result keeps its personal-list generic component type");
			assertListGenericType(components[2].getGenericType(), SecretWaypointStore.WaypointOverride.class,
				"the package result keeps its override-list generic component type");
			assertListGenericType(components[3].getGenericType(), SecretWaypointStore.CollectedWaypoint.class,
				"the package result keeps its collection-list generic component type");
			var constructor = SecretWaypointPackageCodec.DecodeResult.class.getDeclaredConstructor(
				boolean.class, List.class, List.class, List.class, String.class);
			for (int index = 0; index < listElements.length; index++) {
				assertListGenericType(constructor.getGenericParameterTypes()[index + 1], listElements[index],
					"the Java package-result constructor keeps list generic parameter " + index);
			}
		} catch (NoSuchMethodException error) {
			throw new AssertionError("the package codec keeps its generic list signatures", error);
		}
		var nullable = new SecretWaypointPackageCodec.DecodeResult(false, null, null, null, null);
		assertEquals(null, nullable.personal(), "the package record continues to accept a null personal list");
		assertEquals(null, nullable.overrides(), "the package record continues to accept a null overrides list");
		assertEquals(null, nullable.collected(), "the package record continues to accept a null collected list");
		assertEquals(null, nullable.error(), "the package record continues to accept a null error");
		assertEquals("DecodeResult[success=false, personal=null, overrides=null, collected=null, error=null]",
			nullable.toString(), "the package record keeps its Java component-form string representation");
		assertTrue(SecretWaypointPackageCodec.validWaypoint(decoded.personal().getFirst(), true),
			"package validation helpers remain callable from the store code");
		assertTrue(SecretWaypointPackageCodec.validRoomId("cell:1,2"),
			"canonical room-cell validation remains available to callers");
		assertTrue(SecretWaypointPackageCodec.matchesRoom("cell:1,2", -167, -135),
			"world-coordinate room matching remains available to callers");
		assertFalse(SecretWaypointPackageCodec.matchesRoom("cell:2,2", -167, -135),
			"world-coordinate room matching keeps rejecting mismatched cells");
		assertUnsupported(() -> decoded.personal().clear(), "decoded personal markers remain immutable");
		assertUnsupported(() -> decoded.overrides().clear(), "decoded overrides remain immutable");
		assertUnsupported(() -> decoded.collected().clear(), "decoded collection records remain immutable");
		assertEquals("Waypoint JSON is missing or too large.", SecretWaypointPackageCodec.decode(null).error(),
			"the decode result record retains its public failure-message field");
	}

	private static void assertListGenericType(java.lang.reflect.Type genericType, Class<?> elementType, String message) {
		assertTrue(genericType instanceof ParameterizedType, message + " (parameterized type)");
		ParameterizedType listType = (ParameterizedType) genericType;
		assertEquals(List.class, listType.getRawType(), message + " (List raw type)");
		assertEquals(elementType, listType.getActualTypeArguments()[0], message + " (element type)");
	}

	private static void assertPackageCodecMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			Method method = SecretWaypointPackageCodec.class.getDeclaredMethod(name, parameterTypes);
			assertEquals(returnType, method.getReturnType(), name + " keeps its Java return type");
			assertTrue(Modifier.isPublic(method.getModifiers()), name + " remains public");
			assertTrue(Modifier.isStatic(method.getModifiers()), name + " remains static");
		} catch (NoSuchMethodException error) {
			throw new AssertionError("the Java-facing codec method " + name + " remains present", error);
		}
	}

	private static void checkStoreAbiAndPersistence() {
		checkStoreJavaAbi();
		checkRuntimeSnapshotNormalization();
		checkLocalFileSchemas();
		checkCopiedLocalStorePersistence();
	}

	private static void checkStoreJavaAbi() {
		assertTrue(Modifier.isFinal(SecretWaypointStore.class.getModifiers()),
			"the Java-facing store remains a final class");
		assertStoreMethod("load", void.class, true);
		assertStoreMethod("reloadFromDisk", boolean.class, true);
		assertStoreMethod("snapshotRuntimeState", SecretWaypointStore.RuntimeSnapshot.class, true);
		assertStoreMethod("beginProfileTransaction", void.class, true);
		assertStoreMethod("endProfileTransaction", void.class, true);
		assertStoreMethod("restoreRuntimeState", void.class, true, SecretWaypointStore.RuntimeSnapshot.class);
		assertStoreMethod("defaults", List.class, false);
		assertStoreMethod("personal", List.class, false);
		assertStoreMethod("roomTemplates", List.class, false);
		assertStoreMethod("catalogStatus", String.class, false);
		assertStoreMethod("canPersistChanges", boolean.class, false);
		assertStoreMethod("setRuntimeDefaults", void.class, true, List.class);
		assertStoreMethod("visibleFor", List.class, false, String.class, String.class);
		assertStoreMethod("editableForRooms", List.class, false, String.class, List.class);
		assertStoreMethod("addPersonal", String.class, true,
			String.class, String.class, String.class, int.class, int.class, int.class, int.class);
		assertStoreMethod("renamePersonal", boolean.class, true, String.class, String.class);
		assertStoreMethod("updatePersonal", boolean.class, true,
			String.class, String.class, String.class, String.class, int.class, int.class, int.class, int.class);
		assertStoreMethod("removePersonal", boolean.class, true, String.class);
		assertStoreMethod("hideDefault", boolean.class, true, String.class);
		assertStoreMethod("replaceDefault", boolean.class, true, String.class, SecretWaypointStore.Waypoint.class);
		assertStoreMethod("restoreDefault", boolean.class, true, String.class);
		assertStoreMethod("resetRoomOverrides", int.class, true, String.class, List.class);
		assertStoreMethod("isCollected", boolean.class, false, String.class, String.class, String.class);
		assertStoreMethod("markRoomsCollected", int.class, true, String.class, List.class);
		assertStoreMethod("exportPackage", String.class, false);
		assertStoreMethod("importPackage", SecretWaypointStore.ImportResult.class, true, String.class);
		assertEquals(26L, Arrays.stream(SecretWaypointStore.class.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()))
			.count(), "the Java-facing store exposes the same public static method count");

		assertRecordComponents(SecretWaypointStore.Waypoint.class,
			new Class<?>[] {String.class, String.class, String.class, String.class,
				int.class, int.class, int.class, int.class},
			"id", "floor", "roomId", "name", "x", "y", "z", "color");
		assertKotlinValueShape(SecretWaypointStore.RuntimeSnapshot.class,
			new Class<?>[] {List.class, List.class, List.class, List.class, Map.class, List.class,
				String.class, boolean.class, boolean.class},
			"defaults", "roomTemplates", "runtimeDefaults", "personal", "overrides", "collected",
			"catalogStatus", "persistenceBlocked", "loaded");
		assertRecordComponents(SecretWaypointStore.WaypointOverride.class,
			new Class<?>[] {String.class, boolean.class, SecretWaypointStore.Waypoint.class},
			"id", "hidden", "replacement");
		assertRecordComponents(SecretWaypointStore.CollectedWaypoint.class,
			new Class<?>[] {String.class, String.class, String.class},
			"floor", "roomId", "markerId");
		assertRecordComponents(SecretWaypointStore.EditableWaypoint.class,
			new Class<?>[] {SecretWaypointStore.Waypoint.class, boolean.class, boolean.class, boolean.class},
			"waypoint", "personal", "hidden", "collected");
		assertRecordComponents(SecretWaypointStore.ImportResult.class,
			new Class<?>[] {boolean.class, int.class, int.class, int.class, String.class},
			"success", "personal", "overrides", "collected", "error");
		var recordWaypoint = new SecretWaypointStore.Waypoint("id", "F1", "cell:0,0", "Name", 1, 2, 3, 4);
		assertEquals("Waypoint[id=id, floor=F1, roomId=cell:0,0, name=Name, x=1, y=2, z=3, color=4]",
			recordWaypoint.toString(), "waypoints keep the Java record string format");
		assertEquals(recordWaypoint, new SecretWaypointStore.Waypoint("id", "F1", "cell:0,0", "Name", 1, 2, 3, 4),
			"waypoints retain component-wise record equality");
		var importResult = new SecretWaypointStore.ImportResult(false, 0, 0, 0, "failure");
		assertEquals("ImportResult[success=false, personal=0, overrides=0, collected=0, error=failure]",
			importResult.toString(), "import results keep the Java record string format");

		assertEquals(1, new SecretWaypointStore.BundledFile().version,
			"the bundled DTO keeps its version-one default");
		assertEquals(2, new SecretWaypointStore.LocalFile().version,
			"the local DTO keeps its version-two writer default");
		assertGsonDtoShape(SecretWaypointStore.BundledFile.class,
			new String[] {"version", "waypoints"}, new Class<?>[] {int.class, List.class},
			new Class<?>[] {null, SecretWaypointStore.Waypoint.class});
		assertGsonDtoShape(SecretWaypointStore.LocalFile.class,
			new String[] {"version", "personal", "overrides", "collected"},
			new Class<?>[] {int.class, List.class, List.class, List.class},
			new Class<?>[] {null, SecretWaypointStore.Waypoint.class,
				SecretWaypointStore.WaypointOverride.class, SecretWaypointStore.CollectedWaypoint.class});
		assertTrue(new SecretWaypointStore.BundledFile().waypoints instanceof ArrayList,
			"the bundled DTO retains its mutable ArrayList default");
		assertTrue(new SecretWaypointStore.LocalFile().personal instanceof ArrayList
			&& new SecretWaypointStore.LocalFile().overrides instanceof ArrayList
			&& new SecretWaypointStore.LocalFile().collected instanceof ArrayList,
			"local DTO lists retain their mutable ArrayList defaults");
	}

	private static void checkRuntimeSnapshotNormalization() {
		var nullableStatus = new SecretWaypointStore.RuntimeSnapshot(List.of(), List.of(), List.of(), List.of(),
			Map.of(), List.of(), null, false, false);
		assertEquals(null, nullableStatus.catalogStatus(),
			"runtime snapshots preserve a null status component without normalization");
		SecretWaypointStore.Waypoint waypoint = new SecretWaypointStore.Waypoint(
			"personal:123e4567-e89b-12d3-a456-426614174000", "F1", "cell:1,2",
			"Snapshot marker", -167, 70, -135, 0xFF38CAFF);
		SecretWaypointStore.WaypointOverride override =
			new SecretWaypointStore.WaypointOverride("catalog:crypt", true, null);
		SecretWaypointStore.CollectedWaypoint collected =
			new SecretWaypointStore.CollectedWaypoint("F1", "cell:1,2", waypoint.id());
		DungeonWaypointCatalog.Marker template =
			new DungeonWaypointCatalog.Marker("devonian:test:chest:0", "Test", "chest", 1, 70, 2);
		List<SecretWaypointStore.Waypoint> defaults = new ArrayList<>(List.of(waypoint));
		List<DungeonWaypointCatalog.Marker> templates = new ArrayList<>(List.of(template));
		List<SecretWaypointStore.Waypoint> runtimeDefaults = new ArrayList<>(List.of(waypoint));
		List<SecretWaypointStore.Waypoint> personal = new ArrayList<>(List.of(waypoint));
		Map<String, SecretWaypointStore.WaypointOverride> overrides = new LinkedHashMap<>();
		overrides.put(override.id(), override);
		List<SecretWaypointStore.CollectedWaypoint> collectedValues = new ArrayList<>(List.of(collected));
		SecretWaypointStore.RuntimeSnapshot snapshot = new SecretWaypointStore.RuntimeSnapshot(
			defaults, templates, runtimeDefaults, personal, overrides, collectedValues,
			"snapshot", false, true);

		defaults.clear();
		templates.clear();
		runtimeDefaults.clear();
		personal.clear();
		overrides.clear();
		collectedValues.clear();
		assertEquals(List.of(waypoint), snapshot.defaults(), "snapshot defaults are copied at construction");
		assertEquals(List.of(template), snapshot.roomTemplates(), "snapshot templates are copied at construction");
		assertEquals(List.of(waypoint), snapshot.runtimeDefaults(), "runtime defaults are copied at construction");
		assertEquals(List.of(waypoint), snapshot.personal(), "personal points are copied at construction");
		assertEquals(Map.of(override.id(), override), snapshot.overrides(), "overrides are copied at construction");
		assertEquals(List.of(collected), snapshot.collected(), "collected records are copied at construction");
		assertUnsupported(() -> snapshot.defaults().clear(), "snapshot lists remain immutable");
		assertUnsupported(() -> snapshot.overrides().clear(), "snapshot maps remain immutable");
		var equivalent = new SecretWaypointStore.RuntimeSnapshot(
			List.of(waypoint), List.of(template), List.of(waypoint), List.of(waypoint),
			Map.of(override.id(), override), List.of(collected), "snapshot", false, true);
		assertEquals(equivalent, snapshot, "runtime snapshots retain component-wise value equality");
		assertEquals(equivalent.hashCode(), snapshot.hashCode(), "equal runtime snapshots have equal hash codes");
		assertEquals("RuntimeSnapshot[defaults=[" + waypoint + "], roomTemplates=[" + template + "], "
			+ "runtimeDefaults=[" + waypoint + "], personal=[" + waypoint + "], overrides={" + override.id()
			+ "=" + override + "}, collected=[" + collected + "], catalogStatus=snapshot, "
			+ "persistenceBlocked=false, loaded=true]", snapshot.toString(),
			"runtime snapshots retain the former Java record string format");
	}

	private static void checkLocalFileSchemas() {
		SecretWaypointStore.Waypoint waypoint = new SecretWaypointStore.Waypoint(
			"personal:123e4567-e89b-12d3-a456-426614174000", "F1", "cell:1,2",
			"Crypt chest", -167, 70, -135, 0xFF38CAFF);
		String dynamicMarkerId = roomMarker("chest", "Crypt chest").id();
		SecretWaypointStore.WaypointOverride hidden =
			new SecretWaypointStore.WaypointOverride(dynamicMarkerId, true, null);
		SecretWaypointStore.CollectedWaypoint collected =
			new SecretWaypointStore.CollectedWaypoint("F1", "cell:1,2", waypoint.id());
		SecretWaypointStore.LocalFile file = new SecretWaypointStore.LocalFile();
		file.personal = new ArrayList<>(List.of(waypoint));
		file.overrides = new ArrayList<>(List.of(hidden));
		file.collected = new ArrayList<>(List.of(collected));

		String json = ClientJsonFile.GSON.toJson(file);
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		assertEquals(Set.of("version", "personal", "overrides", "collected"), root.keySet(),
			"version-two local JSON keeps the same four top-level fields");
		assertEquals(2, root.get("version").getAsInt(), "local saves retain schema version two");
		JsonObject waypointJson = root.getAsJsonArray("personal").get(0).getAsJsonObject();
		assertEquals(Set.of("id", "floor", "roomId", "name", "x", "y", "z", "color"),
			waypointJson.keySet(), "waypoint records retain their serialized field names");
		JsonObject overrideJson = root.getAsJsonArray("overrides").get(0).getAsJsonObject();
		assertEquals(Set.of("id", "hidden"), overrideJson.keySet(),
			"hidden overrides keep Gson's omission of the null replacement field");
		JsonObject collectedJson = root.getAsJsonArray("collected").get(0).getAsJsonObject();
		assertEquals(Set.of("floor", "roomId", "markerId"), collectedJson.keySet(),
			"collected records keep their serialized field names");
		SecretWaypointStore.LocalFile roundTrip = ClientJsonFile.GSON.fromJson(json, SecretWaypointStore.LocalFile.class);
		assertEquals(List.of(waypoint), roundTrip.personal, "local personal records round-trip through Gson");
		assertEquals(List.of(hidden), roundTrip.overrides, "local overrides round-trip through Gson");
		assertEquals(List.of(collected), roundTrip.collected, "local collection state round-trips through Gson");
		assertTrue(validLocalFile(roundTrip), "a complete version-two local file passes store validation");

		String versionOneJson = "{\"version\":1,\"personal\":[],\"overrides\":[]}";
		SecretWaypointStore.LocalFile versionOne =
			ClientJsonFile.GSON.fromJson(versionOneJson, SecretWaypointStore.LocalFile.class);
		assertEquals(1, versionOne.version, "the version-one local format remains readable");
		assertTrue(validLocalFile(versionOne),
			"legacy version-one files remain valid when they omit collection state");
		String versionOneOverrideJson = "{\"version\":1,\"personal\":[],\"overrides\":["
			+ "{\"id\":\"" + dynamicMarkerId + "\",\"hidden\":false}]}";
		SecretWaypointStore.LocalFile versionOneOverride =
			ClientJsonFile.GSON.fromJson(versionOneOverrideJson, SecretWaypointStore.LocalFile.class);
		assertTrue(validLocalFile(versionOneOverride),
			"version-one overrides without a replacement retain their legacy interpretation");
		SecretWaypointStore.LocalFile missingV2Collections = new SecretWaypointStore.LocalFile();
		missingV2Collections.collected = null;
		assertFalse(validLocalFile(missingV2Collections),
			"version-two files still require the collected array");
		SecretWaypointStore.LocalFile versionTwoOverride = new SecretWaypointStore.LocalFile();
		versionTwoOverride.overrides = new ArrayList<>(List.of(
			new SecretWaypointStore.WaypointOverride(dynamicMarkerId, false, null)));
		assertFalse(validLocalFile(versionTwoOverride),
			"version-two visible overrides still require a replacement record");
		SecretWaypointStore.LocalFile unsupportedVersion = new SecretWaypointStore.LocalFile();
		unsupportedVersion.version = 3;
		assertFalse(validLocalFile(unsupportedVersion), "unsupported local schema versions remain rejected");
		SecretWaypointStore.LocalFile oversized = new SecretWaypointStore.LocalFile();
		oversized.personal = java.util.Collections.nCopies(ClientJsonFile.MAX_ENTRIES + 1, waypoint);
		assertFalse(validLocalFile(oversized), "the local reader still enforces the entry limit");
	}

	private static void checkCopiedLocalStorePersistence() {
		Path directory;
		try {
			directory = Files.createTempDirectory("geiler-secret-waypoint-copy-");
		} catch (IOException error) {
			throw new AssertionError("the copied local-store fixture can be created", error);
		}
		Path copy = directory.resolve("secret-waypoints.json");
		try {
			SecretWaypointStore.Waypoint personal = new SecretWaypointStore.Waypoint(
				"personal:123e4567-e89b-12d3-a456-426614174000", "F1", "cell:1,2",
				"Copied personal marker", -167, 70, -135, 0xFF38CAFF);
			SecretWaypointStore.CollectedWaypoint collected = new SecretWaypointStore.CollectedWaypoint(
				"F1", "cell:1,2", personal.id());
			SecretWaypointStore.CollectedWaypoint futureCollection = new SecretWaypointStore.CollectedWaypoint(
				"F1", "cell:1,2", "future:catalog-marker");
			String dynamicMarkerId = roomMarker("chest", "Copied marker").id();
			SecretWaypointStore.WaypointOverride hidden =
				new SecretWaypointStore.WaypointOverride(dynamicMarkerId, true, null);
			SecretWaypointStore.LocalFile versionTwo = new SecretWaypointStore.LocalFile();
			versionTwo.personal = new ArrayList<>(List.of(personal));
			versionTwo.overrides = new ArrayList<>(List.of(hidden));
			versionTwo.collected = new ArrayList<>(List.of(collected, futureCollection));
			writeCopiedLocalFile(copy, versionTwo,
				"the copied version-two store is written by the same encoder and atomic writer as live commits");
			ClientJsonFile.ReadResult<SecretWaypointStore.LocalFile> loaded =
				SecretWaypointLocalPersistenceLogic.read(copy);
			assertTrue(loaded.exists() && loaded.valid(), "the copied version-two store loads without touching the active profile");
			assertTrue(validLocalFile(loaded.value()), "the copied version-two store passes the production entry validator");
			assertEquals(List.of(personal), loaded.value().personal,
				"copied local load retains personal waypoint data");
			assertEquals(List.of(hidden), loaded.value().overrides,
				"copied local load retains a room-instance override");
			assertEquals(List.of(collected, futureCollection), loaded.value().collected,
				"copied local load retains confirmed and catalog-unknown collection history");

			SecretWaypointStore.LocalFile versionOne = new SecretWaypointStore.LocalFile();
			versionOne.version = 1;
			versionOne.personal = new ArrayList<>(List.of(personal));
			versionOne.overrides = new ArrayList<>(List.of(
				new SecretWaypointStore.WaypointOverride(dynamicMarkerId, false, null)));
			versionOne.collected = null;
			writeCopiedLocalFile(copy, versionOne,
				"the copied legacy store is written in the exact readable version-one shape");
			ClientJsonFile.ReadResult<SecretWaypointStore.LocalFile> legacy =
				SecretWaypointLocalPersistenceLogic.read(copy);
			assertTrue(legacy.valid() && validLocalFile(legacy.value()),
				"copied version-one files with omitted collection state remain readable");
			assertFalse(JsonParser.parseString(Files.readString(copy, StandardCharsets.UTF_8))
				.getAsJsonObject().has("collected"), "the copied version-one file omits its collection field");
			assertEquals(List.of(), legacy.value().collected,
				"Gson keeps the LocalFile initializer's empty list when the legacy field is missing");
			assertEquals(List.of(personal), legacy.value().personal,
				"version-one migration retains existing personal markers");
			SecretWaypointStore.LocalFile migrated = new SecretWaypointStore.LocalFile();
			migrated.personal = new ArrayList<>(legacy.value().personal);
			migrated.overrides = new ArrayList<>();
			migrated.collected = new ArrayList<>();
			writeCopiedLocalFile(copy, migrated,
				"the normal next-save model upgrades copied version-one state to version two");
			ClientJsonFile.ReadResult<SecretWaypointStore.LocalFile> upgraded =
				SecretWaypointLocalPersistenceLogic.read(copy);
			assertTrue(upgraded.valid() && validLocalFile(upgraded.value()),
				"the migrated copied store reloads as a valid version-two file");
			assertEquals(2, upgraded.value().version, "the normal local writer keeps version two as its default");
			assertEquals(List.of(personal), upgraded.value().personal,
				"version-one to version-two persistence keeps saved waypoint data");
			assertEquals(List.of(), upgraded.value().collected,
				"version-one migration initializes the newly persisted collection history empty");

			SecretWaypointStore.LocalFile unknownOverride = new SecretWaypointStore.LocalFile();
			unknownOverride.overrides = new ArrayList<>(List.of(
				new SecretWaypointStore.WaypointOverride("future:override", true, null)));
			writeCopiedLocalFile(copy, unknownOverride, "the unknown-override safety fixture is stored intact");
			String unknownOverrideBytes = Files.readString(copy, StandardCharsets.UTF_8);
			ClientJsonFile.ReadResult<SecretWaypointStore.LocalFile> unknownOverrideRead =
				SecretWaypointLocalPersistenceLogic.read(copy);
			assertTrue(unknownOverrideRead.valid(), "unknown override JSON remains syntactically readable");
			assertFalse(validLocalFile(unknownOverrideRead.value()),
				"an unsupported local override is rejected before it can be applied or rewritten");
			assertEquals(unknownOverrideBytes, Files.readString(copy, StandardCharsets.UTF_8),
				"reading unsupported override data leaves the copied bytes unchanged");

			SecretWaypointStore.LocalFile malformedCollection = new SecretWaypointStore.LocalFile();
			malformedCollection.collected = new ArrayList<>(List.of(new SecretWaypointStore.CollectedWaypoint(
				"F1", "cell:1,2", "personal:123e4567-e89b-12d3-a456-426614174001")));
			writeCopiedLocalFile(copy, malformedCollection, "the malformed collection safety fixture is stored intact");
			String malformedBytes = Files.readString(copy, StandardCharsets.UTF_8);
			ClientJsonFile.ReadResult<SecretWaypointStore.LocalFile> malformedRead =
				SecretWaypointLocalPersistenceLogic.read(copy);
			assertTrue(malformedRead.valid(), "malformed collection references remain syntactically readable");
			assertFalse(validLocalFile(malformedRead.value()),
				"collection state referencing a missing personal marker is rejected as a whole");
			assertEquals(malformedBytes, Files.readString(copy, StandardCharsets.UTF_8),
				"rejecting malformed collection state does not truncate or rewrite the copied file");

			String malformedJson = "{\"version\":2,\"personal\":[";
			try {
				assertTrue(SecretWaypointLocalPersistenceLogic.writeEncoded(copy, malformedJson)
					.get(10, TimeUnit.SECONDS), "the malformed-JSON safety fixture is stored intact");
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new AssertionError("malformed JSON fixture persistence was interrupted", error);
			} catch (Exception error) {
				throw new AssertionError("malformed JSON fixture persistence completes within its bound", error);
			}
		ClientJsonFile.ReadResult<SecretWaypointStore.LocalFile> malformedJsonRead =
			SecretWaypointLocalPersistenceLogic.read(copy);
		assertFalse(malformedJsonRead.valid(), "malformed local JSON is rejected by the production reader");
		assertEquals(malformedJson, Files.readString(copy, StandardCharsets.UTF_8),
			"malformed local JSON bytes remain untouched for recovery");
		} catch (IOException error) {
			throw new AssertionError("copied local-store checks can inspect their temporary file", error);
		} finally {
			try {
				Files.deleteIfExists(copy);
				Files.deleteIfExists(directory);
			} catch (IOException error) {
				throw new AssertionError("the copied local-store fixture is cleaned up", error);
			}
		}
	}

	private static void writeCopiedLocalFile(Path copy, SecretWaypointStore.LocalFile file, String message) {
		try {
			assertTrue(SecretWaypointLocalPersistenceLogic.write(copy, file).get(10, TimeUnit.SECONDS), message);
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new AssertionError("copied local-store persistence was interrupted", error);
		} catch (Exception error) {
			throw new AssertionError("copied local-store persistence completes within its bound", error);
		}
	}

	private static boolean validLocalFile(SecretWaypointStore.LocalFile file) {
		try {
			Method validator = SecretWaypointStoreCore.class.getDeclaredMethod(
				"validLocalFile", SecretWaypointStore.LocalFile.class);
			validator.setAccessible(true);
			return (boolean) validator.invoke(SecretWaypointStoreCore.INSTANCE, file);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("the store's local schema validator remains callable by focused checks", error);
		}
	}

	private static void assertStoreMethod(String name, Class<?> returnType,
		boolean synchronizedMethod, Class<?>... parameterTypes) {
		try {
			Method method = SecretWaypointStore.class.getDeclaredMethod(name, parameterTypes);
			assertEquals(returnType, method.getReturnType(), name + " keeps its Java return type");
			assertTrue(Modifier.isPublic(method.getModifiers()), name + " remains public");
			assertTrue(Modifier.isStatic(method.getModifiers()), name + " remains static");
			assertEquals(synchronizedMethod, Modifier.isSynchronized(method.getModifiers()),
				name + " keeps its synchronization boundary");
		} catch (NoSuchMethodException error) {
			throw new AssertionError("the Java-facing store method " + name + " remains present", error);
		}
	}

	private static void assertJavaStaticMethod(Class<?> owner, String name, Class<?> returnType,
		Class<?>... parameterTypes) {
		try {
			Method method = owner.getDeclaredMethod(name, parameterTypes);
			assertEquals(returnType, method.getReturnType(), name + " keeps its Java return type");
			assertTrue(Modifier.isPublic(method.getModifiers()), name + " remains public");
			assertTrue(Modifier.isStatic(method.getModifiers()), name + " remains static");
		} catch (NoSuchMethodException error) {
			throw new AssertionError("the Java-facing " + owner.getSimpleName() + " method " + name + " remains present", error);
		}
	}

	private static void assertRecordComponents(Class<?> type, Class<?>[] componentTypes, String... names) {
		assertTrue(type.isRecord(), type.getSimpleName() + " preserves its Java record shape");
		assertTrue(Modifier.isPublic(type.getModifiers()) && Modifier.isStatic(type.getModifiers()),
			type.getSimpleName() + " remains a public static nested record");
		RecordComponent[] components = type.getRecordComponents();
		assertEquals(Arrays.asList(names),
			Arrays.stream(components).map(RecordComponent::getName).toList(),
			type.getSimpleName() + " preserves ordered record component names");
		assertEquals(Arrays.asList(componentTypes),
			Arrays.stream(components).map(RecordComponent::getType).toList(),
			type.getSimpleName() + " preserves ordered record component types");
		try {
			assertTrue(Modifier.isPublic(type.getDeclaredConstructor(componentTypes).getModifiers()),
				type.getSimpleName() + " keeps its public canonical constructor");
		} catch (NoSuchMethodException error) {
			throw new AssertionError(type.getSimpleName() + " keeps its canonical constructor", error);
		}
	}

	private static void assertKotlinValueShape(Class<?> type, Class<?>[] componentTypes, String... names) {
		assertFalse(type.isRecord(), type.getSimpleName() + " documents its Kotlin value-like class boundary");
		assertTrue(Modifier.isPublic(type.getModifiers()) && Modifier.isStatic(type.getModifiers())
			&& Modifier.isFinal(type.getModifiers()), type.getSimpleName() + " remains a public static final nested type");
		assertEquals(names.length, componentTypes.length, type.getSimpleName() + " has matching value components");
		try {
			assertTrue(Modifier.isPublic(type.getDeclaredConstructor(componentTypes).getModifiers()),
				type.getSimpleName() + " keeps its public value constructor");
			for (int index = 0; index < names.length; index++) {
				Method accessor = type.getDeclaredMethod(names[index]);
				assertEquals(componentTypes[index], accessor.getReturnType(),
					type.getSimpleName() + " keeps accessor " + names[index] + " return type");
				assertTrue(Modifier.isPublic(accessor.getModifiers()),
					type.getSimpleName() + " keeps accessor " + names[index] + " public");
			}
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(type.getSimpleName() + " keeps its Java-facing value shape", error);
		}
	}

	private static void assertGsonDtoShape(Class<?> type, String[] names,
		Class<?>[] fieldTypes, Class<?>[] listElementTypes) {
		assertTrue(Modifier.isPublic(type.getModifiers()) && Modifier.isStatic(type.getModifiers())
			&& Modifier.isFinal(type.getModifiers()), type.getSimpleName() + " remains a public static final DTO");
		try {
			assertTrue(Modifier.isPublic(type.getDeclaredConstructor().getModifiers()),
				type.getSimpleName() + " keeps its public no-argument constructor");
			for (int index = 0; index < names.length; index++) {
				java.lang.reflect.Field field = type.getDeclaredField(names[index]);
				assertEquals(fieldTypes[index], field.getType(), type.getSimpleName() + " keeps field " + names[index]);
				assertTrue(Modifier.isPublic(field.getModifiers()),
					type.getSimpleName() + " keeps field " + names[index] + " directly accessible");
				if (listElementTypes[index] != null) {
					assertTrue(field.getGenericType() instanceof ParameterizedType,
						type.getSimpleName() + " keeps field " + names[index] + " parameterized");
					ParameterizedType generic = (ParameterizedType) field.getGenericType();
					assertEquals(listElementTypes[index], generic.getActualTypeArguments()[0],
						type.getSimpleName() + " keeps field " + names[index] + " element type");
				}
			}
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(type.getSimpleName() + " keeps its Gson DTO shape", error);
		}
	}

	private static void assertUnsupported(Runnable mutation, String message) {
		try {
			mutation.run();
		} catch (UnsupportedOperationException expected) {
			return;
		}
		throw new AssertionError(message);
	}

	private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
	private static void assertFalse(boolean value, String message) { if (value) throw new AssertionError(message); }
	private static void assertEquals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
}
