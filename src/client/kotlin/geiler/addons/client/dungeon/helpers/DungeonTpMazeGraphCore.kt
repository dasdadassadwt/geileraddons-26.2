package geiler.addons.client.dungeon.helpers

import java.util.Comparator
import java.util.LinkedHashSet

/** Learns directed transitions only from unique, sufficiently large observed corrections. */
internal class DungeonTpMazeGraphCore {
    private data class Link(val from: DungeonTpMazeGraph.PadKey, val to: DungeonTpMazeGraph.PadKey)

    private val observedLinks = LinkedHashSet<Link>()

    fun observeCorrection(
        currentlyObservedPads: Collection<DungeonTpMazeGraph.Pad?>?,
        before: DungeonTpMazeGraph.Position?,
        after: DungeonTpMazeGraph.Position?,
        completeLoadedScan: Boolean,
    ): Boolean {
        if (!completeLoadedScan || currentlyObservedPads == null || before == null || after == null ||
            !before.finite() || !after.finite() ||
            before.distanceSquared(after) < MIN_CORRECTION_DISTANCE_SQUARED
        ) return false

        val source = uniquePadAt(currentlyObservedPads, before) ?: return false
        val destination = uniquePadAt(currentlyObservedPads, after) ?: return false
        if (source == destination) return false
        return observedLinks.add(Link(source, destination))
    }

    fun retainObservedPads(currentlyObservedPads: Collection<DungeonTpMazeGraph.Pad?>?, completeLoadedScan: Boolean) {
        if (!completeLoadedScan) return
        val observed = currentlyObservedPads?.mapNotNull { it?.key }?.toSet() ?: emptySet()
        observedLinks.removeIf { link -> !observed.contains(link.from) || !observed.contains(link.to) }
    }

    fun visibleLinks(
        currentlyObservedPads: Collection<DungeonTpMazeGraph.Pad?>?,
        activePuzzle: Boolean,
        completeLoadedScan: Boolean,
        sameLevel: Boolean,
        sameRoom: Boolean,
    ): List<DungeonTpMazeGraph.Edge> {
        if (!activePuzzle || !completeLoadedScan || !sameLevel || !sameRoom || currentlyObservedPads == null)
            return java.util.List.of()
        val observed = currentlyObservedPads.mapNotNull { it?.key }.toSet()
        val sorted = observedLinks.asSequence()
            .filter { observed.contains(it.from) && observed.contains(it.to) }
            .sortedWith(compareBy<Link>({ it.from }, { it.to }))
            .map { DungeonTpMazeGraph.Edge(it.from, it.to) }
            .toList()
        return java.util.List.copyOf(sorted)
    }

    fun destinations(source: DungeonTpMazeGraph.PadKey?): List<DungeonTpMazeGraph.PadKey> {
        if (source == null) return java.util.List.of()
        val result = observedLinks.asSequence()
            .filter { it.from == source }
            .map { it.to }
            .distinct()
            .sorted()
            .toList()
        return java.util.List.copyOf(result)
    }

    fun sources(destination: DungeonTpMazeGraph.PadKey?): List<DungeonTpMazeGraph.PadKey> {
        if (destination == null) return java.util.List.of()
        val result = observedLinks.asSequence()
            .filter { it.to == destination }
            .map { it.from }
            .distinct()
            .sorted()
            .toList()
        return java.util.List.copyOf(result)
    }

    fun linkCount(): Int = observedLinks.size

    fun clear() {
        observedLinks.clear()
    }

    companion object {
        private const val MATCH_RADIUS_SQUARED = 3.5 * 3.5
        private const val MIN_CORRECTION_DISTANCE_SQUARED = 8.0 * 8.0

        @JvmStatic
        fun uniquePadAt(
            pads: Collection<DungeonTpMazeGraph.Pad?>?,
            position: DungeonTpMazeGraph.Position?,
        ): DungeonTpMazeGraph.PadKey? {
            if (pads.isNullOrEmpty() || position == null || !position.finite()) return null
            var match: DungeonTpMazeGraph.PadKey? = null
            for (pad in pads) {
                val key = pad?.key ?: continue
                val padPosition = pad.position ?: continue
                if (!padPosition.finite() || padPosition.distanceSquared(position) > MATCH_RADIUS_SQUARED) continue
                // Multiple portal clusters in range make this endpoint ambiguous.
                if (match != null) return null
                match = key
            }
            return match
        }

        @JvmStatic
        fun comparePadKeys(first: DungeonTpMazeGraph.PadKey, second: DungeonTpMazeGraph.PadKey): Int {
            val byX = Integer.compare(first.anchorX, second.anchorX)
            if (byX != 0) return byX
            val byZ = Integer.compare(first.anchorZ, second.anchorZ)
            return if (byZ != 0) byZ else Integer.compare(first.anchorY, second.anchorY)
        }

        @JvmStatic
        fun isFinite(position: DungeonTpMazeGraph.Position): Boolean =
            position.x.isFinite() && position.y.isFinite() && position.z.isFinite()

        @JvmStatic
        fun distanceSquared(from: DungeonTpMazeGraph.Position, to: DungeonTpMazeGraph.Position): Double {
            val dx = from.x - to.x
            val dy = from.y - to.y
            val dz = from.z - to.z
            return dx * dx + dy * dy + dz * dz
        }
    }
}
