package geiler.addons.client.macros.rotation

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Turns the view toward something, once per frame, off real elapsed time.
 *
 * The macro only says what to look at, every tick, for as long as it should hold; the turning happens in between, in
 * [advance], which [FrameRotations] calls from the point in the frame where the mouse is read. A view turned only on
 * ticks moves in twenty steps a second and looks chopped, however well each step is chosen.
 *
 * How far each frame turns is the [Rotator]'s business, by the settings of the Rotation Engine: looking at a mob is
 * Combat, looking along the way is General, the teleport item's angles are Teleport or Etherwarp. Every step depends on
 * the time the frame took and not on how many frames there are, so the turn looks the same at 60 and at 240 frames a
 * second. Steering is done by where the view points: the walk input presses the keys that move toward a direction
 * relative to it.
 *
 * A request goes stale a moment after it was made. The frame pump knows nothing about which macro is active, so a
 * control whose owner has stopped asking has to go quiet on its own.
 */
class LookControl : FrameSteered {
    private enum class Kind { POINT, ENTITY, ANGLES }

    private val rotator = Rotator()

    private var kind: Kind? = null
    private var point: Vec3? = null
    private var entity: Entity? = null
    private var heightFraction = 0.0
    private var sideX = 0.0
    private var sideZ = 0.0
    private var calm = false
    private var lastEntityY = 0.0
    private var hasLastY = false
    private var flyingUntilNanos = 0L

    /** 1 while the aim is calm, falling back to 0 over [CALM_FADE_SECONDS] once it no longer has to be. */
    private var calmLevel = 0.0
    private var yaw = 0.0f
    private var pitch = 0.0f

    /** The angles asked for are an etherwarp's, not a teleport jump's: each has a speed of its own. */
    private var warp = false
    private var requestedNanos = 0L
    private var staleLogged = false

    init {
        FrameRotations.register(this)
    }

    /** What the looking being done now counts as, for its engine and speed. */
    private fun activity(): RotationConfig.Activity = when (kind!!) {
        Kind.ENTITY -> RotationConfig.Activity.COMBAT
        Kind.POINT -> RotationConfig.Activity.GENERAL
        Kind.ANGLES -> if (warp) RotationConfig.Activity.ETHERWARP else RotationConfig.Activity.TELEPORT
    }

    /** What is being looked at, for diagnostics: POINT, ENTITY, ANGLES or NONE. */
    fun kindName(): String = kind?.name ?: "NONE"

    /** Looks at a fixed point in the world. */
    fun lookAt(target: Vec3) {
        kind = Kind.POINT
        point = target
        requestedNanos = System.nanoTime()
    }

    /**
     * Follows a spot on the hitbox of an entity as it is drawn, not as it was last ticked: a mob moves twenty times a
     * second and is shown interpolated in between, and a view that follows the ticked position trails what is on screen.
     *
     * @param heightFraction how far up the hitbox, 0 at the feet and 1 at the top
     * @param sideX how far from the middle of the hitbox along x, -1 to 1 where 1 is the edge
     * @param sideZ the same along z
     * @param calm the target is moving up or down fast: hold the pitch instead of following it
     */
    fun lookAtEntity(target: Entity, heightFraction: Double, sideX: Double, sideZ: Double, calm: Boolean) {
        kind = Kind.ENTITY
        if (entity !== target) {
            hasLastY = false
            flyingUntilNanos = 0L
            calmLevel = 0.0
        }
        entity = target
        this.heightFraction = heightFraction
        this.sideX = sideX
        this.sideZ = sideZ
        this.calm = calm
        requestedNanos = System.nanoTime()
    }

    /**
     * Turns to exact angles, quickly and precisely - for the teleport item, which cares about a fraction of a degree.
     *
     * @param warp the angles are an etherwarp's rather than a teleport jump's, which turns at a speed of its own
     */
    fun lookAlong(targetYaw: Float, targetPitch: Float, warp: Boolean) {
        kind = Kind.ANGLES
        this.warp = warp
        yaw = targetYaw
        pitch = targetPitch
        requestedNanos = System.nanoTime()
    }

    /** Forgets what was being looked at; call when nothing is any more. */
    fun release() {
        kind = null
        point = null
        entity = null
    }

    /** The angle between where the view points now and where it was asked to point, in degrees, or infinity when idle. */
    fun residualDegrees(player: LocalPlayer): Double {
        if (kind == null) return Double.POSITIVE_INFINITY
        val wanted = wanted(player, 1.0f) ?: return Double.POSITIVE_INFINITY
        val yawError = Mth.wrapDegrees(wanted.yaw - player.yRot).toDouble()
        val pitchError = (wanted.pitch - player.xRot).toDouble()
        return sqrt(yawError * yawError + pitchError * pitchError)
    }

    override fun advance(player: LocalPlayer, deltaSeconds: Float) {
        val stale = kind != null && System.nanoTime() - requestedNanos > FRESH_NANOS
        if (kind == null || stale) {
            // Nobody asks any more: slow down over a moment instead of stopping dead.
            if (stale && !staleLogged) {
                staleLogged = true
                Rotator.diagnostics?.invoke(
                    "stale request kind=${kind} sinceRequestMs=${(System.nanoTime() - requestedNanos) / 1_000_000L} speed=${"%.1f".format(rotator.speed())}",
                )
            }
            val coast = rotator.coast(deltaSeconds.toDouble())
            if (coast[0] != 0.0 || coast[1] != 0.0) player.turn(coast[0] / TURN_SCALE, coast[1] / TURN_SCALE)
            return
        }
        staleLogged = false
        val partialTick = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(true)
        val wanted = wanted(player, partialTick) ?: return

        val pitchError = (wanted.pitch - player.xRot).toDouble()
        val step = rotator.step(
            RotationConfig.current().settings, activity(), player.yRot.toDouble(), player.xRot.toDouble(),
            wanted.yaw.toDouble(), wanted.pitch.toDouble(), distance(player, partialTick), deltaSeconds.toDouble(),
        )
        val yawStep = step[0]
        var pitchStep = step[1]
        val calmness = if (kind == Kind.ENTITY) calmness(deltaSeconds) else 0.0
        if (calmness > 0.0) {
            // Yaw is left alone: the target is moving along the ground as much as through the air, and the view
            // should stay on it sideways. Up and down it holds still until the target is well off, and then eases.
            // Calm gives way to normal aiming gradually, or the pitch that was held back would be caught up in a snap.
            val excess = abs(pitchError) - CALM_PITCH_DEADZONE
            var calmStep = 0.0
            if (excess > 0.0) {
                val ease = 1.0 - exp(-RESPONSE * deltaSeconds)
                val ceilingPitch = CALM_PITCH_MAX_SPEED * deltaSeconds
                calmStep = max(-ceilingPitch, min(ceilingPitch, sign(pitchError) * excess * CALM_PITCH_SHARE * ease))
            }
            pitchStep = pitchStep * (1.0 - calmness) + calmStep * calmness
        }
        // Through the same call the mouse makes: it also moves the previous rotation, which is what keeps the
        // picture between ticks smooth.
        player.turn(yawStep / TURN_SCALE, pitchStep / TURN_SCALE)
    }

    /**
     * Whether the target has been moving up or down fast, seen from frame to frame. The macro notices the same thing
     * once a tick, but a mob that has just been hit goes up in the middle of one, and the view turns every frame:
     * waiting for the next tick to hear of it would be up to 50 ms of chasing it.
     */
    private fun calmness(deltaSeconds: Float): Double {
        val flying = flyingNow(deltaSeconds)
        calmLevel = if (calm || flying) 1.0 else max(0.0, calmLevel - deltaSeconds / CALM_FADE_SECONDS)
        return calmLevel
    }

    private fun flyingNow(deltaSeconds: Float): Boolean {
        val now = System.nanoTime()
        val y = entity?.getPosition(Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(true))?.y ?: 0.0
        if (hasLastY && deltaSeconds > 0.0f && abs(y - lastEntityY) / deltaSeconds > FLYING_SPEED) {
            flyingUntilNanos = now + FLYING_HOLD_NANOS
        }
        lastEntityY = y
        hasLastY = true
        return now < flyingUntilNanos
    }

    /** How far away what is looked at is, or NaN for bare angles. */
    private fun distance(player: LocalPlayer, partialTick: Float): Double {
        val eye = player.getEyePosition(partialTick)
        return when (kind!!) {
            Kind.POINT -> point?.let { eye.distanceTo(it) } ?: Double.NaN
            Kind.ENTITY -> entity?.let { eye.distanceTo(it.getPosition(partialTick)) } ?: Double.NaN
            Kind.ANGLES -> Double.NaN
        }
    }

    private fun wanted(player: LocalPlayer, partialTick: Float): SmoothRotation.LookAngles? {
        when (kind) {
            Kind.ANGLES -> return SmoothRotation.LookAngles(yaw, pitch)
            Kind.POINT -> {
                val target = point ?: return null
                val angles = SmoothRotation.lookAt(player.getEyePosition(partialTick), target)
                val falling = !player.onGround() && player.deltaMovement.y < -0.1
                val limit = if (falling) FALL_PITCH_DOWN_LIMIT else WALK_PITCH_DOWN_LIMIT
                var pitch = angles.pitch.toDouble()
                if (pitch > limit) {
                    pitch = limit
                } else if (pitch < -WALK_PITCH_UP_FREE) {
                    pitch = max(-WALK_PITCH_UP_LIMIT, -WALK_PITCH_UP_FREE + (pitch + WALK_PITCH_UP_FREE) * WALK_PITCH_UP_SHARE)
                }
                return SmoothRotation.LookAngles(angles.yaw, pitch.toFloat())
            }
            Kind.ENTITY -> {
                val target = entity
                if (target == null || target.isRemoved) return null
                val shown: AABB = target.boundingBox.move(target.getPosition(partialTick).subtract(target.position()))
                val center = shown.center
                val spot = Vec3(
                    center.x + sideX * shown.xsize * 0.5,
                    Mth.lerp(heightFraction, shown.minY, shown.maxY),
                    center.z + sideZ * shown.zsize * 0.5,
                )
                return SmoothRotation.lookAt(player.getEyePosition(partialTick), spot)
            }
            null -> return null
        }
    }

    companion object {
        /** How long a request holds without being repeated. A tick is 50 ms, so this is five missed ticks. */
        private const val FRESH_NANOS = 250_000_000L

        /** Rate at which the remaining pitch shrinks, per second, while the aim is calm. */
        private const val RESPONSE = 14.0

        /** `Entity.turn` multiplies what it is given by this before applying it. */
        private const val TURN_SCALE = 0.15

        /**
         * How far down, in degrees, the view goes to look at a point on the way, when walking and when falling. A route
         * that leads down a ledge has its far end well below the eyes, and looking straight at it is a dive no player
         * makes while walking on: the head stays about level and the feet do the going down.
         */
        private const val WALK_PITCH_DOWN_LIMIT = 10.0
        private const val FALL_PITCH_DOWN_LIMIT = 6.0

        /**
         * The same going up. A flight of stairs puts the point on the way, a few blocks ahead along it, high above the
         * eyes, and the view went up to it - forty degrees at the sky, all the way up. Somebody climbing stairs looks at
         * where the stairs go, a little up, not at the top of the wall: beyond a few degrees up only a share of it is
         * followed, and never more than this.
         */
        private const val WALK_PITCH_UP_FREE = 3.0
        private const val WALK_PITCH_UP_SHARE = 0.3
        private const val WALK_PITCH_UP_LIMIT = 12.0

        /**
         * Calm aim, for a target that is moving up or down fast - one that has just been knocked into the air. The view
         * does not chase it up and down: the pitch holds while the target stays within this many degrees of it, and
         * beyond that follows slowly, with only a share of the error and a low ceiling on the speed.
         */
        private const val CALM_PITCH_DEADZONE = 8.0
        private const val CALM_PITCH_SHARE = 0.2
        private const val CALM_PITCH_MAX_SPEED = 45.0
        private const val CALM_FADE_SECONDS = 0.5

        /** A target that moves up or down faster than this, in blocks a second, is flying; the aim stays calm 0.6 s after. */
        private const val FLYING_SPEED = 2.4
        private const val FLYING_HOLD_NANOS = 600_000_000L
    }
}
