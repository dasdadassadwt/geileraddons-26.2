package geiler.addons.client.dungeon

import com.google.gson.JsonElement
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashSet
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Strict, bounded loader for the read-only bundled Secret Waypoint catalog. */
@JvmSuppressWildcards
class SecretWaypointCatalogCodec private constructor() {
    /** Java-record-compatible value with the original compact-constructor normalization. */
    class DecodeResult(
        success: Boolean,
        waypoints: List<SecretWaypointStore.Waypoint>?,
        error: String?,
    ) {
        private val successValue = success
        private val waypointsValue: List<SecretWaypointStore.Waypoint> =
            java.util.List.copyOf(waypoints ?: java.util.List.of())
        private val errorValue = error ?: ""

        fun success(): Boolean = successValue
        fun waypoints(): List<SecretWaypointStore.Waypoint> = waypointsValue
        fun error(): String = errorValue

        override fun equals(other: Any?): Boolean = other is DecodeResult &&
            successValue == other.successValue && waypointsValue == other.waypointsValue && errorValue == other.errorValue

        override fun hashCode(): Int {
            var result = java.lang.Boolean.hashCode(successValue)
            result = 31 * result + waypointsValue.hashCode()
            result = 31 * result + errorValue.hashCode()
            return result
        }

        override fun toString(): String =
            "DecodeResult[success=$successValue, waypoints=$waypointsValue, error=$errorValue]"
    }

    companion object {
        @JvmStatic
        fun decode(encoded: String?): SecretWaypointCatalogCodec.DecodeResult {
            if (encoded == null || encoded.length > ClientJsonFile.MAX_PACKAGE_BYTES ||
                encoded.toByteArray(StandardCharsets.UTF_8).size > ClientJsonFile.MAX_PACKAGE_BYTES
            ) {
                return failure("Bundled waypoint catalog is missing or too large.")
            }
            try {
                val parsed = BoundedDungeonJsonParser.parse(encoded, 8, 100_000)
                if (!parsed.isJsonObject) return failure("Bundled waypoint catalog must be an object.")
                val root = parsed.asJsonObject
                if (root.size() != 2 || !integer(root.get("version")) || root.get("version").asInt != 1 ||
                    !root.has("waypoints") || !root.get("waypoints").isJsonArray
                ) {
                    return failure("Bundled waypoint catalog has an unsupported schema.")
                }
                val rows = root.getAsJsonArray("waypoints")
                if (rows.size() > ClientJsonFile.MAX_ENTRIES) {
                    return failure("Bundled waypoint catalog has too many entries.")
                }
                val waypoints = ArrayList<SecretWaypointStore.Waypoint>(rows.size())
                val ids = HashSet<String>()
                for (row in rows) {
                    if (!waypointShape(row)) {
                        return failure("Bundled waypoint catalog contains an invalid entry shape.")
                    }
                    val waypoint: SecretWaypointStore.Waypoint? = ClientJsonFile.GSON.fromJson(
                        row,
                        SecretWaypointStore.Waypoint::class.java,
                    )
                    if (waypoint == null || !SecretWaypointPackageCodec.validWaypoint(waypoint, true)) {
                        return failure("Bundled waypoint catalog contains an invalid or duplicate marker.")
                    }
                    val floor = DungeonFloor.parse(waypoint.floor())
                    if (!waypoint.id().startsWith("catalog:") || waypoint.id().length == "catalog:".length ||
                        !ids.add(waypoint.id()) || floor == null || floor.displayName() != waypoint.floor()
                    ) {
                        return failure("Bundled waypoint catalog contains an invalid or duplicate marker.")
                    }
                    waypoints.add(waypoint)
                }
                return SecretWaypointCatalogCodec.DecodeResult(true, java.util.List.copyOf(waypoints), null)
            } catch (exception: IOException) {
                return failure("Bundled waypoint catalog is invalid.")
            } catch (exception: RuntimeException) {
                return failure("Bundled waypoint catalog is invalid.")
            }
        }

        private fun waypointShape(element: JsonElement?): Boolean {
            if (element == null || !element.isJsonObject) return false
            val waypoint = element.asJsonObject
            return waypoint.size() == 8 && string(waypoint.get("id")) && string(waypoint.get("floor")) &&
                string(waypoint.get("roomId")) && string(waypoint.get("name")) &&
                integer(waypoint.get("x")) && integer(waypoint.get("y")) && integer(waypoint.get("z")) &&
                integer(waypoint.get("color"))
        }

        private fun string(value: JsonElement?): Boolean =
            value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString

        private fun integer(value: JsonElement?): Boolean {
            if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return false
            try {
                val number = BigDecimal(value.asString)
                return number.stripTrailingZeros().scale() <= 0 &&
                    number.compareTo(BigDecimal.valueOf(Int.MIN_VALUE.toLong())) >= 0 &&
                    number.compareTo(BigDecimal.valueOf(Int.MAX_VALUE.toLong())) <= 0
            } catch (invalidNumber: NumberFormatException) {
                return false
            }
        }

        private fun failure(error: String): SecretWaypointCatalogCodec.DecodeResult =
            SecretWaypointCatalogCodec.DecodeResult(false, java.util.List.of(), error)
    }
}
