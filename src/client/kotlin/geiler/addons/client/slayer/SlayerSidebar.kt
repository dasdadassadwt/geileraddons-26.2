package geiler.addons.client.slayer

import geiler.addons.client.tree.ChatText
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard

/** Reads the sidebar as plain text, one row per line, for `SlayerScoreboardParser`. */
object SlayerSidebar {
    fun read(scoreboard: Scoreboard?): String {
        if (scoreboard == null) return ""
        val sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return ""
        val text = StringBuilder(ChatText.plain(sidebar.displayName.string))
        for (entry in scoreboard.listPlayerScores(sidebar)) {
            if (entry == null || entry.isHidden()) continue
            val display = entry.display()
            val line = display ?: run {
                val team: PlayerTeam? = scoreboard.getPlayersTeam(entry.owner())
                team?.getFormattedName(entry.ownerName()) ?: entry.ownerName()
            }
            text.append('\n').append(ChatText.plain(line.string))
        }
        return text.toString()
    }
}
