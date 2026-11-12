package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.presentation.PlayerPresentationProfileSource;
import geiler.addons.client.update.ProjectLinks;
import geiler.addons.client.update.UpdateChecker;
import geiler.addons.client.window.WindowIconController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Checks for the window icon switch, the project links, and No Loading Screen. */
public final class WindowIconLinkChecks {
	private WindowIconLinkChecks() { }

	public static void run() throws Exception {
		checkIconDecisions();
		checkGeneralModule();
		checkLinks();
		checkNoLoadingScreen();
	}

	private static void checkIconDecisions() {
		check(WindowIconController.supported("Windows 11", null) && WindowIconController.supported("Linux", null)
			&& WindowIconController.supported("Linux", "") && !WindowIconController.supported("Mac OS X", null)
			&& !WindowIconController.supported("Linux", "wayland-0"),
			"the custom icon is skipped on macOS and Wayland");
		check(WindowIconController.nextAction(null, false) == WindowIconController.Action.NONE
			&& WindowIconController.nextAction(null, true) == WindowIconController.Action.APPLY_CUSTOM
			&& WindowIconController.nextAction(false, true) == WindowIconController.Action.APPLY_CUSTOM
			&& WindowIconController.nextAction(true, false) == WindowIconController.Action.RESTORE_DEFAULT
			&& WindowIconController.nextAction(true, true) == WindowIconController.Action.NONE
			&& WindowIconController.nextAction(false, false) == WindowIconController.Action.NONE,
			"the icon changes only when the switch differs from what is applied; vanilla is restored on switch off");
		int[] alpha = new int[100];
		for (int y = 3; y <= 4; y++) for (int x = 2; x <= 5; x++) alpha[y * 10 + x] = 255;
		alpha[0] = 8;
		check(Arrays.equals(WindowIconController.opaqueSquare(10, 10, alpha), new int[] {2, 2, 4}),
			"the logo is cropped to the square around its opaque pixels so the taskbar shows it full size");
		int[] tall = new int[100];
		for (int y = 0; y < 10; y++) for (int x = 0; x <= 1; x++) tall[y * 10 + x] = 255;
		check(Arrays.equals(WindowIconController.opaqueSquare(10, 10, tall), new int[] {0, 0, 10}),
			"the crop square never leaves the image");
		check(Arrays.equals(WindowIconController.opaqueSquare(10, 10, new int[100]), new int[] {0, 0, 10})
			&& Arrays.equals(WindowIconController.opaqueSquare(10, 10, new int[3]), new int[] {0, 0, 10}),
			"an empty or malformed alpha plane keeps the whole image");
	}

	private static void checkGeneralModule() {
		GeneralModule general = GeneralModule.INSTANCE;
		BooleanSetting icon = general.booleanSettings().stream().filter(setting -> setting.name().equals("Custom Window Icon"))
			.findFirst().orElseThrow();
		check(!icon.rawValue(), "Custom Window Icon is off by default");
		check(general.actions().stream().anyMatch(action -> action.label().equals("Open Discord")),
			"General settings has an Open Discord button");
	}

	private static void checkLinks() throws Exception {
		check(ProjectLinks.DISCORD_URL.equals("https://discord.gg/VmHPgB5Jyc"), "the official Discord invite");
		check(ProjectLinks.GITHUB_URL.equals("https://github.com/dasdadassadwt/geileraddons-26.2"),
			"the public repository, not the private development repository");
		check(UpdateChecker.repositoryPage().equals(ProjectLinks.GITHUB_URL), "the update checker uses the public repository");
		check(PlayerPresentationProfileSource.REPOSITORY.equals("dasdadassadwt/geileraddons-26.2"),
			"the player profile file is read from the public repository");
		try (var stream = WindowIconLinkChecks.class.getResourceAsStream("/fabric.mod.json")) {
			check(stream != null, "fabric.mod.json is on the classpath");
			String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			check(json.contains("\"sources\": \"https://github.com/dasdadassadwt/geileraddons-26.2\"")
				&& json.contains("\"issues\": \"https://github.com/dasdadassadwt/geileraddons-26.2/issues\"")
				&& json.contains("\"discord\": \"https://discord.gg/VmHPgB5Jyc\"")
				&& !json.contains("dasdadassadwt/geileraddons\"") && !json.contains("dasdadassadwt/geileraddons/"),
				"fabric.mod.json points at the public repository and lists the Discord");
		}
	}

	private static void checkNoLoadingScreen() {
		NoLoadingScreenModule module = NoLoadingScreenModule.INSTANCE;
		check(module.name().equals("No Loading Screen") && module.category() == Category.MISCELLANEOUS
			&& !module.defaultEnabled() && !module.hasSettings(), "No Loading Screen is a disabled-by-default module with no settings");
		check(!module.shouldSkip(), "a disabled module never skips the loading screen");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
