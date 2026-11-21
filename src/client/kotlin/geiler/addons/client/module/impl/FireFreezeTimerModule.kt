package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.FireFreezeTimerLogic
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Countdown for the M3 Fire Freeze Staff use after the Professor's guardian line (the fire freeze timer of Odin's
 * Tick Timers, as its own module). The chat line starts it; it moves on the server-tick clock.
 */
class FireFreezeTimerModule private constructor(private val s: Settings) : Module(
    "Fire Freeze Timer",
    "Counts down to when you should use the Fire Freeze Staff in M3.",
    Category.F7,
    false,
    s.displayInTicks, s.displaySymbol, s.showPrefix,
), HudElement {
    private val logic = FireFreezeTimerLogic()
    private var lastLevel: Any? = null
    private var lastServerTicks = -1L
    private var ticksApplied = 0L
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        logic.reset()
        lastServerTicks = -1L
        diagnostics.enable(
            "ticks=${s.displayInTicks.value()} symbol=${s.displaySymbol.value()} prefix=${s.showPrefix.value()} sample=$SAMPLE",
        )
    }

    override fun onDisable() {
        logic.reset()
        diagnostics.disable()
    }

    private fun inBoss() = DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase()

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        val before = logic.ticks
        logic.onChat(plain)
        if (logic.ticks != before) {
            diagnostics.event("PROFESSOR_LINE timerTicks=${logic.ticks} inBoss=${inBoss()}")
        }
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            logic.reset()
            lastServerTicks = -1L
            diagnostics.event("WORLD_CHANGED levelPresent=${level != null} timerReset=true")
        }
        val now = DungeonAssistModule.INSTANCE.serverTicks()
        val delta = if (lastServerTicks < 0L || now < lastServerTicks) 0L else minOf(now - lastServerTicks, MAX_CATCH_UP)
        lastServerTicks = now
        val boss = inBoss()
        for (i in 0 until delta) logic.onServerTick(boss)
        ticksApplied += if (boss) delta else 0L
        diagnostics.status {
            "inBoss=$boss serverTicks=$now ticksApplied=$ticksApplied timerTicks=${logic.ticks} suppressedEvents=$it"
        }
    }

    private fun text(): String? = logic.text(s.displayInTicks.value(), s.displaySymbol.value(), s.showPrefix.value())

    override fun hudIslands(): Set<Island> = setOf(Island.CATACOMBS)

    override fun id(): String = "fire_freeze_timer"
    override fun displayName(): String = "Fire Freeze Timer"
    override fun width(font: Font?): Int = font!!.width(text() ?: SAMPLE)
    override fun height(font: Font?): Int = font!!.lineHeight
    override fun previewWidth(font: Font?): Int = font!!.width(SAMPLE)
    override fun visible(): Boolean = isEnabled() && text() != null
    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text() ?: return), x, y, DEFAULT_COLOR)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text() ?: SAMPLE), x, y, DEFAULT_COLOR)
    }

    class Settings {
        val displayInTicks = BooleanSetting("Display in Ticks", false)
        val displaySymbol = BooleanSetting("Display Symbol", true)
        val showPrefix = BooleanSetting("Show Prefix", true)
    }

    companion object {
        private const val MAX_CATCH_UP = 40L
        private const val DEFAULT_COLOR = 0xFFAA0000.toInt()
        private const val SAMPLE = "Fire Freeze: §a2.50s"

        @JvmField
        val INSTANCE = FireFreezeTimerModule(Settings())
    }
}
