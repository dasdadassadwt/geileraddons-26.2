package geiler.addons.client.dungeon

import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** Tracks stable room-map transitions while tolerating brief gaps and changing discovered footprints. */
class DungeonRoomAlertTracker {
    private val rooms = LinkedHashMap<Long, RoomState>()
    private var nextRoomId = 0L
    private var candidate: Candidate? = null

    /**
     * Requires five identical valid samples before committing a map state. A footprint can grow as
     * map data becomes available; overlapping cells retain the earlier room state across that change.
     */
    fun observe(
        footprint: Collection<String?>?,
        status: DungeonMapGeometry.RoomClearStatus?,
    ): Decision {
        if (status == null || status == DungeonMapGeometry.RoomClearStatus.UNKNOWN ||
            footprint.isNullOrEmpty()
        ) {
            candidate = null
            return Decision(DungeonRoomAlertTransition.Events.NONE, "unknown", 0)
        }

        val cells = LinkedHashSet<String>()
        for (cell in footprint) {
            if (cell == null || cell.isBlank()) continue
            cells.add(cell)
            if (cells.size >= MAX_FOOTPRINT_CELLS) break
        }
        if (cells.isEmpty()) {
            candidate = null
            return Decision(DungeonRoomAlertTransition.Events.NONE, "unknown", 0)
        }

        val existingCandidate = candidate
        if (existingCandidate == null || existingCandidate.status != status || existingCandidate.cells != cells) {
            candidate = Candidate(immutableSet(cells), status)
        }
        val activeCandidate = candidate!!
        if (activeCandidate.committed) {
            return Decision(DungeonRoomAlertTransition.Events.NONE, "stable", STABLE_OBSERVATIONS)
        }
        activeCandidate.observations++
        if (activeCandidate.observations < STABLE_OBSERVATIONS) {
            return Decision(DungeonRoomAlertTransition.Events.NONE, "warming", activeCandidate.observations)
        }
        activeCandidate.committed = true
        return commit(cells, status, STABLE_OBSERVATIONS)
    }

    private fun commit(
        cells: Set<String>,
        status: DungeonMapGeometry.RoomClearStatus,
        observations: Int,
    ): Decision {
        val matches = ArrayList<Long>()
        for ((id, state) in rooms) {
            if (overlaps(state.cells, cells)) matches.add(id)
        }
        if (matches.isEmpty()) {
            putRoom(RoomState(cells, status))
            return Decision(DungeonRoomAlertTransition.Events.NONE, "first-observation", observations)
        }
        if (matches.size > 1) {
            val mergedCells = LinkedHashSet(cells)
            var roomClearedAlerted = false
            var secretsCompleteAlerted = false
            var incompleteHistory = false
            for (id in matches) {
                val matched = rooms.remove(id) ?: continue
                mergedCells.addAll(matched.cells)
                roomClearedAlerted = roomClearedAlerted || matched.roomClearedAlerted
                secretsCompleteAlerted = secretsCompleteAlerted || matched.secretsCompleteAlerted
                incompleteHistory = incompleteHistory ||
                    (!matched.status.cleared() && !matched.roomClearedAlerted)
            }
            val roomCleared = status.cleared() && incompleteHistory && !roomClearedAlerted
            putRoom(
                RoomState(
                    mergedCells,
                    status,
                    roomClearedAlerted || status.cleared(),
                    secretsCompleteAlerted || status == DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN,
                ),
            )
            val events = DungeonRoomAlertTransition.Events(roomCleared, false)
            return Decision(
                events,
                if (roomCleared) "footprints-merged-room-cleared" else "footprints-merged",
                observations,
            )
        }

        val id = matches.first()
        val previous = rooms.remove(id)
        if (previous == null) {
            putRoom(RoomState(cells, status))
            return Decision(DungeonRoomAlertTransition.Events.NONE, "first-observation", observations)
        }
        val rawEvents = DungeonRoomAlertTransition.between(previous.status, status)
        val events = DungeonRoomAlertTransition.Events(
            rawEvents.roomCleared && !previous.roomClearedAlerted,
            rawEvents.secretsComplete && !previous.secretsCompleteAlerted,
        )
        val mergedCells = LinkedHashSet(previous.cells)
        mergedCells.addAll(cells)
        putRoom(
            RoomState(
                mergedCells,
                status,
                previous.roomClearedAlerted || status.cleared(),
                previous.secretsCompleteAlerted || status == DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN,
            ),
            id,
        )
        val disposition = when {
            events.roomCleared && events.secretsComplete -> "room-and-secrets-complete"
            events.roomCleared -> "room-cleared"
            events.secretsComplete -> "secrets-complete"
            rawEvents.roomCleared || rawEvents.secretsComplete -> "duplicate-latched"
            else -> "no-transition"
        }
        return Decision(events, disposition, observations)
    }

    private fun putRoom(state: RoomState) {
        putRoom(state, ++nextRoomId)
    }

    private fun putRoom(state: RoomState, id: Long) {
        rooms[id] = state
        while (rooms.size > MAX_ROOMS) {
            val oldest = rooms.keys.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    fun snapshot(): Map<String, DungeonMapGeometry.RoomClearStatus> {
        val result = LinkedHashMap<String, DungeonMapGeometry.RoomClearStatus>()
        for (state in rooms.values) {
            result[state.cells.sorted().joinToString(";")] = state.status
        }
        return java.util.Map.copyOf(result)
    }

    fun clear() {
        rooms.clear()
        candidate = null
    }

    @JvmRecord
    data class Decision(
        val events: DungeonRoomAlertTransition.Events,
        val disposition: String,
        val stableObservations: Int,
    )

    private class Candidate(
        val cells: Set<String>,
        val status: DungeonMapGeometry.RoomClearStatus,
        var observations: Int = 0,
        var committed: Boolean = false,
    )

    private class RoomState(
        cells: Collection<String>,
        val status: DungeonMapGeometry.RoomClearStatus,
        val roomClearedAlerted: Boolean = status.cleared(),
        val secretsCompleteAlerted: Boolean = status == DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN,
    ) {
        val cells: Set<String> = java.util.Set.copyOf(cells.take(MAX_FOOTPRINT_CELLS))
    }

    companion object {
        private const val MAX_ROOMS = 128
        private const val MAX_FOOTPRINT_CELLS = 36
        private const val STABLE_OBSERVATIONS = 5

        private fun immutableSet(cells: Collection<String>): Set<String> = java.util.Set.copyOf(cells)

        private fun overlaps(left: Set<String>, right: Set<String>): Boolean {
            val smaller = if (left.size <= right.size) left else right
            val larger = if (smaller === left) right else left
            for (cell in smaller) if (larger.contains(cell)) return true
            return false
        }
    }
}
