package geiler.addons.client.mining

import geiler.addons.client.farming.PestWidgetReader
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island

/** Where the player is, for every Mining module: one place so the island rules cannot drift apart. */
object MiningContext {
    /** Islands that have mining content the Mining HUDs read. */
    @JvmStatic
    fun inMiningIsland(): Boolean = when (HypixelModApi.currentIsland()) {
        Island.DWARVEN_MINES, Island.CRYSTAL_HOLLOWS, Island.MINESHAFT, Island.DEEP_CAVERNS, Island.GOLD_MINE -> true
        else -> false
    }

    @JvmStatic
    fun on(island: Island): Boolean = HypixelModApi.currentIsland() == island

    /** The sidebar scoreboard as plain lines (title first), or empty when none is shown. */
    @JvmStatic
    fun sidebarLines(): List<String> {
        val scoreboard = net.minecraft.client.Minecraft.getInstance().level?.scoreboard ?: return emptyList()
        val sidebar = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR) ?: return emptyList()
        val rows = ArrayList<String>()
        rows.add(geiler.addons.client.tree.ChatText.plain(sidebar.displayName.string))
        for (entry in scoreboard.listPlayerScores(sidebar)) {
            if (entry == null || entry.isHidden()) continue
            val component = entry.display() ?: run {
                val team = scoreboard.getPlayersTeam(entry.owner())
                if (team == null) entry.ownerName() else team.getFormattedName(entry.ownerName())
            }
            val line = component.string
            if (!line.isNullOrBlank()) rows.add(geiler.addons.client.tree.ChatText.plain(line))
        }
        return rows
    }

    /** The tab list in vanilla order, or empty off the client thread or outside a world. */
    @JvmStatic
    fun tabLines(): List<String> = PestWidgetReader.currentTabList()
}
