package geiler.addons.client.dungeon

import java.io.ByteArrayInputStream

/** Stable Java RasterText API backed by Kotlin's bounded Java2D rasterizer and cache. */
class DungeonRoomMapRasterText {
    enum class Shadow { NONE, DROP, OUTLINE }

    private val logic = DungeonRoomMapRasterTextLogic()

    /** Returns null when the input cannot be rasterized within the declared per-entry bounds. */
    fun get(text: String?, argb: Int, lineHeight: Int, shadow: Shadow?): RasterText? =
        logic.get(text, argb, lineHeight, shadow)

    fun width(text: String?, lineHeight: Int): Int = logic.width(text, lineHeight)
    fun lineHeight(lineHeight: Int): Int = logic.lineHeight(lineHeight)
    fun cachedEntryCount(): Int = logic.cachedEntryCount()
    fun cachedByteCount(): Int = logic.cachedByteCount()

    /** PNG data stays private; each render receives a read-only stream without copying cached bytes. */
    class RasterText private constructor(
        private val textureKey: String,
        private val png: ByteArray,
        private val imageWidth: Int,
        private val imageHeight: Int,
        private val textWidth: Int,
        private val textHeight: Int,
        private val leftInset: Int,
        private val topInset: Int,
    ) {
        fun pngSize(): Int = png.size
        fun textureKey(): String = textureKey
        fun openPng(): ByteArrayInputStream = ByteArrayInputStream(png)
        fun imageWidth(): Int = imageWidth
        fun imageHeight(): Int = imageHeight
        fun drawWidth(): Int = maxOf(1, (imageWidth + SUPERSAMPLE - 1) / SUPERSAMPLE)
        fun drawHeight(): Int = maxOf(1, (imageHeight + SUPERSAMPLE - 1) / SUPERSAMPLE)
        fun textWidth(): Int = textWidth
        fun textHeight(): Int = textHeight
        fun leftInset(): Int = leftInset
        fun topInset(): Int = topInset

        companion object {
            @JvmSynthetic
            internal fun create(
                textureKey: String,
                png: ByteArray,
                imageWidth: Int,
                imageHeight: Int,
                textWidth: Int,
                textHeight: Int,
                leftInset: Int,
                topInset: Int,
            ): RasterText = RasterText(
                textureKey,
                png,
                imageWidth,
                imageHeight,
                textWidth,
                textHeight,
                leftInset,
                topInset,
            )
        }
    }

    companion object {
        const val MAX_CACHE_ENTRIES = 64
        const val MAX_CACHE_BYTES = 2 * 1024 * 1024
        const val MAX_TEXT_CHARACTERS = 256
        private const val SUPERSAMPLE = 2

        @JvmStatic
        fun createRasterText(
            textureKey: String,
            png: ByteArray,
            imageWidth: Int,
            imageHeight: Int,
            textWidth: Int,
            textHeight: Int,
            leftInset: Int,
            topInset: Int,
        ): RasterText = RasterText.create(
            textureKey,
            png,
            imageWidth,
            imageHeight,
            textWidth,
            textHeight,
            leftInset,
            topInset,
        )
    }
}
