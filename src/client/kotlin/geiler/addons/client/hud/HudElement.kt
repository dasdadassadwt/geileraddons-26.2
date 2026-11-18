package geiler.addons.client.hud

import geiler.addons.client.location.Island
import geiler.addons.client.module.Module
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** A movable panel on the HUD. Registered once with [HudManager], which owns its position. */
interface HudElement {
    /** Stable key this element's position is saved under; renaming one loses its placement. */
    fun id(): String

    /** Shown in the Move Elements screen, where the element may not be drawing anything yet. */
    fun displayName(): String

    /** Module whose page configures this element; its enabled state gates the element in Move Elements. */
    fun module(): Module? = this as? Module

    /** Islands this element belongs on; empty means anywhere. The live HUD hides it elsewhere (never on an unknown island). */
    fun hudIslands(): Set<Island> = emptySet()

    /** A value that changes while the element is in use; the live HUD hides it after 30 s without a change. Null never expires. */
    fun activitySignature(): Any? = null

    fun width(font: Font?): Int

    fun height(font: Font?): Int

    /** Width of the editor sample; live elements can use their regular dimensions. */
    fun previewWidth(font: Font?): Int = width(font)

    /** Height of the editor sample; live elements can use their regular dimensions. */
    fun previewHeight(font: Font?): Int = height(font)

    /** Whether this element is active; Move Elements shows its name when it is inactive. */
    fun visible(): Boolean

    /** Preview source label for the bounded Move Elements diagnostic summary. */
    fun previewMode(): String = if (visible()) "live" else "label"

    /** Whether active content belongs on the live HUD; false is useful for editor-only previews. */
    fun renderOnHud(): Boolean = true

    fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int)

    /**
     * Editor rendering, which may provide a sample when the live widget has no current data.
     * Implementations can override this without changing the live-HUD renderer.
     */
    fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        if (visible()) {
            render(graphics, font, x, y)
        } else {
            graphics.text(font, displayName(), x, y, 0xFFB8C0CC.toInt())
        }
    }
}
