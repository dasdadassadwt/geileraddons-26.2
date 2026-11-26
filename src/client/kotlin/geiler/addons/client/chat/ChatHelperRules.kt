package geiler.addons.client.chat

import geiler.addons.client.tree.ChatText
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import java.util.Locale
import java.util.regex.Pattern

/**
 * Recognises direct messages for the chat helper without touching chat history.
 *
 * A direct message is either the vanilla `commands.message.display.incoming` translatable component
 * (vanilla `/msg` and any server that uses that chat type) or the plain Hypixel system-chat line
 * `From [rank] Name: body`. Only the exact direct-message shapes are accepted; public chat
 * decorations and any other line never reach the trigger check.
 */
object ChatHelperRules {
    const val INCOMING_KEY: String = "commands.message.display.incoming"

    private const val MAX_TEXT_LENGTH = 512

    /** Minecraft chat lines are short; the cap keeps this parser out of regex work on pathological input. */
    private val HYPIXEL_DM = Pattern.compile(
        "(?i)^from\\s+(?:\\[[^\\]]+]\\s*)*([a-z0-9_]{1,16})\\s*:\\s*(.*)$",
    )
    private val RANKED_NAME = Pattern.compile("^(?:\\[[^\\]]+]\\s*)*([a-z0-9_]{1,16})$", Pattern.CASE_INSENSITIVE)
    private val NON_PLAYER_SENDERS = setOf("server", "console", "system")

    /** One incoming private message. Java callers read it through the generated getters. */
    data class Incoming(val sender: String, val body: String)

    /** Vanilla player-chat/incoming component; never falls back to text so public chat cannot match. */
    @JvmStatic
    fun incomingPlayerChat(component: Component?): Incoming? {
        val contents = component?.contents as? TranslatableContents ?: return null
        if (contents.key != INCOMING_KEY) return null
        val args = contents.args
        if (args.size < 2) return null
        return Incoming(text(args[0]), text(args[1]))
    }

    /** System chat: the vanilla component when a server uses it, else the Hypixel `From ...:` line. */
    @JvmStatic
    fun incomingSystemChat(component: Component?): Incoming? =
        incomingPlayerChat(component) ?: incomingFromText(component?.string)

    /** Plain-text Hypixel DM parser. `ChatText` drops section codes and normalises spaces. */
    @JvmStatic
    fun incomingFromText(rawText: String?): Incoming? {
        val text = ChatText.stripForMatch(rawText ?: return null)
        if (text.length > MAX_TEXT_LENGTH) return null
        val match = HYPIXEL_DM.matcher(text)
        if (!match.matches()) return null
        return Incoming(match.group(1), match.group(2))
    }

    /** A blank trigger disables matching even while the settings toggle stays on. */
    @JvmStatic
    fun normalizedTrigger(rawTrigger: String?): String? =
        ChatText.stripForMatch(rawTrigger ?: return null).ifEmpty { null }

    /** Exact match after trimming surrounding whitespace; a longer body never matches. */
    @JvmStatic
    fun matchesTrigger(body: String?, trigger: String?): Boolean {
        val wanted = normalizedTrigger(trigger ?: return false) ?: return false
        val received = ChatText.stripForMatch(body ?: return false)
        return received.equals(wanted, ignoreCase = true)
    }

    /** Extracts a valid Minecraft username from a decorated display name; null means not a player. */
    @JvmStatic
    fun playerName(display: String?): String? {
        val text = ChatText.stripForMatch(display ?: return null)
        val match = RANKED_NAME.matcher(text)
        return if (match.matches()) match.group(1) else null
    }

    /** True when the sender and receiver are the same player. */
    @JvmStatic
    fun isLocalSender(sender: String?, localPlayerName: String?): Boolean {
        val senderName = playerName(sender ?: return false) ?: return false
        val localName = playerName(localPlayerName ?: return false) ?: return false
        return senderName.equals(localName, ignoreCase = true)
    }

    /** Server/console senders and anything that is not a player-shaped name never trigger. */
    @JvmStatic
    fun isNonPlayerSender(sender: String?): Boolean {
        val name = playerName(sender ?: return true) ?: return true
        return name.lowercase(Locale.ROOT) in NON_PLAYER_SENDERS
    }

    private fun text(value: Any?): String = when (value) {
        is Component -> value.string
        null -> ""
        else -> value.toString()
    }
}
