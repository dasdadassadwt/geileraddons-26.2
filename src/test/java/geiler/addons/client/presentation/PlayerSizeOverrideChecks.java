package geiler.addons.client.presentation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.dungeon.ClientJsonFile;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;

/** Direct parity checks for the legacy local player-size override format and behavior. */
public final class PlayerSizeOverrideChecks {
	private PlayerSizeOverrideChecks() { }

	public static void run() {
		Path root = null;
		try {
			root = Files.createTempDirectory("ga-player-size-check-");
			entryAbiIsStable();
			legacyScalarProfilesLoadAndRoundTrip(root);
			axisProfilesLoadIndependently(root);
			invalidInputsAndFilesStayRejected(root);
			entryLimitAllowsUpdatesButRejectsNewRows(root);
		} catch (Exception error) {
			throw new AssertionError("PlayerSizeOverrideChecks failed", error);
		} finally {
			if (root != null) try {
				awaitWrites(root);
				deleteTree(root);
			}
			catch (IOException error) { throw new AssertionError("Could not remove player-size check files", error); }
			catch (Exception error) { throw new AssertionError("Could not finish player-size check writes", error); }
		}
	}

	private static void entryAbiIsStable() throws Exception {
		check(PlayerSizeOverrideStore.INSTANCE != null && PlayerSizeOverrideStore.MAX_ENTRIES == 128,
			"the Java-facing singleton and entry limit stay available");
		check(PlayerSizeOverrideStore.class.getMethod("entries") != null
			&& PlayerSizeOverrideStore.class.getMethod("find", String.class) != null
			&& PlayerSizeOverrideStore.class.getMethod("put", String.class, float.class, boolean.class) != null
			&& PlayerSizeOverrideStore.class.getMethod("put", String.class, float.class, float.class,
				float.class, boolean.class) != null
			&& PlayerSizeOverrideStore.class.getMethod("remove", String.class) != null,
			"Java editor and rendering callers retain their store method signatures");

		PlayerSizeOverrideStore.Entry scalar = new PlayerSizeOverrideStore.Entry("Scalar", 1.25f, true);
		PlayerSizeOverrideStore.Entry axes = new PlayerSizeOverrideStore.Entry("Axes", 0.5f, 1.25f, 2.0f, false);
		check(scalar.getClass().isRecord() && scalar.playerName().equals("Scalar")
			&& scalar.sizeX() == 1.25f && scalar.sizeY() == 1.25f && scalar.sizeZ() == 1.25f
			&& scalar.enabled() && scalar.size() == 1.25f,
			"the legacy uniform constructor, record components, and size accessor remain compatible");
		check(axes.getClass().isRecord() && axes.sizeX() == 0.5f && axes.sizeY() == 1.25f
			&& axes.sizeZ() == 2.0f && !axes.enabled(),
			"the record retains independent X, Y, and Z values");
	}

	private static void legacyScalarProfilesLoadAndRoundTrip(Path root) throws Exception {
		Path file = root.resolve("legacy.json");
		Files.writeString(file, """
			{"schemaVersion":1,"players":[
			  {"name":"Beta","size":1.25,"enabled":true},
			  {"name":"alpha","size":0.75,"enabled":false}
			]}
			""");
		PlayerSizeOverrideStore store = isolatedStore(file);
		PlayerSizeOverrideStore.Entry alpha = store.find(" ALPHA ");
		check(alpha != null && alpha.playerName().equals("alpha") && alpha.sizeX() == 0.75f
			&& alpha.sizeY() == 0.75f && alpha.sizeZ() == 0.75f && !alpha.enabled(),
			"schema 1 scalar values load into all three axes with case-insensitive trimmed lookup");
		check(store.find("\u2003alpha\u2003") == null,
			"lookup keeps Java trim semantics and does not remove Unicode EM SPACE");
		check(store.entries().stream().map(PlayerSizeOverrideStore.Entry::playerName).toList()
			.equals(List.of("alpha", "Beta")),
			"loaded entries remain sorted without changing their saved display names");
		check(Files.readString(file).contains("\"schemaVersion\":1"),
			"loading a legacy file does not rewrite it until an explicit edit");

		check(store.put(" Alpha ", 0.1f, 4.0f, 1.5f, false),
			"an existing case-insensitive key can be updated with inclusive axis bounds");
		check(!store.put("\u2003Ghost\u2003", 1.0f, true),
			"put rejects a username wrapped in Unicode EM SPACE");
		check(store.put("New_User", 0.5f, 1.0f, 2.0f, true), "a valid new player can be added");
		check(!store.remove("\u2003new_user\u2003") && store.remove(" new_user ")
			&& !store.remove("new_user") && !store.remove(null),
			"remove trims and ignores case, then reports missing and null keys");
		awaitWrites(root);

		JsonObject rootObject = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		JsonArray players = rootObject.getAsJsonArray("players");
		check(rootObject.size() == 2 && rootObject.get("schemaVersion").getAsInt() == 2
			&& players.size() == 2,
			"a legacy edit writes the current bounded schema 2 format");
		JsonObject first = players.get(0).getAsJsonObject();
		JsonObject second = players.get(1).getAsJsonObject();
		check(first.size() == 5 && first.get("name").getAsString().equals("Alpha")
			&& first.get("sizeX").getAsFloat() == 0.1f && first.get("sizeY").getAsFloat() == 4.0f
			&& first.get("sizeZ").getAsFloat() == 1.5f && !first.get("enabled").getAsBoolean()
			&& !first.has("size") && second.get("name").getAsString().equals("Beta"),
			"writes preserve independent axes and enabled state and sort by normalized name");
	}

	private static void axisProfilesLoadIndependently(Path root) throws Exception {
		Path file = root.resolve("axes.json");
		Files.writeString(file, """
			{"schemaVersion":2,"players":[
			  {"name":"AxisUser","sizeX":0.5,"sizeY":1.25,"sizeZ":2.0,"enabled":true}
			]}
			""");
		PlayerSizeOverrideStore.Entry entry = isolatedStore(file).find("axisuser");
		check(entry != null && entry.sizeX() == 0.5f && entry.sizeY() == 1.25f
			&& entry.sizeZ() == 2.0f && entry.enabled(),
			"schema 2 keeps per-axis values and the enabled flag on load");
	}

	private static void invalidInputsAndFilesStayRejected(Path root) throws Exception {
		PlayerSizeOverrideStore store = isolatedStore(root.resolve("validations.json"));
		check(!store.put("invalid-name", 1.0f, true) && !store.put("", 1.0f, true)
			&& !store.put("OverSizedUsername123", 1.0f, true),
			"only trimmed Minecraft names of 1 to 16 ASCII letters, digits, or underscores are accepted");
		check(!store.put("Low", 0.099f, 1.0f, 1.0f, true)
			&& !store.put("High", 1.0f, 4.001f, 1.0f, true)
			&& !store.put("NaN", Float.NaN, true)
			&& !store.put("Infinite", Float.POSITIVE_INFINITY, true),
			"every axis rejects out-of-range and non-finite sizes");
		check(store.entries().isEmpty(), "rejected edits do not create entries");

		Path duplicate = root.resolve("duplicate.json");
		Files.writeString(duplicate, """
			{"schemaVersion":2,"players":[
			  {"name":"Same","sizeX":1,"sizeY":1,"sizeZ":1,"enabled":true},
			  {"name":"same","sizeX":2,"sizeY":2,"sizeZ":2,"enabled":false}
			]}
			""");
		check(isolatedStore(duplicate).entries().isEmpty(),
			"duplicate player names differing only by case reject the whole file");

		Path oversized = root.resolve("oversized.json");
		Files.write(oversized, new byte[32 * 1024 + 1]);
		check(isolatedStore(oversized).entries().isEmpty(), "files larger than 32 KiB are ignored");
	}

	private static void entryLimitAllowsUpdatesButRejectsNewRows(Path root) throws Exception {
		Path file = root.resolve("capacity.json");
		JsonObject rootObject = new JsonObject();
		rootObject.addProperty("schemaVersion", 2);
		JsonArray players = new JsonArray();
		for (int i = 0; i < PlayerSizeOverrideStore.MAX_ENTRIES; i++) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", String.format(Locale.ROOT, "Player%03d", i));
			entry.addProperty("sizeX", 1.0f);
			entry.addProperty("sizeY", 1.0f);
			entry.addProperty("sizeZ", 1.0f);
			entry.addProperty("enabled", true);
			players.add(entry);
		}
		rootObject.add("players", players);
		Files.writeString(file, ClientJsonFile.GSON.toJson(rootObject));

		PlayerSizeOverrideStore store = isolatedStore(file);
		check(store.entries().size() == PlayerSizeOverrideStore.MAX_ENTRIES,
			"the store loads up to 128 valid entries");
		check(store.put("player000", 1.5f, true), "an existing player remains editable at capacity");
		check(!store.put("NewPlayer", 1.0f, true), "the store rejects a 129th distinct player");
		awaitWrites(root);
		check(JsonParser.parseString(Files.readString(file)).getAsJsonObject()
			.getAsJsonArray("players").size() == PlayerSizeOverrideStore.MAX_ENTRIES,
			"a capacity update keeps the persisted list within the entry limit");
		check(JsonParser.parseString(Files.readString(file)).getAsJsonObject()
			.getAsJsonArray("players").get(0).getAsJsonObject().get("sizeX").getAsFloat() == 1.5f,
			"an update at capacity reaches disk");
	}

	private static PlayerSizeOverrideStore isolatedStore(Path path) throws Exception {
		Constructor<PlayerSizeOverrideStore> constructor = PlayerSizeOverrideStore.class
			.getDeclaredConstructor(Path.class);
		constructor.setAccessible(true);
		return constructor.newInstance(path);
	}

	private static void awaitWrites(Path root) throws Exception {
		check(ClientJsonFile.writeAsync(root.resolve("write-barrier.json"), "{}").get(),
			"the queued client JSON writes finish before checking or cleaning temporary files");
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

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
