package geiler.addons.client.chat

/** Pure outgoing route selection; slash commands and blank input are left to Vanilla. */
object ChatOutgoingRouter {
    @JvmStatic
    fun routedInput(channel: ChatChannel?, input: String?): String? {
        if (channel == null || input == null || ChatStringSemantics.isBlank(input)
            || ChatStringSemantics.stripLeading(input).startsWith("/")) return null
        val prefix = channel.commandPrefix() ?: return null
        if (ChatStringSemantics.isBlank(prefix)) return null
        var command = ChatStringSemantics.trim(prefix)
        if (!command.startsWith("/")) command = "/$command"
        return "$command $input"
    }
}
