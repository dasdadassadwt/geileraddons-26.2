package geiler.addons.client.macros.input

import net.minecraft.client.player.ClientInput
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Input
import net.minecraft.world.phys.Vec2

/**
 * The keyboard. Holds the keys a player would hold to move in a given direction, and nothing a keyboard cannot do.
 *
 * That restriction is the design, not a simplification. The movement the game reads is a pair of impulses built out of
 * four keys, so the only directions that can be asked for are the eight the keys spell out - and the keys themselves are
 * sent to the server. A smooth analogue vector would move the player in a direction no key combination could produce,
 * which is both wrong and the kind of wrong that is trivial to notice. So every tick is one of the eight directions the
 * keys spell out, exactly the way `KeyboardInput` builds it.
 *
 * Which of the eight is not simply the nearest, though. The view turns smoothly and lags behind a route that bends, and
 * the nearest key direction can be more than twenty degrees off the wanted one for as long as it does - which carries a
 * player sideways off their line by a tenth of a block a tick at the plain speed, and three times that on a server that
 * makes its players three times as fast. So a direction between two key directions is walked as a mix of the two, a tick
 * of the one and a tick of the other in the proportion that averages out to it: forward with a tap of strafe every so
 * often, the way a player holds a line while looking a little to one side of it.
 *
 * The vector is rebuilt in [tick], which the game calls from the movement code itself, so it is always measured against
 * the view as it is at that moment rather than as it was when the macro last had an opinion.
 */
class WalkInput(private val player: LocalPlayer) : ClientInput() {
    private var waterTicks = 0
    private var worldYaw = Float.NaN
    private var moving = false
    private var jumping = false
    private var sprinting = false
    private var sneaking = false

    /** The walk wants the sideways keys even with the view off the heading: a step aside to get off a corner. */
    private var strafeAsked = false

    /** The line is one that needs the exact heading - a ledge, a jump, a tight passage - and keeps the sector mix; else the view leads and the keys push forward. */
    private var preciseAsked = false

    private val mixer = SectorMixer()
    private val jumpGuard = JumpGuard()

    /** Keys are held back while the view is further than this from the wanted heading, in degrees; 0 is off. See [TurnFirst]. */
    private var turnFirstDegrees = 0.0
    private var heldTicks = 0
    private var holding = false

    /** Move toward a direction in the world, given as the yaw that faces it. */
    fun walk(yaw: Float, sprint: Boolean, jump: Boolean, strafe: Boolean = false, precise: Boolean = false) {
        worldYaw = yaw
        moving = true
        sprinting = sprint
        jumping = jump
        strafeAsked = strafe
        preciseAsked = precise
    }

    /** The yaw the keys are being pointed at, or NaN when none is asked for; for diagnostics. */
    fun heading(): Float = if (moving) worldYaw else Float.NaN

    /** Sets how far off the wanted heading the view may be, on the ground, before the movement keys are let go of. 0 is off. */
    fun turnFirst(degrees: Double) {
        turnFirstDegrees = degrees
    }

    /** Whether the keys are being held back at this moment because the view has not turned to the heading yet. */
    fun isHolding(): Boolean = holding

    /** Stay put. Sneaking is left alone: a teleport has to stand still while crouched. */
    fun stand(jump: Boolean) {
        moving = false
        sprinting = false
        jumping = jump
    }

    fun sneak(sneak: Boolean) {
        sneaking = sneak
    }

    /** Everything off, for when the macro stops or a screen opens. */
    fun releaseAll() {
        moving = false
        jumping = false
        sprinting = false
        sneaking = false
        waterTicks = 0
        worldYaw = Float.NaN
        mixer.reset()
        heldTicks = 0
        holding = false
        keyPresses = Input.EMPTY
        moveVector = Vec2.ZERO
    }

    override fun tick() {
        var forward = false
        var backward = false
        var left = false
        var right = false

        holding = false
        if (moving && !worldYaw.isNaN()) {
            // The wanted direction in sectors to the right of the view: 0 is forward, 2 is right, 4 is back, 6 is left.
            val off = Mth.wrapDegrees(worldYaw - player.yRot)
            // The view leads and the keys follow it. A person runs where they look: W, with the mouse turning to the way, and no A or D
            // to get to a heading the view will be on in a moment. Only a line that needs the exact heading keeps the sideways keys, and
            // only a view far off the heading holds the keys up - for a moment - until it has turned.
            val decision = TurnFirst.decide(off.toDouble(), turnFirstDegrees, player.onGround(), strafeAsked, preciseAsked, heldTicks)
            if (decision == TurnFirst.Decision.HOLD) {
                holding = true
                heldTicks++
            } else if (!(player.onGround() && !strafeAsked && TurnFirst.beyond(off.toDouble(), turnFirstDegrees))) {
                heldTicks = 0
            }
            val sector = when (decision) {
                TurnFirst.Decision.HOLD -> NO_SECTOR
                TurnFirst.Decision.FORWARD -> 0
                TurnFirst.Decision.MIX -> mixer.next(off / SECTOR_DEGREES)
            }
            when (if (sector == NO_SECTOR) NO_SECTOR else Math.floorMod(sector, 8)) {
                NO_SECTOR -> Unit
                0 -> forward = true
                1 -> {
                    forward = true
                    right = true
                }
                2 -> right = true
                3 -> {
                    backward = true
                    right = true
                }
                4 -> backward = true
                5 -> {
                    backward = true
                    left = true
                }
                6 -> left = true
                else -> {
                    forward = true
                    left = true
                }
            }
        }

        // In water the player treads at the surface, jump held, and does not sprint. Sprinting in water is what starts
        // the swimming pose, which swims along the view - down into the water, against the wall - and the jump key held
        // is what keeps a player up on top of it. The surface bobs the player in and out of the water, so this holds on
        // for a moment after the last time they were in it.
        if (player.isInWater || player.isSwimming) {
            waterTicks = WATER_GRACE_TICKS
        } else if (waterTicks > 0) {
            waterTicks--
        }
        val inWater = waterTicks > 0
        val jump = jumpGuard.filter(jumping || (inWater && !player.onGround()), player.abilities.mayfly)
        val sprint = sprinting && !inWater

        // Letting go of the sprint key does not end a sprint: the player runs on for as long as forward is held. Somebody who
        // wants to stop sprinting and go on lets go of forward for a moment - one tick of it, on the ground, and the sprint is
        // over; the next tick walks. (In the air the sprint is left alone: a jump is not to be cut short.)
        if (forward && !sprint && player.isSprinting && player.onGround()) {
            forward = false
        }

        // Sprinting only counts while actually pressing forward, which is also all the game will accept.
        keyPresses = Input(forward, backward, left, right, jump, sneaking, sprint && forward)
        moveVector = Vec2(impulse(left, right), impulse(forward, backward)).normalized()
    }

    private fun impulse(positive: Boolean, negative: Boolean): Float {
        if (positive == negative) return 0.0f
        return if (positive) 1.0f else -1.0f
    }

    companion object {
        /** The angle between two neighbouring key directions. */
        private const val SECTOR_DEGREES = 45.0

        private const val NO_SECTOR = Int.MIN_VALUE

        /** Ticks the water rules go on for after the player was last in water: the surface bobs them in and out of it. */
        private const val WATER_GRACE_TICKS = 12
    }
}


/**
 * Which of the eight key directions to hold for a wanted heading [sectors] sectors (of 45 degrees) to the right of the view.
 * Near a key direction it is simply taken: within [NEAR_ENOUGH] of a sector - about twelve degrees - only the one key set, so a
 * run along a straight line is W alone, the way a person runs it, however the view wobbles. Between two key directions the further
 * one gets its share of the ticks, spread as evenly as they can be: forward with a tap of strafe, which holds a line while the view
 * lags a bend.
 */
class SectorMixer {
    /** What is owed to the further of the two key directions the wanted one lies between: it gets a tick whenever this reaches one. */
    private var owed = 0.5

    fun reset() {
        owed = 0.5
    }

    /** The sector to hold this tick, not yet wrapped into 0 to 7. */
    fun next(sectors: Double): Int {
        val nearer = Math.floor(sectors).toInt()
        val share = sectors - nearer
        if (share < NEAR_ENOUGH || share > 1.0 - NEAR_ENOUGH) return if (share < 0.5) nearer else nearer + 1
        owed += share
        if (owed >= 1.0) {
            owed -= 1.0
            return nearer + 1
        }
        return nearer
    }

    companion object {
        /** This near to a key direction, as a share of a sector, it is simply taken: about twelve degrees. */
        const val NEAR_ENOUGH = 0.27
    }
}

/**
 * Turning before walking. When the view is further than [limit] degrees from the heading the keys are let go of until it has
 * turned - at most [MAX_HOLD_TICKS] ticks, so a view that cannot turn does not hold the player for ever.
 */
object TurnFirst {
    const val MAX_HOLD_TICKS = 10

    @JvmStatic
    fun beyond(offDegrees: Double, limit: Double): Boolean = limit > 0.0 && Math.abs(offDegrees) > limit

    @JvmStatic
    fun hold(offDegrees: Double, limit: Double, heldTicks: Int): Boolean = beyond(offDegrees, limit) && heldTicks < MAX_HOLD_TICKS

    /** What the keys do this tick: held up while the view turns, forward alone, or mixed to get to a heading between key directions. */
    enum class Decision { HOLD, FORWARD, MIX }

    /**
     * The decision for a view [offDegrees] from the wanted heading. On the ground, with no step aside asked for, a view past [limit]
     * holds the keys for at most [MAX_HOLD_TICKS] ticks. After that, and for a loose line (not [precise]) at any angle, the keys push
     * forward alone and the view catches up; a precise line keeps the old sector mix, with forward alone while the view is a
     * plain turn off ([runsOn]). A step aside asked for is always the mix.
     */
    @JvmStatic
    fun decide(offDegrees: Double, limit: Double, onGround: Boolean, strafeAsked: Boolean, precise: Boolean, heldTicks: Int): Decision {
        if (strafeAsked) return Decision.MIX
        if (onGround && beyond(offDegrees, limit) && heldTicks < MAX_HOLD_TICKS) return Decision.HOLD
        if (!precise) return Decision.FORWARD
        return if (onGround && runsOn(offDegrees, limit)) Decision.FORWARD else Decision.MIX
    }

    /** Below this many degrees off, the keys follow the heading as the sector mapping has it. */
    const val RUN_ON_FROM_DEGREES = 30.0

    /** Whether the view is off the heading by more than a plain correction but not by so much that the keys are held: W alone. */
    @JvmStatic
    fun runsOn(offDegrees: Double, limit: Double): Boolean {
        val off = Math.abs(offDegrees)
        return limit > 0.0 && off > RUN_ON_FROM_DEGREES && off <= limit
    }
}
