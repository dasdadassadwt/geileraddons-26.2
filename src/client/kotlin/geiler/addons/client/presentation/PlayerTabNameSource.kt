package geiler.addons.client.presentation

import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component

/** Reads original tab-list text for parsers without passing through presentation mixins. */
object PlayerTabNameSource {
    @JvmStatic
    fun rawLine(info: PlayerInfo?): String {
        if (info == null) return ""
        val team = info.getTeam()
        return rawLine(
            info.getTabListDisplayName(),
            info.getProfile().name,
            team?.getPlayerPrefix(),
            team?.getPlayerSuffix(),
        )
    }

    /** Mirrors vanilla's text order while avoiding transformed team-formatting output. */
    @JvmStatic
    fun rawLine(
        tabDisplayName: Component?,
        profileName: String?,
        teamPrefix: Component?,
        teamSuffix: Component?,
    ): String {
        if (tabDisplayName != null) return tabDisplayName.getString()
        val result = StringBuilder()
        teamPrefix?.let { result.append(it.getString()) }
        profileName?.let { result.append(it) }
        teamSuffix?.let { result.append(it.getString()) }
        return result.toString()
    }
}
