package geiler.addons.client.chat

/** Converts additive GUI-pixel chat settings into vanilla's chat coordinate spaces. */
object ChatNativeSizeRules {
    const val MAX_EXTRA_PIXELS = 16_384

    /** Vanilla's width is already measured in scaled GUI pixels. */
    @JvmStatic
    fun effectiveWidth(vanillaWidth: Int, extraGuiPixels: Int, viewportWidth: Int): Int {
        val viewport = maxOf(1, viewportWidth)
        val desired = maxOf(1, vanillaWidth).toLong() + clampExtra(extraGuiPixels).toLong()
        return maxOf(1L, minOf(viewport.toLong(), desired)).toInt()
    }

    /** Leaves room for vanilla chat's four-pixel left and right background insets. */
    @JvmStatic
    fun availableWidth(viewportWidth: Int, chatScale: Double): Int {
        val viewport = maxOf(1, viewportWidth)
        val insets = kotlin.math.ceil(8.0 * safeScale(chatScale)).toInt()
        return maxOf(1, viewport - insets)
    }

    /** Height is counted in unscaled lines, so convert GUI pixels through the chat scale. */
    @JvmStatic
    fun effectiveHeight(vanillaHeight: Int, extraGuiPixels: Int, viewportHeight: Int, chatScale: Double): Int {
        val viewport = maxOf(1, viewportHeight)
        val scale = safeScale(chatScale)
        val maxLogical = maxOf(1, kotlin.math.floor(viewport / scale).toInt())
        val base = maxOf(1, vanillaHeight)
        val extraLogical = kotlin.math.floor(clampExtra(extraGuiPixels) / scale).toLong()
        val desired = base.toLong() + extraLogical
        return maxOf(1L, minOf(maxLogical.toLong(), desired)).toInt()
    }

    /** Converts an old panel width to the additive amount above vanilla's current width. */
    @JvmStatic
    fun migrateLegacyWidthExtra(legacyGuiPixels: Int, vanillaWidth: Int): Int =
        clampExtra(maxOf(0, legacyGuiPixels - maxOf(1, vanillaWidth)))

    /** Converts an old panel height to the additive amount above the rendered vanilla height. */
    @JvmStatic
    fun migrateLegacyHeightExtra(legacyGuiPixels: Int, vanillaHeight: Int, chatScale: Double): Int {
        val scale = safeScale(chatScale)
        val vanillaGuiPixels = java.lang.Math.round(maxOf(1, vanillaHeight) * scale).toInt()
        return clampExtra(maxOf(0, legacyGuiPixels - vanillaGuiPixels))
    }

    @JvmStatic
    fun scaledHeight(logicalHeight: Int, chatScale: Double): Int =
        kotlin.math.floor(maxOf(0, logicalHeight) * safeScale(chatScale)).toInt()

    @JvmStatic
    fun clampExtra(extraGuiPixels: Int): Int = maxOf(0, minOf(MAX_EXTRA_PIXELS, extraGuiPixels))

    private fun safeScale(scale: Double): Double =
        if (scale.isFinite() && scale > 0.0) scale else 1.0
}
