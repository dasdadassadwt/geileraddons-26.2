package geiler.addons.client.dungeon;

import java.lang.reflect.Modifier;

/** Pure checks for exact-marker acknowledgement and locked-chest rollback timing. */
public final class SecretWaypointCollectionTrackerChecks {
	private SecretWaypointCollectionTrackerChecks() { }

	public static void run() {
		checkJavaApi();
		SecretWaypointCollectionTracker tracker = new SecretWaypointCollectionTracker();
		tracker.beginRun("run-1");
		var chestType = SecretWaypointClickFeedbackRules.Type.CHEST;
		check(tracker.beginBlockInteraction("room|chest-1", chestType, 11), "chest click starts a pending marker");
		check(tracker.isPending("room|chest-1") && !tracker.isCollected("room|chest-1"),
			"a clicked marker is hidden while awaiting its exact server acknowledgement");
		check(!tracker.confirmPendingBlockInteraction("room|other-chest"),
			"a response for another chest cannot collect this marker");
		check(tracker.confirmPendingBlockInteraction("room|chest-1"),
			"the exact positive chest event confirms the pending marker");
		check(tracker.isCollected("room|chest-1") && !tracker.isPending("room|chest-1"),
			"confirmed collection removes only the matching pending marker");

		String itemMarker = "room|dropped-item";
		if (SecretWaypointClickFeedbackRules.confirmsPickupPacket(4, 4, true,
			SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM)) tracker.confirm(itemMarker);
		check(tracker.isCollected(itemMarker), "a valid local pickup packet confirms its matching item marker");
		String batMarker = "room|bat";
		if (SecretWaypointClickFeedbackRules.confirmsBatDeathSound("minecraft:entity.bat.death")) tracker.confirm(batMarker);
		check(tracker.isCollected(batMarker), "the exact bat-death event confirms its matching bat marker");
		String despawnedBat = "room|despawned-bat";
		if (SecretWaypointClickFeedbackRules.confirmsBatDeathSound("minecraft:entity.bat.hurt")) tracker.confirm(despawnedBat);
		check(!tracker.isCollected(despawnedBat), "bat unload or unrelated sound does not collect its marker");

		check(tracker.beginBlockInteraction("room|locked", chestType, 11), "a second chest click starts pending");
		for (int i = 0; i < 5; i++) tracker.tick();
		check("room|locked".equals(tracker.onLockedChestMessage()), "locked response selects the pending chest");
		for (int i = 0; i < 10; i++) tracker.tick();
		check(tracker.isPending("room|locked") && !tracker.isCollected("room|locked"),
			"locked chest stays hidden only during the eleven-tick restore interval");
		check(!tracker.beginBlockInteraction("room|blocked", chestType, 11),
			"the locked chest cooldown blocks another chest click until restoration completes");
		tracker.tick();
		check(!tracker.isPending("room|locked") && !tracker.isCollected("room|locked"),
			"locked chest marker returns after eleven ticks and is not marked collected");
		check(tracker.beginBlockInteraction("room|blocked", chestType, 11),
			"chest interaction can retry after the exact rollback window");

		var leverType = SecretWaypointClickFeedbackRules.Type.LEVER;
		check(tracker.beginBlockInteraction("room|lever", leverType, 11), "lever click starts pending");
		for (int i = 0; i < 11; i++) tracker.tick();
		check(!tracker.isPending("room|lever") && !tracker.isCollected("room|lever"),
			"a block click without an observed server response restores the marker");

		tracker.beginRun("run-2");
		check(!tracker.isCollected("room|chest-1") && !tracker.isPending("room|blocked"),
			"a new dungeon run clears collection and pending interaction state");
	}

	private static void checkJavaApi() {
		check(Modifier.isFinal(SecretWaypointCollectionTracker.class.getModifiers()),
			"the tracker facade remains final");
		check(SecretWaypointCollectionTracker.LOCKED_CHEST_RESTORE_TICKS == 11,
			"the locked-chest default remains eleven ticks");
		checkMethod("beginRun", void.class, String.class);
		checkMethod("tick", java.util.List.class);
		checkMethod("beginBlockInteraction", boolean.class, String.class,
			SecretWaypointClickFeedbackRules.Type.class, int.class);
		checkMethod("onLockedChestMessage", String.class);
		checkMethod("onLockedChestMessage", String.class, int.class);
		checkMethod("confirmLatestBlockInteraction", String.class);
		checkMethod("confirmPendingBlockInteraction", boolean.class, String.class);
		checkMethod("confirm", boolean.class, String.class);
		checkMethod("isCollected", boolean.class, String.class);
		checkMethod("isPending", boolean.class, String.class);
		checkMethod("runKey", String.class);
		checkMethod("currentTick", int.class);
		try {
			check(Modifier.isPublic(SecretWaypointCollectionTracker.class.getConstructor().getModifiers()),
				"the tracker keeps its public no-argument constructor");
		} catch (NoSuchMethodException error) {
			throw new AssertionError("the tracker keeps its public no-argument constructor", error);
		}
	}

	private static void checkMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			var method = SecretWaypointCollectionTracker.class.getDeclaredMethod(name, parameterTypes);
			check(method.getReturnType() == returnType, name + " keeps its Java return type");
			check(Modifier.isPublic(method.getModifiers()), name + " remains public");
			check(!Modifier.isStatic(method.getModifiers()), name + " remains an instance method");
		} catch (NoSuchMethodException error) {
			throw new AssertionError("the Java-facing tracker method " + name + " remains present", error);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
