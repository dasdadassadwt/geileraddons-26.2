package geiler.addons.client.module;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** The slot-lock gesture key row must show the live binding and follow rebinds in both directions. */
public final class KeyMappingMirrorChecks {
	private KeyMappingMirrorChecks() { }

	public static void run() {
		KeyMapping mapping = new KeyMapping("key.geileraddons.mirror_check", InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_G, KeyMapping.Category.GAMEPLAY);
		KeyMappingMirror mirror = new KeyMappingMirror(mapping, "Slot Gesture Key");
		check("key.keyboard.g".equals(mirror.getSetting().value()), "the row starts with the real bound key");
		check(mirror.getSetting().keybindCapture(), "the row is an inline key-capture field");

		mapping.setKey(InputConstants.getKey("key.keyboard.h"));
		mirror.sync();
		check("key.keyboard.h".equals(mirror.getSetting().value()), "a rebind from Controls updates the row live");

		mirror.getSetting().setValue("key.keyboard.j");
		mirror.sync();
		check("key.keyboard.j".equals(mapping.saveString()), "capturing a key in the row rebinds the mapping");

		mirror.getSetting().setValue(InputConstants.UNKNOWN.getName());
		mirror.sync();
		check(mapping.isUnbound(), "clearing the row unbinds the mapping");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
