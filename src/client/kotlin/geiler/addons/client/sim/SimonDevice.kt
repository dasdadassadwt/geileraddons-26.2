package geiler.addons.client.sim

import java.util.Random

/**
 * Simon Says as the Floor 7 device behaves, in server ticks. Written from behaviour observed and described by
 * engineerClient's practice mode (no code copied):
 *  - the start button toggles between start and reset; a reset does not cancel rounds already queued, which is why
 *    start, reset, start gives the SS skip;
 *  - a round begins 4 ticks after a start press, its lamps light 8 ticks apart and stay lit 8 ticks, and the buttons
 *    return 10 ticks after the last lamp slot;
 *  - the next round begins 6 ticks after a round's last right press; there are 4 rounds;
 *  - after a wrong button the buttons vanish and a new pattern is shown as a two lamp round;
 *  - two start presses closer than one tick or 49 ms count once.
 *
 * Cells run 0 to 15, rows from the top, columns left to right.
 */
class SimonDevice(private val random: Random) {
    enum class Press { NONE, IDLE, RIGHT, ROUND, DONE, WRONG }

    private class Task(val due: Long, val action: () -> Unit)

    private val tasks = ArrayList<Task>()
    private var now = 0L
    private var lastStartTick = Long.MIN_VALUE
    private var lastStartMillis = 0L
    private var pattern = IntArray(ROUNDS)
    private val lamps = BooleanArray(CELLS)
    private val up = BooleanArray(CELLS)
    private val downUntil = LongArray(CELLS)
    private var running = false

    var round = 0
        private set
    var done = false
        private set
    var accepting = false
        private set
    var pressed = 0
        private set

    fun lit(cell: Int): Boolean = lamps[cell]
    fun buttonUp(cell: Int): Boolean = up[cell]
    fun buttonPressed(cell: Int): Boolean = up[cell] && downUntil[cell] > now
    fun patternCell(index: Int): Int = pattern[index]

    /** Runs what is due this tick, then moves to the next one. */
    fun tick() {
        while (true) {
            val index = tasks.indexOfFirst { it.due <= now }
            if (index < 0) break
            tasks.removeAt(index).action()
        }
        now++
    }

    private fun later(ticks: Int, action: () -> Unit) {
        tasks.add(Task(now + ticks, action))
    }

    /** A start button press that reaches the device at [millis]; false when it is dropped or the device is done. */
    fun startPress(millis: Long): Boolean {
        if (done) return false
        if (lastStartTick != Long.MIN_VALUE) {
            val gap = now - lastStartTick
            if (gap <= 0 || gap == 1L && millis - lastStartMillis < LIMIT_MILLIS) return false
        }
        lastStartTick = now
        lastStartMillis = millis
        if (running) {
            running = false
            round = 0
            accepting = false
            pressed = 0
            lamps.fill(false)
            up.fill(false)
        } else {
            running = true
            pattern = newPattern()
            later(START_DELAY) { nextRound() }
        }
        return true
    }

    fun press(cell: Int): Press {
        if (done || cell !in 0 until CELLS || !up[cell] || downUntil[cell] > now) return Press.NONE
        downUntil[cell] = now + PRESS_TICKS
        if (!accepting) return Press.IDLE
        if (cell != pattern[pressed]) {
            accepting = false
            pressed = 0
            running = false
            round = 0
            later(PRESS_TICKS) { up.fill(false) }
            later(FAIL_DELAY) {
                pattern = newPattern()
                round = 1
                nextRound()
            }
            return Press.WRONG
        }
        pressed++
        if (pressed < round) return Press.RIGHT
        accepting = false
        if (round >= ROUNDS) {
            done = true
            tasks.clear()
            lamps.fill(false)
            up.fill(false)
            return Press.DONE
        }
        later(NEXT_ROUND_DELAY) { nextRound() }
        return Press.ROUND
    }

    private fun nextRound() {
        if (done || round >= ROUNDS) return
        round++
        show()
    }

    private fun show() {
        lamps.fill(false)
        up.fill(false)
        accepting = false
        pressed = 0
        val count = round
        for (slot in 0 until count) {
            val light = {
                // The cell is read when it lights: a restart in between has already drawn a new pattern.
                val cell = pattern[slot]
                lamps[cell] = true
                up[cell] = false
                later(LAMP_TICKS) { lamps[cell] = false }
            }
            if (slot == 0) light() else later(slot * LAMP_TICKS, light)
        }
        later(count * LAMP_TICKS + BUTTON_DELAY) {
            for (cell in 0 until CELLS) up[cell] = !lamps[cell]
            if (round >= 1) accepting = true
        }
    }

    private fun newPattern(): IntArray {
        val cells = IntArray(CELLS) { it }
        for (i in 0 until ROUNDS) {
            val j = i + random.nextInt(CELLS - i)
            val swap = cells[i]
            cells[i] = cells[j]
            cells[j] = swap
        }
        return cells.copyOf(ROUNDS)
    }

    companion object {
        const val CELLS = 16
        const val ROUNDS = 4
        private const val START_DELAY = 4
        private const val LAMP_TICKS = 8
        private const val BUTTON_DELAY = 10
        private const val NEXT_ROUND_DELAY = 6
        private const val PRESS_TICKS = 3
        private const val FAIL_DELAY = 29
        private const val LIMIT_MILLIS = 49L
    }
}
