package geiler.addons.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.FabricCheckEnvironment;
import geiler.addons.client.GeilerAddonsClient;
import geiler.addons.client.chat.ChatChannelsLocalStore;
import geiler.addons.client.dungeon.ClientJsonFile;
import geiler.addons.client.farming.PersistentHourlyStatsStore;
import geiler.addons.client.inventory.PlayerInventoryControlsCodec;
import geiler.addons.client.inventory.PlayerInventoryControlsData;
import geiler.addons.client.inventory.PlayerInventoryControlsStoreSession;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.presentation.PlayerPresentationProfileCacheIo;
import geiler.addons.client.presentation.PlayerPresentationProfileSource;
import geiler.addons.client.presentation.PlayerPresentationProfileStore;
import geiler.addons.client.presentation.PlayerPresentationProfiles;
import geiler.addons.client.scoreboard.ScoreboardLayoutCodec;
import geiler.addons.client.scoreboard.ScoreboardLayoutStore;
import geiler.addons.client.utility.LobbySniperStore;
import geiler.addons.client.utility.LobbySniperStoreLogic;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Load/save/import parity checks against a copied 26.1.2 game directory. */
public final class LegacyConfigProfileRoundTripChecks {
	private LegacyConfigProfileRoundTripChecks() { }

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		FabricCheckEnvironment.initializeConfigDirectory();
		Path gameDir = Path.of(System.getProperty("geileraddons.checkGameDir"));
		Path sourceGameDir = Path.of(System.getProperty("geileraddons.checkSourceGameDir"));
		Path originalSourceConfigDir = sourceGameDir.resolve("config").resolve("geileraddons");
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("geileraddons");
		if (!Files.isRegularFile(originalSourceConfigDir.resolve("config.json"))
			|| !Files.isRegularFile(configDir.resolve("config.json"))) {
			throw new AssertionError("The copied 26.1.2 fixture has no geileraddons/config.json.");
		}

		Path seededFixtureRoot = Files.createTempDirectory("geileraddons-legacy-buttons-");
		try {
			Path sourceConfigDir = seededFixtureRoot.resolve("config").resolve("geileraddons");
			copyDirectory(originalSourceConfigDir, sourceConfigDir);
			byte[] legacyConfigBytes = seedLegacyInventoryButton(sourceConfigDir.resolve("config.json"));
			Path configFile = configDir.resolve("config.json");
			Files.write(configFile, legacyConfigBytes);

			checkPortableProfileImportExport(sourceConfigDir, configDir);
			checkLegacyHourlyStatsStores(sourceConfigDir, configDir);
			LegacyFeatureStoreParityChecks.run(sourceConfigDir, configDir);
			LegacyDungeonGuideParityChecks.run(sourceConfigDir, configDir);
			GeilerAddonsClient.registerModules();
			if (ModuleManager.modules().size() != 53) {
				throw new AssertionError("Expected all 53 registered modules in the legacy-profile round trip.");
			}

			JsonObject legacyConfig = JsonParser.parseString(Files.readString(sourceConfigDir.resolve("config.json")))
				.getAsJsonObject();
			checkLegacyInventoryButtonConfig(legacyConfig, "seeded 26.1.2 fixture");
			resetPersistedClickGuiState();
			seedModuleEnableBitsWithoutLifecycle(configFile);
			ModConfig.load();
			checkLegacyClickGuiState(legacyConfig);
			checkLegacyModuleSettingValues(legacyConfig);
			LegacyMacroCatalogParityChecks.run(sourceConfigDir);
			List<String> loadedState = snapshotModuleState();
			ModConfig.markDirty();
			if (!ModConfig.flushNow()) throw new AssertionError("The legacy profile did not save successfully.");
			JsonObject savedConfig = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
			checkLegacyInventoryButtonConfig(savedConfig, "first 26.2 save");
			if (!legacyConfig.equals(savedConfig)) {
				throw new AssertionError("The 26.1.2 config changed while loading and saving the 26.2 profile.");
			}
			Map<String, byte[]> savedFiles = collectFiles(configDir);

			resetModuleSettingsToDefaults();
			resetPersistedClickGuiState();
			seedModuleEnableBitsWithoutLifecycle(configFile);
			ModConfig.load();
			checkLegacyClickGuiState(legacyConfig);
			LegacyMacroCatalogParityChecks.run(sourceConfigDir);
			List<String> reloadedState = snapshotModuleState();
			if (!loadedState.equals(reloadedState)) {
				throw new AssertionError("Module settings or keybinds changed after saving and reloading the legacy profile.");
			}
			ModConfig.markDirty();
			if (!ModConfig.flushNow()) throw new AssertionError("The reloaded legacy profile did not save successfully.");
			if (!sameFiles(savedFiles, collectFiles(configDir))) {
				throw new AssertionError("The normalized legacy profile changed after a second load/save cycle.");
			}
			System.out.println("Legacy config profile round-trip passed for 53 modules at " + gameDir.toAbsolutePath());
		} finally {
			deleteTree(seededFixtureRoot);
		}
	}

	private static void resetPersistedClickGuiState() {
		ClickGuiState.setCategory(Category.values()[0]);
		ClickGuiState.setOpenModule(null);
		ClickGuiState.setExpandedColor(null);
		ClickGuiState.setSettingsScroll(0);
		ClickGuiState.setCategoryScroll(0);
		ClickGuiState.setGridScrolls(Map.of());
		ClickGuiState.setFavoriteCategoryIds(List.of());
		ClickGuiState.setFavoriteModuleIds(List.of());
	}

	private static void checkLegacyClickGuiState(JsonObject data) {
		Category expectedCategory = Category.values()[0];
		String categoryName = readString(data.get("uiCategory"));
		if (categoryName != null) {
			for (Category category : Category.values()) {
				if (category.name().equals(categoryName)) {
					expectedCategory = category;
					break;
				}
			}
		}
		int expectedCategoryScroll = Math.max(0, readInt(data.get("uiCategoryScroll")));
		Map<Category, Integer> expectedGridScrolls = new java.util.EnumMap<>(Category.class);
		JsonElement gridElement = data.get("uiGridScrollByCategory");
		if (gridElement != null && gridElement.isJsonObject()) {
			JsonObject gridScrolls = gridElement.getAsJsonObject();
			for (Category category : Category.values()) {
				JsonElement scroll = gridScrolls.get(category.name());
				if (scroll != null && scroll.isJsonPrimitive() && scroll.getAsJsonPrimitive().isNumber()) {
					expectedGridScrolls.put(category, Math.max(0, scroll.getAsInt()));
				}
			}
		}
		Set<String> expectedFavoriteCategories = new HashSet<>();
		Set<String> savedCategoryIds = new HashSet<>(readStringList(data.get("uiFavoriteCategories")));
		for (Category category : Category.values()) {
			if (savedCategoryIds.contains(category.name())) expectedFavoriteCategories.add(category.name());
		}
		Set<String> expectedFavoriteModules = new HashSet<>();
		for (String moduleId : readStringList(data.get("uiFavoriteModules"))) {
			if (!moduleId.isBlank()) expectedFavoriteModules.add(moduleId);
		}

		String openModuleId = readString(data.get("uiOpenModule"));
		Module expectedOpenModule = null;
		if (openModuleId != null) {
			for (Module module : ModuleManager.modules()) {
				if (module.configName().equals(openModuleId) && module.hasSettings()) {
					expectedOpenModule = module;
					break;
				}
			}
		}
		ColorSetting expectedExpandedColor = null;
		if (expectedOpenModule != null) {
			String colorName = readString(data.get("uiExpandedColor"));
			if (colorName != null) {
				for (ColorSetting setting : expectedOpenModule.colorSettings()) {
					if (setting.name().equals(colorName)) {
						expectedExpandedColor = setting;
						break;
					}
				}
			}
		}
		int expectedSettingsScroll = expectedOpenModule == null
			? 0 : Math.max(0, readInt(data.get("uiSettingsScroll")));

		if (ClickGuiState.category() != expectedCategory
			|| ClickGuiState.categoryScroll() != expectedCategoryScroll
			|| !ClickGuiState.gridScrolls().equals(expectedGridScrolls)
			|| !ClickGuiState.favoriteCategoryIds().equals(expectedFavoriteCategories)
			|| !ClickGuiState.favoriteModuleIds().equals(expectedFavoriteModules)
			|| ClickGuiState.openModule() != expectedOpenModule
			|| ClickGuiState.settingsScroll() != expectedSettingsScroll
			|| ClickGuiState.expandedColor() != expectedExpandedColor) {
			throw new AssertionError("The copied 26.1.2 Click GUI state changed while loading.");
		}
	}

	private static String readString(JsonElement element) {
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
			? element.getAsString() : null;
	}

	private static int readInt(JsonElement element) {
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
			? element.getAsInt() : 0;
	}

	private static List<String> readStringList(JsonElement element) {
		if (element == null || !element.isJsonArray()) return List.of();
		List<String> values = new java.util.ArrayList<>();
		for (JsonElement value : element.getAsJsonArray()) {
			String text = readString(value);
			if (text != null) values.add(text);
		}
		return List.copyOf(values);
	}

	/** Compare persisted 26.1.2 typed setting values to the settings actually loaded in 26.2. */
	private static void checkLegacyModuleSettingValues(JsonObject legacyConfig) {
		LegacyModuleSettingOracle oracle = new LegacyModuleSettingOracle(legacyConfig);
		for (Module module : ModuleManager.modules()) {
			for (BooleanSetting setting : module.booleanSettings()) oracle.checkToggle(module, setting);
			for (NumberSetting setting : module.numberSettings()) oracle.checkNumber(module, setting);
			for (ColorSetting setting : module.colorSettings()) oracle.checkColor(module, setting);
			for (ChoiceSetting setting : module.choiceSettings()) oracle.checkChoice(module, setting);
			for (TextSetting setting : module.textSettings()) oracle.checkText(module, setting);
		}
		oracle.report();
	}

	/**
	 * Checks only fixture entries with a direct typed-setting counterpart. A residual key is kept
	 * visible in the report rather than being assigned to a setting based on a guessed migration.
	 */
	private static final class LegacyModuleSettingOracle {
		private static final List<String> MAP_NAMES = List.of("toggles", "numbers", "colors", "choices", "texts");
		private static final int MAX_REPORTED_GAPS = 24;

		private final Map<String, JsonObject> maps = new LinkedHashMap<>();
		private final Map<String, Set<String>> comparedKeys = new LinkedHashMap<>();
		private final Map<String, Integer> checkedCounts = new LinkedHashMap<>();
		private final List<String> directValueGaps = new ArrayList<>();

		private LegacyModuleSettingOracle(JsonObject legacyConfig) {
			for (String mapName : MAP_NAMES) {
				JsonElement element = legacyConfig.get(mapName);
				if (element != null && !element.isJsonNull() && !element.isJsonObject()) {
					throw new AssertionError("The copied 26.1.2 " + mapName + " setting map is not an object.");
				}
				maps.put(mapName, element == null || element.isJsonNull() ? new JsonObject() : element.getAsJsonObject());
				comparedKeys.put(mapName, new HashSet<>());
				checkedCounts.put(mapName, 0);
			}
		}

		private void checkToggle(Module module, BooleanSetting setting) {
			String key = settingKey("toggles", module, setting.name());
			if (key == null) return;
			JsonElement value = maps.get("toggles").get(key);
			if (!isBoolean(value)) {
				gap("toggles", key, "unreadable value");
				return;
			}
			if (setting.rawValue() != value.getAsBoolean()) mismatch("toggle", key);
			checked("toggles", key);
		}

		private void checkNumber(Module module, NumberSetting setting) {
			String key = settingKey("numbers", module, setting.name());
			if (key == null) return;
			JsonElement value = maps.get("numbers").get(key);
			if (!isNumber(value)) {
				gap("numbers", key, "unreadable value");
				return;
			}
			float saved = value.getAsFloat();
			if (!Float.isFinite(saved)) {
				gap("numbers", key, "non-finite value is ignored by the setting loader");
				return;
			}
			float expected = saved;
			if (expected < setting.min()) expected = setting.min();
			else if (expected > setting.max()) expected = setting.max();
			if (setting.isInteger()) expected = Math.round(expected);
			if (Float.floatToIntBits(setting.value()) != Float.floatToIntBits(expected)) mismatch("number", key);
			checked("numbers", key);
		}

		private void checkColor(Module module, ColorSetting setting) {
			String key = settingKey("colors", module, setting.name());
			if (key == null) return;
			JsonElement value = maps.get("colors").get(key);
			if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 4) {
				gap("colors", key, "loader requires four RGBA channels");
				return;
			}
			JsonArray rgba = value.getAsJsonArray();
			int[] expected = new int[4];
			for (int channel = 0; channel < expected.length; channel++) {
				JsonElement component = rgba.get(channel);
				if (!isNumber(component)) {
					gap("colors", key, "unreadable channel");
					return;
				}
				expected[channel] = clampColor(component.getAsInt());
			}
			if (setting.hex().length() == 6) expected[3] = 255;
			if (setting.red() != expected[0] || setting.green() != expected[1]
				|| setting.blue() != expected[2] || setting.alpha() != expected[3]) mismatch("color", key);
			checked("colors", key);
		}

		private void checkChoice(Module module, ChoiceSetting setting) {
			String key = settingKey("choices", module, setting.name());
			if (key == null) return;
			JsonElement value = maps.get("choices").get(key);
			if (!isString(value) || !setting.choices().contains(value.getAsString())) {
				gap("choices", key, "value is not a current choice");
				return;
			}
			if (!setting.value().equals(value.getAsString())) mismatch("choice", key);
			checked("choices", key);
		}

		private void checkText(Module module, TextSetting setting) {
			String textKey = settingKey("texts", module, setting.name());
			if (textKey != null) {
				JsonElement value = maps.get("texts").get(textKey);
				if (!isString(value)) {
					gap("texts", textKey, "unreadable value");
					return;
				}
				String expected = truncate(value.getAsString(), setting.maxLength());
				if (!setting.value().equals(expected)) mismatch("text", textKey);
				checked("texts", textKey);
				return;
			}

			// ModConfig.load retains compatibility with text settings that were numbers in 26.1.2.
			String numberKey = settingKey("numbers", module, setting.name());
			if (numberKey == null) return;
			JsonElement legacyNumber = maps.get("numbers").get(numberKey);
			if (!isNumber(legacyNumber) || !Float.isFinite(legacyNumber.getAsFloat())) {
				gap("numbers", numberKey, "legacy text-number fallback is unreadable");
				return;
			}
			String expected = truncate(formatLegacyNumber(legacyNumber.getAsFloat()), setting.maxLength());
			if (!setting.value().equals(expected)) mismatch("text", numberKey);
			checked("numbers", numberKey);
		}

		private String settingKey(String mapName, Module module, String settingName) {
			JsonObject values = maps.get(mapName);
			String currentKey = ConfigSettingKeys.settingKey(module, settingName);
			if (hasValue(values, currentKey)) return currentKey;
			String legacyKey = ConfigSettingKeys.legacySettingKey(module, settingName);
			return legacyKey != null && hasValue(values, legacyKey) ? legacyKey : null;
		}

		private void checked(String mapName, String key) {
			comparedKeys.get(mapName).add(key);
			checkedCounts.put(mapName, checkedCounts.get(mapName) + 1);
		}

		private void gap(String mapName, String key, String reason) {
			comparedKeys.get(mapName).add(key);
			directValueGaps.add(mapName + "." + key + " (" + reason + ")");
		}

		private void mismatch(String settingType, String key) {
			throw new AssertionError("The loaded 26.2 " + settingType
				+ " differs from the copied 26.1.2 fixture for " + key + ".");
		}

		private void report() {
			List<String> gaps = new ArrayList<>(directValueGaps);
			for (String mapName : MAP_NAMES) {
				JsonObject values = maps.get(mapName);
				for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
					if (!comparedKeys.get(mapName).contains(entry.getKey())) {
						gaps.add(mapName + "." + entry.getKey());
					}
				}
			}
			gaps.sort(String::compareTo);
			System.out.println("Legacy module setting values checked against loaded 26.2 settings: toggles="
				+ checkedCounts.get("toggles") + ", numbers=" + checkedCounts.get("numbers")
				+ ", colors=" + checkedCounts.get("colors") + ", choices=" + checkedCounts.get("choices")
				+ ", texts=" + checkedCounts.get("texts") + ".");
			if (!gaps.isEmpty()) {
				int shown = Math.min(MAX_REPORTED_GAPS, gaps.size());
				System.out.println("Legacy setting entries without a direct value comparison (" + gaps.size() + "): "
					+ String.join(", ", gaps.subList(0, shown))
					+ (shown < gaps.size() ? ", ..." : ""));
			}
			System.out.println("This typed-setting oracle leaves enabled-state seeding, keybinds, and top-level setting mirrors outside its comparisons.");
		}

		private static boolean hasValue(JsonObject values, String key) {
			JsonElement value = values.get(key);
			return value != null && !value.isJsonNull();
		}

		private static boolean isBoolean(JsonElement value) {
			return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
		}

		private static boolean isNumber(JsonElement value) {
			return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
		}

		private static boolean isString(JsonElement value) {
			return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
		}

		private static int clampColor(int channel) {
			return Math.max(0, Math.min(255, channel));
		}

		private static String truncate(String value, int maxLength) {
			return value.substring(0, Math.min(value.length(), maxLength));
		}

		private static String formatLegacyNumber(float value) {
			return (double) value == Math.rint((double) value)
				? Long.toString((long) value) : Float.toString(value);
		}
	}

	/** Preserve legacy local tracker values through the Kotlin-backed file readers and writers. */
	private static void checkLegacyHourlyStatsStores(Path sourceConfigDir, Path configDir) throws IOException {
		int checked = 0;
		for (String fileName : List.of("pest-hourly-stats.json", "slayer-hourly-stats.json")) {
			Path sourceFile = sourceConfigDir.resolve(fileName);
			Path copiedFile = configDir.resolve(fileName);
			boolean sourceExists = Files.isRegularFile(sourceFile);
			if (sourceExists != Files.isRegularFile(copiedFile)) {
				throw new AssertionError("A local tracker file was not copied from the legacy profile.");
			}
			if (!sourceExists) continue;
			JsonObject legacy = JsonParser.parseString(Files.readString(sourceFile)).getAsJsonObject();
			if (!legacy.has("kills") || !legacy.has("activeNanos")) {
				throw new AssertionError("A legacy hourly stats file is missing its saved fields.");
			}
			int expectedKills = Math.max(0, legacy.get("kills").getAsInt());
			long expectedActiveNanos = Math.max(0L, legacy.get("activeNanos").getAsLong());
			boolean pestStore = fileName.startsWith("pest-");
			PersistentHourlyStatsStore.Snapshot loaded = pestStore
				? PersistentHourlyStatsStore.loadPests() : PersistentHourlyStatsStore.loadSlayer();
			if (loaded.kills != expectedKills || loaded.activeNanos != expectedActiveNanos) {
				throw new AssertionError("A legacy hourly stats file changed while loading.");
			}
			if (pestStore) PersistentHourlyStatsStore.savePests(loaded.kills, loaded.activeNanos);
			else PersistentHourlyStatsStore.saveSlayer(loaded.kills, loaded.activeNanos);
			if (!geiler.addons.client.dungeon.ClientJsonFile.flushAndReport()) {
				throw new AssertionError("A legacy hourly stats file did not save successfully.");
			}
			PersistentHourlyStatsStore.Snapshot reloaded = pestStore
				? PersistentHourlyStatsStore.loadPests() : PersistentHourlyStatsStore.loadSlayer();
			if (reloaded.kills != expectedKills || reloaded.activeNanos != expectedActiveNanos) {
				throw new AssertionError("A legacy hourly stats file changed after saving and reloading.");
			}
			checked++;
		}
		System.out.println("Legacy local tracker stores checked: " + checked);
	}

	/**
	 * This harness checks persistence without a running Minecraft client. Seed only Module's saved
	 * state bit so ModConfig.load does not invoke real module lifecycle callbacks in the headless JVM;
	 * each module's live enable/disable behavior remains part of client acceptance.
	 */
	private static void seedModuleEnableBitsWithoutLifecycle(Path configFile) throws Exception {
		JsonObject config = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
		JsonObject enabled = config.getAsJsonObject("enabled");
		if (enabled == null) throw new AssertionError("The copied profile has no module enabled-state map.");
		Field enabledField = Module.class.getDeclaredField("enabled");
		enabledField.setAccessible(true);
		for (Module module : ModuleManager.modules()) {
			JsonElement value = enabled.get(module.configName());
			boolean selected = value == null || value.isJsonNull() ? module.defaultEnabled() : value.getAsBoolean();
			enabledField.setBoolean(module, selected);
		}
	}

	private static void resetModuleSettingsToDefaults() {
		for (Module module : ModuleManager.modules()) {
			module.colorSettings().forEach(ColorSetting::reset);
			module.numberSettings().forEach(NumberSetting::reset);
			module.booleanSettings().forEach(BooleanSetting::reset);
			module.choiceSettings().forEach(ChoiceSetting::reset);
			module.textSettings().forEach(TextSetting::reset);
			module.setKeybind(geiler.addons.client.module.ModuleKeybind.NONE);
		}
	}

	private static void checkPortableProfileImportExport(Path sourceConfigDir, Path configDir) throws Exception {
		Map<String, byte[]> portable = collectPortableFiles(sourceConfigDir);
		Map<String, byte[]> localOnly = collectLegacyLocalOnlyFiles(sourceConfigDir);
		Map<String, byte[]> unknown = collectLegacyUnknownFiles(sourceConfigDir);
		checkProductionTransferWalker(configDir, portable, localOnly, unknown);
		byte[] encoded = ConfigProfileCodec.encode(portable);
		ConfigProfileCodec.Archive decoded = ConfigProfileCodec.decode(encoded);
		if (!sameFiles(portable, decoded.files())) {
			throw new AssertionError("Portable files from the copied 26.1.2 profile changed in the export codec.");
		}
		checkLegacyInventoryButtonConfigBytes(decoded.files().get("config.json"), "export archive");

		Path temporary = Files.createTempDirectory("geileraddons-legacy-profile-import-");
		Path installRoot = temporary.resolve("config").resolve("geileraddons");
		try {
			for (Map.Entry<String, byte[]> entry : localOnly.entrySet()) {
				Path target = installRoot.resolve(entry.getKey());
				Files.createDirectories(target.getParent());
				Files.write(target, entry.getValue());
			}
			ConfigProfileInstaller.Result result = ConfigProfileInstaller.install(
				installRoot,
				decoded.files(),
				Map.of(),
				(Predicate<String>) ConfigProfileCodec::isTransferablePath,
				files -> ConfigProfileCodec.validateJsonBounds(files.get("config.json")),
				null);
			if (!result.installed() || !sameFiles(portable, collectPortableFiles(installRoot))) {
				throw new AssertionError("Portable files from the copied 26.1.2 profile changed through import install.");
			}
			checkLegacyInventoryButtonConfigBytes(Files.readAllBytes(installRoot.resolve("config.json")), "import install");
			checkLegacyPortableStoreReloads(portable, temporary.resolve("config"), installRoot);
			for (Map.Entry<String, byte[]> entry : localOnly.entrySet()) {
				if (!java.util.Arrays.equals(entry.getValue(), Files.readAllBytes(installRoot.resolve(entry.getKey())))) {
					throw new AssertionError("A copied client-local file changed during profile install: " + entry.getKey());
				}
			}
			checkLegacyLocalFeatureStoreReloads(localOnly, installRoot, temporary);

			Path unknownInstallRoot = temporary.resolve("unknown-config").resolve("geileraddons");
			for (Map.Entry<String, byte[]> entry : portable.entrySet()) writeFixtureFile(unknownInstallRoot, entry);
			for (Map.Entry<String, byte[]> entry : localOnly.entrySet()) writeFixtureFile(unknownInstallRoot, entry);
			for (Map.Entry<String, byte[]> entry : unknown.entrySet()) writeFixtureFile(unknownInstallRoot, entry);
			Map<String, byte[]> unknownInstallBefore = collectFiles(unknownInstallRoot);
			ConfigProfileInstaller.Result refused = ConfigProfileInstaller.install(
				unknownInstallRoot,
				decoded.files(),
				Map.of(),
				(Predicate<String>) ConfigProfileCodec::isTransferablePath,
				files -> ConfigProfileCodec.validateJsonBounds(files.get("config.json")),
				null);
			if (refused.installed() || !sameFiles(unknownInstallBefore, collectFiles(unknownInstallRoot))) {
				throw new AssertionError("Unknown files from the copied 26.1.2 profile must block import without changing any data.");
			}
		} finally {
			deleteTree(temporary);
		}
	}

	private static void checkLegacyPortableStoreReloads(Map<String, byte[]> portable, Path configDirectory,
		Path installRoot) throws IOException {
		byte[] controlsBytes = portable.get("inventory-controls/controls.json");
		if (controlsBytes != null) {
			PlayerInventoryControlsData expected = PlayerInventoryControlsCodec.decode(
				new String(controlsBytes, StandardCharsets.UTF_8))
				.orElseThrow(() -> new AssertionError("The copied 26.1.2 inventory-controls file could not be decoded."));
			PlayerInventoryControlsData loaded = new PlayerInventoryControlsStoreSession(configDirectory).load();
			if (!expected.equals(loaded)) {
				throw new AssertionError("The copied 26.1.2 inventory-controls profile changed after import and store reload.");
			}
		}

		byte[] layoutBytes = portable.get("scoreboard/layout.json");
		if (layoutBytes != null) {
			String legacyJson = new String(layoutBytes, StandardCharsets.UTF_8);
			String expected = ScoreboardLayoutCodec.encode(ScoreboardLayoutCodec.decode(legacyJson)
				.orElseThrow(() -> new AssertionError("The copied 26.1.2 scoreboard layout could not be decoded.")));
			String installed = ScoreboardLayoutCodec.encode(ScoreboardLayoutStore.loadForReload(
				installRoot.resolve("scoreboard/layout.json")
			).orElseThrow(() -> new AssertionError("The imported 26.1.2 scoreboard layout could not be reloaded.")));
			if (!expected.equals(installed)) {
				throw new AssertionError("The copied 26.1.2 scoreboard layout changed after import and store reload.");
			}
		}
	}

	/** Compare copied 26.1.2 local-only values through their current production stores without editing the source fixture. */
	private static void checkLegacyLocalFeatureStoreReloads(Map<String, byte[]> localOnly, Path installRoot,
		Path temporary) throws Exception {
		byte[] chatBytes = localOnly.get("chat-channels-local.json");
		if (chatBytes != null) {
			Path chatCopy = temporary.resolve("chat-channels-local-reload-check.json");
			Files.write(chatCopy, chatBytes);
			ChatChannelsLocalStore.SanitizedLoad first = ChatChannelsLocalStore.readSanitized(chatCopy);
			if (!first.persistenceAllowed() || first.data() == null) {
				throw new AssertionError("The copied 26.1.2 local chat file was rejected by its production reader.");
			}
			String expected = ClientJsonFile.GSON.toJson(first.data());
			if (!ChatChannelsLocalStore.writeSanitized(chatCopy, first).get(10, TimeUnit.SECONDS)) {
				throw new AssertionError("The copied 26.1.2 local chat file did not save through its production store.");
			}
			ChatChannelsLocalStore.SanitizedLoad reloaded = ChatChannelsLocalStore.readSanitized(chatCopy);
			if (!reloaded.persistenceAllowed() || reloaded.data() == null
				|| !expected.equals(ClientJsonFile.GSON.toJson(reloaded.data()))) {
				throw new AssertionError("The copied 26.1.2 local chat data changed after a production-store reload.");
			}
		}

		byte[] lobbyBytes = localOnly.get("lobby-sniper-targets.json");
		if (lobbyBytes != null) {
			LobbySniperStore.Data raw = ClientJsonFile.GSON.fromJson(
				new String(lobbyBytes, StandardCharsets.UTF_8), LobbySniperStore.Data.class);
			List<LobbySniperStore.EntryData> targets = raw == null || raw.targets == null
				? List.of() : raw.targets.stream().filter(java.util.Objects::nonNull).toList();
			List<LobbySniperStore.Entry> loaded = LobbySniperStoreLogic
				.forPathChecks(installRoot.resolve("lobby-sniper-targets.json")).entries();
			if (targets.size() != 1 || loaded.size() != 1) {
				throw new AssertionError("The copied 26.1.2 Lobby Sniper target count changed during store load.");
			}
			LobbySniperStore.EntryData expected = targets.getFirst();
			LobbySniperStore.Entry actual = loaded.getFirst();
			String expectedName = expected.playerName == null ? "" : expected.playerName.trim();
			String expectedTitle = expected.title == null ? "" : expected.title.replace('\r', ' ').replace('\n', ' ').trim();
			if (!expectedName.equals(actual.playerName())
				|| (expected.enabled == null || expected.enabled) != actual.enabled()
				|| !expectedTitle.equals(actual.title())) {
				throw new AssertionError("The copied 26.1.2 Lobby Sniper target changed during production-store load.");
			}
		}

		byte[] presentationBytes = localOnly.get("player-presentations-cache.json");
		if (presentationBytes != null) {
			PlayerPresentationProfiles expected = PlayerPresentationProfiles.parseUtf8(presentationBytes);
			PlayerPresentationProfiles loaded = PlayerPresentationProfileCacheIo.loadLocal(
				installRoot.resolve("player-presentations-cache.json"),
				PlayerPresentationProfileStore.class,
				PlayerPresentationProfileSource.BUNDLED_RESOURCE);
			if (!expected.entries().equals(loaded.entries()) || expected.hasAnimatedNameStyles() != loaded.hasAnimatedNameStyles()) {
				throw new AssertionError("The copied 26.1.2 player-presentation cache changed during production-store load.");
			}
		}
		System.out.println("Copied local-only feature stores reloaded: chat channels, Lobby Sniper, player presentations");
	}

	private static byte[] seedLegacyInventoryButton(Path configFile) throws IOException {
		JsonObject config = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
		JsonArray buttons = new JsonArray();
		buttons.add(legacyInventoryButton());
		config.add("inventoryButtons", buttons);
		byte[] encoded = config.toString().getBytes(StandardCharsets.UTF_8);
		Files.write(configFile, encoded);
		return encoded;
	}

	private static JsonObject legacyInventoryButton() {
		JsonObject button = new JsonObject();
		button.addProperty("id", 37);
		button.addProperty("macroId", -1);
		button.addProperty("gridX", 2);
		button.addProperty("gridY", -1);
		button.addProperty("slotAligned", false);
		button.addProperty("appearance", "TEXT");
		button.addProperty("value", "Party");
		button.addProperty("hoverTooltip", "Open party action");
		button.addProperty("textAction", "hello party");
		return button;
	}

	private static void checkLegacyInventoryButtonConfig(JsonObject config, String stage) {
		JsonArray buttons = config.getAsJsonArray("inventoryButtons");
		if (buttons == null || buttons.size() != 1 || !legacyInventoryButton().equals(buttons.get(0))) {
			throw new AssertionError("Legacy Inventory Buttons data changed during " + stage + ".");
		}
	}

	private static void checkLegacyInventoryButtonConfigBytes(byte[] encoded, String stage) throws IOException {
		if (encoded == null) throw new AssertionError("Portable profile is missing config.json during " + stage + ".");
		JsonObject config = JsonParser.parseString(new String(encoded, StandardCharsets.UTF_8)).getAsJsonObject();
		checkLegacyInventoryButtonConfig(config, stage);
	}

	private static void copyDirectory(Path source, Path target) throws IOException {
		Files.createDirectories(target);
		try (var paths = Files.walk(source)) {
			for (Path path : paths.toList()) {
				if (Files.isSymbolicLink(path)) {
					throw new IOException("The copied 26.1.2 config fixture contains a symbolic link.");
				}
				Path destination = target.resolve(source.relativize(path));
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static void checkProductionTransferWalker(Path configDir, Map<String, byte[]> portable,
		Map<String, byte[]> localOnly, Map<String, byte[]> unknown) throws Exception {
		Path root = Files.createTempDirectory(configDir, "ga-profile-export-walker-");
		try {
			for (Map.Entry<String, byte[]> entry : portable.entrySet()) writeFixtureFile(root, entry);
			for (Map.Entry<String, byte[]> entry : localOnly.entrySet()) writeFixtureFile(root, entry);
			Map<String, byte[]> snapshot = collectProductionTransferFiles(root);
			if (!sameFiles(portable, snapshot)) {
				throw new AssertionError("The production profile export walker included local data or missed portable data.");
			}

			for (Map.Entry<String, byte[]> entry : unknown.entrySet()) {
				if (ConfigProfileCodec.isLocalOnlyPath(entry.getKey()) || ConfigProfileCodec.isTransferablePath(entry.getKey())) {
					throw new AssertionError("A copied legacy path is unexpectedly classified as owned: " + entry.getKey());
				}
				writeFixtureFile(root, entry);
			}
			writeFixtureFile(root, Map.entry("unknown-config-data.json",
				"{\"keep\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			Map<String, byte[]> beforeUnknownExport = collectFiles(root);
			boolean rejected = false;
			try {
				collectProductionTransferFiles(root);
			} catch (IOException expected) {
				rejected = true;
			}
			if (!rejected || !sameFiles(beforeUnknownExport, collectFiles(root))) {
				throw new AssertionError("Unknown persistent data must block export while remaining untouched.");
			}
		} finally {
			deleteTree(root);
		}
	}

	private static void writeFixtureFile(Path root, Map.Entry<String, byte[]> entry) throws IOException {
		Path target = root.resolve(entry.getKey());
		Files.createDirectories(target.getParent());
		Files.write(target, entry.getValue());
	}

	private static Map<String, byte[]> collectProductionTransferFiles(Path root) throws Exception {
		Method method = ConfigTransferServiceCore.class.getDeclaredMethod("collectCurrentFiles", Path.class);
		method.setAccessible(true);
		try {
			@SuppressWarnings("unchecked")
			Map<String, byte[]> files = (Map<String, byte[]>) method.invoke(ConfigTransferServiceCore.INSTANCE, root);
			return files;
		} catch (InvocationTargetException failure) {
			if (failure.getCause() instanceof IOException cause) throw cause;
			throw failure;
		}
	}

	private static Map<String, byte[]> collectLegacyLocalOnlyFiles(Path root) throws IOException {
		Map<String, byte[]> files = new LinkedHashMap<>();
		Set<String> expected = Set.of(
			"chat-channels-local.json",
			"dungeon-puzzle-personal-bests.json",
			"dungeon-split-personal-bests.json",
			"lobby-sniper-targets.json",
			"miniboss-appearance-signatures.json",
			"pest-hourly-stats.json",
			"player-presentations-cache.json",
			"slayer-hourly-stats.json"
		);
		try (var paths = Files.walk(root)) {
			for (Path file : paths.filter(Files::isRegularFile).toList()) {
				String name = root.relativize(file).toString().replace('\\', '/');
				if (!ConfigProfileCodec.isLocalOnlyPath(name)) continue;
				files.put(name, Files.readAllBytes(file));
			}
		}
		if (!files.keySet().equals(expected)) {
			throw new AssertionError("The copied 26.1.2 profile local-only path set changed: " + files.keySet());
		}
		return files;
	}

	private static Map<String, byte[]> collectLegacyUnknownFiles(Path root) throws IOException {
		Map<String, byte[]> files = new LinkedHashMap<>();
		try (var paths = Files.walk(root)) {
			for (Path file : paths.filter(Files::isRegularFile).toList()) {
				String name = root.relativize(file).toString().replace('\\', '/');
				if (ConfigProfileCodec.isLocalOnlyPath(name) || ConfigProfileCodec.isTransferablePath(name)) continue;
				if (!name.startsWith("config.json.codex-map-")) {
					throw new AssertionError("An unclassified file was found in the copied 26.1.2 profile: " + name);
				}
				files.put(name, Files.readAllBytes(file));
			}
		}
		if (files.size() != 2) {
			throw new AssertionError("Expected the two copied 26.1.2 config backup snapshots, found " + files.keySet());
		}
		return files;
	}

	private static List<String> snapshotModuleState() {
		List<String> state = new ArrayList<>();
		for (Module module : ModuleManager.modules()) {
			state.add("module|" + module.configName() + "|" + module.isEnabled() + "|" + module.keybind().key().getName()
				+ "|" + module.keybind().modifiers());
			for (ColorSetting setting : module.colorSettings()) {
				state.add("color|" + module.configName() + "|" + setting.name() + "|" + setting.red() + "|"
					+ setting.green() + "|" + setting.blue() + "|" + setting.alpha());
			}
			for (NumberSetting setting : module.numberSettings()) {
				state.add("number|" + module.configName() + "|" + setting.name() + "|"
					+ Float.floatToIntBits(setting.value()));
			}
			for (BooleanSetting setting : module.booleanSettings()) {
				state.add("toggle|" + module.configName() + "|" + setting.name() + "|" + setting.rawValue());
			}
			for (ChoiceSetting setting : module.choiceSettings()) {
				state.add("choice|" + module.configName() + "|" + setting.name() + "|" + setting.value());
			}
			for (TextSetting setting : module.textSettings()) {
				state.add("text|" + module.configName() + "|" + setting.name() + "|" + setting.value());
			}
		}
		return List.copyOf(state);
	}

	private static Map<String, byte[]> collectPortableFiles(Path root) throws IOException {
		Map<String, byte[]> files = new LinkedHashMap<>();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
				String relative = root.relativize(path).toString().replace('\\', '/');
				if (ConfigProfileCodec.isTransferablePath(relative)) files.put(relative, Files.readAllBytes(path));
			}
		}
		return files;
	}

	private static Map<String, byte[]> collectFiles(Path root) throws IOException {
		Map<String, byte[]> files = new LinkedHashMap<>();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
				files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
			}
		}
		return files;
	}

	private static boolean sameFiles(Map<String, byte[]> first, Map<String, byte[]> second) {
		if (!first.keySet().equals(second.keySet())) return false;
		for (String path : first.keySet()) {
			if (!java.util.Arrays.equals(first.get(path), second.get(path))) return false;
		}
		return true;
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}
}
