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

    /** Returns display-only lines. Implementations must not query live services or enqueue chat. */
    fun previewLines(font: Font, width: Int): List<Component>

    companion object {
        const val DEFAULT_PREVIEW_HEIGHT = 62
    }
}
