package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonGridRules
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashSet

/** Pure exact-marker matching and route transforms for the licensed Ice Fill catalog. */
internal object DungeonIceFillRulesCore {
    private const val ROTATION_COUNT = 4

    fun probePositions(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: DungeonIceFillCatalog.CatalogResult?,
        floor: Int,
    ): List<DungeonIceFillRules.WorldPosition> {
        if (!validFloor(catalog, floor)) return java.util.List.of()
        val positions = LinkedHashSet<DungeonIceFillRules.WorldPosition>()
        for (rotation in 0 until ROTATION_COUNT) {
            for (pair in catalog!!.identifiers()[floor]) {
                for (point in pair) positions.add(worldPosition(roomBaseX, roomBaseZ, rotation, point))
            }
        }
        return java.util.List.copyOf(positions)
    }

    fun detect(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: DungeonIceFillCatalog.CatalogResult?,
        floor: Int,
        observations: List<DungeonIceFillRules.ObservedBlock?>?,
        completeLoadedScan: Boolean,
        optimized: Boolean,
    ): DungeonIceFillRules.Detection {
        if (!completeLoadedScan) return unknown(DungeonIceFillRules.UnknownReason.INCOMPLETE_SCAN)
        if (!validFloor(catalog, floor)) return unknown(DungeonIceFillRules.UnknownReason.CATALOG_UNAVAILABLE)
        val isAir = HashMap<DungeonIceFillRules.WorldPosition, Boolean>()
        if (observations != null) {
            for (observation in observations) {
                val position = observation?.position()
                val air = observation?.isAir()
                if (observation == null || position == null || air == null || isAir.putIfAbsent(position, air) != null)
                    return unknown(DungeonIceFillRules.UnknownReason.INVALID_OBSERVATION)
            }
        }
        for (required in probePositions(roomBaseX, roomBaseZ, catalog, floor)) {
            if (!isAir.containsKey(required)) return unknown(DungeonIceFillRules.UnknownReason.INCOMPLETE_SCAN)
        }
        val matches = ArrayList<PatternOrientation>()
        for (rotation in 0 until ROTATION_COUNT) {
            val identifiers = catalog!!.identifiers()[floor]
            for (pattern in identifiers.indices) {
                val pair = identifiers[pattern]
                val firstAir = isAir[worldPosition(roomBaseX, roomBaseZ, rotation, pair.first())]
                val secondAir = isAir[worldPosition(roomBaseX, roomBaseZ, rotation, pair.last())]
                if (firstAir == true && secondAir == false) matches.add(PatternOrientation(rotation, pattern))
            }
        }
        if (matches.isEmpty()) return unknown(DungeonIceFillRules.UnknownReason.NO_PATTERN_MATCH)
        if (matches.size != 1) return unknown(DungeonIceFillRules.UnknownReason.AMBIGUOUS_PATTERN_ORIENTATION)
        val match = matches.first()
        val canonicalRoute = catalog!!.route(optimized, floor, match.pattern)
        if (canonicalRoute.isEmpty()) return unknown(DungeonIceFillRules.UnknownReason.INVALID_CATALOG)
        val route = canonicalRoute.map { point -> worldPosition(roomBaseX, roomBaseZ, match.rotation, point) }
        return DungeonIceFillRules.Detection(
            floor,
            match.pattern,
            match.rotation,
            canonicalRoute.first().y(),
            route,
            null,
        )
    }

    fun selectRoute(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: DungeonIceFillCatalog.CatalogResult?,
        detected: DungeonIceFillRules.Detection?,
        optimized: Boolean,
    ): DungeonIceFillRules.Detection {
        if (detected == null || !validFloor(catalog, detected.floor()) || !detected.known() ||
            detected.rotation() >= ROTATION_COUNT
        ) return unknown(DungeonIceFillRules.UnknownReason.INVALID_CATALOG)
        val canonicalRoute = catalog!!.route(optimized, detected.floor(), detected.pattern())
        if (canonicalRoute.isEmpty()) return unknown(DungeonIceFillRules.UnknownReason.INVALID_CATALOG)
        val route = canonicalRoute.map { point -> worldPosition(roomBaseX, roomBaseZ, detected.rotation(), point) }
        return DungeonIceFillRules.Detection(
            detected.floor(),
            detected.pattern(),
            detected.rotation(),
            canonicalRoute.first().y(),
            route,
            null,
        )
    }

    private fun validFloor(catalog: DungeonIceFillCatalog.CatalogResult?, floor: Int): Boolean =
        catalog != null && catalog.available() && floor >= 0 && floor < catalog.identifiers().size &&
            floor < catalog.easy().size && floor < catalog.hard().size

    private fun worldPosition(
        roomBaseX: Int,
        roomBaseZ: Int,
        rotation: Int,
        point: DungeonIceFillCatalog.Point,
    ): DungeonIceFillRules.WorldPosition {
        val rotated = DungeonGridRules.toWorld(rotation, point.x(), point.z())
        return DungeonIceFillRules.WorldPosition(roomBaseX + rotated.x, point.y(), roomBaseZ + rotated.z)
    }

    private fun unknown(reason: DungeonIceFillRules.UnknownReason): DungeonIceFillRules.Detection =
        DungeonIceFillRules.Detection(-1, -1, -1, Int.MIN_VALUE, java.util.List.of(), reason)

    private data class PatternOrientation(val rotation: Int, val pattern: Int)
}
