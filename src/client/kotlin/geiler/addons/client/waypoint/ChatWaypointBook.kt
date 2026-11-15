package geiler.addons.client.waypoint

/** Short-lived chat waypoints: at most one per sender, expiring, and bounded. */
class ChatWaypointBook {
    class Entry(val sender: String, val x: Int, val y: Int, val z: Int, val expiresAtMillis: Long)

    private val entries = LinkedHashMap<String, Entry>()

    fun size(): Int = entries.size

    fun put(sender: String, x: Int, y: Int, z: Int, nowMillis: Long, durationMillis: Long) {
        val key = sender.lowercase()
        entries.remove(key)
        entries[key] = Entry(sender, x, y, z, nowMillis + durationMillis)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    /** Drops expired entries and returns the live ones, oldest first. */
    fun active(nowMillis: Long): List<Entry> {
        entries.values.removeIf { it.expiresAtMillis <= nowMillis }
        return ArrayList(entries.values)
    }

    fun clear() = entries.clear()

    companion object {
        const val MAX_ENTRIES = 64
    }
}
