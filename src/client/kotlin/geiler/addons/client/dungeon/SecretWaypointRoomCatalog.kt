package geiler.addons.client.dungeon

import kotlin.jvm.JvmName
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards
import kotlin.jvm.JvmSynthetic

/** Room-relative marker transformation and IDs for the pinned Devonian coordinate resource. */
@JvmSuppressWildcards
class SecretWaypointRoomCatalog private constructor() {
    @JvmRecord
    data class RoomInstanceIdentity(
        val floor: DungeonFloor?,
        val anchorRoomId: String?,
        val templateRecordId: String?,
    ) {
        @JvmSynthetic @JvmName("floorKotlinCompat") fun floor(): DungeonFloor = floor!!
        @JvmSynthetic @JvmName("anchorRoomIdKotlinCompat") fun anchorRoomId(): String = anchorRoomId.orEmpty()
        @JvmSynthetic @JvmName("templateRecordIdKotlinCompat") fun templateRecordId(): String = templateRecordId.orEmpty()

        override fun toString(): String =
            "RoomInstanceIdentity[floor=$floor, anchorRoomId=$anchorRoomId, templateRecordId=$templateRecordId]"
    }

    companion object {
        /**
         * Converts only the exact named room's canonical coordinates. Invalid coordinates outside the
         * detected physical footprint are dropped instead of being attached to a neighboring room.
         */
        @JvmStatic
        fun materialize(
            floor: String?,
            roomCells: List<DungeonGridRules.Cell?>?,
            roomName: String?,
            orientation: DungeonRoomOrientationRules.Candidate?,
            templates: List<DungeonWaypointCatalog.Marker?>?,
        ): List<SecretWaypointStore.Waypoint> =
            SecretWaypointRoomCatalogLogic.materialize(floor, roomCells, roomName, orientation, templates)

        @JvmStatic
        fun instanceMarkerId(floor: String?, anchorX: Int, anchorZ: Int, templateId: String?): String? =
            SecretWaypointRoomCatalogLogic.instanceMarkerId(floor, anchorX, anchorZ, templateId)

        @JvmStatic
        fun isRoomInstanceMarkerId(id: String?): Boolean =
            SecretWaypointRoomCatalogLogic.isRoomInstanceMarkerId(id)

        @JvmStatic
        fun roomInstanceIdentity(id: String?): RoomInstanceIdentity? =
            SecretWaypointRoomCatalogLogic.roomInstanceIdentity(id)

        /** Category encoded in a bundled marker ID, retained when a local override changes its label. */
        @JvmStatic
        fun markerCategory(id: String?): String? = SecretWaypointRoomCatalogLogic.markerCategory(id)
    }
}
