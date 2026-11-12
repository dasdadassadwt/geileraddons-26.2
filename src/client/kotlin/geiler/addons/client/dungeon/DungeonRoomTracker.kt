package geiler.addons.client.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import kotlin.jvm.JvmRecord

/** Java-facing entrypoints and value types for the Kotlin room footprint tracker. */
class DungeonRoomTracker private constructor() {
    companion object {
        /** Call on the client tick after dungeon context has been updated. */
        @JvmStatic
        fun tick(client: Minecraft?, dungeonMobEspEnabled: Boolean) {
            DungeonRoomTrackerLogic.tick(client, dungeonMobEspEnabled)
        }

        /** Builds an observation from calibrated map geometry and its corresponding physical grid cells. */
        @JvmStatic
        fun currentMapFootprint(client: Minecraft?): MapRoomFootprint? =
            DungeonRoomTrackerLogic.currentMapFootprint(client)

        /** Current confirmed footprint; empty map data may leave the prior footprint retained in-place. */
        @JvmStatic
        fun currentFootprint(): MapRoomFootprint? = DungeonRoomTrackerLogic.currentFootprint()

        @JvmStatic
        fun confirmedPhysicalRoomCells(): List<CellKey> = DungeonRoomTrackerLogic.confirmedPhysicalRoomCells()

        @JvmStatic
        fun diagnostics(): String = DungeonRoomTrackerLogic.diagnostics()

        /** Drop retained geometry on disable, context exit, world loss, or explicit lifecycle changes. */
        @JvmStatic
        fun clear() = DungeonRoomTrackerLogic.clear()
    }

    @JvmRecord
    data class TrackerState(val world: Any?, val footprint: MapRoomFootprint?) {
        companion object {
            @JvmStatic
            fun empty() = TrackerState(null, null)
        }
    }

    @JvmRecord
    data class CellKey(val x: Int, val z: Int) {
        override fun toString(): String = "CellKey[x=$x, z=$z]"
    }

    /**
     * The key is derived only from the normalized occupied grid cells; it carries no room identity.
     * Kotlin records cannot normalize canonical constructor inputs, so this value class preserves the
     * former record's constructor, accessors, defensive copy, and value behavior without record metadata.
     */
    class MapRoomFootprint @Suppress("UNUSED_PARAMETER") constructor(
        footprintKey: String?,
        cells: List<CellKey?>?,
    ) {
        private val normalizedCells: List<CellKey> = DungeonRoomTrackerLogic.normalizeCells(cells)
        private val normalizedFootprintKey: String = DungeonRoomTrackerLogic.footprintKey(normalizedCells)

        fun footprintKey(): String = normalizedFootprintKey

        fun cells(): List<CellKey> = normalizedCells

        fun contains(position: BlockPos?): Boolean = position != null &&
            DungeonRoomTrackerLogic.containsPhysicalCell(normalizedCells, position.x.toDouble(), position.z.toDouble())

        fun contains(worldX: Double, worldZ: Double): Boolean =
            DungeonRoomTrackerLogic.containsPhysicalCell(normalizedCells, worldX, worldZ)

        internal fun containsCell(cell: CellKey?): Boolean = cell != null && normalizedCells.contains(cell)

        override fun equals(other: Any?): Boolean =
            this === other || other is MapRoomFootprint &&
                normalizedFootprintKey == other.normalizedFootprintKey && normalizedCells == other.normalizedCells

        override fun hashCode(): Int = 31 * normalizedFootprintKey.hashCode() + normalizedCells.hashCode()

        override fun toString(): String =
            "MapRoomFootprint[footprintKey=$normalizedFootprintKey, cells=$normalizedCells]"
    }
}
