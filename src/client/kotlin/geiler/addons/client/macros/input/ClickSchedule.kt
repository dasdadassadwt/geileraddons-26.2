package geiler.addons.client.macros.input

import java.util.concurrent.ThreadLocalRandom
import java.util.function.DoubleSupplier
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * When the next clicks are due, for a rate that is a range rather than a number.
 *
 * Every click draws its own rate between the lower and the upper bound, so the gap to the next click is different each
 * time while the rate always stays inside the bounds. A tick is 50 ms, so at ten clicks a second some ticks have two
 * clicks and some none: the schedule is kept in real time and answers how many fall due by now, instead of rounding every
 * gap to a whole number of ticks.
 *
 * How the rate is drawn is up to the randomness setting. At zero every click is an independent draw from the whole range.
 * A hand does not click like that: it speeds up and slows down over a few seconds and holds a rhythm in between, so with
 * more randomness the draws cluster around a centre that wanders through the range, and now and then a click comes late,
 * as a hesitation does. The late clicks are the only ones that fall outside the bounds, and only on the slow side; no gap
 * is ever shorter than the upper bound allows.
 *
 * @param randomness 0 to 100, how far from an even draw the clicking strays
 * @param unit a source of numbers in [0, 1), replaceable so the schedule can be checked deterministically
 */
class ClickSchedule @JvmOverloads constructor(
    private val lowerCps: DoubleSupplier,
    private val upperCps: DoubleSupplier,
    private val randomness: DoubleSupplier,
    private val unit: DoubleSupplier = DoubleSupplier { ThreadLocalRandom.current().nextDouble() },
) {
    private var nextNanos = 0L
    private var drift = 0.0

    /** Forgets the schedule: the next call that asks for clicks starts with one right away. */
    fun reset() {
        nextNanos = 0L
    }

    /** How many clicks are due at this time, at most [cap]. The first call after a [reset] always has one due. */
    fun due(nowNanos: Long, cap: Int): Int {
        if (nextNanos == 0L) nextNanos = nowNanos
        if (nowNanos - nextNanos > MAX_BACKLOG_NANOS) nextNanos = nowNanos

        var due = 0
        while (nowNanos >= nextNanos && due < cap) {
            due++
            nextNanos += gapNanos()
        }
        return due
    }

    private fun gapNanos(): Long {
        val first = lowerCps.asDouble
        val second = upperCps.asDouble
        val low = max(MINIMUM_CPS, min(first, second))
        val high = max(low, max(first, second))
        val strength = max(0.0, min(100.0, randomness.asDouble)) / 100.0

        var position = unit.asDouble
        if (strength > 0.0) {
            // The centre drifts: it keeps most of where it was and is pushed a little each click.
            drift = max(-1.0, min(1.0, drift * DRIFT_KEEP + gauss() * DRIFT_PUSH * strength))
            val centre = 0.5 + 0.5 * drift
            val settled = max(0.0, min(1.0, centre + gauss() * SPREAD))
            position = (1.0 - strength) * position + strength * settled
        }

        val cps = low + (high - low) * position
        var gap = 1.0E9 / cps
        if (strength > 0.0 && unit.asDouble < HESITATION_CHANCE * strength) {
            gap *= HESITATION_MIN + HESITATION_SPAN * unit.asDouble
        }
        return gap.toLong()
    }

    /** A number from a bell curve around zero with a spread of one, off the same source as everything else. */
    private fun gauss(): Double {
        val u = max(1.0E-9, unit.asDouble)
        val v = unit.asDouble
        return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * v)
    }

    companion object {
        /** Below this a "rate" is just a very long pause; keeps a zero from dividing. */
        private const val MINIMUM_CPS = 0.5

        /** After a stall longer than this the clicks that piled up are dropped rather than fired in a burst. */
        private const val MAX_BACKLOG_NANOS = 250_000_000L

        /** How much of the wandering centre survives from one click to the next, and how hard it is pushed. */
        private const val DRIFT_KEEP = 0.9
        private const val DRIFT_PUSH = 0.3

        /** Spread of a single click around the centre, as a share of the range, at full randomness. */
        private const val SPREAD = 0.22

        /** Chance of a hesitation per click at full randomness, and how much longer it makes the gap. */
        private const val HESITATION_CHANCE = 0.06
        private const val HESITATION_MIN = 1.5
        private const val HESITATION_SPAN = 1.0
    }
}
