package geiler.addons.client.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.model.geom.ModelPart
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Bends worn armor (chestplate, leggings belt, trims, glint) around a Cosmetics body shape. Player armor is
 * drawn with its own PlayerModel; right before it renders, that model's setupAnim names its body part and the
 * wearer's shape here ([arm]), and ModelPart.compile hands that part's cubes to [compile], which cuts the side
 * faces into half-pixel rows and moves every corner with [BodyShapeGeometry.warp]. Render thread only.
 */
object ArmorBodyWarp {
    private var part: ModelPart? = null
    private var shape: BodyShape? = null

    /** Called from an armor model's setupAnim; a null [shape] turns the warp off for [body]. */
    @JvmStatic
    fun arm(body: ModelPart, shape: BodyShape?) {
        if (shape == null) {
            if (part === body) part = null
            return
        }
        part = body
        this.shape = shape
    }

    @JvmStatic
    fun shapeFor(candidate: ModelPart): BodyShape? = if (candidate === part) shape else null

    /** Row height for the bent side faces, in pixels. */
    private const val ROW = 0.5f

    @JvmStatic
    fun compile(
        cubes: List<ModelPart.Cube>, shape: BodyShape, pose: PoseStack.Pose, consumer: VertexConsumer,
        light: Int, overlay: Int, color: Int,
    ) {
        val quads = bend(cubes.map { cube -> CubeFaces.of(cube) }, shape)
        emitAll(quads, pose, consumer, light, overlay, color)
    }

    /** One cube's faces as plain numbers (pixels and texture UVs), so the bending can be checked offline. */
    class CubeFaces(val inflate: Float, val faces: List<Array<FloatArray>>) {
        companion object {
            @JvmStatic
            fun of(cube: ModelPart.Cube): CubeFaces {
                var maxX = 0f
                for (polygon in cube.polygons) for (v in polygon.vertices()) maxX = maxOf(maxX, abs(v.x()))
                val faces = cube.polygons.filter { it.vertices().size == 4 }.map { polygon ->
                    Array(4) { i -> polygon.vertices()[i].let { floatArrayOf(it.x(), it.y(), it.z(), it.u(), it.v()) } }
                }
                return CubeFaces(maxX - cube.maxX, faces)
            }
        }
    }

    /**
     * Cuts vertical faces into rows and warps every corner. Returns quads of 4 corners (x, y, z, u, v) plus a
     * normal, laid out as [BodyShapeGeometry.FLOATS_PER_QUAD] floats each, like the skin torso.
     */
    @JvmStatic
    fun bend(cubes: List<CubeFaces>, shape: BodyShape): FloatArray {
        val out = ArrayList<Float>(512)
        val row = Array(4) { FloatArray(5) }
        for (cube in cubes) {
            for (vs in cube.faces) {
                val top = minOf(minOf(vs[0][1], vs[1][1]), minOf(vs[2][1], vs[3][1]))
                val bottom = maxOf(maxOf(vs[0][1], vs[1][1]), maxOf(vs[2][1], vs[3][1]))
                if (bottom - top < 1e-4f) {
                    add(out, vs, shape, cube.inflate)
                    continue
                }
                // Each corner's partner on its vertical edge (the neighbour at the other height).
                val partner = IntArray(4) { i ->
                    val next = (i + 1) % 4
                    if (abs(vs[next][1] - vs[i][1]) > 1e-4f) next else (i + 3) % 4
                }
                val cuts = ArrayList<Float>()
                cuts.add(top)
                var y = (floor(top / ROW) + 1f) * ROW
                while (y < bottom - 1e-4f) {
                    cuts.add(y)
                    y += ROW
                }
                cuts.add(bottom)
                for (r in 0 until cuts.size - 1) {
                    for (i in 0 until 4) {
                        val v = vs[i]
                        val p = vs[partner[i]]
                        val isTop = v[1] < p[1]
                        val t = if (isTop) v else p
                        val b = if (isTop) p else v
                        val f = ((if (isTop) cuts[r] else cuts[r + 1]) - t[1]) / (b[1] - t[1])
                        for (k in 0 until 5) row[i][k] = t[k] + (b[k] - t[k]) * f
                    }
                    add(out, row, shape, cube.inflate)
                }
            }
        }
        return out.toFloatArray()
    }

    private fun add(out: MutableList<Float>, corners: Array<FloatArray>, shape: BodyShape, inflate: Float) {
        val w = Array(4) { FloatArray(3) }
        for (i in 0 until 4) BodyShapeGeometry.warp(shape, inflate, corners[i][0], corners[i][1], corners[i][2], w[i])
        // Normal from the bent corners, turned to face the same way as the original face.
        val ox = (corners[2][1] - corners[0][1]) * (corners[3][2] - corners[1][2]) - (corners[2][2] - corners[0][2]) * (corners[3][1] - corners[1][1])
        val oy = (corners[2][2] - corners[0][2]) * (corners[3][0] - corners[1][0]) - (corners[2][0] - corners[0][0]) * (corners[3][2] - corners[1][2])
        val oz = (corners[2][0] - corners[0][0]) * (corners[3][1] - corners[1][1]) - (corners[2][1] - corners[0][1]) * (corners[3][0] - corners[1][0])
        var nx = (w[2][1] - w[0][1]) * (w[3][2] - w[1][2]) - (w[2][2] - w[0][2]) * (w[3][1] - w[1][1])
        var ny = (w[2][2] - w[0][2]) * (w[3][0] - w[1][0]) - (w[2][0] - w[0][0]) * (w[3][2] - w[1][2])
        var nz = (w[2][0] - w[0][0]) * (w[3][1] - w[1][1]) - (w[2][1] - w[0][1]) * (w[3][0] - w[1][0])
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length < 1e-6f) return
        val sign = if (nx * ox + ny * oy + nz * oz < 0f) -1f else 1f
        nx = nx / length * sign
        ny = ny / length * sign
        nz = nz / length * sign
        // Then make sure the normal points out of the body, whatever the face winding.
        val outward = outwardSign(corners)
        for (i in 0 until 4) {
            out.add(w[i][0]); out.add(w[i][1]); out.add(w[i][2]); out.add(corners[i][3]); out.add(corners[i][4])
        }
        out.add(nx * outward); out.add(ny * outward); out.add(nz * outward)
    }

    /** +1 when the original corners' cross product points away from the body center, else -1. */
    private fun outwardSign(c: Array<FloatArray>): Float {
        val nx = (c[2][1] - c[0][1]) * (c[3][2] - c[1][2]) - (c[2][2] - c[0][2]) * (c[3][1] - c[1][1])
        val ny = (c[2][2] - c[0][2]) * (c[3][0] - c[1][0]) - (c[2][0] - c[0][0]) * (c[3][2] - c[1][2])
        val nz = (c[2][0] - c[0][0]) * (c[3][1] - c[1][1]) - (c[2][1] - c[0][1]) * (c[3][0] - c[1][0])
        val mx = (c[0][0] + c[1][0] + c[2][0] + c[3][0]) / 4f
        val my = (c[0][1] + c[1][1] + c[2][1] + c[3][1]) / 4f - 6f
        val mz = (c[0][2] + c[1][2] + c[2][2] + c[3][2]) / 4f
        return if (nx * mx + ny * my + nz * mz < 0f) -1f else 1f
    }

    private fun emitAll(quads: FloatArray, pose: PoseStack.Pose, consumer: VertexConsumer, light: Int, overlay: Int, color: Int) {
        var i = 0
        while (i < quads.size) {
            val n = i + 20
            for (corner in 0 until 4) {
                val c = i + corner * 5
                consumer.addVertex(pose, quads[c] / 16f, quads[c + 1] / 16f, quads[c + 2] / 16f)
                    .setColor(color)
                    .setUv(quads[c + 3], quads[c + 4])
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, quads[n], quads[n + 1], quads[n + 2])
            }
            i += BodyShapeGeometry.FLOATS_PER_QUAD
        }
    }
}
