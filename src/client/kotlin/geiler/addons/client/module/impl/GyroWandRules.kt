package geiler.addons.client.module.impl

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/** Pure rules of the Gyro Wand overlay: cast detection from particles, the cooldown, and the aim voxel trace. */
object GyroWandRules {
    const val BASE_COOLDOWN_MS = 30_000L
    const val BURST_WINDOW_MS = 120L
    const val BURST_COUNT = 5
    const val BURST_Y_TOLERANCE = 0.02
    const val TRACE_DISTANCE = 25.0
    const val RADIUS = 10.0f
    const val HEIGHT = 0.3f

    /** The SkyBlock id of the wand. */
    const val WAND_ID = "GYROKINETIC_WAND"

    /**
     * Counts still witch particles that land at one height inside a short window; [BURST_COUNT] of them are one cast.
     * Particles are ignored while the wand is on cooldown (the caller does not feed them then).
     */
    class BurstDetector {
        private var startMs = 0L
        private var y = 0.0
        private var count = 0

        /** Returns true when this particle completes a cast. */
        fun onParticle(nowMs: Long, particleY: Double): Boolean {
            if (nowMs - startMs > BURST_WINDOW_MS || abs(particleY - y) > BURST_Y_TOLERANCE) {
                startMs = nowMs
                y = particleY
                count = 1
            } else {
                count++
            }
            if (count >= BURST_COUNT) {
                reset()
                return true
            }
            return false
        }

        fun reset() {
            startMs = 0L
            y = 0.0
            count = 0
        }
    }

    /** A witch particle that carries no velocity: the shape of the wand's burst. */
    @JvmStatic
    fun isStillBurstParticle(isWitch: Boolean, xd: Double, yd: Double, zd: Double): Boolean =
        isWitch && xd == 0.0 && yd == 0.0 && zd == 0.0

    @JvmStatic
    fun cooldownMs(mageMultiplier: Double): Long = Math.round(BASE_COOLDOWN_MS * mageMultiplier)

    /** The eye height Odin uses for the aim ray: swimming 0.4, sneaking 1.27, otherwise 1.62. */
    @JvmStatic
    fun eyeHeight(swimming: Boolean, sneaking: Boolean): Double = when {
        swimming -> 0.4
        sneaking -> 1.27
        else -> 1.62
    }

    /** Result of probing one block for the trace. */
    const val AIR = 0
    const val SOLID = 1
    const val UNLOADED = 2

    /**
     * Walks the voxels from start to end and returns the first block that is not air as {x, y, z}, or null when the
     * ray ends in air or reaches an unloaded chunk.
     */
    @JvmStatic
    fun firstNonAir(
        sx: Double, sy: Double, sz: Double, ex: Double, ey: Double, ez: Double,
        probe: (Int, Int, Int) -> Int,
    ): IntArray? {
        val dx = ex - sx
        val dy = ey - sy
        val dz = ez - sz
        var x = floor(sx).toInt()
        var y = floor(sy).toInt()
        var z = floor(sz).toInt()
        val stepX = sign(dx).toInt()
        val stepY = sign(dy).toInt()
        val stepZ = sign(dz).toInt()
        val deltaX = abs(1.0 / dx)
        val deltaY = abs(1.0 / dy)
        val deltaZ = abs(1.0 / dz)
        var maxX = (if (stepX < 0) sx - x else x + 1 - sx) * deltaX
        var maxY = (if (stepY < 0) sy - y else y + 1 - sy) * deltaY
        var maxZ = (if (stepZ < 0) sz - z else z + 1 - sz) * deltaZ
        var remaining = abs(floor(ex).toInt() - x) + abs(floor(ey).toInt() - y) + abs(floor(ez).toInt() - z)
        while (true) {
            when (probe(x, y, z)) {
                SOLID -> return intArrayOf(x, y, z)
                UNLOADED -> return null
            }
            if (remaining-- <= 0) return null
            if (maxX < maxY) {
                if (maxX < maxZ) {
                    maxX += deltaX
                    x += stepX
                } else {
                    maxZ += deltaZ
                    z += stepZ
                }
            } else if (maxY < maxZ) {
                maxY += deltaY
                y += stepY
            } else {
                maxZ += deltaZ
                z += stepZ
            }
        }
    }
}
