package geiler.addons.client.dungeon

import java.awt.Color
import java.awt.Font
import java.awt.FontFormatException
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.LineMetrics
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.LinkedHashMap
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Bounded Java2D rasterization and caching behind the stable Java RasterText type. */
class DungeonRoomMapRasterTextLogic {
    private val entries = LinkedHashMap<Key, DungeonRoomMapRasterText.RasterText>(16, 0.75f, true)
    private var cachedBytes = 0

    /** Returns null when the input cannot be rasterized within the declared per-entry bounds. */
    fun get(
        text: String?,
        argb: Int,
        lineHeight: Int,
        shadow: DungeonRoomMapRasterText.Shadow?,
    ): DungeonRoomMapRasterText.RasterText? {
        if (!valid(text, lineHeight, shadow)) return null
        val key = Key(text!!, argb, lineHeight, shadow!!)
        entries[key]?.let { return it }
        val created = rasterize(key) ?: return null
        val pngBytes = created.pngSize()
        if (pngBytes > MAX_SINGLE_PNG_BYTES) return null
        while (entries.isNotEmpty() && (entries.size >= DungeonRoomMapRasterText.MAX_CACHE_ENTRIES ||
                cachedBytes > DungeonRoomMapRasterText.MAX_CACHE_BYTES - pngBytes)
        ) {
            val iterator = entries.entries.iterator()
            val removed = iterator.next().value
            cachedBytes -= removed.pngSize()
            iterator.remove()
        }
        if (pngBytes > DungeonRoomMapRasterText.MAX_CACHE_BYTES) return null
        entries[key] = created
        cachedBytes += pngBytes
        return created
    }

    fun width(text: String?, lineHeight: Int): Int {
        if (!valid(text, lineHeight, DungeonRoomMapRasterText.Shadow.NONE)) return 0
        val font = fontFor(lineHeight)
        val bounds = font.getStringBounds(text, FONT_CONTEXT)
        return max(0, ceil(bounds.width / SUPERSAMPLE).toInt())
    }

    fun lineHeight(lineHeight: Int): Int {
        val safeHeight = lineHeight.coerceIn(1, 64)
        val font = fontFor(safeHeight)
        val metrics = font.getLineMetrics("Ag", FONT_CONTEXT)
        return max(1, ceil(metrics.height / SUPERSAMPLE).toInt())
    }

    fun cachedEntryCount(): Int = entries.size
    fun cachedByteCount(): Int = cachedBytes

    private fun valid(text: String?, lineHeight: Int, shadow: DungeonRoomMapRasterText.Shadow?): Boolean {
        if (text.isNullOrEmpty() || text.length > DungeonRoomMapRasterText.MAX_TEXT_CHARACTERS ||
            lineHeight !in 1..64 || shadow == null
        ) return false
        for (index in text.indices) {
            val character = text[index]
            if (character == '\n' || character == '\r' || Character.isISOControl(character)) return false
        }
        return true
    }

    private fun rasterize(key: Key): DungeonRoomMapRasterText.RasterText? {
        val font = fontFor(key.lineHeight)
        val textBounds: Rectangle2D = font.getStringBounds(key.text, FONT_CONTEXT)
        val lineMetrics: LineMetrics = font.getLineMetrics("Ag", FONT_CONTEXT)
        val textWidth = max(1, ceil(textBounds.width).toInt())
        val textHeight = max(1, ceil(lineMetrics.height).toInt())
        val imageWidth = textWidth + PADDING_PIXELS * 2
        val imageHeight = textHeight + PADDING_PIXELS * 2
        if (imageWidth > MAX_TEXTURE_DIMENSION || imageHeight > MAX_TEXTURE_DIMENSION) return null

        val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics: Graphics2D = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            graphics.font = font
            val baseline = PADDING_PIXELS + ceil(lineMetrics.ascent).toInt()
            when (key.shadow) {
                DungeonRoomMapRasterText.Shadow.DROP -> {
                    graphics.color = Color(dropShadowColor(key.argb), true)
                    graphics.drawString(key.text, PADDING_PIXELS + SUPERSAMPLE, baseline + SUPERSAMPLE)
                }
                DungeonRoomMapRasterText.Shadow.OUTLINE -> {
                    graphics.color = Color(0xD0000000.toInt(), true)
                    graphics.drawString(key.text, PADDING_PIXELS - SUPERSAMPLE, baseline)
                    graphics.drawString(key.text, PADDING_PIXELS + SUPERSAMPLE, baseline)
                    graphics.drawString(key.text, PADDING_PIXELS, baseline - SUPERSAMPLE)
                    graphics.drawString(key.text, PADDING_PIXELS, baseline + SUPERSAMPLE)
                }
                DungeonRoomMapRasterText.Shadow.NONE -> Unit
            }
            graphics.color = Color(key.argb, true)
            graphics.drawString(key.text, PADDING_PIXELS, baseline)
        } finally {
            graphics.dispose()
        }

        try {
            ByteArrayOutputStream().use { output ->
                if (!ImageIO.write(image, "png", output)) return null
                val png = output.toByteArray()
                val token = digest(
                    key.text + '\u0000' + java.lang.Integer.toUnsignedString(key.argb) +
                        '\u0000' + key.lineHeight + '\u0000' + key.shadow.name,
                )
                return DungeonRoomMapRasterText.createRasterText(
                    token,
                    png,
                    imageWidth,
                    imageHeight,
                    ceil(textBounds.width / SUPERSAMPLE).toInt(),
                    ceil(lineMetrics.height / SUPERSAMPLE).toInt(),
                    PADDING_PIXELS / SUPERSAMPLE,
                    PADDING_PIXELS / SUPERSAMPLE,
                )
            }
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        } finally {
            image.flush()
        }
    }

    private fun dropShadowColor(argb: Int): Int = (argb and 0xFF000000.toInt()) or ((argb and 0x00FCFCFC) ushr 2)

    private fun fontFor(lineHeight: Int): Font {
        val size = (lineHeight * SUPERSAMPLE * 0.82f).coerceIn(2.0f, 128.0f)
        return BASE_FONT.deriveFont(Font.PLAIN, size)
    }

    private fun digest(value: String): String {
        return try {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            HexFormat.of().formatHex(bytes, 0, 16)
        } catch (_: NoSuchAlgorithmException) {
            java.lang.Integer.toUnsignedString(value.hashCode(), 16).lowercase(Locale.ROOT)
        }
    }

    private data class Key(
        val text: String,
        val argb: Int,
        val lineHeight: Int,
        val shadow: DungeonRoomMapRasterText.Shadow,
    )

    companion object {
        private const val SUPERSAMPLE = 2
        private const val PADDING_PIXELS = 2 * SUPERSAMPLE
        private const val MAX_TEXTURE_DIMENSION = 1024
        private const val MAX_SINGLE_PNG_BYTES = 128 * 1024
        private val FONT_CONTEXT = FontRenderContext(AffineTransform(), true, true)
        private val BASE_FONT = loadBaseFont()

        private fun loadBaseFont(): Font {
            try {
                val input: InputStream? = DungeonRoomMapRasterText::class.java.getResourceAsStream(
                    "/assets/geileraddons/font/scoreboard/noto_sans.ttf",
                )
                if (input != null) input.use { return Font.createFont(Font.TRUETYPE_FONT, it) }
            } catch (_: IOException) {
                // Fall through to Minecraft's logical font.
            } catch (_: FontFormatException) {
                // Fall through to Minecraft's logical font.
            } catch (_: RuntimeException) {
                // Fall through to Minecraft's logical font.
            }
            return Font(Font.SANS_SERIF, Font.PLAIN, 12)
        }
    }
}
