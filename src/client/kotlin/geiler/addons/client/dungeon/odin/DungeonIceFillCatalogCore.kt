package geiler.addons.client.dungeon.odin

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList

/** Bounded loading and point/route validation for the pinned Ice Fill catalog. */
internal object DungeonIceFillCatalogCore {
    private const val RESOURCE = "/data/dungeon-ice-fill-floors.json"
    private const val MAX_BYTES = 32 * 1024
    private const val FLOOR_COUNT = 3

    fun loadBundled(): DungeonIceFillCatalog.CatalogResult {
        try {
            val input = DungeonIceFillCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: return unavailable("licensed Ice Fill catalog is missing")
            input.use {
                val bytes = it.readNBytes(MAX_BYTES + 1)
                if (bytes.isEmpty() || bytes.size > MAX_BYTES)
                    return unavailable("Ice Fill catalog is empty or exceeds its size limit")
                return parse(String(bytes, StandardCharsets.UTF_8))
            }
        } catch (error: IOException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    fun parse(json: String?): DungeonIceFillCatalog.CatalogResult {
        if (json == null || json.all { Character.isWhitespace(it) } || json.length > MAX_BYTES)
            return unavailable("Ice Fill catalog is empty or exceeds its size limit")
        try {
            val rootElement = JsonParser.parseString(json)
            if (!rootElement.isJsonObject) return unavailable("Ice Fill catalog root is not an object")
            val root = rootElement.asJsonObject
            val identifiers = root.get("identifier")
            val easy = root.get("easy")
            val hard = root.get("hard")
            if (identifiers == null || easy == null || hard == null)
                return unavailable("Ice Fill catalog is missing an identifier or route set")
            val parsedIdentifiers = parseFloors(identifiers, true)
            val parsedEasy = parseFloors(easy, false)
            val parsedHard = parseFloors(hard, false)
            if (parsedIdentifiers == null || parsedEasy == null || parsedHard == null)
                return unavailable("Ice Fill catalog contains an invalid floor or point")
            for (floor in 0 until FLOOR_COUNT) {
                val patterns = parsedIdentifiers[floor].size
                if (patterns == 0 || parsedEasy[floor].size != patterns || parsedHard[floor].size != patterns)
                    return unavailable("Ice Fill floor patterns do not align")
                for (pattern in 0 until patterns) {
                    val identifier = parsedIdentifiers[floor][pattern]
                    val first = identifier.first()
                    val second = identifier.last()
                    if (first == second || !sameY(identifier))
                        return unavailable("Ice Fill identifier pair is invalid")
                    if (!sameY(parsedEasy[floor][pattern]) || !sameY(parsedHard[floor][pattern]))
                        return unavailable("Ice Fill route changes vertical floor")
                    val identifierY = first.y()
                    if (parsedEasy[floor][pattern].first().y() != identifierY ||
                        parsedHard[floor][pattern].first().y() != identifierY
                    ) return unavailable("Ice Fill route uses a different floor than its identifier")
                }
            }
            return DungeonIceFillCatalog.CatalogResult(true, parsedIdentifiers, parsedEasy, parsedHard, "")
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    fun route(
        catalog: DungeonIceFillCatalog.CatalogResult,
        optimized: Boolean,
        floor: Int,
        pattern: Int,
    ): List<DungeonIceFillCatalog.Point> {
        val source = if (optimized) catalog.hard() else catalog.easy()
        if (!catalog.available() || floor < 0 || floor >= source.size || pattern < 0 || pattern >= source[floor].size)
            return java.util.List.of()
        return source[floor][pattern]
    }

    private fun parseFloors(element: JsonElement, identifier: Boolean): List<List<List<DungeonIceFillCatalog.Point>>>? {
        if (!element.isJsonArray || element.asJsonArray.size() != FLOOR_COUNT) return null
        val floors = ArrayList<List<List<DungeonIceFillCatalog.Point>>>(FLOOR_COUNT)
        for (floorElement in element.asJsonArray) {
            if (!floorElement.isJsonArray || floorElement.asJsonArray.size() == 0 || floorElement.asJsonArray.size() > 8)
                return null
            val patterns = ArrayList<List<DungeonIceFillCatalog.Point>>()
            for (patternElement in floorElement.asJsonArray) {
                if (!patternElement.isJsonArray) return null
                val points: JsonArray = patternElement.asJsonArray
                val invalidPointCount = if (identifier) points.size() != 2 else points.size() < 2 || points.size() > 64
                if (invalidPointCount) return null
                val route = ArrayList<DungeonIceFillCatalog.Point>(points.size())
                for (pointElement in points) {
                    val point = parsePoint(pointElement) ?: return null
                    route.add(point)
                }
                patterns.add(java.util.List.copyOf(route))
            }
            floors.add(java.util.List.copyOf(patterns))
        }
        return java.util.List.copyOf(floors)
    }

    private fun parsePoint(element: JsonElement?): DungeonIceFillCatalog.Point? {
        if (element == null || !element.isJsonObject) return null
        val point = element.asJsonObject
        val x = integer(point.get("x"))
        val y = integer(point.get("y"))
        val z = integer(point.get("z"))
        if (x == null || y == null || z == null || x < 0 || x >= 31 || z < 0 || z >= 31 || y < 69 || y > 101)
            return null
        return DungeonIceFillCatalog.Point(x, y, z)
    }

    private fun integer(element: JsonElement?): Int? {
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return try {
            element.asBigDecimal.intValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun sameY(points: List<DungeonIceFillCatalog.Point>?): Boolean =
        points != null && points.isNotEmpty() && points.all { it.y() == points.first().y() }

    private fun unavailable(reason: String): DungeonIceFillCatalog.CatalogResult =
        DungeonIceFillCatalog.CatalogResult(false, java.util.List.of(), java.util.List.of(), java.util.List.of(), reason)
}
