package geiler.addons.client.dungeon

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Comparator
import java.util.HashSet
import java.util.List.copyOf
import java.util.function.Predicate
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Corner orientation rules for room-relative waypoint transforms; Java-facing immutable-list contract. */
object DungeonRoomOrientationRules {
    private const val LAST_ROOM_OFFSET = 30

    /**
     * Enumerates candidate corners in a stable order and returns an immutable snapshot.
     */
    @JvmStatic
    fun candidates(cells: List<DungeonGridRules.Cell>?, shape: String?, markerY: Int): List<Candidate> =
        copyOf(enumerateCandidates(cells, shape, markerY))

    /**
     * Enumerates roof-marker corners in component-X, component-Z, then NW/NE/SE/SW order.
     */
    private fun enumerateCandidates(
        cells: List<DungeonGridRules.Cell?>?,
        shape: String?,
        markerY: Int,
    ): List<Candidate> {
        val ordered = validatedCells(cells, shape)
        if (ordered.isEmpty() || markerY < 0 || markerY > 255) return emptyList()
        val validShape = shape ?: return emptyList()

        val result = ArrayList<Candidate>()
        for (cellIndex in ordered.indices) {
            val cell = ordered[cellIndex]
            for (corner in 0..3) {
                if (!allowedOneByFourCorner(ordered, validShape, cellIndex, corner)) continue
                val markerX = cell.baseX + (if (corner == 1 || corner == 2) LAST_ROOM_OFFSET else 0)
                val markerZ = cell.baseZ + (if (corner >= 2) LAST_ROOM_OFFSET else 0)
                result.add(Candidate(corner, markerX, markerY, markerZ))
            }
        }
        return result
    }

    /** Returns a corner only when exactly one candidate block provides the marker evidence. */
    @JvmStatic
    fun uniqueMarkedCandidate(
        candidates: List<Candidate?>?,
        isMarker: Predicate<Candidate?>?,
    ): Candidate? {
        if (candidates == null || candidates.isEmpty() || isMarker == null) return null
        var match: Candidate? = null
        for (candidate in candidates) {
            if (candidate == null || !isMarker.test(candidate)) continue
            if (match != null) return null
            match = candidate
        }
        return match
    }

    /** Applies Devonian's component-to-world rotation while keeping Y unchanged. */
    @JvmStatic
    fun toWorld(
        orientation: Candidate?,
        x: Int,
        y: Int,
        z: Int,
    ): DungeonRoomOrientationRules.Position? {
        if (orientation == null) return null

        val worldX: Long
        val worldZ: Long
        when (orientation.rotation) {
            0 -> {
                worldX = orientation.markerX.toLong() + x
                worldZ = orientation.markerZ.toLong() + z
            }
            1 -> {
                worldX = orientation.markerX.toLong() - z
                worldZ = orientation.markerZ.toLong() + x
            }
            2 -> {
                worldX = orientation.markerX.toLong() - x
                worldZ = orientation.markerZ.toLong() - z
            }
            3 -> {
                worldX = orientation.markerX.toLong() + z
                worldZ = orientation.markerZ.toLong() - x
            }
            else -> return null
        }
        if (worldX < Int.MIN_VALUE.toLong() || worldX > Int.MAX_VALUE.toLong() ||
            worldZ < Int.MIN_VALUE.toLong() || worldZ > Int.MAX_VALUE.toLong()
        ) return null
        return DungeonRoomOrientationRules.Position(worldX.toInt(), y, worldZ.toInt())
    }

    private fun validatedCells(
        cells: List<DungeonGridRules.Cell?>?,
        shape: String?,
    ): List<DungeonGridRules.Cell> {
        if (cells == null || cells.isEmpty() || shape == null) return emptyList()
        val ordered = ArrayList<DungeonGridRules.Cell?>(cells)
        ordered.sortWith(Comparator<DungeonGridRules.Cell?> { first, second ->
            val firstCell = first!!
            val secondCell = second!!
            val xOrder = Integer.compare(firstCell.componentX, secondCell.componentX)
            if (xOrder != 0) xOrder else Integer.compare(firstCell.componentZ, secondCell.componentZ)
        })

        val unique = HashSet<Long>()
        val validated = ArrayList<DungeonGridRules.Cell>(ordered.size)
        for (maybeCell in ordered) {
            val cell = maybeCell ?: return emptyList()
            if (cell.componentX < 0 || cell.componentX >= 6 ||
                cell.componentZ < 0 || cell.componentZ >= 6
            ) return emptyList()
            val origin = DungeonGridRules.cellAtOrigin(cell.baseX, cell.baseZ)
            if (origin == null || origin.componentX != cell.componentX ||
                origin.componentZ != cell.componentZ ||
                !unique.add(key(cell.componentX, cell.componentZ))
            ) return emptyList()
            validated.add(cell)
        }
        if (!shapeMatches(validated, shape) || !connected(validated)) return emptyList()
        return validated
    }

    private fun shapeMatches(cells: List<DungeonGridRules.Cell>, shape: String): Boolean {
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        for (cell in cells) {
            minX = minOf(minX, cell.componentX)
            maxX = maxOf(maxX, cell.componentX)
            minZ = minOf(minZ, cell.componentZ)
            maxZ = maxOf(maxZ, cell.componentZ)
        }
        val width = maxX - minX + 1
        val height = maxZ - minZ + 1
        return when (shape) {
            "1x1" -> cells.size == 1
            "1x2" -> cells.size == 2 && ((width == 2 && height == 1) || (width == 1 && height == 2))
            "1x3" -> cells.size == 3 && ((width == 3 && height == 1) || (width == 1 && height == 3))
            "1x4" -> cells.size == 4 && ((width == 4 && height == 1) || (width == 1 && height == 4))
            "2x2" -> cells.size == 4 && width == 2 && height == 2
            "L" -> cells.size == 3 && width == 2 && height == 2
            else -> false
        }
    }

    private fun connected(cells: List<DungeonGridRules.Cell>): Boolean {
        val all = HashSet<Long>()
        for (cell in cells) all.add(key(cell.componentX, cell.componentZ))
        val seen = HashSet<Long>()
        val pending = ArrayDeque<DungeonGridRules.Cell>()
        pending.add(cells[0])
        while (!pending.isEmpty()) {
            val cell = pending.removeFirst()
            val current = key(cell.componentX, cell.componentZ)
            if (!seen.add(current)) continue
            for (delta in arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))) {
                val adjacent = key(cell.componentX + delta[0], cell.componentZ + delta[1])
                if (!seen.contains(adjacent) && all.contains(adjacent)) {
                    for (next in cells) {
                        if (key(next.componentX, next.componentZ) == adjacent) pending.addLast(next)
                    }
                }
            }
        }
        return seen.size == cells.size
    }

    private fun allowedOneByFourCorner(
        cells: List<DungeonGridRules.Cell>,
        shape: String,
        cellIndex: Int,
        corner: Int,
    ): Boolean {
        if (shape != "1x4") return true
        val last = cells.size - 1
        if (cellIndex != 0 && cellIndex != last) return false
        val horizontal = cells[0].componentZ == cells[1].componentZ
        if (cellIndex == 0) return if (horizontal) corner == 0 || corner == 3 else corner == 0 || corner == 1
        return if (horizontal) corner == 1 || corner == 2 else corner == 2 || corner == 3
    }

    private fun key(x: Int, z: Int): Long =
        (x.toLong() shl 32) xor (z.toLong() and 0xFFFFFFFFL)

    @JvmRecord
    data class Candidate(val rotation: Int, val markerX: Int, val markerY: Int, val markerZ: Int)

    @JvmRecord
    data class Position(val x: Int, val y: Int, val z: Int)
}
