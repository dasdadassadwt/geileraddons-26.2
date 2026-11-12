package geiler.addons.client.dungeon

import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** Stable Java API for the Kotlin collection state machine: run-scoped, marker-level secret collection state and chest rollback timing. */
class SecretWaypointCollectionTracker {
    private val collected: MutableSet<String> = LinkedHashSet()
    private val pendingBlocks: MutableMap<String, PendingBlock> = LinkedHashMap()
    private var activeRunKey: String? = null
    private var tick = 0
    private var chestClickBlockedUntil = 0

    /** Starts a fresh observed collection set when the world, dungeon map, or floor changes. */
    fun beginRun(nextRunKey: String?) {
        if (activeRunKey == nextRunKey) return
        activeRunKey = nextRunKey
        tick = 0
        chestClickBlockedUntil = 0
        collected.clear()
        pendingBlocks.clear()
    }

    /** Advances pending click windows; an unconfirmed interaction expires back to visible. */
    fun tick(): List<String> {
        tick++
        val iterator = pendingBlocks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (tick < entry.value.deadlineTick) continue
            iterator.remove()
        }
        return java.util.List.of()
    }

    /** Hides one exact clicked chest or lever while awaiting its server confirmation. */
    fun beginBlockInteraction(
        markerKey: String?,
        type: SecretWaypointClickFeedbackRules.Type?,
        confirmationWindowTicks: Int,
    ): Boolean {
        if (markerKey == null || markerKey.isBlank() || type == null ||
            (type != SecretWaypointClickFeedbackRules.Type.CHEST &&
                type != SecretWaypointClickFeedbackRules.Type.LEVER) ||
            collected.contains(markerKey)
        ) {
            return false
        }
        if (type == SecretWaypointClickFeedbackRules.Type.CHEST && tick < chestClickBlockedUntil) return false
        val timeout = maxOf(1, confirmationWindowTicks)
        pendingBlocks[markerKey] = PendingBlock(type, tick + timeout, false)
        return true
    }

    /** Compatibility default retained for callers that do not yet pass the configured window. */
    fun onLockedChestMessage(): String? =
        onLockedChestMessage(SecretWaypointCollectionTracker.LOCKED_CHEST_RESTORE_TICKS)

    /** A server's exact locked-chest line restores the pending marker after the confirmation window. */
    fun onLockedChestMessage(confirmationWindowTicks: Int): String? {
        var newestChestKey: String? = null
        for ((key, pending) in pendingBlocks) {
            if (pending.type == SecretWaypointClickFeedbackRules.Type.CHEST && !pending.locked) {
                newestChestKey = key
            }
        }
        if (newestChestKey == null) return null
        val restoreTicks = maxOf(1, confirmationWindowTicks)
        pendingBlocks[newestChestKey] = PendingBlock(
            SecretWaypointClickFeedbackRules.Type.CHEST,
            tick + restoreTicks,
            true,
        )
        chestClickBlockedUntil = tick + restoreTicks
        return newestChestKey
    }

    /** Confirms the latest source-recognized chest/lever interaction from the server's secret message. */
    fun confirmLatestBlockInteraction(): String? {
        var newestKey: String? = null
        for ((key, pending) in pendingBlocks) {
            if (!pending.locked) newestKey = key
        }
        if (newestKey == null) return null
        pendingBlocks.remove(newestKey)
        collected.add(newestKey)
        return newestKey
    }

    /** Confirms a pending block marker only after its exact server block event/update was observed. */
    fun confirmPendingBlockInteraction(markerKey: String?): Boolean {
        if (markerKey == null || markerKey.isBlank()) return false
        val pending = pendingBlocks[markerKey] ?: return false
        if (pending.locked) return false
        pendingBlocks.remove(markerKey)
        return collected.add(markerKey)
    }

    /** Confirms one marker only after an explicit pickup/death or room-clear signal. */
    fun confirm(markerKey: String?): Boolean {
        if (markerKey == null || markerKey.isBlank()) return false
        pendingBlocks.remove(markerKey)
        return collected.add(markerKey)
    }

    fun isCollected(markerKey: String?): Boolean = markerKey != null && collected.contains(markerKey)

    fun isPending(markerKey: String?): Boolean = markerKey != null && pendingBlocks.containsKey(markerKey)

    fun runKey(): String? = activeRunKey

    fun currentTick(): Int = tick

    private data class PendingBlock(
        val type: SecretWaypointClickFeedbackRules.Type,
        val deadlineTick: Int,
        val locked: Boolean,
    )

    companion object {
        const val LOCKED_CHEST_RESTORE_TICKS = 11
    }
}
