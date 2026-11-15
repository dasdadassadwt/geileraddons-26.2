package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.TickTimersLogic
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.Setting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Boss-room tick timers for Goldor, Storm and the purple pillar (Odin's Tick Timers).
 * Chat lines start the timers; they move on the server-tick clock the run splits already keep.
 */
class TickTimersModule private constructor(private val s: Settings) : Module(
    "Tick Timers",
    "Displays timers for Necron, Goldor, Storm, Outbounds and Secrets.",
    Category.F7,
    false,
    s.displayInTicks, s.displaySymbol, s.showPrefix,
    s.goldorHud, s.startTimer, s.stormPadHud, s.stormLightningHud, s.stormPyHud, s.stormTickHud,
) {
    private val logic = TickTimersLogic()
    private var lastLevel: Any? = null
    private var lastServerTicks = -1L
    private var ticksApplied = 0L
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun isSettingVisible(setting: Setting): Boolean =
        setting !== s.startTimer || s.goldorHud.value()

    override fun onEnable() {
        logic.reset()
        lastServerTicks = -1L
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        logic.reset()
        diagnostics.disable()
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        val before = snapshot()
        logic.onChat(plain)
        val after = snapshot()
        if (before != after) diagnostics.event("CHAT_TIMER message=${plain.take(80)} timers=$after")
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level !== lastLevel) {
            lastLevel = level
            logic.reset()
            lastServerTicks = -1L
            diagnostics.event("WORLD_CHANGED levelPresent=${level != null} timersReset=true")
        }
        val now = DungeonOdinAssistModule.INSTANCE.serverTicks()
        val delta = if (lastServerTicks < 0L || now < lastServerTicks) 0L else minOf(now - lastServerTicks, MAX_CATCH_UP)
        lastServerTicks = now
        val inBoss = DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase()
        for (i in 0 until delta) logic.onServerTick(inBoss, s.goldorHud.value(), s.stormPadHud.value())
        ticksApplied += if (inBoss) delta else 0L
        diagnostics.status {
            "inBoss=$inBoss serverTicks=$now ticksApplied=$ticksApplied timers=${snapshot()} suppressedEvents=$it"
        }
    }

    private fun snapshot(): String =
        "goldor=${logic.goldorTick}/${logic.goldorStart},pad=${logic.pad},lightning=${logic.lightning}," +
            "py=${logic.py},storm=${logic.stormTick}"

    private fun settingsText(): String =
        "ticks=${s.displayInTicks.value()} symbol=${s.displaySymbol.value()} prefix=${s.showPrefix.value()} " +
            "goldor=${s.goldorHud.value()} start=${s.startTimer.value()} pad=${s.stormPadHud.value()} " +
            "lightning=${s.stormLightningHud.value()} py=${s.stormPyHud.value()} " +
            "stormTick=${s.stormTickHud.value()}"

    private fun fmt(time: Int, max: Int, prefix: String, override: String? = null) = TickTimersLogic.format(
        time, max, prefix, override, s.displayInTicks.value(), s.displaySymbol.value(), s.showPrefix.value(),
    )

    private fun goldorText(): String? = when {
        logic.goldorStart >= 0 && s.startTimer.value() -> fmt(logic.goldorStart, 64, "§aStart:")
        logic.goldorTick >= 0 -> fmt(logic.goldorTick, 60, "§7Tick:")
        else -> null
    }

    private fun padText() = if (logic.pad >= 0) fmt(logic.pad, 20, "§bPad:") else null

    private fun lightningText() = if (logic.lightning >= 0) fmt(logic.lightning, 520, "§bLightning:") else null

    private fun pyText() = if (logic.py >= 0) fmt(logic.py, 55, "§bPY:") else null

    private fun stormTickText() = if (logic.stormTick >= 0) fmt(logic.stormTick, 540, "§bStorm:") else null

    /** One movable text panel; [live] is null while its timer is idle, [sample] feeds the Move Elements editor. */
    private inner class TimerHud(
        private val id: String,
        private val title: String,
        private val toggle: BooleanSetting,
        private val sample: () -> String,
        private val live: () -> String?,
    ) : HudElement {
        override fun id(): String = id
        override fun displayName(): String = title
        override fun width(font: Font?): Int = font!!.width(live() ?: sample())
        override fun height(font: Font?): Int = font!!.lineHeight
        override fun previewWidth(font: Font?): Int = font!!.width(sample())
        override fun previewHeight(font: Font?): Int = font!!.lineHeight
        override fun visible(): Boolean = isEnabled() && toggle.value() && live() != null
        override fun previewMode(): String = if (visible()) "live" else "sample"

        override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
            graphics.text(font, Component.literal(live() ?: return), x, y, DEFAULT_COLOR)
        }

        override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
            graphics.text(font, Component.literal(live() ?: sample()), x, y, DEFAULT_COLOR)
        }
    }

    val goldorHud: HudElement = TimerHud("tick_timers_goldor", "Goldor Hud", s.goldorHud,
        { fmt(35, 60, "§7Tick:") }, ::goldorText)
    val padHud: HudElement = TimerHud("tick_timers_storm_pad", "Storm Pad Hud", s.stormPadHud,
        { fmt(15, 20, "§bPad:") }, ::padText)
    val lightningHud: HudElement = TimerHud("tick_timers_storm_lightning", "Storm Lightning Hud", s.stormLightningHud,
        { fmt(520, 520, "§bLightning:") }, ::lightningText)
    val pyHud: HudElement = TimerHud("tick_timers_storm_py", "Storm PY Hud", s.stormPyHud,
        { fmt(55, 55, "§bPY:") }, ::pyText)
    val stormTickHud: HudElement = TimerHud("tick_timers_storm_tick", "Storm Tick Hud", s.stormTickHud,
        { fmt(200, 540, "§bStorm:", "§a") }, ::stormTickText)

    class Settings {
        val displayInTicks = BooleanSetting("Display in Ticks", false)
        val displaySymbol = BooleanSetting("Display Symbol", true)
        val showPrefix = BooleanSetting("Show Prefix", true)
        val goldorHud = BooleanSetting("Goldor Hud", true)
        val startTimer = BooleanSetting("Start timer", true)
        val stormPadHud = BooleanSetting("Storm Pad Hud", true)
        val stormLightningHud = BooleanSetting("Storm Lightning Hud", false)
        val stormPyHud = BooleanSetting("Storm PY Hud", true)
        val stormTickHud = BooleanSetting("Storm Tick Hud", false)
    }

    companion object {
        private const val MAX_CATCH_UP = 40L
        private const val DEFAULT_COLOR = 0xFFAA0000.toInt()

        @JvmField
        val INSTANCE = TickTimersModule(Settings())
    }
}
