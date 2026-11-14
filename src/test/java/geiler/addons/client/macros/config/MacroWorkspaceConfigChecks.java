package geiler.addons.client.macros.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.config.ClickGuiState;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Covers the separate JSON file of the Macros workspace: shape, per-module state, sections and recovery rules. */
public final class MacroWorkspaceConfigChecks {
	private MacroWorkspaceConfigChecks() { }

	/** A throwaway module that is never registered, so the production catalog stays untouched. */
	private static final class Probe extends Module {
		final NumberSetting speed = new NumberSetting("Speed", 1.0f, 1_000_000.0f, 400.0f);
		final BooleanSetting flag = new BooleanSetting("Flag", true);
		final ChoiceSetting mode = new ChoiceSetting("Mode", "A", "A", "B", "C");
		final TextSetting name = new TextSetting("Name", "", 12);
		final ColorSetting color = new ColorSetting("Color", 10, 20, 30, 255);

		Probe() {
			super("Probe", "test module", Category.SIM, false);
		}

		@Override
		public java.util.List<NumberSetting> numberSettings() { return List.of(speed); }
		@Override
		public java.util.List<BooleanSetting> booleanSettings() { return List.of(flag); }
		@Override
		public java.util.List<ChoiceSetting> choiceSettings() { return List.of(mode); }
		@Override
		public java.util.List<TextSetting> textSettings() { return List.of(name); }
		@Override
		public java.util.List<ColorSetting> colorSettings() { return List.of(color); }
	}

	public static void run() throws Exception {
		checkModuleState();
		checkSeparationFromConfigJson();
		checkRotationPersistence();
		checkDocumentShape();
		checkSections();
		checkRecovery();
		checkFileRoundTrip();
	}

	/** The real catalog: Sim modules are saved in the Macros file only, and come back from it. */
	private static void checkSeparationFromConfigJson() throws Exception {
		Module speed = null;
		for (Module module : geiler.addons.client.module.ModuleManager.modules(Category.SIM)) {
			if (module.name().equals("Speed Sim")) speed = module;
		}
		check(speed != null, "the Speed Sim module is registered");
		NumberSetting setting = speed.numberSettings().get(0);
		float original = setting.value();
		Path directory = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("geileraddons");
		try {
			setting.setValue(555.0f);
			geiler.addons.client.config.ModConfig.markDirty();
			geiler.addons.client.config.ModConfig.save();
			check(geiler.addons.client.config.ModConfig.flushPrepared(), "the config and the Macros file finish writing");
			JsonObject config = JsonParser.parseString(Files.readString(directory.resolve("config.json"))).getAsJsonObject();
			check(!config.getAsJsonObject("enabled").has("Speed Sim") && !config.getAsJsonObject("numbers").has("Speed Sim.Speed"),
				"config.json never holds a Macros-workspace module");
			check(config.getAsJsonObject("enabled").size() > 10, "config.json still holds the Normal workspace modules");
			JsonObject macros = JsonParser.parseString(Files.readString(directory.resolve("macros-workspace.json"))).getAsJsonObject();
			check(macros.getAsJsonObject("modules").getAsJsonObject("Speed Sim").getAsJsonObject("settings")
				.get("Speed").getAsFloat() == 555.0f, "the Macros file holds the Sim module settings");
			check(!macros.getAsJsonObject("modules").has("General"), "the Macros file holds no Normal workspace module");

			setting.setValue(400.0f);
			geiler.addons.client.config.ModConfig.load();
			check(setting.value() == 555.0f, "ModConfig.load restores the Macros workspace from its own file");
		} finally {
			setting.setValue(original);
			geiler.addons.client.config.ModConfig.markDirty();
			geiler.addons.client.config.ModConfig.save();
			geiler.addons.client.config.ModConfig.flushPrepared();
		}
	}

	/** Presets and the distance curve ride in the Macros file's rotation section and come back with their choices. */
	private static void checkRotationPersistence() {
		geiler.addons.client.macros.rotation.RotationConfig config = geiler.addons.client.macros.rotation.RotationConfig.current();
		Module module = geiler.addons.client.macros.module.RotationEngineModule.INSTANCE;
		config.settings.effectIntensity = 1.5;
		config.settings.distancePoints.get(2)[1] = 1.25;
		config.saveAs("Mine");
		JsonObject document = MacroWorkspaceConfig.INSTANCE.encodeDocument();
		JsonObject rotation = document.getAsJsonObject("rotation");
		check(rotation.getAsJsonArray("presets").size() == 1 && "Mine".equals(rotation.get("preset").getAsString())
			&& rotation.getAsJsonArray("distancePoints").size() == 6, "the rotation section holds presets, the active preset and the curve");
		check(!JsonParser.parseString(new com.google.gson.Gson().toJson(document)).getAsJsonObject().get("modules")
			.getAsJsonObject().has("General"), "the Macros document still holds no Normal workspace module");

		MacroWorkspaceConfig.INSTANCE.applyDocument(new JsonObject());
		check(config.presets.isEmpty() && "".equals(config.preset) && config.settings.distancePoints.get(2)[1] == 1.0,
			"a file with no rotation section falls back to the defaults");
		ChoiceSetting preset = module.choiceSettings().stream().filter(s -> s.name().equals("Preset")).findFirst().orElseThrow();
		check(preset.choices().equals(List.of("None", "Human", "Relaxed", "Fast", "Precise")), "the preset choice then offers None and the built-in presets");

		MacroWorkspaceConfig.INSTANCE.applyDocument(document);
		check(config.presets.size() == 1 && "Mine".equals(config.preset) && config.presets.get(0).settings.effectIntensity == 1.5
			&& config.settings.distancePoints.get(2)[1] == 1.25, "presets, the active preset and the curve are restored");
		check(preset.choices().equals(List.of("None", "Human", "Relaxed", "Fast", "Precise", "Mine")) && "Mine".equals(preset.value()),
			"the preset choice lists the restored presets and selects the active one");
		MacroWorkspaceConfig.INSTANCE.applyDocument(new JsonObject());
	}

	private static void checkModuleState() {
		Probe module = new Probe();
		module.speed.setValue(12345.0f);
		module.flag.setValue(false);
		module.mode.setValue("C");
		module.name.setValue("hello");
		module.color.set(1, 2, 3, 4);
		JsonObject state = MacroWorkspaceConfig.INSTANCE.encodeModule(module);
		check(state.has("enabled") && !state.get("enabled").getAsBoolean(), "module state records the enabled flag");
		JsonObject settings = state.getAsJsonObject("settings");
		check(settings.get("Speed").getAsFloat() == 12345.0f && !settings.get("Flag").getAsBoolean()
			&& "C".equals(settings.get("Mode").getAsString()) && "hello".equals(settings.get("Name").getAsString())
			&& settings.getAsJsonArray("Color").size() == 4, "every setting type is written under its own name");

		Probe restored = new Probe();
		MacroWorkspaceConfig.INSTANCE.decodeModule(restored, state);
		check(restored.speed.value() == 12345.0f && !restored.flag.value() && "C".equals(restored.mode.value())
			&& "hello".equals(restored.name.value()) && restored.color.red() == 1 && restored.color.alpha() == 4,
			"module state round trips through JSON");
		check(restored.speed.max() >= 1_000_000.0f, "a huge Speed value is representable");

		Probe fresh = new Probe();
		MacroWorkspaceConfig.INSTANCE.decodeModule(fresh, new JsonObject());
		check(fresh.speed.value() == 400.0f && fresh.flag.value() && "A".equals(fresh.mode.value()),
			"missing saved values keep the declared defaults");

		JsonObject bad = JsonParser.parseString(
			"{\"enabled\":\"yes\",\"settings\":{\"Speed\":\"fast\",\"Flag\":5,\"Mode\":\"Z\",\"Color\":[1,2]}}").getAsJsonObject();
		Probe tolerant = new Probe();
		MacroWorkspaceConfig.INSTANCE.decodeModule(tolerant, bad);
		check(tolerant.speed.value() == 400.0f && tolerant.flag.value() && "A".equals(tolerant.mode.value())
			&& tolerant.color.red() == 10, "wrong-typed or out-of-catalog saved values are ignored, not fatal");
	}

	private static void checkDocumentShape() {
		Workspace original = ClickGuiState.workspace();
		try {
			ClickGuiState.setWorkspace(Workspace.MACROS);
			ClickGuiState.setCategory(Category.ENGINE);
			ClickGuiState.setCategoryScroll(7);
			ClickGuiState.setGridScroll(Category.ENGINE, 21);
			ClickGuiState.toggleFavorite(Category.SIM);
			JsonObject document = MacroWorkspaceConfig.INSTANCE.encodeDocument(List.of());
			check(document.get("version").getAsInt() == 1, "the file declares version 1");
			check("MACROS".equals(document.get("activeWorkspace").getAsString()),
				"the active workspace is persisted in the macros file");
			JsonObject ui = document.getAsJsonObject("ui");
			check("ENGINE".equals(ui.get("category").getAsString()) && ui.get("categoryScroll").getAsInt() == 7
				&& ui.getAsJsonObject("gridScrollByCategory").get("ENGINE").getAsInt() == 21
				&& ui.getAsJsonArray("favoriteCategories").size() == 1, "Macros view state lives under ui");

			ClickGuiState.setWorkspace(Workspace.NORMAL);
			ClickGuiState.runIn(Workspace.MACROS, () -> {
				ClickGuiState.setCategory(Category.SIM);
				ClickGuiState.setCategoryScroll(0);
				ClickGuiState.setGridScrolls(java.util.Map.of());
				ClickGuiState.setFavoriteCategoryIds(List.of());
			});
			MacroWorkspaceConfig.INSTANCE.applyDocument(document, List.of());
			check(ClickGuiState.workspace() == Workspace.MACROS, "loading restores the active workspace");
			check(ClickGuiState.category() == Category.ENGINE && ClickGuiState.categoryScroll() == 7
				&& ClickGuiState.gridScroll(Category.ENGINE) == 21 && ClickGuiState.isFavorite(Category.SIM),
				"loading restores the Macros view state");
			ClickGuiState.runIn(Workspace.NORMAL, () -> check(ClickGuiState.categoryScroll() != 7,
				"loading the Macros file never touches Normal workspace state"));

			// A file saved while Rotation and Pathfinder were categories of their own opens on Engine.
			for (String old : new String[] {"ROTATION", "PATHFINDER"}) {
				ClickGuiState.runIn(Workspace.MACROS, () -> ClickGuiState.setCategory(Category.SIM));
				document.getAsJsonObject("ui").addProperty("category", old);
				MacroWorkspaceConfig.INSTANCE.applyDocument(document, List.of());
				check(ClickGuiState.category() == Category.ENGINE, "a saved " + old + " category opens Engine");
			}
		} finally {
			ClickGuiState.runIn(Workspace.MACROS, () -> {
				ClickGuiState.setCategory(Category.ENGINE);
				ClickGuiState.setCategoryScroll(0);
				ClickGuiState.setGridScrolls(java.util.Map.of());
				ClickGuiState.setFavoriteCategoryIds(List.of());
			});
			ClickGuiState.setWorkspace(original);
		}
	}

	private static void checkSections() {
		AtomicReference<JsonElement> received = new AtomicReference<>();
		boolean[] called = {false};
		MacroWorkspaceConfig.Section section = new MacroWorkspaceConfig.Section() {
			@Override public String name() { return "probeSection"; }
			@Override public JsonElement save() {
				JsonObject object = new JsonObject();
				object.addProperty("value", 42);
				return object;
			}
			@Override public void load(JsonElement element) { called[0] = true; received.set(element); }
		};
		JsonObject document = MacroWorkspaceConfig.INSTANCE.encodeDocument(List.of(section));
		check(document.has("probeSection") && document.getAsJsonObject("probeSection").get("value").getAsInt() == 42,
			"feature-owned sections are written as top-level keys");
		MacroWorkspaceConfig.INSTANCE.applyDocument(document, List.of(section));
		check(called[0] && received.get().getAsJsonObject().get("value").getAsInt() == 42,
			"a feature section receives its saved JSON on load");
		called[0] = false;
		received.set(null);
		MacroWorkspaceConfig.INSTANCE.applyDocument(new JsonObject(), List.of(section));
		check(called[0] && received.get() == null, "a feature section is told when nothing was saved, so it can reset to defaults");
	}

	private static void checkRecovery() {
		check(MacroWorkspaceConfig.INSTANCE.isSupported(JsonParser.parseString("{\"version\":1}").getAsJsonObject()),
			"version 1 is read");
		check(!MacroWorkspaceConfig.INSTANCE.isSupported(JsonParser.parseString("{\"version\":2}").getAsJsonObject())
			&& !MacroWorkspaceConfig.INSTANCE.isSupported(new JsonObject()),
			"a newer or missing version is not read, and the file is then left untouched");
	}

	private static void checkFileRoundTrip() throws Exception {
		Path directory = Files.createTempDirectory("macros-workspace-checks");
		Path file = directory.resolve("macros-workspace.json");
		try {
			JsonObject document = new JsonObject();
			document.addProperty("version", 1);
			document.addProperty("activeWorkspace", "MACROS");
			MacroWorkspaceConfig.INSTANCE.writeAtomically(file, document);
			check(Files.exists(file), "the file is written");
			try (var listing = Files.list(directory)) {
				check(listing.count() == 1, "no temporary file is left behind");
			}
			JsonObject read = MacroWorkspaceConfig.INSTANCE.readDocument(file);
			check(read != null && "MACROS".equals(read.get("activeWorkspace").getAsString()), "the file reads back");

			Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
			check(MacroWorkspaceConfig.INSTANCE.readDocument(file) == null, "a corrupt file reads as nothing, not as an exception");
			check(MacroWorkspaceConfig.INSTANCE.readDocument(directory.resolve("missing.json")) == null,
				"a missing file reads as nothing");
			Files.writeString(file, " ".repeat(1_100_000), StandardCharsets.UTF_8);
			check(MacroWorkspaceConfig.INSTANCE.readDocument(file) == null, "an oversized file is refused");
		} finally {
			try (var listing = Files.list(directory)) {
				listing.forEach(path -> path.toFile().delete());
			}
			Files.deleteIfExists(directory);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
