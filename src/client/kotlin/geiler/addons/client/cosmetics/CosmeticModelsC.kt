package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Voxel art for the FACE and BACK slots, drawn after the owner's reference sheets, in half-pixel voxels.
 * Channel 0 is the player's Color (the red of the sheets), channel 1 the Second Color (the black); only real
 * material accents (gold, steel, lens shine, flames, wood) use fixed colors. Built once and cached.
 *
 * Local frames (model pixels, y down, z backward, +x the player's left):
 * - face pieces: origin at the head's center, so the hat layer sits at +-4.5; they are mirror-symmetric.
 * - back pieces: x = 0 on the spine, y = 0 at the shoulders, z = 0 on the item's front plane, which the rig
 *   mounts [BACK_MOUNT] behind the body's back face.
 * - the harness meshes are in plain body coordinates (the body is 8 x 12 x 4 around x = 0, z = 0).
 */
object CosmeticModelsC {
    /** Half-pixel voxels. */
    private const val H = 0.5f

    /** Local z = 0 of every back piece sits here in body coordinates: the back face (z = 2) plus half a pixel. */
    const val BACK_MOUNT = 2.5f

    // ---- face ---------------------------------------------------------------------------------
    val SUNGLASSES: VoxelMesh by lazy { sunglasses().mesh(H) }
    val GLINT: VoxelMesh by lazy { glint().mesh(H) }
    val FOX_MASK: VoxelMesh by lazy { foxMask().mesh(H) }
    val FOX_EAR: VoxelMesh by lazy { foxEar().mesh(H) }
    val PLAGUE_MASK: VoxelMesh by lazy { plagueMask().mesh(H) { x, y, z -> dome(x, y, z) } }
    val PLAGUE_BEAK: VoxelMesh by lazy { plagueBeak().mesh() }
    val BANDANA: VoxelMesh by lazy { bandana().mesh(H) }
    val BANDANA_TAIL: VoxelMesh by lazy { bandanaTail().mesh(H) }

    // ---- back ---------------------------------------------------------------------------------
    val BACKPACK: VoxelMesh by lazy { backpack().mesh(H) }
    val BACKPACK_BEDROLL: VoxelMesh by lazy { bedroll().mesh(H) { x, y, z -> bedrollNormal(x, y, z) } }
    val BACKPACK_FLAP: VoxelMesh by lazy { backpackFlap().mesh(H) }
    val HARNESS: VoxelMesh by lazy { harness().mesh(H) }
    val KATANA: VoxelMesh by lazy { katana().mesh(H) }
    val JETPACK: VoxelMesh by lazy { jetpack().mesh(H) { x, y, z -> tankNormal(x, y, z) } }
    val FLAME_OUTER: VoxelMesh by lazy { flame(1.25f, 8f, 0xFF8A1F, 1.0f).mesh(H) }
    val FLAME_CORE: VoxelMesh by lazy { flame(0.7f, 5.5f, 0xFFE680, 1.1f).mesh(H) }
    val TURTLE_SHELL: VoxelMesh by lazy { turtleShell().mesh(H) { x, y, z -> shellNormal(x, y, z) } }
    val GUITAR: VoxelMesh by lazy { guitar().mesh(H) }
    val GUITAR_STRAP: VoxelMesh by lazy { guitarStrap().mesh(H) }

    /** Where the jetpack nozzles end, in the jetpack's frame: flames hang from here. */
    const val NOZZLE_X = 2.2f
    const val NOZZLE_Y = 10.3f
    const val NOZZLE_Z = 2.3f

    /** Hinge of the backpack's pocket flap (pack frame). */
    const val FLAP_HINGE_Y = 6.3f
    const val FLAP_HINGE_Z = 4.4f

    /** Guitar: where it hangs from the strap (upper pin, body coordinates) and its resting pose. */
    const val GUITAR_PIN_X = -1.87f
    const val GUITAR_PIN_Y = 6.57f
    const val GUITAR_ORIGIN_X = -0.8f
    const val GUITAR_ORIGIN_Y = 5.0f
    const val GUITAR_TILT = -0.65f

    /** Katana: hilt end in the back frame and its resting lean. */
    const val KATANA_X = -4.9f
    const val KATANA_Y = -1.2f
    const val KATANA_TILT = -0.6f

    // ---- helpers ------------------------------------------------------------------------------

    private class Vx(@JvmField val ch: Int, @JvmField val shade: Float)

    /** Fills every voxel whose center (pixels) [f] accepts; null leaves it empty. */
    private fun fill(
        g: VoxelGrid, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        f: (Float, Float, Float) -> Vx?,
    ) {
        for (i in floor(x0 / H).toInt() until ceil(x1 / H).toInt()) {
            for (j in floor(y0 / H).toInt() until ceil(y1 / H).toInt()) {
                for (k in floor(z0 / H).toInt() until ceil(z1 / H).toInt()) {
                    val v = f((i + 0.5f) * H, (j + 0.5f) * H, (k + 0.5f) * H) ?: continue
                    g.set(i, j, k, v.ch, v.shade + CosmeticModels.jitter(i, j, k, 0.015f))
                }
            }
        }
    }

    /** Darkening for the outermost voxel layer, from the distance to the surface (pixels). */
    private fun rim(d: Float, amount: Float = 0.08f): Float = if (d < 0.6f) -amount else 0f

    /** A smooth normal from a center, in voxel units (the mesh smoothing hook gets voxel coordinates). */
    private fun unit(x: Float, y: Float, z: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z).takeIf { it > 1e-4f } ?: 1f
        return floatArrayOf(x / l, y / l, z / l)
    }

    // =============================== FACE ======================================================

    /**
     * Pixel sunglasses: chunky frames (color) around recessed lenses (second color) with a white glint,
     * and temples running back along the sides of the head with a hook behind the ear.
     * Voxel indices: z -11 and -10 are the two layers just outside the hat layer.
     */
    private fun sunglasses(): VoxelGrid {
        val g = VoxelGrid()
        val white = g.fixed(0xFFFFFF)
        for (x in -9 until 9) {
            val i = if (x >= 0) x else -1 - x // 0 at the nose, 8 at the outer edge of each lens
            for (y in -4 until 3) {
                val frame = i == 0 || i == 8 || y <= -3 || y == 2
                if (frame) {
                    val edge = (i == 8 || y == -4 || y == 2) && (i == 0 || i == 8 || y == -4 || y == 2)
                    g.set(x, y, -11, 0, 1.05f - (if (edge) 0.05f else 0f))
                    g.set(x, y, -10, 0, 0.84f)
                } else {
                    g.set(x, y, -10, 1, 0.78f + 0.06f * ((y + 2) / 3f) + 0.05f * (1 - i / 7f))
                }
            }
        }
        // The static shine: two white pixels at the upper left of each lens.
        g.set(1, -2, -10, white, 1.1f); g.set(2, -2, -10, white, 1.1f)
        g.set(-8, -2, -10, white, 1.1f); g.set(-7, -2, -10, white, 1.1f)
        // Temples: out past the lens corner, back to the ear, then a hook downward.
        for (sx in intArrayOf(9, -10)) {
            for (z in -11 until 4) for (y in -3 until -1) g.set(sx, y, z, 0, 0.92f - (if (y == -2) 0.06f else 0f))
            for (z in 2 until 4) for (y in -1 until 4) g.set(sx, y, z, 0, 0.84f)
        }
        return g
    }

    /** The sweep of light over a lens: a slanted streak of 4 x 4 voxels, laid in the recess in front of the lens. */
    private fun glint(): VoxelGrid {
        val g = VoxelGrid()
        val white = g.fixed(0xFFFFFF)
        for (y in 0 until 4) {
            g.set(3 - y, y, 0, white, 1.2f)
            g.set(4 - y, y, 0, white, 1.2f)
        }
        return g
    }

    /** Half widths (voxels) of the fox mask by row y = -9..8: full width to the cheeks, then narrowing to the chin. */
    private fun foxWidth(y: Int): Int = when {
        y <= 2 -> 9
        y == 3 -> 8
        y == 4 -> 7
        y == 5 -> 6
        y == 6 -> 5
        y == 7 -> 4
        else -> 3
    }

    private fun near(c: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Boolean =
        distanceToSegment(c, y, ax, ay, bx, by) <= r

    /**
     * Kitsune mask: a 1 px thick panel in front of the face (fur in color 2) with red markings (color), black
     * eye slits and a snout that reaches 2.5 px forward to a black nose. The ears are a separate mesh.
     */
    private fun foxMask(): VoxelGrid {
        val g = VoxelGrid()
        val black = g.fixed(0x16161B)
        for (y in -9..8) {
            val w = foxWidth(y)
            for (c in 0 until w) {
                val px = c + 0.5f
                val py = y + 0.5f
                var ch = 1
                var shade = 1.0f
                if (near(px, py, 0.5f, -8.5f, 0.5f, -5.5f, 0.9f)) ch = 0
                if (near(px, py, 2.0f, -5.5f, 8.5f, -7.5f, 1.0f)) ch = 0
                if (near(px, py, 8.5f, 1.0f, 4.5f, 3.0f, 1.0f) || near(px, py, 6.5f, 4.5f, 4.0f, 6.0f, 0.9f)) ch = 0
                var tone = ch
                if (near(px, py, 2.5f, -0.5f, 6.5f, -2.5f, 0.7f)) { ch = black; tone = black }
                val edge = c == w - 1 || y == -9 || y == 8 || (y < 8 && c >= foxWidth(y + 1))
                if (edge) shade = 0.84f
                for (x in intArrayOf(c, -1 - c)) {
                    g.set(x, y, -11, tone, shade)
                    g.set(x, y, -10, 1, 0.8f)
                }
            }
        }
        // Snout: tapering box with a black nose on its tip.
        for (z in -13..-12) for (x in -3 until 3) for (y in 2 until 8) {
            g.set(x, y, z, 1, if (y == 2) 1.04f else if (y == 7) 0.8f else 0.95f)
        }
        for (z in -15..-14) for (x in -2 until 2) for (y in 3 until 8) {
            g.set(x, y, z, 1, if (y == 3) 1.04f else if (y == 7) 0.8f else 0.93f)
        }
        for (x in -2 until 2) for (y in 4 until 7) g.set(x, y, -16, black, 1.0f)
        return g
    }

    /** A fox ear of the player's left side (x grows outward), standing on the top of the head. */
    private fun foxEar(): VoxelGrid {
        val g = VoxelGrid()
        val black = g.fixed(0x16161B)
        val tan = g.fixed(0xD7B79A)
        val rows = arrayOf(
            intArrayOf(-10, 4, 8), intArrayOf(-11, 4, 8), intArrayOf(-12, 5, 8), intArrayOf(-13, 5, 8),
            intArrayOf(-14, 6, 8), intArrayOf(-15, 7, 8), intArrayOf(-16, 8, 8),
        )
        for (row in rows) {
            val y = row[0]
            for (c in row[1]..row[2]) {
                val tip = y <= -14
                val inner = !tip && c > row[1] && c < row[2]
                val edge = c == row[2] || c == row[1]
                g.set(c, y, -11, if (tip) black else if (inner) tan else 1, if (edge) 0.86f else 1.0f)
                g.set(c, y, -10, if (tip) black else 1, 0.82f)
                g.set(c, y, -9, if (tip) black else 1, 0.78f)
            }
        }
        return g
    }

    // ---- plague doctor mask -----------------------------------------------------------------------

    private fun domeQ(x: Int, y: Int): Float {
        val xr = (x + 0.5f) / 9f
        val yr = (y + 0.5f - 1f) / 8f
        return (abs(xr).pow(2.6f) + abs(yr).pow(2.6f)).pow(1f / 2.6f)
    }

    /** Number of voxel layers of the dome at (x, y): 1 at the rim, 5 in the middle. */
    private fun domeDepth(x: Int, y: Int): Int = 1 + (4.4f * (1f - domeQ(x, y).pow(2.2f))).toInt()

    private fun dome(x: Float, y: Float, z: Float): FloatArray = unit(x / 81f, (y - 1f) / 64f, (z + 10f) / 19f)

    /**
     * Plague doctor mask: a domed black leather mask over the face (color 2) with two gold-ringed red lenses
     * (color) and a strap round the head. The beak is a separate mesh so it can bob.
     */
    private fun plagueMask(): VoxelGrid {
        val g = VoxelGrid()
        val gold = g.fixed(0xD9A63A)
        val glass = g.fixed(0xFFE3E3)
        // Strap round the head, 1 voxel outside the hat layer.
        for (x in -10..9) for (z in -10..9) {
            if (x in -9..8 && z in -9..8) continue
            for (y in -4 until -1) g.set(x, y, z, 1, 0.78f + CosmeticModels.jitter(x, y, z, 0.03f))
        }
        for (x in -9..8) for (y in -8..9) {
            val q = domeQ(x, y)
            if (q > 1f) continue
            val depth = domeDepth(x, y)
            val yr = (y + 0.5f - 1f) / 8f
            for (d in 0 until depth) {
                g.set(x, y, -10 - d, 1, 0.82f + 0.2f * (1f - q) - 0.06f * max(0f, yr) + (if (d == depth - 1 && depth > 1) 0.04f else 0f))
            }
        }
        // Lens cups.
        for (x in -9..8) for (y in -8..9) {
            if (domeQ(x, y) > 1f) continue
            val dx = abs(x + 0.5f) - 5f
            val dy = y + 0.5f - 2f
            val r = sqrt(dx * dx + dy * dy)
            if (r > 3.9f) continue
            val front = -10 - (domeDepth(x, y) - 1)
            val light = -0.05f * dx - 0.05f * dy
            if (r >= 2.8f) {
                for (z in front - 2..-10) g.set(x, y, z, gold, 1.0f + light + (if (z == front - 2) 0.1f else 0f))
            } else {
                for (z in front - 1..-10) {
                    val top = z == front - 1
                    val ch = if (top && r < 0.9f) 1 else 0
                    g.set(x, y, z, ch, if (ch == 1) 0.5f else 0.9f + 0.1f * (r / 2.8f) - light)
                }
            }
        }
        // A lens highlight on each side (mirrored).
        for (sx in intArrayOf(1, -1)) {
            val x = if (sx > 0) 4 else -5
            val front = -10 - (domeDepth(x, 1) - 1)
            g.set(x, 1, front - 1, glass, 1.1f)
        }
        return g
    }

    /** The beak: a tube from the nose that sweeps forward and curves down to a point. */
    private fun plagueBeak(): Tube = tube(
        arrayOf(
            floatArrayOf(0f, 2.8f, -6.5f), floatArrayOf(0f, 3.4f, -8.4f), floatArrayOf(0f, 4.8f, -10.1f),
            floatArrayOf(0f, 6.6f, -11.0f), floatArrayOf(0f, 8.4f, -11.4f),
        ),
        1.6f, 0.45f, 1, 0.95f,
    )

    // ---- bandana ----------------------------------------------------------------------------------

    /**
     * Bandana: a cloth band one voxel outside the hat layer, tilted up toward the back, with the front
     * hanging in a V over the mouth (color, black polka dots in color 2) and a small knot behind.
     */
    private fun bandana(): VoxelGrid {
        val g = VoxelGrid()
        val pi = PI.toFloat()
        for (x in -10..9) for (z in -10..9) {
            if (x in -9..8 && z in -9..8) continue
            val a = abs(atan2(x + 0.5f, -(z + 0.5f)))
            val top = 1f - 5f * a / pi
            val bottom = top + 5f + 5f * max(0f, 1f - a / 1.2f).pow(0.8f)
            val y0 = Math.round(top)
            val y1 = Math.round(bottom)
            val arc = Math.round(a * 9.5f) * (if (x >= 0) 1 else -1)
            for (y in y0 until y1) {
                var ch = 0
                var shade = 0.97f + 0.03f * sin(arc * 0.9f)
                if (y == y0) shade -= 0.1f
                if (y == y1 - 1) shade -= 0.08f
                if (a < 1.3f && ((arc % 4 == 0 && y % 4 == 0) || ((arc + 2) % 4 == 0 && (y + 2) % 4 == 0))) {
                    ch = 1; shade = 0.9f
                }
                g.set(x, y, z, ch, shade + CosmeticModels.jitter(x, y, z, 0.015f))
            }
        }
        // The knot behind: a lump and two little loops.
        for (x in -1 until 1) for (y in -3 until 0) for (z in 10 until 12) g.set(x, y, z, 0, if (z == 11) 0.92f else 0.86f)
        for (sx in intArrayOf(-1, 1)) {
            val x0 = if (sx > 0) 1 else -4
            for (x in x0 until x0 + 3) for (y in -3 until -1) g.set(x, y, 10, 0, 0.88f)
        }
        return g
    }

    private fun sin(v: Float): Float = kotlin.math.sin(v)

    /** One loose end of the knot, hanging down from the origin: 1.5 px wide, 6 px long, with a notched tip. */
    private fun bandanaTail(): VoxelGrid {
        val g = VoxelGrid()
        for (x in -1..1) for (y in 0 until 12) {
            if (y >= 10 && x == 0) continue // swallow-tail cut
            g.set(x, y, 0, 0, 0.95f - 0.015f * y + (if (x == -1) 0.04f else 0f) + CosmeticModels.jitter(x, y, 0, 0.02f))
        }
        for (y in 0 until 3) for (x in -1..1) g.set(x, y, 1, 0, 0.8f)
        return g
    }

    // ============================== BACK =========================================================

    /** Backpack body: bag with lid, side pouches, a pocket and the black straps, in the pack frame. */
    private fun backpack(): VoxelGrid {
        val g = VoxelGrid()
        fill(g, -3.9f, 2.4f, 0f, 3.9f, 10.9f, 4.2f) { x, y, z ->
            val lid = y < 4.7f
            val hw = (if (lid) 3.8f else 3.5f) - (if (y > 9.6f) (y - 9.6f) * 1.2f else 0f)
            val depth = if (lid) 4.1f else 3.6f
            val d = minOf(hw - abs(x), z, depth - z, y - 2.5f, 10.7f - y)
            if (d < 0f) return@fill null
            var shade = 0.96f + (if (lid) 0.05f else 0f) - rim(d)
            if (y in 4.6f..5.0f) shade -= 0.12f
            if (y in 6.2f..6.6f && !lid) shade -= 0.07f
            Vx(0, shade)
        }
        // Side pouches.
        fill(g, -4.4f, 6.0f, 0.4f, 4.4f, 10.4f, 3.2f) { x, y, z ->
            val ax = abs(x)
            val d = minOf(4.3f - ax, z - 0.4f, 3.1f - z, y - 6.0f, 10.3f - y)
            if (ax < 3.4f || d < 0f) return@fill null
            Vx(0, 0.84f - rim(d))
        }
        // Front pocket on the back face.
        fill(g, -3.1f, 6.3f, 3.5f, 3.1f, 10.5f, 4.5f) { x, y, z ->
            val hw = 2.9f - (if (y > 9.5f) (y - 9.5f) * 1.5f else 0f)
            val d = minOf(hw - abs(x), 4.4f - z, 10.4f - y)
            if (d < 0f) return@fill null
            Vx(0, 0.9f - rim(d))
        }
        // Straps from the bedroll down the lid and bag, ending under the pocket flap.
        fill(g, -3.1f, 2.2f, 3.4f, 3.1f, 6.5f, 4.8f) { x, y, z ->
            val ax = abs(x)
            if (ax < 1.9f || ax > 2.9f) return@fill null
            val zs = if (y < 4.7f) 4.1f else 3.6f
            if (z < zs - 0.05f || z > zs + 0.5f) return@fill null
            Vx(1, 0.9f)
        }
        return g
    }

    private const val BEDROLL_Y = 1.8f
    private const val BEDROLL_Z = 1.9f
    private const val BEDROLL_R = 1.75f

    /** The rolled mat on top: a khaki cylinder across the pack with two black straps round it. */
    private fun bedroll(): VoxelGrid {
        val g = VoxelGrid()
        val khaki = g.fixed(0x9C8F73)
        val r = BEDROLL_R
        fill(g, -4.4f, BEDROLL_Y - r - 0.4f, BEDROLL_Z - r - 0.4f, 4.4f, BEDROLL_Y + r + 0.4f, BEDROLL_Z + r + 0.4f) { x, y, z ->
            val dy = y - BEDROLL_Y
            val dz = z - BEDROLL_Z
            val rr = sqrt(dy * dy + dz * dz)
            val ax = abs(x)
            val strap = ax in 1.9f..2.9f
            if (rr > (if (strap) r + 0.3f else r) || ax > 4.3f) return@fill null
            if (strap) return@fill Vx(1, 0.9f)
            var shade = 0.97f - 0.08f * (rr / r)
            if (ax > 3.8f) shade = if (((rr * 2f).toInt() and 1) == 0) 1.0f else 0.8f
            Vx(khaki, shade)
        }
        return g
    }

    private fun bedrollNormal(x: Float, y: Float, z: Float): FloatArray {
        val px = x * H
        if (abs(px) > 4.2f) return unit(if (px > 0) 1f else -1f, 0f, 0f)
        return unit(0f, y * H - BEDROLL_Y, z * H - BEDROLL_Z)
    }

    /** The pocket flap, hinged at the top ([FLAP_HINGE_Y]); black straps with gold buckles hold it. */
    private fun backpackFlap(): VoxelGrid {
        val g = VoxelGrid()
        val gold = g.fixed(0xD8AE4A)
        fill(g, -3.2f, 6.2f, 4.35f, 3.2f, 9.4f, 5.9f) { x, y, z ->
            val ax = abs(x)
            val bottom = 8.0f + 0.9f * (1f - ax / 3.1f)
            if (ax > 3.1f || y > bottom || y < FLAP_HINGE_Y) return@fill null
            val strap = ax in 1.9f..2.9f
            val buckle = strap && y in 7.4f..8.4f
            if (buckle && z > 5.3f && z < 5.8f) return@fill Vx(gold, 1.05f)
            if (z > 5.3f) return@fill if (strap) Vx(1, 0.9f) else null
            if (z > 5.0f) return@fill null
            val d = minOf(3.1f - ax, bottom - y, y - FLAP_HINGE_Y, 5.0f - z)
            Vx(0, 1.0f - rim(d, 0.1f))
        }
        return g
    }

    /** The shoulder straps in body coordinates: over the top, down the chest, with a short end at the hip. */
    private fun harness(): VoxelGrid {
        val g = VoxelGrid()
        fill(g, -4.6f, -0.6f, -2.6f, 4.6f, 9.8f, 2.7f) { x, y, z ->
            val ax = abs(x)
            val front = z in -2.5f..-2.0f
            val strapX = ax in 2.0f..3.2f
            val ok = when {
                front -> (strapX && y < 9.6f) || (y in 8.6f..9.6f && ax in 2.0f..4.5f)
                y < 0f -> strapX && z > -2.5f && z < 2.7f
                else -> strapX && y < 2.4f && z in 2.0f..2.7f
            }
            if (!ok) return@fill null
            Vx(1, 0.88f)
        }
        return g
    }

    // ---- katana ---------------------------------------------------------------------------------------

    /**
     * Katana in its sheath, hilt up, along +y from the pommel: red/black wrapped handle, gold collar, guard
     * and fittings, black lacquered scabbard with two red cords and a slight curve. 20.4 px long.
     */
    private fun katana(): VoxelGrid {
        val g = VoxelGrid()
        val gold = g.fixed(0xE0B64A)
        val zc = 1.05f
        // Handle with diamond wrap.
        fill(g, -1.1f, 0f, 0.2f, 1.1f, 5.3f, 1.9f) { x, y, z ->
            if (abs(x) > 1.0f || abs(z - zc) > 0.8f) return@fill null
            if (y < 0.5f) return@fill Vx(gold, 1.0f)
            val i = floor(x / H).toInt()
            val j = floor(y / H).toInt()
            val red = ((i + j) shr 1) and 1 == 0
            Vx(if (red) 0 else 1, if (red) 0.98f else 0.85f)
        }
        // Collar and guard.
        fill(g, -1.2f, 5.3f, 0.1f, 1.2f, 5.7f, 2.0f) { x, _, z ->
            if (abs(x) > 1.05f || abs(z - zc) > 0.85f) null else Vx(gold, 0.95f)
        }
        fill(g, -2.2f, 5.7f, -0.4f, 2.2f, 6.2f, 2.5f) { x, _, z ->
            val e = (x / 2.0f).pow(2) + ((z - zc) / 1.4f).pow(2)
            if (e > 1f) null else Vx(gold, 1.05f - 0.12f * e)
        }
        fill(g, -1.3f, 6.2f, 0.1f, 1.3f, 6.7f, 2.0f) { x, _, z ->
            if (abs(x) > 1.1f || abs(z - zc) > 0.85f) null else Vx(gold, 0.95f)
        }
        // Scabbard.
        fill(g, -1.4f, 6.7f, 0.2f, 2.2f, 20.4f, 1.9f) { x, y, z ->
            val t = (y - 6.7f) / 13.7f
            val off = 0.7f * t * t
            val hw = 1.0f - 0.25f * t
            val hd = 0.8f - 0.2f * t
            val tip = if (y > 19.7f) 1f - (y - 19.7f) / 0.7f else 1f
            val lx = x - off
            if (abs(lx) > hw * tip.coerceAtLeast(0.2f) || abs(z - zc) > hd || tip <= 0f) return@fill null
            val cord = y in 8.2f..8.8f || y in 9.6f..10.2f
            val shade = 0.9f + (if (lx < -hw + 0.5f) 0.12f else 0f) - (if (lx > hw - 0.5f) 0.08f else 0f)
            Vx(if (cord) 0 else 1, if (cord) 1.0f else shade)
        }
        return g
    }

    // ---- jetpack -----------------------------------------------------------------------------------------

    private const val TANK_R = 1.75f
    private const val TANK_Z = 2.3f

    private fun tankRadius(y: Float): Float = when {
        y < 0.6f || y > 9.0f -> -1f
        y < 1.5f -> TANK_R * sqrt(max(0f, 1f - ((1.5f - y) / 0.9f).pow(2)))
        y > 8.1f -> TANK_R - (y - 8.1f) / 0.9f * 0.7f
        else -> TANK_R
    }

    /** Two red tanks with black bands, a black plate between them and steel nozzles. */
    private fun jetpack(): VoxelGrid {
        val g = VoxelGrid()
        val steel = g.fixed(0xA5ABB3)
        val dark = g.fixed(0x70757D)
        fill(g, -3.9f, 0.8f, 0f, 3.9f, 8.8f, 0.9f) { x, y, z ->
            val d = minOf(3.9f - abs(x), y - 0.8f, 8.8f - y, z)
            Vx(1, 0.84f - rim(d, 0.1f))
        }
        fill(g, -4.2f, 0.1f, 0.4f, 4.2f, 10.4f, 4.2f) { x, y, z ->
            val cx = if (x > 0) NOZZLE_X else -NOZZLE_X
            val dx = x - cx
            val dz = z - TANK_Z
            val rr = sqrt(dx * dx + dz * dz)
            // Valve on top and nozzle below.
            if (y < 0.6f) return@fill if (y > 0.2f && rr < 0.55f) Vx(steel, 1.0f) else null
            if (y > 9.0f) return@fill if (rr < 0.95f - (y - 9.0f) * 0.1f) Vx(if (y < 9.5f) steel else dark, 0.95f) else null
            val r = tankRadius(y)
            if (rr > r) return@fill null
            val band = y in 1.5f..2.3f || y in 7.2f..8.0f
            if (band) return@fill Vx(1, 0.8f)
            Vx(0, 0.98f - 0.1f * (rr / TANK_R) - (if (y < 1.5f) 0.04f else 0f))
        }
        return g
    }

    private fun tankNormal(x: Float, y: Float, z: Float): FloatArray {
        val px = x * H
        val cx = if (px > 0) NOZZLE_X else -NOZZLE_X
        val dy = when {
            y * H < 1.5f -> (y * H - 1.5f) * 1.6f
            y * H > 8.1f -> (y * H - 8.1f) * 1.2f
            else -> 0f
        }
        return unit(px - cx, dy, z * H - TANK_Z)
    }

    /** A flame hanging from the origin along +y: [radius] at the base tapering over [length] px, fixed [rgb]. */
    private fun flame(radius: Float, length: Float, rgb: Int, shade: Float): VoxelGrid {
        val g = VoxelGrid()
        val ch = g.fixed(rgb)
        fill(g, -radius - 0.5f, 0f, -radius - 0.5f, radius + 0.5f, length, radius + 0.5f) { x, y, z ->
            val t = y / length
            val r = radius * (1f - t).pow(0.75f) * (0.55f + 0.45f * sqrt(min(1f, t * 6f)))
            if (sqrt(x * x + z * z) > r) null else Vx(ch, shade - 0.15f * t)
        }
        return g
    }

    // ---- turtle shell ---------------------------------------------------------------------------------------

    private const val SHELL_Y = 6.0f
    private const val SHELL_A = 4.3f
    private const val SHELL_B = 4.9f
    private const val SHELL_C = 3.4f

    private fun shellQ(x: Float, y: Float): Float {
        val u = abs(x / SHELL_A)
        val v = abs((y - SHELL_Y) / SHELL_B)
        return (u.pow(2.4f) + v.pow(2.4f)).pow(1f / 2.4f)
    }

    /** Distance (px) to the nearest edge of a hexagonal lattice (flat-top cells of width [s]). */
    private fun hexEdge(px: Float, py: Float, s: Float): Float {
        var d1 = Float.MAX_VALUE
        var d2 = Float.MAX_VALUE
        var c1x = 0f; var c1y = 0f; var c2x = 0f; var c2y = 0f
        for (i in -3..3) for (j in -3..3) {
            val cx = i * s + j * s / 2f
            val cy = j * s * 0.866f
            val d = (px - cx) * (px - cx) + (py - cy) * (py - cy)
            if (d < d1) { d2 = d1; c2x = c1x; c2y = c1y; d1 = d; c1x = cx; c1y = cy }
            else if (d < d2) { d2 = d; c2x = cx; c2y = cy }
        }
        val gap = sqrt((c2x - c1x) * (c2x - c1x) + (c2y - c1y) * (c2y - c1y)).coerceAtLeast(1e-3f)
        return (d2 - d1) / (2f * gap)
    }

    /** A hexagon-plated dome (color, black seams in color 2) on a brown rim, flat against the back. */
    private fun turtleShell(): VoxelGrid {
        val g = VoxelGrid()
        val rim = g.fixed(0x6D4B34)
        fill(g, -SHELL_A - 0.8f, SHELL_Y - SHELL_B - 0.8f, 0f, SHELL_A + 0.8f, SHELL_Y + SHELL_B + 0.8f, SHELL_C + 0.3f) { x, y, z ->
            val q = shellQ(x, y)
            if (q >= 1f) {
                if (q > 1.14f || z > 0.9f) return@fill null
                return@fill Vx(rim, 0.78f + (if (z > 0.45f) 0.1f else 0f) + (if (q < 1.05f) -0.1f else 0f))
            }
            val top = max(0.5f, SHELL_C * sqrt(max(0f, 1f - q * q)))
            if (z > top) return@fill null
            val stretch = 1f + 0.4f * q * q
            val edge = hexEdge(x * stretch, (y - SHELL_Y) * stretch, 3.6f)
            if (edge < 0.3f) return@fill Vx(1, 0.85f)
            Vx(0, 0.93f + 0.12f * min(1f, edge / 1.6f) - 0.16f * q * q)
        }
        return g
    }

    private fun shellNormal(x: Float, y: Float, z: Float): FloatArray =
        unit(x * H / (SHELL_A * SHELL_A), (y * H - SHELL_Y) / (SHELL_B * SHELL_B), z * H / (SHELL_C * SHELL_C) * 1.2f)

    // ---- electric guitar -------------------------------------------------------------------------------------

    private fun bout(x: Float, y: Float): Float {
        // Largest "inside distance" over the circles of the body outline (negative outside).
        val a = 3.5f - sqrt((x - 0.2f).pow(2) + (y - 6.3f).pow(2))
        val b = 2.6f - sqrt(x * x + (y - 2.3f).pow(2))
        val c = 1.35f - sqrt((x + 2.0f).pow(2) + (y - 0.9f).pow(2))
        val d = 1.2f - sqrt((x - 2.1f).pow(2) + (y - 1.4f).pow(2))
        return max(max(a, b), max(c, d))
    }

    /** Offset double cutaway guitar, neck up, strings facing +z: red body, black pickguard and headstock. */
    private fun guitar(): VoxelGrid {
        val g = VoxelGrid()
        val chrome = g.fixed(0xCFCFD6)
        val wood = g.fixed(0xB48A58)
        val string = g.fixed(0xD9D9DE)
        fill(g, -4f, -1f, 0.3f, 4f, 10f, 2.4f) { x, y, z ->
            val d = bout(x, y)
            if (d < 0f) return@fill null
            if (z < 1.8f) return@fill Vx(0, 0.97f - rim(d, 0.08f) + 0.03f * (1f - y / 10f))
            // Pickguard layer on the face.
            val e = ((x - 0.9f) / 1.5f).pow(2) + ((y - 6.2f) / 2.4f).pow(2)
            if (e <= 1f && d > 0.8f) Vx(1, 0.95f) else null
        }
        fill(g, -2f, 4.5f, 2.3f, 2f, 8.8f, 2.8f) { x, y, _ ->
            val pickup = y in 4.6f..5.1f || y in 6.1f..6.6f
            val bridge = y in 8.2f..8.7f
            val knob = y in 7.5f..8.2f && x in 1.7f..2.2f
            if ((pickup || bridge) && abs(x) < 1.6f || knob) Vx(chrome, 1.0f) else null
        }
        // Neck: wood with a black fretboard, frets and a string.
        fill(g, -0.8f, -8.5f, 0.5f, 0.8f, 0.6f, 2.5f) { x, y, z ->
            if (abs(x) > 0.7f) return@fill null
            when {
                z < 1.5f -> Vx(wood, 0.92f - (if (abs(x) > 0.45f) 0.08f else 0f))
                z < 1.9f -> if (abs(x) < 0.65f) Vx(1, 0.9f) else null
                z < 2.2f -> if (y > -8.3f && y < 0.2f && (abs(y + 1f) % 1.5f < 0.25f) && abs(x) < 0.65f) Vx(chrome, 0.95f) else null
                else -> if (abs(x) < 0.25f && y < 0.2f) Vx(string, 0.95f) else null
            }
        }
        // Headstock with tuning pegs.
        fill(g, -1.7f, -11.1f, 0.5f, 1.7f, -8.3f, 1.9f) { x, y, z ->
            val ax = abs(x)
            if (ax <= 1.1f && z < 1.9f) return@fill Vx(1, 0.88f)
            if (ax in 1.1f..1.6f && z in 1.0f..1.5f && (y in -10.6f..-10.1f || y in -9.7f..-9.2f || y in -8.8f..-8.3f)) Vx(chrome, 0.95f) else null
        }
        return g
    }

    /** The guitar strap in body coordinates: back to the right shoulder, diagonal over the chest to the left hip. */
    private fun guitarStrap(): VoxelGrid {
        val g = VoxelGrid()
        fill(g, -4.6f, -0.6f, -2.6f, 5.6f, 12.6f, 2.7f) { x, y, z ->
            val front = z in -2.5f..-2.0f
            val back = z in 2.0f..2.5f
            val hw = 0.65f
            val ok = when {
                front -> distanceToSegment(x, y, -3.0f, -0.3f, 3.9f, 10.8f) <= hw
                y < 0f -> x in -3.6f..-2.4f && z > -2.5f && z < 2.5f
                back -> distanceToSegment(x, y, -3.0f, 0f, GUITAR_PIN_X, GUITAR_PIN_Y) <= hw ||
                    distanceToSegment(x, y, 5.05f, 12.4f, 4.0f, 11.0f) <= hw
                else -> false
            }
            if (ok) Vx(1, 0.86f) else null
        }
        return g
    }

    // ---- tube (beak) -------------------------------------------------------------------------------------------

    /** A tapered tube along a curve with its samples, so faces light as a smooth round surface. */
    class Tube(val grid: VoxelGrid, val samples: List<FloatArray>) {
        fun mesh(): VoxelMesh = grid.mesh(H) { x, y, z ->
            var best = samples[0]
            var bestD = Float.MAX_VALUE
            for (p in samples) {
                val d = (x * H - p[0]).pow(2) + (y * H - p[1]).pow(2) + (z * H - p[2]).pow(2)
                if (d < bestD) { bestD = d; best = p }
            }
            unit(x * H - best[0], y * H - best[1], z * H - best[2])
        }
    }

    private fun tube(points: Array<FloatArray>, r0: Float, r1: Float, ch: Int, shade: Float): Tube {
        val samples = ArrayList<FloatArray>()
        for (i in 0 until points.size - 1) {
            for (s in 0 until 10) samples.add(catmull(points, i, s / 10f))
        }
        samples.add(points.last())
        val best = HashMap<Long, FloatArray>()
        for ((index, p) in samples.withIndex()) {
            val t = index / (samples.size - 1f)
            val r = r0 + (r1 - r0) * t
            val ri = r / H
            val cx = p[0] / H; val cy = p[1] / H; val cz = p[2] / H
            for (x in floor(cx - ri).toInt()..ceil(cx + ri).toInt()) {
                for (y in floor(cy - ri).toInt()..ceil(cy + ri).toInt()) {
                    for (z in floor(cz - ri).toInt()..ceil(cz + ri).toInt()) {
                        val d = sqrt((x + 0.5f - cx).pow(2) + (y + 0.5f - cy).pow(2) + (z + 0.5f - cz).pow(2)) / ri
                        if (d > 1f) continue
                        val key = ((x + 4096).toLong() shl 32) or ((y + 4096).toLong() shl 16) or (z + 4096).toLong()
                        val old = best[key]
                        if (old == null || d < old[0]) best[key] = floatArrayOf(d, index.toFloat(), y + 0.5f - cy, ri)
                    }
                }
            }
        }
        val g = VoxelGrid()
        for ((key, v) in best) {
            val x = (key shr 32).toInt() - 4096
            val y = ((key shr 16) and 0xFFFF).toInt() - 4096
            val z = (key and 0xFFFF).toInt() - 4096
            val t = v[1] / (samples.size - 1f)
            val under = (v[2] / v[3]).coerceIn(-1f, 1f) // below the curve (y grows down) is darker
            g.set(x, y, z, ch, shade + 0.1f - 0.18f * t - 0.12f * max(0f, under))
        }
        return Tube(g, samples)
    }

    private fun catmull(points: Array<FloatArray>, segment: Int, t: Float): FloatArray {
        val p0 = points[max(0, segment - 1)]
        val p1 = points[segment]
        val p2 = points[segment + 1]
        val p3 = points[min(points.size - 1, segment + 2)]
        val t2 = t * t
        val t3 = t2 * t
        return FloatArray(3) { i ->
            0.5f * (2f * p1[i] + (-p0[i] + p2[i]) * t + (2f * p0[i] - 5f * p1[i] + 4f * p2[i] - p3[i]) * t2 +
                (-p0[i] + 3f * p1[i] - 3f * p2[i] + p3[i]) * t3)
        }
    }
}
