package geiler.addons.client.module.impl;

import geiler.addons.client.hunting.CritterSpecies;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;

import java.util.ArrayList;
import java.util.List;

/** Focused parity checks for Sparkling Critter identity, persisted settings, and pure range rules. */
public final class SparklingCritterModuleChecks {
	private SparklingCritterModuleChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkIdentityAndSettings();
		checkSparklingLabelRecognition();
		checkProductionOutputFormatting();
		checkAnnouncementRangeRules();
		System.out.println("Sparkling Critter module checks passed");
	}

	private static void checkSparklingLabelRecognition() {
		if (CritterSpecies.count() != 37) throw new AssertionError("Expected all 37 Safari species");
		assertSpecies("Rockmite", CritterSpecies.sparkling("\u00a76sPaRkLiNg \u00a7bRoCkMiTe"),
			"formatting and case-insensitive sparkling label");
		assertSpecies(null, CritterSpecies.sparkling("Rockmite"), "non-sparkling species label");
		assertSpecies(null, CritterSpecies.sparkling("Sparkling"), "bare Sparkling label");
		assertSpecies(null, CritterSpecies.sparkling("Sparkling Tepid Shard"),
			"label that only contains a species name");
	}

	private static void checkProductionOutputFormatting() {
		String message = SparklingCritterDecisions.announcementMessage("Rockmite", -12, 64, 127);
		if (!message.equals("Sparkling Rockmite found at x:-12 y:64 z:127")
			|| !SparklingCritterDecisions.partyCommand(message)
				.equals("pc Sparkling Rockmite found at x:-12 y:64 z:127")
			|| !SparklingCritterDecisions.hudLabel("Rockmite").equals("Sparkling Rockmite")) {
			throw new AssertionError("Sparkling Critter party command or projected label output changed");
		}
	}

	private static void checkIdentityAndSettings() {
		SparklingCritterModule module = SparklingCritterModule.INSTANCE;
		if (module != SparklingCritterModule.INSTANCE
			|| !module.name().equals("Sparkling Critter")
			|| !module.configName().equals("Sparkling Critter")
			|| !module.description().equals("Finds sparkling critters in the Critter Safari.")
			|| module.category() != Category.HUNTING
			|| module.defaultEnabled()
			|| module.isEnabled()) {
			throw new AssertionError("Sparkling Critter identity or fresh-install state changed");
		}

		List<ColorSetting> colors = module.colorSettings();
		if (colors.size() != 1) throw new AssertionError("Expected exactly one color setting");
		ColorSetting color = colors.get(0);
		if (!color.name().equals("Color") || color.red() != 255 || color.green() != 200
			|| color.blue() != 0 || color.alpha() != 255 || color.argb() != 0xFFFFC800) {
			throw new AssertionError("Color identity or default changed");
		}

		List<NumberSetting> numbers = module.numberSettings();
		assertNames(numbers, List.of("Text Size", "Rescan Time"), "number setting IDs");
		if (!close(numbers.get(0).min(), 0.5f) || !close(numbers.get(0).max(), 4.0f)
			|| !close(numbers.get(0).value(), 1.0f) || numbers.get(0).isInteger()) {
			throw new AssertionError("Text Size bounds, default, or integer mode changed");
		}
		if (!close(numbers.get(1).min(), 1.0f) || !close(numbers.get(1).max(), 200.0f)
			|| !close(numbers.get(1).value(), 20.0f) || !numbers.get(1).isInteger()) {
			throw new AssertionError("Rescan Time bounds, default, or integer mode changed");
		}

		List<BooleanSetting> booleans = module.booleanSettings();
		assertNames(booleans, List.of("Tracer", "Debug", "Announce to Party Chat"), "boolean setting IDs");
		if (!booleans.get(0).rawValue() || !booleans.get(0).value() || booleans.get(0).isDebugOnly()
			|| booleans.get(1).rawValue() || booleans.get(1).value() || !booleans.get(1).isDebugOnly()
			|| booleans.get(2).rawValue() || booleans.get(2).value() || booleans.get(2).isDebugOnly()) {
			throw new AssertionError("Debug or party announcement defaults/visibility changed");
		}

		List<TextSetting> texts = module.textSettings();
		if (texts.size() != 1 || !texts.get(0).name().equals("Range to Send")
			|| !texts.get(0).value().equals("5") || texts.get(0).maxLength() != 6) {
			throw new AssertionError("Range to Send identity, default, or maximum length changed");
		}

		List<SettingGroup> groups = module.groups();
		if (!groups.stream().map(SettingGroup::name).toList().equals(List.of("Highlight", "Announcements"))) {
			throw new AssertionError("Setting group names or order changed");
		}
		assertGroup(groups.get(0), List.of("Color", "Text Size", "Tracer", "Rescan Time"), "Highlight");
		assertGroup(groups.get(1), List.of("Debug", "Announce to Party Chat", "Range to Send"), "Announcements");
		if (groups.get(0).settings().get(0) != color || groups.get(0).settings().get(1) != numbers.get(0)
			|| groups.get(0).settings().get(2) != booleans.get(0)
			|| groups.get(1).settings().get(0) != booleans.get(1)
			|| groups.get(1).settings().get(2) != texts.get(0)) {
			throw new AssertionError("Setting groups must reference the persisted setting instances");
		}
	}

	private static void checkAnnouncementRangeRules() {
		assertRange(5.0, SparklingCritterDecisions.announcementRange("5"), "default value");
		assertRange(5.0, SparklingCritterDecisions.announcementRange("  5.0  "), "trimmed value");
		assertRange(0.0, SparklingCritterDecisions.announcementRange("-1"), "lower clamp");
		assertRange(128.0, SparklingCritterDecisions.announcementRange("1000"), "upper clamp");
		assertRange(5.0, SparklingCritterDecisions.announcementRange("not a number"), "invalid fallback");
		assertRange(5.0, SparklingCritterDecisions.announcementRange("NaN"), "NaN fallback");
		assertRange(5.0, SparklingCritterDecisions.announcementRange("Infinity"), "infinite fallback");
		assertRange(5.0, SparklingCritterDecisions.announcementRange(null), "null fallback");

		if (!SparklingCritterDecisions.isWithinAnnouncementRange(0.0, 5.0)
			|| !SparklingCritterDecisions.isWithinAnnouncementRange(5.0, 5.0)
			|| SparklingCritterDecisions.isWithinAnnouncementRange(5.0001, 5.0)
			|| SparklingCritterDecisions.isWithinAnnouncementRange(Double.POSITIVE_INFINITY, 5.0)) {
			throw new AssertionError("Announcement range must include its boundary and reject farther targets");
		}
	}

	private static void assertGroup(SettingGroup group, List<String> settingNames, String label) {
		assertNames(group.settings(), settingNames, label + " settings");
		if (group.toggle() != null || group.collapsedByDefault() || group.debugOnly()
			|| !group.children().isEmpty() || group.stateKey() != null) {
			throw new AssertionError(label + " group presentation changed");
		}
	}

	private static void assertNames(List<? extends Setting> actual, List<String> expected, String label) {
		List<String> names = new ArrayList<>();
		for (Setting setting : actual) names.add(setting.name());
		if (!names.equals(expected)) throw new AssertionError(label + " changed: " + names);
	}

	private static void assertRange(double expected, double actual, String label) {
		if (Math.abs(expected - actual) > 0.0001) {
			throw new AssertionError(label + " changed: expected " + expected + ", got " + actual);
		}
	}

	private static void assertSpecies(String expected, String actual, String label) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new AssertionError(label + " changed: expected " + expected + ", got " + actual);
		}
	}

	private static boolean close(float actual, float expected) {
		return Math.abs(actual - expected) < 0.0001f;
	}
}
