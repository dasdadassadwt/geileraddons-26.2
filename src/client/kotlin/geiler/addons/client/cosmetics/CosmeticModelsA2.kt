package geiler.addons.client.cosmetics

import geiler.addons.client.cosmetics.VoxelKit.capsule
import geiler.addons.client.cosmetics.VoxelKit.hypot
import geiler.addons.client.cosmetics.VoxelKit.lerp
import geiler.addons.client.cosmetics.VoxelKit.p
import geiler.addons.client.cosmetics.VoxelKit.q
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mechanical and crystal wings. Built in half-pixel voxels, cached, one rigid mesh per moving part. The
 * mechanical wing is centered on its gold gear (the hub) and the crystal wing on its glowing core.
 */
object CosmeticModelsA2 {
    private const val FINE = 0.5f
    private const val GOLD = 0xE3B341

    // ---- mechanical wings --------------------------------------------------------------------------------

    /** Blade lengths in half pixels, inner to outer; the fan reads best with the longest one in the middle. */
    val MECH_BLADE_LENGTHS = floatArrayOf(34f, 39f, 43f, 46f, 43f, 39f, 34f)

    /** One red blade per entry of [MECH_BLADE_LENGTHS], hanging down (+y) from the hub. */
    val MECH_BLADES: Array<VoxelMesh> by lazy { Array(MECH_BLADE_LENGTHS.size) { blade(MECH_BLADE_LENGTHS[it], it).mesh(FINE) } }

    /** The gold cog (spins around z at the hub). */
    val MECH_GEAR: VoxelMesh by lazy { cog(8f, 6f, 8, 4, GOLD, 2.6f).mesh(FINE) }

    /** The arm from the hub down and in to the small black gear near the spine (static). */
    val MECH_ARM: VoxelMesh by lazy { mechArm().mesh(FINE) }

    /** Hub position in the wing frame (pixels): out and above the shoulder. */
    const val MECH_HUB_X = 5.5f
    const val MECH_HUB_Y = -3.5f

    /** The small black gear's center relative to the hub (pixels). */
    val MECH_SMALL_GEAR = floatArrayOf(-4f, 4.5f)

    private fun blade(length: Float, index: Int): VoxelGrid {
        val grid = VoxelGrid()
        val collar = grid.fixed(GOLD)
        val whole = length.toInt()
        for (y in 3 until whole) {
            val t = (y - 3f) / (whole - 3f)
            val halfWidth = if (t < 0.72f) 3f else lerp(3f, 0.5f, (t - 0.72f) / 0.28f)
            for (x in -3 until 3) {
                val dx = x + 0.5f
                if (kotlin.math.abs(dx) > halfWidth) continue
                val edge = kotlin.math.abs(dx) > halfWidth - 1f
                for (z in 0 until 2) {
                    var shade = when {
                        edge -> 0.8f
                        z == 0 -> 1.12f - t * 0.08f
                        else -> 0.95f
                    }
                    if (x == -1 || x == 0) shade -= 0.07f // a shallow groove down the middle
                    shade += CosmeticModels.jitter(x, y, index, 0.02f)
                    val channel = if (y < 7) 1 else 0
                    grid.set(x, y, z, if (y == 6) collar else channel, q(shade))
                }
            }
        }
        // rounded foot around the pivot pin
        grid.ball(0f, 1.5f, 1f, 3.2f, 1, 0.85f)
        return grid
    }

    /** A toothed disc: [inner] radius, tooth [outer] radius, [teeth] teeth, [thick] voxels thick; its core is second color. */
    private fun cog(outer: Float, inner: Float, teeth: Int, thick: Int, rgb: Int, core: Float): VoxelGrid {
        val grid = VoxelGrid()
        val gold = grid.fixed(rgb)
        val z0 = -thick / 2
        for (x in -10..9) for (y in -10..9) {
            val cx = x + 0.5f
            val cy = y + 0.5f
            val r = hypot(cx, cy)
            val a = atan2(cy, cx)
            val tooth = ((a / VoxelKit.TAU * teeth) - floor(a / VoxelKit.TAU * teeth)) < 0.5f
            val limit = if (tooth) outer else inner + 0.6f
            if (r > limit) continue
            for (z in 0 until thick) {
                val ring = r > inner - 1.2f
                val shade = when {
                    r < core + 1.2f -> 0.8f
                    z == 0 -> if (ring && tooth) 1.15f else 1.05f
                    z == thick - 1 -> 0.82f
                    else -> 0.9f
                } + CosmeticModels.jitter(x, y, z, 0.02f)
                grid.set(x, y, z0 + z, if (r < core) 1 else gold, q(shade))
            }
        }
        // the dark hub pokes out a little on both sides
        for (x in -4..3) for (y in -4..3) {
            if (hypot(x + 0.5f, y + 0.5f) < core) {
                grid.set(x, y, z0 - 1, 1, 0.9f)
                grid.set(x, y, z0 + thick, 1, 0.85f)
            }
        }
        return grid
    }

    /** The small black gear (second color) with a gold bolt, centered on its own axis (spins against the cog). */
    val MECH_SMALL: VoxelMesh by lazy {
        val grid = VoxelGrid()
        val gold = grid.fixed(GOLD)
        for (x in -6..5) for (y in -6..5) {
            val cx = x + 0.5f
            val cy = y + 0.5f
            val r = hypot(cx, cy)
            val a = atan2(cy, cx)
            val tooth = ((a / VoxelKit.TAU * 6) - floor(a / VoxelKit.TAU * 6)) < 0.5f
            if (r > (if (tooth) 4.6f else 3.5f)) continue
            for (z in 0 until 4) grid.set(x, y, z, 1, q((if (z == 0) 1.08f else 0.85f) + CosmeticModels.jitter(x, y, z, 0.02f)))
        }
        grid.ball(0f, 0f, -0.5f, 1.4f, gold, 1.1f)
        grid.mesh(FINE)
    }

    private fun mechArm(): VoxelGrid {
        val grid = VoxelGrid()
        val gold = grid.fixed(GOLD)
        // the gold arm from the hub to the small gear, a little behind the cog
        val gx = MECH_SMALL_GEAR[0] * 2f
        val gy = MECH_SMALL_GEAR[1] * 2f
        capsule(grid, p(0f, 0f, 3.5f), p(gx, gy, 3.5f), 1.9f, 1.7f, gold, { t, d -> 1.12f - 0.25f * d - 0.05f * t })
        return grid
    }

    // ---- crystal wings -----------------------------------------------------------------------------------

    /** Shards from the biggest to the smallest; one mesh each, pointing up (-y) from the origin. */
    val CRYSTAL_SHARDS: Array<VoxelMesh> by lazy {
        arrayOf(shard(28f, 5.2f, 0).mesh(FINE), shard(20f, 4.2f, 1).mesh(FINE), shard(13f, 3.2f, 2).mesh(FINE))
    }

    /** Shard lengths in pixels, per entry of [CRYSTAL_SHARDS]. */
    val CRYSTAL_SHARD_LENGTH = floatArrayOf(14f, 10f, 6.5f)

    /** A thin black rod, 8 px long along +y; the rig stretches it between the core and a shard. */
    val CRYSTAL_ROD: VoxelMesh by lazy {
        val g = VoxelGrid()
        for (y in 0 until 16) for (x in -1 until 1) for (z in -1 until 1) {
            g.set(x, y, z, 1, q(0.95f - (if (z == -1) 0f else 0.15f) - (if (x == -1) 0f else 0.08f)))
        }
        g.mesh(FINE)
    }

    /** The glowing core (a hot ball) and its soft halo; both drawn glowing. */
    val CRYSTAL_CORE: VoxelMesh by lazy { coreBall(3.6f, 1.3f, 0.9f).mesh(FINE) }
    val CRYSTAL_CORE_HALO: VoxelMesh by lazy { coreBall(7f, 1.0f, 0.95f).mesh(FINE) }

    private fun coreBall(radius: Float, hot: Float, edge: Float): VoxelGrid {
        val grid = VoxelGrid()
        val reach = radius.toInt() + 1
        for (x in -reach until reach) for (y in -reach until reach) for (z in -reach until reach) {
            val r = sqrt((x + 0.5f).let { it * it } + (y + 0.5f).let { it * it } + (z + 0.5f).let { it * it })
            if (r > radius) continue
            grid.set(x, y, z, 0, q(lerp(hot, edge, r / radius)))
        }
        return grid
    }

    /**
     * A hexagonal crystal [length] long and [radius] wide (half pixels) with a pointed top: the base is a short
     * flat cap, the body keeps its width for 62%, then it tapers to a point. Each of the six facets has its own
     * shade so it reads as cut glass, with a lighter edge line where two facets meet.
     */
    private fun shard(length: Float, radius: Float, index: Int): VoxelGrid {
        val grid = VoxelGrid()
        val facetShade = floatArrayOf(1.16f, 1.0f, 0.82f, 0.7f, 0.84f, 1.04f)
        val reach = radius.toInt() + 2
        val apothem = radius * cos(PI.toFloat() / 6f)
        for (i in 0 until length.toInt()) {
            val t = (i + 0.5f) / length
            val scale = when {
                t < 0.04f -> 0.8f
                t < 0.62f -> 1f
                else -> lerp(1f, 0.1f, (t - 0.62f) / 0.38f)
            }
            for (x in -reach until reach) for (z in -reach until reach) {
                val cx = x + 0.5f
                val cz = z + 0.5f
                val rho = hypot(cx, cz)
                val a = atan2(cz, cx) + PI.toFloat()
                val sector = a / (PI.toFloat() / 3f)
                val facet = sector.toInt() % 6
                val local = (sector - floor(sector) - 0.5f) * (PI.toFloat() / 3f)
                if (rho * cos(local) > apothem * scale) continue
                val edge = kotlin.math.abs(sector - floor(sector) - 0.5f) > 0.4f
                val shade = facetShade[facet] + (if (edge) 0.1f else 0f) + t * 0.05f + CosmeticModels.jitter(x, i, z + index, 0.015f)
                grid.set(x, -i - 1, z, 0, q(shade))
            }
        }
        return grid
    }
}
