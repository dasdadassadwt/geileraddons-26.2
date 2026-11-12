package geiler.addons.client.dungeon

import java.util.ArrayList
import kotlin.jvm.JvmField
import kotlin.jvm.JvmName
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards
import kotlin.jvm.JvmSynthetic
import kotlin.jvm.Synchronized

/** Bundled waypoint defaults plus a separate local file for personal waypoints, edits, and collection state. */
@JvmSuppressWildcards
class SecretWaypointStore private constructor() {
    @JvmRecord
    data class Waypoint(
        val id: String?,
        val floor: String?,
        val roomId: String?,
        val name: String?,
        val x: Int,
        val y: Int,
        val z: Int,
        val color: Int,
    ) {
        @JvmSynthetic @JvmName("idKotlinCompat") fun id(): String = id.orEmpty()
        @JvmSynthetic @JvmName("floorKotlinCompat") fun floor(): String = floor.orEmpty()
        @JvmSynthetic @JvmName("roomIdKotlinCompat") fun roomId(): String = roomId.orEmpty()
        @JvmSynthetic @JvmName("nameKotlinCompat") fun name(): String = name.orEmpty()
        @JvmSynthetic @JvmName("xKotlinCompat") fun x(): Int = x
        @JvmSynthetic @JvmName("yKotlinCompat") fun y(): Int = y
        @JvmSynthetic @JvmName("zKotlinCompat") fun z(): Int = z
        @JvmSynthetic @JvmName("colorKotlinCompat") fun color(): Int = color

        override fun toString(): String =
            "Waypoint[id=$id, floor=$floor, roomId=$roomId, name=$name, x=$x, y=$y, z=$z, color=$color]"
    }

    /** Immutable pre-transaction view of all runtime state. */
    @JvmSuppressWildcards
    class RuntimeSnapshot(
        defaults: List<Waypoint>?,
        roomTemplates: List<DevonianDungeonWaypointCatalog.Marker>?,
        runtimeDefaults: List<Waypoint>?,
        personal: List<Waypoint>?,
        overrides: Map<String, WaypointOverride>?,
        collected: List<CollectedWaypoint>?,
        catalogStatus: String?,
        persistenceBlocked: Boolean,
        loaded: Boolean,
    ) {
        private val defaultsValue: List<Waypoint> = java.util.List.copyOf(defaults)
        private val roomTemplatesValue: List<DevonianDungeonWaypointCatalog.Marker> =
            java.util.List.copyOf(roomTemplates)
        private val runtimeDefaultsValue: List<Waypoint> = java.util.List.copyOf(runtimeDefaults)
        private val personalValue: List<Waypoint> = java.util.List.copyOf(personal)
        private val overridesValue: Map<String, WaypointOverride> = java.util.Map.copyOf(overrides)
        private val collectedValue: List<CollectedWaypoint> = java.util.List.copyOf(collected)
        private val catalogStatusValue = catalogStatus
        private val persistenceBlockedValue = persistenceBlocked
        private val loadedValue = loaded

        fun defaults(): List<Waypoint> = defaultsValue
        fun roomTemplates(): List<DevonianDungeonWaypointCatalog.Marker> = roomTemplatesValue
        fun runtimeDefaults(): List<Waypoint> = runtimeDefaultsValue
        fun personal(): List<Waypoint> = personalValue
        fun overrides(): Map<String, WaypointOverride> = overridesValue
        fun collected(): List<CollectedWaypoint> = collectedValue

        fun catalogStatus(): String? = catalogStatusValue

        fun persistenceBlocked(): Boolean = persistenceBlockedValue
        fun loaded(): Boolean = loadedValue

        override fun equals(other: Any?): Boolean = other is RuntimeSnapshot &&
            defaultsValue == other.defaultsValue && roomTemplatesValue == other.roomTemplatesValue &&
            runtimeDefaultsValue == other.runtimeDefaultsValue && personalValue == other.personalValue &&
            overridesValue == other.overridesValue && collectedValue == other.collectedValue &&
            catalogStatusValue == other.catalogStatusValue &&
            persistenceBlockedValue == other.persistenceBlockedValue && loadedValue == other.loadedValue

        override fun hashCode(): Int {
            var result = defaultsValue.hashCode()
            result = 31 * result + roomTemplatesValue.hashCode()
            result = 31 * result + runtimeDefaultsValue.hashCode()
            result = 31 * result + personalValue.hashCode()
            result = 31 * result + overridesValue.hashCode()
            result = 31 * result + collectedValue.hashCode()
            result = 31 * result + (catalogStatusValue?.hashCode() ?: 0)
            result = 31 * result + java.lang.Boolean.hashCode(persistenceBlockedValue)
            result = 31 * result + java.lang.Boolean.hashCode(loadedValue)
            return result
        }

        override fun toString(): String =
            "RuntimeSnapshot[defaults=$defaultsValue, roomTemplates=$roomTemplatesValue, " +
                "runtimeDefaults=$runtimeDefaultsValue, personal=$personalValue, overrides=$overridesValue, " +
                "collected=$collectedValue, catalogStatus=$catalogStatusValue, " +
                "persistenceBlocked=$persistenceBlockedValue, loaded=$loadedValue]"
    }

    @JvmRecord
    data class WaypointOverride(val id: String?, val hidden: Boolean, val replacement: Waypoint?) {
        @JvmSynthetic @JvmName("idKotlinCompat") fun id(): String = id.orEmpty()
        @JvmSynthetic @JvmName("hiddenKotlinCompat") fun hidden(): Boolean = hidden
        @JvmSynthetic @JvmName("replacementKotlinCompat") fun replacement(): Waypoint? = replacement

        override fun toString(): String = "WaypointOverride[id=$id, hidden=$hidden, replacement=$replacement]"
    }

    @JvmRecord
    data class CollectedWaypoint(val floor: String?, val roomId: String?, val markerId: String?) {
        @JvmSynthetic @JvmName("floorKotlinCompat") fun floor(): String = floor.orEmpty()
        @JvmSynthetic @JvmName("roomIdKotlinCompat") fun roomId(): String = roomId.orEmpty()
        @JvmSynthetic @JvmName("markerIdKotlinCompat") fun markerId(): String = markerId.orEmpty()

        override fun toString(): String = "CollectedWaypoint[floor=$floor, roomId=$roomId, markerId=$markerId]"
    }

    @JvmRecord
    data class EditableWaypoint(val waypoint: Waypoint?, val personal: Boolean, val hidden: Boolean, val collected: Boolean) {
        @JvmSynthetic @JvmName("waypointKotlinCompat") fun waypoint(): Waypoint = waypoint!!
        @JvmSynthetic @JvmName("personalKotlinCompat") fun personal(): Boolean = personal
        @JvmSynthetic @JvmName("hiddenKotlinCompat") fun hidden(): Boolean = hidden
        @JvmSynthetic @JvmName("collectedKotlinCompat") fun collected(): Boolean = collected

        override fun toString(): String =
            "EditableWaypoint[waypoint=$waypoint, personal=$personal, hidden=$hidden, collected=$collected]"
    }

    @JvmRecord
    data class ImportResult(
        val success: Boolean,
        val personal: Int,
        val overrides: Int,
        val collected: Int,
        val error: String?,
    ) {
        @JvmSynthetic @JvmName("successKotlinCompat") fun success(): Boolean = success
        @JvmSynthetic @JvmName("personalKotlinCompat") fun personal(): Int = personal
        @JvmSynthetic @JvmName("overridesKotlinCompat") fun overrides(): Int = overrides
        @JvmSynthetic @JvmName("collectedKotlinCompat") fun collected(): Int = collected
        @JvmSynthetic @JvmName("errorKotlinCompat") fun error(): String? = error

        override fun toString(): String =
            "ImportResult[success=$success, personal=$personal, overrides=$overrides, collected=$collected, error=$error]"

        companion object {
            @JvmStatic
            @JvmName("failure")
            internal fun failure(error: String?): ImportResult = ImportResult(false, 0, 0, 0, error)
        }
    }

    class BundledFile {
        @JvmField var version: Int = 1
        @JvmField var waypoints: MutableList<Waypoint> = ArrayList()
    }

    class LocalFile {
        @JvmField var version: Int = 2
        @JvmField var personal: MutableList<Waypoint> = ArrayList()
        @JvmField var overrides: MutableList<WaypointOverride> = ArrayList()
        @JvmField var collected: MutableList<CollectedWaypoint> = ArrayList()
    }

    companion object {
        @JvmStatic @Synchronized fun load() = SecretWaypointStoreCore.load()
        @JvmStatic @Synchronized fun reloadFromDisk(): Boolean = SecretWaypointStoreCore.reloadFromDisk()
        @JvmStatic @Synchronized fun snapshotRuntimeState(): RuntimeSnapshot =
            SecretWaypointStoreCore.snapshotRuntimeState()

        /** Pauses local edits while a profile transaction replaces this file. */
        @JvmStatic @Synchronized fun beginProfileTransaction() = SecretWaypointStoreCore.beginProfileTransaction()
        @JvmStatic @Synchronized fun endProfileTransaction() = SecretWaypointStoreCore.endProfileTransaction()

        /** Restores a pre-transaction cache when an installed profile could not be applied or recovered. */
        @JvmStatic @Synchronized fun restoreRuntimeState(snapshot: RuntimeSnapshot?) =
            SecretWaypointStoreCore.restoreRuntimeState(snapshot)

        @JvmStatic fun defaults(): List<Waypoint> = SecretWaypointStoreCore.defaults()
        @JvmStatic fun personal(): List<Waypoint> = SecretWaypointStoreCore.personal()
        @JvmStatic fun roomTemplates(): List<DevonianDungeonWaypointCatalog.Marker> =
            SecretWaypointStoreCore.roomTemplates()
        @JvmStatic fun catalogStatus(): String? = SecretWaypointStoreCore.catalogStatus()
        @JvmStatic fun canPersistChanges(): Boolean = SecretWaypointStoreCore.canPersistChanges()

        /** Replaces the bounded, current-room materialized catalog view; local saved edits remain authoritative. */
        @JvmStatic @Synchronized fun setRuntimeDefaults(values: List<Waypoint>?) =
            SecretWaypointStoreCore.setRuntimeDefaults(values)

        @JvmStatic fun visibleFor(floor: String?, roomId: String?): List<Waypoint> =
            SecretWaypointStoreCore.visibleFor(floor, roomId)

        /** All room markers for editing, including hidden defaults and markers currently marked collected. */
        @JvmStatic fun editableForRooms(floor: String?, roomIds: List<String>?): List<EditableWaypoint> =
            SecretWaypointStoreCore.editableForRooms(floor, roomIds)

        @JvmStatic @Synchronized fun addPersonal(
            floor: String?, roomId: String?, name: String?, x: Int, y: Int, z: Int, color: Int,
        ): String? = SecretWaypointStoreCore.addPersonal(floor, roomId, name, x, y, z, color)

        @JvmStatic @Synchronized fun renamePersonal(id: String?, name: String?): Boolean =
            SecretWaypointStoreCore.renamePersonal(id, name)

        @JvmStatic @Synchronized fun updatePersonal(
            id: String?, floor: String?, roomId: String?, name: String?, x: Int, y: Int, z: Int, color: Int,
        ): Boolean = SecretWaypointStoreCore.updatePersonal(id, floor, roomId, name, x, y, z, color)

        @JvmStatic @Synchronized fun removePersonal(id: String?): Boolean = SecretWaypointStoreCore.removePersonal(id)
        @JvmStatic @Synchronized fun hideDefault(id: String?): Boolean = SecretWaypointStoreCore.hideDefault(id)

        @JvmStatic @Synchronized fun replaceDefault(id: String?, replacement: Waypoint?): Boolean =
            SecretWaypointStoreCore.replaceDefault(id, replacement)

        @JvmStatic @Synchronized fun restoreDefault(id: String?): Boolean = SecretWaypointStoreCore.restoreDefault(id)

        /** Restores only bundled defaults in the selected rooms; personal markers and collection history remain. */
        @JvmStatic @Synchronized fun resetRoomOverrides(floor: String?, roomIds: List<String>?): Int =
            SecretWaypointStoreCore.resetRoomOverrides(floor, roomIds)

        @JvmStatic fun isCollected(floor: String?, roomId: String?, markerId: String?): Boolean =
            SecretWaypointStoreCore.isCollected(floor, roomId, markerId)

        /** Records all authored markers in a room only after the map has confirmed its green clear state. */
        @JvmStatic @Synchronized fun markRoomsCollected(floor: String?, roomIds: List<String>?): Int =
            SecretWaypointStoreCore.markRoomsCollected(floor, roomIds)

        @JvmStatic fun exportPackage(): String? = SecretWaypointStoreCore.exportPackage()

        /** Merges a validated package; colliding personal IDs are remapped without replacing local markers. */
        @JvmStatic @Synchronized fun importPackage(encoded: String?): ImportResult =
            SecretWaypointStoreCore.importPackage(encoded)
    }
}
