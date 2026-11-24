package geiler.addons.client.dungeon.f7

import net.minecraft.core.BlockPos

/**
 * Simon Says solution, line for line after Odin's `SimonSays.kt`: each sea lantern at x = 111 (y 120..123, z 92..95)
 * that turns off adds the stone button one block west of it. The round list is kept whole and [pointer] (Odin's
 * `clickNeeded`) marks the next button. In the first phase (the SS skip: start button pressed several times) a list of
 * two is reversed and a list of three drops the one before the last, every time the list reaches that size.
 */
class SimonSaysState {
    private val sequence = ArrayList<Long>()
    private var pointer = 0
    private var lastLanternTick = -1
    private var startClicks = 0

    /** True from a start-button press (or a fresh device) until the first round is completed or its grid resets. */
    var firstPhase = true
        private set

    fun remaining(): List<Long> = if (pointer >= sequence.size) emptyList() else ArrayList(sequence.subList(pointer, sequence.size))

    /** Buttons in this round, done or not (what Odin's `SS n/4` reports). */
    fun size(): Int = sequence.size

    /** The next button to press as a packed block position, or null. */
    fun next(): Long? = sequence.getOrNull(pointer)

    fun isNext(x: Int, y: Int, z: Int): Boolean = next() == pack(x, y, z)

    fun isLast(x: Int, y: Int, z: Int): Boolean = sequence.lastOrNull() == pack(x, y, z)

    /** A lantern turned off (sea lantern back to obsidian); a button already in this round is ignored. */
    fun lanternOff(x: Int, y: Int, z: Int) {
        if (!inLights(x, y, z)) return
        val button = pack(x - 1, y, z)
        if (button in sequence) return
        sequence.add(button)
        lastLanternTick = 0
        if (!firstPhase) return
        when (sequence.size) {
            2 -> sequence.reverse()
            3 -> sequence.removeAt(sequence.lastIndex - 1)
        }
    }

    /** The grid-reset end of the first phase (Odin's server tick branch); also clears the start-click count. */
    fun endFirstPhase() {
        firstPhase = false
        startClicks = 0
    }

    /**
     * One server tick: when no lantern turned off for more than ten ticks and the stone buttons are back on the grid
     * ([stoneButtons] above 8), the device reset itself and the first phase is over.
     */
    fun serverTick(stoneButtons: Int) {
        if (!firstPhase) return
        if (lastLanternTick++ > 10 && stoneButtons > 8) endFirstPhase()
    }

    /** True when a start-button click must be cancelled: first phase, past [max] clicks, and not [sneaking]. */
    fun startClicked(max: Int, sneaking: Boolean): Boolean {
        if (!firstPhase) return false
        return startClicks++ >= max && !sneaking
    }

    /** Goldor's entry line: a new P3, so the start-click limit counts from zero again. */
    fun resetStartClicks() {
        startClicks = 0
    }

    /**
     * The start button turned powered: forget the round and expect the first phase again. Returns true when the device had
     * left the first phase, i.e. the press restarted a device in progress (a reset), not a skip press.
     */
    fun startPowered(): Boolean {
        val wasRunning = !firstPhase
        resetSolution()
        firstPhase = true
        return wasRunning
    }

    /**
     * A grid button turned powered (the client's own press or another player's): the pointer goes to the button after it,
     * also backwards or to the start for a button outside the list, as Odin does. A finished round is cleared.
     */
    fun buttonPowered(x: Int, y: Int, z: Int) {
        if (!inButtons(x, y, z)) return
        pointer = sequence.indexOf(pack(x, y, z)) + 1
        if (pointer >= sequence.size) {
            resetSolution()
            firstPhase = false
        }
    }

    /** A grid button became air; more than eight air buttons means a new round is being shown. */
    fun gridAir(airButtons: Int) {
        if (airButtons > 8) resetSolution()
    }

    private fun resetSolution() {
        sequence.clear()
        pointer = 0
        lastLanternTick = -1
    }

    /** Back to a fresh device (world load): no sequence, first phase, no start clicks counted. */
    fun reset() {
        resetSolution()
        firstPhase = true
        startClicks = 0
    }

    companion object {
        /** Margin that keeps the box outside the button's faces so they do not z-fight. */
        const val HIGHLIGHT_MARGIN = 0.002

        /** World box `[minX, minY, minZ, maxX, maxY, maxZ]` for a button's block-local shape bounds at block ([x], [y], [z]). */
        @JvmStatic
        fun highlightBox(
            x: Int, y: Int, z: Int,
            minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double,
        ): DoubleArray = doubleArrayOf(
            x + minX - HIGHLIGHT_MARGIN, y + minY - HIGHLIGHT_MARGIN, z + minZ - HIGHLIGHT_MARGIN,
            x + maxX + HIGHLIGHT_MARGIN, y + maxY + HIGHLIGHT_MARGIN, z + maxZ + HIGHLIGHT_MARGIN,
        )

        /** An unpressed button against the lantern wall, for a step whose button is not on the grid yet. */
        @JvmStatic
        fun buttonSpotBox(x: Int, y: Int, z: Int): DoubleArray = highlightBox(x, y, z, 0.875, 0.375, 0.3125, 1.0, 0.625, 0.6875)

        @JvmStatic
        fun pack(x: Int, y: Int, z: Int): Long = BlockPos.asLong(x, y, z)

        @JvmStatic
        fun inLights(x: Int, y: Int, z: Int): Boolean = x == 111 && y in 120..123 && z in 92..95

        @JvmStatic
        fun inButtons(x: Int, y: Int, z: Int): Boolean = x == 110 && y in 120..123 && z in 92..95
    }
}
