package geiler.addons.client.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pins two Click GUI input rules that need a running client to exercise: slider drags are polled from GLFW (vanilla's
 * {@code isLeftPressed} is only tracked while no screen is open, so polling it cancelled every drag), and a module
 * action that changes the saved view makes the open screen follow it.
 */
public final class ClickGuiInputChecks {
	private static final Path SCREEN = Path.of("src", "client", "kotlin", "geiler", "addons", "client", "gui", "ClickGuiScreenImpl.kt");

	private ClickGuiInputChecks() { }

	public static void run() throws IOException {
		String source = Files.readString(SCREEN);
		check(!source.contains("isLeftPressed"),
			"the Click GUI must not poll MouseHandler.isLeftPressed: it is never updated while a screen is open");
		check(source.contains("glfwGetMouseButton"), "slider drags poll the left button from GLFW");
		int action = source.indexOf("row.action.onClick.run()");
		check(action > 0 && source.indexOf("followSavedView()", action) > action
			&& source.indexOf("followSavedView()", action) < action + 120,
			"after a module action runs, the open screen follows the view it saved (Edit Class Colors)");
		check(source.contains("action=slider_drag"), "slider drags write a bounded diagnostic event");
		check(KeybindCapture.clearsBinding(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) && KeybindCapture.clearsBinding(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE)
			&& KeybindCapture.clearsBinding(org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE) && !KeybindCapture.clearsBinding(org.lwjgl.glfw.GLFW.GLFW_KEY_A),
			"Escape, Backspace and Delete clear a captured keybind; other keys bind");
		check(source.contains("KeybindCapture.clearsBinding(event.key())"), "the Click GUI keybind capture uses the shared clear rule");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
