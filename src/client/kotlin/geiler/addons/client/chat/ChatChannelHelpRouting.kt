package geiler.addons.client.chat

/** Local help is visible in All and in the currently selected channel. */
object ChatChannelHelpRouting {
    @JvmStatic
    fun destinationIds(activeChannelId: String?): List<String> {
        val selected = activeChannelId?.takeUnless {
            ChatStringSemantics.isBlank(it) || it == ChatChannel.ALL.id()
        }
        val destinations = if (selected == null) listOf(ChatChannel.ALL.id()) else listOf(ChatChannel.ALL.id(), selected)
        return java.util.Collections.unmodifiableList(destinations)
    }

    /** Local help follows the selected tab only when channel tabs are enabled. */
    @JvmStatic
    fun localOriginId(channelTabsEnabled: Boolean, selectedChannelId: String?): String {
        if (!channelTabsEnabled) return ChatChannel.ALL.id()
        return selectedChannelId?.takeIf { !ChatStringSemantics.isBlank(it) } ?: ChatChannel.ALL.id()
    }

    /** Help is always added through Minecraft's native chat renderer. */
    @JvmStatic
    fun shouldEchoToVanilla(ignored: Boolean): Boolean = true
}
