package geiler.addons.client.module.impl

import geiler.addons.client.chat.ChatTpsTracker
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.performance.PerformanceHudRules
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** A movable panel showing ping, TPS and FPS, each independently switchable. */
class PerformanceHudModule private constructor(settings: Settings) : Module(
    "Performance HUD",
    "Shows ping, TPS and FPS as plain text or a Click GUI styled panel.",
    Category.MISCELLANEOUS,
    false,
    settings.showPing,
    settings.showTps,
    settings.showFps,
    settings.style,
    settings.textColor,
), HudElement {
    private val showPing = settings.showPing
    private val showTps = settings.showTps
    private val showFps = settings.showFps
    private val style = settings.style
    private val textColor = settings.textColor

    private val diagnostics = ModuleDiagnostics(
        Category.MISCELLANEOUS, { name() }, MAX_DIAGNOSTIC_EVENTS_PER_TICK, STATUS_INTERVAL_TICKS, MAX_DIAGNOSTIC_LINE_CHARS,
    )
    private var renderedCallsSinceStatus = 0
    private var lastLines = "none"

    override fun id(): String = "performance_hud"

    override fun displayName(): String = "Performance HUD"

    override fun visible(): Boolean = isEnabled()

    override fun previewMode(): String = "live"

    override fun width(font: Font?): Int {
        val lines = lines()
        val textWidth = lines.maxOfOrNull { font!!.width(it) } ?: 1
        return if (panelStyle()) textWidth + PADDING * 2 else textWidth
    }

    override fun height(font: Font?): Int {
        val count = lines().size
        if (count == 0) return 1
        val text = count * font!!.lineHeight + (count - 1) * LINE_GAP
        return if (panelStyle()) text + PADDING * 2 else text
    }

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val lines = lines()
        draw(graphics, font, x, y, lines)
        if (isEnabled()) {
            if (renderedCallsSinceStatus < MAX_DIAGNOSTIC_COUNT) renderedCallsSinceStatus++
            lastLines = lines.joinToString(" | ").take(MAX_DIAGNOSTIC_TEXT_CHARS).ifEmpty { "none" }
        }
    }

    /** Editor samples stay live but do not affect runtime render diagnostics. */
    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        draw(graphics, font, x, y, lines())
    }

    override fun onEnable() {
        renderedCallsSinceStatus = 0
        lastLines = "none"
        diagnostics.enable(diagnosticState())
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    private fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { suppressed ->
            renderedCallsSinceStatus.let { calls ->
                renderedCallsSinceStatus = 0
                "intervalTicks=$STATUS_INTERVAL_TICKS ${diagnosticState(calls)} suppressedEvents=$suppressed"
            }
        }
    }

    private fun panelStyle(): Boolean = style.value() == STYLE_PANEL

    private fun lines(): List<String> {
        val client = Minecraft.getInstance()
        val uuid = client.player?.uuid
        val ping = if (uuid == null) null else client.connection?.getPlayerInfo(uuid)?.latency
        val tps = ChatTpsTracker.metrics(System.nanoTime()).currentTps
        return PerformanceHudRules.lines(
            showPing.value(), showTps.value(), showFps.value(), ping, tps, client.fps,
        )
    }

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, lines: List<String>) {
        if (lines.isEmpty()) return
        val panel = panelStyle()
        var textX = x
        var textY = y
        if (panel) {
            GuiTheme.roundedRectBordered(
                graphics, x, y, width(font), height(font), GuiTheme.RADIUS_SMALL,
                GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
            )
            textX += PADDING
            textY += PADDING
        }
        for ((index, line) in lines.withIndex()) {
            val lineY = textY + index * (font.lineHeight + LINE_GAP)
            if (panel) {
                val split = line.indexOf(':') + 1
                graphics.text(font, line.substring(0, split), textX, lineY, GuiTheme.TEXT_SECONDARY)
                graphics.text(font, line.substring(split), textX + font.width(line.substring(0, split)), lineY, GuiTheme.TEXT_PRIMARY)
            } else {
                graphics.text(font, line, textX, lineY, textColor.argb())
            }
        }
    }

    private fun diagnosticState(liveRenderCalls: Int = renderedCallsSinceStatus): String =
        "enabled=${isEnabled()} style=${style.value()} ping=${showPing.value()} tps=${showTps.value()} " +
            "fps=${showFps.value()} liveRenderCalls=$liveRenderCalls lastLines=$lastLines previewCalls=excluded"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Performance HUD")
        val showPing: BooleanSetting = spec.toggle("showPing")
        val showTps: BooleanSetting = spec.toggle("showTps")
        val showFps: BooleanSetting = spec.toggle("showFps")
        val style: ChoiceSetting = spec.choice("style")
        val textColor: ColorSetting = spec.color("textColor")
    }

    companion object {
        private const val STYLE_PANEL = "Click GUI"
        private const val PADDING = 4
        private const val LINE_GAP = 2
        private const val STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_COUNT = 1_000_000
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 8
        private const val MAX_DIAGNOSTIC_LINE_CHARS = 512
        private const val MAX_DIAGNOSTIC_TEXT_CHARS = 48

        @JvmField
        val INSTANCE = PerformanceHudModule(Settings())

        init {
            ClientTickEvents.END_CLIENT_TICK.register { INSTANCE.tick() }
        }
    }
}
