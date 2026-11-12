package geiler.addons.client.dungeon;

import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.impl.DungeonRoomAlertModule;

import java.util.List;

/** Focused pure acceptance checks for visual door selection and Guide phase output transitions. */
public final class DungeonVisualBehaviorChecks {
	private DungeonVisualBehaviorChecks() { }

	public static void main(String[] args) {
		if (args != null && args.length == 1 && "--room-alerts".equals(args[0])) {
			roomAlertTransitions();
			roomAlertControls();
			return;
		}
		run();
	}

	public static void run() {
		DungeonRoomOverlayChecks.run();
		DungeonRoomOrientationChecks.run();
		DungeonMimicCatalogChecks.run();
		doorReplacementSelection();
		phaseOutputTransitions();
		roomAlertTransitions();
		roomAlertControls();
		secretWaypointClickFeedback();
	}

	private static void roomAlertControls() {
		var titles = DungeonRoomAlertModule.INSTANCE.textSettings().stream()
			.filter(setting -> setting.name().endsWith("Title Text")).toList();
		check(titles.size() == 2 && titles.stream().anyMatch(setting -> "Room Cleared".equals(setting.value()))
			&& titles.stream().anyMatch(setting -> "Secrets Cleared".equals(setting.value())),
			"room clear and secrets complete retain independent title text settings");
		var durations = DungeonRoomAlertModule.INSTANCE.numberSettings().stream()
			.filter(setting -> "Title Duration (ticks)".equals(setting.name())).toList();
		check(durations.size() == 1 && durations.getFirst().value() == 60.0f
			&& durations.getFirst().max() == 200.0f,
			"room alerts retain their shared title duration setting and default");
		check(DungeonRoomAlertModule.INSTANCE.colorSettings().size() == 2,
			"room-cleared and secrets-cleared titles have separate configurable colors");
		List<TextSetting> sounds = DungeonRoomAlertModule.INSTANCE.textSettings().stream()
			.filter(setting -> setting.name().endsWith("Sound ID")).toList();
		check(sounds.size() == 2 && !sounds.get(0).name().equals(sounds.get(1).name()),
			"room alert sound IDs have independent persisted settings");
		check(sounds.stream().anyMatch(setting -> "entity.player.levelup".equals(setting.value()))
			&& sounds.stream().anyMatch(setting -> "block.note_block.pling".equals(setting.value())),
			"room alerts use distinct level-up and pling defaults");
	}

	private static void roomAlertTransitions() {
		DungeonRoomAlertTracker tracker = new DungeonRoomAlertTracker();
		var green = DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN;
		var white = DungeonMapGeometry.RoomClearStatus.CLEARED_WHITE;
		var uncleared = DungeonMapGeometry.RoomClearStatus.UNCLEARED;
		var unknown = DungeonMapGeometry.RoomClearStatus.UNKNOWN;
		check(!tracker.observe(List.of("F7|map|2,2"), unknown).events().secretsComplete(),
			"unknown map samples do not create a secret-complete alert");
		var firstGreen = observeStable(tracker, green, "F7|map|2,2").events();
		check(!firstGreen.roomCleared() && !firstGreen.secretsComplete(),
			"a room first observed green establishes its state without alerting on entry");
		check(!observeStable(tracker, green, "F7|map|2,2").events().secretsComplete(),
			"a stable green room does not replay the alert");
		var firstWhite = observeStable(tracker, white, "F7|map|3,2").events();
		check(!firstWhite.roomCleared() && !firstWhite.secretsComplete(),
			"a first-observed white clear mark establishes a silent baseline");
		tracker.observe(List.of("F7|map|2,2"), unknown);
		check(!observeStable(tracker, green, "F7|map|2,2").events().secretsComplete(),
			"a detector gap and re-entry into a green room do not replay its alert");
		check(!observeStable(tracker, green, "F7|map|4,2").events().secretsComplete(),
			"a different room first seen green is recorded without an entry alert");

		var baseline = observeStable(tracker, uncleared, "F7|map|5,2").events();
		check(!baseline.roomCleared() && !baseline.secretsComplete(), "an initially incomplete room establishes a baseline");
		var clear = observeStable(tracker, white, "F7|map|5,2").events();
		check(clear.roomCleared() && !clear.secretsComplete(), "white checkmark emits only the room-cleared alert");
		var secret = observeStable(tracker, green, "F7|map|5,2").events();
		check(!secret.roomCleared() && secret.secretsComplete(), "green transition emits only the secrets-complete alert");
		check(!observeStable(tracker, green, "F7|map|5,2").events().secretsComplete(),
			"repeated green observations emit at most one secrets alert");
		observeStable(tracker, white, "F7|map|5,2");
		check(!observeStable(tracker, green, "F7|map|5,2").events().secretsComplete(),
			"a green-white-green map flicker cannot replay a secrets alert in the same room");
		observeStable(tracker, uncleared, "F7|map|5,2");
		check(!observeStable(tracker, white, "F7|map|5,2").events().roomCleared(),
			"a white-clear map flicker cannot replay a room-cleared alert in the same room");

		check(!observeStable(tracker, green, "F7|map|6,2").events().secretsComplete(),
			"a newly observed room segment does not alert when already green");
		check(!observeStable(tracker, green, "F7|map|6,2", "F7|map|7,2").events().secretsComplete(),
			"an expanded map footprint overlapping the same room keeps its green baseline");
		tracker.observe(List.of("F7|map|6,2"), unknown);
		check(!observeStable(tracker, green, "F7|map|7,2").events().secretsComplete(),
			"crossing a room gap and returning through an overlapping footprint does not replay alerts");

		observeStable(tracker, uncleared, "F7|map|8,2");
		observeStable(tracker, uncleared, "F7|map|9,2");
		var mergedGreen = observeStable(tracker, green, "F7|map|8,2", "F7|map|9,2").events();
		check(mergedGreen.roomCleared() && !mergedGreen.secretsComplete(),
			"merging incomplete histories preserves one room-clear event and the conservative secrets baseline");

		tracker.clear();
		observeStable(tracker, uncleared, "F7|map|10,2");
		observeStable(tracker, uncleared, "F7|map|11,2");
		var mergedWhite = observeStable(tracker, white, "F7|map|10,2", "F7|map|11,2").events();
		check(mergedWhite.roomCleared() && !mergedWhite.secretsComplete(),
			"merging incomplete histories into a white state emits one room-clear event");
		var repeatedMergedWhite = observeStable(tracker, white, "F7|map|10,2", "F7|map|11,2").events();
		check(!repeatedMergedWhite.roomCleared() && !repeatedMergedWhite.secretsComplete(),
			"a merged clear event remains latched across repeated stable observations");
		var laterGreen = observeStable(tracker, green, "F7|map|10,2", "F7|map|11,2").events();
		check(!laterGreen.roomCleared() && laterGreen.secretsComplete(),
			"a later green transition shows secrets completion without replaying room clear");

		tracker.clear();
		observeStable(tracker, white, "F7|map|12,2");
		observeStable(tracker, uncleared, "F7|map|13,2");
		var mergeWithLatchedClear = observeStable(tracker, white, "F7|map|12,2", "F7|map|13,2").events();
		check(!mergeWithLatchedClear.roomCleared(),
			"a merged history with an already-latched clear does not produce a duplicate room-clear event");
		tracker.clear();
		check(tracker.snapshot().isEmpty(), "run lifecycle reset clears remembered room alert states");
	}

	private static DungeonRoomAlertTracker.Decision observeStable(DungeonRoomAlertTracker tracker,
		DungeonMapGeometry.RoomClearStatus status, String... cells) {
		DungeonRoomAlertTracker.Decision decision = null;
		for (int i = 0; i < 5; i++) decision = tracker.observe(List.of(cells), status);
		return decision;
	}

	private static void doorReplacementSelection() {
		var enabled = new DungeonDoorBlockReplacementRules.Toggles(true, true, true);
		equals(DungeonDoorBlockReplacementRules.Glass.CLEAR,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.START,
				DungeonDoorBlockReplacementRules.Surface.CHISELED_STONE_BRICKS, enabled), "Start boxes use clear glass");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.START,
			DungeonDoorBlockReplacementRules.Surface.TERRACOTTA, enabled),
			"ordinary terracotta is not treated as a Start replacement source");
		equals(DungeonDoorBlockReplacementRules.Glass.BLACK,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.WITHER,
				DungeonDoorBlockReplacementRules.Surface.COAL_BLOCK, enabled), "Wither boxes use black glass");
		equals(DungeonDoorBlockReplacementRules.Glass.RED,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
				DungeonDoorBlockReplacementRules.Surface.RED_TERRACOTTA, enabled), "Blood boxes use red glass");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
			DungeonDoorBlockReplacementRules.Surface.OTHER, enabled), "unrelated blocks remain unchanged");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.WITHER,
			DungeonDoorBlockReplacementRules.Surface.COAL_BLOCK,
			new DungeonDoorBlockReplacementRules.Toggles(true, false, true)), "door toggle disables substitution");
		equals(DungeonDoorBlockReplacementRules.Surface.RED_TERRACOTTA,
			DungeonDoorBlockReplacementRules.surface("red_terracotta"), "red terracotta classified");
		equals(DungeonDoorBlockReplacementRules.Surface.OTHER,
			DungeonDoorBlockReplacementRules.surface("red_glazed_terracotta"), "glazed terracotta is not mistaken for ordinary terracotta");
		equals(DungeonDoorBlockReplacementRules.Surface.COAL_BLOCK,
			DungeonDoorBlockReplacementRules.surface("coal_block"), "coal block classified");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.NORMAL,
			DungeonDoorBlockReplacementRules.Surface.COAL_BLOCK, enabled), "normal doors keep their source block");
	}

	private static void phaseOutputTransitions() {
		DungeonGuidePhaseOutputChecks.run();
	}

	private static void secretWaypointClickFeedback() {
		var selection = new SecretWaypointClickFeedbackRules.Selection(
			true, true, true, true, true, true);
		equals(SecretWaypointClickFeedbackRules.Type.CHEST,
			SecretWaypointClickFeedbackRules.fromCandidateLabel("Chest candidate"), "chest click type is recognized");
		equals(SecretWaypointClickFeedbackRules.Type.BAT,
			SecretWaypointClickFeedbackRules.fromCandidateLabel("Bat candidate"), "bat click type is recognized");
		equals(SecretWaypointClickFeedbackRules.Type.ITEM_FRAME,
			SecretWaypointClickFeedbackRules.fromCandidateLabel("Item frame candidate"), "item frame click type is recognized");
		equals(SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP,
			SecretWaypointClickFeedbackRules.fromCandidateLabel("Essence drop candidate"), "essence click type is recognized");
		equals(SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY,
			SecretWaypointClickFeedbackRules.fromCandidateLabel("Redstone key candidate"), "redstone key click type is recognized");
		equals(SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM,
			SecretWaypointClickFeedbackRules.fromCandidateLabel("Dropped item candidate"), "dropped item click type is recognized");
		equals(SecretWaypointClickFeedbackRules.Type.UNKNOWN,
			SecretWaypointClickFeedbackRules.fromCandidateLabel("Unverified candidate"), "unknown candidates cannot activate feedback");
		check(SecretWaypointClickFeedbackRules.selected(SecretWaypointClickFeedbackRules.Type.CHEST, selection),
			"enabled candidate type may produce feedback");
		check(SecretWaypointClickFeedbackRules.selected(SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM,
			new SecretWaypointClickFeedbackRules.Selection(false, true, false, false, false, false)),
			"dropped-item switch selects dropped-item feedback");
		check(SecretWaypointClickFeedbackRules.selected(SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP,
			new SecretWaypointClickFeedbackRules.Selection(false, false, true, false, false, false)),
			"essence switch selects essence feedback");
		check(SecretWaypointClickFeedbackRules.selected(SecretWaypointClickFeedbackRules.Type.BAT,
			new SecretWaypointClickFeedbackRules.Selection(false, false, false, true, false, false)),
			"bat switch selects bat feedback");
		check(SecretWaypointClickFeedbackRules.selected(SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY,
			new SecretWaypointClickFeedbackRules.Selection(false, false, false, false, true, false)),
			"redstone-key switch selects key feedback");
		check(SecretWaypointClickFeedbackRules.selected(SecretWaypointClickFeedbackRules.Type.LEVER,
			new SecretWaypointClickFeedbackRules.Selection(false, false, false, false, false, true)),
			"lever switch selects lever feedback");
		check(SecretWaypointClickFeedbackRules.selected(SecretWaypointClickFeedbackRules.Type.ITEM_FRAME,
			new SecretWaypointClickFeedbackRules.Selection(false, true, false, false, false, false)),
			"Dropped Items switch selects item-frame feedback");
		check(!SecretWaypointClickFeedbackRules.selected(SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP,
			new SecretWaypointClickFeedbackRules.Selection(true, true, false, true, true, true)),
			"disabled candidate type cannot produce feedback");
		equals(SecretWaypointClickFeedbackRules.Action.IGNORE,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.UNKNOWN,
				selection, false, true), "unknown target types cannot activate feedback");
		equals(SecretWaypointClickFeedbackRules.Action.ACTIVATE_NOW,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.CHEST,
				selection, false, false), "selected room block activates on the user's click");
		equals(SecretWaypointClickFeedbackRules.Action.IGNORE,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.CHEST,
				selection, true, false), "boss secret block is off unless separately enabled");
		equals(SecretWaypointClickFeedbackRules.Action.ACTIVATE_NOW,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.LEVER,
				selection, true, true), "boss block can activate with its separate setting");
		equals(SecretWaypointClickFeedbackRules.Action.WAIT_FOR_PICKUP,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.BAT,
				selection, true, false), "bat click waits for entity confirmation");
		equals(SecretWaypointClickFeedbackRules.Action.WAIT_FOR_PICKUP,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM,
				selection, false, false), "item click waits for pickup confirmation");
		equals(SecretWaypointClickFeedbackRules.Action.WAIT_FOR_PICKUP,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP,
				selection, false, false), "selected essence click waits for pickup confirmation");
		equals(SecretWaypointClickFeedbackRules.Action.WAIT_FOR_PICKUP,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY,
				selection, false, false), "selected redstone key click waits for pickup confirmation");
		equals(SecretWaypointClickFeedbackRules.Action.WAIT_FOR_PICKUP,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.ITEM_FRAME,
				selection, false, false), "item frame click waits for pickup confirmation");
		equals(SecretWaypointClickFeedbackRules.Action.ACTIVATE_NOW,
			SecretWaypointClickFeedbackRules.clickAction(SecretWaypointClickFeedbackRules.Type.WAYPOINT,
				null, false, false), "waypoint click preserves immediate chime behavior");
		check(!SecretWaypointClickFeedbackRules.pickupConfirmed(SecretWaypointClickFeedbackRules.Type.BAT,
			false, 0, 0, false)
			&& !SecretWaypointClickFeedbackRules.pickupConfirmed(SecretWaypointClickFeedbackRules.Type.BAT,
				true, 0, 0, false), "bat entity disappearance alone is not pickup confirmation");
		check(SecretWaypointClickFeedbackRules.confirmsBatDeathSound("minecraft:entity.bat.death")
			&& !SecretWaypointClickFeedbackRules.confirmsBatDeathSound("minecraft:entity.bat.ambient"),
			"only the authoritative bat-death sound confirms bat collection");
		var batCollection = new SecretWaypointCollectionTracker();
		batCollection.beginRun("bat-click-feedback");
		String batMarker = "room|bat";
		boolean batFeedback = SecretWaypointClickFeedbackRules.confirmsBatDeathSound("minecraft:entity.bat.death")
			&& batCollection.confirm(batMarker);
		check(batFeedback && batCollection.isCollected(batMarker),
			"confirmed bat death collects the matched marker and enters its feedback path");
		check(!SecretWaypointClickFeedbackRules.pickupConfirmed(SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM,
			false, 1, 1, false), "unchanged item does not confirm pickup");
		check(SecretWaypointClickFeedbackRules.pickupConfirmed(SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM,
			false, 2, 1, false), "partial item pickup confirms the clicked target response");
		check(SecretWaypointClickFeedbackRules.pickupConfirmed(SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY,
			true, 1, 0, false), "redstone key entity removal confirms the clicked target response");
		check(SecretWaypointClickFeedbackRules.pickupConfirmed(SecretWaypointClickFeedbackRules.Type.ITEM_FRAME,
			false, 1, 0, true), "item frame pickup confirmation requires its displayed item to be gone");
		check(!SecretWaypointClickFeedbackRules.pickupConfirmed(SecretWaypointClickFeedbackRules.Type.ITEM_FRAME,
			true, 0, 0, false), "breaking an empty item frame is not a pickup confirmation");
		check(!SecretWaypointClickFeedbackRules.pickupConfirmed(SecretWaypointClickFeedbackRules.Type.CHEST,
			true, 0, 0, true), "block confirmation never changes secret collection semantics");
		equals(1_000_000_000L,
			SecretWaypointClickFeedbackRules.deadlineNanos(0L, SecretWaypointClickFeedbackRules.DEFAULT_WINDOW_MILLIS),
			"click feedback defaults to a one-second window");
		long deadline = SecretWaypointClickFeedbackRules.deadlineNanos(5_000L, 1_000);
		check(!SecretWaypointClickFeedbackRules.expired(deadline, deadline), "feedback remains active through its deadline");
		check(SecretWaypointClickFeedbackRules.expired(deadline + 1, deadline), "feedback expires after its deadline");
		equals(SecretWaypointClickFeedbackRules.deadlineNanos(0L, 10_000),
			SecretWaypointClickFeedbackRules.deadlineNanos(0L, 20_000), "feedback window is clamped to a bounded maximum");
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void isNull(Object value, String message) {
		if (value != null) throw new AssertionError(message + ": got " + value);
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
