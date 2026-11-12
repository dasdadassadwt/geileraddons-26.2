package geiler.addons.client.config;

import geiler.addons.client.module.Module;
import geiler.addons.client.module.impl.AutoKickModule;
import geiler.addons.client.module.impl.GeneralModule;

import java.util.HashMap;
import java.util.Map;

/** Focused checks for the shared setting-key and legacy alias rules. */
public final class ConfigSettingKeysChecks {
	private ConfigSettingKeysChecks() { }

	public static void run() {
		checkJavaApi();
		Module autoKick = AutoKickModule.INSTANCE;
		check("Auto Kick.F1 Ask Before".equals(ConfigSettingKeys.settingKey(autoKick, "F1 Ask Before")),
			"setting keys retain the module config name and exact setting label");

		String[][] aliases = {
			{"Ask Before", "Ask Before Kick"}, {"Dupe", "Dupe Check"}, {"Cata", "Minimum Cata"},
			{"Class", "Minimum Joined Class"}, {"Class Avg", "Minimum Class Average"},
			{"Secrets", "Minimum Secrets"}, {"Secret Avg", "Minimum Secret Average"},
			{"MP", "Minimum Magical Power"}, {"Minimum PB", "Maximum PB Seconds"},
			{"Bank", "Minimum Bank"}, {"Terminator", "Require Terminator"},
			{"Hyperion", "Require Hyperion"}, {"GDrag", "Require Golden Dragon"}
		};
		for (String[] alias : aliases) {
			String actual = ConfigSettingKeys.legacySettingKey(autoKick, "M7 " + alias[0]);
			check(("Auto Kick.M7 " + alias[1]).equals(actual),
				"Auto Kick keeps its legacy setting alias for " + alias[0]);
		}
		check(ConfigSettingKeys.legacySettingKey(GeneralModule.INSTANCE, "F1 Ask Before") == null
			&& ConfigSettingKeys.legacySettingKey(autoKick, " Ask Before") == null
			&& ConfigSettingKeys.legacySettingKey(autoKick, "F1 Unknown") == null,
			"unknown modules, malformed names, and unknown settings have no legacy alias");

		Map<String, Boolean> values = new HashMap<>();
		values.put("Auto Kick.F1 Ask Before Kick", false);
		check(Boolean.FALSE.equals(ConfigSettingKeys.settingValue(values, autoKick, "F1 Ask Before")),
			"renamed setting reads the old saved value when the current key is absent");
		values.put("Auto Kick.F1 Ask Before", true);
		check(Boolean.TRUE.equals(ConfigSettingKeys.settingValue(values, autoKick, "F1 Ask Before")),
			"the current saved key wins over its legacy alias");
		values.put("Auto Kick.F1 Ask Before", null);
		check(Boolean.FALSE.equals(ConfigSettingKeys.settingValue(values, autoKick, "F1 Ask Before")),
			"a null current value falls back to the legacy value as before");

		Map<String, String> copies = new HashMap<>();
		copies.put("first", "preferred");
		copies.put("second", "fallback");
		check(ConfigSettingKeys.copyFirstSetting(copies, "first", "second", "target")
			&& "preferred".equals(copies.get("target")), "first legacy source wins when both are present");
		check(!ConfigSettingKeys.copyFirstSetting(copies, "first", "second", "target"),
			"copy helpers never overwrite an existing target");
		Map<String, String> nullFirst = new HashMap<>();
		nullFirst.put("first", null);
		nullFirst.put("second", "fallback");
		check(ConfigSettingKeys.copyFirstSetting(nullFirst, "first", "second", "target")
			&& nullFirst.containsKey("target") && nullFirst.get("target") == null,
			"first-source precedence includes a present null value");
		copies.put("null-source", null);
		check(ConfigSettingKeys.copySetting(copies, "null-source", "null-target")
			&& copies.containsKey("null-target") && copies.get("null-target") == null,
			"copy helpers preserve present null values and the contains-key distinction");
		copies.put("existing-null-target", null);
		check(!ConfigSettingKeys.copySetting(copies, "second", "existing-null-target"),
			"a present null target is still authoritative and is never overwritten");
	}

	private static void checkJavaApi() {
		try {
			ConfigSettingKeys.class.getMethod("settingKey", Module.class, String.class);
			ConfigSettingKeys.class.getMethod("settingValue", Map.class, Module.class, String.class);
			ConfigSettingKeys.class.getMethod("copyFirstSetting", Map.class, String.class, String.class, String.class);
			ConfigSettingKeys.class.getMethod("copySetting", Map.class, String.class, String.class);
			ConfigSettingKeys.class.getMethod("legacySettingKey", Module.class, String.class);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("ConfigSettingKeys lost a Java static entrypoint", error);
		}
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
