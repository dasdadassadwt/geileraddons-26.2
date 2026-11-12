package geiler.addons.client.dungeon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.FabricCheckEnvironment;
import geiler.addons.client.config.ConfigTransferServiceCore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** End-to-end legacy route, saved-key, and profile persistence checks in an isolated config directory. */
public final class DungeonGuidePersistenceChecks {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String NEXT_KEY = "key.keyboard.k";
	private static final String PREVIOUS_KEY = "key.keyboard.j";

	private DungeonGuidePersistenceChecks() { }

	public static void main(String[] args) throws Exception {
		run();
		System.out.println("DungeonGuidePersistenceChecks passed");
	}

	public static void run() throws Exception {
		FabricCheckEnvironment.initializeConfigDirectory();
		Path configDirectory = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
		assertIsolatedConfigDirectory(configDirectory);
		Path addonDirectory = configDirectory.resolve("geileraddons");
		Path guideRoot = addonDirectory.resolve("dungeon-guides");
		Path legacyCatalog = addonDirectory.resolve("dungeon-guides.json");
		Path configFile = addonDirectory.resolve("config.json");
		if (Files.exists(guideRoot, LinkOption.NOFOLLOW_LINKS) || Files.exists(legacyCatalog, LinkOption.NOFOLLOW_LINKS)) {
			throw new AssertionError("Dungeon Guide persistence checks require a fresh isolated config directory.");
		}
		byte[] priorConfig = Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)
			? Files.readAllBytes(configFile) : null;
		try {
			Files.createDirectories(addonDirectory);
			Files.writeString(configFile, minimalConfig(), StandardCharsets.UTF_8);
			checkLegacyCatalogImport(guideRoot, legacyCatalog);
			checkLegacySplitRouteImport(guideRoot, legacyCatalog);
			checkProfileExportImport(addonDirectory);
		} finally {
			clearStoreAndFixture(guideRoot, legacyCatalog);
			if (priorConfig != null) Files.write(configFile, priorConfig);
			else Files.deleteIfExists(configFile);
		}
	}

	private static void checkLegacyCatalogImport(Path guideRoot, Path legacyCatalog) throws Exception {
		List<DungeonGuideNode> legacyNodes = List.of(
			node("catalog-zulu-entry", "F7", "ENTRY", "Zulu route", 0),
			node("catalog-alpha-first", "F7", "ENTRY", "Alpha route", 7),
			node("catalog-alpha-second", "F7", "ENTRY", "Alpha route", 8),
			node("catalog-blood", "F7", "BLOOD_OPEN", "Zulu route", 0),
			node("catalog-f6", "F6", "ENTRY", "My saved route", 0));
		JsonObject catalog = new JsonObject();
		catalog.addProperty("version", 1);
		catalog.add("nodes", GSON.toJsonTree(legacyNodes));
		catalog.addProperty("nextKey", NEXT_KEY);
		catalog.addProperty("previousKey", PREVIOUS_KEY);
		byte[] source = GSON.toJson(catalog).getBytes(StandardCharsets.UTF_8);
		Files.write(legacyCatalog, source);

		DungeonGuideStore.reloadFromDisk();
		assertKeys(NEXT_KEY, PREVIOUS_KEY, "legacy catalog migration loads saved step bindings");
		assertNodes(List.of(
			"catalog-f6", "catalog-alpha-first", "catalog-alpha-second", "catalog-zulu-entry", "catalog-blood"),
			"legacy catalog migration keeps every supported floor and phase step");
		check("Combined Route".equals(DungeonGuideStore.routeName("F7")),
			"different legacy names on one floor combine under the documented route name");
		check("My saved route".equals(DungeonGuideStore.routeName("F6")),
			"a sole legacy route name remains unchanged");
		check(DungeonGuideStore.flushAndReport(), "legacy catalog migration flushes through the transactional writer");
		Path archive = guideRoot.resolve("archive/legacy-v1/dungeon-guides.json");
		check(Files.isRegularFile(archive) && java.util.Arrays.equals(source, Files.readAllBytes(archive)),
			"legacy catalog bytes remain recoverable after migration");
		assertSavedIndex(guideRoot, NEXT_KEY, PREVIOUS_KEY, Map.of(
			"F7|ENTRY", List.of("catalog-alpha-first", "catalog-alpha-second", "catalog-zulu-entry"),
			"F7|BLOOD_OPEN", List.of("catalog-blood"),
			"F6|ENTRY", List.of("catalog-f6")));
	}

	private static void checkLegacySplitRouteImport(Path guideRoot, Path legacyCatalog) throws Exception {
		check(DungeonGuideStore.resetToFactoryDefaults(), "empty the first fixture before importing the legacy split index");
		check(DungeonGuideStore.flushAndReport(), "factory reset flushes before the next isolated fixture");
		deleteTree(guideRoot);
		Files.deleteIfExists(legacyCatalog);

		DungeonGuideNode zulu = node("split-zulu", "F7", "ENTRY", "Zulu route", 0);
		DungeonGuideNode alphaFirst = node("split-alpha-first", "F7", "ENTRY", "Alpha route", 4);
		DungeonGuideNode alphaSecond = node("split-alpha-second", "F7", "ENTRY", "Alpha route", 5);
		DungeonGuideNode blood = node("split-blood", "F7", "BLOOD_OPEN", "Alpha route", 0);
		DungeonGuideNode f6 = node("split-f6", "F6", "ENTRY", "Solo route", 0);
		String zuluFile = "F7/ENTRY/zulu-1.json";
		String alphaFile = "F7/ENTRY/alpha-2.json";
		String bloodFile = "F7/BLOOD_OPEN/alpha-3.json";
		String f6File = "F6/ENTRY/solo-4.json";
		writeRoute(guideRoot, zuluFile, zulu);
		writeRoute(guideRoot, alphaFile, alphaFirst, alphaSecond);
		writeRoute(guideRoot, bloodFile, blood);
		writeRoute(guideRoot, f6File, f6);
		byte[] legacyIndex = legacyIndex(List.of(
			entry("F7", "ENTRY", "Zulu route", zuluFile, "NEXT_OBJECTIVE"),
			entry("F7", "ENTRY", "Alpha route", alphaFile, "EVENT_SEGMENT"),
			entry("F7", "BLOOD_OPEN", "Alpha route", bloodFile, "NEXT_OBJECTIVE"),
			entry("F6", "ENTRY", "Solo route", f6File, "EVENT_SEGMENT")));
		Files.write(guideRoot.resolve("index.json"), legacyIndex);

		check(DungeonGuideStore.reloadFromDisk(), "legacy split index loads and normalizes without blocking persistence");
		assertKeys(NEXT_KEY, PREVIOUS_KEY, "legacy split-index migration preserves saved step bindings");
		assertNodes(List.of("split-f6", "split-alpha-first", "split-alpha-second", "split-zulu", "split-blood"),
			"legacy split-index import keeps distinct floor and phase steps in stable route order");
		check("Combined Route".equals(DungeonGuideStore.routeName("F7")),
			"multiple version-1 route names on one floor are combined without dropping steps");
		check("Solo route".equals(DungeonGuideStore.routeName("F6")),
			"version-1 import preserves a floor with only one route name");
		check(DungeonGuideStore.routeTiming("F7", "ENTRY", "Combined Route") == DungeonGuideStore.TransitionTiming.NEXT_OBJECTIVE,
			"phase timing migration retains the first ordered legacy route's timing");
		check(DungeonGuideStore.flushAndReport(), "legacy split-index migration flushes through the transactional writer");
		check(java.util.Arrays.equals(legacyIndex, Files.readAllBytes(guideRoot.resolve("archive/legacy-v1/index.json"))),
			"legacy split index remains archived byte-for-byte");
		for (String file : List.of(zuluFile, alphaFile, bloodFile, f6File)) {
			check(Files.isRegularFile(guideRoot.resolve("archive/legacy-v1/routes").resolve(file)),
				"legacy split route remains archived: " + file);
		}
		assertSavedIndex(guideRoot, NEXT_KEY, PREVIOUS_KEY, Map.of(
			"F7|ENTRY", List.of("split-alpha-first", "split-alpha-second", "split-zulu"),
			"F7|BLOOD_OPEN", List.of("split-blood"),
			"F6|ENTRY", List.of("split-f6")));
		check(DungeonGuideStore.setRouteName("F6", "Route..A"),
			"a route name with repeated interior periods remains supported");
		check("Route..A".equals(DungeonGuideStore.routeName("F6")), "the exact repeated-period route name is retained");
		check(DungeonGuideStore.flushAndReport(), "a repeated-period route name saves transactionally");
		assertSavedIndex(guideRoot, NEXT_KEY, PREVIOUS_KEY, Map.of(
			"F7|ENTRY", List.of("split-alpha-first", "split-alpha-second", "split-zulu"),
			"F7|BLOOD_OPEN", List.of("split-blood"),
			"F6|ENTRY", List.of("split-f6")));
		JsonObject savedIndex = JsonParser.parseString(Files.readString(guideRoot.resolve("index.json"))).getAsJsonObject();
		String repeatedPeriodPath = null;
		for (var routeValue : savedIndex.getAsJsonArray("routes")) {
			JsonObject route = routeValue.getAsJsonObject();
			if ("F6".equals(route.get("floor").getAsString())) repeatedPeriodPath = route.get("file").getAsString();
		}
		check(repeatedPeriodPath != null && repeatedPeriodPath.matches("F6/ENTRY/route\\.\\.a-[0-9a-z]+\\.json"),
			"repeated periods remain inside a safe basename segment");
		check(DungeonGuideStore.reloadFromDisk(), "a repeated-period route filename reloads without blocking persistence");
		check("Route..A".equals(DungeonGuideStore.routeName("F6")), "a repeated-period route name survives disk reload");
	}

	private static void checkProfileExportImport(Path addonDirectory) throws Exception {
		String profileName = "dungeon-guide-persistence-check-" + Long.toUnsignedString(System.nanoTime());
		Object profile = invokeCore("writeArchive", new Class<?>[] { String.class }, profileName);
		Path archivePath = (Path) profile.getClass().getMethod("path").invoke(profile);
		Object validated = invokeCore("readArchive", new Class<?>[] { Path.class }, archivePath);
		@SuppressWarnings("unchecked")
		Method filesAccessor = validated.getClass().getDeclaredMethod("getFiles");
		filesAccessor.setAccessible(true);
		Map<String, byte[]> archiveFiles = (Map<String, byte[]>) filesAccessor.invoke(validated);
		byte[] archivedIndex = archiveFiles.get("dungeon-guides/index.json");
		check(archivedIndex != null, "profile export includes the Dungeon Guide index");
		JsonObject index = JsonParser.parseString(new String(archivedIndex, StandardCharsets.UTF_8)).getAsJsonObject();
		check(NEXT_KEY.equals(index.get("nextKey").getAsString()) && PREVIOUS_KEY.equals(index.get("previousKey").getAsString()),
			"profile export preserves both saved Dungeon Guide step bindings");
		JsonArray routes = index.getAsJsonArray("routes");
		Set<String> expectedMembers = new HashSet<>();
		for (var routeValue : routes) {
			JsonObject route = routeValue.getAsJsonObject();
			String member = "dungeon-guides/" + route.get("floor").getAsString() + "/" +
				route.get("phase").getAsString() + "/" + route.get("file").getAsString().substring(
					route.get("file").getAsString().lastIndexOf('/') + 1);
			expectedMembers.add(member);
			check(archiveFiles.containsKey(member), "profile export includes referenced route file " + member);
		}
		check(expectedMembers.size() == 3, "profile export keeps routes split by floor and phase");

		Object result = invokeCore("installWithRollback", new Class<?>[] { Map.class, Map.class }, archiveFiles, archiveFiles);
		Method installedAccessor = result.getClass().getDeclaredMethod("getInstalled");
		installedAccessor.setAccessible(true);
		boolean installed = (boolean) installedAccessor.invoke(result);
		check(installed, "profile import installer accepts the exported guide route generation");
		check(DungeonGuideStore.reloadFromDisk(), "post-import guide reload accepts the installed profile files");
		assertKeys(NEXT_KEY, PREVIOUS_KEY, "profile import restores both saved Dungeon Guide step bindings");
		check(DungeonGuideStore.all().size() == 5, "profile import restores guide nodes across routes");
		check("Combined Route".equals(DungeonGuideStore.routeName("F7")) &&
			"Route..A".equals(DungeonGuideStore.routeName("F6")),
			"profile import restores each floor's route name");
		check(archivePath.getParent().equals(addonDirectory.resolve("exports")) && Files.isRegularFile(archivePath),
			"profile export writes a portable archive under the owned exports directory");
	}

	private static void assertSavedIndex(Path guideRoot, String nextKey, String previousKey,
		Map<String, List<String>> expectedGroups) throws IOException {
		JsonObject index = JsonParser.parseString(Files.readString(guideRoot.resolve("index.json"))).getAsJsonObject();
		check(index.get("version").getAsInt() == 2, "guide save writes the current index schema");
		check(nextKey.equals(index.get("nextKey").getAsString()) && previousKey.equals(index.get("previousKey").getAsString()),
			"guide save writes the imported next and previous key bindings");
		Map<String, Set<String>> actual = new LinkedHashMap<>();
		for (var routeValue : index.getAsJsonArray("routes")) {
			JsonObject route = routeValue.getAsJsonObject();
			String group = route.get("floor").getAsString() + "|" + route.get("phase").getAsString();
			String relative = route.get("file").getAsString();
			String[] segments = relative.split("/", -1);
			check(!Path.of(relative).isAbsolute() && segments.length == 3 &&
				segments[0].equals(route.get("floor").getAsString()) &&
				segments[1].equals(route.get("phase").getAsString()) &&
				!segments[2].equals(".") && !segments[2].equals(".."),
				"saved route target uses its exact floor/phase and one safe basename segment: " + relative);
			Path routePath = guideRoot.resolve(relative).normalize();
			check(routePath.startsWith(guideRoot.normalize()) && Files.isRegularFile(routePath),
				"saved route file remains inside the guide root: " + relative);
			JsonObject routeFile = JsonParser.parseString(Files.readString(routePath)).getAsJsonObject();
			check(routeFile.get("version").getAsInt() == 1, "saved route file keeps the current route schema");
			Set<String> ids = actual.computeIfAbsent(group, ignored -> new HashSet<>());
			for (var nodeValue : routeFile.getAsJsonArray("nodes")) {
				JsonObject node = nodeValue.getAsJsonObject();
				check(group.equals(node.get("floor").getAsString() + "|" + node.get("phase").getAsString()),
					"route nodes remain assigned to their source floor and phase");
				ids.add(node.get("id").getAsString());
			}
		}
		Map<String, Set<String>> expected = new LinkedHashMap<>();
		for (var entry : expectedGroups.entrySet()) expected.put(entry.getKey(), new HashSet<>(entry.getValue()));
		check(expected.equals(actual), "saved route files retain every node in the correct floor/phase group: " + actual);
	}

	private static void assertKeys(String next, String previous, String message) {
		check(next.equals(DungeonGuideStore.nextKey()) && previous.equals(DungeonGuideStore.previousKey()), message);
	}

	private static void assertNodes(List<String> expectedIds, String message) {
		List<String> actual = new ArrayList<>();
		for (DungeonGuideNode node : DungeonGuideStore.all()) actual.add(node.id);
		check(expectedIds.equals(actual), message + " (expected " + expectedIds + ", got " + actual + ")");
	}

	private static DungeonGuideNode node(String id, String floor, String phase, String routeName, int order) {
		DungeonGuideNode node = new DungeonGuideNode(DungeonFloor.parse(floor), phase, order, order + 1, 64, -order);
		node.id = id;
		node.routeName = routeName;
		node.label = id;
		return node;
	}

	private static void writeRoute(Path guideRoot, String relative, DungeonGuideNode... nodes) throws IOException {
		JsonObject file = new JsonObject();
		file.addProperty("version", 1);
		file.add("nodes", GSON.toJsonTree(List.of(nodes)));
		Path target = guideRoot.resolve(relative);
		Files.createDirectories(target.getParent());
		Files.writeString(target, GSON.toJson(file), StandardCharsets.UTF_8);
	}

	private static byte[] legacyIndex(List<JsonObject> routes) {
		JsonObject index = new JsonObject();
		index.addProperty("version", 1);
		JsonArray values = new JsonArray();
		routes.forEach(values::add);
		index.add("routes", values);
		index.addProperty("nextKey", NEXT_KEY);
		index.addProperty("previousKey", PREVIOUS_KEY);
		return GSON.toJson(index).getBytes(StandardCharsets.UTF_8);
	}

	private static JsonObject entry(String floor, String phase, String name, String file, String timing) {
		JsonObject entry = new JsonObject();
		entry.addProperty("floor", floor);
		entry.addProperty("phase", phase);
		entry.addProperty("name", name);
		entry.addProperty("file", file);
		entry.addProperty("timing", timing);
		return entry;
	}

	private static String minimalConfig() {
		return "{\"enabled\":{},\"toggles\":{},\"choices\":{},\"texts\":{},\"numbers\":{},\"colors\":{},\"keybinds\":{}}";
	}

	private static Object invokeCore(String name, Class<?>[] parameters, Object... arguments) throws Exception {
		Method method = ConfigTransferServiceCore.class.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method.invoke(ConfigTransferServiceCore.INSTANCE, arguments);
	}

	private static void clearStoreAndFixture(Path guideRoot, Path legacyCatalog) throws Exception {
		try {
			DungeonGuideStore.flushAndReport();
			if (DungeonGuideStore.resetToFactoryDefaults()) DungeonGuideStore.flushAndReport();
		} finally {
			deleteTree(guideRoot);
			Files.deleteIfExists(legacyCatalog);
		}
	}

	private static void assertIsolatedConfigDirectory(Path configDirectory) throws IOException {
		Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
		Files.createDirectories(configDirectory);
		Path realTemporary = temporaryDirectory.toRealPath();
		Path realConfig = configDirectory.toRealPath();
		if (!realConfig.startsWith(realTemporary)) {
			throw new AssertionError("Dungeon Guide persistence checks refuse to mutate a non-temporary config directory: " + realConfig);
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
					throw new IOException("Refusing to clean a non-regular Dungeon Guide fixture file: " + file);
				}
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
				if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
					throw new IOException("Refusing to clean a non-directory Dungeon Guide fixture path: " + directory);
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
				if (error != null) throw error;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
