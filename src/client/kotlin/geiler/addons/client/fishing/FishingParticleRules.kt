package geiler.addons.client.fishing

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geyser rules (Crimson Isle trophy fishing), after SkyHanni's GeyserFishing. */
object GeyserRules {
    const val COUNT = 15
    const val SPEED = 0.05f
    const val OFFSET_X = 0.1f
    const val OFFSET_Y = 0.6f
    const val OFFSET_Z = 0.1f
    private const val DRAW_RANGE = 96.0
    private const val HIDE_RADIUS = 3.0

    /** The cloud particle a geyser emits: count 15, speed 0.05, offset (0.1, 0.6, 0.1). */
    @JvmStatic
    fun matches(count: Int, speed: Float, dx: Float, dy: Float, dz: Float): Boolean =
        count == COUNT && speed == SPEED && dx == OFFSET_X && dy == OFFSET_Y && dz == OFFSET_Z

    /** minX, minY, minZ, maxX, maxY, maxZ: a thin 4 by 4 slab at y 117.9 around the particle. */
    @JvmStatic
    fun box(x: Double, z: Double): DoubleArray = doubleArrayOf(x - 2, 117.9, z - 2, x + 2, 117.91, z + 2)

    /** Horizontal distance only; the box does not draw beyond 96 blocks. */
    @JvmStatic
    fun inDrawRange(horizontalDistance: Double): Boolean = horizontalDistance <= DRAW_RANGE

    /** Whether a geyser particle should be hidden given its distance to the bobber. */
    @JvmStatic
    fun hides(distanceToBobber: Double): Boolean = distanceToBobber < HIDE_RADIUS
}

/** Hotspot Radar rules, after SkyHanni's FishingHotspotRadar. */
object HotspotRules {
    const val MAX_STEP = 3.0
    const val REACHED_DISTANCE = 10.0
    const val RADAR_WINDOW_NANOS = 1_000_000_000L

    /** The radar draws a path of single, still flame particles. */
    @JvmStatic
    fun matches(isFlame: Boolean, count: Int, speed: Float): Boolean = isFlame && count == 1 && speed == 0f

    @JvmStatic
    fun distanceText(distance: Double): String = "§e${String.format(Locale.ROOT, "%,d", distance.toInt())}m"
}

/**
 * Fits a cubic through the radar's particle path and extrapolates to the hotspot, after SkyHanni's
 * ParticlePathBezierFitter: each axis is a degree-3 least-squares polynomial of the point index, and the guess is the
 * curve at `t = 3 * weight / |derivative at 0|` where the weight comes from the path's pitch.
 */
class ParticlePathFitter {
    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val zs = ArrayList<Double>()
    private var lastCurve: Array<DoubleArray>? = null

    fun count(): Int = xs.size

    fun reset() {
        xs.clear()
        ys.clear()
        zs.clear()
        lastCurve = null
    }

    /**
     * Adds a particle position. The first point is kept but reported as not added. Points more than a second after
     * the radar use, too far from the last point, or repeating a used position (Hypixel repeats particles) are dropped.
     */
    fun tryAdd(
        x: Double, y: Double, z: Double,
        maxDistanceToLast: Double,
        lastAbilityNanos: Long,
        nowNanos: Long,
    ): Boolean {
        if (nowNanos - lastAbilityNanos > HotspotRules.RADAR_WINDOW_NANOS) return false
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        if (xs.isEmpty()) {
            add(x, y, z)
            return false
        }
        if (distance(xs.last(), ys.last(), zs.last(), x, y, z) > maxDistanceToLast) return false
        for (i in xs.indices) {
            val dx = xs[i] - x
            val dy = ys[i] - y
            val dz = zs[i] - z
            if (dx * dx + dy * dy + dz * dz < REPEAT_TOLERANCE_SQ) return false
        }
        add(x, y, z)
        return true
    }

    /** The extrapolated hotspot position, or null until more than three points are known. */
    fun solve(): DoubleArray? {
        if (xs.size <= DEGREE) return null
        val curve = lastCurve ?: arrayOf(fit(xs), fit(ys), fit(zs)).also { lastCurve = it }
        val derivative = DoubleArray(3) { curve[it][1] }
        val length = sqrt(derivative[0] * derivative[0] + derivative[1] * derivative[1] + derivative[2] * derivative[2])
        if (length == 0.0 || !length.isFinite()) return null
        val weight = pitchWeight(derivative)
        val t = 3 * weight / length
        val result = DoubleArray(3) { axis ->
            var value = 0.0
            for (power in DEGREE downTo 0) value = value * t + curve[axis][power]
            value
        }
        return if (result.all { it.isFinite() }) result else null
    }

    private fun add(x: Double, y: Double, z: Double) {
        xs.add(x)
        ys.add(y)
        zs.add(z)
        lastCurve = null
    }

    private fun distance(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Least-squares cubic in the point index; coefficients in ascending powers. */
    private fun fit(values: List<Double>): DoubleArray {
        val size = DEGREE + 1
        val normal = Array(size) { DoubleArray(size + 1) }
        for ((index, value) in values.withIndex()) {
            val powers = DoubleArray(size) { Math.pow(index.toDouble(), it.toDouble()) }
            for (row in 0 until size) {
                for (col in 0 until size) normal[row][col] += powers[row] * powers[col]
                normal[row][size] += powers[row] * value
            }
        }
        for (col in 0 until size) {
            var pivot = col
            for (row in col + 1 until size) if (abs(normal[row][col]) > abs(normal[pivot][col])) pivot = row
            val swap = normal[col]
            normal[col] = normal[pivot]
            normal[pivot] = swap
            for (row in 0 until size) {
                if (row == col) continue
                val factor = normal[row][col] / normal[col][col]
                for (k in col..size) normal[row][k] -= factor * normal[col][k]
            }
        }
        return DoubleArray(size) { normal[it][size] / normal[it][it] }
    }

    private fun pitchWeight(derivative: DoubleArray): Double =
        sqrt(24 * sin(pitchFromDerivative(derivative) - PI) + 25)

    /** Solves `y = atan2(sin x - 0.75, cos x)` for x by bisection, as SkyHanni's LocationUtils does. */
    private fun pitchFromDerivative(derivative: DoubleArray): Double {
        val horizontal = sqrt(derivative[0] * derivative[0] + derivative[2] * derivative[2])
        val pitch = -atan2(derivative[1], horizontal)
        var guess = pitch
        var result = atan2(sin(guess) - 0.75, cos(guess))
        var max = PI / 2
        var min = -PI / 2
        repeat(100) {
            if (result < pitch) {
                min = guess
            } else {
                max = guess
            }
            guess = (min + max) / 2
            result = atan2(sin(guess) - 0.75, cos(guess))
            if (result == pitch) return guess
        }
        return guess
    }

    private companion object {
        const val DEGREE = 3
        const val REPEAT_TOLERANCE_SQ = 0.0001
    }
}
