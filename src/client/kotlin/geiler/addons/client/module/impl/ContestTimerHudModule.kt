package geiler.addons.client.module.impl

import geiler.addons.client.farming.PestWidgetReader
import geiler.addons.client.foraging.ForagingRules
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** An always-visible countdown to the next SkyBlock day (every 20 real minutes), for Safari critter tickets. */
class ContestTimerHudModule private constructor(settings: Settings) : Module(
    "Contest Timer",
    "Shows a countdown to the next SkyBlock day, which comes every 20 minutes.",
    Category.FORAGING,
    settings.display, settings.color,
), HudElement {
    private val color = settings.color
    private val display = settings.display
    private var agatha: String? = null
    private val diagnostics = ModuleDiagnostics(Category.FORAGING, { name() }, 8, 200L, 256)
    private var lastSeconds = -1
    private var ticks = 0
    private var days = 0L

    override fun id(): String = "contest_timer"

    override fun displayName(): String = "Contest Timer"

    override fun visible(): Boolean = isEnabled()

    override fun previewMode(): String = "live"

    override fun width(font: Font?): Int = font!!.width(text())

    override fun height(font: Font?): Int = font!!.lineHeight

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, text(), x, y, color.argb())
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = render(graphics, font, x, y)

    private fun seconds(): Int = ForagingRules.secondsToNextDay(System.currentTimeMillis())

    private fun dayText(): String = "Next day: ${ForagingRules.clock(seconds())}"

    /** Contest mode shows the Agatha's Contest tab widget as Hypixel words it; without the widget it shows the day clock. */
    private fun text(): String =
        if (display.value() == CONTEST) agatha?.let { "Contest: $it" } ?: dayText() else dayText()

    override fun onEnable() {
        lastSeconds = -1
        days = 0
        diagnostics.enable("display=${display.value()},colorArgb=${color.argb()}")
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    private fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val now = seconds()
        // The countdown wrapping from 1 up to 1200 is one SkyBlock day starting.
        if (lastSeconds in 0 until now) {
            days++
            diagnostics.event("DAY_START seconds=$now daysSinceEnable=$days")
        }
        lastSeconds = now
        if (display.value() == CONTEST && ticks++ % WIDGET_TICKS == 0) {
            val read = ForagingRules.agathaContest(PestWidgetReader.currentTabList())
            if (read != agatha) diagnostics.event("CONTEST_WIDGET ${read ?: "absent"}")
            agatha = read
        }
        diagnostics.status { suppressed ->
            "display=${display.value()} secondsToNextDay=$now dayLengthSeconds=${ForagingRules.DAY_SECONDS} daysSinceEnable=$days " +
                "agatha=${agatha ?: "none"} colorArgb=${color.argb()} suppressedEvents=$suppressed"
        }
    }

    class Settings {
        val display = ChoiceSetting("Display", NEXT_DAY, NEXT_DAY, CONTEST)
        val color = ColorSetting("Text Color", 255, 170, 0)
    }

    companion object {
        const val NEXT_DAY = "Next Day"
        const val CONTEST = "Agatha's Contest"
        private const val WIDGET_TICKS = 20

        @JvmField
        val INSTANCE = ContestTimerHudModule(Settings())

        init {
            ClientTickEvents.END_CLIENT_TICK.register { INSTANCE.tick() }
        }
    }
}
