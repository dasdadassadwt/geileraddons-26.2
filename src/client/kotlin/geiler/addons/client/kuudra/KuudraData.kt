package geiler.addons.client.kuudra

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The bundled Kuudra data and its readers. The four JSON files under `geileraddons/kuudra/` are IQ's default
 * configuration (https://github.com/iqaddons/IQ, Apache-2.0; see IQ-NOTICE.txt there). Bad or missing data yields empty lists.
 */
object KuudraData {
    /** A supply pile in the middle of the lobby; [noPre] is the missing-pre value that makes it the special pile. */
    class Pile(val name: String, val x: Double, val y: Double, val z: Double, val noPre: Int)

    /** One timed pearl landing: [text] is the label (when to throw), [pre] and [hideForPre] limit it to a pre-spot. */
    class PearlSpot(
        val x: Double, val y: Double, val z: Double, val argb: Int, val size: Double, val text: String,
        val pre: Int?, val hideForPre: Int?,
    )

    class PearlArea(val name: String, x1: Double, z1: Double, x2: Double, z2: Double, val spots: List<PearlSpot>) {
        private val minX = min(x1, x2)
        private val maxX = max(x1, x2)
        private val minZ = min(z1, z2)
        private val maxZ = max(z1, z2)

        fun contains(x: Double, z: Double): Boolean = x in minX..maxX && z in minZ..maxZ
    }

    /** A fixed marker (safe spot, DPS spot, skip spot): drawn at each position while the run is in a phase of [showIn]. */
    class Waypoint(
        val name: String, val positions: List<DoubleArray>, val argb: Int, val text: String,
        val showIn: Set<KuudraPhase>, val hideIn: Set<KuudraPhase>, val maxDistance: Double, val filled: Boolean,
    )

    private val piles: List<Pile> by lazy { parsePiles(resource("pile_locations.json")) }
    private val pearlAreas: List<PearlArea> by lazy { parsePearlAreas(resource("pearl_waypoints.json")) }
    private val safeSpots: List<Waypoint> by lazy { parseWaypoints(resource("new_etherwarp_waypoints.json")) }
    private val customWaypoints: List<Waypoint> by lazy { parseWaypoints(resource("custom_waypoints.json")) }

    @JvmStatic fun piles(): List<Pile> = piles
    @JvmStatic fun pearlAreas(): List<PearlArea> = pearlAreas
    @JvmStatic fun safeSpots(): List<Waypoint> = safeSpots
    @JvmStatic fun customWaypoints(): List<Waypoint> = customWaypoints

    private fun resource(name: String): String =
        KuudraData::class.java.getResourceAsStream("/geileraddons/kuudra/$name")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    @JvmStatic
    fun parsePiles(json: String): List<Pile> = guard {
        array(root(json), "piles").mapNotNull { element ->
            val pile = element.asJsonObject
            val at = numbers(pile.get("position")) ?: return@mapNotNull null
            Pile(pile.get("name").asString, at[0], at[1], at[2], pile.get("noPreValue")?.asInt ?: 0)
        }
    }

    @JvmStatic
    fun parsePearlAreas(json: String): List<PearlArea> = guard {
        array(root(json), "areas").mapNotNull { element ->
            val area = element.asJsonObject
            val one = numbers(area.get("pos1")) ?: return@mapNotNull null
            val two = numbers(area.get("pos2")) ?: return@mapNotNull null
            val spots = array(area, "waypoints").mapNotNull { waypoint ->
                val spot = waypoint.asJsonObject
                val at = numbers(spot.get("coords")) ?: return@mapNotNull null
                val rgb = numbers(spot.get("rgb")) ?: doubleArrayOf(0.0, 255.0, 255.0)
                val argb = (0xFF shl 24) or (rgb[0].toInt() shl 16) or (rgb[1].toInt() shl 8) or rgb[2].toInt()
                PearlSpot(at[0], at[1], at[2], argb, spot.get("size")?.asDouble ?: 1.0, spot.get("text")?.asString ?: "",
                    spot.get("pre")?.takeIf { it.isJsonPrimitive }?.asInt, spot.get("hideForPre")?.takeIf { it.isJsonPrimitive }?.asInt)
            }
            PearlArea(area.get("name").asString, one[0], one[1], two[0], two[1], spots)
        }
    }

    /** The waypoints of every enabled category in IQ's category file format; unknown phase names are dropped. */
    @JvmStatic
    fun parseWaypoints(json: String): List<Waypoint> = guard {
        array(root(json), "categories").flatMap { categoryElement ->
            val category = categoryElement.asJsonObject
            if (category.get("enabled")?.asBoolean == false) return@flatMap emptyList<Waypoint>()
            array(category, "waypoints").mapNotNull { element ->
                val waypoint = element.asJsonObject
                val positions = waypoint.get("positions")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { numbers(it) }
                    ?: listOfNotNull(numbers(waypoint.get("position")))
                if (positions.isEmpty()) return@mapNotNull null
                val rgb = (waypoint.get("color")?.asString ?: "#00FFFF").removePrefix("#").toIntOrNull(16) ?: 0x00FFFF
                val alpha = ((waypoint.get("alpha")?.asDouble ?: 0.8).coerceIn(0.0, 1.0) * 255).roundToInt()
                Waypoint(
                    waypoint.get("name")?.asString ?: "", positions, (alpha shl 24) or (rgb and 0xFFFFFF),
                    waypoint.get("text")?.asString ?: "", phases(waypoint.get("showInPhases")), phases(waypoint.get("hideInPhases")),
                    waypoint.get("maxRenderDistance")?.asDouble ?: -1.0, waypoint.get("renderStyle")?.asString.equals("SOLID", true),
                )
            }
        }
    }

    private fun phases(element: JsonElement?): Set<KuudraPhase> =
        element?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { KuudraPhase.byName(it.asString) }?.toSet() ?: emptySet()

    private fun root(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun array(source: JsonObject, name: String): JsonArray = source.get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun numbers(element: JsonElement?): DoubleArray? {
        if (element == null || !element.isJsonArray) return null
        val array = element.asJsonArray
        if (array.size() < 2) return null
        return DoubleArray(array.size()) { array[it].asDouble }
    }

    private inline fun <T> guard(read: () -> List<T>): List<T> = try {
        read()
    } catch (_: RuntimeException) {
        emptyList()
    }
}
