package geiler.addons.client.chat

import geiler.addons.client.collections.FolderTree
import java.util.HashSet
import java.util.Locale
import java.util.regex.Pattern

/** Bounds and sender policy for locally configured, plain-text chat replies. */
object ChatReplyConfigRules {
    const val MAX_ENTRIES = 16
    const val MAX_COMMAND_LENGTH = 24
    const val MAX_RESPONSE_LENGTH = 160
    const val MAX_FILTER_NAMES = 32

    private val COMMAND = Pattern.compile("[a-z][a-z0-9_-]{0,23}")
    private val USERNAME = ChatStringSemantics.MINECRAFT_USERNAME
    private val RESERVED_COMMANDS = setOf(
        "help", "h", "coords", "co", "odin", "od", "cf", "8ball", "dice", "ping", "tps", "fps", "time",
        "lobby", "location", "inv", "invite", "holding", "pt", "ptme", "transfer", "warp", "w", "allinvite", "allinv", "boop",
        "kick", "k", "promote", "demote", "kickoffline", "ko",
        "f1", "f2", "f3", "f4", "f5", "f6", "f7",
        "m1", "m2", "m3", "m4", "m5", "m6", "m7",
        "t1", "t2", "t3", "t4", "t5",
    )
    private val PARTY_TRANSFER_NAMES = setOf("pt", "ptme", "transfer")

    @JvmStatic
    fun normalizeCommand(value: String?): String {
        if (value == null) return ""
        val normalized = value.javaTrim().lowercase(Locale.ROOT)
        return if (normalized.length <= MAX_COMMAND_LENGTH && COMMAND.matcher(normalized).matches()) normalized else ""
    }

    @JvmStatic
    fun isReservedCommand(command: String?): Boolean =
        command != null && RESERVED_COMMANDS.contains(command.javaTrim().lowercase(Locale.ROOT))

    @JvmStatic
    fun isPartyTransferName(command: String?): Boolean =
        command != null && PARTY_TRANSFER_NAMES.contains(command.javaTrim().lowercase(Locale.ROOT))

    @JvmStatic
    fun validResponse(value: String?): Boolean {
        if (value == null) return false
        val trimmed = value.javaTrim()
        if (trimmed.isEmpty() || trimmed.length > MAX_RESPONSE_LENGTH) return false
        for (current in trimmed) {
            val type = Character.getType(current)
            if (current == '\n' || current == '\r' || current == '\u00a7' || Character.isISOControl(current) ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
            ) return false
        }
        return true
    }

    /** Why a new or edited reply draft cannot be saved, or null when it is valid. */
    @JvmStatic
    fun draftError(prefix: String?, command: String?, localAction: Boolean, payload: String?): String? {
        val name = normalizeCommand(command)
        val value = ChatStringSemantics.trim(payload ?: "")
        val valid = if (localAction) ChatActionTemplateRules.isValid(ChatActionTemplateRules.normalize(value)) else validResponse(value)
        if (name.isEmpty() || !valid) {
            return if (localAction) "Enter one /command with exactly one {args} placeholder." else "Enter one line of plain reply text."
        }
        if (ChatReplyCommandRules.isBuiltInName(name) && (!localAction || !isPartyTransferName(name))) {
            return "That name belongs to a built-in command; only pt/ptme/transfer can be locally customized."
        }
        val active = ChatReplyCommandRules.normalizePrefix(prefix)
        if (!localAction && active != null && value.startsWith(active)) {
            return "A reply cannot start with the command prefix; that would create a reply loop."
        }
        return null
    }

    @JvmStatic
    fun canAddEntry(entries: List<ChatReplyEntry?>?, candidate: ChatReplyEntry?): Boolean {
        if (candidate == null || !candidate.isValid() || entries == null || entries.size >= MAX_ENTRIES) return false
        return entries.none { entry -> entry != null && entry.command() == candidate.command() }
    }

    /** Validates an edited flat list atomically, preserving stable ids while preventing aliases. */
    @JvmStatic
    fun validReplacement(entries: List<ChatReplyEntry?>?): Boolean {
        if (entries == null || entries.size > MAX_ENTRIES) return false
        val ids = HashSet<String>()
        val commands = HashSet<String>()
        for (entry in entries) {
            if (entry == null || !entry.isValid() || !ids.add(entry.id()) || !commands.add(entry.command())) return false
        }
        return true
    }

    /** A disabled folder also disables commands below it; missing folder state defaults enabled. */
    @JvmStatic
    fun enabledInFolderTree(
        tree: FolderTree?,
        folderEnabled: Map<String, Boolean>?,
        folderId: String?,
    ): Boolean {
        if (tree == null || folderId == null || javaIsBlank(folderId)) return true
        val visited = HashSet<String>()
        var currentId: String? = folderId
        while (currentId != null && visited.add(currentId)) {
            if (folderEnabled != null && folderEnabled[currentId] == false) return false
            val folder = tree.folder(currentId) ?: break
            currentId = folder.parentId
        }
        return true
    }

    /** Names are normalized case-insensitively; malformed values are ignored on input and load. */
    @JvmStatic
    fun normalizeUsernames(names: List<String?>?): List<String> {
        if (names == null) return emptyList()
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        for (value in names) {
            if (result.size >= MAX_FILTER_NAMES) break
            if (value == null) continue
            val name = value.javaTrim()
            if (!USERNAME.matcher(name).matches()) continue
            val key = name.lowercase(Locale.ROOT)
            if (seen.add(key)) result.add(key)
        }
        return java.util.List.copyOf(result)
    }

    @JvmStatic
    fun validUsernameList(names: List<String?>?): Boolean {
        if (names == null) return true
        var count = 0
        for (value in names) {
            if (value == null || javaIsBlank(value)) continue
            if (!USERNAME.matcher(value.javaTrim()).matches()) return false
            count++
            if (count > MAX_FILTER_NAMES) return false
        }
        return true
    }

    /** The blocklist with [name] added (normalized), or null when it is invalid, already listed, or the list is full. */
    @JvmStatic
    fun blocklistWith(current: List<String?>?, name: String?): List<String>? {
        val trimmed = name?.javaTrim() ?: return null
        if (!USERNAME.matcher(trimmed).matches()) return null
        val list = normalizeUsernames(current)
        val key = trimmed.lowercase(Locale.ROOT)
        if (key in list || list.size >= MAX_FILTER_NAMES) return null
        return list + key
    }

    /** The blocklist without [name] (case-insensitive); unchanged when it was not listed. */
    @JvmStatic
    fun blocklistWithout(current: List<String?>?, name: String?): List<String> =
        normalizeUsernames(current).filter { !it.equals(name?.javaTrim(), ignoreCase = true) }

    /** Old files had implicit list activation; preserve that behavior when migrating the nullable toggle. */
    @JvmStatic
    fun resolveLegacyFilterEnabled(savedEnabled: Boolean?, names: List<String?>?): Boolean =
        savedEnabled ?: normalizeUsernames(names).isNotEmpty()

    /** Enabled lists constrain ordinary incoming replies independently; an enabled block always wins. */
    @JvmStatic
    fun senderAllowed(
        sender: String?,
        allowlistEnabled: Boolean,
        allowlist: List<String?>?,
        blocklistEnabled: Boolean,
        blocklist: List<String?>?,
    ): Boolean {
        if (sender == null || javaIsBlank(sender)) return false
        val trimmed = sender.javaTrim()
        if (!USERNAME.matcher(trimmed).matches()) return false
        val normalized = trimmed.lowercase(Locale.ROOT)
        if (blocklistEnabled && normalizeUsernames(blocklist).contains(normalized)) return false
        return !allowlistEnabled || normalizeUsernames(allowlist).contains(normalized)
    }

    /** Remote commands with client-side effects require an enabled allowlist entry; an enabled blocklist vetoes. */
    @JvmStatic
    fun remoteActionAllowed(
        sender: String?,
        allowlistEnabled: Boolean,
        allowlist: List<String?>?,
        blocklistEnabled: Boolean,
        blocklist: List<String?>?,
    ): Boolean = allowlistEnabled && senderAllowed(sender, true, allowlist, blocklistEnabled, blocklist)

    /** Party requests are open to otherwise-valid senders by default; other routes need their toggle. */
    @JvmStatic
    fun incomingChannelAllowed(
        channel: ChatChannel?,
        partyEnabled: Boolean,
        guildEnabled: Boolean,
        coopEnabled: Boolean,
        privateEnabled: Boolean,
        customEnabled: Boolean,
    ): Boolean {
        if (channel == null) return false
        if (channel.custom()) return customEnabled
        return when (channel.id()) {
            "party" -> partyEnabled
            "guild" -> guildEnabled
            "coop" -> coopEnabled
            "private" -> privateEnabled
            else -> false
        }
    }

    private fun String.javaTrim(): String = ChatStringSemantics.trim(this)

    private fun javaIsBlank(value: String): Boolean = ChatStringSemantics.isBlank(value)
}
