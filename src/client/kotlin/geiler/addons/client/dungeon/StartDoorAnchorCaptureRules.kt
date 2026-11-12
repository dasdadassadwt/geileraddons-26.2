package geiler.addons.client.dungeon

import net.minecraft.core.BlockPos
import java.util.List as JavaList
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Java-facing values and entrypoints for the Kotlin Start-door capture rules. */
class StartDoorAnchorCaptureRules private constructor() {
    companion object {
        @JvmField
        val MAX_DIAGNOSTIC_EVENTS_PER_RUN: Int = StartDoorAnchorCaptureRulesLogic.MAX_DIAGNOSTIC_EVENTS_PER_RUN

        @JvmStatic
        fun bounds(first: BlockPos?, second: BlockPos?): Bounds? = StartDoorAnchorCaptureRulesLogic.bounds(first, second)

        @JvmStatic
        fun supportedSource(blockId: String?): Boolean = StartDoorAnchorCaptureRulesLogic.supportedSource(blockId)

        /** Dungeon/map frame identity; level comparison deliberately uses object identity. */
        @JvmStatic
        fun sameSession(
            firstLevel: Any?,
            firstRun: Long,
            firstMapId: String?,
            firstEntranceCell: Int,
            nextLevel: Any?,
            nextRun: Long,
            nextMapId: String?,
            nextEntranceCell: Int,
        ): Boolean = StartDoorAnchorCaptureRulesLogic.sameSession(
            firstLevel, firstRun, firstMapId, firstEntranceCell, nextLevel, nextRun, nextMapId, nextEntranceCell,
        )

        /** Describes where the captured inclusive region falls relative to the Entrance grid cell. */
        @JvmStatic
        fun gridRelation(bounds: Bounds?, entranceBaseX: Int, entranceBaseZ: Int): GridRelation =
            StartDoorAnchorCaptureRulesLogic.gridRelation(bounds, entranceBaseX, entranceBaseZ)
    }

    @JvmRecord
    data class Bounds(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
        fun width(): Int = maxX - minX + 1
        fun height(): Int = maxY - minY + 1
        fun depth(): Int = maxZ - minZ + 1
    }

    /** Snapshots and normalizes its former Java record components during construction. */
    class GridRelation(
        private val relativeMinXValue: Int,
        private val relativeMaxXValue: Int,
        private val relativeMinZValue: Int,
        private val relativeMaxZValue: Int,
        private val minCellOffsetXValue: Int,
        private val maxCellOffsetXValue: Int,
        private val minCellOffsetZValue: Int,
        private val maxCellOffsetZValue: Int,
        xGapLines: List<Int?>?,
        zGapLines: List<Int?>?,
        orientation: String?,
    ) {
        private val xGapLinesValue: List<Int> = JavaList.copyOf(xGapLines?.map { it!! } ?: emptyList())
        private val zGapLinesValue: List<Int> = JavaList.copyOf(zGapLines?.map { it!! } ?: emptyList())
        private val orientationValue: String = orientation ?: "unknown"

        fun relativeMinX(): Int = relativeMinXValue
        fun relativeMaxX(): Int = relativeMaxXValue
        fun relativeMinZ(): Int = relativeMinZValue
        fun relativeMaxZ(): Int = relativeMaxZValue
        fun minCellOffsetX(): Int = minCellOffsetXValue
        fun maxCellOffsetX(): Int = maxCellOffsetXValue
        fun minCellOffsetZ(): Int = minCellOffsetZValue
        fun maxCellOffsetZ(): Int = maxCellOffsetZValue
        fun xGapLines(): List<Int> = xGapLinesValue
        fun zGapLines(): List<Int> = zGapLinesValue
        fun orientation(): String = orientationValue

        fun summary(): String = "orientation=$orientationValue relativeX=[$relativeMinXValue..$relativeMaxXValue] " +
            "relativeZ=[$relativeMinZValue..$relativeMaxZValue] cellOffsetX=[$minCellOffsetXValue..$maxCellOffsetXValue] " +
            "cellOffsetZ=[$minCellOffsetZValue..$maxCellOffsetZValue] xGapLines=$xGapLinesValue " +
            "zGapLines=$zGapLinesValue axes=world-X/Z-aligned-to-map-grid-X/Z"

        override fun equals(other: Any?): Boolean = this === other || other is GridRelation &&
            relativeMinXValue == other.relativeMinXValue && relativeMaxXValue == other.relativeMaxXValue &&
            relativeMinZValue == other.relativeMinZValue && relativeMaxZValue == other.relativeMaxZValue &&
            minCellOffsetXValue == other.minCellOffsetXValue && maxCellOffsetXValue == other.maxCellOffsetXValue &&
            minCellOffsetZValue == other.minCellOffsetZValue && maxCellOffsetZValue == other.maxCellOffsetZValue &&
            xGapLinesValue == other.xGapLinesValue && zGapLinesValue == other.zGapLinesValue &&
            orientationValue == other.orientationValue

        override fun hashCode(): Int {
            var result = relativeMinXValue
            result = 31 * result + relativeMaxXValue
            result = 31 * result + relativeMinZValue
            result = 31 * result + relativeMaxZValue
            result = 31 * result + minCellOffsetXValue
            result = 31 * result + maxCellOffsetXValue
            result = 31 * result + minCellOffsetZValue
            result = 31 * result + maxCellOffsetZValue
            result = 31 * result + xGapLinesValue.hashCode()
            result = 31 * result + zGapLinesValue.hashCode()
            result = 31 * result + orientationValue.hashCode()
            return result
        }

        override fun toString(): String = "GridRelation[relativeMinX=$relativeMinXValue, " +
            "relativeMaxX=$relativeMaxXValue, relativeMinZ=$relativeMinZValue, relativeMaxZ=$relativeMaxZValue, " +
            "minCellOffsetX=$minCellOffsetXValue, maxCellOffsetX=$maxCellOffsetXValue, " +
            "minCellOffsetZ=$minCellOffsetZValue, maxCellOffsetZ=$maxCellOffsetZValue, " +
            "xGapLines=$xGapLinesValue, zGapLines=$zGapLinesValue, orientation=$orientationValue]"
    }

    /** Small per-run cap for user-triggered capture diagnostics. */
    class DiagnosticBudget {
        private val state = StartDoorAnchorCaptureRulesLogic.DiagnosticBudgetState()

        fun switchRun(nextRunGeneration: Long): Long = state.switchRun(nextRunGeneration)
        fun allowDetail(): Boolean = state.allowDetail()
        fun suppressed(): Long = state.suppressed()
        fun takeSuppressed(): Long = state.takeSuppressed()
    }
}
