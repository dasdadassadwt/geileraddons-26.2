package geiler.addons.client.module.impl;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.material.FogType;

/** No Fog was merged into No Render: its toggles migrate once, and the fog rules live in No Render. */
public final class NoFogMigrationChecks {
	private NoFogMigrationChecks() { }

	public static void run() {
		Map<String, Boolean> toggles = new HashMap<>();
		Map<String, Boolean> enabled = new HashMap<>();

		check(NoFogMigration.toggleValues(toggles, enabled).isEmpty(), "no saved No Fog state copies nothing");
		enabled.put("No Fog", false);
		toggles.put("No Fog.Lava", true);
		check(NoFogMigration.toggleValues(toggles, enabled).isEmpty(), "a disabled No Fog copies nothing");
		check(!NoFogMigration.enableNoRender(enabled), "a disabled No Fog does not enable No Render");

		enabled.put("No Fog", true);
		Map<String, Boolean> copied = NoFogMigration.toggleValues(toggles, enabled);
		check(copied.size() == 4 && copied.get("Underwater Fog") && copied.get("Lava Fog")
			&& copied.get("Air Fog") && copied.get("Distance Fog"), "absent old toggles count as on (the old default)");
		check(NoFogMigration.enableNoRender(enabled), "an enabled No Fog enables an unsaved No Render");

		toggles.put("No Fog.Underwater", false);
		toggles.put("No Fog.Distance", false);
		copied = NoFogMigration.toggleValues(toggles, enabled);
		check(!copied.get("Underwater Fog") && copied.get("Lava Fog") && !copied.get("Distance Fog"),
			"saved old toggles are copied as they were");

		toggles.put("No Render.Lava Fog", false);
		copied = NoFogMigration.toggleValues(toggles, enabled);
		check(!copied.containsKey("Lava Fog") && copied.size() == 3, "a saved No Render toggle always wins");
		enabled.put("No Render", false);
		check(!NoFogMigration.enableNoRender(enabled), "a saved No Render state is never overridden");

		NoRenderModule module = NoRenderModule.INSTANCE;
		check(NoRenderModule.Companion.shouldClearFog(FogType.WATER, true, false, false)
			&& !NoRenderModule.Companion.shouldClearFog(FogType.WATER, false, true, true)
			&& NoRenderModule.Companion.shouldClearFog(FogType.LAVA, false, true, false)
			&& !NoRenderModule.Companion.shouldClearFog(FogType.LAVA, true, false, true)
			&& NoRenderModule.Companion.shouldClearFog(FogType.ATMOSPHERIC, false, false, true)
			&& NoRenderModule.Companion.shouldClearFog(FogType.POWDER_SNOW, false, false, true)
			&& NoRenderModule.Companion.shouldClearFog(FogType.NONE, false, false, true)
			&& !NoRenderModule.Companion.shouldClearFog(FogType.ATMOSPHERIC, true, true, false),
			"water and lava follow their toggles, every other fog follows the air toggle");
		check(NoRenderModule.FAR_END > NoRenderModule.FAR_START && NoRenderModule.FAR_START > 100_000f,
			"cleared fog distances lie far past any render distance");
		check(module.booleanSettings().stream().filter(s -> s.name().endsWith(" Fog")).count() == 4
			&& module.booleanSettings().stream().filter(s -> s.name().endsWith(" Fog")).noneMatch(s -> s.value()),
			"No Render has four fog toggles, all off by default");
		check(!module.shouldClearFog(FogType.WATER), "a disabled No Render clears nothing");
		try {
			Class.forName("geiler.addons.client.module.impl.NoFogModule");
			throw new AssertionError("NoFogModule must be gone");
		} catch (ClassNotFoundException expected) { }
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
