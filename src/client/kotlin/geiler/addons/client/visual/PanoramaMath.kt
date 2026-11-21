package geiler.addons.client.visual

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure maths behind the Custom Main Menu desert: the noise texture, a CPU copy of the shader's dune
 * height, the looping camera flight, the render-target size and the uniform block handed to
 * `panorama_desert.fsh`. The shader does the per-pixel work; this owns everything that is the same for
 * every pixel, so it can be checked offline.
 */
object PanoramaMath {
    /** One full flight; the path and time repeat after this, so float time stays precise. */
    const val LOOP_SECONDS = 360.0
    /** Thirteen vec4s, in the order `PanoramaParams` declares them. */
    const val PARAM_FLOATS = 52
    const val MAX_BANK = 0.08f
    const val MAX_TARGET_SIDE = 4096
    const val MARCH_STEPS = 160
    const val NOISE_SIZE = 256
    /** No dune reaches this; the shader stops a rising ray above it. Must match `MAX_HEIGHT` in the shader. */
    const val MAX_TERRAIN_HEIGHT = 120f
    const val MIN_DUNE_HEIGHT = 0.5f
    const val MAX_DUNE_HEIGHT = 1.5f

    /**
     * How the dune fields are mixed and scaled. The four amplitudes scale the long ridges, the transverse waves, the star
     * dunes and the broad swells; the biases shift how much of the land is ridges or star dunes; [height] scales every dune.
     * [DEFAULT] is the original terrain exactly.
     */
    data class DuneStyle(
        val ridge: Double, val wave: Double, val star: Double, val swell: Double,
        val linearBias: Double, val starBias: Double, val height: Double,
    ) {
        companion object {
            val DEFAULT = DuneStyle(1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0)
        }
    }

    /** The four colours the desert takes from settings (ARGB, sRGB) and the dune style. */
    data class DesertLook(val sandDark: Int, val sandLight: Int, val sky: Int, val sunset: Int, val style: DuneStyle) {
        companion object {
            /** The original golden-hour colours, written as sRGB so the shader's linear constants come out unchanged. */
            val DEFAULT = DesertLook(0xFFD3AE8D.toInt(), 0xFFECCDA8.toInt(), 0xFF688DC2.toInt(), 0xFFFFD09C.toInt(), DuneStyle.DEFAULT)
        }
    }

    /** Setting values of the dune style choice, the first one being the original look. */
    @JvmField
    val DUNE_STYLES: List<String> = listOf("Mixed", "Gentle", "Towering", "Long ridges", "Star dunes", "Rolling")

    /** The style for a settings [choice] and a [height] multiplier; an unknown choice is Mixed. */
    @JvmStatic
    fun duneStyle(choice: String?, height: Float): DuneStyle {
        val scale = (if (height.isFinite()) height else 1f).toDouble().coerceIn(MIN_DUNE_HEIGHT.toDouble(), MAX_DUNE_HEIGHT.toDouble())
        val base = when (choice) {
            "Gentle" -> DuneStyle(0.55, 0.8, 0.35, 0.7, 0.0, 0.0, 1.0)
            "Towering" -> DuneStyle(1.35, 1.2, 1.4, 1.1, 0.0, 0.0, 1.0)
            "Long ridges" -> DuneStyle(1.25, 0.8, 0.4, 1.0, 0.3, -0.3, 1.0)
            "Star dunes" -> DuneStyle(0.8, 0.8, 1.35, 1.0, -0.2, 0.3, 1.0)
            "Rolling" -> DuneStyle(0.5, 1.5, 0.3, 1.0, -0.3, -0.3, 1.0)
            else -> DuneStyle.DEFAULT
        }
        return base.copy(height = scale)
    }
    /** The camera never comes closer than this to the sand straight below it. */
    const val MIN_CLEARANCE = 4f

    private const val NOISE_SEED = 0x6E1L
    private const val OMEGA = 2.0 * PI / LOOP_SECONDS
    private const val FOCAL = 1.6f
    // Low golden-hour sun ahead and to the side of the flight's start, so dunes cast long shadows.
    private val LIGHT = normalize(-0.62f, 0.13f, -0.77f)

    /** Prevailing wind (transverse dunes face it) and the cross direction long ridges run across. */
    private const val WIND_X = 0.2425
    private const val WIND_Z = 0.9701
    private const val CROSS_X = 0.9435
    private const val CROSS_Z = -0.3313

    /** ARGB texels of the noise texture; all four channels are independent, so the shader gets four noises. */
    @JvmStatic
    fun noiseTexels(): IntArray = TEXELS.copyOf()

    private val TEXELS: IntArray by lazy {
        val random = java.util.Random(NOISE_SEED)
        IntArray(NOISE_SIZE * NOISE_SIZE) { random.nextInt() }
    }

    /** Channel values 0..1 in shader order r, g, b, a, four per texel. */
    private val CHANNELS: DoubleArray by lazy {
        val out = DoubleArray(TEXELS.size * 4)
        for ((i, argb) in TEXELS.withIndex()) {
            out[i * 4] = ((argb ushr 16) and 0xFF) / 255.0
            out[i * 4 + 1] = ((argb ushr 8) and 0xFF) / 255.0
            out[i * 4 + 2] = (argb and 0xFF) / 255.0
            out[i * 4 + 3] = (argb ushr 24) / 255.0
        }
        out
    }

    /**
     * Height of the sand at ([x], [z]). A line-for-line copy of `terrain()` in `panorama_desert.fsh`
     * (which runs in float); the camera uses it to fly over the dunes it is drawing.
     */
    @JvmStatic
    @JvmOverloads
    fun terrainHeight(x: Double, z: Double, style: DuneStyle = DuneStyle.DEFAULT): Double {
        val n = DoubleArray(4)
        val m = DoubleArray(4)
        noise4(x * 0.0016 + 3.1, z * 0.0016 + 7.4, n)
        noise4(x * 0.007 + 9.2, z * 0.007 + 1.7, m)
        val ridges = duneProfile(x, z, CROSS_X, CROSS_Z, 0.0085, m[0] * 1.3) * (14.0 + 18.0 * m[2]) * style.ridge
        val waves = duneProfile(x, z, WIND_X, WIND_Z, 0.021, m[1] * 2.0 + n[3] * 1.2) * (5.0 + 9.0 * m[3]) * style.wave
        val star = (1.0 - abs(m[0] * 2.0 - 1.0)).pow(6.0) * 36.0 * style.star
        val linearWeight = smoothstep(0.38, 0.62, n[0] + style.linearBias)
        val starWeight = smoothstep(0.52, 0.72, n[1] + style.starBias)
        var dunes = mix(waves, smax(waves * 0.5, ridges, 5.0), linearWeight)
        dunes = mix(dunes, smax(dunes * 0.6, star, 6.0), starWeight)
        dunes *= 1.0 - 0.7 * smoothstep(0.6, 0.75, n[2])
        val swell = ((n[3] - 0.5) * 40.0 + (m[3] - 0.5) * 8.0) * style.swell
        return swell + dunes * style.height
    }

    private fun noise4(x: Double, y: Double, out: DoubleArray) {
        val px = floor(x)
        val py = floor(y)
        var fx = x - px
        var fy = y - py
        fx = fx * fx * (3.0 - 2.0 * fx)
        fy = fy * fy * (3.0 - 2.0 * fy)
        val ix = Math.floorMod(px.toInt(), NOISE_SIZE)
        val iy = Math.floorMod(py.toInt(), NOISE_SIZE)
        val jx = (ix + 1) and (NOISE_SIZE - 1)
        val jy = (iy + 1) and (NOISE_SIZE - 1)
        val c = CHANNELS
        for (k in 0..3) {
            val a = c[(iy * NOISE_SIZE + ix) * 4 + k]
            val b = c[(iy * NOISE_SIZE + jx) * 4 + k]
            val cc = c[(jy * NOISE_SIZE + ix) * 4 + k]
            val d = c[(jy * NOISE_SIZE + jx) * 4 + k]
            out[k] = mix(mix(a, b, fx), mix(cc, d, fx), fy)
        }
    }

    private fun duneProfile(x: Double, z: Double, dx: Double, dz: Double, freq: Double, warp: Double): Double {
        val v = (x * dx + z * dz) * freq + warp
        val f = v - floor(v)
        val profile = if (f < 0.75) f / 0.75 else (1.0 - f) / 0.25
        return profile * profile * (1.5 - 0.5 * profile)
    }

    private fun smax(a: Double, b: Double, k: Double): Double {
        val h = ((0.5 + 0.5 * (a - b) / k)).coerceIn(0.0, 1.0)
        return mix(b, a, h) + k * h * (1.0 - h)
    }

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t

    @JvmStatic
    fun loopTime(seconds: Double): Float {
        if (!seconds.isFinite()) return 0f
        val wrapped = seconds % LOOP_SECONDS
        return (if (wrapped < 0) wrapped + LOOP_SECONDS else wrapped).toFloat()
    }

    @JvmStatic
    fun resolutionScale(choice: String?): Float = when (choice) {
        "Performance" -> 0.5f
        "Quality" -> 1f
        else -> 0.75f
    }

    @JvmStatic
    fun targetSize(windowSide: Int, scale: Float): Int =
        (windowSide * scale).roundToInt().coerceIn(1, MAX_TARGET_SIDE)

    /** Ground position of the flight: a wide meandering loop through several dune fields. */
    private fun pathX(t: Double) = 1250.0 * cos(OMEGA * t) + 180.0 * sin(3.0 * OMEGA * t)
    private fun pathZ(t: Double) = 900.0 * sin(OMEGA * t) + 160.0 * cos(2.0 * OMEGA * t)

    private const val TABLE_STEP = 0.25
    private val TABLE_SIZE = (LOOP_SECONDS / TABLE_STEP).toInt()

    /**
     * Camera height at each table step. Each step takes the highest sand under and beside the path from
     * two seconds back to five ahead, then a wide Gaussian smooths that envelope, so the flight glides over
     * the dunes instead of bobbing over every crest. Depends only on time, so the loop stays seamless.
     */
    private val ALTITUDES = HashMap<DuneStyle, DoubleArray>()

    /** The table of [style], built on first use; the menu draws on the render thread only. */
    @Synchronized
    private fun altitudeTable(style: DuneStyle): DoubleArray = ALTITUDES.getOrPut(style) { buildAltitude(style) }

    private fun buildAltitude(style: DuneStyle): DoubleArray {
        val raw = DoubleArray(TABLE_SIZE) { i ->
            val t = i * TABLE_STEP
            val x = pathX(t)
            val z = pathZ(t)
            val vx = pathX(t + 0.1) - pathX(t - 0.1)
            val vz = pathZ(t + 0.1) - pathZ(t - 0.1)
            val len = hypot(vx, vz).coerceAtLeast(1e-6)
            val sx = -vz / len * 10.0
            val sz = vx / len * 10.0
            maxOf(terrainHeight(x, z, style), terrainHeight(x + sx, z + sz, style), terrainHeight(x - sx, z - sz, style))
        }
        val envelope = DoubleArray(TABLE_SIZE) { i ->
            var best = -Double.MAX_VALUE
            for (k in -8..20) best = maxOf(best, raw[Math.floorMod(i + k, TABLE_SIZE)])
            best
        }
        val sigma = 20.0
        val reach = 60
        val weights = DoubleArray(2 * reach + 1) { exp(-((it - reach) * (it - reach)) / (2 * sigma * sigma)) }
        val total = weights.sum()
        fun smooth(values: DoubleArray) = DoubleArray(TABLE_SIZE) { i ->
            var sum = 0.0
            for (k in -reach..reach) sum += weights[k + reach] * values[Math.floorMod(i + k, TABLE_SIZE)]
            sum / total
        }
        val smoothed = smooth(envelope)
        // Smoothing undercuts sharp peaks. Lift by the shortfall: a max over the kernel's reach, smoothed by
        // the same kernel, is at least the shortfall at every point, so the lift is smooth and sufficient.
        val shortfall = DoubleArray(TABLE_SIZE) { i -> maxOf(0.0, envelope[i] - smoothed[i]) }
        val widened = DoubleArray(TABLE_SIZE) { i ->
            var best = 0.0
            for (k in -reach..reach) best = maxOf(best, shortfall[Math.floorMod(i + k, TABLE_SIZE)])
            best
        }
        val lift = smooth(widened)
        return DoubleArray(TABLE_SIZE) { i -> smoothed[i] + lift[i] + CLEARANCE }
    }
    private const val CLEARANCE = 11.0

    /**
     * Camera at [seconds]: x, z, view direction x, z (normalised), bank, height. The view follows the path
     * a moment ahead with a slow sway, so it explores the dunes rather than staring at one spot.
     */
    @JvmStatic
    @JvmOverloads
    fun camera(seconds: Double, style: DuneStyle = DuneStyle.DEFAULT): FloatArray {
        val t = loopTime(seconds).toDouble()
        val h = 0.05
        val x = pathX(t)
        val z = pathZ(t)
        val ahead = t + 2.0
        val vx = (pathX(ahead + h) - pathX(ahead - h)) / (2 * h)
        val vz = (pathZ(ahead + h) - pathZ(ahead - h)) / (2 * h)
        val sway = 0.16 * sin(6.0 * OMEGA * t) + 0.06 * sin(11.0 * OMEGA * t)
        val heading = atan2(vz, vx) + sway
        // Lean into the turn: signed curvature of the ground path.
        val cvx = (pathX(t + h) - pathX(t - h)) / (2 * h)
        val cvz = (pathZ(t + h) - pathZ(t - h)) / (2 * h)
        val ax = (pathX(t + h) - 2 * x + pathX(t - h)) / (h * h)
        val az = (pathZ(t + h) - 2 * z + pathZ(t - h)) / (h * h)
        val speed = hypot(cvx, cvz).coerceAtLeast(1e-6)
        val curvature = (cvx * az - cvz * ax) / (speed * speed * speed)
        val bank = (curvature * 18.0).toFloat().coerceIn(-MAX_BANK, MAX_BANK)
        return floatArrayOf(x.toFloat(), z.toFloat(), cos(heading).toFloat(), sin(heading).toFloat(), bank, altitude(t, style).toFloat())
    }

    private fun altitude(t: Double, style: DuneStyle): Double {
        val table = altitudeTable(style)
        val f = t / TABLE_STEP
        val i = floor(f).toInt()
        val a = table[Math.floorMod(i, TABLE_SIZE)]
        val b = table[Math.floorMod(i + 1, TABLE_SIZE)]
        return mix(a, b, f - i)
    }

    /** Fills [out] with the `PanoramaParams` block; colours are ARGB from the theme. */
    @JvmStatic
    fun writeParams(
        out: FloatArray, time: Float, aspect: Float, steps: Int, targetHeight: Int, camera: FloatArray,
        accent: Int, background: Int, text: Int, look: DesertLook = DesertLook.DEFAULT,
    ) {
        require(out.size >= PARAM_FLOATS && camera.size >= 6)
        out.fill(0f, 0, PARAM_FLOATS)
        out[0] = time; out[1] = aspect; out[2] = 2f / targetHeight.coerceAtLeast(1); out[3] = steps.toFloat()
        out[4] = camera[0]; out[5] = camera[1]; out[6] = camera[2]; out[7] = camera[3]
        out[8] = camera[4]; out[9] = FOCAL; out[10] = camera[5]
        out[12] = LIGHT[0]; out[13] = LIGHT[1]; out[14] = LIGHT[2]
        rgb(accent, out, 16)
        rgb(background, out, 20)
        rgb(text, out, 24)
        rgb(look.sandDark, out, 28)
        rgb(look.sandLight, out, 32)
        rgb(look.sky, out, 36)
        rgb(look.sunset, out, 40)
        val s = look.style
        out[44] = s.ridge.toFloat(); out[45] = s.wave.toFloat(); out[46] = s.star.toFloat(); out[47] = s.swell.toFloat()
        out[48] = s.linearBias.toFloat(); out[49] = s.starBias.toFloat(); out[50] = s.height.toFloat()
    }

    /**
     * UV rectangle (u0, u1, v0, v1) that covers a [screenW]x[screenH] area with the photo without
     * stretching. [zoom] >= 1 crops further; [panX]/[panY] in -1..1 slide inside the spare margin.
     */
    @JvmStatic
    fun coverUv(imageW: Int, imageH: Int, screenW: Int, screenH: Int, zoom: Float, panX: Float, panY: Float): FloatArray {
        if (imageW <= 0 || imageH <= 0 || screenW <= 0 || screenH <= 0) return floatArrayOf(0f, 1f, 0f, 1f)
        val imageAspect = imageW.toFloat() / imageH
        val screenAspect = screenW.toFloat() / screenH
        var w = 1f
        var h = 1f
        if (imageAspect > screenAspect) w = screenAspect / imageAspect else h = imageAspect / screenAspect
        val z = zoom.coerceAtLeast(1f)
        w /= z
        h /= z
        val cx = 0.5f + panX.coerceIn(-1f, 1f) * (1f - w) / 2f
        val cy = 0.5f + panY.coerceIn(-1f, 1f) * (1f - h) / 2f
        return floatArrayOf(cx - w / 2f, cx + w / 2f, cy - h / 2f, cy + h / 2f)
    }

    private fun rgb(argb: Int, out: FloatArray, at: Int) {
        out[at] = ((argb ushr 16) and 0xFF) / 255f
        out[at + 1] = ((argb ushr 8) and 0xFF) / 255f
        out[at + 2] = (argb and 0xFF) / 255f
        out[at + 3] = 1f
    }

    private fun normalize(x: Float, y: Float, z: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / l, y / l, z / l)
    }
}
