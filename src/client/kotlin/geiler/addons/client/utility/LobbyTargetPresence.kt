package geiler.addons.client.utility

import java.util.HashSet
import java.util.Locale
import java.util.UUID
import kotlin.jvm.JvmRecord
import geiler.addons.client.chat.ChatStringSemantics

/** Pure filtering for authenticated player-info and loaded-player sources used by Lobby Sniper. */
class LobbyTargetPresence private constructor() {
    /**
     * Keeps names backed by a connection PlayerInfo and either its full online roster, a listed state,
     * or a loaded player entity. A local player, missing UUID, unverified entity, or invalid profile
     * name is never treated as a lobby target.
     */
    @JvmRecord
    data class Candidate(
        val profileId: UUID?,
        val rawProfileName: String?,
        val fromFullRoster: Boolean,
        val listed: Boolean,
        val loadedPlayerEntity: Boolean,
        val verifiedPlayerInfo: Boolean,
        val localPlayer: Boolean,
    )

    companion object {
        private val VALID_PROFILE_NAME = ChatStringSemantics.MINECRAFT_USERNAME

        /**
         * Keeps the original Java static entrypoint and filters only authenticated, non-local profiles
         * represented by an allowed source.
         */
        @JvmStatic
        fun confirmedNames(candidates: Collection<Candidate?>?): List<String> {
            if (candidates.isNullOrEmpty()) return java.util.List.of()
            val seenIds = HashSet<UUID>()
            val seenNames = HashSet<String>()
            val names = ArrayList<String>()
            for (candidate in candidates) {
                if (candidate == null || candidate.profileId == null || candidate.localPlayer
                    || !candidate.verifiedPlayerInfo
                    || !(candidate.fromFullRoster || candidate.listed || candidate.loadedPlayerEntity)
                ) continue

                val name = candidate.rawProfileName?.trim { Character.isWhitespace(it) } ?: ""
                if (!VALID_PROFILE_NAME.matcher(name).matches()) continue
                if (seenIds.add(candidate.profileId) && seenNames.add(name.lowercase(Locale.ROOT))) {
                    names.add(name)
                }
            }
            return java.util.List.copyOf(names)
        }
    }
}
