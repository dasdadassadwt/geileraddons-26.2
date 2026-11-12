package geiler.addons.client.chat

/** One filter in native chat history. Custom channels are built from saved rules. */
class ChatChannel(id: String?, name: String?, commandPrefix: String?, custom: Boolean) {
    private val idValue = id ?: ""
    private val nameValue = if (name == null || ChatStringSemantics.isBlank(name)) idValue else name
    private val commandPrefixValue = if (commandPrefix == null || ChatStringSemantics.isBlank(commandPrefix)) {
        null
    } else {
        commandPrefix
    }
    private val customValue = custom

    fun id(): String = idValue

    fun name(): String = nameValue

    fun commandPrefix(): String? = commandPrefixValue

    fun custom(): Boolean = customValue

    override fun equals(other: Any?): Boolean = this === other || other is ChatChannel &&
        idValue == other.idValue && nameValue == other.nameValue &&
        commandPrefixValue == other.commandPrefixValue && customValue == other.customValue

    override fun hashCode(): Int {
        var result = idValue.hashCode()
        result = 31 * result + nameValue.hashCode()
        result = 31 * result + (commandPrefixValue?.hashCode() ?: 0)
        result = 31 * result + customValue.hashCode()
        return result
    }

    override fun toString(): String =
        "ChatChannel[id=$idValue, name=$nameValue, commandPrefix=$commandPrefixValue, custom=$customValue]"

    companion object {
        @JvmField
        val ALL = ChatChannel("all", "All", "/ac", false)

        @JvmField
        val PARTY = ChatChannel("party", "Party", "/pc", false)

        @JvmField
        val GUILD = ChatChannel("guild", "Guild", "/gc", false)

        @JvmField
        val COOP = ChatChannel("coop", "Co-op", "/cc", false)

        @JvmField
        val PRIVATE = ChatChannel("private", "Private", "/r", false)

        @JvmField
        val VANILLA_SYSTEM = ChatChannel("vanilla", "Vanilla/System", null, false)

        @JvmStatic
        fun builtIns(): List<ChatChannel> = java.util.List.of(
            ALL,
            PARTY,
            GUILD,
            COOP,
            PRIVATE,
            VANILLA_SYSTEM,
        )
    }
}