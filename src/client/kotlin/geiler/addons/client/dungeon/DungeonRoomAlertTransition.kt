package geiler.addons.client.dungeon

/** Separates the first clear mark from a later all-secrets mark on the dungeon map. */
class DungeonRoomAlertTransition private constructor() {
    @JvmRecord
    data class Events(val roomCleared: Boolean, val secretsComplete: Boolean) {
        companion object {
            @JvmField
            val NONE = Events(false, false)
        }
    }

    companion object {
        @JvmStatic
        fun between(
            previous: DungeonMapGeometry.RoomClearStatus?,
            current: DungeonMapGeometry.RoomClearStatus?,
        ): Events {
            if (previous == null || current == null) return Events.NONE
            val secretsComplete = current == DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN &&
                previous != DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN
            val roomCleared = current.cleared() && !previous.cleared()
            return Events(roomCleared, secretsComplete)
        }

        /** An initial observation establishes the room's current state; it is not a completion event. */
        @JvmStatic
        fun firstObservation(current: DungeonMapGeometry.RoomClearStatus?): Events = Events.NONE
    }
}
