package geiler.addons.client.visual

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.abs

/** Pure maths of the Zoom module: a zoom factor of 1 is no zoom, 4 divides the field of view by four. */
object ZoomRules {
    private const val MAX_FRAME_SECONDS = 0.25
    private const val SNAP = 0.001

    /** The zoom a press starts at, kept between no zoom and [max]. */
    @JvmStatic
    fun initial(level: Double, max: Double): Double = if (level.isFinite()) level.coerceIn(1.0, maxOf(1.0, max)) else 1.0

    /** One mouse-wheel scroll of [notches] (positive zooms in): each notch changes the zoom by 10% of [speed]. */
    @JvmStatic
    fun scroll(level: Double, notches: Double, speed: Double, max: Double): Double {
        if (!notches.isFinite() || !speed.isFinite() || !level.isFinite()) return level
        val factor = Math.pow(1.0 + 0.1 * speed.coerceAtLeast(0.0), notches)
        return (level * factor).coerceIn(1.0, maxOf(1.0, max))
    }

    /** Moves [current] toward [target] over [dtSeconds]; smooth mode eases on a log scale so zooming in and out feel alike. */
    @JvmStatic
    fun step(current: Double, target: Double, speed: Double, dtSeconds: Double, smooth: Boolean): Double {
        if (!smooth) return target
        if (!dtSeconds.isFinite() || dtSeconds <= 0.0 || current == target) return current
        val dt = dtSeconds.coerceAtMost(MAX_FRAME_SECONDS)
        val blend = 1.0 - exp(-speed.coerceAtLeast(0.0) * dt)
        val next = exp(ln(current) + (ln(target) - ln(current)) * blend)
        return if (abs(next - target) < SNAP) target else next
    }

    /** The factor the field of view is multiplied by. */
    @JvmStatic
    fun fovScale(zoom: Double): Double = if (zoom.isFinite() && zoom > 1.0) 1.0 / zoom else 1.0
}
