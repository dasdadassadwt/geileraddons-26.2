package geiler.addons.client.dungeon.f7

import java.util.random.RandomGenerator

/**
 * Auto Mask without Minecraft types: which mask to put on after a proc, how items are recognised, and the order and
 * timing of the steps. After a proc: switch to the rod, cast it once (an autopet rule then swaps to Phoenix), switch
 * back, run `/stats`, click the other mask there, close the screen if it is still open.
 */
object AutoMaskRules {
    enum class Step { IDLE, ROD_SWITCH, ROD_USE, ROD_BACK, OPEN_MENU, WAIT_MENU, CLICK_MASK, CLOSE, DONE }

    /** The mask to equip after the named one procced; null for anything that is not Bonzo or Spirit. */
    @JvmStatic
    fun otherMask(procced: String): String? = when (procced) {
        "BONZO" -> SPIRIT
        "SPIRIT" -> BONZO
        else -> null
    }

    const val BONZO = "Bonzo's Mask"
    const val SPIRIT = "Spirit Mask"

    /** An item name is the wanted mask, ignoring reforges, stars and the shiny/fragged prefixes. */
    @JvmStatic
    fun isMask(itemName: String, wanted: String): Boolean = itemName.contains(wanted)

    /** The step after [step]; without a rod the rod steps are skipped. */
    @JvmStatic
    fun next(step: Step, hasRod: Boolean): Step = when (step) {
        Step.IDLE -> if (hasRod) Step.ROD_SWITCH else Step.OPEN_MENU
        Step.ROD_SWITCH -> Step.ROD_USE
        Step.ROD_USE -> Step.ROD_BACK
        Step.ROD_BACK -> Step.OPEN_MENU
        Step.OPEN_MENU -> Step.WAIT_MENU
        Step.WAIT_MENU -> Step.CLICK_MASK
        Step.CLICK_MASK -> Step.CLOSE
        Step.CLOSE -> Step.DONE
        Step.DONE -> Step.DONE
    }

    /**
     * The sequence in progress: the current step, when it may run, and when the whole thing gives up. Every step waits a
     * pause drawn between the two delay values; [WAIT_MENU] waits for the screen instead and gives up at the timeout.
     */
    class Sequence(private val random: RandomGenerator) {
        var step = Step.IDLE
            private set
        var wanted: String? = null
            private set
        private var dueAtMs = 0L
        private var deadlineMs = 0L
        private var hasRod = false

        fun running(): Boolean = step != Step.IDLE && step != Step.DONE

        fun start(nowMs: Long, mask: String, rod: Boolean, minMs: Long, maxMs: Long, timeoutMs: Long) {
            wanted = mask
            hasRod = rod
            step = next(Step.IDLE, rod)
            dueAtMs = nowMs + LeverAimRules.drawDelayMs(minMs, maxMs, random.nextDouble())
            deadlineMs = nowMs + timeoutMs
        }

        /** True when the current step may run now. */
        fun due(nowMs: Long): Boolean = running() && nowMs >= dueAtMs

        fun timedOut(nowMs: Long): Boolean = running() && nowMs >= deadlineMs

        /** The current step ran: move on and wait a fresh pause. */
        fun advance(nowMs: Long, minMs: Long, maxMs: Long) {
            step = next(step, hasRod)
            dueAtMs = nowMs + LeverAimRules.drawDelayMs(minMs, maxMs, random.nextDouble())
        }

        fun stop() {
            step = Step.IDLE
            wanted = null
        }
    }
}
