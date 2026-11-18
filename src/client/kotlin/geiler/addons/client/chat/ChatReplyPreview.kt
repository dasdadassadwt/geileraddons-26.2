package geiler.addons.client.chat

import java.time.ZonedDateTime
import java.util.Random

/** Plain-language previews and the step-by-step guide for custom replies, shown from the settings page. */
object ChatReplyPreview {
    /** One chat line per entry; the settings button prints them in order. */
    @JvmField
    val GUIDE_LINES: List<String> = listOf(
        "How to make your own command:",
        "1. New Command: type a short name, like rng. Players trigger it with the prefix first, like !rng.",
        "2. Pick a mode. Text Reply: when someone types it in party, guild or co-op chat, you answer with your text. " +
            "Local Action: it only runs when you type it yourself, and runs a /command for you.",
        "3. New Reply / Local Action Value: your answer text, or the /command to run.",
        "4. Press Preview to see an example, then Add Reply to save it.",
        "Tags (Text Reply): ${ChatReplyTags.HELP}. They are filled in when the reply is sent.",
        "Example: name rng, value \"{sender} rolled {rng:1-10}\" answers \"Steve rolled 7\".",
        "Local Action: use {args} for what you typed after the command, e.g. /warp {args}. Another player's message never runs one.",
        "A reply cannot start with the prefix, and built-in names (like help or coords) are taken.",
    )

    @JvmField
    val HELP: String = GUIDE_LINES.joinToString("\n")

    @JvmStatic
    fun describe(prefix: String, command: String, localAction: Boolean, payload: String): String {
        val trigger = "$prefix$command"
        return if (localAction) {
            val example = payload.replace("{args}", "example")
            "$trigger example  →  you run  $example"
        } else {
            val sample = ChatReplyTags.expand(payload, "Steve", "You", ZonedDateTime.now(), Random())
            "$trigger  →  you reply  \"$sample\""
        }
    }
}
