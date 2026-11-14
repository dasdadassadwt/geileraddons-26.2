package geiler.addons.client.macros.sim

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Aspect of the Void's Instant Transmission as plain arithmetic: a right click without sneaking carries the player 8 blocks along
 * the look direction, a block further for each Transmission Tuner (four at most, so 12). The ray is cast from the eyes and stops short
 * of the first block; the whole player is carried over by that distance, and if the body does not fit where that puts it - a ray into
 * the floor ends with the feet below it - it is lifted, then moved back along the ray, to the furthest place it does fit. So a look at
 * the floor carries the player forward along it, and a look at a wall stops short of it. Free of game state so checks can run it.
 * The numbers are the item's page on the Hypixel SkyBlock wiki; the body is 0.6 wide and 1.8 high.
 */
object InstantTransmission {
    const val BASE_DISTANCE = 8.0
    const val MAX_TUNERS = 4

    private const val STEP = 0.1
    private const val HALF_WIDTH = 0.3
    private const val HEIGHT = 1.8
    const val EYE_HEIGHT = 1.62

    /** The most the body is lifted to clear the floor it was carried into, in blocks, in [LIFT_STEP] steps. */
    private const val MAX_LIFT = 2.0
    private const val LIFT_STEP = 0.25

    /** Less than this is not a teleport: there is no room to go anywhere. */
    private const val MIN_MOVE = 0.5

    class Result(
        @JvmField val moved: Boolean,
        @JvmField val x: Double,
        @JvmField val y: Double,
        @JvmField val z: Double,
        @JvmField val distance: Double,
        @JvmField val reason: String,
    )

    /** 8 blocks plus one per Transmission Tuner, four at most. */
    @JvmStatic
    fun distance(tuners: Int): Double = BASE_DISTANCE + min(MAX_TUNERS, max(0, tuners))

    /**
     * From the feet at ([fx], [fy], [fz]) along ([dx], [dy], [dz]) for up to [length] blocks, using the [EtherwarpMath.OBSTRUCTS] flag
     * of the cells. [step] is how finely the ray is walked.
     */
    @JvmStatic
    @JvmOverloads
    fun trace(
        probe: EtherwarpMath.Probe, fx: Double, fy: Double, fz: Double, dx: Double, dy: Double, dz: Double, length: Double, step: Double = STEP,
    ): Result {
        val norm = sqrt(dx * dx + dy * dy + dz * dz)
        if (norm < 1.0E-9) return Result(false, fx, fy, fz, 0.0, "no look direction")
        val ux = dx / norm
        val uy = dy / norm
        val uz = dz / norm
        val ey = fy + EYE_HEIGHT

        // How far the ray of the eyes gets before a block.
        var reach = 0.0
        var t = step
        while (t <= length + 1.0E-9) {
            if (probe.flags(floor(fx + ux * t).toInt(), floor(ey + uy * t).toInt(), floor(fz + uz * t).toInt()) and EtherwarpMath.OBSTRUCTS != 0) break
            reach = t
            t += step
        }
        if (reach < MIN_MOVE) return Result(false, fx, fy, fz, reach, "no room")

        // The furthest distance at which the body fits, lifted as little as it takes. From the end of the ray back toward the player.
        var along = reach
        while (along >= MIN_MOVE - 1.0E-9) {
            var lift = 0.0
            while (lift <= MAX_LIFT + 1.0E-9) {
                val x = fx + ux * along
                val y = fy + uy * along + lift
                val z = fz + uz * along
                if (fits(probe, x, y, z)) {
                    // Like a warp, the player is put in the middle of the block they end in, when the body fits there.
                    val cx = floor(x) + 0.5
                    val cz = floor(z) + 0.5
                    return if (fits(probe, cx, y, cz)) Result(true, cx, y, cz, along, "ok") else Result(true, x, y, z, along, "ok")
                }
                lift += LIFT_STEP
            }
            along -= step
        }
        return Result(false, fx, fy, fz, reach, "no room")
    }

    private fun fits(probe: EtherwarpMath.Probe, x: Double, y: Double, z: Double): Boolean {
        val x0 = floor(x - HALF_WIDTH).toInt()
        val x1 = floor(x + HALF_WIDTH).toInt()
        val z0 = floor(z - HALF_WIDTH).toInt()
        val z1 = floor(z + HALF_WIDTH).toInt()
        val y0 = floor(y).toInt()
        val y1 = floor(y + HEIGHT - 1.0E-9).toInt()
        for (cx in x0..x1) for (cz in z0..z1) for (cy in y0..y1) {
            if (probe.flags(cx, cy, cz) and EtherwarpMath.OBSTRUCTS != 0) return false
        }
        return true
    }
}
