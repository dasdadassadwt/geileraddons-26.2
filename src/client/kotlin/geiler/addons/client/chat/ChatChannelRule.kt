package geiler.addons.client.chat

import geiler.addons.client.tree.ChatText
import java.util.Locale
import java.util.UUID

/** A custom inbound match and outgoing command prefix for one user-defined tab. */
class ChatChannelRule(
    id: String?,
    name: String?,
    incomingContains: String?,
    commandPrefix: String?,
    whitelist: List<String?>?,
    blacklist: List<String?>?,
    matchMode: MatchMode?,
) {
    enum class MatchMode { TEXT, REGEX }

    private val idValue = if (id == null || ChatStringSemantics.isBlank(id)) UUID.randomUUID().toString() else id
    private val nameValue = bounded(name, 24)
    private val incomingContainsValue = bounded(incomingContains, 96)
    private val commandPrefixValue = bounded(commandPrefix, 80)
    private val whitelistValue = normalizeNames(whitelist)
    private val blacklistValue = normalizeNames(blacklist)
    private val matchModeValue = matchMode ?: MatchMode.TEXT

    /** Backward-compatible constructor for existing text-match rules and call sites. */
    constructor(
        id: String?,
        name: String?,
        incomingContains: String?,
        commandPrefix: String?,
        whitelist: List<String?>?,
        blacklist: List<String?>?,
    ) : this(id, name, incomingContains, commandPrefix, whitelist, blacklist, MatchMode.TEXT)

    fun id(): String = idValue

    fun name(): String = nameValue

    fun incomingContains(): String = incomingContainsValue

    fun commandPrefix(): String = commandPrefixValue

    fun whitelist(): List<String> = whitelistValue

    fun blacklist(): List<String> = blacklistValue

    fun matchMode(): MatchMode = matchModeValue

    fun tab(): ChatChannel = ChatChannel("custom:$idValue", nameValue, commandPrefixValue, true)

    @Suppress("DEPRECATION")
    fun matches(rawText: String?, sender: String?): Boolean {
        if (rawText == null || ChatStringSemantics.isBlank(incomingContainsValue)) return false
        if (whitelistValue.isNotEmpty() && !containsName(whitelistValue, sender)) return false
        if (containsName(blacklistValue, sender)) return false

        val matchText = ChatText.stripForMatch(rawText)
        return if (matchModeValue == MatchMode.REGEX) {
            ChatChannelRegex.matches(incomingContainsValue, matchText)
        } else {
            matchText.lowercase(Locale.ROOT).contains(incomingContainsValue.lowercase(Locale.ROOT))
        }
    }

    override fun equals(other: Any?): Boolean = this === other || other is ChatChannelRule &&
        idValue == other.idValue && nameValue == other.nameValue &&
        incomingContainsValue == other.incomingContainsValue && commandPrefixValue == other.commandPrefixValue &&
        whitelistValue == other.whitelistValue && blacklistValue == other.blacklistValue &&
        matchModeValue == other.matchModeValue

    override fun hashCode(): Int {
        var result = idValue.hashCode()
        result = 31 * result + nameValue.hashCode()
        result = 31 * result + incomingContainsValue.hashCode()
        result = 31 * result + commandPrefixValue.hashCode()
        result = 31 * result + whitelistValue.hashCode()
        result = 31 * result + blacklistValue.hashCode()
        result = 31 * result + matchModeValue.hashCode()
        return result
    }

    override fun toString(): String =
        "ChatChannelRule[id=$idValue, name=$nameValue, incomingContains=$incomingContainsValue, " +
            "commandPrefix=$commandPrefixValue, whitelist=$whitelistValue, blacklist=$blacklistValue, " +
            "matchMode=$matchModeValue]"

    private fun containsName(names: List<String>, sender: String?): Boolean {
        if (sender == null || ChatStringSemantics.isBlank(sender)) return false
        return names.any { it.equals(sender, ignoreCase = true) }
    }

    private fun bounded(value: String?, maximum: Int): String {
        if (value == null) return ""
        val clean = ChatStringSemantics.trim(value.replace('\r', ' ').replace('\n', ' '))
        return clean.substring(0, minOf(maximum, clean.length))
    }

    private fun normalizeNames(names: List<String?>?): List<String> {
        if (names == null) return java.util.List.of()

        val normalized = ArrayList<String>()
        val seen = HashSet<String>()
        for (name in names) {
            if (name == null || ChatStringSemantics.isBlank(name)) continue
            val trimmed = ChatStringSemantics.trim(name)
            if (seen.add(trimmed)) {
                normalized.add(trimmed)
                if (normalized.size == 32) break
            }
        }
        return java.util.List.copyOf(normalized)
    }
}
