package geiler.addons.client.sim

import java.util.Random

/**
 * The 4th device (i4), in server ticks, from behaviour observed and described by engineerClient's practice mode (no
 * code copied): the nine cells are the target once each in random order; the next one counts the moment the last is
 * hit, but it only lights on a 10 tick grid while you stand on the plate; nothing counts before the first light; and
 * leaving the plate resets the run, a few ticks after you step off.
 */
class TargetDevice(private val random: Random) {
    private var order = IntArray(CELLS) { it }
    private var grid = 0
    private var now = 0L
    private var shownOnce = false
    private var held = false
    private var releaseAt = -1L

    /** The cell showing emerald, or -1. */
    var lit = -1
        private set
    var hits = 0
        private set
    var done = false
        private set

    init {
        reset()
    }

    /** The cell that is the target now, shown or not, or -1 when done. */
    fun current(): Int = if (hits < CELLS) order[hits] else -1

    private fun reset() {
        lit = -1
        hits = 0
        shownOnce = false
        grid = random.nextInt(GRID_TICKS)
        order = IntArray(CELLS) { it }
        for (i in CELLS - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val swap = order[i]
            order[i] = order[j]
            order[j] = swap
        }
    }

    fun tick(onPlate: Boolean) {
        now++
        if (onPlate) {
            held = true
            releaseAt = -1
        } else if (held) {
            if (releaseAt < 0) releaseAt = now + 1 + random.nextInt(GRID_TICKS)
            if (now >= releaseAt) {
                held = false
                releaseAt = -1
            }
        }
        if (done || Math.floorMod(now - grid, GRID_TICKS.toLong()) != 0L) return
        if (held) {
            if (lit != current()) {
                lit = current()
                shownOnce = true
            }
        } else if (lit >= 0 || hits > 0) {
            reset()
        }
    }

    /** An arrow hit [cell]; true when it counted. */
    fun hit(cell: Int): Boolean {
        if (done || cell != current() || !shownOnce || (lit < 0 && hits == 0 && !held)) return false
        if (lit == cell) lit = -1
        hits++
        if (hits >= CELLS) {
            done = true
            lit = -1
        }
        return true
    }

    companion object {
        const val CELLS = 9
        private const val GRID_TICKS = 10
    }
}
