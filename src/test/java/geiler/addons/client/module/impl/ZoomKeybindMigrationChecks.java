package geiler.addons.client.module.impl;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.module.ModuleKeybind;

/** Zoom holds on the module keybind: the old "Zoom Key" text migrates once and the held test is exact. */
public final class ZoomKeybindMigrationChecks {
	private ZoomKeybindMigrationChecks() { }

	public static void run() {
		ModuleKeybind moved = ZoomKeybindMigration.resolve(false, "key.keyboard.z");
		check(moved != null && moved.key().equals(InputConstants.getKey("key.keyboard.z")) && moved.modifiers() == 0,
			"an old Zoom Key becomes the module bind");
		check(ZoomKeybindMigration.resolve(true, "key.keyboard.z") == null, "a saved module bind wins");
		check(ZoomKeybindMigration.resolve(false, "not a key") == null, "a garbage old key is ignored");
		check(ZoomKeybindMigration.resolve(false, "") == null && ZoomKeybindMigration.resolve(false, null) == null,
			"no old key keeps the default");
		ModuleKeybind unbound = ZoomKeybindMigration.resolve(false, "key.keyboard.unknown");
		check(unbound != null && !unbound.isBound(), "an old unbound key leaves Zoom unbound");
		ModuleKeybind mouse = ZoomKeybindMigration.resolve(false, "key.mouse.4");
		check(mouse != null && mouse.isBound(), "a mouse button migrates too");

		ModuleKeybind c = new ModuleKeybind(InputConstants.getKey("key.keyboard.c"), 0);
		check(ZoomKeybindMigration.held(c, true, false, false, false, false), "a plain bind is held when its key is down");
		check(!ZoomKeybindMigration.held(c, false, false, false, false, false), "a released key is not held");
		check(!ZoomKeybindMigration.held(c, true, true, false, false, false), "an extra modifier breaks a plain bind");
		ModuleKeybind ctrlC = new ModuleKeybind(InputConstants.getKey("key.keyboard.c"), InputConstants.MOD_CONTROL);
		check(ZoomKeybindMigration.held(ctrlC, true, false, true, false, false), "a combo is held with its modifier");
		check(!ZoomKeybindMigration.held(ctrlC, true, false, false, false, false), "a combo needs its modifier");
		check(!ZoomKeybindMigration.held(ModuleKeybind.NONE, true, false, false, false, false), "an unbound module never zooms");

		// diagnosticName must never touch GLFW: Zoom logs it from onEnable while the config loads, before GLFW is
		// initialized, and displayName() (glfwGetKeyName) there crashed the game on start.
		check(c.diagnosticName().equals("key.keyboard.c") && ctrlC.diagnosticName().equals("Ctrl + key.keyboard.c")
			&& ModuleKeybind.NONE.diagnosticName().equals("None"), "diagnosticName is GLFW-free and readable");

		ZoomModule zoom = ZoomModule.INSTANCE;
		check(!zoom.togglesOnKeybind() && NoRenderModule.INSTANCE.togglesOnKeybind(),
			"Zoom does not toggle on its bind, other modules do");
		check(zoom.defaultKeybind().equals(c) && zoom.keybind().equals(c), "a fresh install binds Zoom to C");
		check(zoom.textSettings().isEmpty(), "the old Zoom Key setting is gone");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
