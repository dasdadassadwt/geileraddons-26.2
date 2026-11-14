package geiler.addons.client.macros.input

import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.GeilerAddons
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import net.minecraft.client.input.MouseButtonInfo
import org.lwjgl.glfw.GLFW
import java.lang.reflect.Method

/**
 * Presses and releases mouse buttons the way the mouse does.
 *
 * A real click reaches the game as a GLFW callback, which ends in `MouseHandler#onButton`. That is called here, with the
 * same arguments, so everything a hardware click sets off happens: the pressed state of the button, the key binding it is
 * mapped to, and then `Minecraft#handleKeybinds` acts on the click on the next pass of the tick - hit what the crosshair
 * is on, use the item in hand. Nothing is sent to the server directly; whatever the server sees is what the game itself
 * decided to send for that click.
 *
 * If the handler cannot be reached, or the mouse is not grabbed (in which case a real press would only grab it and not
 * count as a click), the key binding of the button is driven directly instead, which is the same thing the handler does
 * after its own checks.
 */
object SimulatedMouse {
    enum class Button(val code: Int) {
        LEFT(GLFW.GLFW_MOUSE_BUTTON_LEFT),
        RIGHT(GLFW.GLFW_MOUSE_BUTTON_RIGHT),
    }

    private var onButton: Method? = null
    private var handlerUnavailable = false
    private val presses = IntArray(Button.values().size)

    /** How many presses of this button have been sent since the game started. */
    @JvmStatic
    fun presses(button: Button): Int = presses[button.ordinal]

    @JvmStatic
    fun press(client: Minecraft, button: Button) = send(client, button, GLFW.GLFW_PRESS)

    @JvmStatic
    fun release(client: Minecraft, button: Button) = send(client, button, GLFW.GLFW_RELEASE)

    private fun send(client: Minecraft, button: Button, action: Int) {
        if (action == GLFW.GLFW_PRESS) presses[button.ordinal]++
        if (!handlerUnavailable && client.mouseHandler.isMouseGrabbed) {
            try {
                handler().invoke(client.mouseHandler, client.window.handle(), MouseButtonInfo(button.code, 0), action)
                return
            } catch (exception: ReflectiveOperationException) {
                handlerUnavailable = true
                GeilerAddons.LOGGER.warn("Could not reach MouseHandler#onButton, driving the key binding directly", exception)
            } catch (exception: RuntimeException) {
                handlerUnavailable = true
                GeilerAddons.LOGGER.warn("Could not reach MouseHandler#onButton, driving the key binding directly", exception)
            }
        }

        val key = InputConstants.Type.MOUSE.getOrCreate(button.code)
        KeyMapping.set(key, action == GLFW.GLFW_PRESS)
        if (action == GLFW.GLFW_PRESS) KeyMapping.click(key)
    }

    private fun handler(): Method {
        var method = onButton
        if (method == null) {
            method = MouseHandler::class.java.getDeclaredMethod(
                "onButton", Long::class.javaPrimitiveType, MouseButtonInfo::class.java, Int::class.javaPrimitiveType,
            )
            method.isAccessible = true
            onButton = method
        }
        return method!!
    }
}
