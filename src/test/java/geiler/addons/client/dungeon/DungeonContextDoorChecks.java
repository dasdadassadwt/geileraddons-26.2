package geiler.addons.client.dungeon;

import java.util.List;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Offline checks for dungeon map gating and the immutable Box Doors key-color state. */
public final class DungeonContextDoorChecks {
	private DungeonContextDoorChecks() { }

	public static void run() {
		checkContextTrackerJavaBoundary();
		checkQueueFloorCaptureRules();
		checkMapScanGate();
		checkClearPhaseEspGate();
		checkClearLatchResetsAfterConfirmedExit();
		checkMobEspPhaseGate();
		checkBossSidebarLossKeepsContext();
		checkEntryProbe();
		checkEntryScanInterval();
		checkMapOverlayCountdownPreload();
		checkKeyOwnershipMerge();
	}

	private static void checkContextTrackerJavaBoundary() {
		checkPublicStatic("tick", void.class, net.minecraft.client.Minecraft.class, boolean.class, boolean.class);
		checkPublicStatic("tick", void.class, net.minecraft.client.Minecraft.class, boolean.class);
		checkPublicStatic("tick", void.class, net.minecraft.client.Minecraft.class);
		checkPublicStatic("inDungeon", boolean.class);
		checkPublicStatic("observeMapOverlayCountdown", boolean.class, String.class);
		checkPublicStatic("isMapOverlayPreloading", boolean.class);
		checkPublicStatic("isMapOverlayPixelsContextActive", boolean.class);
		checkPublicStatic("isMapOverlayContextActive", boolean.class);
		checkPublicStatic("isMapOverlayStartupContext", boolean.class);
		checkPublicStatic("shouldShowMapOverlay", boolean.class, boolean.class, boolean.class, boolean.class);
		checkPublicStatic("observeAuthoritativeMapPixels", void.class, boolean.class);
		checkPublicStatic("mapOverlayPixelDiagnostics", String.class);
		checkPublicStatic("mapOverlayPreloadFloor", DungeonFloor.class);
		checkPublicStatic("mapOverlayContextSource", String.class);
		checkPublicStatic("mapOverlayPreloadDiagnostics", String.class);
		checkPublicStatic("clearMapOverlayPreload", void.class);
		checkPublicStatic("clearMapOverlayStartupState", void.class);
		checkPublicStatic("isClearPhase", boolean.class);
		checkPublicStatic("shouldEnableClearEsp", boolean.class, boolean.class, boolean.class);
		checkPublicStatic("isMobEspPhase", boolean.class);
		checkPublicStatic("shouldEnableMobEsp", boolean.class, boolean.class, boolean.class);
		checkPublicStatic("onChatMessage", void.class, String.class);
		checkPublicStatic("detectedFloor", DungeonFloor.class);
		checkPublicStatic("manualFloor", DungeonFloor.class);
		checkPublicStatic("currentFloor", DungeonFloor.class);
		checkPublicStatic("floorSource", DungeonContextTracker.FloorSource.class);
		checkPublicStatic("diagnostics", String.class);
		checkPublicStatic("setManualFloor", boolean.class, DungeonFloor.class);
		checkPublicStatic("clearManualFloor", boolean.class);
		checkPackageStatic("shouldRunFullMapScan", boolean.class, boolean.class, boolean.class);
		checkPackageStatic("mobEspBossPhaseAfter", boolean.class, boolean.class, String.class);
		checkPackageStatic("shouldBeginClearPhase", boolean.class, boolean.class);
		checkPackageStatic("floorSelection", DungeonContextTracker.FloorSelection.class,
			boolean.class, DungeonFloor.class, DungeonFloor.class, DungeonFloor.class);
		checkPackageStatic("readSidebarText", DungeonContextTracker.Signal.class, String.class);
		assertTrue(DungeonContextTracker.Signal.class.isRecord()
			&& DungeonContextTracker.FloorSelection.class.isRecord(),
			"the tracker preserves its Java record boundaries");
		assertTrue(new DungeonContextTracker.Signal(true, DungeonFloor.F7).toString()
				.equals("Signal[inDungeon=true, floor=F7]")
			&& new DungeonContextTracker.FloorSelection(DungeonFloor.M7,
				DungeonContextTracker.FloorSource.MANUAL).toString()
				.equals("FloorSelection[floor=M7, source=MANUAL]"),
			"the tracker preserves Java record string formatting");
		assertTrue(java.util.Arrays.equals(DungeonContextTracker.FloorSource.values(),
			new DungeonContextTracker.FloorSource[] { DungeonContextTracker.FloorSource.UNKNOWN,
				DungeonContextTracker.FloorSource.MANUAL, DungeonContextTracker.FloorSource.SIDEBAR,
				DungeonContextTracker.FloorSource.QUEUE }),
			"the tracker preserves floor source enum order");
	}

	private static void checkPublicStatic(String name, Class<?> returnType, Class<?>... parameters) {
		checkStatic(name, returnType, parameters, true);
	}

	private static void checkPackageStatic(String name, Class<?> returnType, Class<?>... parameters) {
		try {
			Method method = DungeonContextTracker.class.getDeclaredMethod(name, parameters);
			assertTrue(Modifier.isStatic(method.getModifiers()) && method.getReturnType() == returnType,
				"DungeonContextTracker retains static helper method " + name);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("DungeonContextTracker lost helper method " + name, exception);
		}
	}

	private static void checkStatic(String name, Class<?> returnType, Class<?>[] parameters, boolean isPublic) {
		try {
			Method method = DungeonContextTracker.class.getDeclaredMethod(name, parameters);
			assertTrue(Modifier.isStatic(method.getModifiers())
				&& Modifier.isPublic(method.getModifiers()) == isPublic
				&& method.getReturnType() == returnType,
				"DungeonContextTracker preserves Java static method " + name);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("DungeonContextTracker lost Java method " + name, exception);
		}
	}

	private static void checkQueueFloorCaptureRules() {
		DungeonFloor[] floorOrder = DungeonFloor.values();
		assertTrue(floorOrder.length == 15 && floorOrder[0] == DungeonFloor.ENTRANCE
			&& floorOrder[7] == DungeonFloor.F7 && floorOrder[8] == DungeonFloor.M1
			&& floorOrder[14] == DungeonFloor.M7,
			"the shared Java enum retains every floor constant in its original order");
		assertTrue(DungeonFloor.parse(" ent ") == DungeonFloor.ENTRANCE
			&& DungeonFloor.parse("m7") == DungeonFloor.M7 && DungeonFloor.parse("F8") == null
			&& DungeonFloor.parse(null) == null,
			"floor parsing keeps aliases, case/whitespace normalization, and unknown handling");
		assertTrue(DungeonFloor.M7.displayName().equals("M7") && DungeonFloor.M7.master()
			&& DungeonFloor.M7.number() == 7,
			"Java callers retain the floor display, mode, and number accessors");
		assertTrue(DungeonQueueFloorTracker.parseGroupBuilderSelection("Catacombs", "VII") == DungeonFloor.F7,
			"a normal Catacombs selection maps to its floor");
		assertTrue(DungeonQueueFloorTracker.parseGroupBuilderSelection("\u0001Catacombs\u0001", "\u0001VII\u0001")
			== DungeonFloor.F7,
			"Group Builder labels preserve Java String.trim behavior for low control characters");
		assertTrue(DungeonQueueFloorTracker.parseGroupBuilderSelection("Master Mode Catacombs", "III") == DungeonFloor.M3,
			"a Master Mode selection maps to the master floor");
		assertTrue(DungeonQueueFloorTracker.parseGroupBuilderSelection("Catacombs", "M IV") == DungeonFloor.M4,
			"an explicit M floor remains master even when the type label is generic");
		assertTrue(DungeonQueueFloorTracker.parseGroupBuilderSelection("Master Catacombs", "Entrance") == DungeonFloor.ENTRANCE,
			"Entrance keeps its shared floor identity in Master Mode menus");
		assertTrue(DungeonQueueFloorTracker.parseGroupBuilderSelection("Blaze Slayer", "VII") == null,
			"a non-Catacombs group builder selection is rejected");
		assertTrue(DungeonQueueFloorTracker.parseGroupBuilderSelection("Catacombs", "Floor VIII") == null,
			"an unsupported floor label cannot be normalized");

		assertTrue(DungeonQueueFloorTracker.parseJoinedListing(List.of("Dungeon: The Catacombs", "Floor: VII"))
			== DungeonFloor.F7, "a Catacombs party listing provides its advertised floor");
		assertTrue(DungeonQueueFloorTracker.parseJoinedListing(
			List.of("Dungeon: Master Mode Catacombs", "Floor: VII")) == DungeonFloor.M7,
			"a Master Mode party listing provides its advertised master floor");
		assertTrue(DungeonQueueFloorTracker.parseJoinedListing(
			List.of("Dungeon: The Catacombs", "Floor: F2", "Floor: M3")) == DungeonFloor.M3,
			"the last matching floor row retains the baseline listing precedence");
		assertTrue(DungeonQueueFloorTracker.parseJoinedListing(
			List.of("Dungeon: The Catacombs", "Floor: Entrance")) == DungeonFloor.ENTRANCE,
			"an advertised Entrance remains distinct from regular floors");
		assertTrue(DungeonQueueFloorTracker.parseJoinedListing(List.of("Dungeon: The Rift", "Floor: VII")) == null,
			"a non-Catacombs listing cannot establish a queue floor");
		assertTrue(DungeonQueueFloorTracker.parseJoinedListing(List.of("Dungeon: The Catacombs")) == null,
			"a listing without a floor cannot establish a queue floor");

		long maximumAge = 15_000_000_000L;
		assertTrue(DungeonQueueFloorTracker.isFreshCandidate(100L, 100L, maximumAge),
			"a candidate remains fresh at capture time");
		assertTrue(DungeonQueueFloorTracker.isFreshCandidate(100L, 100L + maximumAge, maximumAge),
			"the inclusive 15-second candidate boundary is preserved");
		assertFalse(DungeonQueueFloorTracker.isFreshCandidate(100L, 101L + maximumAge, maximumAge),
			"an older-than-15-second candidate expires");
		assertFalse(DungeonQueueFloorTracker.isFreshCandidate(100L, 99L, maximumAge),
			"a monotonic clock value before capture rejects the candidate");
		assertFalse(DungeonQueueFloorTracker.isFreshCandidate(0L, 100L, maximumAge),
			"an unset capture time cannot promote a candidate");

		var fields = DungeonQueueFloorTracker.CaptureResult.class.getRecordComponents();
		assertTrue(fields != null && fields.length == 3
			&& "attempted".equals(fields[0].getName()) && "floor".equals(fields[1].getName())
			&& "error".equals(fields[2].getName()),
			"the Java capture result retains its native record components and order");
		try {
			DungeonQueueFloorTracker.class.getMethod("captureConfirmation", net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class,
				net.minecraft.world.inventory.Slot.class, int.class, net.minecraft.world.inventory.ContainerInput.class);
			DungeonQueueFloorTracker.class.getMethod("captureJoinedListing", net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class,
				net.minecraft.world.inventory.Slot.class, int.class, net.minecraft.world.inventory.ContainerInput.class);
			DungeonQueueFloorTracker.class.getMethod("confirmQueued");
			DungeonQueueFloorTracker.class.getMethod("confirmJoinedListing");
			DungeonQueueFloorTracker.class.getMethod("currentFloor");
			DungeonQueueFloorTracker.class.getMethod("tick", net.minecraft.client.Minecraft.class);
			DungeonQueueFloorTracker.class.getMethod("clear");
		} catch (NoSuchMethodException error) {
			throw new AssertionError("the queue-floor tracker lost a Java static entrypoint", error);
		}
	}

	private static void checkMapOverlayCountdownPreload() {
		assertTrue(DungeonContextLifecycleRules.shouldStartMapOverlayPreload(
			"Starting in 4 seconds.", true, false),
			"the exact four-second countdown can preload the map with a queued Catacombs floor");
		assertTrue(DungeonContextLifecycleRules.shouldStartMapOverlayPreload(
			"Starting in 1 second.", false, true),
			"an authoritative Catacombs sidebar can validate the exact countdown cue");
		assertFalse(DungeonContextLifecycleRules.shouldStartMapOverlayPreload(
			"Starting in 4 seconds.", false, false),
			"a generic countdown cannot activate the map without Catacombs evidence");
		assertFalse(DungeonContextLifecycleRules.shouldStartMapOverlayPreload(
			"We are starting in 4 seconds.", true, false),
			"a similar but non-exact message cannot activate preload");
		assertTrue(DungeonContextLifecycleRules.shouldStartMapOverlayPreload(
			"Starting in 5 seconds.", true, false),
			"the earliest exact countdown line is accepted when the Catacombs queue is verified");
		assertFalse(DungeonContextLifecycleRules.shouldClearMapOverlayPreload(
			"queued-floor-countdown", false, true, false),
			"a valid queued-floor preload survives while the floor remains queued");
		assertTrue(DungeonContextLifecycleRules.shouldClearMapOverlayPreload(
			"queued-floor-countdown", false, false, false),
			"clearing the queued Catacombs floor cancels the countdown preload");
		assertFalse(DungeonContextLifecycleRules.shouldClearMapOverlayPreload(
			"catacombs-sidebar-countdown", false, false, false),
			"a brief sidebar disappearance cannot cut off the map handoff");
		assertTrue(DungeonContextLifecycleRules.shouldClearMapOverlayPreload(
			"catacombs-sidebar-countdown", true, false, false),
			"every preload source expires at the bounded stale timeout");
		assertTrue(DungeonContextLifecycleRules.shouldObserveAuthoritativeMapPixels(true, false),
			"a valid parsed map layout can take over from the countdown-only state");
		assertFalse(DungeonContextLifecycleRules.shouldObserveAuthoritativeMapPixels(false, false),
			"an invalid map layout cannot take over the countdown state");
		assertFalse(DungeonContextLifecycleRules.shouldObserveAuthoritativeMapPixels(true, true),
			"normal dungeon context already owns map authority");
		assertTrue(DungeonContextLifecycleRules.shouldKeepMapOverlayPixelContext(true, true, false,
			1_000_000_000L, 2_000_000_000L),
			"validated map pixels keep the map-only context alive during its refresh grace");
		assertFalse(DungeonContextLifecycleRules.shouldKeepMapOverlayPixelContext(true, true, false,
			2_000_000_000L, 2_000_000_000L),
			"validated map pixels expire after the refresh grace if they stop arriving");
		assertFalse(DungeonContextLifecycleRules.shouldKeepMapOverlayPixelContext(true, false, false,
			1_000_000_000L, 2_000_000_000L),
			"a changed world immediately invalidates the map-only pixel context");
		assertFalse(DungeonContextLifecycleRules.shouldKeepMapOverlayPixelContext(true, true, true,
			1_000_000_000L, 2_000_000_000L),
			"confirmed dungeon context clears the temporary map-only pixel latch");
		assertFalse(DungeonContextTracker.shouldEnableMobEsp(false, false),
			"map-only startup state does not activate dungeon-scoped modules");
		assertTrue(DungeonContextLifecycleRules.shouldShowMapOverlay(false, false, false, true),
			"the map can display during its verified startup context before dungeon recognition");
		assertFalse(DungeonContextLifecycleRules.shouldShowMapOverlay(true, false, false, false),
			"the map remains hidden during a normal boss phase unless Show In Boss is enabled");
	}

	private static void checkClearLatchResetsAfterConfirmedExit() {
		boolean closedForRun = DungeonContextLifecycleRules.clearEspClosedForRunAfterChat(false, "BLOOD_OPEN");
		assertTrue(closedForRun, "Blood Open closes Clear for the current run");
		closedForRun = DungeonContextLifecycleRules.clearEspClosedForRunAfterChat(closedForRun, "BOSS_ENTRY");
		assertTrue(closedForRun, "boss entry keeps Clear closed for the current run");
		closedForRun = DungeonContextLifecycleRules.clearEspClosedForRunAfterExit(closedForRun, false);
		assertTrue(closedForRun, "transfer grace or uncertain context does not clear the run latch");
		assertFalse(DungeonContextTracker.shouldBeginClearPhase(closedForRun),
			"re-entry before a confirmed exit cannot reopen stale Clear state");

		closedForRun = DungeonContextLifecycleRules.clearEspClosedForRunAfterExit(closedForRun, true);
		assertFalse(closedForRun, "a confirmed same-level dungeon exit clears the run latch");
		assertTrue(DungeonContextTracker.shouldBeginClearPhase(closedForRun),
			"a later dungeon entry initializes the next run in Clear");
	}

	private static void checkClearPhaseEspGate() {
		assertTrue(DungeonContextTracker.shouldBeginClearPhase(false),
			"a new dungeon context starts in Clear when no later-stage evidence arrived first");
		assertFalse(DungeonContextTracker.shouldBeginClearPhase(true),
			"a blood or boss signal received before the first sidebar tick cannot reopen clear ESP");
		assertTrue(DungeonContextTracker.shouldEnableClearEsp(true, true),
			"the legacy Clear-phase gate stays available to existing context consumers");
		assertFalse(DungeonContextTracker.shouldEnableClearEsp(false, true),
			"the Clear flag cannot activate outside a dungeon");
		assertFalse(DungeonContextTracker.shouldEnableClearEsp(true, false),
			"the legacy Clear-phase gate remains closed after Clear");
	}

	private static void checkMobEspPhaseGate() {
		boolean bossPhase = false;
		assertTrue(DungeonContextTracker.shouldEnableMobEsp(true, bossPhase),
			"Dungeon Mob ESP is available during Entry and Clear");
		bossPhase = DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "BLOOD_OPEN");
		assertFalse(bossPhase, "Blood Open does not close Dungeon Mob ESP");
		assertTrue(DungeonContextTracker.shouldEnableMobEsp(true, bossPhase),
			"Dungeon Mob ESP remains available during Blood Open");
		bossPhase = DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "BLOOD_CLEAR");
		assertFalse(bossPhase, "Blood Clear does not close Dungeon Mob ESP");
		assertTrue(DungeonContextTracker.shouldEnableMobEsp(true, bossPhase),
			"Dungeon Mob ESP remains available during Blood Clear");
		bossPhase = DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "BOSS_ENTRY");
		assertTrue(bossPhase, "recognized boss entry closes the mob ESP phase");
		assertFalse(DungeonContextTracker.shouldEnableMobEsp(true, bossPhase),
			"Dungeon Mob ESP is inactive after boss entry");
		assertTrue(DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "CLEAR"),
			"a late Clear event cannot reopen the phase after boss entry");
		assertTrue(DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "BLOOD_CLEAR"),
			"late Blood events cannot reopen the phase after boss entry");
		assertFalse(DungeonContextTracker.shouldEnableMobEsp(false, false),
			"an open pre-boss phase cannot activate outside a dungeon");
		assertFalse(DungeonContextTracker.mobEspBossPhaseAfter(bossPhase, "ENTRY"),
			"a new Entry event resets the prior run's boss phase");
	}

	private static void checkMapScanGate() {
		assertFalse(DungeonContextTracker.shouldRunFullMapScan(false, false),
			"ordinary non-dungeon ticks do not run full map geometry");
		assertTrue(DungeonContextTracker.shouldRunFullMapScan(false, true),
			"map entry evidence keeps the fallback that can establish dungeon context");
		assertTrue(DungeonContextTracker.shouldRunFullMapScan(true, false),
			"confirmed dungeon context continues normal map updates");
	}

	private static void checkEntryProbe() {
		byte[] colors = new byte[DungeonMapGeometry.MAP_SIZE * DungeonMapGeometry.MAP_SIZE];
		DungeonMapGeometry.MapPoint marker = new DungeonMapGeometry.MapPoint(64, 64);
		assertFalse(DungeonMapRoomDetector.hasDungeonMapMarkerColor(colors, marker),
			"an uncolored map neighborhood is not a dungeon-entry candidate");
		colors[65 + (64 << 7)] = (byte) 63;
		assertTrue(DungeonMapRoomDetector.hasDungeonMapMarkerColor(colors, marker),
			"one recognized room-color pixel beside the marker is a cheap entry candidate");
		assertFalse(DungeonMapRoomDetector.hasDungeonMapMarkerColor(new byte[12], marker),
			"an incomplete map buffer cannot establish entry evidence");
		assertFalse(DungeonMapRoomDetector.hasDungeonMapMarkerColor(colors, null),
			"a missing map marker cannot establish entry evidence");
		assertFalse(DungeonMapRoomDetector.hasDungeonMapMarkerColor(colors,
			new DungeonMapGeometry.MapPoint(128, 64)), "an out-of-map marker cannot establish entry evidence");
	}

	private static void checkEntryScanInterval() {
		assertTrue(DungeonMapRoomDetector.entryScanIntervalElapsed(0L, 100L),
			"the first candidate may run a full calibration immediately");
		assertFalse(DungeonMapRoomDetector.entryScanIntervalElapsed(100L, 100L + 999_999_999L),
			"unconfirmed candidates wait before recalibrating");
		assertTrue(DungeonMapRoomDetector.entryScanIntervalElapsed(100L, 100L + 1_000_000_000L),
			"a candidate may retry calibration after the one-second interval");
	}

	private static void checkKeyOwnershipMerge() {
		DungeonKeyRules.KeyOwnership inventory = new DungeonKeyRules.KeyOwnership(false, true);
		DungeonKeyRules.KeyOwnership state = DungeonKeyRules.combineOwnership(true, false,
			inventory.wither(), inventory.blood());
		assertTrue(state.wither(), "the local Wither-key chat fallback is retained");
		assertTrue(state.blood(), "the inventory scan adds Blood-key ownership to the same snapshot");
		DungeonKeyRules.KeyOwnership none = DungeonKeyRules.combineOwnership(false, false, false, false);
		assertFalse(none.wither() || none.blood(), "no observed or carried key leaves both palettes unchanged");
	}

	/** Hypixel's sidebar can drop the Catacombs line mid boss (seen at M7 P5); only a world change ends the run there. */
	private static void checkBossSidebarLossKeepsContext() {
		assertTrue(!DungeonContextLifecycleRules.shouldExitOnSidebarLoss(true, true, true),
			"boss phase on the same level must survive sidebar loss");
		assertTrue(DungeonContextLifecycleRules.shouldExitOnSidebarLoss(true, false, true),
			"boss phase after a world change must exit once the grace expires");
		assertTrue(DungeonContextLifecycleRules.shouldExitOnSidebarLoss(false, true, true),
			"outside boss phase sidebar loss must still exit after the grace");
		assertTrue(!DungeonContextLifecycleRules.shouldExitOnSidebarLoss(false, false, false),
			"sidebar loss inside the grace must not exit");
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static void assertFalse(boolean value, String message) {
		if (value) throw new AssertionError(message);
	}
}
