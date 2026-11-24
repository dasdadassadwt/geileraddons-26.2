package geiler.addons.client.cosmetics

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Placement and animation of the second cosmetics batch (dragon, fairy, phoenix, mechanical and crystal wings,
 * dragon, fox and devil tails, unicorn horn). [CosmeticRig.place] hands these styles over. Pure math: every
 * phase comes from the integrated phases of [CosmeticRig.Motion] or from the piece's constant speed times the
 * animation clock, so a change of walking speed never makes a pose jump.
 */
object CosmeticRigA {
    private val STYLES = setOf(
        CosmeticStyle.DRAGON_WINGS, CosmeticStyle.FAIRY_WINGS, CosmeticStyle.PHOENIX_WINGS,
        CosmeticStyle.MECHANICAL_WINGS, CosmeticStyle.CRYSTAL_WINGS, CosmeticStyle.UNICORN_HORN,
        CosmeticStyle.DRAGON_TAIL, CosmeticStyle.FOX_TAIL, CosmeticStyle.DEVIL_TAIL,
    )
    private const val TAU = (2.0 * PI).toFloat()
    private const val PX = 0.5f // one half-pixel voxel in pixels

    @JvmStatic
    fun handles(style: CosmeticStyle): Boolean = style in STYLES

    @JvmStatic
    fun place(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        when (piece.style) {
            CosmeticStyle.DRAGON_WINGS -> dragonWings(piece, motion, out)
            CosmeticStyle.FAIRY_WINGS -> fairyWings(piece, motion, out)
            CosmeticStyle.PHOENIX_WINGS -> phoenixWings(piece, motion, out)
            CosmeticStyle.MECHANICAL_WINGS -> mechanicalWings(piece, motion, out)
            CosmeticStyle.CRYSTAL_WINGS -> crystalWings(piece, motion, out)
            CosmeticStyle.UNICORN_HORN -> unicornHorn(piece, motion, out)
            CosmeticStyle.DRAGON_TAIL -> dragonTail(piece, motion, out)
            CosmeticStyle.FOX_TAIL -> foxTail(piece, motion, out)
            CosmeticStyle.DEVIL_TAIL -> devilTail(piece, motion, out)
            else -> Unit
        }
    }

    // ---- turning: how far a trailing tail lags behind the body -------------------------------------------

    private class Turn(var seconds: Float, var yaw: Float, var lag: Float)

    private val turns = HashMap<Int, Turn>()

    /**
     * Feeds the body yaw (degrees) of player [id] once per rendered frame; tails read the smoothed lag. The lag
     * is the integral of the turn rate with exponential decay, so it is continuous and settles by itself.
     * Render thread only.
     */
    @JvmStatic
    fun noteTurn(id: Int, seconds: Float, yawDegrees: Float) {
        val state = turns.getOrPut(id) { Turn(seconds, yawDegrees, 0f) }
        val dt = seconds - state.seconds
        if (dt < 0f || dt > 1f) {
            state.seconds = seconds
            state.yaw = yawDegrees
            state.lag = 0f
            return
        }
        if (dt == 0f) return
        var delta = (yawDegrees - state.yaw) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        state.lag = (state.lag * exp(-dt / TURN_DECAY) + Math.toRadians(delta.toDouble()).toFloat()).coerceIn(-TURN_MAX, TURN_MAX)
        state.seconds = seconds
        state.yaw = yawDegrees
        if (turns.size > 64) turns.values.removeIf { seconds - it.seconds > 30f || it.seconds - seconds > 30f }
    }

    /** Positive while the body turns right (the tail swings to its right, -x). */
    private fun lag(id: Int): Float = turns[id]?.lag ?: 0f

    private const val TURN_DECAY = 0.3f
    private const val TURN_MAX = 1.1f

    // ---- shared helpers ------------------------------------------------------------------------------------

    private fun fract(v: Float): Float = v - floor(v)

    private fun seedPhase(seed: Int): Float = ((seed * 31) and 0xFF) / 255f * TAU

    private fun lobeBase(piece: CosmeticPiece, side: Int): Matrix4f =
        Matrix4f().translate(side * 0.8f, 1.5f - piece.up, 2.3f + piece.back).scale(piece.size).scale(side.toFloat(), 1f, 1f)

    private fun add(out: MutableList<CosmeticRig.Placement>, mesh: VoxelMesh, m: Matrix4f, piece: CosmeticPiece,
        kind: CosmeticRig.Kind = CosmeticRig.Kind.SOLID, alpha: Int = 255) {
        out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, mesh, Matrix4f(m), piece.color, piece.color2, kind, alpha))
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    // ---- dragon wings: heavy beat, flexing fingers ---------------------------------------------------------

    private val DRAGON_FOLD = floatArrayOf(0.30f, 0.22f, 0.15f, 0.09f, 0.04f)

    private fun dragonWings(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val phase = motion.wingPhase * piece.speed
        val beat = sin(phase)
        val swing = beat * (0.3f + motion.air * 0.45f)
        val sweep = motion.moving * 0.35f
        // folded a little at rest, spread by the downstroke and while moving
        val fold = (0.26f - 0.2f * max(motion.moving, motion.air) - 0.14f * sin(phase + 0.6f)).coerceIn(0f, 0.6f)
        val parts = CosmeticModelsA.DRAGON_WING
        for (side in intArrayOf(1, -1)) {
            val m = lobeBase(piece, side)
                .rotateY(-(0.42f + sweep + swing))
                .rotateZ(-0.1f + sweep * 0.25f - swing * 0.4f)
                .rotateX(0.12f + sweep * 0.3f + swing * 0.15f)
                .translate(0f, -CosmeticModelsA.DRAGON_ROOT_Y, 0f)
            add(out, parts[0], m, piece)
            for (k in 0 until 5) {
                val pitch = 0.2f * sin(phase - 0.7f - 0.18f * k) * (0.4f + 0.12f * k)
                val finger = Matrix4f(m)
                    .translate(CosmeticModelsA.DRAGON_WRIST_X, CosmeticModelsA.DRAGON_WRIST_Y, 0f)
                    .rotateZ(fold * DRAGON_FOLD[k]).rotateX(pitch)
                    .translate(-CosmeticModelsA.DRAGON_WRIST_X, -CosmeticModelsA.DRAGON_WRIST_Y, 0f)
                add(out, parts[k + 1], finger, piece)
            }
        }
    }

    // ---- fairy wings: fast flutter, sparkles ---------------------------------------------------------------

    private fun fairyWings(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val phase = motion.wingPhase * piece.speed * 3.8f + seedPhase(motion.seed)
        val flutter = 0.32f + motion.air * 0.06f
        val sweep = motion.moving * 0.2f
        val parts = CosmeticModelsA.FAIRY_WING
        val tmp = Vector3f()
        for (side in intArrayOf(1, -1)) {
            for (lobe in 0..1) {
                val lag = if (lobe == 0) 0f else 0.9f
                val amount = if (lobe == 0) flutter else flutter * 0.85f
                val beat = sin(phase - lag)
                val tremor = 0.025f * sin(phase * 3.7f + lobe)
                val m = lobeBase(piece, side).scale(if (lobe == 0) 1.25f else 1.5f)
                    .rotateY(-((if (lobe == 0) 0.5f else 0.4f) + sweep + amount * beat + tremor))
                    .rotateZ(if (lobe == 0) -0.06f - 0.05f * beat else 0.12f + 0.05f * beat)
                    .rotateX(0.1f)
                    .translate(0f, -CosmeticModelsA.FAIRY_ROOT_Y, 0f)
                add(out, parts[lobe * 2], m, piece, CosmeticRig.Kind.TRANSLUCENT, if (lobe == 0) 125 else 150)
                add(out, parts[lobe * 2 + 1], m, piece, CosmeticRig.Kind.TRANSLUCENT, 225)
                // two sparkles leave each wing tip, offset from each other
                val tip = CosmeticModelsA.FAIRY_TIPS[lobe]
                m.transformPosition(tip[0], tip[1], 0f, tmp)
                for (n in 0..1) {
                    val clock = motion.seconds * piece.speed * 0.42f + n * 0.5f + lobe * 0.27f + side * 0.13f + (motion.seed and 7) * 0.11f
                    val life = fract(clock)
                    val fade = sin(life * PI.toFloat())
                    if (fade < 0.05f) continue
                    val cycle = floor(clock).toInt()
                    val angle = VoxelKit.hash01(cycle, lobe * 2 + n, motion.seed) * TAU
                    val size = (0.7f + 0.7f * fade) * piece.size
                    val s = Matrix4f()
                        .translate(tmp.x + kotlin.math.cos(angle) * life * 2.2f * piece.size * side,
                            tmp.y + life * 4f * piece.size, tmp.z + sin(angle) * life * 1.6f * piece.size + life * 1.5f)
                        .rotateZ(life * 3f).scale(size)
                    out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, CosmeticModelsA.SPARK, s, 0xFFFFFF, piece.color2,
                        CosmeticRig.Kind.GLOW, (235 * fade).toInt()))
                }
            }
        }
    }

    // ---- phoenix wings: angel-like beat, flickering fire tips, embers --------------------------------------

    private const val PHOENIX_FAN = 0.6f
    private const val PHOENIX_LIFT = 2f

    private fun phoenixWings(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val phase = motion.wingPhase * piece.speed
        val swing = sin(phase) * (0.3f + motion.air * 0.45f)
        val sweep = motion.moving * 0.35f
        val parts = CosmeticModelsA.PHOENIX_WING
        val tmp = Vector3f()
        val clock = motion.seconds * piece.speed
        for (side in intArrayOf(1, -1)) {
            val m = lobeBase(piece, side).scale(0.85f)
                .rotateY(-(0.5f + sweep + swing * 0.85f))
                .rotateZ(-0.1f + sweep * 0.25f - swing * 0.4f)
                .rotateX(0.12f + sweep * 0.3f + swing * 0.15f)
                .translate(0f, -CosmeticModelsA.PHOENIX_ROOT_Y, 0f)
            // The primaries fan out and up from the wrist as an upper tier, so the two feather sets do not hide
            // behind each other (owner feedback 2026-11-22).
            val primaries = Matrix4f(m)
                .translate(CosmeticModelsA.PHOENIX_WRIST_X, CosmeticModelsA.PHOENIX_WRIST_Y - PHOENIX_LIFT, 0f)
                .rotateZ(-PHOENIX_FAN + 0.12f * sin(phase - 0.8f)).rotateX(0.1f * sin(phase - 1f))
                .translate(-CosmeticModelsA.PHOENIX_WRIST_X, -CosmeticModelsA.PHOENIX_WRIST_Y, 0f)
            add(out, parts[0], m, piece)
            add(out, parts[1], primaries, piece)
            for (g in 0 until 4) {
                val flicker = 0.62f * sin(clock * 9f + g * 2.1f + side) + 0.38f * sin(clock * 21.3f + g * 4.7f)
                val alpha = (200 + 55 * flicker).toInt().coerceIn(120, 255)
                add(out, parts[2 + g], if (g == 3) m else primaries, piece, CosmeticRig.Kind.GLOW, alpha)
            }
            // embers: some fall, some rise, all fade
            for (e in 0 until 4) {
                val life = fract(clock * 0.33f + e * 0.25f + side * 0.09f + (motion.seed and 7) * 0.07f)
                val fade = sin(life * PI.toFloat())
                if (fade < 0.06f) continue
                val cycle = floor(clock * 0.33f + e * 0.25f).toInt()
                val tip = CosmeticModelsA.PHOENIX_TIPS[(e * 2 + (cycle and 3)) % CosmeticModelsA.PHOENIX_TIPS.size]
                primaries.transformPosition(tip[0], tip[1], 0f, tmp)
                val dirY = if (e % 2 == 0) 1f else -0.7f
                val size = (0.45f + 0.4f * fade) * piece.size
                val s = Matrix4f()
                    .translate(tmp.x + side * life * 1.5f * piece.size + sin(life * 6f + e) * 0.5f, tmp.y + dirY * life * 6f * piece.size, tmp.z + life)
                    .scale(size).translate(-0.5f, -0.5f, -0.5f)
                val color = if (e % 2 == 0) piece.color2 else piece.color
                out.add(CosmeticRig.Placement(CosmeticRig.Anchor.BODY, CosmeticModels.CUBE, s, color, color, CosmeticRig.Kind.GLOW, (240 * fade).toInt()))
            }
        }
    }

    // ---- mechanical wings: blades fan open, gears turn -----------------------------------------------------

    /** Smooth staircase: moves between whole steps quickly, like a ratchet, and stays continuous. */
    private fun ratchet(x: Float, steps: Int): Float {
        val v = x * steps
        val k = floor(v)
        val f = (v - k - 0.3f).coerceIn(0f, 0.7f) / 0.7f
        return (k + smooth(f)) / steps
    }

    private fun mechanicalWings(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val phase = motion.wingPhase * piece.speed
        val clock = motion.seconds * piece.speed
        val reaction = smooth(max(motion.moving * 1.6f, motion.air * 1.2f).coerceIn(0f, 1f))
        // now and then it flexes open on its own while the player stands still
        val burstClock = fract(clock / 7f + (motion.seed and 15) / 15f)
        val burst = if (piece.speed > 0f && burstClock < 0.2f) 0.65f * sin(burstClock / 0.2f * PI.toFloat()).let { it * it } else 0f
        val open = ratchet(max(max(reaction, burst), 0.2f - 0.1f * reaction), 24)
        val gear = motion.swayPhase * piece.speed * 2.4f + seedPhase(motion.seed)
        val blades = CosmeticModelsA2.MECH_BLADES
        for (side in intArrayOf(1, -1)) {
            val m = lobeBase(piece, side)
                .rotateY(-(0.12f + 0.38f * open + motion.moving * 0.12f))
                .rotateZ(-0.05f - 0.1f * open)
                .rotateX(0.08f)
            val hub = Matrix4f(m).translate(CosmeticModelsA2.MECH_HUB_X, CosmeticModelsA2.MECH_HUB_Y, 0f)
            val hubBig = Matrix4f(hub).scale(1.3f)
            add(out, CosmeticModelsA2.MECH_ARM, hubBig, piece)
            add(out, CosmeticModelsA2.MECH_GEAR, Matrix4f(hubBig).rotateZ(gear), piece)
            val small = Matrix4f(hubBig)
                .translate(CosmeticModelsA2.MECH_SMALL_GEAR[0], CosmeticModelsA2.MECH_SMALL_GEAR[1], 0f).rotateZ(-gear * 1.33f)
            add(out, CosmeticModelsA2.MECH_SMALL, small, piece)
            for (k in blades.indices) {
                val folded = 0.04f + 0.035f * k
                val opened = -0.2f + 2.1f * k / (blades.size - 1f)
                val jitter = 0.012f * (0.4f + open) * sin(phase * 13f + k * 2.3f + seedPhase(motion.seed))
                val b = Matrix4f(hub).translate(0f, 0f, 1.2f + 0.28f * k).rotateZ(-(folded + (opened - folded) * open + jitter))
                add(out, blades[k], b, piece)
            }
        }
    }

    // ---- crystal wings: floating shards, pulsing core, chatter ---------------------------------------------

    private val SHARD_ANGLE = floatArrayOf(0.4f, 0.95f, 1.55f, 2.15f, 2.75f)
    private val SHARD_SIZE = intArrayOf(0, 0, 1, 1, 2)
    private val SHARD_DISTANCE = floatArrayOf(5.2f, 5.8f, 6.2f, 5.6f, 4.6f)

    private fun crystalWings(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val phase = motion.swayPhase * piece.speed * 1.6f + seedPhase(motion.seed)
        val clock = motion.seconds * piece.speed
        val swing = sin(motion.wingPhase * piece.speed) * (0.1f + motion.air * 0.22f)
        val sweep = motion.moving * 0.3f
        // a quick vibration every few seconds
        val chatterClock = fract(clock / 5f + (motion.seed and 15) / 15f)
        val chatter = if (piece.speed > 0f && chatterClock < 0.12f) sin(chatterClock / 0.12f * PI.toFloat()) else 0f
        val pulse = 0.5f + 0.5f * sin(clock * 2.6f + seedPhase(motion.seed))
        // the core sits at the center of the back
        val coreBase = Matrix4f().translate(0f, 1.5f - piece.up, 2.3f + piece.back + 1.2f * piece.size).scale(piece.size * 1.5f)
        add(out, CosmeticModelsA2.CRYSTAL_CORE, Matrix4f(coreBase).scale(0.9f + 0.18f * pulse), piece,
            CosmeticRig.Kind.GLOW, (200 + 55 * pulse).toInt())
        add(out, CosmeticModelsA2.CRYSTAL_CORE_HALO, Matrix4f(coreBase).scale(0.9f + 0.25f * pulse), piece,
            CosmeticRig.Kind.GLOW, (45 + 40 * pulse).toInt())
        for (side in intArrayOf(1, -1)) {
            val m = lobeBase(piece, side).scale(1.55f)
                .translate(-0.8f, 0f, 0f)
                .rotateY(-(0.3f + sweep + swing * 0.5f))
                .rotateX(0.08f)
            for (j in SHARD_ANGLE.indices) {
                val bob = sin(phase + j * 1.7f + side * 0.6f)
                val shake = chatter * sin(clock * 75f + j * 3f + side) * 0.05f
                val angle = SHARD_ANGLE[j] + 0.06f * sin(phase * 0.8f + j * 2.1f) + shake
                val d = SHARD_DISTANCE[j] + 0.9f * bob + chatter * 0.25f * sin(clock * 61f + j)
                val bx = sin(angle) * d
                val by = -kotlin.math.cos(angle) * d + 0.5f * sin(phase * 0.9f + j)
                val z = 0.3f * j - 0.5f
                val shard = Matrix4f(m).translate(bx, by, z).rotateZ(angle).rotateX(0.12f)
                add(out, CosmeticModelsA2.CRYSTAL_SHARDS[SHARD_SIZE[j]], shard, piece, CosmeticRig.Kind.TRANSLUCENT, 225)
                // the black rod from the core to the shard's base
                val length = kotlin.math.sqrt(bx * bx + by * by + z * z)
                val rod = Matrix4f(m).rotateZ(atan2(-bx, by)).scale(1f, length / 8f, 1f)
                add(out, CosmeticModelsA2.CRYSTAL_ROD, rod, piece)
            }
        }
    }

    // ---- unicorn horn: pulsing tip, occasional sparkle -----------------------------------------------------

    private fun unicornHorn(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val clock = motion.seconds * piece.speed
        // Size grows the horn from its base; the base stays on the forehead.
        val m = Matrix4f().translate(0f, -7.6f - piece.up, -2.2f + piece.back).scale(piece.size)
            .rotateX(0.28f).scale(1.3f)
        fun put(mesh: VoxelMesh, matrix: Matrix4f, kind: CosmeticRig.Kind, alpha: Int) =
            out.add(CosmeticRig.Placement(CosmeticRig.Anchor.HEAD, mesh, matrix, piece.color, piece.color2, kind, alpha))
        put(CosmeticModelsATails.UNICORN_HORN, Matrix4f(m), CosmeticRig.Kind.SOLID, 255)
        val pulse = 0.5f + 0.5f * sin(clock * 2.4f + seedPhase(motion.seed))
        put(CosmeticModelsATails.UNICORN_TIP, Matrix4f(m), CosmeticRig.Kind.GLOW, (175 + 75 * pulse).toInt())
        val tip = Vector3f()
        m.transformPosition(0f, -CosmeticModelsATails.UNICORN_LENGTH * PX, 0f, tip)
        for (j in 0 until 3) {
            val clockJ = clock / 3.4f + j / 3f + (motion.seed and 7) * 0.05f
            val u = fract(clockJ)
            if (u > 0.36f) continue
            val life = u / 0.36f
            val fade = sin(life * PI.toFloat())
            if (fade < 0.05f) continue
            val angle = VoxelKit.hash01(floor(clockJ).toInt(), j, motion.seed) * TAU
            val s = Matrix4f()
                .translate(tip.x + kotlin.math.cos(angle) * life * 2.6f * piece.size, tip.y - 1f - life * 3.2f * piece.size,
                    tip.z + sin(angle) * life * 2.2f * piece.size)
                .rotateZ(life * 2f).scale((0.8f + 0.8f * fade) * piece.size)
            out.add(CosmeticRig.Placement(CosmeticRig.Anchor.HEAD, CosmeticModelsA.SPARK, s, 0xFFF1B8, 0xFFF1B8,
                CosmeticRig.Kind.GLOW, (240 * fade).toInt()))
        }
    }

    // ---- tails ---------------------------------------------------------------------------------------------

    private fun dragonTail(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val phase = motion.swayPhase * piece.speed * 3f
        val lag = lag(motion.seed)
        val streamline = motion.moving * 0.04f
        val segments = CosmeticModelsATails.DRAGON_TAIL
        val length = CosmeticModelsATails.DRAGON_SEGMENT * PX
        val m = Matrix4f().translate(0f, 10.5f - piece.up, 1.5f + piece.back).scale(piece.size).rotateX(-2.25f)
        for (i in segments.indices) {
            if (i > 0) {
                m.translate(0f, -length, 0f)
                val f = i / (segments.size - 1f)
                val rest = (if (i <= 1) -0.02f else 0.1f) - streamline
                val lift = 0.045f * sin(phase * 0.5f - 0.5f * i)
                val sway = 0.05f * (1f + motion.moving * 0.5f) * sin(phase - 0.42f * i) * (0.3f + f) - lag * 0.1f * (0.4f + f)
                m.rotateX(rest + lift).rotateZ(sway)
            }
            add(out, segments[i], m, piece)
        }
    }

    private fun foxTail(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val phase = motion.swayPhase * piece.speed * 3.5f
        val lag = lag(motion.seed)
        val puffs = CosmeticModelsATails.FOX_TAIL
        val m = Matrix4f().translate(0f, 9.6f - piece.up, 1.2f + piece.back).scale(piece.size).rotateX(-1.95f)
        for (i in puffs.indices) {
            m.translate(0f, -CosmeticModelsATails.FOX_SPACING * (if (i == 0) 0.75f else 1f), 0f)
            val tipFlick = if (i >= 3) 0.1f * sin(phase * 2.3f - 1.4f * i) * (i - 2f) / 3f else 0f
            val sway = 0.075f * sin(phase - 0.6f * i) * (0.5f + 0.25f * i) + tipFlick - lag * 0.09f
            val rest = if (i == 0) 0f else 0.1f - motion.moving * 0.03f
            m.rotateX(rest + 0.05f * sin(phase * 0.5f - 0.8f * i)).rotateZ(sway)
            add(out, puffs[i], m, piece)
        }
    }

    private fun devilTail(piece: CosmeticPiece, motion: CosmeticRig.Motion, out: MutableList<CosmeticRig.Placement>) {
        val phase = motion.swayPhase * piece.speed * 6f
        val lag = lag(motion.seed)
        val links = CosmeticModelsATails.DEVIL_TAIL
        val length = CosmeticModelsATails.DEVIL_SEGMENT * PX
        val m = Matrix4f().translate(0f, 10f - piece.up, 1.5f + piece.back).scale(piece.size).rotateX(-2.35f)
        for (i in links.indices) {
            if (i > 0) {
                m.translate(0f, -length, 0f)
                val f = i / (links.size - 1f)
                // an S: the whip curves up, with a wave running down it
                val rest = 0.13f + 0.035f * sin(i * 0.7f - phase * 0.5f)
                val wave = 0.1f * (1f + motion.moving * 0.4f) * sin(phase - 0.55f * i) * (0.25f + f) - lag * 0.05f
                m.rotateX(rest).rotateZ(wave)
            }
            add(out, links[i], m, piece)
        }
    }
}
