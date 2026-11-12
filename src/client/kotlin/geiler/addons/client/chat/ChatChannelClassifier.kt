package geiler.addons.client.chat

import geiler.addons.client.tree.ChatText
import java.util.Locale
import java.util.regex.Pattern

/** Best-effort routing of common Hypixel chat decorations into built-in tabs. */
object ChatChannelClassifier {
    private val party = Pattern.compile("(?i)^(?:\\[party]|party\\s*>)\\s*.*$")
    private val guild = Pattern.compile("(?i)^(?:\\[guild]|guild\\s*>)\\s*.*$")
    private val coop = Pattern.compile("(?i)^(?:\\[co-?op]|co-?op\\s*>)\\s*.*$")
    private val privateMessage = Pattern.compile("(?i)^(?:from|to)\\s+(?:\\[[^]]+\\]\\s*)?[a-z0-9_]{1,16}\\s*:")
    private val sender = Pattern.compile(
        "(?i)^(?:(?:party|guild|officer|co-?op)\\s*>\\s*)?(?:\\[[^]]+\\]\\s*)*([a-z0-9_]{1,16})\\s*:",
    )
    private val privateSender = Pattern.compile(
        "(?i)^(?:from|to)\\s+(?:\\[[^]]+\\]\\s*)*([a-z0-9_]{1,16})\\s*:",
    )

    @JvmStatic
    fun classify(rawText: String?): ChatChannel {
        val text = normalize(rawText)
        if (party.matcher(text).matches()) return ChatChannel.PARTY
        if (guild.matcher(text).matches()) return ChatChannel.GUILD
        if (coop.matcher(text).matches()) return ChatChannel.COOP
        if (privateMessage.matcher(text).find()) return ChatChannel.PRIVATE
        return ChatChannel.VANILLA_SYSTEM
    }

    /** Chooses the most specific matching custom route before using a built-in channel. */
    @JvmStatic
    fun classify(rawText: String?, rules: List<ChatChannelRule?>?): ChatChannel {
        if (!rules.isNullOrEmpty()) {
            val messageSender = sender(rawText)
            val best = rules.asSequence()
                .filterNotNull()
                .filter { it.matches(rawText, messageSender) }
                .maxByOrNull { it.incomingContains().length }
            return best?.tab() ?: classify(rawText)
        }
        return classify(rawText)
    }

    @JvmStatic
    fun sender(rawText: String?): String? {
        val text = normalize(rawText)
        val privateMatch = privateSender.matcher(text)
        if (privateMatch.find()) return privateMatch.group(1)
        val match = sender.matcher(text)
        return if (match.find()) match.group(1) else null
    }

    @Suppress("DEPRECATION")
    private fun normalize(text: String?): String =
        ChatText.stripForMatch(text ?: "").lowercase(Locale.ROOT)
}
