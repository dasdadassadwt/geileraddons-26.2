package geiler.addons.client.module.impl

import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.SystemClockFormat
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** A movable clock rendered from the local system clock and operating-system time zone. */
class SystemClockHudModule private constructor(settings: Settings) : Module(
    "System Clock",
    "Shows the current local system time.",
    Category.MISCELLANEOUS,
    false,
    settings.timeFormat,
    settings.showSeconds,
    settings.textColor,
    settings.showBackground,
    settings.backgroundColor,
), HudElement {
    private val timeFormat = settings.timeFormat
    private val showSeconds = settings.showSeconds
    private val textColor = settings.textColor
    private val showBackground = settings.showBackground
    private val backgroundColor = settings.backgroundColor

    private val diagnostics = ModuleDiagnostics(
        Category.MISCELLANEOUS, { name() }, MAX_DIAGNOSTIC_EVENTS_PER_TICK, STATUS_INTERVAL_TICKS, MAX_DIAGNOSTIC_LINE_CHARS,
    )
    private var renderedCallsSinceStatus = 0
    private var lastRenderedText = "none"
    private var lastRenderedX = 0
    private var lastRenderedY = 0
    private var lastRenderedWidth = 0
    private var lastRenderedHeight = 0

    override fun id(): String = "system_clock"

    override fun displayName(): String = "System Clock"

    override fun width(font: Font?): Int = font!!.width(time()) +
        if (showBackground.value()) BACKGROUND_PADDING * 2 else 0

    override fun height(font: Font?): Int = font!!.lineHeight +
        if (showBackground.value()) BACKGROUND_PADDING * 2 else 0

    override fun visible(): Boolean = isEnabled()

    override fun previewMode(): String = "live"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val text = time()
        drawClock(graphics, font, x, y, text)
        if (isEnabled()) recordLiveRender(font, text, x, y)
    }

    /** Editor samples stay live but do not affect runtime render diagnostics. */
    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        drawClock(graphics, font, x, y, time())
    }

    override fun onEnable() {
        renderedCallsSinceStatus = 0
        lastRenderedText = "none"
        lastRenderedX = 0
        lastRenderedY = 0
        lastRenderedWidth = 0
        lastRenderedHeight = 0
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

    private fun drawClock(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, text: String) {
        var textX = x
        var textY = y
        if (showBackground.value()) {
            val right = x + font.width(text) + BACKGROUND_PADDING * 2
            val bottom = y + font.lineHeight + BACKGROUND_PADDING * 2
            graphics.fill(x, y, right, bottom, backgroundColor.argb())
            textX += BACKGROUND_PADDING
            textY += BACKGROUND_PADDING
        }
        graphics.text(font, text, textX, textY, textColor.argb())
    }

    private fun recordLiveRender(font: Font, text: String, x: Int, y: Int) {
        if (renderedCallsSinceStatus < MAX_DIAGNOSTIC_COUNT) renderedCallsSinceStatus++
        lastRenderedText = bounded(text, MAX_DIAGNOSTIC_TEXT_CHARS)
        lastRenderedX = x
        lastRenderedY = y
        lastRenderedWidth = font.width(text) + if (showBackground.value()) BACKGROUND_PADDING * 2 else 0
        lastRenderedHeight = font.lineHeight + if (showBackground.value()) BACKGROUND_PADDING * 2 else 0
    }

    private fun diagnosticState(liveRenderCalls: Int = renderedCallsSinceStatus): String {
        val zone = bounded(ZoneId.systemDefault().id, MAX_DIAGNOSTIC_ZONE_CHARS)
        val now = bounded(time(), MAX_DIAGNOSTIC_TEXT_CHARS)
        return "enabled=${isEnabled()} format=${timeFormat.value()} showSeconds=${showSeconds.value()} " +
            "localTime=$now zone=$zone showBackground=${showBackground.value()} " +
            "textArgb=${argbHex(textColor.argb())} backgroundArgb=${argbHex(backgroundColor.argb())} " +
            "liveRenderCalls=$liveRenderCalls lastText=$lastRenderedText " +
            "lastPosition=${lastRenderedX},${lastRenderedY} " +
            "lastSize=${lastRenderedWidth}x$lastRenderedHeight previewCalls=excluded"
    }

    private fun time(): String = SystemClockFormat.format(
        LocalTime.now(), timeFormat.value(), showSeconds.value(),
    )

    private fun bounded(value: String, limit: Int): String =
        value.replace('\r', ' ').replace('\n', ' ').take(limit)

    private fun argbHex(color: Int): String =
        "0x${Integer.toHexString(color).uppercase(Locale.ROOT).padStart(8, '0')}"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("System Clock")
        val timeFormat = spec.choice("timeFormat")
        val showSeconds = spec.toggle("showSeconds")
        val textColor = spec.color("textColor")
        val showBackground = spec.toggle("showBackground")
        val backgroundColor = spec.color("backgroundColor")
    }

    companion object {
        private const val BACKGROUND_PADDING = 2
        private const val STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_COUNT = 1_000_000
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 8
        private const val MAX_DIAGNOSTIC_LINE_CHARS = 512
        private const val MAX_DIAGNOSTIC_TEXT_CHARS = 16
        private const val MAX_DIAGNOSTIC_ZONE_CHARS = 64

        @JvmField
        val INSTANCE = SystemClockHudModule(Settings())

        init {
            ClientTickEvents.END_CLIENT_TICK.register { INSTANCE.tick() }
        }
    }
}
