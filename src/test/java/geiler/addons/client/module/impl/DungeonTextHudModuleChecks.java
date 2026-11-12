package geiler.addons.client.module.impl;

import geiler.addons.client.hud.HudElement;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Direct contract checks for the shared dungeon text HUD implementation. */
public final class DungeonTextHudModuleChecks {
	private DungeonTextHudModuleChecks() { }

	public static void run() {
		Probe module = new Probe();
		try {
			require(module instanceof Module && module instanceof HudElement
					&& module.name().equals("Dungeon HUD Probe")
					&& module.description().equals("Checks the shared dungeon text HUD contract.")
					&& module.category() == Category.F7 && !module.defaultEnabled()
					&& module.id().equals("dungeon_hud_probe")
					&& module.displayName().equals("Dungeon HUD Probe"),
				"module and HUD identity, category, and disabled default remain stable");
			require(module.previewLinesForCheck().equals(List.of("Dungeon HUD Probe", "Preview"))
					&& module.previewMode().equals("sample") && !module.visible()
					&& module.renderOnHud(),
				"empty snapshots retain the default editor sample and stay off the live HUD");

			module.publishHudLines(null);
			require(module.previewMode().equals("sample") && !module.visible(),
				"a null snapshot clears live content");
			module.publishHudLines(List.of());
			require(module.previewMode().equals("sample") && !module.visible(),
				"an empty snapshot clears live content");
			module.publishHudLines(Arrays.asList(null, null));
			require(module.previewMode().equals("sample") && !module.visible(),
				"a snapshot containing only null lines is treated as empty");

			ArrayList<String> source = new ArrayList<>(Arrays.asList("Title", null, "Body"));
			module.publishHudLines(source, 0xFF123456);
			source.clear();
			module.setEnabled(true);
			require(module.visible() && module.previewMode().equals("live"),
				"a non-empty snapshot is copied and becomes live only while the module is enabled");
			module.setEnabled(false);
			require(!module.visible() && module.previewMode().equals("live"),
				"disabling hides the snapshot while preserving its live/sample source state");
			module.clearHudLines();
			require(!module.visible() && module.previewMode().equals("sample"),
				"clearing a snapshot returns the preview source to the editor sample");
		} finally {
			module.clearHudLines();
			module.setEnabled(false);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError("DungeonTextHudModuleChecks: " + message);
	}

	private static final class Probe extends DungeonTextHudModule {
		private Probe() {
			super("Dungeon HUD Probe", "Checks the shared dungeon text HUD contract.",
				"dungeon_hud_probe", "Dungeon HUD Probe");
		}

		private List<String> previewLinesForCheck() {
			return previewLines();
		}
	}
}
