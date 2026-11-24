package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.exp

/**
 * Per-player animation state. Walk speed and ground contact change every frame; using them directly as a
 * rate (`age * rate`) makes the phase jump and the wings and tails jitter. Here they are smoothed and the
 * phases are integrated, so a change in speed only changes how fast the phase moves, never where it is.
 */
class CosmeticMotionTracker {
    private class State(var seconds: Float, var moving: Float, var air: Float, var wingPhase: Float, var swayPhase: Float)

    private val states = HashMap<Int, State>()
    private var lastPrune = 0f

    /** @param seconds the entity's age in seconds (partial ticks included); one call per rendered frame is fine. */
    fun update(id: Int, seconds: Float, rawMoving: Float, rawAirborne: Boolean): CosmeticRig.Motion {
        val state = states.getOrPut(id) { State(seconds, rawMoving, if (rawAirborne) 1f else 0f, 0f, 0f) }
        val dt = (seconds - state.seconds).coerceIn(0f, MAX_STEP)
        if (dt > 0f) {
            state.seconds = seconds
            state.moving += (rawMoving.coerceIn(0f, 1f) - state.moving) * (1f - exp(-dt * MOVING_RESPONSE))
            // Leaving the ground is slow to count, so steps, slabs and sprint-jumps do not flicker the wings.
            val airTarget = if (rawAirborne) 1f else 0f
            state.air += (airTarget - state.air) * (1f - exp(-dt * if (rawAirborne) AIR_RISE else AIR_FALL))
            state.wingPhase = wrap(state.wingPhase + dt * CosmeticRig.wingRate(state.moving, state.air) * TAU)
            state.swayPhase = wrap(state.swayPhase + dt * (1f + state.moving * 0.8f) * TAU)
        } else if (seconds < state.seconds - 1f) {
            state.seconds = seconds // entity id reused or clock reset
        }
        if (seconds - lastPrune > PRUNE_SECONDS || seconds < lastPrune) prune(seconds)
        return CosmeticRig.Motion(seconds, id, state.moving, state.air, state.wingPhase, state.swayPhase)
    }

    fun size(): Int = states.size

    private fun prune(now: Float) {
        lastPrune = now
        states.values.removeIf { now - it.seconds > PRUNE_SECONDS || it.seconds - now > PRUNE_SECONDS }
    }

    private fun wrap(phase: Float): Float = if (phase > WRAP) phase - WRAP else phase

    private companion object {
        const val TAU = (2.0 * PI).toFloat()
        /** A multiple of 2 pi large enough that phase * speed stays continuous for every allowed speed step. */
        const val WRAP = TAU * 1000f
        const val MAX_STEP = 0.25f
        const val MOVING_RESPONSE = 4f
        const val AIR_RISE = 3f
        const val AIR_FALL = 6f
        const val PRUNE_SECONDS = 30f
    }
}
