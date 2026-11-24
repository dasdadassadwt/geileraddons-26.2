package geiler.addons.client.cosmetics

import geiler.addons.client.cosmetics.VoxelKit.capsule
import geiler.addons.client.cosmetics.VoxelKit.hash01
import geiler.addons.client.cosmetics.VoxelKit.hypot
import geiler.addons.client.cosmetics.VoxelKit.inside
import geiler.addons.client.cosmetics.VoxelKit.edgeDistance
import geiler.addons.client.cosmetics.VoxelKit.lerp
import geiler.addons.client.cosmetics.VoxelKit.p
import geiler.addons.client.cosmetics.VoxelKit.q
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Dragon, fox and devil tails and the unicorn horn. Half-pixel voxels, built once. A tail is a list of rigid
 * meshes the rig chains together; each runs from y = 0 up to y = -[SEGMENT] (half pixels) with the back of the
 * animal (dorsal side) toward -z.
 */
object CosmeticModelsATails {
    private const val FINE = 0.5f

    /** Length of one dragon or devil segment in half pixels, and the chain lengths. */
    const val DRAGON_SEGMENT = 4
    const val DRAGON_SEGMENTS = 8
    const val DEVIL_SEGMENT = 3
    const val DEVIL_SEGMENTS = 11
    const val FOX_PUFFS = 6

    /** Distance between fox puff centers in pixels. */
    const val FOX_SPACING = 2.8f

    /** Dragon tail: [DRAGON_SEGMENTS] scaled segments with spikes, then the spear tip as the last entry. */
    val DRAGON_TAIL: List<VoxelMesh> by lazy { List(DRAGON_SEGMENTS + 1) { dragonSegment(it) } }

    /** Devil tail: [DEVIL_SEGMENTS] whip links, then the spade. */
    val DEVIL_TAIL: List<VoxelMesh> by lazy { List(DEVIL_SEGMENTS + 1) { devilLink(it) } }

    /** Fox tail: [FOX_PUFFS] overlapping puffs, each centered on its own origin. */
    val FOX_TAIL: List<VoxelMesh> by lazy { List(FOX_PUFFS) { foxPuff(it) } }

    // ---- dragon tail -------------------------------------------------------------------------------------

    /** Half sizes (x, z) in half pixels of dragon segment [index]; the tail narrows toward the tip. */
    private fun dragonHalf(index: Int): FloatArray {
        val f = (index / (DRAGON_SEGMENTS - 1f)).coerceIn(0f, 1f).pow(0.85f)
        return floatArrayOf(lerp(5.5f, 1.6f, f), lerp(4.6f, 1.6f, f))
    }

    private fun dragonSegment(index: Int): VoxelMesh {
        val grid = VoxelGrid()
        if (index == DRAGON_SEGMENTS) return dragonSpear(grid)
        val half = dragonHalf(index)
        val a = half[0]
        val b = half[1]
        for (y in -DRAGON_SEGMENT until 0) {
            val ring = y == -DRAGON_SEGMENT || y == -1
            val k = if (ring) 0.9f else 1f
            for (x in floor(-a - 1).toInt()..a.toInt() + 1) for (z in floor(-b - 1).toInt()..b.toInt() + 1) {
                val cx = x + 0.5f
                val cz = z + 0.5f
                if ((abs(cx) / (a * k)).pow(2.6f) + (abs(cz) / (b * k)).pow(2.6f) > 1f) continue
                val belly = cz / b > 0.25f
                val jitter = CosmeticModels.jitter(x, y + index * 4, z, 0.02f)
                if (belly) {
                    // plated underside: darker rings at the segment seams
                    grid.set(x, y, z, 1, q((if (ring) 0.78f else 0.98f) + jitter))
                } else {
                    // roof-tile scales, offset every other row, lit from the top
                    val row = Math.floorDiv(y + index * 4, 2)
                    val scale = if (((x + row) and 1) == 0) 1f else 0.88f
                    grid.set(x, y, z, 0, q(scale * (0.92f + 0.14f * (-cz / b)) - (if (ring) 0.08f else 0f) + jitter))
                }
            }
        }
        // dorsal spike: a wedge leaning toward the tip
        val height = lerp(5f, 2f, index / (DRAGON_SEGMENTS - 1f)).roundToInt()
        for (x in -1..0) {
            for (y in -DRAGON_SEGMENT until 0) {
                var top = 99
                for (z in -14..14) if (grid.has(x, y, z)) { top = z; break }
                if (top == 99) continue
                for (k in 0 until height) {
                    if (y >= -k) break
                    if (k == height - 1 && x == -1) break
                    grid.set(x, y, top - 1 - k, 1, q(1.08f - 0.07f * k + CosmeticModels.jitter(x, y, k, 0.02f)))
                }
            }
        }
        return grid.mesh(FINE) { x, _, z -> VoxelKit.radialNormal(x, z, 0f, 0f, a, b) }
    }

    /** The tail end: a leaf-shaped spearhead with a black spine. */
    private fun dragonSpear(grid: VoxelGrid): VoxelMesh {
        val length = 16
        for (i in 0 until length) {
            val t = i / length.toFloat()
            val halfX = if (i < 9) lerp(1.8f, 5.4f, kotlin.math.sin(i / 9f * (PI.toFloat() / 2f))) else lerp(5.4f, 0.3f, (i - 9) / 7f)
            val halfZ = lerp(1.7f, 0.9f, t)
            for (x in -7..6) for (z in -3..2) {
                val cx = x + 0.5f
                val cz = z + 0.5f
                if (abs(cx) > halfX || abs(cz) > halfZ + 0.5f * (1f - abs(cx) / halfX)) continue
                val spine = abs(cx) < 1f && cz < 0f
                val edge = abs(cx) > halfX - 1f
                grid.set(x, -i - 1, z, if (spine) 1 else if (cz > 0.5f && abs(cx) < 2f) 1 else 0,
                    q((if (edge) 0.78f else 1.05f - cz * 0.1f) + CosmeticModels.jitter(x, i, z, 0.02f)))
            }
        }
        return grid.mesh(FINE)
    }

    // ---- devil tail --------------------------------------------------------------------------------------

    private fun devilLink(index: Int): VoxelMesh {
        val grid = VoxelGrid()
        if (index == DEVIL_SEGMENTS) return devilSpade(grid)
        for (y in -DEVIL_SEGMENT until 0) for (x in -1..0) for (z in -1..0) {
            val seam = y == -1
            val shade = (if (z == -1) 1.1f else 0.85f) - (if (x == -1) 0f else 0.06f) - (if (seam) 0.14f else 0f)
            grid.set(x, y, z, 1, q(shade + CosmeticModels.jitter(x, y + index * 3, z, 0.02f)))
        }
        return grid.mesh(FINE) { x, _, z -> VoxelKit.radialNormal(x, z, 0f, 0f, 1f, 1f) }
    }

    /** The spade: a barbed arrowhead, three voxels thick in the middle, pointing along the chain. */
    private fun devilSpade(grid: VoxelGrid): VoxelMesh {
        val outline = listOf(
            floatArrayOf(0f, -14.5f), floatArrayOf(4.6f, -7f), floatArrayOf(6.2f, -2.2f), floatArrayOf(1.7f, -4.2f),
            floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(-1.7f, -4.2f), floatArrayOf(-6.2f, -2.2f),
            floatArrayOf(-4.6f, -7f),
        )
        for (y in -16..0) for (x in -7..6) {
            val cx = x + 0.5f
            val cy = y + 0.5f
            if (!inside(outline, cx, cy)) continue
            val edge = edgeDistance(outline, cx, cy)
            val thick = if (edge > 1.6f) 3 else if (edge > 0.8f) 2 else 1
            val z0 = -(thick / 2)
            for (z in z0 until z0 + thick) {
                val ridge = abs(cx) < 0.9f
                val shade = (if (z == z0) 1.14f else 0.9f) - (if (edge < 0.9f) 0.1f else 0f) + (if (ridge) 0.08f else 0f)
                grid.set(x, y, z, 0, q(shade + CosmeticModels.jitter(x, y, z, 0.02f)))
            }
        }
        return grid.mesh(FINE)
    }

    // ---- fox tail ----------------------------------------------------------------------------------------

    private val FOX_RADIUS = floatArrayOf(4.6f, 6.4f, 7.6f, 7.8f, 6.6f, 4.6f)

    private fun foxPuff(index: Int): VoxelMesh {
        val grid = VoxelGrid()
        val r = FOX_RADIUS[index]
        val ry = FOX_SPACING * 2f * 0.95f * (if (index == FOX_PUFFS - 1) 1.25f else 1f)
        val tip = index >= FOX_PUFFS - 2
        val reach = r.toInt() + 2
        val ditherLimit = floatArrayOf(0.125f, 0.625f, 0.875f, 0.375f)
        for (x in -reach..reach) for (y in -(ry.toInt() + 2)..(ry.toInt() + 2)) for (z in -reach..reach) {
            val cx = x + 0.5f
            val cy = y + 0.5f
            val cz = z + 0.5f
            val rho = sqrt((cx / r).pow(2) + (cy / ry).pow(2) + (cz / r).pow(2))
            if (rho > 1f + (hash01(x, y, z + index) - 0.5f) * 0.2f) continue
            val row = Math.floorDiv(y + (abs(x) shr 1) + index * 5, 2)
            val fur = if ((row and 1) == 0) 1f else 0.88f
            val shade = q(fur * (0.93f + 0.14f * (-cz / r) - 0.05f * (cy / ry)) + CosmeticModels.jitter(x, y, z, 0.03f))
            val white = when {
                index == FOX_PUFFS - 1 -> true
                index == FOX_PUFFS - 2 -> ((-cy / ry + 1f) / 2f) > ditherLimit[(x and 1) + (z and 1) * 2] * 1.2f + 0.2f
                else -> false
            }
            grid.set(x, y, z, if (white && tip) 1 else 0, shade)
        }
        return grid.mesh(FINE) { x, y, z ->
            val nx = x / (r * r)
            val ny = y / (ry * ry)
            val nz = z / (r * r)
            val length = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-6f } ?: 1f
            floatArrayOf(nx / length, ny / length, nz / length)
        }
    }

    // ---- unicorn horn ------------------------------------------------------------------------------------

    /** Horn length and base radius in half pixels; the glowing tip covers the last [UNICORN_TIP_LENGTH]. */
    const val UNICORN_LENGTH = 24f
    const val UNICORN_RADIUS = 3.8f
    const val UNICORN_TIP_LENGTH = 7f

    /** The spiral horn: second color bands wind up a first color cone; it points up (-y) from the origin. */
    val UNICORN_HORN: VoxelMesh by lazy { unicornHorn() }

    /** The glowing tip (fixed warm white), and a wider faint shell, both drawn glowing. */
    val UNICORN_TIP: VoxelMesh by lazy { unicornTip(0.0f, 14).mesh(FINE) }
    val UNICORN_TIP_SHELL: VoxelMesh by lazy { unicornTip(1.1f, 15).mesh(FINE) }

    private fun coneRadius(t: Float): Float = UNICORN_RADIUS * (1f - t).pow(0.95f) + 0.25f

    private fun unicornHorn(): VoxelMesh {
        val grid = VoxelGrid()
        val reach = UNICORN_RADIUS.toInt() + 2
        val body = (UNICORN_LENGTH - UNICORN_TIP_LENGTH + 2f).toInt()
        val pitch = 7.5f
        for (i in 0 until body) {
            val t = (i + 0.5f) / UNICORN_LENGTH
            val radius = coneRadius(t)
            for (x in -reach until reach) for (z in -reach until reach) {
                val cx = x + 0.5f
                val cz = z + 0.5f
                val rho = hypot(cx, cz)
                val phi = atan2(cz, cx)
                val turn = phi / VoxelKit.TAU + i / pitch
                val band = (turn - floor(turn)) < 0.27f
                if (rho > radius + (if (band) 0.5f else 0f)) continue
                val light = 1f + 0.12f * kotlin.math.cos(phi - 2.2f)
                val shade = (if (band) 1.12f else 0.98f - t * 0.06f) * light
                grid.set(x, -i - 1, z, if (band) 1 else 0, q(shade + CosmeticModels.jitter(x, i, z, 0.015f)))
            }
        }
        return grid.mesh(FINE) { x, y, z ->
            val rho = hypot(x, z).takeIf { it > 1e-4f } ?: 1f
            val t = (-y / UNICORN_LENGTH).coerceIn(0f, 1f)
            val slope = UNICORN_RADIUS / UNICORN_LENGTH * 0.9f * (1f - t).pow(-0.05f)
            val length = sqrt(1f + slope * slope)
            floatArrayOf(x / rho / length, -slope / length, z / rho / length)
        }
    }

    private fun unicornTip(grow: Float, seed: Int): VoxelGrid {
        val grid = VoxelGrid()
        val warm = grid.fixed(0xFFE7A3)
        val from = (UNICORN_LENGTH - UNICORN_TIP_LENGTH).toInt()
        val reach = UNICORN_RADIUS.toInt() + 2
        for (i in from until UNICORN_LENGTH.toInt() + (if (grow > 0f) 2 else 0)) {
            val t = (i + 0.5f) / UNICORN_LENGTH
            val radius = coneRadius(t) + grow - 0.15f
            for (x in -reach until reach) for (z in -reach until reach) {
                if (hypot(x + 0.5f, z + 0.5f) > radius) continue
                grid.set(x, -i - 1, z, warm, q(1.0f + 0.12f * (t - 0.8f) / 0.2f + CosmeticModels.jitter(x, i, z + seed, 0.02f)))
            }
        }
        return grid
    }
}
