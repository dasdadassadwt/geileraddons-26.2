package geiler.addons.client.dungeon.f7

import net.minecraft.core.BlockPos

/**
 * Simon Says sequence: each sea lantern that lights at x = 111 (y 120..123, z 92..95) adds the stone button one
 * block west of it, in order. Following NoFrills' Simon Says Solver. In the first phase (the SS skip: the first round
 * right after the start button) the lit order is not the click order; that reordering and the start-click limit are
 * adapted from Odin's Simon Says.
 */
class SimonSaysState {
    private val sequence = ArrayList<Long>()
    private var sinceLantern = 0

    /** Lanterns recorded since the list was last cleared; the first-phase rules apply to the 2nd and 3rd only. */
    private var lanternsSeen = 0
    private var startClicks = 0

    /** True from a start-button press (or a fresh device) until the first round is completed or its grid resets. */
    var firstPhase = true
        private set

    fun remaining(): List<Long> = ArrayList(sequence)

    /** The next button to press as a packed block position, or null. */
    fun next(): Long? = sequence.firstOrNull()

    /** A lantern lit: only restarts the idle count of [tick]; the order is recorded when it turns off ([lanternOff]). */
    fun lanternLit(x: Int, y: Int, z: Int) {
        if (inLights(x, y, z)) sinceLantern = 0
    }

    /**
     * A lantern turned off (sea lantern back to obsidian), the order Odin records and applies its first-phase rules to.
     * A lantern already in the list is ignored, so repeated events never duplicate a step.
     */
    fun lanternOff(x: Int, y: Int, z: Int) {
        if (!inLights(x, y, z)) return
        val button = pack(x - 1, y, z)
        if (button in sequence) return
        sequence.add(button)
        sinceLantern = 0
        if (!firstPhase) return
        when (++lanternsSeen) {
            2 -> sequence.reverse()
            3 -> sequence.removeAt(sequence.lastIndex - 1)
        }
    }

    /** The first round is over: later lanterns keep their lit order and start clicks are no longer limited. */
    fun endFirstPhase() {
        firstPhase = false
        startClicks = 0
    }

    /**
     * One tick: when no lantern lit for more than ten ticks and the stone buttons are back on the grid ([stoneButtons]
     * above 8), the device reset itself and the first phase is over. Callers pass client ticks for Odin's server ticks.
     */
    fun tick(stoneButtons: Int) {
        if (!firstPhase) return
        if (sinceLantern++ > 10 && stoneButtons > 8) endFirstPhase()
    }

    /** True when a start-button click must be cancelled: first phase, past [max] clicks, and not [sneaking]. */
    fun startClicked(max: Int, sneaking: Boolean): Boolean {
        if (!firstPhase) return false
        return startClicks++ >= max && !sneaking
    }

    /** The start button was pressed: forget the sequence and expect the first phase again. */
    fun restart() {
        sequence.clear()
        lanternsSeen = 0
        sinceLantern = 0
        firstPhase = true
    }

    /**
     * What a press of the button would be, without changing anything: [CORRECT] for the next button, [AHEAD] for a later one
     * (a skip: the buttons before it are done too), [WRONG] for a button that is not left in the list, else [NONE].
     */
    fun peek(x: Int, y: Int, z: Int): Int {
        if (!inButtons(x, y, z) || sequence.isEmpty()) return NONE
        return when (sequence.indexOf(pack(x, y, z))) {
            -1 -> WRONG
            0 -> CORRECT
            else -> AHEAD
        }
    }

    /** Applies a press: the list continues after the pressed button (Odin's pointer), and the first phase ends with the list. */
    fun buttonClicked(x: Int, y: Int, z: Int): Int {
        val kind = peek(x, y, z)
        if (kind == CORRECT || kind == AHEAD) completeThrough(pack(x, y, z))
        return kind
    }

    /** The server confirmed a press (the button turned powered); a button the client press already completed changes nothing. */
    fun buttonConfirmed(x: Int, y: Int, z: Int) {
        if (inButtons(x, y, z)) completeThrough(pack(x, y, z))
    }

    private fun completeThrough(button: Long) {
        val index = sequence.indexOf(button)
        if (index < 0) return
        repeat(index + 1) { sequence.removeAt(0) }
        if (sequence.isEmpty()) endFirstPhase()
    }

    /** Forgets [x]/[y]/[z] when its button was skipped or removed from the world; only that button, and only once. */
    fun removeButton(x: Int, y: Int, z: Int) {
        sequence.remove(pack(x, y, z))
    }

    /** A new round is being shown (the button grid was cleared): drop the old list but stay in the current phase. */
    fun clearSequence() {
        sequence.clear()
        lanternsSeen = 0
        sinceLantern = 0
    }

    /** Back to a fresh device: no sequence, first phase, no start clicks counted. */
    fun reset() {
        restart()
        startClicks = 0
    }

    companion object {
        const val NONE = 0
        const val CORRECT = 1
        const val WRONG = 2
        const val AHEAD = 3

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

        @JvmStatic
        fun pack(x: Int, y: Int, z: Int): Long = BlockPos.asLong(x, y, z)

        @JvmStatic
        fun inLights(x: Int, y: Int, z: Int): Boolean = x == 111 && y in 120..123 && z in 92..95

        @JvmStatic
        fun inButtons(x: Int, y: Int, z: Int): Boolean = x == 110 && y in 120..123 && z in 92..95
    }
}
