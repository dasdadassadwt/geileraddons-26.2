package geiler.addons.client.dungeon

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.HashSet
import java.util.Locale

/** Bounded parser and index builder for the separately licensed Devonian coordinate subset. */
object DungeonWaypointCatalogLogic {
    @JvmStatic
    fun loadBundled(owner: Class<*>, resource: String): DungeonWaypointCatalog.CatalogResult {
        return try {
            val bytes = ClientJsonFile.openBundledResource(owner, resource).use {
                it.readNBytes(ClientJsonFile.MAX_PACKAGE_BYTES + 1)
            }
            if (bytes.isEmpty() || bytes.size > ClientJsonFile.MAX_PACKAGE_BYTES) {
                unavailable("Devonian waypoint resource is empty or too large")
            } else {
                parse(String(bytes, StandardCharsets.UTF_8))
            }
        } catch (error: IOException) {
            unavailable(error.message ?: error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    @JvmStatic
    fun parse(json: String?): DungeonWaypointCatalog.CatalogResult {
        if (json.isNullOrBlank() || json.length > ClientJsonFile.MAX_PACKAGE_BYTES ||
            json.toByteArray(StandardCharsets.UTF_8).size > ClientJsonFile.MAX_PACKAGE_BYTES
        ) {
            return unavailable("Devonian waypoint data is empty or too large")
        }

        return try {
            val parsed = BoundedDungeonJsonParser.parse(json, 8, 100_000)
            if (!parsed.isJsonObject) return unavailable("Devonian waypoint root must be an object")
            val root = parsed.asJsonObject
            if (!hasSupportedHeader(root)) {
                return unavailable("Devonian waypoint data has an unsupported schema or provenance header")
            }

            val rows = root.getAsJsonArray("waypoints")
            if (rows.isEmpty || rows.size() > ClientJsonFile.MAX_ENTRIES) {
                return unavailable("Devonian waypoint row count is outside its supported range")
            }

            val markers = ArrayList<DungeonWaypointCatalog.Marker>(rows.size())
            val ids = HashSet<String>()
            val nextIndex = HashMap<String, Int>()
            val roomNames = HashSet<String>()
            val namesBySlug = HashMap<String, String>()
            for (row in rows) {
                if (!markerShape(row)) return unavailable("Devonian waypoint row has an invalid shape")
                val marker = ClientJsonFile.GSON.fromJson(
                    row,
                    DungeonWaypointCatalog.Marker::class.java,
                )
                if (!valid(marker)) return unavailable("Devonian waypoint row contains an invalid marker")

                val roomName = marker.roomName!!
                val category = marker.category!!
                val roomKey = normalized(roomName)
                val categoryKey = "$roomKey|$category"
                val expectedIndex = nextIndex.getOrDefault(categoryKey, 0)
                val slug = slug(roomName)
                val existingName = namesBySlug.putIfAbsent(slug, roomKey)
                if (expectedIndex >= DungeonWaypointCatalog.MAX_MARKERS_PER_CATEGORY_AND_ROOM ||
                    slug.isEmpty() || (existingName != null && existingName != roomKey)
                ) {
                    return unavailable("Devonian waypoint category or room identifiers exceed their supported bounds")
                }
                if (marker.id != markerId(roomName, category, expectedIndex) ||
                    !ids.add(marker.id!!)
                ) {
                    return unavailable("Devonian waypoint row has a duplicate or unstable ID")
                }
                nextIndex[categoryKey] = expectedIndex + 1
                roomNames.add(roomKey)
                markers.add(marker)
            }

            if (roomNames.isEmpty() || roomNames.size > DungeonWaypointCatalog.MAX_ROOM_ROWS) {
                unavailable("Devonian waypoint room count is outside its supported range")
            } else {
                available(markers, roomNames.size)
            }
        } catch (error: IOException) {
            unavailable(error.message ?: error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    @JvmStatic
    fun find(
        catalog: DungeonWaypointCatalog.CatalogResult,
        roomName: String?,
        category: String?,
    ): List<DungeonWaypointCatalog.Marker> {
        if (!catalog.available() || roomName == null || category == null ||
            !DungeonWaypointCatalog.CATEGORIES.contains(category)
        ) {
            return emptyList()
        }
        return catalog.byRoom().getOrDefault(normalized(roomName), emptyList())
            .stream()
            .filter { marker -> marker.category == category }
            .toList()
    }

    private fun hasSupportedHeader(root: JsonObject): Boolean =
        root.size() == 5 && integer(root.get("version")) && root.get("version").asInt == 1 &&
            string(root.get("sourceProject")) && root.get("sourceProject").asString == "Synnerz/devonian" &&
            string(root.get("license")) && root.get("license").asString == "GPL-3.0" &&
            string(root.get("sourceRevision")) &&
            root.get("sourceRevision").asString == DungeonWaypointCatalog.SOURCE_REVISION &&
            root.has("waypoints") && root.get("waypoints").isJsonArray

    private fun markerShape(element: JsonElement?): Boolean {
        if (element == null || !element.isJsonObject) return false
        val row = element.asJsonObject
        return row.size() == 6 && string(row.get("id")) && string(row.get("roomName")) &&
            string(row.get("category")) && integer(row.get("x")) && integer(row.get("y")) && integer(row.get("z"))
    }

    private fun valid(marker: DungeonWaypointCatalog.Marker?): Boolean =
        marker != null && boundedText(marker.id, 128) && boundedText(marker.roomName, 80) &&
            marker.category?.let { DungeonWaypointCatalog.CATEGORIES.contains(it) } == true &&
            marker.x in 0..127 && marker.y in 0..255 && marker.z in 0..127

    private fun string(value: JsonElement?): Boolean =
        value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString

    private fun integer(value: JsonElement?): Boolean {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return false
        return try {
            val number = BigDecimal(value.asString)
            number.stripTrailingZeros().scale() <= 0 &&
                number.compareTo(BigDecimal.valueOf(Int.MIN_VALUE.toLong())) >= 0 &&
                number.compareTo(BigDecimal.valueOf(Int.MAX_VALUE.toLong())) <= 0
        } catch (_: NumberFormatException) {
            false
        }
    }

    private fun markerId(roomName: String, category: String, index: Int): String =
        "devonian:${slug(roomName)}:$category:$index"

    private fun slug(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").replace(Regex("^-|-$"), "")

    private fun normalized(value: String): String =
        value.trim { Character.isWhitespace(it) }.lowercase(Locale.ROOT)

    private fun boundedText(value: String?, maximum: Int): Boolean =
        value != null && value.isNotBlank() && value.length <= maximum && value == value.trim { it <= ' ' }

    private fun available(
        markers: List<DungeonWaypointCatalog.Marker>,
        roomCount: Int,
    ): DungeonWaypointCatalog.CatalogResult {
        val byRoom = HashMap<String?, MutableList<DungeonWaypointCatalog.Marker?>?>()
        for (marker in markers) {
            byRoom.getOrPut(normalized(marker.roomName!!)) { ArrayList() }!!.add(marker)
        }
        return DungeonWaypointCatalog.CatalogResult(true, markers, roomCount, byRoom, "")
    }

    private fun unavailable(reason: String): DungeonWaypointCatalog.CatalogResult =
        DungeonWaypointCatalog.CatalogResult(false, emptyList(), 0, emptyMap(), reason)
}
