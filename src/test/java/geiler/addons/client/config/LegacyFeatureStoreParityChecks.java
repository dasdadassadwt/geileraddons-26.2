package geiler.addons.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.dungeon.ClientJsonFile;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonSplitPersonalBestBook;
import geiler.addons.client.dungeon.DungeonSplitPersonalBestStoreSession;
import geiler.addons.client.dungeon.helpers.DungeonPuzzlePersonalBestStoreSession;
import geiler.addons.client.dungeon.helpers.DungeonSplitTracker;
import geiler.addons.client.module.impl.MinibossAppearanceCapture;
import geiler.addons.client.module.impl.MinibossAppearanceCatalog;
import geiler.addons.client.module.impl.MinibossAppearanceCatalogStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Compares local feature stores with the copied 26.1.2 profile without exposing stored values. */
final class LegacyFeatureStoreParityChecks {
	private LegacyFeatureStoreParityChecks() { }

	static void run(Path sourceConfigDir, Path copiedConfigDir) throws Exception {
		checkDungeonSplitBests(sourceConfigDir, copiedConfigDir);
		checkDungeonPuzzleBests(sourceConfigDir, copiedConfigDir);
		checkMinibossCatalog(sourceConfigDir, copiedConfigDir);
	}

	private static void checkDungeonSplitBests(Path sourceConfigDir, Path copiedConfigDir) throws Exception {
		String fileName = "dungeon-split-personal-bests.json";
		Path sourceFile = sourceConfigDir.resolve(fileName);
		Path copiedFile = copiedConfigDir.resolve(fileName);
		byte[] sourceBytes = requireMatchingCopy(sourceFile, copiedFile, fileName);
		DungeonSplitPersonalBestBook.DecodeResult baseline;
		try {
			baseline = DungeonSplitPersonalBestBook.decode(decodeUtf8Strictly(sourceBytes));
		} catch (CharacterCodingException malformedUtf8) {
			baseline = DungeonSplitPersonalBestBook.decode(null);
		}

		DungeonSplitPersonalBestStoreSession loaded = new DungeonSplitPersonalBestStoreSession(copiedFile);
		loaded.initialize();
		assertOriginalBytesRemain(sourceFile, copiedFile, sourceBytes, fileName);
		if (!baseline.valid()) {
			if (loaded.find(DungeonFloor.F1, "ENTRY") != null) {
				throw new AssertionError("The legacy dungeon split store did not fail closed.");
			}
			return;
		}
		DungeonSplitPersonalBestBook originalBook = baseline.book();
		if (originalBook == null || !sameBookEntries(originalBook, loaded)) {
			throw new AssertionError("A copied dungeon split best changed while loading.");
		}

		Path temporary = Files.createTempDirectory("geileraddons-legacy-split-pb-");
		try {
			Path roundTripFile = temporary.resolve(fileName);
			Files.write(roundTripFile, sourceBytes);
			DungeonSplitPersonalBestStoreSession roundTrip = new DungeonSplitPersonalBestStoreSession(roundTripFile);
			roundTrip.initialize();
			DungeonSplitTracker.Split sample = new DungeonSplitTracker.Split(
				"ENTRY", "Entry", 15_000_000_000L, 90L, 95L, 45L, true);
			DungeonSplitPersonalBestBook.Update expected = originalBook.record(DungeonFloor.F1, sample, 987_654_321L);
			roundTrip.record(DungeonFloor.F1, sample, 987_654_321L);
			if (!ClientJsonFile.flushAndReport()) {
				throw new AssertionError("The copied dungeon split best did not finish its save.");
			}
			DungeonSplitPersonalBestBook.DecodeResult saved = DungeonSplitPersonalBestBook.decode(
				Files.readString(roundTripFile, StandardCharsets.UTF_8));
			if (!saved.valid() || saved.book() == null || !sameBookEntries(expected.book(), saved.book())) {
				throw new AssertionError("A copied dungeon split best changed after save and reload.");
			}
		} finally {
			deleteTree(temporary);
		}
		assertOriginalBytesRemain(sourceFile, copiedFile, sourceBytes, fileName);
	}

	private static boolean sameBookEntries(DungeonSplitPersonalBestBook expected,
		DungeonSplitPersonalBestStoreSession actual) {
		for (DungeonSplitPersonalBestBook.Entry entry : expected.entries()) {
			if (!entry.equals(actual.find(entry.floor(), entry.splitId()))) return false;
		}
		return true;
	}

	private static boolean sameBookEntries(DungeonSplitPersonalBestBook expected,
		DungeonSplitPersonalBestBook actual) {
		if (expected.entries().size() != actual.entries().size()) return false;
		for (DungeonSplitPersonalBestBook.Entry entry : expected.entries()) {
			if (!entry.equals(actual.find(entry.floor(), entry.splitId()))) return false;
		}
		return true;
	}

	private static void checkDungeonPuzzleBests(Path sourceConfigDir, Path copiedConfigDir) throws Exception {
		String fileName = "dungeon-puzzle-personal-bests.json";
		Path sourceFile = sourceConfigDir.resolve(fileName);
		Path copiedFile = copiedConfigDir.resolve(fileName);
		byte[] sourceBytes = requireMatchingCopy(sourceFile, copiedFile, fileName);
		Map<String, Float> expected = expectedLegacyPuzzleBests(sourceBytes);
		DungeonPuzzlePersonalBestStoreSession loaded = new DungeonPuzzlePersonalBestStoreSession(copiedFile);
		Map<String, Float> actual = loaded.load();
		assertOriginalBytesRemain(sourceFile, copiedFile, sourceBytes, fileName);
		if (expected == null) {
			if (!actual.isEmpty()) throw new AssertionError("An invalid legacy puzzle PB file did not fail closed.");
			return;
		}
		if (!expected.equals(actual)) {
			throw new AssertionError("Copied dungeon puzzle bests changed while loading or canonicalizing.");
		}

		Path temporary = Files.createTempDirectory("geileraddons-legacy-puzzle-pb-");
		try {
			Path roundTripFile = temporary.resolve(fileName);
			Files.write(roundTripFile, sourceBytes);
			DungeonPuzzlePersonalBestStoreSession roundTrip = new DungeonPuzzlePersonalBestStoreSession(roundTripFile);
			Map<String, Float> loadedCopy = roundTrip.load();
			if (!expected.equals(loadedCopy)) {
				throw new AssertionError("A copied dungeon puzzle best changed before save.");
			}
			Future<Boolean> save = roundTrip.save(loadedCopy);
			if (save == null || !Boolean.TRUE.equals(save.get(30, TimeUnit.SECONDS))) {
				throw new AssertionError("The copied dungeon puzzle best did not finish its save.");
			}
			Map<String, Float> reloaded = new DungeonPuzzlePersonalBestStoreSession(roundTripFile).load();
			if (!expected.equals(reloaded)) {
				throw new AssertionError("Copied dungeon puzzle bests changed after save and reload.");
			}
		} finally {
			deleteTree(temporary);
		}
		assertOriginalBytesRemain(sourceFile, copiedFile, sourceBytes, fileName);
	}

	/** Mirrors the frozen Java reader's schema, aliases, map bounds, and all-or-nothing validation. */
	private static Map<String, Float> expectedLegacyPuzzleBests(byte[] bytes) {
		try {
			JsonObject root = JsonParser.parseString(decodeUtf8Strictly(bytes)).getAsJsonObject();
			JsonElement version = root.get("version");
			JsonElement bestSeconds = root.get("bestSeconds");
			if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
				|| version.getAsInt() != 1 || bestSeconds == null || !bestSeconds.isJsonObject()
				|| bestSeconds.getAsJsonObject().size() > 8) {
				return null;
			}
			Map<String, Float> expected = new TreeMap<>();
			for (Map.Entry<String, JsonElement> entry : bestSeconds.getAsJsonObject().entrySet()) {
				JsonElement value = entry.getValue();
				if (!isKnownPuzzle(entry.getKey()) || value == null || !value.isJsonPrimitive()
					|| !value.getAsJsonPrimitive().isNumber()) {
					return null;
				}
				float seconds = value.getAsFloat();
				if (!Float.isFinite(seconds) || seconds < 0.0f || seconds > 86_400.0f) return null;
				String canonical = entry.getKey().equals("Higher Blaze") || entry.getKey().equals("Lower Blaze")
					? "Higher Or Lower" : entry.getKey();
				expected.merge(canonical, seconds, Math::min);
			}
			return Map.copyOf(expected);
		} catch (RuntimeException | CharacterCodingException invalid) {
			return null;
		}
	}

	private static boolean isKnownPuzzle(String name) {
		return switch (name) {
			case "Water Board", "Teleport Maze", "Ice Fill", "Higher Or Lower", "Higher Blaze", "Lower Blaze",
				"Creeper Beams", "Three Weirdos", "Quiz", "Boulder" -> true;
			default -> false;
		};
	}

	private static void checkMinibossCatalog(Path sourceConfigDir, Path copiedConfigDir) throws IOException {
		String fileName = "miniboss-appearance-signatures.json";
		Path sourceFile = sourceConfigDir.resolve(fileName);
		Path copiedFile = copiedConfigDir.resolve(fileName);
		byte[] sourceBytes = requireMatchingCopy(sourceFile, copiedFile, fileName);
		String legacyJson = new String(sourceBytes, StandardCharsets.UTF_8);
		MinibossAppearanceCatalog.ParseResult expected = MinibossAppearanceCatalog.parse(legacyJson);
		MinibossAppearanceCatalogStore.LoadResult actual =
			MinibossAppearanceCatalogStore.loadFromPathForChecks(copiedFile);
		String status = actual.status();
		if (actual.catalog().reviewedEntryCount() > MinibossAppearanceCatalog.MAX_ENTRIES
			|| status.length() > 240 || status.chars().anyMatch(Character::isISOControl)) {
			throw new AssertionError("The copied miniboss catalog exceeded its data or diagnostic bounds.");
		}
		if (expected.valid()) {
			if (actual.catalog().reviewedEntryCount() != expected.catalog().reviewedEntryCount()
				|| !status.equals(expected.unreviewedCount() == 0 ? ""
					: "ignored " + expected.unreviewedCount() + " unreviewed appearance entries")) {
				throw new AssertionError("The copied miniboss catalog changed while loading.");
			}
			JsonObject root = JsonParser.parseString(legacyJson).getAsJsonObject();
			for (JsonElement element : root.getAsJsonArray("profiles")) {
				JsonObject profile = element.getAsJsonObject();
				var identity = new MinibossAppearanceCapture.AppearanceIdentity(
					profile.get("signature").getAsString(), profile.get("skinModel").getAsString());
				String expectedLabel = profile.get("reviewed").getAsBoolean()
					? profile.get("label").getAsString() : null;
				if (!java.util.Objects.equals(expectedLabel, actual.catalog().labelFor(identity))) {
					throw new AssertionError("A copied miniboss appearance label changed while loading.");
				}
			}
		} else if (actual.catalog().reviewedEntryCount() != 0 || status.isEmpty()) {
			throw new AssertionError("An invalid copied miniboss catalog did not fail closed.");
		}
		assertOriginalBytesRemain(sourceFile, copiedFile, sourceBytes, fileName);
	}

	private static byte[] requireMatchingCopy(Path source, Path copied, String fileName) throws IOException {
		if (!Files.isRegularFile(source) || !Files.isRegularFile(copied)) {
			throw new AssertionError("The copied legacy profile is missing " + fileName + ".");
		}
		byte[] sourceBytes = Files.readAllBytes(source);
		if (!Arrays.equals(sourceBytes, Files.readAllBytes(copied))) {
			throw new AssertionError("The copied legacy profile changed " + fileName + " before the check.");
		}
		return sourceBytes;
	}

	private static void assertOriginalBytesRemain(Path source, Path copied, byte[] sourceBytes, String fileName)
		throws IOException {
		if (!Arrays.equals(sourceBytes, Files.readAllBytes(source))
			|| !Arrays.equals(sourceBytes, Files.readAllBytes(copied))) {
			throw new AssertionError("The legacy fixture or copied " + fileName + " changed during load checks.");
		}
	}

	private static String decodeUtf8Strictly(byte[] bytes) throws CharacterCodingException {
		return StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(bytes))
			.toString();
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}
}
