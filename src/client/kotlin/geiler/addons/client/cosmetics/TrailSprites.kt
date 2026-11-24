package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pixel art of the trail cosmetics, drawn after the reference sheet: flat sprites one half-pixel voxel thick
 * (flames burn down, flowers sprout and wilt, stars twinkle out, bubbles drift and pop, ender sparks blink out) and
 * the rainbow ribbon's band cells. Sprites are baked once (lazy), facing -z with y down, and bottom-anchored when
 * they stand on the ground (flames, flowers) or centered otherwise. Red areas of the sheet are channel 0, black
 * areas channel 1; only gold centers and white highlights keep a fixed color.
 */
object TrailSprites {
    /** Voxel edge in model pixels. */
    const val VOXEL = 0.5f
    const val FRAMES = 6

    val FLAME: Array<VoxelMesh> by lazy { Array(FRAMES) { flame(it) } }
    val FLOWER: Array<VoxelMesh> by lazy { flowers() }
    val SPARKLE: Array<VoxelMesh> by lazy { Array(FRAMES) { sparkle(it) } }
    val BUBBLE: Array<VoxelMesh> by lazy { Array(FRAMES) { bubble(it) } }
    val ENDER: Array<VoxelMesh> by lazy { enders() }

    /** One pixel of art: a color channel (0, 1) or a fixed RGB, and a grey shade. */
    private class Ink(val channel: Int, val rgb: Int, val shade: Float)

    private fun main(shade: Float) = Ink(0, 0, shade)
    private fun second(shade: Float) = Ink(1, 0, shade)
    private fun fixed(rgb: Int, shade: Float = 1f) = Ink(-1, rgb, shade)

    private const val GOLD = 0xFFC82A
    private const val WHITE = 0xFFFFFF

    private fun bake(width: Int, height: Int, bottom: Boolean, ink: (Int, Int) -> Ink?): VoxelMesh {
        val grid = VoxelGrid()
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = ink(x, y) ?: continue
            val channel = if (pixel.channel >= 0) pixel.channel else grid.fixed(pixel.rgb)
            grid.set(x - width / 2, if (bottom) y - height else y - height / 2, 0, channel, pixel.shade)
        }
        return grid.mesh(VOXEL)
    }

    private fun ascii(rows: List<String>, bottom: Boolean, legend: Map<Char, Ink>): VoxelMesh {
        val width = rows.maxOf { it.length }
        return bake(width, rows.size, bottom) { x, y -> rows[y].getOrNull(x)?.let { legend[it] } }
    }

    private fun hash(x: Int, y: Int, k: Int): Float = TrailHistory.rand(k * 7919 + 13, x * 131 + y, 5)

    // ---- flame -----------------------------------------------------------------------------------

    private const val FLAME_W = 15
    private const val FLAME_H = 17

    private fun bump(bx: Float, center: Float, height: Float, width: Float): Float = height * max(0f, 1f - abs(bx - center) / width)

    /** Is the point (bx across, by above the base, in unscaled flame pixels) inside the flame body? */
    private fun flameInside(bx: Float, by: Float): Boolean {
        if (by < -0.5f) return false
        val body = 6.9f * (sin(PI.toFloat() * min(1f, (by + 1f) / 16.5f)).coerceAtLeast(0f)).pow(0.75f)
        if (abs(bx) > body) return false
        val top = max(
            max(bump(bx, 0f, 17.5f, 5.2f), bump(bx, -4f, 13.4f, 4.2f)),
            max(bump(bx, 4.2f, 14.4f, 4.4f), max(bump(bx, -6.4f, 10f, 3.2f), bump(bx, 6.6f, 10.5f, 3.2f))),
        )
        return by <= max(top, 5.5f) - 0.5f
    }

    /**
     * The six frames of the sheet: a flickering flame with ember pixels above it, then it shrinks, breaks into a
     * yellow core with red embers, scattered gold specks and finally a few sparks.
     */
    private fun flame(frame: Int): VoxelMesh {
        val size = floatArrayOf(1f, 0.97f, 0.8f, 0.55f, 0.36f, 0.22f)[frame]
        val erode = floatArrayOf(0f, 0.03f, 0.14f, 0.4f, 0.72f, 0.9f)[frame]
        val embers = intArrayOf(3, 3, 4, 5, 4, 0)[frame]
        fun inside(x: Int, y: Int): Boolean = flameInside((x - FLAME_W / 2) / size, (FLAME_H - 1 - y) / size)
        return bake(FLAME_W, FLAME_H, true) { x, y ->
            if (inside(x, y)) {
                if (hash(x, y, frame) < erode) return@bake null
                val bx = (x - FLAME_W / 2) / size
                val by = (FLAME_H - 1 - y) / size
                val edge = !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y + 1)
                val d = sqrt((bx / 4.2f) * (bx / 4.2f) + ((by - 4.6f) / 5.4f) * ((by - 4.6f) / 5.4f))
                when {
                    d < 0.32f -> second(1.22f)
                    d < 0.58f -> second(1f)
                    edge && by > 3f -> main(0.7f)
                    edge -> main(0.78f)
                    d < 0.82f -> main(1.22f)
                    by > 11f -> main(0.88f)
                    else -> main(1f)
                }
            } else {
                // Embers: a few loose pixels above and beside the flame.
                var ember: Ink? = null
                for (e in 0 until embers) {
                    val ex = 1 + (hash(e, 1, frame + 20) * (FLAME_W - 2)).toInt()
                    val top = FLAME_H - 1 - (17 * size).toInt()
                    val ey = max(0, top - 1 - (hash(e, 2, frame + 20) * (3 + 6 * (1 - size))).toInt())
                    if (ex == x && ey == y) ember = if (e % 3 == 1) second(1f) else main(if (e % 2 == 0) 1f else 0.78f)
                }
                ember
            }
        }
    }

    // ---- flower ----------------------------------------------------------------------------------

    private fun flowers(): Array<VoxelMesh> {
        val legend = mapOf(
            'P' to main(1.0f), 'p' to main(0.8f), 'd' to main(0.55f), 'D' to main(0.4f), 'Y' to fixed(GOLD),
            'S' to second(1f), 's' to second(0.6f), 'k' to second(0.35f),
        )
        val frames = listOf(
            listOf(
                "....PPP....",
                "..PPPPPPP..",
                "..PPPYPPP..",
                "..pPPPPPp..",
                "....ppp....",
                ".....S.....",
                ".....S.....",
                ".S...S...S.",
                "..S..S..S..",
                "...S.S.S...",
                "....SSS....",
                ".....S.....",
                ".....S.....",
                ".....S.....",
            ),
            listOf(
                "...........",
                "....PPP....",
                "...PPYPP...",
                "....ppp....",
                ".....S.....",
                ".....S.....",
                ".S...S...S.",
                "..S..S..S..",
                "...S.S.S...",
                "....SSS....",
                ".....S.....",
                ".....S.....",
                ".....S.....",
                ".....S.....",
            ),
            listOf(
                "...........",
                "...........",
                "......dd...",
                ".....dpYd..",
                ".....kddd..",
                ".....ks....",
                "....ks.....",
                "....S......",
                ".S..S..s...",
                "..S.S.s....",
                "...SSs.....",
                "....S......",
                "....S......",
                "....S......",
            ),
            listOf(
                "...........",
                "...........",
                "...........",
                "...........",
                "....kkk....",
                "...k...ddd.",
                "..k....ddd.",
                ".s.........",
                "k..kk......",
                ".k.k.s.....",
                "..kk.......",
                "...k.......",
                "...k.......",
                "...k.......",
            ),
            listOf(
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...k.......",
                ".k..k.....D",
                "..k.k.s....",
                "k..ks......",
                "...k.......",
                ".....s.....",
                "...........",
            ),
            listOf(
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                ".k.........",
                "..ks.....s.",
                "...........",
            ),
        )
        return Array(frames.size) { ascii(frames[it], true, legend) }
    }

    // ---- sparkle ---------------------------------------------------------------------------------

    private const val STAR = 13

    /**
     * Four-point star with a white core (frame 0), arms shortening and ringed by loose dots as it fades (1 to 3),
     * then scattered white and red pixels and a last white speck.
     */
    private fun sparkle(frame: Int): VoxelMesh {
        val arm = intArrayOf(6, 5, 4, 2, 0, 0)[frame]
        val ring = intArrayOf(1, 1, 1, 0, 0, 0)[frame]
        val c = STAR / 2
        val dots = listOf(
            intArrayOf(-4, -4, 1), intArrayOf(4, -4, 0), intArrayOf(-4, 4, 0), intArrayOf(4, 4, 1),
            intArrayOf(-5, 2, 0), intArrayOf(3, -5, 1),
        )
        val loose = when (frame) {
            3 -> listOf(intArrayOf(-3, -3, 1), intArrayOf(3, -2, 0), intArrayOf(-3, 3, 0), intArrayOf(4, 4, 1))
            4 -> listOf(intArrayOf(-3, 2, 1), intArrayOf(0, 3, 0), intArrayOf(3, -1, 1), intArrayOf(2, 4, 0))
            5 -> listOf(intArrayOf(1, 1, 1))
            else -> emptyList()
        }
        return bake(STAR, STAR, false) { x, y ->
            val dx = x - c
            val dy = y - c
            val ax = abs(dx)
            val ay = abs(dy)
            if (frame <= 2) {
                when {
                    ax + ay <= 1 -> fixed(WHITE, 1.0f)
                    ax + ay <= 2 && (ax == 0 || ay == 0 || frame <= 1) -> main(1.0f)
                    (ax <= 1 || ay <= 1) && ax + ay <= 3 && frame <= 1 -> main(0.92f)
                    ax == 1 && ay == 1 -> main(0.85f)
                    (ax == 0 && ay <= arm) || (ay == 0 && ax <= arm) -> main(if (ax + ay >= arm - 1) 0.85f else 1f)
                    ring == 1 && dots.any { it[0] == dx && it[1] == dy && (frame == 0 || it[2] == 1) } && frame > 0 ->
                        if (frame == 2) fixed(WHITE, 0.9f) else main(0.9f)
                    else -> null
                }
            } else {
                val hit = loose.firstOrNull { it[0] == dx && it[1] == dy }
                when {
                    frame == 3 && ax + ay <= 1 -> if (ax + ay == 0) main(1.0f) else main(0.9f)
                    frame == 3 && ((ax == 0 && ay <= arm) || (ay == 0 && ax <= arm)) -> main(0.85f)
                    hit != null -> if (hit[2] == 1) fixed(WHITE, 0.95f) else main(0.95f)
                    else -> null
                }
            }
        }
    }

    // ---- bubble ----------------------------------------------------------------------------------

    private fun disc(size: Int, radius: Float, cx: Float, cy: Float, x: Int, y: Int): Float =
        sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy)) - radius

    private fun bubbleBody(size: Int, radius: Float, x: Int, y: Int, cx: Float = size / 2f, cy: Float = size / 2f): Ink? {
        val d = disc(size, radius, cx, cy, x, y)
        if (d > 0.35f) return null
        val dx = x + 0.5f - cx
        val dy = y + 0.5f - cy
        if (d > -1.0f) return main(if (dx + dy > 0.3f * radius) 0.82f else 1f)
        // Soft highlight in the upper left, white film elsewhere with a faint tint toward the lower right.
        val hx = x + 0.5f - (cx - radius * 0.45f)
        val hy = y + 0.5f - (cy - radius * 0.45f)
        if (hx * hx + hy * hy < radius * radius * 0.11f || (hx * hx + hy * hy < radius * radius * 0.2f && hx > 0f)) {
            return fixed(WHITE, 1f)
        }
        return second(if (dx + dy > 0.2f * radius) 0.86f else 1f)
    }

    private fun bubble(frame: Int): VoxelMesh {
        return when (frame) {
            0 -> bake(12, 12, false) { x, y -> bubbleBody(12, 5.6f, x, y) }
            1 -> bake(12, 12, false) { x, y -> bubbleBody(12, 4.6f, x, y) }
            2 -> bake(12, 12, false) { x, y -> bubbleBody(12, 3.6f, x, y) }
            3 -> bake(12, 12, false) { x, y ->
                bubbleBody(12, 2.3f, x, y, 3.5f, 8f) ?: bubbleBody(12, 1.7f, x, y, 9f, 3.5f)
                    ?: if (x == 6 && y == 6 || x == 5 && y == 7) fixed(WHITE) else null
            }
            4 -> bake(12, 12, false) { x, y ->
                when {
                    x == 3 && y == 3 || x == 8 && y == 4 || x == 5 && y == 8 -> fixed(WHITE)
                    x == 4 && y == 3 -> main(0.9f)
                    x == 7 && y == 9 -> main(0.9f)
                    else -> null
                }
            }
            else -> bake(12, 12, false) { x, y -> if (x == 6 && y == 6) fixed(WHITE) else if (x == 4 && y == 7) main(0.9f) else null }
        }
    }

    // ---- ender sparks ----------------------------------------------------------------------------

    private fun enders(): Array<VoxelMesh> {
        val legend = mapOf('P' to main(1f), 'H' to main(1.25f), 'p' to main(0.75f), 'K' to second(1f), 'k' to second(0.8f))
        val frames = listOf(
            listOf(
                "...........",
                "........K..",
                ".....P.....",
                "....PHP.K..",
                ".K...P.....",
                "...........",
                ".........K.",
                "...........",
                "....KK.....",
                "....KK.....",
            ),
            listOf(
                "...........",
                "..PP.......",
                "..PP...K...",
                "K..........",
                "........pp.",
                ".......K...",
                "...........",
                "....PP.....",
                "....PP..K..",
                "..K........",
            ),
            listOf(
                "...........",
                "...........",
                "..p......P.",
                "......K....",
                ".K.........",
                ".......P...",
                "..........K",
                "...pp......",
                "...........",
                "K.....K....",
            ),
            listOf(
                "...........",
                "...........",
                "...........",
                "..p....K...",
                "...........",
                ".....P.....",
                ".K.........",
                "...........",
                "........p..",
                "......K....",
            ),
            listOf(
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "....p......",
                "...........",
                ".......p...",
                "...........",
                "...........",
            ),
            listOf(
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "......p....",
                "...........",
                "...........",
                "...........",
            ),
        )
        return Array(frames.size) { ascii(frames[it], false, legend) }
    }

    // ---- rainbow ribbon -------------------------------------------------------------------------

    const val BANDS = 6

    /** Height of one band (model pixels); the ribbon is centered on its path. */
    const val BAND_HEIGHT = 1.5f

    /** Half thickness of the ribbon (model pixels). */
    const val RIBBON_HALF = 0.5f

    /** The sheet's hue of each band (red, orange, yellow, green, blue, violet) as a fraction of the sweep. */
    private val HUE_CURVE = floatArrayOf(0f, 28f / 280f, 55f / 280f, 125f / 280f, 215f / 280f, 1f)

    /**
     * The six band colors. [top] is the first band's color and [bottom] the last; the bands between sweep the hue
     * from one to the other the long way round (so the default red to violet is the rainbow), while saturation and
     * brightness blend linearly. A grey or white end (no hue of its own) borrows the other end's hue, so blue and
     * white is a plain blue-to-white ribbon.
     */
    @JvmStatic
    fun bandColors(top: Int, bottom: Int): IntArray {
        val a = hsv(top)
        val b = hsv(bottom)
        if (a[1] < 0.12f) a[0] = b[0]
        if (b[1] < 0.12f) b[0] = a[0]
        var span = (b[0] - a[0]) % 360f
        if (span < 0f) span += 360f
        return IntArray(BANDS) { i ->
            val t = i / (BANDS - 1).toFloat()
            rgb((a[0] + span * HUE_CURVE[i]) % 360f, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
        }
    }

    private fun hsv(color: Int): FloatArray {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val bl = (color and 0xFF) / 255f
        val high = max(r, max(g, bl))
        val low = min(r, min(g, bl))
        val delta = high - low
        var hue = when {
            delta < 1e-5f -> 0f
            high == r -> 60f * (((g - bl) / delta) % 6f)
            high == g -> 60f * ((bl - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        if (hue < 0f) hue += 360f
        return floatArrayOf(hue, if (high <= 0f) 0f else delta / high, high)
    }

    private fun rgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(value: Float) = ((value + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private val ribbonCells = object : LinkedHashMap<Long, VoxelMesh>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, VoxelMesh>): Boolean = size > 192
    }

    /**
     * One unit-long stretch of ribbon along +z (scaled by the rig to the segment), with only the bands in [mask]
     * (bit 0 = top band). Band colors are baked as fixed colors, so a cell is shared by every segment with the same
     * colors and mask; the cache is bounded.
     */
    @JvmStatic
    fun ribbonCell(color: Int, color2: Int, mask: Int): VoxelMesh = synchronized(ribbonCells) {
        val key = ((color and 0xFFFFFF).toLong() shl 30) xor ((color2 and 0xFFFFFF).toLong() shl 6) xor mask.toLong()
        ribbonCells.getOrPut(key) { buildCell(bandColors(color, color2), mask) }
    }

    private fun buildCell(colors: IntArray, mask: Int): VoxelMesh {
        val positions = ArrayList<Float>()
        val directions = ArrayList<Byte>()
        val channels = ArrayList<Byte>()
        val shades = ArrayList<Float>()
        fun quad(direction: Int, channel: Int, shade: Float, vararg p: Float) {
            for (v in p) positions.add(v)
            directions.add(direction.toByte())
            channels.add(channel.toByte())
            shades.add(shade)
        }
        val half = BANDS * BAND_HEIGHT / 2f
        for (band in 0 until BANDS) {
            if (mask and (1 shl band) == 0) continue
            val y0 = -half + band * BAND_HEIGHT
            val y1 = y0 + BAND_HEIGHT
            val channel = 2 + band
            val h = RIBBON_HALF
            quad(0, channel, 0.94f, -h, y0, 0f, -h, y0, 1f, -h, y1, 1f, -h, y1, 0f)
            quad(1, channel, 1.0f, h, y0, 0f, h, y0, 1f, h, y1, 1f, h, y1, 0f)
            if (band == 0 || mask and (1 shl (band - 1)) == 0) quad(2, channel, 1.08f, -h, y0, 0f, h, y0, 0f, h, y0, 1f, -h, y0, 1f)
            if (band == BANDS - 1 || mask and (1 shl (band + 1)) == 0) quad(3, channel, 0.82f, -h, y1, 0f, h, y1, 0f, h, y1, 1f, -h, y1, 1f)
        }
        return VoxelMesh(
            positions.toFloatArray(), directions.toByteArray(), channels.toByteArray(), shades.toFloatArray(), null, colors,
        )
    }

    @JvmStatic
    fun cachedCells(): Int = synchronized(ribbonCells) { ribbonCells.size }
}
