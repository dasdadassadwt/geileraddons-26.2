package geiler.addons.client.scoreboard

import geiler.addons.client.module.impl.PlayerPresentationModule
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.StyledFormat
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import java.util.ArrayList
import java.util.Comparator

/** Client-thread capture for the immutable Java boundary record. */
object ScoreboardSnapshotCapture {
    @JvmStatic
    fun capture(minecraft: Minecraft?): ScoreboardSnapshot {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return ScoreboardSnapshot.empty()
        val scoreboard: Scoreboard = minecraft.level!!.scoreboard
        val objective: Objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return ScoreboardSnapshot.empty()

        val ordered = ArrayList<PlayerScoreEntry>()
        for (entry in scoreboard.listPlayerScores(objective)) {
            if (entry != null && !entry.isHidden()) ordered.add(entry)
        }
        ordered.sortWith(
            Comparator.comparingInt<PlayerScoreEntry> { it.value() }
                .reversed()
                .thenComparing { it.owner() },
        )

        val rows = ArrayList<ScoreboardSnapshot.Entry>(minOf(15, ordered.size))
        for (score in ordered) {
            if (rows.size >= 15) break
            var label: Component? = score.display()
            if (label == null) {
                val owner: Component = score.ownerName()
                val team: PlayerTeam? = scoreboard.getPlayersTeam(score.owner())
                label = if (team == null) owner else team.getFormattedName(owner)
            }
            // Custom score display components bypass PlayerTeam.formatNameForTeam. The score owner is
            // still available, so apply the same username-token transform before snapshotting the row.
            label = PlayerPresentationModule.replaceRenderedName(score.owner(), label)
            val value = score.formatValue(objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT))
            rows.add(ScoreboardSnapshot.Entry(score.owner(), label, value))
        }
        return ScoreboardSnapshot(true, objective.formattedDisplayName, rows)
    }
}
