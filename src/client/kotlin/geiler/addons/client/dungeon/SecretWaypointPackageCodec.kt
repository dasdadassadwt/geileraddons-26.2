package geiler.addons.client.dungeon

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmSuppressWildcards

/** Java record and static API for the versioned, bounded Secret Waypoint package codec. */
class SecretWaypointPackageCodec private constructor() {
    @JvmRecord
    @JvmSuppressWildcards
    data class DecodeResult(
        val success: Boolean,
        val personal: List<SecretWaypointStore.Waypoint?>?,
        val overrides: List<SecretWaypointStore.WaypointOverride?>?,
        val collected: List<SecretWaypointStore.CollectedWaypoint?>?,
        val error: String?,
    ) {
        override fun toString(): String =
            "DecodeResult[success=$success, personal=$personal, overrides=$overrides, " +
                "collected=$collected, error=$error]"
    }

    companion object {
        @JvmStatic
        @JvmSuppressWildcards
        fun encode(
            personal: List<SecretWaypointStore.Waypoint?>?,
            overrides: List<SecretWaypointStore.WaypointOverride?>?,
            collected: List<SecretWaypointStore.CollectedWaypoint?>?,
        ): String? = SecretWaypointPackageCodecLogic.encode(personal, overrides, collected)

        @JvmStatic
        fun decode(encoded: String?): DecodeResult = SecretWaypointPackageCodecLogic.decode(encoded)

        @JvmStatic
        fun validWaypoint(
            waypoint: SecretWaypointStore.Waypoint?,
            requireRoomCoordinates: Boolean,
        ): Boolean = SecretWaypointPackageCodecLogic.validWaypoint(waypoint, requireRoomCoordinates)

        @JvmStatic
        fun validRoomId(roomId: String?): Boolean = SecretWaypointPackageCodecLogic.validRoomId(roomId)

        @JvmStatic
        fun matchesRoom(roomId: String?, x: Int, z: Int): Boolean =
            SecretWaypointPackageCodecLogic.matchesRoom(roomId, x, z)
    }
}

// Keep existing Kotlin store callers' Java-record accessor syntax source-compatible.
internal fun SecretWaypointPackageCodec.DecodeResult.success(): Boolean = this.success

internal fun SecretWaypointPackageCodec.DecodeResult.personal(): List<SecretWaypointStore.Waypoint> {
    val values = this.personal ?: throw NullPointerException()
    @Suppress("UNCHECKED_CAST")
    return values as List<SecretWaypointStore.Waypoint>
}

internal fun SecretWaypointPackageCodec.DecodeResult.overrides(): List<SecretWaypointStore.WaypointOverride> {
    val values = this.overrides ?: throw NullPointerException()
    @Suppress("UNCHECKED_CAST")
    return values as List<SecretWaypointStore.WaypointOverride>
}

internal fun SecretWaypointPackageCodec.DecodeResult.collected(): List<SecretWaypointStore.CollectedWaypoint> {
    val values = this.collected ?: throw NullPointerException()
    @Suppress("UNCHECKED_CAST")
    return values as List<SecretWaypointStore.CollectedWaypoint>
}

internal fun SecretWaypointPackageCodec.DecodeResult.error(): String? = this.error
