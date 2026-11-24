package geiler.addons.client.cosmetics

import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Places and animates a loadout. Pure math, shared by the in-game layer, the menu icons and the offline
 * preview. Output matrices are in model pixels (1/16 block) relative to the player's body or head part,
 * in Minecraft model space: y grows down, z grows backward, +x is the player's left.
 */
object CosmeticRig {
    enum class Anchor { BODY, HEAD, WORLD, CAPE, CAPE_ARMOR }

    /** How a placement is drawn: opaque, see-through (haze) or glowing see-through (aura sparks). */
    enum class Kind { SOLID, TRANSLUCENT, GLOW }

    @JvmRecord
    data class Placement(
        val anchor: Anchor,
        val mesh: VoxelMesh,
        val transform: Matrix4f,
        val color: Int,
        val color2: Int,
        val kind: Kind,
        val alpha: Int,
    ) {
        constructor(anchor: Anchor, mesh: VoxelMesh, transform: Matrix4f, color: Int, color2: Int) :
            this(anchor, mesh, transform, color, color2, Kind.SOLID, 255)
    }

    /**
     * Smoothed animation input; build it with [CosmeticMotionTracker] so phases never jump.
     * @param seconds animation clock (entity age)
     * @param seed per-player value that desynchronises twitches and particles between players
     * @param moving 0 standing, toward 1 sprinting (smoothed)
     * @param air 0 on the ground, toward 1 while airborne (smoothed)
     * @param wingPhase integrated wing-beat phase (radians)
     * @param swayPhase integrated tail-sway phase (radians)
     */
    @JvmRecord
    data class Motion(
        val seconds: Float,
        val seed: Int,
        val moving: Float,
        val air: Float,
        val wingPhase: Float,
        val swayPhase: Float,
        /** Where the PET slot's companion is relative to the body (see [PetFollow]); the rest spot by default. */
        val pet: PetPose = PetPose.REST,
    ) {
        /** Without a pet pose (Java callers do not see Kotlin's default argument). */
        constructor(seconds: Float, seed: Int, moving: Float, air: Float, wingPhase: Float, swayPhase: Float) :
            this(seconds, seed, moving, air, wingPhase, swayPhase, PetPose.REST)

        companion object {
            @JvmField
            val REST = Motion(0f, 0, 0f, 0f, 0f, 0f)

            /** A standing pose at [seconds] with phases as if the player had stood still all along. */
            @JvmStatic
            fun standing(seconds: Float, seed: Int): Motion =
                Motion(seconds, seed, 0f, 0f, seconds * wingRate(0f, 0f) * TAU, seconds * TAU, PetPose.standing(seconds))
        }
    }

    private const val TAU = (2.0 * PI).toFloat()

    /** Wing beats per second before the player's speed setting. */
    @JvmStatic
    fun wingRate(moving: Float, air: Float): Float = 0.9f + moving * 1.1f + air * 1.8f

    @JvmStatic
    fun place(loadout: CosmeticLoadout, motion: Motion): List<Placement> = place(loadout, motion, false)

    /**
     * [live] is the in-game call: the trail and cape slots depend on the real world (walked path, cape lean) and
     * are added by [LiveCosmetics]; menu pictures and previews pass false and get a synthetic walk and a resting cape.
     */
    @JvmStatic
    fun place(loadout: CosmeticLoadout, motion: Motion, live: Boolean): MutableList<Placement> {
        val out = ArrayList<Placement>(48)
        if (loadout.pieces().isEmpty()) return out
        for (piece in loadout.pieces()) {
            if (CosmeticRigA.handles(piece.style)) {
                CosmeticRigA.place(piece, motion, out)
                continue
            }
            when (piece.style.slot) {
                CosmeticSlot.WINGS -> wings(piece, motion, out)
                CosmeticSlot.HEAD -> head(piece, motion, out)
                CosmeticSlot.HALO -> halo(piece, motion, out)
                CosmeticSlot.TAIL -> tail(piece, motion, out)
                CosmeticSlot.AURA -> {
                    // The storm cloud floats over tall ears, horns and hats instead of cutting through them.
                    val top = if (piece.style == CosmeticStyle.STORM_CLOUD) headTop(loadout) else null
                    val p = if (top != null) CosmeticRigD.clearHead(piece, top) else piece
                    if (CosmeticRigE.handles(p.style)) CosmeticRigE.aura(p, motion, out) else aura(p, motion, out)
                }
                CosmeticSlot.HAT -> CosmeticRigB.hat(piece, motion, out)
                CosmeticSlot.FACE -> CosmeticRigC.face(piece, motion, out)
                CosmeticSlot.BACK -> CosmeticRigC.back(piece, motion, out)
                CosmeticSlot.PET -> CosmeticRigD.pet(piece, motion, out)
                CosmeticSlot.SKIRT -> SkirtRig.place(piece, motion, loadout.body(), out)
                CosmeticSlot.TRAIL -> if (!live) TrailRig.preview(piece, motion, out)
                CosmeticSlot.CAPE -> if (!live) CapeRig.preview(piece, motion, out)
            }
        }
        return out
    }

    private val headTops = HashMap<List<CosmeticPiece?>, Float>()

    /**
     * Highest point (lowest y, head frame) of the worn head and hat pieces standing still, or null when neither
     * slot is worn. Cached per pair of pieces; render thread only.
     */
    @JvmStatic
    fun headTop(loadout: CosmeticLoadout): Float? {
        val head = loadout.piece(CosmeticSlot.HEAD)
        val hat = loadout.piece(CosmeticSlot.HAT)
        if (head == null && hat == null) return null
        val key = listOf(head, hat)
        headTops[key]?.let { return it }
        var top = -8f
        val v = org.joml.Vector3f()
        for (p in place(CosmeticLoadout.of(listOfNotNull(head, hat)), Motion.REST)) {
            if (p.anchor != Anchor.HEAD || p.kind != Kind.SOLID) continue
            val pos = p.mesh.positions
            for (i in pos.indices step 3) {
                p.transform.transformPosition(pos[i], pos[i + 1], pos[i + 2], v)
                if (v.y < top) top = v.y
            }
        }
        if (headTops.size > 64) headTops.clear()
        headTops[key] = top
        return top
    }

    // ---- wings: rooted high on the back, beating forward and down ----------------------------

    /** Wing beat in [-1, 1]; negative sweeps the tips forward and down. */
    @JvmStatic
    fun wingBeat(motion: Motion, speed: Float): Float = sin(motion.wingPhase * speed)

    private fun wings(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val devil = piece.style == CosmeticStyle.DEVIL_WINGS
        val mesh = if (devil) CosmeticModels.DEVIL_WING else CosmeticModels.ANGEL_WING
        val rootY = if (devil) CosmeticModels.DEVIL_ROOT_Y else CosmeticModels.ANGEL_ROOT_Y
        val swing = wingBeat(motion, piece.speed) * (0.3f + motion.air * 0.45f)
        // Moving sweeps the wings back and tilts them down a little, like they trail in the wind.
        val sweep = motion.moving * 0.35f
        for (side in intArrayOf(1, -1)) {
            val m = Matrix4f()
                // Upper back, just behind the body (which is 4 deep, so its back face is at z = 2).
                .translate(side * 0.8f, 1.5f - piece.up, 2.3f + piece.back)
                .scale(piece.size)
                .scale(side.toFloat(), 1f, 1f)
                .rotateY(-(0.5f + sweep + swing * 0.85f))
                .rotateZ(-0.1f + sweep * 0.25f - swing * 0.4f)
                .rotateX(0.12f + sweep * 0.3f + swing * 0.15f)
                .translate(0f, -rootY, 0f)
            out.add(Placement(Anchor.BODY, mesh, m, piece.color, piece.color2))
        }
    }

    // ---- head pieces: ears twitch, horns stay put ----------------------------------------------

    /**
     * Ear twitch: a quick decaying flick every few seconds, offset per player and per ear.
     * Returns 0 most of the time so the ears rest between twitches.
     */
    @JvmStatic
    fun earTwitch(seconds: Float, seed: Int, ear: Int, speed: Float): Float {
        if (speed <= 0f) return 0f
        val period = 2.6f / speed
        val local = seconds + ((seed * 31 + ear * 17) and 0xFF) / 255f * period
        val cycle = floor(local / period).toInt()
        // Some cycles skip the twitch so the rhythm is not mechanical.
        if (((cycle * 1103515245 + seed + ear) ushr 7) % 3 == 0) return 0f
        val t = local - cycle * period
        val flick = 0.35f
        if (t > flick) return 0f
        return sin(t / flick * TAU) * exp(-t * 6f)
    }

    private fun head(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        if (CosmeticRigB.handlesHead(piece.style)) return CosmeticRigB.head(piece, motion, out)
        for ((ear, side) in intArrayOf(1, -1).withIndex()) {
            // Size grows each piece from its base, which stays where it sits on the head.
            val m = Matrix4f().translate(0f, -piece.up, piece.back)
            m.scale(side.toFloat(), 1f, 1f)
            val mesh = when (piece.style) {
                CosmeticStyle.CAT_EARS -> {
                    val twitch = earTwitch(motion.seconds, motion.seed, ear, piece.speed)
                    // Pivot on the ear's outer base corner at the head's top corner; leaned ~40 degrees outward.
                    m.translate(3.6f, -8f, -1f).scale(piece.size).rotateZ(0.7f + twitch * 0.35f).rotateX(twitch * -0.3f).translate(-4f, 0f, 0f)
                    CosmeticModels.CAT_EAR
                }
                CosmeticStyle.DEMON_HORNS -> {
                    m.translate(2.0f, -7.6f, -2.2f).scale(piece.size)
                    CosmeticModels.DEMON_HORN
                }
                CosmeticStyle.RAM_HORNS -> {
                    m.translate(2.4f, -7.6f, -0.6f).scale(piece.size)
                    CosmeticModels.RAM_HORN
                }
                else -> {
                    m.translate(1.8f, -7.8f, -1.6f).scale(piece.size).rotateX(-0.2f)
                    CosmeticModels.CUTE_HORN
                }
            }
            out.add(Placement(Anchor.HEAD, mesh, m, piece.color, piece.color2))
        }
    }

    // ---- halo: spins and floats -----------------------------------------------------------------

    private fun halo(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val bob = sin(motion.seconds * 1.6f * piece.speed) * 0.8f
        val spin = motion.seconds * 0.9f * piece.speed
        val m = Matrix4f()
            .translate(0f, -11.5f - piece.up + bob, piece.back)
            .scale(piece.size)
            .rotateY(spin)
            .rotateX(0.08f)
        out.add(Placement(Anchor.HEAD, CosmeticModels.HALO, m, piece.color, piece.color2))
    }

    // ---- tails: a jointed chain that sways from the base ------------------------------------------

    /** Per-style shape: root pitch, bend per segment and how many segments bend, sway feel. */
    private class TailShape(val rootPitch: Float, val bend: Float, val bendSegments: Int, val swayRate: Float, val swayAmount: Float)

    private fun shape(style: CosmeticStyle): TailShape = when (style) {
        // Back and slightly down, then straight up: the J of the sheet.
        CosmeticStyle.STRAIGHT_TAIL -> TailShape(-1.75f, 0.42f, 5, 1.4f, 0.10f)
        // Keeps bending until the tip curls forward over itself.
        CosmeticStyle.CURLED_TAIL -> TailShape(-1.6f, 0.27f, 9, 1.9f, 0.08f)
        // Heavy: bends less and sways slowly.
        else -> TailShape(-1.45f, 0.30f, 5, 1.0f, 0.07f)
    }

    private fun tail(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val segments = CosmeticModels.tail(piece.style)
        val shape = shape(piece.style)
        val phase = motion.swayPhase * shape.swayRate * piece.speed
        // Base at the lower back (the body is 12 tall), just behind it.
        val m = Matrix4f().translate(0f, 10.5f - piece.up, 1.5f + piece.back).scale(piece.size).rotateX(shape.rootPitch)
        for ((index, mesh) in segments.withIndex()) {
            val lag = index * 0.55f
            val sway = sin(phase - lag) * shape.swayAmount * (0.4f + index * 0.18f)
            val lift = sin(phase * 0.5f - lag) * 0.03f
            if (index > 0) {
                m.translate(0f, -CosmeticModels.TAIL_SEGMENT_LENGTH.toFloat(), 0f)
                m.rotateX(if (index < shape.bendSegments) shape.bend else 0f)
            }
            m.rotateZ(sway).rotateX(lift)
            out.add(Placement(Anchor.BODY, mesh, Matrix4f(m), piece.color, piece.color2))
        }
    }

    // ---- auras: particles around the whole player ------------------------------------------------

    private const val GLOW_SPARKS = 34
    private const val STINK_PUFFS = 9
    private const val STINK_FLIES = 7

    private fun aura(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        if (CosmeticRigD.aura(piece, motion, out)) return
        if (piece.style == CosmeticStyle.STINK_AURA) stink(piece, motion, out) else glow(piece, motion, out)
    }

    /** Sparks rising around the body from the feet to above the head, fading in and out. */
    private fun glow(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val t = motion.seconds * piece.speed
        for (i in 0 until GLOW_SPARKS) {
            // Height offset from a hash, not the golden ratio: that would line the sparks up in a spiral.
            val rise = fract(t * 0.16f + fract(sin(i * 12.9898f) * 43758.547f) + motion.seed * 0.013f)
            val fade = sin(rise * PI.toFloat())
            if (fade < 0.05f) continue
            val angle = i * 2.39996f + t * 0.45f
            val radius = (6.5f + 1.6f * sin(i * 1.7f)) * piece.size
            val size = (0.8f + 0.8f * ((i * 7) % 5) / 4f) * fade
            val center = Matrix4f()
                .translate(cos(angle) * radius, 23f - rise * 34f - piece.up, sin(angle) * radius + piece.back)
                .rotateY(angle + t)
                .rotateX(i.toFloat())
            val color = if (i % 3 == 0) piece.color2 else piece.color
            val core = Matrix4f(center).scale(size).translate(-0.5f, -0.5f, -0.5f)
            out.add(Placement(Anchor.BODY, CosmeticModels.CUBE, core, color, color, Kind.GLOW, (230 * fade).toInt()))
            // A soft shell around each spark reads as glow.
            val shell = Matrix4f(center).scale(size * 2.4f).translate(-0.5f, -0.5f, -0.5f)
            out.add(Placement(Anchor.BODY, CosmeticModels.CUBE, shell, color, color, Kind.GLOW, (55 * fade).toInt()))
        }
    }

    /** Green haze drifting round the legs and flies buzzing round the player. */
    private fun stink(piece: CosmeticPiece, motion: Motion, out: MutableList<Placement>) {
        val t = motion.seconds * piece.speed
        for (i in 0 until STINK_PUFFS) {
            val angle = i * 0.698f + t * 0.25f * (if (i % 2 == 0) 1f else -1f)
            val radius = (4.5f + 2.5f * sin(i * 2.1f + t * 0.4f)) * piece.size
            val size = (4f + 2.5f * ((i * 5) % 4) / 3f) * (0.85f + 0.15f * sin(t * 1.3f + i)) * piece.size
            val y = 14f + (i % 4) * 2.6f + sin(t * 0.7f + i) * 1.2f - piece.up
            // Each puff is a small cluster of overlapping cubes, so it reads as a cloud rather than a box.
            for (blob in 0 until 3) {
                val offset = PUFF_OFFSETS[blob]
                val m = Matrix4f()
                    .translate(cos(angle) * radius, y, sin(angle) * radius + piece.back)
                    .rotateY(angle * 0.5f + i)
                    .translate(offset[0] * size, offset[1] * size, offset[2] * size)
                    .scale(size * offset[3])
                    .translate(-0.5f, -0.5f, -0.5f)
                val tone = if (blob == 1) shadeColor(piece.color, 0.85f) else piece.color
                out.add(Placement(Anchor.BODY, CosmeticModels.CUBE, m, tone, tone, Kind.TRANSLUCENT, 48))
            }
        }
        for (i in 0 until STINK_FLIES) {
            val w = 1.3f + i * 0.17f
            val a = t * w + i * 2.3f
            val radius = (7f + 2f * sin(t * 0.9f + i)) * piece.size
            // Buzz: a fast small wobble on top of the lazy orbit.
            val x = cos(a) * radius + sin(t * 23f + i * 5f) * 0.6f
            val z = sin(a) * radius + cos(t * 19f + i * 3f) * 0.6f
            val y = 6f + (i % 5) * 3.5f + sin(t * 2.7f + i) * 2.5f - piece.up
            val heading = atan2(cos(a), -sin(a))
            val m = Matrix4f().translate(x, y, z + piece.back).rotateY(heading).scale(piece.size)
            out.add(Placement(Anchor.BODY, CosmeticModels.FLY, m, FLY_WING, piece.color2))
        }
    }

    private const val FLY_WING = 0xDDE8E8

    /** Blob offsets (x, y, z as a fraction of the puff size) and relative sizes inside one haze puff. */
    private val PUFF_OFFSETS = arrayOf(floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0.35f, -0.25f, 0.1f, 0.7f),
        floatArrayOf(-0.3f, 0.15f, -0.2f, 0.75f))

    private fun shadeColor(rgb: Int, amount: Float): Int {
        val r = ((rgb shr 16 and 0xFF) * amount).toInt()
        val g = ((rgb shr 8 and 0xFF) * amount).toInt()
        val b = ((rgb and 0xFF) * amount).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    private fun fract(value: Float): Float = value - floor(value)
}
