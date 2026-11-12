package geiler.addons.client.dungeon.odin

import java.util.LinkedHashSet
import java.util.TreeSet

/** Kotlin-owned run-local state behind the stable Java lifecycle and record API. */
internal class OdinDungeonRoomLifecycleCore {
    private var level: Any? = null
    private var dungeonActive = false
    private var bossPhase = false
    private var currentRoomId: String? = null
    private val walkedIntoTiles = LinkedHashSet<Int>()

    /**
     * Observes the room currently under the player. The caller sets [roomReady] only after
     * matching a loaded room signature, resolving its rotation, and passing Odin's room-height gate.
     * Unknown or not-yet-ready observations retain the previous current room, matching Odin's
     * early-return behavior; dungeon exit and boss entry clear only the current-room identity.
     */
    fun observe(
        levelIdentity: Any?,
        inDungeon: Boolean,
        inBoss: Boolean,
        candidateRoomId: String?,
        roomReady: Boolean,
        playerTile: Int,
        roomTiles: List<Int?>?,
    ): OdinDungeonRoomLifecycle.Snapshot {
        if (levelIdentity == null) {
            reset()
            return snapshot()
        }
        if (level !== levelIdentity) {
            clearSession()
            level = levelIdentity
        }
        if (!inDungeon) {
            clearSession()
            level = levelIdentity
            return snapshot()
        }
        dungeonActive = true
        if (inBoss) {
            bossPhase = true
            currentRoomId = null
            return snapshot()
        }
        bossPhase = false
        if (!roomReady || !validTile(playerTile) || candidateRoomId == null ||
            candidateRoomId.all { Character.isWhitespace(it) }
        ) {
            return snapshot()
        }

        val normalizedId = candidateRoomId.trim { Character.isWhitespace(it) }
        val enteredRoomId = if (normalizedId == currentRoomId) null else normalizedId
        currentRoomId = normalizedId
        walkedIntoTiles.add(playerTile)
        roomTiles?.forEach { tile ->
            if (tile != null && validTile(tile)) walkedIntoTiles.add(tile)
        }
        return snapshot(enteredRoomId)
    }

    fun snapshot(): OdinDungeonRoomLifecycle.Snapshot = snapshot(null)

    fun reset() {
        level = null
        clearSession()
    }

    private fun clearSession() {
        dungeonActive = false
        bossPhase = false
        currentRoomId = null
        walkedIntoTiles.clear()
    }

    private fun snapshot(enteredRoomId: String?): OdinDungeonRoomLifecycle.Snapshot =
        OdinDungeonRoomLifecycle.Snapshot(
            dungeonActive,
            bossPhase,
            currentRoomId,
            enteredRoomId,
            java.util.Set.copyOf(walkedIntoTiles),
        )

    companion object {
        /** Stable map-room key from zero-based 6x6 tile indexes. */
        @JvmStatic
        fun roomId(tileIndices: List<Int?>?): String {
            if (tileIndices.isNullOrEmpty()) return ""
            val valid = TreeSet<Int>()
            for (index in tileIndices) {
                if (index != null && validTile(index)) valid.add(index)
            }
            if (valid.isEmpty()) return ""
            return "cells:" + valid.joinToString(",")
        }

        private fun validTile(tile: Int): Boolean = tile >= 0 && tile < 36
    }
}
