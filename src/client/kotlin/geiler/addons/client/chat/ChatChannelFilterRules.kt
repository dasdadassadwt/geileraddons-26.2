package geiler.addons.client.chat

/** Pure selection rules for the native chat history filter. */
object ChatChannelFilterRules {
    @JvmStatic
    fun isVisible(selectedChannelId: String?, messageChannelId: String?): Boolean =
        selectedChannelId == null || ChatStringSemantics.isBlank(selectedChannelId) ||
            selectedChannelId == ChatChannel.ALL.id() ||
            selectedChannelId == messageChannelId
}
