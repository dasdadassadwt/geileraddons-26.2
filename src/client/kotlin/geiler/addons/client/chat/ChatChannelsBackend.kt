package geiler.addons.client.chat

import geiler.addons.client.mixin.ChatComponentPresentationAccessor
import geiler.addons.client.module.impl.ChatChannelsModule
import geiler.addons.client.module.impl.PartyCommandsModule
import geiler.addons.client.module.impl.PlayerPresentationModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.client.multiplayer.chat.GuiMessageSource
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MessageSignature
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Predicate
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic
import kotlin.jvm.Synchronized

/** Bounded client-thread channel history. Message text is never written to disk. */
class ChatChannelsBackend private constructor() {
    companion object {
        private const val MAX_MESSAGES_PER_CHANNEL = 500
        private const val MAX_LOCAL_CHANNEL_OVERRIDES = 128
        private val nextId = AtomicLong()
        private val allMessages = ArrayDeque<ChatMessage>()
        private val channelMessages = LinkedHashMap<String, ArrayDeque<ChatMessage>>()
        private val localChannelOverrides = IdentityHashMap<Component, String>()
        private val localChannelOverrideOrder = ArrayDeque<Component>()
        private var selectedChannelId = ChatChannel.ALL.id()
        private var messageRevision = 0L
        private var routingRevision = 0L
        private var nativeFilterRevision = 0L
        private var filterChatComponent: ChatComponent? = null
        private var lastFilterEnabled = false
        private var lastFilterChannelId: String? = null
        private var lastFilterRoutingRevision = Long.MIN_VALUE
        private var insertingNativeMessage: ChatComponent? = null
        private var scrollBeforeNativeInsert = 0
        private var newMessageFlagBeforeNativeInsert = false

        @JvmStatic
        @Synchronized
        fun onIncoming(original: Component?, signature: MessageSignature?, source: GuiMessageSource?) {
            if (original == null || !ChatChannelRuntimeGate.shouldCapture(isActive())) return
            val localOverride = localChannelOverrides[original]
            val messageChannel = if (localOverride == null) routeIncoming(original.string) else channelLocked(localOverride)
            val message = ChatMessage.createFromBackend(nextId.incrementAndGet(), original, signature, source, messageChannel,
                System.currentTimeMillis())
            append(allMessages, message)
            append(channelMessages.computeIfAbsent(messageChannel.id()) { ArrayDeque() }, message)
        }

        @JvmStatic
        @Synchronized
        fun onDelete(signature: MessageSignature?) {
            if (signature == null) return
            var changed = removeMatching(allMessages, signature)
            for (messages in channelMessages.values) changed = removeMatching(messages, signature) || changed
            if (changed) messageRevision++
        }

        @JvmStatic
        @Synchronized
        fun messages(channelId: String?): List<ChatMessage> {
            val selected = if (channelId == null || channelId == ChatChannel.ALL.id()) {
                allMessages
            } else {
                channelMessages[channelId]
            }
            return selected?.let { java.util.List.copyOf(it) } ?: java.util.List.of()
        }

        /** Monotonic revision for invalidating wrapped-line caches after any visible history change. */
        @JvmStatic
        @Synchronized
        fun messageRevision(): Long = messageRevision * 31L +
            PlayerPresentationModule.chatLayoutPresentationRevision()

        @JvmStatic
        @Synchronized
        fun channels(): List<ChatChannel> = channelsLocked()

        @JvmStatic
        @Synchronized
        fun channel(id: String?): ChatChannel = channelLocked(id)

        @JvmStatic
        @Synchronized
        fun selectedChannel(): ChatChannel = channelLocked(selectedChannelId)

        @JvmStatic
        @Synchronized
        fun selectChannel(id: String?) {
            selectedChannelId = channelLocked(id).id()
        }

        /** Rebuilds channel views after a custom route is added or removed. */
        @JvmStatic
        @Synchronized
        fun reclassifyMessages() {
            channelMessages.clear()
            for (message in allMessages) {
                if (message.source() != GuiMessageSource.SYSTEM_CLIENT) {
                    message.setChannel(routeIncoming(message.original().string))
                }
                appendWithoutRevision(channelMessages.computeIfAbsent(message.channel().id()) { ArrayDeque() }, message)
            }
            messageRevision++
            routingRevision++
        }

        @JvmStatic
        fun isActive(): Boolean = ChatChannelsModule.INSTANCE.isEnabled()

        @JvmStatic
        fun isWindowOpen(): Boolean = false

        /** The native chat renderer always remains visible. Channel selection filters its message history. */
        @JvmStatic
        fun shouldSuppressVanillaOverlay(focused: Boolean): Boolean = false

        /** Rebuilds the native chat line list when its local channel filter changes. */
        @JvmStatic
        @Synchronized
        fun refreshVisibleMessageFilter(chat: ChatComponent?) {
            if (chat == null) return
            val enabled = ChatChannelsModule.INSTANCE.isEnabled()
            val channelId = if (enabled) channelLocked(selectedChannelId).id() else ChatChannel.ALL.id()
            val selectionChanged = filterChatComponent === chat && enabled &&
                (!lastFilterEnabled || !java.util.Objects.equals(lastFilterChannelId, channelId))
            if (filterChatComponent === chat && lastFilterEnabled == enabled &&
                java.util.Objects.equals(lastFilterChannelId, channelId) && lastFilterRoutingRevision == routingRevision) return
            chat.setVisibleMessageFilter(Predicate { message -> ChatChannelsBackend.shouldShowVanillaMessage(message) })
            if (selectionChanged) chat.resetChatScroll()
            nativeFilterRevision++
            filterChatComponent = chat
            lastFilterEnabled = enabled
            lastFilterChannelId = channelId
            lastFilterRoutingRevision = routingRevision
        }

        @JvmStatic
        @Synchronized
        fun nativeFilterRevision(): Long = nativeFilterRevision

        /** Mirrors the same per-line classification used by the former local history view. */
        @JvmStatic
        @Synchronized
        fun shouldShowVanillaMessage(message: GuiMessage?): Boolean = shouldShowVanillaMessageLocked(message)

        /** Keeps vanilla's bounded message storage even when the current channel does not display a line. */
        @JvmStatic
        @Synchronized
        fun beginNativeMessageInsertion(chat: ChatComponent?) {
            if (chat == null) return
            insertingNativeMessage = chat
            val access = chat as ChatComponentPresentationAccessor
            scrollBeforeNativeInsert = access.`geileraddons$scrollbarPosition`()
            newMessageFlagBeforeNativeInsert = access.`geileraddons$newMessageSinceScroll`()
        }

        /** Filters a newly stored line after vanilla has retained it for later tab changes. */
        @JvmStatic
        @Synchronized
        fun finishNativeMessageInsertion(chat: ChatComponent?) {
            if (chat == null || insertingNativeMessage !== chat) return
            insertingNativeMessage = null
            if (!ChatChannelsModule.INSTANCE.isEnabled() || ChatChannel.ALL.id() == channelLocked(selectedChannelId).id()) return
            val access = chat as ChatComponentPresentationAccessor
            val nativeMessages = access.`geileraddons$allMessages`()
            if (nativeMessages.isEmpty() || shouldShowVanillaMessageLocked(nativeMessages[0])) return

            access.`geileraddons$setScrollbarPosition`(0)
            chat.setVisibleMessageFilter(Predicate { message -> ChatChannelsBackend.shouldShowVanillaMessage(message) })
            val maxScroll = maxOf(0, access.`geileraddons$trimmedMessages`().size - access.`geileraddons$linesPerPage`())
            access.`geileraddons$setScrollbarPosition`(minOf(scrollBeforeNativeInsert, maxScroll))
            access.`geileraddons$setNewMessageSinceScroll`(newMessageFlagBeforeNativeInsert)
            nativeFilterRevision++
        }

        @JvmStatic
        @Synchronized
        fun channelForVanillaMessage(message: GuiMessage?): ChatChannel = channelForVanillaMessageLocked(message)

        /** Marks a local system line for the channel that owns it while retaining vanilla rendering. */
        @JvmStatic
        @Synchronized
        fun routeLocalMessage(component: Component?, channelId: String?) {
            if (component == null) return
            routeLocalMessageLocked(component, channelId)
        }

        @JvmStatic
        @Synchronized
        fun dynamicHelp(): String {
            val prefix = ChatReplyCommandRules.normalizePrefix(PartyCommandsModule.INSTANCE.commandPrefix().value())
            val lines = ChatReplyCommandRules.helpLines(prefix, channelLocked(selectedChannelId),
                ChatChannelsLocalStore.instance().enabledChatReplies())
            return if (lines == null) "Chat command prefix is invalid." else lines.joinToString("\n")
        }

        @JvmStatic
        @Synchronized
        fun showHelp() {
            val mc = Minecraft.getInstance()
            val originId = ChatChannelHelpRouting.localOriginId(ChatChannelsModule.INSTANCE.isEnabled(), selectedChannelId)
            val selected = channelLocked(originId)
            val prefix = ChatReplyCommandRules.normalizePrefix(PartyCommandsModule.INSTANCE.commandPrefix().value())
            val lines = ChatReplyCommandRules.helpLines(prefix, selected,
                ChatChannelsLocalStore.instance().enabledChatReplies()) ?: listOf("Chat command prefix is invalid.")
            val gui = mc.gui ?: return
            for (line in lines) {
                val message = Component.literal(line)
                routeLocalMessageLocked(message, selected.id())
                gui.hud.getChat().addClientSystemMessage(message)
            }
        }

        @JvmStatic
        @Synchronized
        fun clearForDisconnect() {
            allMessages.clear()
            channelMessages.clear()
            localChannelOverrides.clear()
            localChannelOverrideOrder.clear()
            messageRevision++
            routingRevision++
            nativeFilterRevision++
            filterChatComponent = null
        }

        /** Kotlin backend factory; the Java facade exposed this only to package peers. */
        @JvmStatic
        fun createMessage(
            id: Long,
            original: Component,
            signature: MessageSignature?,
            source: GuiMessageSource?,
            channel: ChatChannel,
            receivedAtMillis: Long,
        ): ChatMessage = ChatMessage.createFromBackend(id, original, signature, source, channel, receivedAtMillis)

        @JvmStatic
        fun setMessageChannel(message: ChatMessage, channel: ChatChannel) {
            message.setChannel(channel)
        }

        private fun channelsLocked(): List<ChatChannel> {
            val channels = ArrayList<ChatChannel>(ChatChannel.builtIns().size + 4)
            channels.add(ChatChannel.ALL)
            channels.add(ChatChannel.PARTY)
            channels.add(ChatChannel.GUILD)
            channels.add(ChatChannel.COOP)
            channels.add(ChatChannel.PRIVATE)
            val activeCustomIds = HashSet<String>()
            for (rule in ChatChannelsLocalStore.instance().rules()) {
                val customChannel = rule.tab()
                channels.add(customChannel)
                activeCustomIds.add(customChannel.id())
            }
            if (channelMessages.keys.removeIf { id -> id.startsWith("custom:") && id !in activeCustomIds }) {
                messageRevision++
            }
            channels.add(ChatChannel.VANILLA_SYSTEM)
            return java.util.List.copyOf(channels)
        }

        private fun channelLocked(id: String?): ChatChannel {
            for (channel in channelsLocked()) if (channel.id() == id) return channel
            return ChatChannel.ALL
        }

        private fun shouldShowVanillaMessageLocked(message: GuiMessage?): Boolean {
            if (insertingNativeMessage != null) return true
            if (message == null || !ChatChannelsModule.INSTANCE.isEnabled()) return true
            val selectedId = channelLocked(selectedChannelId).id()
            return ChatChannelFilterRules.isVisible(selectedId, channelForVanillaMessageLocked(message).id())
        }

        private fun channelForVanillaMessageLocked(message: GuiMessage?): ChatChannel {
            if (message == null) return ChatChannel.VANILLA_SYSTEM
            val component = message.content()
            val override = localChannelOverrides[component]
            return if (override == null) routeIncoming(component.string) else channelLocked(override)
        }

        private fun routeLocalMessageLocked(component: Component, channelId: String?) {
            val normalized = channelLocked(channelId).id()
            if (localChannelOverrides.put(component, normalized) == null) localChannelOverrideOrder.addLast(component)
            while (localChannelOverrideOrder.size > MAX_LOCAL_CHANNEL_OVERRIDES) {
                localChannelOverrides.remove(localChannelOverrideOrder.removeFirst())
            }
        }

        private fun routeIncoming(text: String): ChatChannel =
            ChatChannelClassifier.classify(text, ChatChannelsLocalStore.instance().rules())

        private fun append(messages: ArrayDeque<ChatMessage>, message: ChatMessage) {
            appendWithoutRevision(messages, message)
            messageRevision++
        }

        private fun appendWithoutRevision(messages: ArrayDeque<ChatMessage>, message: ChatMessage) {
            messages.addLast(message)
            while (messages.size > MAX_MESSAGES_PER_CHANNEL) messages.removeFirst()
        }

        private fun removeMatching(messages: ArrayDeque<ChatMessage>, signature: MessageSignature): Boolean {
            var changed = false
            val iterator = messages.iterator()
            while (iterator.hasNext()) {
                if (signature == iterator.next().signature()) {
                    iterator.remove()
                    changed = true
                }
            }
            return changed
        }
    }

    /** Keeps the original server component for fallback and rebuilds only safe text bodies. */
    class ChatMessage private constructor(
        private val id: Long,
        private val original: Component,
        private val signature: MessageSignature?,
        private val source: GuiMessageSource?,
        private var channel: ChatChannel,
        private val receivedAtMillis: Long,
    ) {
        private var display: Component = original
        private var displayRevision: Long = 0L

        fun id(): Long = id
        fun original(): Component = original
        fun display(): Component = PlayerPresentationModule.replaceKnownRenderedName(display) ?: display
        fun rawDisplay(): Component = display
        fun signature(): MessageSignature? = signature
        fun source(): GuiMessageSource? = source
        fun receivedAtMillis(): Long = receivedAtMillis
        fun channel(): ChatChannel = channel
        fun displayRevision(): Long = displayRevision * 31L +
            PlayerPresentationModule.chatMessagePresentationRevision(display)

        internal fun setChannel(channel: ChatChannel) {
            this.channel = channel
        }

        companion object {
            @JvmSynthetic
            internal fun createFromBackend(
                id: Long,
                original: Component,
                signature: MessageSignature?,
                source: GuiMessageSource?,
                channel: ChatChannel,
                receivedAtMillis: Long,
            ): ChatMessage = ChatMessage(id, original, signature, source, channel, receivedAtMillis)
        }
    }
}
