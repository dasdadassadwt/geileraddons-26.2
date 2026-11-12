package geiler.addons.client.module.impl

import geiler.addons.client.chat.ChatChannelTabs
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module

/** Vanilla chat-entry tabs that filter local history and prefix ordinary outgoing messages. */
class ChatChannelsModule private constructor() : Module(
    "Chat Channels",
    "Captures channel history and adds clickable tabs above vanilla chat.",
    Category.MISCELLANEOUS,
    false,
) {
    override fun configName(): String = "Chat Channels"

    companion object {
        @JvmField
        val INSTANCE = ChatChannelsModule()

        /** The selected tab is session-only and intentionally returns to All when the client restarts. */
        @JvmStatic
        fun selectedTab(): ChatChannelTabs.Tab = ChatChannelTabs.selected()

        /** Compatibility for old entry points until the central send callback is switched to Party Commands. */
        @JvmStatic
        fun handleLocalChatCommand(input: String?): Boolean =
            input != null && PartyCommandsModule.handleLocalChatCommand(input)
    }
}
