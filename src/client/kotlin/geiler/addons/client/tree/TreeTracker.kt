package geiler.addons.client.tree

import java.util.EnumMap

/** Gift counts and rates for all three tree types. */
class TreeTracker {
    private val treeStats = EnumMap<TreeType, TreeStats>(TreeType::class.java)
    private var lastGiftedValue: TreeType? = null

    init {
        for (type in TreeType.values()) treeStats[type] = TreeStats()
    }

    fun stats(type: TreeType): TreeStats = treeStats.getValue(type)

    fun lastGifted(): TreeType? = lastGiftedValue

    /** @return the credited type, or null if the message was not a gift or was a repeat */
    fun onChatMessage(message: String, now: Long, timeoutMillis: Long): TreeType? {
        val type = TreeType.fromMessage(message) ?: return null
        val stats = treeStats.getValue(type)
        // One gift announces itself over several lines; a second real gift cannot arrive inside 1s.
        if (stats.countedWithin(now, DUPLICATE_WINDOW_MILLIS)) return null
        stats.onGift(now, timeoutMillis)
        lastGiftedValue = type
        return type
    }

    /** Ends the current session for every type at once and starts a new one. */
    fun resetSession(now: Long, timeoutMillis: Long) {
        for (value in treeStats.values) value.rollOver(now, timeoutMillis)
        lastGiftedValue = null
    }

    /** Closes out whatever session was loaded from disk without crediting time between launches. */
    fun rollOverLoadedSession() {
        for (value in treeStats.values) value.rollOver(0, 0)
        lastGiftedValue = null
    }

    fun clearAll() {
        for (value in treeStats.values) value.restore(0, 0, 0, 0)
        lastGiftedValue = null
    }

    companion object {
        private const val DUPLICATE_WINDOW_MILLIS = 1_000L
    }
}
