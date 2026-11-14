package geiler.addons.client.module

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/** Optional, read-only content shown above a module's settings rows in the Click GUI. */
interface ModulePreview {
    /** Optional folded settings group that owns this preview. */
    fun previewGroupName(): String? = null

    /** Whether preview content is enabled within its settings group. */
    fun previewEnabled(): Boolean = true

    /** Preferred clipped preview height. Existing previews keep the compact Click GUI height. */
    fun previewHeight(width: Int, availableHeight: Int): Int = DEFAULT_PREVIEW_HEIGHT

    /** Draws local preview content; false falls back to the existing text-line preview. */
    fun drawPreview(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean = false

    /**
     * A mouse press inside the content area, in coordinates relative to its top-left corner. Return true to take the
     * press: the drag and the release that follow are then sent here too. Previews are read-only by default.
     */
    fun previewMousePressed(
        localX: Int, localY: Int, width: Int, height: Int, button: Int, doubleClick: Boolean, shift: Boolean,
    ): Boolean = false

    /** The mouse moved with a press taken by [previewMousePressed] still held. */
    fun previewMouseDragged(localX: Int, localY: Int, width: Int, height: Int, button: Int) = Unit

    /** The press taken by [previewMousePressed] ended. */
    fun previewMouseReleased(localX: Int, localY: Int, width: Int, height: Int, button: Int) = Unit

    /** Returns display-only lines. Implementations must not query live services or enqueue chat. */
    fun previewLines(font: Font, width: Int): List<Component>

    companion object {
        const val DEFAULT_PREVIEW_HEIGHT = 62
    }
}
