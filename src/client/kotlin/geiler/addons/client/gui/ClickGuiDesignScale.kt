package geiler.addons.client.gui

import kotlin.math.floor

/**
 * The Click GUI is laid out in "design px": GUI-scale-2 pixels, whatever Minecraft's GUI scale is.
 * The screen draws under one extra pose scale and divides the mouse by it, so layout code never sees the real scale.
 */
object ClickGuiDesignScale {
    /** Design px per framebuffer px; windows below 1280x720 drop to 1 so the menu keeps its minimum size. */
    @JvmStatic
    fun designScale(framebufferWidth: Int, framebufferHeight: Int): Double =
        if (framebufferWidth >= 1280 && framebufferHeight >= 720) 2.0 else 1.0

    /** Extra pose scale from design px to the current GUI px; 1 when the lock is off. */
    @JvmStatic
    fun poseScale(framebufferWidth: Int, framebufferHeight: Int, guiScale: Double, locked: Boolean): Float {
        if (!locked || guiScale <= 0.0) return 1.0f
        return (designScale(framebufferWidth, framebufferHeight) / guiScale).toFloat()
    }

    /** A GUI-px screen size expressed in design px. */
    @JvmStatic
    fun virtualSize(guiSize: Int, poseScale: Float): Int = maxOf(1, floor(guiSize / poseScale.toDouble() + 1e-6).toInt())
}
