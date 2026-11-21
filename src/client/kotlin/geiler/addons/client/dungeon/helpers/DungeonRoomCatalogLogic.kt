package geiler.addons.client.dungeon.helpers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Kotlin parser for the bundled Odin room signatures; the Java records remain the ABI. */
object DungeonRoomCatalogLogic {
    private const val RESOURCE = "/data/geileraddons/dungeon-rooms.json"
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_ROWS = 256
    private val BUNDLED: DungeonRoomCatalog.CatalogResult by lazy { loadBundled() }

    @JvmStatic
    fun bundled(): DungeonRoomCatalog.CatalogResult = BUNDLED

    private fun loadBundled(): DungeonRoomCatalog.CatalogResult {
        try {
            val input = DungeonRoomCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: return unavailable("bundled Odin room catalog is missing")
            input.use {
                val bytes = it.readNBytes(MAX_BYTES + 1)
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
                    return unavailable("bundled Odin room catalog is empty or too large")
                }
                return parse(String(bytes, StandardCharsets.UTF_8))
            }
        } catch (error: IOException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    @JvmStatic
    fun parse(json: String?): DungeonRoomCatalog.CatalogResult {
        if (json.isNullOrBlank() || json.length > MAX_BYTES) return unavailable("room catalog is empty or too large")
        try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) return unavailable("room catalog root is not an array")
            val rows: JsonArray = root.asJsonArray
            if (rows.isEmpty || rows.size() > MAX_ROWS) {
                return unavailable("room catalog row count is outside its supported range")
            }
            val coreIndex = HashMap<Int, DungeonRoomCatalog.RoomData>()
            var validRows = 0
            for (row in rows) {
                if (!row.isJsonObject) return unavailable("room catalog contains a non-object row")
                val obj: JsonObject = row.asJsonObject
                val name = string(obj, "name")
                val type = string(obj, "type")
                val shape = string(obj, "shape")
                val coresElement = obj.get("cores")
                if (name == null || name.length > 80 || type == null || !validType(type) ||
                    shape == null || !validShape(shape) || coresElement == null || !coresElement.isJsonArray
                ) return unavailable("room catalog contains invalid room metadata")

                val coreValues = coresElement.asJsonArray
                if (coreValues.isEmpty || coreValues.size() > 64) {
                    return unavailable("room catalog contains an invalid core list")
                }
                val crypts = optionalInt(obj, "crypts", 0, 64)
                val maxSecrets = optionalInt(obj, "maxSecrets", 0, 128)
                val trappedChests = optionalInt(obj, "trappedChests", 0, 64)
                if (crypts == null || maxSecrets == null || trappedChests == null) {
                    return unavailable("room catalog contains an invalid count")
                }
                val data = DungeonRoomCatalog.RoomData(name, type, shape, crypts, maxSecrets, trappedChests)
                for (core in coreValues) {
                    val key = integer(core)
                        ?: return unavailable("room catalog contains a non-integer core")
                    // Match Odin's file-order map construction: the final row wins for duplicate cores.
                    coreIndex[key] = data
                }
                validRows++
            }
            return DungeonRoomCatalog.CatalogResult(true, validRows, coreIndex, "")
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun string(obj: JsonObject, key: String): String? {
        val value: JsonElement? = obj.get(key)
        return if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
            value.asString.trim { Character.isWhitespace(it) } else null
    }

    private fun optionalInt(obj: JsonObject, key: String, fallback: Int, maximum: Int): Int? {
        val value: JsonElement? = obj.get(key)
        if (value == null || value.isJsonNull) return fallback
        val parsed = integer(value)
        return if (parsed != null && parsed >= 0 && parsed <= maximum) parsed else null
    }

    private fun integer(value: JsonElement?): Int? {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return try {
            value.asBigDecimal.intValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun validType(value: String): Boolean = value in setOf(
        "NORMAL", "RARE", "PUZZLE", "TRAP", "MINIBOSS", "CHAMPION", "ENTRANCE", "FAIRY", "BLOOD",
    )

    private fun validShape(value: String): Boolean = value in setOf("1x1", "1x2", "1x3", "1x4", "2x2", "L")

    private fun unavailable(reason: String): DungeonRoomCatalog.CatalogResult =
        DungeonRoomCatalog.CatalogResult(false, 0, emptyMap(), reason)
}
