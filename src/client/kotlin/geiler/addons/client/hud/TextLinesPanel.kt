package geiler.addons.client.hud

import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.location.Island
import geiler.addons.client.module.Module
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** One colored row of a [TextLinesPanel]. */
class HudLine(val text: String, val argb: Int)

/**
 * A movable panel of colored text rows owned by a module. It shows only while [wanted] holds and there is content,
 * so a feature's panel disappears when the feature is not running.
 */
class TextLinesPanel(
    private val panelId: String,
    private val title: String,
    private val owner: Module,
    private val wanted: () -> Boolean,
    private val content: () -> List<HudLine>,
    private val sample: List<HudLine>,
    private val islands: Set<Island> = emptySet(),
    private val background: Boolean = true,
) : HudElement {
    override fun id(): String = panelId
    override fun displayName(): String = title
    override fun module(): Module = owner
    override fun hudIslands(): Set<Island> = islands
    override fun visible(): Boolean = owner.isEnabled() && wanted() && content().isNotEmpty()
    override fun width(font: Font?): Int = widthOf(font!!, content())
    override fun height(font: Font?): Int = heightOf(font!!, content())
    override fun previewWidth(font: Font?): Int = widthOf(font!!, sample)
    override fun previewHeight(font: Font?): Int = heightOf(font!!, sample)
    override fun previewMode(): String = if (visible()) "live" else "sample"
    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = draw(graphics, font, x, y, content())
    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        draw(graphics, font, x, y, if (visible()) content() else sample)

    private fun pad(): Int = if (background) 4 else 0

    private fun widthOf(font: Font, lines: List<HudLine>): Int = (lines.maxOfOrNull { font.width(it.text) } ?: 0) + pad() * 2

    private fun heightOf(font: Font, lines: List<HudLine>): Int = lines.size * (font.lineHeight + 1) + pad() * 2

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, lines: List<HudLine>) {
        if (lines.isEmpty()) return
        if (background) GuiTheme.roundedRect(graphics, x, y, widthOf(font, lines), heightOf(font, lines), 3, GuiTheme.PANEL_TOP)
        var row = y + pad()
        for (line in lines) {
            graphics.text(font, line.text, x + pad(), row, line.argb)
            row += font.lineHeight + 1
        }
    }
}
