package geiler.addons.client.dungeon

import net.minecraft.core.BlockPos

/** Kotlin implementation behind the Java record and call boundary for Start-door capture rules. */
object StartDoorAnchorCaptureRulesLogic {
    private val gridStride = DungeonGridRules.CELL_STRIDE
    private val gapLocalCoordinate = DungeonGridRules.ROOM_SIZE

    const val MAX_DIAGNOSTIC_EVENTS_PER_RUN = 32

    /** Returns inclusive bounds, or null when either corner is missing or both are identical. */
    fun bounds(first: BlockPos?, second: BlockPos?): StartDoorAnchorCaptureRules.Bounds? {
        if (first == null || second == null || first == second) return null
        return StartDoorAnchorCaptureRules.Bounds(
            minOf(first.x, second.x),
            minOf(first.y, second.y),
            minOf(first.z, second.z),
            maxOf(first.x, second.x),
            maxOf(first.y, second.y),
            maxOf(first.z, second.z),
        )
    }

    fun supportedSource(blockId: String?): Boolean =
        blockId == "minecraft:chiseled_stone_bricks" || blockId == "minecraft:infested_chiseled_stone_bricks"

    /** Dungeon/map frame identity; level comparison deliberately uses object identity. */
    fun sameSession(
        firstLevel: Any?,
        firstRun: Long,
        firstMapId: String?,
        firstEntranceCell: Int,
        nextLevel: Any?,
        nextRun: Long,
        nextMapId: String?,
        nextEntranceCell: Int,
    ): Boolean = firstLevel != null && firstLevel === nextLevel && firstRun > 0 && firstRun == nextRun &&
        firstMapId != null && firstMapId == nextMapId && firstEntranceCell >= 0 && firstEntranceCell == nextEntranceCell

    /** Describes where the captured inclusive region falls relative to the Entrance grid cell. */
    fun gridRelation(
        bounds: StartDoorAnchorCaptureRules.Bounds?,
        entranceBaseX: Int,
        entranceBaseZ: Int,
    ): StartDoorAnchorCaptureRules.GridRelation {
        if (bounds == null) {
            return StartDoorAnchorCaptureRules.GridRelation(0, 0, 0, 0, 0, 0, 0, 0, emptyList(), emptyList(), "unavailable")
        }
        val relativeMinX = bounds.minX - entranceBaseX
        val relativeMaxX = bounds.maxX - entranceBaseX
        val relativeMinZ = bounds.minZ - entranceBaseZ
        val relativeMaxZ = bounds.maxZ - entranceBaseZ
        val xGapLines = gapLines(relativeMinX, relativeMaxX)
        val zGapLines = gapLines(relativeMinZ, relativeMaxZ)
        val orientation = when {
            xGapLines.isNotEmpty() && zGapLines.isEmpty() -> "gap-axis-X"
            xGapLines.isEmpty() && zGapLines.isNotEmpty() -> "gap-axis-Z"
            xGapLines.isNotEmpty() -> "crosses-X-and-Z-grid-gaps"
            else -> "does-not-cross-a-grid-gap"
        }
        return StartDoorAnchorCaptureRules.GridRelation(
            relativeMinX,
            relativeMaxX,
            relativeMinZ,
            relativeMaxZ,
            Math.floorDiv(relativeMinX, gridStride),
            Math.floorDiv(relativeMaxX, gridStride),
            Math.floorDiv(relativeMinZ, gridStride),
            Math.floorDiv(relativeMaxZ, gridStride),
            xGapLines,
            zGapLines,
            orientation,
        )
    }

    private fun gapLines(minimum: Int, maximum: Int): List<Int> {
        val lines = ArrayList<Int>()
        var line = Math.floorDiv(minimum - gapLocalCoordinate, gridStride).toLong() * gridStride + gapLocalCoordinate
        if (line < minimum) line += gridStride
        while (line <= maximum) {
            lines.add(line.toInt())
            line += gridStride
        }
        return java.util.List.copyOf(lines)
    }

    /** Kotlin-owned per-run diagnostic state behind the Java wrapper. */
    class DiagnosticBudgetState {
        private var runGeneration = Long.MIN_VALUE
        private var emitted = 0
        private var suppressed = 0L
        private var suppressionSummaryEmitted = false

        /** Advances the run window and returns any detail count suppressed in the previous run. */
        fun switchRun(nextRunGeneration: Long): Long {
            if (runGeneration == nextRunGeneration) return 0
            val previousSuppressed = takeSuppressed()
            runGeneration = nextRunGeneration
            emitted = 0
            suppressed = 0
            suppressionSummaryEmitted = false
            return previousSuppressed
        }

        fun allowDetail(): Boolean {
            if (emitted >= MAX_DIAGNOSTIC_EVENTS_PER_RUN) {
                suppressed++
                return false
            }
            emitted++
            return true
        }

        fun suppressed(): Long = suppressed

        /** Emits at most one suppression summary for this run and prevents repeated cancel spam. */
        fun takeSuppressed(): Long {
            if (suppressionSummaryEmitted || suppressed <= 0) return 0
            suppressionSummaryEmitted = true
            val result = suppressed
            suppressed = 0
            return result
        }
    }
}
