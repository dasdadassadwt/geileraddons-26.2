package geiler.addons.client.dungeon.helpers

import java.util.HashSet

/** Kotlin implementation of Odin's pinned room-shape rotation and blue-terracotta probe rules. */
object DungeonRoomRotationLogic {
    private const val GRID_SIZE = 6
    private const val WORLD_TILE_STRIDE = 32
    private const val WORLD_TILE_ORIGIN = -185

    @JvmStatic
    fun resolve(
        room: DungeonRoomCatalog.RoomData?,
        tileIndices: List<Int?>?,
        highestBlock: Int?,
        observations: DungeonRoomRotation.ProbeLookup?,
    ): DungeonRoomRotation.Result {
        if (room == null || highestBlock == null || !validFootprint(room.shape(), tileIndices)) {
            return unresolved("room metadata, core height, or complete footprint is unavailable")
        }

        val indices = requireNotNull(tileIndices)
        val height = highestBlock
        val bounds = TileBounds.of(indices)
        if (room.shape() == "1x1") {
            if (room.name() == "Fairy") {
                val clay = probePosition(Tile(bounds.minX, bounds.minZ), DungeonRoomRotation.Rotation.SOUTH, height)
                return resolved(DungeonRoomRotation.Rotation.SOUTH, clay)
            }
            if (observations == null) return unresolved("1x1 marker observations are unavailable")
            for (rotation in DungeonRoomRotation.Rotation.values()) {
                val probe = probePosition(Tile(bounds.minX, bounds.minZ), rotation, height)
                val state = observations.observe(probe)
                if (state == null || state == DungeonRoomRotation.ProbeState.UNAVAILABLE) {
                    return unresolved("an earlier 1x1 marker probe is in an unloaded chunk")
                }
                if (state == DungeonRoomRotation.ProbeState.BLUE_TERRACOTTA) return resolved(rotation, probe)
            }
            return unresolved("no blue terracotta marker was found at the 1x1 probe positions")
        }

        val rotation = when (room.shape()) {
            "1x2", "1x3", "1x4" ->
                if (bounds.minX == bounds.maxX) DungeonRoomRotation.Rotation.WEST
                else DungeonRoomRotation.Rotation.SOUTH
            "2x2" -> DungeonRoomRotation.Rotation.SOUTH
            "L" -> lRotation(bounds, indices)
            else -> null
        } ?: return unresolved("room shape has no Odin rotation rule")

        return resolved(rotation, clayPosition(room.shape(), bounds, indices, rotation, height))
    }

    @JvmStatic
    fun passesHeightGate(playerBlockY: Int, highestBlock: Int, singlePlayer: Boolean): Boolean =
        singlePlayer || playerBlockY <= highestBlock - 10

    /** Maps a world offset from the clay into the north-facing room frame. */
    internal fun rotateToNorth(rotation: DungeonRoomRotation.Rotation, x: Int, z: Int): IntArray = when (rotation) {
        DungeonRoomRotation.Rotation.NORTH -> intArrayOf(-x, -z)
        DungeonRoomRotation.Rotation.WEST -> intArrayOf(z, -x)
        DungeonRoomRotation.Rotation.SOUTH -> intArrayOf(x, z)
        DungeonRoomRotation.Rotation.EAST -> intArrayOf(-z, x)
    }

    /** Inverse of [rotateToNorth]: maps a north-facing room offset back into world orientation. */
    internal fun rotateAroundNorth(rotation: DungeonRoomRotation.Rotation, x: Int, z: Int): IntArray = when (rotation) {
        DungeonRoomRotation.Rotation.NORTH -> intArrayOf(-x, -z)
        DungeonRoomRotation.Rotation.WEST -> intArrayOf(-z, x)
        DungeonRoomRotation.Rotation.SOUTH -> intArrayOf(x, z)
        DungeonRoomRotation.Rotation.EAST -> intArrayOf(z, -x)
    }

    private fun lRotation(bounds: TileBounds, indices: List<Int?>): DungeonRoomRotation.Rotation? {
        val topLeft = Tile(bounds.minX, bounds.minZ)
        val bottomRight = indices.mapNotNull { it?.let(::tile) }
            .maxByOrNull { it.x * 1000 + it.z } ?: return null
        // Odin preserves segment insertion order when choosing the third L tile.
        val other = indices.asSequence().mapNotNull { it?.let(::tile) }
            .firstOrNull { it != topLeft && it != bottomRight } ?: return null
        if (topLeft.x == bottomRight.x) return DungeonRoomRotation.Rotation.EAST
        if (topLeft.z == bottomRight.z) return DungeonRoomRotation.Rotation.WEST
        return if (other.x == topLeft.x) DungeonRoomRotation.Rotation.SOUTH
        else DungeonRoomRotation.Rotation.NORTH
    }

    private fun clayPosition(
        shape: String,
        bounds: TileBounds,
        indices: List<Int?>,
        rotation: DungeonRoomRotation.Rotation,
        height: Int,
    ): DungeonRoomRotation.ProbePosition? {
        val topLeftTile = Tile(bounds.minX, bounds.minZ)
        val topLeft = realPosition(topLeftTile, height)
        val bottomRightTile = indices.mapNotNull { it?.let(::tile) }
            .maxByOrNull { it.x * 1000 + it.z } ?: return null
        val bottomRight = realPosition(bottomRightTile, height)
        if (shape == "L") {
            val other = indices.asSequence().mapNotNull { it?.let(::tile) }
                .firstOrNull { it != topLeftTile && it != bottomRightTile } ?: return null
            val otherX = realPosition(other, height).x()
            return when (rotation) {
                DungeonRoomRotation.Rotation.EAST ->
                    DungeonRoomRotation.ProbePosition(otherX - 15, height, topLeft.z() + 15)
                DungeonRoomRotation.Rotation.WEST ->
                    DungeonRoomRotation.ProbePosition(bottomRight.x() + 15, height, bottomRight.z() - 15)
                DungeonRoomRotation.Rotation.SOUTH ->
                    DungeonRoomRotation.ProbePosition(topLeft.x() - 15, height, topLeft.z() - 15)
                DungeonRoomRotation.Rotation.NORTH ->
                    DungeonRoomRotation.ProbePosition(bottomRight.x() + 15, height, bottomRight.z() + 15)
            }
        }
        return if (rotation == DungeonRoomRotation.Rotation.WEST) {
            DungeonRoomRotation.ProbePosition(topLeft.x() + 15, height, topLeft.z() - 15)
        } else {
            DungeonRoomRotation.ProbePosition(topLeft.x() - 15, height, topLeft.z() - 15)
        }
    }

    private fun probePosition(
        tile: Tile,
        rotation: DungeonRoomRotation.Rotation,
        height: Int,
    ): DungeonRoomRotation.ProbePosition {
        val real = realPosition(tile, height)
        return DungeonRoomRotation.ProbePosition(real.x() + rotation.dx(), height, real.z() + rotation.dz())
    }

    private fun realPosition(tile: Tile, height: Int) =
        DungeonRoomRotation.ProbePosition(
            tile.x * WORLD_TILE_STRIDE + WORLD_TILE_ORIGIN,
            height,
            tile.z * WORLD_TILE_STRIDE + WORLD_TILE_ORIGIN,
        )

    private fun validFootprint(shape: String?, indices: List<Int?>?): Boolean {
        if (shape == null || indices == null) return false
        val expectedCount = when (shape) {
            "1x1" -> 1
            "1x2" -> 2
            "1x3", "L" -> 3
            "1x4", "2x2" -> 4
            else -> -1
        }
        if (expectedCount < 0 || indices.size != expectedCount) return false
        val unique = HashSet<Int>()
        for (rawIndex in indices) {
            val index = rawIndex ?: return false
            if (index < 0 || index >= GRID_SIZE * GRID_SIZE || !unique.add(index)) return false
        }
        val bounds = TileBounds.of(indices)
        val width = bounds.maxX - bounds.minX
        val depth = bounds.maxZ - bounds.minZ
        return when (shape) {
            "1x1" -> width == 0 && depth == 0
            "1x2" -> (width == 0 && depth == 1) || (width == 1 && depth == 0)
            "1x3" -> (width == 0 && depth == 2) || (width == 2 && depth == 0)
            "1x4" -> (width == 0 && depth == 3) || (width == 3 && depth == 0)
            "2x2" -> width == 1 && depth == 1 && indices.size == 4
                && contains(indices, bounds.minX, bounds.minZ)
                && contains(indices, bounds.maxX, bounds.minZ)
                && contains(indices, bounds.minX, bounds.maxZ)
                && contains(indices, bounds.maxX, bounds.maxZ)
            "L" -> width == 1 && depth == 1 && indices.size == 3
            else -> false
        }
    }

    private fun contains(indices: List<Int?>, x: Int, z: Int) = indices.contains(z * GRID_SIZE + x)

    private fun tile(index: Int) = Tile(index % GRID_SIZE, index / GRID_SIZE)

    private fun resolved(
        rotation: DungeonRoomRotation.Rotation,
        clayPosition: DungeonRoomRotation.ProbePosition?,
    ) = DungeonRoomRotation.Result(rotation, clayPosition, "")

    private fun unresolved(reason: String) = DungeonRoomRotation.Result(null, null, reason)

    private data class Tile(val x: Int, val z: Int)

    private data class TileBounds(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {
        companion object {
            fun of(indices: List<Int?>): TileBounds {
                var minX = GRID_SIZE
                var minZ = GRID_SIZE
                var maxX = -1
                var maxZ = -1
                for (rawIndex in indices) {
                    val cell = tile(requireNotNull(rawIndex))
                    minX = minOf(minX, cell.x)
                    minZ = minOf(minZ, cell.z)
                    maxX = maxOf(maxX, cell.x)
                    maxZ = maxOf(maxZ, cell.z)
                }
                return TileBounds(minX, minZ, maxX, maxZ)
            }
        }
    }
}
