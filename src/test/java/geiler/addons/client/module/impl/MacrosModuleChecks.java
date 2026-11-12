package geiler.addons.client.module.impl;

import geiler.addons.client.location.Island;
import geiler.addons.client.macro.MacroDefinition;
import geiler.addons.client.macro.MacroFunction;
import geiler.addons.client.macro.MacroStep;
import geiler.addons.client.macro.MacroTransfer;
import geiler.addons.client.macro.MacroTriggerContext;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;

import java.util.ArrayList;
import java.util.List;

/** Direct checks for the registered Macros module contract and restored macro settings. */
public final class MacrosModuleChecks {
	private MacrosModuleChecks() { }

	public static void run() {
		MacrosModule module = MacrosModule.INSTANCE;
		List<MacroDefinition> previousMacros = new ArrayList<>(module.macros());
		List<MacroFunction> previousFunctions = new ArrayList<>(module.functions());
		try {
			module.restore(List.of());
			module.restoreFunctions(List.of());
			checkIdentityAndModuleSettings(module);
			checkGroupsAndNodeColors(module);
			checkRestoredMacroControls(module);
			checkCreateDeleteAndFunctionActions(module);
			checkEncodedImportAppendsWithoutReplacing(module);
			checkActivityGate(module);
		} finally {
			module.restore(previousMacros);
			module.restoreFunctions(previousFunctions);
		}
	}

	private static void checkIdentityAndModuleSettings(MacrosModule module) {
		check(module == MacrosModule.INSTANCE
			&& "Macros".equals(module.name())
			&& "Macros".equals(module.configName())
			&& "Build workflows from Minecraft inputs. Each node has its own optional delay and start context."
				.equals(module.description())
			&& module.category() == Category.MISCELLANEOUS
			&& !module.defaultEnabled(),
			"Macros retains its registered singleton, persisted identity, description, category, and disabled fresh-install default");

		List<BooleanSetting> toggles = module.booleanSettings();
		check(toggles.size() == 1
			&& "Enable Macro System".equals(toggles.get(0).name())
			&& toggles.get(0).rawValue(),
			"the master switch keeps its persisted setting name and enabled default");

		List<ModuleAction> actions = module.actions();
		check(actions.size() == 5
			&& labels(actions).equals(List.of(
				"Replay Last Blocked Macro",
				"Create Macro",
				"Copy Shared Macros",
				"Paste Macros",
				"Manage Folders")),
			"sharing is two inline actions (copy the macros marked Include In Share, paste from the clipboard); no pop-out window");
		check(module.sharedMacros().isEmpty(), "no macro is marked for sharing by default");
		check(module.settingTooltip(toggles.get(0)).contains("Master toggle"),
			"a module owns its setting tooltips instead of the Click GUI matching module names");
		check("Starts the chat-triggered macro stack that was most recently refused."
				.equals(actions.get(0).description())
			&& "Copies every macro whose Include In Share switch is on, with linked macros and functions."
				.equals(actions.get(2).description()),
			"the replay and transfer actions retain their help text");
	}

	private static void checkGroupsAndNodeColors(MacrosModule module) {
		List<SettingGroup> groups = module.groups();
		check(groups.size() == 3
			&& groups.get(0).settings().size() == 6
			&& "Macro Library".equals(groups.get(1).name())
			&& groups.get(1).collapsedByDefault()
			&& "macro-library".equals(groups.get(1).stateKey())
			&& "Macro Node Colors".equals(groups.get(2).name())
			&& groups.get(2).collapsedByDefault()
			&& "macro-node-colors".equals(groups.get(2).stateKey()),
			"module settings keep the master section, folded macro library, and keyed color section");

		String[] names = {
			"Node Color · Actions", "Node Color · Control", "Node Color · Player Input",
			"Node Color · Inventory", "Node Color · Display", "Node Color · Data",
			"Node Color · Reusable", "Node Color · World",
		};
		int[] colors = {
			0xFF4C97FF, 0xFFFFAB19, 0xFF0FBD8C, 0xFF7D77F5,
			0xFF2EB8D4, 0xFFE2C432, 0xFFFF6680, 0xFF668E3D,
		};
		List<ColorSetting> settings = module.colorSettings();
		check(settings.size() == names.length && groups.get(2).settings().size() == names.length,
			"the eight independently persisted node colors remain available");
		for (int index = 0; index < names.length; index++) {
			check(names[index].equals(settings.get(index).name())
				&& settings.get(index).argb() == colors[index],
				"node color setting name and default remain stable at index " + index);
		}
		check(module.actionsNodeColor() == colors[0]
			&& module.controlNodeColor() == colors[1]
			&& module.inputNodeColor() == colors[2]
			&& module.inventoryNodeColor() == colors[3]
			&& module.displayNodeColor() == colors[4]
			&& module.dataNodeColor() == colors[5]
			&& module.reusableNodeColor() == colors[6]
			&& module.worldNodeColor() == colors[7],
			"editor color accessors preserve their setting values");
	}

	private static void checkRestoredMacroControls(MacrosModule module) {
		MacroDefinition macro = new MacroDefinition(17);
		macro.setName("Saved Route");
		macro.setEnabled(false);
		macro.setTriggerContext(MacroTriggerContext.WORLD_AND_CONTAINER);
		macro.setIslandRestricted(false);
		macro.setIslands(List.of(Island.HUB));
		module.restore(List.of(macro));

		SettingGroup library = module.groups().get(1);
		check(library.children().size() == 1, "restored macros appear in the Macro Library");
		SettingGroup macroGroup = library.children().get(0);
		check("Macro 17 • Saved Route".equals(macroGroup.name())
			&& "macro-entry:17".equals(macroGroup.stateKey())
			&& macroGroup.settings().size() == 8,
			"each restored macro keeps its dynamic heading, stable fold key, settings, and actions");

		BooleanSetting enabled = (BooleanSetting) findSetting(macroGroup, "Macro Enabled");
		TextSetting name = (TextSetting) findSetting(macroGroup, "Macro Name");
		ChoiceSetting context = (ChoiceSetting) findSetting(macroGroup, "Trigger Context");
		BooleanSetting islandRestricted = (BooleanSetting) findSetting(macroGroup, "Island Filter");
		check(!enabled.value() && "Saved Route".equals(name.value())
			&& context.value().equals("World or container")
			&& context.choices().equals(List.of(
				"World only (no screen)",
				"Container/inventory screens",
				"World or container",
				"Any non-text screen"))
			&& !islandRestricted.value(),
			"restored macro metadata maps to the existing per-macro controls and context choices");

		SettingGroup islandGroup = macroGroup.children().get(0);
		check("Islands".equals(islandGroup.name())
			&& islandGroup.collapsedByDefault()
			&& "macro-islands:17".equals(islandGroup.stateKey())
			&& islandGroup.settings().size() == selectableIslandCount(),
			"island controls remain folded, individually keyed, and limited to selectable islands");
		BooleanSetting hub = (BooleanSetting) findSetting(islandGroup, Island.HUB.label());
		check(hub.value(), "the restored island set initializes the matching island toggle");

		enabled.setValue(true);
		name.setValue("Updated Route");
		context.setValue("Container/inventory screens");
		islandRestricted.setValue(true);
		hub.setValue(false);
		module.syncSettings();
		check(macro.enabled()
			&& "Updated Route".equals(macro.name())
			&& macro.triggerContext() == MacroTriggerContext.CONTAINER_ONLY
			&& macro.islandRestricted()
			&& macro.islands().isEmpty(),
			"module control changes synchronize back to the restored macro metadata");

		try {
			module.macros().add(new MacroDefinition(18));
			throw new AssertionError("the public macro list must remain immutable");
		} catch (UnsupportedOperationException expected) {
			// The Java module returned an immutable snapshot.
		}

		MacroFunction duplicate = new MacroFunction("stable-id");
		module.restoreFunctions(List.of(duplicate, duplicate));
		check(module.functions().size() == 1 && module.function("stable-id") == duplicate,
			"restored reusable functions retain the size bound and duplicate-ID filtering");
		try {
			module.functions().clear();
			throw new AssertionError("the public function list must remain immutable");
		} catch (UnsupportedOperationException expected) {
			// The Java module returned an immutable snapshot.
		}
	}

	private static void checkActivityGate(MacrosModule module) {
		BooleanSetting systemEnabled = module.booleanSettings().get(0);
		boolean previousSystemEnabled = systemEnabled.rawValue();
		boolean previousModuleEnabled = module.isEnabled();
		try {
			systemEnabled.setValue(true);
			module.setEnabled(true);
			check(module.isActive() && module.inactiveReason() == null,
				"the runner becomes active only through an enabled module and enabled macro-system setting");
			systemEnabled.setValue(false);
			check(!module.isActive()
				&& "Macro System is disabled.".equals(module.inactiveReason()),
				"the master switch stops activity and preserves the inactive explanation");
		} finally {
			systemEnabled.setValue(previousSystemEnabled);
			module.setEnabled(previousModuleEnabled);
		}
	}

	private static void checkCreateDeleteAndFunctionActions(MacrosModule module) {
		int macroId = unusedInventoryMacroId();
		module.restore(List.of(new MacroDefinition(macroId - 1)));
		module.restoreFunctions(List.of());

		ModuleAction createMacro = module.actions().stream()
			.filter(action -> "Create Macro".equals(action.label()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Create Macro action is missing"));
		createMacro.onClick().run();
		MacroDefinition created = module.macro(macroId);
		check(created != null && module.macros().size() == 2,
			"the Create Macro action creates and registers the next macro ID");

		SettingGroup library = module.groups().get(1);
		SettingGroup macroGroup = library.children().stream()
			.filter(group -> ("macro-entry:" + macroId).equals(group.stateKey()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("created macro settings are missing"));
		ModuleAction deleteMacro = null;
		for (Setting setting : macroGroup.settings()) {
			if (setting instanceof ModuleAction action && "Delete Macro".equals(action.label())) {
				deleteMacro = action;
				break;
			}
		}
		check(deleteMacro != null, "created macros expose a Delete Macro action");
		deleteMacro.onClick().run();
		check(module.macro(macroId) == null && module.macros().size() == 1,
			"the Delete Macro action removes the selected macro from the library");

		module.restore(List.of());
		MacroFunction function = module.createFunction();
		check(function != null && module.function(function.id()) == function && module.functions().size() == 1,
			"creating a reusable function registers it under its stable ID");
	}

	private static void checkEncodedImportAppendsWithoutReplacing(MacrosModule module) {
		List<MacroDefinition> previousMacros = new ArrayList<>(module.macros());
		List<MacroFunction> previousFunctions = new ArrayList<>(module.functions());
		try {
			MacroDefinition existing = new MacroDefinition(41);
			existing.setName("Existing macro");
			module.restore(List.of(existing));
			module.restoreFunctions(List.of());

			MacroDefinition source = new MacroDefinition(7);
			source.setName("Imported macro");
			source.steps().add(new MacroStep.Chat("imported output"));
			MacroTransfer.ImportResult result = module.importEncoded(MacroTransfer.encode(List.of(source)));
			check(result.success() && result.macros().size() == 1,
				"the registered module accepts a valid encoded macro package");

			MacroDefinition imported = module.macro(42);
			check(module.macros().size() == 2 && module.macro(41) == existing
				&& imported != null && "Imported macro".equals(imported.name())
				&& imported.steps().getFirst() instanceof MacroStep.Chat chat
				&& "imported output".equals(chat.message()),
				"module import appends the macro at the next fresh ID, preserves its step, and keeps existing entries");
		} finally {
			module.restore(previousMacros);
			module.restoreFunctions(previousFunctions);
		}
	}

	private static int unusedInventoryMacroId() {
		int candidate = 1_000_000;
		while (candidate < Integer.MAX_VALUE) {
			boolean assigned = false;
			for (var placement : InventoryButtonsModule.INSTANCE.placements()) {
				if (placement.macroId() == candidate) {
					assigned = true;
					break;
				}
			}
			if (!assigned) return candidate;
			candidate++;
		}
		throw new AssertionError("No unused macro ID is available for the module check.");
	}

	private static int selectableIslandCount() {
		int count = 0;
		for (Island island : Island.values()) if (island.selectable()) count++;
		return count;
	}

	private static List<String> labels(List<ModuleAction> actions) {
		return actions.stream().map(ModuleAction::label).toList();
	}

	private static Setting findSetting(SettingGroup group, String name) {
		for (Setting setting : group.settings()) {
			if (name.equals(setting.name())) return setting;
		}
		throw new AssertionError("missing setting " + name + " from group " + group.name());
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
