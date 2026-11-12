package geiler.addons.client.dungeon

import java.util.HashMap
import java.util.regex.Pattern

/** Client-local per-room secret counts for the dungeon map. */
class DungeonRoomSecretProgress {
    private val foundByRoom = HashMap<String, Int>()
    private var currentRoomId: String? = null

    /** Selects the stable map-grid room currently occupied by the local player. */
    fun enterRoom(roomId: String?) {
        currentRoomId = normalize(roomId)
        currentRoomId?.let { foundByRoom.putIfAbsent(it, -1) }
    }

    /** Clears attribution while retaining run-local progress, for example during a boss transition. */
    fun clearCurrentRoom() {
        currentRoomId = null
    }

    /**
     * Accepts a parsed actionbar only when it belongs to the current map room and its maximum
     * matches the catalog's room maximum. Counts are monotonic so a delayed stale overlay cannot
     * roll progress backward.
     */
    fun recordActionbar(actionbar: String?, attributedRoomId: String?, expectedMaximum: Int): Boolean {
        val counts = parseActionbar(actionbar)
        val roomId = normalize(attributedRoomId)
        if (counts == null || roomId == null || currentRoomId != roomId ||
            expectedMaximum <= 0 || expectedMaximum > MAX_SECRETS || counts.maximum != expectedMaximum
        ) return false
        val previous = foundByRoom.getOrDefault(roomId, -1)
        if (counts.found <= previous) return false
        foundByRoom[roomId] = counts.found
        return true
    }

    /** A GREEN map tile is authoritative completion; other map colors never imply a full count. */
    fun observeMapCheckmark(roomId: String?, maximum: Int, checkmark: String?): Boolean {
        val key = normalize(roomId)
        if (key == null || maximum <= 0 || maximum > MAX_SECRETS || checkmark != "GREEN") return false
        if (foundByRoom.getOrDefault(key, -1) == maximum) return false
        foundByRoom[key] = maximum
        return true
    }

    /** Returns -1 until an actionbar count or an authoritative GREEN map state is seen. */
    fun foundSecrets(roomId: String?): Int {
        val key = normalize(roomId)
        return if (key == null) -1 else foundByRoom.getOrDefault(key, -1)
    }

    fun currentRoomId(): String? = currentRoomId
    fun trackedRoomCount(): Int = foundByRoom.size

    fun reset() {
        foundByRoom.clear()
        currentRoomId = null
    }

    @JvmRecord
    data class Counts(val found: Int, val maximum: Int)

    companion object {
        private const val MAX_SECRETS = 128
        private val ACTIONBAR_FORMATTING = Pattern.compile("(?i)\\u00a7[0-9A-FK-OR]")
        private val ACTIONBAR_SECRETS = Pattern.compile(
            "(?<![0-9])(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s+Secrets?\\b",
            Pattern.CASE_INSENSITIVE,
        )

        @JvmStatic
        fun parseActionbar(actionbar: String?): Counts? {
            if (actionbar == null || actionbar.length > 256) return null
            val plain = ACTIONBAR_FORMATTING.matcher(actionbar).replaceAll("").stripJavaWhitespace()
            val matcher = ACTIONBAR_SECRETS.matcher(plain)
            if (!matcher.find()) return null
            val found: Int
            val maximum: Int
            try {
                found = Integer.parseInt(matcher.group(1))
                maximum = Integer.parseInt(matcher.group(2))
            } catch (_: NumberFormatException) {
                return null
            }
            if (matcher.find() || maximum <= 0 || maximum > MAX_SECRETS || found > maximum) return null
            return Counts(found, maximum)
        }

        private fun normalize(roomId: String?): String? {
            if (roomId == null || roomId.isJavaWhitespaceBlank() || roomId.length > 128) return null
            return roomId.stripJavaWhitespace()
        }

        private fun String.isJavaWhitespaceBlank(): Boolean = all { Character.isWhitespace(it) }

        private fun String.stripJavaWhitespace(): String = trim { Character.isWhitespace(it) }
    }
}
