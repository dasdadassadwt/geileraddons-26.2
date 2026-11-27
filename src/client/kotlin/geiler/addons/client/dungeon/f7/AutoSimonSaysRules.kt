package geiler.addons.client.dungeon.f7

import java.util.random.RandomGenerator

/**
 * The parts of Auto Simon Says that need no Minecraft types: which modes press buttons, the key that keeps a button of
 * one round apart from the same button in the next round, where a button is aimed at, and the SS skip start presses.
 */
object AutoSimonSaysRules {
    enum class Mode { AIM_ASSIST, TRIGGER, AUTO }

    @JvmStatic
    fun mode(name: String): Mode = Mode.entries.firstOrNull { it.name == name } ?: Mode.TRIGGER

    /** Whether the mode turns the view onto the button on its own. */
    @JvmStatic
    fun turns(mode: Mode): Boolean = mode == Mode.AUTO

    /** Whether the mode presses the button; the aim assist only pulls. */
    @JvmStatic
    fun clicks(mode: Mode): Boolean = mode != Mode.AIM_ASSIST

    /**
     * A trigger key for the button [packed] at step [pointer] of a round of [size] buttons. The same button comes back in
     * every later round, so the key also carries the step and the round, and a click never rests on a button the next
     * round needs.
     */
    @JvmStatic
    fun key(packed: Long, pointer: Int, size: Int): Long = packed * 31L + pointer * 7L + size * 1009L

    /** The point a Floor 7 button at ([x], [y], [z]) is aimed at: the middle of its face (the button is 0.875..1 in x). */
    @JvmStatic
    fun aimPoint(x: Int, y: Int, z: Int): DoubleArray = doubleArrayOf(x + 0.9375, y + 0.5, z + 0.5)

    /**
     * The extra start-button presses of the SS skip: after your own press, [extra] more presses, each after a gap drawn
     * between [minMs] and [maxMs]. Pressing start three times in quick succession lets the first button of the first round
     * be skipped (the solver already drops it, see [SimonSaysState]).
     */
    class Skip(private val random: RandomGenerator) {
        private var left = 0
        private var nextAtMs = 0L
        private var minGap = 0L
        private var maxGap = 0L

        fun active(): Boolean = left > 0

        fun left(): Int = left

        fun start(nowMs: Long, extra: Int, minMs: Long, maxMs: Long) {
            left = extra.coerceAtLeast(0)
            minGap = minMs
            maxGap = maxMs
            nextAtMs = nowMs + LeverAimRules.drawDelayMs(minGap, maxGap, random.nextDouble())
        }

        /** True when a press is due now; the next one is then scheduled. */
        fun due(nowMs: Long): Boolean {
            if (left <= 0 || nowMs < nextAtMs) return false
            left--
            nextAtMs = nowMs + LeverAimRules.drawDelayMs(minGap, maxGap, random.nextDouble())
            return true
        }

        fun cancel() {
            left = 0
        }
    }
}
