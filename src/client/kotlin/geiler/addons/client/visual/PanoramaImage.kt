package geiler.addons.client.visual

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Loads the player's own title-screen photo (`config/geileraddons/panorama.png`, PNG or JPG) off the
 * client thread. Size is checked from the header before decoding, and big photos are scaled down, so a
 * huge file cannot stall or exhaust memory. Nothing here touches Minecraft.
 */
object PanoramaImage {
    /** Long side after scaling; larger than any common screen, small enough to stay cheap. */
    const val MAX_SIDE = 2560
    /** Photos wider or taller than this are refused without decoding. */
    const val MAX_INPUT_SIDE = 12_000
    private const val MAX_FILE_BYTES = 48L * 1024 * 1024

    /** [pixels] is ARGB row-major, or null with [reason] saying why the photo cannot be used. */
    class Result(val pixels: IntArray?, val width: Int, val height: Int, val reason: String) {
        fun pixels(): IntArray? = pixels
        fun width(): Int = width
        fun height(): Int = height
        fun reason(): String = reason
    }

    @JvmStatic
    fun load(path: Path): Result {
        try {
            if (!Files.isRegularFile(path)) return fail("missing")
            if (Files.size(path) > MAX_FILE_BYTES) return fail("file-too-large")
            val image = ImageIO.createImageInputStream(path.toFile()).use { input ->
                if (input == null) return fail("unreadable")
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return fail("unreadable")
                val reader = readers.next()
                try {
                    reader.input = input
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w < 1 || h < 1) return fail("unreadable")
                    if (w > MAX_INPUT_SIDE || h > MAX_INPUT_SIDE) return fail("too-large-dimensions")
                    reader.read(0)
                } finally {
                    reader.dispose()
                }
            }
            val scaled = scaleDown(image)
            val pixels = scaled.getRGB(0, 0, scaled.width, scaled.height, null, 0, scaled.width)
            return Result(pixels, scaled.width, scaled.height, "ok")
        } catch (_: java.io.IOException) {
            return fail("unreadable")
        } catch (_: RuntimeException) {
            return fail("unreadable")
        } catch (_: OutOfMemoryError) {
            return fail("out-of-memory")
        }
    }

    private fun scaleDown(source: BufferedImage): BufferedImage {
        val longSide = maxOf(source.width, source.height)
        if (longSide <= MAX_SIDE && source.type == BufferedImage.TYPE_INT_ARGB) return source
        val factor = minOf(1.0, MAX_SIDE.toDouble() / longSide)
        val w = maxOf(1, Math.round(source.width * factor).toInt())
        val h = maxOf(1, Math.round(source.height * factor).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun fail(reason: String) = Result(null, 0, 0, reason)
}
