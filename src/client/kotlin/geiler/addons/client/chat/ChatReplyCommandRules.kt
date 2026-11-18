package geiler.addons.client.chat

import geiler.addons.client.party.PartyMember
import geiler.addons.client.party.PartySnapshot
import geiler.addons.client.tree.ChatText
import geiler.addons.client.utility.BuiltInAliases
import java.util.ArrayList
import java.util.Locale
import java.util.Optional
import java.util.regex.Pattern

/** Channel-aware parsing and routing rules for local and incoming chat commands. */
object ChatReplyCommandRules {
    enum class Command {
        HELP,
        COORDS,
        ODIN_LINK,
        COIN_FLIP,
        EIGHT_BALL,
        DICE,
        PING,
        TPS,
        FPS,
        TIME,
        LOBBY,
        HOLDING,
        PARTY_TRANSFER,
        PARTY_WARP,
        PARTY_ALLINVITE,
        PARTY_BOOP,
        PARTY_KICK,
        PARTY_PROMOTE,
        PARTY_DEMOTE,
        PARTY_KICK_OFFLINE,
        PARTY_QUEUE_INSTANCE,
        PRIVATE_INVITE,
        PARTY_DOWNTIME,
        PARTY_UNDOWNTIME,
        PARTY_REINVITE,
        CUSTOM_REPLY,
    }

    class LocalPartyTransfer(target: String?) {
        private val targetValue = target

        fun target(): String? = targetValue

        override fun equals(other: Any?): Boolean = other is LocalPartyTransfer && targetValue == other.targetValue

        override fun hashCode(): Int = targetValue?.hashCode() ?: 0

        override fun toString(): String = "LocalPartyTransfer[target=$targetValue]"
    }

    class LocalActionRequest(entry: ChatReplyEntry?, arguments: String?) {
        private val entryValue = entry
        private val argumentsValue = arguments

        fun entry(): ChatReplyEntry? = entryValue
        fun arguments(): String? = argumentsValue

        override fun equals(other: Any?): Boolean = other is LocalActionRequest &&
            entryValue == other.entryValue && argumentsValue == other.argumentsValue

        override fun hashCode(): Int = 31 * (entryValue?.hashCode() ?: 0) + (argumentsValue?.hashCode() ?: 0)

        override fun toString(): String = "LocalActionRequest[entry=$entryValue, arguments=$argumentsValue]"
    }

    class Request(
        channel: ChatChannel?,
        sender: String?,
        command: Command?,
        argument: String?,
        response: String?,
        replyChannel: ChatChannel?,
    ) {
        private val channelValue = channel
        private val senderValue = sender
        private val commandValue = command
        private val argumentValue = argument
        private val responseValue = response
        private val replyChannelValue = replyChannel ?: channel

        constructor(
            channel: ChatChannel?,
            sender: String?,
            command: Command?,
            argument: String?,
            response: String?,
        ) : this(channel, sender, command, argument, response, channel)

        fun channel(): ChatChannel? = channelValue
        fun sender(): String? = senderValue
        fun command(): Command? = commandValue
        fun argument(): String? = argumentValue
        fun response(): String? = responseValue
        fun replyChannel(): ChatChannel? = replyChannelValue

        override fun equals(other: Any?): Boolean = other is Request &&
            channelValue == other.channelValue && senderValue == other.senderValue &&
            commandValue == other.commandValue && argumentValue == other.argumentValue &&
            responseValue == other.responseValue && replyChannelValue == other.replyChannelValue

        override fun hashCode(): Int {
            var result = channelValue?.hashCode() ?: 0
            result = 31 * result + (senderValue?.hashCode() ?: 0)
            result = 31 * result + (commandValue?.hashCode() ?: 0)
            result = 31 * result + (argumentValue?.hashCode() ?: 0)
            result = 31 * result + (responseValue?.hashCode() ?: 0)
            result = 31 * result + (replyChannelValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Request[channel=$channelValue, sender=$senderValue, command=$commandValue, argument=$argumentValue, " +
                "response=$responseValue, replyChannel=$replyChannelValue]"
    }

    private const val MAX_CHAT_LENGTH = 512
    private const val MAX_PREFIX_LENGTH = 4
    private const val MAX_HELP_LENGTH = 170

    private val CHANNEL_LINE = Pattern.compile(
        "(?i)^(party|guild|co-?op)\\s*>\\s*(?:\\[[^]]+]\\s*)*([a-z0-9_]{1,16})(?:\\s+[\u1243\u2692]|\\s+\\[[^]]*])?\\s*:\\s*(.*)$",
    )
    private val PRIVATE_LINE = Pattern.compile(
        "(?i)^from\\s+(?:\\[[^]]+]\\s*)*([a-z0-9_]{1,16})\\s*:\\s*(.*)$",
    )
    private val CUSTOM_CHANNEL_LINE = Pattern.compile(
        "(?i)^(?:[^:>]{1,80}>\\s*)?(?:\\[[^]]+]\\s*)*([a-z0-9_]{1,16})\\s*:\\s*(.*)$",
    )
    private val OFFICER_LINE = Pattern.compile("(?i)^officer\\s*>.*$")
    private val USERNAME = ChatStringSemantics.MINECRAFT_USERNAME
    private val WHITESPACE = Pattern.compile("\\s+")
    private val QUEUE_INSTANCE_COMMANDS = setOf(
        "f1", "f2", "f3", "f4", "f5", "f6", "f7",
        "m1", "m2", "m3", "m4", "m5", "m6", "m7",
        "t1", "t2", "t3", "t4", "t5",
    )

    @JvmStatic
    fun parse(rawChat: String?, configuredPrefix: String?): Optional<ChatReplyCommandRules.Request> =
        parse(rawChat, configuredPrefix, emptyList(), emptyList())

    @JvmStatic
    fun parse(
        rawChat: String?,
        configuredPrefix: String?,
        customReplies: List<ChatReplyEntry?>?,
    ): Optional<ChatReplyCommandRules.Request> =
        parse(rawChat, configuredPrefix, customReplies, emptyList())

    @JvmStatic
    fun parse(
        rawChat: String?,
        configuredPrefix: String?,
        customReplies: List<ChatReplyEntry?>?,
        channelRules: List<ChatChannelRule?>?,
    ): Optional<ChatReplyCommandRules.Request> {
        val prefix = normalizePrefix(configuredPrefix)
        if (prefix == null || rawChat == null || rawChat.length > MAX_CHAT_LENGTH) return Optional.empty()
        val line = ChatText.stripForMatch(rawChat)
        if (OFFICER_LINE.matcher(line).matches()) return Optional.empty()

        val channel: ChatChannel
        val replyChannel: ChatChannel
        val sender: String
        val body: String
        val channelLine = CHANNEL_LINE.matcher(line)
        if (channelLine.matches()) {
            channel = when (channelLine.group(1).lowercase(Locale.ROOT).replace("-", "")) {
                "party" -> ChatChannel.PARTY
                "guild" -> ChatChannel.GUILD
                else -> ChatChannel.COOP
            }
            sender = channelLine.group(2)
            body = channelLine.group(3)
            replyChannel = matchingRule(line, sender, channelRules)
                .map(ChatChannelRule::tab)
                .orElse(channel)
        } else {
            val privateLine = PRIVATE_LINE.matcher(line)
            if (privateLine.matches()) {
                channel = ChatChannel.PRIVATE
                sender = privateLine.group(1)
                body = privateLine.group(2)
                // Custom rules classify private lines, but private replies always use /r.
                replyChannel = channel
            } else {
                val customLine = CUSTOM_CHANNEL_LINE.matcher(line)
                if (!customLine.matches()) return Optional.empty()
                sender = customLine.group(1)
                val rule = matchingRule(line, sender, channelRules).orElse(null) ?: return Optional.empty()
                channel = rule.tab()
                replyChannel = channel
                body = customLine.group(2)
            }
        }

        val commandText = stripLeadingJavaWhitespace(body)
        if (!commandText.startsWith(prefix)) return Optional.empty()
        val remainder = javaTrim(commandText.substring(prefix.length))
        if (remainder.isEmpty()) return Optional.empty()
        val parts = WHITESPACE.split(remainder, 0)
        val name = parts[0].lowercase(Locale.ROOT)
        if (name == "help" || name == "h") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.HELP)
        }
        if (name == "coords" || name == "co") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.COORDS)
        }
        if (name == "odin" || name == "od") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.ODIN_LINK)
        }
        if (name == "cf") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.COIN_FLIP)
        }
        if (name == "8ball") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.EIGHT_BALL)
        }
        if (name == "dice") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.DICE)
        }
        if (name == "ping") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.PING)
        }
        if (name == "tps") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.TPS)
        }
        if (name == "fps") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.FPS)
        }
        if (name == "time") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.TIME)
        }
        if (name == "lobby" || name == "location") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.LOBBY)
        }
        if (name == "holding") {
            return noArgument(parts, channel, replyChannel, sender, ChatReplyCommandRules.Command.HOLDING)
        }
        if ((name == "inv" || name == "invite") && channel == ChatChannel.PRIVATE && parts.size == 1) {
            return Optional.of(Request(channel, sender, Command.PRIVATE_INVITE, null, null, replyChannel))
        }
        if (channel == ChatChannel.PARTY) {
            when (name) {
                "warp", "w" -> return noArgument(parts, channel, replyChannel, sender, Command.PARTY_WARP)
                "allinvite", "allinv" ->
                    return noArgument(parts, channel, replyChannel, sender, Command.PARTY_ALLINVITE)
                "boop" -> return partyTargetRequest(parts, channel, replyChannel, sender, Command.PARTY_BOOP)
                "kick", "k" -> return partyTargetRequest(parts, channel, replyChannel, sender, Command.PARTY_KICK)
                "promote" -> return partyTargetRequest(parts, channel, replyChannel, sender, Command.PARTY_PROMOTE)
                "demote" -> return partyTargetRequest(parts, channel, replyChannel, sender, Command.PARTY_DEMOTE)
                "kickoffline", "ko" ->
                    return noArgument(parts, channel, replyChannel, sender, Command.PARTY_KICK_OFFLINE)
                "reinv", "reinvite" -> return noArgument(parts, channel, replyChannel, sender, Command.PARTY_REINVITE)
                "undt", "undowntime" -> return noArgument(parts, channel, replyChannel, sender, Command.PARTY_UNDOWNTIME)
                "dt", "downtime" -> return Optional.of(
                    Request(channel, sender, Command.PARTY_DOWNTIME, downtimeReason(remainder, parts[0]), null, replyChannel),
                )
                else -> if (name in QUEUE_INSTANCE_COMMANDS && parts.size == 1) {
                    return Optional.of(Request(channel, sender, Command.PARTY_QUEUE_INSTANCE, name, null, replyChannel))
                }
            }
        }
        if (name == "pt" || name == "ptme" || name == "transfer") {
            if (channel != ChatChannel.PARTY || parts.size > 2 ||
                parts.size == 2 && !USERNAME.matcher(parts[1]).matches()
            ) return Optional.empty()
            // Party transfer aliases default to the requesting member when no target is supplied.
            return Optional.of(
                ChatReplyCommandRules.Request(
                    channel,
                    sender,
                    ChatReplyCommandRules.Command.PARTY_TRANSFER,
                    if (parts.size == 2) parts[1] else sender,
                    null,
                    replyChannel,
                ),
            )
        }
        if (parts.size == 1 && customReplies != null) {
            for (entry in customReplies) {
                if (entry == null || !entry.enabled() || !entry.isValid() ||
                    entry.mode() != ChatReplyEntry.Mode.TEXT_REPLY || entry.command() != name
                ) continue
                // Replies that begin with the active prefix would recursively trigger this handler.
                if (entry.response().startsWith(prefix)) return Optional.empty()
                return Optional.of(
                    ChatReplyCommandRules.Request(
                        channel,
                        sender,
                        ChatReplyCommandRules.Command.CUSTOM_REPLY,
                        name,
                        entry.response(),
                        replyChannel,
                    ),
                )
            }
        }
        return Optional.empty()
    }

    /** Recognizes a locally typed action row; this parser is never used for received chat messages. */
    @JvmStatic
    fun parseLocalAction(
        input: String?,
        configuredPrefix: String?,
        entries: List<ChatReplyEntry?>?,
    ): Optional<ChatReplyCommandRules.LocalActionRequest> {
        val prefix = normalizePrefix(configuredPrefix)
        if (prefix == null || input == null || input.length > MAX_CHAT_LENGTH) return Optional.empty()
        val text = stripLeadingJavaWhitespace(input)
        if (!text.startsWith(prefix)) return Optional.empty()
        val remainder = stripLeadingJavaWhitespace(text.substring(prefix.length))
        if (remainder.isEmpty()) return Optional.empty()
        val parts = WHITESPACE.split(remainder, 2)
        val name = parts[0].lowercase(Locale.ROOT)
        val arguments = if (parts.size == 2) javaTrim(parts[1]) else ""
        var unsafeInput = false
        for (current in input) {
            val type = Character.getType(current)
            if (Character.isISOControl(current) ||
                type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                current == '\u00a7'
            ) {
                unsafeInput = true
                break
            }
        }
        val safeArguments = if (unsafeInput) null else arguments
        if (entries == null) return Optional.empty()
        for (entry in entries) {
            if (entry != null && entry.isValid() && entry.mode() == ChatReplyEntry.Mode.LOCAL_ACTION &&
                entry.command() == name
            ) {
                return Optional.of(ChatReplyCommandRules.LocalActionRequest(entry, safeArguments))
            }
        }
        return Optional.empty()
    }

    /** Parses a locally typed Party transfer command before it is sent as ordinary chat. */
    @JvmStatic
    fun parseLocalPartyTransfer(
        input: String?,
        configuredPrefix: String?,
    ): Optional<ChatReplyCommandRules.LocalPartyTransfer> {
        val prefix = normalizePrefix(configuredPrefix)
        if (prefix == null || input == null || input.length > MAX_CHAT_LENGTH) return Optional.empty()
        val text = stripLeadingJavaWhitespace(input)
        if (!text.startsWith(prefix)) return Optional.empty()
        val remainder = javaTrim(text.substring(prefix.length))
        if (remainder.isEmpty()) return Optional.empty()
        val parts = WHITESPACE.split(remainder, 0)
        if (parts.size != 2 || !isPartyTransferName(parts[0]) || !USERNAME.matcher(parts[1]).matches()) {
            return Optional.empty()
        }
        return Optional.of(ChatReplyCommandRules.LocalPartyTransfer(parts[1]))
    }

    /** Recognizes local help with the same configurable prefix as incoming chat replies. */
    @JvmStatic
    fun isLocalHelpCommand(input: String?, configuredPrefix: String?): Boolean {
        val prefix = normalizePrefix(configuredPrefix)
        if (prefix == null || input == null || input.length > MAX_CHAT_LENGTH) return false
        val text = stripLeadingJavaWhitespace(input)
        if (!text.startsWith(prefix)) return false
        val remainder = javaTrim(text.substring(prefix.length))
        if (remainder.isEmpty()) return false
        val parts = WHITESPACE.split(remainder, 0)
        val name = parts[0].lowercase(Locale.ROOT)
        return parts.size == 1 && (name == "help" || name == "h")
    }

    /** Recognizes malformed local transfer attempts too, so they are not leaked as chat text. */
    @JvmStatic
    fun isLocalPartyTransferTrigger(input: String?, configuredPrefix: String?): Boolean {
        val prefix = normalizePrefix(configuredPrefix)
        if (prefix == null || input == null || input.length > MAX_CHAT_LENGTH) return false
        val text = stripLeadingJavaWhitespace(input)
        if (!text.startsWith(prefix)) return false
        val remainder = javaTrim(text.substring(prefix.length))
        return remainder.isNotEmpty() && isPartyTransferName(WHITESPACE.split(remainder, 2)[0])
    }

    @JvmStatic
    fun incomingPrivateSender(rawChat: String?): Optional<String> {
        if (rawChat == null || rawChat.length > MAX_CHAT_LENGTH) return Optional.empty()
        val matcher = PRIVATE_LINE.matcher(ChatText.stripForMatch(rawChat))
        return if (matcher.matches()) Optional.of(matcher.group(1)) else Optional.empty()
    }

    /** Resolves explicit local whisper commands whose target can replace the server's /r target. */
    @JvmStatic
    fun outgoingWhisperTarget(command: String?): Optional<String> {
        if (command == null || command.length > MAX_CHAT_LENGTH) return Optional.empty()
        var text = javaTrim(command)
        if (text.startsWith("/")) text = stripLeadingJavaWhitespace(text.substring(1))
        val parts = WHITESPACE.split(text, 0)
        if (parts.size < 2) return Optional.empty()
        val alias = parts[0].lowercase(Locale.ROOT)
        if (alias !in listOf("msg", "message", "tell", "whisper", "w", "m") ||
            !USERNAME.matcher(parts[1]).matches()
        ) return Optional.empty()
        return Optional.of(parts[1])
    }

    @JvmStatic
    fun privateReplyRecipientMatches(
        request: ChatReplyCommandRules.Request?,
        observedCounterpart: String?,
    ): Boolean = request != null && (!ChatChannel.PRIVATE.equals(request.channel()) ||
        observedCounterpart != null && request.sender()!!.equals(observedCounterpart, ignoreCase = true))

    @JvmStatic
    fun remotePartyTransferAllowed(
        request: ChatReplyCommandRules.Request?,
        allowlistEnabled: Boolean,
        allowlist: List<String?>?,
        blocklistEnabled: Boolean,
        blocklist: List<String?>?,
    ): Boolean = request != null && request.command() == ChatReplyCommandRules.Command.PARTY_TRANSFER &&
        remotePartyActionAllowed(request, allowlistEnabled, allowlist, blocklistEnabled, blocklist)

    /**
     * Every party action is open to any sender; only an enabled blocklist vetoes. The allowlist arguments are legacy
     * (the Party Commands allowlist was removed) and are ignored.
     */
    @JvmStatic
    fun remotePartyActionAllowed(
        request: Request?,
        allowlistEnabled: Boolean,
        allowlist: List<String?>?,
        blocklistEnabled: Boolean,
        blocklist: List<String?>?,
    ): Boolean = request != null && isRemotePartyAction(request.command()) &&
        ChatChannel.PARTY.equals(request.channel()) &&
        ChatReplyConfigRules.senderAllowed(request.sender(), false, allowlist, blocklistEnabled, blocklist)

    /** `party invite <sender>` for a private `!inv`; the sender is re-validated as a plain username. */
    @JvmStatic
    fun privateInviteCommand(request: Request?): Optional<String> =
        if (request != null && request.command() == Command.PRIVATE_INVITE && ChatChannel.PRIVATE.equals(request.channel()) &&
            request.sender() != null && USERNAME.matcher(request.sender()!!).matches()
        ) Optional.of("party invite ${request.sender()}") else Optional.empty()

    @JvmStatic
    fun isPartyAction(command: Command?): Boolean = command != null && command in PARTY_ACTIONS

    @JvmStatic
    fun isRemotePartyAction(command: Command?): Boolean = command != null && command in REMOTE_PARTY_ACTIONS

    @JvmStatic
    fun requiresPartyLeader(command: Command?): Boolean = command != null && command in LEADER_PARTY_ACTIONS

    /** Resolves only fixed, allowlisted party actions against a fresh roster at execution time. */
    @JvmStatic
    fun partyActionCommand(
        request: Request?,
        party: PartySnapshot?,
        freshRoster: Boolean,
        localPlayerName: String?,
    ): Optional<String> {
        if (request == null || !isPartyAction(request.command()) || !ChatChannel.PARTY.equals(request.channel()) ||
            party == null || !party.inParty() || !freshRoster || !contains(party.members(), request.sender())
        ) return Optional.empty()
        val command = request.command()!!
        if (requiresPartyLeader(command) && !party.isLeader(localPlayerName)) return Optional.empty()
        val target = request.argument()?.let { resolvePartyTarget(party.members(), it) }
        return when (command) {
            Command.PARTY_TRANSFER -> {
                if (target == null || sameIgnoreCase(target.name(), localPlayerName)) Optional.empty()
                else Optional.of("party transfer ${target.name()}")
            }
            Command.PARTY_WARP -> Optional.of("party warp")
            Command.PARTY_ALLINVITE -> Optional.of("party settings allinvite")
            Command.PARTY_BOOP -> {
                val name = request.argument()?.takeIf { USERNAME.matcher(it).matches() }
                name?.let { Optional.of("boop $it") } ?: Optional.empty()
            }
            Command.PARTY_KICK ->
                if (target == null || sameIgnoreCase(target.name(), localPlayerName)) Optional.empty()
                else Optional.of("party kick ${target.name()}")
            Command.PARTY_PROMOTE ->
                if (target == null || sameIgnoreCase(target.name(), localPlayerName)) Optional.empty()
                else Optional.of("party promote ${target.name()}")
            Command.PARTY_DEMOTE ->
                if (target == null || sameIgnoreCase(target.name(), localPlayerName)) Optional.empty()
                else Optional.of("party demote ${target.name()}")
            Command.PARTY_KICK_OFFLINE -> Optional.of("party kickoffline")
            Command.PARTY_QUEUE_INSTANCE -> {
                val instance = request.argument()?.lowercase(Locale.ROOT)
                    ?.takeIf { it in QUEUE_INSTANCE_COMMANDS }
                instance?.let { BuiltInAliases.command(it) }?.let { Optional.of(it) } ?: Optional.empty()
            }
            else -> Optional.empty()
        }
    }

    /**
     * The command for a request that arrived as `Party > name: !cmd`. The prefix is trusted: no fresh roster or roster
     * membership is needed (Odin does the same). Leader-only actions run only when the local player is the known leader;
     * with an unknown leader they do nothing, as in Odin.
     */
    @JvmStatic
    fun incomingPartyActionCommand(request: Request?, party: PartySnapshot?, localPlayerName: String?): Optional<String> {
        if (request == null || !isPartyAction(request.command()) || !ChatChannel.PARTY.equals(request.channel())) {
            return Optional.empty()
        }
        val command = request.command()!!
        if (requiresPartyLeader(command) &&
            !(party != null && party.inParty() && party.leaderName() != null && party.isLeader(localPlayerName))
        ) return Optional.empty()
        val argument = request.argument()
        val target = if (argument == null) null else {
            (if (party != null && party.inParty()) resolvePartyTarget(party.members(), argument)?.name() else null)
                ?: argument.takeIf { USERNAME.matcher(it).matches() }
        }
        fun targeted(verb: String): Optional<String> =
            if (target == null || sameIgnoreCase(target, localPlayerName)) Optional.empty()
            else Optional.of("party $verb $target")
        return when (command) {
            Command.PARTY_TRANSFER -> targeted("transfer")
            Command.PARTY_KICK -> targeted("kick")
            Command.PARTY_PROMOTE -> targeted("promote")
            Command.PARTY_DEMOTE -> targeted("demote")
            Command.PARTY_WARP -> Optional.of("party warp")
            Command.PARTY_ALLINVITE -> Optional.of("party settings allinvite")
            Command.PARTY_KICK_OFFLINE -> Optional.of("party kickoffline")
            Command.PARTY_BOOP -> target?.let { Optional.of("boop $it") } ?: Optional.empty()
            Command.PARTY_QUEUE_INSTANCE -> argument?.lowercase(Locale.ROOT)?.takeIf { it in QUEUE_INSTANCE_COMMANDS }
                ?.let { BuiltInAliases.command(it) }?.let { Optional.of(it) } ?: Optional.empty()
            else -> Optional.empty()
        }
    }

    /** The words after `!dt`, kept as typed but limited to printable text and 60 characters; null when empty. */
    private fun downtimeReason(remainder: String, commandWord: String): String? {
        val printable = remainder.substring(commandWord.length).filter { it.code >= 0x20 && !Character.isISOControl(it) && it != '\u00a7' }
        return javaTrim(javaTrim(printable).take(60)).ifEmpty { null }
    }

    private fun noArgument(
        parts: Array<String>,
        channel: ChatChannel,
        replyChannel: ChatChannel,
        sender: String,
        command: ChatReplyCommandRules.Command,
    ): Optional<ChatReplyCommandRules.Request> = if (parts.size == 1) {
        Optional.of(ChatReplyCommandRules.Request(channel, sender, command, null, null, replyChannel))
    } else {
        Optional.empty()
    }

    private fun partyTargetRequest(
        parts: Array<String>,
        channel: ChatChannel,
        replyChannel: ChatChannel,
        sender: String,
        command: Command,
    ): Optional<Request> {
        if (parts.size > 2) return Optional.empty()
        val target = if (parts.size == 2) parts[1] else sender
        if (!USERNAME.matcher(target).matches()) return Optional.empty()
        return Optional.of(Request(channel, sender, command, target, null, replyChannel))
    }

    private fun matchingRule(
        line: String,
        sender: String?,
        rules: List<ChatChannelRule?>?,
    ): Optional<ChatChannelRule> {
        if (rules == null || sender == null || !USERNAME.matcher(sender).matches()) return Optional.empty()
        var best: ChatChannelRule? = null
        for (rule in rules) {
            if (rule == null || ChatStringSemantics.isBlank(rule.commandPrefix()) || !rule.matches(line, sender)) continue
            if (best == null || rule.incomingContains().length > best.incomingContains().length) best = rule
        }
        return Optional.ofNullable(best)
    }

    private fun isPartyTransferName(value: String?): Boolean =
        value != null && (value.equals("pt", ignoreCase = true) ||
            value.equals("ptme", ignoreCase = true) || value.equals("transfer", ignoreCase = true))

    /** A safe short prefix keeps settings from turning chat text into a command string. */
    @JvmStatic
    fun normalizePrefix(configuredPrefix: String?): String? {
        if (configuredPrefix == null) return null
        val prefix = javaTrim(configuredPrefix)
        if (prefix.isEmpty() || prefix.length > MAX_PREFIX_LENGTH) return null
        for (current in prefix) {
            if (Character.isWhitespace(current) || Character.isISOControl(current) ||
                current == '/' || current == '\\' || current == '\u00a7'
            ) return null
        }
        return prefix
    }

    /** Resolves a transfer only against a fresh, complete roster and a current local-leader state. */
    @JvmStatic
    fun partyTransferCommand(
        request: ChatReplyCommandRules.Request?,
        party: PartySnapshot?,
        freshRoster: Boolean,
        localPlayerName: String?,
    ): Optional<String> = if (request?.command() == Command.PARTY_TRANSFER) {
        partyActionCommand(request, party, freshRoster, localPlayerName)
    } else {
        Optional.empty()
    }

    @JvmStatic
    fun helpText(prefix: String?, channel: ChatChannel?): String? = helpText(prefix, channel, emptyList())

    @JvmStatic
    fun helpText(
        prefix: String?,
        channel: ChatChannel?,
        customReplies: List<ChatReplyEntry?>?,
    ): String? {
        val lines = helpLines(prefix, channel, customReplies) ?: return null
        return lines.joinToString("\n")
    }

    /** Full help listing split into safe single-message lines for local display and chat replies. */
    @JvmStatic
    fun helpLines(
        prefix: String?,
        channel: ChatChannel?,
        customReplies: List<ChatReplyEntry?>?,
    ): List<String>? {
        val safePrefix = normalizePrefix(prefix)
        if (safePrefix == null || channel == null) return null
        val commands = ArrayList<String>().apply {
            addAll(
                listOf(
                    safePrefix + "help/" + safePrefix + "h",
                    safePrefix + "coords/" + safePrefix + "co",
                    safePrefix + "odin/" + safePrefix + "od",
                    safePrefix + "cf",
                    safePrefix + "8ball",
                    safePrefix + "dice",
                    safePrefix + "ping",
                    safePrefix + "tps",
                    safePrefix + "fps",
                    safePrefix + "time",
                    safePrefix + "lobby",
                    safePrefix + "holding",
                    safePrefix + "pt [username] (Party leader)",
                    safePrefix + "ptme [username] (Party leader)",
                    safePrefix + "transfer [username] (Party leader)",
                    safePrefix + "warp (Party leader)",
                    safePrefix + "allinvite (Party leader)",
                    safePrefix + "boop [username] (Party only)",
                    safePrefix + "kick [username] (Party leader)",
                    safePrefix + "promote [username] (Party leader)",
                    safePrefix + "demote [username] (Party leader)",
                    safePrefix + "kickoffline (Party leader)",
                    safePrefix + "f1-f7/m1-m7/t1-t5 (queue, Party leader)",
                    safePrefix + "dt [reason]/" + safePrefix + "undt (Party, downtime reminder)",
                    safePrefix + "reinvite (Party)",
                    safePrefix + "inv (Private)",
                ),
            )
        }
        if (customReplies != null) {
            for (entry in customReplies) {
                if (entry != null && entry.enabled() && entry.isValid()) {
                    commands.add(
                        safePrefix + entry.command() +
                            if (entry.mode() == ChatReplyEntry.Mode.LOCAL_ACTION) " <args> (local action)" else "",
                    )
                }
            }
        }
        val lines = ArrayList<String>()
        var line = StringBuilder("Commands:")
        for (command in commands) {
            var separator = if (line.length == "Commands:".length) " " else ", "
            if (line.length + separator.length + command.length > MAX_HELP_LENGTH) {
                lines.add(line.toString())
                line = StringBuilder("Commands (continued):")
                separator = " "
            }
            line.append(separator).append(command)
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return java.util.List.copyOf(lines)
    }

    @JvmStatic
    fun senderAllowed(
        sender: String?,
        allowlistEnabled: Boolean,
        allowlist: List<String?>?,
        blocklistEnabled: Boolean,
        blocklist: List<String?>?,
    ): Boolean = ChatReplyConfigRules.senderAllowed(
        sender,
        allowlistEnabled,
        allowlist,
        blocklistEnabled,
        blocklist,
    )

    @JvmStatic
    fun isBuiltInName(name: String?): Boolean = ChatReplyConfigRules.isReservedCommand(name)

    /** Odin-compatible integer block-coordinate representation for !coords and !co. */
    @JvmStatic
    fun coordinateText(blockX: Int, blockY: Int, blockZ: Int): String =
        "x: $blockX, y: $blockY, z: $blockZ"

    /** Formats the local island snapshot and explicitly marks the unavailable server instance. */
    @JvmStatic
    fun lobbyLocationText(islandLabel: String?): String =
        if (islandLabel.isNullOrBlank()) "Current island unavailable · lobby/instance unavailable"
        else "Current island: $islandLabel · lobby/instance unavailable"

    /** Returns the slashless command, or null if this channel cannot carry a reply. */
    @JvmStatic
    fun replyCommand(channel: ChatChannel?, message: String?): String? {
        if (channel == null || message == null || ChatStringSemantics.isBlank(message) ||
            message.length > MAX_HELP_LENGTH
        ) return null
        for (current in message) {
            val type = Character.getType(current)
            if (current == '\u00a7' || Character.isISOControl(current) ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
            ) return null
        }
        var prefix = channel.commandPrefix()
        if (prefix == null || ChatStringSemantics.isBlank(prefix)) return null
        prefix = javaTrim(prefix)
        if (prefix.startsWith("/")) prefix = prefix.substring(1)
        if (ChatStringSemantics.isBlank(prefix) || prefix.length > 80 || prefix.startsWith("/")) return null
        for (current in prefix) {
            if (Character.isISOControl(current) || current == '\u00a7' || current == ';' || current == '|') return null
        }
        val command = "$prefix $message"
        return if (command.length <= 256) command else null
    }

    private fun contains(members: List<PartyMember?>?, name: String?): Boolean =
        name != null && members != null && members.any { member ->
            member != null && sameIgnoreCase(member.name(), name)
        }

    private fun resolvePartyTarget(members: List<PartyMember?>, partialName: String): PartyMember? {
        val exact = members.firstOrNull { member -> sameIgnoreCase(member?.name(), partialName) }
        if (exact != null) return exact
        val matches = members.filter { member ->
            val name = member?.name()
            name != null && name.startsWith(partialName, ignoreCase = true)
        }
        return matches.singleOrNull()
    }

    /** Mirrors Java String.stripLeading(), including Character.isWhitespace's NBSP distinction. */
    private fun stripLeadingJavaWhitespace(value: String): String {
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (!Character.isWhitespace(codePoint)) break
            index += Character.charCount(codePoint)
        }
        return if (index == 0) value else value.substring(index)
    }

    /** Uses Java's locale-independent String.equalsIgnoreCase for source-compatible matching. */
    private fun sameIgnoreCase(first: String?, second: String?): Boolean =
        first != null && second != null && first.equals(second, ignoreCase = true)

    private val PARTY_ACTIONS = setOf(
        Command.PARTY_TRANSFER, Command.PARTY_WARP, Command.PARTY_ALLINVITE, Command.PARTY_BOOP,
        Command.PARTY_KICK, Command.PARTY_PROMOTE, Command.PARTY_DEMOTE, Command.PARTY_KICK_OFFLINE,
        Command.PARTY_QUEUE_INSTANCE,
    )
    private val REMOTE_PARTY_ACTIONS = PARTY_ACTIONS
    private val LEADER_PARTY_ACTIONS = setOf(
        Command.PARTY_TRANSFER, Command.PARTY_WARP, Command.PARTY_ALLINVITE, Command.PARTY_KICK,
        Command.PARTY_PROMOTE, Command.PARTY_DEMOTE, Command.PARTY_KICK_OFFLINE, Command.PARTY_QUEUE_INSTANCE,
    )

    private fun javaTrim(value: String): String = value.trim { it.code <= 0x20 }
}
