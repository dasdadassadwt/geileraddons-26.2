package geiler.addons.client.cosmetics

import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a player's walked path ([TrailHistory]) into placements. The history is in world coordinates; the render
 * layer's pose is the player's model space (pixels, y down, z backward, rotated by body yaw, scaled and moved with
 * the interpolated position), so [toModel] undoes that transform for every point each frame and the particles stay
 * where they were left while the player walks away. Sprites turn to face the camera.
 */
object TrailRig {
    private const val TAU = (2.0 * PI).toFloat()

    /** Most placements one player's trail may add in a frame. */
    const val MAX_PLACEMENTS = 80

    /** Where the player's feet are in model pixels, and the model offset vanilla applies (blocks). */
    private const val FEET_Y = 24f
    private const val MODEL_OFFSET = 1.501f

    /** Height of the ribbon's center above the feet (blocks) and how far behind the body it starts (pixels). */
    private const val RIBBON_HEIGHT = 0.78f
    private const val RIBBON_START_Z = 2.6f

    /** The player's transform for one frame, reused (no allocation per frame). */
    class Frame {
        @JvmField var ex = 0.0
        @JvmField var ey = 0.0
        @JvmField var ez = 0.0
        @JvmField var scale = 1f
        @JvmField var sx = 1f
        @JvmField var sy = 1f
        @JvmField var sz = 1f
        @JvmField var seconds = 0.0
        @JvmField var cos = 1f
        @JvmField var sin = 0f
        @JvmField var hasCamera = false
        @JvmField var cx = 0.0
        @JvmField var cy = 0.0
        @JvmField var cz = 0.0

        /** [bodyRot] in degrees as in the render state; [scale] the state's scale, [sx], [sy], [sz] the Player Size factors. */
        fun set(ex: Double, ey: Double, ez: Double, bodyRot: Float, scale: Float, sx: Float, sy: Float, sz: Float, seconds: Double): Frame {
            this.ex = ex; this.ey = ey; this.ez = ez
            this.scale = scale; this.sx = sx; this.sy = sy; this.sz = sz
            this.seconds = seconds
            val angle = Math.toRadians((bodyRot - 180f).toDouble())
            cos = cos(angle).toFloat()
            sin = sin(angle).toFloat()
            return this
        }

        fun camera(x: Double, y: Double, z: Double): Frame {
            hasCamera = true; cx = x; cy = y; cz = z
            return this
        }

        fun noCamera(): Frame {
            hasCamera = false
            return this
        }
    }

    /** Model-space pixels of the world point ([px], [py], [pz]) for the player described by [frame]. */
    @JvmStatic
    fun toModel(px: Double, py: Double, pz: Double, frame: Frame, out: FloatArray) {
        val dx = (px - frame.ex).toFloat()
        val dy = (py - frame.ey).toFloat()
        val dz = (pz - frame.ez).toFloat()
        val ax = (dx * frame.cos + dz * frame.sin) / frame.scale
        val az = (-dx * frame.sin + dz * frame.cos) / frame.scale
        val ay = dy / frame.scale
        out[0] = -16f * ax / frame.sx
        out[1] = 16f * (MODEL_OFFSET - ay / frame.sy)
        out[2] = 16f * az / frame.sz
    }

    /** The inverse of [toModel]: the world point of model-space pixels ([mx], [my], [mz]). */
    @JvmStatic
    fun toWorld(mx: Float, my: Float, mz: Float, frame: Frame, out: DoubleArray) {
        val ax = -mx * frame.sx / 16f * frame.scale
        val ay = (MODEL_OFFSET - my / 16f) * frame.sy * frame.scale
        val az = mz * frame.sz / 16f * frame.scale
        out[0] = frame.ex + ax * frame.cos - az * frame.sin
        out[1] = frame.ey + ay
        out[2] = frame.ez + ax * frame.sin + az * frame.cos
    }

    /** Lifetime in seconds of one event of [style] ([r1] is its random value in [0, 1)). */
    @JvmStatic
    fun lifeOf(style: CosmeticStyle, r1: Float): Float = when (style) {
        CosmeticStyle.FLAME_FOOTPRINTS -> 1.55f
        CosmeticStyle.FLOWER_STEPS -> 2.3f
        CosmeticStyle.SPARKLE_TRAIL -> 0.85f + 0.45f * r1
        CosmeticStyle.BUBBLE_TRAIL -> 1.3f + 0.7f * r1
        CosmeticStyle.ENDER_SPARKS -> 0.9f + 0.6f * r1
        else -> 1.7f
    }

    // ---- live ----------------------------------------------------------------------------------

    private val scratch = FloatArray(3)

    /**
     * Adds the placements of [track] (already updated for this frame) to [out]. [motion] gives the integrated
     * animation phase; the speed setting scales it (0 freezes the twinkle, flicker and wobble).
     */
    @JvmStatic
    fun live(piece: CosmeticPiece, track: TrailHistory.Track, motion: CosmeticRig.Motion, frame: Frame, out: MutableList<CosmeticRig.Placement>) {
        val phase = motion.swayPhase * piece.speed
        if (piece.style == CosmeticStyle.RAINBOW_RIBBON) {
            ribbon(piece, track, frame, out)
            return
        }
        var added = 0
        // Oldest first: newer sprites draw over older ones.
        for (i in 0 until track.size) {
            if (added >= MAX_PLACEMENTS) break
            val p = track.at(i)
            val age = (frame.seconds - track.birth[p]).toFloat()
            val life = lifeOf(piece.style, track.r1[p])
            if (age < 0f || age >= life) continue
            if (particle(piece, track, p, age, life, phase, frame, out)) added++
        }
    }

    private fun particle(
        piece: CosmeticPiece, track: TrailHistory.Track, p: Int, age: Float, life: Float, phase: Float,
        frame: Frame, out: MutableList<CosmeticRig.Placement>,
    ): Boolean {
        val u = age / life
        val r1 = track.r1[p]
        val r2 = track.r2[p]
        val r3 = track.r3[p]
        var wx = track.x[p]
        var wy = track.y[p]
        var wz = track.z[p]
        val mesh: VoxelMesh
        val kind: CosmeticRig.Kind
        var alpha = 255
        var scaleX = piece.size
        var scaleY = piece.size
        when (piece.style) {
            CosmeticStyle.FLAME_FOOTPRINTS -> {
                val f = when {
                    u < 0.4f -> if (sin(phase * 7f + r2 * TAU) > 0f) 1 else 0
                    u < 0.55f -> 2
                    u < 0.7f -> 3
                    u < 0.85f -> 4
                    else -> 5
                }
                mesh = TrailSprites.FLAME[f]
                kind = CosmeticRig.Kind.GLOW
                val grow = min(1f, age / 0.09f)
                scaleX *= 0.7f + 0.3f * grow
                scaleY *= 0.55f + 0.45f * grow
                if (u > 0.9f) alpha = (255 * (1f - (u - 0.9f) / 0.1f)).toInt()
            }
            CosmeticStyle.FLOWER_STEPS -> {
                val f = when {
                    u < 0.55f -> 0
                    u < 0.68f -> 1
                    u < 0.78f -> 2
                    u < 0.88f -> 3
                    u < 0.95f -> 4
                    else -> 5
                }
                mesh = TrailSprites.FLOWER[f]
                kind = CosmeticRig.Kind.SOLID
                val grow = easeOutBack(min(1f, age / 0.24f))
                scaleX *= 0.6f + 0.4f * grow
                scaleY *= max(0.05f, grow)
            }
            CosmeticStyle.SPARKLE_TRAIL -> {
                mesh = TrailSprites.SPARKLE[min(TrailSprites.FRAMES - 1, (u * TrailSprites.FRAMES).toInt())]
                kind = CosmeticRig.Kind.GLOW
                wx += (r2 - 0.5f) * 0.7f
                wz += (r3 - 0.5f) * 0.7f
                wy += 0.15f + fract(r1 * 7.31f) * 1.45f + age * 0.25f
                val twinkle = 0.82f + 0.22f * abs(sin(phase * 3f + r2 * TAU))
                scaleX *= twinkle
                scaleY *= twinkle
            }
            CosmeticStyle.BUBBLE_TRAIL -> {
                val f = when {
                    u < 0.3f -> 0
                    u < 0.55f -> 1
                    u < 0.78f -> 2
                    u < 0.88f -> 3
                    u < 0.95f -> 4
                    else -> 5
                }
                mesh = TrailSprites.BUBBLE[f]
                kind = CosmeticRig.Kind.TRANSLUCENT
                alpha = 205
                wx += (r2 - 0.5f) * 0.6f + sin(phase * 3f + r3 * TAU) * 0.05f
                wz += (r3 - 0.5f) * 0.6f + cos(phase * 3f + r2 * TAU) * 0.05f
                wy += 0.1f + r3 * 0.6f + age * (0.45f + 0.15f * r2)
                val size = 0.8f + 0.5f * r2
                scaleX *= size
                scaleY *= size
            }
            CosmeticStyle.ENDER_SPARKS -> {
                mesh = TrailSprites.ENDER[min(TrailSprites.FRAMES - 1, (u * TrailSprites.FRAMES).toInt())]
                kind = CosmeticRig.Kind.GLOW
                wx += (r2 - 0.5f) * 0.8f + (r3 - 0.5f) * 0.5f * age
                wz += (fract(r1 * 3.1f) - 0.5f) * 0.8f + (r2 - 0.5f) * 0.5f * age
                wy += 0.1f + fract(r1 * 5.3f) * 1.6f + (r3 - 0.35f) * 0.45f * age
                val blink = 0.6f + 0.4f * sin(phase * 5f + r3 * TAU)
                alpha = (255 * blink).toInt().coerceIn(60, 255)
            }
            else -> return false
        }
        toModel(wx, wy, wz, frame, scratch)
        val psi = facing(wx, wy, wz, frame)
        val m = Matrix4f().translate(scratch[0], scratch[1] - piece.up, scratch[2]).rotateY(psi).scale(scaleX, scaleY, piece.size)
        out.add(CosmeticRig.Placement(CosmeticRig.Anchor.WORLD, mesh, m, piece.color, piece.color2, kind, alpha.coerceIn(0, 255)))
        return true
    }

    /** Y rotation (radians, model space) that turns a sprite's front (-z) toward the camera, or toward the back with none. */
    private fun facing(wx: Double, wy: Double, wz: Double, frame: Frame): Float {
        if (!frame.hasCamera) return PI.toFloat()
        val dx = (frame.cx - wx).toFloat()
        val dz = (frame.cz - wz).toFloat()
        val ax = dx * frame.cos + dz * frame.sin
        val az = -dx * frame.sin + dz * frame.cos
        val mx = -ax / frame.sx
        val mz = az / frame.sz
        if (mx * mx + mz * mz < 1e-8f) return PI.toFloat()
        return atan2(-mx, -mz)
    }

    // ---- ribbon --------------------------------------------------------------------------------

    private var bandKey = -1L
    private var bandColors = IntArray(TrailSprites.BANDS)

    private fun bands(color: Int, color2: Int): IntArray = synchronized(this) {
        val key = (color.toLong() shl 24) or (color2.toLong() and 0xFFFFFF)
        if (key != bandKey) {
            bandColors = TrailSprites.bandColors(color, color2)
            bandKey = key
        }
        bandColors
    }

    private val points = FloatArray((TrailHistory.CAPACITY + 1) * 3)
    private val pointAge = FloatArray(TrailHistory.CAPACITY + 1)
    private val pointSerial = IntArray(TrailHistory.CAPACITY + 1)

    /**
     * A continuous strip of colored bands from the player's back along the walked path. Segments lose bands as they
     * age (each band at its own moment, so the tail breaks into loose pixels like the sheet) and the tail is gone
     * when the oldest sample expires.
     */
    private fun ribbon(piece: CosmeticPiece, track: TrailHistory.Track, frame: Frame, out: MutableList<CosmeticRig.Placement>) {
        val life = lifeOf(piece.style, 0f)
        val colors = bands(piece.color, piece.color2)
        // Head: the player's own back, then the samples from newest to oldest.
        var count = 0
        points[0] = 0f
        points[1] = 11f - piece.up
        points[2] = RIBBON_START_Z + piece.back
        pointAge[0] = 0f
        pointSerial[0] = -1
        count = 1
        for (i in track.size - 1 downTo 0) {
            val p = track.at(i)
            val age = (frame.seconds - track.birth[p]).toFloat()
            if (age < 0f) continue
            if (age >= life) break
            toModel(track.x[p], track.y[p] + RIBBON_HEIGHT, track.z[p], frame, scratch)
            points[count * 3] = scratch[0]
            points[count * 3 + 1] = scratch[1] - piece.up
            points[count * 3 + 2] = scratch[2]
            pointAge[count] = age
            pointSerial[count] = track.serial[p]
            count++
        }
        var added = 0
        for (k in 0 until count - 1) {
            if (added >= MAX_PLACEMENTS) break
            // Segment from the older point B back to the newer point A (A is nearer the player).
            val ax = points[k * 3]; val ay = points[k * 3 + 1]; val az = points[k * 3 + 2]
            val bx = points[k * 3 + 3]; val by = points[k * 3 + 4]; val bz = points[k * 3 + 5]
            val vx = ax - bx; val vy = ay - by; val vz = az - bz
            val horizontal = sqrt(vx * vx + vz * vz)
            val length = sqrt(horizontal * horizontal + vy * vy)
            if (length < 0.05f) continue
            val u = (pointAge[k] + pointAge[k + 1]) / 2f / life
            val serial = pointSerial[k + 1]
            var mask = 0
            for (band in 0 until TrailSprites.BANDS) {
                val gone = dissolveAt(serial, band)
                if (u < gone) mask = mask or (1 shl band)
                else if (u < gone + DEBRIS_SPAN && added < MAX_PLACEMENTS) {
                    added += debris(piece, colors, band, serial, (u - gone) / DEBRIS_SPAN, bx, by, bz, vx, vy, vz, out)
                }
            }
            if (mask == 0) continue
            val m = Matrix4f().translate(bx, by, bz).rotateY(atan2(vx, vz)).rotateX(-atan2(vy, horizontal))
                .scale(piece.size, piece.size, length + 0.7f)
            out.add(CosmeticRig.Placement(CosmeticRig.Anchor.WORLD, TrailSprites.ribbonCell(piece.color, piece.color2, mask), m,
                piece.color, piece.color2))
            added++
        }
    }

    private const val DEBRIS_SPAN = 0.2f

    /** The age fraction at which [band] of the segment with this sample [serial] breaks away. */
    @JvmStatic
    fun dissolveAt(serial: Int, band: Int): Float = 0.42f + 0.5f * TrailHistory.rand(serial, band, 7)

    private fun debris(
        piece: CosmeticPiece, colors: IntArray, band: Int, serial: Int, q: Float,
        bx: Float, by: Float, bz: Float, vx: Float, vy: Float, vz: Float, out: MutableList<CosmeticRig.Placement>,
    ): Int {
        val center = -TrailSprites.BANDS * TrailSprites.BAND_HEIGHT / 2f + (band + 0.5f) * TrailSprites.BAND_HEIGHT
        val along = TrailHistory.rand(serial, band, 8)
        val x = bx + vx * along + (TrailHistory.rand(serial, band, 9) - 0.5f) * 5f * q
        val y = by + vy * along + center * piece.size - q * (2f + 5f * TrailHistory.rand(serial, band, 10))
        val z = bz + vz * along + q * 3f
        val size = (1f - q) * 1.1f * piece.size
        if (size < 0.1f) return 0
        val m = Matrix4f().translate(x, y, z).scale(size).translate(-0.5f, -0.5f, -0.5f)
        out.add(CosmeticRig.Placement(CosmeticRig.Anchor.WORLD, CosmeticModels.CUBE, m, colors[band], colors[band]))
        return 1
    }

    // ---- previews ------------------------------------------------------------------------------

    /** Walking speed of the synthetic preview walk, blocks per second. */
    private const val PREVIEW_SPEED = 2.0

    /**
     * A trail for pictures (the menu card and the offline previews): the player walked straight ahead for a few
     * seconds, simulated through the real history code at 30 steps a second, so a card shows exactly what the
     * game spawns. The player faces -z in the picture and the trail runs out behind (+z).
     */
    @JvmStatic
    fun preview(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val seconds = motion.seconds.toDouble()
        val track = TrailHistory.Track()
        val spec = TrailHistory.spec(piece.style)
        track.style = piece.style
        val start = seconds - spec.maxLife - 0.4
        var t = start
        // World: the player faces +z (body yaw 0) and walks that way; at [seconds] it stands at the origin.
        while (t <= seconds + 1e-9) {
            track.move(piece.style, t, 0.0, 0.0, PREVIEW_SPEED * (t - seconds), true, motion.seed, piece.back / 16f)
            t += 1.0 / 30.0
        }
        val frame = Frame().set(0.0, 0.0, 0.0, 0f, 1f, 1f, 1f, 1f, seconds)
        live(piece, track, motion, frame, out)
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.9f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    private fun fract(value: Float): Float = value - floor(value)
}
