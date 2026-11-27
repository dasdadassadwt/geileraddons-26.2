package geiler.addons.client.gui

import kotlin.math.roundToInt

/** Panel and top-bar measurements of the flat Click GUI, in design px (see [ClickGuiDesignScale]). */
object ClickGuiHeaderGeometry {
    private const val WIDTH_FRACTION = 0.965f
    private const val MAX_HEIGHT_FRACTION = 0.905f
    private const val HEADER_HEIGHT_FRACTION = 0.08f
    /** Search and toolbar sizes come from this taller reference height, so they stay readable in a short bar. */
    private const val HEADER_CONTROLS_HEIGHT_FRACTION = 0.12f
    /** Gap between toolbar buttons and between the search bar and the toolbar. */
    const val TOOLBAR_GAP = 8

    @JvmStatic
    fun panelWidthForScreen(screenWidth: Int): Int =
        maxOf(1, minOf(maxOf(1, screenWidth), (screenWidth * WIDTH_FRACTION).roundToInt()))

    @JvmStatic
    fun panelHeightForScreen(screenHeight: Int): Int =
        maxOf(1, minOf(maxOf(1, screenHeight), (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()))

    /** Centres the panel on one axis. Both values are design px, never Minecraft GUI px. */
    @JvmStatic
    fun panelOriginForScreen(screenSize: Int, panelSize: Int): Int = (screenSize - panelSize) / 2

    @JvmStatic
    fun baseHeaderHeightForPanelHeight(panelHeight: Int): Int =
        maxOf(40, (maxOf(1, panelHeight) * HEADER_HEIGHT_FRACTION).roundToInt())

    @JvmStatic
    fun controlHeaderHeightForPanelHeight(panelHeight: Int): Int =
        maxOf(40, (maxOf(1, panelHeight) * HEADER_CONTROLS_HEIGHT_FRACTION).roundToInt())

    @JvmStatic
    fun headerInsetXForPanelWidth(panelWidth: Int): Int =
        maxOf(8, (maxOf(1, panelWidth) * 0.018f).roundToInt())

    @JvmStatic
    fun headerButtonSizeForLayout(panelWidth: Int, panelHeight: Int): Int {
        val count = ClickGuiScreen.TopBarIcon.values().size
        val inset = headerInsetXForPanelWidth(panelWidth)
        val available = maxOf(1, panelWidth - inset * 2 - TOOLBAR_GAP * (count - 1))
        val desired = maxOf(24, (controlHeaderHeightForPanelHeight(panelHeight) * 0.55f).roundToInt())
        return maxOf(1, minOf(desired, available / count))
    }

    /** The version text lines up with the category names below it. */
    @JvmStatic
    fun versionTextX(panelX: Int, categoryWidth: Int): Int = panelX + maxOf(11, (categoryWidth * 0.10f).roundToInt())

    /** From the card grid's left edge to just before the toolbar. */
    @JvmStatic
    @JvmName("searchRectForLayout")
    internal fun searchRectForLayout(
        panelX: Int,
        panelY: Int,
        panelWidth: Int,
        panelHeight: Int,
        categoryWidth: Int,
    ): ClickGuiScreen.Rect {
        val toolbar = topBarRectForLayout(panelX, panelY, panelWidth, panelHeight, ClickGuiScreen.TopBarIcon.entries.first())
        val x = minOf(panelX + panelWidth, panelX + categoryWidth + ClickGuiModuleGrid.GRID_INSET)
        val right = toolbar.x() - TOOLBAR_GAP
        val height = maxOf(18, (controlHeaderHeightForPanelHeight(panelHeight) * 0.38f).roundToInt())
        val width = if (right - x < 24) 0 else right - x
        val y = panelY + (baseHeaderHeightForPanelHeight(panelHeight) - height) / 2
        return ClickGuiScreen.Rect(x, y, width, height)
    }

    /** Toolbar buttons, right-aligned with the card grid's content edge (left of the scrollbar gutter). */
    @JvmStatic
    @JvmName("topBarRectForLayout")
    internal fun topBarRectForLayout(
        panelX: Int,
        panelY: Int,
        panelWidth: Int,
        panelHeight: Int,
        icon: ClickGuiScreen.TopBarIcon,
    ): ClickGuiScreen.Rect {
        val size = headerButtonSizeForLayout(panelWidth, panelHeight)
        val count = ClickGuiScreen.TopBarIcon.values().size
        val totalWidth = count * size + (count - 1) * TOOLBAR_GAP
        val right = panelX + panelWidth - ClickGuiModuleGrid.GRID_INSET - ClickGuiScreen.scrollbarGutter()
        val y = panelY + (baseHeaderHeightForPanelHeight(panelHeight) - size) / 2
        return ClickGuiScreen.Rect(right - totalWidth + icon.ordinal * (size + TOOLBAR_GAP), y, size, size)
    }
}
