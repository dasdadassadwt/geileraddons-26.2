package geiler.addons.client.window

import com.mojang.blaze3d.platform.IconSet
import com.mojang.blaze3d.platform.NativeImage
import geiler.addons.client.config.GeilerAddonsLog
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWImage
import org.lwjgl.system.MemoryUtil

/** Swaps the game window and taskbar icon for the GeilerAddons logo, and back to the vanilla icon. */
object WindowIconController {
    enum class Action { NONE, APPLY_CUSTOM, RESTORE_DEFAULT }

    private const val LOGO = "/assets/geileraddons/icon.png"
    private var applied: Boolean? = null

    /** GLFW cannot set a window icon on macOS, and Wayland has crashed on it, so both are left alone. */
    @JvmStatic
    fun supported(osName: String, waylandDisplay: String?): Boolean =
        !osName.lowercase().contains("mac") && waylandDisplay.isNullOrEmpty()

    /** [applied] is what is on the window now (null = never touched), [wanted] is the setting. */
    @JvmStatic
    fun nextAction(applied: Boolean?, wanted: Boolean): Action = when {
        applied == true && !wanted -> Action.RESTORE_DEFAULT
        applied != true && wanted -> Action.APPLY_CUSTOM
        else -> Action.NONE
    }

    /**
     * `[left, top, size]` of the square around the pixels with alpha >= 32. The logo sits in a padded canvas, so
     * without this crop the taskbar draws it much smaller than other icons. Falls back to the whole image.
     */
    @JvmStatic
    fun opaqueSquare(width: Int, height: Int, alpha: IntArray): IntArray {
        val whole = intArrayOf(0, 0, minOf(width, height))
        if (width <= 0 || height <= 0 || alpha.size < width * height) return whole
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (alpha[y * width + x] < 32) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX < 0) return whole
        val size = minOf(maxOf(maxX - minX + 1, maxY - minY + 1), width, height)
        val left = (minX + (maxX - minX + 1 - size) / 2).coerceIn(0, width - size)
        val top = (minY + (maxY - minY + 1 - size) / 2).coerceIn(0, height - size)
        return intArrayOf(left, top, size)
    }

    /** Call each client tick with the setting; applies or restores the icon only when it changes. */
    fun sync(wanted: Boolean) {
        val action = nextAction(applied, wanted)
        if (action == Action.NONE) return
        applied = wanted
        GeilerAddonsLog.setModuleDiagnosticsEnabled(geiler.addons.client.module.Category.MISCELLANEOUS, "General", true)
        if (!supported(System.getProperty("os.name", ""), System.getenv("WAYLAND_DISPLAY"))) {
            GeilerAddonsLog.write(
                geiler.addons.client.module.Category.MISCELLANEOUS, "General", 0L,
                "WINDOW_ICON skipped unsupported platform",
            )
            return
        }
        val client = Minecraft.getInstance()
        try {
            if (action == Action.APPLY_CUSTOM) applyCustom(client) else restoreDefault(client)
            GeilerAddonsLog.write(geiler.addons.client.module.Category.MISCELLANEOUS, "General", 0L, "WINDOW_ICON $action")
        } catch (error: Exception) {
            applied = null
            GeilerAddonsLog.write(
                geiler.addons.client.module.Category.MISCELLANEOUS, "General", 0L,
                "WINDOW_ICON failed ${error.javaClass.simpleName}",
            )
        }
    }

    private fun applyCustom(client: Minecraft) {
        val stream = WindowIconController::class.java.getResourceAsStream(LOGO) ?: error("logo missing")
        stream.use { input ->
            NativeImage.read(input).use { image ->
                val alpha = IntArray(image.width * image.height) { image.getPixel(it % image.width, it / image.width) ushr 24 }
                val (left, top, size) = opaqueSquare(image.width, image.height, alpha).toList()
                val width = size
                val height = size
                val pixels = MemoryUtil.memAlloc(width * height * 4)
                try {
                    for (y in 0 until height) for (x in 0 until width) {
                        val argb = image.getPixel(left + x, top + y)
                        pixels.put((argb shr 16 and 0xFF).toByte()).put((argb shr 8 and 0xFF).toByte())
                            .put((argb and 0xFF).toByte()).put((argb ushr 24).toByte())
                    }
                    pixels.flip()
                    val icons = GLFWImage.malloc(1)
                    try {
                        icons.get(0).set(width, height, pixels)
                        GLFW.glfwSetWindowIcon(client.window.handle(), icons)
                    } finally {
                        icons.free()
                    }
                } finally {
                    MemoryUtil.memFree(pixels)
                }
            }
        }
    }

    private fun restoreDefault(client: Minecraft) {
        val set = if (SharedConstants.getCurrentVersion().stable()) IconSet.RELEASE else IconSet.SNAPSHOT
        client.window.setIcon(client.vanillaPackResources, set)
    }
}
