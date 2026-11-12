package geiler.addons.client.chat

/** Ephemeral vanilla-chat tab selection and per-message routing to Hypixel chat channels. */
object ChatChannelTabs {
    private val lock = Any()
    private var selectedValue: Tab = Tab.NONE

    @JvmStatic
    fun selected(): Tab = synchronized(lock) { selectedValue }

    @JvmStatic
    fun tabs(): List<Tab> = java.util.Collections.unmodifiableList(
        listOf(Tab.NONE, Tab.ALL, Tab.PARTY, Tab.GUILD, Tab.COOP, Tab.PRIVATE),
    )

    /** Tab changes are local; the selected prefix is applied only when ordinary chat is submitted. */
    @JvmStatic
    fun select(tab: Tab?): Boolean {
        if (tab == null) return false
        synchronized(lock) { selectedValue = tab }
        return true
    }

    /** Slash commands pass through; ordinary text is sent using the selected channel's prefix. */
    @JvmStatic
    fun routeInput(tab: Tab?, input: String?): String? {
        if (tab == null || input == null || ChatStringSemantics.isBlank(input)) return input
        if (ChatStringSemantics.stripLeading(input).startsWith("/")) return input
        val prefix = tab.messagePrefix()
        return if (prefix == null) input else "$prefix ${ChatStringSemantics.strip(input)}"
    }

    /** State resets with the app process; it is deliberately not written into either config file. */
    @JvmStatic
    fun reset() {
        synchronized(lock) { selectedValue = Tab.NONE }
    }

    enum class Tab(private val channelIdValue: String, private val labelValue: String, private val messagePrefixValue: String?) {
        NONE("all", "None", null),
        ALL("all", "All", "/ac"),
        PARTY("party", "Party", "/pc"),
        GUILD("guild", "Guild", "/gc"),
        COOP("coop", "Co-op", "/cc"),
        PRIVATE("private", "Private", "/r");

        fun channelId(): String = channelIdValue
        fun label(): String = labelValue
        fun messagePrefix(): String? = messagePrefixValue
    }
}
