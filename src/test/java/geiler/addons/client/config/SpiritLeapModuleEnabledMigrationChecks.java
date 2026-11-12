package geiler.addons.client.config;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.impl.SpiritLeapMenuModule;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Focused offline checks for the former dungeon helper's Spirit Leap module toggle. */
public final class SpiritLeapModuleEnabledMigrationChecks {
	private static final String LEGACY_MODULE = "Dungeon Odin Helpers";
	private static final String NEW_MODULE = "Spirit Leap Menu";

	private SpiritLeapModuleEnabledMigrationChecks() { }

	public static void run() {
		SpiritLeapMenuModule leap = SpiritLeapMenuModule.INSTANCE;
		String newModuleName = leap.configName();
		check(NEW_MODULE.equals(newModuleName), "the successor module keeps its persisted config alias");
		check(!leap.defaultEnabled(), "a fresh Spirit Leap Menu remains disabled");

		BooleanSetting showPovs = leap.booleanSettings().stream()
			.filter(setting -> "Show POVs".equals(setting.name()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Spirit Leap Menu retains the Show POVs setting"));
		boolean originalShowPovs = showPovs.rawValue();
		boolean originalEnabled = leap.isEnabled();
		check(originalShowPovs, "Show POVs keeps its true fresh default");

		try {
			checkSavedShowPovsFalseSurvivesLoad(newModuleName, leap, showPovs);

			Map<String, Boolean> legacyOn = new HashMap<>();
			legacyOn.put(LEGACY_MODULE, true);
			check(ModConfig.migrateLegacyModuleEnabledState(legacyOn, LEGACY_MODULE, newModuleName),
				"legacy enabled true migrates when no successor choice exists");
			check(Boolean.TRUE.equals(legacyOn.get(newModuleName)), "legacy enabled true is copied");
			check(!ModConfig.migrateLegacyModuleEnabledState(legacyOn, LEGACY_MODULE, newModuleName),
				"a copied successor toggle makes the migration idempotent");

			Map<String, Boolean> legacyOff = new HashMap<>();
			legacyOff.put(LEGACY_MODULE, false);
			check(ModConfig.migrateLegacyModuleEnabledState(legacyOff, LEGACY_MODULE, newModuleName),
				"legacy enabled false also migrates when no successor choice exists");
			check(Boolean.FALSE.equals(legacyOff.get(newModuleName)), "legacy enabled false is copied");

			Map<String, Boolean> explicitOff = new HashMap<>();
			explicitOff.put(LEGACY_MODULE, true);
			explicitOff.put(newModuleName, false);
			check(!ModConfig.migrateLegacyModuleEnabledState(explicitOff, LEGACY_MODULE, newModuleName)
				&& Boolean.FALSE.equals(explicitOff.get(newModuleName)),
				"an explicit successor false choice wins over legacy true");

			Map<String, Boolean> explicitOn = new HashMap<>();
			explicitOn.put(LEGACY_MODULE, false);
			explicitOn.put(newModuleName, true);
			check(!ModConfig.migrateLegacyModuleEnabledState(explicitOn, LEGACY_MODULE, newModuleName)
				&& Boolean.TRUE.equals(explicitOn.get(newModuleName)),
				"an explicit successor true choice wins over legacy false");

			Map<String, Boolean> fresh = new HashMap<>();
			check(!ModConfig.migrateLegacyModuleEnabledState(fresh, LEGACY_MODULE, newModuleName)
				&& !fresh.containsKey(newModuleName) && !leap.defaultEnabled(),
				"no legacy toggle leaves the fresh-install default disabled");
		} finally {
			leap.setEnabled(originalEnabled);
			showPovs.setValue(originalShowPovs);
		}
	}

	private static void checkSavedShowPovsFalseSurvivesLoad(String newModuleName, SpiritLeapMenuModule leap,
		BooleanSetting showPovs) {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve("geileraddons/config.json");
		boolean configExisted = Files.exists(configPath);
		byte[] previousConfig = null;
		try {
			if (configExisted) previousConfig = Files.readAllBytes(configPath);
			Files.createDirectories(configPath.getParent());
			Files.writeString(configPath, """
				{
				  "enabled": {"Dungeon Odin Helpers": true},
				  "toggles": {"%s.Show POVs": false}
				}
				""".formatted(newModuleName));
			ModConfig.load();
			check(leap.isEnabled(), "a legacy enabled module loads as enabled on Spirit Leap Menu");
			check(!showPovs.rawValue(), "an explicit saved Show POVs false value survives config loading");
		} catch (IOException error) {
			throw new AssertionError("Could not prepare the offline Spirit Leap migration config", error);
		} finally {
			try {
				if (configExisted && previousConfig != null) Files.write(configPath, previousConfig);
				else if (!configExisted) Files.deleteIfExists(configPath);
			} catch (IOException error) {
				throw new AssertionError("Could not restore the offline Spirit Leap migration config", error);
			}
		}
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
