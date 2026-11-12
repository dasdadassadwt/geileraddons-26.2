package geiler.addons.client.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Every module card must resolve to a bundled icon whose PNG exists. */
public final class ModuleIconTexturesChecks {
	private ModuleIconTexturesChecks() { }

	private static final List<String> MODULES = List.of(
		"Terminal Stats", "Terminal Titles", "Dungeon Map", "Room Alerts", "Key Pickup", "Score Titles",
		"Blood Camp", "Livid Helper", "Invincibility Timers", "Secret Clicked", "Slayer Tracker",
		"Pest Tracker", "Crop Fever Timer", "Party Commands", "Lobby Sniper", "Slot Locking",
		"Player Names", "Custom Scoreboard", "Snap Tap");

	public static void run() throws Exception {
		Path root = Path.of("src", "main", "resources", "assets", "geileraddons");
		for (String module : MODULES) {
			var id = ModuleIconTextures.forModule(module);
			check(id != null, "module has an icon entry: " + module);
			Path png = root.resolve(id.getPath());
			check(Files.size(png) > 200, "icon PNG exists and is not empty: " + id.getPath());
			check(ModuleIconTextures.isTintable(module), "icon follows the theme tint: " + module);
			check(ModuleIconTextures.sourceSize(module) > 0, "icon has a source size: " + module);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError("ModuleIconTexturesChecks: " + message);
	}
}
