package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.mining.MineshaftRules
import geiler.addons.client.mining.MineshaftTimerState
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Setting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Times each Glacite Mineshaft visit from entering the shaft to leaving it, names the variant from the scoreboard,
 * and keeps the best and last visit of the session. Optionally prints the finished time in chat.
 */
class MineshaftTimerModule private constructor(settings: Settings) : MiningHudModule(
    "Mineshaft Timer",
    "Times your time in a Glacite Mineshaft and keeps the best of the session.",
    "mineshaft_timer",
    "Mineshaft Timer",
    listOf("§bMineshaft §7(Jade 1)", "§f2:31 §7best §a1:58 §7last §f2:12"),
    settings.display,
    settings.appearance,
) {
    private val showBest = settings.showBest
    private val showLast = settings.showLast
    private val chatSummary = settings.chatSummary

    private val timer = MineshaftTimerState()
    private var visits = 0L

    override fun hudIslands(): Set<Island> = setOf(Island.MINESHAFT)

    override fun diagnosticSettings(): String =
        "best=${showBest.value()};last=${showLast.value()};chat=${chatSummary.value()}"

    override fun onEnable() {
        timer.reset()
        visits = 0
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
        val now = System.nanoTime()
        val inShaft = mc.level != null && mc.player != null && MiningContext.on(Island.MINESHAFT)
        if (inShaft) {
            if (!timer.active()) {
                timer.enter(now)
                visits++
                diagnostics.event("SHAFT_ENTERED visits=$visits")
            }
            if (timer.shaft() == null) {
                val name = MineshaftRules.shaftName(MiningContext.sidebarLines())
                if (name != null) {
                    timer.setShaft(name)
                    diagnostics.event("SHAFT_NAMED name=$name")
                }
            }
        } else if (timer.active()) {
            val shaft = timer.shaft()
            val length = timer.leave(now)
            if (length != null) {
                diagnostics.event("SHAFT_LEFT shaft=${shaft ?: "unknown"} seconds=${length / 1_000_000_000L}")
                if (chatSummary.value()) {
                    mc.player?.sendSystemMessage(
                        Component.literal("§bMineshaft${if (shaft != null) " ($shaft)" else ""} finished in §f${MineshaftRules.clock(length)}")
                    )
                }
            }
        }
        lines = buildLines(now)
        diagnostics.statusOnChange {
            "inShaft=$inShaft active=${timer.active()} shaft=${timer.shaft()} visits=$visits best=${timer.best()?.let { it / 1_000_000_000L }}"
        }
    }

    private fun buildLines(now: Long): List<String> {
        val out = ArrayList<String>(2)
        if (timer.active()) {
            val shaft = timer.shaft()?.let { " §7($it)" }.orEmpty()
            out.add("§bMineshaft$shaft §f${MineshaftRules.clock(timer.elapsed(now))}")
        } else if (timer.last() == null) {
            return emptyList()
        } else {
            out.add("§bMineshaft")
        }
        val extra = ArrayList<String>(2)
        if (showBest.value()) timer.best()?.let { extra.add("§7best §a${MineshaftRules.clock(it)}") }
        if (showLast.value()) timer.last()?.let { extra.add("§7last §f${MineshaftRules.clock(it)}") }
        if (extra.isNotEmpty()) out.add(extra.joinToString(" "))
        return out
    }

    private class Settings {
        val showBest = BooleanSetting("Show Best", true)
        val showLast = BooleanSetting("Show Last", true)
        val chatSummary = BooleanSetting("Chat Summary", false)
        val display: List<Setting> = listOf(showBest, showLast, chatSummary)
        val appearance = MiningHudAppearance()
    }

    companion object {
        @JvmField
        val INSTANCE = MineshaftTimerModule(Settings())
    }
}
