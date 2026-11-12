package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.DungeonDoorTracker.Door
import geiler.addons.client.dungeon.DungeonDoorTracker.Type
import net.minecraft.core.BlockPos

/** Java-facing lifecycle decisions for refreshing detected door edges and removing stale detections. */
class DungeonDoorRefreshRules private constructor() {
    companion object {
        /** Initial availability is covered by the normal scan; later load/unload transitions need a rescan. */
        @JvmStatic
        fun shouldRescanAvailability(known: Boolean, previouslyLoaded: Boolean, currentlyLoaded: Boolean): Boolean =
            known && previouslyLoaded != currentlyLoaded

        /** Block changes inside the sampled doorway or jamb frame invalidate this adjacent edge. */
        @JvmStatic
        fun changedBlockTouchesEdge(
            alongX: Boolean,
            planeX: Int,
            planeZ: Int,
            scanMinY: Int,
            blockX: Int,
            blockY: Int,
            blockZ: Int,
        ): Boolean {
            if (blockY < scanMinY || blockY > scanMinY + 3) return false
            val sourceVolume = if (alongX) {
                kotlin.math.abs(blockX - planeX) <= 1 && blockZ >= planeZ - 1 && blockZ <= planeZ + 2
            } else {
                kotlin.math.abs(blockZ - planeZ) <= 1 && blockX >= planeX - 1 && blockX <= planeX + 2
            }
            val doorwayFrame = if (alongX) {
                blockX == planeX && kotlin.math.abs(blockZ - planeZ) <= 2
            } else {
                blockZ == planeZ && kotlin.math.abs(blockX - planeX) <= 2
            }
            return sourceVolume || doorwayFrame
        }

        /** Replaces one adjacent cell edge, including removing its prior result when the rescan is empty. */
        @JvmStatic
        fun replaceEdge(current: List<Door>?, firstCell: Int, secondCell: Int, replacement: Door?): List<Door> {
            val next = ArrayList<Door>((current?.size ?: 0) + 1)
            current?.forEach { door ->
                if (door.firstCell() != firstCell || door.secondCell() != secondCell) next.add(door)
            }
            if (replacement != null) next.add(replacement)
            return java.util.List.copyOf(next)
        }

        /** Refreshes one observed edge while keeping discovered Wither/Blood identity through open or non-source states. */
        @JvmStatic
        fun refreshEdge(current: List<Door>?, firstCell: Int, secondCell: Int, detected: Door?): List<Door> {
            var replacement = detected
            val existing = findEdge(current, firstCell, secondCell)
            if (isKeyDoor(existing)) {
                // The first confident material match establishes the edge's typed identity. A barrier,
                // open state, incomplete scan, or conflicting transient match cannot reclassify it.
                if (!isKeyDoor(replacement) || existing!!.type() != replacement!!.type()) {
                    return current?.let { java.util.List.copyOf(it) } ?: java.util.List.of()
                }
                val positions = LinkedHashSet<BlockPos>(existing!!.sourcePositions())
                positions.addAll(replacement!!.sourcePositions())
                replacement = existing.withSourcePositions(java.util.List.copyOf(positions), replacement.sourceDiagnostics())
            }
            return replaceEdge(current, firstCell, secondCell, replacement)
        }

        /** Drops transient detections on chunk eviction but retains typed key doors for this dungeon. */
        @JvmStatic
        fun onChunkUnload(current: List<Door>?, firstCell: Int, secondCell: Int): List<Door> =
            if (isKeyDoor(findEdge(current, firstCell, secondCell))) {
                current?.let { java.util.List.copyOf(it) } ?: java.util.List.of()
            } else {
                replaceEdge(current, firstCell, secondCell, null)
            }

        private fun findEdge(current: List<Door>?, firstCell: Int, secondCell: Int): Door? {
            current?.forEach { door ->
                if (door.firstCell() == firstCell && door.secondCell() == secondCell) return door
            }
            return null
        }

        private fun isKeyDoor(door: Door?): Boolean =
            door != null && (door.type() == Type.WITHER || door.type() == Type.BLOOD)
    }
}
