package geiler.addons.client.mining

import geiler.addons.client.mining.CrystalHollowsRules.Structure
import kotlin.math.abs

/**
 * Finding Crystal Hollows structures in chunks the client has already loaded. Each structure has a short vertical
 * column of blocks that only it has; the column facts, the quarter each structure sits in and the grotto spacing are the
 * ones community scanners publish (QUOI and GumTuneClient, as restated by killer560's mod, MIT). Block ids are registry
 * paths so these rules run without Minecraft.
 */
object CrystalHollowsScan {
    const val MIN_Y = 30
    const val MAX_Y = 180
    /** A chunk is read at most this often: on arrival, then once more [RETRY_TICKS] later for late section updates. */
    const val MAX_TRIES = 2
    const val RETRY_TICKS = 200L
    private const val GROTTO_CHUNKS = 4

    fun interface BlockLookup {
        fun at(x: Int, y: Int, z: Int): String?
    }

    enum class Quarter(private val test: (Int, Int, Int) -> Boolean) {
        JUNGLE({ x, _, z -> x <= 576 && z <= 576 }),
        PRECURSOR_REMNANTS({ x, _, z -> x > 448 && z > 448 }),
        GOBLIN_HOLDOUT({ x, _, z -> x <= 576 && z > 448 }),
        MITHRIL_DEPOSITS({ x, _, z -> x > 448 && z <= 576 }),
        MAGMA_FIELDS({ _, y, _ -> y < 80 }),
        ANY({ _, _, _ -> true });

        fun contains(x: Int, y: Int, z: Int): Boolean = test(x, y, z)
    }

    /** [column] goes upward from the matched block (null = any block); the marker is the matched block plus the offset. */
    class Signature(
        val structure: Structure,
        val quarter: Quarter,
        val dx: Int,
        val dy: Int,
        val dz: Int,
        val multiple: Boolean,
        val column: List<String?>,
    )

    @JvmStatic
    val signatures: List<Signature> = listOf(
        Signature(Structure.GOBLIN_QUEENS_DEN, Quarter.GOBLIN_HOLDOUT, 0, 5, 0, false,
            listOf("stone", "acacia_log", "acacia_log", "acacia_log", "cauldron", "fire")),
        Signature(Structure.MINES_OF_DIVAN, Quarter.MITHRIL_DEPOSITS, 0, 5, 0, false,
            listOf("quartz_pillar", "quartz_stairs", "stone_brick_stairs", "chiseled_stone_bricks")),
        Signature(Structure.LOST_PRECURSOR_CITY, Quarter.PRECURSOR_REMNANTS, 24, 0, -17, false,
            listOf("cobblestone", "cobblestone", "cobblestone", "cobblestone", "cobblestone_stairs", "polished_andesite", "polished_andesite", "dark_oak_stairs")),
        Signature(Structure.JUNGLE_TEMPLE, Quarter.JUNGLE, -45, 47, -18, false,
            listOf("bedrock", "bedrock", "clay", "clay")),
        Signature(Structure.KHAZAD_DUM, Quarter.MAGMA_FIELDS, 0, 1, 0, false,
            listOf("lava") + List(10) { "barrier" }),
        Signature(Structure.DRAGONS_LAIR, Quarter.ANY, 0, -3, 5, false,
            listOf("stone", "red_terracotta", "red_terracotta", "red_terracotta", "skeleton_skull", "red_wool")),
        Signature(Structure.FAIRY_GROTTO, Quarter.ANY, 0, 0, 0, true,
            listOf("magenta_stained_glass")),
    )

    /** The block ids a column can start with: a chunk section without any of them is skipped unread. */
    @JvmStatic
    fun firstBlocks(): List<String> = signatures.mapNotNull { it.column.first() }.distinct()

    @JvmStatic
    fun quarterAllows(signature: Signature, x: Int, y: Int, z: Int): Boolean = signature.quarter.contains(x, y, z)

    /** Whether the whole column stands with its first block at x y z. */
    @JvmStatic
    fun matches(signature: Signature, x: Int, y: Int, z: Int, blocks: BlockLookup): Boolean {
        if (y + signature.column.size > MAX_Y) return false
        for ((i, want) in signature.column.withIndex()) {
            if (want != null && blocks.at(x, y + i, z) != want) return false
        }
        return true
    }

    @JvmStatic
    fun markAt(signature: Signature, x: Int, y: Int, z: Int): IntArray = intArrayOf(x + signature.dx, y + signature.dy, z + signature.dz)

    /** Whether a find at (bx, bz) is the one already known at (ax, az); only grottos can repeat, four chunks apart. */
    @JvmStatic
    fun sameFind(signature: Signature, ax: Int, az: Int, bx: Int, bz: Int): Boolean =
        !signature.multiple || (abs((ax shr 4) - (bx shr 4)) <= GROTTO_CHUNKS && abs((az shr 4) - (bz shr 4)) <= GROTTO_CHUNKS)

    @JvmStatic
    fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) xor (cz.toLong() and 0xFFFFFFFFL)

    /** Chunks that overlap the Hollows' x/z bounds. */
    @JvmStatic
    fun chunkInHollows(cx: Int, cz: Int): Boolean = cx in (202 shr 4)..(823 shr 4) && cz in (202 shr 4)..(823 shr 4)

    /** How often each chunk was read this lobby, so a visible area costs a few reads and then nothing. */
    class ChunkTries {
        private val tries = HashMap<Long, Int>()
        private val last = HashMap<Long, Long>()

        fun claim(key: Long, tick: Long): Boolean {
            val count = tries[key] ?: 0
            if (count >= MAX_TRIES) return false
            if (count > 0 && tick - (last[key] ?: 0L) < RETRY_TICKS) return false
            tries[key] = count + 1
            last[key] = tick
            return true
        }

        fun clear() {
            tries.clear()
            last.clear()
        }
    }
}
