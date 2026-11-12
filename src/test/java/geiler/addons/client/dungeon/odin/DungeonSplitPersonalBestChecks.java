package geiler.addons.client.dungeon.odin;

import geiler.addons.client.dungeon.ClientJsonFile;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonSplitPersonalBestBook;
import geiler.addons.client.dungeon.DungeonSplitPersonalBestStoreSession;
import geiler.addons.client.dungeon.DungeonSplitTimeMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Focused offline checks for split signals, metric separation, PB persistence data, and formatting. */
public final class DungeonSplitPersonalBestChecks {
	private DungeonSplitPersonalBestChecks() { }

	public static void run() {
		checkStorePersistence();
		checkServerTickCounterAndBoundaries();
		checkHistoryAndPersonalBestQueueStayConsistent();
		DungeonSplitTracker tracker = new DungeonSplitTracker();
		require(tracker.observe(DungeonFloor.F5, "ENTRY", 1_000_000_000L, 20, 100),
			"run begins on the existing Entry milestone");
		require(tracker.observe(DungeonFloor.F5, "BLOOD_OPEN", 4_000_000_000L, 80, 160),
			"the existing client-observed blood-door milestone closes Entry");
		List<DungeonSplitTracker.Split> closed = tracker.drainNewlyFinished();
		require(closed.size() == 2 && closed.getFirst().id().equals("ENTRY")
			&& closed.getLast().id().equals("BLOOD"),
			"Blood Open records the just-closed stage and the run-wide Blood aggregate");
		DungeonSplitTracker.Split entry = closed.getFirst();
		require(entry.elapsedNanos() == 3_000_000_000L && entry.elapsedTicks() == 60
			&& entry.elapsedPingPackets() == 60,
			"real time, local client ticks, and server ping packets remain distinct measurements");
		DungeonSplitTracker.Split current = tracker.snapshot(5_000_000_000L, 100, 180).stream()
			.filter(row -> row.id().equals("BLOOD_OPEN")).findFirst().orElseThrow();
		require(!current.complete() && current.elapsedTicks() == 20 && current.elapsedPingPackets() == 20,
			"the active split reports only its own elapsed counts");
		require(tracker.observeChat(DungeonFloor.F5,
			"[BOSS] The Watcher: You have proven yourself. You may pass.",
			6_000_000_000L, 120, 200), "the current Watcher milestone closes the blood-open split");
		DungeonSplitTracker.Split bloodOpen = tracker.drainNewlyFinished().getFirst();
		require(bloodOpen.id().equals("BLOOD_OPEN") && bloodOpen.elapsedNanos() == 2_000_000_000L
			&& bloodOpen.elapsedTicks() == 40 && bloodOpen.elapsedPingPackets() == 40,
			"each newly observed split resets its independent packet-count baseline");

		DungeonSplitPersonalBestBook book = DungeonSplitPersonalBestBook.empty();
		DungeonSplitPersonalBestBook.Update first = book.record(DungeonFloor.F5, entry, 1_000L);
		require(first.firstForSplit() && first.isPersonalBest(DungeonSplitTimeMode.REAL_TIME)
			&& first.isPersonalBest(DungeonSplitTimeMode.CLIENT_TICKS)
			&& first.isPersonalBest(DungeonSplitTimeMode.SERVER_PING_PACKETS),
			"first recorded values establish separate PBs for all available metrics");
		DungeonSplitTracker.Split slowerTimeButFewerTicks = new DungeonSplitTracker.Split("ENTRY", "Entry",
			4_000_000_000L, 40, 70, true);
		DungeonSplitPersonalBestBook.Update second = first.book().record(DungeonFloor.F5,
			slowerTimeButFewerTicks, 2_000L);
		require(second.entry().attempts() == 2 && second.entry().bestRealTimeNanos() == 3_000_000_000L
			&& second.entry().bestClientTicks() == 40 && second.entry().bestServerPingPackets() == 60
			&& second.entry().lastRealTimeNanos() == 4_000_000_000L,
			"one floor/split key holds only aggregate best and latest values rather than run history");
		require(!second.isPersonalBest(DungeonSplitTimeMode.REAL_TIME)
			&& second.isPersonalBest(DungeonSplitTimeMode.CLIENT_TICKS)
			&& !second.isPersonalBest(DungeonSplitTimeMode.SERVER_PING_PACKETS),
			"each measurement mode compares against its own PB");
		require(second.book().find(DungeonFloor.F6, "ENTRY") == null,
			"personal bests are keyed by both floor and split ID");

		String encoded = second.book().encode();
		require(encoded.contains("\"version\": 1") && !encoded.contains("serverTick")
			&& !encoded.contains("elapsedServerTicks"),
			"server-tick HUD measurements do not change the legacy version-1 personal-best schema");
		DungeonSplitPersonalBestBook.DecodeResult decoded = DungeonSplitPersonalBestBook.decode(encoded);
		require(decoded.valid() && decoded.book().entries().size() == 1
			&& decoded.book().find(DungeonFloor.F5, "ENTRY").attempts() == 2,
			"versioned split data round-trips as an immutable per-split aggregate");
		require(!DungeonSplitPersonalBestBook.decode(encoded.replace("\"version\": 1", "\"version\": 2")).valid(),
			"unsupported local data versions are rejected");
		require(!DungeonSplitPersonalBestBook.decode(" ".repeat(DungeonSplitPersonalBestBook.MAX_JSON_BYTES + 1)).valid(),
			"oversized local data is rejected before parsing");
		require(DungeonSplitTimeMode.REAL_TIME.format(65_400_000_000L).equals("1:05.4s")
			&& DungeonSplitTimeMode.CLIENT_TICKS.format(20).equals("20 client ticks")
			&& DungeonSplitTimeMode.SERVER_PING_PACKETS.format(20)
				.equals("20 ping events (Odin proxy; not server ticks)")
			&& DungeonSplitTimeMode.SERVER_PING_PACKETS.format(1)
				.equals("1 ping event (Odin proxy; not server ticks)"),
			"display modes label real time, local tick counts, and the Odin ping-event proxy honestly");

		require(tracker.observeChat(DungeonFloor.F5,
			"[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.",
			7_000_000_000L, 140, 220), "the existing floor-specific boss chat milestone advances the run");
		tracker.drainNewlyFinished();
		require(tracker.observeChat(DungeonFloor.F5, "☠ Defeated Livid", 9_000_000_000L, 180, 260),
			"the existing boss defeat chat milestone closes the run");
		List<DungeonSplitTracker.Split> completion = tracker.drainNewlyFinished();
		require(completion.size() == 2 && completion.getLast().id().equals("COMPLETE")
			&& completion.getLast().elapsedNanos() == 8_000_000_000L
			&& completion.getLast().elapsedTicks() == 160
			&& completion.getLast().elapsedPingPackets() == 160,
			"completion persists the total from Entry and does not invent missing phase observations");

		var transformedProfessor = geiler.addons.client.dungeon.DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"[BOSS] The Professor: I see. You have forced me to use my ultimate technique.");
		var guardianPowerCue = geiler.addons.client.dungeon.DungeonGuideSegments.fromChat(DungeonFloor.F3,
			"[BOSS] The Professor: What?! My Guardian power is unbeatable!");
		require("PROFESSOR_TRANSFORMED".equals(transformedProfessor.segmentId())
			&& !transformedProfessor.independentCue()
			&& "PROFESSOR_GUARDIAN_POWER_CUE".equals(guardianPowerCue.segmentId())
			&& guardianPowerCue.independentCue(),
			"the Devonian transformed phase and Odin guardian-power cue remain separate event types");
		require("WITHER_KING".equals(geiler.addons.client.dungeon.DungeonGuideSegments
			.fromChat(DungeonFloor.M7,
				"[BOSS] The Wither King: You... again?").segmentId()),
			"the stable M7 Wither King dialogue advances its final split");
		require(geiler.addons.client.dungeon.DungeonGuideSegments.fromChat(DungeonFloor.F7,
			"[BOSS] The Wither King: You... again?") == null,
			"the Master-only Wither King milestone does not advance normal F7");
	}

	private static void checkStorePersistence() {
		Path root = null;
		try {
			root = Files.createTempDirectory("ga-dungeon-split-pb-store-");
			Path file = root.resolve("dungeon-split-personal-bests.json");
			String legacyVersionOne = """
				{
				  "version": 1,
				  "entries": [{
				    "floor": "F5",
				    "splitId": "ENTRY",
				    "attempts": 2,
				    "bestRealTimeNanos": 3000000000,
				    "bestClientTicks": 40,
				    "bestServerPingPackets": 50,
				    "lastRealTimeNanos": 4000000000,
				    "lastClientTicks": 60,
				    "lastServerPingPackets": 80,
				    "updatedAtMillis": 1234
				  }]
				}
				""";
			Files.writeString(file, legacyVersionOne, StandardCharsets.UTF_8);

			DungeonSplitPersonalBestStoreSession loaded = new DungeonSplitPersonalBestStoreSession(file);
			loaded.initialize();
			DungeonSplitPersonalBestBook.Entry oldEntry = loaded.find(DungeonFloor.F5, "ENTRY");
			require(oldEntry != null && oldEntry.attempts() == 2
				&& oldEntry.bestRealTimeNanos() == 3_000_000_000L
				&& oldEntry.bestClientTicks() == 40 && oldEntry.bestServerPingPackets() == 50
				&& oldEntry.lastRealTimeNanos() == 4_000_000_000L && oldEntry.updatedAtMillis() == 1234L,
				"the path-bound store loads existing version-1 split bests and all measurement fields");
			require(loaded.find(DungeonFloor.F6, "ENTRY") == null && loaded.find(DungeonFloor.F5, "entry") == null,
				"floor and canonical split ID both participate in the persisted key");

			DungeonSplitTracker.Split slower = new DungeonSplitTracker.Split("ENTRY", "Entry",
				5_000_000_000L, 70, 90, true);
			loaded.record(DungeonFloor.F5, slower, 2_000L);
			loaded.record(DungeonFloor.F6, slower, 3_000L);
			ClientJsonFile.flush();
			require(Files.exists(file) && !Files.readString(file).isBlank(),
				"the asynchronous store write reaches its target before the writer barrier returns");
			try (var children = Files.list(root)) {
				require(children.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
					"atomic store writes leave no temporary file after completion");
			}

			DungeonSplitPersonalBestStoreSession reloaded = new DungeonSplitPersonalBestStoreSession(file);
			reloaded.initialize();
			DungeonSplitPersonalBestBook.Entry f5 = reloaded.find(DungeonFloor.F5, "ENTRY");
			DungeonSplitPersonalBestBook.Entry f6 = reloaded.find(DungeonFloor.F6, "ENTRY");
			require(f5 != null && f5.attempts() == 3 && f5.bestRealTimeNanos() == 3_000_000_000L
				&& f5.lastRealTimeNanos() == 5_000_000_000L && f5.updatedAtMillis() == 2_000L
				&& f6 != null && f6.attempts() == 1 && f6.bestRealTimeNanos() == 5_000_000_000L
				&& reloaded.find(DungeonFloor.F5, "entry") == null,
				"updated and newly added floor/split records survive save, reload, and canonical-key lookup");

			Path malformed = root.resolve("malformed.json");
			assertInvalidStoreFile(malformed, "{not-json".getBytes(StandardCharsets.UTF_8),
				"malformed JSON is retained when store initialization rejects it");
			Path invalidUtf8 = root.resolve("invalid-utf8.json");
			assertInvalidStoreFile(invalidUtf8, new byte[] { (byte) 0xC3, (byte) 0x28 },
				"invalid UTF-8 is retained when store initialization rejects it");
			Path oversized = root.resolve("oversized.json");
			assertInvalidStoreFile(oversized,
				new byte[DungeonSplitPersonalBestBook.MAX_JSON_BYTES + 1],
				"oversized data is retained when store initialization rejects it");
		} catch (Exception failure) {
			throw new AssertionError("dungeon split personal-best store persistence check failed", failure);
		} finally {
			if (root != null) {
				try (var paths = Files.walk(root)) {
					for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
						Files.deleteIfExists(path);
					}
				} catch (IOException cleanupFailure) {
					throw new AssertionError("Could not remove isolated dungeon split PB files", cleanupFailure);
				}
			}
		}
	}

	private static void assertInvalidStoreFile(Path file, byte[] contents, String message) throws IOException {
		Files.write(file, contents);
		DungeonSplitPersonalBestStoreSession store = new DungeonSplitPersonalBestStoreSession(file);
		store.initialize();
		require(store.find(DungeonFloor.F5, "ENTRY") == null && java.util.Arrays.equals(contents, Files.readAllBytes(file)),
			message);
	}

	private static void checkServerTickCounterAndBoundaries() {
		DungeonServerTickCounter counter = new DungeonServerTickCounter();
		require(!counter.observePingId(0L) && !counter.observePingId(1L)
			&& !counter.observePingId(Long.MAX_VALUE) && counter.count() == 0L,
			"zero and positive ping IDs do not advance the server-tick counter");
		require(counter.observePingId(-1L) && counter.observePingId(-42L) && counter.count() == 2L,
			"each negative ping ID advances exactly one server tick");

		DungeonSplitTracker tracker = new DungeonSplitTracker();
		require(tracker.observe(DungeonFloor.F5, "ENTRY", 1_000_000_000L, 1L, 40L, 80L),
			"server-tick baseline starts with Entry");
		require(tracker.observe(DungeonFloor.F5, "BLOOD_OPEN", 2_200_000_000L, 2L, 44L, 100L),
			"a later phase closes the entry and Blood aggregate at the observed boundary");
		List<DungeonSplitTracker.Split> closed = tracker.drainNewlyFinished();
		require(closed.size() == 2 && closed.getFirst().id().equals("ENTRY")
			&& closed.getFirst().elapsedNanos() == 1_200_000_000L
			&& closed.getFirst().elapsedServerTicks() == 20L
			&& closed.getFirst().elapsedTicks() == 1L && closed.getFirst().elapsedPingPackets() == 4L
			&& closed.getLast().id().equals("BLOOD") && closed.getLast().elapsedServerTicks() == 20L,
			"split and run rows retain independent server-tick deltas without replacing client ticks or ping events");
		List<DungeonSplitTracker.Split> snapshot = tracker.snapshot(2_250_000_000L, 10_000L, 45L, 101L);
		DungeonSplitTracker.Split liveStage = snapshot.stream().filter(row -> row.id().equals("BLOOD_OPEN"))
			.findFirst().orElseThrow();
		DungeonSplitTracker.Split liveCamp = snapshot.stream().filter(row -> row.id().equals("CAMP"))
			.findFirst().orElseThrow();
		DungeonSplitTracker.Split liveRun = snapshot.stream().filter(row -> row.id().equals("RUN"))
			.findFirst().orElseThrow();
		require(liveStage.elapsedServerTicks() == 1L && liveCamp.elapsedServerTicks() == 1L
			&& liveRun.elapsedServerTicks() == 21L,
			"active stage, Camp, and Run snapshots use their own server-tick baselines");
		tracker.reset();
		counter.reset();
		require(tracker.snapshot(3_000_000_000L, 10_001L, 46L, 102L).isEmpty() && counter.count() == 0L,
			"tracker and server-tick counter reset cleanly between runs or connections");
	}

	private static void checkHistoryAndPersonalBestQueueStayConsistent() {
		DungeonSplitTracker tracker = new DungeonSplitTracker();
		require(tracker.observe(DungeonFloor.F5, "ENTRY", 1_000_000_000L, 20, 100), "history run starts");
		require(tracker.observe(DungeonFloor.F5, "CLEAR", 2_000_000_000L, 40, 120), "clear closes Entry");
		require(tracker.observe(DungeonFloor.F5, "BLOOD_OPEN", 4_000_000_000L, 80, 160), "blood open closes Clear");
		require(tracker.observe(DungeonFloor.F5, "BLOOD_CLEAR", 7_000_000_000L, 140, 220), "blood clear closes Blood Open");
		require(tracker.observe(DungeonFloor.F5, "BOSS_ENTRY", 11_000_000_000L, 220, 300), "boss entry closes Blood Clear");
		require(tracker.observe(DungeonFloor.F5, "LIVID", 16_000_000_000L, 320, 400), "Livid closes Boss Entry");

		List<DungeonSplitTracker.Split> rows = tracker.snapshot(18_000_000_000L, 360, 440);
		List<DungeonSplitTracker.Split> completedRows = rows.stream()
			.filter(DungeonSplitTracker.Split::complete).toList();
		List<String> expectedCompletedIds = List.of("ENTRY", "CLEAR", "BLOOD", "BLOOD_OPEN",
			"CAMP", "BLOOD_CLEAR", "PORTAL", "BOSS_ENTRY");
		require(completedRows.stream().map(DungeonSplitTracker.Split::id).toList().equals(expectedCompletedIds),
			"the snapshot keeps every completed row in original order while below the baseline 32-row cap");
		require(completedRows.getFirst().elapsedNanos() == 1_000_000_000L
			&& completedRows.getFirst().elapsedTicks() == 20 && completedRows.getFirst().elapsedPingPackets() == 20,
			"the earliest retained row keeps its independent real-time, client-tick, and ping-event measurements");
		DungeonSplitTracker.Split active = rows.get(rows.size() - 2);
		DungeonSplitTracker.Split activeRun = rows.getLast();
		require(rows.stream().map(DungeonSplitTracker.Split::id).toList()
				.equals(List.of("ENTRY", "CLEAR", "BLOOD", "BLOOD_OPEN", "CAMP", "BLOOD_CLEAR",
					"PORTAL", "BOSS_ENTRY", "LIVID", "RUN"))
			&& active.id().equals("LIVID") && !active.complete()
			&& active.elapsedNanos() == 2_000_000_000L && active.elapsedTicks() == 40
			&& active.elapsedPingPackets() == 40 && activeRun.id().equals("RUN") && !activeRun.complete()
			&& activeRun.elapsedNanos() == 17_000_000_000L && activeRun.elapsedTicks() == 340
			&& activeRun.elapsedPingPackets() == 340,
			"the active phase and run aggregate stay separately visible with independent elapsed measurements");

		List<DungeonSplitTracker.Split> recorded = tracker.drainNewlyFinished();
		require(recorded.stream().map(DungeonSplitTracker.Split::id).toList().equals(expectedCompletedIds),
			"the PB queue preserves every completed event independently of the 32-row display history");
		DungeonSplitPersonalBestBook book = DungeonSplitPersonalBestBook.empty();
		for (DungeonSplitTracker.Split split : recorded) book = book.record(DungeonFloor.F5, split, 1_000L).book();
		require(book.entries().size() == 8 && book.find(DungeonFloor.F5, "ENTRY") != null
			&& book.find(DungeonFloor.F5, "BOSS_ENTRY").bestClientTicks() == 100,
			"PB aggregates include every completed split while the display snapshot remains within its 32-row cap");

		require(tracker.observe(DungeonFloor.F5, "COMPLETE", 19_000_000_000L, 380, 460),
			"completion closes the active split while the retained history remains within the baseline cap");
		List<DungeonSplitTracker.Split> finalRows = tracker.snapshot(19_000_000_000L, 380, 460);
		require(finalRows.stream().map(DungeonSplitTracker.Split::id).toList()
				.equals(List.of("ENTRY", "CLEAR", "BLOOD", "BLOOD_OPEN", "CAMP", "BLOOD_CLEAR",
					"PORTAL", "BOSS_ENTRY", "LIVID", "COMPLETE")),
			"completion preserves the full completed-row order, including the run total, within the 32-row cap");
		List<DungeonSplitTracker.Split> completion = tracker.drainNewlyFinished();
		require(completion.stream().map(DungeonSplitTracker.Split::id).toList().equals(List.of("LIVID", "COMPLETE"))
			&& completion.getLast().elapsedNanos() == 18_000_000_000L
			&& completion.getLast().elapsedTicks() == 360
			&& completion.getLast().elapsedPingPackets() == 360,
			"the run-total PB event retains its independent elapsed measurements in the separate completion queue");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
