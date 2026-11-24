package geiler.addons.client.cosmetics

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A small software renderer for cosmetic pictures: the Cosmetics menu cards and the offline preview task.
 * Orthographic, painter's algorithm, 2x supersampled; draws placements on an optional grey stand-in player.
 * Returns ARGB pixels with a transparent background. Pure code, no game or AWT classes.
 */
object CosmeticIconRenderer {
    enum class Stand { NONE, HEAD, FULL, TORSO }

    /** Toward the light in view space: above, slightly left, from the viewer's side. */
    private val LIGHT = Vector3f(-0.3f, -0.6f, -0.75f).normalize()
    private const val STAND_COLOR = 0x9A9A9A
    private const val SUPERSAMPLE = 2

    private class Face(val xs: FloatArray, val ys: FloatArray, val depth: Float, val argb: Int)

    /**
     * @param yaw turns the subject (0 = front view, PI = back view)
     * @param pitch tilts the view (negative looks down on it)
     * @param scale pixels per model pixel; null fits the subject into the image with a small margin
     * @param centerY model y (pixels, down from the neck) drawn at the image center when [scale] is fixed
     */
    @JvmStatic
    @JvmOverloads
    fun render(
        placements: List<CosmeticRig.Placement>, body: BodyShape?, stand: Stand, size: Int,
        yaw: Float, pitch: Float, scale: Float? = null, centerY: Float = 4f,
        /** Extra torso-format quads (armor) drawn over the body in [extraColor]; preview only. */
        extra: FloatArray? = null, extraColor: Int = 0x6F8FB0,
    ): IntArray {
        val camera = Matrix4f().rotateX(pitch).rotateY(yaw)
        val faces = ArrayList<Face>()
        if (stand != Stand.NONE) {
            addMesh(faces, camera, Matrix4f(), when (stand) { Stand.HEAD -> HEAD_STAND; Stand.TORSO -> ARMS_STAND; else -> LIMBS_STAND }, STAND_COLOR, STAND_COLOR, 255)
            if (stand == Stand.FULL || stand == Stand.TORSO) {
                if (body == null) addMesh(faces, camera, Matrix4f(), TORSO_STAND, STAND_COLOR, STAND_COLOR, 255)
                else addTorso(faces, camera, BodyShapeGeometry.torso(body, false))
                if (extra != null) addTorso(faces, camera, extra, extraColor)
            }
        }
        for (p in placements) addMesh(faces, camera, p.transform, p.mesh, p.color, p.color2, p.alpha)
        val big = size * SUPERSAMPLE
        val pixels = IntArray(big * big)
        if (faces.isEmpty()) return downsample(pixels, size)
        val pixelsPerUnit: Float
        val offsetX: Float
        val offsetY: Float
        if (scale == null) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (face in faces) for (i in 0 until 4) {
                minX = min(minX, face.xs[i]); maxX = max(maxX, face.xs[i])
                minY = min(minY, face.ys[i]); maxY = max(maxY, face.ys[i])
            }
            pixelsPerUnit = big * 0.86f / max(maxX - minX, maxY - minY).coerceAtLeast(1f)
            offsetX = big / 2f - (minX + maxX) / 2f * pixelsPerUnit
            offsetY = big / 2f - (minY + maxY) / 2f * pixelsPerUnit
        } else {
            pixelsPerUnit = scale * SUPERSAMPLE
            offsetX = big / 2f
            offsetY = big / 2f - centerY * pixelsPerUnit
        }
        faces.sortByDescending { it.depth }
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        for (face in faces) {
            for (i in 0 until 4) {
                xs[i] = offsetX + face.xs[i] * pixelsPerUnit
                ys[i] = offsetY + face.ys[i] * pixelsPerUnit
            }
            fill(pixels, big, xs, ys, face.argb)
        }
        return downsample(pixels, size)
    }

    private fun addMesh(faces: MutableList<Face>, camera: Matrix4f, model: Matrix4f, mesh: VoxelMesh, color: Int, color2: Int, alpha: Int) {
        val full = Matrix4f(camera).mul(model)
        val normals = Matrix4f(full).invert().transpose()
        val corner = Vector3f()
        val normal = Vector3f()
        val smooth = FloatArray(3)
        for (face in 0 until mesh.faceCount()) {
            val xs = FloatArray(4)
            val ys = FloatArray(4)
            var depth = 0f
            for (c in 0 until 4) {
                val base = (face * 4 + c) * 3
                full.transformPosition(mesh.positions[base], mesh.positions[base + 1], mesh.positions[base + 2], corner)
                xs[c] = corner.x
                ys[c] = corner.y
                depth += corner.z
            }
            val d = mesh.directions[face] * 3
            normal.set(VoxelMesh.NORMALS[d], VoxelMesh.NORMALS[d + 1], VoxelMesh.NORMALS[d + 2])
            normals.transformDirection(normal).normalize()
            if (normal.z > 0.05f && alpha >= 255) continue // faces turned away, unless see-through
            mesh.normal(face, smooth)
            normals.transformDirection(normal.set(smooth[0], smooth[1], smooth[2])).normalize()
            val tint = mesh.tint(face, color, color2)
            faces.add(Face(xs, ys, depth / 4f, shade(tint, mesh.shades[face] * light(normal), alpha)))
        }
    }

    private fun addTorso(faces: MutableList<Face>, camera: Matrix4f, quads: FloatArray, color: Int = STAND_COLOR) {
        val corner = Vector3f()
        val normal = Vector3f()
        var i = 0
        while (i < quads.size) {
            val xs = FloatArray(4)
            val ys = FloatArray(4)
            var depth = 0f
            for (c in 0 until 4) {
                val o = i + c * 5
                camera.transformPosition(quads[o], quads[o + 1], quads[o + 2], corner)
                xs[c] = corner.x
                ys[c] = corner.y
                depth += corner.z
            }
            camera.transformDirection(quads[i + 20], quads[i + 21], quads[i + 22], normal)
            if (normal.z <= 0.05f) faces.add(Face(xs, ys, depth / 4f, shade(color, 0.95f * light(normal), 255)))
            i += BodyShapeGeometry.FLOATS_PER_QUAD
        }
    }

    private fun light(normal: Vector3f): Float = 0.62f + 0.38f * max(0f, normal.dot(LIGHT))

    private fun shade(rgb: Int, amount: Float, alpha: Int): Int {
        val r = min(255, ((rgb shr 16 and 0xFF) * amount).roundToInt())
        val g = min(255, ((rgb shr 8 and 0xFF) * amount).roundToInt())
        val b = min(255, ((rgb and 0xFF) * amount).roundToInt())
        return (alpha.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Scanline fill of a convex quad, blending see-through colors over what is already drawn. */
    private fun fill(pixels: IntArray, size: Int, xs: FloatArray, ys: FloatArray, argb: Int) {
        val top = max(0, floor(ys.min()).toInt())
        val bottom = min(size - 1, ceil(ys.max()).toInt())
        for (y in top..bottom) {
            val sy = y + 0.5f
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                val y0 = ys[i]
                val y1 = ys[j]
                if ((sy < y0) == (sy < y1)) continue
                val x = xs[i] + (sy - y0) / (y1 - y0) * (xs[j] - xs[i])
                left = min(left, x)
                right = max(right, x)
            }
            if (left > right) continue
            val from = max(0, (left + 0.5f).toInt())
            val to = min(size - 1, (right - 0.5f).toInt())
            for (x in from..to) {
                val index = y * size + x
                pixels[index] = blend(pixels[index], argb)
            }
        }
    }

    private fun blend(below: Int, above: Int): Int {
        val a = above ushr 24
        if (a == 0) return below
        if (a == 255 || below ushr 24 == 0) return above
        val inv = 255 - a
        val r = ((above shr 16 and 0xFF) * a + (below shr 16 and 0xFF) * inv) / 255
        val g = ((above shr 8 and 0xFF) * a + (below shr 8 and 0xFF) * inv) / 255
        val b = ((above and 0xFF) * a + (below and 0xFF) * inv) / 255
        val outA = max(below ushr 24, a)
        return (outA shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun downsample(pixels: IntArray, size: Int): IntArray {
        val big = size * SUPERSAMPLE
        val out = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0 until SUPERSAMPLE) for (dx in 0 until SUPERSAMPLE) {
                val p = pixels[(y * SUPERSAMPLE + dy) * big + x * SUPERSAMPLE + dx]
                val pa = p ushr 24
                a += pa
                r += (p shr 16 and 0xFF) * pa
                g += (p shr 8 and 0xFF) * pa
                b += (p and 0xFF) * pa
            }
            out[y * size + x] = if (a == 0) 0 else
                ((a / (SUPERSAMPLE * SUPERSAMPLE)) shl 24) or ((r / a) shl 16) or ((g / a) shl 8) or (b / a)
        }
        return out
    }

    /** Head 8x8x8 above the neck; limbs: arms beside the body and legs below it. */
    private val HEAD_STAND: VoxelMesh by lazy { VoxelGrid().apply { box(-4, -8, -4, 4, 0, 4, 0, 1f) }.mesh() }
    private val LIMBS_STAND: VoxelMesh by lazy {
        VoxelGrid().apply {
            box(-4, -8, -4, 4, 0, 4, 0, 1f)
            box(4, 0, -2, 8, 12, 2, 0, 0.9f)
            box(-8, 0, -2, -4, 12, 2, 0, 0.9f)
            box(-4, 12, -2, 4, 24, 2, 0, 0.85f)
        }.mesh()
    }
    /** Both arms beside the body, no head or legs: the frame for back pieces. */
    private val ARMS_STAND: VoxelMesh by lazy {
        VoxelGrid().apply {
            box(4, 0, -2, 8, 12, 2, 0, 0.9f)
            box(-8, 0, -2, -4, 12, 2, 0, 0.9f)
        }.mesh()
    }
    private val TORSO_STAND: VoxelMesh by lazy { VoxelGrid().apply { box(-4, 0, -2, 4, 12, 2, 0, 0.95f) }.mesh() }
}
