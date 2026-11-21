package geiler.addons.client.dungeon.helpers

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Pure render gating/projection for observed TP Maze links; coordinates are already world-space. */
@JvmSuppressWildcards
class DungeonTpMazeRenderGeometry private constructor() {
    enum class PadStyle { VISITED, SINGLE_CANDIDATE, MULTIPLE_CANDIDATES, UNKNOWN }

    @JvmRecord
    data class Point(val x: Double, val y: Double, val z: Double) {
        override fun toString(): String = "Point[x=$x, y=$y, z=$z]"
    }

    @JvmRecord
    data class Segment(val from: Point?, val to: Point?) {
        override fun toString(): String = "Segment[from=$from, to=$to]"
    }

    companion object {
        /** Odin's pad colors distinguish visited pads from one or several remaining view candidates. */
        @JvmStatic
        fun padStyle(visited: Boolean, candidate: Boolean, remainingCandidates: Int): PadStyle =
            DungeonTpMazeRenderGeometryCore.padStyle(visited, candidate, remainingCandidates)

        @JvmStatic
        fun depthTested(style: PadStyle?): Boolean = DungeonTpMazeRenderGeometryCore.depthTested(style)

        @JvmStatic
        fun tracerDepthTested(): Boolean = DungeonTpMazeRenderGeometryCore.tracerDepthTested()

        @JvmStatic
        fun shouldRender(
            activePuzzle: Boolean,
            completeLoadedScan: Boolean,
            sameLevel: Boolean,
            sameRoom: Boolean,
            endpointsStillLoadedAsPortals: Boolean,
            links: List<DungeonTpMazeGraph.Edge?>?,
        ): Boolean = DungeonTpMazeRenderGeometryCore.shouldRender(
            activePuzzle,
            completeLoadedScan,
            sameLevel,
            sameRoom,
            endpointsStillLoadedAsPortals,
            links,
        )

        /** Projects observed portal centers directly; no room-template rotation or external coordinates apply. */
        @JvmStatic
        fun segment(from: DungeonTpMazeGraph.Position?, to: DungeonTpMazeGraph.Position?): Segment? =
            DungeonTpMazeRenderGeometryCore.segment(from, to)

        /** Keeps the camera endpoint on its view ray; the caller supplies the portal target height. */
        @JvmStatic
        fun cameraToPadTracer(from: DungeonTpMazeGraph.Position?, to: DungeonTpMazeGraph.Position?): Segment? =
            DungeonTpMazeRenderGeometryCore.cameraToPadTracer(from, to)
    }
}
