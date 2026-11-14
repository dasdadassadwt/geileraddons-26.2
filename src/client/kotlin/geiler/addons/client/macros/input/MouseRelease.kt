package geiler.addons.client.macros.input

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * Lets go of the mouse while a macro runs, so the cursor is free for other windows and a hand on the mouse does not turn
 * the view.
 *
 * The macro never needed the mouse: it turns the view itself, every frame, and a click it makes goes to the key binding
 * when the mouse is not grabbed (see [SimulatedMouse]). What has to be kept from happening is the game taking the mouse
 * back - it grabs it again on a click into the window and whenever a screen closes - and the game opening its pause menu
 * when the window loses focus, because with a screen open the macro stands still. Both are refused while this is on (see
 * the mixins). The game also lowers the frame rate after a minute without input; the macro counts as input, so it does not.
 */
object MouseRelease {
    private var ungrabSetting: () -> Boolean = { false }
    private var macroRunning: () -> Boolean = { false }
    private var released = false
    private var registered = false

    /** Wires in the setting and the running macros; call once while the mod starts. */
    @JvmStatic
    fun init(ungrab: () -> Boolean, running: () -> Boolean) {
        ungrabSetting = ungrab
        macroRunning = running
        if (registered) return
        registered = true
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    /** Whether the mouse is to stay free now: the setting is on and a macro is running. */
    @JvmStatic
    fun active(): Boolean = ungrabSetting() && macroRunning()

    private fun tick(client: Minecraft) {
        if (active() && client.player != null) {
            if (client.mouseHandler.isMouseGrabbed) client.mouseHandler.releaseMouse()
            client.framerateLimitTracker.onInputReceived()
            released = true
        } else if (released) {
            // The macro has stopped: the mouse goes back to the game, as it would have been, if nothing else has it.
            released = false
            if (client.player != null && client.gui.screen() == null && client.isWindowActive) {
                client.mouseHandler.grabMouse()
            }
        }
    }
}
