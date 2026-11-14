package geiler.addons.client.macros.sim

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hypixel SkyBlock's Etherwarp (Ether Transmission) as plain arithmetic, free of game state so checks can run it.
 *
 * A ray goes from the sneaking eye [length] blocks along the look direction and stops at the first block that is not
 * passable. The block is a valid target when the two cells standing on it hold nothing that obstructs. The landing is the
 * middle of the target block, [LAND_OFFSET] above the height a player stands at on it. The reference for the rules is
 * Odin's Etherwarp overlay (BSD 3-Clause) and the Etherwarp Conduit wiki page; the algorithm here is written independently.
 */
object EtherwarpMath {
    /** The ray passes through this block. */
    const val PASSABLE = 1

    /** A player cannot stand in this cell (a collision box, or one of the thin blocks that still blocks feet). */
    const val OBSTRUCTS = 2

    /** A full block: not passable, obstructs. */
    const val SOLID = OBSTRUCTS

    /** Flags above this shift hold how many blocks above the block's cell a player stands on it (at least 1). */
    const val STAND_SHIFT = 2

    const val BASE_RANGE = 57.0
    const val MAX_TUNERS = 4
    const val SNEAK_EYE_HEIGHT = 1.27
    const val LAND_OFFSET = 0.05
    const val BLOCKED = "no room above the block"

    /** What the world says about one cell: [PASSABLE], [OBSTRUCTS] and the stand offset shifted by [STAND_SHIFT]. */
    fun interface Probe {
        fun flags(x: Int, y: Int, z: Int): Int
    }

    class Result(
        @JvmField val valid: Boolean,
        @JvmField val blockX: Int,
        @JvmField val blockY: Int,
        @JvmField val blockZ: Int,
        @JvmField val landX: Double,
        @JvmField val landY: Double,
        @JvmField val landZ: Double,
        @JvmField val distance: Double,
        /** Why it is not valid, or "ok". */
        @JvmField val reason: String,
    ) {
        fun getValid(): Boolean = valid
        fun getBlockX(): Int = blockX
        fun getBlockY(): Int = blockY
        fun getBlockZ(): Int = blockZ
        fun getLandX(): Double = landX
        fun getLandY(): Double = landY
        fun getLandZ(): Double = landZ

        /** Whether the ray ended on a block at all, valid or not: the block the preview box is drawn at. */
        fun hitBlock(): Boolean = valid || reason == BLOCKED
    }

    @JvmStatic
    fun range(tuners: Int): Double = BASE_RANGE + min(MAX_TUNERS, max(0, tuners))

    /**
     * Traces the ray and applies the landing rules.
     *
     * @param dx the look direction; it need not be normalised
     * @param length how far the ray reaches, in blocks
     */
    @JvmStatic
    fun trace(probe: Probe, ex: Double, ey: Double, ez: Double, dx: Double, dy: Double, dz: Double, length: Double): Result {
        val norm = sqrt(dx * dx + dy * dy + dz * dz)
        if (norm < 1.0E-9) return none("no look direction")
        val ux = dx / norm
        val uy = dy / norm
        val uz = dz / norm
        var x = floor(ex).toInt()
        var y = floor(ey).toInt()
        var z = floor(ez).toInt()
        val stepX = if (ux > 0) 1 else -1
        val stepY = if (uy > 0) 1 else -1
        val stepZ = if (uz > 0) 1 else -1
        val deltaX = if (ux == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / ux)
        val deltaY = if (uy == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / uy)
        val deltaZ = if (uz == 0.0) Double.POSITIVE_INFINITY else abs(1.0 / uz)
        var maxX = if (ux == 0.0) Double.POSITIVE_INFINITY else (if (ux > 0) x + 1 - ex else ex - x) * deltaX
        var maxY = if (uy == 0.0) Double.POSITIVE_INFINITY else (if (uy > 0) y + 1 - ey else ey - y) * deltaY
        var maxZ = if (uz == 0.0) Double.POSITIVE_INFINITY else (if (uz > 0) z + 1 - ez else ez - z) * deltaZ

        var travelled = 0.0
        while (travelled <= length) {
            val flags = probe.flags(x, y, z)
            if (flags and PASSABLE == 0) return landing(probe, x, y, z, flags, travelled)
            if (maxX <= maxY && maxX <= maxZ) {
                travelled = maxX
                maxX += deltaX
                x += stepX
            } else if (maxY <= maxZ) {
                travelled = maxY
                maxY += deltaY
                y += stepY
            } else {
                travelled = maxZ
                maxZ += deltaZ
                z += stepZ
            }
        }
        return none("nothing in range")
    }

    private fun landing(probe: Probe, x: Int, y: Int, z: Int, flags: Int, travelled: Double): Result {
        val offset = max(1, flags shr STAND_SHIFT)
        val feet = y + offset
        val blocked = probe.flags(x, feet, z) and OBSTRUCTS != 0 || probe.flags(x, feet + 1, z) and OBSTRUCTS != 0
        val landY = feet + LAND_OFFSET
        return Result(!blocked, x, y, z, x + 0.5, landY, z + 0.5, travelled, if (blocked) BLOCKED else "ok")
    }

    private fun none(reason: String) = Result(false, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, reason)

    /** How many blocks above its cell a player stands on a block whose collision reaches [maxY]. */
    @JvmStatic
    fun standOffset(maxY: Double): Int = max(1, ceil(maxY - 1.0E-9).toInt())
}
