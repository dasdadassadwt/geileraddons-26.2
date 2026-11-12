package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.ChoiceSetting;

import java.util.List;
import java.util.Map;

/** Checks for the Dungeon Mob ESP split into Mob, Boss and Class ESP modules. */
public final class DungeonEspSplitChecks {
	private DungeonEspSplitChecks() { }

	public static void run() {
		checkBossModule();
		checkClassModule();
		checkFillAndTitle();
		checkMigration();
		checkMobEspKeepsOnlyItsOwnSettings();
	}

	private static void checkBossModule() {
		DungeonBossEspModule module = DungeonBossEspModule.INSTANCE;
		check(module.name().equals("Dungeon Boss ESP") && module.configName().equals("Dungeon Boss ESP")
			&& module.category() == Category.F7 && !module.defaultEnabled() && module.hasSettings(),
			"Boss ESP is a disabled-by-default Dungeons module with a stable config key");
		check(names(module.booleanSettings()).equals(List.of("F7 Wither Boss ESP", "Known Mimic Chest ESP", "Depth Check"))
			&& module.booleanSettings().stream().map(BooleanSetting::rawValue).toList().equals(List.of(true, true, false)),
			"Boss ESP boolean settings keep the old names and defaults");
		check(names(module.numberSettings()).equals(List.of("Highlight Range")),
			"Boss ESP keeps one shared range");
		NumberSetting range = module.numberSettings().getFirst();
		check(Float.compare(range.min(), 8f) == 0 && Float.compare(range.max(), 128f) == 0
			&& Float.compare(range.value(), 64f) == 0 && range.isInteger(), "Boss ESP range bounds and default are unchanged");
		check(module.colorSettings().stream().map(setting -> setting.name() + "=" + setting.hex()).toList().equals(List.of(
			"Mimic Chest Fill=D11D05A0", "Mimic Chest Outline=D11D05FF", "Special Mob Fill=FF502330",
			"Special Mob Outline=FF5023FF")), "Boss ESP colors keep the old names and defaults");
		List<SettingGroup> groups = module.groups();
		check(groups.size() == 3 && "Wither Bosses".equals(groups.get(0).name()) && "Mimics".equals(groups.get(1).name()),
			"Boss ESP shows Wither Bosses, Mimics and a visibility row");
	}

	private static void checkClassModule() {
		DungeonClassEspModule module = DungeonClassEspModule.INSTANCE;
		check(module.name().equals("Dungeon Class ESP") && module.configName().equals("Dungeon Class ESP")
			&& module.category() == Category.F7 && !module.defaultEnabled() && module.hasSettings(),
			"Class ESP is a disabled-by-default Dungeons module with a stable config key");
		check(names(module.booleanSettings()).equals(List.of(
			"Depth Check", "Fill By Class Color", "Class Titles", "Show Class In Title"))
			&& module.booleanSettings().stream().map(BooleanSetting::rawValue).toList()
				.equals(List.of(false, true, true, true)),
			"Class ESP booleans: class-colored fill, titles and the class letter on by default");
		check(names(module.numberSettings()).equals(List.of("Highlight Range", "Class Fill Opacity", "Title Scale", "Title Height")),
			"Class ESP number settings");
		check(Float.compare(number(module, "Title Height").value(), 3.25f) == 0
			&& Float.compare(number(module, "Title Scale").value(), 1.0f) == 0, "titles float 3.25 blocks above the feet, like NoFrills");
		check(module.colorSettings().stream().map(setting -> setting.name() + "=" + setting.hex()).toList()
			.equals(List.of("Party Class Fill=00000018")), "Class ESP keeps the old custom fill as its fallback color");
		check(module.actions().size() == 1 && module.actions().getFirst().label().equals("Edit Class Colors"),
			"Class ESP links to the shared class color settings");
		check(module.textSettings().isEmpty() && module.choiceSettings().isEmpty(), "Class ESP has no text or choice settings");
		NumberSetting opacity = number(module, "Class Fill Opacity");
		BooleanSetting byClass = bool(module, "Fill By Class Color");
		try {
			check(module.isSettingVisible(opacity) && !module.isSettingVisible(color(module, "Party Class Fill")),
				"class-colored fill shows opacity and hides the custom color");
			byClass.setValue(false);
			check(!module.isSettingVisible(opacity) && module.isSettingVisible(color(module, "Party Class Fill")),
				"custom fill shows the color and hides opacity");
			BooleanSetting titles = bool(module, "Class Titles");
			titles.setValue(false);
			check(!module.isSettingVisible(bool(module, "Show Class In Title"))
				&& !module.isSettingVisible(number(module, "Title Scale"))
				&& !module.isSettingVisible(number(module, "Title Height")), "title options follow the titles switch");
		} finally {
			module.booleanSettings().forEach(BooleanSetting::reset);
		}
	}

	private static void checkFillAndTitle() {
		check(DungeonClassEspModule.classFillArgb(0xFF55FFFF, 20) == 0x3355FFFF,
			"class fill keeps the class RGB and applies the opacity percent");
		check(DungeonClassEspModule.classFillArgb(0xFF00AA00, 0) == 0x0000AA00
			&& DungeonClassEspModule.classFillArgb(0xFF00AA00, 100) == 0xFF00AA00
			&& DungeonClassEspModule.classFillArgb(0xFF00AA00, 250) == 0xFF00AA00
			&& DungeonClassEspModule.classFillArgb(0xFF00AA00, -5) == 0x0000AA00,
			"class fill opacity is clamped to 0..100 percent");
		check(DungeonClassEspModule.titleText("Steve", "Healer", false).equals("Steve")
			&& DungeonClassEspModule.titleText("Steve", "Healer", true).equals("§e[H]§r Steve")
			&& DungeonClassEspModule.titleText("Steve", "Berserk", true).equals("§e[B]§r Steve")
			&& DungeonClassEspModule.titleText("Steve", "", true).equals("Steve"),
			"title text is NoFrills' yellow [class letter] followed by the name, which keeps the class color");
	}

	private static void checkMigration() {
		check(DungeonEspSplitMigration.bossEnabled(true, null, null) == Boolean.TRUE
			&& DungeonEspSplitMigration.bossEnabled(true, false, true) == Boolean.TRUE
			&& DungeonEspSplitMigration.bossEnabled(true, false, false) == Boolean.FALSE
			&& DungeonEspSplitMigration.bossEnabled(false, true, true) == Boolean.FALSE
			&& DungeonEspSplitMigration.bossEnabled(null, true, true) == null,
			"Boss ESP starts enabled only when the old module was on and a boss/mimic switch was on");
		check(DungeonEspSplitMigration.classEnabled(true, true) == Boolean.TRUE
			&& DungeonEspSplitMigration.classEnabled(true, null) == Boolean.FALSE
			&& DungeonEspSplitMigration.classEnabled(true, false) == Boolean.FALSE
			&& DungeonEspSplitMigration.classEnabled(false, true) == Boolean.FALSE
			&& DungeonEspSplitMigration.classEnabled(null, true) == null,
			"Class ESP starts enabled only when the old module and Party Only Class Highlight were on");

		DungeonBossEspModule boss = DungeonBossEspModule.INSTANCE;
		DungeonClassEspModule classEsp = DungeonClassEspModule.INSTANCE;
		try {
			boss.restoreLegacySettings(
				Map.of("Dungeon Mob ESP.Highlight Range", 100f),
				Map.of("Dungeon Mob ESP.Known Mimic Chest ESP", false, "Dungeon Mob ESP.F7 Wither Boss ESP", false,
					"Dungeon Mob ESP.Depth Check", true),
				Map.of("Dungeon Mob ESP.Mimic Chest Fill", new int[] {1, 2, 3, 4}));
			check(bool(boss, "Known Mimic Chest ESP").rawValue() == false && bool(boss, "F7 Wither Boss ESP").rawValue() == false
				&& bool(boss, "Depth Check").rawValue() && number(boss, "Highlight Range").intValue() == 100
				&& color(boss, "Mimic Chest Fill").hex().equals("01020304"),
				"Boss ESP restores its old Dungeon Mob ESP values");
			classEsp.restoreLegacySettings(
				Map.of("Dungeon Mob ESP.Highlight Range", 90f),
				Map.of("Dungeon Mob ESP.Depth Check", true),
				Map.of("Dungeon Mob ESP.Party Class Fill", new int[] {5, 6, 7, 8}));
			check(bool(classEsp, "Depth Check").rawValue() && number(classEsp, "Highlight Range").intValue() == 90
				&& color(classEsp, "Party Class Fill").hex().equals("05060708"),
				"Class ESP restores its old Dungeon Mob ESP values");
			number(boss, "Highlight Range").setValue(50f);
			boss.restoreLegacySettings(Map.of("Dungeon Mob ESP.Highlight Range", 100f), Map.of(), Map.of());
			check(number(boss, "Highlight Range").intValue() == 100 || number(boss, "Highlight Range").intValue() == 50,
				"restoring twice never throws");
		} finally {
			resetAll(boss);
			resetAll(classEsp);
		}
	}

	private static void checkMobEspKeepsOnlyItsOwnSettings() {
		DungeonMobEspModule module = DungeonMobEspModule.INSTANCE;
		List<String> all = new java.util.ArrayList<>();
		module.booleanSettings().forEach(setting -> all.add(setting.name()));
		module.numberSettings().forEach(setting -> all.add(setting.name()));
		module.colorSettings().forEach(setting -> all.add(setting.name()));
		for (String moved : List.of("F7 Wither Boss ESP", "Known Mimic Chest ESP", "Party Only Class Highlight",
			"Highlight Range", "Mimic Chest Fill", "Mimic Chest Outline", "Special Mob Fill", "Special Mob Outline",
			"Party Class Fill")) {
			check(!all.contains(moved), "Dungeon Mob ESP no longer owns " + moved);
		}
		check(module.groups().stream().noneMatch(group -> "Wither Bosses".equals(group.name())
			|| "Mimics".equals(group.name()) || "Party Classes".equals(group.name())),
			"Dungeon Mob ESP no longer shows the boss, mimic and party groups");
	}

	private static void resetAll(Module module) {
		module.booleanSettings().forEach(BooleanSetting::reset);
		module.numberSettings().forEach(NumberSetting::reset);
		module.colorSettings().forEach(ColorSetting::reset);
		module.choiceSettings().forEach(ChoiceSetting::reset);
		module.textSettings().forEach(TextSetting::reset);
	}

	private static List<String> names(List<? extends Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static BooleanSetting bool(Module module, String name) {
		return module.booleanSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static NumberSetting number(Module module, String name) {
		return module.numberSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static ColorSetting color(Module module, String name) {
		return module.colorSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
