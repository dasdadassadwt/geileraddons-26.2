package geiler.addons.client.cosmetics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One gem of the royal crown: where it sits (crown frame, pixels), which way it faces and its hue index. */
@JvmRecord
data class GemSpot(val x: Float, val y: Float, val z: Float, val yaw: Float, val hue: Int)

/**
 * Voxel art for the hats (HAT slot) and the extra head pieces (bunny ears, fox ears, deer antlers), drawn after the
 * owner's reference sheets in half-pixel voxels. Everything is baked once (lazy) into rigid meshes; the rig only builds
 * matrices. Colors: channel 0 = Color (the sheets' red areas), channel 1 = Second Color (the black areas);
 * [VoxelGrid.fixed] only for small real-material accents (gold, gems, flower centers, hub).
 *
 * Frames, in model pixels. Hats: origin on the top of the head's skin, y grows down (hats extend to negative y),
 * z grows backward, +x is the player's left; the hat layer's top is at y = -0.5. Head pieces describe the player's LEFT
 * side with the origin at the piece's base on the head top (x grows outward); the right side is the mirrored mesh.
 */
object CosmeticModelsB {
    // ---- public geometry constants shared with the rig ------------------------------------------

    /** Hat frame y of the flower ring's center and its half-extent (rounded square, n = 4). */
    const val FLOWER_RING_Y = 1.4f
    const val FLOWER_RING_A = 5.0f
    const val FLOWER_COUNT = 8

    /** Headphone cup: center in the hat frame (left side) and the headband's lowest point. */
    const val CUP_X = 5.75f
    const val CUP_Y = 4.0f

    /** Propeller hub height above the hat origin (the spinner sits on a short stem). */
    const val PROPELLER_Y = -8.9f

    const val BUNNY_PIVOT_Y = -5.0f
    const val FOX_PIVOT_Y = -4.0f
    const val BUNNY_EAR_X = 2.2f
    const val FOX_EAR_X = 2.7f
    const val ANTLER_EAR_X = 2.4f

    val TOP_HAT: VoxelMesh by lazy { topHat() }
    val ROYAL_CROWN: VoxelMesh by lazy { crown() }

    /** Crown gems, one mesh per hue (channel 0, tinted by the rig so each gem can glint), with their colors. */
    val CROWN_GEMS: List<VoxelMesh> by lazy { GEM_HUES.indices.map { hue -> crownGems(hue) } }
    val GEM_COLORS: IntArray = intArrayOf(0xE0243A, 0x2D6BFF, 0x2FC25B, 0xA040E0)
    val SPARKLE: VoxelMesh by lazy { sparkle() }

    /** The witch hat's three rigid pieces (base with brim and band, middle, bent tip) and the two joints between. */
    val WITCH_HAT: List<VoxelMesh> by lazy { witchHat() }
    val WITCH_JOINTS: Array<FloatArray> by lazy { arrayOf(WITCH_SPINE.at(0.36f), WITCH_SPINE.at(0.66f)) }

    /** Where the witch hat's tip ends (hat frame), used to turn head turns into tip swing. */
    val WITCH_TIP: FloatArray by lazy { WITCH_SPINE.samples.last().copyOf() }

    val BEANIE: VoxelMesh by lazy { beanie() }
    val PROPELLER: VoxelMesh by lazy { propeller() }
    val FLOWER_RING: VoxelMesh by lazy { flowerRing() }
    val FLOWER: VoxelMesh by lazy { flower() }
    val SPRIG: VoxelMesh by lazy { sprig() }
    val HEADPHONE_BAND: VoxelMesh by lazy { headphoneBand() }
    val HEADPHONE_CUP: VoxelMesh by lazy { headphoneCup() }

    /** Two-piece ears: [0] lower (rooted on the head), [1] upper (hinged at the pivot). */
    val BUNNY_EAR: List<VoxelMesh> by lazy { listOf(bunnyEar(false), bunnyEar(true)) }
    val FOX_EAR: List<VoxelMesh> by lazy { listOf(foxEar(false), foxEar(true)) }
    val ANTLER: List<VoxelMesh> by lazy { antler() }
    val ANTLER_JOINT: FloatArray by lazy { ANTLER_SPINE.at(ANTLER_HINGE) }

    val GEM_SPOTS: List<GemSpot> by lazy { gemSpots() }

    /** Frame position and radial yaw (rotateY angle that turns a -z facing mesh outward) of flower or sprig [k] of [count]. */
    @JvmStatic
    fun ringSpot(k: Int, count: Int, phaseOffset: Float): FloatArray {
        val phi = (k + phaseOffset) / count * 2f * PI.toFloat()
        val r = ringRadius(phi)
        return floatArrayOf(r * sin(phi), FLOWER_RING_Y, -r * cos(phi), -phi)
    }

    private fun ringRadius(phi: Float): Float =
        FLOWER_RING_A / (abs(sin(phi)).pow(4) + abs(cos(phi)).pow(4)).pow(0.25f)

    // ---- top hat --------------------------------------------------------------------------------

    /**
     * Top hat: a wide rolled brim, a tall slightly flared crown with softly rounded corners and a Color band;
     * crown and brim are Second Color.
     */
    private fun topHat(): VoxelMesh {
        val g = VoxelGrid()
        fill(-6.6f, 6.6f, -2.0f, -0.5f, -6.6f, 6.6f) { vx, vy, vz, x, y, z ->
            val e = superNorm(x, z, 6.4f, 6.4f, 2.8f)
            if (e > 1f) return@fill
            val rim = e > 0.84f
            if (y < (if (rim) -2.0f else -1.5f)) return@fill
            val shade = 0.86f + (-0.5f - y) * 0.07f - (if (rim) 0f else e * 0.04f)
            g.set(vx, vy, vz, C1, shade)
        }
        fill(-4.7f, 4.7f, -9.5f, -1.5f, -4.7f, 4.7f) { vx, vy, vz, x, y, z ->
            val h = (-1.5f - y) / 8f
            val band = y > -3.5f
            val half = 4.0f + 0.14f * h + (if (band) 0.3f else 0f)
            val reach = if (y < -9.0f) half - 0.4f else half
            val e = superNorm(x, z, reach, reach, 7f)
            if (e > 1f) return@fill
            val corner = min(abs(x), abs(z)) / half
            val shade = when {
                band && y > -1.9f -> 0.84f
                band && y < -3.2f -> 1.12f
                band -> 1.0f
                y < -9.2f -> 1.14f
                y in -4.0f..-3.5f -> 0.9f
                else -> 0.97f + 0.1f * h
            } - (if (corner > 0.78f) 0.05f else 0f)
            g.set(vx, vy, vz, if (band) C0 else C1, shade)
        }
        return g.mesh(FINE) { fx, fy, fz ->
            val x = fx * FINE
            val y = fy * FINE
            val z = fz * FINE
            if (y <= -9.45f) return@mesh UP.copyOf()
            if (y > -2.05f && max(abs(x), abs(z)) > 4.7f) return@mesh outwardUp(x, z, 0.35f)
            val half = 4.0f + 0.14f * ((-1.5f - y) / 8f).coerceIn(0f, 1f) + (if (y > -3.5f) 0.3f else 0f)
            normalize(sign(x) * (abs(x) / half).pow(6f), 0f, sign(z) * (abs(z) / half).pow(6f))
        }
    }

    // ---- royal crown ----------------------------------------------------------------------------

    /**
     * Royal crown: a ragged dark fur band (fixed), a gold ring (Color) with five prongs per side and a puffed
     * velvet cap (Second Color). The gems are separate meshes so they can glint.
     */
    private fun crown(): VoxelMesh {
        val g = VoxelGrid()
        val fur = g.fixed(0x1C1B20)
        fill(-5.2f, 5.2f, -2.0f, -0.5f, -5.2f, 5.2f) { vx, vy, vz, x, y, z ->
            val bump = noise(vx, vy, vz, 3) - 0.5f
            val e = superNorm(x, z, 4.85f + bump * 0.4f, 4.85f + bump * 0.4f, 6f)
            if (e > 1f) return@fill
            g.set(vx, vy, vz, fur, 0.72f + 0.5f * noise(vx, vy, vz, 4) + (if (y < -1.5f) 0.06f else 0f))
        }
        // The gold ring: a one pixel wall.
        fill(-4.6f, 4.6f, -7.0f, -2.0f, -4.6f, 4.6f) { vx, vy, vz, x, y, z ->
            val outer = superNorm(x, z, 4.5f, 4.5f, 10f)
            val inner = superNorm(x, z, 3.5f, 3.5f, 10f)
            if (outer > 1f || inner < 1f) return@fill
            val shade: Float
            if (y > -4.5f) {
                val corner = min(abs(x), abs(z)) > 3.6f
                shade = when {
                    y > -2.8f -> 1.06f
                    y < -4.2f -> 1.14f
                    y in -3.55f..-3.2f -> 0.9f
                    else -> 1.0f
                } - (if (corner) 0.05f else 0f)
            } else {
                // Prongs: columns two voxels wide, taller in the middle.
                val u = if (abs(z) > abs(x)) vx else vz
                if (!prong(u)) return@fill
                if (y < -6.5f) return@fill
                shade = 1.0f + (-4.5f - y) * 0.05f - (if (u and 1 == 0) 0.04f else 0f)
            }
            g.set(vx, vy, vz, C0, shade)
        }
        // Velvet cap, puffed toward the middle with a cross seam.
        fill(-3.5f, 3.5f, -4.5f, -2.0f, -3.5f, 3.5f) { vx, vy, vz, x, y, z ->
            val e = superNorm(x, z, 3.5f, 3.5f, 10f)
            if (e >= 1f) return@fill
            val top = -3.6f - 0.9f * (1f - superNorm(x, z, 3.5f, 3.5f, 2f).coerceAtMost(1f))
            if (y < top) return@fill
            val seam = abs(abs(x) - abs(z)) < 0.3f
            g.set(vx, vy, vz, C1, 0.95f + 0.2f * (1f - e) - (if (seam) 0.1f else 0f))
        }
        return g.mesh(FINE) { fx, fy, fz ->
            val x = fx * FINE
            val y = fy * FINE
            val z = fz * FINE
            if (y > -2.05f && max(abs(x), abs(z)) > 4.6f) return@mesh outwardUp(x, z, 0.5f)
            if (max(abs(x), abs(z)) < 3.6f && y > -4.6f) normalize(x * 0.08f, -1f, z * 0.08f) else UP.copyOf()
        }.let { flatNormals(it) }
    }

    private fun prong(u: Int): Boolean = u in -9..-7 || u in -4..-2 || u in 1..3 || u in 6..8

    private val GEM_HUES = intArrayOf(0, 1, 2, 3)

    private class GemLayout(val face: Int, val u0: Int, val hue: Int)

    private val GEM_LAYOUT = listOf(
        GemLayout(0, -8, 0), GemLayout(0, -2, 1), GemLayout(0, 4, 2),
        GemLayout(1, -8, 2), GemLayout(1, -2, 3), GemLayout(1, 4, 0),
        GemLayout(2, -6, 3), GemLayout(2, 2, 1), GemLayout(3, -6, 1), GemLayout(3, 2, 3),
    )

    private fun crownGems(hue: Int): VoxelMesh {
        val g = VoxelGrid()
        for (layout in GEM_LAYOUT) {
            if (layout.hue != hue) continue
            for (a in 0..3) for (b in 0..3) {
                if ((a == 0 || a == 3) && (b == 0 || b == 3)) continue
                val inner = a in 1..2 && b in 1..2
                for (layer in 0..(if (inner) 1 else 0)) {
                    val u = layout.u0 + a
                    val vy = -8 + b
                    val shade = if (layer == 1) (if (a == 1 && b == 1) 1.25f else 1.08f) else 0.82f
                    when (layout.face) {
                        0 -> g.set(u, vy, -10 - layer, 0, shade)
                        1 -> g.set(u, vy, 9 + layer, 0, shade)
                        2 -> g.set(9 + layer, vy, u, 0, shade)
                        else -> g.set(-10 - layer, vy, u, 0, shade)
                    }
                }
            }
        }
        return g.mesh(FINE)
    }

    private fun gemSpots(): List<GemSpot> = GEM_LAYOUT.map { layout ->
        val along = (layout.u0 + 2) * FINE
        val out = 5.5f
        val y = -3.0f
        when (layout.face) {
            0 -> GemSpot(along, y, -out, 0f, layout.hue)
            1 -> GemSpot(along, y, out, PI.toFloat(), layout.hue)
            2 -> GemSpot(out, y, along, -PI.toFloat() / 2f, layout.hue)
            else -> GemSpot(-out, y, along, PI.toFloat() / 2f, layout.hue)
        }
    }

    /** A four-point sparkle facing -z, one voxel thick (drawn glowing by the rig). */
    private fun sparkle(): VoxelMesh {
        val g = VoxelGrid()
        for (i in -5..4) {
            val long = 5 - abs(i + 0.5f).toInt().coerceAtMost(5)
            if (long > 0) g.set(0, i, 0, 0, 1f)
        }
        for (i in -3..2) g.set(i, 0, 0, 0, 1f)
        g.box(-1, -1, 0, 1, 1, 1, 0, 1.2f)
        return g.mesh(FINE)
    }

    // ---- witch hat ------------------------------------------------------------------------------

    private val WITCH_SPINE: Spine by lazy {
        Spine.through(
            arrayOf(
                floatArrayOf(0f, -1.0f, 0f), floatArrayOf(0.1f, -4.0f, 0.2f), floatArrayOf(0.7f, -7.0f, 0.8f),
                floatArrayOf(2.2f, -9.6f, 2.0f), floatArrayOf(4.4f, -11.2f, 3.3f), floatArrayOf(6.4f, -10.9f, 4.3f),
            ),
            14,
        )
    }

    private fun witchRadius(t: Float, y: Float): Float {
        val cone = 3.6f * (1f - t).pow(1.15f) + 0.3f + (if (y > -1.9f) 0.25f else 0f)
        return cone + (if (y <= -1.5f && y >= -4.0f) 0.35f else 0f)
    }

    /**
     * Witch hat in three pieces so the bent tip can sway: base (wavy drooping brim, band with a gold buckle and the
     * lower cone), middle cone and the tip that curls back and to the player's left. Brim and cone are Second Color,
     * the band is Color, the buckle is fixed gold.
     */
    private fun witchHat(): List<VoxelMesh> {
        val spine = WITCH_SPINE
        val i1 = spine.indexAt(0.36f)
        val i2 = spine.indexAt(0.66f)
        val last = spine.samples.size - 1
        val ranges = listOf(0 to i1 + 1, (i1 - 1) to (i2 + 1), (i2 - 1) to last)
        val normals = spineNormal(spine.samples, 0.28f)
        return ranges.mapIndexed { index, (lo, hi) ->
            val g = VoxelGrid()
            if (index == 0) {
                fill(-7.4f, 7.4f, -1.5f, -0.5f, -7.4f, 7.4f) { vx, vy, vz, x, y, z ->
                    val edge = 6.8f + 0.35f * sin(2f * atan2(z, x) + 0.6f)
                    val e = sqrt(x * x + z * z) / edge
                    if (e > 1f) return@fill
                    val top = -1.5f + smoothstep(0.55f, 1f, e) * 0.5f
                    if (y < top) return@fill
                    g.set(vx, vy, vz, C1, 0.9f + (-0.5f - y) * 0.1f - e * 0.05f)
                }
            }
            tube(g, spine, lo, hi, -0.5f, ::witchRadius) { vx, vy, vz, t, d ->
                val py = (vy + 0.5f) * FINE
                val band = py <= -1.5f && py >= -4.0f
                val shade = when {
                    band && py > -1.9f -> 0.8f
                    band && py < -3.6f -> 1.1f
                    band -> 0.96f
                    else -> 1.0f + 0.06f * t - 0.05f * d * d
                }
                g.set(vx, vy, vz, if (band) C0 else C1, shade)
            }
            if (index == 0) buckle(g)
            g.mesh(FINE) { fx, fy, fz ->
                if (index == 0 && fy * FINE >= -1.55f) outwardUp(fx * FINE, fz * FINE, 0.3f) else normals(fx, fy, fz)
            }
        }
    }

    /** A gold frame buckle on the band's front, following the surface. */
    private fun buckle(g: VoxelGrid) {
        val gold = g.fixed(0xE8B923)
        for (vx in -3..2) for (vy in -7..-5) {
            val frame = vy == -7 || vy == -5 || vx == -3 || vx == 2
            if (!frame) continue
            var front = 0
            for (vz in -24..0) if (g.has(vx, vy, vz)) { front = vz; break }
            if (front == 0) continue
            g.set(vx, vy, front - 1, gold, if (vy == -7) 1.2f else 1.0f)
        }
    }

    // ---- propeller beanie -----------------------------------------------------------------------

    /**
     * Beanie: a Second Color band and a six-panel Color dome (seams in Second Color) with a short gold stem; the
     * propeller is a separate spinning mesh.
     */
    private fun beanie(): VoxelMesh {
        val g = VoxelGrid()
        val gold = g.fixed(0xE0A81C)
        fill(-5.2f, 5.2f, -2.5f, -0.5f, -5.2f, 5.2f) { vx, vy, vz, x, y, z ->
            val e = superNorm(x, z, 5.0f, 5.0f, 4f)
            if (e > 1f) return@fill
            g.set(vx, vy, vz, C1, 0.88f + (-0.5f - y) * 0.06f - e * 0.05f)
        }
        val sixth = (PI / 3.0).toFloat()
        fill(-5f, 5f, -7.0f, -2.5f, -5f, 5f) { vx, vy, vz, x, y, z ->
            val h = (-2.5f - y) / 4.5f
            val a = 4.7f * sqrt(max(0f, 1f - h.pow(2.3f)))
            val e = superNorm(x, z, a, a, 3.2f)
            if (e > 1f) return@fill
            val angle = atan2(z, x)
            val diff = angle - (angle / sixth).roundToInt() * sixth
            val r = sqrt(x * x + z * z)
            val seam = r * sin(abs(diff)) < 0.38f && r > 0.5f
            val centered = 1f - abs(diff) / (sixth / 2f)
            val button = h > 0.9f && r < 0.8f
            if (seam || button) g.set(vx, vy, vz, C1, 0.95f + 0.08f * h)
            else g.set(vx, vy, vz, C0, 0.92f + 0.12f * centered + 0.1f * h - e * 0.06f)
        }
        fill(-0.7f, 0.7f, -8.9f, -6.9f, -0.7f, 0.7f) { vx, vy, vz, x, y, z ->
            if (sqrt(x * x + z * z) < 0.6f) g.set(vx, vy, vz, gold, 0.9f + (-6.9f - y) * 0.08f)
        }
        return g.mesh(FINE) { fx, fy, fz ->
            val x = fx * FINE
            val y = fy * FINE
            val z = fz * FINE
            if (y > -2.55f) return@mesh outwardUp(x, z, 0.2f)
            val h = ((-2.5f - y) / 4.5f).coerceIn(0f, 1f)
            val r = sqrt(x * x + z * z).coerceAtLeast(0.1f)
            normalize(x / r, -0.35f - 1.4f * h.pow(1.5f), z / r)
        }
    }

    /** Two flat blades through a gold hub, origin at the hub center (the rig spins it about y). */
    private fun propeller(): VoxelMesh {
        val g = VoxelGrid()
        val yellow = g.fixed(0xF2C230)
        val gold = g.fixed(0xC98A12)
        fill(-6.7f, 6.7f, -0.5f, 0.5f, -1.5f, 1.5f) { vx, vy, vz, x, y, z ->
            val ax = abs(x)
            var halfZ = 0.8f + 0.4f * smoothstep(1.5f, 4.5f, ax)
            if (ax > 5.5f) halfZ *= sqrt(max(0f, 1f - ((ax - 5.5f) / 1.2f).pow(2)))
            if (abs(z) >= halfZ) return@fill
            // The two blades are pitched opposite ways, so the propeller looks the same after half a turn.
            val lowSide = if (x > 0f) z < 0f else z > 0f
            val spine = abs(z) < 0.3f || ax < 1.2f
            if (!spine && (if (lowSide) y < 0f else y > 0f)) return@fill
            g.set(vx, vy, vz, yellow, 1.0f + (if (y < 0f) 0.1f else 0f) - ax * 0.015f + noise2(vx, vy, vz, 10))
        }
        g.ball(0f, -0.3f, 0f, 1.1f, gold, 1.05f)
        return g.mesh(FINE)
    }

    // ---- flower crown ---------------------------------------------------------------------------

    /** A ring of leafy vine around the upper head (Second Color leaves, ragged and mottled). */
    private fun flowerRing(): VoxelMesh {
        val g = VoxelGrid()
        val samples = Array(240) { i ->
            val phi = i / 240f * 2f * PI.toFloat()
            val r = ringRadius(phi)
            floatArrayOf(r * sin(phi), FLOWER_RING_Y, -r * cos(phi))
        }
        val spine = Spine(samples)
        tube(g, spine, 0, samples.size - 1, 99f, { _, _ -> 0.8f }) { vx, vy, vz, _, d ->
            if (d > 0.72f && noise(vx, vy, vz, 11) > 0.55f) return@tube
            val mottled = 0.78f + 0.38f * blockNoise(vx, vy, vz, 2, 12)
            g.set(vx, vy, vz, C1, mottled + (if (vy < 0) 0.08f else -0.04f))
        }
        return g.mesh(FINE)
    }

    /** A five-petal flower facing -z, origin at its center: Color petals, fixed yellow heart, Second Color calyx. */
    private fun flower(): VoxelMesh {
        val g = VoxelGrid()
        val yellow = g.fixed(0xF4C21E)
        for (vx in -4..3) for (vy in -4..3) {
            val cx = vx + 0.5f
            val cy = vy + 0.5f
            val r = sqrt(cx * cx + cy * cy)
            val theta = atan2(cy, cx)
            val lobes = cos(5f * theta)
            if (r > 3.3f + 0.55f * lobes) continue
            if (r < 1.25f) {
                g.set(vx, vy, -1, yellow, 1.12f - 0.08f * r)
                g.set(vx, vy, 0, C0, 0.7f)
            } else {
                g.set(vx, vy, 0, C0, 0.82f + 0.22f * lobes - (if (r < 2.0f) 0.14f else 0f))
            }
            if (r < 2.1f) g.set(vx, vy, 1, C1, 0.78f)
        }
        return g.mesh(FINE)
    }

    /** Two small leaves in a V pointing outward (-z), origin at the stem. */
    private fun sprig(): VoxelMesh {
        val g = VoxelGrid()
        for (side in intArrayOf(-1, 1)) {
            val angle = 0.62f * side
            val dx = sin(angle)
            val dz = -cos(angle)
            val length = 3.6f
            for (vx in -9..8) for (vz in -9..1) {
                val x = (vx + 0.5f) * FINE
                val z = (vz + 0.5f) * FINE
                val t = x * dx + z * dz
                val s = x * dz * -1f + z * dx
                if (t < 0f || t > length) continue
                val half = 0.85f * sin(PI.toFloat() * (t / length).pow(0.8f))
                if (abs(s) > half) continue
                val shade = if (abs(s) < 0.22f) 0.8f else 0.95f + 0.2f * (1f - t / length) + noise2(vx, 0, vz, 13)
                g.set(vx, 0, vz, C1, shade)
                if (t < 1.4f) g.set(vx, -1, vz, C1, shade + 0.08f)
            }
        }
        return g.mesh(FINE)
    }

    // ---- headphones -----------------------------------------------------------------------------

    private fun inRoundTop(x: Float, y: Float, halfW: Float, top: Float, bottom: Float, r: Float): Boolean {
        if (abs(x) > halfW || y < top || y > bottom) return false
        if (y < top + r && abs(x) > halfW - r) {
            val dx = abs(x) - (halfW - r)
            val dy = top + r - y
            return dx * dx + dy * dy <= r * r
        }
        return true
    }

    /** The arched headband (Second Color) with small Color sliders on the side bars. */
    private fun headphoneBand(): VoxelMesh {
        val g = VoxelGrid()
        fill(-6.0f, 6.0f, -2.6f, 4.2f, -1.0f, 0.5f) { vx, vy, vz, x, y, z ->
            if (!inRoundTop(x, y, 5.7f, -2.5f, 4.0f, 2.2f) || inRoundTop(x, y, 4.7f, -1.5f, 99f, 1.2f)) return@fill
            val inside = inRoundTop(x, y, 5.45f, -2.25f, 4.0f, 1.9f) && !inRoundTop(x, y, 4.95f, -1.75f, 99f, 1.0f)
            val shade = (if (y < -2.0f) 1.12f else 0.98f) - (if (inside) 0f else 0.06f) + noise2(vx, vy, vz, 14)
            g.set(vx, vy, vz, C1, shade)
        }
        fill(-6.0f, 6.0f, -0.2f, 1.6f, -1.25f, 0.75f) { vx, vy, vz, x, y, z ->
            if (abs(x) in 4.55f..5.95f) g.set(vx, vy, vz, C0, 1.0f + (if (y < 0.4f) 0.08f else -0.04f))
        }
        return g.mesh(FINE)
    }

    /**
     * An ear cup (left side), centered on its own origin with the axis along x: a pad toward the head, a rounded
     * Color shell and a bullseye on the outer face (black, color, fixed gold, black).
     */
    private fun headphoneCup(): VoxelMesh {
        val g = VoxelGrid()
        val gold = g.fixed(0xE8B01E)
        fill(-1.3f, 1.3f, -2.8f, 2.8f, -2.8f, 2.8f) { vx, vy, vz, x, y, z ->
            val r = sqrt(y * y + z * z)
            if (r > 2.7f) return@fill
            val shade = 1.0f - 0.12f * (r / 2.7f) + noise2(vx, vy, vz, 15)
            if (x < -0.75f) {
                if (r < 2.3f) g.set(vx, vy, vz, C1, 0.82f)
            } else if (x < 0.75f) {
                g.set(vx, vy, vz, C0, if (r > 2.35f) shade + 0.1f else shade)
            } else {
                val reach = if (r < 1.15f) 1.3f else if (r < 2.2f) 1.0f else 0.75f
                if (x >= reach) return@fill
                when {
                    r < 0.6f -> g.set(vx, vy, vz, C1, 0.85f)
                    r < 1.15f -> g.set(vx, vy, vz, gold, 1.08f)
                    r < 1.7f -> g.set(vx, vy, vz, C0, 0.78f)
                    r < 2.2f -> g.set(vx, vy, vz, C1, 0.9f)
                    else -> g.set(vx, vy, vz, C0, shade + 0.06f)
                }
            }
        }
        return g.mesh(FINE) { fx, fy, fz ->
            val x = fx * FINE
            val y = fy * FINE
            val z = fz * FINE
            val r = sqrt(y * y + z * z).coerceAtLeast(0.1f)
            if (x > 0.7f) normalize(0.9f, y / r * 0.35f, z / r * 0.35f) else normalize(0f, y / r, z / r)
        }
    }

    // ---- bunny ears -----------------------------------------------------------------------------

    private fun bunnyEarHalfWidth(y: Float): Float {
        if (y > -8.0f) return 1.5f - 0.1f * (1f - (y / -8f).coerceIn(0f, 1f)) * 0f
        return 1.5f * sqrt(max(0f, 1f - ((-8.0f - y) / 3.0f).pow(2)))
    }

    /**
     * Bunny ear: a 3 px wide paddle, 11 px tall with a rounded tip, a Color back and a recessed Second Color
     * (pink) inside on the front. Two pieces hinged at [BUNNY_PIVOT_Y] so the upper half can flop.
     */
    private fun bunnyEar(upper: Boolean): VoxelMesh {
        val g = VoxelGrid()
        val yMin = if (upper) -11.1f else -5.5f
        val yMax = if (upper) -4.5f else 0.4f
        fill(-1.6f, 1.6f, yMin, yMax, -1.2f, 0.7f) { vx, vy, vz, x, y, z ->
            val w = bunnyEarHalfWidth(y)
            if (abs(x) >= w) return@fill
            val frontLayer = vz == -2
            val thick = abs(x) < w - 0.5f
            if (!(vz in (if (thick) -2 else -1)..0)) return@fill
            val panelW = 0.85f * sqrt(max(0f, 1f - ((-7.2f - y).coerceAtLeast(0f) / 2.4f).pow(2)))
            val panel = abs(x) < panelW && y < -2.3f && y > -9.4f
            if (frontLayer && panel) return@fill
            val shade = 0.97f - (if (abs(x) > w - 0.5f) 0.07f else 0f) + (if (y < -9.5f) 0.05f else 0f) + noise2(vx, vy, vz, 16)
            if (vz == -1 && panel) g.set(vx, vy, vz, C1, 0.82f + (-2.3f - y) * 0.03f)
            else g.set(vx, vy, vz, C0, shade)
        }
        return g.mesh(FINE)
    }

    // ---- fox ears -------------------------------------------------------------------------------

    private fun foxEdgeDistance(x: Float, y: Float): Float {
        // Triangle (-2.5, 0.4) (2.5, 0.4) (0.9, -6.4): distance inside the triangle to its nearest side.
        val ax = -2.5f
        val ay = 0.4f
        val bx = 2.5f
        val by = 0.4f
        val cx = 0.9f
        val cy = -6.4f
        return min(
            min(edgeInside(x, y, ax, ay, bx, by), edgeInside(x, y, bx, by, cx, cy)),
            edgeInside(x, y, cx, cy, ax, ay),
        )
    }

    private fun edgeInside(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        // Positive on the interior side for a clockwise-in-screen triangle; unit distance.
        return ((px - ax) * dy - (py - ay) * dx) / sqrt(dx * dx + dy * dy)
    }

    /**
     * Fox ear: a pointed triangle 5 px wide and 6.8 tall, Color body with a recessed fixed-cream inside on the front
     * and a Second Color tip (the upper piece, hinged at [FOX_PIVOT_Y]).
     */
    private fun foxEar(tip: Boolean): VoxelMesh {
        val g = VoxelGrid()
        val cream = g.fixed(0xF3E7D3)
        val yMin = if (tip) -6.6f else -4.0f
        val yMax = if (tip) -3.5f else 0.4f
        fill(-2.8f, 2.8f, yMin, yMax, -1.2f, 1.0f) { vx, vy, vz, x, y, z ->
            val inside = foxEdgeDistance(x, y)
            if (inside < 0f) return@fill
            val h = (0.4f - y) / 6.8f
            val back = if (h < 0.55f) 1 else if (h < 0.82f) 0 else -1
            if (vz < -2 || vz > back + 1) return@fill
            val front = if (h < 0.55f) -2 else if (h < 0.82f) -2 else -1
            if (vz < front) return@fill
            val black = y < -4.4f
            val frontLayer = vz == front
            val pocket = !black && inside > 0.75f && y > -4.2f
            if (frontLayer && pocket) return@fill
            if (vz == front + 1 && pocket) {
                g.set(vx, vy, vz, cream, 0.9f + 0.1f * (1f - h))
                return@fill
            }
            val shade = 0.98f - 0.06f * (1f - min(1f, inside / 1.2f)) + 0.05f * h + noise2(vx, vy, vz, 17)
            g.set(vx, vy, vz, if (black) C1 else C0, shade)
        }
        return g.mesh(FINE)
    }

    // ---- deer antlers ---------------------------------------------------------------------------

    private const val ANTLER_HINGE = 0.42f

    private val ANTLER_BEAM: Array<FloatArray> = arrayOf(
        floatArrayOf(0f, 0.3f, 0f), floatArrayOf(0.6f, -1.8f, 0.2f), floatArrayOf(1.5f, -3.7f, 0.6f),
        floatArrayOf(2.6f, -5.5f, 0.4f), floatArrayOf(3.5f, -7.0f, -0.3f),
    )

    private val ANTLER_SPINE: Spine by lazy { Spine.through(ANTLER_BEAM, 12) }

    /** A side tine: start fraction along the beam, direction (x, y, z), length. */
    private class Tine(val at: Float, val dx: Float, val dy: Float, val dz: Float, val length: Float)

    private val ANTLER_TINES = listOf(
        Tine(0.26f, -0.15f, -1f, -1.05f, 2.6f), Tine(0.52f, 1.05f, -1f, 0.15f, 2.7f),
        Tine(0.74f, -0.7f, -1f, 0.35f, 2.3f),
    )

    /**
     * Antler: a curving beam with a forward brow tine, an outward tine and an inward tine, Color shaft with Second
     * Color tips. [0] is the beam up to the hinge with the brow tine; [1] the rest.
     */
    private fun antler(): List<VoxelMesh> {
        val beam = ANTLER_SPINE
        val hinge = beam.indexAt(ANTLER_HINGE)
        val spines = ArrayList<Spine>()
        spines.add(beam)
        for (tine in ANTLER_TINES) {
            val root = beam.at(tine.at)
            val dir = normalize(tine.dx, tine.dy, tine.dz)
            val mid = floatArrayOf(root[0] + dir[0] * tine.length * 0.5f + 0.15f * dir[2], root[1] + dir[1] * tine.length * 0.5f, root[2] + dir[2] * tine.length * 0.5f)
            val end = floatArrayOf(root[0] + dir[0] * tine.length, root[1] + dir[1] * tine.length, root[2] + dir[2] * tine.length)
            spines.add(Spine.through(arrayOf(root, mid, end), 8))
        }
        val normals = spineNormal(spines.flatMap { it.samples.toList() }, 0f)
        return listOf(0, 1).map { part ->
            val g = VoxelGrid()
            for ((index, spine) in spines.withIndex()) {
                val isBeam = index == 0
                val lowerTine = index == 1
                val lo: Int
                val hi: Int
                when {
                    isBeam && part == 0 -> { lo = 0; hi = min(spine.samples.size - 1, hinge + 2) }
                    isBeam -> { lo = max(0, hinge - 2); hi = spine.samples.size - 1 }
                    (part == 0) == lowerTine -> { lo = 0; hi = spine.samples.size - 1 }
                    else -> continue
                }
                tube(g, spine, lo, hi, 99f, { t, _ -> (if (isBeam) 0.9f - 0.45f * t else 0.62f - 0.3f * t) }) { vx, vy, vz, t, d ->
                    val tip = t > 0.8f && !(isBeam && t < 0.95f && false)
                    val shade = 1.05f - 0.14f * d + (if (vy < 0) 0.04f else 0f) + noise2(vx, vy, vz, 18)
                    g.set(vx, vy, vz, if (tip) C1 else C0, shade)
                }
            }
            g.mesh(FINE, normals)
        }
    }

    // ---- toolkit --------------------------------------------------------------------------------

    private const val FINE = 0.5f
    private const val C0 = 0
    private const val C1 = 1
    private val UP = floatArrayOf(0f, -1f, 0f)

    /** Visits every voxel whose center lies inside the box; [x], [y], [z] are the center in pixels. */
    private inline fun fill(
        x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float,
        body: (vx: Int, vy: Int, vz: Int, x: Float, y: Float, z: Float) -> Unit,
    ) {
        val ax = ceil(x0 * 2f - 0.5f).toInt()
        val bx = ceil(x1 * 2f - 0.5f).toInt() - 1
        val ay = ceil(y0 * 2f - 0.5f).toInt()
        val by = ceil(y1 * 2f - 0.5f).toInt() - 1
        val az = ceil(z0 * 2f - 0.5f).toInt()
        val bz = ceil(z1 * 2f - 0.5f).toInt() - 1
        for (vx in ax..bx) {
            val x = (vx + 0.5f) * FINE
            for (vy in ay..by) {
                val y = (vy + 0.5f) * FINE
                for (vz in az..bz) body(vx, vy, vz, x, y, (vz + 0.5f) * FINE)
            }
        }
    }

    private fun superNorm(x: Float, z: Float, a: Float, b: Float, n: Float): Float =
        ((abs(x) / a).pow(n) + (abs(z) / b).pow(n)).pow(1f / n)

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun sign(v: Float): Float = if (v < 0f) -1f else 1f

    private fun noise(x: Int, y: Int, z: Int, salt: Int): Float {
        var h = x * 73856093 xor y * 19349663 xor z * 83492791 xor salt * 374761393
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0xFFFF) / 65535f
    }

    private fun blockNoise(x: Int, y: Int, z: Int, size: Int, salt: Int): Float =
        noise(Math.floorDiv(x, size), Math.floorDiv(y, size), Math.floorDiv(z, size), salt)

    /** Soft felt-like shade variation in 2-voxel blocks, about +-0.03. */
    private fun noise2(x: Int, y: Int, z: Int, salt: Int): Float = (blockNoise(x, y, z, 2, salt) - 0.5f) * 0.06f

    private fun normalize(x: Float, y: Float, z: Float): FloatArray {
        val length = sqrt(x * x + y * y + z * z).takeIf { it > 1e-4f } ?: 1f
        return floatArrayOf(x / length, y / length, z / length)
    }

    private fun outwardUp(x: Float, z: Float, outward: Float): FloatArray {
        val r = sqrt(x * x + z * z).takeIf { it > 1e-3f } ?: 1f
        return normalize(x / r * outward, -1f, z / r * outward)
    }

    /** Used where the surface normal is simply the face's own: keeps faces unblended. */
    private fun flatNormals(mesh: VoxelMesh): VoxelMesh = mesh

    /** A polyline through control points as a smooth sampled curve (Catmull-Rom), with its running length. */
    private class Spine(val samples: Array<FloatArray>) {
        val travelled = FloatArray(samples.size)
        val total: Float

        init {
            for (i in 1 until samples.size) {
                val p = samples[i]
                val q = samples[i - 1]
                travelled[i] = travelled[i - 1] + sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2))
            }
            total = max(travelled.last(), 1e-3f)
        }

        fun indexAt(t: Float): Int {
            val target = t * total
            var i = 0
            while (i < samples.size - 1 && travelled[i] < target) i++
            return i
        }

        fun at(t: Float): FloatArray = samples[indexAt(t)].copyOf()

        companion object {
            fun through(points: Array<FloatArray>, perSegment: Int): Spine {
                val out = ArrayList<FloatArray>()
                for (i in 0 until points.size - 1) for (step in 0 until perSegment) out.add(catmullRom(points, i, step / perSegment.toFloat()))
                out.add(points.last().copyOf())
                return Spine(out.toTypedArray())
            }
        }
    }

    private fun catmullRom(points: Array<FloatArray>, segment: Int, t: Float): FloatArray {
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

    /**
     * Fills a tube of samples [lo]..[hi] of [spine]: each voxel is painted once, from its nearest sample.
     * [radius] gets (fraction along the spine, sample y) in pixels; voxels below [maxY] only.
     */
    private fun tube(
        grid: VoxelGrid, spine: Spine, lo: Int, hi: Int, maxY: Float,
        radius: (Float, Float) -> Float,
        paint: (vx: Int, vy: Int, vz: Int, t: Float, d: Float) -> Unit,
    ) {
        val best = HashMap<Long, FloatArray>()
        for (i in lo..hi) {
            val p = spine.samples[i]
            val t = spine.travelled[i] / spine.total
            val r = max(radius(t, p[1]), 0.3f) / FINE
            val cx = p[0] / FINE
            val cy = p[1] / FINE
            val cz = p[2] / FINE
            for (x in floor(cx - r).toInt()..ceil(cx + r).toInt()) {
                for (y in floor(cy - r).toInt()..ceil(cy + r).toInt()) {
                    if ((y + 0.5f) * FINE > maxY) continue
                    for (z in floor(cz - r).toInt()..ceil(cz + r).toInt()) {
                        val d = sqrt((x + 0.5f - cx).pow(2) + (y + 0.5f - cy).pow(2) + (z + 0.5f - cz).pow(2)) / r
                        if (d > 1f) continue
                        val key = tubeKey(x, y, z)
                        val old = best[key]
                        if (old == null || d < old[0]) best[key] = floatArrayOf(d, i.toFloat())
                    }
                }
            }
            val ox = floor(cx).toInt()
            val oy = floor(cy).toInt()
            val oz = floor(cz).toInt()
            if ((oy + 0.5f) * FINE <= maxY) best.putIfAbsent(tubeKey(ox, oy, oz), floatArrayOf(0.95f, i.toFloat()))
        }
        for ((key, b) in best) {
            val x = (key shr 32).toInt() - 4096
            val y = ((key shr 16) and 0xFFFF).toInt() - 4096
            val z = (key and 0xFFFF).toInt() - 4096
            paint(x, y, z, spine.travelled[b[1].toInt()] / spine.total, b[0])
        }
    }

    private fun tubeKey(x: Int, y: Int, z: Int): Long = ((x + 4096).toLong() shl 32) or ((y + 4096).toLong() shl 16) or (z + 4096).toLong()

    /** Smooth normals for tube meshes: radial from the nearest sample, tilted [lift] toward the tip. */
    private fun spineNormal(samples: List<FloatArray>, lift: Float): (Float, Float, Float) -> FloatArray = { fx, fy, fz ->
        spineNormalAt(samples.toTypedArray(), lift, fx * FINE, fy * FINE, fz * FINE)
    }

    private fun spineNormal(samples: Array<FloatArray>, lift: Float): (Float, Float, Float) -> FloatArray = { fx, fy, fz ->
        spineNormalAt(samples, lift, fx * FINE, fy * FINE, fz * FINE)
    }

    private fun spineNormalAt(samples: Array<FloatArray>, lift: Float, x: Float, y: Float, z: Float): FloatArray {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in samples.indices) {
            val s = samples[i]
            val d = (x - s[0]).pow(2) + (y - s[1]).pow(2) + (z - s[2]).pow(2)
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        val s = samples[best]
        val next = samples[min(best + 1, samples.size - 1)]
        val prev = samples[max(best - 1, 0)]
        val tangent = normalize(next[0] - prev[0], next[1] - prev[1], next[2] - prev[2])
        val radial = normalize(x - s[0], y - s[1], z - s[2])
        return normalize(radial[0] + tangent[0] * lift, radial[1] + tangent[1] * lift, radial[2] + tangent[2] * lift)
    }
}
