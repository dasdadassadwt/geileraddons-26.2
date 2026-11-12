package geiler.addons.client.dungeon

/** Rules for confirmed Secret Waypoint completion state. */
class SecretWaypointCollectionRules private constructor() {
    companion object {
        /** The map's green checkmark is the only current signal that confirms all room secrets were found. */
        @JvmStatic
        fun confirmsAllRoomSecrets(status: DungeonMapGeometry.RoomClearStatus?): Boolean =
            status == DungeonMapGeometry.RoomClearStatus.CLEARED_GREEN
    }
}
