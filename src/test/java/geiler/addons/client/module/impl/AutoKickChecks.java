package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.TextSetting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Focused parity and safety checks for Auto Kick's Java-facing Kotlin API. */
public final class AutoKickChecks {
	private AutoKickChecks() {
	}

	public static void main(String[] arguments) {
		checkRules();
		checkModuleSettings();
	}

	private static void checkRules() {
		check(AutoKickRules.gearOutcome(false, false, false) == AutoKickRules.GearOutcome.PASS,
			"disabled gear requirement passes without profile data");
		check(AutoKickRules.gearOutcome(true, false, false) == AutoKickRules.GearOutcome.UNKNOWN,
			"unavailable gear evidence remains unknown");
		check(AutoKickRules.gearOutcome(true, false, true) == AutoKickRules.GearOutcome.UNKNOWN,
			"partial profile text cannot turn unavailable gear data into a pass");
		check(AutoKickRules.gearOutcome(true, true, true) == AutoKickRules.GearOutcome.PASS,
			"verified owned gear passes");
		check(AutoKickRules.gearOutcome(true, true, false) == AutoKickRules.GearOutcome.FAIL,
			"complete data confirms missing gear as a failure");
		check(!AutoKickRules.canApplyAction(
			AutoKickRules.gearOutcome(true, false, false) == AutoKickRules.GearOutcome.UNKNOWN,
			AutoKickRules.gearOutcome(true, false, false) == AutoKickRules.GearOutcome.FAIL),
			"unavailable gear evidence cannot authorize an action");
		check(AutoKickRules.canApplyAction(
			AutoKickRules.gearOutcome(true, true, false) == AutoKickRules.GearOutcome.UNKNOWN,
			AutoKickRules.gearOutcome(true, true, false) == AutoKickRules.GearOutcome.FAIL),
			"confirmed gear absence is an actionable failure when no data is unknown");
		check(!AutoKickRules.canApplyAction(true, true) && AutoKickRules.canApplyAction(false, true)
			&& !AutoKickRules.canApplyAction(false, false), "unknown data never authorizes enforcement");
		List<String> mixedUnknowns = List.of("Party classes", "Cata", "Cata");
		check(AutoKickRules.profileRefreshableUnknowns(mixedUnknowns).equals(List.of("Cata"))
			&& AutoKickRules.needsProfileRefresh(false, mixedUnknowns),
			"refresh retries only include distinct fields answerable by the target profile");
		check(AutoKickRules.profileRefreshableUnknowns(List.of("Party classes")).isEmpty()
			&& !AutoKickRules.needsProfileRefresh(false, List.of("Party classes"))
			&& AutoKickRules.needsProfileRefresh(true, List.of("Party classes")),
			"roster-only unknowns wait for updates, while incomplete profiles still refresh");
		List<String> nullableUnknowns = Arrays.asList(null, "Cata", "Party classes", null);
		check(AutoKickRules.profileRefreshableUnknowns(nullableUnknowns).equals(List.of("Cata"))
			&& AutoKickRules.profileRefreshableUnknowns(null).isEmpty(),
			"profile retry filtering ignores null entries and a missing unknown list");
		check(AutoKickRules.personalBestPasses(59, 60) && AutoKickRules.personalBestPasses(60, 60)
			&& !AutoKickRules.personalBestPasses(61, 60) && !AutoKickRules.personalBestPasses(0, 60),
			"personal-best bounds are preserved");
		check(AutoKickRules.parsePersonalBestLimitSeconds("6:42") == 402
			&& AutoKickRules.parsePersonalBestLimitSeconds("402") == 402
			&& AutoKickRules.parsePersonalBestLimitSeconds("6:60") == 0,
			"personal-best input retains m:ss and legacy seconds formats");
		String nonBreakingSpace = "\u00A0";
		check(AutoKickRules.parsePersonalBestLimitSeconds(nonBreakingSpace + "6:42" + nonBreakingSpace) == 0
			&& AutoKickRules.parsePersonalBestLimitSeconds("6:" + nonBreakingSpace + "42") == 0,
			"personal-best parsing retains Java trim semantics for Unicode spaces");
	}

	private static void checkModuleSettings() {
		AutoKickModule module = AutoKickModule.INSTANCE;
		check(module.name().equals("Auto Kick") && module.description().equals("Checks Party Finder requirements.")
			&& module.category() == Category.F7, "module identity is preserved");

		List<DungeonFloor> floors = new ArrayList<>();
		for (DungeonFloor floor : DungeonFloor.values()) {
			if (floor.number() > 0) floors.add(floor);
		}
		check(floors.size() == 14, "all F1-F7 and M1-M7 policies are registered");

		List<BooleanSetting> booleans = module.booleanSettings();
		List<TextSetting> texts = module.textSettings();
		check(booleans.size() == 1 + floors.size() * 6, "boolean setting count is preserved");
		check(texts.size() == floors.size() * 8, "text setting count is preserved");
		check(booleans.getFirst().name().equals("Debug") && !booleans.getFirst().rawValue(),
			"diagnostics setting keeps its ID and default");

		String[] booleanKeys = {"Auto Kick", "Ask Before", "Dupe", "Terminator", "Hyperion", "GDrag"};
		boolean[] booleanDefaults = {false, true, false, false, false, false};
		String[] textKeys = {"Cata", "Class", "Class Avg", "Secrets", "Secret Avg", "MP", "Minimum PB", "Bank"};
		String[] textLabels = {"Min Cata Level", "Min Class Level", "Min Class Avg", "Min Secrets",
			"Min Secret Avg", "Min MP", "PB limit (m:ss or sec; 0 off)", "Min Bank"};
		for (int index = 0; index < floors.size(); index++) {
			DungeonFloor floor = floors.get(index);
			String prefix = floor.displayName() + " ";
			for (int setting = 0; setting < booleanKeys.length; setting++) {
				BooleanSetting actual = booleans.get(1 + index * booleanKeys.length + setting);
				check(actual.name().equals(prefix + booleanKeys[setting])
					&& actual.displayName().equals(booleanKeys[setting])
					&& actual.rawValue() == booleanDefaults[setting],
					floor.displayName() + " boolean setting " + booleanKeys[setting] + " retains ID, label, and default");
			}
			for (int setting = 0; setting < textKeys.length; setting++) {
				TextSetting actual = texts.get(index * textKeys.length + setting);
				check(actual.name().equals(prefix + textKeys[setting])
					&& actual.displayName().equals(textLabels[setting])
					&& actual.value().isEmpty(),
					floor.displayName() + " text setting " + textKeys[setting] + " retains ID, label, and default");
			}
		}

		List<NumberSetting> numbers = module.numberSettings();
		check(numbers.size() == 2, "profile retry settings remain the only numeric settings");
		NumberSetting window = numbers.get(0);
		NumberSetting interval = numbers.get(1);
		check(window.name().equals("Profile Retry Window") && window.displayName().equals("Retry Window (s)")
			&& window.min() == 1f && window.max() == 60f && window.value() == 15f && window.isInteger(),
			"profile retry window retains its ID, label, bounds, and default");
		check(interval.name().equals("Profile Retry Interval") && interval.displayName().equals("Retry Every (s)")
			&& interval.min() == 1f && interval.max() == 10f && interval.value() == 2f && interval.isInteger(),
			"profile retry interval retains its ID, label, bounds, and default");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
