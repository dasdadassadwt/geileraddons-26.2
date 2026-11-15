package geiler.addons.client.dungeon.f7

import java.util.BitSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Closed-form arrow aiming for the dragon stack aimer. The closed form follows the one Devonian's `Projectile`
 * uses (a drag-and-gravity projectile solved through the Lambert W function); the code is written independently.
 */
object ProjectileSolver {
    class Data(val theta: Double, val phi: Double, val ticks: Double) {
        val valid: Boolean get() = !ticks.isNaN()
    }

    private val NONE = Data(Double.NaN, Double.NaN, Double.NaN)
    private const val NEG_INV_E = -1.0 / Math.E

    /** Lambert W principal branch, x >= -1/e. Halley iteration from a branch-aware guess. */
    @JvmStatic
    fun lambertW0(x: Double): Double {
        if (x.isNaN() || x < NEG_INV_E) return Double.NaN
        if (x == 0.0) return 0.0
        val guess = when {
            x < -0.25 -> {
                val p = sqrt(2.0 * (Math.E * x + 1.0))
                -1.0 + p - p * p / 3.0 + 11.0 / 72.0 * p * p * p
            }
            x < 3.0 -> ln(1.0 + x) * (1.0 - ln(1.0 + ln(1.0 + x)) / (2.0 + ln(1.0 + x)))
            else -> ln(x) - ln(ln(x))
        }
        return halley(x, guess)
    }

    /** Lambert W lower branch, -1/e <= x < 0. */
    @JvmStatic
    fun lambertW1(x: Double): Double {
        if (x.isNaN() || x < NEG_INV_E || x >= 0.0) return Double.NaN
        val guess = if (x < -0.25) {
            val p = -sqrt(2.0 * (Math.E * x + 1.0))
            -1.0 + p - p * p / 3.0 + 11.0 / 72.0 * p * p * p
        } else {
            val l1 = ln(-x)
            val l2 = ln(-l1)
            l1 - l2 + l2 / l1
        }
        return halley(x, guess)
    }

    private fun halley(x: Double, start: Double): Double {
        var w = start
        for (i in 0 until 60) {
            val e = exp(w)
            val f = w * e - x
            val denominator = e * (w + 1.0) - (w + 2.0) * f / (2.0 * w + 2.0)
            if (denominator == 0.0 || denominator.isNaN()) break
            val next = w - f / denominator
            if (next.isNaN()) break
            if (abs(next - w) <= 1e-15 * (1.0 + abs(next))) return next
            w = next
        }
        return w
    }

    /**
     * Launch angles for a projectile with gravity [a] (negative), launch speed [v] and per-tick drag [d] that
     * reaches the offset (dx, dy, dz); [high] picks the lobbed arc. theta is the yaw angle in the xz plane,
     * phi the angle from straight up, ticks the flight time. All NaN when out of range.
     */
    @JvmStatic
    fun solve(dx: Double, dy: Double, dz: Double, eps: Double, a: Double, v: Double, d: Double, high: Boolean): Data {
        require(d != 1.0) { "drag of exactly 1 has no closed form" }
        val theta = atan2(dz, dx)
        val range = hypot(dx, dz)
        val f = 1.0 / (d - 1.0)
        val lnd = ln(d)
        val invA = 1.0 / a
        val invLnd = 1.0 / lnd

        var cA = v + a * f
        var r = -(dy * (d - 1.0) + cA) * invA
        var b = -cA * lnd * exp(lnd * r) * invA
        if (b < NEG_INV_E) return NONE

        if (dy > 0.0 && !high) {
            var left = 0.0
            var right = PI / 2.0
            var t = 0.0
            while (right - left > eps) {
                val m = (left + right) * 0.5
                cA = v * cos(m) + a * f
                r = -(dy * (d - 1.0) + cA) * invA
                b = -cA * lnd * exp(lnd * r) * invA
                if (b < NEG_INV_E) right = m
                else {
                    t = r - invLnd * lambertW1(b)
                    val x = v * sin(m) * (exp(lnd * t) - 1.0) * f
                    if (x < range) left = m else right = m
                }
            }
            return Data(theta, (left + right) * 0.5, t)
        }

        var maxDomain = if (dy > 0.0) PI * 0.5 else PI
        var left = 0.0
        var right = maxDomain
        var x1 = 0.0
        var x2 = 0.0
        while (true) {
            val window = right - left
            if (window < eps) break
            val m1 = window / 3.0 + left
            cA = v * cos(m1) + a * f
            r = -(dy * (d - 1.0) + cA) * invA
            b = -cA * lnd * exp(lnd * r) * invA
            if (b < NEG_INV_E) {
                right = m1
                maxDomain = m1
            } else {
                x1 = v * sin(m1) * (exp(lnd * (r - invLnd * lambertW0(b))) - 1.0) * f
                val m2 = window * 2.0 / 3.0 + left
                cA = v * cos(m2) + a * f
                r = -(dy * (d - 1.0) + cA) * invA
                b = -cA * lnd * exp(lnd * r) * invA
                if (b < NEG_INV_E) {
                    right = m2
                    maxDomain = m2
                } else {
                    x2 = v * sin(m2) * (exp(lnd * (r - invLnd * lambertW0(b))) - 1.0) * f
                    if (x1 < x2) left = m1 else right = m2
                }
            }
        }
        if (range > (x1 + x2) * 0.5) return NONE

        val peak = (left + right) * 0.5
        left = if (high) 0.0 else peak
        right = if (high) peak else maxDomain
        var t = Double.NaN
        while (right - left > eps) {
            val m = (left + right) * 0.5
            cA = v * cos(m) + a * f
            r = -(dy * (d - 1.0) + cA) * invA
            b = -cA * lnd * exp(lnd * r) * invA
            if (b < NEG_INV_E) right = m
            else {
                t = r - invLnd * lambertW0(b)
                x1 = v * sin(m) * (exp(lnd * t) - 1.0) * f
                if ((x1 < range) == high) left = m else right = m
            }
        }
        return Data(theta, (left + right) * 0.5, t)
    }

    class Aim(val index: Int, val data: Data)

    /**
     * Picks the path point whose flight time best matches the dragon's arrival: [ttl] ticks until spawn, walking
     * outward from [previous]. (px, py, pz) is the shooter's eye position, already offset.
     */
    @JvmStatic
    fun aim(
        ttl: Double, path: Array<DoubleArray>, previous: Int,
        err: Double, g: Double, v: Double, d: Double, high: Boolean,
        px: Double, py: Double, pz: Double,
    ): Aim {
        val visited = BitSet(path.size)
        val start = previous.coerceIn(0, path.size - 1)
        visited.set(start)
        fun solveAt(i: Int) = solve(path[i][0] - px, path[i][1] - py, path[i][2] - pz, err, g, v, d, high)
        var bestIndex = start
        var best = solveAt(start)
        var bestOffset = abs(best.ticks - ttl - start)
        if (bestOffset.isNaN()) bestOffset = Double.POSITIVE_INFINITY

        var direction = 1
        var swapped = false
        var i = start
        while (true) {
            val next = i + direction
            var swap = false
            if (next in path.indices && !visited.get(next)) {
                visited.set(next)
                val data = solveAt(next)
                val offset = abs(data.ticks - ttl - next)
                if (offset < bestOffset) {
                    bestIndex = next
                    bestOffset = offset
                    best = data
                    i = next
                } else swap = true
            } else swap = true
            if (swap) {
                if (swapped) break
                swapped = true
                direction = -direction
            }
        }
        return Aim(if (bestOffset.isFinite()) bestIndex else 0, best)
    }
}
