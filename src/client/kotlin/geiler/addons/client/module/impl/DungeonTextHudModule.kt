package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.Setting
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/** Shared text-panel plumbing for dungeon modules that publish independent HUD snapshots. */
abstract class DungeonTextHudModule(
    moduleName: String,
    description: String,
    private val hudId: String,
    private val hudName: String,
    vararg settings: Setting,
) : Module(moduleName, description, Category.F7, false, *settings), HudElement {
    @Volatile
    private var hudLines: List<HudLine> = emptyList()

    fun publishHudLines(lines: List<String?>?) {
        publishHudLines(lines, DEFAULT_TITLE_COLOR)
    }

    /** Publishes a text snapshot with a caller-resolved title color and the standard body color. */
    fun publishHudLines(lines: List<String?>?, titleColorArgb: Int) {
        if (lines.isNullOrEmpty()) {
            hudLines = emptyList()
            return
        }
        hudLines = lines.mapIndexedNotNull { index, text ->
            text?.let { HudLine(it, if (index == 0) titleColorArgb else DEFAULT_BODY_COLOR) }
        }
    }

    fun clearHudLines() {
        hudLines = emptyList()
    }

    protected open fun previewLines(): List<String?> = listOf(hudName, "Preview")

    private fun currentLines(): List<HudLine> {
        if (hudLines.isNotEmpty()) return hudLines
        return previewLines().mapIndexedNotNull { index, text ->
            text?.let { HudLine(it, if (index == 0) DEFAULT_TITLE_COLOR else DEFAULT_BODY_COLOR) }
        }
    }

    override fun hudIslands(): Set<Island> = setOf(Island.CATACOMBS)

    final override fun id(): String = hudId

    final override fun displayName(): String = hudName

    override fun visible(): Boolean = isEnabled() && hudLines.isNotEmpty()

    override fun previewMode(): String = if (hudLines.isEmpty()) "sample" else "live"

    override fun width(font: Font?): Int = currentLines().maxOfOrNull { font!!.width(it.text) } ?: 1

    override fun height(font: Font?): Int = maxOf(1, currentLines().size * (font!!.lineHeight + 2))

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        drawLines(graphics, font, x, y, hudLines)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        drawLines(graphics, font, x, y, currentLines())
    }

    private fun drawLines(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, lines: List<HudLine>) {
        for ((index, line) in lines.withIndex()) {
            graphics.text(font, Component.literal(line.text), x, y + index * (font.lineHeight + 2), line.colorArgb)
        }
    }

    private data class HudLine(val text: String, val colorArgb: Int)

    companion object {
        private const val DEFAULT_TITLE_COLOR = 0xFFFFD24A.toInt()
        private const val DEFAULT_BODY_COLOR = 0xFFE5E7EB.toInt()
    }
}
