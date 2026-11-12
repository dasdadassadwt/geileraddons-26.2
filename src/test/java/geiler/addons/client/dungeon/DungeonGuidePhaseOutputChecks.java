package geiler.addons.client.dungeon;

import geiler.addons.client.dungeon.odin.DungeonSplitEntryBuffer;

/** Focused JDK checks for source-backed Guide phase-output routing and run-scoped deduplication. */
public final class DungeonGuidePhaseOutputChecks {
	private DungeonGuidePhaseOutputChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		chatDispatchIsIndependentOfGuideToggle();
		earlyEntryWorksWithoutOdinAndDeduplicatesWhenBothAreEnabled();
		DungeonGuidePhaseOutputState state = new DungeonGuidePhaseOutputState();
		var livid = DungeonGuideSegments.fromChat(DungeonFloor.F5, "Livid: I am the Master of Shadows.");
		var complete = DungeonGuideSegments.fromChat(DungeonFloor.F5, "☠ Defeated Livid in 1m 2s!");
		equals(new DungeonGuidePhaseOutputState.Transition(DungeonFloor.F5, "LIVID"),
			state.observeEvent(DungeonFloor.F5, livid), "source-backed Livid cue produces its named phase output");
		isNull(state.observeEvent(DungeonFloor.F5, livid), "same phase cue is one-shot");
		equals(new DungeonGuidePhaseOutputState.Transition(DungeonFloor.F5, "COMPLETE"),
			state.observeEvent(DungeonFloor.F5, complete), "stable completion cue is accepted");
		isNull(state.observe(DungeonFloor.F5, "LIVID", "Boss entry chat"),
			"phase output rejects evidence that does not map to an emitted event ID");
		isNull(state.observe(null, "LIVID", "Livid opening dialogue"), "unknown floor cannot output");
		check(DungeonGuidePhaseOutputState.supportedPhaseIds(DungeonFloor.F5).contains("LIVID")
			&& !DungeonGuidePhaseOutputState.supportedPhaseIds(DungeonFloor.F5).contains("BOSS_ENTRY"),
			"phase settings expose observed floor stages instead of generic Boss Entry");
		check(DungeonGuidePhaseOutputState.supportedPhaseIds(DungeonFloor.M7).contains("WITHER_KING"),
			"M7 Wither King output is exposed for its observed source-backed cues");

		DungeonGuidePhaseOutputState floorSeven = new DungeonGuidePhaseOutputState();
		var terminals = DungeonGuideSegments.fromChat(DungeonFloor.M7,
			"[BOSS] Goldor: Who dares trespass into my domain?");
		var goldor = DungeonGuideSegments.fromChat(DungeonFloor.M7, "The Core entrance is opening!");
		var necron = DungeonGuideSegments.fromChat(DungeonFloor.M7,
			"[BOSS] Necron: All this, for nothing...");
		var witherKing = DungeonGuideSegments.fromChat(DungeonFloor.M7, "[BOSS] Necron: ARGH!");
		equals(new DungeonGuidePhaseOutputState.Transition(DungeonFloor.M7, "TERMINALS"),
			floorSeven.observeEvent(DungeonFloor.M7, terminals), "Goldor domain line maps to the terminal stage");
		equals(new DungeonGuidePhaseOutputState.Transition(DungeonFloor.M7, "GOLDOR"),
			floorSeven.observeEvent(DungeonFloor.M7, goldor), "core-opening cue maps to the Goldor interval");
		equals(new DungeonGuidePhaseOutputState.Transition(DungeonFloor.M7, "NECRON"),
			floorSeven.observeEvent(DungeonFloor.M7, necron), "M7 Necron cue remains distinct from Goldor");
		equals(new DungeonGuidePhaseOutputState.Transition(DungeonFloor.M7, "WITHER_KING"),
			floorSeven.observeEvent(DungeonFloor.M7, witherKing), "M7 Necron transition cue maps to Wither King");

		DungeonGuidePhaseOutputState professor = new DungeonGuidePhaseOutputState();
		var ultimate = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"Professor: I see. You have forced me to use my ultimate technique.");
		var guardianPower = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"Professor: What?! My Guardian power is unbeatable!");
		equals(new DungeonGuidePhaseOutputState.Transition(DungeonFloor.F3, "PROFESSOR_TRANSFORMED"),
			professor.observeEvent(DungeonFloor.F3, ultimate), "Devonian's ultimate-technique line starts transformed phase feedback");
		equals(new DungeonGuidePhaseOutputState.Transition(DungeonFloor.F3, "PROFESSOR_GUARDIAN_POWER_CUE"),
			professor.observeEvent(DungeonFloor.F3, guardianPower), "F3 guardian-power cue stays independently observable");
		isNull(professor.observeEvent(DungeonFloor.F3, ultimate), "F3 cue feedback is one-shot within a run");
		check(DungeonGuidePhaseOutputState.supportedPhaseIds(DungeonFloor.F3).containsAll(
			java.util.List.of("PROFESSOR_TRANSFORMED", "PROFESSOR_GUARDIAN_POWER_CUE")),
			"the Devonian phase and Odin's independent F3 cue have separate configurable outputs");
		var professorEntry = DungeonGuideSegments.fromChat(DungeonFloor.F3, "Starting in 1 second.");
		var professorComplete = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"☠ Defeated The Professor in 1m 2s!");
		check(professor.observeEvent(DungeonFloor.F3, professorEntry) != null
			&& professor.observeEvent(DungeonFloor.F3, ultimate) != null
			&& professor.observeEvent(DungeonFloor.F3, professorComplete) != null
			&& professor.observeEvent(DungeonFloor.F3, professorEntry) != null
			&& professor.observeEvent(DungeonFloor.F3, ultimate) != null,
			"the same source cue may emit again after a new explicitly observed run start");

		DungeonGuidePhaseOutputState nextRun = new DungeonGuidePhaseOutputState();
		var entry = DungeonGuideSegments.fromChat(DungeonFloor.F3, "Starting in 1 second.");
		equals(new DungeonGuidePhaseOutputState.Transition(DungeonFloor.F3, "ENTRY"),
			nextRun.observeEvent(DungeonFloor.F3, entry), "countdown event can trigger Entry feedback");
		var stormEnergy = DungeonGuideSegments.fromChat(DungeonFloor.F7, "Storm: ENERGY HEED MY CALL!");
		isNull(nextRun.observeEvent(DungeonFloor.F3, stormEnergy), "wrong-floor independent cue is rejected");
		isNull(nextRun.observeEvent(DungeonFloor.F3, null), "missing source event cannot trigger feedback");
		isNull(nextRun.observeEvent(null, entry), "event with unknown dungeon floor cannot trigger feedback");
	}

	private static void chatDispatchIsIndependentOfGuideToggle() {
		DungeonGuidePhaseOutputState phaseState = new DungeonGuidePhaseOutputState();
		int[] phaseOutputs = {0};
		int[] phaseObservations = {0};
		int[] guideObservations = {0};
		boolean phaseOutputEnabled = true;
		boolean[] guideEnabled = {false};
		String message = "Livid: I am the Master of Shadows.";

		DungeonGuideChatDispatch.dispatch(DungeonFloor.F5, null, message, (floor, event) -> {
			phaseObservations[0]++;
			if (phaseOutputEnabled && phaseState.observeEvent(floor, event) != null) phaseOutputs[0]++;
		}, received -> {
			if (guideEnabled[0]) guideObservations[0]++;
		});

		check(phaseOutputs[0] == 1 && phaseObservations[0] == 1 && guideObservations[0] == 0,
			"enabled phase feedback receives chat while the route Guide is disabled");

		guideEnabled[0] = true;
		DungeonGuideChatDispatch.dispatch(DungeonFloor.F5, null, "☠ Defeated Livid in 1m 2s!", (floor, event) -> {
			phaseObservations[0]++;
			if (phaseOutputEnabled && phaseState.observeEvent(floor, event) != null) phaseOutputs[0]++;
		}, received -> {
			if (guideEnabled[0]) {
				guideObservations[0]++;
				check(received.startsWith("☠ Defeated Livid"), "guide receives the original chat line");
			}
		});
		check(phaseOutputs[0] == 2 && phaseObservations[0] == 2 && guideObservations[0] == 1,
			"enabling the Guide preserves its single chat observation alongside phase feedback");
	}

	private static void earlyEntryWorksWithoutOdinAndDeduplicatesWhenBothAreEnabled() {
		Object level = new Object();
		Object connection = new Object();
		long capturedAt = 1_000L;
		String countdown = "Starting in 1 second.";
		DungeonGuidePhaseOutputState phaseState = new DungeonGuidePhaseOutputState();
		DungeonGuidePhaseEntryBuffer phaseBuffer = new DungeonGuidePhaseEntryBuffer();
		DungeonSplitEntryBuffer odinBuffer = new DungeonSplitEntryBuffer();
		int[] phaseOutputs = {0};

		// Odin Assist is off: Phase Output captures the validated queued floor by itself.
		DungeonGuideChatDispatch.dispatch(null, DungeonFloor.F3, countdown, (floor, event) -> {
			if ("ENTRY".equals(event.segmentId())) phaseBuffer.capture(floor, level, connection, capturedAt);
		}, null);
		check(phaseBuffer.isPending() && !odinBuffer.isPending(),
			"an early countdown is retained by Phase Output even when Odin Assist is disabled");
		check(!phaseBuffer.capture(DungeonFloor.F3, level, connection, capturedAt + 5),
			"duplicate countdown cannot replace or extend the pending Entry");
		var readyWithoutOdin = phaseBuffer.consumeIfReady(true, DungeonFloor.F3, level, connection, capturedAt + 1);
		check(readyWithoutOdin != null
			&& phaseState.observeEvent(readyWithoutOdin.floor(),
				new DungeonGuideSegments.Event("ENTRY", "Run countdown chat")) != null,
			"Phase Output emits the buffered Entry when its matching dungeon context becomes available");
		phaseOutputs[0]++;
		check(phaseOutputs[0] == 1 && phaseBuffer.consumeIfReady(true, DungeonFloor.F3, level, connection,
			capturedAt + 2) == null, "the early Entry is emitted at most once");

		DungeonGuidePhaseOutputState sharedState = new DungeonGuidePhaseOutputState();
		DungeonGuidePhaseEntryBuffer phaseBufferWithOdin = new DungeonGuidePhaseEntryBuffer();
		DungeonSplitEntryBuffer enabledOdinBuffer = new DungeonSplitEntryBuffer();
		DungeonGuideChatDispatch.dispatch(null, DungeonFloor.F5, countdown, (floor, event) -> {
			if (!"ENTRY".equals(event.segmentId())) return;
			phaseBufferWithOdin.capture(floor, level, connection, capturedAt);
			enabledOdinBuffer.capture(floor, level, connection, capturedAt, 12L, 34L);
		}, null);
		var phaseEntry = phaseBufferWithOdin.consumeIfReady(true, DungeonFloor.F5, level, connection, capturedAt + 1);
		var odinEntry = enabledOdinBuffer.consumeIfReady(true, DungeonFloor.F5, level, connection, capturedAt + 1);
		var phaseTransition = sharedState.observeEvent(phaseEntry.floor(),
			new DungeonGuideSegments.Event("ENTRY", "Run countdown chat"));
		var odinTransition = sharedState.observeEvent(odinEntry.floor(),
			new DungeonGuideSegments.Event("ENTRY", "Run countdown chat"));
		check(phaseTransition != null && odinTransition == null,
			"when both buffers see the countdown, shared phase state suppresses Odin's duplicate output");

		DungeonGuidePhaseEntryBuffer expired = new DungeonGuidePhaseEntryBuffer();
		check(expired.capture(DungeonFloor.F3, level, connection, capturedAt), "valid pending Entry is captured");
		expired.discardIfStale(false, null, level, connection,
			capturedAt + DungeonGuidePhaseEntryBuffer.MAX_PENDING_NANOS + 1L);
		check(!expired.isPending(), "pending Entry expires after its bounded wait window");
		DungeonGuidePhaseEntryBuffer wrongWorld = new DungeonGuidePhaseEntryBuffer();
		wrongWorld.capture(DungeonFloor.F3, level, connection, capturedAt);
		wrongWorld.discardIfStale(false, null, new Object(), connection, capturedAt + 1);
		check(!wrongWorld.isPending(), "pending Entry is canceled when its world changes");
		DungeonGuidePhaseEntryBuffer wrongFloor = new DungeonGuidePhaseEntryBuffer();
		wrongFloor.capture(DungeonFloor.F3, level, connection, capturedAt);
		wrongFloor.discardIfStale(true, DungeonFloor.F5, level, connection, capturedAt + 1);
		check(!wrongFloor.isPending(), "pending Entry is canceled when the active floor does not match");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
	private static void isNull(Object actual, String message) {
		if (actual != null) throw new AssertionError(message + ": expected null, got " + actual);
	}
	private static void equals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual))
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
}
