package geiler.addons.client.dungeon

import kotlin.jvm.JvmRecord

/** Frozen grid constants and Java-facing values around the Kotlin grid and orientation implementation. */
class DungeonGridRules private constructor() {
    companion object {
        const val GRID_MIN = -200
        const val GRID_MAX = -10
        const val CELL_STRIDE = 32
        const val ROOM_SIZE = 31
        const val SCAN_MIN_Y = 69
        const val SCAN_MAX_Y = 101

        private const val GRID_COMPONENTS = 6
        private val FINGERPRINT_SIZE_FACTOR = 0x9E3779B97F4A7C15uL.toLong()
        private val FNV_OFFSET_BASIS = 0xCBF29CE484222325uL.toLong()
        private val MIX_MULTIPLIER_ONE = 0xBF58476D1CE4E5B9uL.toLong()
        private val MIX_MULTIPLIER_TWO = 0x94D049BB133111EBuL.toLong()

        @JvmStatic
        fun cellAt(blockX: Int, blockZ: Int): DungeonGridRules.Cell? {
            if (blockX < DungeonGridRules.GRID_MIN || blockX > DungeonGridRules.GRID_MAX ||
                blockZ < DungeonGridRules.GRID_MIN || blockZ > DungeonGridRules.GRID_MAX
            ) return null
            val componentX = Math.floorDiv(blockX - DungeonGridRules.GRID_MIN, DungeonGridRules.CELL_STRIDE)
            val componentZ = Math.floorDiv(blockZ - DungeonGridRules.GRID_MIN, DungeonGridRules.CELL_STRIDE)
            val localX = blockX - (DungeonGridRules.GRID_MIN + componentX * DungeonGridRules.CELL_STRIDE)
            val localZ = blockZ - (DungeonGridRules.GRID_MIN + componentZ * DungeonGridRules.CELL_STRIDE)
            if (componentX < 0 || componentX >= GRID_COMPONENTS || componentZ < 0 || componentZ >= GRID_COMPONENTS ||
                localX >= DungeonGridRules.ROOM_SIZE || localZ >= DungeonGridRules.ROOM_SIZE
            ) return null
            return cellAtOrigin(
                DungeonGridRules.GRID_MIN + componentX * DungeonGridRules.CELL_STRIDE,
                DungeonGridRules.GRID_MIN + componentZ * DungeonGridRules.CELL_STRIDE,
            )
        }

        @JvmStatic
        fun cellAtOrigin(baseX: Int, baseZ: Int): DungeonGridRules.Cell? {
            val componentX = Math.floorDiv(baseX - DungeonGridRules.GRID_MIN, DungeonGridRules.CELL_STRIDE)
            val componentZ = Math.floorDiv(baseZ - DungeonGridRules.GRID_MIN, DungeonGridRules.CELL_STRIDE)
            if (componentX < 0 || componentX >= GRID_COMPONENTS || componentZ < 0 || componentZ >= GRID_COMPONENTS ||
                DungeonGridRules.GRID_MIN + componentX * DungeonGridRules.CELL_STRIDE != baseX ||
                DungeonGridRules.GRID_MIN + componentZ * DungeonGridRules.CELL_STRIDE != baseZ
            ) return null
            return DungeonGridRules.Cell(componentX, componentZ, baseX, baseZ)
        }

        @JvmStatic
        fun toCanonical(rotation: Int, x: Int, z: Int): DungeonGridRules.Position =
            when (Math.floorMod(rotation, 4)) {
                1 -> DungeonGridRules.Position(DungeonGridRules.ROOM_SIZE - 1 - z, x)
                2 -> DungeonGridRules.Position(DungeonGridRules.ROOM_SIZE - 1 - x, DungeonGridRules.ROOM_SIZE - 1 - z)
                3 -> DungeonGridRules.Position(z, DungeonGridRules.ROOM_SIZE - 1 - x)
                else -> DungeonGridRules.Position(x, z)
            }

        @JvmStatic
        fun toWorld(rotation: Int, x: Int, z: Int): DungeonGridRules.Position =
            when (Math.floorMod(rotation, 4)) {
                1 -> DungeonGridRules.Position(z, DungeonGridRules.ROOM_SIZE - 1 - x)
                2 -> DungeonGridRules.Position(DungeonGridRules.ROOM_SIZE - 1 - x, DungeonGridRules.ROOM_SIZE - 1 - z)
                3 -> DungeonGridRules.Position(DungeonGridRules.ROOM_SIZE - 1 - z, x)
                else -> DungeonGridRules.Position(x, z)
            }

        @JvmStatic
        fun fingerprint(samples: List<DungeonGridRules.BlockSample>): DungeonGridRules.Fingerprint {
            var best = 0L
            var bestRotation = 0
            var first = true
            for (rotation in 0..3) {
                var sum = 0L
                var xor = 0L
                for (sample in samples) {
                    val canonical = toCanonical(rotation, sample.x, sample.z)
                    val value = hash(sample.blockId, canonical.x, sample.y, canonical.z)
                    sum += value
                    xor = xor xor java.lang.Long.rotateLeft(value, value.toInt() and 63)
                }
                val candidate = avalanche(
                    sum xor java.lang.Long.rotateLeft(xor, 19) xor (samples.size.toLong() * FINGERPRINT_SIZE_FACTOR),
                )
                if (first || java.lang.Long.compareUnsigned(candidate, best) < 0) {
                    first = false
                    best = candidate
                    bestRotation = rotation
                }
            }
            return DungeonGridRules.Fingerprint(best, bestRotation, samples.size)
        }

        private fun hash(blockId: String, x: Int, y: Int, z: Int): Long {
            var result = FNV_OFFSET_BASIS
            for (character in blockId) {
                result = result xor character.code.toLong()
                result *= 0x100000001B3L
            }
            result = result xor ((x.toLong() shl 32) xor (y.toLong() shl 16) xor z.toLong())
            return avalanche(result)
        }

        private fun avalanche(input: Long): Long {
            var value = input
            value = value xor (value ushr 30)
            value *= MIX_MULTIPLIER_ONE
            value = value xor (value ushr 27)
            value *= MIX_MULTIPLIER_TWO
            return value xor (value ushr 31)
        }
    }

    @JvmRecord
    data class Cell(val componentX: Int, val componentZ: Int, val baseX: Int, val baseZ: Int) {
        override fun toString(): String =
            "Cell[componentX=$componentX, componentZ=$componentZ, baseX=$baseX, baseZ=$baseZ]"
    }

    @JvmRecord
    data class Position(val x: Int, val z: Int)

    @JvmRecord
    data class BlockSample(val x: Int, val y: Int, val z: Int, val blockId: String)

    @JvmRecord
    data class Fingerprint(val hash: Long, val rotation: Int, val sampleCount: Int)
}
