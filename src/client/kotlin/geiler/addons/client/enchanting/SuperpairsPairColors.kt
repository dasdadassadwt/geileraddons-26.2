package geiler.addons.client.enchanting

/** Colours for discovered Superpairs pairs, so several open pairs can be told apart at a glance. */
object SuperpairsPairColors {
    private val FILLS = intArrayOf(
        0xFFFFC107.toInt(), 0xFF4FC3F7.toInt(), 0xFFF06292.toInt(),
        0xFF81C784.toInt(), 0xFFBA68C8.toInt(), 0xFFFF8A65.toInt(),
    )
    const val PALETTE_SIZE = 6

    @JvmStatic
    fun color(index: Int): Int = FILLS[Math.floorMod(index, PALETTE_SIZE)]

    /** A darker shade of the fill for the card border. */
    @JvmStatic
    fun border(index: Int): Int {
        val c = color(index)
        val r = (c shr 16 and 0xFF) * 3 / 5
        val g = (c shr 8 and 0xFF) * 3 / 5
        val b = (c and 0xFF) * 3 / 5
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Slot id to pair index for every discovered pair that is still open (both cards known, not collected). */
    @JvmStatic
    fun indexBySlot(view: SuperpairsBoard.View): Map<Int, Int> {
        val open = view.cards().filter {
            it.state() == SuperpairsBoard.CardState.MATCH || it.state() == SuperpairsBoard.CardState.SELECTED
        }.map { it.slotId() }.toSet()
        val result = LinkedHashMap<Int, Int>()
        var next = 0
        for (slots in view.pairs().values) {
            val live = slots.filter { it in open }
            if (live.size < 2) continue
            for (slot in live) result[slot] = next
            next++
        }
        return result
    }
}
