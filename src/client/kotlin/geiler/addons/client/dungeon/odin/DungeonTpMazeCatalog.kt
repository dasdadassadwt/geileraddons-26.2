package geiler.addons.client.dungeon.odin

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashSet
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Loads the pinned BSD-3-Clause canonical TP Maze portal pad coordinates. */
class DungeonTpMazeCatalog private constructor() {
    companion object {
        private val BUNDLED: CatalogResult = loadBundled()

        @JvmStatic
        fun bundled(): CatalogResult = BUNDLED

        private const val RESOURCE = "/data/dungeon-tp-maze-pads.json"
        private const val MAX_BYTES = 4 * 1024
        private const val REQUIRED_PADS = 30

        private fun loadBundled(): CatalogResult {
            try {
                val input = DungeonTpMazeCatalog::class.java.getResourceAsStream(RESOURCE)
                    ?: return unavailable("licensed TP Maze pad catalog is missing")
                input.use {
                    val bytes = it.readNBytes(MAX_BYTES + 1)
                    if (bytes.isEmpty() || bytes.size > MAX_BYTES)
                        return unavailable("TP Maze catalog is empty or exceeds its size limit")
                    return parse(String(bytes, StandardCharsets.UTF_8))
                }
            } catch (error: IOException) {
                return unavailable(error.message ?: error.javaClass.simpleName)
            } catch (error: RuntimeException) {
                return unavailable(error.message ?: error.javaClass.simpleName)
            }
        }

        /** Package-level parser retained as a Java-callable fixture seam. */
        @JvmStatic
        fun parse(json: String?): CatalogResult {
            if (json == null || json.all { Character.isWhitespace(it) } || json.length > MAX_BYTES)
                return unavailable("TP Maze catalog is empty or exceeds its size limit")
            try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonArray || root.asJsonArray.size() != REQUIRED_PADS)
                    return unavailable("TP Maze catalog does not contain the expected 30 pads")
                val pads = ArrayList<DungeonTpMazeCatalog.Point>(REQUIRED_PADS)
                val unique = HashSet<DungeonTpMazeCatalog.Point>()
                for (element in root.asJsonArray) {
                    if (!element.isJsonArray || element.asJsonArray.size() != 3)
                        return unavailable("TP Maze pad does not contain three coordinates")
                    val values = element.asJsonArray
                    val x = coordinate(values[0])
                    val y = coordinate(values[1])
                    val z = coordinate(values[2])
                    if (x == null || y == null || z == null || x < 0 || x >= 31 || z < 0 || z >= 31 || y != 69)
                        return unavailable("TP Maze pad coordinate is outside the supported room layer")
                    val point = DungeonTpMazeCatalog.Point(x, y, z)
                    if (!unique.add(point)) return unavailable("TP Maze catalog contains a duplicate pad")
                    pads.add(point)
                }
                return CatalogResult(true, pads, "")
            } catch (error: RuntimeException) {
                return unavailable(error.message ?: error.javaClass.simpleName)
            }
        }

        private fun coordinate(value: JsonElement?): Int? {
            if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
            return try {
                value.asBigDecimal.intValueExact()
            } catch (_: ArithmeticException) {
                null
            }
        }

        private fun unavailable(reason: String): CatalogResult =
            CatalogResult(false, java.util.List.of(), reason)
    }

    @JvmRecord
    data class Point(val x: Int, val y: Int, val z: Int) {
        override fun toString(): String = "Point[x=$x, y=$y, z=$z]"
    }

    /** A value class because its former compact record constructor copies and normalizes components. */
    class CatalogResult(available: Boolean, pads: List<Point?>?, reason: String?) {
        private val availableValue = available
        private val padsValue: List<Point> = java.util.List.copyOf((pads ?: java.util.List.of()).map { it!! })
        private val reasonValue = reason ?: ""

        fun available(): Boolean = availableValue
        fun pads(): List<Point> = padsValue
        fun reason(): String = reasonValue

        override fun equals(other: Any?): Boolean = other is CatalogResult && availableValue == other.availableValue &&
            padsValue == other.padsValue && reasonValue == other.reasonValue

        override fun hashCode(): Int {
            var result = availableValue.hashCode()
            result = 31 * result + padsValue.hashCode()
            result = 31 * result + reasonValue.hashCode()
            return result
        }

        override fun toString(): String = "CatalogResult[available=$availableValue, pads=$padsValue, reason=$reasonValue]"
    }
}
