package geiler.addons.client.enchanting

import java.util.ArrayList
import java.util.EnumMap
import java.util.HashSet

/** Ordered live-container slots shared by board capture, rendering, and click filtering. */
class ExperimentBoardGeometry(columns: Int, rows: Int, slotIds: List<Int?>?) {
    private val columnCount = columns
    private val rowCount = rows
    private val orderedSlotIds =
        ExperimentBoardGeometryConstructorSupport.normalizeSlotIds(columns, rows, slotIds)

    fun columns(): Int = columnCount

    fun rows(): Int = rowCount

    fun slotIds(): List<Int> = orderedSlotIds

    fun containsSlot(slotId: Int): Boolean = ExperimentBoardGeometry.containsSlot(this, slotId)

    /** Returns the compact custom-GUI column for a live container slot. */
    fun column(slotId: Int): Int = ExperimentBoardGeometry.column(this, slotId)

    /** Returns the compact custom-GUI row for a live container slot. */
    fun row(slotId: Int): Int = ExperimentBoardGeometry.row(this, slotId)

    fun slotId(column: Int, row: Int): Int = ExperimentBoardGeometry.slotId(this, column, row)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExperimentBoardGeometry
            && columnCount == other.columnCount
            && rowCount == other.rowCount
            && orderedSlotIds == other.orderedSlotIds)

    override fun hashCode(): Int =
        ExperimentValueSemantics.recordHashCode(columnCount, rowCount, orderedSlotIds)

    override fun toString(): String = ExperimentValueSemantics.recordString(
        "ExperimentBoardGeometry",
        "columns" to columnCount,
        "rows" to rowCount,
        "slotIds" to orderedSlotIds,
    )

    companion object {
        private val cache: Map<ExperimentType, EnumMap<ExperimentTier, ExperimentBoardGeometry>> = buildCache()

        @JvmStatic
        fun forExperiment(type: ExperimentType?, tier: ExperimentTier?): ExperimentBoardGeometry {
            require(type != null) { "type must not be null" }
            val safeTier = tier ?: ExperimentTier.UNKNOWN
            return cache.getValue(type).getValue(safeTier)
        }

        @JvmStatic
        fun containsSlot(geometry: ExperimentBoardGeometry, slotId: Int): Boolean =
            geometry.slotIds().contains(slotId)

        @JvmStatic
        fun column(geometry: ExperimentBoardGeometry, slotId: Int): Int {
            val index = geometry.slotIds().indexOf(slotId)
            return if (index < 0) -1 else index % geometry.columns()
        }

        @JvmStatic
        fun row(geometry: ExperimentBoardGeometry, slotId: Int): Int {
            val index = geometry.slotIds().indexOf(slotId)
            return if (index < 0) -1 else index / geometry.columns()
        }

        @JvmStatic
        fun slotId(geometry: ExperimentBoardGeometry, column: Int, row: Int): Int {
            if (column < 0 || column >= geometry.columns() || row < 0 || row >= geometry.rows()) return -1
            val index = row * geometry.columns() + column
            return if (index >= geometry.slotIds().size) -1 else geometry.slotIds()[index]
        }

        private fun buildCache(): Map<ExperimentType, EnumMap<ExperimentTier, ExperimentBoardGeometry>> {
            val result = EnumMap<ExperimentType, EnumMap<ExperimentTier, ExperimentBoardGeometry>>(
                ExperimentType::class.java,
            )
            for (type in ExperimentType.values()) {
                val byTier = EnumMap<ExperimentTier, ExperimentBoardGeometry>(ExperimentTier::class.java)
                for (tier in ExperimentTier.values()) byTier[tier] = create(type, tier)
                result[type] = byTier
            }
            return java.util.Map.copyOf(result)
        }

        private fun create(type: ExperimentType, safeTier: ExperimentTier): ExperimentBoardGeometry =
            when (type) {
                ExperimentType.CHRONOMATRON -> when (safeTier) {
                    ExperimentTier.HIGH, ExperimentTier.GRAND, ExperimentTier.SUPREME -> range(9, 17)
                    ExperimentTier.TRANSCENDENT,
                    ExperimentTier.METAPHYSICAL,
                    ExperimentTier.UNKNOWN,
                    ExperimentTier.BEGINNER -> range(18, 17)
                }

                // Skyblocker uses all 36 board slots and filters decorative panes by their item name.
                ExperimentType.ULTRASEQUENCER -> rectangle(9, 4, 0, 1)
                ExperimentType.SUPERPAIRS -> when (safeTier) {
                    ExperimentTier.BEGINNER -> rectangle(7, 2, 1, 1)
                    ExperimentTier.HIGH, ExperimentTier.GRAND -> rectangle(5, 4, 2, 1)
                    else -> rectangle(7, 4, 1, 1)
                }
            }

        private fun range(size: Int, firstSlot: Int): ExperimentBoardGeometry {
            val slots = ArrayList<Int>(size)
            for (index in 0 until size) slots += firstSlot + index
            return ExperimentBoardGeometry(9, maxOf(1, (size + 8) / 9), slots)
        }

        private fun rectangle(
            columns: Int,
            rows: Int,
            firstColumn: Int,
            firstRow: Int,
        ): ExperimentBoardGeometry {
            val slots = ArrayList<Int>(columns * rows)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    slots += (firstRow + row) * 9 + firstColumn + column
                }
            }
            return ExperimentBoardGeometry(columns, rows, slots)
        }
    }

    /** Constructor rules isolated from the cached geometry factory to avoid initialization cycles. */
    object ExperimentBoardGeometryConstructorSupport {
        @JvmStatic
        fun normalizeSlotIds(columns: Int, rows: Int, slotIds: List<Int?>?): List<Int> {
            if (columns < 1 || rows < 1) throw IllegalArgumentException("Invalid board dimensions")
            val copy = ArrayList<Int?>()
            if (slotIds != null) copy.addAll(slotIds)
            if (copy.isEmpty() || copy.size > columns * rows) {
                throw IllegalArgumentException("Invalid experiment board slots")
            }
            val seen = HashSet<Int>()
            for (slot in copy) {
                if (slot == null || slot < 0 || !seen.add(slot)) {
                    throw IllegalArgumentException("Invalid experiment board slots")
                }
            }
            return java.util.List.copyOf(copy.filterNotNull())
        }
    }
}
