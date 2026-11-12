package geiler.addons.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import geiler.addons.client.location.Island;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroFunction;
import geiler.addons.client.macro.MacroTriggerContext;
import geiler.addons.client.macro.MacroValue;
import geiler.addons.client.macro.MacroVariableStore;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.impl.MacrosModule;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Compares the loaded macro catalog to the copied legacy records without displaying user content. */
final class LegacyMacroCatalogParityChecks {
	private static final Gson GSON = new GsonBuilder().create();
	private static final int MAX_INDEX_BYTES = 65_536;
	private static final int MAX_RECORD_BYTES = 1_048_576;
	private static final int MAX_MACROS = 2_000;

	private LegacyMacroCatalogParityChecks() { }

	static void run(Path sourceConfigDir) throws IOException {
		Path indexPath = sourceConfigDir.resolve("macro-index.json");
		ModConfig.MacroIndex index = GSON.fromJson(
			new String(readBounded(indexPath, MAX_INDEX_BYTES), StandardCharsets.UTF_8), ModConfig.MacroIndex.class);
		if (index == null || index.version != 1 || index.macros == null || index.functions == null ||
			index.macros.size() > MAX_MACROS || index.functions.size() > MacroFunction.MAX_FUNCTIONS) {
			throw new AssertionError("The copied legacy macro catalog index is unsupported or exceeds its limits.");
		}

		List<JsonElement> expectedMacros = new ArrayList<>();
		for (String name : index.macros) {
			ModConfig.MacroData saved = GSON.fromJson(
				new String(readRecord(sourceConfigDir.resolve("macros"), name), StandardCharsets.UTF_8),
				ModConfig.MacroData.class);
			if (saved == null || saved.id < 0) throw new AssertionError("A copied legacy macro record is invalid.");
			expectedMacros.add(snapshot(restoreMacro(saved)));
		}
		List<JsonElement> actualMacros = new ArrayList<>();
		for (MacroDefinition macro : MacrosModule.INSTANCE.macros()) actualMacros.add(snapshot(macro));
		if (!expectedMacros.equals(actualMacros)) {
			throw new AssertionError("The copied legacy macro catalog changed when loaded or saved.");
		}

		List<JsonElement> expectedFunctions = new ArrayList<>();
		for (String name : index.functions) {
			ModConfig.MacroFunctionData saved = GSON.fromJson(
				new String(readRecord(sourceConfigDir.resolve("functions"), name), StandardCharsets.UTF_8),
				ModConfig.MacroFunctionData.class);
			if (saved == null || saved.id == null) throw new AssertionError("A copied legacy function record is invalid.");
			expectedFunctions.add(snapshot(restoreFunction(saved)));
		}
		List<JsonElement> actualFunctions = new ArrayList<>();
		for (MacroFunction function : MacrosModule.INSTANCE.functions()) actualFunctions.add(snapshot(function));
		if (!expectedFunctions.equals(actualFunctions)) {
			throw new AssertionError("The copied legacy function catalog changed when loaded or saved.");
		}

		JsonObject sourceConfig = JsonParser.parseString(Files.readString(sourceConfigDir.resolve("config.json")))
			.getAsJsonObject();
		JsonArray savedVariables = sourceConfig.has("macroVariables") && sourceConfig.get("macroVariables").isJsonArray()
			? sourceConfig.getAsJsonArray("macroVariables") : null;
		List<MacroVariableStore.SavedVariable> expectedVariables = MacroVariableConfigCodec.decode(savedVariables);
		if (!expectedVariables.equals(MacrosModule.INSTANCE.globalVariables().savedVariables())) {
			throw new AssertionError("The copied legacy global macro variables changed when loaded or saved.");
		}
	}

	private static byte[] readRecord(Path directory, String name) throws IOException {
		if (name == null || !name.matches("[A-Za-z0-9._-]{1,128}\\.json")) {
			throw new AssertionError("The copied legacy macro index contains an unsafe record name.");
		}
		Path normalizedDirectory = directory.toAbsolutePath().normalize();
		Path path = normalizedDirectory.resolve(name).normalize();
		if (!path.getParent().equals(normalizedDirectory) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new AssertionError("The copied legacy macro index references a missing record.");
		}
		return readBounded(path, MAX_RECORD_BYTES);
	}

	private static byte[] readBounded(Path path, int limit) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) {
			throw new AssertionError("A copied legacy macro file is missing or exceeds its size limit.");
		}
		return Files.readAllBytes(path);
	}

	private static MacroDefinition restoreMacro(ModConfig.MacroData saved) {
		MacroDefinition macro = new MacroDefinition(saved.id);
		macro.setFolderId(saved.folderId);
		if (saved.name != null) macro.setName(saved.name);
		if (saved.enabled != null) macro.setEnabled(saved.enabled);
		if (saved.key != null) {
			try {
				macro.setKeybind(new ModuleKeybind(InputConstants.getKey(saved.key), saved.modifiers));
			} catch (RuntimeException ignored) { }
		}
		if (saved.triggerContext != null) {
			try { macro.setTriggerContext(MacroTriggerContext.valueOf(saved.triggerContext)); }
			catch (IllegalArgumentException ignored) { }
		}
		if (saved.islandRestricted != null) macro.setIslandRestricted(saved.islandRestricted);
		EnumSet<Island> islands = EnumSet.noneOf(Island.class);
		if (saved.islands != null) for (String name : saved.islands) {
			if (name == null) continue;
			try {
				Island island = Island.valueOf(name);
				if (island.selectable()) islands.add(island);
			} catch (IllegalArgumentException ignored) { }
		}
		macro.setIslands(islands);
		float zoom = saved.canvasZoom == null ? 1.0f : saved.canvasZoom;
		int zoomVersion = saved.canvasZoomVersion == null ? 0 : saved.canvasZoomVersion;
		if (zoomVersion < 1) zoom *= 100.0f;
		if (zoomVersion < 2) zoom *= 100.0f;
		macro.setCanvasView(saved.canvasPanX == null ? 0.0f : saved.canvasPanX,
			saved.canvasPanY == null ? 0.0f : saved.canvasPanY, zoom);
		if (saved.scripts != null && !saved.scripts.isEmpty()) {
			macro.restoreScripts(MacroScriptConfigCodec.decode(saved.scripts));
		} else {
			macro.steps().addAll(MacroStepConfigCodec.decode(saved.steps));
		}
		macro.restoreDetachedBlocks(MacroStepConfigCodec.decode(saved.detachedBlocks));
		return macro;
	}

	private static MacroFunction restoreFunction(ModConfig.MacroFunctionData saved) {
		MacroFunction function = new MacroFunction(saved.id);
		function.setName(saved.name);
		if (saved.parameters != null) for (ModConfig.MacroFunctionParameterData parameter : saved.parameters) {
			if (function.parameters().size() >= 32 || parameter == null) break;
			function.parameters().add(new MacroFunction.Parameter(parameter.name, parseType(parameter.type),
				parameter.defaultValue));
		}
		function.steps().addAll(MacroStepConfigCodec.decode(saved.steps));
		return function;
	}

	private static MacroValue.Type parseType(String value) {
		try { return MacroValue.Type.valueOf((value == null ? "TEXT" : value).toUpperCase(Locale.ROOT)); }
		catch (RuntimeException ignored) { return MacroValue.Type.TEXT; }
	}

	private static JsonObject snapshot(MacroDefinition macro) {
		JsonObject result = new JsonObject();
		result.addProperty("id", macro.id());
		result.addProperty("name", macro.name());
		result.addProperty("folderId", macro.folderId());
		result.addProperty("enabled", macro.enabled());
		result.addProperty("key", macro.keybind().isBound() ? macro.keybind().key().getName() : null);
		result.addProperty("modifiers", macro.keybind().modifiers());
		result.addProperty("triggerContext", macro.triggerContext().name());
		result.addProperty("islandRestricted", macro.islandRestricted());
		JsonArray islands = new JsonArray();
		for (Island island : macro.islands()) islands.add(island.name());
		result.add("islands", islands);
		result.add("scripts", MacroScriptConfigCodec.encode(macro.scripts()));
		result.add("steps", MacroStepConfigCodec.encode(macro.steps()));
		result.add("detachedBlocks", MacroStepConfigCodec.encode(macro.detachedBlocks()));
		result.addProperty("canvasPanX", macro.canvasPanX());
		result.addProperty("canvasPanY", macro.canvasPanY());
		result.addProperty("canvasZoom", macro.canvasZoom());
		return result;
	}

	private static JsonObject snapshot(MacroFunction function) {
		JsonObject result = new JsonObject();
		result.addProperty("id", function.id());
		result.addProperty("name", function.name());
		JsonArray parameters = new JsonArray();
		for (MacroFunction.Parameter parameter : function.parameters()) {
			JsonObject saved = new JsonObject();
			saved.addProperty("name", parameter.name());
			saved.addProperty("type", parameter.type().name());
			saved.addProperty("defaultValue", parameter.defaultValue());
			parameters.add(saved);
		}
		result.add("parameters", parameters);
		result.add("steps", MacroStepConfigCodec.encode(function.steps()));
		return result;
	}
}
