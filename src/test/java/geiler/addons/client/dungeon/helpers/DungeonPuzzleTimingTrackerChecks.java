package geiler.addons.client.dungeon.helpers;

import com.google.gson.JsonObject;
import geiler.addons.client.dungeon.ClientJsonFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Pure checks for the source-backed puzzle timer, PB, trigger, and draft prompt rules. */
public final class DungeonPuzzleTimingTrackerChecks {
	private DungeonPuzzleTimingTrackerChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkPersonalBestPersistence();
		DungeonPuzzleTimingTracker ticTacToe = new DungeonPuzzleTimingTracker();
		require(!ticTacToe.onPuzzleRoomEnter("Tic Tac Toe", 1_000_000_000L)
			&& ticTacToe.completePuzzle("Tic Tac Toe", 20_000_000_000L).isEmpty()
			&& ticTacToe.onPuzzleBlockEvent("Tic Tac Toe", "CHERRY_LOG", 15, 56, 22,
				20_000_000_000L, true, true).isEmpty()
			&& ticTacToe.personalBestSnapshot().isEmpty(),
			"Tic Tac Toe does not start a timer or create a personal best without a completion rule");
		DungeonPuzzleTimingTracker tracker = new DungeonPuzzleTimingTracker();
		require(tracker.onPuzzleRoomEnter("Water Board", 1_000_000_000L),
			"the first exact puzzle room entry starts its timer");
		require(!tracker.onPuzzleRoomEnter("Water Board", 2_000_000_000L),
			"re-entering a named puzzle does not replace the original run timer");
		require(DungeonPuzzleTimingTracker.isCompletionTarget("Water Board", "CHERRY_LOG", 15, 56, 22)
			&& !DungeonPuzzleTimingTracker.isCompletionTarget("Water Board", "STONE", 15, 56, 22)
			&& !DungeonPuzzleTimingTracker.isCompletionTarget("Water Board", "CHERRY_LOG", 15, 56, 21)
			&& !DungeonPuzzleTimingTracker.isCompletionTarget("Blaze", "CHERRY_LOG", 15, 56, 22),
			"only exact CHERRY_LOG room-relative targets are completion sources");
		require(tracker.onPuzzleBlockEvent("Water Board", "CHERRY_LOG", 15, 56, 22,
			10_999_000_000L, true, true).isEmpty(),
			"completion below the source minimum time is ignored");
		var first = tracker.onPuzzleBlockEvent("Water Board", "CHERRY_LOG", 15, 56, 22,
			11_250_000_000L, true, true).orElseThrow();
		require(first.elapsedSeconds() == 10.25f && first.minimumSeconds() == 10
			&& first.personalBest() && first.bestSeconds() == 10.25f,
			"qualifying completion records elapsed time and establishes a personal best");
		require(tracker.onPuzzleBlockEvent("Water Board", "CHERRY_LOG", 15, 56, 22,
			12_000_000_000L, true, true).isEmpty(),
			"a reported completion is not recorded twice");
		require(tracker.onPuzzleBlockEvent("Water Board", "CHERRY_LOG", 15, 56, 22,
			13_000_000_000L, false, true).isEmpty()
			&& tracker.onPuzzleBlockEvent("Water Board", "CHERRY_LOG", 15, 56, 22,
				13_000_000_000L, true, false).isEmpty(),
			"boss/out-of-clear and non-puzzle events cannot complete the timer");

		DungeonPuzzleTimingTracker secondRun = new DungeonPuzzleTimingTracker();
		secondRun.restorePersonalBests(tracker.personalBestSnapshot());
		secondRun.onPuzzleRoomEnter("Water Board", 20_000_000_000L);
		var slower = secondRun.completePuzzle("Water Board", 31_000_000_000L).orElseThrow();
		require(!slower.personalBest() && slower.previousBestSeconds() == 10.25f
			&& slower.bestSeconds() == 10.25f,
			"slower qualifying attempts retain the stored best time");
		secondRun.onLevelLoad();
		require(secondRun.completePuzzle("Water Board", 40_000_000_000L).isEmpty()
			&& secondRun.personalBestSnapshot().get("Water Board") == 10.25f,
			"level load clears active timers but preserves PB state");
		secondRun.onPuzzleRoomEnter("Water Board", 50_000_000_000L);
		var faster = secondRun.completePuzzle("Water Board", 60_000_000_000L).orElseThrow();
		require(faster.personalBest() && faster.bestSeconds() == 10.0f,
			"strictly faster qualifying attempts replace the stored PB");

		require(DungeonPuzzleTimingTracker.isCompletionTarget("Ice Fill", "CHERRY_LOG", 14, 75, 29)
			&& DungeonPuzzleTimingTracker.isCompletionTarget("Ice Fill", "CHERRY_LOG", 16, 75, 29)
			&& DungeonPuzzleTimingTracker.isCompletionTarget("Teleport Maze", "CHERRY_LOG", 15, 70, 20)
			&& DungeonPuzzleTimingTracker.isCompletionTarget("Three Weirdos", "CHERRY_LOG", 18, 69, 24)
			&& DungeonPuzzleTimingTracker.isCompletionTarget("Three Weirdos", "CHERRY_LOG", 16, 69, 25)
			&& DungeonPuzzleTimingTracker.isCompletionTarget("Three Weirdos", "CHERRY_LOG", 14, 69, 24)
			&& DungeonPuzzleTimingTracker.isCompletionTarget("Boulder", "CHERRY_LOG", 15, 66, 29),
			"all alternate Ice Fill and source completion targets are covered");
		checkCreeperBeamsAndQuizEvents();
		checkBlazeTrackedTargetEdge();

		var failPrompt = DungeonPuzzleTimingTracker.draftPrompt("PUZZLE FAIL! Player_1 failed to solve it.",
			true, true, true).orElseThrow();
		var oruoPrompt = DungeonPuzzleTimingTracker.draftPrompt(
			"[STATUE] Oruo the Omniscient: Player_2 chose the wrong answer! I shall never forget this moment of misrememberance.",
			true, true, true).orElseThrow();
		require(failPrompt.playerName().equals("Player_1")
			&& failPrompt.runCommand().equals("gfs architect's first draft 1")
			&& oruoPrompt.playerName().equals("Player_2")
			&& oruoPrompt.text().contains("Click"),
			"both exact failure messages create a clickable draft prompt");
		require(DungeonPuzzleTimingTracker.draftPrompt("puzzle fail! Player failed", true, true, true).isEmpty()
			&& DungeonPuzzleTimingTracker.draftPrompt("PUZZLE FAIL! Player failed", false, true, true).isEmpty()
			&& DungeonPuzzleTimingTracker.draftPrompt("PUZZLE FAIL! Player failed", true, false, true).isEmpty()
			&& DungeonPuzzleTimingTracker.draftPrompt("PUZZLE FAIL! Player failed", true, true, false).isEmpty(),
			"draft prompts require the exact matcher and all source context gates");
		secondRun.restorePersonalBests(Map.of("Water Board", Float.NaN, "Unknown", 1.0f,
			"Boulder", 0.0f));
		require(secondRun.personalBestSnapshot().equals(Map.of("Boulder", 0.0f)),
			"PB restoration rejects invalid, unknown, or non-finite records");
	}

	private static void checkPersonalBestPersistence() {
		Path root = null;
		try {
			root = Files.createTempDirectory("ga-dungeon-puzzle-pb-check-");
			Path path = root.resolve("dungeon-puzzle-personal-bests.json");
			Files.writeString(path,
				"{\"version\":1,\"bestSeconds\":{\"Water Board\":13.5,\"Higher Blaze\":12.0,\"Lower Blaze\":11.0}}",
				StandardCharsets.UTF_8);
			DungeonPuzzlePersonalBestStoreSession loadedStore = new DungeonPuzzlePersonalBestStoreSession(path);
			Map<String, Float> loaded = loadedStore.load();
			require(loaded.equals(Map.of("Water Board", 13.5f, "Higher Or Lower", 11.0f)),
				"loading v1 puzzle PB data canonicalizes Blaze aliases and keeps their lowest time");
			require(loadedStore.load().equals(loaded), "the path-bound store keeps its one-time loaded snapshot");

			Map<String, Float> snapshot = Map.of("Water Board", 0.0f, "Higher Blaze", 12.0f,
				"Lower Blaze", 10.0f, "Boulder", 86_400.0f);
			var queuedWrite = loadedStore.save(snapshot);
			require(queuedWrite != null && queuedWrite.get(10, TimeUnit.SECONDS),
				"the store's asynchronous save completes successfully");
			JsonObject saved = ClientJsonFile.read(path, JsonObject.class);
			JsonObject bestSeconds = saved == null ? null : saved.getAsJsonObject("bestSeconds");
			require(saved != null && saved.get("version").getAsInt() == 1 && bestSeconds != null
				&& bestSeconds.has("Higher Or Lower") && !bestSeconds.has("Higher Blaze")
				&& !bestSeconds.has("Lower Blaze") && bestSeconds.get("Higher Or Lower").getAsFloat() == 10.0f,
				"saving retains the version-1 schema and serializes one canonical Blaze key");
			DungeonPuzzlePersonalBestStoreSession reloadedStore = new DungeonPuzzlePersonalBestStoreSession(path);
			require(reloadedStore.load().equals(Map.of("Water Board", 0.0f,
				"Higher Or Lower", 10.0f, "Boulder", 86_400.0f)),
				"a fresh production store reloads the saved map including the inclusive time bounds");

			byte[] stableFile = Files.readAllBytes(path);
			Map<String, Float> tooManySourceKeys = new HashMap<>();
			tooManySourceKeys.put("Water Board", 1.0f);
			tooManySourceKeys.put("Teleport Maze", 1.0f);
			tooManySourceKeys.put("Ice Fill", 1.0f);
			tooManySourceKeys.put("Higher Or Lower", 1.0f);
			tooManySourceKeys.put("Higher Blaze", 1.0f);
			tooManySourceKeys.put("Creeper Beams", 1.0f);
			tooManySourceKeys.put("Three Weirdos", 1.0f);
			tooManySourceKeys.put("Quiz", 1.0f);
			tooManySourceKeys.put("Boulder", 1.0f);
			require(loadedStore.save(tooManySourceKeys) == null
				&& Arrays.equals(stableFile, Files.readAllBytes(path)),
				"save rejects more than eight source keys before alias collapsing and retains prior bytes");
			require(loadedStore.save(Map.of("Water Board", -0.01f)) == null
				&& Arrays.equals(stableFile, Files.readAllBytes(path)),
				"save rejects negative times without replacing the current file");
			require(loadedStore.save(Map.of("Water Board", 86_400.1f)) == null
				&& Arrays.equals(stableFile, Files.readAllBytes(path)),
				"save rejects times above the 86,400-second limit without replacing the current file");
			require(loadedStore.save(Map.of("Unknown Puzzle", 1.0f)) == null
				&& Arrays.equals(stableFile, Files.readAllBytes(path)),
				"save rejects unknown puzzle keys without replacing the current file");

			checkRejectedLoad(root.resolve("malformed.json"), "{".getBytes(StandardCharsets.UTF_8),
				"malformed JSON is rejected without changing its bytes");
			checkRejectedLoad(root.resolve("malformed-utf8.json"), new byte[] {(byte) 0xc3, 0x28},
				"malformed UTF-8 is rejected without changing its bytes");
			checkRejectedLoad(root.resolve("negative-time.json"),
				"{\"version\":1,\"bestSeconds\":{\"Water Board\":-1}}".getBytes(StandardCharsets.UTF_8),
				"negative persisted times are rejected without changing their bytes");
			checkRejectedLoad(root.resolve("over-limit-time.json"),
				"{\"version\":1,\"bestSeconds\":{\"Water Board\":86400.1}}".getBytes(StandardCharsets.UTF_8),
				"persisted times above 86,400 seconds are rejected without changing their bytes");
			checkRejectedLoad(root.resolve("too-many-entries.json"),
				("{\"version\":1,\"bestSeconds\":{\"Water Board\":1,\"Teleport Maze\":1,"
					+ "\"Ice Fill\":1,\"Higher Or Lower\":1,\"Higher Blaze\":1,\"Creeper Beams\":1,"
					+ "\"Three Weirdos\":1,\"Quiz\":1,\"Boulder\":1}}")
					.getBytes(StandardCharsets.UTF_8),
				"more than eight persisted source keys are rejected before aliases collapse");
			byte[] oversized = new byte[ClientJsonFile.MAX_FILE_BYTES + 1];
			checkRejectedLoad(root.resolve("oversized.json"), oversized,
				"oversized persisted data is rejected before parsing and retained byte-for-byte");
		} catch (Exception error) {
			throw new AssertionError("Dungeon puzzle PB persistence check failed", error);
		} finally {
			if (root != null) try { deleteTree(root); }
			catch (IOException error) {
				throw new AssertionError("Could not remove the puzzle PB check's temporary files", error);
			}
		}
	}

	private static void checkRejectedLoad(Path path, byte[] bytes, String message) throws IOException {
		Files.write(path, bytes);
		require(new DungeonPuzzlePersonalBestStoreSession(path).load().isEmpty(), message);
		require(Arrays.equals(bytes, Files.readAllBytes(path)), message + " (source bytes stay untouched)");
	}

	private static void deleteTree(Path root) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
				if (error != null) throw error;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void checkCreeperBeamsAndQuizEvents() {
		DungeonPuzzleTimingTracker tracker = new DungeonPuzzleTimingTracker();
		tracker.onPuzzleRoomEnter("Creeper Beams", 1_000_000_000L);
		require(tracker.onPuzzleBlockUpdate("Creeper Beams", "air", "minecraft:chest", 15, 69, 15,
			6_999_000_000L, true, true).isEmpty(),
			"Creeper Beams ignores updates before its six-second source minimum");
		require(tracker.onPuzzleBlockUpdate("Creeper Beams", "stone", "chest", 15, 69, 15,
			7_250_000_000L, true, true).isEmpty()
			&& tracker.onPuzzleBlockUpdate("Creeper Beams", "air", "chest", 15, 69, 14,
				7_250_000_000L, true, true).isEmpty()
			&& tracker.onPuzzleBlockUpdate("Creeper Beams", "air", "chest", 15, 69, 15,
				7_250_000_000L, false, true).isEmpty(),
			"Creeper Beams requires the exact air-to-chest target and clear-phase gates");
		var beams = tracker.onPuzzleBlockUpdate("Creeper Beams", "minecraft:air", "minecraft:chest", 15, 69, 15,
			7_250_000_000L, true, true).orElseThrow();
		require(beams.puzzleName().equals("Creeper Beams") && beams.minimumSeconds() == 6
			&& beams.personalBest(), "source Creeper Beams transition records a six-second PB");

		tracker.onPuzzleRoomEnter("Quiz", 20_000_000_000L);
		require(tracker.onQuizFinalAnswer("Quiz", false, 31_000_000_000L, true, true).isEmpty()
			&& tracker.onQuizFinalAnswer("Quiz", true, 31_000_000_000L, false, true).isEmpty()
			&& tracker.onQuizFinalAnswer("Three Weirdos", true, 31_000_000_000L, true, true).isEmpty(),
			"Quiz requires its exact source answer predicate, room, and dungeon-clear gates");
		require(tracker.onQuizFinalAnswer("Quiz", true, 29_999_000_000L, true, true).isEmpty(),
			"Quiz ignores completion before its ten-second source minimum");
		var quiz = tracker.onQuizFinalAnswer("Quiz", true, 30_250_000_000L, true, true).orElseThrow();
		require(quiz.puzzleName().equals("Quiz") && quiz.minimumSeconds() == 10,
			"Quiz source final-answer event records the ten-second threshold");
	}

	private static void checkBlazeTrackedTargetEdge() {
		DungeonPuzzleTimingTracker tracker = new DungeonPuzzleTimingTracker();
		tracker.onPuzzleRoomEnter("Higher Blaze", 1_000_000_000L);
		require(tracker.onBlazeTrackedTargetCount("Higher Blaze", 0, 2_000_000_000L, true, true).isEmpty()
			&& tracker.onBlazeTrackedTargetCount("Higher Blaze", 2, 3_000_000_000L, true, true).isEmpty()
			&& tracker.onBlazeTrackedTargetCount("Higher Blaze", 0, 4_000_000_000L, true, true).isEmpty(),
			"empty scans and counts other than one do not complete Blaze");
		tracker.onBlazeTrackedTargetCount("Higher Blaze", 1, 5_000_000_000L, true, true);
		require(tracker.onBlazeTrackedTargetCount("Higher Blaze", 0, 6_999_000_000L, true, true).isEmpty(),
			"Blaze completion is ignored below the six-second source minimum");
		tracker.onBlazeTrackedTargetCount("Higher Blaze", 1, 7_000_000_000L, true, true);
		var blaze = tracker.onBlazeTrackedTargetCount("Higher Blaze", 0, 7_250_000_000L, true, true)
			.orElseThrow();
		require(blaze.puzzleName().equals("Higher Or Lower") && blaze.minimumSeconds() == 6
			&& tracker.personalBestSnapshot().containsKey("Higher Or Lower"),
			"both Blaze room identities share the source personal-best label and threshold");
		require(tracker.personalBestSnapshot().get("Higher Or Lower") == 6.25f,
			"Blaze PB stores elapsed time under its semantic puzzle label");

		DungeonPuzzleTimingTracker lower = new DungeonPuzzleTimingTracker();
		lower.onPuzzleRoomEnter("Lower Blaze", 1_000_000_000L);
		lower.onBlazeTrackedTargetCount("Lower Blaze", 1, 2_000_000_000L, true, true);
		var lowerBlaze = lower.onBlazeTrackedTargetCount("Lower Blaze", 0, 7_250_000_000L, true, true)
			.orElseThrow();
		require(lowerBlaze.puzzleName().equals("Higher Or Lower"),
			"Lower Blaze uses the shared semantic personal-best label");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
