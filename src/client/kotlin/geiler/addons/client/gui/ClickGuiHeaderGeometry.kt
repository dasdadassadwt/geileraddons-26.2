package geiler.addons.client.gui

import kotlin.math.roundToInt

/** Responsive panel and header measurements shared by the Click GUI's Java screen adapter. */
object ClickGuiHeaderGeometry {
    private const val WIDTH_FRACTION = 0.9004f
    private const val MAX_HEIGHT_FRACTION = 0.765f
    /** Brand band height, scaled with the panel like the approved reference. */
    private const val HEADER_HEIGHT_FRACTION = 0.15f
    /** Search and toolbar controls stay compact while their hit targets remain readable. */
    private const val HEADER_CONTROLS_HEIGHT_FRACTION = 0.12f
    private const val LOGO_HEADER_HEIGHT_FRACTION = 0.82f
    private const val VERSION_WIDTH_FRACTION = 0.0467f
    private const val VERSION_HEIGHT_FRACTION = 0.25f
    private const val BRAND_TRAILING_INSET = 12

    @JvmStatic
    fun panelWidthForScreen(screenWidth: Int): Int =
        maxOf(1, minOf(maxOf(1, screenWidth), (screenWidth * WIDTH_FRACTION).roundToInt()))

    @JvmStatic
    fun panelHeightForScreen(screenHeight: Int): Int =
        maxOf(1, minOf(maxOf(1, screenHeight), (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()))

    @JvmStatic
    fun categoryBrandRoom(panelWidth: Int, panelHeight: Int): Int {
        val safeWidth = maxOf(1, panelWidth)
        val inset = headerInsetXForPanelWidth(safeWidth)
        val trailingInset = maxOf(BRAND_TRAILING_INSET, (inset * 0.4f).roundToInt())
        return inset + logoTileSizeForPanelHeight(panelHeight) + logoVersionGapForPanelWidth(safeWidth) +
            versionBadgeWidthForPanelWidth(safeWidth) + trailingInset
    }

    @JvmStatic
    fun baseHeaderHeightForPanelHeight(panelHeight: Int): Int =
        maxOf(40, (maxOf(1, panelHeight) * HEADER_HEIGHT_FRACTION).roundToInt())

    @JvmStatic
    fun controlHeaderHeightForPanelHeight(panelHeight: Int): Int =
        maxOf(40, (maxOf(1, panelHeight) * HEADER_CONTROLS_HEIGHT_FRACTION).roundToInt())

    @JvmStatic
    fun logoTileSizeForPanelHeight(panelHeight: Int): Int =
        maxOf(36, (baseHeaderHeightForPanelHeight(panelHeight) * LOGO_HEADER_HEIGHT_FRACTION).roundToInt())

    @JvmStatic
    fun versionBadgeWidthForPanelWidth(panelWidth: Int): Int =
        maxOf(36, (maxOf(1, panelWidth) * VERSION_WIDTH_FRACTION).roundToInt())

    @JvmStatic
    fun versionBadgeHeightForPanelHeight(panelHeight: Int): Int =
        maxOf(18, (baseHeaderHeightForPanelHeight(panelHeight) * VERSION_HEIGHT_FRACTION).roundToInt())

    @JvmStatic
    fun logoVersionGapForPanelWidth(panelWidth: Int): Int =
        maxOf(6, (maxOf(1, panelWidth) * 0.004f).roundToInt())

    @JvmStatic
    fun headerInsetXForPanelWidth(panelWidth: Int): Int =
        maxOf(8, (maxOf(1, panelWidth) * 0.018f).roundToInt())

    @JvmStatic
    fun headerInsetYForPanelHeight(panelHeight: Int): Int =
        maxOf(5, (maxOf(1, panelHeight) * 0.018f).roundToInt())

    @JvmStatic
    fun headerButtonGapForPanelWidth(panelWidth: Int): Int =
        maxOf(5, (maxOf(1, panelWidth) * 0.012f).roundToInt())

    @JvmStatic
    fun headerButtonSizeForLayout(panelWidth: Int, panelHeight: Int): Int {
        val count = ClickGuiScreen.TopBarIcon.values().size
        val inset = headerInsetXForPanelWidth(panelWidth)
        val gap = headerButtonGapForPanelWidth(panelWidth)
        val available = maxOf(1, panelWidth - inset * 2 - gap * (count - 1))
        val desired = maxOf(24, (controlHeaderHeightForPanelHeight(panelHeight) * 0.55f).roundToInt())
        return maxOf(1, minOf(desired, available / count))
    }

    @JvmStatic
    @JvmName("profileHeaderRectForLayout")
    internal fun profileHeaderRectForLayout(panelX: Int, panelY: Int, panelWidth: Int, panelHeight: Int): ClickGuiScreen.Rect {
        val size = logoTileSizeForPanelHeight(panelHeight)
        return ClickGuiScreen.Rect(
            panelX + headerInsetXForPanelWidth(panelWidth),
            panelY + headerInsetYForPanelHeight(panelHeight),
            size,
            size,
        )
    }

    @JvmStatic
    @JvmName("versionBadgeRectForLayout")
    internal fun versionBadgeRectForLayout(panelX: Int, panelY: Int, panelWidth: Int, panelHeight: Int): ClickGuiScreen.Rect {
        val logo = profileHeaderRectForLayout(panelX, panelY, panelWidth, panelHeight)
        val height = versionBadgeHeightForPanelHeight(panelHeight)
        return ClickGuiScreen.Rect(
            logo.x() + logo.w() + logoVersionGapForPanelWidth(panelWidth),
            logo.y() + (logo.h() - height) / 2,
            versionBadgeWidthForPanelWidth(panelWidth),
            height,
        )
    }

    @JvmStatic
    @JvmName("searchRectForLayout")
    internal fun searchRectForLayout(
        panelX: Int,
        panelY: Int,
        panelWidth: Int,
        panelHeight: Int,
        categoryWidth: Int,
    ): ClickGuiScreen.Rect {
        val repository = topBarRectForLayout(panelX, panelY, panelWidth, panelHeight, ClickGuiScreen.TopBarIcon.GITHUB)
        val x = minOf(
            panelX + panelWidth,
            panelX + categoryWidth + maxOf(6, (panelWidth * 0.006f).roundToInt()),
        )
        val right = repository.x() - maxOf(6, (panelWidth * 0.010f).roundToInt())
        val height = maxOf(18, (controlHeaderHeightForPanelHeight(panelHeight) * 0.38f).roundToInt())
        val availableWidth = maxOf(0, right - x)
        val minWidth = searchIconOffset(height) + searchIconSize(height)
        val width = if (availableWidth < minWidth) 0 else availableWidth
        val y = panelY + (baseHeaderHeightForPanelHeight(panelHeight) - height) / 2
        return ClickGuiScreen.Rect(x, y, width, height)
    }

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
        val gap = headerButtonGapForPanelWidth(panelWidth)
        val count = ClickGuiScreen.TopBarIcon.values().size
        val totalWidth = count * size + (count - 1) * gap
        val left = panelX + panelWidth - headerInsetXForPanelWidth(panelWidth) - totalWidth
        val y = panelY + (baseHeaderHeightForPanelHeight(panelHeight) - size) / 2
        return ClickGuiScreen.Rect(left + icon.ordinal * (size + gap), y, size, size)
    }

    @JvmStatic
    fun searchIconSize(fieldHeight: Int): Int = minOf(18, maxOf(12, fieldHeight - 4))

    @JvmStatic
    fun searchIconOffset(fieldHeight: Int): Int = maxOf(8, (fieldHeight * 0.28f).roundToInt())

}
