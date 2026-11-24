package geiler.addons.client.dungeon.helpers;

import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonGuideSegments;
import geiler.addons.client.module.impl.DungeonSpecialMobRules;

import java.lang.reflect.Modifier;
import java.util.List;

/** Pure JDK checks for helper timing, split signals, party text, and special-mob gates. */
public final class DungeonHelperChecks {
	private DungeonHelperChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkBossTimers();
		checkTerminalCounts();
		checkF7TerminalSections();
		checkSplitEntryBuffer();
		checkSplitSignals();
		checkF7SplitPhaseSequence();
		checkIncomingLeapChat();
		checkSpecialMobGatesAndPartyPalette();
	}

	private static void checkStaticMethod(java.lang.reflect.Method method, Class<?> returnType, String label) {
		check(method.getReturnType() == returnType && Modifier.isPublic(method.getModifiers())
			&& Modifier.isStatic(method.getModifiers()), "public static descriptor remains stable: " + label);
	}

	private static void checkInstanceMethod(java.lang.reflect.Method method, Class<?> returnType, String label) {
		check(method.getReturnType() == returnType && Modifier.isPublic(method.getModifiers())
			&& !Modifier.isStatic(method.getModifiers()),
			"public instance descriptor remains stable: " + label);
	}

	private static void checkBossTimers() {
		Class<DungeonBossTimers> timersType = DungeonBossTimers.class;
		Class<DungeonBossTimers.Reading> readingType = DungeonBossTimers.Reading.class;
		try {
			check(Modifier.isPublic(timersType.getModifiers()) && Modifier.isFinal(timersType.getModifiers())
				&& Modifier.isPublic(timersType.getConstructor().getModifiers()),
				"boss timer tracker keeps its public Java class and no-argument constructor");
			checkInstanceMethod(timersType.getMethod("onChat", String.class), void.class, "boss timer chat input");
			checkInstanceMethod(timersType.getMethod("tick", boolean.class), void.class, "boss timer tick");
			checkInstanceMethod(timersType.getMethod("snapshot"), List.class, "boss timer snapshot");
			checkInstanceMethod(timersType.getMethod("reset"), void.class, "boss timer reset");
			checkStaticMethod(timersType.getMethod("formatTicks", int.class, boolean.class, boolean.class),
				String.class, "boss timer formatter");
			check(readingType.isRecord()
				&& java.util.Arrays.stream(readingType.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)
					.toList().equals(List.of("name", "ticks", "countUp"))
				&& Modifier.isPublic(readingType.getConstructor(String.class, int.class, boolean.class).getModifiers()),
				"timer readings retain their public Java record components and constructor");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("boss timer Java API changed", exception);
		}
		check(new DungeonBossTimers.Reading("Storm", 2, true)
			.equals(new DungeonBossTimers.Reading("Storm", 2, true)),
			"timer reading value equality remains stable");
		DungeonBossTimers timers = new DungeonBossTimers();
		timers.onChat("\u2003[BOSS] Goldor: Who dares trespass into my domain?\u2003");
		check(timers.snapshot().isEmpty(), "boss cues preserve Java trim behavior for Unicode whitespace");
		timers.onChat("  [BOSS] Goldor: Who dares trespass into my domain?  ");
		check(timers.snapshot().equals(List.of(new DungeonBossTimers.Reading("Goldor", 60, false))),
			"Goldor's exact chat cue starts the 60 tick timer after trimming boundary whitespace");
		timers.tick(false);
		check(timers.snapshot().getFirst().ticks() == 60,
			"boss timers pause when the caller has not confirmed boss-room context");
		timers.tick(true);
		timers.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.");
		timers.onChat("[BOSS] Storm: ENERGY HEED MY CALL!");
		check(timers.snapshot().equals(List.of(
			new DungeonBossTimers.Reading("Goldor", 59, false),
			new DungeonBossTimers.Reading("Storm Pad", 20, false),
			new DungeonBossTimers.Reading("Storm Lightning", 520, false),
			new DungeonBossTimers.Reading("Storm", 0, true),
			new DungeonBossTimers.Reading("Storm PY", 55, false))),
			"Storm cues start exact timers once and preserve their display ordering");
		timers.tick(true);
		timers.onChat("[BOSS] Storm: ENERGY HEED MY CALL!");
		check(timers.snapshot().equals(List.of(
			new DungeonBossTimers.Reading("Goldor", 58, false),
			new DungeonBossTimers.Reading("Storm Pad", 19, false),
			new DungeonBossTimers.Reading("Storm Lightning", 519, false),
			new DungeonBossTimers.Reading("Storm", 1, true),
			new DungeonBossTimers.Reading("Storm PY", 54, false))),
			"server ticks decrement countdowns and increment the count-up timer by one tick");
		timers.onChat("[BOSS] Storm: I should have known that I stood no chance.");
		check(timers.snapshot().equals(List.of(
			new DungeonBossTimers.Reading("Goldor", 58, false),
			new DungeonBossTimers.Reading("Storm Lightning", 519, false),
			new DungeonBossTimers.Reading("Storm PY", 54, false),
			new DungeonBossTimers.Reading("Goldor Start", 64, false))),
			"Storm end removes only the Storm and pad timers while starting Goldor's 64 tick cue");
		timers.onChat("The Core entrance is opening!");
		timers.onChat("[BOSS] The Professor: Oh? You found my Guardians' one weakness?");
		check(timers.snapshot().equals(List.of(
			new DungeonBossTimers.Reading("Storm Lightning", 519, false),
			new DungeonBossTimers.Reading("Storm PY", 54, false),
			new DungeonBossTimers.Reading("Professor Freeze", 206, false))),
			"Core opening clears Goldor timers and Professor's cue starts the 206 tick freeze timer");
		check(DungeonBossTimers.formatTicks(21, true, false).equals("21")
			&& DungeonBossTimers.formatTicks(21, true, true).equals("21t")
			&& DungeonBossTimers.formatTicks(21, false, false).equals("1.1")
			&& DungeonBossTimers.formatTicks(21, false, true).equals("1.1s")
			&& DungeonBossTimers.formatTicks(-1, false, true).equals("0.0s"),
			"timer formatting preserves tick/second precision, suffix options, and negative clamping");
		timers.reset();
		check(timers.snapshot().isEmpty(), "reset clears every boss timer");

		DungeonBossTimers rollover = new DungeonBossTimers();
		rollover.onChat("[BOSS] Goldor: Who dares trespass into my domain?");
		for (int i = 0; i < 60; i++) rollover.tick(true);
		check(rollover.snapshot().equals(List.of(new DungeonBossTimers.Reading("Goldor", 60, false))),
			"Goldor's countdown restarts at 60 ticks when it reaches zero");
		DungeonBossTimers padRollover = new DungeonBossTimers();
		padRollover.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.");
		for (int i = 0; i < 20; i++) padRollover.tick(true);
		check(padRollover.snapshot().equals(List.of(
			new DungeonBossTimers.Reading("Storm Pad", 20, false),
			new DungeonBossTimers.Reading("Storm Lightning", 500, false),
			new DungeonBossTimers.Reading("Storm", 20, true))),
			"Storm pad restarts at 20 ticks while the lightning and Storm timers continue independently");

		DungeonBossTimers alternatePowerLine = new DungeonBossTimers();
		alternatePowerLine.onChat("[BOSS] Storm: Pathetic Maxor, just like expected.");
		alternatePowerLine.onChat("[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!");
		check(alternatePowerLine.snapshot().stream().anyMatch(row -> row.name().equals("Storm PY")
			&& row.ticks() == 55), "Storm's alternate power cue starts the same one-shot timer");
	}

	private static void checkTerminalCounts() {
		DungeonTerminalBreakdown terminals = new DungeonTerminalBreakdown();
		check(terminals.observeTitle("ArcherOne activated a terminal! (2/7)", "Self", false) != null,
			"valid terminal title is recorded");
		check(terminals.observeTitle("Other completed a device! (1/1)", "Self", true) == null,
			"self-only mode rejects teammate title events");
		terminals.observeTitle("ArcherOne activated a lever! (1/2)", "Self", false);
		terminals.observeTitle("BerserkOne completed a device! (1/3)", "Self", false);
		terminals.observeTitle("archerone completed a device! (2/3)", "Self", false);
		List<DungeonTerminalBreakdown.PlayerSummary> players = terminals.perPlayer();
		check(players.size() == 2 && players.getFirst().player().equals("ArcherOne")
			&& players.getFirst().terminals() == 1 && players.getFirst().levers() == 1
			&& players.getFirst().devices() == 1 && players.getLast().player().equals("BerserkOne")
			&& players.getLast().devices() == 1,
			"per-player counts merge case-only name changes and preserve first-seen display names");
	}

	private static void checkF7TerminalSections() {
		DungeonTerminalSectionTracker sections = new DungeonTerminalSectionTracker();
		sections.begin(100L, 10L, 20L);
		long at = 110L;
		at = terminalTask(sections, at, "Alice activated a device! (1/1)");
		at = terminalTask(sections, at, "Alice activated a device! (1/1)"); // Duplicate for the same player/index.
		at = terminalTask(sections, at, "Bob activated a device! (1/1)"); // S1 carries this to S2.
		at = terminalTask(sections, at, "Cora activated a terminal! (1/7)");
		at = terminalTask(sections, at, "Cora activated a terminal! (1/7)"); // Exact duplicate stays suppressed.
		at = terminalTask(sections, at, "Cora activated a lever! (1/2)"); // Same player/index, new task type.
		at = terminalTask(sections, at, "Dina activated a terminal! (4/7)");
		at = terminalTask(sections, at, "Eli activated a terminal! (5/7)");
		at = terminalTask(sections, at, "Fay activated a lever! (1/2)");
		at = terminalTask(sections, at, "Hal activated a device! (2/2)");
		sections.observeChat("The gate has been destroyed!", at, 30L, 40L);
		check(sections.drainNewlyFinished().isEmpty(),
			"S1 waits for four distinct terminal tasks despite the gate, levers, and device being done");
		at = terminalTask(sections, at + 10L, "Ian activated a terminal! (6/7)");
		List<DungeonTerminalSectionTracker.Reading> first = sections.drainNewlyFinished();
		check(first.size() == 1 && first.getFirst().id().equals("S1")
			&& sections.snapshot(at, 40L, 50L).getLast().id().equals("S2"),
			"same-player terminal and lever events both count, exact duplicates do not, and the fourth terminal closes S1");

		at = terminalSectionSignals(sections, at, 5, true, false);
		List<DungeonTerminalSectionTracker.Reading> second = sections.drainNewlyFinished();
		check(second.size() == 1 && second.getFirst().id().equals("S2")
			&& sections.snapshot(at, 80L, 90L).getLast().id().equals("S3"),
			"S1's device carry completes S2 only after its own five terminals, two levers, and gate");

		at = terminalSectionSignals(sections, at, 4, true, true);
		List<DungeonTerminalSectionTracker.Reading> third = sections.drainNewlyFinished();
		check(third.size() == 1 && third.getFirst().id().equals("S3")
			&& sections.snapshot(at, 120L, 130L).getLast().id().equals("S4"),
			"S3 still needs its own device and starts S4 after four terminals, two levers, and the gate");

		at = terminalSectionSignals(sections, at, 4, false, false);
		List<DungeonTerminalSectionTracker.Reading> fourth = sections.drainNewlyFinished();
		check(fourth.size() == 1 && fourth.getFirst().id().equals("S4") && !sections.active(),
			"S1's carry-over supplies S4 device completion, whose gate is already open by default");
		check(!sections.observeChat("The gate has been destroyed!", at + 1L, 130L, 140L),
			"finished terminal sections ignore later duplicate lines");
		sections.reset();
		check(sections.begin(at + 2L, 140L, 150L) && sections.snapshot(at + 2L, 140L, 150L)
			.getFirst().id().equals("S1"), "reset begins a fresh bounded S1 state");

		DungeonSplitTracker tracker = new DungeonSplitTracker();
		tracker.begin(DungeonFloor.F7, 1_000L, 1L, 1L, 100L);
		check(tracker.observeChat(DungeonFloor.F7, "[BOSS] Goldor: Who dares trespass into my domain?",
			2_000L, 2L, 2L, 110L), "normal F7 terminal-domain cue starts the aggregate and nested S1");
		List<DungeonSplitTracker.Split> live = tracker.snapshot(3_000L, 3L, 3L, 120L);
		DungeonSplitTracker.Split liveSection = live.stream().filter(row -> row.id().equals("S1"))
			.findFirst().orElseThrow();
		check(live.stream().anyMatch(row -> row.id().equals("TERMINALS") && !row.complete())
			&& !liveSection.complete() && liveSection.elapsedServerTicks() == 10L,
			"HUD snapshot keeps aggregate Terminals and S1 server-tick deltas together");
		check(tracker.observeChat(DungeonFloor.F7, "The Core entrance is opening!", 4_000L, 4L, 4L, 130L)
			&& tracker.currentId().equals("GOLDOR"), "Core opening ends the nested section and aggregate interval");
		List<DungeonSplitTracker.Split> closed = tracker.drainNewlyFinished();
		check(closed.stream().anyMatch(row -> row.id().equals("TERMINALS") && row.elapsedServerTicks() == 20L)
			&& closed.stream().anyMatch(row -> row.id().equals("S1") && row.elapsedServerTicks() == 20L)
			&& DungeonGuideSegments.contains(DungeonFloor.F7, "S1")
			&& DungeonGuideSegments.contains(DungeonFloor.M7, "S4"),
			"Core cutoff closes its live partial subsection and F7/M7 split IDs are accepted");
		tracker.tickContext(DungeonFloor.F6);
		check(!tracker.running() && tracker.snapshot(5_000L, 5L, 5L).stream().noneMatch(row -> row.id().startsWith("S")),
			"floor changes clear terminal section state with the run context");
	}

	private static long terminalTask(DungeonTerminalSectionTracker sections, long nowNanos, String line) {
		sections.observeChat(line, nowNanos, nowNanos / 10L, nowNanos / 5L);
		return nowNanos + 10L;
	}

	private static long terminalSectionSignals(DungeonTerminalSectionTracker sections, long start,
		int terminalCount, boolean includeGate, boolean includeDevice) {
		long at = start;
		for (int i = 0; i < terminalCount; i++) at = terminalTask(sections, at,
			"Term" + i + " activated a terminal! (" + (3 + i) + "/7)");
		at = terminalTask(sections, at, "LeverA activated a lever! (3/4)");
		at = terminalTask(sections, at, "LeverB activated a lever! (4/4)");
		if (includeDevice) at = terminalTask(sections, at, "DeviceA completed a device! (2/2)");
		if (includeGate) sections.observeChat("The gate has been destroyed!", at, at / 10L, at / 5L);
		return at + 10L;
	}

	private static void checkSplitEntryBuffer() {
		DungeonSplitEntryBuffer buffer = new DungeonSplitEntryBuffer();
		Object level = new Object();
		Object connection = new Object();
		check(buffer.capture(DungeonFloor.F7, level, connection, 1_000L, 12L, 3L, 33L),
			"run countdown can be buffered with a confirmed queue floor and connection");
		check(buffer.consumeIfReady(false, null, level, connection, 2_000L) == null && buffer.isPending(),
			"buffer waits while dungeon context is not yet confirmed");
		DungeonSplitEntryBuffer.Pending pending = buffer.consumeIfReady(true, DungeonFloor.F7,
			level, connection, 3_000L);
		check(pending != null && pending.floor() == DungeonFloor.F7 && pending.capturedAtNanos() == 1_000L
			&& pending.clientTick() == 12L && pending.serverPingPackets() == 3L
			&& pending.serverTickCount() == 33L && !buffer.isPending()
			&& buffer.consumeIfReady(true, DungeonFloor.F7, level, connection, 4_000L) == null,
			"matching dungeon consumes the original countdown timestamp once");
		buffer.capture(DungeonFloor.F7, level, connection, 5_000L, 50L, 5L, 55L);
		buffer.capture(DungeonFloor.F7, level, connection, 6_000L, 60L, 6L, 66L);
		pending = buffer.consumeIfReady(true, DungeonFloor.F7, level, connection, 7_000L);
		check(pending != null && pending.capturedAtNanos() == 6_000L && pending.clientTick() == 60L
			&& pending.serverPingPackets() == 6L && pending.serverTickCount() == 66L,
			"the newest buffered countdown replaces an earlier same-session start cue");

		buffer.capture(DungeonFloor.F7, level, connection, 10_000L, 20L, 4L);
		buffer.discardIfStale(true, DungeonFloor.F6, level, connection, 11_000L);
		check(!buffer.isPending(), "confirmed floor change discards a pending countdown");
		buffer.capture(DungeonFloor.F7, level, connection, 20_000L, 30L, 5L);
		check(buffer.consumeIfReady(true, DungeonFloor.F7, new Object(), connection, 21_000L) == null
			&& !buffer.isPending(), "level replacement discards a pending countdown");
		buffer.capture(DungeonFloor.F7, level, connection, 30_000L, 40L, 6L);
		check(buffer.consumeIfReady(true, DungeonFloor.F7, level, new Object(), 31_000L) == null
			&& !buffer.isPending(), "connection replacement discards a pending countdown");
		buffer.capture(DungeonFloor.F7, level, connection, 40_000L, 50L, 7L);
		buffer.discardIfStale(false, null, level, connection,
			40_000L + DungeonSplitEntryBuffer.MAX_PENDING_NANOS + 1L);
		check(!buffer.isPending(), "expired countdown cannot start a later dungeon run");
	}

	private static void checkSplitSignals() {
		List<String> watcherOpenCues = List.of(
			"Congratulations, you made it through the Entrance.",
			"Ah, you've finally arrived.",
			"Ah, we meet again...",
			"So you made it this far... interesting...",
			"You've managed to scratch and claw your way here, eh?",
			"I'm starting to get tired of seeing you around here...",
			"Oh... hello?",
			"Things feel a little more roomy now, eh?"
		);
		for (DungeonFloor floor : DungeonFloor.values()) {
			DungeonGuideSegments.Event entry = DungeonGuideSegments.fromChat(floor, "Starting in 1 second.");
			DungeonGuideSegments.Event clear = DungeonGuideSegments.fromChat(floor,
				"[NPC] Mort: Here, I found this map when I first entered the dungeon.");
			DungeonGuideSegments.Event bloodOpen = DungeonGuideSegments.fromChat(floor,
				"The Blood door has been opened!");
			DungeonGuideSegments.Event bloodClear = DungeonGuideSegments.fromChat(floor,
				"The Watcher: You have proven yourself. You may pass.");
			check(entry != null && entry.segmentId().equals("ENTRY") && !entry.independentCue()
				&& clear != null && clear.segmentId().equals("CLEAR")
				&& bloodOpen != null && bloodOpen.segmentId().equals("BLOOD_OPEN")
				&& bloodClear != null && bloodClear.segmentId().equals("BLOOD_CLEAR"),
				"entry and blood phases have observed signals on " + floor);
			for (String cue : watcherOpenCues) {
				DungeonGuideSegments.Event watcherOpen = DungeonGuideSegments.fromChat(floor,
					"[BOSS] The Watcher: " + cue);
				check(watcherOpen != null && watcherOpen.segmentId().equals("BLOOD_OPEN"),
					"Watcher Blood Open variant is recognized on " + floor + ": " + cue);
			}
			String bossEntry = switch (floor.number()) {
				case 0, 1 -> "Bonzo: I am basically unbeatable!";
				case 2 -> "Scarf: Your journey ends here!";
				case 3 -> "Professor: I am burdened with terrible news.";
				case 4 -> "Thorn: Welcome adventurers!";
				case 5 -> "Livid: I am the Master of Shadows.";
				case 6 -> "Sadan: You dare defy me?";
				case 7 -> "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!";
				default -> throw new AssertionError("unknown floor number");
			};
			DungeonGuideSegments.Event boss = DungeonGuideSegments.fromChat(floor, bossEntry);
			String bossEntrySegment = switch (floor.number()) {
				case 0 -> "BONZO";
				case 1 -> "BONZO_SIKE";
				case 2 -> "SCARF_MINIONS";
				case 3 -> "GUARDIANS";
				case 4 -> "THORN";
				case 5 -> "LIVID";
				case 6 -> "TERRACOTTAS";
				case 7 -> "MAXOR";
				default -> throw new AssertionError("unknown floor number");
			};
			check(boss != null && boss.segmentId().equals(bossEntrySegment),
				"floor boss-entry cue is recognized on " + floor);
			String defeated = floor.number() == 7 && floor.master()
				? "☠ Defeated Wither King in 1m 2s!"
				: floor.number() == 7 ? "☠ Defeated Necron in 1m 2s!"
				: floor.number() == 0 || floor.number() == 1 ? "☠ Defeated Bonzo in 1m 2s!"
				: floor.number() == 2 ? "☠ Defeated Scarf in 1m 2s!"
				: floor.number() == 3 ? "☠ Defeated The Professor in 1m 2s!"
				: floor.number() == 4 ? "☠ Defeated Thorn in 1m 2s!"
				: floor.number() == 5 ? "☠ Defeated Livid in 1m 2s!"
				: "☠ Defeated Sadan in 1m 2s!";
			DungeonGuideSegments.Event complete = DungeonGuideSegments.fromChat(floor, defeated);
			check(complete != null && complete.segmentId().equals("COMPLETE"),
				"floor boss defeat closes the observed run on " + floor);
		}

		List<Milestone> milestones = List.of(
			new Milestone(DungeonFloor.F1, "[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable.", "BONZO_SIKE"),
			new Milestone(DungeonFloor.F1, "Bonzo: Oh I'm dead!", "BONZO"),
			new Milestone(DungeonFloor.F2, "Scarf: This is where the journey ends for you, Adventurers.", "SCARF_MINIONS"),
			new Milestone(DungeonFloor.F2, "[BOSS] Scarf: Those toys are not strong enough I see.", "SCARF"),
			new Milestone(DungeonFloor.F3, "[BOSS] The Professor: I was burdened with terrible news recently...", "GUARDIANS"),
			new Milestone(DungeonFloor.F3, "Professor: Oh? You found my Guardians' one weakness?", "PROFESSOR"),
			new Milestone(DungeonFloor.F3, "Professor: I see. You have forced me to use my ultimate technique.", "PROFESSOR_TRANSFORMED"),
			new Milestone(DungeonFloor.F6, "[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!", "TERRACOTTAS"),
			new Milestone(DungeonFloor.F6, "Sadan: ENOUGH!", "SADAN_GIANTS"),
			new Milestone(DungeonFloor.F6, "Sadan: You did it. I understand now, you have earned my respect.", "SADAN"),
			new Milestone(DungeonFloor.F7, "[BOSS] Storm: Pathetic Maxor, just like expected.", "STORM"),
			new Milestone(DungeonFloor.F7, "[BOSS] Goldor: Who dares trespass into my domain?", "TERMINALS"),
			new Milestone(DungeonFloor.F7, "Alice activated a terminal! (1/7)", "TERMINALS"),
			new Milestone(DungeonFloor.F7, "The Core entrance is opening!", "GOLDOR"),
			new Milestone(DungeonFloor.F7, "[BOSS] Necron: You went further than any human before, congratulations.", "NECRON"),
			new Milestone(DungeonFloor.M7, "Necron: All this, for nothing!", "NECRON"),
			new Milestone(DungeonFloor.M7, "[BOSS] Necron: ARGH!", "WITHER_KING"),
			new Milestone(DungeonFloor.M7, "Wither King: You... again?", "WITHER_KING"));
		for (Milestone milestone : milestones) {
			DungeonSplitTracker tracker = new DungeonSplitTracker();
			tracker.begin(milestone.floor(), 1_000_000_000L, 0L);
			DungeonGuideSegments.Event event = DungeonGuideSegments.fromChat(milestone.floor(), milestone.message());
			check(event != null && tracker.observePhase(milestone.floor(), event.segmentId(), 2_000_000_000L, 20L)
				&& milestone.segment().equals(tracker.currentId()),
				"observable boss phase advances the split to " + milestone.segment());
		}
		checkIndependentCues();
		checkSplitPhaseSequence();
		check(DungeonGuideSegments.fromChat(DungeonFloor.F5, "The dungeon is clear.") == null,
			"generic Clear text is not treated as a stable split signal");
		check(DungeonGuideSegments.fromChat(DungeonFloor.F5, "[BOSS] The Watcher: This line is not a known cue.") == null
			&& DungeonGuideSegments.fromChat(DungeonFloor.F6, "Alice activated a terminal! (1/7)") == null
			&& DungeonGuideSegments.fromChat(DungeonFloor.F7, "[BOSS] Necron: ARGH!") == null,
			"unknown Watcher text, terminal progress off F7, and Wither King cue off M7 are rejected");
	}

	private static void checkIndependentCues() {
		List<Milestone> cues = List.of(
			new Milestone(DungeonFloor.F2,
				"[BOSS] Scarf: Did you forget? I was taught by the best! Let's dance.", "SCARF_DANCE_CUE"),
			new Milestone(DungeonFloor.F3,
				"[BOSS] The Professor: What?! My Guardian power is unbeatable!",
				"PROFESSOR_GUARDIAN_POWER_CUE"),
			new Milestone(DungeonFloor.F7, "[BOSS] Storm: ENERGY HEED MY CALL!", "STORM_LIGHTNING_ENERGY_CUE"),
			new Milestone(DungeonFloor.F7, "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!",
				"STORM_LIGHTNING_THUNDER_CUE"));
		for (Milestone cue : cues) {
			DungeonSplitTracker tracker = new DungeonSplitTracker();
			tracker.begin(cue.floor(), 1_000_000_000L, 10L);
			DungeonGuideSegments.Event event = DungeonGuideSegments.fromChat(cue.floor(), cue.message());
			check(event != null && event.independentCue() && event.segmentId().equals(cue.segment())
				&& !tracker.observeCue(cue.floor() == DungeonFloor.F3 ? DungeonFloor.F2 : DungeonFloor.F3,
					event, 1_500_000_000L, 15L)
				&& tracker.observeCue(cue.floor(), event, 2_000_000_000L, 20L)
				&& !tracker.observeCue(cue.floor(), event, 2_100_000_000L, 21L)
				&& tracker.currentId().equals("ENTRY") && tracker.drainNewlyFinished().isEmpty()
				&& tracker.cueMarkers().size() == 1,
				"independent cue is labeled, exact-once, and excluded from timed phase intervals: " + cue.segment());
		}

		DungeonSplitTracker forward = new DungeonSplitTracker();
		forward.begin(DungeonFloor.F3, 1L, 1L);
		DungeonGuideSegments.Event ultimate = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"Professor: I see. You have forced me to use my ultimate technique.");
		DungeonGuideSegments.Event guardianPower = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"Professor: What?! My Guardian power is unbeatable!");
		check(ultimate != null && !ultimate.independentCue()
			&& ultimate.segmentId().equals("PROFESSOR_TRANSFORMED")
			&& guardianPower != null && guardianPower.independentCue()
			&& guardianPower.segmentId().equals("PROFESSOR_GUARDIAN_POWER_CUE"),
			"Devonian's transformed phase and Odin's guardian-power cue retain distinct identities");
		check(forward.observeEvent(DungeonFloor.F3, ultimate, 2L, 2L, 2L)
			&& forward.observeEvent(DungeonFloor.F3, guardianPower, 3L, 3L, 3L)
			&& forward.currentId().equals("PROFESSOR_TRANSFORMED")
			&& forward.cueMarkers().getFirst().id().equals("PROFESSOR_GUARDIAN_POWER_CUE"),
			"F3 transformed phase can precede the independently timed Odin marker");
		DungeonSplitTracker reverse = new DungeonSplitTracker();
		reverse.begin(DungeonFloor.F3, 1L, 1L);
		check(reverse.observeEvent(DungeonFloor.F3, guardianPower, 2L, 2L, 2L)
			&& reverse.observeEvent(DungeonFloor.F3, ultimate, 3L, 3L, 3L)
			&& reverse.currentId().equals("PROFESSOR_TRANSFORMED")
			&& reverse.cueMarkers().getFirst().id().equals("PROFESSOR_GUARDIAN_POWER_CUE"),
			"F3 Odin marker can precede the Devonian stage without asserting a cross-source order");
	}

	private static void checkF7SplitPhaseSequence() {
		DungeonSplitTracker tracker = new DungeonSplitTracker();
		tracker.begin(DungeonFloor.M7, 1_000_000_000L, 10L);
		check(tracker.observeChat(DungeonFloor.M7, "The BLOOD DOOR has been opened!", 2_000_000_000L, 20L)
			&& tracker.observeChat(DungeonFloor.M7, "[BOSS] The Watcher: You have proven yourself. You may pass.",
				3_000_000_000L, 30L), "M7 advances through observed Blood milestones");
		check(tracker.observeChat(DungeonFloor.M7, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!",
			4_000_000_000L, 40L) && tracker.currentId().equals("MAXOR"),
			"M7 Maxor opening starts MAXOR directly instead of an extra generic Boss Entry interval");
		check(tracker.observeChat(DungeonFloor.M7, "[BOSS] Storm: Pathetic Maxor, just like expected.",
			5_000_000_000L, 50L) && tracker.currentId().equals("STORM"),
			"Storm dialogue ends the Maxor interval and starts Storm");
		check(tracker.observeChat(DungeonFloor.M7, "[BOSS] Goldor: Who dares trespass into my domain?",
			6_000_000_000L, 60L) && tracker.currentId().equals("TERMINALS"),
			"Goldor's domain line starts the terminal stage");
		check(tracker.observeChat(DungeonFloor.M7, "Alice completed a device! (1/1)",
			6_500_000_000L, 65L) && tracker.currentId().equals("TERMINALS"),
			"terminal progress feeds subsection state without splitting the aggregate twice");
		check(tracker.observeChat(DungeonFloor.M7, "The Core entrance is opening!", 7_000_000_000L, 70L)
			&& tracker.currentId().equals("GOLDOR"), "Core opening starts the Goldor interval");
		check(tracker.observeChat(DungeonFloor.M7,
			"[BOSS] Necron: All this, for nothing...", 8_000_000_000L, 80L)
			&& tracker.currentId().equals("NECRON"), "M7 Necron line starts the Necron interval");
		check(!tracker.observeChat(DungeonFloor.M7, "[BOSS] Necron: All this, for nothing...",
			8_100_000_000L, 81L), "repeated Necron cue is one-shot");
		check(tracker.observeChat(DungeonFloor.M7, "[BOSS] Necron: ARGH!", 9_000_000_000L, 90L)
			&& tracker.currentId().equals("WITHER_KING"), "Necron defeat dialogue starts the M7 Wither King phase");
		check(!tracker.observeChat(DungeonFloor.M7, "[BOSS] The Wither King: You... again?",
			9_100_000_000L, 91L) && tracker.currentId().equals("WITHER_KING"),
			"later Wither King intro does not duplicate the phase transition");
		check(tracker.observeChat(DungeonFloor.M7, "☠ Defeated Wither King in 1m 2s!",
			10_000_000_000L, 100L) && tracker.complete(), "observed final defeat completes M7");
		List<DungeonSplitTracker.Split> rows = tracker.drainNewlyFinished();
		List<String> splitIds = rows.stream().map(DungeonSplitTracker.Split::id).toList();
		check(splitIds.equals(List.of("ENTRY", "BLOOD", "BLOOD_OPEN", "CAMP", "BLOOD_CLEAR", "PORTAL",
			"MAXOR", "STORM", "TERMINALS", "S1", "GOLDOR", "NECRON", "WITHER_KING", "COMPLETE")),
			"ordered M7 cues close aggregate, observed terminal subsection, and run total");
	}

	private static void checkSplitPhaseSequence() {
		DungeonSplitTracker tracker = new DungeonSplitTracker();
		tracker.begin(DungeonFloor.F3, 1_000_000_000L, 10L);
		check(tracker.observePhase(DungeonFloor.F3, "GUARDIANS", 1_500_000_000L, 20L, 5L, 110L),
			"an observed boss phase closes the first Entry interval before a retry");
		DungeonGuideSegments.Event retry = DungeonGuideSegments.fromChat(DungeonFloor.F3, "Starting in 1 second.");
		check(retry != null && tracker.observeEvent(DungeonFloor.F3, retry, 2_000_000_000L, 30L, 8L, 120L)
			&& tracker.currentId().equals("ENTRY"),
			"a same-floor retry cue starts a fresh run and returns true for the Run Splits ENTRY_START path");
		List<DungeonSplitTracker.Split> restarted = tracker.snapshot(2_500_000_000L, 40L, 13L, 130L);
		check(restarted.stream().map(DungeonSplitTracker.Split::id).toList()
				.equals(List.of("ENTRY", "BLOOD", "RUN"))
			&& restarted.stream().noneMatch(DungeonSplitTracker.Split::complete)
			&& restarted.getFirst().elapsedNanos() == 500_000_000L
			&& restarted.getFirst().elapsedTicks() == 10L
			&& restarted.getFirst().elapsedPingPackets() == 5L
			&& restarted.getFirst().elapsedServerTicks() == 10L
			&& tracker.drainNewlyFinished().isEmpty(),
			"a same-floor retry clears old split history and restarts all timing baselines at its cue");
		DungeonGuideSegments.Event guardians = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"[BOSS] The Professor: I was burdened with terrible news recently...");
		check(guardians != null && tracker.observePhase(DungeonFloor.F3, guardians.segmentId(),
			3_000_000_000L, 50L, 28L, 140L) && tracker.currentId().equals("GUARDIANS"),
			"Professor opening cue advances to the Guardian interval");
		check(!tracker.observePhase(DungeonFloor.F3, guardians.segmentId(), 2_100_000_000L, 31L)
			&& !tracker.observePhase(DungeonFloor.F3, "BLOOD_CLEAR", 2_200_000_000L, 32L)
			&& !tracker.observePhase(DungeonFloor.F2, "SCARF_MINIONS", 2_300_000_000L, 33L),
			"duplicate, out-of-order, and wrong-floor phase events are ignored");
		List<DungeonSplitTracker.Split> entryClosed = tracker.drainNewlyFinished();
		check(entryClosed.size() == 1 && entryClosed.getFirst().id().equals("ENTRY")
			&& entryClosed.getFirst().elapsedNanos() == 1_000_000_000L
			&& entryClosed.getFirst().elapsedTicks() == 20L,
			"duplicate phase cues do not enqueue duplicate closed splits");

		DungeonGuideSegments.Event professor = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"Professor: Oh? You found my Guardians' one weakness?");
		DungeonGuideSegments.Event ultimate = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"Professor: I see. You have forced me to use my ultimate technique.");
		DungeonGuideSegments.Event guardianPower = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"Professor: What?! My Guardian power is unbeatable!");
		DungeonGuideSegments.Event complete = DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"☠ Defeated The Professor in 1m 2s!");
		check(professor != null && tracker.observePhase(DungeonFloor.F3, professor.segmentId(),
			3_000_000_000L, 50L)
			&& tracker.currentId().equals("PROFESSOR")
			&& ultimate != null && guardianPower != null
			&& tracker.observeEvent(DungeonFloor.F3, ultimate, 3_500_000_000L, 60L, 60L)
			&& tracker.currentId().equals("PROFESSOR_TRANSFORMED")
			&& tracker.observeEvent(DungeonFloor.F3, guardianPower, 4_000_000_000L, 70L, 70L)
			&& !tracker.observeEvent(DungeonFloor.F3, ultimate, 4_100_000_000L, 71L, 71L)
			&& tracker.currentId().equals("PROFESSOR_TRANSFORMED")
			&& complete != null && tracker.observePhase(DungeonFloor.F3, complete.segmentId(),
				5_000_000_000L, 90L),
			"Devonian transformed phase and Odin's independent marker remain separate before defeat");
		List<DungeonSplitTracker.Split> remaining = tracker.drainNewlyFinished();
		check(tracker.complete() && remaining.size() == 4
			&& remaining.get(0).id().equals("GUARDIANS")
			&& remaining.get(1).id().equals("PROFESSOR")
			&& remaining.get(2).id().equals("PROFESSOR_TRANSFORMED")
			&& remaining.get(3).id().equals("COMPLETE")
			&& tracker.cueMarkers().size() == 1,
			"Devonian supplies the transformed interval while Odin's unpaired cue never invents another interval"
				+ " (complete=" + tracker.complete() + ", closed=" + remaining + ", cues=" + tracker.cueMarkers() + ")");
		tracker.tickContext(null);
		check(!tracker.running() && tracker.observePhase(DungeonFloor.F3, "ENTRY", 6_000_000_000L, 110L),
			"leaving the dungeon resets the completed run so the same floor can start again");
	}

	private static void checkIncomingLeapChat() {
		List<String> roster = List.of("Self", "Alice", "Bob");
		var first = SpiritLeapIncomingRules.parse("Party > [MVP+] Alice: Self is ready", "Self", roster).orElseThrow();
		var variant = SpiritLeapIncomingRules.parse("Party > Alice: tp successful to Self; can you enter now?",
			"Self", roster).orElseThrow();
		check(first.teammateName().equals("Alice") && first.targetName().equals("Self") && first.equals(variant),
			"party wording is flexible while sender and target stay tied to explicitly present roster names");
		check(SpiritLeapIncomingRules.parse("Party > Alice: tp successful; can you enter now?", "Self", roster).isEmpty()
			&& SpiritLeapIncomingRules.parse("Party > Stranger: Self is ready", "Self", roster).isEmpty()
			&& SpiritLeapIncomingRules.parse("Guild > Alice: Self is ready", "Self", roster).isEmpty(),
			"incoming feedback does not infer a missing target or accept non-roster/non-party lines");
		SpiritLeapIncomingRules.Coalescer coalescer = new SpiritLeapIncomingRules.Coalescer();
		check(coalescer.accept(first, 100L)
			&& !coalescer.accept(variant, 100L + SpiritLeapIncomingRules.COALESCE_NANOS)
			&& coalescer.accept(variant, 101L + SpiritLeapIncomingRules.COALESCE_NANOS),
			"reworded duplicate party lines coalesce for two seconds then may alert again");
	}

	private static void checkSpecialMobGatesAndPartyPalette() {
		DungeonSpecialMobRules.ChestLocation known = new DungeonSpecialMobRules.ChestLocation("room-a", 10, 70, 20);
		check(DungeonSpecialMobRules.shouldHighlightMimic(known, known, true, true),
			"Mimic rule requires the known coordinate, trapped chest, and Clear phase");
		check(!DungeonSpecialMobRules.shouldHighlightMimic(known,
			new DungeonSpecialMobRules.ChestLocation("room-a", 11, 70, 20), true, true)
			&& !DungeonSpecialMobRules.shouldHighlightMimic(known,
				new DungeonSpecialMobRules.ChestLocation("room-b", 10, 70, 20), true, true)
			&& !DungeonSpecialMobRules.shouldHighlightMimic(known, known, false, true)
			&& !DungeonSpecialMobRules.shouldHighlightMimic(known, known, true, false),
			"Mimic gate rejects unknown coordinate, another room, normal chest, and other phases");
		check(DungeonSpecialMobRules.isF7WitherBoss(true, false, true, "Wither")
			&& !DungeonSpecialMobRules.isF7WitherBoss(false, false, true, "Wither")
			&& DungeonSpecialMobRules.isF7WitherBoss(true, true, true, "Wither")
			&& !DungeonSpecialMobRules.isF7WitherBoss(true, false, false, "Wither")
			&& !DungeonSpecialMobRules.isF7WitherBoss(true, false, true, "Wither King"),
			"F7 Wither gate covers regular and Master F7 and excludes other floors, other entity types, and Wither King");
		check(DungeonSpecialMobRules.shouldScanF7WitherBoss(true, true, true, false, true)
			&& !DungeonSpecialMobRules.shouldScanF7WitherBoss(true, false, true, false, true)
			&& !DungeonSpecialMobRules.shouldScanF7WitherBoss(true, true, true, false, false)
			&& !DungeonSpecialMobRules.shouldScanF7WitherBoss(false, true, true, false, true)
			&& !DungeonSpecialMobRules.shouldScanF7WitherBoss(true, true, false, false, true)
			&& DungeonSpecialMobRules.shouldScanF7WitherBoss(true, true, true, true, true),
			"Wither name and entity paths require an enabled F7 dungeon (regular or Master) after observed boss entry");
		check(DungeonSpecialMobRules.isEarlyF7WitherBoss(true, false, true, true)
			&& !DungeonSpecialMobRules.isEarlyF7WitherBoss(true, false, false, true)
			&& !DungeonSpecialMobRules.isEarlyF7WitherBoss(false, false, true, true)
			&& DungeonSpecialMobRules.isEarlyF7WitherBoss(true, true, true, true)
			&& !DungeonSpecialMobRules.isEarlyF7WitherBoss(true, false, true, false),
			"the pre-name type path requires observed boss phase and the exact F7 Wither entity, regular or Master");
		check(DungeonSpecialMobRules.defaultClassColor(DungeonClass.ARCHER) == 0xFFFFAA00
			&& DungeonSpecialMobRules.defaultClassColor(DungeonClass.BERSERK) == 0xFFAA0000
			&& DungeonSpecialMobRules.defaultClassColor(DungeonClass.HEALER) == 0xFFFF55FF
			&& DungeonSpecialMobRules.defaultClassColor(DungeonClass.MAGE) == 0xFF55FFFF
			&& DungeonSpecialMobRules.defaultClassColor(DungeonClass.TANK) == 0xFF00AA00,
			"party outline defaults match Odin's leap-menu colors");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record Milestone(DungeonFloor floor, String message, String segment) { }
}
