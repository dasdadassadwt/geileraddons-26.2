package geiler.addons.client.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.config.MacroScriptConfigCodec;
import geiler.addons.client.config.MacroStepConfigCodec;
import geiler.addons.client.config.MacroVariableConfigCodec;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.impl.MacrosModule;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Isolated regression checks for inline macro migration and split macro/function persistence. */
public final class MacroPersistenceChecks {
	private static final int MAX_RECORD_BYTES = 1_048_576;
	private static final int MAX_INDEX_BYTES = 65_536;
	private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

	private MacroPersistenceChecks() { }

	public static void run() {
		Path configDirectory = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
		Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
		if (!configDirectory.startsWith(temporaryDirectory)) {
			throw new AssertionError("Macro persistence checks require the isolated temporary config directory.");
		}

		MacrosModule module = MacrosModule.INSTANCE;
		List<MacroDefinition> previousMacros = new ArrayList<>(module.macros());
		List<MacroFunction> previousFunctions = new ArrayList<>(module.functions());
		MacroVariableStore variables = module.globalVariables();
		List<MacroVariableStore.Definition> previousDefinitions = new ArrayList<>(variables.definitions());
		List<MacroVariableStore.SavedVariable> previousSavedVariables = new ArrayList<>(variables.savedVariables());
		Map<String, Object> previousValues = new LinkedHashMap<>();
		for (MacroVariableStore.Definition definition : previousDefinitions) {
			previousValues.put(definition.id(), variables.value(definition.id()));
		}

		Path root = configDirectory.resolve("geileraddons");
		Path config = root.resolve("config.json");
		Path index = root.resolve("macro-index.json");
		Path backup = root.resolve("config.before-split.json");
		Path macrosDirectory = root.resolve("macros");
		Path functionsDirectory = root.resolve("functions");
		ensureFreshFixture(config, index, backup, macrosDirectory, functionsDirectory);

		try {
			checkCodecWhitespaceSemantics();
			checkCodecBounds();
			checkSplitWriterBounds();
			checkInlineMigrationAndReload(config, index, backup);
		} catch (Exception error) {
			throw new AssertionError("Macro/function persistence parity checks failed.", error);
		} finally {
			module.restore(previousMacros);
			module.restoreFunctions(previousFunctions);
			variables.restore(previousSavedVariables);
			for (MacroVariableStore.Definition definition : previousDefinitions) {
				Object value = previousValues.get(definition.id());
				if (value != null) variables.setValue(definition.id(), value);
			}
			try {
				ModConfig.markDirty();
				if (!ModConfig.flushNow() || !ModConfig.flushPrepared()) {
					throw new IOException("Could not restore and drain the isolated config snapshot.");
				}
				deleteTree(macrosDirectory);
				deleteTree(functionsDirectory);
				Files.deleteIfExists(index);
				Files.deleteIfExists(config);
				Files.deleteIfExists(backup);
				try {
					Files.deleteIfExists(root);
				} catch (DirectoryNotEmptyException sharedConfigFilesRemain) {
					// Other offline checks may own files under this shared config directory.
				}
			} catch (IOException error) {
				throw new AssertionError("Could not clean the isolated macro persistence fixture.", error);
			}
		}
	}

	private static void checkCodecWhitespaceSemantics() {
		JsonArray paddedIsland = JsonParser.parseString(
			"[{\"type\":\"world_switch\",\"island\":\"\u2003GARDEN\u2003\"}]").getAsJsonArray();
		MacroStep.WorldSwitch worldSwitch = (MacroStep.WorldSwitch) MacroStepConfigCodec.decode(paddedIsland).getFirst();
		check(worldSwitch.target() == Island.HUB,
			"Java trim leaves U+2003 padding intact, so a padded persisted island falls back to HUB");
		JsonObject encodedWorldSwitch = MacroStepConfigCodec.encode(List.of(worldSwitch)).get(0).getAsJsonObject();
		check(encodedWorldSwitch.has("island") && !encodedWorldSwitch.has("target"),
			"world-switch persistence keeps the legacy island serialization key");

		JsonArray paddedEnums = JsonParser.parseString(
			"[{\"type\":\"key\",\"inputMode\":\" \u2003MOUSE\u2003 \",\"mouseButton\":\" \u2003RIGHT\u2003 \"}]")
			.getAsJsonArray();
		MacroStep.Key key = (MacroStep.Key) MacroStepConfigCodec.decode(paddedEnums).getFirst();
		check(key.inputMode() == MacroStep.Key.InputMode.KEYBOARD
			&& key.mouseButton() == MacroStep.MouseButton.Button.LEFT,
			"Java trim does not accept U+2003-padded enum values and preserves enum fallbacks");
		JsonObject encodedKey = MacroStepConfigCodec.encode(List.of(key)).get(0).getAsJsonObject();
		check(encodedKey.has("inputMode") && encodedKey.has("mouseButton") && encodedKey.has("hotbarSlot"),
			"key-step persistence keeps the legacy enum and hotbar serialization keys");

		JsonArray nonBreakingSpaceMode = JsonParser.parseString(
			"[{\"type\":\"repeat\",\"forever\":true,\"mode\":\"\u00a0\",\"count\":4}]").getAsJsonArray();
		MacroStep.Repeat repeat = (MacroStep.Repeat) MacroStepConfigCodec.decode(nonBreakingSpaceMode).getFirst();
		check(repeat.mode() == MacroStep.Repeat.Mode.COUNT,
			"Java String.isBlank treats U+00A0 as nonblank, parses the invalid mode, and falls back to COUNT");

		JsonArray nonBreakingSpaceVariable = JsonParser.parseString(
			"[{\"id\":\"\u00a0\",\"name\":\"\u00a0\",\"type\":\"TEXT\",\"persistValue\":true,\"savedValue\":\"kept\"}]")
			.getAsJsonArray();
		List<MacroVariableStore.SavedVariable> variables = MacroVariableConfigCodec.decode(nonBreakingSpaceVariable);
		check(variables.size() == 1 && "\u00a0".equals(variables.getFirst().definition().id())
			&& "\u00a0".equals(variables.getFirst().definition().name())
			&& "kept".equals(variables.getFirst().savedValue()),
			"Java blank checks retain NBSP-only persisted variable IDs, names, and opted-in values");
		JsonObject encodedVariable = MacroVariableConfigCodec.encode(variables).get(0).getAsJsonObject();
		check(encodedVariable.has("id") && encodedVariable.has("name") && encodedVariable.has("type")
			&& encodedVariable.has("persistValue") && encodedVariable.has("savedValue"),
			"global-variable persistence keeps the legacy definition and saved-value keys");
	}

	private static void ensureFreshFixture(Path config, Path index, Path backup, Path macros, Path functions) {
		if (Files.isSymbolicLink(config.getParent()) || Files.exists(config) || Files.exists(index)
			|| Files.exists(backup) || Files.exists(macros) || Files.exists(functions)) {
			throw new AssertionError("Macro persistence checks refuse to overwrite an existing config fixture.");
		}
	}

	private static void checkInlineMigrationAndReload(Path config, Path index, Path backup) throws Exception {
		ModConfig.Data inline = new ModConfig.Data();
		inline.macros = new ArrayList<>(List.of(macroRecord(createPrimaryMacro()), macroRecord(createSecondMacro())));
		inline.macroFunctions = new ArrayList<>(List.of(functionRecord(createFirstFunction()), functionRecord(createSecondFunction())));
		inline.macroVariables = MacroVariableConfigCodec.encode(List.of(
			new MacroVariableStore.SavedVariable(
				new MacroVariableStore.Definition("global-score", "Score", MacroValue.Type.NUMBER, true), "12.5"),
			new MacroVariableStore.SavedVariable(
				new MacroVariableStore.Definition("session-label", "Session Label", MacroValue.Type.TEXT, false), "not persisted")));
		byte[] originalInlineBytes = JSON.toJson(inline).getBytes(StandardCharsets.UTF_8);
		Files.createDirectories(config.getParent());
		Files.write(config, originalInlineBytes);

		ModConfig.Data loadedInline = loadMacroCatalog();
		check(loadedInline.macros != null && loadedInline.macros.size() == 2,
			"an inline 26.1.2 macro list remains the source when no split index exists");
		check(loadedInline.macroFunctions != null && loadedInline.macroFunctions.size() == 2,
			"inline reusable functions remain the source when no split index exists");
		checkLoadedCatalog("Legacy Inline Plan", "First Function", false);

		MacrosModule module = MacrosModule.INSTANCE;
		List<MacroDefinition> currentMacros = new ArrayList<>(module.macros());
		MacroDefinition editedMacro = module.macro(91);
		editedMacro.setName("Edited Plan");
		editedMacro.steps().add(0, new MacroStep.Chat("edited-first"));
		findScript(editedMacro, MacroScript.Trigger.CHAT).setChatPattern("edited chat pattern");
		module.restore(currentMacros);

		MacroFunction editedFunction = module.function("function-one");
		editedFunction.setName("Edited Function");
		editedFunction.steps().add(0, new MacroStep.Chat("edited-function-first"));
		module.restoreFunctions(module.functions());
		module.globalVariables().setValue("global-score", "15.75");
		ModConfig.markDirty();
		check(ModConfig.flushNow(), "editing and saving the inline catalog writes split macro/function records");

		JsonObject savedIndex = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
		check(savedIndex.keySet().equals(Set.of("version", "macros", "functions")),
			"macro-index keeps exactly the version, macros, and functions fields");
		check(savedIndex.get("version").getAsInt() == 1, "macro-index keeps schema version 1");
		List<String> macroFiles = strings(savedIndex.getAsJsonArray("macros"));
		List<String> functionFiles = strings(savedIndex.getAsJsonArray("functions"));
		check(macroFiles.equals(List.of("Edited-Plan-91.json", "Second-Plan-4.json")),
			"the manifest preserves macro order and derives safe filenames from the edited names and stable IDs");
		String firstFunctionPath = "Edited-Function-function-one-"
			+ Integer.toUnsignedString("function-one".hashCode(), 36) + ".json";
		String secondFunctionPath = "Second-Function-function-two-"
			+ Integer.toUnsignedString("function-two".hashCode(), 36) + ".json";
		check(functionFiles.equals(List.of(firstFunctionPath, secondFunctionPath)),
			"the manifest preserves function order and uses stable function-ID filename suffixes");
		check(Arrays.equals(originalInlineBytes, Files.readAllBytes(backup)),
			"the first split save retains an exact backup of the original inline config");
		JsonObject savedConfig = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
		check(!savedConfig.has("macros") && !savedConfig.has("macroFunctions"),
			"the post-migration main config no longer duplicates split catalog entries inline");
		check(Files.size(index) <= MAX_INDEX_BYTES,
			"the emitted macro-index remains within its 64 KiB read/write bound");
		check(Files.size(FilesSupport.child(config.getParent(), "macros", macroFiles.get(0))) <= MAX_RECORD_BYTES,
			"a saved macro record remains within its 1 MiB read/write bound");

		ModConfig.Data reloaded = loadMacroCatalog();
		check(reloaded.macros != null && reloaded.macros.size() == 2
			&& reloaded.macroFunctions != null && reloaded.macroFunctions.size() == 2,
			"the version-1 index reloads both split libraries in manifest order");
		checkLoadedCatalog("Edited Plan", "Edited Function", true);
		check(!Files.exists(FilesSupport.child(config.getParent(), "macros", "Legacy-Inline-Plan-91.json")),
			"renaming a macro deletes its prior writer-owned file only after the new index commits");

		byte[] stableConfig = Files.readAllBytes(config);
		byte[] stableIndex = Files.readAllBytes(index);
		byte[] stableMacro = Files.readAllBytes(FilesSupport.child(config.getParent(), "macros", macroFiles.get(0)));
		MacroDefinition oversized = MacrosModule.INSTANCE.macro(91);
		oversized.steps().add(new MacroStep.Chat("x".repeat(MAX_RECORD_BYTES)));
		ModConfig.markDirty();
		check(!ModConfig.flushNow(), "a macro record larger than 1 MiB is rejected rather than truncated");
		check(Arrays.equals(stableConfig, Files.readAllBytes(config))
			&& Arrays.equals(stableIndex, Files.readAllBytes(index))
			&& Arrays.equals(stableMacro,
				Files.readAllBytes(FilesSupport.child(config.getParent(), "macros", macroFiles.get(0)))),
			"rejecting an oversized macro leaves the prior config, index, and record intact");
	}

	private static ModConfig.Data loadMacroCatalog() throws Exception {
		Object core = configCore();
		ModConfig.Data data = (ModConfig.Data) invoke(core, "readData", new Class<?>[0]);
		MacrosModule.INSTANCE.globalVariables().restore(MacroVariableConfigCodec.decode(data.macroVariables));
		invoke(core, "loadSeparatedMacroData", new Class<?>[]{ModConfig.Data.class}, data);
		invoke(core, "loadMacroFunctions", new Class<?>[]{ModConfig.Data.class}, data);
		invoke(core, "loadMacros", new Class<?>[]{ModConfig.Data.class}, data);
		return data;
	}

	private static void checkLoadedCatalog(String macroName, String functionName, boolean edited) {
		MacrosModule module = MacrosModule.INSTANCE;
		List<MacroDefinition> macros = module.macros();
		check(macros.size() == 2 && macros.get(0).id() == 91 && macros.get(1).id() == 4,
			"macro array order and stable numeric IDs survive loading");
		MacroDefinition macro = macros.get(0);
		check(macroName.equals(macro.name()) && !macro.enabled()
			&& macro.triggerContext() == MacroTriggerContext.CONTAINER_ONLY
			&& macro.islandRestricted() && macro.islands().containsAll(List.of(Island.GARDEN, Island.HUB))
			&& "legacy-folder".equals(macro.folderId()),
			"macro name, enabled state, trigger context, island filter, and folder are retained");
		check(macro.scripts().stream().map(MacroScript::trigger).toList().equals(List.of(
			MacroScript.Trigger.CHAT, MacroScript.Trigger.KEY_PRESS,
			MacroScript.Trigger.WORLD_REGION, MacroScript.Trigger.ON_CALL)),
			"chat, key, world-region, and on-call stacks retain their original order");
		MacroScript chat = findScript(macro, MacroScript.Trigger.CHAT);
		check((edited ? "edited chat pattern" : "wave cleared").equals(chat.chatPattern())
			&& !chat.chatContains() && chat.chatCooldownMillis() == 1_250,
			"chat trigger matching settings survive the inline and split reloads");
		MacroScript key = findScript(macro, MacroScript.Trigger.KEY_PRESS);
		check("key.keyboard.g".equals(key.keybind().key().getName()) && key.keybind().modifiers() == 3,
			"the key stack retains its key and modifier mask");
		List<MacroStep> macroSteps = key.steps();
		check((edited ? "edited-first" : "macro-first").equals(((MacroStep.Chat) macroSteps.get(0)).message()),
			"editing and re-saving preserves the first macro action position");
		int updateIndex = edited ? 2 : 1;
		int callIndex = edited ? 3 : 2;
		int lastIndex = edited ? 4 : 3;
		MacroStep.UpdateVariable update = (MacroStep.UpdateVariable) macroSteps.get(updateIndex);
		MacroStep.FunctionCall call = (MacroStep.FunctionCall) macroSteps.get(callIndex);
		check(update.operation() == MacroStep.UpdateVariable.Operation.SET
			&& "global-score".equals(update.globalVariableId())
			&& "3.5".equals(update.value().value()),
			"global-variable action type, stable ID, and literal survive the round trip");
		check("function-one".equals(call.functionId()) && call.arguments().size() == 1
			&& call.arguments().getFirst().variableReference()
			&& call.arguments().getFirst().scope() == MacroValue.Scope.GLOBAL
			&& "global-score".equals(call.arguments().getFirst().value()),
			"function calls retain their target and global-variable argument");
		check((edited ? "macro-last" : "macro-last").equals(((MacroStep.Chat) macroSteps.get(lastIndex)).message()),
			"the final macro action remains after preceding ordered actions");
		MacroWorldRegion region = findScript(macro, MacroScript.Trigger.WORLD_REGION).worldRegion();
		check(region.placed() && "garden-world".equals(region.worldKey())
			&& region.shape() == MacroWorldRegion.Shape.RING && region.oncePerWorld()
			&& region.repeatDelayMillis() == 2_500 && region.x() == 11.5 && region.y() == 72.0
			&& region.z() == -4.25,
			"the world trigger's placement, shape, one-shot mode, and repeat delay survive");
		MacroStep.MacroCall onCall = (MacroStep.MacroCall)
			findScript(macro, MacroScript.Trigger.ON_CALL).steps().getFirst();
		check(onCall.macroId() == 4 && onCall.condition() == null,
			"an unconditional on-call macro action retains its target and remains unconditional");
		check(macro.detachedBlocks().size() == 1
			&& "detached note".equals(((MacroStep.Comment) macro.detachedBlocks().getFirst()).text()),
			"detached editor blocks remain independent and ordered");

		List<MacroFunction> functions = module.functions();
		check(functions.size() == 2 && "function-one".equals(functions.get(0).id())
			&& "function-two".equals(functions.get(1).id()),
			"function order and stable string IDs survive loading");
		MacroFunction function = functions.get(0);
		check(functionName.equals(function.name()) && function.parameters().size() == 1
			&& "amount".equals(function.parameters().getFirst().name())
			&& function.parameters().getFirst().type() == MacroValue.Type.NUMBER
			&& "6.25".equals(function.parameters().getFirst().defaultValue()),
			"function names, ordered parameter metadata, type, and default survive");
		check((edited ? "edited-function-first" : "function-first").equals(
			((MacroStep.Chat) function.steps().getFirst()).message())
			&& "function-last".equals(((MacroStep.Chat) function.steps().getLast()).message()),
			"function action order survives editing, saving, and reloading");
		check("function-one".equals(((MacroStep.FunctionCall) macroSteps.get(callIndex)).functionId()),
			"the macro's function reference still resolves to its saved function ID");

		List<MacroVariableStore.Definition> definitions = module.globalVariables().definitions();
		check(definitions.size() == 2 && "global-score".equals(definitions.get(0).id())
			&& "session-label".equals(definitions.get(1).id()),
			"global-variable definitions retain manifest-independent insertion order");
		Object score = module.globalVariables().value("global-score");
		check(score instanceof Number && Math.abs(((Number) score).doubleValue() - (edited ? 15.75 : 12.5)) < 0.0001,
			"opted-in global-variable values survive the save/reload path");
		check("".equals(module.globalVariables().value("session-label")),
			"non-persistent variables restart at their typed default rather than leaking a live value");
	}

	private static void checkCodecBounds() {
		List<MacroScript> scripts = new ArrayList<>();
		for (int index = 0; index < 40; index++) {
			scripts.add(new MacroScript("script-" + index, MacroScript.Trigger.KEY_PRESS));
		}
		check(MacroScriptConfigCodec.encode(scripts).size() == 32
			&& MacroScriptConfigCodec.decode(MacroScriptConfigCodec.encode(scripts)).size() == 32,
			"the per-macro trigger-stack cap remains 32 on encode and decode");

		List<MacroVariableStore.SavedVariable> variables = new ArrayList<>();
		for (int index = 0; index < MacroVariableStore.MAX_VARIABLES + 5; index++) {
			variables.add(new MacroVariableStore.SavedVariable(
				new MacroVariableStore.Definition("variable-" + index, "Variable " + index, MacroValue.Type.TEXT, false), null));
		}
		JsonArray encodedVariables = MacroVariableConfigCodec.encode(variables);
		check(encodedVariables.size() == MacroVariableStore.MAX_VARIABLES
			&& MacroVariableConfigCodec.decode(encodedVariables).size() == MacroVariableStore.MAX_VARIABLES,
			"the global-variable cap remains enforced on encode and decode");
	}

	private static void checkSplitWriterBounds() throws Exception {
		ModConfig.Data tooManyMacros = new ModConfig.Data();
		tooManyMacros.macroFiles = new ArrayList<>();
		tooManyMacros.functionFiles = new ArrayList<>();
		for (int id = 0; id <= 2_000; id++) {
			ModConfig.MacroData macro = new ModConfig.MacroData();
			macro.id = id;
			macro.name = "Macro " + id;
			tooManyMacros.macroFiles.add(macro);
		}
		check(writerFailure(tooManyMacros).contains("2,000 macros"),
			"the split writer still rejects a macro catalog above 2,000 entries");

		ModConfig.Data oversizedIndex = new ModConfig.Data();
		oversizedIndex.macroFiles = new ArrayList<>();
		oversizedIndex.functionFiles = new ArrayList<>();
		for (int id = 1_000; id < 2_200; id++) {
			ModConfig.MacroData macro = new ModConfig.MacroData();
			macro.id = id;
			macro.name = "N".repeat(48);
			oversizedIndex.macroFiles.add(macro);
		}
		check(writerFailure(oversizedIndex).contains(Integer.toString(MAX_INDEX_BYTES)),
			"a serialized macro-index above 64 KiB is rejected before replacing the current catalog");

		ModConfig.Data oversizedRecord = new ModConfig.Data();
		oversizedRecord.macroFiles = new ArrayList<>();
		oversizedRecord.functionFiles = new ArrayList<>();
		ModConfig.MacroData macro = new ModConfig.MacroData();
		macro.id = 99;
		macro.name = "Oversized";
		macro.steps = new JsonArray();
		JsonObject chat = new JsonObject();
		chat.addProperty("type", "chat");
		chat.addProperty("message", "x".repeat(MAX_RECORD_BYTES));
		macro.steps.add(chat);
		oversizedRecord.macroFiles.add(macro);
		check(writerFailure(oversizedRecord).contains("persistence limit"),
			"a serialized macro record above 1 MiB is rejected before any files are written");

		ModConfig.Data tooManyFunctions = new ModConfig.Data();
		tooManyFunctions.macroFiles = new ArrayList<>();
		tooManyFunctions.functionFiles = new ArrayList<>();
		for (int index = 0; index <= MacroFunction.MAX_FUNCTIONS; index++) {
			ModConfig.MacroFunctionData function = new ModConfig.MacroFunctionData();
			function.id = "function-" + index;
			function.name = "Function " + index;
			tooManyFunctions.functionFiles.add(function);
		}
		check(writerFailure(tooManyFunctions).contains("functions"),
			"the split writer still rejects a function catalog above its shared limit");
	}

	private static String writerFailure(ModConfig.Data data) throws Exception {
		Object core = configCore();
		Method method = core.getClass().getDeclaredMethod("writeSeparatedMacroData", ModConfig.Data.class);
		method.setAccessible(true);
		try {
			method.invoke(core, data);
		} catch (InvocationTargetException error) {
			Throwable cause = error.getCause();
			if (cause instanceof IOException io) return io.getMessage();
			throw new AssertionError("The split writer failed for an unexpected reason.", cause);
		}
		throw new AssertionError("The split writer accepted an over-limit macro/function catalog.");
	}

	private static MacroDefinition createPrimaryMacro() {
		MacroDefinition macro = new MacroDefinition(91);
		macro.setName("Legacy Inline Plan");
		macro.setEnabled(false);
		macro.setFolderId("legacy-folder");
		macro.setTriggerContext(MacroTriggerContext.CONTAINER_ONLY);
		macro.setIslandRestricted(true);
		macro.setIslands(List.of(Island.GARDEN, Island.HUB));
		macro.setCanvasView(14.0f, -22.5f, 72.0f);

		MacroScript chat = new MacroScript("legacy-chat", MacroScript.Trigger.CHAT);
		chat.setCanvasPosition(30, 20);
		chat.setChatPattern("wave cleared");
		chat.setChatContains(false);
		chat.setChatCooldownMillis(1_250);
		chat.steps().add(new MacroStep.Chat("chat-stack-action"));

		MacroScript key = new MacroScript("legacy-key", MacroScript.Trigger.KEY_PRESS);
		key.setCanvasPosition(0, 0);
		key.setKeybind(new ModuleKeybind(InputConstants.getKey("key.keyboard.g"), 3));
		key.steps().add(new MacroStep.Chat("macro-first"));
		MacroStep.UpdateVariable update = new MacroStep.UpdateVariable();
		update.setOperation(MacroStep.UpdateVariable.Operation.SET);
		update.setName("score");
		update.setGlobalVariableId("global-score");
		update.setValue(MacroValue.literal(MacroValue.Type.NUMBER, "3.5"));
		key.steps().add(update);
		MacroStep.FunctionCall call = new MacroStep.FunctionCall("function-one");
		call.arguments().add(MacroValue.globalVariable(MacroValue.Type.NUMBER, "global-score"));
		key.steps().add(call);
		key.steps().add(new MacroStep.Chat("macro-last"));

		MacroScript world = new MacroScript("legacy-world", MacroScript.Trigger.WORLD_REGION);
		world.setCanvasPosition(320, 20);
		MacroWorldRegion region = world.worldRegion();
		region.place("garden-world", 11.5, 72.0, -4.25);
		region.setShape(MacroWorldRegion.Shape.RING);
		region.setSize(16);
		region.setInnerSize(4);
		region.setRepeatDelayMillis(2_500);
		region.setOncePerWorld(true);
		world.steps().add(new MacroStep.WorldSwitch(Island.GARDEN));

		MacroScript onCall = new MacroScript("legacy-call", MacroScript.Trigger.ON_CALL);
		onCall.setCanvasPosition(30, 110);
		onCall.steps().add(new MacroStep.MacroCall(4));
		macro.restoreScripts(List.of(chat, key, world, onCall));
		macro.detachedBlocks().add(new MacroStep.Comment("detached note"));
		return macro;
	}

	private static MacroDefinition createSecondMacro() {
		MacroDefinition macro = new MacroDefinition(4);
		macro.setName("Second Plan");
		macro.steps().add(new MacroStep.Chat("second-macro"));
		return macro;
	}

	private static MacroFunction createFirstFunction() {
		MacroFunction function = new MacroFunction("function-one");
		function.setName("First Function");
		function.parameters().add(new MacroFunction.Parameter("amount", MacroValue.Type.NUMBER, "6.25"));
		function.steps().add(new MacroStep.Chat("function-first"));
		MacroStep.UpdateVariable update = new MacroStep.UpdateVariable();
		update.setOperation(MacroStep.UpdateVariable.Operation.ADD);
		update.setName("amount");
		update.setGlobalVariableId("global-score");
		update.setAmount(1.25);
		function.steps().add(update);
		function.steps().add(new MacroStep.Chat("function-last"));
		return function;
	}

	private static MacroFunction createSecondFunction() {
		MacroFunction function = new MacroFunction("function-two");
		function.setName("Second Function");
		function.steps().add(new MacroStep.Chat("second-function"));
		return function;
	}

	private static ModConfig.MacroData macroRecord(MacroDefinition macro) {
		ModConfig.MacroData saved = new ModConfig.MacroData();
		saved.id = macro.id();
		saved.folderId = macro.folderId();
		saved.name = macro.name();
		saved.enabled = macro.enabled();
		saved.key = macro.keybind().isBound() ? macro.keybind().key().getName() : null;
		saved.modifiers = macro.keybind().modifiers();
		saved.triggerContext = macro.triggerContext().name();
		saved.islandRestricted = macro.islandRestricted();
		saved.islands = new ArrayList<>();
		for (Island island : macro.islands()) saved.islands.add(island.name());
		saved.steps = MacroStepConfigCodec.encode(macro.steps());
		saved.scripts = MacroScriptConfigCodec.encode(macro.scripts());
		saved.detachedBlocks = MacroStepConfigCodec.encode(macro.detachedBlocks());
		saved.canvasPanX = macro.canvasPanX();
		saved.canvasPanY = macro.canvasPanY();
		saved.canvasZoom = macro.canvasZoom();
		saved.canvasZoomVersion = 2;
		return saved;
	}

	private static ModConfig.MacroFunctionData functionRecord(MacroFunction function) {
		ModConfig.MacroFunctionData saved = new ModConfig.MacroFunctionData();
		saved.id = function.id();
		saved.name = function.name();
		saved.parameters = new ArrayList<>();
		for (MacroFunction.Parameter parameter : function.parameters()) {
			ModConfig.MacroFunctionParameterData savedParameter = new ModConfig.MacroFunctionParameterData();
			savedParameter.name = parameter.name();
			savedParameter.type = parameter.type().name();
			savedParameter.defaultValue = parameter.defaultValue();
			saved.parameters.add(savedParameter);
		}
		saved.steps = MacroStepConfigCodec.encode(function.steps());
		return saved;
	}

	private static MacroScript findScript(MacroDefinition macro, MacroScript.Trigger trigger) {
		for (MacroScript script : macro.scripts()) if (script.trigger() == trigger) return script;
		throw new AssertionError("missing macro trigger " + trigger);
	}

	private static List<String> strings(JsonArray array) {
		List<String> result = new ArrayList<>();
		for (var item : array) result.add(item.getAsString());
		return result;
	}

	private static Object configCore() throws ReflectiveOperationException {
		Class<?> coreType = Class.forName("geiler.addons.client.config.ModConfigCore");
		Field instance = coreType.getField("INSTANCE");
		return instance.get(null);
	}

	private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments)
		throws ReflectiveOperationException {
		Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		try {
			return method.invoke(target, arguments);
		} catch (InvocationTargetException error) {
			Throwable cause = error.getCause();
			if (cause instanceof ReflectiveOperationException reflection) throw reflection;
			throw new AssertionError("Macro config helper " + name + " failed.", cause);
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}

/** Small path helper keeps all test-generated paths rooted in the isolated config tree. */
final class FilesSupport {
	private FilesSupport() { }

	static Path child(Path root, String first, String second) {
		return root.resolve(first).resolve(second);
	}
}
