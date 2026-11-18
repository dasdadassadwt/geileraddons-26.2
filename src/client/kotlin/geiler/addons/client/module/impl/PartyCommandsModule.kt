package geiler.addons.client.module.impl

import geiler.addons.client.chat.ChatActionTemplateRules
import geiler.addons.client.chat.ChatChannel
import geiler.addons.client.chat.ChatChannelsLocalStore
import geiler.addons.client.chat.ChatReplyCommandRules
import geiler.addons.client.chat.ChatReplyConfigRules
import geiler.addons.client.chat.ChatReplyEntry
import geiler.addons.client.chat.ChatReplyPreview
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.Collections
import java.util.HashMap
import java.util.HashSet

/** Opt-in party commands, bounded replies, local actions, and the custom reply library. */
class PartyCommandsModule private constructor(settings: Settings) : Module(
    "Party Commands",
    "Answers configured incoming commands and handles local party-transfer helpers.",
    Category.MISCELLANEOUS,
    false,
    settings.chatRepliesEnabled,
    settings.replyPartyEnabled,
    settings.replyGuildEnabled,
    settings.replyCoopEnabled,
    settings.replyPrivateEnabled,
    settings.replyCustomEnabled,
    *settings.presets.toTypedArray(),
    settings.inviteAutoConfirm,
    settings.commandPrefix,
    settings.responseDelayTicks,
    settings.replyHelp,
    settings.saveReplyEdits,
) {
    companion object {
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 16
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 512
        private const val MAX_DIAGNOSTIC_COUNT = 1_000_000
        private const val DIAGNOSTIC_STATUS_INTERVAL_TICKS = 100L

        /** Java callers retain the registered singleton and static local-chat entrypoint. */
        @JvmField
        val INSTANCE = PartyCommandsModule(Settings())

        /** The ten built-in preset switches in declaration order. */
        @JvmStatic
        fun presetToggles(): List<BooleanSetting> = INSTANCE.presets

        /** Runs local actions and !pt before vanilla chat submits a normal message. */
        @JvmStatic
        fun handleLocalChatCommand(input: String?): Boolean {
            if (!INSTANCE.isEnabled() || !INSTANCE.chatRepliesEnabled().value()) return false
            if (geiler.addons.client.chat.ChatCommandReplyBackend.handleLocalCustomAction(input)) {
                INSTANCE.recordLocalCommandDiagnostic("custom_action")
                return true
            }
            if (geiler.addons.client.chat.ChatCommandReplyBackend.handleLocalPartyTransfer(input)) {
                INSTANCE.recordLocalCommandDiagnostic("party_transfer")
                return true
            }
            if (!ChatReplyCommandRules.isLocalHelpCommand(input, INSTANCE.commandPrefix().value())) return false
            geiler.addons.client.chat.ChatChannelsBackend.showHelp()
            INSTANCE.recordLocalCommandDiagnostic("help")
            return true
        }

        /** Folder dropdown: "" is the top level, other values are folder ids shown by their path. */
        private fun replyFolderChoice(current: String?): ChoiceSetting {
            val tree = ChatChannelsLocalStore.instance().replyFolders()
            val labels = linkedMapOf("" to "Top level")
            for (folder in tree.folders()) {
                val names = ArrayList<String>()
                var node: geiler.addons.client.collections.FolderTree.Folder? = folder
                while (node != null) {
                    names.add(0, node.name.orEmpty())
                    node = tree.folder(node.parentId)
                }
                labels[folder.id!!] = names.joinToString(" / ")
            }
            val value = if (current != null && labels.containsKey(current)) current else ""
            return ChoiceSetting("Folder", value, labels, *labels.keys.toTypedArray())
        }

        private fun showFeedback(message: String) {
            val client = Minecraft.getInstance()
            if (client != null && client.gui != null) {
                client.gui.hud.getChat().addClientSystemMessage(Component.literal("[Party Commands] $message"))
            }
        }
    }

    enum class QueueDiagnosticOutcome {
        OFFERED,
        OFFER_REJECTED,
        READY,
        COMMAND_SUBMITTED,
        CLEARED,
    }

    enum class PartyActionDiagnosticOutcome {
        SUBMITTED,
        BLOCKED_ALLOWLIST,
        BLOCKED_PARTY_OR_LEADER_OR_TARGET,
    }

    private val chatRepliesEnabledSetting = settings.chatRepliesEnabled
    private val replyPartyEnabledSetting = settings.replyPartyEnabled
    private val replyGuildEnabledSetting = settings.replyGuildEnabled
    private val replyCoopEnabledSetting = settings.replyCoopEnabled
    private val replyPrivateEnabledSetting = settings.replyPrivateEnabled
    private val replyCustomEnabledSetting = settings.replyCustomEnabled
    private val commandPrefixSetting = settings.commandPrefix
    private val responseDelayTicksSetting = settings.responseDelayTicks
    private val presets = settings.presets
    private val inviteAutoConfirmSetting = settings.inviteAutoConfirm
    private val replyControls = HashMap<String, ReplyControls>()
    private val folderControls = HashMap<String, BooleanSetting>()
    private val folderNames = HashMap<String, TextSetting>()
    private val form by lazy { Form() }
    private val channelGateCounts = linkedMapOf(
        "party" to intArrayOf(0, 0),
        "guild" to intArrayOf(0, 0),
        "coop" to intArrayOf(0, 0),
        "private" to intArrayOf(0, 0),
        "custom" to intArrayOf(0, 0),
        "other" to intArrayOf(0, 0),
    )
    private val queueOutcomeCounts = QueueDiagnosticOutcome.values()
        .associateWith { 0 }
        .toMutableMap()
    private var diagnosticsActive = false
    private var diagnosticTick = 0L
    private var eventTick = Long.MIN_VALUE
    private var diagnosticEventsThisTick = 0
    private var suppressedEventsThisTick = 0
    private var suppressedEventsSinceStatus = 0
    private var lastPendingCount = -1
    private var maxPendingCountSinceStatus = 0

    init {
        group(
            SettingGroup(
                "Replies",
                chatRepliesEnabledSetting,
                commandPrefixSetting,
                responseDelayTicksSetting,
                settings.replyHelp,
                settings.saveReplyEdits,
            ),
            SettingGroup(
                "Incoming Reply Channels",
                replyPartyEnabledSetting,
                replyGuildEnabledSetting,
                replyCoopEnabledSetting,
                replyPrivateEnabledSetting,
                replyCustomEnabledSetting,
            ),
            SettingGroup("Preset Commands", *presets.toTypedArray(), inviteAutoConfirmSetting),
        )
    }

    override fun isWorkInProgress(): Boolean = true

    override fun configName(): String = "Party Commands"

    fun chatRepliesEnabled(): BooleanSetting = chatRepliesEnabledSetting

    fun replyPartyEnabled(): BooleanSetting = replyPartyEnabledSetting

    fun replyGuildEnabled(): BooleanSetting = replyGuildEnabledSetting

    fun replyCoopEnabled(): BooleanSetting = replyCoopEnabledSetting

    fun replyPrivateEnabled(): BooleanSetting = replyPrivateEnabledSetting

    fun replyCustomEnabled(): BooleanSetting = replyCustomEnabledSetting

    fun commandPrefix(): TextSetting = commandPrefixSetting

    fun responseDelayTicks(): NumberSetting = responseDelayTicksSetting

    /** Per-command switch for the built-in Odin-style presets; informational commands are always on. */
    fun presetEnabled(command: ChatReplyCommandRules.Command?): Boolean = when (command) {
        ChatReplyCommandRules.Command.PARTY_WARP -> presets[0].value()
        ChatReplyCommandRules.Command.PARTY_ALLINVITE -> presets[1].value()
        ChatReplyCommandRules.Command.PARTY_TRANSFER -> presets[2].value()
        ChatReplyCommandRules.Command.PARTY_QUEUE_INSTANCE -> presets[3].value()
        ChatReplyCommandRules.Command.PARTY_BOOP -> presets[4].value()
        ChatReplyCommandRules.Command.PARTY_KICK -> presets[5].value()
        ChatReplyCommandRules.Command.PARTY_KICK_OFFLINE -> presets[6].value()
        ChatReplyCommandRules.Command.PARTY_PROMOTE -> presets[7].value()
        ChatReplyCommandRules.Command.PARTY_DEMOTE -> presets[8].value()
        ChatReplyCommandRules.Command.PRIVATE_INVITE -> presets[9].value()
        ChatReplyCommandRules.Command.PARTY_DOWNTIME, ChatReplyCommandRules.Command.PARTY_UNDOWNTIME -> presets[10].value()
        ChatReplyCommandRules.Command.PARTY_REINVITE -> presets[11].value()
        else -> true
    }

    /** When off, a private `!inv` shows a clickable invite instead of inviting at once (Odin's default). */
    fun inviteAutoConfirm(): BooleanSetting = inviteAutoConfirmSetting

    fun allowsIncomingReply(channel: ChatChannel): Boolean {
        val allowed = ChatReplyConfigRules.incomingChannelAllowed(
            channel,
            replyPartyEnabledSetting.value(),
            replyGuildEnabledSetting.value(),
            replyCoopEnabledSetting.value(),
            replyPrivateEnabledSetting.value(),
            replyCustomEnabledSetting.value(),
        )
        if (diagnosticsActive && isEnabled()) recordChannelGate(channel, allowed)
        return allowed
    }

    /** Client-tick hook for bounded status intervals and suppression summaries. */
    fun tickDiagnostics() {
        if (!diagnosticsActive || !isEnabled()) return
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        flushSuppressedEvents()
        if (diagnosticTick % DIAGNOSTIC_STATUS_INTERVAL_TICKS == 0L) {
            writeDiagnosticStatus(enabled = true)
        }
    }

    /** Queue outcomes contain enum values and counts only; caller text is intentionally excluded. */
    fun recordQueueDiagnostic(outcome: QueueDiagnosticOutcome, pendingCount: Int) {
        if (!diagnosticsActive) return
        val boundedPendingCount = pendingCount.coerceIn(0, MAX_DIAGNOSTIC_COUNT)
        queueOutcomeCounts[outcome] = incrementDiagnosticCount(queueOutcomeCounts[outcome] ?: 0)
        lastPendingCount = boundedPendingCount
        maxPendingCountSinceStatus = maxOf(maxPendingCountSinceStatus, boundedPendingCount)
        writeDiagnosticEvent("QUEUE outcome=${outcome.name} pending=$boundedPendingCount")
    }

    /** Party-action traces use enum values only; sender and target names are never recorded. */
    fun recordPartyActionDiagnostic(
        command: ChatReplyCommandRules.Command,
        outcome: PartyActionDiagnosticOutcome,
    ) {
        if (!diagnosticsActive || !isEnabled()) return
        writeDiagnosticEvent("PARTY_ACTION command=${command.name} outcome=${outcome.name}")
    }

    /** Downtime, reinvite and invite-prompt results; the outcome is a fixed token, never chat text. */
    fun recordFeatureOutcome(outcome: String) {
        if (diagnosticsActive && isEnabled()) writeDiagnosticEvent("FEATURE outcome=$outcome")
    }

    /** A request that was parsed but not run; the reason is a fixed token plus a command name, never chat text. */
    fun recordRequestDropped(reason: String) {
        if (diagnosticsActive && isEnabled()) writeDiagnosticEvent("REQUEST_DROPPED reason=$reason")
    }

    private fun recordLocalCommandDiagnostic(outcome: String) {
        if (diagnosticsActive && isEnabled()) {
            writeDiagnosticEvent("LOCAL_COMMAND outcome=$outcome")
        }
    }

    private fun recordChannelGate(channel: ChatChannel, allowed: Boolean) {
        val key = when {
            channel.custom() -> "custom"
            channel.id() == "party" -> "party"
            channel.id() == "guild" -> "guild"
            channel.id() == "coop" -> "coop"
            channel.id() == "private" -> "private"
            else -> "other"
        }
        val counts = channelGateCounts.getValue(key)
        val decisionIndex = if (allowed) 0 else 1
        counts[decisionIndex] = incrementDiagnosticCount(counts[decisionIndex])
        writeDiagnosticEvent("CHANNEL_GATE channel=$key decision=${if (allowed) "allow" else "deny"}")
    }

    private fun writeDiagnosticStatus(enabled: Boolean) {
        if (!diagnosticsActive) return
        val channelGates = channelGateCounts.entries.joinToString(",") { (channel, counts) ->
            "$channel:${counts[0]}/${counts[1]}"
        }
        val queueOutcomes = QueueDiagnosticOutcome.values().joinToString(",") { outcome ->
            "${outcome.name}=${queueOutcomeCounts[outcome] ?: 0}"
        }
        val line = "STATUS enabled=$enabled replyCommands=${chatRepliesEnabledSetting.value()} " +
            "prefixConfigured=${ChatReplyCommandRules.normalizePrefix(commandPrefixSetting.value()) != null} " +
            "delayTicks=${responseDelayTicksSetting.intValue().coerceIn(0, 40)} " +
            "channels[party=${replyPartyEnabledSetting.value()},guild=${replyGuildEnabledSetting.value()}," +
            "coop=${replyCoopEnabledSetting.value()},private=${replyPrivateEnabledSetting.value()}," +
            "custom=${replyCustomEnabledSetting.value()}] gates[$channelGates] " +
            "queue[$queueOutcomes,lastPendingObserved=$lastPendingCount," +
            "maxPendingThisInterval=$maxPendingCountSinceStatus] " +
            "suppressed=$suppressedEventsSinceStatus writer=${GeilerAddonsLog.enabled()}"
        GeilerAddonsLog.write(
            Category.MISCELLANEOUS,
            name(),
            diagnosticTick,
            line.take(MAX_DIAGNOSTIC_LINE_LENGTH),
        )
        resetDiagnosticWindow()
    }

    private fun writeDiagnosticEvent(event: String) {
        if (!diagnosticsActive) return
        if (eventTick != diagnosticTick) {
            flushSuppressedEvents()
            eventTick = diagnosticTick
            diagnosticEventsThisTick = 0
        }
        if (diagnosticEventsThisTick >= MAX_DIAGNOSTIC_EVENTS_PER_TICK) {
            suppressedEventsThisTick = incrementDiagnosticCount(suppressedEventsThisTick)
            suppressedEventsSinceStatus = incrementDiagnosticCount(suppressedEventsSinceStatus)
            return
        }
        diagnosticEventsThisTick++
        GeilerAddonsLog.write(
            Category.MISCELLANEOUS,
            name(),
            diagnosticTick,
            "EVENT $event".take(MAX_DIAGNOSTIC_LINE_LENGTH),
        )
    }

    private fun flushSuppressedEvents() {
        if (!diagnosticsActive || suppressedEventsThisTick <= 0) return
        GeilerAddonsLog.write(
            Category.MISCELLANEOUS,
            name(),
            eventTick,
            "EVENTS_SUPPRESSED count=$suppressedEventsThisTick limit=$MAX_DIAGNOSTIC_EVENTS_PER_TICK",
        )
        suppressedEventsThisTick = 0
    }

    private fun resetDiagnosticWindow() {
        channelGateCounts.values.forEach { counts ->
            counts[0] = 0
            counts[1] = 0
        }
        QueueDiagnosticOutcome.values().forEach { queueOutcomeCounts[it] = 0 }
        suppressedEventsSinceStatus = 0
        maxPendingCountSinceStatus = 0
    }

    private fun incrementDiagnosticCount(value: Int): Int =
        if (value >= MAX_DIAGNOSTIC_COUNT) MAX_DIAGNOSTIC_COUNT else value + 1

    override fun onEnable() {
        diagnosticsActive = true
        diagnosticTick = 0L
        eventTick = Long.MIN_VALUE
        diagnosticEventsThisTick = 0
        suppressedEventsThisTick = 0
        lastPendingCount = -1
        resetDiagnosticWindow()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, name(), true)
        writeDiagnosticStatus(enabled = true)
    }

    override fun onDisable() {
        geiler.addons.client.chat.ChatCommandReplyBackend.clear()
        if (!diagnosticsActive) return
        flushSuppressedEvents()
        writeDiagnosticStatus(enabled = false)
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, name(), false)
        diagnosticsActive = false
    }

    fun showFeedbackPublic(message: String) = showFeedback(message)

    /** Drops inline switch state after an edit made in the dedicated reply screen. */
    fun refreshReplySettings() {
        replyControls.clear()
        folderControls.clear()
        folderNames.clear()
    }

    override fun groups(): List<SettingGroup> {
        syncReplySwitches()
        val store = ChatChannelsLocalStore.instance()
        val library = ArrayList<SettingGroup>()
        for (folder in store.replyFolderChildren(null)) {
            library.add(replyFolderGroup(folder, store))
        }
        for (entry in store.chatReplies()) {
            if (entry.folderId() == null || !store.hasReplyFolder(entry.folderId())) {
                library.add(replyGroup(entry))
            }
        }
        val result = ArrayList(super.groups())
        syncFilters()
        result.add(SettingGroup("Add Reply", form.command, form.mode, form.value, form.preview, form.add))
        result.add(blocklistGroup(store))
        result.add(SettingGroup("Reply Folders", form.folderName, form.createFolder))
        result.add(
            SettingGroup(
                "Custom Reply Library",
                null,
                true,
                emptyList<Setting>(),
                library,
                false,
                "party-command-library",
            ),
        )
        return Collections.unmodifiableList(result)
    }

    /** Name field + Block button, then one row per blocked player with a Remove button. */
    private fun blocklistGroup(store: ChatChannelsLocalStore): SettingGroup {
        val rows = store.replyBlocklist().map { name ->
            val remove = ModuleAction("Remove", "Let $name use party commands again.", Runnable { unblockPlayer(name) })
            SettingGroup(name, null, false, listOf<Setting>(remove), emptyList(), false, "party-block:$name")
        }
        return SettingGroup(
            "Blocklist", form.block, false, listOf<Setting>(form.blockName, form.blockAdd), rows, false,
            "party-command-blocklist",
        )
    }

    private fun blockPlayer() {
        val store = ChatChannelsLocalStore.instance()
        val updated = ChatReplyConfigRules.blocklistWith(store.replyBlocklist(), form.blockName.value())
        if (updated == null) {
            showFeedback("Enter a valid, not yet blocked username (up to ${ChatReplyConfigRules.MAX_FILTER_NAMES} players).")
            return
        }
        store.setReplyFilters(store.replyAllowlist(), store.replyAllowlistEnabled(), updated, true)
        form.block.setValue(true)
        form.blockName.setValue("")
    }

    private fun unblockPlayer(name: String) {
        val store = ChatChannelsLocalStore.instance()
        store.setReplyFilters(
            store.replyAllowlist(), store.replyAllowlistEnabled(),
            ChatReplyConfigRules.blocklistWithout(store.replyBlocklist(), name), store.replyBlocklistEnabled(),
        )
    }

    private fun replyFolderGroup(folder: ChatChannelsLocalStore.FolderData, store: ChatChannelsLocalStore): SettingGroup {
        val folderId = folder.id!!
        val enabled = folderControls.getOrPut(folderId) {
            BooleanSetting("Enabled", store.replyFolderEnabled(folderId))
        }
        val rename = folderNames.getOrPut(folderId) { TextSetting("Folder Name", folder.name.orEmpty(), 32) }
        val delete = ModuleAction("Delete Folder", "Remove this folder; its replies move to the top level.", Runnable {
            store.replyFolders().delete(folderId)
            store.persistReplyFolders()
            refreshReplySettings()
        })
        val children = ArrayList<SettingGroup>()
        for (child in store.replyFolderChildren(folderId)) {
            children.add(replyFolderGroup(child, store))
        }
        for (entry in store.chatReplies()) {
            if (folderId == entry.folderId()) children.add(replyGroup(entry))
        }
        return SettingGroup(
            folder.name,
            enabled,
            false,
            listOf<Setting>(rename, delete),
            children,
            false,
            "party-command-folder:$folderId",
        )
    }

    private fun replyGroup(entry: ChatReplyEntry): SettingGroup {
        val controls = replyControls.getOrPut(entry.id()) { ReplyControls(entry) }
        val preview = ModuleAction(
            "Preview",
            "Shows in chat what this reply does when someone uses it.",
            Runnable {
                showFeedback(
                    ChatReplyPreview.describe(
                        commandPrefixSetting.value(), controls.command.value(),
                        controls.mode.value() == ChatReplyEntry.Mode.LOCAL_ACTION.name, controls.payload.value(),
                    ),
                )
            },
        )
        val remove = ModuleAction(
            "Delete Command",
            "Remove this reply or local action command.",
            Runnable {
                ChatChannelsLocalStore.instance().removeChatReplyById(entry.id())
                replyControls.remove(entry.id())
            },
        )
        return SettingGroup.switched(
            commandPrefixSetting.value() + entry.command(),
            controls.enabled,
            controls.command,
            controls.mode,
            controls.folder,
            controls.payload,
            preview,
            remove,
        ).keyed("party-command:${entry.id()}")
    }

    private fun syncReplySwitches() {
        val store = ChatChannelsLocalStore.instance()
        val liveReplies = HashSet<String>()
        for (entry in store.chatReplies()) {
            liveReplies.add(entry.id())
            val controls = replyControls[entry.id()]
            if (controls != null && controls.enabled.value() != entry.enabled()) {
                store.setChatReplyEnabled(entry.id(), controls.enabled.value())
            }
            if (controls != null && controls.folder.value() != (entry.folderId() ?: "")) {
                store.setChatReplyFolder(entry.id(), controls.folder.value().ifEmpty { null })
            }
        }
        replyControls.keys.retainAll(liveReplies)

        val liveFolders = HashSet<String>()
        for (folder in store.replyFolderSnapshot()) {
            val folderId = folder.id!!
            liveFolders.add(folderId)
            val setting = folderControls[folderId]
            if (setting != null && setting.value() != store.replyFolderEnabled(folderId)) {
                store.setReplyFolderEnabled(folderId, setting.value())
            }
            val rename = folderNames[folderId]
            if (rename != null && rename.value().isNotBlank() && rename.value() != folder.name &&
                store.replyFolders().rename(folderId, rename.value())
            ) store.persistReplyFolders()
        }
        folderControls.keys.retainAll(liveFolders)
        folderNames.keys.retainAll(liveFolders)
    }

    private fun saveReplyEdits() {
        val store = ChatChannelsLocalStore.instance()
        val updated = ArrayList<ChatReplyEntry>()
        for (current in store.chatReplies()) {
            val controls = replyControls[current.id()]
            if (controls == null) {
                updated.add(current)
                continue
            }
            val command = ChatReplyConfigRules.normalizeCommand(controls.command.value())
            val localAction = controls.mode.value() == ChatReplyEntry.Mode.LOCAL_ACTION.name
            val payload = controls.payload.value().trim()
            val response = if (localAction) current.response() else payload
            val actionTemplate = if (localAction) {
                ChatActionTemplateRules.normalize(payload)
            } else {
                current.actionTemplate()
            }
            val validPayload = if (localAction) {
                ChatActionTemplateRules.isValid(actionTemplate)
            } else {
                ChatReplyConfigRules.validResponse(response)
            }
            if (command.isEmpty() || !validPayload) {
                showFeedback("Choose Text Reply with one line of text, or Local Action with one /command {args} template.")
                return
            }
            val prefix = ChatReplyCommandRules.normalizePrefix(commandPrefixSetting.value())
            if (!localAction && prefix != null && response.startsWith(prefix)) {
                showFeedback("A reply cannot start with the active command prefix.")
                return
            }
            val mode = if (localAction) ChatReplyEntry.Mode.LOCAL_ACTION else ChatReplyEntry.Mode.TEXT_REPLY
            updated.add(
                ChatReplyEntry(
                    current.id(),
                    command,
                    mode,
                    response,
                    actionTemplate,
                    current.folderId(),
                    controls.enabled.value(),
                ),
            )
        }
        if (!ChatReplyConfigRules.validReplacement(updated) || !store.replaceChatReplies(updated)) {
            showFeedback("Reply names must be unique and cannot shadow built-in commands.")
            return
        }
        refreshReplySettings()
        showFeedback("Custom reply settings saved.")
    }

    private class Settings {
        val chatRepliesEnabled = BooleanSetting("Reply Commands", true)
        val replyPartyEnabled = BooleanSetting("Party", true)
        val replyGuildEnabled = BooleanSetting("Guild", false)
        val replyCoopEnabled = BooleanSetting("Co-op", false)
        val replyPrivateEnabled = BooleanSetting("Private", false)
        val replyCustomEnabled = BooleanSetting("Custom Channels", false)
        // Odin defaults: harmless party presets on; transfer, kick, promote and demote off.
        val presets: List<BooleanSetting> = listOf(
            BooleanSetting("Warp (!w)", true),
            BooleanSetting("All Invite (!allinv)", true),
            BooleanSetting("Transfer (!pt)", false),
            BooleanSetting("Dungeon Queue (!f1-!t5)", true),
            BooleanSetting("Boop (!boop)", true),
            BooleanSetting("Kick (!kick)", false),
            BooleanSetting("Kick Offline (!ko)", false),
            BooleanSetting("Promote (!promote)", false),
            BooleanSetting("Demote (!demote)", false),
            BooleanSetting("Invite (!inv)", true),
            BooleanSetting("Downtime (!dt)", true),
            BooleanSetting("Reinvite (!reinvite)", false),
        )
        val inviteAutoConfirm = BooleanSetting("Auto Confirm Invite", false)
        val commandPrefix = TextSetting("Command Prefix", "!", 4)
        val responseDelayTicks = NumberSetting("Reply Delay (Ticks)", 0f, 40f, 4f, true)
        val replyHelp = ModuleAction(
            "How Replies Work (i)",
            "Prints a step-by-step guide to making a command, with the tags you can use.",
            Runnable { ChatReplyPreview.GUIDE_LINES.forEach { INSTANCE.showFeedbackPublic(it) } },
        )
        val saveReplyEdits = ModuleAction(
            "Save Settings Edits",
            "Save changes made to the inline reply controls.",
            Runnable { INSTANCE.saveReplyEdits() },
        )
    }

    /** Controls for the inline Add Reply form, sender filters and new folders; not persisted as module settings. */
    private inner class Form {
        private val store = ChatChannelsLocalStore.instance()
        val command = TextSetting("New Command", "", ChatReplyConfigRules.MAX_COMMAND_LENGTH)
        val mode = ChoiceSetting(
            "New Mode", ChatReplyEntry.Mode.TEXT_REPLY.name,
            mapOf("TEXT_REPLY" to "Text Reply", "LOCAL_ACTION" to "Local Action"), "TEXT_REPLY", "LOCAL_ACTION",
        )
        val value = TextSetting(
            "New Reply / Local Action Value", "",
            maxOf(ChatReplyConfigRules.MAX_RESPONSE_LENGTH, ChatActionTemplateRules.MAX_TEMPLATE_LENGTH),
        )
        val preview = ModuleAction("Preview New Reply", "Shows in chat what this reply would do.", Runnable {
            showFeedback(
                ChatReplyPreview.describe(
                    commandPrefixSetting.value(), command.value(),
                    mode.value() == ChatReplyEntry.Mode.LOCAL_ACTION.name, value.value(),
                ),
            )
        })
        val add = ModuleAction("Add Reply", "Saves the reply above to the library.", Runnable { addReply() })
        val block = BooleanSetting("Blocklist", store.replyBlocklistEnabled())
        val blockName = TextSetting("Player Name", "", 16)
        val blockAdd = ModuleAction("Block", "Stop this player from using your party commands.", Runnable { blockPlayer() })
        val folderName = TextSetting("New Folder Name", "", 32)
        val createFolder = ModuleAction("Create Folder", "Adds a top-level folder for replies.", Runnable {
            val created = store.replyFolders().create(folderName.value(), null)
            if (created == null) {
                showFeedback("Enter a folder name first.")
            } else {
                store.persistReplyFolders()
                folderName.setValue("")
                refreshReplySettings()
            }
        })
    }

    private fun addReply() {
        val local = form.mode.value() == ChatReplyEntry.Mode.LOCAL_ACTION.name
        val error = ChatReplyConfigRules.draftError(commandPrefixSetting.value(), form.command.value(), local, form.value.value())
        if (error != null) {
            showFeedback(error)
            return
        }
        val name = ChatReplyConfigRules.normalizeCommand(form.command.value())
        val text = form.value.value().trim()
        val id = java.util.UUID.randomUUID().toString()
        val entry = if (local) {
            ChatReplyEntry(id, name, ChatReplyEntry.Mode.LOCAL_ACTION, "", ChatActionTemplateRules.normalize(text), null, true)
        } else {
            ChatReplyEntry(id, name, ChatReplyEntry.Mode.TEXT_REPLY, text, "", null, true)
        }
        if (!entry.isValid() || !ChatChannelsLocalStore.instance().addChatReply(entry)) {
            showFeedback("The name is already used, invalid, or the 16-entry limit is full.")
            return
        }
        form.command.setValue("")
        form.value.setValue("")
        refreshReplySettings()
        showFeedback("Reply added.")
    }

    /** Pushes the Blocklist on/off switch into the store (the names themselves are edited through Block/Remove). */
    private fun syncFilters() {
        val store = ChatChannelsLocalStore.instance()
        if (store.replyBlocklistEnabled() == form.block.value()) return
        store.setReplyFilters(store.replyAllowlist(), store.replyAllowlistEnabled(), store.replyBlocklist(), form.block.value())
    }

    private class ReplyControls(entry: ChatReplyEntry) {
        val enabled = BooleanSetting("Enabled", entry.enabled())
        val command = TextSetting("Command", entry.command(), ChatReplyConfigRules.MAX_COMMAND_LENGTH)
        val mode = ChoiceSetting("Mode", entry.mode().name, "TEXT_REPLY", "LOCAL_ACTION")
        val folder = replyFolderChoice(entry.folderId())
        val payload = TextSetting(
            "Reply / Local Action Value",
            if (entry.mode() == ChatReplyEntry.Mode.TEXT_REPLY) entry.response() else entry.actionTemplate(),
            maxOf(ChatReplyConfigRules.MAX_RESPONSE_LENGTH, ChatActionTemplateRules.MAX_TEMPLATE_LENGTH),
        )
    }
}
