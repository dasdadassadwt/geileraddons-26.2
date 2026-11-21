package geiler.addons.client.module.impl

import geiler.addons.client.foraging.ForagingWhispers
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Setting
import net.minecraft.client.Minecraft

/**
 * Forest and Desert Whispers from the tab-list widget, with the amount gained this session and per hour: Foraging's
 * counterpart of Mining Powder. Spending whispers on the Heart of the Forest never counts as a loss.
 */
class ForagingPowderModule private constructor(settings: Settings) : MiningHudModule(
    "Foraging Powder",
    "Shows your Forest and Desert Whispers with session gain and per-hour rate.",
    "foraging_powder",
    "Foraging Powder",
    listOf("§2Forest §f12,300 §7(+420 | 3,800/h)", "§6Desert §f4,100 §7(+150 | 1,200/h)"),
    settings.display,
    settings.appearance,
    Category.FORAGING,
    emptySet(),
) {
    private val showGain = settings.showGain
    private val showPerHour = settings.showPerHour
    private val session = ForagingWhispers.Session()
    private var tickCount = 0L
    private var readings = 0L
    private var missing = 0L

    override fun activitySignature(): Any? = if (lines.isEmpty()) null else Pair(session.gainForest(), session.gainDesert())

    override fun diagnosticSettings(): String = "gain=${showGain.value()};perHour=${showPerHour.value()}"

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
        if (mc.level == null || mc.player == null) {
            if (lines.isNotEmpty()) session.reset()
            lines = emptyList()
            report("no-world")
            return
        }
        if (tickCount % REFRESH_TICKS != 0L && lines.isNotEmpty()) return
        val reading = ForagingWhispers.read(MiningContext.tabLines())
        if (reading == null) {
            missing++
            lines = emptyList()
            report("no-widget")
            return
        }
        readings++
        val now = System.nanoTime()
        session.observe(reading, now)
        lines = ForagingWhispers.lines(reading, session, now, showGain.value(), showPerHour.value())
        report("ready")
    }

    private fun report(state: String) {
        diagnostics.statusOnChange {
            "state=$state readings=$readings missing=$missing lines=${lines.size} gain=${session.gainForest()}/${session.gainDesert()}"
        }
    }

    private class Settings {
        val showGain = BooleanSetting("Show Gain", true)
        val showPerHour = BooleanSetting("Show Per Hour", true)
        val display: List<Setting> = listOf(showGain, showPerHour)
        val appearance = MiningHudAppearance()
    }

    companion object {
        private const val REFRESH_TICKS = 10L

        @JvmField
        val INSTANCE = ForagingPowderModule(Settings())
    }
}
