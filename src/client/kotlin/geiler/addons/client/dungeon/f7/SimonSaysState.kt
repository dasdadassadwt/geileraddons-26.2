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
    private var startClicks = 0

    /** True from a start-button press (or a fresh device) until the first round is completed or its grid resets. */
    var firstPhase = true
        private set

    fun remaining(): List<Long> = ArrayList(sequence)

    /** The next button to press as a packed block position, or null. */
    fun next(): Long? = sequence.firstOrNull()

    fun lanternLit(x: Int, y: Int, z: Int) {
        if (!inLights(x, y, z)) return
        val button = pack(x - 1, y, z)
        if (button in sequence) return
        sequence.add(button)
        sinceLantern = 0
        if (!firstPhase) return
        when (sequence.size) {
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
        sinceLantern = 0
        firstPhase = true
    }

    /** [CORRECT] when the pressed button is the next one (and it is consumed), [WRONG] for another button, else [NONE]. */
    fun buttonClicked(x: Int, y: Int, z: Int): Int {
        if (!inButtons(x, y, z) || sequence.isEmpty()) return NONE
        if (sequence.first() == pack(x, y, z)) {
            sequence.removeAt(0)
            if (sequence.isEmpty()) endFirstPhase()
            return CORRECT
        }
        return WRONG
    }

    /** Drops the head of the sequence (a skipped step observed from the world). */
    fun skipHead() {
        if (sequence.isNotEmpty()) sequence.removeAt(0)
    }

    /** Forgets [x]/[y]/[z] when its button was removed from the world. */
    fun removeButton(x: Int, y: Int, z: Int) {
        sequence.remove(pack(x, y, z))
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
