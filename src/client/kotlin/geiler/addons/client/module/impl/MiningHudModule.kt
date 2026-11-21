package geiler.addons.client.module.impl

import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/** Appearance settings shared by every Mining panel. */
class MiningHudAppearance {
    val background = BooleanSetting("Background", true)
    val textColor = ColorSetting("Text Color", 229, 231, 235, 255)
    val backgroundColor = ColorSetting("Background Color", 20, 16, 28, 190)
    val textScale = NumberSetting("Text Scale", 0.75f, 2.0f, 1.0f)

    fun all(): Array<Setting> = arrayOf(background, textColor, backgroundColor, textScale)
}

/**
 * Shared plumbing for the Mining text panels (and the Foraging whisper panel, which passes its own category and islands): a movable multi-line HUD with the standard appearance settings, a
 * sample for the Move Elements screen, and the automatic bounded diagnostics every feature owes. Subclasses only
 * decide which lines to show.
 */
abstract class MiningHudModule(
    moduleName: String,
    description: String,
    private val hudId: String,
    private val hudName: String,
    private val sample: List<String>,
    displaySettings: List<Setting>,
    private val appearance: MiningHudAppearance,
    category: Category = Category.MINING,
    private val islands: Set<Island> = MINING_ISLANDS,
) : Module(moduleName, description, category, false, *(displaySettings + appearance.all()).toTypedArray()), HudElement {
    protected val diagnostics = ModuleDiagnostics(category, { name() }, 8, 100L, 400)

    @Volatile
    protected var lines: List<String> = emptyList()

    init {
        group(SettingGroup("Display", *displaySettings.toTypedArray()), SettingGroup("Appearance", *appearance.all()))
    }

    protected abstract fun diagnosticSettings(): String

    /** Advances the diagnostic clock; call from the client tick while enabled. */
    protected fun beginTick() = diagnostics.advance()

    override fun onEnable() {
        lines = emptyList()
        diagnostics.enable(diagnosticSettings())
    }

    override fun onDisable() {
        lines = emptyList()
        diagnostics.disable()
    }

    final override fun id(): String = hudId

    final override fun displayName(): String = hudName

    override fun visible(): Boolean = isEnabled() && lines.isNotEmpty()

    /** The islands whose mining content this panel reads; subclasses narrow it. */
    override fun hudIslands(): Set<Island> = islands

    /** The shown lines: a panel whose lines stop changing for 30 s is not in use and hides. */
    override fun activitySignature(): Any? = lines.ifEmpty { null }

    override fun previewMode(): String = if (lines.isEmpty()) "sample" else "live"

    override fun width(font: Font?): Int = widthOf(font!!, lines.ifEmpty { sample })

    override fun height(font: Font?): Int = heightOf(font!!, lines.ifEmpty { sample })

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = draw(graphics, font, x, y, lines)

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        draw(graphics, font, x, y, lines.ifEmpty { sample })

    private fun widthOf(font: Font, content: List<String>): Int =
        Math.round((content.maxOfOrNull { font.width(it) } ?: 1) * appearance.textScale.value()) + PAD * 2

    private fun heightOf(font: Font, content: List<String>): Int =
        Math.round(content.size * (font.lineHeight + 2) * appearance.textScale.value()) + PAD * 2

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, content: List<String>) {
        if (content.isEmpty()) return
        if (appearance.background.value()) {
            GuiTheme.roundedRect(
                graphics, x, y, widthOf(font, content), heightOf(font, content), 3, appearance.backgroundColor.argb(),
            )
        }
        val scale = appearance.textScale.value()
        graphics.pose().pushMatrix()
        graphics.pose().translate((x + PAD).toFloat(), (y + PAD).toFloat())
        graphics.pose().scale(scale, scale)
        for ((index, line) in content.withIndex()) {
            graphics.text(font, Component.literal(line), 0, index * (font.lineHeight + 2), appearance.textColor.argb())
        }
        graphics.pose().popMatrix()
    }

    private companion object {
        const val PAD = 4
        val MINING_ISLANDS = setOf(
            Island.DWARVEN_MINES, Island.CRYSTAL_HOLLOWS, Island.MINESHAFT, Island.DEEP_CAVERNS, Island.GOLD_MINE,
        )
    }
}
