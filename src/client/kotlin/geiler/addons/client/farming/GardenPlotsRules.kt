package geiler.addons.client.farming

/**
 * Pure rules of the Garden Plots overlay, after Skyblocker's garden plots widget: reading the Configure Plots menu,
 * the commands each click runs, and the panel's geometry. Grid cell `row * 5 + column` matches [GardenPlotGrid].
 */
object GardenPlotsRules {
    const val WIDTH = 104
    const val HEIGHT = 132
    const val CELL = 18
    const val GRID_X = 7
    const val GRID_Y = 17
    const val GRID = 5
    const val BUTTON = 20
    const val BUTTON_Y = HEIGHT - 24
    const val BARN = 12
    const val SPACING = 4
    const val BUTTONS = 3

    private val NAME = Regex("^[A-Za-z0-9 _]{1,24}$")
    private val FORMATTING = Regex("§.")

    /** Grid cell of a Configure Plots menu slot (five rows, columns 3-7), or -1 for the border, other rows and the barn. */
    @JvmStatic
    fun menuIndex(slot: Int): Int {
        if (slot < 0) return -1
        val row = slot / 9
        val column = slot % 9
        if (row >= GRID || column < 2 || column > 6) return -1
        val index = row * GRID + column - 2
        return if (index == BARN) -1 else index
    }

    /** The plot name after the first dash of a hover name (`Plot - 4`), or null when it could not be a plain plot name. */
    @JvmStatic
    fun plotName(hoverName: String): String? {
        val parts = FORMATTING.replace(hoverName, "").split("-", limit = 2)
        if (parts.size < 2) return null
        return parts[1].trim().takeIf { NAME.matches(it) }
    }

    /** Whether [name] is safe to send in a command: letters, digits, spaces and underscores only. */
    @JvmStatic
    fun validName(name: String): Boolean = NAME.matches(name)

    /** Grid cell of a server plot id (as listed in the tab list's `Plots:` row), or -1. */
    @JvmStatic
    fun gridIndexOfPlot(id: Int): Int {
        val plot = GardenPlotGrid.plotById(id).orElse(null) ?: return -1
        return plot.gridZ * GRID + plot.gridX
    }

    /** The slashless command a click on a cell runs. */
    @JvmStatic
    fun command(index: Int, name: String): String = if (index == BARN) "plottp barn" else "plottp $name"

    @JvmStatic
    fun buttonCommand(button: Int): String? = when (button) {
        0 -> "desk"
        1 -> "warp garden"
        2 -> "setspawn"
        else -> null
    }

    @JvmStatic
    fun buttonX(button: Int): Int = when (button) {
        0 -> GRID_X
        1 -> WIDTH - GRID_X - 2 * BUTTON - 2
        else -> WIDTH - GRID_X - BUTTON
    }

    /** Cell under a point given relative to the panel's top-left, or -1. */
    @JvmStatic
    fun cellAt(dx: Int, dy: Int): Int {
        val column = Math.floorDiv(dx - GRID_X, CELL)
        val row = Math.floorDiv(dy - GRID_Y, CELL)
        return if (column in 0 until GRID && row in 0 until GRID) row * GRID + column else -1
    }

    /** Button under a point given relative to the panel's top-left, or -1. */
    @JvmStatic
    fun buttonAt(dx: Int, dy: Int): Int {
        if (dy < BUTTON_Y || dy >= BUTTON_Y + BUTTON) return -1
        for (button in 0 until BUTTONS) if (dx >= buttonX(button) && dx < buttonX(button) + BUTTON) return button
        return -1
    }

    /** Right of the inventory, or left of it when the screen has no room on the right. */
    @JvmStatic
    fun originX(leftPos: Int, imageWidth: Int, screenWidth: Int): Int {
        val right = leftPos + imageWidth + SPACING
        return if (right + WIDTH <= screenWidth) right else maxOf(0, leftPos - SPACING - WIDTH)
    }
}
