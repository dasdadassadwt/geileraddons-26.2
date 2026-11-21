package geiler.addons.client.dungeon.helpers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.helpers.DungeonCreeperBeamsCatalog.CatalogResult
import geiler.addons.client.dungeon.helpers.DungeonCreeperBeamsCatalog.Pair
import geiler.addons.client.dungeon.helpers.DungeonCreeperBeamsCatalog.Point
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashSet
import java.util.List as JavaList
import kotlin.jvm.JvmStatic

/** Kotlin parser and resource loader behind the Java-compatible catalog value API. */
object DungeonCreeperBeamsCatalogLogic {
    private const val RESOURCE = "/data/dungeon-creeper-beams-solutions.json"
    private const val MAX_BYTES = 16 * 1024
    private const val MAX_RAW_PAIRS = 32
    private val BUNDLED = loadBundled()

    @JvmStatic
    fun bundled(): CatalogResult = BUNDLED

    private fun loadBundled(): CatalogResult {
        val input: InputStream = DungeonCreeperBeamsCatalog::class.java.getResourceAsStream(RESOURCE)
            ?: return unavailable("licensed pair catalog is missing")
        return try {
            input.use { stream ->
                val bytes = stream.readNBytes(MAX_BYTES + 1)
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
                    unavailable("licensed pair catalog is empty or exceeds its size limit")
                } else {
                    parse(String(bytes, StandardCharsets.UTF_8))
                }
            }
        } catch (error: IOException) {
            unavailable(error.message ?: error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    @JvmStatic
    fun parse(json: String?): CatalogResult {
        if (json.isNullOrBlank() || json.length > MAX_BYTES) {
            return unavailable("pair catalog is empty or exceeds its size limit")
        }
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) return unavailable("pair catalog root is not an array")
            val sourcePairs = root.asJsonArray
            if (sourcePairs.size() < DungeonCreeperBeamsRules.REQUIRED_MIN_PAIRS ||
                sourcePairs.size() > MAX_RAW_PAIRS
            ) return unavailable("pair catalog row count is outside its supported range")

            val pairs = ArrayList<Pair>(sourcePairs.size())
            val uniqueStarts = HashSet<Point>()
            for (sourcePair in sourcePairs) {
                if (!sourcePair.isJsonArray || sourcePair.asJsonArray.size() != 6) {
                    return unavailable("pair catalog row does not contain six coordinates")
                }
                val values = sourcePair.asJsonArray
                val first = point(values, 0)
                val second = point(values, 3)
                if (first == null || second == null || first == second) {
                    return unavailable("pair catalog contains invalid endpoint coordinates")
                }
                val pair = Pair(first, second)
                // Odin keys rendered pairs by their first endpoint, preserving reversed rows and dropping repeat starts.
                if (uniqueStarts.add(first)) pairs.add(pair)
            }
            if (pairs.size < DungeonCreeperBeamsRules.REQUIRED_MIN_PAIRS) {
                unavailable("pair catalog has too few distinct endpoint pairs")
            } else {
                CatalogResult(true, JavaList.copyOf(pairs), "")
            }
        } catch (error: RuntimeException) {
            unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun point(values: JsonArray, offset: Int): Point? {
        val x = coordinate(values.get(offset))
        val y = coordinate(values.get(offset + 1))
        val z = coordinate(values.get(offset + 2))
        if (x < 0 || x >= DungeonGridRules.ROOM_SIZE || z < 0 || z >= DungeonGridRules.ROOM_SIZE ||
            y < 68 || y > 86
        ) return null
        return Point(x, y, z)
    }

    private fun coordinate(value: JsonElement?): Int {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw IllegalArgumentException("pair catalog coordinate is not numeric")
        }
        return value.asBigDecimal.intValueExact()
    }

    private fun unavailable(reason: String): CatalogResult = CatalogResult(false, JavaList.of(), reason)
}
