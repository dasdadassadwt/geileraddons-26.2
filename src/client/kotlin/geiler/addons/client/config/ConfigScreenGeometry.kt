package geiler.addons.client.config

/** Pure responsive geometry shared by destructive profile dialogs and their hit targets. */
object ConfigScreenGeometry {
    @JvmRecord
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun right(): Int = x + width
        fun bottom(): Int = y + height
        fun contains(px: Int, py: Int): Boolean = px >= x && px < right() && py >= y && py < bottom()
        fun overlaps(other: Rect?): Boolean = other != null &&
            x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y
    }

    @JvmRecord
    data class Footer(val folder: Rect, val action: Rect, val done: Rect)

    @JvmRecord
    data class ResetFooter(val back: Rect, val action: Rect)

    @JvmStatic
    fun panel(screenWidth: Int, screenHeight: Int, maxWidth: Int, maxHeight: Int): Rect {
        val width = maxOf(1, minOf(maxWidth, screenWidth - 16))
        val height = maxOf(1, minOf(maxHeight, screenHeight - 16))
        return Rect((screenWidth - width) / 2, (screenHeight - height) / 2, width, height)
    }

    @JvmStatic
    fun content(panel: Rect, topInset: Int, bottomInset: Int): Rect {
        val top = panel.y + topInset
        val bottom = maxOf(top, panel.bottom() - bottomInset)
        return Rect(panel.x + 10, top, maxOf(0, panel.width - 20), bottom - top)
    }

    @JvmStatic
    fun compactProfilePanel(panel: Rect): Boolean = panel.height < 200

    @JvmStatic
    fun profileViewport(panel: Rect): Rect =
        if (compactProfilePanel(panel)) content(panel, 56, 31) else content(panel, 94, 61)

    @JvmStatic
    fun previewViewport(panel: Rect): Rect =
        if (compactProfilePanel(panel)) content(panel, 32, 31) else content(panel, 94, 61)

    @JvmStatic
    fun visibleRows(viewport: Rect, rowHeight: Int): Int =
        if (rowHeight <= 0) 0 else maxOf(0, viewport.height / rowHeight)

    @JvmStatic
    fun maxScroll(totalRows: Int, visibleRows: Int): Int = maxOf(0, totalRows - maxOf(0, visibleRows))

    /** Paint and click bounds use the same footer rectangles. */
    @JvmStatic
    fun transferFooter(panel: Rect, confirming: Boolean): Footer {
        val margin = 12
        val gap = 6
        val height = 20
        val doneWidth = minOf(42, maxOf(1, panel.width - margin * 2))
        val actionY = panel.bottom() - 30
        val available = maxOf(0, panel.width - margin * 2 - doneWidth - gap * 2)
        val folderWidth = minOf(if (panel.width < 360) 72 else 142, maxOf(0, available - 88))
        val wantedAction = if (confirming) 178 else 128
        val actionWidth = minOf(wantedAction, maxOf(0, available - folderWidth))
        val folderX = panel.x + margin
        val doneX = panel.right() - margin - doneWidth
        val actionX = doneX - gap - actionWidth
        return Footer(
            Rect(folderX, actionY, folderWidth, height),
            Rect(actionX, actionY, actionWidth, height),
            Rect(doneX, actionY, doneWidth, height),
        )
    }

    @JvmStatic
    fun resetFooter(panel: Rect): ResetFooter {
        val margin = 12
        val gap = 6
        val height = 22
        val available = maxOf(2, panel.width - margin * 2 - gap)
        var backWidth = 74
        var actionWidth = 160
        if (available < backWidth + actionWidth) {
            backWidth = minOf(48, maxOf(1, available / 3))
            actionWidth = maxOf(1, minOf(actionWidth, available - backWidth))
            backWidth = maxOf(1, available - actionWidth)
        }
        val y = panel.bottom() - 30
        return ResetFooter(
            Rect(panel.x + margin, y, backWidth, height),
            Rect(panel.right() - margin - actionWidth, y, actionWidth, height),
        )
    }
}
