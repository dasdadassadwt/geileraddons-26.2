package geiler.addons.client.config;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.impl.DungeonPuzzleSolversModule;

import java.util.HashMap;
import java.util.Map;

/** Focused checks for the Puzzle Solvers module-state and legacy strategy migrations. */
public final class PuzzleSolversMigrationChecks {
	private PuzzleSolversMigrationChecks() { }

	public static void run() {
		DungeonPuzzleSolversModule puzzles = DungeonPuzzleSolversModule.INSTANCE;
		Map<String, Boolean> water = new HashMap<>();
		Map<String, String> choices = new HashMap<>();
		choices.put("Puzzle Solvers.Water Board Strategy", "STANDARD");
		choices.put("Dungeon Odin Helpers.Water Board Strategy", "OPTIMIZED");
		check(ModConfig.migratePuzzleStrategy(water, choices, puzzles, "Water Board Strategy",
			"Optimized Solutions", Map.of("STANDARD", false, "OPTIMIZED", true))
			&& Boolean.FALSE.equals(water.get("Puzzle Solvers.Optimized Solutions")),
			"the saved Puzzle Solvers strategy wins and STANDARD migrates to false");

		Map<String, Boolean> ice = new HashMap<>();
		Map<String, String> oldIce = Map.of("Dungeon Odin Helpers.Ice Fill Route", "HARD");
		check(ModConfig.migratePuzzleStrategy(ice, oldIce, puzzles, "Ice Fill Route",
			"Use Optimized Patterns", Map.of("EASY", false, "HARD", true))
			&& Boolean.TRUE.equals(ice.get("Puzzle Solvers.Use Optimized Patterns")),
			"HARD migrates to optimized Ice Fill patterns");

		Map<String, Boolean> explicit = new HashMap<>();
		explicit.put("Puzzle Solvers.Optimized Solutions", false);
		check(!ModConfig.migratePuzzleStrategy(explicit, choices, puzzles, "Water Board Strategy",
			"Optimized Solutions", Map.of("STANDARD", false, "OPTIMIZED", true))
			&& Boolean.FALSE.equals(explicit.get("Puzzle Solvers.Optimized Solutions")),
			"an explicit target choice is preserved over the legacy strategy");

		Map<String, Boolean> unknown = new HashMap<>();
		Map<String, String> unknownChoice = Map.of("Dungeon Odin Helpers.Ice Fill Route", "MEDIUM");
		check(!ModConfig.migratePuzzleStrategy(unknown, unknownChoice, puzzles, "Ice Fill Route",
			"Use Optimized Patterns", Map.of("EASY", false, "HARD", true))
			&& !unknown.containsKey("Puzzle Solvers.Use Optimized Patterns"),
			"unknown Ice Fill choices remain unmapped");

		checkRetainedSettingMigration(puzzles);
		checkCombinedDungeonFeatureMigration();

		Map<String, Boolean> legacyOn = new HashMap<>();
		legacyOn.put("Dungeon Odin Helpers", true);
		check(ModConfig.migrateLegacyModuleEnabledState(legacyOn, "Dungeon Odin Helpers", puzzles.configName())
			&& Boolean.TRUE.equals(legacyOn.get(puzzles.configName())),
			"a saved legacy parent-on state migrates when Puzzle Solvers has no saved choice");
		Map<String, Boolean> explicitOff = new HashMap<>();
		explicitOff.put("Dungeon Odin Helpers", true);
		explicitOff.put(puzzles.configName(), false);
		check(!ModConfig.migrateLegacyModuleEnabledState(explicitOff, "Dungeon Odin Helpers", puzzles.configName())
			&& Boolean.FALSE.equals(explicitOff.get(puzzles.configName())),
			"an explicit Puzzle Solvers off state wins over the legacy parent");
	}

	private static void checkRetainedSettingMigration(DungeonPuzzleSolversModule puzzles) {
		String legacyPrefix = "Dungeon Odin Helpers.";
		String targetPrefix = puzzles.configName() + ".";
		Map<String, Boolean> toggles = new HashMap<>();
		Map<String, Float> numbers = new HashMap<>();
		Map<String, int[]> colors = new HashMap<>();
		Map<String, String> choices = new HashMap<>();
		Map<String, String> texts = new HashMap<>();
		for (BooleanSetting setting : puzzles.booleanSettings())
			toggles.put(legacyPrefix + setting.name(), true);
		for (NumberSetting setting : puzzles.numberSettings())
			numbers.put(legacyPrefix + setting.name(), setting.value());
		for (ColorSetting setting : puzzles.colorSettings())
			colors.put(legacyPrefix + setting.name(), new int[]{1, 2, 3, 255});
		for (ChoiceSetting setting : puzzles.choiceSettings())
			choices.put(legacyPrefix + setting.name(), setting.value());
		for (TextSetting setting : puzzles.textSettings())
			texts.put(legacyPrefix + setting.name(), setting.value());

		// A saved target value always wins over an older value of the same setting.
		toggles.put(targetPrefix + "Draft Prompt", false);
		// A value stored under the wrong typed map must not be migrated as a setting.
		toggles.put(legacyPrefix + "Ice Fill Color", true);

		check(ModConfigMigrations.migrateLegacyDungeonFeatureSettings(new HashMap<>(), toggles, numbers, colors,
			choices, texts), "the combined Kotlin migration copies same-named settings from typed maps");
		for (BooleanSetting setting : puzzles.booleanSettings()) {
			Boolean expected = "Draft Prompt".equals(setting.name()) ? false : true;
			check(expected.equals(toggles.get(targetPrefix + setting.name())),
				"legacy toggle migrates with target precedence: " + setting.name());
		}
		for (NumberSetting setting : puzzles.numberSettings())
			check(numbers.containsKey(targetPrefix + setting.name()), "legacy number migrates: " + setting.name());
		for (ColorSetting setting : puzzles.colorSettings())
			check(colors.containsKey(targetPrefix + setting.name()), "legacy color migrates: " + setting.name());
		for (ChoiceSetting setting : puzzles.choiceSettings())
			check(choices.containsKey(targetPrefix + setting.name()), "legacy choice migrates: " + setting.name());
		for (TextSetting setting : puzzles.textSettings())
			check(texts.containsKey(targetPrefix + setting.name()), "legacy text migrates: " + setting.name());
		check(!toggles.containsKey(targetPrefix + "Ice Fill Color"),
			"a toggle-map value is not copied into the Ice Fill Color setting");
	}

	private static void checkCombinedDungeonFeatureMigration() {
		var scorer = geiler.addons.client.module.impl.DungeonScorerModule.INSTANCE;
		var splits = geiler.addons.client.module.impl.DungeonRunSplitsModule.INSTANCE;
		var leap = geiler.addons.client.module.impl.SpiritLeapMenuModule.INSTANCE;
		var puzzles = DungeonPuzzleSolversModule.INSTANCE;
		var guide = geiler.addons.client.module.impl.DungeonHelperModule.INSTANCE;
		Map<String, Boolean> enabled = new HashMap<>();
		enabled.put("Dungeon Odin Helpers", true);
		enabled.put(puzzles.configName(), false);
		Map<String, Boolean> toggles = new HashMap<>();
		toggles.put("Dungeon Odin Helpers.Score Alerts 270/300", false);
		toggles.put(scorer.configName() + ".Score Alerts", true);
		toggles.put("Dungeon Odin Helpers.Score Alert Sound", true);
		toggles.put("Dungeon Odin Helpers.Show Tick Timing", true);
		Map<String, Float> numbers = new HashMap<>();
		numbers.put("Dungeon Odin Helpers.Score Alert Duration (Ticks)", 40f);
		Map<String, int[]> colors = new HashMap<>();
		Map<String, String> choices = new HashMap<>();
		choices.put("Dungeon Odin Helpers.Count Source", "SERVER_PING_PACKETS");
		choices.put("Dungeon Odin Helpers.Leap Sorting", "CLASS");
		Map<String, String> texts = new HashMap<>();
		texts.put("Dungeon Odin Helpers.Score Alert Text", "Score reached");
		texts.put("Dungeon Odin Helpers.Top Left Key", "Q");
		String guideTitleKey = guide.configName() + ".BLOOD_OPEN Title";
		toggles.put("Dungeon Guide Phase Outputs.BLOOD_OPEN Title", true);

		check(ModConfigMigrations.migrateLegacyDungeonFeatureSettings(enabled, toggles, numbers, colors, choices, texts),
			"the focused Kotlin dungeon migration reports migrated legacy values");
		check(Boolean.TRUE.equals(enabled.get(leap.configName())) && Boolean.FALSE.equals(enabled.get(puzzles.configName())),
			"legacy module enablement migrates only when a destination has no saved choice");
		check(Boolean.TRUE.equals(toggles.get(scorer.configName() + ".Score Alerts"))
			&& Boolean.TRUE.equals(toggles.get(scorer.configName() + ".Play Sound")),
			"legacy score-alert values migrate while current target values take precedence");
		check(numbers.get(scorer.configName() + ".Duration (Ticks)") == 40f
			&& "Score reached".equals(texts.get(scorer.configName() + ".Alert Text")),
			"legacy score duration and alert text preserve their typed-map destinations");
		check(Boolean.TRUE.equals(toggles.get(splits.configName() + ".Show Count Value"))
			&& Boolean.FALSE.equals(toggles.get(splits.configName() + ".Show Tick Time")),
			"server-packet count mode preserves count display without enabling the tick-time row");
		check("CLASS".equals(choices.get(leap.configName() + ".Sort Teammates"))
			&& "Q".equals(texts.get(leap.configName() + ".Top Left Key")),
			"legacy Spirit Leap sorting and key settings migrate unchanged");
		check(Boolean.TRUE.equals(toggles.get(guideTitleKey)),
			"shared phase-output settings migrate from their legacy phase key");
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
