package geiler.addons.client.waypoint

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import geiler.addons.client.location.Island
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Import and export of Skyblocker's `[Skyblocker-Waypoint-Data-V1]` share strings (gzip + base64 of a JSON
 * list of groups), so waypoint packs can move between the two mods.
 */
object WaypointShare {
    const val PREFIX = "[Skyblocker-Waypoint-Data-V1]"
    const val MAX_JSON_BYTES = 262_144
    const val MAX_WAYPOINTS = 2_000

    class Result(val groups: List<WaypointGroup>, val error: String?)

    private fun failure(message: String) = Result(emptyList(), message)

    @JvmStatic
    fun encode(groups: List<WaypointGroup>): String {
        val array = JsonArray()
        for (group in groups) {
            val json = JsonObject()
            json.addProperty("name", group.name)
            json.addProperty("island", group.island.ifEmpty { "unknown" })
            val waypoints = JsonArray()
            for (w in group.waypoints) {
                val entry = JsonObject()
                entry.add("pos", JsonArray().also { it.add(w.x); it.add(w.y); it.add(w.z) })
                entry.addProperty("name", w.name)
                entry.add("colorComponents", JsonArray().also {
                    it.add(((w.rgb shr 16) and 0xFF) / 255f)
                    it.add(((w.rgb shr 8) and 0xFF) / 255f)
                    it.add((w.rgb and 0xFF) / 255f)
                })
                entry.addProperty("alpha", w.alpha)
                entry.addProperty("shouldRender", w.enabled)
                waypoints.add(entry)
            }
            json.add("waypoints", waypoints)
            json.addProperty("ordered", group.ordered)
            json.addProperty("renderThroughWalls", group.throughWalls)
            json.addProperty("waypointType", group.type)
            array.add(json)
        }
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(array.toString().toByteArray(Charsets.UTF_8)) }
        return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    @JvmStatic
    fun decode(text: String?): Result {
        val trimmed = text?.trim().orEmpty()
        if (!trimmed.startsWith(PREFIX)) return failure("Not a Skyblocker waypoint string.")
        val raw = try {
            Base64.getDecoder().decode(trimmed.substring(PREFIX.length).trim())
        } catch (_: IllegalArgumentException) {
            return failure("The waypoint string is not valid base64.")
        }
        val json = try {
            GZIPInputStream(ByteArrayInputStream(raw)).use { input ->
                val out = input.readNBytes(MAX_JSON_BYTES + 1)
                if (out.size > MAX_JSON_BYTES) return failure("The waypoint data is too large.")
                String(out, Charsets.UTF_8)
            }
        } catch (_: java.io.IOException) {
            return failure("The waypoint data is not compressed correctly.")
        }
        return try {
            parseGroups(JsonParser.parseString(json))
        } catch (_: RuntimeException) {
            failure("The waypoint data is malformed.")
        }
    }

    private fun parseGroups(root: JsonElement): Result {
        if (!root.isJsonArray) return failure("Expected a list of waypoint groups.")
        val groups = ArrayList<WaypointGroup>()
        var total = 0
        for (element in root.asJsonArray) {
            val obj = element.asJsonObject
            val group = WaypointGroup(
                name = obj.string("name") ?: "Imported",
                island = islandFromId(obj.string("island")),
                ordered = obj.get("ordered")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                throughWalls = obj.get("renderThroughWalls")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                type = WaypointType.fromId(obj.string("waypointType")).id,
            )
            for (w in obj.getAsJsonArray("waypoints") ?: JsonArray()) {
                if (++total > MAX_WAYPOINTS) return failure("More than $MAX_WAYPOINTS waypoints.")
                group.waypoints.add(parseWaypoint(w.asJsonObject))
            }
            groups.add(group)
        }
        return Result(groups, null)
    }

    private fun parseWaypoint(obj: JsonObject): WaypointEntry {
        val pos = obj.getAsJsonArray("pos")
        val colors = obj.getAsJsonArray("colorComponents")
        var rgb = 0x00FF00
        if (colors != null && colors.size() >= 3) {
            fun channel(i: Int) = (colors[i].asFloat.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            rgb = (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
        return WaypointEntry(
            name = obj.string("name") ?: "",
            x = pos[0].asInt, y = pos[1].asInt, z = pos[2].asInt,
            rgb = rgb,
            alpha = obj.get("alpha")?.takeIf { it.isJsonPrimitive }?.asFloat?.coerceIn(0f, 1f) ?: 0.5f,
            enabled = obj.get("shouldRender")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
        )
    }

    /** A plain string, or the `text` of a serialized text component. */
    private fun JsonObject.string(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonPrimitive) return value.asString
        if (value.isJsonObject) return value.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        return null
    }

    /** Skyblocker island ids are Hypixel mode strings; anything this mod cannot name becomes "every island". */
    private fun islandFromId(id: String?): String {
        if (id.isNullOrBlank()) return ""
        val island = Island.fromMode(id)
        return if (island == Island.OTHER) "" else island.mode() ?: ""
    }
}
