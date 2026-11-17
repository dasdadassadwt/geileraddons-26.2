package geiler.addons.client.module.impl

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.mining.MiningHudFormat
import geiler.addons.client.mining.MiningItemText
import geiler.addons.client.mining.MiningPowderSession
import geiler.addons.client.mining.MiningTabText
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack

/**
 * Mithril, Gemstone and Glacite powder from the tab-list Powders widget, with the amount gained this session and
 * per hour, plus a fuel bar on drills. Spending powder never counts as a loss.
 */
class MiningPowderModule private constructor(settings: Settings) : MiningHudModule(
    "Mining Powder",
    "Shows your powders with session gain and per-hour rate, and drill fuel on the item.",
    "mining_powder",
    "Mining Powder",
    listOf("§2Mithril §f152,300 §7(+4,200 | 38,000/h)", "§dGemstone §f98,100 §7(+1,150 | 9,800/h)", "§bGlacite §f12,400 §7(+300 | 2,500/h)"),
    settings.display,
    settings.appearance,
) {
    private val showGain = settings.showGain
    private val showPerHour = settings.showPerHour
    private val showGlacite = settings.showGlacite
    private val drillFuelBar = settings.drillFuelBar

    private val session = MiningPowderSession()
    private var lastIsland: Island? = null
    private var tickCount = 0L
    private var readings = 0L
    private var missing = 0L

    override fun diagnosticSettings(): String =
        "gain=${showGain.value()};perHour=${showPerHour.value()};glacite=${showGlacite.value()};fuelBar=${drillFuelBar.value()}"

    override fun onEnable() {
        session.reset()
        readings = 0
        missing = 0
        super.onEnable()
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        beginTick()
        tickCount++
        val island = HypixelModApi.currentIsland()
        if (island != lastIsland) {
            if (lastIsland != null) diagnostics.event("ISLAND_CHANGED from=$lastIsland to=$island sessionReset=true")
            lastIsland = island
            session.reset()
        }
        if (mc.level == null || mc.player == null || !MiningContext.inMiningIsland()) {
            lines = emptyList()
            report("outside-mining")
            return
        }
        if (tickCount % REFRESH_TICKS != 0L && lines.isNotEmpty()) return

        val powders = MiningTabText.powders(MiningContext.tabLines())
        if (powders == null) {
            missing++
            lines = emptyList()
            report("no-widget")
            return
        }
        readings++
        val now = System.nanoTime()
        session.observe(powders, now)
        lines = MiningHudFormat.powderLines(powders, session, now, showGain.value(), showPerHour.value(), showGlacite.value())
        report("ready")
    }

    /** A fuel bar along the bottom of a drill, drawn after the item like vanilla's durability bar. */
    fun drawOnItem(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!isEnabled() || !drillFuelBar.value() || stack.isEmpty) return
        if (!SkyblockItems.skyBlockId(stack).contains("DRILL")) return
        val fuel = MiningItemText.drillFuel(SkyblockItems.loreLines(stack)) ?: return
        val fraction = fuel.fraction()
        val filled = Math.round(fraction * BAR_WIDTH).toInt().coerceIn(if (fuel.current > 0) 1 else 0, BAR_WIDTH)
        graphics.fill(x + 2, y + 13, x + 2 + BAR_WIDTH, y + 15, 0xFF000000.toInt())
        if (filled > 0) graphics.fill(x + 2, y + 13, x + 2 + filled, y + 14, MiningHudFormat.fuelColor(fraction))
    }

    private fun report(state: String) {
        diagnostics.statusOnChange {
            "state=$state island=$lastIsland readings=$readings missing=$missing lines=${lines.size} " +
                "gain=${session.gainMithril()}/${session.gainGemstone()}/${session.gainGlacite()}"
        }
    }

    private class Settings {
        val showGain = BooleanSetting("Show Gain", true)
        val showPerHour = BooleanSetting("Show Per Hour", true)
        val showGlacite = BooleanSetting("Show Glacite", true)
        val drillFuelBar = BooleanSetting("Drill Fuel Bar", true)
        val display: List<Setting> = listOf(showGain, showPerHour, showGlacite, drillFuelBar)
        val appearance = MiningHudAppearance()
    }

    companion object {
        private const val REFRESH_TICKS = 10L
        private const val BAR_WIDTH = 13

        @JvmField
        val INSTANCE = MiningPowderModule(Settings())
    }
}
