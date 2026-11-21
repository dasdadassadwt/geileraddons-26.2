package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.utility.PlayerAlerts
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * Borderless fullscreen: while enabled, the game window loses its title bar and border and covers the whole monitor it is on,
 * so alt-tabbing and a second monitor behave like a normal window while the game looks fullscreen. Turning it off restores
 * the decorations, size and position the window had. It does nothing while Minecraft's own fullscreen (F11) is active; turn
 * that off first.
 */
class BorderlessFullscreenModule private constructor() : Module(
    "Borderless Fullscreen",
    "Removes the window border and stretches the game window over the whole monitor. Turn off F11 fullscreen first.",
    Category.MISCELLANEOUS,
    false,
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 240)
    private var applied = false
    private var ticks = 0L
    private var applies = 0L
    private var savedX = 0
    private var savedY = 0
    private var savedW = 0
    private var savedH = 0
    private var savedDecorated = true

    override fun onEnable() {
        applied = false
        applies = 0
        diagnostics.enable("backend=GLFW undecorated window sized to its monitor")
        // GLFW window calls belong to the client thread, and enabling can come from config loading on another one.
        Minecraft.getInstance().execute { apply() }
    }

    override fun onDisable() {
        Minecraft.getInstance().execute { restore() }
        diagnostics.event("DISABLE applies=$applies")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val handle = Minecraft.getInstance().window.handle()
        // F11 and back leaves the window with a border again; put the borderless state back once a second.
        if (++ticks % RECHECK_TICKS == 0L && applied && GLFW.glfwGetWindowMonitor(handle) == 0L &&
            GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED) == GLFW.GLFW_TRUE
        ) {
            diagnostics.event("REAPPLY window regained its border")
            apply()
        }
        diagnostics.statusOnChange { "enabled=true applied=$applied applies=$applies" }
    }

    private fun apply() {
        if (!isEnabled()) return
        val handle = Minecraft.getInstance().window.handle()
        if (GLFW.glfwGetWindowMonitor(handle) != 0L) {
            diagnostics.event("SKIP vanilla fullscreen is active")
            PlayerAlerts.chat("§eBorderless Fullscreen: turn off Minecraft fullscreen (F11), then toggle the module again.")
            return
        }
        if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) GLFW.glfwRestoreWindow(handle)
        val x = IntArray(1)
        val y = IntArray(1)
        val w = IntArray(1)
        val h = IntArray(1)
        GLFW.glfwGetWindowPos(handle, x, y)
        GLFW.glfwGetWindowSize(handle, w, h)
        val target = monitorBounds(x[0] + w[0] / 2, y[0] + h[0] / 2)
        if (target == null) {
            diagnostics.event("SKIP no monitor video mode")
            return
        }
        if (!applied) {
            savedX = x[0]
            savedY = y[0]
            savedW = w[0]
            savedH = h[0]
            savedDecorated = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED) == GLFW.GLFW_TRUE
        }
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE)
        GLFW.glfwSetWindowPos(handle, target[0], target[1])
        GLFW.glfwSetWindowSize(handle, target[2], target[3])
        applied = true
        applies++
        diagnostics.event("APPLY monitor=${target[2]}x${target[3]}@${target[0]},${target[1]} was=${savedW}x$savedH@$savedX,$savedY decorated=$savedDecorated")
    }

    private fun restore() {
        if (!applied) return
        applied = false
        val handle = Minecraft.getInstance().window.handle()
        if (GLFW.glfwGetWindowMonitor(handle) != 0L) return
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, if (savedDecorated) GLFW.GLFW_TRUE else GLFW.GLFW_FALSE)
        GLFW.glfwSetWindowSize(handle, savedW.coerceAtLeast(MIN_SIZE), savedH.coerceAtLeast(MIN_SIZE))
        GLFW.glfwSetWindowPos(handle, savedX, savedY)
        diagnostics.event("RESTORE ${savedW}x$savedH@$savedX,$savedY decorated=$savedDecorated")
    }

    /** x, y, width, height of the monitor holding ([px], [py]), or the primary monitor; null when GLFW reports none. */
    private fun monitorBounds(px: Int, py: Int): IntArray? {
        val monitors = GLFW.glfwGetMonitors() ?: return null
        var best: IntArray? = null
        for (i in 0 until monitors.limit()) {
            val mode = GLFW.glfwGetVideoMode(monitors.get(i)) ?: continue
            val mx = IntArray(1)
            val my = IntArray(1)
            GLFW.glfwGetMonitorPos(monitors.get(i), mx, my)
            val bounds = intArrayOf(mx[0], my[0], mode.width(), mode.height())
            if (best == null) best = bounds
            if (px >= bounds[0] && px < bounds[0] + bounds[2] && py >= bounds[1] && py < bounds[1] + bounds[3]) return bounds
        }
        return best
    }

    companion object {
        private const val RECHECK_TICKS = 20L
        private const val MIN_SIZE = 320

        @JvmField
        val INSTANCE = BorderlessFullscreenModule()
    }
}
