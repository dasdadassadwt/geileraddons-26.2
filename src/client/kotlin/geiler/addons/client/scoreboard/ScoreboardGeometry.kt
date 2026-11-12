package geiler.addons.client.scoreboard

/** Pure panel measurement shared by HUD rendering, the editor preview, and offline checks. */
class ScoreboardGeometry private constructor() {
    @JvmRecord
    data class Box(val width: Int, val height: Int, val lineHeight: Int, val padding: Int)

    companion object {
        @JvmStatic
        fun measure(
            rowCount: Int,
            contentWidth: Int,
            requestedWidth: Int,
            requestedHeight: Int,
            fontHeight: Int,
            padding: Int,
            lineSpacing: Int,
        ): Box {
            val rows = rowCount.coerceIn(0, 64)
            val pad = clamp(padding, 0, 32)
            val lineHeight = maxOf(1, fontHeight + clamp(lineSpacing, 0, 16))
            val measuredWidth = clamp(maxOf(48, maxOf(0, contentWidth) + pad * 2), 16, 600)
            val measuredHeight = clamp(maxOf(1, pad * 2 + rows * lineHeight), 1, 600)
            val width = if (requestedWidth <= 0) measuredWidth else clamp(requestedWidth, 16, 600)
            val height = if (requestedHeight <= 0) measuredHeight else clamp(requestedHeight, 1, 600)
            return Box(width, height, lineHeight, pad)
        }

        @JvmStatic
        fun rowY(box: Box, rowIndex: Int): Int = box.padding + maxOf(0, rowIndex) * box.lineHeight

        private fun clamp(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(max, value))
    }
}
