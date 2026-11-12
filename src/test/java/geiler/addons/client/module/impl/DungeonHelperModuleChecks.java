package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonContextTracker;
import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonGuidePhaseOutputState;
import geiler.addons.client.dungeon.DungeonGuideSegments;
import geiler.addons.client.gui.DungeonFloorPickerScreen;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Direct parity checks for the Dungeon Guide module's Java-facing surface and settings. */
public final class DungeonHelperModuleChecks {
	private static final List<String> SHARED_PHASES = List.of(
		"ENTRY", "CLEAR", "BLOOD_OPEN", "BLOOD_CLEAR", "COMPLETE");

	private DungeonHelperModuleChecks() {
	}

	public static void run() {
		checkIdentityAndDefaults();
		checkSettingsAndGroups();
		checkPhaseOutputTree();
		checkActionsAndInterop();
		checkFloorPickerAbi();
	}

	private static void checkIdentityAndDefaults() {
		DungeonHelperModule module = DungeonHelperModule.INSTANCE;
		check(module == DungeonHelperModule.INSTANCE, "the module remains a single registered instance");
		check("Dungeon Guide".equals(module.name()), "the display and config IDs retain Dungeon Guide");
		check("Dungeon Helper".equals(module.configName()), "the stable persisted module ID remains unchanged");
		check(module.category() == Category.F7, "the module remains in F7");
		check(!module.defaultEnabled() && !module.isEnabled(), "fresh installs keep the feature disabled");
		check("One ordered route per floor with condition-driven guide steps and linked macros. Bind this module to open its editor."
			.equals(module.description()), "the module description remains stable");
		check("dungeon_guide_status".equals(module.id())
			&& "Dungeon Guide Status".equals(module.displayName()), "the movable HUD identity remains stable");

		module.setEnabled(true);
		check(module.isEnabled(), "enabling the module updates its active state");
		module.setEnabled(false);
		check(!module.isEnabled(), "disabling the module clears its active state");
	}

	private static void checkSettingsAndGroups() {
		DungeonHelperModule module = DungeonHelperModule.INSTANCE;
		List<BooleanSetting> booleans = module.booleanSettings();
		check(names(booleans).subList(0, 5).equals(List.of(
			"Show Guide Status", "Always Display All Route Steps", "Connect Guide Steps",
			"Draw Through Walls", "Debug Route Progress")), "boolean setting IDs and order remain stable");
		check(!booleans.get(0).rawValue() && !booleans.get(1).rawValue()
			&& booleans.get(2).rawValue() && booleans.get(3).rawValue() && !booleans.get(4).rawValue(),
			"HUD, route visibility, connector, depth, and debug defaults remain stable");

		List<NumberSetting> numbers = module.numberSettings();
		check(names(numbers).equals(List.of("Render Range", "Marker Scale", "Guide Ring Count",
			"Guide Ring Height", "Guide Ring Radius", "Guide Ring Speed", "Guide Ring Width")),
			"render and legacy ring settings keep their persisted IDs and order");
		check(number(numbers.get(0), 8, 128, 64, true)
			&& number(numbers.get(1), 0.25f, 4, 1, false)
			&& number(numbers.get(2), 1, 8, 3, true)
			&& number(numbers.get(3), 0.25f, 8, 2.5f, false)
			&& number(numbers.get(4), 0.25f, 8, 1.2f, false)
			&& number(numbers.get(5), 0.05f, 2, 0.35f, false)
			&& number(numbers.get(6), 0.5f, 5, 1.5f, false),
			"render and legacy ring bounds/defaults remain stable");

		for (int i = 2; i < numbers.size(); i++) {
			check(!module.isSettingVisible(numbers.get(i)), "legacy shared ring controls stay hidden in the module UI");
		}
		check(module.isSettingVisible(numbers.get(0)) && module.isSettingVisible(numbers.get(1)),
			"active render controls remain visible");

		List<SettingGroup> groups = module.groups();
		check(groups.size() == 2 && groups.get(0).name() == null
			&& "Phase Outputs".equals(groups.get(1).name()), "settings retain their two top-level groups");
		check(names(groups.get(0).settings()).equals(List.of(
			"Show Guide Status", "Always Display All Route Steps", "Connect Guide Steps",
			"Draw Through Walls", "Render Range", "Marker Scale", "Guide Ring Count",
			"Guide Ring Height", "Guide Ring Radius", "Guide Ring Speed", "Guide Ring Width",
			"Debug Route Progress")), "the general settings group keeps its exact contents and order");
	}

	private static void checkPhaseOutputTree() {
		DungeonHelperModule module = DungeonHelperModule.INSTANCE;
		SettingGroup root = module.groups().get(1);
		check(root.collapsedByDefault(), "the phase-output root stays folded by default");
		check(namesGroups(root.children()).equals(List.of("Clear", "Normal Mode", "Master Mode")),
			"phase outputs remain separated into Clear, Normal Mode, and Master Mode");

		SettingGroup clear = root.children().get(0);
		List<SettingGroup> clearPhases = clear.children();
		check(clearPhases.size() == SHARED_PHASES.size(), "all five shared phase output groups remain present");
		for (int i = 0; i < SHARED_PHASES.size(); i++) {
			String phase = SHARED_PHASES.get(i);
			SettingGroup group = clearPhases.get(i);
			check(group.collapsedByDefault()
				&& ("guide-phase-shared-" + phase.toLowerCase(java.util.Locale.ROOT)).equals(group.stateKey()),
				"shared phase group key and collapsed state remain stable for " + phase);
			check(names(group.settings()).equals(List.of(
				phase + " Title", phase + " HUD Hint", phase + " Chat", phase + " Sound")),
				"shared phase setting IDs remain stable for " + phase);
		}

		checkFloorGroups(root.children().get(1), false);
		checkFloorGroups(root.children().get(2), true);

		List<String> expectedOutputSettings = new ArrayList<>();
		for (String phase : SHARED_PHASES) appendOutputNames(expectedOutputSettings, phase);
		for (DungeonFloor floor : DungeonFloor.values()) {
			for (String phase : DungeonGuidePhaseOutputState.supportedPhaseIds(floor)) {
				if (SHARED_PHASES.contains(phase)) continue;
				appendOutputNames(expectedOutputSettings, floor.displayName() + "_" + phase);
			}
		}
		List<BooleanSetting> booleans = module.booleanSettings();
		check(names(booleans).subList(5, booleans.size()).equals(expectedOutputSettings),
			"flat persisted phase-output setting IDs retain their source-derived order");
		check(booleans.subList(5, booleans.size()).stream().noneMatch(BooleanSetting::rawValue),
			"phase output title, HUD, chat, and sound controls remain off by default");
	}

	private static void checkFloorGroups(SettingGroup modeGroup, boolean master) {
		List<SettingGroup> expectedFloors = java.util.Arrays.stream(DungeonFloor.values())
			.filter(floor -> floor.master() == master)
			.filter(floor -> DungeonGuidePhaseOutputState.supportedPhaseIds(floor).stream()
				.anyMatch(phase -> !SHARED_PHASES.contains(phase)))
			.map(floor -> new SettingGroup(floor.displayName()))
			.toList();
		check(namesGroups(modeGroup.children()).equals(namesGroups(expectedFloors)),
			"" + modeGroup.name() + " retains all floor-specific groups in enum order");

		for (int i = 0; i < modeGroup.children().size(); i++) {
			DungeonFloor floor = DungeonFloor.parse(modeGroup.children().get(i).name());
			check(floor != null && floor.master() == master, "floor group remains in the correct mode");
			List<String> expectedPhases = DungeonGuidePhaseOutputState.supportedPhaseIds(floor).stream()
				.filter(phase -> !SHARED_PHASES.contains(phase))
				.toList();
			List<SettingGroup> groups = modeGroup.children().get(i).children();
			check(groups.size() == expectedPhases.size(), "every supported floor phase retains a UI group");
			for (int phaseIndex = 0; phaseIndex < expectedPhases.size(); phaseIndex++) {
				String phase = expectedPhases.get(phaseIndex);
				SettingGroup group = groups.get(phaseIndex);
				String stableKey = "guide-phase-" + floor.displayName().toLowerCase(java.util.Locale.ROOT)
					+ "-" + phase.toLowerCase(java.util.Locale.ROOT);
				String label = DungeonGuideSegments.label(floor, phase);
				check(group.collapsedByDefault() && stableKey.equals(group.stateKey())
					&& label.equals(group.name()), "floor phase labels and UI state keys remain stable");
				String stableId = floor.displayName() + "_" + phase;
				check(names(group.settings()).equals(List.of(
					stableId + " Title", stableId + " HUD Hint", stableId + " Chat", stableId + " Sound")),
					"floor phase setting IDs remain stable");
			}
		}
	}

	private static void checkActionsAndInterop() {
		DungeonHelperModule module = DungeonHelperModule.INSTANCE;
		check(module.actions().stream().map(ModuleAction::label).toList().equals(List.of(
			"Choose Dungeon Floor", "Edit Dungeon Guide", "Edit Guide Stages",
			"Enable All Phase Titles", "Disable All Phase Outputs")),
			"floor, route editor, stage editor, and bulk phase-output actions are registered in order");
		var titles = module.booleanSettings().stream().filter(s -> s.name().endsWith(" Title")).toList();
		var outputs = module.booleanSettings().stream().filter(s -> s.name().endsWith(" Title")
			|| s.name().endsWith(" HUD Hint") || s.name().endsWith(" Chat") || s.name().endsWith(" Sound")).toList();
		check(titles.size() > 20 && outputs.size() == titles.size() * 4, "phase outputs come in title/hud/chat/sound sets");
		boolean[] before = new boolean[outputs.size()];
		for (int i = 0; i < before.length; i++) before[i] = outputs.get(i).rawValue();
		try {
			module.actions().get(3).onClick().run();
			check(titles.stream().allMatch(s -> s.rawValue()),
				"Enable All Phase Titles switches every phase title on");
			module.actions().get(4).onClick().run();
			check(outputs.stream().noneMatch(s -> s.rawValue()),
				"Disable All Phase Outputs switches every phase output off");
		} finally {
			for (int i = 0; i < before.length; i++) outputs.get(i).setValue(before[i]);
		}
		check("ENTRY".equals(module.activePhase()) && !module.phaseIsManual(),
			"the route runner starts in automatic ENTRY phase");
		check(module.availablePhases().isEmpty() && !module.setPhase("ENTRY"),
			"phase selection remains gated until a dungeon floor has been established");
		check(module.guideRingCountSetting() == module.numberSettings().get(2)
			&& module.guideRingHeightSetting() == module.numberSettings().get(3)
			&& module.guideRingRadiusSetting() == module.numberSettings().get(4)
			&& module.guideRingSpeedSetting() == module.numberSettings().get(5)
			&& module.guideRingWidthSetting() == module.numberSettings().get(6),
			"the route editor's Java-facing shared-ring accessors preserve setting identity");
		check(module.inactiveReason() == null, "a disabled module does not report an inactive reason");
	}

	private static void checkFloorPickerAbi() {
		try {
			check(DungeonFloorPickerScreen.class.getConstructor(Screen.class).getParameterCount() == 1,
				"the manual floor picker retains its public Java Screen constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("the manual floor picker must keep its public Java Screen constructor", exception);
		}
	}

	private static void appendOutputNames(List<String> names, String stableKey) {
		names.add(stableKey + " Title");
		names.add(stableKey + " HUD Hint");
		names.add(stableKey + " Chat");
		names.add(stableKey + " Sound");
	}

	private static List<String> names(List<? extends Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static List<String> namesGroups(List<SettingGroup> groups) {
		return groups.stream().map(SettingGroup::name).toList();
	}

	private static boolean number(NumberSetting setting, float min, float max, float value, boolean integer) {
		return setting.min() == min && setting.max() == max && setting.value() == value && setting.isInteger() == integer;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
