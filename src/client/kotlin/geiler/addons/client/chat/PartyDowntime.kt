package geiler.addons.client.chat

/** Downtime reminders set with `!dt`: who needs a break after the run. A reminder also holds Instance Requeue. */
object PartyDowntime {
    private const val MAX_ENTRIES = 16
    private const val NO_REASON = "No reason given"

    private val entries = LinkedHashMap<String, Pair<String, String>>()

    /** False when the player already has a reminder or the list is full. */
    @JvmStatic
    fun add(name: String, reason: String?): Boolean {
        val key = name.lowercase()
        if (entries.containsKey(key) || entries.size >= MAX_ENTRIES) return false
        entries[key] = name to (reason?.takeIf { it.isNotBlank() } ?: NO_REASON)
        return true
    }

    @JvmStatic
    fun remove(name: String): Boolean = entries.remove(name.lowercase()) != null

    @JvmStatic
    fun reasonFor(name: String?): String? = name?.let { entries[it.lowercase()]?.second }

    @JvmStatic
    fun holdsRequeue(): Boolean = entries.isNotEmpty()

    @JvmStatic
    fun size(): Int = entries.size

    /** `Alice: Mana, Bob: AFK`, grouped as written by Odin: reason first, then the names that gave it. */
    @JvmStatic
    fun summary(): String = entries.values.groupBy({ it.second }, { it.first }).entries
        .joinToString(", ") { (reason, names) -> "${names.joinToString(", ")}: $reason" }

    @JvmStatic
    fun clear() = entries.clear()
}
