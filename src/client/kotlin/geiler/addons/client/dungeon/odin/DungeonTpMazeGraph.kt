package geiler.addons.client.dungeon.odin

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/**
 * Learns only directed pad-to-pad transitions reported by a sufficiently large client position
 * correction. It contains no room catalog, rotation table, or guessed maze route.
 */
@JvmSuppressWildcards
class DungeonTpMazeGraph {
    private val core = DungeonTpMazeGraphCore()

    fun observeCorrection(
        currentlyObservedPads: Collection<Pad?>?,
        before: Position?,
        after: Position?,
        completeLoadedScan: Boolean,
    ): Boolean = core.observeCorrection(currentlyObservedPads, before, after, completeLoadedScan)

    /** Keeps links only while both physical pads remain in a complete current-room observation. */
    fun retainObservedPads(currentlyObservedPads: Collection<Pad?>?, completeLoadedScan: Boolean) =
        core.retainObservedPads(currentlyObservedPads, completeLoadedScan)

    fun visibleLinks(
        currentlyObservedPads: Collection<Pad?>?,
        activePuzzle: Boolean,
        completeLoadedScan: Boolean,
        sameLevel: Boolean,
        sameRoom: Boolean,
    ): List<Edge> = core.visibleLinks(currentlyObservedPads, activePuzzle, completeLoadedScan, sameLevel, sameRoom)

    fun destinations(source: PadKey?): List<PadKey> = core.destinations(source)
    fun sources(destination: PadKey?): List<PadKey> = core.sources(destination)
    fun linkCount(): Int = core.linkCount()
    fun clear() = core.clear()

    @JvmRecord
    data class PadKey(val anchorX: Int, val anchorY: Int, val anchorZ: Int) : Comparable<PadKey> {
        override fun compareTo(other: PadKey): Int = DungeonTpMazeGraphCore.comparePadKeys(this, other)
        override fun toString(): String = "PadKey[anchorX=$anchorX, anchorY=$anchorY, anchorZ=$anchorZ]"
    }

    @JvmRecord
    data class Position(val x: Double, val y: Double, val z: Double) {
        fun finite(): Boolean = DungeonTpMazeGraphCore.isFinite(this)
        fun distanceSquared(other: Position): Double = DungeonTpMazeGraphCore.distanceSquared(this, other)
        override fun toString(): String = "Position[x=$x, y=$y, z=$z]"
    }

    @JvmRecord
    data class Pad(val key: PadKey?, val position: Position?) {
        override fun toString(): String = "Pad[key=$key, position=$position]"
    }

    @JvmRecord
    data class Edge(val from: PadKey?, val to: PadKey?) {
        override fun toString(): String = "Edge[from=$from, to=$to]"
    }

    companion object {
        @JvmStatic
        fun uniquePadAt(pads: Collection<Pad?>?, position: Position?): PadKey? =
            DungeonTpMazeGraphCore.uniquePadAt(pads, position)
    }
}
