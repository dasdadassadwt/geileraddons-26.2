package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;
import net.minecraft.world.level.material.FogType;

/** Offline checks for Auto Sprint, Fullbright and Block Overlay rules and defaults. */
public final class ViewModulesChecks {
	private ViewModulesChecks() { }

	public static void run() {
		checkAutoSprint();
		checkFullbright();
		checkBlockOverlay();
	}

	private static void checkAutoSprint() {
		AutoSprintModule module = AutoSprintModule.INSTANCE;
		check("Auto Sprint".equals(module.name()) && module.category() == Category.MISCELLANEOUS
			&& !module.isEnabled() && !module.hasSettings(), "Auto Sprint is a default-off settings-free module");
		check(AutoSprintModule.resolve(true, false, false, false), "enabled with no screen forces sprint");
		check(!AutoSprintModule.resolve(true, true, false, false), "an open screen does not force sprint");
		check(!AutoSprintModule.resolve(true, false, true, false), "Freecam active does not force sprint");
		check(!AutoSprintModule.resolve(false, false, false, false), "disabled leaves sprint alone");
		check(AutoSprintModule.resolve(false, true, true, true), "a held sprint key always passes through");
		check(!module.resolveSprint(false, false, false), "a disabled module never forces sprint on its instance");
	}

	private static void checkFullbright() {
		FullbrightModule module = FullbrightModule.INSTANCE;
		check("Fullbright".equals(module.name()) && module.category() == Category.VISUAL && !module.isEnabled()
			&& !module.hasSettings(), "Fullbright is a default-off settings-free module");
		check(FullbrightModule.NIGHT_VISION == 1.0f && FullbrightModule.BRIGHTNESS == 1.0f
			&& FullbrightModule.DARKNESS_SCALE == 0.0f && FullbrightModule.BOSS_DARKENING == 0.0f,
			"the lightmap override is full night vision with no darkness effects");
		check(!module.consumeRefresh(), "no lightmap refresh is pending before the module is switched");
		module.setEnabled(true);
		check(module.consumeRefresh() && !module.consumeRefresh(), "enabling queues exactly one lightmap refresh");
		module.setEnabled(false);
		check(module.consumeRefresh(), "disabling queues one lightmap refresh");
	}

	private static void checkBlockOverlay() {
		BlockOverlayModule module = BlockOverlayModule.INSTANCE;
		check("Block Overlay".equals(module.name()) && module.category() == Category.VISUAL && !module.isEnabled(),
			"Block Overlay is a default-off Visual module");
		var toggles = module.booleanSettings();
		check(toggles.size() == 4 && !toggles.get(0).value() && toggles.get(1).value() && !toggles.get(2).value()
			&& !toggles.get(3).value(), "toggle defaults match Devonian: entity off, wire phase on, fill phase off, dynamic off");
		check(module.numberSettings().size() == 1 && module.numberSettings().get(0).value() == 3.0f,
			"outline width defaults to 3.0");
		check(module.colorSettings().get(0).argb() == 0xFF14FEEA, "outline colour is the saved Devonian colour");
		check(!BlockOverlayRules.hasAlpha(module.colorSettings().get(1).argb()), "fill colour is fully transparent");
		check(BlockOverlayRules.phase(false, true, true) && !BlockOverlayRules.phase(false, true, false),
			"without dynamic phase the setting decides");
		check(BlockOverlayRules.phase(true, true, false) && !BlockOverlayRules.phase(true, false, true),
			"dynamic phase is on in first person and off in third person whatever the setting");
		check(BlockOverlayModule.shouldCancelVanillaOutline(true, true)
			&& !BlockOverlayModule.shouldCancelVanillaOutline(true, false)
			&& !BlockOverlayModule.shouldCancelVanillaOutline(false, true), "vanilla outline is cancelled only when on and hitting");
		check(!module.cancelsVanillaOutline(), "a disabled module never cancels the vanilla outline");
		double[] box = BlockOverlayRules.box(1, 2, 3, 0.5, 1, 0.25);
		check(box.length == 6 && box[0] == 1 && box[3] == 0.5 && box[5] == 0.25, "box keeps origin and size");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("ViewModulesChecks: " + message);
	}
}
