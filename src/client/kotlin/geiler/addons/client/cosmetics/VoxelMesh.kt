package geiler.addons.client.cosmetics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Cosmetic geometry as voxels, baked once into the faces that can be seen. A face between two filled voxels
 * is dropped, and touching faces with the same channel and shade are merged into one rectangle (greedy
 * meshing), so fine half-pixel models stay cheap. Each voxel carries a color channel (0 = color, 1 = color2,
 * or a fixed color from [fixed]) and a grey shade that multiplies the tint, so one model takes any color.
 */
class VoxelGrid {
    private val cells = LinkedHashMap<Long, Int>()
    private val palette = ArrayList<Int>()

    /**
     * A channel for a part that keeps its own color whatever the player picks (gold trim, gems, eyes, fire).
     * Pass the result as `channel` to [set], [box] or [ball]; the shade still multiplies it.
     */
    fun fixed(rgb: Int): Int {
        val color = rgb and 0xFFFFFF
        var index = palette.indexOf(color)
        if (index < 0) {
            check(palette.size < MAX_FIXED) { "too many fixed colors in one model" }
            palette.add(color)
            index = palette.size - 1
        }
        return FIXED_BASE + index
    }

    fun set(x: Int, y: Int, z: Int, channel: Int, shade: Float) {
        cells[key(x, y, z)] = (channel and 0xFF) or (level(shade) shl 8)
    }

    /** Like [set] but never overwrites a voxel that is already filled (for layering back to front). */
    fun setIfEmpty(x: Int, y: Int, z: Int, channel: Int, shade: Float) {
        cells.putIfAbsent(key(x, y, z), (channel and 0xFF) or (level(shade) shl 8))
    }

    fun clear(x: Int, y: Int, z: Int) {
        cells.remove(key(x, y, z))
    }

    fun has(x: Int, y: Int, z: Int): Boolean = cells.containsKey(key(x, y, z))

    /** Fills the box [x0, x1) x [y0, y1) x [z0, z1). */
    fun box(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, channel: Int, shade: Float) {
        for (x in x0 until x1) for (y in y0 until y1) for (z in z0 until z1) set(x, y, z, channel, shade)
    }

    /** Fills every voxel whose center lies within [radius] of ([cx], [cy], [cz]). */
    fun ball(cx: Float, cy: Float, cz: Float, radius: Float, channel: Int, shade: Float) {
        val r2 = radius * radius
        for (x in floor(cx - radius).toInt()..ceil(cx + radius).toInt()) {
            for (y in floor(cy - radius).toInt()..ceil(cy + radius).toInt()) {
                for (z in floor(cz - radius).toInt()..ceil(cz + radius).toInt()) {
                    val dx = x + 0.5f - cx
                    val dy = y + 0.5f - cy
                    val dz = z + 0.5f - cz
                    if (dx * dx + dy * dy + dz * dz <= r2) set(x, y, z, channel, shade)
                }
            }
        }
    }

    fun size(): Int = cells.size

    /**
     * Bakes the visible surface; [scale] is the voxel edge length in model pixels (0.5 = half-pixel voxels).
     * [smooth], given a face center in voxel units, returns the surface direction there; it is blended with
     * the face's own normal so curved shapes light smoothly instead of as a staircase.
     */
    @JvmOverloads
    fun mesh(scale: Float = 1f, smooth: ((Float, Float, Float) -> FloatArray)? = null): VoxelMesh {
        val out = MeshBuilder(scale, smooth)
        for (direction in 0 until 6) {
            val axis = direction / 2
            val positive = direction % 2 == 1
            // plane -> (u, v) -> attributes, where u and v are the two other axes.
            val planes = HashMap<Int, HashMap<Long, Int>>()
            for ((packed, value) in cells) {
                val p = intArrayOf(unpack(packed, 40), unpack(packed, 20), unpack(packed, 0))
                val n = p.copyOf()
                n[axis] += if (positive) 1 else -1
                if (has(n[0], n[1], n[2])) continue
                val plane = p[axis] + if (positive) 1 else 0
                val u = p[(axis + 1) % 3]
                val v = p[(axis + 2) % 3]
                planes.getOrPut(plane) { HashMap() }[planeKey(u, v)] = value
            }
            for ((plane, faces) in planes) greedy(direction, axis, plane, faces, out)
        }
        return out.build(palette.toIntArray())
    }

    private fun greedy(direction: Int, axis: Int, plane: Int, faces: HashMap<Long, Int>, out: MeshBuilder) {
        val keys = faces.keys.sortedWith(compareBy({ unpackPlane(it, true) }, { unpackPlane(it, false) }))
        val used = HashSet<Long>()
        for (start in keys) {
            if (start in used) continue
            val value = faces[start]!!
            val v0 = unpackPlane(start, true)
            val u0 = unpackPlane(start, false)
            var u1 = u0 + 1
            while (faces[planeKey(u1, v0)] == value && planeKey(u1, v0) !in used) u1++
            var v1 = v0 + 1
            rows@ while (true) {
                for (u in u0 until u1) {
                    val k = planeKey(u, v1)
                    if (faces[k] != value || k in used) break@rows
                }
                v1++
            }
            for (v in v0 until v1) for (u in u0 until u1) used.add(planeKey(u, v))
            out.quad(direction, axis, plane, u0, v0, u1, v1, value and 0xFF, (value shr 8) / 200f)
        }
    }

    private class MeshBuilder(val scale: Float, val smooth: ((Float, Float, Float) -> FloatArray)?) {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val directions = ArrayList<Byte>()
        val channels = ArrayList<Byte>()
        val shades = ArrayList<Float>()

        fun quad(direction: Int, axis: Int, plane: Int, u0: Int, v0: Int, u1: Int, v1: Int, channel: Int, shade: Float) {
            val corners = arrayOf(intArrayOf(u0, v0), intArrayOf(u1, v0), intArrayOf(u1, v1), intArrayOf(u0, v1))
            for (corner in corners) {
                val p = IntArray(3)
                p[axis] = plane
                p[(axis + 1) % 3] = corner[0]
                p[(axis + 2) % 3] = corner[1]
                positions.add(p[0] * scale)
                positions.add(p[1] * scale)
                positions.add(p[2] * scale)
            }
            directions.add(direction.toByte())
            channels.add(channel.toByte())
            shades.add(shade)
            if (smooth != null) {
                val center = FloatArray(3)
                center[axis] = plane.toFloat()
                center[(axis + 1) % 3] = (u0 + u1) / 2f
                center[(axis + 2) % 3] = (v0 + v1) / 2f
                val surface = smooth.invoke(center[0], center[1], center[2])
                var nx = VoxelMesh.NORMALS[direction * 3] * 0.45f + surface[0] * 0.55f
                var ny = VoxelMesh.NORMALS[direction * 3 + 1] * 0.45f + surface[1] * 0.55f
                var nz = VoxelMesh.NORMALS[direction * 3 + 2] * 0.45f + surface[2] * 0.55f
                val length = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-4f } ?: 1f
                nx /= length; ny /= length; nz /= length
                normals.add(nx); normals.add(ny); normals.add(nz)
            }
        }

        fun build(palette: IntArray) = VoxelMesh(
            positions.toFloatArray(), directions.toByteArray(), channels.toByteArray(), shades.toFloatArray(),
            if (smooth == null) null else normals.toFloatArray(), palette,
        )
    }

    private companion object {
        private const val FIXED_BASE = 2
        private const val MAX_FIXED = 250
        private const val BIAS = 1 shl 19
        private const val MASK = (1L shl 20) - 1

        fun level(shade: Float): Int = (shade.coerceIn(0f, 1.25f) * 200f).toInt()

        fun key(x: Int, y: Int, z: Int): Long =
            ((x + BIAS).toLong() shl 40) or ((y + BIAS).toLong() shl 20) or (z + BIAS).toLong()

        fun unpack(packed: Long, shift: Int): Int = ((packed shr shift) and MASK).toInt() - BIAS

        fun planeKey(u: Int, v: Int): Long = ((v + BIAS).toLong() shl 32) or (u + BIAS).toLong()

        fun unpackPlane(key: Long, v: Boolean): Int =
            (if (v) (key shr 32) else (key and 0xFFFFFFFFL)).toInt() - BIAS
    }
}

/** Baked faces: 4 corners (12 floats) per face, plus its direction (0..5), channel and shade. */
class VoxelMesh(
    @JvmField val positions: FloatArray,
    @JvmField val directions: ByteArray,
    @JvmField val channels: ByteArray,
    @JvmField val shades: FloatArray,
    /** Optional smoothed normal per face (3 floats); null means the face direction is the normal. */
    @JvmField val smoothNormals: FloatArray? = null,
    /** Fixed colors (RGB) for channels 2 and up, see [VoxelGrid.fixed]. */
    @JvmField val palette: IntArray = IntArray(0),
) {
    fun faceCount(): Int = directions.size

    /** The RGB a face is drawn in: [color] for channel 0, [color2] for channel 1, else its fixed color. */
    fun tint(face: Int, color: Int, color2: Int): Int = when (val channel = channels[face].toInt() and 0xFF) {
        0 -> color
        1 -> color2
        else -> palette[channel - 2]
    }

    /** Writes face [face]'s normal into [out] (index 0..2). */
    fun normal(face: Int, out: FloatArray) {
        val smooth = smoothNormals
        if (smooth != null) {
            out[0] = smooth[face * 3]; out[1] = smooth[face * 3 + 1]; out[2] = smooth[face * 3 + 2]
        } else {
            val d = directions[face] * 3
            out[0] = NORMALS[d]; out[1] = NORMALS[d + 1]; out[2] = NORMALS[d + 2]
        }
    }

    companion object {
        /** Unit normal per direction: -X, +X, -Y, +Y, -Z, +Z (direction = axis * 2 + positive). */
        @JvmField
        val NORMALS = floatArrayOf(-1f, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, 0f, 1f)
    }
}

/** Distance helper shared by the model builders. */
internal fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    val ex = px - (ax + dx * t)
    val ey = py - (ay + dy * t)
    return sqrt(ex * ex + ey * ey)
}
