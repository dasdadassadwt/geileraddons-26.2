package geiler.addons.client.dungeon

import java.util.ArrayDeque
import java.util.HashSet

/** Physical cell/door geometry used to decide whether a dungeon door trace belongs in this room. */
object DungeonDoorAdjacency {
    private const val GRID_SIZE = 6

    /**
     * Tests a door against the room footprint rooted at the player's physical dungeon-grid cell.
     * If supplied map offsets are malformed, the conservative footprint is the player's cell alone;
     * room identity, room rotation, and catalog recognition are deliberately not involved.
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun bordersCurrentRoom(
        door: DungeonDoorTracker.Door?,
        playerCell: DungeonGridRules.Cell?,
        mapOffsets: List<DungeonMapGeometry.CellOffset?>?,
    ): Boolean {
        if (door == null || playerCell == null || !validCell(playerCell.componentX, playerCell.componentZ)) {
            return false
        }
        val footprint = footprint(playerCell, mapOffsets)
        val first = door.firstCell()
        val second = door.secondCell()
        if (!validDoorEdge(first, second)) return false
        val firstInside = first in footprint
        val secondInside = second in footprint
        return firstInside != secondInside
    }

    /** Returns the valid physical footprint; unavailable/invalid map data falls back to a 1x1 cell. */
    @JvmStatic
    @JvmSuppressWildcards
    fun footprint(
        playerCell: DungeonGridRules.Cell?,
        mapOffsets: List<DungeonMapGeometry.CellOffset?>?,
    ): Set<Int> {
        if (playerCell == null || !validCell(playerCell.componentX, playerCell.componentZ)) {
            return java.util.Set.of()
        }
        val current = index(playerCell.componentX, playerCell.componentZ)
        val fallback = java.util.Set.of(current)
        if (mapOffsets == null || mapOffsets.isEmpty() || mapOffsets.size > GRID_SIZE * GRID_SIZE) return fallback

        val cells = HashSet<Int>()
        for (offset in mapOffsets) {
            if (offset == null) return fallback
            val x = playerCell.componentX + offset.x
            val z = playerCell.componentZ + offset.z
            if (!validCell(x, z) || !cells.add(index(x, z))) return fallback
        }
        if (current !in cells || !connected(cells)) return fallback
        return java.util.Set.copyOf(cells)
    }

    private fun validDoorEdge(first: Int, second: Int): Boolean {
        if (first < 0 || first >= GRID_SIZE * GRID_SIZE || second < 0 || second >= GRID_SIZE * GRID_SIZE) return false
        val firstX = first % GRID_SIZE
        val firstZ = first / GRID_SIZE
        val secondX = second % GRID_SIZE
        val secondZ = second / GRID_SIZE
        return kotlin.math.abs(firstX - secondX) + kotlin.math.abs(firstZ - secondZ) == 1
    }

    private fun connected(cells: Set<Int>): Boolean {
        val first = cells.iterator().next()
        val visited = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        pending.add(first)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val x = current % GRID_SIZE
            val z = current / GRID_SIZE
            for (candidate in cells) {
                if (candidate !in visited &&
                    kotlin.math.abs(x - candidate % GRID_SIZE) + kotlin.math.abs(z - candidate / GRID_SIZE) == 1
                ) {
                    pending.addLast(candidate)
                }
            }
        }
        return visited.size == cells.size
    }

    private fun validCell(x: Int, z: Int): Boolean = x >= 0 && x < GRID_SIZE && z >= 0 && z < GRID_SIZE

    private fun index(x: Int, z: Int): Int = z * GRID_SIZE + x
}
