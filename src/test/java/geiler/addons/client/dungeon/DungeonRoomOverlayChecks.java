package geiler.addons.client.dungeon;

import com.google.gson.Gson;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.dungeon.helpers.DungeonPlayerMap;
import geiler.addons.client.dungeon.helpers.DungeonMapProjection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.impl.DungeonRoomOverlayModule;

/** Pure checks for map-visible cells, fail-closed footprints, and observed-exit render cues. */
public final class DungeonRoomOverlayChecks {
	private static final int ROOM_SIZE = 6;

	private DungeonRoomOverlayChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		mapRescanCadenceRemainsPeriodic();
		mapHudBoundsMatchTheVisibleGrid();
		customMapLayoutAndScaleStayBounded();
		leapHeldNamePlacementKeepsFullTextInsideViewport();
		unknownMapSpaceIsNotRenderedAsRoom();
		mapVisibleRoomCanRenderWithoutLoadedRoomBlocks();
		connectedFootprintAndObservedExitAreRendered();
		ambiguousOutOfGridFootprintFailsClosed();
		unopenedRoomsRequireExplicitVerifiedMetadata();
		roomLabelsWrapWithoutLoss();
		puzzleAndBossSecretCountsAreSuppressed();
		roomLabelOverhangIsLimitedToHalfTheConfiguredGap();
		roomAndDoorColorsUseTheirConfiguredSlots();
		geiler.addons.client.module.impl.DungeonRoomOverlaySnapshotRetentionChecks.run();
		mapVisualModesAndYellowMinibossColorsStayDistinct();
		rasterTextRendererIsBoundedAndKeepsStyleVariantsDistinct();
		mapStyleJsonAndBase64RoundTripWithoutPartialMutation();
		moduleBackedMapStyleRoundTripCoversRepeatedSettingNames();
		mapStyleRejectsValuesOutsideSettingBounds();
		mapStyleResetRestoresRequestedDefaults();
		mapStyleClipboardRejectsOversizedInput();
		settingsPreviewUsesProductionVisibilityAndColorRules();
		previewRoomFootprintsDoNotOverlap();
		roomLabelsKeepProgressWhenCheckmarksAreEnabled();
		secretProgressIsAttributedLocallyAndResetByLifecycle();
		mapDisablePausesWithoutEndingTheDungeonSession();
		verifiedRoomCoreFallbackUsesNormalMapRenderer();
		mapSettingsMigrateOnceAndPersistTheirMarker();
		mapDoorsUseOdinGeometryAndTypedPalettes();
		doorHintsAreSeparateAndRevealOffOnly();
		playerMarkerRetentionIsBounded();
		normalizedHelperValuesKeepTheirJavaContract();
		playerMarkersRemainClientObservedAndImmutable();
		localPlayerMarkerUsesOdinMapDecoration();
		localPlayerMarkerTracksYawWhileGridStaysFixed();
		mapRenderDataAndDoorGeometryAreCachedUntilInputsChange();
		mapPreviewSettingIsFoldedAndVisibleByDefault();
		appearanceSettingsAreNestedWithoutChangingTheSettingRegistry();
		internalTwoByTwoRoomGapIsFilledWithoutTouchingOuterDoorGeometry();
		playerProjectionCrossesRoomBoundariesWithoutRoomSizedJumps();
	}

	private static void mapRescanCadenceRemainsPeriodic() {
		for (int activeTick = 1; activeTick <= 15; activeTick++) {
			boolean expected = activeTick % 5 == 0;
			equals(expected, DungeonRoomOverlayLifecycleRules.mapRefreshDue(activeTick),
				"full saved-map scan remains due every fifth active map tick (tick " + activeTick + ")");
		}
	}

	private static void settingsPreviewUsesProductionVisibilityAndColorRules() {
		var preview = DungeonRoomOverlayPreview.sampleSnapshot();
		var revealedPlan = DungeonRoomOverlayGeometry.renderPlan(preview, true);
		var unopened = new DungeonRoomOverlayGeometry.Cell(1, 2, DungeonMapGeometry.RoomType.ROOM,
			"verified-unopened-preview-check", DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED);
		check(!DungeonRoomOverlayGeometry.visible(unopened.visibility(), false),
			"verified unopened rooms remain hidden when reveal is off");
		check(DungeonRoomOverlayGeometry.visible(unopened.visibility(), true),
			"verified unopened rooms appear when reveal is enabled");
		DungeonRoomOverlayStyle.RoomColors colors = new DungeonRoomOverlayStyle.RoomColors(
			0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444, 0xFF555555, 0xFF666666,
			0xFF777777, 0xFF888888, 0xFF999999, 0xFFAAAAAA);
		int unopenedFill = 0xFF507090;
		int unopenedText = 0xFFE1D2C3;
		equals(DungeonRoomOverlayStyle.darken(colors.room(), 0.4),
			DungeonRoomOverlayStyle.roomFillColor(unopened, colors, unopenedFill, 0.4),
			"known unopened rooms keep their true type color and apply the darkening factor");
		var unknown = new DungeonRoomOverlayGeometry.Cell(1, 1, DungeonMapGeometry.RoomType.UNKNOWN,
			"unknown-placeholder", DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED);
		equals(unopenedFill, DungeonRoomOverlayStyle.roomFillColor(unknown, colors, unopenedFill, 0.4),
			"topology-confirmed unknown rooms use the neutral unopened placeholder color");
		equals(unopenedText, DungeonRoomOverlayStyle.roomTextColor(unopened.visibility(), "WHITE",
			true, 0xFFFFFFFF, unopenedText), "preview uses the configured unopened text color");
		check(DungeonRoomOverlayStyle.predictionsVisible(false), "predictions remain visible when not disabled");
		check(DungeonRoomOverlayStyle.predictionsVisible(true, false),
			"revealing unopened rooms is independent of prediction visibility");
		check(!DungeonRoomOverlayStyle.predictionsVisible(false, true)
			&& !DungeonRoomOverlayStyle.predictionsVisible(true, true),
			"Disable Predictions alone suppresses predictions");
		check(!preview.predictions().isEmpty(), "preview includes a neutral hint for the unresolved connected cell");
		var unresolvedHint = preview.predictions().getFirst();
		check(preview.cells().stream().noneMatch(cell -> cell.x() == unresolvedHint.x()
			&& cell.z() == unresolvedHint.z()), "unresolved preview cell does not claim a predicted room type");
		for (DungeonMapGeometry.RoomType type : List.of(DungeonMapGeometry.RoomType.ROOM,
			DungeonMapGeometry.RoomType.PUZZLE, DungeonMapGeometry.RoomType.TRAP,
			DungeonMapGeometry.RoomType.BLOOD, DungeonMapGeometry.RoomType.CHAMPION,
			DungeonMapGeometry.RoomType.ENTRANCE))
			check(preview.cells().stream().anyMatch(cell -> cell.type() == type),
				"preview includes the " + type.label() + " room color from the supplied layout");
		for (DungeonRoomOverlayGeometry.DoorKind kind : DungeonRoomOverlayGeometry.DoorKind.values())
			check(revealedPlan.exits().stream().anyMatch(exit -> exit.kind() == kind),
				"preview includes the " + kind.name().toLowerCase(java.util.Locale.ROOT) + " door color");
		check(preview.rooms().stream().anyMatch(room -> "GREEN".equals(room.checkmark())),
			"preview includes a checkmark sample for the selected item-icon style");
		check(preview.playerCell() != null && preview.cells().stream()
			.anyMatch(cell -> cell.x() == preview.playerCell().componentX()
				&& cell.z() == preview.playerCell().componentZ()),
			"the local marker is anchored over its observed room tile");
		check(preview.players().stream().anyMatch(DungeonRoomOverlayGeometry.PlayerMarker::localPlayer),
			"preview includes the independently rendered local-player marker");
		check(List.of("Red Blue", "Purple Flags", "Water Board", "Higher Blaze", "Pedestal", "Pipes",
			"New Trap", "Three Weirdos", "Ice Path", "Bangers", "Prison Cell", "Double Diamond",
			"Dip", "Diagonal", "Mossy", "Jumping Skulls", "Rails", "King Midas")
			.stream().allMatch(name -> preview.rooms().stream().anyMatch(room -> name.equals(room.name()))),
			"the deterministic preview uses the latest Water Board screenshot room layout");
		var waterBoard = preview.rooms().stream().filter(room -> "Water Board".equals(room.name()))
			.findFirst().orElseThrow();
		equals(DungeonMapGeometry.RoomType.PUZZLE, waterBoard.type(),
			"the preview identifies Water Board as a puzzle room");
		var previewRails = preview.rooms().stream().filter(room -> "Rails".equals(room.name()))
			.findFirst().orElseThrow();
		equals(0, previewRails.foundSecrets(), "the deterministic preview matches Rails at 0 found secrets");
		equals(9, previewRails.maxSecrets(), "the deterministic preview matches Rails out of 9 secrets");
		equals(4, previewRails.footprint().size(), "the preview shows Rails as a 2 by 2 room");
		for (DungeonRoomOverlayGeometry.Room room : preview.rooms()) {
			if (room.maxSecrets() <= 0 || room.name().equals("Unknown")
				|| room.type() == DungeonMapGeometry.RoomType.PUZZLE) continue;
			String label = DungeonRoomMapLabel.build(room.name(), room.foundSecrets(), room.maxSecrets(), "Both");
			check(label.contains("\n") && label.contains("/"),
				"preview room " + room.name() + " carries its name and secret count");
		}
	}

	private static void mapHudBoundsMatchTheVisibleGrid() {
		var layout = DungeonRoomOverlayLayout.calculate(16, 4, 12);
		equals(128, layout.width(), "default HUD canvas matches Odin's 128 px background");
		equals(layout.width(), layout.height(), "map-only HUD bounds match its square grid");
		equals(116, layout.cellSize() * 6 + layout.cellGap() * 5,
			"six 16 px rooms and five 4 px gaps occupy Odin's 116 px map grid");
		equals(6, layout.gridTop(), "the room grid starts six pixels below the 128 px frame edge");
		equals(6, layout.contentInset(), "the room grid is centered with an even leading margin");
		equals(6, layout.width() - layout.contentInset() - layout.cellSize() * 6 - layout.cellGap() * 5,
			"the 116 px grid keeps a matching six-pixel trailing margin");
	}

	private static void previewRoomFootprintsDoNotOverlap() {
		var preview = DungeonRoomOverlayPreview.sampleSnapshot();
		Set<String> occupied = new HashSet<>();
		for (DungeonRoomOverlayGeometry.Room room : preview.rooms()) {
			check(!room.footprint().isEmpty(), "preview room " + room.name() + " has a footprint");
			for (DungeonRoomOverlayGeometry.Cell cell : room.footprint()) {
				check(cell.x() >= 0 && cell.x() < ROOM_SIZE && cell.z() >= 0 && cell.z() < ROOM_SIZE,
					"preview room " + room.name() + " stays inside the 6 by 6 grid");
				String position = cell.x() + "," + cell.z();
				check(occupied.add(position), "preview room footprints do not overlap at " + position);
				check(room.id().equals(cell.roomId()), "footprint cell belongs to its preview room");
			}
		}
		Set<String> snapshotCells = new HashSet<>();
		for (DungeonRoomOverlayGeometry.Cell cell : preview.cells()) {
			String position = cell.x() + "," + cell.z();
			check(snapshotCells.add(position), "preview snapshot has one cell per room tile at " + position);
		}
		check(occupied.equals(snapshotCells), "preview map cells exactly cover the room-footprint tiles");
	}

	private static void customMapLayoutAndScaleStayBounded() {
		var compact = DungeonRoomOverlayLayout.calculate(8, 0, 2);
		var spacious = DungeonRoomOverlayLayout.calculate(20, 5, 10);
		check(compact.width() < spacious.width(), "room size, gap, and padding affect map width");
		check(compact.height() < spacious.height(), "room size, gap, and padding affect map height");
		equals(1, DungeonRoomOverlayLayout.scaledDimension(1, Float.NaN), "non-finite map scale falls back to one");
		equals(40, DungeonRoomOverlayLayout.scaledDimension(20, 2), "map scale is reflected in HUD bounds");
		equals(5, DungeonRoomOverlayLayout.scaledDimension(20, 0.1f), "map scale clamps to a visible minimum");
		int mapPreviewAtDefaultViewport = DungeonRoomOverlayLayout.settingsPreviewHeight(460, 500);
		equals(460, mapPreviewAtDefaultViewport,
			"the settings fixture is shown at its fixed 460 px preview size when space permits");
		equals(460, DungeonRoomOverlayLayout.settingsPreviewHeight(460, 700),
			"the map preview stays capped at the reference 460 px size");
		equals(120, DungeonRoomOverlayLayout.settingsPreviewHeight(120, 500),
			"the map preview adapts to a narrow settings pane");
		equals(80, DungeonRoomOverlayLayout.settingsPreviewHeight(360, 80),
			"the map preview never exceeds a short settings pane");
	}

	private static void leapHeldNamePlacementKeepsFullTextInsideViewport() {
		float[] leftEdge = leapHeldNamePlacement(0.0f, -5.0f, 100.0f, 10.0f, 1.0f, 80, 50);
		equals(0.78f, leftEdge[2], "a Leap-held name leaves a one-pixel viewport gutter");
		equals(1.0f, leftEdge[0], "a left-edge name leaves a one-pixel viewport gutter");
		equals(1.0f, leftEdge[1], "a top-edge name leaves a one-pixel viewport gutter");
		float[] rightEdge = leapHeldNamePlacement(80.0f, 99.0f, 20.0f, 10.0f, 1.0f, 80, 50);
		equals(1.0f, rightEdge[2], "short Leap-held names keep their configured scale");
		equals(59.0f, rightEdge[0], "a right-edge name leaves a one-pixel viewport gutter");
		equals(39.0f, rightEdge[1], "a bottom-edge name leaves a one-pixel viewport gutter");
	}

	private static float[] leapHeldNamePlacement(float centerX, float topY, float textWidth,
		float textHeight, float requestedScale, int viewportWidth, int viewportHeight) {
		try {
			var method = DungeonRoomOverlayModule.class.getDeclaredMethod("leapHeldNamePlacement",
				float.class, float.class, float.class, float.class, float.class, int.class, int.class);
			method.setAccessible(true);
			return (float[]) method.invoke(null, centerX, topY, textWidth, textHeight, requestedScale,
				viewportWidth, viewportHeight);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("Could not exercise Leap-held name placement", error);
		}
	}

	private static void unknownMapSpaceIsNotRenderedAsRoom() {
		byte[] colors = blankMap();
		var snapshot = scan(colors, 2, 2, 40, 40, List.of());
		check(snapshot.available(), "calibrated map remains usable with no recognized room pixels");
		check(snapshot.cells().isEmpty(), "unrecognized map cells are omitted");
		var plan = DungeonRoomOverlayGeometry.renderPlan(snapshot);
		check(plan.visibleCells().isEmpty(), "render plan does not draw question-mark rooms for blank space");
		check(plan.footprintEdges().isEmpty() && plan.exits().isEmpty(), "blank map produces no footprint or exit marks");
	}

	private static void mapVisibleRoomCanRenderWithoutLoadedRoomBlocks() {
		byte[] colors = blankMap();
		paintTile(colors, 40, 40, DungeonMapGeometry.RoomType.PUZZLE.mapColor());
		var snapshot = scan(colors, 2, 2, 40, 40, List.of());
		var plan = DungeonRoomOverlayGeometry.renderPlan(snapshot);
		equals(1, plan.visibleCells().size(), "map pixel reveals one puzzle cell");
		equals(DungeonMapGeometry.RoomType.PUZZLE, plan.visibleCells().getFirst().type(), "type comes from observed saved map color");
		equals(1, snapshot.rooms().size(), "a map-visible room is represented without a world scan");
		check(snapshot.rooms().getFirst().footprintKnown(), "single-cell special room has a known footprint");
		equals(4, plan.footprintEdges().size(), "single room renders a complete footprint outline");
		check(snapshot.rooms().getFirst().name().equals("Unknown"), "no name is invented without a catalog");
	}

	private static void connectedFootprintAndObservedExitAreRendered() {
		byte[] colors = blankMap();
		paintTile(colors, 40, 40, DungeonMapGeometry.RoomType.ROOM.mapColor());
		paintTile(colors, 50, 40, DungeonMapGeometry.RoomType.ROOM.mapColor());
		for (int x = 46; x < 50; x++) colors[x + (40 << 7)] = (byte) DungeonMapGeometry.RoomType.ROOM.mapColor();
		var door = new DungeonRoomOverlayGeometry.ObservedDoor(2 * 6 + 3, 2 * 6 + 4,
			DungeonRoomOverlayGeometry.DoorKind.BLOOD);
		var snapshot = scan(colors, 2, 2, 40, 40, List.of(door));
		equals(1, snapshot.rooms().size(), "matching map-gap pixels connect the two room cells");
		var room = snapshot.rooms().getFirst();
		check(room.footprintKnown(), "connected map footprint is recognized completely");
		equals(2, room.footprint().size(), "known two-cell footprint retains both cells");
		check(!room.exitsKnown(), "loaded door evidence never claims the full exit set is known");
		equals(1, room.exits().size(), "one positive observed door yields one exit marker");
		equals(DungeonRoomOverlayGeometry.Direction.EAST, room.exits().getFirst().direction(), "exit direction follows the observed adjacent cells");
		equals(DungeonRoomOverlayGeometry.DoorKind.BLOOD, room.exits().getFirst().kind(), "door type survives map projection");
		var plan = DungeonRoomOverlayGeometry.renderPlan(snapshot);
		equals(6, plan.footprintEdges().size(), "two-cell footprint plan contains only outer boundaries");
		equals(1, plan.exits().size(), "render plan displays the positive door cue");
		var withoutDoors = scan(colors, 2, 2, 40, 40, List.of());
		check(!withoutDoors.rooms().getFirst().exitsKnown() && withoutDoors.rooms().getFirst().exits().isEmpty(),
			"missing loaded-door evidence remains unknown instead of proving no exit");
		check(DungeonRoomOverlayGeometry.renderPlan(withoutDoors).exits().isEmpty(),
			"adjacency or a connected same-room footprint does not fabricate a door cue");
		var palette = new DungeonRoomOverlayStyle.DoorColors(1, 2, 3, 4, 5);
		equals(5, DungeonRoomOverlayStyle.doorColor(plan.exits().getFirst().kind(), palette),
			"observed Blood exit selects the configured Blood door color");
	}

	private static void ambiguousOutOfGridFootprintFailsClosed() {
		byte[] colors = blankMap();
		paintTile(colors, 80, 40, DungeonMapGeometry.RoomType.ROOM.mapColor());
		paintTile(colors, 90, 40, DungeonMapGeometry.RoomType.ROOM.mapColor());
		for (int x = 86; x < 90; x++) colors[x + (40 << 7)] = (byte) DungeonMapGeometry.RoomType.ROOM.mapColor();
		var snapshot = scan(colors, 5, 2, 80, 40, List.of(new DungeonRoomOverlayGeometry.ObservedDoor(17, 18)));
		equals(1, snapshot.cells().size(), "only the in-grid observed tile appears");
		check(!snapshot.rooms().getFirst().footprintKnown(), "footprint crossing the six-cell grid boundary is ambiguous");
		check(snapshot.rooms().getFirst().footprint().isEmpty(), "ambiguous footprint does not expose partial geometry");
		check(snapshot.rooms().getFirst().exits().isEmpty(), "ambiguous room is not assigned door directions");
		var plan = DungeonRoomOverlayGeometry.renderPlan(snapshot);
		check(plan.visibleCells().size() == 1 && plan.footprintEdges().isEmpty() && plan.exits().isEmpty(),
			"ambiguous tile keeps its observed type but adds no guessed outline or exit");
	}

	private static void unopenedRoomsRequireExplicitVerifiedMetadata() {
		var opened = new DungeonRoomOverlayGeometry.Cell(0, 0, DungeonMapGeometry.RoomType.ROOM,
			"opened", DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		var openedAdjacent = new DungeonRoomOverlayGeometry.Cell(0, 1, DungeonMapGeometry.RoomType.ROOM,
			"opened", DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		var hidden = new DungeonRoomOverlayGeometry.Cell(1, 0, DungeonMapGeometry.RoomType.PUZZLE,
			"hidden", DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED);
		var unknown = new DungeonRoomOverlayGeometry.Cell(2, 0, DungeonMapGeometry.RoomType.UNKNOWN,
			"unknown", DungeonRoomOverlayGeometry.RoomVisibility.UNKNOWN);
		var otherObserved = new DungeonRoomOverlayGeometry.Cell(3, 0, DungeonMapGeometry.RoomType.ROOM,
			"other", DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		var openedRoom = new DungeonRoomOverlayGeometry.Room("opened", "Unknown", opened.type(),
			List.of(opened, openedAdjacent),
			List.of(), false, true, opened.visibility());
		var hiddenRoom = new DungeonRoomOverlayGeometry.Room("hidden", "Unknown", hidden.type(), List.of(hidden),
			List.of(new DungeonRoomOverlayGeometry.ObservedExit(hidden, DungeonRoomOverlayGeometry.Direction.WEST,
				DungeonRoomOverlayGeometry.DoorKind.WITHER)), false, true, hidden.visibility());
		var unknownRoom = new DungeonRoomOverlayGeometry.Room("unknown", "Unknown", unknown.type(), List.of(unknown),
			List.of(), false, true, unknown.visibility());
		var otherObservedRoom = new DungeonRoomOverlayGeometry.Room("other", "Other", otherObserved.type(),
			List.of(otherObserved), List.of(), false, true, otherObserved.visibility());
		var snapshot = new DungeonRoomOverlayGeometry.Snapshot(true,
			List.of(opened, openedAdjacent, hidden, unknown, otherObserved),
			List.of(openedRoom, hiddenRoom, unknownRoom, otherObservedRoom), new DungeonGridRules.Cell(0, 0, -200, -200),
			List.of(), "synthetic verified-state fixture");
		var defaultPlan = DungeonRoomOverlayGeometry.renderPlan(snapshot, false);
		equals(List.of(opened, openedAdjacent, otherObserved), defaultPlan.visibleCells(),
			"reveal off shows the current room and all other Odin-viewable map rooms");
		check(defaultPlan.footprintEdges().size() == 10 && defaultPlan.exits().isEmpty(),
			"reveal off draws viewable room footprints and omits exits attached only to hidden rooms");
		var revealPlan = DungeonRoomOverlayGeometry.renderPlan(snapshot, true);
		check(revealPlan.visibleCells().contains(opened) && revealPlan.visibleCells().contains(hidden)
			&& revealPlan.visibleCells().contains(otherObserved),
			"map-observed rooms and explicitly verified unopened rooms appear when reveal is enabled");
		check(!revealPlan.visibleCells().contains(unknown), "unknown cells stay hidden with reveal enabled");
		check(revealPlan.footprintEdges().size() == 14 && revealPlan.exits().size() == 1,
			"reveal on adds the unopened room footprint and its confirmed door cue");
		var unmarkedCurrent = new DungeonRoomOverlayGeometry.Cell(4, 4, DungeonMapGeometry.RoomType.ROOM,
			"unmarked-current", DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED);
		var unmarkedCurrentSnapshot = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(unmarkedCurrent),
			List.of(new DungeonRoomOverlayGeometry.Room("unmarked-current", "Unknown", unmarkedCurrent.type(),
				List.of(unmarkedCurrent), List.of(), false, true, unmarkedCurrent.visibility())),
			new DungeonGridRules.Cell(4, 4, -72, -72), "current room without a checkmark");
		check(DungeonRoomOverlayGeometry.renderPlan(unmarkedCurrentSnapshot, false).visibleCells()
			.contains(unmarkedCurrent), "the current local room remains visible before its map checkmark appears");
		equals(DungeonRoomOverlayGeometry.DoorKind.WITHER, revealPlan.exits().getFirst().kind(),
			"verified hidden-room exits retain their observed door class");
		check(DungeonRoomOverlayGeometry.hasVerifiedUnopenedMetadata(snapshot), "explicit unopened metadata is surfaced");
		check(!DungeonRoomOverlayGeometry.hasVerifiedUnopenedMetadata(new DungeonRoomOverlayGeometry.Snapshot(true,
			List.of(opened), List.of(openedRoom), null, "observed-only fixture")),
			"observed map colors do not masquerade as unopened-room metadata");
		var confirmedUnknown = new DungeonRoomOverlayGeometry.Cell(3, 0, DungeonMapGeometry.RoomType.UNKNOWN,
			"confirmed-unknown", DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED);
		check(!DungeonRoomOverlayStyle.predictionTargetAllowed(opened)
			&& !DungeonRoomOverlayStyle.predictionTargetAllowed(hidden)
			&& !DungeonRoomOverlayStyle.predictionTargetAllowed(unknown)
			&& DungeonRoomOverlayStyle.predictionTargetAllowed(confirmedUnknown)
			&& !DungeonRoomOverlayStyle.predictionTargetAllowed(null),
			"predictions decorate only a topology-confirmed unknown placeholder, never empty or authoritative cells");
	}

	private static void doorHintsAreSeparateAndRevealOffOnly() {
		var opened = new DungeonRoomOverlayGeometry.Cell(1, 1, DungeonMapGeometry.RoomType.ROOM,
			"opened-door-hint", DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		var unopened = new DungeonRoomOverlayGeometry.Cell(2, 1, DungeonMapGeometry.RoomType.ROOM,
			"unopened-door-hint", DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED);
		var openedRoom = new DungeonRoomOverlayGeometry.Room("opened-door-hint", "Unknown", opened.type(),
			List.of(opened), List.of(new DungeonRoomOverlayGeometry.ObservedExit(opened,
				DungeonRoomOverlayGeometry.Direction.EAST, DungeonRoomOverlayGeometry.DoorKind.WITHER)),
			false, true, opened.visibility());
		var unopenedRoom = new DungeonRoomOverlayGeometry.Room("unopened-door-hint", "Unknown", unopened.type(),
			List.of(unopened), List.of(), false, true, unopened.visibility());
		var snapshot = new DungeonRoomOverlayGeometry.Snapshot(true, List.of(opened, unopened),
			List.of(openedRoom, unopenedRoom), null, "confirmed visible-to-unopened door");

		var normalPlan = DungeonRoomOverlayGeometry.renderPlan(snapshot, false);
		check(normalPlan.visibleCells().equals(List.of(opened))
			&& normalPlan.unopenedDoorHints().equals(List.of(new DungeonRoomOverlayGeometry.DoorHint(2, 1)))
			&& normalPlan.exits().size() == 1 && normalPlan.footprintEdges().size() == 4
			&& normalPlan.exits().getFirst().kind() == DungeonRoomOverlayGeometry.DoorKind.WITHER,
			"reveal off draws one gray hint at a confirmed door endpoint without adding a room, outline, or bridge");
		var revealPlan = DungeonRoomOverlayGeometry.renderPlan(snapshot, true);
		check(revealPlan.visibleCells().equals(List.of(opened, unopened))
			&& revealPlan.unopenedDoorHints().isEmpty() && revealPlan.exits().equals(normalPlan.exits())
			&& revealPlan.doorHintsSuppressedByReveal() == 1,
			"reveal on adds the real unopened room and keeps the same door output without question hints");

		var alreadyVisible = new DungeonRoomOverlayGeometry.Cell(2, 1, DungeonMapGeometry.RoomType.ROOM,
			"already-visible-door-target", DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		var visibleTargetPlan = DungeonRoomOverlayGeometry.renderPlan(new DungeonRoomOverlayGeometry.Snapshot(true,
			List.of(opened, alreadyVisible), List.of(openedRoom), null, "door target already visible"), false);
		check(visibleTargetPlan.unopenedDoorHints().isEmpty()
			&& visibleTargetPlan.doorHintsAlreadyVisible() == 1,
			"a confirmed door does not cover a room that is already viewable");

		List<DungeonRoomOverlayGeometry.Cell> borderSources = List.of(
			new DungeonRoomOverlayGeometry.Cell(2, 0, DungeonMapGeometry.RoomType.ROOM, "north-border",
				DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED),
			new DungeonRoomOverlayGeometry.Cell(5, 2, DungeonMapGeometry.RoomType.ROOM, "east-border",
				DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED),
			new DungeonRoomOverlayGeometry.Cell(2, 5, DungeonMapGeometry.RoomType.ROOM, "south-border",
				DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED),
			new DungeonRoomOverlayGeometry.Cell(0, 2, DungeonMapGeometry.RoomType.ROOM, "west-border",
				DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED));
		List<DungeonRoomOverlayGeometry.Direction> borderDirections = List.of(
			DungeonRoomOverlayGeometry.Direction.NORTH, DungeonRoomOverlayGeometry.Direction.EAST,
			DungeonRoomOverlayGeometry.Direction.SOUTH, DungeonRoomOverlayGeometry.Direction.WEST);
		for (int index = 0; index < borderSources.size(); index++) {
			DungeonRoomOverlayGeometry.Cell border = borderSources.get(index);
			var borderRoom = new DungeonRoomOverlayGeometry.Room(border.roomId(), "Unknown", border.type(),
				List.of(border), List.of(new DungeonRoomOverlayGeometry.ObservedExit(border,
					borderDirections.get(index), DungeonRoomOverlayGeometry.DoorKind.NORMAL)), false, true,
				border.visibility());
			var borderPlan = DungeonRoomOverlayGeometry.renderPlan(new DungeonRoomOverlayGeometry.Snapshot(true,
				List.of(border), List.of(borderRoom), null, "border exit has no in-grid target"), false);
			check(borderPlan.unopenedDoorHints().isEmpty() && borderPlan.doorHintsOutsideGrid() == 1,
				"a "+ borderDirections.get(index) + " border cue never paints outside the 6x6 map");
		}

		var duplicateExit = new DungeonRoomOverlayGeometry.ObservedExit(opened,
			DungeonRoomOverlayGeometry.Direction.EAST, DungeonRoomOverlayGeometry.DoorKind.WITHER);
		var duplicateRoom = new DungeonRoomOverlayGeometry.Room("duplicate-exit-source", "Unknown", opened.type(),
			List.of(opened), List.of(duplicateExit, duplicateExit), false, true, opened.visibility());
		var duplicatePlan = DungeonRoomOverlayGeometry.renderPlan(new DungeonRoomOverlayGeometry.Snapshot(true,
			List.of(opened), List.of(duplicateRoom), null, "duplicate confirmed edge"), false);
		check(duplicatePlan.unopenedDoorHints().size() == 1 && duplicatePlan.doorHintCandidates() == 1,
			"duplicate confirmed edges produce one question hint");

		var noDoorPlan = DungeonRoomOverlayGeometry.renderPlan(new DungeonRoomOverlayGeometry.Snapshot(true,
			List.of(opened), List.of(new DungeonRoomOverlayGeometry.Room("opened-door-hint", "Unknown",
				opened.type(), List.of(opened), List.of(), false, true, opened.visibility())),
			null, "no confirmed door evidence"), false);
		check(noDoorPlan.unopenedDoorHints().isEmpty() && noDoorPlan.doorHintCandidates() == 0,
			"room occupancy without positive door evidence does not create a question hint");
	}

	private static void playerMarkerRetentionIsBounded() {
		var retention = new DungeonRoomPlayerMarkerRetention();
		var local = new DungeonRoomOverlayGeometry.PlayerMarker(1, 1, "Local", true);
		var remote = new DungeonRoomOverlayGeometry.PlayerMarker(3, 2, "Teammate", false,
			0.25, 0.75, "Mage", 90.0f);
		var livingRoster = List.of(new DungeonPlayerMap.TabPlayer("Local", "Archer", false),
			new DungeonPlayerMap.TabPlayer("Teammate", "Mage", false));
		var first = retention.merge(List.of(local, remote), livingRoster, 10);
		check(first.players().equals(List.of(local, remote)) && first.confirmedRemote() == 1,
			"fresh teammate marker remains in ordered projection and is cached");

		var gap = retention.merge(List.of(local), livingRoster, 30);
		check(gap.players().contains(remote) && gap.appendedRemote() == 1
			&& gap.maximumRetainedAgeTicks() == 20,
			"a confirmed teammate position survives a brief missing decoration update");

		var dead = retention.merge(List.of(local), List.of(
			new DungeonPlayerMap.TabPlayer("Local", "Archer", false),
			new DungeonPlayerMap.TabPlayer("Teammate", "Mage", true)), 31);
		check(dead.players().stream().noneMatch(marker -> marker.name().equals("Teammate"))
			&& dead.deadRemoved() == 1,
			"a current DEAD roster row removes the retained teammate marker immediately");

		retention.merge(List.of(remote), List.of(), 100);
		var justWithinBound = retention.merge(List.of(), List.of(),
			100 + DungeonRoomPlayerMarkerRetention.MAX_AGE_TICKS);
		check(justWithinBound.players().contains(remote), "retention includes its configured final tick");
		var expired = retention.merge(List.of(), List.of(),
			101 + DungeonRoomPlayerMarkerRetention.MAX_AGE_TICKS);
		check(expired.players().isEmpty() && expired.expiredRemote() == 1,
			"stale markers expire after the bounded missing-update window");

		retention.merge(List.of(remote), List.of(), 200);
		retention.clear();
		check(retention.merge(List.of(), List.of(), 201).players().isEmpty(),
			"run or world lifecycle clear removes all cached teammate markers");

		retention.merge(List.of(remote), livingRoster, 300);
		var replacementRoster = retention.merge(List.of(), List.of(
			new DungeonPlayerMap.TabPlayer("Local", "Archer", false),
			new DungeonPlayerMap.TabPlayer("Other", "Tank", false)), 301);
		check(replacementRoster.players().isEmpty() && replacementRoster.staleRosterRemoved() == 1,
			"a complete current roster that no longer contains a teammate clears that identity");

		var boundedResult = new DungeonRoomPlayerMarkerRetention.Result(
			null, -1, 100, 100, 100, 100, 100, 100);
		var boundedResultEquivalent = new DungeonRoomPlayerMarkerRetention.Result(
			List.of(), 0, 16, 16, DungeonRoomPlayerMarkerRetention.MAX_AGE_TICKS, 16, 16, 16);
		check(hasObservableRetentionResultApi()
			&& boundedResult.players().isEmpty() && boundedResult.confirmedRemote() == 0
			&& boundedResult.retainedRemote() == 16 && boundedResult.appendedRemote() == 16
			&& boundedResult.maximumRetainedAgeTicks() == DungeonRoomPlayerMarkerRetention.MAX_AGE_TICKS
			&& boundedResult.expiredRemote() == 16 && boundedResult.deadRemoved() == 16
			&& boundedResult.staleRosterRemoved() == 16
			&& boundedResult.equals(boundedResultEquivalent)
			&& boundedResult.hashCode() == boundedResultEquivalent.hashCode()
			&& boundedResult.toString().equals("Result[players=[], confirmedRemote=0, retainedRemote=16, " +
				"appendedRemote=16, maximumRetainedAgeTicks=40, expiredRemote=16, deadRemoved=16, staleRosterRemoved=16]")
			&& boundedResult.diagnostics().equals("confirmed=0,cached=16,appended=16,maxAgeTicks=40,expired=16," +
				"deadRemoved=16,staleRosterRemoved=16"),
			"the Java constructor/accessor API, normalized value behavior, bounds, and diagnostics stay stable");

		var boundedRetention = new DungeonRoomPlayerMarkerRetention();
		List<DungeonRoomOverlayGeometry.PlayerMarker> moreThanBound =
			java.util.stream.IntStream.range(0, 17)
				.mapToObj(index -> new DungeonRoomOverlayGeometry.PlayerMarker(
					index % 6, index / 6, "Teammate-" + index, false))
				.toList();
		var firstOverflow = boundedRetention.merge(moreThanBound, List.of(), 400);
		check(firstOverflow.players().size() == 17 && firstOverflow.confirmedRemote() == 16
			&& firstOverflow.retainedRemote() == 16,
			"the bounded cache preserves every current observation while capping retained remote entries");
		var afterEviction = boundedRetention.merge(List.of(), List.of(), 401);
		check(afterEviction.players().size() == 16 && afterEviction.appendedRemote() == 16
			&& afterEviction.players().getFirst().name().equals("Teammate-1")
			&& afterEviction.players().stream().noneMatch(marker -> marker.name().equals("Teammate-0")),
			"the oldest cached marker is evicted first when the retention bound is exceeded");
	}

	private static void normalizedHelperValuesKeepTheirJavaContract() {
		var cell = new DungeonRoomOverlayGeometry.Cell(2, 3, null, null, null);
		var sameCell = new DungeonRoomOverlayGeometry.Cell(2, 3, null, null,
			DungeonRoomOverlayGeometry.RoomVisibility.UNKNOWN);
		check(cell.equals(sameCell) && cell.hashCode() == sameCell.hashCode()
			&& cell.toString().equals("Cell[x=2, z=3, type=null, roomId=null, visibility=UNKNOWN]"),
			"cell normalization, equality, hash, and record-style text remain observable through Java");

		List<DungeonRoomOverlayGeometry.Cell> sourceFootprint = new ArrayList<>(List.of(cell));
		var room = new DungeonRoomOverlayGeometry.Room(null, " \t ", null, sourceFootprint, null,
			false, false, null, -2, 7, null);
		sourceFootprint.clear();
		var sameRoom = new DungeonRoomOverlayGeometry.Room(null, "Unknown",
			DungeonMapGeometry.RoomType.UNKNOWN, List.of(sameCell), List.of(), false, false,
			DungeonRoomOverlayGeometry.RoomVisibility.UNKNOWN, 0, -1, "");
		check(room.equals(sameRoom) && room.hashCode() == sameRoom.hashCode()
			&& room.name().equals("Unknown") && room.type() == DungeonMapGeometry.RoomType.UNKNOWN
			&& room.footprint().size() == 1 && room.foundSecrets() == -1
			&& room.toString().equals("Room[id=null, name=Unknown, type=UNKNOWN, footprint=[" + sameCell
				+ "], exits=[], exitsKnown=false, footprintKnown=false, visibility=UNKNOWN, maxSecrets=0, " +
				"foundSecrets=-1, checkmark=]") && rejectsMutation(room.footprint()),
			"room constructors normalize values, snapshot input lists, and keep Java value methods stable");

		var door = new DungeonRoomOverlayGeometry.ObservedDoor(4, 5, null);
		var sameDoor = new DungeonRoomOverlayGeometry.ObservedDoor(4, 5);
		var exit = new DungeonRoomOverlayGeometry.ObservedExit(cell, null, null);
		var sameExit = new DungeonRoomOverlayGeometry.ObservedExit(sameCell, null,
			DungeonRoomOverlayGeometry.DoorKind.NORMAL);
		var exitMarker = new DungeonRoomOverlayGeometry.ExitMarker(null, null, null);
		check(door.equals(sameDoor) && door.hashCode() == sameDoor.hashCode()
			&& door.kind() == DungeonRoomOverlayGeometry.DoorKind.NORMAL
			&& exit.equals(sameExit) && exit.hashCode() == sameExit.hashCode()
			&& exitMarker.toString().equals("ExitMarker[cell=null, direction=null, kind=NORMAL]"),
			"door and exit values retain null defaults and record-style equality and text");

		var player = new DungeonRoomOverlayGeometry.PlayerMarker(1, 2, "  ", true,
			Double.NaN, -1.0, "  Mage  ", Float.POSITIVE_INFINITY);
		var samePlayer = new DungeonRoomOverlayGeometry.PlayerMarker(1, 2, "Player", true,
			0.5, 0.0, "Mage", 0.0f);
		check(player.equals(samePlayer) && player.hashCode() == samePlayer.hashCode()
			&& player.toString().equals("PlayerMarker[x=1, z=2, name=Player, localPlayer=true, offsetX=0.5, " +
				"offsetZ=0.0, dungeonClass=Mage, yawDegrees=0.0]"),
			"player marker normalization and Java value methods retain their observable behavior");

		var snapshot = new DungeonRoomOverlayGeometry.Snapshot(false, null, null, null, null, null, null);
		var sameSnapshot = new DungeonRoomOverlayGeometry.Snapshot(false, List.of(), List.of(), null,
			List.of(), null, List.of());
		check(snapshot.equals(sameSnapshot) && snapshot.hashCode() == sameSnapshot.hashCode()
			&& snapshot.toString().equals("Snapshot[available=false, cells=[], rooms=[], playerCell=null, " +
				"players=[], reason=null, predictions=[]]") && rejectsMutation(snapshot.cells())
			&& rejectsMutation(snapshot.players()),
			"snapshot null lists become immutable empty lists with stable Java value methods");

		var plan = new DungeonRoomOverlayGeometry.RenderPlan(null, null, null, null, -1, 2, 3, 4);
		var samePlan = new DungeonRoomOverlayGeometry.RenderPlan(List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0);
		check(plan.equals(samePlan) && plan.hashCode() == samePlan.hashCode()
			&& plan.toString().equals("RenderPlan[visibleCells=[], footprintEdges=[], exits=[], unopenedDoorHints=[], " +
				"doorHintCandidates=0, doorHintsAlreadyVisible=0, doorHintsOutsideGrid=0, doorHintsSuppressedByReveal=0]")
			&& rejectsMutation(plan.exits()),
			"render plans preserve clamping, immutable lists, equality, hash, and record-style text");

		var anchor = new DungeonRoomOverlayStyle.Anchor(Double.NaN, Double.NEGATIVE_INFINITY);
		var sameAnchor = new DungeonRoomOverlayStyle.Anchor(0.0, 0.0);
		check(anchor.equals(sameAnchor) && anchor.hashCode() == sameAnchor.hashCode()
			&& anchor.toString().equals("Anchor[x=0.0, z=0.0]"),
			"style anchor normalization and Java value methods remain stable");
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static boolean rejectsMutation(List<?> values) {
		try {
			((List) values).add(new Object());
			return false;
		} catch (UnsupportedOperationException expected) {
			return true;
		}
	}

	private static void roomLabelsWrapWithoutLoss() {
		List<String> nameAndCount = DungeonRoomMapLabel.wrap("Creeper Beams\n0/6", 8, String::length);
		equals(List.of("Creeper", "Beams", "0/6"), nameAndCount,
			"long room names wrap at word boundaries and keep the count on its own line");
		String longWord = "Superlongroomname";
		List<String> hardWrapped = DungeonRoomMapLabel.wrap(longWord, 7, String::length);
		check(hardWrapped.stream().allMatch(line -> line.length() <= 7)
			&& String.join("", hardWrapped).equals(longWord),
			"an unbroken long room name hard-wraps without truncating characters");
		String emSpace = "\u2003";
		String surroundedRoomName = emSpace + "Room Name" + emSpace;
		equals(List.of(surroundedRoomName), DungeonRoomMapLabel.wrap(surroundedRoomName, 40, String::length),
			"room label wrapping preserves em spaces because Java trim does not remove them");
		equals(emSpace + "A… Be…", DungeonRoomMapLabel.fit(emSpace + "Alpha Beta" + emSpace, 8, String::length),
			"room label abbreviation preserves em spaces when matching Java trim behavior");
		String nonBreakingSpace = "\u00a0";
		equals(List.of(nonBreakingSpace), DungeonRoomMapLabel.wrap(nonBreakingSpace, 8, String::length),
			"NBSP-only paragraphs stay nonblank under Java String.isBlank semantics");
		equals(nonBreakingSpace, DungeonRoomMapLabel.fit(nonBreakingSpace, 1, String::length),
			"NBSP-only labels stay nonblank under Java String.isBlank semantics");
	}

	private static void puzzleAndBossSecretCountsAreSuppressed() {
		DungeonRoomOverlayModule module = DungeonRoomOverlayModule.INSTANCE;
		ChoiceSetting roomText = module.choiceSettings().stream()
			.filter(setting -> "Room Text".equals(setting.name())).findFirst().orElseThrow();
		String originalMode = roomText.value();
		BooleanSetting showPuzzleName = module.booleanSettings().stream()
			.filter(setting -> "Show Puzzle Name".equals(setting.name())).findFirst().orElseThrow();
		boolean originalShowPuzzleName = showPuzzleName.value();
		DungeonRoomOverlayGeometry.Cell puzzleCell = new DungeonRoomOverlayGeometry.Cell(1, 1,
			DungeonMapGeometry.RoomType.PUZZLE, "water-board", DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		DungeonRoomOverlayGeometry.Room puzzle = new DungeonRoomOverlayGeometry.Room("water-board", "Water Board",
			DungeonMapGeometry.RoomType.PUZZLE, List.of(puzzleCell), List.of(), false, true,
			DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED, 5, 0, "NONE");
		DungeonRoomOverlayGeometry.Cell normalCell = new DungeonRoomOverlayGeometry.Cell(2, 1,
			DungeonMapGeometry.RoomType.ROOM, "hallway", DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
		DungeonRoomOverlayGeometry.Room normal = new DungeonRoomOverlayGeometry.Room("hallway", "Hallway",
			DungeonMapGeometry.RoomType.ROOM, List.of(normalCell), List.of(), false, true,
			DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED, 5, 0, "NONE");
		java.lang.reflect.Field inDungeon = null;
		java.lang.reflect.Field mobEspBossPhase = null;
		Object trackerInstance = null;
		boolean previousDungeon = false;
		boolean previousMobEspBossPhase = false;
		try {
			java.lang.reflect.Method roomLabel = DungeonRoomOverlayModule.class.getDeclaredMethod(
				"roomLabel", DungeonRoomOverlayGeometry.Cell.class, DungeonRoomOverlayGeometry.Room.class);
			roomLabel.setAccessible(true);
			roomText.setValue("Both");
			equals("Water Board", (String) roomLabel.invoke(module, puzzleCell, puzzle),
				"puzzle labels retain the room name but suppress their secret count");
			roomText.setValue("Room Secrets");
			equals("Water Board", (String) roomLabel.invoke(module, puzzleCell, puzzle),
				"puzzle rooms show their puzzle name instead of secrets in secrets-only mode");

			DungeonRoomOverlayGeometry.Cell yellowCell = new DungeonRoomOverlayGeometry.Cell(3, 1,
				DungeonMapGeometry.RoomType.CHAMPION, "yellow", DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED);
			DungeonRoomOverlayGeometry.Room yellow = new DungeonRoomOverlayGeometry.Room("yellow", "Yellow",
				DungeonMapGeometry.RoomType.CHAMPION, List.of(yellowCell), List.of(), false, true,
				DungeonRoomOverlayGeometry.RoomVisibility.OBSERVED, 0, 0, "NONE");
			for (String mode : List.of("Both", "Room Name", "Room Secrets")) {
				roomText.setValue(mode);
				equals("", (String) roomLabel.invoke(module, yellowCell, yellow),
					"yellow rooms draw no text in " + mode + " mode");
			}

			Class<?> tracker = Class.forName("geiler.addons.client.dungeon.DungeonContextTracker");
			trackerInstance = tracker.getField("INSTANCE").get(null);
			inDungeon = tracker.getDeclaredField("inDungeon");
			mobEspBossPhase = tracker.getDeclaredField("mobEspBossPhase");
			inDungeon.setAccessible(true);
			mobEspBossPhase.setAccessible(true);
			previousDungeon = inDungeon.getBoolean(trackerInstance);
			previousMobEspBossPhase = mobEspBossPhase.getBoolean(trackerInstance);
			inDungeon.setBoolean(trackerInstance, true);
			mobEspBossPhase.setBoolean(trackerInstance, true);
			roomText.setValue("Both");
			equals("Hallway", (String) roomLabel.invoke(module, normalCell, normal),
				"boss-phase suppression retains ordinary room names");
			roomText.setValue("Room Secrets");
			equals("", (String) roomLabel.invoke(module, normalCell, normal),
				"boss-phase suppression removes the label in secrets-only mode");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("room-label policy could not be checked", error);
		} finally {
			roomText.setValue(originalMode);
			showPuzzleName.setValue(originalShowPuzzleName);
			try {
				if (inDungeon != null) inDungeon.setBoolean(trackerInstance, previousDungeon);
				if (mobEspBossPhase != null) mobEspBossPhase.setBoolean(trackerInstance, previousMobEspBossPhase);
			} catch (IllegalAccessException error) {
				throw new AssertionError("dungeon tracker state could not be restored", error);
			}
		}
	}

	private static void roomLabelOverhangIsLimitedToHalfTheConfiguredGap() {
		try {
			var method = DungeonRoomOverlayModule.class.getDeclaredMethod("roomLabelMaximumWidth",
				int.class, int.class, int.class, int.class, float.class);
			method.setAccessible(true);
			equals(20, (Integer) method.invoke(null, 2, 2, 16, 4, 1.0f),
				"one-room labels gain two layout pixels on either side at the default gap");
			equals(40, (Integer) method.invoke(null, 2, 3, 16, 4, 1.0f),
				"a two-cell label gains only the configured four-pixel total overhang");
			equals(16, (Integer) method.invoke(null, 2, 2, 16, 0, 1.0f),
				"zero room gap does not grant text overhang");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("room-label overhang could not be checked", error);
		}
	}

	private static void minibossRoomsShowGreenCheckWhenDone() {
		DungeonMapGeometry.RoomType mini = DungeonMapGeometry.RoomType.MINIBOSS;
		equals("GREEN", DungeonRoomOverlayStyle.minibossDoneCheckmark(mini, "WHITE"),
			"a cleared yellow room shows a green checkmark");
		equals("GREEN", DungeonRoomOverlayStyle.minibossDoneCheckmark(mini, "GREEN"),
			"a green yellow room stays green");
		equals(null, DungeonRoomOverlayStyle.minibossDoneCheckmark(mini, "NONE"),
			"an uncleared yellow room shows nothing extra");
		equals(null, DungeonRoomOverlayStyle.minibossDoneCheckmark(mini, null), "no map evidence shows nothing");
		equals(null, DungeonRoomOverlayStyle.minibossDoneCheckmark(DungeonMapGeometry.RoomType.ROOM, "WHITE"),
			"other room types keep their existing behavior");
		equals(null, DungeonRoomOverlayStyle.minibossDoneCheckmark(DungeonMapGeometry.RoomType.PUZZLE, "GREEN"),
			"only yellow rooms get the forced checkmark");
	}

	private static void roomLabelsKeepProgressWhenCheckmarksAreEnabled() {
		minibossRoomsShowGreenCheckWhenDone();
		BooleanSetting showCheckmarks = DungeonRoomOverlayModule.INSTANCE.booleanSettings().stream()
			.filter(setting -> "Show Checkmarks".equals(setting.name())).findFirst().orElseThrow();
		boolean original = showCheckmarks.value();
		try {
			showCheckmarks.setValue(true);
			String completeLabel = DungeonRoomMapLabel.build("Flags", 7, 7, "Both");
			DungeonRoomOverlayStyle.RoomDecoration enabled = DungeonRoomOverlayStyle.roomDecoration(
				showCheckmarks.value(), "GREEN", completeLabel);
			equals("GREEN", enabled.checkmark(), "the enabled renderer path retains the optional checkmark");
			equals("Flags\n7/7", enabled.label(), "Show Checkmarks=true still draws name and secret progress");
			showCheckmarks.setValue(false);
			DungeonRoomOverlayStyle.RoomDecoration disabled = DungeonRoomOverlayStyle.roomDecoration(
				showCheckmarks.value(), "GREEN", completeLabel);
			equals("", disabled.checkmark(), "the optional icon can be turned off independently");
			equals("Flags\n7/7", disabled.label(), "labels remain when optional checkmarks are off");
		} finally {
			showCheckmarks.setValue(original);
		}
		equals("Slime\n?/5", DungeonRoomMapLabel.build("Slime", -1, 5, "Both"),
			"unseen local progress displays unknown over the catalog maximum");
		equals("Slime\n0/5", DungeonRoomMapLabel.build("Slime", -1, 5, "Both", true),
			"a confirmed unopened room starts at zero of its catalog maximum");
		equals("?\n0/?", DungeonRoomMapLabel.build("Unknown", -1, 0, "Both", true),
			"an unopened room without catalog maximum displays zero over an unknown maximum");
		equals("?", DungeonRoomMapLabel.build("Unknown", -1, 0, "Both"),
			"unknown room names remain a question mark");
	}

	private static void secretProgressIsAttributedLocallyAndResetByLifecycle() {
		equals(new DungeonRoomSecretProgress.Counts(2, 6),
			DungeonRoomSecretProgress.parseActionbar(" 2/6 Secrets "), "secret progress accepts the overlay counter format");
		equals(new DungeonRoomSecretProgress.Counts(2, 6),
			DungeonRoomSecretProgress.parseActionbar("§aFound 2/6 Secrets §r (Dungeon)"),
			"the formatted actionbar counter is parsed when surrounded by other HUD text");
		check(DungeonRoomSecretProgress.parseActionbar("2/6 Secrets 3/6 Secrets") == null,
			"ambiguous actionbars with multiple counts are rejected");
		check(DungeonRoomSecretProgress.parseActionbar("7/6 Secrets") == null,
			"invalid counts above their maximum are rejected");
		DungeonRoomSecretProgress progress = new DungeonRoomSecretProgress();
		progress.enterRoom("2,3,8");
		check(progress.recordActionbar("0/6 Secrets", "2,3,8", 6), "a count is attributed to the active map room");
		check(!progress.recordActionbar("2/6 Secrets", "4,5", 6), "a different room cannot receive the active count");
		check(!progress.recordActionbar("2/5 Secrets", "2,3,8", 6), "catalog maximum mismatch is rejected");
		check(progress.recordActionbar("2/6 Secrets", "2,3,8", 6), "progress advances monotonically");
		check(!progress.recordActionbar("1/6 Secrets", "2,3,8", 6), "a stale lower overlay cannot roll progress back");
		progress.clearCurrentRoom();
		check(!progress.recordActionbar("3/6 Secrets", "2,3,8", 6), "boss transition clears active-room attribution");
		progress.enterRoom("4,5");
		check(progress.foundSecrets("2,3,8") == 2, "room progress survives revisits during a run");
		check(!progress.observeMapCheckmark("4,5", 6, "WHITE")
			&& !progress.observeMapCheckmark("4,5", 6, "RED")
			&& !progress.observeMapCheckmark("4,5", 6, "UNDISCOVERED"),
			"white, red, and unknown map states do not imply completion");
		check(progress.foundSecrets("4,5") == -1, "unconfirmed progress remains unknown");
		check(progress.observeMapCheckmark("4,5", 6, "GREEN"), "green map completion marks all secrets found");
		equals(6, progress.foundSecrets("4,5"), "green completion records the maximum count");
		check(!progress.recordActionbar("1/6 Secrets", "4,5", 6), "a delayed counter cannot undo green completion");
		progress.reset();
		check(progress.foundSecrets("2,3,8") == -1 && progress.currentRoomId() == null,
			"new-run, disconnect, and world/floor resets clear room counts and attribution");
		equals("2,7,8", DungeonMapProjection.roomIdForSegments(List.of(8, 2, 7, 2)),
			"room progress keys are deterministic sorted map-grid segment IDs");
	}

	private static void mapDisablePausesWithoutEndingTheDungeonSession() {
		DungeonRoomSecretProgress progress = new DungeonRoomSecretProgress();
		progress.enterRoom("2,3,8");
		check(progress.recordActionbar("2/6 Secrets", "2,3,8", 6),
			"progress exists before temporarily disabling the map");
		equals(DungeonRoomOverlayLifecycleRules.TickDisposition.PAUSE_DISABLED,
			DungeonRoomOverlayLifecycleRules.tickDisposition(false, true, true),
			"disabling the module during a ready dungeon pauses map processing");
		check(progress.foundSecrets("2,3,8") == 2, "a paused module retains run-scoped secret progress");
		equals(DungeonRoomOverlayLifecycleRules.TickDisposition.RESET_SESSION,
			DungeonRoomOverlayLifecycleRules.tickDisposition(false, false, true),
			"leaving the dungeon resets even while the map module is disabled");
		equals(DungeonRoomOverlayLifecycleRules.TickDisposition.RESET_SESSION,
			DungeonRoomOverlayLifecycleRules.tickDisposition(true, true, false),
			"losing the client world or player remains a reset boundary");
		equals(DungeonRoomOverlayLifecycleRules.TickDisposition.ACTIVE,
			DungeonRoomOverlayLifecycleRules.tickDisposition(true, true, true),
			"an enabled map in a ready dungeon continues normal processing");
		progress.reset();
		check(progress.foundSecrets("2,3,8") == -1, "session reset clears retained progress");
	}

	private static void verifiedRoomCoreFallbackUsesNormalMapRenderer() {
		check(DungeonRoomOverlayLifecycleRules.hasRenderableMapLayout(true, false, true),
			"a verified loaded-core room uses the normal map renderer while map pixels are pending");
		check(DungeonRoomOverlayLifecycleRules.hasRenderableMapLayout(true, true, false),
			"an authoritative map uses the normal map renderer");
		check(!DungeonRoomOverlayLifecycleRules.hasRenderableMapLayout(false, false, true),
			"an unavailable snapshot cannot render a room from a stale loaded-core fallback");
	}

	private static void mapSettingsMigrateOnceAndPersistTheirMarker() {
		Map<String, Boolean> toggles = new HashMap<>();
		toggles.put("Dungeon Room Map.Reveal Unopened Rooms", false);
		toggles.put("Dungeon Room Map.Show Checkmarks", true);
		Set<String> markers = new HashSet<>();
		check(DungeonRoomOverlaySettingsMigration.migrate(toggles, markers, "Dungeon Room Map"),
			"the legacy settings profile migrates once");
		check(toggles.get("Dungeon Room Map.Reveal Unopened Rooms")
			&& !toggles.get("Dungeon Room Map.Show Checkmarks"), "migration applies reference-style defaults");
		check(markers.contains(DungeonRoomOverlaySettingsMigration.MIGRATION_ID), "migration records a profile marker");
		toggles.put("Dungeon Room Map.Reveal Unopened Rooms", false);
		toggles.put("Dungeon Room Map.Show Checkmarks", true);
		check(!DungeonRoomOverlaySettingsMigration.migrate(toggles, markers, "Dungeon Room Map"),
			"a completed migration is not re-applied");
		check(!toggles.get("Dungeon Room Map.Reveal Unopened Rooms")
			&& toggles.get("Dungeon Room Map.Show Checkmarks"), "later explicit preferences survive reload migration");
		try {
			Class<?> dataType = Class.forName("geiler.addons.client.config.ModConfig$Data");
			var constructor = dataType.getDeclaredConstructor();
			constructor.setAccessible(true);
			Object data = constructor.newInstance();
			var migrationField = dataType.getDeclaredField("migrations");
			migrationField.setAccessible(true);
			migrationField.set(data, new HashSet<>(markers));
			Gson gson = new Gson();
			String json = gson.toJson(data);
			Object reloaded = gson.fromJson(json, dataType);
			@SuppressWarnings("unchecked") Set<String> savedMarkers = (Set<String>) migrationField.get(reloaded);
			check(savedMarkers.contains(DungeonRoomOverlaySettingsMigration.MIGRATION_ID),
				"the one-time migration marker survives config serialization and reload");
			Map<String, Boolean> savedChoices = new HashMap<>();
			savedChoices.put("Dungeon Room Map.Reveal Unopened Rooms", false);
			savedChoices.put("Dungeon Room Map.Show Checkmarks", true);
			DungeonRoomOverlaySettingsMigration.migrate(savedChoices, savedMarkers, "Dungeon Room Map");
			check(!savedChoices.get("Dungeon Room Map.Reveal Unopened Rooms")
				&& savedChoices.get("Dungeon Room Map.Show Checkmarks"),
				"reloaded migration marker leaves later saved values intact");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("config migration marker could not be serialized and reloaded", error);
		}
		Map<String, Boolean> alreadyNew = new HashMap<>();
		alreadyNew.put("Dungeon Room Map.Reveal Unopened Rooms", true);
		alreadyNew.put("Dungeon Room Map.Show Checkmarks", false);
		check(DungeonRoomOverlaySettingsMigration.migrate(alreadyNew, new HashSet<>(), "Dungeon Room Map"),
			"a profile already using new defaults still records the one-time marker");
		check(alreadyNew.get("Dungeon Room Map.Reveal Unopened Rooms")
			&& !alreadyNew.get("Dungeon Room Map.Show Checkmarks"), "new settings choices remain unchanged");
	}

	private static void mapDoorsUseOdinGeometryAndTypedPalettes() {
		var north = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 4,
			DungeonRoomOverlayGeometry.Direction.NORTH, 1.0f);
		equals(new DungeonRoomOverlayLayout.DoorRect(104, 96, 8, 4), north,
			"north doors are 8 px across and 4 px deep in the gap");
		var east = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 4,
			DungeonRoomOverlayGeometry.Direction.EAST, 1.0f);
		equals(new DungeonRoomOverlayLayout.DoorRect(116, 104, 4, 8), east,
			"east doors are 8 px across and 4 px deep in the gap");
		var south = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 4,
			DungeonRoomOverlayGeometry.Direction.SOUTH, 1.0f);
		equals(new DungeonRoomOverlayLayout.DoorRect(104, 116, 8, 4), south,
			"south doors are 8 px across and 4 px deep in the gap");
		var west = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 4,
			DungeonRoomOverlayGeometry.Direction.WEST, 1.0f);
		equals(new DungeonRoomOverlayLayout.DoorRect(96, 104, 4, 8), west,
			"west doors are 8 px across and 4 px deep in the gap");
		var northWithoutGap = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 0,
			DungeonRoomOverlayGeometry.Direction.NORTH, 1.0f);
		var eastWithoutGap = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 0,
			DungeonRoomOverlayGeometry.Direction.EAST, 1.0f);
		var southWithoutGap = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 0,
			DungeonRoomOverlayGeometry.Direction.SOUTH, 1.0f);
		var westWithoutGap = DungeonRoomOverlayLayout.doorRect(100, 100, 16, 0,
			DungeonRoomOverlayGeometry.Direction.WEST, 1.0f);
		equals(new DungeonRoomOverlayLayout.DoorRect(104, 100, 8, 0), northWithoutGap,
			"north doors have no depth to overdraw when the room gap is zero");
		equals(new DungeonRoomOverlayLayout.DoorRect(116, 104, 0, 8), eastWithoutGap,
			"east doors have no depth to overdraw when the room gap is zero");
		equals(new DungeonRoomOverlayLayout.DoorRect(104, 116, 8, 0), southWithoutGap,
			"south doors have no depth to overdraw when the room gap is zero");
		equals(new DungeonRoomOverlayLayout.DoorRect(100, 104, 0, 8), westWithoutGap,
			"west doors have no depth to overdraw when the room gap is zero");
		var doorColors = new DungeonRoomOverlayStyle.DoorColors(21, 22, 23, 24, 25, 26, 27);
		int[] expected = {21, 22, 23, 24, 25, 26, 27};
		int index = 0;
		for (DungeonRoomOverlayGeometry.DoorKind kind : DungeonRoomOverlayGeometry.DoorKind.values())
			equals(expected[index++], DungeonRoomOverlayStyle.doorColor(kind, doorColors),
				kind + " doors use their independently configured palette slot");
		var roomColors = new DungeonRoomOverlayStyle.RoomColors(31, 32, 33, 34, 35, 36, 37, 38, 39, 40);
		for (DungeonMapGeometry.RoomType special : List.of(DungeonMapGeometry.RoomType.PUZZLE,
			DungeonMapGeometry.RoomType.TRAP, DungeonMapGeometry.RoomType.MINIBOSS,
			DungeonMapGeometry.RoomType.CHAMPION, DungeonMapGeometry.RoomType.ENTRANCE,
			DungeonMapGeometry.RoomType.FAIRY, DungeonMapGeometry.RoomType.BLOOD,
			DungeonMapGeometry.RoomType.RARE)) {
			DungeonMapGeometry.RoomType selected = DungeonRoomOverlayStyle.dynamicDoorRoomType(
				DungeonMapGeometry.RoomType.ROOM, special);
			equals(special, selected, "normal dynamic door chooses adjacent " + special.label() + " room");
			equals(DungeonRoomOverlayStyle.roomColor(special, roomColors),
				DungeonRoomOverlayStyle.roomColor(selected, roomColors),
				"dynamic door color uses the configured " + special.label() + " room palette");
			equals(DungeonRoomOverlayStyle.roomColor(special, roomColors),
				DungeonRoomOverlayStyle.dynamicDoorColor(DungeonMapGeometry.RoomType.ROOM, special,
					roomColors, 99),
				"renderer dynamic door color resolves to the configured " + special.label() + " palette");
		}
		equals(99, DungeonRoomOverlayStyle.dynamicDoorColor(DungeonMapGeometry.RoomType.UNKNOWN,
			DungeonMapGeometry.RoomType.UNKNOWN, roomColors, 99),
			"dynamic door color uses the normal-door fallback when both sides are unknown");
	}

	private static void roomAndDoorColorsUseTheirConfiguredSlots() {
		var rooms = new DungeonRoomOverlayStyle.RoomColors(11, 12, 13, 14, 15, 16, 17, 18);
		var doors = new DungeonRoomOverlayStyle.DoorColors(21, 22, 23, 24, 25);
		equals(12, DungeonRoomOverlayStyle.roomColor(DungeonMapGeometry.RoomType.PUZZLE, rooms),
			"Puzzle uses its configured room color");
		var fullDoorPalette = new DungeonRoomOverlayStyle.DoorColors(21, 22, 23, 24, 25, 26, 27);
		equals(21, DungeonRoomOverlayStyle.doorColor(DungeonRoomOverlayGeometry.DoorKind.NORMAL, fullDoorPalette),
			"Normal door uses its configured door color");
		equals(22, DungeonRoomOverlayStyle.doorColor(DungeonRoomOverlayGeometry.DoorKind.GLASS, fullDoorPalette),
			"Glass door uses its configured door color");
		equals(23, DungeonRoomOverlayStyle.doorColor(DungeonRoomOverlayGeometry.DoorKind.START, fullDoorPalette),
			"Start door uses its configured door color");
		equals(24, DungeonRoomOverlayStyle.doorColor(DungeonRoomOverlayGeometry.DoorKind.WITHER, fullDoorPalette),
			"Wither door uses its configured door color");
		equals(25, DungeonRoomOverlayStyle.doorColor(DungeonRoomOverlayGeometry.DoorKind.BLOOD, fullDoorPalette),
			"Blood door uses its configured door color");
		equals(26, DungeonRoomOverlayStyle.doorColor(DungeonRoomOverlayGeometry.DoorKind.FAIRY, fullDoorPalette),
			"Fairy door uses its configured door color");
		equals("P", DungeonRoomOverlayStyle.roomText(DungeonMapGeometry.RoomType.PUZZLE, "Abbreviation"),
			"abbreviation text mode remains compact");
		equals("PUZZLE", DungeonRoomOverlayStyle.roomText(DungeonMapGeometry.RoomType.PUZZLE, "Room Type"),
			"full type text mode uses the observed type label");
		equals("", DungeonRoomOverlayStyle.roomText(DungeonMapGeometry.RoomType.PUZZLE, "Off"),
			"text-off mode draws no room label");
		equals(0x80402010, DungeonRoomOverlayStyle.darken(0x80804020, 0.5),
			"unopened darkening scales RGB while preserving source alpha");
	}

	private static void playerMarkersRemainClientObservedAndImmutable() {
		byte[] colors = blankMap();
		paintTile(colors, 40, 40, DungeonMapGeometry.RoomType.PUZZLE.mapColor());
		var player = new DungeonRoomOverlayGeometry.PlayerMarker(2, 2, "Local", true);
		var snapshot = scan(colors, 2, 2, 40, 40, List.of(), List.of(player));
		equals(List.of(player), snapshot.players(), "loaded-player marker is retained in the map snapshot");
		try {
			snapshot.players().clear();
			throw new AssertionError("player snapshot collection must be immutable");
		} catch (UnsupportedOperationException expected) { }
	}

	private static void localPlayerMarkerUsesOdinMapDecoration() {
		check(DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_U == 2
			&& DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_V == 0
			&& DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_WIDTH == 5
			&& DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_HEIGHT == 7
			&& DungeonRoomPlayerMarkerGeometry.MAP_DECORATION_TEXTURE_SIZE == 8,
			"the local marker uses Odin's 5x7 map-decoration crop");
		equals((float) Math.PI, DungeonRoomPlayerMarkerGeometry.facingRotationRadians(0.0f),
			"the map-decoration sprite keeps Odin's yaw-zero rotation");
	}

	private static void localPlayerMarkerTracksYawWhileGridStaysFixed() {
		BooleanSetting rotateMap = DungeonRoomOverlayModule.INSTANCE.booleanSettings().stream()
			.filter(setting -> "Rotate Map Around Player".equals(setting.name())).findFirst().orElseThrow();
		check(!rotateMap.value(), "fresh Dungeon Map config keeps the room grid fixed like Odin");
		equals((float) Math.PI, DungeonRoomPlayerMarkerGeometry.facingRotationRadians(0.0f),
		"the map-decoration marker follows the player's yaw");
		equals((float) (Math.PI * 1.5), DungeonRoomPlayerMarkerGeometry.facingRotationRadians(90.0f),
		"the map-decoration marker rotates while the grid stays fixed");
	}

	private static void mapRenderDataAndDoorGeometryAreCachedUntilInputsChange() {
		DungeonRoomOverlayRenderCache cache = new DungeonRoomOverlayRenderCache();
		var fixture = DungeonRoomOverlayPreview.sampleSnapshot();
		var data = cache.resolve(fixture, true);
		equals(data, cache.resolve(fixture, true), "unchanged map inputs reuse cached render data");
		var doors = data.doorGeometry(16, 4, 1.0f);
		check(doors == data.doorGeometry(16, 4, 1.0f), "unchanged door layout reuses cached door rectangles");
		check(doors != data.doorGeometry(16, 4, 0.5f), "door scale changes invalidate cached door rectangles");
		check(data != cache.resolve(fixture, false), "reveal setting changes rebuild visible map render data");
		var nextSnapshot = new DungeonRoomOverlayGeometry.Snapshot(true,
			new ArrayList<>(fixture.cells()), new ArrayList<>(fixture.rooms()), fixture.playerCell(),
			fixture.players(), fixture.reason(), fixture.predictions());
		var changed = cache.resolve(nextSnapshot, false);
		check(changed != data && cache.rebuildCount() == 3,
			"new room snapshot inputs invalidate map, label, and door caches");
	}

	private static void mapPreviewSettingIsFoldedAndVisibleByDefault() {
		DungeonRoomOverlayModule module = DungeonRoomOverlayModule.INSTANCE;
		BooleanSetting showPreview = module.booleanSettings().stream()
			.filter(setting -> "Show Preview".equals(setting.name())).findFirst().orElseThrow();
		boolean original = showPreview.rawValue();
		try {
			showPreview.reset();
			check(showPreview.value(), "the map preview setting is enabled by default");
			var group = module.groups().stream().filter(candidate -> "Map Preview".equals(candidate.name()))
				.findFirst().orElseThrow();
			check(group.collapsedByDefault() && group.toggle() == showPreview,
				"the production map preview sits under a collapsed-by-default disclosure");
		} finally {
			showPreview.setValue(original);
		}
	}

	private static void appearanceSettingsAreNestedWithoutChangingTheSettingRegistry() {
		DungeonRoomOverlayModule module = DungeonRoomOverlayModule.INSTANCE;
		SettingGroup appearance = module.groups().stream()
			.filter(group -> "Appearance".equals(group.name())).findFirst().orElseThrow();
		check(appearance.collapsedByDefault(), "Appearance starts collapsed to keep the map controls compact");
		equals(List.of("Layout", "Players", "Room Text and Icons", "Colors"),
			appearance.children().stream().map(SettingGroup::name).toList(),
			"Appearance groups visual settings into the requested nested sections");
		SettingGroup colors = appearance.children().stream()
			.filter(group -> "Colors".equals(group.name())).findFirst().orElseThrow();
		equals(List.of("Map", "Rooms", "Doors", "Unopened Rooms"),
			colors.children().stream().map(SettingGroup::name).toList(),
			"room, door, map, and unopened colors stay grouped under Appearance");
		check(module.groups().stream().anyMatch(group -> "Map Preview".equals(group.name()))
			&& module.groups().stream().anyMatch(group -> "Map Style".equals(group.name())),
			"preview and style actions remain separate top-level groups");

		List<Setting> expected = new ArrayList<>();
		expected.addAll(module.booleanSettings());
		expected.addAll(module.numberSettings());
		expected.addAll(module.choiceSettings());
		expected.addAll(module.colorSettings());
		expected.removeIf(setting -> !module.isSettingVisible(setting)
			|| "Player Marker".equals(setting.displayName())
			|| "Local Player Marker".equals(setting.displayName()));
		List<Setting> grouped = new ArrayList<>();
		for (SettingGroup group : module.groups()) collectGroupedSettings(group, grouped);
		List<Setting> groupedSettings = grouped.stream().filter(expected::contains).toList();
		equals(expected.size(), groupedSettings.size(),
			"reorganizing groups does not lose or duplicate visible config-backed settings");
		check(new HashSet<>(expected).equals(new HashSet<>(groupedSettings)),
			"all visible config-backed settings retain their existing objects");
		List<String> styleActions = module.groups().stream()
			.filter(group -> "Map Style".equals(group.name())).findFirst().orElseThrow()
			.settings().stream().filter(ModuleAction.class::isInstance)
			.map(Setting::displayName).toList();
		equals(List.of("Copy Style to Clipboard", "Import Style from Clipboard", "Reset All Map Settings"),
			styleActions, "map style actions remain grouped outside the config-backed setting registry");
	}

	private static void collectGroupedSettings(SettingGroup group, List<Setting> target) {
		if (group.toggle() != null) target.add(group.toggle());
		target.addAll(group.settings());
		for (SettingGroup child : group.children()) collectGroupedSettings(child, target);
	}

	private static void internalTwoByTwoRoomGapIsFilledWithoutTouchingOuterDoorGeometry() {
		List<DungeonRoomOverlayGeometry.Cell> footprint = List.of(
			new DungeonRoomOverlayGeometry.Cell(1, 1, DungeonMapGeometry.RoomType.ROOM, "two-by-two"),
			new DungeonRoomOverlayGeometry.Cell(2, 1, DungeonMapGeometry.RoomType.ROOM, "two-by-two"),
			new DungeonRoomOverlayGeometry.Cell(1, 2, DungeonMapGeometry.RoomType.ROOM, "two-by-two"),
			new DungeonRoomOverlayGeometry.Cell(2, 2, DungeonMapGeometry.RoomType.ROOM, "two-by-two"));
		List<DungeonRoomOverlayLayout.GapRect> intersections =
			DungeonRoomOverlayLayout.internalGapIntersections(footprint, 16, 4);
		equals(1, intersections.size(), "one complete 2x2 room gets one center intersection fill");
		DungeonRoomOverlayLayout.GapRect center = intersections.getFirst();
		equals(new DungeonRoomOverlayLayout.GapRect(36, 36, 4, 4), center,
			"center fill occupies only the four-pixel internal connector crossing");
		DungeonRoomOverlayLayout.DoorRect outerDoor = DungeonRoomOverlayLayout.doorRect(20, 20, 16, 4,
			DungeonRoomOverlayGeometry.Direction.WEST, 1.0f);
		check(!overlaps(center, outerDoor.x(), outerDoor.y(), outerDoor.width(), outerDoor.height()),
			"the center fill stays disjoint from the room's outer door geometry");
		check(DungeonRoomOverlayLayout.internalGapIntersections(footprint.subList(0, 3), 16, 4).isEmpty(),
			"partial footprints do not fill an unconfirmed gap intersection");
		check(DungeonRoomOverlayLayout.internalGapIntersections(footprint, 16, 0).isEmpty(),
			"zero-width room gaps need no intersection fill");
	}

	private static boolean overlaps(DungeonRoomOverlayLayout.GapRect first,
		int x, int y, int width, int height) {
		return first.x() < x + width && first.x() + first.width() > x
			&& first.y() < y + height && first.y() + first.height() > y;
	}

	private static void playerProjectionCrossesRoomBoundariesWithoutRoomSizedJumps() {
		var before = geiler.addons.client.dungeon.helpers.DungeonPlayerMap.projectLoadedPosition(
			"Local", true, "Mage", -170.0, -184.0, 0.0f);
		var after = geiler.addons.client.dungeon.helpers.DungeonPlayerMap.projectLoadedPosition(
			"Local", true, "Mage", -167.0, -184.0, 0.0f);
		check(before != null && after != null, "loaded local position projects in both adjacent rooms");
		double beforePixel = before.x() * 20.0 + before.offsetX() * 16.0;
		double afterPixel = after.x() * 20.0 + after.offsetX() * 16.0;
		check(afterPixel > beforePixel && afterPixel - beforePixel < 8.0,
			"crossing a room seam advances through only the visual gap and local movement distance");
		check(!DungeonRoomPlayerMarkerGeometry.shouldSnap(-170, -184, -169, -184),
			"ordinary adjacent movement remains interpolated");
		check(DungeonRoomPlayerMarkerGeometry.shouldSnap(-170, -184, -138, -184),
			"a large discontinuity is marked for an immediate position snap");
	}

	private static void mapVisualModesAndYellowMinibossColorsStayDistinct() {
		var colors = new DungeonRoomOverlayStyle.RoomColors(11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
		equals(14, DungeonRoomOverlayStyle.roomColor(DungeonMapGeometry.RoomType.MINIBOSS, colors),
			"Miniboss rooms use their own configured color");
		equals(20, DungeonRoomOverlayStyle.roomColor(DungeonMapGeometry.RoomType.CHAMPION, colors),
			"Yellow/Champion rooms use their separate configured color");
		var roomTypes = DungeonMapGeometry.RoomType.values();
		equals(List.of("ENTRANCE", "ROOM", "RARE", "PUZZLE", "TRAP", "MINIBOSS", "CHAMPION", "FAIRY", "BLOOD", "UNKNOWN"),
			java.util.Arrays.stream(roomTypes).map(DungeonMapGeometry.RoomType::name).toList(),
			"the map keeps every existing room type and its enum order");
		equals(List.of(30, 63, 63, 66, 62, 74, 74, 82, 18, 85),
			java.util.Arrays.stream(roomTypes).map(DungeonMapGeometry.RoomType::mapColor).toList(),
			"the map keeps every existing room type color");
		equals(List.of("entrance", "room", "rare", "puzzle", "trap", "miniboss", "champion", "fairy", "blood", "unknown"),
			java.util.Arrays.stream(roomTypes).map(DungeonMapGeometry.RoomType::label).toList(),
			"the map keeps every existing room type label");
		Map<Integer, DungeonMapGeometry.RoomType> expectedTypes = Map.of(
			30, DungeonMapGeometry.RoomType.ENTRANCE,
			63, DungeonMapGeometry.RoomType.ROOM,
			66, DungeonMapGeometry.RoomType.PUZZLE,
			62, DungeonMapGeometry.RoomType.TRAP,
			74, DungeonMapGeometry.RoomType.CHAMPION,
			82, DungeonMapGeometry.RoomType.FAIRY,
			18, DungeonMapGeometry.RoomType.BLOOD,
			85, DungeonMapGeometry.RoomType.UNKNOWN);
		expectedTypes.forEach((color, type) -> equals(type, DungeonMapGeometry.RoomType.fromColor(color),
			"map color " + color + " keeps its room type classification"));
		equals(DungeonMapGeometry.RoomType.UNKNOWN, DungeonMapGeometry.RoomType.fromColor(0),
			"unrecognized map colors retain the UNKNOWN fallback");
		equals(DungeonMapGeometry.RoomType.UNKNOWN, DungeonMapGeometry.RoomType.fromColor(-1),
			"negative map colors retain the UNKNOWN fallback");
		equals(DungeonMapGeometry.RoomType.UNKNOWN, DungeonMapGeometry.RoomType.fromColor(256),
			"map classification compares the full integer input");
		equals(DungeonRoomOverlayStyle.IconStyle.ILLEGAL_MAP, DungeonRoomOverlayStyle.iconStyle("IllegalMap"),
			"IllegalMap uses the local textured status icon renderer");
		equals(DungeonRoomOverlayStyle.IconStyle.NEU, DungeonRoomOverlayStyle.iconStyle("NEU"),
			"NEU has its own disc-and-glyph renderer");
		equals(DungeonRoomOverlayStyle.IconStyle.VANILLA_NEW, DungeonRoomOverlayStyle.iconStyle("Vanilla New"),
			"Vanilla New has a thin local glyph renderer");
		equals(DungeonRoomOverlayStyle.IconStyle.VANILLA_OLD, DungeonRoomOverlayStyle.iconStyle("Vanilla Old"),
			"Vanilla Old has a thick local glyph renderer");
		equals(DungeonRoomOverlayStyle.TextShadow.NONE, DungeonRoomOverlayStyle.textShadow("None"),
			"None shadow mode is selectable");
		equals(DungeonRoomOverlayStyle.TextShadow.DROP, DungeonRoomOverlayStyle.textShadow("Drop"),
			"Drop shadow mode is selectable");
		equals(DungeonRoomOverlayStyle.TextShadow.OUTLINE, DungeonRoomOverlayStyle.textShadow("Outline"),
			"Outline shadow mode is selectable");
		var first = new DungeonRoomOverlayGeometry.Cell(0, 0, DungeonMapGeometry.RoomType.ROOM, "r");
		var second = new DungeonRoomOverlayGeometry.Cell(1, 0, DungeonMapGeometry.RoomType.ROOM, "r");
		equals(new DungeonRoomOverlayStyle.Anchor(0, 0),
			DungeonRoomOverlayStyle.alignmentAnchor(List.of(first, second), "Top Left"),
			"Top Left alignment anchors to the top-left occupied cell");
		equals(new DungeonRoomOverlayStyle.Anchor(1, 0),
			DungeonRoomOverlayStyle.alignmentAnchor(List.of(first, second), "Top Right"),
			"Top Right alignment anchors to the top-right occupied cell");
	}

	private static void mapStyleJsonAndBase64RoundTripWithoutPartialMutation() {
		BooleanSetting enabled = new BooleanSetting("Enabled", false);
		enabled.setValue(true);
		NumberSetting scale = new NumberSetting("Scale", 0.0f, 4.0f, 1.0f);
		scale.setValue(2.5f);
		ChoiceSetting mode = new ChoiceSetting("Mode", "A", "A", "B");
		mode.setValue("B");
		ColorSetting tint = new ColorSetting("Tint", "Tint", 1, 2, 3, 4);
		tint.set(100, 110, 120, 130);
		BooleanSetting useMcFontRenderer = new BooleanSetting("Use MC Font Renderer", true);
		useMcFontRenderer.setValue(false);
		List<Setting> settings = List.of(enabled, scale, mode, tint, useMcFontRenderer);
		String json = DungeonRoomMapStyleCodec.encode(settings);
		var decodedJson = DungeonRoomMapStyleCodec.decodeClipboard(json);
		check(decodedJson.success() && DungeonRoomMapStyleCodec.appliesTo(settings, decodedJson.decoded()),
			"exported editable JSON validates against the same settings");
		String base64 = DungeonRoomMapStyleCodec.encodeBase64(settings);
		var decodedBase64 = DungeonRoomMapStyleCodec.decodeClipboard(base64);
		check(decodedBase64.success() && DungeonRoomMapStyleCodec.appliesTo(settings, decodedBase64.decoded()),
			"exported Base64 imports through the clipboard decoder");
		enabled.reset(); scale.reset(); mode.reset(); tint.set(1, 2, 3, 4); useMcFontRenderer.reset();
		DungeonRoomMapStyleCodec.apply(settings, decodedJson.decoded());
		check(enabled.value(), "JSON restore preserves toggles");
		check(!useMcFontRenderer.value(), "JSON restore preserves the selected map text renderer mode");
		equals(2.5f, scale.value(), "JSON restore preserves bounded scale values");
		equals("B", mode.value(), "JSON restore preserves choice values");
		equals(100, tint.red(), "JSON restore preserves colors");
		equals(130, tint.alpha(), "JSON restore preserves alpha");
		var invalid = new DungeonRoomMapStyleCodec.Decoded(Map.of(), Map.of(), Map.of("Mode", "not-a-choice"), Map.of());
		check(!DungeonRoomMapStyleCodec.appliesTo(settings, invalid), "unknown choice values are rejected before apply");
		equals("B", mode.value(), "rejected style data does not partially mutate current settings");
	}

	private static void moduleBackedMapStyleRoundTripCoversRepeatedSettingNames() {
		DungeonRoomOverlayModule module = DungeonRoomOverlayModule.INSTANCE;
		List<Setting> settings = new ArrayList<>();
		settings.addAll(module.booleanSettings());
		settings.addAll(module.numberSettings());
		settings.addAll(module.choiceSettings());
		settings.addAll(module.colorSettings());
		BooleanSetting legacyTextShadow = module.booleanSettings().stream()
			.filter(setting -> "Text Shadow".equals(setting.name())).findFirst().orElseThrow();
		ChoiceSetting textShadowMode = module.choiceSettings().stream()
			.filter(setting -> "Text Shadow".equals(setting.name())).findFirst().orElseThrow();
		legacyTextShadow.setValue(true);
		textShadowMode.setValue("Outline");

		String exported = DungeonRoomMapStyleCodec.encodeBase64(settings);
		var result = DungeonRoomMapStyleCodec.decodeClipboard(exported);
		check(result.success() && DungeonRoomMapStyleCodec.appliesTo(settings, result.decoded()),
			"the real Dungeon Room Map style validates its Base64 round-trip with repeated names across types");
		check(Boolean.TRUE.equals(result.decoded().toggles().get("Text Shadow"))
			&& "Outline".equals(result.decoded().choices().get("Text Shadow")),
			"the module export keeps the legacy toggle and shadow-mode choice in their typed fields");

		check(DungeonRoomMapStyleCodec.reset(settings), "module-backed style settings reset before restore");
		DungeonRoomMapStyleCodec.apply(settings, result.decoded());
		check(legacyTextShadow.value() && "Outline".equals(textShadowMode.value()),
			"module-backed import restores both same-named settings independently");
		check(DungeonRoomMapStyleCodec.reset(settings), "module-backed style check restores defaults afterward");
	}

	private static void rasterTextRendererIsBoundedAndKeepsStyleVariantsDistinct() {
		DungeonRoomMapRasterText renderer = new DungeonRoomMapRasterText();
		var plain = renderer.get("Shadow Assassin", 0xFFFFFFFF, 9, DungeonRoomMapRasterText.Shadow.NONE);
		check(plain != null && plain == renderer.get("Shadow Assassin", 0xFFFFFFFF, 9,
			DungeonRoomMapRasterText.Shadow.NONE), "raster text reuses its cached entry");
		var outlined = renderer.get("Shadow Assassin", 0xFFFFFFFF, 9, DungeonRoomMapRasterText.Shadow.OUTLINE);
		check(outlined != null && !plain.textureKey().equals(outlined.textureKey()),
			"shadow mode participates in the raster-text texture identity");
		check(renderer.width("Shadow Assassin", 9) > 0 && renderer.lineHeight(9) > 0,
			"custom renderer provides nonzero text measurements for HUD layout");
		try (var png = plain.openPng()) {
			var decoded = ImageIO.read(png);
			check(decoded != null && decoded.getWidth() == plain.imageWidth()
				&& decoded.getHeight() == plain.imageHeight(),
				"cached raster output is a decodable PNG with bounded dimensions");
		} catch (java.io.IOException invalidPng) {
			throw new AssertionError("raster output PNG should be readable", invalidPng);
		}
		check(renderer.get("two\nlines", 0xFFFFFFFF, 9, DungeonRoomMapRasterText.Shadow.NONE) == null,
			"line breaks are rejected rather than creating an unbounded multi-line map texture");
		for (int index = 0; index < DungeonRoomMapRasterText.MAX_CACHE_ENTRIES + 8; index++) {
			var rendered = renderer.get("cache-entry-" + index, 0xFF00AAFF, 9,
				DungeonRoomMapRasterText.Shadow.DROP);
			check(rendered != null, "short map labels rasterize within the per-entry limit");
		}
		check(renderer.cachedEntryCount() <= DungeonRoomMapRasterText.MAX_CACHE_ENTRIES
			&& renderer.cachedByteCount() <= DungeonRoomMapRasterText.MAX_CACHE_BYTES,
			"raster cache enforces entry-count and encoded-byte bounds");
	}

	private static void mapStyleClipboardRejectsOversizedInput() {
		String oversizedJson = "{\"x\":\"" + "x".repeat(32 * 1024) + "\"}";
		var plain = DungeonRoomMapStyleCodec.decodeClipboard(oversizedJson);
		check(!plain.success() && plain.error().contains("32 KiB limit"),
			"oversized editable JSON is rejected using its original character count");

		String oversizedUtf8 = "{\"x\":\"" + "é".repeat(16_383) + "\"}";
		var utf8 = DungeonRoomMapStyleCodec.decodeClipboard(oversizedUtf8);
		check(!utf8.success() && utf8.error().contains("32 KiB limit"),
			"multibyte JSON is rejected by UTF-8 byte count before parsing");

		String decodedOverLimit = Base64.getEncoder().encodeToString(new byte[32 * 1024 + 1]);
		var decoded = DungeonRoomMapStyleCodec.decodeClipboard(decodedOverLimit);
		check(!decoded.success() && decoded.error().contains("32 KiB limit"),
			"Base64 that decodes one byte over the limit is rejected before JSON parsing");

		String encodedOverLimit = Base64.getEncoder().encodeToString(new byte[32 * 1024 + 2]);
		var encoded = DungeonRoomMapStyleCodec.decodeClipboard(encodedOverLimit);
		check(!encoded.success() && encoded.error().contains("32 KiB limit"),
			"Base64 input beyond the encoded character bound is rejected early");
	}

	private static void mapStyleRejectsValuesOutsideSettingBounds() {
		NumberSetting scale = new NumberSetting("Scale", 0.5f, 5.0f, 3.6f);
		ColorSetting opaque = new ColorSetting("Opaque", 1, 2, 3);
		List<Setting> settings = List.of(scale, opaque);
		var invalidScale = new DungeonRoomMapStyleCodec.Decoded(Map.of(), Map.of("Scale", 5.5f), Map.of(), Map.of());
		check(!DungeonRoomMapStyleCodec.appliesTo(settings, invalidScale),
			"map-style numbers outside the target setting range are rejected");
		var invalidColor = new DungeonRoomMapStyleCodec.Decoded(Map.of(), Map.of(), Map.of(),
			Map.of("Opaque", new DungeonRoomMapStyleCodec.Rgba(10, 20, 30, 254)));
		check(!DungeonRoomMapStyleCodec.appliesTo(settings, invalidColor),
			"map-style alpha cannot change a setting that is opaque by definition");
		List<Setting> duplicateChoiceIds = List.of(new ChoiceSetting("Duplicate", "A", "A"),
			new ChoiceSetting("Duplicate", "B", "B"));
		var duplicateChoice = new DungeonRoomMapStyleCodec.Decoded(Map.of(), Map.of(),
			Map.of("Duplicate", "A"), Map.of());
		check(DungeonRoomMapStyleCodec.encode(duplicateChoiceIds) == null
			&& !DungeonRoomMapStyleCodec.appliesTo(duplicateChoiceIds, duplicateChoice),
			"same-type duplicate setting keys are rejected instead of silently overwriting a style value");
		try {
			DungeonRoomMapStyleCodec.apply(settings, invalidScale);
			throw new AssertionError("out-of-range map-style import must be rejected before apply");
		} catch (IllegalArgumentException expected) {
			// Rejection must leave all current values unchanged.
		}
		equals(3.6f, scale.value(), "rejected map-style values do not clamp or mutate current settings");
		equals(1, opaque.red(), "rejected map-style values do not partially apply later entries");
	}

	private static void mapStyleResetRestoresRequestedDefaults() {
		BooleanSetting reveal = new BooleanSetting("Reveal Unopened Rooms", true);
		reveal.setValue(false);
		BooleanSetting rotateMap = new BooleanSetting("Rotate Map Around Player", false);
		rotateMap.setValue(true);
		ChoiceSetting iconStyle = new ChoiceSetting("Icon Style", "IllegalMap", "IllegalMap", "NEU");
		iconStyle.setValue("NEU");
		NumberSetting scale = new NumberSetting("Map Scale", 0.5f, 5.0f, 3.6f);
		scale.setValue(2.0f);
		ColorSetting unopened = new ColorSetting("Unopened Room", 1, 2, 3);
		unopened.set(20, 30, 40, 255);
		List<Setting> settings = List.of(reveal, rotateMap, iconStyle, scale, unopened);
		check(DungeonRoomMapStyleCodec.reset(settings), "map-style reset accepts the module's supported settings");
		check(reveal.value(), "reset reveals verified unopened rooms by default");
		check(!rotateMap.value(), "reset keeps the room grid fixed and player arrow yaw-aware");
		equals("IllegalMap", iconStyle.value(), "reset restores the requested Illegal Map item-icon style");
		equals(3.6f, scale.value(), "reset restores layout settings to their declared defaults");
		equals(1, unopened.red(), "reset restores colors to their declared defaults");
	}

	private static byte[] blankMap() { return new byte[DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE]; }

	private static boolean hasObservableRetentionResultApi() {
		try {
			return DungeonRoomPlayerMarkerRetention.Result.class.getConstructor(
				List.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class) != null
				&& DungeonRoomPlayerMarkerRetention.Result.class.getMethod("players").getReturnType() == List.class
				&& DungeonRoomPlayerMarkerRetention.Result.class.getMethod("diagnostics").getReturnType() == String.class;
		} catch (ReflectiveOperationException exception) {
			return false;
		}
	}

	private static void paintTile(byte[] colors, int x, int z, int color) {
		for (int dz = 0; dz < ROOM_SIZE; dz++) for (int dx = 0; dx < ROOM_SIZE; dx++) {
			colors[x + dx + ((z + dz) << 7)] = (byte) color;
		}
	}

	private static DungeonRoomOverlayGeometry.Snapshot scan(byte[] colors, int x, int z,
		int mapX, int mapZ, List<DungeonRoomOverlayGeometry.ObservedDoor> doors) {
		return scan(colors, x, z, mapX, mapZ, doors, List.of());
	}

	private static DungeonRoomOverlayGeometry.Snapshot scan(byte[] colors, int x, int z,
		int mapX, int mapZ, List<DungeonRoomOverlayGeometry.ObservedDoor> doors,
		List<DungeonRoomOverlayGeometry.PlayerMarker> players) {
		var calibration = new DungeonMapGeometry.Calibration(true,
			new DungeonMapGeometry.MapPoint(20, 20), ROOM_SIZE, null);
		var currentRoom = new DungeonMapGeometry.RoomGeometry(true, calibration,
			new DungeonMapGeometry.MapPoint(mapX, mapZ), (byte) DungeonMapGeometry.RoomType.ROOM.mapColor(),
			DungeonMapGeometry.RoomType.ROOM, List.of(new DungeonMapGeometry.CellOffset(0, 0)), "1x1", null);
		var cell = new DungeonGridRules.Cell(x, z, DungeonGridRules.GRID_MIN + x * DungeonGridRules.CELL_STRIDE,
			DungeonGridRules.GRID_MIN + z * DungeonGridRules.CELL_STRIDE);
		return DungeonRoomOverlayGeometry.scan(colors, calibration, currentRoom, cell, doors, players);
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
