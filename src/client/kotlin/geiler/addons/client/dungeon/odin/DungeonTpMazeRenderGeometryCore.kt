package geiler.addons.client.dungeon.odin

/** Pure render gating and projection for already observed TP Maze links. */
internal object DungeonTpMazeRenderGeometryCore {
    fun padStyle(
        visited: Boolean,
        candidate: Boolean,
        remainingCandidates: Int,
    ): DungeonTpMazeRenderGeometry.PadStyle = when {
        visited -> DungeonTpMazeRenderGeometry.PadStyle.VISITED
        !candidate || remainingCandidates <= 0 -> DungeonTpMazeRenderGeometry.PadStyle.UNKNOWN
        remainingCandidates == 1 -> DungeonTpMazeRenderGeometry.PadStyle.SINGLE_CANDIDATE
        else -> DungeonTpMazeRenderGeometry.PadStyle.MULTIPLE_CANDIDATES
    }

    /** Only the confirmed single route is drawn through walls; hints and visited pads stay occluded. */
    fun depthTested(style: DungeonTpMazeRenderGeometry.PadStyle?): Boolean =
        style != DungeonTpMazeRenderGeometry.PadStyle.SINGLE_CANDIDATE

    /** The cyan camera-to-pad hint remains occluded; only the green single-candidate pad is through-wall. */
    fun tracerDepthTested(): Boolean = true

    fun shouldRender(
        activePuzzle: Boolean,
        completeLoadedScan: Boolean,
        sameLevel: Boolean,
        sameRoom: Boolean,
        endpointsStillLoadedAsPortals: Boolean,
        links: List<DungeonTpMazeGraph.Edge?>?,
    ): Boolean = activePuzzle && completeLoadedScan && sameLevel && sameRoom && endpointsStillLoadedAsPortals &&
        links != null && links.isNotEmpty()

    fun segment(
        from: DungeonTpMazeGraph.Position?,
        to: DungeonTpMazeGraph.Position?,
    ): DungeonTpMazeRenderGeometry.Segment? {
        if (from == null || to == null || !from.finite() || !to.finite()) return null
        return DungeonTpMazeRenderGeometry.Segment(
            DungeonTpMazeRenderGeometry.Point(from.x, from.y + 0.15, from.z),
            DungeonTpMazeRenderGeometry.Point(to.x, to.y + 0.15, to.z),
        )
    }

    fun cameraToPadTracer(
        from: DungeonTpMazeGraph.Position?,
        to: DungeonTpMazeGraph.Position?,
    ): DungeonTpMazeRenderGeometry.Segment? {
        if (from == null || to == null || !from.finite() || !to.finite()) return null
        return DungeonTpMazeRenderGeometry.Segment(
            DungeonTpMazeRenderGeometry.Point(from.x, from.y, from.z),
            DungeonTpMazeRenderGeometry.Point(to.x, to.y, to.z),
        )
    }
}
