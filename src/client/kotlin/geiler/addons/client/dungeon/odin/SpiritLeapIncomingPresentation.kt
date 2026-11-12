package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.odin.OdinDungeonPlayerMap.TabPlayer
import geiler.addons.client.party.PartyMember
import java.util.Optional

/** Formats a correlated incoming-leap title using party membership and tab-only class data. */
class SpiritLeapIncomingPresentation private constructor() {
    @JvmRecord
    data class Title(val playerName: String?, val className: String?, val durationTicks: Int) {
        fun text(): String = "Leap arrival: $playerName · $className"

        override fun toString() =
            "Title[playerName=$playerName, className=$className, durationTicks=$durationTicks]"
    }

    companion object {
        const val DEFAULT_DURATION_TICKS = 60
        const val MIN_DURATION_TICKS = 20
        const val MAX_DURATION_TICKS = 200

        @JvmStatic
        fun title(
            incoming: SpiritLeapIncomingRules.Incoming?,
            currentMembers: List<PartyMember?>?,
            currentTabPlayers: List<TabPlayer?>?,
            configuredDurationTicks: Int,
        ): Optional<Title> {
            if (incoming == null || currentMembers == null) return Optional.empty()
            val teammate = currentMembers.firstOrNull { member ->
                member != null && member.name() != null && member.name().equals(incoming.teammateName, ignoreCase = true)
            } ?: return Optional.empty()
            val tabPlayer = currentTabPlayers?.firstOrNull { member ->
                member != null && member.name() != null && member.name().equals(teammate.name(), ignoreCase = true)
            }
            val dungeonClass = tabPlayer?.let { DungeonClass.parse(it.dungeonClass()) }
            val className = dungeonClass?.displayName() ?: "Class unknown"
            val duration = configuredDurationTicks.coerceIn(MIN_DURATION_TICKS, MAX_DURATION_TICKS)
            return Optional.of(Title(teammate.name(), className, duration))
        }
    }
}
