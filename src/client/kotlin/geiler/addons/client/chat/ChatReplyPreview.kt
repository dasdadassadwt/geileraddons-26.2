package geiler.addons.client.chat

/** Plain-language previews for custom replies, shown from the settings page so the rules are self-explanatory. */
object ChatReplyPreview {
    const val HELP = "A reply runs when someone writes <prefix><command> in an enabled channel. " +
        "Text Reply sends the text back. Local Action runs a /command on your client; {args} is replaced by " +
        "what the sender wrote after the command."

    @JvmStatic
    fun describe(prefix: String, command: String, localAction: Boolean, payload: String): String {
        val trigger = "$prefix$command"
        return if (localAction) {
            val example = payload.replace("{args}", "example")
            "$trigger example  →  you run  $example"
        } else {
            "$trigger  →  you reply  \"$payload\""
        }
    }
}
