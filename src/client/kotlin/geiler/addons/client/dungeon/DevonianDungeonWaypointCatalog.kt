package geiler.addons.client.dungeon

import java.util.HashMap
import java.util.List as JavaList
import java.util.Map as JavaMap
import java.util.Set as JavaSet
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Values and entrypoints for the separately licensed Devonian room-relative waypoint subset. */
class DevonianDungeonWaypointCatalog private constructor() {
    companion object {
        const val SOURCE_REVISION = "6e98846e02c4e1b676ab5c17424913b708ca07ac"

        private const val RESOURCE = "/data/geileraddons/devonian-dungeon-waypoints.json"

        @JvmField
        val CATEGORIES: Set<String> = JavaSet.of("chest", "item", "essence", "bat", "redstone", "lever")

        const val MAX_ROOM_ROWS = 128
        const val MAX_MARKERS_PER_CATEGORY_AND_ROOM = 64

        private val BUNDLED: CatalogResult =
            DevonianDungeonWaypointCatalogLogic.loadBundled(DevonianDungeonWaypointCatalog::class.java, RESOURCE)

        @JvmStatic
        fun bundled(): CatalogResult = BUNDLED

        /** Package-level parser retained as a Java-callable fixture seam. */
        @JvmStatic
        fun parse(json: String?): CatalogResult = DevonianDungeonWaypointCatalogLogic.parse(json)
    }

    @JvmRecord
    data class Marker(val id: String?, val roomName: String?, val category: String?, val x: Int, val y: Int, val z: Int)

    /** Snapshots and normalizes its former Java record components at construction. */
    class CatalogResult(
        private val availableValue: Boolean,
        markers: List<Marker?>?,
        private val roomCountValue: Int,
        byRoom: Map<String?, List<Marker?>?>?,
        reason: String?,
    ) {
        @Suppress("UNCHECKED_CAST")
        private val markersValue: List<Marker> =
            JavaList.copyOf(markers ?: emptyList()) as List<Marker>

        @Suppress("UNCHECKED_CAST")
        private val byRoomValue: Map<String, List<Marker>> = run {
            val safe = HashMap<String?, List<Marker>>()
            byRoom?.forEach { (key, value) ->
                safe[key] = JavaList.copyOf(value!!) as List<Marker>
            }
            JavaMap.copyOf(safe) as Map<String, List<Marker>>
        }
        private val reasonValue: String = reason ?: ""

        fun available(): Boolean = availableValue
        fun markers(): List<Marker> = markersValue
        fun roomCount(): Int = roomCountValue
        fun byRoom(): Map<String, List<Marker>> = byRoomValue
        fun reason(): String = reasonValue

        fun find(roomName: String?, category: String?): List<Marker> =
            DevonianDungeonWaypointCatalogLogic.find(this, roomName, category)

        override fun equals(other: Any?): Boolean = this === other || other is CatalogResult &&
            availableValue == other.availableValue && markersValue == other.markersValue &&
            roomCountValue == other.roomCountValue && byRoomValue == other.byRoomValue && reasonValue == other.reasonValue

        override fun hashCode(): Int {
            var result = availableValue.hashCode()
            result = 31 * result + markersValue.hashCode()
            result = 31 * result + roomCountValue
            result = 31 * result + byRoomValue.hashCode()
            result = 31 * result + reasonValue.hashCode()
            return result
        }

        override fun toString(): String = "CatalogResult[available=$availableValue, markers=$markersValue, " +
            "roomCount=$roomCountValue, byRoom=$byRoomValue, reason=$reasonValue]"
    }
}
