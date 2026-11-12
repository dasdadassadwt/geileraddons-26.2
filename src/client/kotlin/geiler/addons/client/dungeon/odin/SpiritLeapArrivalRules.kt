package geiler.addons.client.dungeon.odin

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.tree.ChatText
import java.util.HashMap
import java.util.Locale
import java.util.Optional
import java.util.regex.Pattern

/** Pure parsing, formatting, and duplicate suppression for confirmed Spirit Leap arrivals. */
class SpiritLeapArrivalRules private constructor() {
    @JvmRecord
    data class Arrival(val playerName: String?) {
        override fun toString() = "Arrival[playerName=$playerName]"
    }

    /** Suppresses duplicate confirmed completion lines for the same player in a short time window. */
    class Coalescer {
        private val lastSeen = HashMap<String, Long>()

        fun accept(arrival: Arrival?, nowNanos: Long): Boolean {
            if (arrival == null || !validPlayerName(arrival.playerName)) return false
            lastSeen.entries.removeIf { entry ->
                nowNanos >= entry.value && nowNanos - entry.value > COALESCE_NANOS
            }
            val key = normalize(arrival.playerName)
            val previous = lastSeen[key]
            if (previous != null && nowNanos >= previous && nowNanos - previous <= COALESCE_NANOS) return false
            lastSeen[key] = nowNanos
            return true
        }

        fun clear() = lastSeen.clear()
    }

    companion object {
        private val OUTGOING = Pattern.compile("^You have teleported to ([A-Za-z0-9_]{1,16})!$")
        const val COALESCE_NANOS = 2_000_000_000L

        /** Accepts only the pinned server-confirmed outgoing completion line. */
        @JvmStatic
        fun parse(rawChat: String?): Optional<Arrival> {
            if (rawChat == null) return Optional.empty()
            val plain = ChatText.stripFormattingCodes(rawChat).trim()
            val outgoing = OUTGOING.matcher(plain)
            return if (outgoing.matches()) Optional.of(Arrival(outgoing.group(1))) else Optional.empty()
        }

        @JvmStatic
        fun validPlayerName(playerName: String?): Boolean =
            ChatStringSemantics.isMinecraftUsername(playerName)

        private fun normalize(text: String?): String = text?.trim()?.lowercase(Locale.ROOT).orEmpty()
    }
}
