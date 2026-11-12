package geiler.addons.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.dungeon.DungeonGuideNode;
import geiler.addons.client.dungeon.DungeonGuideStore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Loads, saves, and reloads actual copied Dungeon Guide routes without printing user content. */
final class LegacyDungeonGuideParityChecks {
	private static final Gson GSON = new GsonBuilder().create();

	private LegacyDungeonGuideParityChecks() { }

	static void run(Path sourceConfigDir, Path copiedConfigDir) throws IOException {
		Path sourceRoot = sourceConfigDir.resolve("dungeon-guides").toAbsolutePath().normalize();
		Path copiedRoot = copiedConfigDir.resolve("dungeon-guides").toAbsolutePath().normalize();
		Path activeRoot = FabricLoader.getInstance().getConfigDir().resolve("geileraddons/dungeon-guides")
			.toAbsolutePath().normalize();
		if (sourceRoot.equals(copiedRoot) || !activeRoot.equals(copiedRoot) ||
			!Files.isRegularFile(sourceRoot.resolve("index.json"), LinkOption.NOFOLLOW_LINKS)) {
			throw new AssertionError("The copied legacy Dungeon Guide fixture is missing or not isolated.");
		}

		Map<String, byte[]> sourceBefore = collectBytes(sourceRoot);
		Map<String, JsonElement> expectedFiles = collectJson(sourceRoot);
		if (!expectedFiles.equals(collectJson(copiedRoot))) {
			throw new AssertionError("The copied Dungeon Guide files differ from the legacy source before load.");
		}
		List<JsonElement> expectedNodes = readExpectedNodes(sourceRoot);
		JsonObject sourceIndex = readObject(sourceRoot.resolve("index.json"));

		check(DungeonGuideStore.reloadFromDisk(), "the copied Dungeon Guide store loads successfully");
		check(expectedNodes.equals(snapshotNodes()), "the copied Dungeon Guide nodes retain their legacy semantics");
		check(stringOr(sourceIndex, "nextKey", "key.keyboard.right.bracket").equals(DungeonGuideStore.nextKey()) &&
			stringOr(sourceIndex, "previousKey", "key.keyboard.left.bracket").equals(DungeonGuideStore.previousKey()),
			"the copied Dungeon Guide key bindings retain their legacy values");

		DungeonGuideStore.changed();
		check(DungeonGuideStore.flushAndReport(), "the copied Dungeon Guide store saves successfully");
		Map<String, JsonElement> savedFiles = collectJson(copiedRoot);
		if (!expectedFiles.keySet().equals(savedFiles.keySet()) ||
			!sourceIndex.equals(readObject(copiedRoot.resolve("index.json")))) {
			throw new AssertionError("The copied Dungeon Guide save changed route files or index settings.");
		}
		check(DungeonGuideStore.reloadFromDisk(), "the saved copied Dungeon Guide store reloads successfully");
		check(expectedNodes.equals(snapshotNodes()), "Dungeon Guide nodes retain their legacy semantics after reload");
		check(stringOr(sourceIndex, "nextKey", "key.keyboard.right.bracket").equals(DungeonGuideStore.nextKey()) &&
			stringOr(sourceIndex, "previousKey", "key.keyboard.left.bracket").equals(DungeonGuideStore.previousKey()),
			"Dungeon Guide key bindings retain their legacy values after reload");
		if (!sameBytes(sourceBefore, collectBytes(sourceRoot))) {
			throw new AssertionError("The read-only legacy Dungeon Guide source changed during parity checks.");
		}
	}

	private static List<JsonElement> readExpectedNodes(Path root) throws IOException {
		JsonObject index = readObject(root.resolve("index.json"));
		JsonArray routes = index.getAsJsonArray("routes");
		if (routes == null || routes.size() > 10_000) {
			throw new AssertionError("The copied Dungeon Guide index is missing or exceeds its entry limit.");
		}
		List<JsonElement> result = new ArrayList<>();
		for (JsonElement routeValue : routes) {
			if (routeValue == null || !routeValue.isJsonObject()) {
				throw new AssertionError("The copied Dungeon Guide index contains an invalid route entry.");
			}
			JsonObject route = routeValue.getAsJsonObject();
			String relativeName = stringOr(route, "file", null);
			if (relativeName == null) throw new AssertionError("The copied Dungeon Guide index has a route without a file.");
			Path routePath = root.resolve(relativeName).normalize();
			if (Path.of(relativeName).isAbsolute() || !routePath.startsWith(root) ||
				!Files.isRegularFile(routePath, LinkOption.NOFOLLOW_LINKS)) {
				throw new AssertionError("The copied Dungeon Guide index references a missing or unsafe file.");
			}
			JsonArray nodes = readObject(routePath).getAsJsonArray("nodes");
			if (nodes == null || result.size() + nodes.size() > 20_000) {
				throw new AssertionError("A copied Dungeon Guide route is missing nodes or exceeds its entry limit.");
			}
			for (JsonElement nodeValue : nodes) {
				DungeonGuideNode node = GSON.fromJson(nodeValue, DungeonGuideNode.class);
				if (node == null) throw new AssertionError("A copied Dungeon Guide route contains an invalid node.");
				node.sanitize();
				result.add(GSON.toJsonTree(node));
			}
		}
		return result;
	}

	private static List<JsonElement> snapshotNodes() {
		List<JsonElement> result = new ArrayList<>();
		for (DungeonGuideNode node : DungeonGuideStore.all()) result.add(GSON.toJsonTree(node));
		return result;
	}

	private static JsonObject readObject(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4_194_304) {
			throw new AssertionError("A copied Dungeon Guide JSON file is missing or too large.");
		}
		JsonElement parsed = JsonParser.parseString(Files.readString(path));
		if (!parsed.isJsonObject()) throw new AssertionError("A copied Dungeon Guide JSON object is invalid.");
		return parsed.getAsJsonObject();
	}

	private static String stringOr(JsonObject object, String name, String fallback) {
		JsonElement value = object.get(name);
		return value == null || value.isJsonNull() ? fallback : value.getAsString();
	}

	private static Map<String, byte[]> collectBytes(Path root) throws IOException {
		Map<String, byte[]> result = new LinkedHashMap<>();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return result;
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
				.sorted(Comparator.comparing(Path::toString)).toList()) {
				result.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
			}
		}
		return result;
	}

	private static Map<String, JsonElement> collectJson(Path root) throws IOException {
		Map<String, JsonElement> result = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> entry : collectBytes(root).entrySet()) {
			if (!entry.getKey().endsWith(".json")) continue;
			result.put(entry.getKey(), JsonParser.parseString(new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8)));
		}
		return result;
	}

	private static boolean sameBytes(Map<String, byte[]> first, Map<String, byte[]> second) {
		if (!first.keySet().equals(second.keySet())) return false;
		for (String path : first.keySet()) if (!java.util.Arrays.equals(first.get(path), second.get(path))) return false;
		return true;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
