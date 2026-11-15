package geiler.addons.client.dungeon.f7

import net.minecraft.core.BlockPos

/**
 * Simon Says sequence: each sea lantern that lights at x = 111 (y 120..123, z 92..95) adds the stone button one
 * block west of it, in order. Following NoFrills' Simon Says Solver.
 */
class SimonSaysState {
    private val sequence = ArrayList<Long>()

    fun remaining(): List<Long> = ArrayList(sequence)

    /** The next button to press as a packed block position, or null. */
    fun next(): Long? = sequence.firstOrNull()

    fun lanternLit(x: Int, y: Int, z: Int) {
        if (!inLights(x, y, z)) return
        val button = pack(x - 1, y, z)
        if (button !in sequence) sequence.add(button)
    }

    /** [CORRECT] when the pressed button is the next one (and it is consumed), [WRONG] for another button, else [NONE]. */
    fun buttonClicked(x: Int, y: Int, z: Int): Int {
        if (!inButtons(x, y, z) || sequence.isEmpty()) return NONE
        if (sequence.first() == pack(x, y, z)) {
            sequence.removeAt(0)
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

    fun reset() = sequence.clear()

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
