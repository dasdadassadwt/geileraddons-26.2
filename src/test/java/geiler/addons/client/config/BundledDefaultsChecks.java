package geiler.addons.client.config;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Workspace;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class BundledDefaultsChecks {
	private static final Set<String> SECTIONS = Set.of("toggles", "numbers", "choices", "colors", "hudPositions");
	/** Settings that automate, can get an account banned, send chat or describe the player must keep code defaults. */
	private static final Pattern FORBIDDEN_NAME = Pattern.compile(
		"debug|auto|announce|party chat|send|click|depth check|walls|camera clip|protect|serums|macro|aim|webhook",
		Pattern.CASE_INSENSITIVE);
	private static final Set<String> FORBIDDEN_MODULES = Set.of(
		"Auto Experiments", "Auto Kick", "Auto Terms", "Party Commands", "Instance Requeue", "Lasso", "Terminal Solver",
		"Freecam", "Lever Aim Assist", "General", "Theme", "Cosmetics", "Player Names", "Player Size", "Spotify", "Debug",
		"Macros", "Any Rename", "Aliases");

	private BundledDefaultsChecks() { }

	public static void run() {
		Set<String> sections = BundledDefaults.INSTANCE.sections();
		check(sections.equals(SECTIONS),
			"only settings and HUD positions are bundled, never enabled states, texts or keybinds: " + sections);

		Set<String> known = new HashSet<>();
		for (Module module : ModuleManager.modules()) {
			if (module.category().getWorkspace() != Workspace.NORMAL) continue;
			module.colorSettings().forEach(s -> known.add("colors " + module.configName() + "." + s.name()));
			module.numberSettings().forEach(s -> known.add("numbers " + module.configName() + "." + s.name()));
			module.booleanSettings().forEach(s -> known.add("toggles " + module.configName() + "." + s.name()));
			module.choiceSettings().forEach(s -> known.add("choices " + module.configName() + "." + s.name()));
		}
		for (String section : SECTIONS) {
			for (String key : BundledDefaults.INSTANCE.keys(section)) {
				if (section.equals("hudPositions")) {
					check(key.matches("[a-z0-9_]{1,64}"), "HUD ids are code names: " + key);
					continue;
				}
				check(known.contains(section + " " + key), "stale or unknown bundled setting: " + section + " " + key);
				int dot = key.indexOf('.');
				check(!FORBIDDEN_MODULES.contains(key.substring(0, dot)), "module keeps code defaults: " + key);
				check(!FORBIDDEN_NAME.matcher(key.substring(dot + 1)).find(), "setting keeps code defaults: " + key);
			}
		}

		Module noRender = module("No Render");
		BooleanSetting clouds = noRender.booleanSettings().stream().filter(s -> s.name().equals("Clouds")).findFirst().orElseThrow();
		Module bloodCamp = module("Blood Camp");
		NumberSetting boxSize = bloodCamp.numberSettings().stream().filter(s -> s.name().equals("Box Size")).findFirst().orElseThrow();
		boolean cloudsBefore = clouds.rawValue();
		float boxBefore = boxSize.value();
		try {
			boolean enabled = noRender.isEnabled();
			clouds.setValue(false);
			boxSize.setValue(1.0f);
			BundledDefaults.INSTANCE.apply(noRender);
			BundledDefaults.INSTANCE.apply(bloodCamp);
			check(clouds.rawValue() && Math.abs(boxSize.value() - 0.8f) < 1e-4f, "bundled values are applied");
			check(noRender.isEnabled() == enabled, "enabled state is never touched");
		} finally {
			clouds.setValue(cloudsBefore);
			boxSize.setValue(boxBefore);
		}

		check(BundledDefaults.isFreshInstall(false, false), "no config at all is a new player");
		check(!BundledDefaults.isFreshInstall(true, false) && !BundledDefaults.isFreshInstall(false, true),
			"an existing or legacy config is never overwritten");
	}

	private static Module module(String name) {
		return ModuleManager.modules().stream().filter(m -> m.configName().equals(name)).findFirst().orElseThrow();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
