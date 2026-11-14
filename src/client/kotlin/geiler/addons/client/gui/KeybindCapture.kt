package geiler.addons.client.gui

import org.lwjgl.glfw.GLFW

/** Rules for the Click GUI's key-capture fields (Wardrobe, Loadout, Spirit Leap keybinds). */
object KeybindCapture {
    /** Escape, Backspace and Delete remove the binding instead of binding themselves. */
    @JvmStatic
    fun clearsBinding(glfwKey: Int): Boolean =
        glfwKey == GLFW.GLFW_KEY_ESCAPE || glfwKey == GLFW.GLFW_KEY_BACKSPACE || glfwKey == GLFW.GLFW_KEY_DELETE

    const val HINT = "Press a key (Esc clears)"
}
