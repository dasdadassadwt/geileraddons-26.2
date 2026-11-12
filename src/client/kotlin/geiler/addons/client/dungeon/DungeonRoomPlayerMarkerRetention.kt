package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.odin.OdinDungeonPlayerMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

/** Java-callable, stateful bounded retention of confirmed teammate map positions. */
class DungeonRoomPlayerMarkerRetention {
    companion object {
        const val MAX_AGE_TICKS: Int = 40
    }

    private val logic = RetentionCore()

    fun merge(
        current: List<DungeonRoomOverlayGeometry.PlayerMarker?>?,
        roster: List<OdinDungeonPlayerMap.TabPlayer?>?,
        tick: Long,
    ): Result = logic.merge(current, roster, tick)

    fun clear() = logic.clear()

    class Result(
        players: List<DungeonRoomOverlayGeometry.PlayerMarker>?,
        confirmedRemote: Int,
        retainedRemote: Int,
        appendedRemote: Int,
        maximumRetainedAgeTicks: Int,
        expiredRemote: Int,
        deadRemoved: Int,
        staleRosterRemoved: Int,
    ) {
        private val playersValue = RetentionCore.copyPlayers(players)
        private val confirmedRemoteValue = RetentionCore.bounded(confirmedRemote)
        private val retainedRemoteValue = RetentionCore.bounded(retainedRemote)
        private val appendedRemoteValue = RetentionCore.bounded(appendedRemote)
        private val maximumRetainedAgeTicksValue = RetentionCore.boundedAge(maximumRetainedAgeTicks)
        private val expiredRemoteValue = RetentionCore.bounded(expiredRemote)
        private val deadRemovedValue = RetentionCore.bounded(deadRemoved)
        private val staleRosterRemovedValue = RetentionCore.bounded(staleRosterRemoved)

        fun players(): List<DungeonRoomOverlayGeometry.PlayerMarker> = playersValue
        fun confirmedRemote(): Int = confirmedRemoteValue
        fun retainedRemote(): Int = retainedRemoteValue
        fun appendedRemote(): Int = appendedRemoteValue
        fun maximumRetainedAgeTicks(): Int = maximumRetainedAgeTicksValue
        fun expiredRemote(): Int = expiredRemoteValue
        fun deadRemoved(): Int = deadRemovedValue
        fun staleRosterRemoved(): Int = staleRosterRemovedValue
        fun diagnostics(): String = RetentionCore.diagnostics(this)

        override fun equals(other: Any?): Boolean = other is Result &&
            recordEquals(
                arrayOf(
                    playersValue, confirmedRemoteValue, retainedRemoteValue, appendedRemoteValue,
                    maximumRetainedAgeTicksValue, expiredRemoteValue, deadRemovedValue, staleRosterRemovedValue,
                ),
                arrayOf(
                    other.playersValue, other.confirmedRemoteValue, other.retainedRemoteValue,
                    other.appendedRemoteValue, other.maximumRetainedAgeTicksValue, other.expiredRemoteValue,
                    other.deadRemovedValue, other.staleRosterRemovedValue,
                ),
            )

        override fun hashCode(): Int = recordHash(
            playersValue, confirmedRemoteValue, retainedRemoteValue, appendedRemoteValue,
            maximumRetainedAgeTicksValue, expiredRemoteValue, deadRemovedValue, staleRosterRemovedValue,
        )

        override fun toString(): String = recordToString(
            "Result", "players" to playersValue, "confirmedRemote" to confirmedRemoteValue,
            "retainedRemote" to retainedRemoteValue, "appendedRemote" to appendedRemoteValue,
            "maximumRetainedAgeTicks" to maximumRetainedAgeTicksValue, "expiredRemote" to expiredRemoteValue,
            "deadRemoved" to deadRemovedValue, "staleRosterRemoved" to staleRosterRemovedValue,
        )
    }

private class RetentionCore {
    private val markers = LinkedHashMap<String, RetainedMarker>()

    fun merge(
        current: List<DungeonRoomOverlayGeometry.PlayerMarker?>?,
        roster: List<OdinDungeonPlayerMap.TabPlayer?>?,
        tick: Long,
    ): DungeonRoomPlayerMarkerRetention.Result {
        val observed: List<DungeonRoomOverlayGeometry.PlayerMarker?> = current ?: emptyList()
        val currentRoster: List<OdinDungeonPlayerMap.TabPlayer?> = roster ?: emptyList()
        val living = LinkedHashSet<String>()
        val dead = LinkedHashSet<String>()
        for (player in currentRoster) {
            if (player == null) continue
            val key = key(player.name())
            if (key.isEmpty()) continue
            if (player.dead()) dead.add(key) else living.add(key)
        }

        var deadRemoved = 0
        var staleRosterRemoved = 0
        var expired = 0
        val iterator = markers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val age = age(tick, entry.value.tick)
            when {
                dead.contains(entry.key) -> {
                    iterator.remove()
                    deadRemoved++
                }
                currentRoster.isNotEmpty() && !living.contains(entry.key) -> {
                    iterator.remove()
                    staleRosterRemoved++
                }
                age > DungeonRoomPlayerMarkerRetention.MAX_AGE_TICKS -> {
                    iterator.remove()
                    expired++
                }
            }
        }

        val merged = LinkedHashMap<String, DungeonRoomOverlayGeometry.PlayerMarker>()
        var confirmedRemote = 0
        for (marker in observed) {
            val observedMarker = marker ?: continue
            if (!valid(observedMarker)) continue
            val nameKey = key(observedMarker.name())
            if (nameKey.isEmpty()) continue
            if (!observedMarker.localPlayer()) {
                if (dead.contains(nameKey) || currentRoster.isNotEmpty() && !living.contains(nameKey)) continue
                markers[nameKey] = RetainedMarker(observedMarker, tick)
                confirmedRemote++
            }
            merged[nameKey] = observedMarker
        }
        trimToBound()

        var appended = 0
        var maximumRetainedAge = 0
        for ((key, retained) in markers) {
            if (merged.containsKey(key)) continue
            if (dead.contains(key) || currentRoster.isNotEmpty() && !living.contains(key)) continue
            val age = age(tick, retained.tick)
            if (age > DungeonRoomPlayerMarkerRetention.MAX_AGE_TICKS) continue
            merged[key] = retained.marker
            appended++
            maximumRetainedAge = maxOf(maximumRetainedAge, age)
        }
        return DungeonRoomPlayerMarkerRetention.Result(
            java.util.List.copyOf(merged.values), confirmedRemote, markers.size, appended,
            maximumRetainedAge, expired, deadRemoved, staleRosterRemoved,
        )
    }

    fun clear() {
        markers.clear()
    }

    private fun trimToBound() {
        while (markers.size > MAX_MARKERS) {
            var oldestKey: String? = null
            var oldestTick = Long.MAX_VALUE
            for ((key, retained) in markers) {
                if (retained.tick < oldestTick) {
                    oldestKey = key
                    oldestTick = retained.tick
                }
            }
            if (oldestKey == null) return
            markers.remove(oldestKey)
        }
    }

    private fun valid(marker: DungeonRoomOverlayGeometry.PlayerMarker?): Boolean =
        marker != null && marker.x() >= 0 && marker.z() >= 0 &&
            marker.x() < DungeonRoomOverlayLayout.GRID_CELLS &&
            marker.z() < DungeonRoomOverlayLayout.GRID_CELLS

    private fun age(tick: Long, seenTick: Long): Int =
        if (tick < seenTick) DungeonRoomPlayerMarkerRetention.MAX_AGE_TICKS + 1
        else minOf(Int.MAX_VALUE.toLong(), tick - seenTick).toInt()

    private fun key(name: String?): String =
        name?.trim { Character.isWhitespace(it) }?.lowercase(Locale.ROOT) ?: ""

    private data class RetainedMarker(
        val marker: DungeonRoomOverlayGeometry.PlayerMarker,
        val tick: Long,
    )

    companion object {
        private const val MAX_MARKERS = 16

        fun copyPlayers(players: List<DungeonRoomOverlayGeometry.PlayerMarker>?): List<DungeonRoomOverlayGeometry.PlayerMarker> =
            java.util.List.copyOf(players ?: emptyList())

        fun bounded(value: Int): Int = value.coerceIn(0, MAX_MARKERS)

        fun boundedAge(value: Int): Int = value.coerceIn(0, DungeonRoomPlayerMarkerRetention.MAX_AGE_TICKS)

        fun diagnostics(result: DungeonRoomPlayerMarkerRetention.Result): String =
            "confirmed=${result.confirmedRemote()},cached=${result.retainedRemote()},appended=${result.appendedRemote()}," +
                "maxAgeTicks=${result.maximumRetainedAgeTicks()},expired=${result.expiredRemote()}," +
                "deadRemoved=${result.deadRemoved()},staleRosterRemoved=${result.staleRosterRemoved()}"
    }

}
}

private fun recordEquals(first: Array<out Any?>, second: Array<out Any?>): Boolean = first.contentEquals(second)
private fun recordHash(vararg values: Any?): Int =
    values.fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
private fun recordToString(type: String, vararg components: Pair<String, Any?>): String =
    components.joinToString(prefix = "$type[", postfix = "]") { (name, value) -> "$name=$value" }

