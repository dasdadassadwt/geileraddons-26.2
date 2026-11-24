package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a skirt moves, per style: where the cloth starts to follow the legs and how strongly it reacts. */
class SkirtLook(
    /** Height (pixels) where the cloth starts to follow the legs; above it the skirt is rigid on the body. */
    @JvmField val yStart: Float,
    /** Share of a leg's swing the hem follows (about the hip). */
    @JvmField val follow: Float,
    /** Half width (pixels) over which the left and right leg blend; small for a slit skirt. */
    @JvmField val blend: Float,
    /** Hem lift per unit of leg separation (pixels). */
    @JvmField val bounce: Float,
    /** Hem flare per unit of leg separation (fraction of the radius). */
    @JvmField val flare: Float,
    /** Travelling ripple while walking (fraction of the radius). */
    @JvmField val ripple: Float,
    /** Sideways flow while walking (pixels at the hem). */
    @JvmField val flow: Float,
    /** Idle sway of the hem (pixels). */
    @JvmField val sway: Float,
)

/**
 * A baked skirt in rest pose: one voxel shell (half-pixel voxels) around the waist and legs, plus what the rig
 * needs to bend it. [rowA] and [rowB] are the outer half width and depth (pixels) per half-pixel row, from row
 * [SkirtGeometry.ROW0], so the waistband can follow a body shape.
 */
class SkirtModel(
    @JvmField val mesh: VoxelMesh,
    @JvmField val rowA: FloatArray,
    @JvmField val rowB: FloatArray,
    @JvmField val yHem: Float,
    @JvmField val look: SkirtLook,
)

/**
 * The skirt voxel art. Every skirt is a hollow shell around a rounded-rectangle outline that flares from the
 * waist to the hem; a style supplies the outline per height and angle, an offset for pleats and ruffles, a
 * hem line and a paint function (channel 0 = Color, channel 1 = Second Color, fixed colors for lace and
 * metal). Built once per style and cached. Local frame: the player's body (pixels, y down, z back).
 */
object SkirtGeometry {
    /** First row (half pixels) of the per-row tables, i.e. y = 6. */
    const val ROW0 = 12
    const val ROWS = 56

    /** Waistband outer half width and depth: outside the skin (4 x 2), the jacket (4.25) and leggings (4.5). */
    const val BAND_A = 4.8f
    const val BAND_B = 2.8f
    const val BAND_TOP = 8f

    private const val V = 0.5f
    private const val PI_F = PI.toFloat()
    private const val TAU = 2f * PI_F

    private val cache = HashMap<CosmeticStyle, SkirtModel>()

    @JvmStatic
    fun model(style: CosmeticStyle): SkirtModel = synchronized(cache) { cache.getOrPut(style) { build(style) } }

    private fun build(style: CosmeticStyle): SkirtModel = when (style) {
        CosmeticStyle.RUFFLED_SKIRT -> ruffled()
        CosmeticStyle.LONG_SKIRT -> long()
        CosmeticStyle.TUTU -> tutu()
        CosmeticStyle.KILT -> kilt()
        CosmeticStyle.MAID_SKIRT -> maid()
        else -> pleated()
    }

    // ---- shell builder ------------------------------------------------------------------------------

    /** What a paint function reads and writes for one voxel. */
    private class Cell {
        var x = 0f
        var y = 0f
        var z = 0f
        var phi = 0f
        var depth = 0f
        var channel = 0
        var shade = 1f
        var skip = false

        fun put(channel: Int, shade: Float) {
            this.channel = channel
            this.shade = (shade * 20f).roundToInt() / 20f
        }
    }

    private class Shell(
        val yTop: Float,
        val yBottom: Float,
        val thick: Float,
        val a: (Float, Float) -> Float,
        val b: (Float, Float) -> Float,
        val n: (Float) -> Float,
        val paint: (Cell) -> Unit,
        val offset: (Float, Float) -> Float = { _, _ -> 0f },
        val hem: (Float) -> Float = { 0f },
        val protrude: Float = 0f,
        val reach: Float = 10f,
        val carve: (Float, Float, Float) -> Boolean = { _, _, _ -> false },
    ) {
        /** Distance (pixels) inside the outer surface at a point; negative outside. Writes the angle into [phi]. */
        fun depth(x: Float, y: Float, z: Float, phi: FloatArray?): Float {
            val a0 = a(y, 0f)
            val b0 = b(y, 0f)
            val angle = atan2(z / b0, x / a0)
            val ea = a(y, angle)
            val eb = b(y, angle)
            val exponent = n(y)
            val r = (abs(x / ea).pow(exponent) + abs(z / eb).pow(exponent)).pow(1f / exponent)
            val rho = sqrt(x * x + z * z)
            if (phi != null) phi[0] = angle
            if (r < 1e-5f) return 99f
            return rho / r + offset(y, angle) - rho
        }
    }

    private fun fill(grid: VoxelGrid, shell: Shell) {
        val cell = Cell()
        val phi = FloatArray(1)
        val span = ceil(shell.reach * 2f).toInt()
        val maxHem = 0.8f
        val from = floor(shell.yTop * 2f).toInt()
        val to = ceil((shell.yBottom + maxHem) * 2f).toInt()
        for (iy in from until to) {
            val y = (iy + 0.5f) * V
            if (y < shell.yTop) continue
            for (ix in -span until span) {
                val x = (ix + 0.5f) * V
                for (iz in -span until span) {
                    val z = (iz + 0.5f) * V
                    val depth = shell.depth(x, y, z, phi)
                    if (depth < -shell.protrude || depth >= shell.thick) continue
                    if (y > shell.yBottom + shell.hem(phi[0])) continue
                    if (shell.carve(x, y, z)) continue
                    cell.x = x; cell.y = y; cell.z = z; cell.phi = phi[0]; cell.depth = depth
                    cell.skip = false
                    shell.paint(cell)
                    if (cell.skip) continue
                    grid.set(ix, iy, iz, cell.channel, cell.shade)
                }
            }
        }
    }

    /** Bakes [grid], lights the outside as a smooth curve and drops the faces that look at the body. */
    private fun bake(grid: VoxelGrid, shell: Shell, rim: (Float, Float, Float) -> Boolean = { _, _, _ -> false }): VoxelMesh {
        val mesh = grid.mesh(V) { cx, cy, cz ->
            val x = cx * V
            val y = cy * V
            val z = cz * V
            val e = 0.25f
            var nx = shell.depth(x - e, y, z, null) - shell.depth(x + e, y, z, null)
            var ny = shell.depth(x, y - e, z, null) - shell.depth(x, y + e, z, null)
            var nz = shell.depth(x, y, z - e, null) - shell.depth(x, y, z + e, null)
            if (shell.depth(x, y, z, null) > shell.thick * 0.5f) { nx = -nx; ny = -ny; nz = -nz }
            val length = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-4f } ?: 1f
            floatArrayOf(nx / length, ny / length, nz / length)
        }
        return withoutInnerWalls(mesh, rim)
    }

    /**
     * The inside of the shell is only ever seen through the hem; the outer wall seen from inside (faces are not
     * culled) already covers that, so vertical faces that look toward the body's axis are dropped to halve the
     * face count. Faces on the rims (hem, top, slit edges) stay.
     */
    private fun withoutInnerWalls(mesh: VoxelMesh, rim: (Float, Float, Float) -> Boolean): VoxelMesh {
        val keep = ArrayList<Int>(mesh.faceCount())
        for (face in 0 until mesh.faceCount()) {
            val direction = mesh.directions[face].toInt()
            val axis = direction / 2
            if (axis == 1) { keep.add(face); continue }
            val sign = if (direction % 2 == 1) 1f else -1f
            val base = face * 12
            val cx = (mesh.positions[base] + mesh.positions[base + 6]) / 2f
            val cz = (mesh.positions[base + 2] + mesh.positions[base + 8]) / 2f
            val inward = if (axis == 0) cx * sign else cz * sign
            // A face on the plane x = 0 / z = 0 has no radial side; keep it.
            if (inward >= -0.01f || rim(cx, (mesh.positions[base + 1] + mesh.positions[base + 7]) / 2f, cz)) keep.add(face)
        }
        if (keep.size == mesh.faceCount()) return mesh
        val positions = FloatArray(keep.size * 12)
        val directions = ByteArray(keep.size)
        val channels = ByteArray(keep.size)
        val shades = FloatArray(keep.size)
        val normals = mesh.smoothNormals?.let { FloatArray(keep.size * 3) }
        for ((i, face) in keep.withIndex()) {
            System.arraycopy(mesh.positions, face * 12, positions, i * 12, 12)
            directions[i] = mesh.directions[face]
            channels[i] = mesh.channels[face]
            shades[i] = mesh.shades[face]
            if (normals != null) System.arraycopy(mesh.smoothNormals!!, face * 3, normals, i * 3, 3)
        }
        return VoxelMesh(positions, directions, channels, shades, normals, mesh.palette)
    }

    private fun finish(
        grid: VoxelGrid, main: Shell, yHem: Float, look: SkirtLook, rim: (Float, Float, Float) -> Boolean = { _, _, _ -> false },
    ): SkirtModel {
        val mesh = bake(grid, main, rim)
        val rowA = FloatArray(ROWS)
        val rowB = FloatArray(ROWS)
        for (i in 0 until ROWS) {
            val y = ((ROW0 + i) * 0.5f).coerceIn(main.yTop, main.yBottom)
            rowA[i] = main.a(y, 0f)
            rowB[i] = main.b(y, 0f)
        }
        return SkirtModel(mesh, rowA, rowB, yHem, look)
    }

    // ---- small helpers ----------------------------------------------------------------------------------

    /** 0 at a pleat's valley, 1 at its ridge, for a phase that is a whole number at every valley. */
    private fun tri(p: Float): Float = abs(p - Math.round(p)) * 2f

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Position around the skirt as a phase: whole numbers at the front center and every 1/[count] turn from it. */
    private fun around(phi: Float, count: Float): Float = (phi + PI_F / 2f) / TAU * count

    private fun noise(a: Int, b: Int): Float {
        var h = a * 73856093 xor b * 19349663
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0xFFFF) / 65535f
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    // ---- pleated skirt --------------------------------------------------------------------------------------

    /** Plaid (tartan) look shared by the pleated skirt and the kilt: returns the channel and shade of a spot. */
    private fun plaid(cell: Cell, phi: Float, t: Float, bars: Float, rows: Float, fold: Float, kilt: Boolean) {
        val pb = around(phi, bars)
        val nearBar = abs(pb - Math.round(pb))
        val ty = t * rows
        val nearRow = abs(ty - Math.round(ty))
        val fabric = (0.80f + 0.38f * fold) * (0.9f + 0.12f * t)
        if (kilt) {
            val u = nearBar < 0.25f
            val v = nearRow < 0.25f
            when {
                u && v -> cell.put(1, 0.78f * fabric)
                u || v -> cell.put(0, 0.5f * fabric)
                else -> cell.put(0, 1.0f * fabric)
            }
            return
        }
        val barV = nearBar < 0.13f
        val barH = abs(t - 0.36f) < 0.055f || abs(t - 0.80f) < 0.05f
        val thin = abs(pb - floor(pb) - 0.5f) < 0.05f || abs(t - 0.58f) < 0.03f
        when {
            barV && barH -> cell.put(1, 0.72f * fabric)
            barV || barH -> cell.put(1, 0.95f * fabric)
            thin -> cell.put(0, 0.6f * fabric)
            else -> cell.put(0, 1.0f * fabric)
        }
    }

    private fun pleated(): SkirtModel {
        val yBand = 9.5f
        val yHem = 15.5f
        fun s(y: Float) = ((y - yBand) / (yHem - yBand)).coerceIn(0f, 1f)
        val shell = Shell(
            BAND_TOP, yHem, 1.0f,
            a = { y, _ -> if (y < yBand) BAND_A else 4.95f + 3.0f * s(y).pow(0.9f) },
            b = { y, _ -> if (y < yBand) BAND_B else 2.95f + 2.6f * s(y).pow(0.9f) },
            n = { y -> 3.4f - 1.0f * s(y) },
            offset = { y, phi -> if (y < yBand) 0f else (0.25f + 0.75f * s(y)) * (tri(around(phi, 16f)) - 0.5f) },
            hem = { phi -> 0.3f * (tri(around(phi, 16f)) - 0.5f) },
            reach = 8.5f,
            paint = { c ->
                val fold = if (c.y < yBand) 0.7f else tri(around(c.phi, 16f))
                plaid(c, c.phi, (c.y - BAND_TOP) / (yHem - BAND_TOP), 8f, 0f, fold, false)
            },
        )
        val grid = VoxelGrid()
        fill(grid, shell)
        return finish(grid, shell, yHem, SkirtLook(9.5f, 0.62f, 3.5f, 0.45f, 0.05f, 0.020f, 0f, 0.35f))
    }

    // ---- ruffled tiered skirt ---------------------------------------------------------------------------------

    private fun ruffled(): SkirtModel {
        val tops = floatArrayOf(9.5f, 12.0f, 14.5f, 17.1f)
        val hemA = floatArrayOf(6.1f, 7.3f, 8.6f)
        val hemB = floatArrayOf(4.0f, 4.85f, 5.8f)
        val topA = floatArrayOf(5.0f, hemA[0] - 1.2f, hemA[1] - 1.2f)
        val topB = floatArrayOf(3.0f, hemB[0] - 1.0f, hemB[1] - 1.0f)
        fun scallop(phi: Float) = 0.3f * (tri(around(phi, 18f)) - 0.5f)
        fun tier(y: Float, phi: Float): Int {
            val sc = scallop(phi)
            return if (y < tops[1] + sc) 0 else if (y < tops[2] + sc) 1 else 2
        }
        fun u(y: Float, phi: Float, k: Int): Float = ((y - tops[k] - scallop(phi)) / (tops[k + 1] - tops[k])).coerceIn(0f, 1f)
        val yHem = tops[3]
        val shell = Shell(
            BAND_TOP, yHem, 1.0f,
            a = { y, phi ->
                if (y < tops[0]) BAND_A else { val k = tier(y, phi); lerp(topA[k], hemA[k], u(y, phi, k).pow(0.75f)) }
            },
            b = { y, phi ->
                if (y < tops[0]) BAND_B else { val k = tier(y, phi); lerp(topB[k], hemB[k], u(y, phi, k).pow(0.75f)) }
            },
            n = { y -> 3.2f - 0.9f * ((y - tops[0]) / (tops[3] - tops[0])).coerceIn(0f, 1f) },
            offset = { y, phi ->
                if (y < tops[0]) 0f else (0.15f + 0.55f * u(y, phi, tier(y, phi))) * (tri(around(phi, 18f)) - 0.5f)
            },
            hem = { phi -> scallop(phi) },
            reach = 9.5f,
            paint = { c ->
                if (c.y < tops[0]) {
                    c.put(0, 0.92f)
                } else {
                    val k = tier(c.y, c.phi)
                    val bottom = tops[k + 1] + scallop(c.phi)
                    val fold = tri(around(c.phi, 18f))
                    if (c.y > bottom - 0.6f) c.put(1, (0.78f + 0.3f * fold) * 0.95f)
                    else c.put(0, (0.80f + 0.38f * fold) * (0.9f + 0.14f * u(c.y, c.phi, k)))
                }
            },
        )
        val grid = VoxelGrid()
        fill(grid, shell)
        return finish(grid, shell, yHem, SkirtLook(9.5f, 0.60f, 3.5f, 0.6f, 0.06f, 0.035f, 0f, 0.4f))
    }

    // ---- long flowing skirt (front slit) ----------------------------------------------------------------------------

    private fun long(): SkirtModel {
        val yBand = 9.8f
        val yHem = 22.6f
        val slitTop = 13.5f
        fun s(y: Float) = ((y - yBand) / (yHem - yBand)).coerceIn(0f, 1f)
        fun slit(y: Float) = 1.3f * ((y - slitTop) / (yHem - slitTop)).coerceIn(0f, 1f).pow(0.9f)
        val shell = Shell(
            BAND_TOP, yHem, 1.0f,
            a = { y, _ -> if (y < yBand) BAND_A else 4.95f + 3.4f * s(y).pow(1.2f) },
            b = { y, _ -> if (y < yBand) BAND_B else 2.95f + 2.6f * s(y).pow(1.1f) },
            n = { y -> 3.3f - 0.8f * s(y) },
            offset = { y, phi -> if (y < yBand) 0f else (0.1f + 0.6f * s(y)) * (tri(around(phi, 14f)) - 0.5f) },
            hem = { phi -> 0.4f * (tri(around(phi, 14f)) - 0.5f) },
            reach = 10.5f,
            carve = { x, y, z -> z < -1.2f && y > slitTop && abs(x) < slit(y) },
            paint = { c ->
                val fold = tri(around(c.phi, 14f))
                val ragged = 20.6f + 0.5f * (tri(around(c.phi, 28f)) - 0.5f)
                val edge = abs(c.x) < slit(c.y) + 0.55f && c.z < -1.2f && c.y > slitTop
                val tone = (0.84f + 0.2f * fold) * (0.92f + 0.1f * s(c.y)) +
                    CosmeticModels.jitter((c.x * 2f).toInt(), (c.y * 2f).toInt(), (c.z * 2f).toInt(), 0.04f)
                when {
                    c.y < yBand -> c.put(0, 0.85f)
                    c.y > ragged -> c.put(1, tone * (if (edge) 0.8f else 1f))
                    else -> c.put(0, tone * (if (edge) 0.82f else 1f))
                }
            },
        )
        val grid = VoxelGrid()
        fill(grid, shell)
        return finish(grid, shell, yHem, SkirtLook(9.8f, 0.70f, 0.8f, 0.25f, 0.03f, 0.015f, 0.9f, 0.7f)) { x, y, z ->
            abs(x) < 2.2f && z < -1.2f && y > slitTop - 0.5f
        }
    }

    // ---- tutu --------------------------------------------------------------------------------------------------------

    private fun tutu(): SkirtModel {
        val tops = floatArrayOf(9.9f, 11.3f, 12.6f, 13.9f)
        val hemA = floatArrayOf(6.5f, 7.8f, 9.0f)
        val topA = floatArrayOf(5.3f, hemA[0] - 1.3f, hemA[1] - 1.3f)
        fun tier(y: Float): Int = if (y < tops[1]) 0 else if (y < tops[2]) 1 else 2
        fun u(y: Float, k: Int) = ((y - tops[k]) / (tops[k + 1] - tops[k])).coerceIn(0f, 1f)
        fun ra(y: Float): Float = if (y < tops[0]) BAND_A else { val k = tier(y); lerp(topA[k], hemA[k], u(y, k).pow(0.6f)) }
        val band = Shell(
            BAND_TOP, 10.3f, 1.4f,
            a = { _, _ -> BAND_A }, b = { _, _ -> BAND_B }, n = { _ -> 3.4f }, reach = 6f,
            paint = { c -> c.put(1, 0.9f - 0.1f * ((c.y - BAND_TOP) / 2.8f)) },
        )
        val tulle = Shell(
            10.0f, tops[3], 1.5f,
            a = { y, _ -> ra(y) }, b = { y, _ -> ra(y) * 0.86f }, n = { _ -> 2.6f },
            offset = { y, phi ->
                (noise(Math.round(phi * 38f), Math.round(y * 3f)) - 0.5f) * 0.6f +
                    (0.1f + 0.4f * u(y, tier(y))) * (tri(around(phi, 26f)) - 0.5f)
            },
            hem = { phi -> 0.35f * (noise(Math.round(phi * 30f), 7) - 0.5f) },
            reach = 10.5f,
            paint = { c ->
                val k = tier(c.y)
                val grain = noise(Math.round(c.x * 2f) * 31 + Math.round(c.y * 2f), Math.round(c.z * 2f))
                c.put(0, 0.7f + 0.28f * grain + 0.3f * u(c.y, k) - (if (c.depth > 1.0f) 0.1f else 0f))
            },
        )
        val grid = VoxelGrid()
        fill(grid, tulle)
        fill(grid, band)
        val model = finish(grid, tulle, tops[3], SkirtLook(10.0f, 0.40f, 3.5f, 0.55f, 0.08f, 0.05f, 0f, 0.25f))
        return model
    }

    // ---- kilt --------------------------------------------------------------------------------------------------------

    /** Where the chain of the kilt's pouch hangs on the waistband (angle around the skirt, front = -PI / 2). */
    const val POUCH_X = -1.7f

    private fun kilt(): SkirtModel {
        val yBand = 9.5f
        val yHem = 16.0f
        fun s(y: Float) = ((y - yBand) / (yHem - yBand)).coerceIn(0f, 1f)
        val grid = VoxelGrid()
        val gold = grid.fixed(GOLD_RGB)
        val pouchPhi = atan2(-1f, POUCH_X / BAND_A)
        // The chain: two arcs from the pouch top up to the hips, 1 voxel proud of the band.
        fun chain(phi: Float, y: Float): Boolean {
            val d = abs(phi - pouchPhi)
            if (d < 0.5f || d > 1.45f) return false
            val t = (d - 0.5f) / 0.95f
            return abs(y - (9.05f - 0.85f * t)) < 0.28f
        }
        val shell = Shell(
            BAND_TOP, yHem, 1.0f,
            a = { y, _ -> if (y < yBand) BAND_A else 4.95f + 1.9f * s(y).pow(0.9f) },
            b = { y, _ -> if (y < yBand) BAND_B else 2.95f + 1.6f * s(y).pow(0.9f) },
            n = { y -> 3.4f - 0.5f * s(y) },
            offset = { y, phi -> if (y < yBand) 0f else (0.2f + 0.3f * s(y)) * (tri(around(phi, 12f)) - 0.5f) },
            hem = { phi -> 0.2f * (tri(around(phi, 12f)) - 0.5f) },
            protrude = 0.5f,
            reach = 7.5f,
            paint = { c ->
                if (c.depth < 0f) {
                    if (c.y < yBand && chain(c.phi, c.y)) c.put(gold, 1.05f) else c.skip = true
                } else if (c.y < yBand) {
                    c.put(1, 0.88f)
                } else {
                    plaid(c, c.phi, (c.y - yBand) / (yHem - yBand), 6f, 2.6f, tri(around(c.phi, 12f)), true)
                }
            },
        )
        fill(grid, shell)
        return finish(grid, shell, yHem, SkirtLook(9.5f, 0.62f, 3.5f, 0.3f, 0.03f, 0.015f, 0f, 0.3f))
    }

    /** Metal accents (kilt clasp and chain) keep this color whatever the player picks. */
    const val GOLD_RGB = 0xE0B13A

    // ---- maid skirt --------------------------------------------------------------------------------------------------------

    private fun maid(): SkirtModel {
        val yBand = 9.5f
        val yHem = 15.2f
        fun s(y: Float) = ((y - yBand) / (yHem - yBand)).coerceIn(0f, 1f)
        fun bell(y: Float) = 1f - (1f - s(y)).pow(1.7f)
        fun apronHalf(y: Float): Float = 3.0f + 0.5f * s(y)
        val apronBottom = 14.1f
        fun apron(x: Float, y: Float, z: Float, grow: Float): Boolean {
            if (z > 0f || y < yBand - 0.2f || y > apronBottom + grow) return false
            var half = apronHalf(y) + grow
            val corner = 1.5f
            if (y > apronBottom - corner) {
                val dy = y - (apronBottom - corner)
                half = half - corner + sqrt(max(0f, (corner + grow) * (corner + grow) - dy * dy))
            }
            return abs(x) <= half
        }
        val grid = VoxelGrid()
        val white = grid.fixed(0xF4F1EC)
        val shell = Shell(
            BAND_TOP, yHem, 1.0f,
            a = { y, _ -> if (y < yBand) BAND_A else 4.95f + 3.0f * bell(y) },
            b = { y, _ -> if (y < yBand) BAND_B else 2.95f + 2.7f * bell(y) },
            n = { y -> 3.2f - 0.7f * s(y) },
            offset = { y, phi ->
                val front = abs(phi + PI_F / 2f) < 0.7f
                if (y < yBand || front) 0f
                else (0.2f + 0.5f * s(y)) * (tri(around(phi, 14f)) - 0.5f) + (noise(Math.round(phi * 24f), Math.round(y * 2f)) - 0.5f) * 0.3f
            },
            hem = { phi -> 0.25f * (tri(around(phi, 14f)) - 0.5f) },
            protrude = 0.5f,
            reach = 8.5f,
            paint = { c ->
                val onApron = apron(c.x, c.y, c.z, 0f)
                val rim = onApron && !apron(c.x, c.y, c.z, -0.5f)
                val grain = noise(Math.round(c.x * 2f) * 31 + Math.round(c.y * 2f), Math.round(c.z * 2f))
                when {
                    c.depth < 0f -> if (onApron) c.put(0, if (rim) 1.12f else 0.98f) else c.skip = true
                    c.y < yBand -> c.put(0, 0.92f)
                    onApron -> c.put(0, if (rim) 1.12f else 0.96f - 0.05f * grain)
                    else -> c.put(1, 0.75f + 0.3f * tri(around(c.phi, 14f)) + 0.1f * grain)
                }
            },
        )
        val lace = Shell(
            14.8f, 16.1f, 0.6f,
            a = { _, phi -> 7.95f + 0.55f + 0.45f * (tri(around(phi, 24f)) - 0.5f) },
            b = { _, phi -> 5.65f + 0.55f + 0.45f * (tri(around(phi, 24f)) - 0.5f) },
            n = { _ -> 2.5f },
            hem = { phi -> 0.4f * (tri(around(phi, 24f)) - 0.5f) },
            reach = 9.5f,
            paint = { c ->
                // Two frills: the upper one a little darker, the scalloped lower one bright.
                c.put(white, if (c.y < 15.3f) 0.86f else 1.02f - 0.1f * tri(around(c.phi, 24f)))
            },
        )
        fill(grid, shell)
        fill(grid, lace)
        val model = finish(grid, shell, yHem, SkirtLook(9.5f, 0.62f, 3.5f, 0.5f, 0.05f, 0.03f, 0f, 0.35f))
        return model
    }
}
