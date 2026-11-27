package geiler.addons.client.module.impl

/**
 * Time-based easing of one view angle of a Spirit Leap POV camera. The result is the same at any POV FPS: after a time
 * constant of `strength` percent of [MAX_TAU_SECONDS] about 63 percent of a turn is done.
 */
class PovAngleSmoother {
    private var value = 0f
    private var lastNanos = 0L
    private var key = 0
    private var started = false

    fun reset() {
        started = false
    }

    /** The angle to show for [target] degrees. A new [targetKey] (another player) or a long gap starts over. */
    fun next(target: Float, nowNanos: Long, strength: Int, targetKey: Int): Float {
        val seconds = (nowNanos - lastNanos) / 1_000_000_000.0
        val restart = !started || strength <= 0 || targetKey != key || seconds < 0 || seconds > MAX_GAP_SECONDS
        value = if (restart) {
            target
        } else {
            var turn = (target - value) % 360f
            if (turn >= 180f) turn -= 360f
            if (turn < -180f) turn += 360f
            val tau = strength.coerceAtMost(100) / 100.0 * MAX_TAU_SECONDS
            value + turn * (1.0 - Math.exp(-seconds / tau)).toFloat()
        }
        started = true
        lastNanos = nowNanos
        key = targetKey
        return value
    }

    private companion object {
        const val MAX_TAU_SECONDS = 0.35
        const val MAX_GAP_SECONDS = 1.5
    }
}
