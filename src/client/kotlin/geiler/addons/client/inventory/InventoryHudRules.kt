package geiler.addons.client.inventory

/** Grid geometry and slot mapping of the Inventory HUD: three inventory rows, optionally the hotbar below a gap. */
object InventoryHudRules {
    const val SLOT = 18
    const val PAD = 6
    const val HOTBAR_GAP = 4
    const val COLUMNS = 9

    @JvmStatic
    fun rows(showHotbar: Boolean): Int = if (showHotbar) 4 else 3

    @JvmStatic
    fun width(): Int = COLUMNS * SLOT + 2 * PAD

    @JvmStatic
    fun height(showHotbar: Boolean): Int =
        rows(showHotbar) * SLOT + 2 * PAD + if (showHotbar) HOTBAR_GAP else 0

    /** Player inventory slot of a grid cell: rows 0-2 are slots 9-35, row 3 is the hotbar (0-8). */
    @JvmStatic
    fun slotIndex(row: Int, column: Int): Int = if (row == 3) column else 9 + row * COLUMNS + column

    /** Top-left pixel of a cell's item, relative to the panel. */
    @JvmStatic
    fun cellX(column: Int): Int = PAD + column * SLOT + 1

    @JvmStatic
    fun cellY(row: Int): Int = PAD + row * SLOT + 1 + if (row == 3) HOTBAR_GAP else 0

    /** Vanilla bars are 182 wide, the tallest stack reaches 60 above the bottom, the XP bar ends 24 above it. */
    const val BAR_WIDTH = 182
    const val BAR_STACK_HEIGHT = 60
    const val XP_BOTTOM = 24
    const val BAR_GAP = 2

    /** Disable Hotbar moves the hotbar into this HUD, so the row it shows is forced on. */
    @JvmStatic
    fun showsHotbar(showHotbar: Boolean, disableHotbar: Boolean): Boolean = showHotbar || disableHotbar

    /**
     * [dx, dy] that moves the vanilla status bars and action bar: centred on the panel (at [hudX], [hudY], [hudWidth]
     * wide), the XP bar just above its top, kept on screen. Zero when the panel sits where the vanilla hotbar is.
     */
    @JvmStatic
    fun statusBarOffset(guiW: Int, guiH: Int, hudX: Int, hudY: Int, hudWidth: Int): IntArray {
        val limit = maxOf(0, guiW / 2 - BAR_WIDTH / 2)
        val dx = (hudX + hudWidth / 2 - guiW / 2).coerceIn(-limit, limit)
        val dy = (hudY - BAR_GAP - (guiH - XP_BOTTOM)).coerceAtMost(XP_BOTTOM).coerceAtLeast(BAR_STACK_HEIGHT - guiH)
        return intArrayOf(dx, dy)
    }

    /** A 0-100 percentage as a 0-1 factor. */
    @JvmStatic
    fun opacity(percent: Float): Float = (percent / 100f).coerceIn(0f, 1f)
}
