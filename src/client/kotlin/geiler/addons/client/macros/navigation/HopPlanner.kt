package geiler.addons.client.macros.navigation

import geiler.addons.client.macros.pathing.Path
import geiler.addons.client.macros.sim.EtherwarpSim
import geiler.addons.client.macros.pathing.Region
import geiler.addons.client.macros.pathing.Terrain
import geiler.addons.client.macros.rotation.SmoothRotation
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Works out a teleport jump along the route, and where the player will be when it goes off.
 *
 * The item moves the player in a straight line along the view, by its full range when nothing is in the way - the same as
 * `TeleportRodItem#instantTransmission`, whose model this mirrors: the whole player is carried over, the feet end up where
 * the ray of the eyes ends minus the eye height, a tenth above it. So the jump that lands on a point of the route is the
 * one that looks at it from where the player is at that moment.
 *
 * That moment is not now. The click is read at the start of the next tick, the server acts on it a little later still, and
 * the player does not stand still in between: after a jump into the air the next one has to start from a spot that is
 * already lower. [predictFeet] runs the player's motion forward by that many ticks, with the fall and the friction the game
 * applies, and the jump is aimed from there.
 */
object HopPlanner {
    /** Matches the item: the feet land this far above the end of the ray. */
    private const val LANDING_CLEARANCE = 0.1
    private const val GRAVITY = 0.08
    private const val VERTICAL_DRAG = 0.98
    private const val AIR_FRICTION = 0.91

    /** Ground friction 0.6 times the air factor the game applies on top of it. */
    private const val GROUND_FRICTION = 0.546
    private const val CHORD_STEP = 0.5
    private const val CHORD_BISECTIONS = 14
    private const val SAMPLE_STEP = 0.5

    /** The height of the body a teleport carries along its line, for the shut-zone sweep. */
    private const val BODY_HEIGHT = 1.8
    private const val EPSILON = 1.0E-3

    /** Slack on the fall limit, for a landing that is a hair above or below where it was worked out. */
    private const val FALL_SLACK = 0.5

    /**
     * A jump that has been checked.
     *
     * @param direction unit vector to look along
     * @param landing where the feet will be
     * @param progress how far along the route the landing is
     */
    class Plan(val direction: Vec3, val landing: Vec3, val progress: Double)

    /**
     * Where the feet will be after the given number of ticks of the motion the player has now. On the ground
     * that is little more than the last of a walk dying out; in the air it is the fall, which gets faster by
     * the game's gravity every tick, until there is ground to stop on.
     */
    fun predictFeet(player: LocalPlayer, terrain: Terrain, ticks: Int): Vec3 {
        var position = player.position()
        var velocity = player.deltaMovement
        // Afloat is not falling: the water holds the player where they are.
        var grounded = player.onGround() || player.isInWater

        for (tick in 0 until ticks) {
            val x = position.x + velocity.x
            val z = position.z + velocity.z
            var y = if (grounded) position.y else position.y + velocity.y
            var verticalSpeed = if (grounded) 0.0 else (velocity.y - GRAVITY) * VERTICAL_DRAG

            if (!grounded) {
                val floor = terrain.surfaceUnder(x, z, position.y, abs(velocity.y) + 0.8)
                if (floor.isFinite() && y <= floor + EPSILON) {
                    y = floor
                    verticalSpeed = 0.0
                    grounded = true
                }
            }

            val friction = if (grounded) GROUND_FRICTION else AIR_FRICTION
            position = Vec3(x, y, z)
            velocity = Vec3(velocity.x * friction, verticalSpeed, velocity.z * friction)
        }
        return position
    }

    /**
     * A jump of exactly `range` blocks from `feet` that lands on the route, or null when there is none worth taking. A jump
     * is only taken when the item will really carry the full distance, the landing has room and ground within reach of a
     * fall, and no part of the way goes through the shut zone.
     */
    fun plan(
        level: ClientLevel, player: LocalPlayer, terrain: Terrain, region: Region, path: Path, progress: Double, feet: Vec3,
        range: Double, maxFall: Double,
    ): Plan? {
        val landingProgress = chord(path, progress, feet, range) ?: return null

        val target = path.pointAt(landingProgress)
        val direction = target.subtract(feet).normalize()
        val landing = landingOf(level, player, terrain, region, feet, direction, range, maxFall)
        // A landing past an edge is over a drop: it has to be on ground at about the height the route has there, within the fall allowed.
        if (landing != null && HopRules.landingOk(landing.y, groundFloorY(terrain, landing, maxFall), path.pointAt(landingProgress).y, maxFall)) {
            return Plan(direction, landing, landingProgress)
        }
        return planFree(level, player, terrain, region, path, progress, feet, range, maxFall, direction)
    }

    /**
     * Where a jump of exactly `range` blocks along this direction puts the feet, or null if it cannot be made: the way is
     * not clear, goes through the shut zone, or the landing has no room or no ground within reach of a fall.
     */
    private fun landingOf(
        level: ClientLevel, player: LocalPlayer, terrain: Terrain, region: Region, feet: Vec3, direction: Vec3, range: Double,
        maxFall: Double,
    ): Vec3? {
        val eye = feet.add(0.0, player.eyeHeight.toDouble(), 0.0)

        // The item casts this very ray, and stops short at the first block it finds. Only a clear one gives the
        // full distance, which is the only distance the plan is worked out for.
        val ray = level.clip(ClipContext(eye, eye.add(direction.scale(range)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (ray.type != HitResult.Type.MISS) return null

        val halfWidth = player.bbWidth * 0.5
        var along = 0.0
        while (along <= range + EPSILON) {
            if (!footprintInside(region, feet.add(direction.scale(min(along, range))), halfWidth)) return null
            along += SAMPLE_STEP
        }
        // The body is carried along the line, head to feet: nothing shut at any of the heights it fills, wall or forbidden cell.
        if (region.crossesShut(feet, feet.add(direction.scale(range)), halfWidth, BODY_HEIGHT)) return null

        val landing = feet.add(direction.scale(range)).add(0.0, LANDING_CLEARANCE, 0.0)
        val height = max(1.8, player.bbHeight.toDouble())
        val body = AABB(
            landing.x - halfWidth, landing.y + EPSILON, landing.z - halfWidth,
            landing.x + halfWidth, landing.y + height - EPSILON, landing.z + halfWidth,
        )
        if (!terrain.clearBox(body) || !groundBelow(terrain, landing, maxFall)) return null
        return landing
    }

    /** Pitches tried above and below the straight way to the route, in degrees, and yaws to either side of it. */
    private val FREE_UP_DEGREES = doubleArrayOf(3.0, 6.0, 10.0, 15.0, 22.0, -4.0, -8.0)
    private val FREE_YAW_DEGREES = doubleArrayOf(0.0, -4.0, 4.0, -8.0, 8.0, -14.0, 14.0)

    /** A free landing has to end this near the route, or the walker takes it for being off it. */
    private const val FREE_MAX_DEVIATION = 1.5

    /** And this much of the range has to be progress along the route, not just distance. */
    private const val FREE_MIN_GAIN_SHARE = 0.5

    /** The most the ground at a free landing may be above or below the route at that point. */
    private const val FREE_MAX_HEIGHT_OFF = 1.5

    /**
     * A jump to a place near the route, when the straight way to the route is blocked.
     *
     * A route that follows a slope up has the slope between it and anything reached in a straight line - the chord to a
     * point twelve blocks along it goes through the ground - while the cave above it is open, and the item carries the
     * player through the air to wherever the ray ends. So other directions are tried, mostly upward: each one that is clear
     * and lands on ground is worth what it gains along the route, and only a landing close to the route counts, since the
     * walker takes it up again from there.
     */
    private fun planFree(
        level: ClientLevel, player: LocalPlayer, terrain: Terrain, region: Region, path: Path, progress: Double, feet: Vec3,
        range: Double, maxFall: Double, base: Vec3,
    ): Plan? {
        val straight = SmoothRotation.lookAt(Vec3.ZERO, base)
        var best: Plan? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (up in FREE_UP_DEGREES) {
            for (yawOffset in FREE_YAW_DEGREES) {
                val pitch = Mth.clamp(straight.pitch - up, -89.0, 89.0).toFloat()
                val direction = Vec3.directionFromRotation(pitch, (straight.yaw + yawOffset).toFloat())
                val landing = landingOf(level, player, terrain, region, feet, direction, range, maxFall) ?: continue
                val where = path.project(landing, progress, range + 2.0)
                val gain = where.distance - progress
                if (where.deviation > FREE_MAX_DEVIATION || gain < range * FREE_MIN_GAIN_SHARE) continue
                val floor = groundFloorY(terrain, landing, maxFall)
                if (floor.isNaN() || abs(floor - path.pointAt(where.distance).y) > FREE_MAX_HEIGHT_OFF) continue
                val score = gain - 2.0 * where.deviation - 0.02 * (abs(up) + abs(yawOffset))
                if (score > bestScore) {
                    bestScore = score
                    best = Plan(direction, landing, where.distance)
                }
            }
        }
        return best
    }

    /**
     * An Instant Transmission hop that leaves the player nearer a place there is no route to - up out of a pit onto a platform, across
     * a gap - by the item's full [range] along a look, see [InstantHops]. Null when none does, or the way crosses the shut zone.
     */
    fun planInstant(
        level: ClientLevel, player: LocalPlayer, region: Region, feet: Vec3, goal: Vec3, range: Double, maxFall: Double,
        accept: InstantHops.Accept = InstantHops.Accept { true },
    ): InstantHops.Hop? {
        val hop = InstantHops.plan(
            EtherwarpSim.probe(level), feet.x, feet.y, feet.z, goal.x, goal.y, goal.z, range, maxFall,
            InstantHops.Allowed { x, y, z -> region.contains(x, y, z) }, accept,
        ) ?: return null
        val landing = Vec3(hop.landX, hop.landY, hop.landZ)
        return if (lineInside(region, feet, landing, player.bbWidth * 0.5)) hop else null
    }

    /** Pitches and yaws tried around the way to the route when a jump is to get out of water: wider than for a free landing. */
    private val EXIT_UP_DEGREES = doubleArrayOf(0.0, 3.0, -3.0, 6.0, -6.0, 10.0, -10.0, 15.0, 22.0)
    private val EXIT_YAW_DEGREES = doubleArrayOf(0.0, -4.0, 4.0, -8.0, 8.0, -14.0, 14.0, -25.0, 25.0, -40.0, 40.0)

    /** A landing out of water has to be at least this much further along the route than the player is. */
    private const val EXIT_MIN_GAIN = 1.0

    /**
     * A jump of exactly `range` blocks that puts the player on dry ground at or near the route, for getting out of water, or
     * null when no direction does.
     *
     * Unlike [plan] this does not need the route to be as long as the jump, or the landing to be on its chord: every
     * direction in a fan around the way to the route is tried, and the landing has to be dry, close to the route (the
     * walker takes it up again from there) and further along it. Among those the one that gains the most wins.
     */
    fun planExit(
        level: ClientLevel, player: LocalPlayer, terrain: Terrain, region: Region, path: Path, progress: Double, feet: Vec3,
        range: Double, maxFall: Double,
    ): Plan? {
        val chord = chord(path, progress, feet, range)
        val base = path.pointAt(chord ?: path.length()).subtract(feet)
        if (base.lengthSqr() < 1.0E-6) return null

        val straight = SmoothRotation.lookAt(Vec3.ZERO, base)
        var best: Plan? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (up in EXIT_UP_DEGREES) {
            for (yawOffset in EXIT_YAW_DEGREES) {
                val pitch = Mth.clamp(straight.pitch - up, -89.0, 89.0).toFloat()
                val direction = Vec3.directionFromRotation(pitch, (straight.yaw + yawOffset).toFloat())
                val landing = landingOf(level, player, terrain, region, feet, direction, range, maxFall) ?: continue
                val floor = groundFloorY(terrain, landing, maxFall, true)
                if (floor.isNaN()) continue
                val where = path.project(landing, progress, range + 2.0)
                val gain = where.distance - progress
                if (where.deviation > FREE_MAX_DEVIATION || gain < EXIT_MIN_GAIN ||
                    abs(floor - path.pointAt(where.distance).y) > FREE_MAX_HEIGHT_OFF
                ) {
                    continue
                }
                val score = gain - 2.0 * where.deviation - 0.02 * (abs(up) + abs(yawOffset))
                if (score > bestScore) {
                    bestScore = score
                    best = Plan(direction, landing, where.distance)
                }
            }
        }
        return best
    }

    /**
     * The first point along the route, from `progress` on, that is `range` blocks from `feet` in a straight line - a chord
     * of the route rather than a stretch of it. Returns its distance along the route, or null if the route never gets that
     * far away.
     */
    private fun chord(path: Path, progress: Double, feet: Vec3, range: Double): Double? {
        var previous = progress
        var along = progress + CHORD_STEP
        while (along < path.length() + CHORD_STEP) {
            val clamped = min(along, path.length())
            if (path.pointAt(clamped).distanceTo(feet) >= range) {
                var low = previous
                var high = clamped
                for (i in 0 until CHORD_BISECTIONS) {
                    val middle = (low + high) * 0.5
                    if (path.pointAt(middle).distanceTo(feet) >= range) high = middle else low = middle
                }
                return high
            }
            previous = clamped
            if (clamped >= path.length()) break
            along += CHORD_STEP
        }
        return null
    }

    /** Whether every block column the body touches at this point is outside the shut zone. */
    private fun footprintInside(region: Region, feet: Vec3, halfWidth: Double): Boolean {
        val y = Mth.floor(feet.y + EPSILON)
        for (x in Mth.floor(feet.x - halfWidth)..Mth.floor(feet.x + halfWidth)) {
            for (z in Mth.floor(feet.z - halfWidth)..Mth.floor(feet.z + halfWidth)) {
                if (!region.contains(x, y, z)) return false
            }
        }
        return true
    }

    /**
     * A warp that was checked.
     *
     * @param floor the block the warp lands on top of
     * @param aimPoint the point on that block to look at
     * @param landing where the feet will be
     * @param progress how far along the route the landing is
     */
    class EtherPlan(val floor: BlockPos, val aimPoint: Vec3, val landing: Vec3, val progress: Double)

    /** Point on the top of a block tried for the aim: the middle first, then this far off it at most. */
    private const val ETHER_AIM_SCATTER = 0.3
    private const val ETHER_AIM_ATTEMPTS = 4

    /** Keep a little clear of the range limit: the player keeps moving while the aim settles. */
    private const val ETHER_RANGE_MARGIN = 1.5

    /** How many of the farthest usable landings the pick is made from. */
    private const val ETHER_CANDIDATES = 3

    /** The eyes the warp casts from: crouching, whatever the player is doing, as the item does. */
    fun etherOrigin(player: LocalPlayer, feet: Vec3): Vec3 = feet.add(0.0, player.getEyeHeight(Pose.CROUCHING).toDouble(), 0.0)

    /**
     * A warp that lands on the route, far ahead and short of its end, or null when there is none worth taking.
     *
     * The landing is never the mob and never exactly the farthest place that can be reached: among the few farthest places
     * that work one is picked at random, and the last `standOff` blocks of the route are left to be walked. Aiming at the
     * thing being hunted, every time from the same distance, is neither needed nor how anybody plays.
     *
     * A place works if the item will really put the player there: the ray from the crouching eyes to a point on the block -
     * its top, or when that cannot be seen a side or its underside - meets that block first, and the two blocks above it are
     * air. And it works if no part of the way goes through the shut zone, since a warp that skips the gate is a crossing
     * like any other.
     */
    fun planEther(
        level: ClientLevel, player: LocalPlayer, terrain: Terrain, region: Region, path: Path, progress: Double, feet: Vec3,
        range: Double, minDistance: Double, standOff: Double,
    ): EtherPlan? {
        val end = path.length() - standOff
        if (end - progress < minDistance) return null

        val origin = etherOrigin(player, feet)
        val halfWidth = player.bbWidth * 0.5
        val usable = ArrayList<EtherPlan>()
        var next = min(end, progress + range)
        while (next >= progress + minDistance && usable.size < ETHER_CANDIDATES) {
            val along = next
            next -= 1.0
            val point = path.pointAt(along)
            if (point.distanceTo(feet) < minDistance) continue

            val floor = BlockPos.containing(point.x, point.y - 0.1, point.z)
            val cell = floor.above()
            // Two air blocks above the target block - the item checks exactly that, and air means air: a carpet or
            // a layer of snow is no air.
            if (!level.getBlockState(cell).isAir || !level.getBlockState(cell.above()).isAir || terrain.standAt(cell.x, cell.y, cell.z) == null) {
                continue
            }
            val landing = Vec3(cell.x + 0.5, cell.y + LANDING_CLEARANCE, cell.z + 0.5)

            val aim = findAim(level, player, origin, range - ETHER_RANGE_MARGIN, floor)
            if (aim == null || !lineInside(region, feet, landing, halfWidth)) continue
            // The block has to be the green one: what the Etherwarp Sim says the ray from the crouching eyes ends on.
            if (!TeleportCheck.warp(level, player, region, feet, TeleportCheck.lookTo(feet, aim), range, landing).ok) continue
            usable.add(EtherPlan(floor, aim, landing, along))
        }
        return if (usable.isEmpty()) null else usable[ThreadLocalRandom.current().nextInt(usable.size)]
    }

    /** How far around a mob the landing of a warp onto it is looked for, sideways and up and down, in blocks. */
    private const val ONTO_SIDE = 4
    private const val ONTO_DOWN = 4
    private const val ONTO_UP = 2

    /** Landings no closer than this to the player: a warp of a step is not worth the crouch. */
    private const val ONTO_MIN_DISTANCE = 3.0

    /** How many of the landings nearest to the mob are tried for an aim, which is what costs. */
    private const val ONTO_TRIES = 16

    /**
     * A warp onto the ground next to a mob that cannot be walked to: across water, onto a ledge, into a hollow with no way
     * in. The landings are the places to stand around the mob - the two blocks above them air, as for any warp - nearest to
     * it first, and the first that can be seen from the crouching eyes is taken. Like any warp it never ends in the shut
     * zone or crosses it. Null when there is none.
     *
     * @param target the hitbox of the mob
     */
    fun planOnto(
        level: ClientLevel, player: LocalPlayer, terrain: Terrain, region: Region, feet: Vec3, target: AABB, range: Double,
    ): EtherPlan? {
        val origin = etherOrigin(player, feet)
        val halfWidth = player.bbWidth * 0.5
        val eye = player.getEyeHeight(Pose.STANDING).toDouble()
        val base = BlockPos.containing(target.center.x, target.minY + 0.1, target.center.z)
        val landings = ArrayList<Vec3>()
        val floors = ArrayList<BlockPos>()
        for (dx in -ONTO_SIDE..ONTO_SIDE) {
            for (dz in -ONTO_SIDE..ONTO_SIDE) {
                for (dy in -ONTO_DOWN..ONTO_UP) {
                    val cell = base.offset(dx, dy, dz)
                    if (!level.getBlockState(cell).isAir || !level.getBlockState(cell.above()).isAir ||
                        terrain.standAt(cell.x, cell.y, cell.z) == null
                    ) {
                        continue
                    }
                    val landing = Vec3(cell.x + 0.5, cell.y + LANDING_CLEARANCE, cell.z + 0.5)
                    if (landing.distanceTo(feet) < ONTO_MIN_DISTANCE || landing.distanceTo(origin) > range - ETHER_RANGE_MARGIN) continue
                    landings.add(landing)
                    floors.add(cell.below())
                }
            }
        }
        // Nearest to the mob first, measured from the eyes there to the hitbox: that is where the fight is from.
        val order = ArrayList<Int>()
        for (i in landings.indices) order.add(i)
        order.sortWith { a, b ->
            java.lang.Double.compare(distanceToBox(landings[a].add(0.0, eye, 0.0), target), distanceToBox(landings[b].add(0.0, eye, 0.0), target))
        }
        for (n in 0 until min(ONTO_TRIES, order.size)) {
            val i = order[n]
            val landing = landings[i]
            if (!lineInside(region, feet, landing, halfWidth)) continue
            val aim = findAim(level, player, origin, range - ETHER_RANGE_MARGIN, floors[i]) ?: continue
            if (!TeleportCheck.warp(level, player, region, feet, TeleportCheck.lookTo(feet, aim), range, landing).ok) continue
            return EtherPlan(floors[i], aim, landing, 0.0)
        }
        return null
    }

    /** The rays a warp toward somewhere is looked for along: every this many degrees, sideways and up and down. */
    private const val TOWARD_YAW_STEP = 6.0f
    private const val TOWARD_PITCH_STEP = 6.0f
    private const val TOWARD_PITCH_UP = -72.0f
    private const val TOWARD_PITCH_DOWN = 48.0f

    /** How much nearer to the goal a landing has to be to be worth it, unless it gets the player out of water. */
    private const val TOWARD_MIN_GAIN = 2.0

    /** A landing in water counts as this much further from the goal: out of the water first. */
    private const val TOWARD_WATER_PENALTY = 8.0

    /**
     * A warp that gets the player nearer to a place there is no way to on foot: onto whatever block can be seen and stood on
     * that is nearest to it - out of a pool with walls too high to climb, up onto a ledge on the way, across a gap. Not the
     * place itself, which may be out of sight, only the best step toward it that can be made from here; from there the
     * route is planned again. The candidates are what the crouching eyes see along a fan of rays all round, so only blocks
     * the item can really be aimed at are taken. A landing has to be at least [TOWARD_MIN_GAIN] blocks nearer to the goal
     * than the player - or be dry when the player is in water. Null when there is none.
     */
    fun planToward(
        level: ClientLevel, player: LocalPlayer, terrain: Terrain, region: Region, feet: Vec3, goal: Vec3, range: Double,
    ): EtherPlan? {
        val origin = etherOrigin(player, feet)
        val halfWidth = player.bbWidth * 0.5
        // Afloat at the surface the game hardly counts the player as in the water - they bob out of it every other tick -
        // so the water right under the feet counts too.
        val wet = player.isInWater || level.getFluidState(BlockPos.containing(feet.x, feet.y - 0.2, feet.z)).`is`(FluidTags.WATER)
        val now = feet.distanceTo(goal)
        var best: EtherPlan? = null
        var bestScore = Double.POSITIVE_INFINITY
        val reach = range - ETHER_RANGE_MARGIN
        val yaws = ArrayList<Float>()
        var yawValue = 0.0f
        while (yawValue < 360.0f) {
            yaws.add(yawValue)
            yawValue += TOWARD_YAW_STEP
        }
        val pitches = ArrayList<Float>()
        var pitchValue = TOWARD_PITCH_UP
        while (pitchValue <= TOWARD_PITCH_DOWN) {
            pitches.add(pitchValue)
            pitchValue += TOWARD_PITCH_STEP
        }
        for (yaw in yaws) {
            for (pitch in pitches) {
                val direction = Vec3.directionFromRotation(pitch, yaw)
                val hit = level.clip(ClipContext(origin, origin.add(direction.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
                if (hit.type != HitResult.Type.BLOCK) continue
                val floor = hit.blockPos
                val cell = floor.above()
                if (!level.getBlockState(cell).isAir || !level.getBlockState(cell.above()).isAir) continue
                val stand = terrain.standAt(cell.x, cell.y, cell.z) ?: continue
                val landing = Vec3(cell.x + 0.5, cell.y + LANDING_CLEARANCE, cell.z + 0.5)
                if (landing.distanceTo(feet) < ONTO_MIN_DISTANCE) continue
                val score = landing.distanceTo(goal) + (if (stand.water) TOWARD_WATER_PENALTY else 0.0)
                val gains = score <= now - TOWARD_MIN_GAIN || wet && !stand.water
                if (!gains || score >= bestScore || !lineInside(region, feet, landing, halfWidth)) continue
                // The point the ray met, a hair inside the block, is the aim: it is seen, so the item will take it.
                val aim = hit.location.add(direction.scale(0.02))
                if (!landsOn(level, player, origin, aim, reach, floor)) continue
                if (!TeleportCheck.warp(level, player, region, feet, TeleportCheck.lookTo(feet, aim), range, landing).ok) continue
                best = EtherPlan(floor, aim, landing, 0.0)
                bestScore = score
            }
        }
        return best
    }

    private fun distanceToBox(point: Vec3, box: AABB): Double {
        val dx = max(max(box.minX - point.x, 0.0), point.x - box.maxX)
        val dy = max(max(box.minY - point.y, 0.0), point.y - box.maxY)
        val dz = max(max(box.minZ - point.z, 0.0), point.z - box.maxZ)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Whether the warp from this spot, looking at this point, lands on top of this block: the ray meets it first.
     * The point must be within the range given, which is already short of the item's own.
     */
    fun landsOn(level: ClientLevel, player: LocalPlayer, origin: Vec3, point: Vec3, range: Double, floor: BlockPos): Boolean {
        val direction = point.subtract(origin)
        if (direction.lengthSqr() < 1.0E-6 || direction.length() > range) return false
        val unit = direction.normalize()
        val hit = level.clip(
            ClipContext(origin, origin.add(unit.scale(range + ETHER_RANGE_MARGIN)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player),
        )
        return hit.type == HitResult.Type.BLOCK && hit.blockPos == floor
    }

    private fun findAim(level: ClientLevel, player: LocalPlayer, origin: Vec3, range: Double, floor: BlockPos): Vec3? {
        val shape = level.getBlockState(floor).getShape(level, floor)
        if (shape.isEmpty) return null
        val top = floor.y + shape.max(Direction.Axis.Y)
        val middle = Vec3(floor.x + 0.5, top, floor.z + 0.5)
        if (landsOn(level, player, origin, middle, range, floor)) return middle
        for (attempt in 0 until ETHER_AIM_ATTEMPTS) {
            val random = ThreadLocalRandom.current()
            val point = Vec3(
                middle.x + (random.nextDouble() * 2.0 - 1.0) * ETHER_AIM_SCATTER, top,
                middle.z + (random.nextDouble() * 2.0 - 1.0) * ETHER_AIM_SCATTER,
            )
            if (landsOn(level, player, origin, point, range, floor)) return point
        }
        return sideAim(level, player, origin, range, floor, shape.bounds())
    }

    /**
     * A point on one of the other faces of the block - the four sides, then the underside - when its top cannot be seen.
     * The item takes the block the ray meets, whichever face that is, and puts the player on top of it: a block that is
     * only seen from the side, or from below, out of a pool say, is as good a target as one seen from above. The face
     * nearest to the eyes is tried first, its middle before scattered points, and every point sits a hair inside the block
     * so the ray cannot slip along the face.
     */
    private fun sideAim(level: ClientLevel, player: LocalPlayer, origin: Vec3, range: Double, floor: BlockPos, bounds: AABB): Vec3? {
        val inset = 0.02
        val midX = floor.x + (bounds.minX + bounds.maxX) * 0.5
        val midY = floor.y + (bounds.minY + bounds.maxY) * 0.5
        val midZ = floor.z + (bounds.minZ + bounds.maxZ) * 0.5
        // Middle of each face, and the axes along it the scatter is spread on.
        val middles = arrayOf(
            Vec3(floor.x + bounds.minX + inset, midY, midZ),
            Vec3(floor.x + bounds.maxX - inset, midY, midZ),
            Vec3(midX, midY, floor.z + bounds.minZ + inset),
            Vec3(midX, midY, floor.z + bounds.maxZ - inset),
            Vec3(midX, floor.y + bounds.minY + inset, midZ),
        )
        val alongX = booleanArrayOf(false, false, true, true, true)
        val alongY = booleanArrayOf(true, true, true, true, false)
        val alongZ = booleanArrayOf(true, true, false, false, true)
        val order = listOf(0, 1, 2, 3, 4).sortedBy { middles[it].distanceToSqr(origin) }

        val random = ThreadLocalRandom.current()
        for (face in order) {
            for (attempt in 0..ETHER_AIM_ATTEMPTS) {
                val spread = if (attempt == 0) 0.0 else ETHER_AIM_SCATTER
                val point = middles[face].add(
                    if (alongX[face]) (random.nextDouble() * 2.0 - 1.0) * spread else 0.0,
                    if (alongY[face]) (random.nextDouble() * 2.0 - 1.0) * spread else 0.0,
                    if (alongZ[face]) (random.nextDouble() * 2.0 - 1.0) * spread else 0.0,
                )
                if (landsOn(level, player, origin, point, range, floor)) return point
            }
        }
        return null
    }

    /** Whether every point of the straight line between two spots has its footprint outside the shut zone. */
    private fun lineInside(region: Region, from: Vec3, to: Vec3, halfWidth: Double): Boolean {
        if (region.crossesShut(from, to, halfWidth, BODY_HEIGHT)) return false
        val length = from.distanceTo(to)
        var along = 0.0
        while (along <= length + EPSILON) {
            val point = if (length < EPSILON) from else from.lerp(to, min(along, length) / length)
            if (!footprintInside(region, point, halfWidth)) return false
            along += SAMPLE_STEP
        }
        return true
    }

    /** Whether there is somewhere to stand under the landing, no further down than a fall may go. */
    private fun groundBelow(terrain: Terrain, landing: Vec3, maxFall: Double): Boolean = !groundFloorY(terrain, landing, maxFall).isNaN()

    /**
     * The height of the place to stand under the landing, no further down than a fall may go, or NaN. With `dryOnly` NaN too
     * when the first place to stand under the landing is in water: a fall onto the surface of a pool is not a way out of it.
     */
    private fun groundFloorY(terrain: Terrain, landing: Vec3, maxFall: Double, dryOnly: Boolean = false): Double {
        val x = Mth.floor(landing.x)
        val z = Mth.floor(landing.z)
        val top = Mth.floor(landing.y + 0.3)
        val bottom = Mth.floor(landing.y - maxFall - FALL_SLACK) - 1
        var y = top
        while (y >= bottom) {
            val stand = terrain.standAt(x, y, z)
            if (stand != null && stand.floorY <= landing.y + 0.3) {
                if (dryOnly && stand.water) return Double.NaN
                return if (landing.y - stand.floorY <= maxFall + FALL_SLACK) stand.floorY else Double.NaN
            }
            y--
        }
        return Double.NaN
    }
}
