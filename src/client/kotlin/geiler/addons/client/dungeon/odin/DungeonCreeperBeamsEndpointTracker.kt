package geiler.addons.client.dungeon.odin

import java.util.HashMap
import java.util.HashSet

/** Tracks only endpoints supported by the latest complete scan in one room and world. */
class DungeonCreeperBeamsEndpointTracker {
    private var levelIdentity: Any? = null
    private var roomIdentity: String? = null
    private var confirmed: Set<DungeonCreeperBeamsRules.Endpoint> = emptySet()
    private var progressedPairs: Set<DungeonCreeperBeamsRules.Pair> = emptySet()

    /** Replaces endpoint confirmation and reports each catalog pair's first confirmed prismarine transition. */
    fun update(
        level: Any?,
        room: String?,
        roomBaseX: Int,
        roomBaseZ: Int,
        catalog: DungeonCreeperBeamsCatalog.CatalogResult?,
        detection: DungeonCreeperBeamsRules.Detection?,
        observations: List<DungeonCreeperBeamsRules.ObservedBlock?>?,
    ): List<DungeonCreeperBeamsRules.Pair> {
        val sameScope = level != null && level === levelIdentity && room == roomIdentity
        val previous = if (sameScope) confirmed else emptySet()
        val previouslyProgressed = if (sameScope) progressedPairs else emptySet()
        levelIdentity = level
        roomIdentity = room
        if (!sameScope) progressedPairs = emptySet()

        if (level == null || room == null || detection == null || !detection.known()) {
            confirmed = emptySet()
            return emptyList()
        }

        val next = HashSet(DungeonCreeperBeamsRules.endpoints(detection))
        val nextProgressed = HashSet(previouslyProgressed)
        val newlyProgressed = HashSet<DungeonCreeperBeamsRules.Pair>()
        if (observations != null) {
            val observed = HashMap<DungeonCreeperBeamsRules.Endpoint, DungeonCreeperBeamsRules.BlockKind>()
            for (block in observations) {
                val position = block?.position ?: continue
                val blockKind = block.blockKind ?: continue
                observed[DungeonCreeperBeamsRules.Endpoint(position.x, position.y, position.z)] = blockKind
            }
            for (pair in DungeonCreeperBeamsRules.catalogPairsForRotation(
                roomBaseX,
                roomBaseZ,
                catalog,
                detection.rotation(),
            )) {
                val firstEndpoint = pair.first!!
                val secondEndpoint = pair.second!!
                val first = observed[firstEndpoint]
                val second = observed[secondEndpoint]
                val hasProgress = first == DungeonCreeperBeamsRules.BlockKind.PRISMARINE ||
                    second == DungeonCreeperBeamsRules.BlockKind.PRISMARINE
                val pairStillObserved = isKnownEndpointBlock(first) && isKnownEndpointBlock(second)
                if (hasProgress && pairStillObserved) {
                    if (firstEndpoint in previous) next.add(firstEndpoint)
                    if (secondEndpoint in previous) next.add(secondEndpoint)
                    if (nextProgressed.add(pair)) newlyProgressed.add(pair)
                }
            }
        }
        confirmed = java.util.Set.copyOf(next)
        progressedPairs = java.util.Set.copyOf(nextProgressed)
        return java.util.List.copyOf(newlyProgressed)
    }

    private fun isKnownEndpointBlock(kind: DungeonCreeperBeamsRules.BlockKind?): Boolean =
        kind == DungeonCreeperBeamsRules.BlockKind.SEA_LANTERN ||
            kind == DungeonCreeperBeamsRules.BlockKind.PRISMARINE

    /** A scope mismatch invalidates the old set, even if a caller later returns to the old room. */
    fun contains(level: Any?, room: String?, endpoint: DungeonCreeperBeamsRules.Endpoint?): Boolean {
        if (level == null || level !== levelIdentity || room == null || room != roomIdentity) {
            clear()
            return false
        }
        return endpoint != null && endpoint in confirmed
    }

    fun clear() {
        levelIdentity = null
        roomIdentity = null
        confirmed = emptySet()
        progressedPairs = emptySet()
    }

    /** Drops click authorization while preserving the session's already-reported pair transitions. */
    fun clearConfirmation() {
        confirmed = emptySet()
    }
}
