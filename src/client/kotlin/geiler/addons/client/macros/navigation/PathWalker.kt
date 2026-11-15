package geiler.addons.client.macros.navigation

import geiler.addons.client.macros.input.WalkInput
import geiler.addons.client.macros.killer.CameraLead
import geiler.addons.client.macros.pathing.MoveType
import geiler.addons.client.macros.pathing.Path
import geiler.addons.client.macros.pathing.PathingSettings
import geiler.addons.client.macros.pathing.Terrain
import geiler.addons.client.macros.rotation.SmoothRotation
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walks a finished route.
 *
 * It steers at a point of the route ahead - the furthest one, within a few blocks, that the body can walk to in a straight
 * line from where it is now. That one check does what a list of special cases did before: on open ground the point is far
 * ahead and the walk is straight and calm; round a corner the line to a point past the corner runs into the wall, so the
 * point is the one before it and the player goes round the corner instead of into it; and after a shove off the line, the
 * point is one that can actually be got back to.
 *
 * The ground is watched as well as the route. A step too high to walk that the player is about to run into - the walker
 * is never exactly on the line, and on rough ground the line runs close to such steps - is jumped onto, at the moment a
 * player would jump, instead of being walked into until the route is given up. And when there is no progress all the
 * same, the walker first hops, then steps to the side, and only then reports the route as stuck.
 *
 * Nothing here assumes how fast the player is. A server may give its players several times the plain walking speed, and
 * every distance that matters is a matter of ticks, not of blocks: how far ahead to steer, how far before a block the jump
 * key has to go down for the body to be over the edge when it gets there, how early to let go of the keys to come to rest
 * at the end of the route and not past it. All of these are worked out from the speed the player has and the game's own
 * motion - a push every tick on the ground, 0.546 of the speed kept from one tick to the next. The same goes for what
 * cannot be done at speed at all: a ledge is walked off, not run off, and a sharp turn with a drop beyond it is taken
 * slowly enough to make it, by letting go of the keys in time.
 *
 * It keeps its speed where a player would. It sprints through bends that are not sharp, up to the end of the route, and
 * into a jump onto a block. And on a long straight stretch it jumps as it sprints, when that is the faster way to move at
 * the speed the player has: at the plain walking speed a sprint with jumps covers a quarter more ground than one without,
 * and at the speeds some servers give their players it covers less, so that too is worked out and not assumed.
 *
 * It also decides when the route has stopped working: standing still or drifting off the line is reported rather than
 * pushed through - the caller replans, which is cheap, instead of grinding against a wall, which never resolves.
 */
class PathWalker {
    enum class Status {
        /** Following the route. */
        WALKING,

        /** The end of the route is reached. */
        ARRIVED,

        /** No progress for a while: something is in the way that the route does not know about. */
        STUCK,

        /** Too far off the line to keep following it. */
        OFF_PATH,

        /** Nothing to follow. */
        NO_PATH,
    }

    private var path: Path? = null
    private var progress = 0.0
    private var bestProgress = 0.0
    private var stallTicks = 0
    private var jumpTicks = 0

    /** How much earlier than it has to the next jump key goes down: 0 to JUMP_GIVE. */
    private var jumpGive = 0.0
    private var cameraPoint: Vec3? = null

    /** The point steered at, kept while the player is in the air, and whether the line to it had no room to spare. */
    private var steerPoint: Vec3? = null
    private var steerTight = false

    /** A step to the side under way: ticks left, and to which side. */
    private var sidestepTicks = 0
    private var sidestepSign = 1.0f

    /** Hops made at something the route did not know about since the last progress; the planned jump being tried and how often. */
    private var nudges = 0
    private var jumpEdge = Double.NaN
    private var jumpAttempts = 0

    /** How often the keys were let go of because the ground ended within the slide, for the log. */
    private var edgeBrakes = 0

    fun edgeBrakes(): Int = edgeBrakes

    /** Why the last [Status.STUCK] was given, for the log: a wall above the jump, or a planned jump that does not land. */
    private var stuckReason: String? = null

    /** The speed the gain of jumping was last worked out for, whether it gains, and how far one such jump goes. */
    private var hopSpeed = Double.NaN
    private var hopGains = false
    private var hopLength = 0.0

    /** Starts following a route, measuring progress from wherever the player already is on it. */
    fun follow(route: Path?, position: Vec3) {
        path = route
        progress = if (route == null) 0.0 else route.project(position, 0.0).distance
        bestProgress = progress
        stallTicks = 0
        jumpTicks = 0
        cameraPoint = null
        steerPoint = null
        steerTight = false
        sidestepTicks = 0
        resetClimb()
    }

    private fun resetClimb() {
        nudges = 0
        jumpEdge = Double.NaN
        jumpAttempts = 0
        stuckReason = null
    }

    /** Why the walker said STUCK last, or null when it has not. */
    fun stuckReason(): String? = stuckReason

    /**
     * Carries on from a place further along the route than walking could have got to by now - after a
     * teleport. The projection only looks a few blocks ahead of the last progress, so without this the
     * jump would read as having left the route.
     *
     * @param expectedProgress where on the route the player is expected to have come out
     */
    fun seek(expectedProgress: Double, position: Vec3) {
        val route = path ?: return
        progress = route.project(position, expectedProgress).distance
        bestProgress = progress
        stallTicks = 0
        jumpTicks = 0
        steerPoint = null
        sidestepTicks = 0
        // The camera point still belongs to the place before the jump, which is now behind the player. Left alone
        // for the one tick before the next walk tick replaces it, the view would start to swing round toward it at
        // full speed - a flick of 36 degrees and straight back.
        cameraPoint = route.pointAt(progress + CAMERA_LOOKAHEAD)
    }

    fun clear() {
        path = null
        cameraPoint = null
        steerPoint = null
        progress = 0.0
        bestProgress = 0.0
        stallTicks = 0
        jumpTicks = 0
        sidestepTicks = 0
        resetClimb()
    }

    fun path(): Path? = path

    fun progress(): Double = if (path == null) 0.0 else progress

    /** Where the camera should be looking while walking, or null when there is no route. */
    fun cameraPoint(): Vec3? = cameraPoint

    /** How far the player was from the route at the last tick, in blocks; 0 when there is none. */
    fun deviation(): Double = lastDeviation

    private var lastDeviation = 0.0

    /** How much route is left, or zero when there is none. */
    fun remaining(): Double = path?.let { max(0.0, it.length() - progress) } ?: 0.0

    fun tick(player: LocalPlayer, input: WalkInput, settings: PathingSettings): Status {
        val route = path ?: return Status.NO_PATH

        // The speed of the player without the sprint, and how much longer than at the plain speed every distance has to be.
        val speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED) / (if (player.isSprinting) SPRINT_FACTOR else 1.0)
        val stretch = Mth.clamp(speed / PLAIN_SPEED, 1.0, MAX_STRETCH)

        val projection = route.project(player.position(), progress, 6.0 * stretch)
        // Monotonic: sliding backwards off a ledge must not rewind the route and start it over.
        progress = max(progress, projection.distance)
        lastDeviation = projection.deviation
        if (projection.deviation > MAX_DEVIATION * sqrt(stretch)) {
            input.stand(player.isInWater)
            return Status.OFF_PATH
        }

        val remaining = route.length() - progress
        if (remaining <= ARRIVAL_DISTANCE) {
            cameraPoint = visibleAhead(player, min(2.0, stretch), null)
            input.stand(player.isInWater)
            return Status.ARRIVED
        }

        val upcoming = route.upcoming(progress, SPECIAL_LOOKAHEAD * stretch)
        val toSpecial = if (upcoming == null) Double.POSITIVE_INFINITY else upcoming.edgeDistance - progress
        val straightAhead = nextTurn(SPRINT_STRAIGHT_AHEAD * stretch, TURN_COSINE) < 0
        val nearDrop = upcoming != null && upcoming.move == MoveType.DROP && toSpecial <= SPRINT_CLEAR_OF_DROP * stretch
        val wantSprint = settings.sprint() && settings.sprintsAt(remaining) && remaining > SPRINT_MIN_REMAINING &&
            !nearDrop && straightAhead && !player.isInWater

        // Off a ledge the player walks on in the direction they were going and falls on the way, which is an arc
        // down the wall's far side - not along the face of it. Steering at the lower stand instead, which is
        // right under the edge, puts the aim almost on top of the player: it flips as they move, the keys flip
        // with it, and they crawl at the lip. So from a block before the edge, and all the way down, the aim
        // is straight ahead and the route catches up after the landing.
        val straight = dropHeading(upcoming, toSpecial)
        val dropping = straight != null
        val steer: Vec3 = if (straight != null) {
            player.position().add(straight.scale(DROP_STEER_DISTANCE))
        } else {
            steerAt(player, upcoming, toSpecial, remaining, wantSprint, stretch)
        }
        // The keys are pointed from where the player's speed is taking them, not from where they are: what they have kept
        // of it carries them on for another 1.2 ticks' worth whatever is pressed now, and pressing straight at the point
        // from here lets that carry them wide of every bend. Aimed from where the slide ends, the push takes the
        // sideways part of the speed out as well.
        val motion = player.deltaMovement
        var from = player.position()
        if (player.onGround() && !dropping) {
            val carried = from.add(motion.x * SLIDE_TICKS, 0.0, motion.z * SLIDE_TICKS)
            val dx = steer.x - carried.x
            val dz = steer.z - carried.z
            if (dx * dx + dz * dz > 0.5 * 0.5) from = carried
        }
        // The view goes along the way the keys push, see CameraAim: a route point on the far side of a bend is not looked at.
        cameraPoint = visibleAhead(player, min(2.0, stretch), steer)
        var yaw = SmoothRotation.lookAt(from, Vec3(steer.x, from.y, steer.z)).yaw
        if (from !== player.position() && !freeToward(player, yaw)) {
            // Aimed like that the keys would push into a wall - the inside of a turn in a passage. Then straight at the point.
            yaw = SmoothRotation.lookAt(player.position(), Vec3(steer.x, player.y, steer.z)).yaw
        }
        var stepping = false
        if (sidestepTicks > 0) {
            sidestepTicks--
            yaw += sidestepSign * SIDESTEP_DEGREES
            stepping = true
        }

        var sprint = wantSprint && !dropping && sidestepTicks == 0
        // What the player has kept of their speed, and how far this tick takes them with the keys down.
        val kept = sqrt(motion.x * motion.x + motion.z * motion.z)
        val push = speed * (if (player.isSprinting) SPRINT_FACTOR else 1.0) * INPUT
        val stride = kept + push

        val hop = sprint && settings.sprintJump() && player.onGround() && hops(player, yaw, remaining, toSpecial)
        val jump = decideJump(player, upcoming, toSpecial, yaw, kept, push, stride, settings.pathRandomness(), hop)
        // A jump is made as the player is moving: the sprint is not taken away in the tick the key goes down, which would
        // mean letting go of forward for that tick - a jump with nothing behind it, that comes down short of the block.
        if (jump && player.onGround() && player.isSprinting && wantSprint) sprint = true
        val edgeHit = !jump && !dropping && player.onGround() && !player.isInWater && edgeUnderfoot(player, yaw, kept, push, upcoming, toSpecial)
        if (edgeHit) edgeBrakes++
        if (edgeHit || (!jump && player.onGround() && !player.isInWater && sidestepTicks == 0 &&
                tooFast(player, upcoming, toSpecial, remaining, kept, stride))
        ) {
            // Too fast for what is coming, or the ground ends within the slide: no keys for this tick, which is all the brake there is.
            input.stand(false)
        } else {
            // On a line with no room to spare the sprint goes, not the keys: a walk along it, not a stop and a go every other tick.
            if (steerTight && kept > KEPT_WHERE_TIGHT * (1.0 + (stretch - 1.0) * TIGHT_SPEED_SHARE)) sprint = false
            // A line that needs the exact heading - a tight passage, an edge, a jump close ahead - keeps the sideways keys; any other is
            // walked with the view leading and the keys pushing forward, the way a person runs where they look.
            val precise = steerTight || dropping || (upcoming != null && toSpecial <= PRECISE_NEAR_SPECIAL * min(stretch, PRECISE_MAX_STRETCH))
            input.walk(yaw, sprint, jump, stepping, precise)
        }

        if (progress > bestProgress + PROGRESS_EPSILON) {
            bestProgress = progress
            stallTicks = 0
            nudges = 0
        } else {
            stallTicks++
            // A wall higher than any jump, in the way: hopping at it is all that would be left, and it gets nowhere. Said at once, so the
            // caller can plan another way or teleport up, and the place is remembered.
            if (stallTicks >= UNCLIMBABLE_AFTER_TICKS && player.horizontalCollision && wallAboveJump(player, yaw)) {
                stuckReason = "wall above the jump height ahead jump=${String.format(java.util.Locale.ROOT, "%.2f", path!!.terrain().jumpHeight())}"
                return Status.STUCK
            }
            if (jumpAttempts >= MAX_JUMP_ATTEMPTS && !jumpEdge.isNaN() && progress < jumpEdge + 0.3) {
                stuckReason = "planned jump failed $jumpAttempts times"
                return Status.STUCK
            }
            // Against something and going nowhere: a step to the side at once. Without a touch it may only be a slow
            // stretch, and the step waits.
            val pinned = player.horizontalCollision && stallTicks >= PINNED_TICKS && sidestepTicks == 0 && !jump
            if (stallTicks == SIDESTEP_AFTER_TICKS || pinned) {
                // Nothing moved for a while: off to one side for a moment, the way somebody caught on a corner steps away from
                // it. The side is the one the route is on, if there is room there; failing that, the other.
                sidestepSign = freeSide(player, yaw)
                sidestepTicks = SIDESTEP_TICKS
            }
            if (stallTicks > STUCK_AFTER_TICKS) return Status.STUCK
        }
        return Status.WALKING
    }

    /**
     * The point to steer at: of the points of the route from a short way ahead out to a few blocks ahead - and never past
     * the edge of a jump or a drop - the furthest the body can walk to in a straight line from where it is. In the air
     * there is no floor to measure a line from, and the point chosen on the ground is kept.
     */
    private fun steerAt(
        player: LocalPlayer, upcoming: Path.Upcoming?, toSpecial: Double, remaining: Double, sprinting: Boolean, stretch: Double,
    ): Vec3 {
        val route = path!!
        var far = min(MAX_STEER_FAR, (if (sprinting) STEER_FAR_SPRINTING else STEER_FAR_WALKING) * stretch)
        far = min(remaining, far)
        if (upcoming != null) {
            // Steer at the edge itself when one is close, so the player meets it square instead of at an angle that carries
            // them off the side of the block they were supposed to land on.
            far = min(far, max(0.7, toSpecial))
        }
        val near = min(far, STEER_NEAR * min(2.0, stretch))
        val terrain = route.terrain()
        val grounded = player.onGround() && !player.isInWater
        if (!grounded) {
            val kept = steerPoint
            if (kept != null && route.project(kept, progress).distance > progress + 0.3) return kept
            return route.pointAt(progress + (if (player.isInWater) far else min(far, near + 0.4)))
        }
        val feet = player.position()
        val step = max(0.4, (far - near) / STEER_STEPS)
        // A line with room to spare if there is one, however short: a body that is steered along the tightest line that
        // will do grazes every pillar it passes. Only where there is none is a tight one taken, and then only a short one
        // - round a corner in a narrow passage the route itself is the line with the most room, and is kept to.
        for (pass in 0 until 2) {
            val from = if (pass == 0) far else min(far, near + TIGHT_STEER_REACH * stretch)
            // From the furthest in to the nearest, the nearest included: close before a jump or the end it is the only one.
            var ahead = from
            while (ahead > near - 1.0E-6) {
                val point = route.pointAt(progress + ahead)
                if (terrain.canWalkFrom(feet, point, pass == 0)) {
                    steerPoint = point
                    steerTight = pass == 1
                    return point
                }
                ahead = if (ahead - step < near && ahead > near + 1.0E-6) near else ahead - step
            }
        }
        steerTight = true
        val fallback = route.pointAt(progress + near)
        steerPoint = fallback
        return fallback
    }

    /**
     * Whether the player is too fast for something ahead, and the keys are to be let go of for this tick. The only brake a
     * player has is not pushing: every tick without the keys, 0.546 of the speed is left. So from a speed kept of k the
     * player comes down to a speed of f in (k - f) / 0.454 blocks, and that is how far before the thing the last push may
     * be. Three things are braked for: the end of the route, to come to rest there; a ledge, to walk off it and land where
     * the route says, not blocks beyond; and a sharp turn with a drop past it.
     */
    private fun tooFast(
        player: LocalPlayer, upcoming: Path.Upcoming?, toSpecial: Double, remaining: Double, kept: Double, stride: Double,
    ): Boolean {
        // The end: let go when a push would carry the player past it - and letting go gets them there. The second part is
        // what keeps a player who is standing a little short of the end from standing there for good.
        val rest = remaining - REST_BEFORE_END
        if (brakeNow(stride, rest + END_GIVE, 0.0) && kept / GROUND_LOST >= remaining - ARRIVAL_DISTANCE + 0.02) return true
        // For the rest, only a player who is faster than they should be there is braked: one who is not has to be let
        // go on, or they would stop short of the thing and never reach it.
        if (upcoming != null && upcoming.move == MoveType.DROP && kept > KEPT_AT_DROP &&
            brakeNow(stride, edgeAhead(player, upcoming, toSpecial), KEPT_AT_DROP)
        ) {
            return true
        }
        // A player at the plain speed makes every turn; only one who is faster has to be asked.
        if (kept <= KEPT_AT_TURN) return false
        val route = path!!
        val terrain = route.terrain()
        val overshoot = kept / GROUND_LOST
        val index = nextTurn(overshoot + stride + 0.5, TURN_COSINE)
        if (index < 0) return false
        // Turning, the player goes on in the old direction for as far as their speed takes them. Is there ground for that?
        val steps = route.steps()
        val corner = steps[index].position
        val before = steps[index - 1].position
        val inX = corner.x - before.x
        val inZ = corner.z - before.z
        val length = sqrt(inX * inX + inZ * inZ)
        if (length < 1.0E-6) return false
        val ground = terrain.groundAhead(corner, inX / length, inZ / length, overshoot)
        if (ground >= overshoot) return false
        val keptThere = max(KEPT_AT_TURN, ground * GROUND_LOST)
        return kept > keptThere && brakeNow(stride, steps[index].distance - progress, keptThere)
    }

    /**
     * Whether to jump now as part of sprinting along a straight stretch: the stretch is long, straight and looked along,
     * the body can walk the line of the jump and has the room over its head all along it, and at the speed the player
     * has a sprint with jumps is the faster one.
     */
    private fun hops(player: LocalPlayer, yaw: Float, remaining: Double, toSpecial: Double): Boolean {
        if (!player.isSprinting || player.isInWater || player.horizontalCollision || !hopGains(player)) return false
        // All the distances are on top of the length of the jump, which grows with the speed of the player.
        val length = hopLength + HOP_MARGIN
        if (remaining < length + HOP_MIN_REMAINING || toSpecial < length + HOP_CLEAR_OF_SPECIAL) return false
        if (abs(Mth.wrapDegrees(yaw - player.yRot)) > HOP_VIEW_DEGREES || nextTurn(length + HOP_STRAIGHT_AHEAD, HOP_COSINE) >= 0) {
            return false
        }
        val route = path!!
        val terrain = route.terrain()
        val feet = player.position()
        val landing = route.pointAt(progress + length)
        // In the air there is next to no steering: the jump goes where the player is moving and looking when it starts.
        // So both have to be along the line to where it comes down, and the line needs the room of a shortcut - more than
        // a walk along it would, which can still be corrected on the way.
        val lineX = landing.x - feet.x
        val lineZ = landing.z - feet.z
        val lineLength = sqrt(lineX * lineX + lineZ * lineZ)
        val motion = player.deltaMovement
        val moving = sqrt(motion.x * motion.x + motion.z * motion.z)
        if (lineLength < 1.0 || moving < 0.05 || (motion.x * lineX + motion.z * lineZ) / (moving * lineLength) < HOP_MOTION_COSINE) {
            return false
        }
        val lineYaw = (Mth.atan2(lineZ, lineX) * Mth.RAD_TO_DEG).toFloat() - 90.0f
        if (abs(Mth.wrapDegrees(lineYaw - player.yRot)) > HOP_VIEW_DEGREES || !terrain.canSlide(feet, landing)) return false
        // Room over the head all along the jump, over whatever floor is under it there.
        val half = player.bbWidth * 0.5
        var floor = feet.y
        val pieces = ceil(length).toInt()
        for (piece in 0..pieces) {
            val t = piece / pieces.toDouble()
            val x = Mth.lerp(t, feet.x, landing.x)
            val z = Mth.lerp(t, feet.z, landing.z)
            val under = terrain.surfaceUnder(x, z, floor, Terrain.STEP_HEIGHT)
            if (!under.isFinite()) return false
            floor = under
            // Ground to both sides as well: a jump lands a little off its line, and a walkway one block wide is walked.
            val sideX = -lineZ / lineLength * HOP_SIDE_GROUND
            val sideZ = lineX / lineLength * HOP_SIDE_GROUND
            if (!terrain.floorAtPoint(x + sideX, z + sideZ, floor, Terrain.STEP_HEIGHT + 0.1).isFinite() ||
                !terrain.floorAtPoint(x - sideX, z - sideZ, floor, Terrain.STEP_HEIGHT + 0.1).isFinite()
            ) {
                return false
            }
            if (floor < feet.y - HOP_MAX_DESCENT) {
                // Down a slope a jump ends in a fall from its top to wherever the ground has got to: not far enough to hurt.
                return false
            }
            val top = max(floor, feet.y) + player.bbHeight + HOP_HEADROOM
            if (!terrain.clearBox(AABB(x - half, floor + player.bbHeight - 0.2, z - half, x + half, top, z + half))) return false
        }
        return true
    }

    /**
     * Whether sprinting with jumps is faster than sprinting without at the speed this player has, by the game's own
     * motion. On the ground the player is pushed by their speed every tick and keeps 0.546 of what they had; a jump adds
     * 0.2 at once, and in the air the push is a fixed small one and 0.91 is kept. At the plain speed the jumps win by a
     * quarter. A player made several times faster gets several times the push on the ground and the same small one in the
     * air, and loses by jumping.
     */
    private fun hopGains(player: LocalPlayer): Boolean {
        val speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED)
        if (speed != hopSpeed) {
            hopSpeed = speed
            val push = speed * INPUT
            val running = push / GROUND_LOST
            // A few jumps one after the other, twelve ticks each, the last of them measured.
            var kept = running * GROUND_KEPT
            var covered = 0.0
            for (jump in 0 until 6) {
                var moved = kept + SPRINT_JUMP_PUSH + push
                covered = moved
                kept = moved * GROUND_KEPT
                for (tick in 1 until HOP_TICKS) {
                    moved = kept + AIR_PUSH * SPRINT_FACTOR
                    covered += moved
                    kept = moved * AIR_KEPT
                }
            }
            hopGains = covered / HOP_TICKS > running * HOP_MIN_GAIN
            hopLength = covered
        }
        return hopGains
    }

    /**
     * The first bend of the route ahead that is sharper than the cosine says, within the distance: the number of its
     * point, or -1 if the route runs on without one for that far.
     */
    private fun nextTurn(distance: Double, cosine: Double): Int {
        val steps = path!!.steps()
        for (i in 1 until steps.size - 1) {
            val corner = steps[i]
            val along = corner.distance - progress
            if (along <= 0.05) continue
            if (along > distance) return -1
            val previous = steps[i - 1]
            val next = steps[i + 1]
            val inX = corner.position.x - previous.position.x
            val inZ = corner.position.z - previous.position.z
            val outX = next.position.x - corner.position.x
            val outZ = next.position.z - corner.position.z
            val lengths = sqrt(inX * inX + inZ * inZ) * sqrt(outX * outX + outZ * outZ)
            if (lengths > 1.0E-6 && (inX * outX + inZ * outZ) / lengths < cosine) return i
        }
        return -1
    }

    /**
     * The point of the route ahead the view goes to: the one [CAMERA_LOOKAHEAD] along, or - when the route bends round a
     * corner and that one is behind the wall - the furthest one the eyes can see. Looking at a point behind a wall is
     * looking at the wall, from close up, which is what the view did at every corner of a twisting route.
     */
    private fun visibleAhead(player: LocalPlayer, stretch: Double, steer: Vec3?): Vec3 {
        val route = path!!
        val eye = player.getEyePosition()
        val lift = player.eyeHeight - CAMERA_BELOW_EYES
        val heading = if (steer == null) Double.NaN else Math.toDegrees(Math.atan2(-(steer.x - player.x), steer.z - player.z))
        // The view leads the point the keys are steered at by a little, and does not look far past it: heading and view then agree.
        val steerAhead = if (steer == null) Double.NaN else route.project(steer, progress, MAX_STEER_FAR + 2.0).distance - progress
        var ahead = CameraLead.ahead(route.length() - progress, steerAhead, CAMERA_LOOKAHEAD * stretch)
        while (ahead > CAMERA_MIN_LOOKAHEAD) {
            val point = route.pointAt(progress + ahead)
            if (heading.isNaN() || CameraAim.along(player.x, player.z, point.x, point.z, heading)) {
                val hit = player.level().clip(ClipContext(eye, point.add(0.0, lift, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                if (hit.type == HitResult.Type.MISS) return point
            }
            ahead -= CAMERA_STEP * stretch
        }
        // Nothing along the heading is in sight: the point the keys are steered at, which is where the player is going.
        return steer ?: route.pointAt(progress + CAMERA_MIN_LOOKAHEAD)
    }

    /**
     * The horizontal direction of a drop the player is on or about to step off, or null when there is none to speak of.
     * Within [DROP_COMMIT] blocks of the edge counts as about to.
     */
    private fun dropHeading(upcoming: Path.Upcoming?, toSpecial: Double): Vec3? {
        val current = path!!.segmentAt(progress)
        val from: Vec3
        val to: Vec3
        if (current != null && current.move == MoveType.DROP) {
            from = current.from
            to = current.to
        } else if (upcoming != null && upcoming.move == MoveType.DROP && toSpecial <= DROP_COMMIT) {
            from = upcoming.from
            to = upcoming.to
        } else {
            return null
        }

        val dx = to.x - from.x
        val dz = to.z - from.z
        val length = sqrt(dx * dx + dz * dz)
        return if (length < 1.0E-6) null else Vec3(dx / length, 0.0, dz / length)
    }

    private fun decideJump(
        player: LocalPlayer, upcoming: Path.Upcoming?, toSpecial: Double, yaw: Float, kept: Double, push: Double, stride: Double,
        randomness: Double, hop: Boolean,
    ): Boolean {
        // A jump takes a moment to leave the ground; releasing the key the same tick it went down can be
        // swallowed entirely, so it is held for a couple of ticks once started.
        if (jumpTicks > 0) {
            jumpTicks--
            return true
        }
        // Swimming: holding jump is what keeps a player at the surface instead of sinking to the floor.
        if (player.isInWater && !player.onGround()) return true
        if (!player.onGround()) return false
        // The key goes down when waiting one more tick would be too late - a tick is a stride, so that is up to a stride
        // early - and, since nobody presses it at the same distance every time, a little earlier still, drawn once per
        // jump. Early costs nothing: the top of a jump lasts for several ticks. Late is into the side of the block.
        val early = 1.0 + jumpGive * randomness
        var planned = false
        if (upcoming != null && upcoming.move == MoveType.JUMP) {
            val lead = jumpLead(player, upcoming.to.y - upcoming.from.y, kept, push)
            val ahead = edgeAhead(player, upcoming, toSpecial)
            // Not once the player is up there or past the edge: the route still calls the jump "ahead" until its far end.
            planned = ahead <= (lead + stride) * early && ahead > -0.2 && player.y < upcoming.to.y - 0.1
        }
        // A step too high to walk just in front, that the route did not plan a jump for.
        val obstacle = !planned && stepAhead(player, yaw, kept, push, stride * early)
        // Something the route did not know about, and against it for a while: a hop clears a fence post, a mob or a snow
        // layer, and if it does not, the step to the side and then the stall counter take over.
        val nudge = player.horizontalCollision && stallTicks >= HOP_AFTER_TICKS && stallTicks < SIDESTEP_AFTER_TICKS && nudges < MAX_NUDGES
        if (planned || obstacle || nudge || hop) {
            jumpTicks = 1
            jumpGive = ThreadLocalRandom.current().nextDouble(0.0, JUMP_GIVE)
            if (nudge && !planned && !obstacle && !hop) nudges++
            if (planned && upcoming != null) {
                if (jumpEdge.isNaN() || abs(jumpEdge - upcoming.edgeDistance) > 0.5) {
                    jumpEdge = upcoming.edgeDistance
                    jumpAttempts = 0
                }
                jumpAttempts++
            }
            return true
        }
        return false
    }

    /**
     * How far before the edge of a block this much higher the middle of the body has to be when the jump key goes down,
     * for the feet to be above the block when the front of the body gets to it. A jump rises 0.42 in its first tick and
     * less in each one after; a block is cleared in the third. What the body covers in the ticks before that - the
     * first with the push of the ground and of the sprint, the rest with what the air leaves of it - is the distance,
     * and at several times the plain speed it is several blocks.
     */
    private fun jumpLead(player: LocalPlayer, rise: Double, kept: Double, push: Double): Double {
        var power = player.getAttributeValue(Attributes.JUMP_STRENGTH)
        val boost = player.getEffect(MobEffects.JUMP_BOOST)
        if (boost != null) power += 0.1 * (boost.amplifier + 1)
        val gravity = player.getAttributeValue(Attributes.GRAVITY)
        val sprinting = player.isSprinting
        var moved = kept + push + (if (sprinting) SPRINT_JUMP_PUSH else 0.0)
        val air = AIR_PUSH * (if (sprinting) SPRINT_FACTOR else 1.0)
        var height = 0.0
        var covered = 0.0
        for (tick in 1..12) {
            height += power
            if (height >= rise - 1.0E-3) break
            covered += moved
            moved = moved * (if (tick == 1) GROUND_KEPT else AIR_KEPT) + air
            power = (power - gravity) * 0.98
        }
        return covered + player.bbWidth * 0.5 + JUMP_CLEARANCE
    }

    /**
     * Whether the floor in front of the body, in the direction it is going, rises by more than a step and no more than
     * a jump, with room for the body on top, and near enough that the jump has to be now: a step the game will not lift
     * the player onto, about to be run into. The floor is followed out from the front of the body point by point, and the
     * first rise is what counts - the first stair of a flight is half a block, which is walked, and the stair after it is
     * not a reason to jump.
     */
    private fun stepAhead(player: LocalPlayer, yaw: Float, kept: Double, push: Double, early: Double): Boolean {
        val terrain = path!!.terrain()
        val radians = (yaw * Mth.DEG_TO_RAD).toDouble()
        val dirX = -sin(radians)
        val dirZ = cos(radians)
        val front = player.bbWidth * 0.5
        val feet = player.y
        val jump = terrain.jumpHeight() - 0.05
        // No further out than the highest jump would have to be started from.
        val reach = jumpLead(player, jump, kept, push) + early
        var along = front + 0.05
        while (along <= reach) {
            val x = player.x + dirX * along
            val z = player.z + dirZ * along
            val floor = terrain.floorAtPoint(x, z, feet + jump * 0.5, jump * 0.5 + 0.05)
            if (!floor.isFinite() || floor <= feet + 0.05) {
                along += 0.1
                continue
            }
            if (floor - feet <= Terrain.STEP_HEIGHT + 0.02) return false
            if (along > jumpLead(player, floor - feet, kept, push) + early) return false
            // The body has to fit on top, a little further in, and the jump has to be free over the player's head.
            val half = front - 0.01
            val cx = player.x + dirX * (along + front)
            val cz = player.z + dirZ * (along + front)
            val onTop = AABB(cx - half, floor + 0.01, cz - half, cx + half, floor + 1.79, cz + half)
            val overHead = player.boundingBox.move(0.0, floor - feet, 0.0)
            return terrain.clearBox(onTop) && terrain.clearBox(overHead)
        }
        return false
    }

    /**
     * Whether the ground under the player ends within the distance they still get, with the push of this tick and the slide after
     * (see [EdgeGuard]), in the direction they are moving - or looking, from a standstill - and the route plans no jump or drop there.
     */
    private fun edgeUnderfoot(player: LocalPlayer, yaw: Float, kept: Double, push: Double, upcoming: Path.Upcoming?, toSpecial: Double): Boolean {
        val reach = EdgeGuard.travel(kept, push)
        val motion = player.deltaMovement
        val dirX: Double
        val dirZ: Double
        if (kept > 0.03) {
            dirX = motion.x / kept
            dirZ = motion.z / kept
        } else {
            val radians = (yaw * Mth.DEG_TO_RAD).toDouble()
            dirX = -sin(radians)
            dirZ = cos(radians)
        }
        val special = upcoming != null && toSpecial <= reach + EDGE_SPECIAL_MARGIN
        if (special) return false
        val ground = path!!.terrain().groundAhead(player.position(), dirX, dirZ, reach)
        return EdgeGuard.brake(ground, reach, false)
    }

    /**
     * Whether what the player is against reaches higher than any jump of theirs: the body, lifted the height of a jump less a little and
     * moved a bit on in the direction of the keys, is still in a block.
     */
    private fun wallAboveJump(player: LocalPlayer, yaw: Float): Boolean {
        val terrain = path!!.terrain()
        val radians = (yaw * Mth.DEG_TO_RAD).toDouble()
        val box = player.boundingBox.move(-sin(radians) * UNCLIMBABLE_REACH, terrain.jumpHeight() - 0.1, cos(radians) * UNCLIMBABLE_REACH)
        return !terrain.clearBox(box)
    }

    /** Whether the player can walk a little way in the direction of this yaw from where they stand. */
    private fun freeToward(player: LocalPlayer, yaw: Float): Boolean {
        val terrain = path!!.terrain()
        val radians = (yaw * Mth.DEG_TO_RAD).toDouble()
        val x = player.x - sin(radians) * FREE_PROBE
        val z = player.z + cos(radians) * FREE_PROBE
        val floor = terrain.surfaceUnder(x, z, player.y, Terrain.STEP_HEIGHT)
        return floor.isFinite() && terrain.canWalkFrom(player.position(), Vec3(x, floor, z), false)
    }

    /** Which side of the way the player is facing has room to step to: +1 or -1, as added to the yaw. */
    private fun freeSide(player: LocalPlayer, yaw: Float): Float {
        val route = path!!
        val terrain = route.terrain()
        // The side the route is on first: whatever is in the way is between the player and where they are going, and the
        // route goes round it on one side.
        val point = route.pointAt(progress + 0.5)
        val left = ((yaw + SIDESTEP_DEGREES) * Mth.DEG_TO_RAD).toDouble()
        val toward = -sin(left) * (point.x - player.x) + cos(left) * (point.z - player.z)
        val sign = if (abs(toward) < 0.05) (if (ThreadLocalRandom.current().nextBoolean()) 1.0f else -1.0f) else (if (toward > 0.0) 1.0f else -1.0f)
        for (side in floatArrayOf(sign, -sign)) {
            val radians = ((yaw + side * SIDESTEP_DEGREES) * Mth.DEG_TO_RAD).toDouble()
            val to = player.position().add(-sin(radians) * 1.0, 0.0, cos(radians) * 1.0)
            val floor = terrain.surfaceUnder(to.x, to.z, player.y, Terrain.STEP_HEIGHT)
            if (floor.isFinite() && terrain.canWalkFrom(player.position(), Vec3(to.x, floor, to.z), false)) return side
        }
        return sign
    }

    companion object {
        /** The share of its speed a player on the ground keeps from one tick to the next, and one in the air. */
        private const val GROUND_KEPT = 0.546
        private const val AIR_KEPT = 0.91

        /** What is lost per block of braking distance: one less the share kept. */
        private const val GROUND_LOST = 1.0 - GROUND_KEPT

        /** How many ticks' worth of the speed kept a player on the ground still slides with no keys down. */
        private const val SLIDE_TICKS = GROUND_KEPT / GROUND_LOST

        /** The plain walking speed, which the distances below are for; a faster player has them stretched. */
        private const val PLAIN_SPEED = 0.1
        private const val MAX_STRETCH = 5.0

        /** What the sprint adds to the speed, the push a tick in the air gives, and what a sprinting jump adds at once. */
        private const val SPRINT_FACTOR = 1.3
        private const val AIR_PUSH = 0.02 * 0.98
        private const val SPRINT_JUMP_PUSH = 0.2
        private const val INPUT = 0.98

        /** How close to the end of the route counts as arrived, and where short of the end the player aims to come to rest. */
        private const val ARRIVAL_DISTANCE = 0.4
        private const val REST_BEFORE_END = 0.15

        /** How far past that place a last push may carry the player. */
        private const val END_GIVE = 0.1

        /** How far ahead along the route the steering point may be, sprinting and walking, and the least it is. */
        private const val STEER_FAR_SPRINTING = 4.0
        private const val STEER_FAR_WALKING = 3.0
        private const val STEER_NEAR = 0.8
        private const val STEER_STEPS = 9
        private const val MAX_STEER_FAR = 12.0

        /** How far past the nearest point a line with only the room of a grid step may be steered along. */
        private const val TIGHT_STEER_REACH = 1.2

        /** How far in the direction of the keys the way has to be free for the aim that allows for the player's speed. */
        private const val FREE_PROBE = 0.7

        /** How far ahead the camera looks while walking, and the least it looks ahead when the route bends out of sight. */
        private const val CAMERA_LOOKAHEAD = 5.5

        /** Within this many blocks (times the stretch) of a jump or a drop the line is walked exactly. */
        private const val PRECISE_NEAR_SPECIAL = 3.0

        /** The stretch of a fast player counts only this far for that: at four times the speed the whole of twelve blocks was one long sidestep. */
        private const val PRECISE_MAX_STRETCH = 1.5

        /** How much of the extra speed of a fast player the limit on a tight line allows. */
        private const val TIGHT_SPEED_SHARE = 0.5
        private const val CAMERA_MIN_LOOKAHEAD = 2.0
        private const val CAMERA_STEP = 0.5

        /** The point is looked at this far under the eyes, as the macros do. */
        private const val CAMERA_BELOW_EYES = 0.25

        /** How far ahead a jump or a drop is worth noticing. */
        private const val SPECIAL_LOOKAHEAD = 4.0

        /** What is added to the worked-out distance of a jump: the body must be clear of the block, not touching it. */
        private const val JUMP_CLEARANCE = 0.06

        /** How much earlier, as a share of the distance, a jump may be at full randomness. Never later: late is into the block. */
        private const val JUMP_GIVE = 0.2

        /**
         * Sprinting goes on to the end of the route - letting go of the keys there, the player slides a third of a block,
         * which is what "arrived" allows - but not off a ledge, and not into a sharp turn.
         */
        private const val SPRINT_MIN_REMAINING = 1.0
        private const val SPRINT_CLEAR_OF_DROP = 1.3
        private const val SPRINT_STRAIGHT_AHEAD = 1.6

        /** Bends of the route sharper than this (the cosine: 60 degrees) are turns: no sprinting into them. */
        private const val TURN_COSINE = 0.5

        /**
         * The speed (as kept from tick to tick) a ledge is walked off at, and the least a turn is slowed to. Both are what a
         * sprint at the plain speed has, so that a player with no extra speed is never slowed.
         */
        private const val KEPT_AT_DROP = 0.16
        private const val KEPT_AT_TURN = 0.16
        private const val KEPT_WHERE_TIGHT = 0.3

        /**
         * Sprinting with jumps: only with route left and no jump or drop near beyond the length of the jump, on a stretch
         * that is straight to within about 15 degrees, with the view along it - the push of a sprinting jump goes where the
         * player looks - and only if it gains at least this share over running.
         */
        private const val HOP_MIN_REMAINING = 2.2
        private const val HOP_CLEAR_OF_SPECIAL = 1.7
        private const val HOP_STRAIGHT_AHEAD = 1.2
        private const val HOP_COSINE = 0.966

        /** What is checked beyond the length of the jump itself. */
        private const val HOP_MARGIN = 0.5
        private const val HOP_VIEW_DEGREES = 6.0f

        /** The cosine of how far the direction the player is moving in may be off the line of the jump: 6 degrees. */
        private const val HOP_MOTION_COSINE = 0.9945
        private const val HOP_MIN_GAIN = 1.05

        /** How high a jump lifts the head, with a little to spare. */
        private const val HOP_HEADROOM = 1.3
        private const val HOP_TICKS = 12

        /** How far to each side of the line of a jump there has to be ground. */
        private const val HOP_SIDE_GROUND = 0.8

        /** How far below the place it starts from the ground along a jump may be: with the height of the jump, a fall of 2.3. */
        private const val HOP_MAX_DESCENT = 1.0

        /** How close to a ledge the aim switches from the edge itself to straight ahead, in blocks. */
        private const val DROP_COMMIT = 1.0

        /** How far ahead of the player that straight-ahead aim is set. */
        private const val DROP_STEER_DISTANCE = 3.0

        /** Drifting further than this off the line means the route no longer describes where the player is. */
        private const val MAX_DEVIATION = 1.8

        /** Progress worth counting as progress, in blocks. */
        private const val PROGRESS_EPSILON = 0.25

        /** Ticks without progress before a hop, before a step to the side and for how long, and before giving up. */
        private const val HOP_AFTER_TICKS = 8
        private const val SIDESTEP_AFTER_TICKS = 16
        private const val PINNED_TICKS = 2
        private const val SIDESTEP_TICKS = 9
        private const val SIDESTEP_DEGREES = 70.0f
        private const val STUCK_AFTER_TICKS = 34

        /** Hops at an unknown obstacle before the player stops hopping, and tries of one planned jump before it is given up. */
        /** A jump or drop of the route this much past the slide still counts as meant: the edge is walked off, not braked for. */
        private const val EDGE_SPECIAL_MARGIN = 1.5

        private const val MAX_NUDGES = 2
        private const val MAX_JUMP_ATTEMPTS = 3

        /** Ticks against a wall before it is checked for being higher than a jump, and how far on the body is tried. */
        private const val UNCLIMBABLE_AFTER_TICKS = 5
        private const val UNCLIMBABLE_REACH = 0.3

        /**
         * How far the player is from the edge of a jump or a drop, on the ground: from the middle of the body to the line
         * between the two blocks, along the direction of the move. The distance along the route is not that - the route goes
         * up or down the height of the move as well, and counts it.
         */
        private fun edgeAhead(player: LocalPlayer, upcoming: Path.Upcoming, alongRoute: Double): Double {
            val dx = upcoming.to.x - upcoming.from.x
            val dz = upcoming.to.z - upcoming.from.z
            val length = sqrt(dx * dx + dz * dz)
            if (length < 1.0E-6) return alongRoute
            val edgeX = (upcoming.from.x + upcoming.to.x) * 0.5
            val edgeZ = (upcoming.from.z + upcoming.to.z) * 0.5
            val ahead = ((edgeX - player.x) * dx + (edgeZ - player.z) * dz) / length
            // Round a bend before it the straight measure means nothing yet; the route's own is the one to go by there.
            return if (alongRoute > length + 1.0) max(ahead, alongRoute - length * 0.5) else ahead
        }

        /**
         * Whether pushing this tick would leave the player too fast to be down to the given speed at the given distance:
         * after the push they have moved a stride and keep 0.546 of it, with a stride less of the distance to lose the rest in.
         */
        private fun brakeNow(stride: Double, distance: Double, keptThere: Double): Boolean =
            stride * GROUND_KEPT > keptThere + GROUND_LOST * (distance - stride)
    }
}
