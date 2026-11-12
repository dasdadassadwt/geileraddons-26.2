package geiler.addons.client.dungeon.odin

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Comparator
import java.util.HashMap

/** Bounded loading, validation, and ordered route access for Water Board schedules. */
internal object DungeonWaterBoardCatalogCore {
    private const val RESOURCE = "/data/dungeon-water-solutions.json"
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_SCHEDULE_STEPS = 16

    fun route(
        catalog: DungeonWaterBoardCatalog.CatalogResult,
        optimized: Boolean,
        patternId: Int,
        extendedSlots: String?,
    ): List<DungeonWaterBoardCatalog.Click> {
        if (!catalog.available() || patternId !in 0..3 || !DungeonWaterBoardRulesCore.validSlots(extendedSlots))
            return java.util.List.of()
        return catalog.routes().getOrDefault(
            DungeonWaterBoardCatalog.RouteKey(optimized, patternId, extendedSlots!!), java.util.List.of(),
        )
    }

    fun loadBundled(): DungeonWaterBoardCatalog.CatalogResult {
        try {
            val input = DungeonWaterBoardCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: return unavailable("bundled route resource is missing")
            input.use {
                val bytes = it.readNBytes(MAX_BYTES + 1)
                if (bytes.isEmpty() || bytes.size > MAX_BYTES)
                    return unavailable("bundled route resource is empty or exceeds its size limit")
                return parse(String(bytes, StandardCharsets.UTF_8))
            }
        } catch (error: IOException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    fun parse(json: String?): DungeonWaterBoardCatalog.CatalogResult {
        if (json == null || json.all { Character.isWhitespace(it) } || json.length > MAX_BYTES)
            return unavailable("route catalog is empty or exceeds its size limit")
        try {
            val rootElement = JsonParser.parseString(json)
            if (!rootElement.isJsonObject) return unavailable("route catalog root is not an object")
            val root = rootElement.asJsonObject
            val routes = HashMap<DungeonWaterBoardCatalog.RouteKey, List<DungeonWaterBoardCatalog.Click>>()
            for (optimized in listOf(false, true)) {
                val modeElement = root.get(java.lang.Boolean.toString(optimized))
                if (modeElement == null || !modeElement.isJsonObject)
                    return unavailable("route catalog is missing a strategy")
                val mode = modeElement.asJsonObject
                var modeRouteCount = 0
                for (patternId in 0 until 4) {
                    val patternElement = mode.get(patternId.toString())
                    if (patternElement == null || !patternElement.isJsonObject)
                        return unavailable("route catalog is missing a pattern")
                    val pattern = patternElement.asJsonObject
                    for (signatureEntry in pattern.entrySet()) {
                        val slots = signatureEntry.key
                        val signatureValue = signatureEntry.value
                        if (!DungeonWaterBoardRulesCore.validSlots(slots) ||
                            signatureValue == null || !signatureValue.isJsonObject
                        ) return unavailable("route catalog contains an invalid slot signature")
                        val leverSchedules = signatureValue.asJsonObject
                        val clicks = ArrayList<DungeonWaterBoardCatalog.Click>()
                        for (leverEntry in leverSchedules.entrySet()) {
                            val leverId = leverEntry.key
                            val scheduleValue = leverEntry.value
                            if (DungeonWaterBoardRulesCore.valveOrder(leverId) == Int.MAX_VALUE ||
                                scheduleValue == null || !scheduleValue.isJsonArray
                            ) return unavailable("route catalog contains an unknown valve")
                            val schedule = scheduleValue.asJsonArray
                            if (schedule.size() == 0 || clicks.size + schedule.size() > MAX_SCHEDULE_STEPS)
                                return unavailable("route catalog contains an oversized schedule")
                            var previousSeconds = -1.0
                            for (timeElement in schedule) {
                                if (!timeElement.isJsonPrimitive || !timeElement.asJsonPrimitive.isNumber)
                                    return unavailable("route catalog contains a non-numeric time")
                                val seconds = timeElement.asDouble
                                if (!seconds.isFinite() || seconds < 0.0 || seconds > 60.0 || seconds < previousSeconds)
                                    return unavailable("route catalog contains an invalid time sequence")
                                clicks.add(DungeonWaterBoardCatalog.Click(leverId, seconds))
                                previousSeconds = seconds
                            }
                        }
                        if (clicks.none { it.leverId == "water" && it.seconds == 0.0 })
                            return unavailable("route catalog has no observed start-valve action")
                        clicks.sortWith(Comparator { first, second ->
                            val byTime = java.lang.Double.compare(first.seconds, second.seconds)
                            if (byTime != 0) byTime else Integer.compare(
                                DungeonWaterBoardRulesCore.valveOrder(first.leverId),
                                DungeonWaterBoardRulesCore.valveOrder(second.leverId),
                            )
                        })
                        val key = DungeonWaterBoardCatalog.RouteKey(optimized, patternId, slots)
                        if (routes.put(key, java.util.List.copyOf(clicks)) != null)
                            return unavailable("route catalog contains a duplicate case")
                        modeRouteCount++
                    }
                    if (pattern.size() != 10)
                        return unavailable("route catalog pattern coverage is incomplete")
                }
                if (modeRouteCount != 40)
                    return unavailable("route catalog strategy coverage is incomplete")
            }
            return DungeonWaterBoardCatalog.CatalogResult(true, routes, "")
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun unavailable(reason: String): DungeonWaterBoardCatalog.CatalogResult =
        DungeonWaterBoardCatalog.CatalogResult(false, java.util.Map.of(), reason)
}
