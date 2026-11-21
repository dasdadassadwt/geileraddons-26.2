package geiler.addons.client.chat

import geiler.addons.client.module.impl.PartyCommandsModule
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.party.PartyMember
import geiler.addons.client.party.PartySnapshot
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/** Client-thread handler for delayed incoming replies and locally typed, bounded command actions. */
object ChatCommandReplyBackend {
    private const val MAX_PENDING = 16
    private val EIGHT_BALL_RESPONSES = arrayOf(
        "Looks promising", "The odds lean yes", "Probably yes", "The signal is favorable",
        "No clear answer yet", "Ask again later", "Try a different question", "I would not count on it",
        "The outlook is uncertain", "Probably not", "The answer leans no", "A yes seems likely",
        "Wait and see", "That seems unlikely", "The signs are mixed", "The result is unclear",
    )
    private val pending = ChatReplyCommandQueue<Pending>(MAX_PENDING)
    private var nextSendTick = 0L
    private var privateCounterpart: String? = null
    private const val RUN_END_LINE = "> EXTRA STATS <"
    private const val REMINDER_DELAY_TICKS = 30L
    private const val REINVITE_DELAY_TICKS = 100L
    private const val MAX_REINVITES = 4
    private val reinvites = ArrayDeque<Pair<Long, String>>()
    private var reminderTick = -1L

    /** Called only from Fabric's client chat receive callback; overlay lines are never commands. */
    @JvmStatic
    fun onIncoming(rawMessage: String?, overlay: Boolean, clientTick: Long) {
        val module = PartyCommandsModule.INSTANCE
        if (overlay || !module.isEnabled() || !module.chatRepliesEnabled().value()) return
        ChatReplyCommandRules.incomingPrivateSender(rawMessage).ifPresent { sender -> privateCounterpart = sender }
        if (PartyDowntime.holdsRequeue() && rawMessage != null && ChatText.stripForMatch(rawMessage).trim() == RUN_END_LINE) {
            reminderTick = clientTick + REMINDER_DELAY_TICKS
        }
        val client = Minecraft.getInstance()
        val player = client.player
        val connection = client.getConnection()
        if (player == null || connection == null) return
        val store = ChatChannelsLocalStore.instance()
        val request = parseIncomingRequest(
            rawMessage,
            module.commandPrefix().value(),
            store.enabledChatReplies(),
            store.rules(),
        ).orElse(null) ?: return
        if (!module.allowsIncomingReply(request.channel()!!)) return
        if (!module.presetEnabled(request.command())) {
            module.recordRequestDropped("preset_off command=${request.command()!!.name}")
            return
        }
        // Your own messages are valid commands: no built-in reply starts with the prefix, so there is no echo loop.
        if (!ChatReplyCommandRules.senderAllowed(
                request.sender(),
                false,
                emptyList(),
                store.replyBlocklistEnabled(),
                store.replyBlocklist(),
            )
        ) {
            module.recordRequestDropped("sender_blocked command=${request.command()!!.name}")
            return
        }

        val party = PartyListBackend.snapshot()
        if (ChatReplyCommandRules.isRemotePartyAction(request.command()) &&
            !ChatReplyCommandRules.remotePartyActionAllowed(
                request,
                false,
                emptyList(),
                store.replyBlocklistEnabled(),
                store.replyBlocklist(),
            )
        ) {
            module.recordPartyActionDiagnostic(
                request.command()!!,
                PartyCommandsModule.PartyActionDiagnosticOutcome.BLOCKED_ALLOWLIST,
            )
            return
        }
        if (ChatReplyCommandRules.requiresPartyLeader(request.command()) && party.inParty() &&
            party.leaderName() != null && !party.isLeader(localName(client))
        ) {
            module.recordPartyActionDiagnostic(
                request.command()!!,
                PartyCommandsModule.PartyActionDiagnosticOutcome.BLOCKED_PARTY_OR_LEADER_OR_TARGET,
            )
            return
        }

        val delayTicks = module.responseDelayTicks().intValue().coerceIn(0, 40)
        val offered = pending.offer(
            Pending(
                request,
                connection,
                party.generation(),
                delayTicks,
                if (request.command() == ChatReplyCommandRules.Command.HELP) 0 else -1,
            ),
            clientTick + delayTicks,
        )
        module.recordQueueDiagnostic(
            if (offered) PartyCommandsModule.QueueDiagnosticOutcome.OFFERED
            else PartyCommandsModule.QueueDiagnosticOutcome.OFFER_REJECTED,
            pending.size(),
        )
    }

    /** Same saved-rule parser entrypoint used by incoming messages and its offline checks. */
    @JvmStatic
    fun parseIncomingRequest(
        rawMessage: String?,
        commandPrefix: String?,
        replies: List<ChatReplyEntry?>?,
        channelRules: List<ChatChannelRule?>?,
    ): java.util.Optional<ChatReplyCommandRules.Request> =
        ChatReplyCommandRules.parse(rawMessage, commandPrefix, replies, channelRules)

    /** Drains at most one chat command per configured delay interval on the Minecraft client thread. */
    @JvmStatic
    fun tick(client: Minecraft?, clientTick: Long) {
        val module = PartyCommandsModule.INSTANCE
        if (client == null || !module.isEnabled() || !module.chatRepliesEnabled().value()) {
            clear()
            return
        }
        val player = client.player
        val connection = client.getConnection()
        if (player == null || connection == null) {
            clear()
            return
        }
        runTimers(client, clientTick)
        if (clientTick < nextSendTick) return
        val queued = pending.pollReady(clientTick) ?: return
        module.recordQueueDiagnostic(PartyCommandsModule.QueueDiagnosticOutcome.READY, pending.size())
        if (queued.connection !== connection) {
            clear()
            return
        }

        val request = queued.request
        if (!module.allowsIncomingReply(request.channel()!!) || !module.presetEnabled(request.command())) return
        val store = ChatChannelsLocalStore.instance()
        val localPlayerName = localName(client)
        if (!ChatReplyCommandRules.senderAllowed(
                request.sender(),
                false,
                emptyList(),
                store.replyBlocklistEnabled(),
                store.replyBlocklist(),
            )
        ) return
        if (ChatReplyCommandRules.isRemotePartyAction(request.command()) &&
            !ChatReplyCommandRules.remotePartyActionAllowed(
                request,
                false,
                emptyList(),
                store.replyBlocklistEnabled(),
                store.replyBlocklist(),
            )
        ) {
            module.recordPartyActionDiagnostic(
                request.command()!!,
                PartyCommandsModule.PartyActionDiagnosticOutcome.BLOCKED_ALLOWLIST,
            )
            return
        }
        if (!ChatReplyCommandRules.privateReplyRecipientMatches(request, privateCounterpart)) return
        val party = PartyListBackend.snapshot()

        if (request.command() == ChatReplyCommandRules.Command.CUSTOM_REPLY) {
            val activePrefix = ChatReplyCommandRules.normalizePrefix(module.commandPrefix().value())
            if (activePrefix == null || request.response()!!.startsWith(activePrefix) ||
                store.enabledChatReplies().none { entry ->
                    entry.command() == request.argument() && entry.response() == request.response()
                }
            ) return
        }

        if (handleLocalFeature(request, party, localPlayerName, clientTick, module)) return

        var helpLines: List<String>? = null
        val response: String? = when (request.command()) {
            ChatReplyCommandRules.Command.HELP -> {
                helpLines = ChatReplyCommandRules.helpLines(
                    module.commandPrefix().value(),
                    request.channel(),
                    store.enabledChatReplies(),
                )
                helpLines?.getOrNull(queued.helpLineIndex)
            }
            ChatReplyCommandRules.Command.COORDS -> {
                val position = player.blockPosition()
                ChatReplyCommandRules.coordinateText(position.x, position.y, position.z)
            }
            ChatReplyCommandRules.Command.TPS -> ChatTpsTracker.metrics(System.nanoTime()).format()
            ChatReplyCommandRules.Command.ODIN_LINK -> "Odin! https://discord.gg/2nCbC9hkxT"
            ChatReplyCommandRules.Command.COIN_FLIP ->
                if (ThreadLocalRandom.current().nextBoolean()) "Heads" else "Tails"
            ChatReplyCommandRules.Command.EIGHT_BALL ->
                EIGHT_BALL_RESPONSES[ThreadLocalRandom.current().nextInt(EIGHT_BALL_RESPONSES.size)]
            ChatReplyCommandRules.Command.DICE -> "Dice: ${ThreadLocalRandom.current().nextInt(1, 7)}"
            ChatReplyCommandRules.Command.PING -> {
                val info = connection.getPlayerInfo(player.uuid)
                if (info == null) "Ping unavailable" else "Current Ping: ${maxOf(0, info.latency)}ms"
            }
            ChatReplyCommandRules.Command.FPS -> "Current FPS: ${client.getFps()}"
            ChatReplyCommandRules.Command.TIME -> "Current Time: " + ZonedDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT))
            ChatReplyCommandRules.Command.LOBBY -> {
                val island = if (HypixelModApi.hasLocation()) HypixelModApi.currentIsland() else Island.NONE
                ChatReplyCommandRules.lobbyLocationText(island.takeUnless { it == Island.NONE }?.label())
            }
            ChatReplyCommandRules.Command.HOLDING -> "Holding: " + if (player.getMainHandItem().isEmpty) {
                "Nothing :("
            } else {
                ChatText.stripForMatch(player.getMainHandItem().getHoverName().string)
            }
            ChatReplyCommandRules.Command.CUSTOM_REPLY -> {
                val expanded = ChatReplyTags.expand(
                    request.response()!!, request.sender()!!, localPlayerName ?: "", ZonedDateTime.now(),
                    ThreadLocalRandom.current(),
                )
                val prefix = ChatReplyCommandRules.normalizePrefix(module.commandPrefix().value())
                if (!ChatReplyConfigRules.validResponse(expanded) || prefix == null || expanded.startsWith(prefix)) {
                    module.recordRequestDropped("tag_expansion_rejected")
                    return
                }
                expanded
            }
            ChatReplyCommandRules.Command.PARTY_TRANSFER,
            ChatReplyCommandRules.Command.PARTY_WARP,
            ChatReplyCommandRules.Command.PARTY_ALLINVITE,
            ChatReplyCommandRules.Command.PARTY_BOOP,
            ChatReplyCommandRules.Command.PARTY_KICK,
            ChatReplyCommandRules.Command.PARTY_PROMOTE,
            ChatReplyCommandRules.Command.PARTY_DEMOTE,
            ChatReplyCommandRules.Command.PARTY_KICK_OFFLINE,
            ChatReplyCommandRules.Command.PARTY_QUEUE_INSTANCE,
            ChatReplyCommandRules.Command.PRIVATE_INVITE -> null
            else -> throw NoWhenBranchMatchedException()
        }
        val command = if (ChatReplyCommandRules.isPartyAction(request.command())) {
            ChatReplyCommandRules.incomingPartyActionCommand(request, party, localPlayerName).orElse(null)
        } else if (request.command() == ChatReplyCommandRules.Command.PRIVATE_INVITE) {
            val invite = ChatReplyCommandRules.privateInviteCommand(request).orElse(null)
            if (invite != null && !module.inviteAutoConfirm().value()) {
                promptInvite(client, invite, request.sender()!!)
                module.recordFeatureOutcome("INVITE_PROMPTED")
                return
            }
            invite
        } else {
            ChatReplyCommandRules.replyCommand(request.replyChannel(), response)
        }
        if (command != null) {
            player.connection.sendCommand(command)
            module.recordQueueDiagnostic(PartyCommandsModule.QueueDiagnosticOutcome.COMMAND_SUBMITTED, pending.size())
            if (ChatReplyCommandRules.isPartyAction(request.command())) {
                module.recordPartyActionDiagnostic(
                    request.command()!!,
                    PartyCommandsModule.PartyActionDiagnosticOutcome.SUBMITTED,
                )
            }
        } else if (ChatReplyCommandRules.isPartyAction(request.command())) {
            module.recordPartyActionDiagnostic(
                request.command()!!,
                PartyCommandsModule.PartyActionDiagnosticOutcome.BLOCKED_PARTY_OR_LEADER_OR_TARGET,
            )
        }
        val sendInterval = maxOf(1, queued.delayTicks)
        if (command != null && request.command() == ChatReplyCommandRules.Command.HELP && helpLines != null &&
            queued.helpLineIndex + 1 < helpLines.size
        ) {
            val offered = pending.offer(
                Pending(
                    request,
                    queued.connection,
                    queued.partyGeneration,
                    queued.delayTicks,
                    queued.helpLineIndex + 1,
                ),
                clientTick + sendInterval,
            )
            module.recordQueueDiagnostic(
                if (offered) PartyCommandsModule.QueueDiagnosticOutcome.OFFERED
                else PartyCommandsModule.QueueDiagnosticOutcome.OFFER_REJECTED,
                pending.size(),
            )
        }
        nextSendTick = clientTick + sendInterval
    }

    @JvmStatic
    fun clear() {
        reinvites.clear()
        reminderTick = -1L
        PartyDowntime.clear()
        val cleared = pending.size()
        if (cleared > 0) {
            PartyCommandsModule.INSTANCE.recordQueueDiagnostic(
                PartyCommandsModule.QueueDiagnosticOutcome.CLEARED,
                cleared,
            )
        }
        pending.clear()
        nextSendTick = 0L
        privateCounterpart = null
    }

    /** Observes explicit client whisper commands so a delayed /r cannot follow a changed target. */
    @JvmStatic
    fun onOutgoingCommand(command: String?) {
        ChatReplyCommandRules.outgoingWhisperTarget(command).ifPresent { target -> privateCounterpart = target }
    }

    /** Executes a locally typed !pt/!transfer action without exposing it as party chat. */
    @JvmStatic
    fun handleLocalPartyTransfer(input: String?): Boolean {
        val module = PartyCommandsModule.INSTANCE
        if (!module.isEnabled() || !module.chatRepliesEnabled().value()) return false
        val prefix = module.commandPrefix().value()
        if (!ChatReplyCommandRules.isLocalPartyTransferTrigger(input, prefix)) return false
        val client = Minecraft.getInstance()
        val transfer = ChatReplyCommandRules.parseLocalPartyTransfer(input, prefix).orElse(null)
        if (transfer == null) {
            showLocalMessage(client, "Usage: $prefix" + "pt <username>")
            return true
        }
        val player = client.player
        val connection = client.getConnection()
        if (player == null || connection == null) {
            showLocalMessage(client, "Party transfer is unavailable while disconnected.")
            return true
        }
        val localName = localName(client)
        val party = PartyListBackend.snapshot()
        val localRequest = ChatReplyCommandRules.Request(
            ChatChannel.PARTY,
            localName,
            ChatReplyCommandRules.Command.PARTY_TRANSFER,
            transfer.target(),
            null,
        )
        val command = ChatReplyCommandRules.incomingPartyActionCommand(localRequest, party, localName).orElse(null)
        if (command == null) {
            showLocalMessage(client, "Transfer needs you as party leader and a valid username.")
            return true
        }
        player.connection.sendCommand(command)
        module.recordQueueDiagnostic(PartyCommandsModule.QueueDiagnosticOutcome.COMMAND_SUBMITTED, pending.size())
        return true
    }

    /** Executes only locally typed, explicitly configured one-command actions; received messages never call this. */
    @JvmStatic
    fun handleLocalCustomAction(input: String?): Boolean {
        val module = PartyCommandsModule.INSTANCE
        if (!module.isEnabled() || !module.chatRepliesEnabled().value()) return false
        val store = ChatChannelsLocalStore.instance()
        val request = ChatReplyCommandRules.parseLocalAction(input, module.commandPrefix().value(), store.chatReplies())
            .orElse(null) ?: return false
        val entry = request.entry() ?: return false
        if (!store.isChatReplyEnabled(entry)) {
            showLocalMessage(
                Minecraft.getInstance(),
                "That local action is disabled by its command or folder switch.",
            )
            return true
        }
        val command = ChatActionTemplateRules.expand(entry.actionTemplate(), request.arguments()).orElse(null)
        if (entry.mode() != ChatReplyEntry.Mode.LOCAL_ACTION || command == null) {
            showLocalMessage(
                Minecraft.getInstance(),
                "Usage: " + module.commandPrefix().value() + entry.command() +
                    " <args>; use one single-line command template with {args}.",
            )
            return true
        }
        val client = Minecraft.getInstance()
        val player = client.player
        val connection = client.getConnection()
        if (player == null || connection == null) {
            showLocalMessage(client, "Local action is unavailable while disconnected.")
            return true
        }
        player.connection.sendCommand(command)
        module.recordQueueDiagnostic(PartyCommandsModule.QueueDiagnosticOutcome.COMMAND_SUBMITTED, pending.size())
        showLocalMessage(client, "Local action sent: /$command")
        return true
    }

    private fun showLocalMessage(client: Minecraft?, text: String) {
        val chat = client?.gui?.hud?.getChat() ?: return
        chat.addClientSystemMessage(Component.literal(text))
    }

    private fun localName(client: Minecraft): String? {
        val player = client.player ?: return null
        return player.gameProfile.name
    }

    private fun sameIgnoreCase(first: String?, second: String?): Boolean =
        first != null && second != null && first.equals(second, ignoreCase = true)

    /** Downtime and reinvite are handled on this client and send no reply; true when the request was one of them. */
    private fun handleLocalFeature(
        request: ChatReplyCommandRules.Request,
        party: PartySnapshot,
        localPlayerName: String?,
        clientTick: Long,
        module: PartyCommandsModule,
    ): Boolean {
        val sender = request.sender() ?: return false
        when (request.command()) {
            ChatReplyCommandRules.Command.PARTY_DOWNTIME -> {
                if (PartyDowntime.add(sender, request.argument())) {
                    module.showFeedbackPublic("Downtime reminder set for the end of the run. Auto requeue is held for this run.")
                    module.recordFeatureOutcome("DT_ADDED")
                } else {
                    module.showFeedbackPublic("$sender already has a downtime reminder.")
                    module.recordFeatureOutcome("DT_DUPLICATE")
                }
            }
            ChatReplyCommandRules.Command.PARTY_UNDOWNTIME -> {
                val removed = PartyDowntime.remove(sender)
                module.showFeedbackPublic(if (removed) "Downtime reminder removed." else "$sender has no downtime reminder.")
                module.recordFeatureOutcome(if (removed) "DT_REMOVED" else "DT_MISSING")
            }
            ChatReplyCommandRules.Command.PARTY_REINVITE -> {
                val otherLeads = party.inParty() && party.leaderName() != null && !party.isLeader(localPlayerName)
                if (otherLeads || reinvites.size >= MAX_REINVITES) {
                    module.recordRequestDropped("reinvite_not_allowed")
                } else {
                    reinvites.addLast((clientTick + REINVITE_DELAY_TICKS) to sender)
                    module.showFeedbackPublic("Reinviting $sender in 5 seconds...")
                    module.recordFeatureOutcome("REINVITE_QUEUED")
                }
            }
            else -> return false
        }
        return true
    }

    /** Sends due reinvites and the end-of-run downtime reminder. */
    private fun runTimers(client: Minecraft, clientTick: Long) {
        val player = client.player ?: return
        val module = PartyCommandsModule.INSTANCE
        val due = reinvites.firstOrNull()
        if (due != null && clientTick >= due.first) {
            reinvites.removeFirst()
            player.connection.sendCommand("party invite ${due.second}")
            module.recordFeatureOutcome("REINVITE_SENT")
        }
        if (reminderTick in 0..clientTick) {
            reminderTick = -1L
            PartyDowntime.reasonFor(localName(client))?.let { player.connection.sendCommand("pc Downtime needed: $it") }
            module.showFeedbackPublic("Downtime reasons: ${PartyDowntime.summary()}")
            module.recordFeatureOutcome("DT_REMINDER")
            PartyDowntime.clear()
        }
    }

    private fun promptInvite(client: Minecraft, command: String, name: String) {
        val chat = client.gui?.hud?.getChat() ?: return
        chat.addClientSystemMessage(
            Component.literal("[Party Commands] ").append(
                Component.literal("Click to invite $name to your party.").withStyle { style ->
                    style.withUnderlined(true)
                        .withClickEvent(ClickEvent.RunCommand("/$command"))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("/$command")))
                },
            ),
        )
    }

    private data class Pending(
        val request: ChatReplyCommandRules.Request,
        val connection: Any,
        val partyGeneration: Long,
        val delayTicks: Int,
        val helpLineIndex: Int,
    )
}
