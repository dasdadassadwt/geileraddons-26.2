package geiler.addons.client.cosmetics

import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The cosmetic capes: pixel-art capes that replace the vanilla cape and follow its physics exactly. The vanilla
 * cape is one rigid 10 x 16 x 1 plate hinged at the top of the back; its lean, flap and sway come from the
 * render state (`capeLean`, `capeFlap`, `capeLean2`), so [matrix] reproduces the transform of `PlayerCapeModel`
 * and the cape moves like the player's own. Each cape is two pixel layers (outside and inside) in half-pixel
 * voxels, [WIDTH] x [HEIGHT] per face, with a few animated overlays (twinkling stars, a shimmer wave over the
 * scales). Red areas of the reference sheet are channel 0, black areas channel 1; only the gold clasp, white stars,
 * the moon and the ermine fur keep fixed colors.
 */
object CapeRig {
    /** Half-pixels per face. */
    const val WIDTH = 20
    const val HEIGHT = 32

    private const val RAD = 0.017453292f

    private const val C0 = 0
    private const val C1 = 1
    private const val GOLD = 2
    private const val WHITE = 3
    private const val CREAM = 4
    private const val FUR = 5
    private val FIXED_RGB = intArrayOf(0, 0, 0xE6B422, 0xFFFFFF, 0xF3EBD5, 0xF4F1EA)

    /** One pixel of a cape face: what it is made of, its grey shade, how far it stands out, and its animation. */
    private class Px(var kind: Int, var shade: Float, var relief: Int = 0, var twinkle: Int = -1, var shimmer: Int = -1)

    /** A cape face: [HEIGHT] rows of [WIDTH] pixels. */
    private class Face {
        val px = Array(HEIGHT) { Array(WIDTH) { Px(C1, 1f) } }

        fun put(x: Int, y: Int, kind: Int, shade: Float, relief: Int = 0, twinkle: Int = -1, shimmer: Int = -1) {
            if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return
            val p = px[y][x]
            p.kind = kind; p.shade = shade; p.relief = relief; p.twinkle = twinkle; p.shimmer = shimmer
        }
    }

    private class Built(val base: VoxelMesh, val overlays: List<Overlay>)

    /** An animated layer drawn over the cape in the same transform. */
    class Overlay(val mesh: VoxelMesh, val twinkle: Boolean, val group: Int)

    private val built = java.util.EnumMap<CosmeticStyle, Built>(CosmeticStyle::class.java)

    private fun cape(style: CosmeticStyle): Built = synchronized(built) {
        built.getOrPut(style) { build(style) }
    }

    @JvmStatic
    fun mesh(style: CosmeticStyle): VoxelMesh = cape(style).base

    @JvmStatic
    fun overlays(style: CosmeticStyle): List<Overlay> = cape(style).overlays

    // ---- transform -----------------------------------------------------------------------------

    /**
     * The cape's transform in the body's frame (pixels), as vanilla computes it: hinge two pixels behind the body,
     * tilted back by `6 + lean / 2 + flap` degrees, with the sideways sway from [lean2]. Size scales the cape about
     * its hinge; Height and Depth move the hinge.
     */
    @JvmStatic
    fun matrix(piece: CosmeticPiece, lean: Float, flap: Float, lean2: Float): Matrix4f =
        Matrix4f().translate(0f, -piece.up, 2f + piece.back).scale(piece.size)
            .rotateX((6f + lean / 2f + flap) * RAD)
            .rotateZ(lean2 / 2f * RAD)
            .rotateY((180f - lean2 / 2f) * RAD)

    /** Adds the cape (and its animated overlays) to [out]; [anchor] decides whether chest armor pushes it out. */
    @JvmStatic
    fun place(
        piece: CosmeticPiece, motion: CosmeticRig.Motion, lean: Float, flap: Float, lean2: Float,
        anchor: CosmeticRig.Anchor, out: MutableList<CosmeticRig.Placement>,
    ) {
        val m = matrix(piece, lean, flap, lean2)
        val c = cape(piece.style)
        out.add(CosmeticRig.Placement(anchor, c.base, m, piece.color, piece.color2))
        val phase = motion.swayPhase * piece.speed + (motion.seed and 0xFF) / 255f * (2f * PI.toFloat())
        for (o in c.overlays) {
            val alpha = if (o.twinkle) 255f * (0.12f + 0.88f * (0.5f + 0.5f * sin(phase * 0.5f + o.group * 2.1f)))
            else 135f * max(0f, sin(phase * 0.5f - o.group * 0.9f)).pow(2)
            val a = alpha.toInt().coerceIn(0, 255)
            if (a > 4) out.add(CosmeticRig.Placement(anchor, o.mesh, m, piece.color, piece.color2, CosmeticRig.Kind.GLOW, a))
        }
    }

    /** The cape as the card and the offline previews show it: hanging at rest with a slight swing. */
    @JvmStatic
    fun preview(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) =
        place(piece, motion, PREVIEW_LEAN, 0f, 0f, CosmeticRig.Anchor.CAPE, out)

    const val PREVIEW_LEAN = 10f

    // ---- art -----------------------------------------------------------------------------------

    /** ARGB of the pixel ([x], [y]) of a cape face in the given colors, for pictures of the cape's texture. */
    @JvmStatic
    fun colorAt(style: CosmeticStyle, inner: Boolean, x: Int, y: Int, color: Int, color2: Int): Int {
        val p = face(style, inner).px[y][x]
        val tint = when (p.kind) { C0 -> color; C1 -> color2; else -> FIXED_RGB[p.kind] }
        fun ch(v: Int) = (((v and 0xFF) * p.shade).toInt()).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(tint shr 16) shl 16) or (ch(tint shr 8) shl 8) or ch(tint)
    }

    /** Fractions of the face's pixels in channel 0, channel 1 and fixed colors (for the channel checks). */
    @JvmStatic
    fun channelShare(style: CosmeticStyle): FloatArray {
        val share = FloatArray(3)
        for (row in face(style, false).px) for (p in row) share[if (p.kind == C0) 0 else if (p.kind == C1) 1 else 2]++
        for (i in 0 until 3) share[i] /= (WIDTH * HEIGHT).toFloat()
        return share
    }

    private fun face(style: CosmeticStyle, inner: Boolean): Face = when (style) {
        CosmeticStyle.DRAGON_SCALE_CAPE -> dragon(inner)
        CosmeticStyle.STARFIELD_CAPE -> starfield(inner)
        else -> royal(inner)
    }

    private fun build(style: CosmeticStyle): Built {
        val outer = face(style, false)
        val inner = face(style, true)
        val grid = VoxelGrid()
        val channels = IntArray(FIXED_RGB.size) { if (it < 2) it else grid.fixed(FIXED_RGB[it]) }
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val o = outer.px[y][x]
            // z: -2 is the outer half of the 1 px plate, -1 the inner half, -3 a raised relief voxel.
            // Twinkling stars rest dim here; their overlay brightens them.
            val shade = if (o.twinkle >= 0) o.shade * 0.4f else o.shade
            grid.set(x - WIDTH / 2, y, -2, channels[o.kind], shade)
            if (o.relief > 0) grid.set(x - WIDTH / 2, y, -3, channels[o.kind], shade)
            // Seen from the body side the inside is mirrored.
            val i = inner.px[y][WIDTH - 1 - x]
            grid.set(x - WIDTH / 2, y, -1, channels[i.kind], i.shade)
        }
        val overlays = ArrayList<Overlay>()
        for (twinkle in booleanArrayOf(true, false)) for (group in 0 until 4) {
            val g = VoxelGrid()
            val fixedChannels = IntArray(FIXED_RGB.size) { if (it < 2) it else g.fixed(FIXED_RGB[it]) }
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val o = outer.px[y][x]
                if ((if (twinkle) o.twinkle else o.shimmer) != group) continue
                g.set(x - WIDTH / 2, y, -3 - o.relief, fixedChannels[o.kind], if (twinkle) 1.15f else 1.4f)
            }
            if (g.size() > 0) overlays.add(Overlay(g.mesh(0.5f), twinkle, group))
        }
        return Built(grid.mesh(0.5f), overlays)
    }

    private fun quantize(value: Float, step: Float = 0.06f): Float = round(value / step) * step

    private fun jitter(x: Int, y: Int, amount: Float): Float = CosmeticModels.jitter(x, y, 3, amount)

    // ---- dragon scale ----------------------------------------------------------------------------

    private fun dragon(inner: Boolean): Face {
        val f = Face()
        val shift = if (inner) 3 else 0
        val shiftY = if (inner) 2 else 0
        val owner = Array(HEIGHT) { IntArray(WIDTH) { -1 } }
        val centerX = Array(HEIGHT) { FloatArray(WIDTH) }
        val centerY = Array(HEIGHT) { FloatArray(WIDTH) }
        // Bottom rows first: the upper scale overlaps the one below it.
        for (k in 6 downTo 0) {
            val cy = 8.5f + 5f * k - shiftY
            val offset = if (k % 2 == 0) 3 else 6
            for (n in -1..4) {
                val cx = (offset + 6 * n + shift).toFloat()
                for (y in 6 until HEIGHT) for (x in 0 until WIDTH) {
                    val dx = (x + 0.5f - cx) / 3.35f
                    val dy = (y + 0.5f - cy) / 3.5f
                    if (dx * dx + dy * dy <= 1f) {
                        owner[y][x] = k * 10 + n + 2
                        centerX[y][x] = cx
                        centerY[y][x] = cy
                    }
                }
            }
        }
        // Collar: darker neck with the gold clasp curving across it.
        for (y in 0 until 6) for (x in 0 until WIDTH) f.put(x, y, C1, if (y < 4) 0.55f else 0.4f)
        for (x in 0 until WIDTH) {
            val u = (x - 9.5f) / 9.5f
            val arc = 4.6f * (1f - u * u)
            for (y in 0 until 7) if (abs(y + 0.5f - arc - 0.6f) < 1.05f) f.put(x, y, GOLD, if (y + 0.5f > arc + 0.6f) 0.82f else 1.05f)
        }
        for (y in 6 until HEIGHT) for (x in 0 until WIDTH) {
            val id = owner[y][x]
            if (id < 0) {
                f.put(x, y, C1, 0.32f)
                continue
            }
            val covered = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { (nx, ny) ->
                nx in 0 until WIDTH && ny in 6 until HEIGHT && owner[ny][nx] >= 0 && (owner[ny][nx] / 10 < id / 10 || owner[ny][nx] / 10 == id / 10 && owner[ny][nx] > id)
            }
            if (covered) {
                f.put(x, y, C1, 0.36f)
                continue
            }
            val dx = (x + 0.5f - centerX[y][x]) / 3.35f
            val dy = (y + 0.5f - centerY[y][x]) / 3.5f
            val t = sqrt(dx * dx + dy * dy)
            val shade = quantize(1.18f - 0.6f * t.pow(1.4f) - (if (dy > 0f) 0.1f * dy else 0f) + jitter(x, y, 0.025f))
            val highlight = t < 0.55f && dx < 0.15f && dy < 0.1f
            f.put(x, y, C0, shade.coerceIn(0.3f, 1.25f), if (!inner && t < 0.5f) 1 else 0, -1,
                if (!inner && highlight) min(3, y / 8) else -1)
        }
        if (inner) {
            for (row in f.px) for (p in row) p.shade *= 0.82f
        }
        return f
    }

    // ---- starfield --------------------------------------------------------------------------------

    private val RED_BIG = arrayOf(intArrayOf(15, 9), intArrayOf(5, 25), intArrayOf(14, 23))
    private val RED_SMALL = arrayOf(intArrayOf(3, 10), intArrayOf(12, 28), intArrayOf(10, 18), intArrayOf(17, 14))
    private val WHITE_CROSS = arrayOf(intArrayOf(4, 29), intArrayOf(15, 16), intArrayOf(9, 7))
    private val WHITE_DOTS = arrayOf(
        intArrayOf(2, 7), intArrayOf(12, 8), intArrayOf(17, 11), intArrayOf(11, 14), intArrayOf(2, 20), intArrayOf(18, 20),
        intArrayOf(8, 22), intArrayOf(3, 24), intArrayOf(14, 27), intArrayOf(9, 30), intArrayOf(17, 25), intArrayOf(6, 15),
    )
    private val RED_DOTS = arrayOf(intArrayOf(3, 16), intArrayOf(10, 11), intArrayOf(13, 31 - 2), intArrayOf(7, 19), intArrayOf(17, 30))

    private fun starfield(inner: Boolean): Face {
        val f = Face()
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val collar = y < 6 && abs(x - 9.5f) < 6.5f - (5 - y) * 0.2f
            f.put(x, y, C1, if (collar) 0.6f else quantize(1f + (y - 16) * 0.004f + jitter(x, y, 0.03f), 0.03f))
        }
        fun px(x: Int) = if (inner) WIDTH - 1 - x else x
        var group = 0
        for (s in RED_BIG) {
            plus(f, px(s[0]), s[1], 2, C0, 1.0f, group % 4)
            group++
        }
        for (s in RED_SMALL) {
            plus(f, px(s[0]), s[1], 1, C0, 0.95f, group % 4)
            group++
        }
        for (s in WHITE_CROSS) {
            plus(f, px(s[0]), s[1], 1, WHITE, 0.95f, group % 4)
            group++
        }
        for ((i, s) in WHITE_DOTS.withIndex()) {
            val tw = if (i % 3 == 0) group++ % 4 else -1
            dot(f, px(s[0]), s[1], WHITE, if (i % 2 == 0) 0.95f else 0.7f, tw)
        }
        for (s in RED_DOTS) dot(f, px(s[0]), s[1], C0, 0.55f, -1)
        if (!inner) {
            // The moon: a crescent, lit on its left.
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val a = sqrt((x + 0.5f - 7.5f) * (x + 0.5f - 7.5f) + (y + 0.5f - 14f) * (y + 0.5f - 14f))
                val b = sqrt((x + 0.5f - 10.6f) * (x + 0.5f - 10.6f) + (y + 0.5f - 12.3f) * (y + 0.5f - 12.3f))
                if (a <= 5.6f && b > 4.7f) f.put(x, y, CREAM, if (a > 4.6f) 0.82f else 1f)
            }
        } else {
            for (row in f.px) for (p in row) if (p.kind == C1) p.shade *= 0.8f
        }
        return f
    }

    /** A plus-shaped star of arm length [arm] half-pixels centered on ([cx], [cy]); twinkles in [group]. */
    private fun plus(f: Face, cx: Int, cy: Int, arm: Int, kind: Int, shade: Float, group: Int) {
        for (d in -arm..arm) {
            val tip = abs(d) == arm
            f.put(cx + d, cy, kind, if (tip) 0.62f else shade, 0, group)
            if (d != 0) f.put(cx, cy + d, kind, if (tip) 0.62f else shade, 0, group)
        }
        f.put(cx, cy, if (kind == C0) WHITE else kind, 1.05f, 0, group)
    }

    /** A 1 px star (2 x 2 half-pixels). */
    private fun dot(f: Face, x: Int, y: Int, kind: Int, shade: Float, group: Int) {
        for (dy in 0..1) for (dx in 0..1) f.put(x + dx, y + dy, kind, shade, 0, group)
    }

    // ---- royal --------------------------------------------------------------------------------------

    private fun royal(inner: Boolean): Face {
        val f = Face()
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            // Velvet with long vertical folds, a little darker toward the top.
            val fold = 0.5f + 0.5f * sin(x * 0.78f + 0.9f) * sin(x * 0.31f + 2f)
            val shade = 0.74f + 0.3f * fold + (y - 16) * 0.004f + jitter(x, y, 0.015f)
            f.put(x, y, C0, quantize(shade * (if (inner) 0.86f else 1f), 0.03f))
        }
        // Ermine trim: white fur along both sides and the hem, raised a half pixel, with small black tails.
        val trim = 4
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val edge = min(min(x, WIDTH - 1 - x), if (y > 6) HEIGHT - 1 - y else 99)
            if (edge < trim) {
                val tone = if (edge == trim - 1) 0.8f else 0.96f + jitter(x, y, 0.04f)
                f.put(x, y, FUR, tone, if (inner) 0 else 1)
            }
        }
        for (k in 0..4) {
            tail(f, 1, 8 + k * 5)
            tail(f, WIDTH - 3, 8 + k * 5 + 2)
        }
        for (k in 0..2) {
            tail(f, 5 + k * 5, HEIGHT - 3)
            tail(f, 3 + k * 5 + 7, HEIGHT - 4)
        }
        // Collar: the black neckline.
        for (y in 0 until 6) for (x in 0 until WIDTH) {
            if (abs(x - 9.5f) < 7f - (5 - y) * 0.45f) f.put(x, y, C1, if (y < 4) 0.6f else 0.45f)
        }
        return f
    }

    private fun tail(f: Face, x: Int, y: Int) {
        // A short tapered bar, like the black tail tips of ermine.
        f.put(x, y, C1, 0.85f, 1)
        f.put(x, y + 1, C1, 0.85f, 1)
        f.put(x, y + 2, C1, 0.7f, 1)
        f.put(x + 1, y + 1, C1, 0.7f, 1)
    }

}
