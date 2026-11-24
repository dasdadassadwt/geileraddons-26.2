package geiler.addons.client.dojo

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * World-free rules of the Crimson Isle Dojo helpers, ported from Skyblocker's `skyblock/crimson/dojo` (LGPL-3.0):
 * which test is running, which zombies to mark, when a Mastery block turns red, where Control's skeleton will be and
 * where Stamina's walls have holes.
 */
object DojoRules {
    enum class Test(val title: String) { NONE("none"), FORCE("Force"), STAMINA("Stamina"), MASTERY("Mastery"), DISCIPLINE("Discipline"),
        SWIFTNESS("Swiftness"), CONTROL("Control"), TENACITY("Tenacity") }

    const val START_MESSAGE = "[NPC] Master Tao: Ahhh, here we go! Let's get you into the Arena."
    // Skyblocker matches the raw centred line; chat arrives trimmed here, so the leading spaces are optional.
    private val TEST_OF = Regex("^\\s*Test of (\\w+) OBJECTIVES$")
    private val FINISHED = Regex("^\\s*CHALLENGE (COMPLETED|FAILED)$")

    const val FORCE_ZOMBIE_LIFETIME_MS = 10_100L
    const val MASTERY_BLOCK_LIFETIME_MS = 6_550L
    private const val ARROW_BLOCKS_PER_SECOND = 60.0

    /** The test a chat line announces, or null. */
    @JvmStatic
    fun testFrom(message: String): Test? {
        val name = TEST_OF.matchEntire(message)?.groupValues?.get(1) ?: return null
        return Test.entries.firstOrNull { it != Test.NONE && it.title == name } ?: Test.NONE
    }

    @JvmStatic
    fun finished(message: String): Boolean = FINISHED.matches(message)

    /** Force: a zombie whose value tag is negative must not be hit. */
    @JvmStatic
    fun forceNegative(tag: String): Boolean = tag.contains("-")

    /** Discipline: the sword (SkyBlock id) each zombie tag asks for, and the box color Skyblocker uses for it. */
    private val SWORD_MATERIAL = mapOf("WOOD_SWORD" to "Wood", "IRON_SWORD" to "Iron", "GOLD_SWORD" to "Gold", "DIAMOND_SWORD" to "Diamond")
    private val SWORD_COLOR = mapOf("WOOD_SWORD" to 0xFFA52A2A.toInt(), "IRON_SWORD" to 0xFFC0C0C0.toInt(),
        "GOLD_SWORD" to 0xFFFFD700.toInt(), "DIAMOND_SWORD" to 0xFF00FFFF.toInt())

    /** Whether a Discipline zombie's tag (an undead icon and a material) matches the held sword. */
    @JvmStatic
    fun disciplineMatches(heldSkyblockId: String, tag: String): Boolean {
        val material = SWORD_MATERIAL[heldSkyblockId] ?: return false
        val words = tag.trim().split(' ')
        return words.lastOrNull() == material && words.size <= 2
    }

    @JvmStatic
    fun disciplineColor(heldSkyblockId: String): Int = SWORD_COLOR[heldSkyblockId] ?: 0

    /** Mastery: when a just-lit block turns red, minus ping and the arrow's flight time from [distance] blocks. */
    @JvmStatic
    fun masteryEndMs(nowMs: Long, pingMs: Long, distance: Double): Long =
        nowMs + MASTERY_BLOCK_LIFETIME_MS - pingMs.coerceAtLeast(0L) - (distance * 1000.0 / ARROW_BLOCKS_PER_SECOND).toLong()

    /** Control: movement over the last update, scaled to one update (3 ticks) plus ping; vertical motion damped. */
    @JvmStatic
    fun controlOffset(previous: Vec3, current: Vec3, pingMs: Long): Vec3 =
        current.subtract(previous).multiply(1.0, 0.1, 1.0).scale(1.0 + 3.0 / 20.0 + pingMs.coerceAtLeast(0L) / 1000.0)

    // ---- Stamina ----

    private const val WALL_THRESHOLD = 13
    private const val WALL_HEIGHT = 5

    enum class HoleDirection { POSITIVE_X, POSITIVE_Z, NEGATIVE_X, NEGATIVE_Z, NEW, UNCHANGED }

    /** Groups the non-air blocks of the arena floor row into wall lines (13+ blocks sharing an x or a z) as boxes. */
    @JvmStatic
    fun walls(bottom: List<BlockPos>): List<AABB> {
        val byX = bottom.groupBy { it.x }.values.filter { it.size >= WALL_THRESHOLD }
        val byZ = bottom.groupBy { it.z }.values.filter { it.size >= WALL_THRESHOLD }
        return (byX + byZ).map { wall ->
            val minX = wall.minOf { it.x }
            val minZ = wall.minOf { it.z }
            val maxX = wall.maxOf { it.x }
            val maxZ = wall.maxOf { it.z }
            val y = wall.first().y
            AABB(minX.toDouble(), y.toDouble(), minZ.toDouble(), maxX + 1.0, y + WALL_HEIGHT + 1.0, maxZ + 1.0)
        }
    }

    /** Holes in one wall box, from Skyblocker's corner pairing; [air] reads the world. */
    @JvmStatic
    fun holes(box: AABB, air: (Int, Int, Int) -> Boolean): List<AABB> {
        val alongZ = box.xsize == 1.0
        val dx = if (alongZ) 0 else 1
        val dz = if (alongZ) 1 else 0
        val topLeft = ArrayList<BlockPos>()
        val topRight = ArrayList<BlockPos>()
        val bottomLeft = ArrayList<BlockPos>()
        for (z in box.minZ.toInt() until box.maxZ.toInt()) for (x in box.minX.toInt() until box.maxX.toInt()) for (y in box.minY.toInt() until box.maxY.toInt()) {
            if (!air(x, y, z)) continue
            val top = y == box.maxY.toInt() - 1 || !air(x, y + 1, z)
            val bottom = !air(x, y - 1, z)
            val left = !air(x + dx, y, z + dz)
            val right = !air(x - dx, y, z - dz)
            val pos = BlockPos(x, y, z)
            if (top && left) topLeft.add(pos)
            if (top && right) topRight.add(pos)
            if (bottom && left) bottomLeft.add(pos)
        }
        val result = ArrayList<AABB>()
        for (i in topLeft.indices) {
            if (topRight.size <= i || bottomLeft.size <= i) break
            val a = topLeft[i]
            val b = topRight[i]
            val hole = AABB(minOf(a.x, b.x).toDouble(), minOf(a.y, b.y).toDouble(), minOf(a.z, b.z).toDouble(),
                maxOf(a.x, b.x) + 1.0, maxOf(a.y, b.y) + 1.0, maxOf(a.z, b.z) + 1.0)
            result.add(hole.expandTowards(0.0, (bottomLeft[i].y - a.y).toDouble(), 0.0))
        }
        return result
    }

    /** Which way a hole moved since the last scan, by finding it one block over in the previous holes. */
    @JvmStatic
    fun direction(hole: AABB, last: Collection<AABB>): HoleDirection = when {
        hole in last -> HoleDirection.UNCHANGED
        hole.move(1.0, 0.0, 0.0) in last -> HoleDirection.POSITIVE_X
        hole.move(-1.0, 0.0, 0.0) in last -> HoleDirection.NEGATIVE_X
        hole.move(0.0, 0.0, 1.0) in last -> HoleDirection.POSITIVE_Z
        hole.move(0.0, 0.0, -1.0) in last -> HoleDirection.NEGATIVE_Z
        else -> HoleDirection.NEW
    }

    /** Whether a hole is moving towards the player (green) rather than away (orange). */
    @JvmStatic
    fun incoming(hole: AABB, direction: HoleDirection?, px: Int, pz: Int): Boolean = when (direction) {
        HoleDirection.POSITIVE_X -> px < hole.minX
        HoleDirection.POSITIVE_Z -> pz < hole.minZ
        HoleDirection.NEGATIVE_X -> px > hole.maxX
        HoleDirection.NEGATIVE_Z -> pz > hole.maxZ
        else -> true
    }
}
