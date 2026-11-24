package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Voxel art for the music note, frost and cherry blossom auras and for the loose parts of the skirts (kilt
 * pouch, maid bow). All in half-pixel voxels. Local frames: notes, flakes and petals are flat in the x/y plane
 * (y grows down) around their own center; the crystal cluster stands on y = 0 and grows toward -y; the pouch
 * hangs from its top edge at y = 0 with its front toward -z; the bow sits on z = 0 and grows toward +z.
 */
object CosmeticModelsE {
    private const val FINE = 0.5f

    /** Note meshes with the model-pixel center to rotate them about. */
    class Note(val mesh: VoxelMesh, val centerX: Float, val centerY: Float)

    val NOTES: List<Note> by lazy {
        listOf(
            Note(eighthNote().mesh(FINE), 5.5f * FINE, 8f * FINE),
            Note(beamedNotes().mesh(FINE), 9.5f * FINE, 8f * FINE),
            Note(quarterNote().mesh(FINE), 4.5f * FINE, 8f * FINE),
        )
    }

    val FLAKE_BIG: VoxelMesh by lazy { flake(7.5f, true).mesh(FINE) }
    val FLAKE_SMALL: VoxelMesh by lazy { flake(4.5f, false).mesh(FINE) }

    /** Ice crystal clusters standing on the floor; the cluster is about 7 pixels tall. */
    val CRYSTALS: List<VoxelMesh> by lazy { listOf(crystals(0).mesh(FINE), crystals(1).mesh(FINE)) }

    val PETAL_BIG: VoxelMesh by lazy { petal(4.2f, 2.8f).mesh(FINE) }
    val PETAL_SMALL: VoxelMesh by lazy { petal(3.2f, 2.2f).mesh(FINE) }

    /** A whole sakura blossom, flat in the x/y plane: five notched petals, a darker heart and yellow stamens. */
    val BLOSSOM: VoxelMesh by lazy { blossom().mesh(FINE) }

    val POUCH: VoxelMesh by lazy { pouch().mesh(FINE) }
    val BOW: VoxelMesh by lazy { bow().mesh(FINE) }
    val BOW_TAIL: VoxelMesh by lazy { bowTail().mesh(FINE) }

    // ---- drawing helpers -----------------------------------------------------------------------------------

    private fun tone(x: Int, y: Int, z: Int, base: Float, amount: Float = 0.03f): Float =
        ((base + CosmeticModels.jitter(x, y, z, amount)) * 20f).toInt() / 20f

    /** An ellipse in the plane z = [z], rotated by [angle]; [shade] gets the normalized position (-1..1) inside. */
    private fun ellipse(
        grid: VoxelGrid, cx: Float, cy: Float, rx: Float, ry: Float, angle: Float, z: Int, channel: Int,
        shade: (Float, Float) -> Float,
    ) {
        val reach = ceil(max(rx, ry)).toInt() + 1
        val c = cos(angle)
        val s = sin(angle)
        for (x in floor(cx).toInt() - reach..floor(cx).toInt() + reach) {
            for (y in floor(cy).toInt() - reach..floor(cy).toInt() + reach) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val u = (dx * c + dy * s) / rx
                val v = (-dx * s + dy * c) / ry
                if (u * u + v * v <= 1f) grid.set(x, y, z, channel, shade(u, v))
            }
        }
    }

    /** A line of voxels from (x0, y0) to (x1, y1) with a width that tapers from [w0] to [w1]. */
    private fun stroke(
        grid: VoxelGrid, x0: Float, y0: Float, x1: Float, y1: Float, w0: Float, w1: Float, z: Int, channel: Int, shade: Float,
    ) {
        val steps = (hypot(x1 - x0, y1 - y0) * 3f).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            val r = (w0 + (w1 - w0) * t) / 2f
            for (gx in floor(x - r).toInt()..ceil(x + r).toInt()) {
                for (gy in floor(y - r).toInt()..ceil(y + r).toInt()) {
                    if (hypot(gx + 0.5f - x, gy + 0.5f - y) <= r + 0.05f) grid.set(gx, gy, z, channel, shade)
                }
            }
        }
    }

    // ---- music notes -----------------------------------------------------------------------------------------------

    /** A note head: a tilted oval two voxels thick, lit from its upper left. */
    private fun head(grid: VoxelGrid, cx: Float, cy: Float) {
        for (z in 0..1) {
            ellipse(grid, cx, cy, 3.4f, 2.4f, -0.5f, z, 0) { u, v ->
                tone(Math.round(u * 5), Math.round(v * 5), z, 1.0f - 0.16f * (u + v) / 2f + 0.07f * z - 0.12f * (u * u + v * v))
            }
        }
    }

    private fun eighthNote(): VoxelGrid {
        val grid = VoxelGrid()
        stroke(grid, 6.5f, 1f, 6.5f, 14f, 2f, 2f, 0, 0, 0.9f)
        stroke(grid, 7.5f, 1.5f, 10.3f, 4.2f, 2f, 1.8f, 0, 0, 0.97f)
        stroke(grid, 10.3f, 4.2f, 10.6f, 7.2f, 1.8f, 1.8f, 0, 0, 0.97f)
        stroke(grid, 10.6f, 7.2f, 8.4f, 10.6f, 1.8f, 1.4f, 0, 0, 0.93f)
        head(grid, 3.8f, 14f)
        return grid
    }

    private fun quarterNote(): VoxelGrid {
        val grid = VoxelGrid()
        stroke(grid, 6.5f, 1f, 6.5f, 14f, 2f, 2f, 0, 0, 0.9f)
        head(grid, 3.8f, 14f)
        return grid
    }

    private fun beamedNotes(): VoxelGrid {
        val grid = VoxelGrid()
        stroke(grid, 6.5f, 2.5f, 6.5f, 14f, 2f, 2f, 0, 0, 0.9f)
        stroke(grid, 16.5f, 0.5f, 16.5f, 12f, 2f, 2f, 0, 0, 0.9f)
        // The beam: a thick slanted bar joining both stems, thicker on the left.
        stroke(grid, 6f, 2.8f, 17f, 0.8f, 3f, 3f, 0, 0, 0.95f)
        stroke(grid, 6f, 6.2f, 17f, 4.2f, 1.6f, 1.6f, 0, 0, 0.88f)
        head(grid, 3.8f, 14f)
        head(grid, 13.8f, 12f)
        return grid
    }

    // ---- snowflakes ------------------------------------------------------------------------------------------------

    /** A six-armed flake in the x/y plane: arms, side branches and a bright hexagonal heart. */
    private fun flake(length: Float, branches: Boolean): VoxelGrid {
        val grid = VoxelGrid()
        for (k in 0 until 6) {
            val angle = k * PI.toFloat() / 3f + PI.toFloat() / 2f
            val dx = cos(angle)
            val dy = sin(angle)
            stroke(grid, dx * 1.2f, dy * 1.2f, dx * length, dy * length, 1.6f, 1.2f, 0, 0, 1.0f)
            if (branches) {
                for (at in floatArrayOf(0.5f, 0.75f)) {
                    val px = dx * length * at
                    val py = dy * length * at
                    for (side in intArrayOf(-1, 1)) {
                        val a = angle + side * 1.05f
                        val l = length * (0.42f - 0.12f * (at - 0.5f) * 4f)
                        stroke(grid, px, py, px + cos(a) * l, py + sin(a) * l, 1.2f, 1.0f, 0, 0, 0.94f)
                    }
                }
            } else {
                val px = dx * length * 0.55f
                val py = dy * length * 0.55f
                for (side in intArrayOf(-1, 1)) {
                    val a = angle + side * 1.05f
                    stroke(grid, px, py, px + cos(a) * length * 0.3f, py + sin(a) * length * 0.3f, 1.1f, 1.0f, 0, 0, 0.94f)
                }
            }
        }
        // The heart is thicker and takes the second color.
        for (z in -1..0) ellipse(grid, 0f, 0f, 1.8f, 1.8f, 0f, z, 1) { u, v -> tone(Math.round(u * 4), Math.round(v * 4), z, 1.05f - 0.12f * (u * u + v * v)) }
        return grid
    }

    // ---- ice crystals ----------------------------------------------------------------------------------------------

    /**
     * One crystal: a faceted spike standing on y = 0 (tip toward -y). The two facing sides are lit differently so
     * it reads as cut ice; the last three voxel rows take the second color (frosted tip).
     */
    private fun spike(grid: VoxelGrid, cx: Float, cz: Float, height: Int, radius: Float, lean: Float, seed: Int) {
        for (y in 0 until height) {
            val t = y / height.toFloat()
            val r = radius * (1f - t).pow(0.85f).coerceAtLeast(0.2f)
            val shift = lean * t * height
            val reach = ceil(r).toInt() + 1
            for (x in floor(cx + shift).toInt() - reach..floor(cx + shift).toInt() + reach) {
                for (z in floor(cz).toInt() - reach..floor(cz).toInt() + reach) {
                    val dx = x + 0.5f - cx - shift
                    val dz = z + 0.5f - cz
                    // Hexagon: three pairs of facets.
                    val hex = max(abs(dx), max(abs(dx * 0.5f + dz * 0.866f), abs(dx * 0.5f - dz * 0.866f)))
                    if (hex > r) continue
                    val facet = (dx * 0.62f - dz * 0.78f) / max(r, 0.5f)
                    val edge = hex > r - 0.7f
                    val tip = y >= height - 3
                    val shade = (0.92f + 0.28f * facet.coerceIn(-1f, 1f)) * (if (edge) 1.06f else 0.95f) * (0.88f + 0.2f * t)
                    grid.set(x, -y - 1, z, if (tip) 1 else 0, tone(x, y, z + seed, shade, 0.02f))
                }
            }
        }
    }

    private fun crystals(variant: Int): VoxelGrid {
        val grid = VoxelGrid()
        if (variant == 0) {
            spike(grid, 0f, 0f, 15, 2.6f, 0f, 0)
            spike(grid, -3.4f, 1f, 9, 2.0f, -0.05f, 1)
            spike(grid, 3.2f, -0.8f, 11, 2.2f, 0.05f, 2)
            spike(grid, 1.2f, 2.8f, 6, 1.6f, 0.02f, 3)
        } else {
            spike(grid, 0f, 0f, 12, 2.4f, 0.03f, 4)
            spike(grid, 3.4f, 0.6f, 15, 2.5f, 0.06f, 5)
            spike(grid, -3.0f, -0.6f, 8, 1.9f, -0.06f, 6)
            spike(grid, -0.8f, -2.8f, 6, 1.5f, 0f, 7)
        }
        return grid
    }

    // ---- cherry blossom petals -----------------------------------------------------------------------------------

    /** A petal: an oval notched at its far end, cupped (edges bend toward +z), with a darker vein at the base. */
    private fun petal(rx: Float, ry: Float): VoxelGrid {
        val grid = VoxelGrid()
        val reach = ceil(rx).toInt() + 1
        val notch = ry * 0.55f
        for (x in -reach..reach) for (y in -reach..reach) {
            val dx = x + 0.5f
            val dy = y + 0.5f
            val u = dx / rx
            // Widest toward the notch end, narrow at the stem end.
            val v = dy / (ry * (0.62f + 0.38f * ((u + 1f) / 2f)))
            if (u * u + v * v > 1f) continue
            if (hypot(dx - rx, dy) < notch) continue
            val cup = (0.8f * v * v + 0.5f * u * u).let { if (it > 0.55f) 1 else 0 }
            val vein = dx < 0.1f && abs(dy) < 0.9f
            val tip = u > 0.55f
            val shade = (if (vein) 0.95f else if (tip) 1.15f else 1.08f) - 0.05f * (u * u + v * v)
            grid.set(x, y, cup, if (vein) 1 else 0, tone(x, y, cup, shade, 0.03f))
            if (!vein && u < 0.4f && abs(v) < 0.5f) grid.set(x, y, cup + 1, 0, tone(x, y, cup + 1, shade - 0.1f, 0.03f))
        }
        return grid
    }

    private fun blossom(): VoxelGrid {
        val grid = VoxelGrid()
        val stamen = grid.fixed(0xF6D35A)
        val tau = 2f * PI.toFloat()
        for (k in 0 until 5) {
            val angle = k * tau / 5f - PI.toFloat() / 2f
            val dx = cos(angle)
            val dy = sin(angle)
            // Each petal is an oval pointing out from the middle; brighter toward its tip.
            ellipse(grid, dx * 3.2f, dy * 3.2f, 3.1f, 2.3f, angle, 0, 0) { u, v -> tone(Math.round(u * 4), Math.round(v * 4), k, 1.02f + 0.1f * u - 0.06f * v * v) }
            // The sakura notch: a small bite out of the petal tip.
            val nx = dx * 6.4f
            val ny = dy * 6.4f
            for (x in floor(nx - 2f).toInt()..ceil(nx + 2f).toInt()) for (y in floor(ny - 2f).toInt()..ceil(ny + 2f).toInt()) {
                if (hypot(x + 0.5f - nx, y + 0.5f - ny) < 1.1f) grid.clear(x, y, 0)
            }
        }
        // Darker pink heart, raised a little, with stamens around it.
        for (z in -1..0) ellipse(grid, 0f, 0f, 1.6f, 1.6f, 0f, z, 1) { u, v -> tone(Math.round(u * 4), Math.round(v * 4), z, 0.95f - 0.08f * (u * u + v * v)) }
        for (k in 0 until 5) {
            val angle = k * tau / 5f - PI.toFloat() / 2f + tau / 10f
            grid.set(Math.round(cos(angle) * 2.2f - 0.5f), Math.round(sin(angle) * 2.2f - 0.5f), -1, stamen, 1.1f)
        }
        return grid
    }

    // ---- kilt pouch ------------------------------------------------------------------------------------------------

    /** The sporran: a black leather pouch hanging from its top edge, with a pointed flap and a gold clasp. */
    private fun pouch(): VoxelGrid {
        val grid = VoxelGrid()
        val gold = grid.fixed(SkirtGeometry.GOLD_RGB)
        for (y in 0 until 10) {
            val trim = if (y >= 9) 2 else if (y >= 8) 1 else 0
            for (x in -4 + trim..4 - trim) {
                val edge = x == -4 + trim || x == 4 - trim || y == 9
                for (z in -2..-1) grid.set(x, y, z, 1, tone(x, y, z, if (edge) 0.7f else 0.85f))
            }
        }
        // The flap lies over the top half and ends in a point.
        for (y in 0 until 7) {
            val half = if (y < 4) 4 else 4 - (y - 3)
            for (x in -half..half) {
                val edge = x == -half || x == half || y == 6
                grid.set(x, y, -3, 1, tone(x, y, -3, if (edge) 0.72f else 1.0f - 0.04f * y))
            }
        }
        // Clasp: a small gold plate with a bright corner.
        for (x in -1..0) for (y in 4..5) grid.set(x, y, -4, gold, if (x == -1 && y == 4) 1.25f else 1.0f)
        // Studs on the flap's top corners.
        grid.set(-3, 1, -4, gold, 1.0f)
        grid.set(3, 1, -4, gold, 1.0f)
        return grid
    }

    // ---- maid bow --------------------------------------------------------------------------------------------------

    /** Two loops and a knot, lying on z = 0 and standing out toward +z; origin at the knot's center. */
    private fun bow(): VoxelGrid {
        val grid = VoxelGrid()
        for (side in intArrayOf(-1, 1)) {
            val cx = side * 5f
            val tilt = side * -0.22f
            for (z in 0..2) {
                val shrink = 1f - 0.2f * z
                ellipse(grid, cx, 0f, 3.9f * shrink, 3.1f * shrink, tilt, z, 0) { u, v ->
                    // A fold pressed across each loop: darker toward the knot, lighter on the outer curve.
                    val outward = u * side
                    tone(Math.round(u * 6), Math.round(v * 6), z, 0.82f + 0.2f * outward + 0.05f * z - 0.08f * abs(v))
                }
            }
            // Hollow center of the loop, so it reads as a ribbon and not a disc.
            ellipse(grid, cx + side * 0.4f, 0.1f, 1.5f, 1.0f, tilt, 2, 0) { _, _ -> 0.62f }
        }
        for (z in 0..3) {
            for (x in -2..1) for (y in -2..1) grid.set(x, y, z, 0, tone(x, y, z, if (z == 3) 1.15f else 1.0f - 0.05f * (x + 2)))
        }
        return grid
    }

    /** One tail of the bow: a ribbon hanging from its top, ending in a swallowtail. Mirror it for the other side. */
    private fun bowTail(): VoxelGrid {
        val grid = VoxelGrid()
        for (y in 0 until 12) {
            for (x in 0 until 4) {
                // Swallowtail: a V cut out of the last rows.
                if (y >= 10 && x in 1..2 && y - 10 >= 1 - (if (x == 1 || x == 2) 0 else 1)) continue
                for (z in 0..1) grid.set(x, y, z, 0, tone(x, y, z, 0.88f + 0.06f * x + 0.05f * z - 0.015f * y))
            }
        }
        return grid
    }
}
