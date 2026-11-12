package geiler.addons.client.gui;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;

import java.util.List;

/** Small settings sections must not become collapsible headings (the "category slop" rule). */
public final class ClickGuiGroupFlatteningChecks {
	private ClickGuiGroupFlatteningChecks() { }

	public static void run() {
		SettingGroup two = new SettingGroup("Two", color("a"), color("b"));
		SettingGroup three = new SettingGroup("Three", color("c"), color("d"), color("e"));
		Setting[] many = new Setting[5];
		for (int i = 0; i < many.length; i++) many[i] = color("m" + i);
		SettingGroup five = new SettingGroup("Five", many);
		SettingGroup switched = SettingGroup.switched("Switched", new BooleanSetting("On", true), color("s"));
		SettingGroup nested = new SettingGroup("Nested", color("n")).containing(two);

		List<SettingGroup> loose = ClickGuiSettingsLayout.flattenSmallGroups(List.of(two, three, five, two));
		check(loose.size() == 3, "adjacent small sections merge into one headerless block, got " + loose.size());
		check(loose.get(0).name() == null && loose.get(0).settings().size() == 5, "merged block is nameless with 5 rows");
		check("Five".equals(loose.get(1).name()), "a section above the limit keeps its heading");
		check(loose.get(2).name() == null && loose.get(2).settings().size() == 2, "a later small section is flattened too");

		List<SettingGroup> kept = ClickGuiSettingsLayout.flattenSmallGroups(List.of(switched, nested, five));
		check(kept.get(0) == switched, "switched sections keep their toggle heading");
		check(kept.get(1) == nested, "sections with children keep their heading");

		List<SettingGroup> tiny = ClickGuiSettingsLayout.flattenSmallGroups(List.of(five, new SettingGroup("Extra", color("x"), color("y"), color("z"))));
		check(tiny.size() == 1 && tiny.get(0).name() == null && tiny.get(0).settings().size() == 8,
			"a module with at most 8 plain settings shows no headings at all");
	}

	private static Setting color(String name) {
		return new ColorSetting(name, 255, 255, 255, 255);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
