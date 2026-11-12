package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsCatalog.CatalogResult
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsCatalog.Pair as CatalogPair
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsCatalog.Point
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsRules.BlockKind
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsRules.Detection
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsRules.Endpoint
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsRules.ObservedBlock
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsRules.Pair as BeamPair
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsRules.UnknownReason
import geiler.addons.client.dungeon.odin.DungeonCreeperBeamsRules.WorldPosition
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.List as JavaList
import java.util.Set as JavaSet
import kotlin.jvm.JvmStatic

/** Kotlin implementation for loaded-block matching, validated guidance, and click confirmation. */
object DungeonCreeperBeamsRulesLogic {
    private const val ROTATION_COUNT = 4
    private const val MIN_CATALOG_Y = 68
    private const val MAX_CATALOG_Y = 86

    /** Requires the exact map-catalog room name used by the reference solvers. */
    @JvmStatic
    fun isCreeperBeamsRoom(roomName: String?): Boolean = roomName == "Creeper Beams"

    /** Returns each loaded world coordinate that may be read in any room rotation. */
    @JvmStatic
    fun catalogWorldPositions(roomBaseX: Int, roomBaseZ: Int, catalog: CatalogResult?): List<WorldPosition> {
        val valid = catalog ?: return emptyList()
        if (!validCatalog(valid)) return emptyList()
        val positions = LinkedHashSet<WorldPosition>()
        for (rotation in 0 until ROTATION_COUNT) {
            for (pair in valid.pairs()) {
                positions.add(worldPosition(roomBaseX, roomBaseZ, rotation, pair.first!!))
                positions.add(worldPosition(roomBaseX, roomBaseZ, rotation, pair.second!!))
            }
        }
        return JavaList.copyOf(positions)
    }

    /** Matches only pairs whose two loaded blocks are sea lanterns and accepts one unique rotation. */
    @JvmStatic
    fun detect(
        roomVolumeLoaded: Boolean,
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: CatalogResult?,
        observedBlocks: List<ObservedBlock?>?,
    ): Detection {
        if (!roomVolumeLoaded) return unknown(UnknownReason.UNLOADED_ROOM_VOLUME)
        if (catalog == null || !catalog.available()) return unknown(UnknownReason.CATALOG_UNAVAILABLE)
        if (!validCatalog(catalog)) return unknown(UnknownReason.INVALID_CATALOG)

        val observed = validatedObservations(roomBaseX, roomBaseZ, catalog, observedBlocks)
            ?: return unknown(observationFailure(roomBaseX, roomBaseZ, catalog, observedBlocks))

        val matches = ArrayList<OrientationCandidate>()
        for (rotation in 0 until ROTATION_COUNT) {
            val pairs = ArrayList<BeamPair>()
            for (catalogPair in catalog.pairs()) {
                val first = worldPosition(roomBaseX, roomBaseZ, rotation, catalogPair.first!!)
                val second = worldPosition(roomBaseX, roomBaseZ, rotation, catalogPair.second!!)
                if (observed[first] == BlockKind.SEA_LANTERN && observed[second] == BlockKind.SEA_LANTERN) {
                    pairs.add(BeamPair(Endpoint(first.x, first.y, first.z), Endpoint(second.x, second.y, second.z)))
                }
            }
            if (pairs.size >= DungeonCreeperBeamsRules.REQUIRED_MIN_PAIRS) {
                matches.add(OrientationCandidate(rotation, pairs))
            }
        }
        if (matches.isEmpty()) return unknown(UnknownReason.NO_CATALOG_MATCH)
        if (matches.size != 1) return unknown(UnknownReason.AMBIGUOUS_ORIENTATION)

        val match = matches.first()
        val uniqueStarts = HashSet<Endpoint>()
        for (pair in match.pairs) {
            val first = pair.first ?: return unknown(UnknownReason.INVALID_CATALOG)
            val second = pair.second ?: return unknown(UnknownReason.INVALID_CATALOG)
            if (first == second || !uniqueStarts.add(first)) return unknown(UnknownReason.INVALID_CATALOG)
        }
        return Detection(match.rotation, JavaList.copyOf(match.pairs), null)
    }

    /** Keeps a previously resolved room rotation stable as endpoints change from lantern to prismarine. */
    @JvmStatic
    fun detectResolvedRotation(
        roomVolumeLoaded: Boolean,
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: CatalogResult?,
        observedBlocks: List<ObservedBlock?>?,
        resolvedRotation: Int,
    ): Detection {
        if (!roomVolumeLoaded) return unknown(UnknownReason.UNLOADED_ROOM_VOLUME)
        if (catalog == null || !catalog.available()) return unknown(UnknownReason.CATALOG_UNAVAILABLE)
        if (!validCatalog(catalog) || resolvedRotation < 0 || resolvedRotation >= ROTATION_COUNT) {
            return unknown(UnknownReason.INVALID_CATALOG)
        }
        val observed = validatedObservations(roomBaseX, roomBaseZ, catalog, observedBlocks)
            ?: return unknown(observationFailure(roomBaseX, roomBaseZ, catalog, observedBlocks))

        val pairs = ArrayList<BeamPair>()
        for (catalogPair in catalog.pairs()) {
            val first = worldPosition(roomBaseX, roomBaseZ, resolvedRotation, catalogPair.first!!)
            val second = worldPosition(roomBaseX, roomBaseZ, resolvedRotation, catalogPair.second!!)
            if (isKnownEndpointBlock(observed[first]) && isKnownEndpointBlock(observed[second])) {
                pairs.add(BeamPair(Endpoint(first.x, first.y, first.z), Endpoint(second.x, second.y, second.z)))
            }
        }
        if (pairs.size < DungeonCreeperBeamsRules.REQUIRED_MIN_PAIRS) {
            return unknown(UnknownReason.NO_CATALOG_MATCH)
        }
        return Detection(resolvedRotation, JavaList.copyOf(pairs), null)
    }

    private fun validatedObservations(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: CatalogResult,
        observedBlocks: List<ObservedBlock?>?,
    ): Map<WorldPosition, BlockKind>? {
        val expectedPositions = catalogWorldPositions(roomBaseX, roomBaseZ, catalog)
        val observed = HashMap<WorldPosition, BlockKind>()
        if (observedBlocks != null) {
            for (block in observedBlocks) {
                val position = block?.position ?: return null
                val blockKind = block.blockKind ?: return null
                if (observed.putIfAbsent(position, blockKind) != null) return null
            }
        }
        for (position in expectedPositions) {
            if (!observed.containsKey(position)) return null
        }
        return observed
    }

    private fun observationFailure(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: CatalogResult,
        observedBlocks: List<ObservedBlock?>?,
    ): UnknownReason {
        val unique = HashMap<WorldPosition, BlockKind>()
        if (observedBlocks != null) {
            for (block in observedBlocks) {
                val position = block?.position ?: return UnknownReason.DUPLICATE_OR_INVALID_OBSERVATION
                val blockKind = block.blockKind ?: return UnknownReason.DUPLICATE_OR_INVALID_OBSERVATION
                if (unique.putIfAbsent(position, blockKind) != null) {
                    return UnknownReason.DUPLICATE_OR_INVALID_OBSERVATION
                }
            }
        }
        for (position in catalogWorldPositions(roomBaseX, roomBaseZ, catalog)) {
            if (!unique.containsKey(position)) return UnknownReason.INCOMPLETE_ENDPOINT_OBSERVATIONS
        }
        return UnknownReason.DUPLICATE_OR_INVALID_OBSERVATION
    }

    private fun isKnownEndpointBlock(kind: BlockKind?): Boolean =
        kind == BlockKind.SEA_LANTERN || kind == BlockKind.PRISMARINE

    /** Stable HUD text using room-local coordinates; it does not issue interactions. */
    @JvmStatic
    fun guidanceLines(detection: Detection?, roomBaseX: Int, roomBaseZ: Int): List<String> {
        if (detection == null || !detection.known() || detection.pairs().isEmpty()) return emptyList()
        val pairs = detection.pairs()
        val lines = ArrayList<String>(minOf(pairs.size, 8) + 2)
        lines.add("Catalog match · rotation ${detection.rotation()}")
        val visiblePairs = minOf(pairs.size, 7)
        for (index in 0 until visiblePairs) {
            val pair = pairs[index]
            lines.add("Pair ${index + 1}: ${localPosition(pair.first!!, roomBaseX, roomBaseZ)} " +
                "↔ ${localPosition(pair.second!!, roomBaseX, roomBaseZ)}")
        }
        if (pairs.size > visiblePairs) lines.add("${pairs.size - visiblePairs} more catalog pairs not shown")
        return JavaList.copyOf(lines)
    }

    /** Returns endpoints from one complete, unambiguous catalog match. */
    @JvmStatic
    fun endpoints(detection: Detection?): Set<Endpoint> {
        if (detection == null || !detection.known()) return emptySet()
        val endpoints = HashSet<Endpoint>()
        for (pair in detection.pairs()) {
            if (pair == null) continue
            pair.first?.let(endpoints::add)
            pair.second?.let(endpoints::add)
        }
        return JavaSet.copyOf(endpoints)
    }

    /** Returns all catalog pairs at one validated rotation, including pairs changed by progress. */
    @JvmStatic
    fun catalogPairsForRotation(
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: CatalogResult?,
        rotation: Int,
    ): List<BeamPair> {
        if (!validCatalog(catalog) || rotation < 0 || rotation >= ROTATION_COUNT) return emptyList()
        val pairs = ArrayList<BeamPair>()
        for (pair in catalog!!.pairs()) {
            val first = worldPosition(roomBaseX, roomBaseZ, rotation, pair.first!!)
            val second = worldPosition(roomBaseX, roomBaseZ, rotation, pair.second!!)
            pairs.add(BeamPair(Endpoint(first.x, first.y, first.z), Endpoint(second.x, second.y, second.z)))
        }
        return JavaList.copyOf(pairs)
    }

    /** Exact world-coordinate membership; unknown detections never confirm a click target. */
    @JvmStatic
    fun containsEndpoint(detection: Detection?, position: WorldPosition?): Boolean {
        if (position == null || detection == null || !detection.known()) return false
        val endpoint = Endpoint(position.x, position.y, position.z)
        return detection.pairs().any { pair -> pair != null &&
            (endpoint == pair.first || endpoint == pair.second) }
    }

    private fun localPosition(endpoint: Endpoint, roomBaseX: Int, roomBaseZ: Int): String =
        "(${endpoint.x - roomBaseX},${endpoint.y},${endpoint.z - roomBaseZ})"

    private fun validCatalog(catalog: CatalogResult?): Boolean {
        if (catalog == null || !catalog.available() ||
            catalog.pairs().size < DungeonCreeperBeamsRules.REQUIRED_MIN_PAIRS ||
            catalog.pairs().size > DungeonCreeperBeamsRules.MAX_CATALOG_PAIRS
        ) return false
        val uniqueStarts = HashSet<Point>()
        for (pair in catalog.pairs()) {
            val first = pair.first ?: return false
            val second = pair.second ?: return false
            if (!validPoint(first) || !validPoint(second) || first == second || !uniqueStarts.add(first)) return false
        }
        return true
    }

    private fun validPoint(point: Point?): Boolean =
        point != null && point.x >= 0 && point.x < DungeonGridRules.ROOM_SIZE &&
            point.z >= 0 && point.z < DungeonGridRules.ROOM_SIZE &&
            point.y >= MIN_CATALOG_Y && point.y <= MAX_CATALOG_Y

    private fun worldPosition(roomBaseX: Int, roomBaseZ: Int, rotation: Int, point: Point): WorldPosition {
        val rotated = DungeonGridRules.toWorld(rotation, point.x, point.z)
        return WorldPosition(roomBaseX + rotated.x, point.y, roomBaseZ + rotated.z)
    }

    private fun unknown(reason: UnknownReason): Detection = Detection(-1, JavaList.of(), reason)

    private data class OrientationCandidate(val rotation: Int, val pairs: List<BeamPair>)
}
