package geiler.addons.client.cosmetics

import geiler.addons.client.cosmetics.CosmeticRig.Anchor
import geiler.addons.client.cosmetics.CosmeticRig.Kind
import geiler.addons.client.cosmetics.CosmeticRig.Motion
import geiler.addons.client.cosmetics.CosmeticRig.Placement
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Places and animates the FACE and BACK slots. Every animation is driven by the tracker's integrated
 * [Motion.swayPhase] (a step-like cycle: about 1 Hz standing, 1.8 Hz moving) scaled by the piece's speed, or by
 * the entity clock for rare events (the sunglasses glint), and offset per player by [Motion.seed], so nothing
 * jumps when the player starts, stops or jumps. Speed 0 freezes every animation and ignores walking and jumping.
 */
object CosmeticRigC {
    private const val TAU = (2.0 * PI).toFloat()

    @JvmStatic
    fun face(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        when (piece.style) {
            CosmeticStyle.SUNGLASSES -> sunglasses(piece, motion, out)
            CosmeticStyle.FOX_MASK -> foxMask(piece, motion, out)
            CosmeticStyle.PLAGUE_MASK -> plagueMask(piece, motion, out)
            else -> bandana(piece, motion, out)
        }
    }

    @JvmStatic
    fun back(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        when (piece.style) {
            CosmeticStyle.BACKPACK -> backpack(piece, motion, out)
            CosmeticStyle.KATANA -> katana(piece, motion, out)
            CosmeticStyle.JETPACK -> jetpack(piece, motion, out)
            CosmeticStyle.TURTLE_SHELL -> turtleShell(piece, motion, out)
            else -> guitar(piece, motion, out)
        }
    }

    // ---- shared motion terms --------------------------------------------------------------------

    private fun seedPhase(seed: Int): Float = ((seed * -1640531527 + 12345) ushr 16) / 65536f * TAU

    /** Integrated step phase for this piece (radians); constant when the speed is 0. */
    private fun phase(motion: Motion, piece: CosmeticPiece, offset: Float = 0f): Float =
        motion.swayPhase * piece.speed + seedPhase(motion.seed) + offset

    /** Walking and jumping only animate a piece whose speed is above 0. */
    private fun live(piece: CosmeticPiece): Float = if (piece.speed > 0f) 1f else 0f

    private fun fract(v: Float): Float = v - floor(v)

    private fun solid(anchor: Anchor, mesh: VoxelMesh, m: Matrix4f, piece: CosmeticPiece, out: MutableList<Placement>) {
        out.add(Placement(anchor, mesh, m, piece.color, piece.color2))
    }

    // ---- face pieces: origin at the head center ---------------------------------------------------

    private fun faceRoot(piece: CosmeticPiece): Matrix4f =
        Matrix4f().translate(0f, -4f - piece.up, piece.back).scale(piece.size)

    /** A glint sweeps over each lens every few seconds, the second lens a moment after the first. */
    private fun sunglasses(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val root = faceRoot(piece)
        solid(Anchor.HEAD, CosmeticModelsC.SUNGLASSES, root, piece, out)
        if (piece.speed <= 0f) return
        val period = 5.2f
        val local = motion.seconds * piece.speed + seedPhase(motion.seed) / TAU * period
        val t = local - floor(local / period) * period
        for (eye in 0..1) {
            val u = (t - eye * 0.3f) / 0.8f
            if (u <= 0f || u >= 1f) continue
            val left = if (eye == 0) -4.0f else 0.5f
            val alpha = (210f * sin(u * PI.toFloat())).toInt()
            val m = Matrix4f(root).translate(left + u * 1.5f, -1f, -5.5f)
            out.add(Placement(Anchor.HEAD, CosmeticModelsC.GLINT, m, 0xFFFFFF, 0xFFFFFF, Kind.GLOW, alpha))
        }
    }

    /** The mask hangs slightly tilted and sways a little with the head; the ears twitch now and then. */
    private fun foxMask(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val mv = motion.moving * live(piece)
        val air = motion.air * live(piece)
        val ph = phase(motion, piece)
        val tilt = 0.1f + sin(ph * 0.5f) * (0.01f + 0.028f * mv) + sin(ph - 0.8f) * 0.02f * mv + air * 0.03f
        val bob = sin(ph - 0.4f) * 0.12f * mv
        val sway = Matrix4f(faceRoot(piece)).translate(0f, bob, 0f)
            .translate(0f, -4.5f, -5f).rotateZ(tilt).translate(0f, 4.5f, 5f)
        solid(Anchor.HEAD, CosmeticModelsC.FOX_MASK, Matrix4f(sway), piece, out)
        for ((ear, side) in intArrayOf(1, -1).withIndex()) {
            val twitch = CosmeticRig.earTwitch(motion.seconds, motion.seed, ear, piece.speed)
            val m = Matrix4f(sway).scale(side.toFloat(), 1f, 1f)
                .translate(4f, -4.6f, -4.5f).rotateZ(0.1f + twitch * 0.35f).rotateX(twitch * -0.3f)
                .translate(-4f, 4.6f, 4.5f)
            solid(Anchor.HEAD, CosmeticModelsC.FOX_EAR, m, piece, out)
        }
    }

    /** The beak nods with every step. */
    private fun plagueMask(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val mv = motion.moving * live(piece)
        val air = motion.air * live(piece)
        val ph = phase(motion, piece)
        val root = faceRoot(piece)
        solid(Anchor.HEAD, CosmeticModelsC.PLAGUE_MASK, Matrix4f(root), piece, out)
        val nod = sin(ph * 0.4f) * 0.025f * (1f - mv) + sin(ph - 0.5f) * 0.07f * mv + air * 0.05f
        val m = Matrix4f(root).translate(0f, 2.8f, -6.5f).rotateX(nod).translate(0f, -2.8f, 6.5f)
        solid(Anchor.HEAD, CosmeticModelsC.PLAGUE_BEAK, m, piece, out)
    }

    /** The two loose ends behind the knot flutter, more when moving, and stream back in the air. */
    private fun bandana(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val mv = motion.moving * live(piece)
        val air = motion.air * live(piece)
        val ph = phase(motion, piece)
        val root = faceRoot(piece)
        solid(Anchor.HEAD, CosmeticModelsC.BANDANA, Matrix4f(root), piece, out)
        val lift = 0.12f + mv * 0.3f + air * 0.25f
        for ((i, side) in intArrayOf(1, -1).withIndex()) {
            val flutterX = sin(ph * 2.0f + i * 1.7f) * (0.05f + mv * 0.15f)
            val flutterZ = sin(ph * 1.5f + i * 2.9f) * (0.05f + mv * 0.1f)
            val m = Matrix4f(root).translate(0f, -0.7f, 5.5f).scale(side.toFloat(), if (i == 0) 1f else 0.85f, 1f)
                .rotateZ(0.3f + flutterZ).rotateX(lift + flutterX)
            solid(Anchor.HEAD, CosmeticModelsC.BANDANA_TAIL, m, piece, out)
        }
    }

    // ---- back pieces: origin at the shoulders, z = 0 on the item's front plane ---------------------------

    private fun mount(piece: CosmeticPiece): Matrix4f =
        Matrix4f().translate(0f, -piece.up, CosmeticModelsC.BACK_MOUNT + piece.back).scale(piece.size)

    /** The pack bounces and lags with the steps; its bedroll and pocket flap move a little more. */
    private fun backpack(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val mv = motion.moving * live(piece)
        val air = motion.air * live(piece)
        val ph = phase(motion, piece)
        val bounce = mv * sin(ph - 0.35f) * 0.28f + (1f - mv) * 0.05f * sin(ph * 0.4f)
        val lean = 0.03f + mv * (0.05f + 0.03f * sin(ph - 0.8f)) + air * 0.06f
        val pack = mount(piece).translate(0f, bounce, 0f).translate(0f, 1.5f, 0f).rotateX(lean).translate(0f, -1.5f, 0f)
        solid(Anchor.BODY, CosmeticModelsC.HARNESS, Matrix4f(), piece, out)
        solid(Anchor.BODY, CosmeticModelsC.BACKPACK, Matrix4f(pack), piece, out)
        solid(Anchor.BODY, CosmeticModelsC.BACKPACK_BEDROLL, Matrix4f(pack).translate(0f, 0.12f * mv * sin(ph - 0.9f), 0f), piece, out)
        val flap = 0.13f + 0.1f * mv * sin(ph - 1.1f) + air * 0.15f + 0.01f * sin(ph * 0.4f)
        val hy = CosmeticModelsC.FLAP_HINGE_Y
        val hz = CosmeticModelsC.FLAP_HINGE_Z
        solid(Anchor.BODY, CosmeticModelsC.BACKPACK_FLAP, Matrix4f(pack).translate(0f, hy, hz).rotateX(flap).translate(0f, -hy, -hz), piece, out)
    }

    /** The sheathed katana sways and rattles a little with the steps. */
    private fun katana(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val mv = motion.moving * live(piece)
        val ph = phase(motion, piece)
        val sway = sin(ph - 0.6f) * 0.035f * mv + sin(ph * 0.4f) * 0.008f * (1f - mv) + sin(ph * 2.7f) * 0.012f * mv
        val bounce = mv * sin(ph - 0.2f) * 0.15f
        val m = mount(piece).translate(0f, bounce, 0f)
            .translate(CosmeticModelsC.KATANA_X, CosmeticModelsC.KATANA_Y, 0f).rotateZ(CosmeticModelsC.KATANA_TILT + sway)
        solid(Anchor.BODY, CosmeticModelsC.KATANA, m, piece, out)
    }

    private val SMOKE = 0x9A9AA0

    /** Two tanks that shake in the air; flames flicker (small idle flame, big when airborne) with smoke puffs. */
    private fun jetpack(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val mv = motion.moving * live(piece)
        val air = motion.air * live(piece)
        val ph = phase(motion, piece)
        val base = mount(piece)
        val pack = Matrix4f(base).translate(air * sin(ph * 17f) * 0.05f, mv * sin(ph - 0.2f) * 0.2f, 0f)
        solid(Anchor.BODY, CosmeticModelsC.JETPACK, Matrix4f(pack), piece, out)
        val activity = minOf(1f, air + mv * 0.25f)
        for ((i, side) in intArrayOf(1, -1).withIndex()) {
            val flicker = 1f + 0.08f * sin(ph * 5f + i) + 0.05f * sin(ph * 8.3f + 1.3f + i * 2f) + 0.025f * sin(ph * 13.1f + 2.1f)
            val len = (1.5f + mv * 0.7f + air * 5f) * flicker
            val fat = 1f + air * 0.3f
            val origin = Matrix4f(pack).translate(side * CosmeticModelsC.NOZZLE_X, CosmeticModelsC.NOZZLE_Y, CosmeticModelsC.NOZZLE_Z)
                .rotateZ(side * 0.04f * sin(ph * 4.3f)).rotateX(0.03f * sin(ph * 3.7f + i))
            out.add(Placement(Anchor.BODY, CosmeticModelsC.FLAME_OUTER, Matrix4f(origin).scale(fat, len / 8f, fat), 0, 0, Kind.GLOW, 190))
            out.add(Placement(Anchor.BODY, CosmeticModelsC.FLAME_CORE, Matrix4f(origin).scale(fat, len * 0.65f / 5.5f, fat), 0, 0, Kind.GLOW, 235))
            if (activity < 0.04f) continue
            for (puff in 0 until 3) {
                val a = fract(ph / TAU * 0.9f + puff / 3f + i * 0.17f)
                val alpha = (80f * sin(a * PI.toFloat()) * activity).toInt()
                if (alpha < 4) continue
                val size = 1f + a * 2.2f
                val m = Matrix4f(base)
                    .translate(side * CosmeticModelsC.NOZZLE_X + sin(puff * 2.1f + a * 3f) * 0.6f,
                        CosmeticModelsC.NOZZLE_Y + len * 0.5f + a * 7f, CosmeticModelsC.NOZZLE_Z + a * (1.5f + mv * 2f))
                    .scale(size).translate(-0.5f, -0.5f, -0.5f)
                out.add(Placement(Anchor.BODY, CosmeticModels.CUBE, m, SMOKE, SMOKE, Kind.TRANSLUCENT, alpha))
            }
        }
    }

    /** A heavy shell: a slow, subtle bob. */
    private fun turtleShell(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val mv = motion.moving * live(piece)
        val air = motion.air * live(piece)
        val ph = phase(motion, piece)
        val bounce = mv * sin(ph - 0.3f) * 0.18f + (1f - mv) * 0.04f * sin(ph * 0.4f)
        val lean = 0.02f + mv * (0.025f + 0.02f * sin(ph - 0.6f)) + air * 0.04f
        val m = mount(piece).translate(0f, bounce, 0f).translate(0f, 1.5f, 0f).rotateX(lean).translate(0f, -1.5f, 0f)
        solid(Anchor.BODY, CosmeticModelsC.TURTLE_SHELL, m, piece, out)
    }

    /** The guitar hangs from its strap: it sways and swings with the steps and lags behind the body. */
    private fun guitar(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val mv = motion.moving * live(piece)
        val air = motion.air * live(piece)
        val ph = phase(motion, piece)
        val swayZ = sin(ph - 0.8f) * 0.07f * mv + sin(ph * 0.4f) * 0.012f * (1f - mv)
        val swayX = 0.06f + 0.04f * mv * sin(ph - 1.2f) + air * 0.06f
        val px = CosmeticModelsC.GUITAR_PIN_X
        val py = CosmeticModelsC.GUITAR_PIN_Y
        val m = mount(piece).translate(px, py, 0f).rotateZ(swayZ).rotateX(swayX).translate(-px, -py, 0f)
            .translate(CosmeticModelsC.GUITAR_ORIGIN_X, CosmeticModelsC.GUITAR_ORIGIN_Y, 0f).rotateZ(CosmeticModelsC.GUITAR_TILT)
        solid(Anchor.BODY, CosmeticModelsC.GUITAR_STRAP, Matrix4f(), piece, out)
        solid(Anchor.BODY, CosmeticModelsC.GUITAR, m, piece, out)
    }
}
