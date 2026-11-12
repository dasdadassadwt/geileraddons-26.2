package geiler.addons.client.chat

import geiler.addons.GeilerAddons
import geiler.addons.client.collections.FolderTree
import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.Synchronized

/** Feature-local reply rules and settings retained across module revisions. */
class ChatChannelsLocalStore private constructor() {
    private val replyFolders = FolderTree()
    private val replyFolderEnabled = HashMap<String, Boolean>()
    private val persistenceAllowed: Boolean
    private var data: Data

    init {
        val loaded = readSanitized(configFile())
        data = requireNotNull(loaded.data) { "Sanitized local chat data is missing" }
        persistenceAllowed = loaded.persistenceAllowed
        val warning = loaded.warning
        if (warning != null) GeilerAddons.LOGGER.warn("[Chat Channels] {}", warning)
        if (data.chatReplyConfigVersion == null) data.chatReplyConfigVersion = REPLY_SCHEMA_VERSION
        restoreReplyFolders(data.replyFolders)
        if (loaded.needsRewrite && persistenceAllowed) saveLocked()
    }

    @Synchronized
    fun rules(): List<ChatChannelRule> {
        val result = ArrayList<ChatChannelRule>()
        for (stored in data.rules!!) {
            if (stored == null) continue
            val rule = ruleFromData(stored)
            if (isValidChatChannelRule(rule)) result.add(rule)
        }
        return java.util.List.copyOf(result)
    }

    @Synchronized
    fun addRule(rule: ChatChannelRule?) {
        if (rule == null || !isValidChatChannelRule(rule)) return
        val current = rules()
        if (current.size >= MAX_RULES || current.any { it.id() == rule.id() }) return
        data.rules!!.add(ruleDataFrom(rule))
        saveLocked()
    }

    @Synchronized
    fun removeRule(id: String?): Boolean {
        if (id == null) return false
        val changed = data.rules!!.removeIf { rule -> rule != null && id == rule.id }
        if (changed) saveLocked()
        return changed
    }

    @Synchronized
    fun chatReplies(): List<ChatReplyEntry> {
        val result = ArrayList<ChatReplyEntry>()
        for (stored in data.chatReplies!!) {
            if (stored == null) continue
            val entry = stored.toEntry()
            if (entry.isValid() && result.none { existing -> existing.command() == entry.command() }) result.add(entry)
        }
        return java.util.List.copyOf(result)
    }

    /** Enabled custom commands after command and folder switches; parsers then filter by mode. */
    @Synchronized
    fun enabledChatReplies(): List<ChatReplyEntry> = java.util.List.copyOf(
        chatReplies().filter { isChatReplyEnabled(it) },
    )

    @Synchronized
    fun isChatReplyEnabled(entry: ChatReplyEntry?): Boolean = entry != null && entry.enabled() &&
        ChatReplyConfigRules.enabledInFolderTree(replyFolders, replyFolderEnabled, entry.folderId())

    @Synchronized
    fun addChatReply(entry: ChatReplyEntry?): Boolean {
        if (entry == null || !entry.isValid()) return false
        val current = chatReplies()
        if (!ChatReplyConfigRules.canAddEntry(current, entry)) return false
        data.chatReplies!!.add(ReplyData.from(normalizeFolder(entry)))
        saveLocked()
        return true
    }

    /** Replaces rows in one write while preserving each row's id, folder, and enabled state. */
    @Synchronized
    fun replaceChatReplies(entries: List<ChatReplyEntry>?): Boolean {
        if (!ChatReplyConfigRules.validReplacement(entries)) return false
        val normalized = entries!!.map { normalizeFolder(it) }
        data.chatReplies = normalized.mapTo(ArrayList<ReplyData?>()) { ReplyData.from(it) }
        saveLocked()
        return true
    }

    @Synchronized
    fun setChatReplyEnabled(id: String?, enabled: Boolean): Boolean {
        for (index in data.chatReplies!!.indices) {
            val stored = data.chatReplies!![index]
            if (stored == null || id == null || id != stored.id) continue
            val old = stored.toEntry()
            if (old.enabled() == enabled) return true
            data.chatReplies!![index] = ReplyData.from(ChatReplyEntry(
                old.id(), old.command(), old.mode(), old.response(), old.actionTemplate(), old.folderId(), enabled,
            ))
            saveLocked()
            return true
        }
        return false
    }

    @Synchronized
    fun setChatReplyFolder(entryId: String?, folderId: String?): Boolean {
        val target = if (folderId == null || !replyFolders.contains(folderId)) null else folderId
        for (index in data.chatReplies!!.indices) {
            val stored = data.chatReplies!![index]
            if (stored == null || entryId == null || entryId != stored.id) continue
            val old = stored.toEntry()
            data.chatReplies!![index] = ReplyData.from(ChatReplyEntry(
                old.id(), old.command(), old.mode(), old.response(), old.actionTemplate(), target, old.enabled(),
            ))
            saveLocked()
            return true
        }
        return false
    }

    @Synchronized
    fun removeChatReplyById(id: String?): Boolean {
        if (id == null) return false
        val changed = data.chatReplies!!.removeIf { entry -> entry != null && id == entry.id }
        if (changed) saveLocked()
        return changed
    }

    @Synchronized
    fun removeChatReply(command: String?): Boolean {
        val normalized = ChatReplyConfigRules.normalizeCommand(command)
        if (normalized.isEmpty()) return false
        val changed = data.chatReplies!!.removeIf { entry ->
            entry != null && normalized == (entry.command?.let { ChatStringSemantics.trim(it) }
                ?.lowercase(Locale.ROOT) ?: "")
        }
        if (changed) saveLocked()
        return changed
    }

    /** Feature-local tree, edited only on the client thread and persisted with persistReplyFolders(). */
    @Synchronized
    fun replyFolders(): FolderTree = replyFolders

    /** Immutable folder snapshots for Kotlin callers crossing the Java store facade. */
    @Synchronized
    fun replyFolderSnapshot(): List<FolderData> {
        val result = ArrayList<FolderData>()
        for (folder in replyFolders.folders()) {
            val folderId = folder.id ?: continue
            result.add(folderDataFrom(folder, replyFolderEnabled[folderId] ?: true))
        }
        return java.util.List.copyOf(result)
    }

    @Synchronized
    fun replyFolderChildren(parentId: String?): List<FolderData> =
        java.util.List.copyOf(replyFolderSnapshot().filter { it.parentId == parentId })

    @Synchronized
    fun hasReplyFolder(id: String?): Boolean = replyFolders.contains(id)

    @Synchronized
    fun replyFolderEnabled(id: String?): Boolean = replyFolders.contains(id) && (replyFolderEnabled[id] ?: true)

    @Synchronized
    fun setReplyFolderEnabled(id: String?, enabled: Boolean): Boolean {
        if (!replyFolders.contains(id)) return false
        if ((replyFolderEnabled[id] ?: true) == enabled) return true
        replyFolderEnabled[id!!] = enabled
        persistReplyFoldersLocked()
        return true
    }

    @Synchronized
    fun persistReplyFolders() = persistReplyFoldersLocked()

    @Synchronized
    fun replyAllowlist(): List<String> = java.util.List.copyOf(data.replyAllowlist?.filterNotNull() ?: emptyList())

    @Synchronized
    fun replyBlocklist(): List<String> = java.util.List.copyOf(data.replyBlocklist?.filterNotNull() ?: emptyList())

    @Synchronized
    fun replyAllowlistEnabled(): Boolean = java.lang.Boolean.TRUE == data.replyAllowlistEnabled

    @Synchronized
    fun replyBlocklistEnabled(): Boolean = java.lang.Boolean.TRUE == data.replyBlocklistEnabled

    @Synchronized
    fun setReplyFilters(
        allowlist: List<String>?,
        allowlistEnabled: Boolean,
        blocklist: List<String>?,
        blocklistEnabled: Boolean,
    ): Boolean {
        if (!ChatReplyConfigRules.validUsernameList(allowlist) || !ChatReplyConfigRules.validUsernameList(blocklist)) {
            return false
        }
        data.replyAllowlist = ArrayList<String?>(ChatReplyConfigRules.normalizeUsernames(allowlist))
        data.replyAllowlistEnabled = allowlistEnabled
        data.replyBlocklist = ArrayList<String?>(ChatReplyConfigRules.normalizeUsernames(blocklist))
        data.replyBlocklistEnabled = blocklistEnabled
        saveLocked()
        return true
    }

    /** Returns legacy HUD dimensions once so the standalone Chat module can take ownership. */
    @Synchronized
    fun migrateLegacyHudBounds(): ChatWindowLayoutRules.Settings? {
        if (java.lang.Boolean.TRUE == data.chatHudBoundsMigrated) return null
        data.chatHudBoundsMigrated = true
        val previous = chatWindowSettings()
        saveLocked()
        return previous
    }

    private fun chatWindowSettings(): ChatWindowLayoutRules.Settings = ChatWindowLayoutRules.Settings(
        java.lang.Boolean.TRUE == data.unfocusedChatHudEnabled,
        data.focusedChatX,
        data.focusedChatY,
        data.chatWindowWidth,
        data.chatWindowHeight,
    )

    private fun saveLocked(): Future<Boolean> {
        if (!persistenceAllowed) return CompletableFuture.completedFuture(false)
        return ClientJsonFile.writeAsync(configFile(), ClientJsonFile.GSON.toJson(data))
    }

    private fun restoreReplyFolders(saved: List<FolderData?>?) {
        val values = ArrayList<FolderTree.Folder>()
        if (saved != null) for (folder in saved) if (folder != null) values.add(folderFromData(folder))
        replyFolders.restore(values)
        replyFolderEnabled.clear()
        if (saved != null) for (folder in saved) {
            val folderId = folder?.id
            if (folderId != null && replyFolders.contains(folderId)) {
                replyFolderEnabled.putIfAbsent(folderId, folder.enabled != false)
            }
        }
    }

    private fun persistReplyFoldersLocked() {
        val folders = ArrayList<FolderData?>()
        val retained = HashSet<String>()
        for (folder in replyFolders.folders()) {
            val folderId = folder.id ?: continue
            retained.add(folderId)
            folders.add(folderDataFrom(folder, replyFolderEnabled[folderId] ?: true))
        }
        replyFolderEnabled.keys.retainAll(retained)
        data.replyFolders = folders
        for (index in data.chatReplies!!.indices) {
            val stored = data.chatReplies!![index]
            if (stored != null && stored.folderId != null && stored.folderId !in retained) {
                val entry = stored.toEntry()
                data.chatReplies!![index] = ReplyData.from(ChatReplyEntry(
                    entry.id(), entry.command(), entry.mode(), entry.response(), entry.actionTemplate(), null,
                    entry.enabled(),
                ))
            }
        }
        saveLocked()
    }

    private fun normalizeFolder(entry: ChatReplyEntry): ChatReplyEntry {
        val folderId = if (entry.folderId() != null && replyFolders.contains(entry.folderId())) entry.folderId() else null
        return ChatReplyEntry(entry.id(), entry.command(), entry.mode(), entry.response(), entry.actionTemplate(),
            folderId, entry.enabled())
    }

    /** The strict local data store result, kept as a JVM record for Java callers. */
    @JvmRecord
    data class SanitizedLoad(
        val data: Data?,
        val needsRewrite: Boolean,
        val persistenceAllowed: Boolean,
        val warning: String?,
    ) {
        constructor(data: Data?, needsRewrite: Boolean) : this(data, needsRewrite, true, null)
    }

    /** Gson model. Field names and defaults remain stable for existing local chat settings. */
    class Data {
        @JvmField
        var rules: MutableList<RuleData?>? = ArrayList()

        @JvmField
        var chatReplies: MutableList<ReplyData?>? = ArrayList()

        @JvmField
        var replyFolders: MutableList<FolderData?>? = ArrayList()

        /** Missing in old flat-list files; saved payloads infer one legacy mode. */
        @JvmField
        var chatReplyConfigVersion: Int? = null

        @JvmField
        var replyAllowlist: MutableList<String?>? = ArrayList()

        @JvmField
        var replyBlocklist: MutableList<String?>? = ArrayList()

        /** Missing before schema 6; inferred from the old implicit nonempty-list behavior. */
        @JvmField
        var replyAllowlistEnabled: Boolean? = null

        @JvmField
        var replyBlocklistEnabled: Boolean? = null

        /** Retired panel fields; old dimensions are read once for native-chat size migration. */
        @JvmField
        var unfocusedChatHudEnabled: Boolean? = null

        /** Marks the one-time move of legacy chat HUD dimensions into the Chat module settings. */
        @JvmField
        var chatHudBoundsMigrated: Boolean? = null

        @JvmField
        var focusedChatX: Int? = null

        @JvmField
        var focusedChatY: Int? = null

        @JvmField
        var chatWindowWidth: Int? = null

        @JvmField
        var chatWindowHeight: Int? = null
    }

    class ReplyData {
        @JvmField
        var id: String? = null

        @JvmField
        var command: String? = null

        @JvmField
        var response: String? = null

        /** Missing in legacy JSON; inferred from which payload was present. */
        @JvmField
        var mode: String? = null

        /** Missing legacy value defaults to an empty dormant template. */
        @JvmField
        var actionTemplate: String? = null

        @JvmField
        var folderId: String? = null

        /** Nullable so absent legacy JSON defaults on instead of Gson's primitive false default. */
        @JvmField
        var enabled: Boolean? = null

        fun toEntry(): ChatReplyEntry {
            val storedId = id
            val storedMode = mode
            val storedCommand = command
            val storedResponse = response
            val storedActionTemplate = actionTemplate
            val storedFolderId = folderId
            val storedEnabled = enabled
            val normalizedCommand = ChatReplyConfigRules.normalizeCommand(storedCommand)
            val stableId = if (storedId == null || ChatStringSemantics.isBlank(storedId)) {
                UUID.nameUUIDFromBytes(("geileraddons-chat-reply:" + normalizedCommand)
                    .toByteArray(StandardCharsets.UTF_8)).toString()
            } else {
                storedId
            }
            var parsedMode: ChatReplyEntry.Mode? = null
            try {
                if (storedMode != null) parsedMode = ChatReplyEntry.Mode.valueOf(storedMode)
            } catch (_: IllegalArgumentException) {
            }
            if (parsedMode == null) {
                val hasResponse = storedResponse != null && !ChatStringSemantics.isBlank(storedResponse)
                val hasActionTemplate = storedActionTemplate != null && !ChatStringSemantics.isBlank(storedActionTemplate)
                // If both legacy payloads exist, keep them both and select the safer, non-executing reply mode.
                parsedMode = if (hasActionTemplate && !hasResponse) {
                    ChatReplyEntry.Mode.LOCAL_ACTION
                } else {
                    ChatReplyEntry.Mode.TEXT_REPLY
                }
            }
            return ChatReplyEntry(stableId, storedCommand, parsedMode, storedResponse, storedActionTemplate,
                storedFolderId, storedEnabled != false)
        }

        companion object {
            @JvmStatic
            fun from(entry: ChatReplyEntry): ReplyData {
                val stored = ReplyData()
                stored.id = entry.id()
                stored.command = entry.command()
                stored.mode = entry.mode().name
                stored.response = entry.response()
                stored.actionTemplate = entry.actionTemplate()
                stored.folderId = entry.folderId()
                stored.enabled = entry.enabled()
                return stored
            }
        }
    }

    class FolderData {
        @JvmField
        var id: String? = null

        @JvmField
        var name: String? = null

        @JvmField
        var parentId: String? = null

        @JvmField
        var enabled: Boolean? = null
    }

    class RuleData {
        @JvmField
        var id: String? = null

        @JvmField
        var name: String? = null

        @JvmField
        var incomingContains: String? = null

        @JvmField
        var commandPrefix: String? = null

        /** Missing on older local files; defaults to text matching. */
        @JvmField
        var matchMode: String? = null

        @JvmField
        var whitelist: MutableList<String>? = ArrayList()

        @JvmField
        var blacklist: MutableList<String>? = ArrayList()
    }

    companion object {
        const val MAX_RULES = 16
        const val MAX_CHAT_REPLIES = ChatReplyConfigRules.MAX_ENTRIES
        private const val REPLY_SCHEMA_VERSION = 8

        private class InstanceHolder {
            companion object {
                val instance = ChatChannelsLocalStore()
            }
        }

        @JvmStatic
        fun instance(): ChatChannelsLocalStore = InstanceHolder.instance

        /** Removes retired raw properties before the strict runtime read, preserving unreadable files. */
        @JvmStatic
        fun readSanitized(path: Path?): SanitizedLoad {
            val existed = path != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            val confirmedAbsent = path != null && Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
            val redaction = RetiredChatConfigRedactor.redact(path)
            // Keep local chat parse errors quiet; generic config logging could expose an unexpected raw token.
            val loaded = if (redaction.cleaned() && redaction.sourceJsonValid) readQuietly(path) else null
            val oldSchema = loaded != null &&
                (loaded.chatReplyConfigVersion == null || loaded.chatReplyConfigVersion!! < REPLY_SCHEMA_VERSION)
            var persistenceAllowed = confirmedAbsent || loaded != null
            if (!redaction.cleaned() || !redaction.sourceJsonValid) persistenceAllowed = false
            val needsRewrite = oldSchema
            val warning = if ((!persistenceAllowed && (existed || path != null)) ||
                (loaded != null && !redaction.cleaned())
            ) unreadableFileWarning(path, redaction, loaded != null) else null
            return SanitizedLoad(sanitize(loaded ?: Data()), needsRewrite, persistenceAllowed, warning)
        }

        @JvmStatic
        fun writeSanitized(path: Path?, loaded: SanitizedLoad?): Future<Boolean> {
            if (path == null || loaded == null || !loaded.persistenceAllowed) {
                return CompletableFuture.completedFuture(false)
            }
            return ClientJsonFile.writeAsync(path, ClientJsonFile.GSON.toJson(loaded.data))
        }

        private fun configFile(): Path {
            val configDir = FabricLoader.getInstance().configDir
                ?: throw IllegalStateException("Fabric config directory is not available")
            return configDir.resolve("geileraddons").resolve("chat-channels-local.json")
        }

        private fun readQuietly(path: Path?): Data? {
            if (path == null) return null
            return try {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return null
                }
                val size = Files.size(path)
                if (size <= 0 || size > ClientJsonFile.MAX_FILE_BYTES) return null
                ClientJsonFile.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data::class.java)
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun unreadableFileWarning(
            file: Path?,
            redaction: RetiredChatConfigRedactor.Result,
            loaded: Boolean,
        ): String {
            val path = file?.toAbsolutePath()?.toString() ?: "the local chat settings file"
            if (!redaction.cleaned()) {
                val reason = redaction.reason ?: "cleanup did not complete"
                if (reason.contains("recovery copy")) {
                    return "Retired chat settings cleanup did not finish ($reason). Read-only mode is active to protect " +
                        "stored replies and folders. Back up both the recovery copy and $path before manual repair."
                }
                return "Retired chat settings could not be safely scrubbed ($reason). The original file was preserved" +
                    (if (loaded) " and read-only mode is active" else "; read-only mode is active to protect stored replies and folders") +
                    ". Back up $path and remove its retired azureKey/translation fields manually."
            }
            if (!redaction.sourceJsonValid) {
                if (redaction.status == RetiredChatConfigRedactor.Status.REDACTED) {
                    return "Retired translation properties were removed, but the original JSON was malformed. Other bytes were " +
                        "preserved and read-only mode is active to protect stored replies and folders. Back up and repair " +
                        "$path, then restart."
                }
                return "The local chat settings file contains malformed JSON and was preserved. Read-only mode is active to " +
                    "protect stored replies and folders. Back up and repair $path, then restart."
            }
            if (redaction.status == RetiredChatConfigRedactor.Status.REDACTED && !loaded) {
                return "Retired translation properties were removed, but the rest of the file could not be loaded within the " +
                    "strict 1 MiB runtime limit or does not match the chat settings schema. Remaining bytes were preserved and read-only mode is active. " +
                    "Back up and trim or repair $path, then restart."
            }
            return "The local chat settings file could not be loaded within the strict 1 MiB runtime limit or does not match the chat settings schema. " +
                "It was preserved and read-only mode is active to protect stored replies and folders. Back up and repair or trim " +
                "$path, then restart."
        }

        private fun sanitize(value: Data): Data {
            val clean = Data()
            clean.unfocusedChatHudEnabled = value.unfocusedChatHudEnabled
            clean.chatHudBoundsMigrated = value.chatHudBoundsMigrated
            clean.focusedChatX = boundedPosition(value.focusedChatX)
            clean.focusedChatY = boundedPosition(value.focusedChatY)
            clean.chatWindowWidth = boundedDimension(value.chatWindowWidth,
                ChatWindowLayoutRules.MIN_WIDTH, ChatWindowLayoutRules.MAX_WIDTH)
            clean.chatWindowHeight = boundedDimension(value.chatWindowHeight,
                ChatWindowLayoutRules.MIN_HEIGHT, ChatWindowLayoutRules.MAX_HEIGHT)
            val sourceRules = value.rules
            if (sourceRules != null) {
                for (rule in sourceRules) {
                    if (clean.rules!!.size >= MAX_RULES || rule == null) break
                    val parsed = ruleFromData(rule)
                    if (isValidChatChannelRule(parsed) && clean.rules!!.none { existing -> existing?.id == parsed.id() }) {
                        clean.rules!!.add(ruleDataFrom(parsed))
                    }
                }
            }
            val restoredFolders = FolderTree()
            val folderValues = ArrayList<FolderTree.Folder>()
            val sourceFolders = value.replyFolders
            if (sourceFolders != null) {
                for (folder in sourceFolders) {
                    if (folder == null) continue
                    val folderId = folder.id
                    if (folderId == null || ChatStringSemantics.isBlank(folderId)) continue
                    folderValues.add(folderFromData(folder))
                }
            }
            restoredFolders.restore(folderValues)
            if (sourceFolders != null) {
                for (folder in restoredFolders.folders()) {
                    val folderId = folder.id ?: continue
                    val source = sourceFolders.firstOrNull { candidate -> candidate != null && folderId == candidate.id }
                    clean.replyFolders!!.add(folderDataFrom(folder, source?.enabled != false))
                }
            }
            val validFolderIds = HashSet<String>()
            for (folder in restoredFolders.folders()) folder.id?.let(validFolderIds::add)
            val sourceReplies = value.chatReplies
            if (sourceReplies != null) {
                for (stored in sourceReplies) {
                    if (clean.chatReplies!!.size >= MAX_CHAT_REPLIES || stored == null) break
                    var entry = stored.toEntry()
                    val entryCommand = entry.command()
                    val entryId = entry.id()
                    if (entry.isValid() && clean.chatReplies!!.none { existing ->
                            existing?.command == entryCommand || existing?.id == entryId
                        }
                    ) {
                        if (entry.folderId() != null && entry.folderId() !in validFolderIds) {
                            entry = ChatReplyEntry(entry.id(), entry.command(), entry.mode(), entry.response(),
                                entry.actionTemplate(), null, entry.enabled())
                        }
                        clean.chatReplies!!.add(ReplyData.from(entry))
                    }
                }
            }
            clean.chatReplyConfigVersion = REPLY_SCHEMA_VERSION
            clean.replyAllowlist = ArrayList<String?>(ChatReplyConfigRules.normalizeUsernames(value.replyAllowlist))
            clean.replyBlocklist = ArrayList<String?>(ChatReplyConfigRules.normalizeUsernames(value.replyBlocklist))
            clean.replyAllowlistEnabled = ChatReplyConfigRules.resolveLegacyFilterEnabled(
                value.replyAllowlistEnabled, clean.replyAllowlist)
            clean.replyBlocklistEnabled = ChatReplyConfigRules.resolveLegacyFilterEnabled(
                value.replyBlocklistEnabled, clean.replyBlocklist)
            return clean
        }

        private fun boundedPosition(value: Int?): Int? = value?.coerceIn(0, 16_384)

        private fun boundedDimension(value: Int?, min: Int, max: Int): Int? = value?.coerceIn(min, max)

        private fun isValidChatChannelRule(rule: ChatChannelRule?): Boolean =
            rule != null && !ChatStringSemantics.isBlank(rule.name()) &&
                !ChatStringSemantics.isBlank(rule.incomingContains()) && !ChatStringSemantics.isBlank(rule.commandPrefix()) &&
                (rule.matchMode() != ChatChannelRule.MatchMode.REGEX ||
                    ChatChannelRegex.validationError(rule.incomingContains()).isEmpty())

        private fun ruleFromData(stored: RuleData): ChatChannelRule {
            val mode = try {
                if (stored.matchMode == null) ChatChannelRule.MatchMode.TEXT
                else ChatChannelRule.MatchMode.valueOf(stored.matchMode!!)
            } catch (_: IllegalArgumentException) {
                ChatChannelRule.MatchMode.TEXT
            }
            return ChatChannelRule(stored.id ?: UUID.randomUUID().toString(), stored.name,
                stored.incomingContains, stored.commandPrefix, stored.whitelist, stored.blacklist, mode)
        }

        private fun ruleDataFrom(rule: ChatChannelRule): RuleData {
            val stored = RuleData()
            stored.id = rule.id()
            stored.name = rule.name()
            stored.incomingContains = rule.incomingContains()
            stored.commandPrefix = rule.commandPrefix()
            stored.matchMode = rule.matchMode().name
            stored.whitelist = ArrayList(rule.whitelist())
            stored.blacklist = ArrayList(rule.blacklist())
            return stored
        }

        private fun folderFromData(stored: FolderData): FolderTree.Folder =
            FolderTree.Folder(stored.id, stored.name, stored.parentId)

        private fun folderDataFrom(folder: FolderTree.Folder, enabled: Boolean): FolderData {
            val stored = FolderData()
            stored.id = folder.id
            stored.name = folder.name
            stored.parentId = folder.parentId
            stored.enabled = enabled
            return stored
        }
    }
}
