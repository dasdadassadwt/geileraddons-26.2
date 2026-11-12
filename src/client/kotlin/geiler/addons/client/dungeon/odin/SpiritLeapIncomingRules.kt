package geiler.addons.client.dungeon.odin

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.tree.ChatText
import java.util.HashMap
import java.util.Locale
import java.util.Optional
import java.util.regex.Pattern

/**
 * Candidate party-chat signal for a teammate possibly leaping to the local player.
 *
 * Hypixel wording is not stable enough for an exact phrase parser here. This matches a
 * token-boundary mention of the local player's name in a current teammate's party line. The name
 * mention itself is the observed signal; callers must not infer a target when that name is absent.
 */
class SpiritLeapIncomingRules private constructor() {
    @JvmRecord
    data class Incoming(val teammateName: String?, val targetName: String?) {
        override fun toString() = "Incoming[teammateName=$teammateName, targetName=$targetName]"
    }

    /** Suppresses repeated chat-derived arrival feedback per teammate for a brief window. */
    class Coalescer {
        private val lastSeen = HashMap<String, Long>()

        fun accept(incoming: Incoming?, nowNanos: Long): Boolean {
            if (incoming == null || !validName(incoming.teammateName) || !validName(incoming.targetName)) return false
            lastSeen.entries.removeIf { entry ->
                nowNanos >= entry.value && nowNanos - entry.value > COALESCE_NANOS
            }
            val key = normalize(incoming.teammateName)
            val previous = lastSeen[key]
            if (previous != null && nowNanos >= previous && nowNanos - previous <= COALESCE_NANOS) return false
            lastSeen[key] = nowNanos
            return true
        }

        fun clear() = lastSeen.clear()
    }

    companion object {
        const val COALESCE_NANOS = 2_000_000_000L
        private val PARTY_LINE = Pattern.compile("(?i)^party\\s*>\\s*(?:\\[[^]]+\\]\\s*)*([a-z0-9_]{1,16})\\s*:\\s*(.*)$")
        private val USERNAME = ChatStringSemantics.MINECRAFT_USERNAME

        @JvmStatic
        fun parse(rawChat: String?, localPlayerName: String?, verifiedPartyNames: List<String>?): Optional<Incoming> {
            if (rawChat == null || rawChat.length > 512 || !validName(localPlayerName) ||
                !containsName(verifiedPartyNames, localPlayerName!!)
            ) return Optional.empty()
            val line = ChatText.stripForMatch(rawChat)
            val partyLine = PARTY_LINE.matcher(line)
            if (!partyLine.matches()) return Optional.empty()
            val sender = partyLine.group(1)
            if (!validName(sender) || sender.equals(localPlayerName, ignoreCase = true) ||
                !containsName(verifiedPartyNames, sender)
            ) return Optional.empty()
            return if (containsToken(partyLine.group(2), localPlayerName)) {
                Optional.of(Incoming(sender, localPlayerName))
            } else {
                Optional.empty()
            }
        }

        private fun containsToken(text: String?, name: String?): Boolean {
            if (text == null || !validName(name)) return false
            val mention = Pattern.compile("(?i)(?<![a-z0-9_])${Pattern.quote(name!!)}(?![a-z0-9_])")
            return mention.matcher(text).find()
        }

        private fun containsName(names: List<String>?, target: String): Boolean =
            names?.asSequence()?.filter(::validName)?.any { it.equals(target, ignoreCase = true) } ?: false

        private fun validName(name: String?): Boolean = name != null && USERNAME.matcher(name).matches()

        private fun normalize(text: String?): String = text?.trim()?.lowercase(Locale.ROOT).orEmpty()
    }
}
