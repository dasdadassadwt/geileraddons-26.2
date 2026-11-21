package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;

/** Offline checks for Custom Main Menu: saved-name stability, title-screen toggles and the button colours. */
public final class CustomMainMenuChecks {
	private CustomMainMenuChecks() { }

	public static void run() {
		CustomMainMenuModule module = CustomMainMenuModule.INSTANCE;
		check("Custom Main Menu".equals(module.name()), "the module is shown as Custom Main Menu");
		check("Custom Panorama".equals(module.configName()), "saved settings keep the old Custom Panorama key");

		for (String name : new String[] {"Minecraft logo", "Version text", "Copyright"}) {
			BooleanSetting setting = module.booleanSettings().stream()
				.filter(s -> s.name().equals(name)).findFirst().orElse(null);
			check(setting != null, "toggle exists: " + name);
			check(!setting.value(), "hidden by default: " + name);
		}

		int background = 0xF8080808;
		int accent = 0xFF3B82F6;
		int normal = CustomMainMenuModule.buttonColor(background, accent, true, false, 1f);
		int hover = CustomMainMenuModule.buttonColor(background, accent, true, true, 1f);
		int inactive = CustomMainMenuModule.buttonColor(background, accent, false, false, 1f);
		check((normal & 0xFFFFFF) == (background & 0xFFFFFF), "a resting button is the theme background colour");
		check(alpha(normal) < alpha(background) && alpha(normal) > 0, "buttons are more transparent than the panel background");
		check(alpha(hover) > alpha(normal), "hovering makes a button more solid");
		check((hover & 0xFFFFFF) != (background & 0xFFFFFF), "hovering tints a button toward the accent");
		check(alpha(inactive) < alpha(normal), "an inactive button is fainter");
		check(alpha(CustomMainMenuModule.buttonColor(background, accent, true, false, 0.5f)) < alpha(normal),
			"the widget fade still applies");
	}

	private static int alpha(int argb) {
		return argb >>> 24;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
