package geiler.addons.client.cosmetics

import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Hands the hats and the extra head pieces to the shared rig; see [CosmeticRig.place]. */
fun CosmeticLoadout.withoutSlot(slot: CosmeticSlot): CosmeticLoadout =
    if (piece(slot) == null) this else CosmeticLoadout.of(pieces().filter { it.style.slot != slot }, body())

/**
 * Placement and animation of the HAT slot and the bunny ear, fox ear and antler head styles. Pure math. Loose parts
 * follow [CosmeticHeadMotion] (head-turn lag, step and jump bounce); periodic motion uses the integrated phases of
 * [CosmeticRig.Motion] times the speed setting, so nothing jumps when the player starts, stops or jumps, and speed 0
 * freezes everything.
 */
object CosmeticRigB {
    private const val TAU = (2.0 * PI).toFloat()
    private const val HEAD_TOP = -8f
    private val WHITE = 0xFFFFFF

    @JvmStatic
    fun handlesHead(style: CosmeticStyle): Boolean =
        style == CosmeticStyle.BUNNY_EARS || style == CosmeticStyle.FOX_EARS || style == CosmeticStyle.DEER_ANTLERS

    /** Animation strength: 0 freezes, 1 is the tuned default, capped so a high speed setting cannot flail. */
    private fun strength(piece: CosmeticPiece): Float = piece.speed.coerceIn(0f, 1.5f)

    private fun add(out: MutableList<CosmeticRig.Placement>, mesh: VoxelMesh, m: Matrix4f, piece: CosmeticPiece) {
        out.add(CosmeticRig.Placement(CosmeticRig.Anchor.HEAD, mesh, m, piece.color, piece.color2))
    }

    // ---- hats -----------------------------------------------------------------------------------

    @JvmStatic
    fun hat(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val k = strength(piece)
        val sway = CosmeticHeadMotion.sway(motion.seed)
        val phase = motion.swayPhase * piece.speed
        // Bounce with steps and jumps, plus a faint breath; leans back a little when moving fast.
        val lift = (sway.bob * 0.45f + sin(phase * 0.5f) * 0.05f) * k
        val base = Matrix4f().translate(0f, HEAD_TOP - piece.up - lift, piece.back).scale(piece.size)
        val lean = -(motion.moving * 0.05f + motion.air * 0.07f) * k
        when (piece.style) {
            CosmeticStyle.TOP_HAT -> {
                base.rotateX(lean + sway.fore * 0.2f * k).rotateZ(sin(phase * 0.7f) * 0.012f * k)
                add(out, CosmeticModelsB.TOP_HAT, base, piece)
            }
            CosmeticStyle.ROYAL_CROWN -> crown(piece, motion, base.rotateX(lean + sway.fore * 0.2f * k), k, out)
            CosmeticStyle.WITCH_HAT -> witch(piece, motion, base.rotateX(lean * 0.6f), sway, k, phase, out)
            CosmeticStyle.PROPELLER_BEANIE -> beanie(piece, motion, base.rotateX(lean + sway.fore * 0.15f * k), k, out)
            // Size grows the flowers only; the ring keeps hugging the head (owner feedback).
            CosmeticStyle.FLOWER_CROWN -> flowers(piece, motion, base.scale(1f / piece.size).rotateX(lean * 0.5f), k, phase, out)
            else -> headphones(piece, base, sway, k, phase, out)
        }
    }

    // Witch hat: the middle and the bent tip swing about their joints, each lagging more than the one below.
    private fun witch(
        piece: CosmeticPiece, motion: CosmeticRig.Motion, base: Matrix4f, sway: CosmeticHeadMotion.Sway,
        k: Float, phase: Float, out: MutableList<CosmeticRig.Placement>,
    ) {
        val meshes = CosmeticModelsB.WITCH_HAT
        val joints = CosmeticModelsB.WITCH_JOINTS
        val tip = CosmeticModelsB.WITCH_TIP
        add(out, meshes[0], Matrix4f(base), piece)
        // Windy lean back while moving or airborne; the head's turns and nods swing the tip the way it lags.
        val wind = -(motion.moving * 0.12f + motion.air * 0.2f)
        val h = 11f
        val m1 = Matrix4f(base).translate(joints[0][0], joints[0][1], joints[0][2])
            .rotateX((wind * 0.5f + sway.fore * 0.6f + sway.twist * tip[0] / h * 0.5f + sin(phase * 0.9f) * 0.035f) * k)
            .rotateZ((sway.twist * tip[2] / h * 0.5f + sin(phase * 0.7f + 1f) * 0.025f) * k)
            .translate(-joints[0][0], -joints[0][1], -joints[0][2])
        add(out, meshes[1], m1, piece)
        val m2 = Matrix4f(m1).translate(joints[1][0], joints[1][1], joints[1][2])
            .rotateX((wind + sway.fore * 1.1f + sway.twist * tip[0] / h + sin(phase * 0.9f - 0.9f) * 0.085f) * k)
            .rotateZ((sway.twist * tip[2] / h + sin(phase * 0.7f + 0.2f) * 0.07f) * k)
            .rotateY(sway.twist * 0.4f * k)
            .translate(-joints[1][0], -joints[1][1], -joints[1][2])
        add(out, meshes[2], m2, piece)
    }

    // Crown: still apart from the step bounce; the gems brighten and throw a sparkle one after another.
    private fun crown(piece: CosmeticPiece, motion: CosmeticRig.Motion, base: Matrix4f, k: Float, out: MutableList<CosmeticRig.Placement>) {
        add(out, CosmeticModelsB.ROYAL_CROWN, base, piece)
        val spots = CosmeticModelsB.GEM_SPOTS
        val glints = FloatArray(spots.size) { glint(motion.seconds, motion.seed, it, spots.size, piece.speed) }
        for (hue in CosmeticModelsB.CROWN_GEMS.indices) {
            var best = 0f
            for ((i, spot) in spots.withIndex()) if (spot.hue == hue) best = max(best, glints[i])
            val c = CosmeticModelsB.GEM_COLORS[hue]
            out.add(CosmeticRig.Placement(CosmeticRig.Anchor.HEAD, CosmeticModelsB.CROWN_GEMS[hue], Matrix4f(base), mix(c, WHITE, best * 0.6f), c))
        }
        for ((i, spot) in spots.withIndex()) {
            val g = glints[i]
            if (g < 0.04f) continue
            val m = Matrix4f(base).translate(spot.x, spot.y, spot.z).rotateY(spot.yaw).scale(0.35f + g * 0.9f)
            out.add(CosmeticRig.Placement(CosmeticRig.Anchor.HEAD, CosmeticModelsB.SPARKLE, m, WHITE, WHITE, CosmeticRig.Kind.GLOW, (235 * g).toInt()))
        }
    }

    /** 0..1 glint of gem [gem] of [count]: one short bright flash per gem in turn, every ~10 s, offset per player. */
    @JvmStatic
    fun glint(seconds: Float, seed: Int, gem: Int, count: Int, speed: Float): Float {
        if (speed <= 0f) return 0f
        val period = 10f / speed.coerceAtMost(3f)
        val local = seconds + ((seed * 37) and 0xFF) / 255f * period - gem * period / count
        val t = local - floor(local / period) * period
        val length = 0.6f
        if (t > length) return 0f
        val s = sin(t / length * PI.toFloat())
        return s * s
    }

    /** Propeller turn angle (rad): the integrated wing-beat phase, so it speeds up smoothly when moving and in the air. */
    @JvmStatic
    fun propellerSpin(motion: CosmeticRig.Motion, speed: Float): Float = motion.wingPhase * 2f * speed

    private fun beanie(piece: CosmeticPiece, motion: CosmeticRig.Motion, base: Matrix4f, k: Float, out: MutableList<CosmeticRig.Placement>) {
        add(out, CosmeticModelsB.BEANIE, Matrix4f(base), piece)
        val spin = propellerSpin(motion, piece.speed)
        val hub = Matrix4f(base).translate(0f, CosmeticModelsB.PROPELLER_Y, 0f)
        val blades = CosmeticModelsB.PROPELLER
        add(out, blades, Matrix4f(hub).rotateY(spin), piece)
        // Motion blur: faint extra blades trailing the real ones once it spins fast.
        val blur = (motion.moving * 0.45f + motion.air * 0.9f).coerceIn(0f, 1f) * min(1f, piece.speed)
        if (blur > 0.05f) {
            for (trail in 1..2) {
                out.add(CosmeticRig.Placement(CosmeticRig.Anchor.HEAD, blades, Matrix4f(hub).rotateY(spin - trail * 0.5f),
                    piece.color, piece.color2, CosmeticRig.Kind.TRANSLUCENT, (110 * blur / trail).toInt()))
            }
        }
    }

    private fun flowers(piece: CosmeticPiece, motion: CosmeticRig.Motion, base: Matrix4f, k: Float, phase: Float, out: MutableList<CosmeticRig.Placement>) {
        add(out, CosmeticModelsB.FLOWER_RING, Matrix4f(base), piece)
        val size = piece.size
        val amount = (0.07f + 0.1f * motion.moving + 0.06f * motion.air) * k
        for (i in 0 until CosmeticModelsB.FLOWER_COUNT) {
            val spot = CosmeticModelsB.ringSpot(i, CosmeticModelsB.FLOWER_COUNT, 0f)
            val a = phase * 1.4f + i * 1.9f
            val m = Matrix4f(base).translate(spot[0], spot[1], spot[2]).rotateY(spot[3]).translate(0f, 0f, -0.7f).scale(1.3f * size)
                .rotateX(sin(a) * amount).rotateZ(sin(a * 0.8f + 1f) * amount)
                .translate(0f, 0f, -0.12f * sin(a) * k)
            add(out, CosmeticModelsB.FLOWER, m, piece)
            val leaf = CosmeticModelsB.ringSpot(i, CosmeticModelsB.FLOWER_COUNT, 0.5f)
            val b = phase * 1.7f + i * 2.3f
            val n = Matrix4f(base).translate(leaf[0], leaf[1], leaf[2]).rotateY(leaf[3]).scale(size)
                .rotateX(sin(b) * amount * 1.8f).rotateZ(sin(b * 0.9f + 2f) * amount * 1.8f)
            add(out, CosmeticModelsB.SPRIG, n, piece)
        }
    }

    /** Beat 0..1: two soft thumps per cycle of [phase] (radians). */
    @JvmStatic
    fun beat(phase: Float): Float {
        val f = (phase / TAU).let { it - floor(it) }
        return bump(f) + 0.6f * bump((f + 0.5f).let { it - floor(it) })
    }

    private fun bump(f: Float): Float {
        if (f > 0.35f) return 0f
        val s = sin(f / 0.35f * PI.toFloat())
        return s * s
    }

    private fun headphones(piece: CosmeticPiece, base: Matrix4f, sway: CosmeticHeadMotion.Sway, k: Float, phase: Float, out: MutableList<CosmeticRig.Placement>) {
        add(out, CosmeticModelsB.HEADPHONE_BAND, Matrix4f(base), piece)
        val pulse = beat(phase) * k
        for (side in intArrayOf(1, -1)) {
            val m = Matrix4f(base).scale(side.toFloat(), 1f, 1f)
                .translate(CosmeticModelsB.CUP_X + pulse * 0.28f, CosmeticModelsB.CUP_Y, 0f).scale(1f + pulse * 0.06f)
            add(out, CosmeticModelsB.HEADPHONE_CUP, m, piece)
        }
    }

    // ---- bunny ears, fox ears, antlers ----------------------------------------------------------

    @JvmStatic
    fun head(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val k = strength(piece)
        val sway = CosmeticHeadMotion.sway(motion.seed)
        val phase = motion.swayPhase * piece.speed
        // Head styles share the slot's default sink, so cancel it: the base sits on the head top at default height.
        val y0 = HEAD_TOP + CosmeticSlot.HEAD.defaultUp
        val shake = shake(motion.seconds, motion.seed, piece.speed) * k
        for ((ear, side) in intArrayOf(1, -1).withIndex()) {
            // Size grows each piece from its base; the base stays on the head (owner feedback: it used to climb).
            val m = Matrix4f().translate(0f, y0 - piece.up, piece.back).scale(side.toFloat(), 1f, 1f)
            when (piece.style) {
                CosmeticStyle.BUNNY_EARS ->
                    bunny(piece, motion, m.translate(CosmeticModelsB.BUNNY_EAR_X, 0f, 0.4f).scale(piece.size), ear, side, sway, k, phase, shake, out)
                CosmeticStyle.FOX_EARS ->
                    fox(piece, motion, m.translate(CosmeticModelsB.FOX_EAR_X, 0f, -0.2f).scale(piece.size), ear, side, sway, k, phase, shake, out)
                else -> antler(piece, m.translate(CosmeticModelsB.ANTLER_EAR_X, 0f, 0.3f).scale(piece.size), ear, side, sway, k, phase, shake, out)
            }
        }
    }

    /**
     * Like [CosmeticRig.earTwitch] but with a smooth start and end (the swing eases in and out), so the flick has no
     * sudden kick. Rests most of the time; some cycles skip it.
     */
    @JvmStatic
    fun twitch(seconds: Float, seed: Int, ear: Int, speed: Float): Float {
        if (speed <= 0f) return 0f
        val period = 2.6f / speed
        val local = seconds + ((seed * 31 + ear * 17) and 0xFF) / 255f * period
        val cycle = floor(local / period).toInt()
        if (((cycle * 1103515245 + seed + ear) ushr 7) % 3 == 0) return 0f
        val t = local - cycle * period
        val length = 0.45f
        if (t > length) return 0f
        val u = t / length
        return sin(u * TAU) * sin(u * PI.toFloat())
    }

    /**
     * 0 most of the time; every ~12 s (offset per player) a short fast wobble, like an animal shaking its head.
     * Three swings in 1.4 s, eased in and out: slow enough to stay inside the rig's smoothness budget (a faster
     * shake reads as jitter). Speed 0 stops it.
     */
    @JvmStatic
    fun shake(seconds: Float, seed: Int, speed: Float): Float {
        if (speed <= 0f) return 0f
        val period = 12f / speed.coerceAtMost(3f)
        val local = seconds + ((seed * 71 + 29) and 0xFF) / 255f * period
        val t = local - floor(local / period) * period
        val length = 1.4f
        if (t > length) return 0f
        val u = t / length
        return sin(u * TAU * 3f) * sin(u * PI.toFloat())
    }

    private fun bunny(
        piece: CosmeticPiece, motion: CosmeticRig.Motion, m: Matrix4f, ear: Int, side: Int,
        sway: CosmeticHeadMotion.Sway, k: Float, phase: Float, shake: Float, out: MutableList<CosmeticRig.Placement>,
    ) {
        val twitch = twitch(motion.seconds, motion.seed, ear, piece.speed)
        val blown = -(motion.moving * 0.14f + motion.air * 0.28f) * k
        val tilt = side * sway.twist * CosmeticModelsB.BUNNY_EAR_X / 9f
        // The shake swings both ears the same way (side undoes the mirror).
        m.rotateZ(0.14f + twitch * 0.3f * k + side * shake * 0.24f)
            .rotateX(-0.04f + blown + (sway.fore * 0.9f + tilt) * k + sin(phase * 0.8f + ear * 1.7f) * 0.025f * k)
        val meshes = CosmeticModelsB.BUNNY_EAR
        add(out, meshes[0], Matrix4f(m), piece)
        // The bent upper half: a resting fold (different per ear) that flops with bounce and twitches.
        val fold = (if (ear == 0) 0.2f else 0.34f) + (-sway.bob * 0.22f + sway.fore * 0.5f + twitch * 0.45f +
            sin(phase * 1.1f + ear * 2.2f) * 0.05f + blown * -0.8f) * k
        val pivot = CosmeticModelsB.BUNNY_PIVOT_Y
        val upper = Matrix4f(m).translate(0f, pivot, 0f).rotateX(fold).translate(0f, -pivot, 0f)
        add(out, meshes[1], upper, piece)
    }

    /** 0..1 alert perk: both fox ears swivel forward for a moment every ~9 s. */
    @JvmStatic
    fun alert(seconds: Float, seed: Int, speed: Float): Float {
        if (speed <= 0f) return 0f
        val period = 9f / speed.coerceAtMost(3f)
        val local = seconds + ((seed * 53) and 0xFF) / 255f * period
        val t = local - floor(local / period) * period
        if (t > 1.5f) return 0f
        val s = sin(t / 1.5f * PI.toFloat())
        return s * s
    }

    private fun fox(
        piece: CosmeticPiece, motion: CosmeticRig.Motion, m: Matrix4f, ear: Int, side: Int,
        sway: CosmeticHeadMotion.Sway, k: Float, phase: Float, shake: Float, out: MutableList<CosmeticRig.Placement>,
    ) {
        val twitch = twitch(motion.seconds, motion.seed, ear, piece.speed * 1.4f)
        val perk = alert(motion.seconds, motion.seed, piece.speed)
        val blown = -(motion.moving * 0.1f + motion.air * 0.2f) * k
        val tilt = side * sway.twist * CosmeticModelsB.FOX_EAR_X / 7f
        m.rotateY(((sin(phase * 0.35f + ear * 2.1f) * 0.12f + twitch * 0.4f) * k))
            .rotateZ(0.2f + (twitch * 0.5f - perk * 0.12f) * k + side * shake * 0.2f)
            .rotateX(-0.1f + (-perk * 0.3f - twitch * 0.35f + blown + sway.fore * 0.9f + tilt) * k)
        val meshes = CosmeticModelsB.FOX_EAR
        add(out, meshes[0], Matrix4f(m), piece)
        val pivot = CosmeticModelsB.FOX_PIVOT_Y
        // The tip flicks on its own and lags the ear.
        val flick = (twitch * 0.7f + sin(phase * 1.3f + ear * 1.4f) * 0.07f - sway.bob * 0.1f) * k
        add(out, meshes[1], Matrix4f(m).translate(0f, pivot, 0f).rotateX(flick).rotateZ(flick * 0.5f).translate(0f, -pivot, 0f), piece)
    }

    private fun antler(
        piece: CosmeticPiece, m: Matrix4f, ear: Int, side: Int, sway: CosmeticHeadMotion.Sway,
        k: Float, phase: Float, shake: Float, out: MutableList<CosmeticRig.Placement>,
    ) {
        val tilt = side * sway.twist * CosmeticModelsB.ANTLER_EAR_X / 9f
        // Antlers are rigid: the shake is a small rattle of the whole rack.
        m.rotateZ(0.16f + sin(phase * 0.6f + ear * 1.3f) * 0.035f * k + side * shake * 0.07f)
            .rotateX((sway.fore * 0.5f + tilt + sin(phase * 0.5f + ear) * 0.02f) * k)
        val meshes = CosmeticModelsB.ANTLER
        add(out, meshes[0], Matrix4f(m), piece)
        val j = CosmeticModelsB.ANTLER_JOINT
        val sw = (sin(phase * 0.6f - 0.8f + ear * 1.3f) * 0.05f + sway.fore * 0.5f + tilt - sway.bob * 0.05f) * k + side * shake * 0.05f
        add(out, meshes[1], Matrix4f(m).translate(j[0], j[1], j[2]).rotateZ(sw).rotateX(sw * 0.6f).translate(-j[0], -j[1], -j[2]), piece)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int = (((a shr shift) and 0xFF) * (1f - u) + ((b shr shift) and 0xFF) * u).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
