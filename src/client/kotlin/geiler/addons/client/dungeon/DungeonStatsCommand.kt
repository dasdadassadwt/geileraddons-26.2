package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.module.impl.PartyFinderStatsModule
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.Collections
import java.util.Comparator
import java.util.Locale

/** Client-only handler for the local `/ga dstats [player-name]` command. */
class DungeonStatsCommand private constructor() {
    @JvmRecord
    data class ParseResult(val recognized: Boolean, val name: String?, val error: String?) {
        fun valid(): Boolean = recognized && name != null

        fun usesLocalPlayer(): Boolean = recognized && name == null && error == null

        companion object {
            @JvmField val NOT_RECOGNIZED = ParseResult(false, null, null)
            @JvmField val USAGE = ParseResult(true, null, "usage")
            @JvmField val LOCAL_PLAYER = ParseResult(true, null, null)
        }
    }

    companion object {
        private val PLAYER_NAME = ChatStringSemantics.MINECRAFT_USERNAME
        private val SUGGESTION_ORDER = Comparator<String> { left, right ->
            val caseInsensitive = java.lang.String.CASE_INSENSITIVE_ORDER.compare(left, right)
            if (caseInsensitive != 0) caseInsensitive else left.compareTo(right)
        }

        @JvmStatic
        fun handle(rawCommand: String?): Boolean {
            val parsed = parse(rawCommand)
            if (!parsed.recognized) return false
            if (parsed.usesLocalPlayer()) lookupLocalPlayer()
            else if (!parsed.valid()) showUsage()
            else lookup(parsed.name)
            return true
        }

        /** Looks up the account name attached to the local player's profile. */
        @JvmStatic
        fun lookupLocalPlayer(): Int {
            val player = Minecraft.getInstance().player ?: return showUnavailable("no local player is connected")
            return lookup(player.getGameProfile().name())
        }

        @JvmStatic
        fun showUsage(): Int {
            val minecraft = Minecraft.getInstance()
            minecraft.gui?.hud?.getChat()?.addClientSystemMessage(
                Component.literal("[DStats] Usage: /ga dstats [player-name]"),
            )
            return 0
        }

        private fun showUnavailable(reason: String): Int {
            val minecraft = Minecraft.getInstance()
            minecraft.gui?.hud?.getChat()?.addClientSystemMessage(
                Component.literal("[DStats] Unavailable: $reason."),
            )
            return 0
        }

        @JvmStatic
        fun showRetryUsage(): Int {
            val minecraft = Minecraft.getInstance()
            minecraft.gui?.hud?.getChat()?.addClientSystemMessage(
                Component.literal("[PF] Usage: /ga pfretry <player-name>"),
            )
            return 0
        }

        @JvmStatic
        fun lookup(name: String?): Int {
            if (name == null || !PLAYER_NAME.matcher(name).matches()) return showUsage()
            PartyFinderStatsModule.INSTANCE.lookupStats(name)
            return 1
        }

        /** Case-insensitive prefix filter with deterministic ordering for the full listed-player set. */
        @JvmStatic
        fun suggestions(profileNames: Collection<String?>?, prefix: String?): List<String> {
            if (profileNames == null || profileNames.isEmpty()) return emptyList()
            val normalizedPrefix = prefix?.lowercase(Locale.ROOT) ?: ""
            val suggestions = profileNames.asSequence()
                .filterNotNull()
                .filter { PLAYER_NAME.matcher(it).matches() }
                .filter { it.lowercase(Locale.ROOT).startsWith(normalizedPrefix) }
                .sortedWith(SUGGESTION_ORDER)
                .toList()
            return Collections.unmodifiableList(suggestions)
        }

        @JvmStatic
        fun retryPartyMember(name: String?): Int {
            if (name == null || !PLAYER_NAME.matcher(name).matches()) return showRetryUsage()
            PartyFinderStatsModule.INSTANCE.retryPlayer(name)
            return 1
        }

        /** Parses a command without touching Minecraft state, so quoting and usage edges stay testable offline. */
        @JvmStatic
        fun parse(rawCommand: String?): ParseResult {
            if (rawCommand == null) return ParseResult.NOT_RECOGNIZED
            var input = javaTrim(rawCommand)
            if (input.startsWith("/")) input = javaTrim(input.substring(1))
            val firstSpace = firstWhitespace(input)
            val root = (if (firstSpace < 0) input else input.substring(0, firstSpace)).lowercase(Locale.ROOT)
            if (root != "ga" || firstSpace < 0) return ParseResult.NOT_RECOGNIZED
            val tail = javaTrim(input.substring(firstSpace))
            val commandEnd = firstWhitespace(tail)
            val subcommand = (if (commandEnd < 0) tail else tail.substring(0, commandEnd)).lowercase(Locale.ROOT)
            if (subcommand != "dstats") return ParseResult.NOT_RECOGNIZED
            if (commandEnd < 0) return ParseResult.LOCAL_PLAYER

            var name = javaTrim(tail.substring(commandEnd))
            if (name.startsWith("\"")) {
                if (name.length < 2 || !name.endsWith("\"")) return ParseResult.USAGE
                name = name.substring(1, name.length - 1)
            } else if (containsWhitespace(name) || name.indexOf('"') >= 0) {
                return ParseResult.USAGE
            }
            return if (PLAYER_NAME.matcher(name).matches()) ParseResult(true, name, null) else ParseResult.USAGE
        }

        private fun javaTrim(value: String): String = value.trim { it <= ' ' }

        private fun firstWhitespace(value: String): Int {
            for (index in value.indices) if (Character.isWhitespace(value[index])) return index
            return -1
        }

        private fun containsWhitespace(value: String): Boolean =
            value.any { Character.isWhitespace(it) }
    }
}
