package geiler.addons.client.module.impl

import geiler.addons.client.mining.MiningContext
import geiler.addons.client.mining.MiningHudFormat
import geiler.addons.client.mining.MiningItemText
import geiler.addons.client.mining.MiningTabText
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Lists the active mining commissions with their progress and nothing else (no rewards). The tab-list Commissions
 * widget is the source; when the tab does not show it, the last Commissions menu the player opened is used.
 */
class MiningCommissionsModule private constructor(settings: Settings) : MiningHudModule(
    "Mining Commissions",
    "Shows your commissions and their progress, without the rewards.",
    "mining_commissions",
    "Mining Commissions",
    listOf("§6Commissions", "§7Mithril Miner §e31.1%", "§7Goblin Slayer §c8%", "§a✔ Titanium Miner"),
    settings.display,
    settings.appearance,
) {
    private val hideCompleted = settings.hideCompleted
    private val highlightCompleted = settings.highlightCompleted

    private var menuCommissions: List<MiningTabText.Commission> = emptyList()
    private var source = "none"
    private var tickCount = 0L

    override fun diagnosticSettings(): String =
        "hideCompleted=${hideCompleted.value()};highlightCompleted=${highlightCompleted.value()}"

    override fun onDisable() {
        super.onDisable()
        menuCommissions = emptyList()
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
        if (mc.level == null || mc.player == null || !MiningContext.inMiningIsland()) {
            lines = emptyList()
            source = "outside-mining"
            report(0)
            return
        }
        readMenu(mc)
        if (tickCount % REFRESH_TICKS != 0L && lines.isNotEmpty()) return

        val fromTab = MiningTabText.commissions(MiningContext.tabLines())
        val commissions = if (fromTab.isNotEmpty()) fromTab else menuCommissions
        source = when {
            fromTab.isNotEmpty() -> "tab"
            menuCommissions.isNotEmpty() -> "menu"
            else -> "none"
        }
        lines = MiningHudFormat.commissionLines(commissions, hideCompleted.value(), highlightCompleted.value())
        report(commissions.size)
    }

    /** Remembers the Commissions menu while it is open, so the panel still works when the tab list omits the widget. */
    private fun readMenu(mc: Minecraft) {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        if (!screen.title.string.trim().equals("Commissions", ignoreCase = true)) return
        val read = ArrayList<MiningTabText.Commission>()
        for (slot in screen.menu.slots) {
            val stack = slot.item
            if (stack.isEmpty) continue
            val commission = MiningItemText.commissionFromMenu(stack.hoverName.string, SkyblockItems.loreLines(stack)) ?: continue
            read.add(commission)
        }
        if (read.isNotEmpty() && read.map { it.name to it.progressText() } != menuCommissions.map { it.name to it.progressText() }) {
            menuCommissions = read
            diagnostics.event("MENU_READ commissions=${read.size} done=${read.count { it.done }}")
        }
    }

    private fun report(count: Int) {
        diagnostics.statusOnChange { "source=$source commissions=$count lines=${lines.size} menuCached=${menuCommissions.size}" }
    }

    private class Settings {
        val hideCompleted = BooleanSetting("Hide Completed", false)
        val highlightCompleted = BooleanSetting("Highlight Completed", true)
        val display: List<Setting> = listOf(hideCompleted, highlightCompleted)
        val appearance = MiningHudAppearance()
    }

    companion object {
        private const val REFRESH_TICKS = 10L

        @JvmField
        val INSTANCE = MiningCommissionsModule(Settings())
    }
}
