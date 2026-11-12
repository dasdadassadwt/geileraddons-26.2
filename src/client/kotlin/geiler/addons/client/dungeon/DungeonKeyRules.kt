package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.tree.ChatText
import java.util.Locale

/** Exact display-name matching for the two different Catacombs key types. */
object DungeonKeyRules {
    /** One tick's immutable ownership result, shared by every door color decision in that render frame. */
    @JvmRecord
    data class KeyOwnership(val wither: Boolean, val blood: Boolean) {
        companion object {
            @JvmField
            val NONE = KeyOwnership(false, false)
        }
    }

    @JvmStatic
    fun combineOwnership(
        observedWither: Boolean,
        observedBlood: Boolean,
        inventoryWither: Boolean,
        inventoryBlood: Boolean,
    ): KeyOwnership = KeyOwnership(observedWither || inventoryWither, observedBlood || inventoryBlood)

    @JvmStatic
    fun isMatchingKey(displayName: String?, doorType: DungeonDoorTracker.Type?): Boolean {
        if (displayName == null || doorType == null ||
            (doorType != DungeonDoorTracker.Type.WITHER && doorType != DungeonDoorTracker.Type.BLOOD)
        ) return false

        val normalized = ChatText.stripForMatch(displayName)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim { it <= ' ' }
            .replace(Regex("\\s+"), " ")
        val expected = if (doorType == DungeonDoorTracker.Type.WITHER) "wither key" else "blood key"
        return (" $normalized ").contains(" $expected ")
    }

    /** Chat fallback events only belong to the local player when their username is present. */
    @JvmStatic
    fun isPickupForPlayer(line: String?, playerName: String?, doorType: DungeonDoorTracker.Type?): Boolean =
        isLocalPlayerEvent(line, playerName, doorType, " has obtained ", " Key!")

    @JvmStatic
    fun isDoorOpenedByPlayer(line: String?, playerName: String?, doorType: DungeonDoorTracker.Type?): Boolean {
        val ending = if (doorType == DungeonDoorTracker.Type.WITHER) {
            " opened a wither door!"
        } else {
            " opened a blood door!"
        }
        return isLocalPlayerEvent(line, playerName, doorType, null, ending)
    }

    private fun isLocalPlayerEvent(
        line: String?,
        playerName: String?,
        doorType: DungeonDoorTracker.Type?,
        middle: String?,
        ending: String,
    ): Boolean {
        if (line == null || playerName == null || ChatStringSemantics.isBlank(playerName) ||
            (doorType != DungeonDoorTracker.Type.WITHER && doorType != DungeonDoorTracker.Type.BLOOD)
        ) return false

        val cleanLine = ChatText.plain(line).trim { it <= ' ' }
        val localName = ChatText.stripForMatch(playerName)
        val suffix = if (middle != null) {
            val key = if (doorType == DungeonDoorTracker.Type.WITHER) "Wither" else "Blood"
            middle + key + ending
        } else {
            ending
        }
        if (cleanLine.length <= suffix.length ||
            !cleanLine.regionMatches(
                cleanLine.length - suffix.length,
                suffix,
                0,
                suffix.length,
                ignoreCase = true,
            )
        ) return false

        var actor = cleanLine.substring(0, cleanLine.length - suffix.length).trim { it <= ' ' }
        val lastSpace = actor.lastIndexOf(' ')
        if (lastSpace >= 0) actor = actor.substring(lastSpace + 1)
        return actor.equals(localName, ignoreCase = true)
    }
}
