package geiler.addons.client.cosmetics

import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Placement of the music note, frost and cherry blossom auras. Like the other auras they are particles around the
 * whole player on the body anchor, but positions are written relative to the aura slot's tuned defaults: at the
 * default Size / Height / Depth the cloud is centered on the body and reaches from above the head to the floor.
 * Every motion is a constant rate times the animation clock (which the Speed setting scales), so it never jumps.
 */
object CosmeticRigE {
    private const val TAU = (2.0 * PI).toFloat()

    const val NOTES = 8
    const val FLAKES = 14
    const val CRYSTAL_CLUSTERS = 10
    const val GLINTS = 5
    const val PETALS = 18

    /** Most placements one of these auras can produce (glints are two each). */
    const val MAX_PLACEMENTS = FLAKES + CRYSTAL_CLUSTERS + GLINTS * 2

    @JvmStatic
    fun handles(style: CosmeticStyle): Boolean =
        style == CosmeticStyle.MUSIC_AURA || style == CosmeticStyle.FROST_AURA || style == CosmeticStyle.BLOSSOM_AURA

    @JvmStatic
    fun aura(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        when (piece.style) {
            CosmeticStyle.MUSIC_AURA -> music(piece, motion, out)
            CosmeticStyle.FROST_AURA -> frost(piece, motion, out)
            else -> blossom(piece, motion, out)
        }
    }

    private fun fract(value: Float): Float = value - floor(value)

    private fun hash(i: Int, salt: Float = 0f): Float = fract(sin(i * 12.9898f + salt * 78.233f) * 43758.547f)

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Offsets relative to the aura slot defaults: (height shift, depth shift, size ratio). */
    private fun frame(piece: CosmeticPiece): FloatArray = floatArrayOf(
        -(piece.up - CosmeticSlot.AURA.defaultUp),
        piece.back - CosmeticSlot.AURA.defaultBack,
        piece.size / CosmeticSlot.AURA.defaultSize,
    )

    private fun add(
        out: MutableList<CosmeticRig.Placement>, mesh: VoxelMesh, m: Matrix4f, color: Int, color2: Int, alpha: Int,
        kind: CosmeticRig.Kind = CosmeticRig.Kind.SOLID,
    ) {
        if (alpha <= 0) return
        val translucent = kind == CosmeticRig.Kind.SOLID && alpha < 255
        out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, mesh, m, color, color2,
            if (translucent) CosmeticRig.Kind.TRANSLUCENT else kind, alpha.coerceIn(0, 255)))
    }

    // ---- music notes --------------------------------------------------------------------------------------------

    /**
     * Notes drift around the body, rising slowly; each spins about its own vertical axis, hops on a shared beat
     * (about 100 per minute) and pops in at the bottom and fades out at the top.
     */
    private fun music(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val t = motion.seconds * piece.speed
        val (dy, dz, s) = frame(piece).let { Triple(it[0], it[1], it[2]) }
        for (i in 0 until NOTES) {
            val h = hash(i, 1f) + motion.seed * 0.013f
            val life = fract(t * 0.075f + i / NOTES.toFloat() + h * 0.4f)
            val note = CosmeticModelsE.NOTES[i % CosmeticModelsE.NOTES.size]
            val angle = i * 0.785f + t * 0.3f * (if (i % 2 == 0) 1f else -1f) + h
            val radius = (8.2f + 1.8f * sin(i * 1.9f)) * s
            val beat = (cos(t * 1.75f * TAU - i * 0.6f) * 0.5f + 0.5f).pow(6)
            val y = 12f + (21f - life * 28f - 12f) * s - beat * 1.3f
            val pop = smooth(life / 0.1f)
            val fade = smooth((1f - life) / 0.18f)
            val size = (1.5f + 0.15f * beat) * pop * s
            val m = Matrix4f()
                .translate(cos(angle) * radius, y + dy, sin(angle) * radius + dz)
                .rotateY(t * 0.9f + i * 1.1f)
                .rotateZ(sin(t * 1.3f + i) * 0.18f)
                .scale(size)
                .translate(-note.centerX, -note.centerY, 0f)
            val color = if (i % 2 == 0) piece.color else piece.color2
            add(out, note.mesh, m, color, color, (255 * fade).toInt())
        }
    }

    // ---- frost ----------------------------------------------------------------------------------------------------------

    /**
     * Snowflakes swirl down around the body, tumbling slowly, while ice crystal clusters grow out of the floor
     * around the feet, hold, shrink away and glint.
     */
    private fun frost(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val t = motion.seconds * piece.speed
        val (dy, dz, s) = frame(piece).let { Triple(it[0], it[1], it[2]) }
        for (i in 0 until FLAKES) {
            val h = hash(i, 2f) + motion.seed * 0.017f
            val life = fract(t * 0.07f + i / FLAKES.toFloat() + h * 0.3f)
            val angle = i * 0.9f + t * 0.45f * (if (i % 3 == 0) -1f else 1f) + life * 2.4f
            val radius = (6.6f + 2.6f * hash(i, 3f) + 0.7f * sin(t * 0.8f + i)) * s
            val y = 12f + (-7f + life * 31f - 12f) * s
            val fade = smooth(life / 0.1f) * smooth((1f - life) / 0.12f)
            val big = i % 2 == 0
            val m = Matrix4f()
                .translate(cos(angle) * radius, y + dy, sin(angle) * radius + dz)
                .rotateY(t * 0.6f + i)
                .rotateX(1.0f + sin(t * 0.5f + i * 1.3f) * 0.5f)
                // A few pixels across: the owner found the first flakes (11 px) as big as a forearm.
                .scale((if (big) 0.5f else 0.6f) * s)
            val color = if (i % 4 == 3) piece.color2 else piece.color
            add(out, if (big) CosmeticModelsE.FLAKE_BIG else CosmeticModelsE.FLAKE_SMALL, m, color, piece.color2, (255 * fade).toInt())
        }
        val floor = 24f + dy
        var glints = 0
        for (j in 0 until CRYSTAL_CLUSTERS) {
            val h = hash(j, 4f) + motion.seed * 0.011f
            val cycle = fract(t * 0.13f + h)
            val grow = if (cycle < 0.2f) smooth(cycle / 0.2f) else if (cycle < 0.7f) 1f else smooth((1f - cycle) / 0.3f)
            if (grow < 0.03f) continue
            val angle = j * TAU / CRYSTAL_CLUSTERS + 0.3f * (j % 2)
            val radius = (5.6f + 2.0f * hash(j, 5f)) * s
            val sx = (0.5f + 0.5f * grow) * 1.35f * s
            val m = Matrix4f()
                .translate(cos(angle) * radius, floor, sin(angle) * radius + dz)
                .rotateY(-angle + TAU / 4f)
                .rotateX(-0.14f)
                .scale(sx, grow * 1.35f * s, sx)
            add(out, CosmeticModelsE.CRYSTALS[j % 2], m, piece.color, piece.color2, 225, CosmeticRig.Kind.TRANSLUCENT)
            // A glint flashes on a grown crystal's tip now and then.
            val flash = if (grow > 0.95f) sin(((cycle - 0.2f) / 0.5f).coerceIn(0f, 1f) * TAU * 2f).coerceAtLeast(0f) else 0f
            if (j % 2 == 0 && flash > 0.15f && glints < GLINTS) {
                glints++
                val tip = Matrix4f(m).translate(0f, -7.5f, 0f)
                val core = Matrix4f(tip).scale(0.9f).translate(-0.5f, -0.5f, -0.5f)
                out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, CosmeticModels.CUBE, core, 0xFFFFFF, 0xFFFFFF,
                    CosmeticRig.Kind.GLOW, (235 * flash).toInt()))
                val shell = Matrix4f(tip).scale(2.2f).translate(-0.5f, -0.5f, -0.5f)
                out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, CosmeticModels.CUBE, shell, piece.color, piece.color,
                    CosmeticRig.Kind.GLOW, (70 * flash).toInt()))
            }
        }
    }

    // ---- cherry blossom ----------------------------------------------------------------------------------------------

    /**
     * Petals fall slowly from above the head to the floor, tumbling and swaying, fading in and out near the ground.
     * Every third one is a whole five-petal blossom so the aura reads as cherry blossom, not loose pink flakes.
     */
    private fun blossom(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val t = motion.seconds * piece.speed
        val (dy, dz, s) = frame(piece).let { Triple(it[0], it[1], it[2]) }
        for (i in 0 until PETALS) {
            val h = hash(i, 6f) + motion.seed * 0.019f
            val life = fract(t * 0.085f + i / PETALS.toFloat() + h * 0.2f)
            val angle = i * 2.4f + t * 0.15f
            val radius = (5.5f + 4.5f * hash(i, 7f)) * s
            val sway = sin(t * 0.9f + h * TAU) * 2f
            val x = cos(angle) * radius + sway
            val z = sin(angle) * radius + cos(t * 0.7f + h * 5f) * 1.5f + dz
            val y = 12f + (-9f + life * 33f - 12f) * s
            val fade = smooth(life / 0.07f) * smooth((1f - life) / 0.12f)
            val blossom = i % 3 == 0
            // Whole blossoms fall face-up and spin slowly, like real ones; loose petals tumble.
            val m = Matrix4f().translate(x, y + dy, z)
            if (blossom) {
                m.rotateY(t * 0.5f + h * 3f).rotateX(1.25f + sin(t * 1.1f + h * 5f) * 0.35f).scale(0.9f * s)
            } else {
                m.rotateX(t * 0.9f + h * 6f).rotateY(t * 0.6f + h * 3f).rotateZ(sin(t * 1.1f + h * 5f) * 0.8f).scale(1.1f * s)
            }
            val mesh = if (blossom) CosmeticModelsE.BLOSSOM else if (i % 2 == 0) CosmeticModelsE.PETAL_BIG else CosmeticModelsE.PETAL_SMALL
            add(out, mesh, m, piece.color, piece.color2, (255 * fade).toInt())
        }
    }
}
