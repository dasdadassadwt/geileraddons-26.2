package geiler.addons.client.gui

import net.minecraft.client.gui.screens.Screen
import kotlin.math.max
import kotlin.math.min

/** Which Click GUI the Theme "Style" setting opens. */
enum class ClickGuiStyle {
    MODERN,
    CLASSIC;

    companion object {
        /** Unknown or missing persisted values fall back to Modern. */
        @JvmStatic
        fun fromSetting(value: String?): ClickGuiStyle = if (value == "Classic") CLASSIC else MODERN

        /** The screen the menu keybind and the commands open for [style]. */
        @JvmStatic
        fun screen(style: ClickGuiStyle): Screen = if (style == CLASSIC) ClickGuiClassicScreen() else ClickGuiScreen()
    }
}

/** Geometry of the Classic menu: one column per category, wrapped onto more rows when the screen is narrow. */
object ClickGuiClassicLayout {
    const val MARGIN = 10
    const val GAP = 6
    const val TITLE_HEIGHT = 20
    const val ROW_HEIGHT = 16
    const val MIN_COLUMN_WIDTH = 84
    const val MAX_COLUMN_WIDTH = 116

    /** Room kept free under the columns for the search bar and its buttons. */
    const val BOTTOM_BAR_HEIGHT = 40

    data class Column(val index: Int, val x: Int, val y: Int, val width: Int)

    /** [bandHeight] is the room of one row of columns, so a column's module list may be at most `bandHeight - TITLE_HEIGHT`. */
    data class Plan(val columns: List<Column>, val columnWidth: Int, val bandHeight: Int) {
        val maxListHeight: Int get() = max(0, bandHeight - TITLE_HEIGHT)
    }

    @JvmStatic
    fun plan(screenWidth: Int, screenHeight: Int, count: Int): Plan {
        if (count <= 0) return Plan(emptyList(), MIN_COLUMN_WIDTH, 0)
        val usable = max(MIN_COLUMN_WIDTH, screenWidth - MARGIN * 2)
        val fitted = (usable - (count - 1) * GAP) / count
        val width = fitted.coerceIn(MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH)
        val perRow = max(1, min(count, (usable + GAP) / (width + GAP)))
        val rows = (count + perRow - 1) / perRow
        val available = max(TITLE_HEIGHT + ROW_HEIGHT, screenHeight - BOTTOM_BAR_HEIGHT - MARGIN)
        val band = (available - (rows - 1) * GAP) / rows
        val columns = ArrayList<Column>(count)
        for (row in 0 until rows) {
            val inRow = min(perRow, count - row * perRow)
            val rowWidth = inRow * width + (inRow - 1) * GAP
            val startX = (screenWidth - rowWidth) / 2
            for (slot in 0 until inRow) {
                columns.add(Column(row * perRow + slot, startX + slot * (width + GAP), MARGIN + row * (band + GAP), width))
            }
        }
        return Plan(columns, width, band)
    }

    /** Pixel height of a column's module list: its rows, capped at [maxListHeight]. */
    @JvmStatic
    fun listHeight(rowCount: Int, maxListHeight: Int): Int = min(max(0, rowCount) * ROW_HEIGHT, max(0, maxListHeight))

    @JvmStatic
    fun maxScroll(rowCount: Int, viewHeight: Int): Int = max(0, max(0, rowCount) * ROW_HEIGHT - max(0, viewHeight))

    /** The module row under [mouseY] in a list starting at [listTop] and scrolled by [scroll], or -1. */
    @JvmStatic
    fun rowAt(mouseY: Int, listTop: Int, scroll: Int, rowCount: Int, viewHeight: Int): Int {
        if (mouseY < listTop || mouseY >= listTop + viewHeight) return -1
        val row = (mouseY - listTop + scroll) / ROW_HEIGHT
        return if (row in 0 until rowCount) row else -1
    }
}
