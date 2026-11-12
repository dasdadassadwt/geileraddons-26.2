package geiler.addons.client.dungeon

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.UUID

/** Bounded implementation behind the stable Java Secret Waypoint package-codec API. */
object SecretWaypointPackageCodecLogic {
    private val packageGson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    @JvmStatic
    fun encode(
        personal: List<SecretWaypointStore.Waypoint?>?,
        overrides: List<SecretWaypointStore.WaypointOverride?>?,
        collected: List<SecretWaypointStore.CollectedWaypoint?>?,
    ): String? {
        if (personal == null || overrides == null || collected == null ||
            personal.size > ClientJsonFile.MAX_ENTRIES ||
            overrides.size > ClientJsonFile.MAX_ENTRIES ||
            collected.size > ClientJsonFile.MAX_ENTRIES
        ) {
            return null
        }

        val file = PackageFile()
        file.personal = ArrayList(personal)
        file.overrides = ArrayList(overrides)
        file.collected = ArrayList(collected)
        if (!valid(file)) return null
        return boundedEncode(file)
    }

    @JvmStatic
    fun decode(encoded: String?): SecretWaypointPackageCodec.DecodeResult {
        if (encoded == null || encoded.length > ClientJsonFile.MAX_PACKAGE_BYTES ||
            encoded.toByteArray(StandardCharsets.UTF_8).size > ClientJsonFile.MAX_PACKAGE_BYTES
        ) {
            return failure("Waypoint JSON is missing or too large.")
        }

        val file: PackageFile?
        try {
            val parsed = BoundedDungeonJsonParser.parse(encoded, 8, 100_000)
            if (!parsed.isJsonObject) return failure("Waypoint JSON must be an object.")
            val root = parsed.asJsonObject
            if (!validRawShape(root)) {
                return failure("Waypoint JSON has an unsupported shape or numeric value.")
            }
            file = ClientJsonFile.GSON.fromJson(root, PackageFile::class.java)
        } catch (exception: IOException) {
            return failure("Waypoint JSON is invalid.")
        } catch (exception: JsonParseException) {
            return failure("Waypoint JSON is invalid.")
        } catch (exception: IllegalStateException) {
            return failure("Waypoint JSON is invalid.")
        } catch (exception: NullPointerException) {
            return failure("Waypoint JSON is invalid.")
        }

        if (!valid(file)) return failure("Waypoint JSON has an unsupported schema or invalid entries.")
        val decodedFile = file ?: return failure("Waypoint JSON has an unsupported schema or invalid entries.")
        val personal = decodedFile.personal
            ?: return failure("Waypoint JSON has an unsupported schema or invalid entries.")
        val overrides = decodedFile.overrides
            ?: return failure("Waypoint JSON has an unsupported schema or invalid entries.")
        val collected = decodedFile.collected
            ?: return failure("Waypoint JSON has an unsupported schema or invalid entries.")
        return SecretWaypointPackageCodec.DecodeResult(
            true,
            immutableCopy(personal),
            immutableCopy(overrides),
            immutableCopy(collected),
            null,
        )
    }

    @JvmStatic
    fun validWaypoint(
        waypoint: SecretWaypointStore.Waypoint?,
        requireRoomCoordinates: Boolean,
    ): Boolean {
        if (waypoint == null || !boundedText(waypoint.id(), 128) || !boundedText(waypoint.name(), 64) ||
            DungeonFloor.parse(waypoint.floor()) == null || !validRoomId(waypoint.roomId())
        ) {
            return false
        }
        if (waypoint.id().startsWith("personal:")) {
            try {
                val parsed = UUID.fromString(waypoint.id().substring("personal:".length))
                if (waypoint.id() != "personal:$parsed") return false
            } catch (invalidId: IllegalArgumentException) {
                return false
            }
        }
        return !requireRoomCoordinates || matchesRoom(waypoint.roomId(), waypoint.x(), waypoint.z())
    }

    @JvmStatic
    fun validRoomId(roomId: String?): Boolean {
        if (roomId == null || !roomId.startsWith("cell:")) return false
        val parts = roomId.substring("cell:".length)
        val comma = parts.indexOf(',')
        if (comma < 0 || comma != parts.lastIndexOf(',')) return false
        try {
            val x = Integer.parseInt(parts.substring(0, comma))
            val z = Integer.parseInt(parts.substring(comma + 1))
            return x >= 0 && x < 6 && z >= 0 && z < 6 && roomId == "cell:$x,$z"
        } catch (invalidCell: NumberFormatException) {
            return false
        }
    }

    @JvmStatic
    fun matchesRoom(roomId: String?, x: Int, z: Int): Boolean {
        val cell = DungeonGridRules.cellAt(x, z)
        return cell != null && roomId!!.equals("cell:${cell.componentX},${cell.componentZ}")
    }

    private fun valid(file: PackageFile?): Boolean {
        if (file == null || file.version != 1) return false
        val personal = file.personal ?: return false
        val overrides = file.overrides ?: return false
        val collected = file.collected ?: return false
        if (personal.size > ClientJsonFile.MAX_ENTRIES ||
            overrides.size > ClientJsonFile.MAX_ENTRIES ||
            collected.size > ClientJsonFile.MAX_ENTRIES
        ) {
            return false
        }

        val personalIds = HashSet<String>()
        val personalById = HashMap<String, SecretWaypointStore.Waypoint>()
        for (candidate in personal) {
            val waypoint = candidate ?: return false
            if (!validWaypoint(waypoint, true) || !waypoint.id().startsWith("personal:") ||
                !personalIds.add(waypoint.id())
            ) {
                return false
            }
            personalById[waypoint.id()] = waypoint
        }

        val overrideIds = HashSet<String>()
        for (candidate in overrides) {
            val override = candidate ?: return false
            if (!boundedText(override.id(), 128) || !overrideIds.add(override.id())) return false
            val replacement = override.replacement()
            if (override.hidden()) {
                if (replacement != null) return false
            } else if (replacement == null || !validWaypoint(replacement, true) ||
                override.id() != replacement.id()
            ) {
                return false
            }
        }

        val collectedIds = HashSet<String>()
        for (candidate in collected) {
            val marker = candidate ?: return false
            if (DungeonFloor.parse(marker.floor()) == null || !validRoomId(marker.roomId()) ||
                !boundedText(marker.markerId(), 128)
            ) {
                return false
            }
            val key = marker.floor().uppercase(Locale.ROOT) + "|" + marker.roomId() + "|" + marker.markerId()
            if (!collectedIds.add(key)) return false
            if (marker.markerId().startsWith("personal:")) {
                val personalWaypoint = personalById[marker.markerId()]
                if (personalWaypoint == null || !sameFloor(marker.floor(), personalWaypoint.floor()) ||
                    marker.roomId() != personalWaypoint.roomId()
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun validRawShape(root: JsonObject): Boolean {
        if (root.size() != 4 || !root.has("version") || !root.has("personal") || !root.has("overrides") ||
            !root.has("collected") || !integer(root.get("version")) || root.get("version").asInt != 1 ||
            !root.get("personal").isJsonArray || !root.get("overrides").isJsonArray ||
            !root.get("collected").isJsonArray
        ) {
            return false
        }

        val personal = root.getAsJsonArray("personal")
        val overrides = root.getAsJsonArray("overrides")
        val collected = root.getAsJsonArray("collected")
        if (personal.size() > ClientJsonFile.MAX_ENTRIES ||
            overrides.size() > ClientJsonFile.MAX_ENTRIES ||
            collected.size() > ClientJsonFile.MAX_ENTRIES
        ) {
            return false
        }
        for (element in personal) if (!waypointShape(element)) return false
        for (element in overrides) {
            if (!element.isJsonObject) return false
            val override = element.asJsonObject
            if (override.size() != 3 || !string(override.get("id")) || !bool(override.get("hidden")) ||
                !override.has("replacement")
            ) {
                return false
            }
            val replacement = override.get("replacement")
            if (!replacement.isJsonNull && !waypointShape(replacement)) return false
        }
        for (element in collected) {
            if (!element.isJsonObject) return false
            val marker = element.asJsonObject
            if (marker.size() != 3 || !string(marker.get("floor")) || !string(marker.get("roomId")) ||
                !string(marker.get("markerId"))
            ) {
                return false
            }
        }
        return true
    }

    private fun waypointShape(element: JsonElement?): Boolean {
        if (element == null || !element.isJsonObject) return false
        val waypoint = element.asJsonObject
        return waypoint.size() == 8 && string(waypoint.get("id")) && string(waypoint.get("floor")) &&
            string(waypoint.get("roomId")) && string(waypoint.get("name")) &&
            integer(waypoint.get("x")) && integer(waypoint.get("y")) &&
            integer(waypoint.get("z")) && integer(waypoint.get("color"))
    }

    private fun string(value: JsonElement?): Boolean =
        value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString

    private fun bool(value: JsonElement?): Boolean =
        value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean

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

    private fun sameFloor(left: String?, right: String?): Boolean =
        DungeonFloor.parse(left) != null && DungeonFloor.parse(left) == DungeonFloor.parse(right)

    private fun boundedText(value: String?, maximum: Int): Boolean {
        if (value == null || value.length == 0 || value.length > maximum || javaBlank(value)) return false
        return value[0].code > 0x20 && value[value.length - 1].code > 0x20
    }

    /** Mirrors String.isBlank(), including Character.isWhitespace semantics on the JDK. */
    private fun javaBlank(value: String): Boolean {
        for (character in value) if (!Character.isWhitespace(character)) return false
        return true
    }

    private fun boundedEncode(file: PackageFile): String? {
        val personal = file.personal ?: return null
        val overrides = file.overrides ?: return null
        val collected = file.collected ?: return null
        val root = JsonObject()
        root.addProperty("version", file.version)
        root.add("personal", ClientJsonFile.GSON.toJsonTree(personal))
        val encodedOverrides = JsonArray()
        for (overrideCandidate in overrides) {
            val override = overrideCandidate ?: return null
            val encoded = JsonObject()
            encoded.addProperty("id", override.id())
            encoded.addProperty("hidden", override.hidden())
            encoded.add("replacement", override.replacement()?.let { ClientJsonFile.GSON.toJsonTree(it) } ?: JsonNull.INSTANCE)
            encodedOverrides.add(encoded)
        }
        root.add("overrides", encodedOverrides)
        root.add("collected", ClientJsonFile.GSON.toJsonTree(collected))
        val json = packageGson.toJson(root)
        return if (json.toByteArray(StandardCharsets.UTF_8).size <= ClientJsonFile.MAX_PACKAGE_BYTES) json else null
    }

    private fun failure(error: String): SecretWaypointPackageCodec.DecodeResult =
        SecretWaypointPackageCodec.DecodeResult(false, java.util.List.of(), java.util.List.of(), java.util.List.of(), error)

    private fun <T : Any> immutableCopy(values: Collection<T?>): List<T> {
        @Suppress("UNCHECKED_CAST")
        val nonNullCollection = values as Collection<T>
        return java.util.List.copyOf(nonNullCollection)
    }

    /** Mutable public fields preserve Gson's existing DTO reflection behavior and field names. */
    private class PackageFile {
        @JvmField var version: Int = 1
        @JvmField var personal: MutableList<SecretWaypointStore.Waypoint?>? = ArrayList()
        @JvmField var overrides: MutableList<SecretWaypointStore.WaypointOverride?>? = ArrayList()
        @JvmField var collected: MutableList<SecretWaypointStore.CollectedWaypoint?>? = ArrayList()
    }
}
