package geiler.addons.client.macros.pathing

import it.unimi.dsi.fastutil.bytes.ByteArrayList
import it.unimi.dsi.fastutil.floats.FloatArrayList
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The world as the planner sees it: where the player can stand, what it costs to get from one of those places to the
 * next, and what will hurt on the way.
 *
 * Everything works off the real collision shapes of the blocks rather than their 1x1x1 cells, so a stand is an exact
 * floor height and not an integer Y. That is the difference between a planner that understands slabs, stairs, carpets,
 * snow layers and fence tops and one that walks the player into them. A stand is identified by the block its feet are
 * in, which is unique: two standable heights in one column always lie at least two blocks apart, because a stand needs
 * the full height of the player above its floor.
 *
 * One instance serves one search, but what it reads is not thrown away with it: the blocks and the stands made from them
 * are kept by [TerrainCache] and only forgotten where the world changes, so the next search starts with most of what it
 * needs. The region and the other rules are not part of that - they are asked on every look.
 */
class Terrain @JvmOverloads constructor(
    private val level: ClientLevel,
    private val player: LocalPlayer,
    private val settings: PathingSettings,
    private val region: Region = Region.UNBOUNDED,
) {
    /**
     * Every move there is from one stand, worked out once and kept with the blocks it was worked out from (see
     * [TerrainCache]): where it goes, how, and what it costs before anything that changes from search to search.
     */
    class Edges(val to: Array<Stand>, val cost: FloatArray, val move: ByteArray) {
        companion object {
            val NONE = Edges(emptyArray(), FloatArray(0), ByteArray(0))
        }
    }

    /**
     * A place the player can stand, and the exact height their feet rest at.
     *
     * @param floating afloat at the surface of deep water rather than on something
     * @param squeeze how hemmed in the place is: one for every wall right beside it, a half for one on a corner
     * @param edges on how many of the four sides the ground falls away further than a step
     * @param waterDepth how far the surface of the water is above the feet, or 0 on dry ground
     */
    data class Stand(
        val x: Int,
        val y: Int,
        val z: Int,
        val floorY: Double,
        val speedFactor: Double,
        val water: Boolean,
        val nearHazard: Boolean,
        val floating: Boolean,
        val squeeze: Double,
        val edges: Int,
        val waterDepth: Double,
    ) {
        fun centerX(): Double = x + 0.5

        fun centerZ(): Double = z + 0.5

        fun feet(): Vec3 = Vec3(centerX(), floorY, centerZ())

        fun eye(eyeHeight: Double): Vec3 = Vec3(centerX(), floorY + eyeHeight, centerZ())

        fun key(): Long = BlockPos.asLong(x, y, z)
    }

    /** Receives one reachable neighbour of a stand. */
    fun interface Visitor {
        fun accept(to: Stand, cost: Double, move: MoveType)
    }

    /** What is in one block: its collision, and what standing in or on it does. Shared by every search, see [TerrainCache]. */
    class Cell(
        val loaded: Boolean,
        val parts: Array<AABB>,
        val ranges: DoubleArray,
        val flags: Int,
        val speedFactor: Float,
        val fluidHeight: Float,
    ) {
        companion object {
            val UNLOADED = Cell(false, emptyArray(), DoubleArray(0), 0, 1.0f, 0.0f)

            /** Air and everything else with no collision, no fluid and nothing about it: most of the world, so one copy. */
            val EMPTY = Cell(true, emptyArray(), DoubleArray(0), 0, 1.0f, 0.0f)
        }
    }

    private val halfWidth = player.bbWidth * 0.5

    // Plan for standing upright even if the player happens to be crouched right now: the follower
    // decides when to sneak, and a route that only fits while crouched is a route that gets stuck.
    private val height = max(1.8, player.bbHeight.toDouble())
    private val eyeHeight = player.eyeHeight.toDouble()

    /** The eye height standing, which is what stands are made under whether or not the player is crouched now. */
    private val standingEyeHeight = player.getEyeHeight(Pose.STANDING).toDouble()

    /** How high the feet can be got with a jump: the plain 1.25, or more with a jump boost on. */
    private val jumpHeight = if (settings.useJumpBoost()) jumpHeightOf(player) else JUMP_HEIGHT
    private val cache: TerrainCache = TerrainCache.of(
        level, TerrainCache.Profile(halfWidth, height, standingEyeHeight, settings.allowWater(), settings.allowSwim()),
    )

    /** Which set of rules the moves of this terrain are made under, as the cache numbers them. */
    private val rulesId: Int = cache.rulesId(
        TerrainCache.Rules(
            settings.allowJumps(), settings.allowDiagonals(), settings.maxFallHeight(),
            Math.round(jumpHeight * 100.0) / 100.0, settings.sprint(), Math.round(settings.jumpCost() * 100.0).toInt(),
        ),
    )
    private val bounded = region !== Region.UNBOUNDED

    /** What a step onto this stand costs relative to the plain cost: below 1 on ground the region says to lean toward. */
    fun costFactor(x: Int, y: Int, z: Int): Double = if (bounded) region.costFactor(x, y, z) else 1.0

    /** The section looked at last, which is nearly always the one looked at next. */
    private var memoKey = Long.MIN_VALUE
    private var memoSection: TerrainCache.Section? = null
    private var memoEpoch = -1
    private var memoNow = 0L

    /** Scratch lists for working out the moves of one stand. */
    private val edgeTo = ArrayList<Stand>()
    private val edgeCost = FloatArrayList()
    private val edgeMove = ByteArrayList()

    fun level(): ClientLevel = level

    /** Everything about this terrain that decides which moves there are, for telling whether a [WalkGraph] still fits. */
    fun graphSignature(): WalkGraph.Signature = WalkGraph.Signature(
        region, settings.allowJumps(), settings.allowDiagonals(), settings.allowWater(), settings.allowSwim(),
        settings.maxFallHeight(), Math.round(jumpHeight * 100.0) / 100.0, settings.sprint(),
        Math.round(settings.straightness() * 100.0) / 100.0, settings.searchRadius(),
    )

    fun eyeHeight(): Double = eyeHeight

    /**
     * The most height one move can gain: a block with the stairs and slabs of a walk, or the height of a jump when jumping
     * is allowed. Distance to a goal that is higher up is at least this many moves, however near it is.
     */
    fun maxRisePerMove(): Double = if (settings.allowJumps()) jumpHeight - JUMP_MARGIN else STAIR_HEIGHT

    /** The height a jump gets the feet to, which the planner climbs one block at a time up to. */
    fun jumpHeight(): Double = jumpHeight

    /** The cheapest a single block of travel can be. Heuristics have to scale by this to stay honest. */
    fun minimumCostPerBlock(): Double = if (settings.sprint()) SPRINT_TICKS_PER_BLOCK else WALK_TICKS_PER_BLOCK

    /** [minimumCostPerBlock] less what preferred painted ground takes off it, if any: the scale of the estimate to a goal. */
    fun heuristicCostPerBlock(): Double = minimumCostPerBlock() * (if (bounded) region.minCostFactor() else 1.0)

    /**
     * The stand the player currently occupies, or the nearest one holding them up. A player who stands where the region does not let
     * them - on ground painted forbidden, say, after a jump landed there - still has the stand they are on: the search leaves from it
     * and may step out over [ESCAPE_RADIUS] blocks of shut ground, at a price, instead of the macro having nowhere to start from.
     */
    fun standUnder(target: LocalPlayer): Stand? {
        escapeFrom = null
        val inside = standUnder(target, false)
        if (inside != null || !bounded) return inside
        val outside = standUnder(target, true) ?: return null
        escapeFrom = outside
        return outside
    }

    /** Whether the last [standUnder] had to take a stand the region shuts. */
    fun startedOutside(): Boolean = escapeFrom != null

    private fun standUnder(target: LocalPlayer, anywhere: Boolean): Stand? {
        val direct = standInColumn(Mth.floor(target.x), Mth.floor(target.z), target.y, anywhere)
        if (direct != null) return direct

        // On an edge the column under the middle of the player can be empty while their box still rests
        // on a neighbour. Take whichever overlapped column holds them up.
        var best: Stand? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (x in Mth.floor(target.x - halfWidth)..Mth.floor(target.x + halfWidth)) {
            for (z in Mth.floor(target.z - halfWidth)..Mth.floor(target.z + halfWidth)) {
                val candidate = standInColumn(x, z, target.y, anywhere) ?: continue
                val distance = candidate.feet().distanceToSqr(target.position())
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = candidate
                }
            }
        }
        if (best == null && target.isInWater) return surfaceAbove(target)
        return best
    }

    /** Where the search started when that was a stand the region shuts; ground within [ESCAPE_RADIUS] of it may be left over at a price. */
    private var escapeFrom: Stand? = null

    private fun escapes(target: Stand): Boolean {
        val from = escapeFrom ?: return false
        val dx = target.centerX() - from.centerX()
        val dz = target.centerZ() - from.centerZ()
        return dx * dx + dz * dz <= ESCAPE_RADIUS * ESCAPE_RADIUS
    }

    /**
     * Under the surface of water the player is held up by nothing, and has no stand of their own: the one they are about
     * to have is at the surface, which they rise to by holding the jump key. That is the first place to float above them.
     */
    private fun surfaceAbove(target: LocalPlayer): Stand? {
        val x = Mth.floor(target.x)
        val z = Mth.floor(target.z)
        val from = Mth.floor(target.y)
        for (y in from..from + SURFACE_SEARCH) {
            val stand = standAt(x, y, z)
            if (stand != null && stand.floating) return stand
        }
        return null
    }

    /**
     * The stand the feet would come to rest on at an arbitrary position, or null if there is nothing there
     * to stand on. For checking a step before taking it without planning a whole route for it.
     */
    fun standNear(x: Double, feetY: Double, z: Double): Stand? = standInColumn(Mth.floor(x), Mth.floor(z), feetY)

    /** The highest stand in a column whose floor is at or just below the given feet height. */
    private fun standInColumn(x: Int, z: Int, feetY: Double, anywhere: Boolean = false): Stand? {
        val from = Mth.floor(feetY + 0.5)
        val to = from - ceil(settings.maxFallHeight()).toInt() - 2
        var y = from
        while (y >= to) {
            val stand = if (anywhere) standAnywhere(x, y, z) else standAt(x, y, z)
            if (stand != null && stand.floorY <= feetY + STEP_HEIGHT) return stand
            y--
        }
        return null
    }

    /** Whether the player can stand in this block, and where exactly their feet would rest. */
    fun standAt(x: Int, y: Int, z: Int): Stand? {
        // Checked before anything is read from the world: a stand outside the region is not a poor choice,
        // it is not a choice, and every move, shortcut and goal test goes through here. It is also why what the
        // cache holds is the same whatever the region: the region is only ever asked here and in expand, never stored.
        if (bounded && !region.contains(x, y, z)) return null
        return standAnywhere(x, y, z)
    }

    /** The stand in this block whatever the region says, which is what the cache holds. */
    private fun standAnywhere(x: Int, y: Int, z: Int): Stand? {
        val section = section(x, y, z)
        val index = TerrainCache.index(x, y, z)
        val cached = section.stands[index]
        if (cached != null) return if (cached === NO_STAND) null else cached as Stand
        val stand = computeStand(x, y, z)
        section.stands[index] = stand ?: NO_STAND
        return stand
    }

    private fun computeStand(x: Int, y: Int, z: Int): Stand? {
        val onFloor = computeFloorStand(x, y, z)
        if (onFloor != null || !settings.allowSwim()) return onFloor
        return computeSwimStand(x, y, z)
    }

    /**
     * Afloat at the surface of water with nothing to stand on: the top layer of the water, with air above it.
     * Only the surface is a place to be - deeper down the player cannot breathe, and the walker keeps the jump
     * key down in water, which holds them up there. The feet are counted at the bottom of the cell, a little
     * under where they really bob.
     */
    private fun computeSwimStand(x: Int, y: Int, z: Int): Stand? {
        val feet = cell(x, y, z)
        val head = cell(x, y + 1, z)
        val below = cell(x, y - 1, z)
        if (!feet.loaded || !head.loaded || !below.loaded) return null
        if ((feet.flags and FLAG_WATER) == 0 || (head.flags and FLAG_WATER) != 0) return null
        if (((feet.flags or head.flags or below.flags) and (FLAG_DANGER or FLAG_AVOID)) != 0) return null
        if (!clearColumn(x, z, y + EPSILON, y + height - EPSILON)) return null
        return Stand(x, y, z, y.toDouble(), 1.0, true, hazardBeside(x, y, z, y + 1), true, 0.0, 0, feet.fluidHeight.toDouble())
    }

    private fun computeFloorStand(x: Int, y: Int, z: Int): Stand? {
        val feet = cell(x, y, z)
        val below = cell(x, y - 1, z)
        if (!feet.loaded || !below.loaded) return null

        val fromFeet = topIn(feet, y.toDouble(), (y + 1).toDouble())
        val floorY = maxNaN(topIn(below, y.toDouble(), (y + 1).toDouble()), fromFeet)
        if (floorY.isNaN()) return null
        // A surface flush with the top of this block belongs to the block above instead. Keeping both
        // would put two nodes on one spot and let the search drift between them for free.
        if (floorY >= y + 1 - EPSILON) return null
        if (!clearColumn(x, z, floorY + EPSILON, floorY + height - EPSILON)) return null

        val support = if (abs(fromFeet - floorY) < EPSILON) feet else below
        if ((support.flags and (FLAG_DANGER or FLAG_AVOID)) != 0) return null

        var water = false
        var surface = Double.NEGATIVE_INFINITY
        val headBlock = Mth.floor(floorY + height - EPSILON)
        for (by in y..headBlock) {
            val inside = cell(x, by, z)
            val flags = inside.flags
            if ((flags and (FLAG_DANGER or FLAG_AVOID)) != 0) return null
            if ((flags and FLAG_WATER) != 0) {
                water = true
                surface = max(surface, by + inside.fluidHeight.toDouble())
            }
        }
        if (water) {
            if (!settings.allowWater()) return null
            // Wading is for water the head stays out of. Deeper, the player is under the surface with no air to
            // breathe; that is swimming's, along the top.
            val eyeBlock = Mth.floor(floorY + standingEyeHeight)
            if ((cell(x, eyeBlock, z).flags and FLAG_WATER) != 0) return null
        }

        return Stand(
            x, y, z, floorY, support.speedFactor.toDouble(), water, hazardBeside(x, y, z, headBlock), false, squeezeAt(x, z, floorY),
            edgesAt(x, z, floorY), if (water) max(0.0, surface - floorY) else 0.0,
        )
    }

    /**
     * How hemmed in a place is: for each of the four sides with something in the way of the body above a step's height,
     * one, and for each corner a half. Only what the body would catch on counts - the next stair of a flight is half a
     * block up and walked onto, a block in the way is not.
     */
    private fun squeezeAt(x: Int, z: Int, floorY: Double): Double {
        val low = floorY + STEP_HEIGHT + 0.05
        val high = floorY + height - EPSILON
        var squeeze = 0.0
        for (i in OFFSETS.indices) {
            if (!clearColumn(x + OFFSETS[i][0], z + OFFSETS[i][1], low, high)) squeeze += if (i < 4) 1.0 else 0.5
        }
        return squeeze
    }

    /**
     * On how many of the four sides the ground falls away: nothing under the middle of the block beside from a step
     * above the feet down to [EDGE_DEPTH] below them, and no water there either. A wall beside is not an edge.
     */
    private fun edgesAt(x: Int, z: Int, floorY: Double): Int {
        var edges = 0
        for (i in 0 until 4) {
            if (fallsAway(x + OFFSETS[i][0], z + OFFSETS[i][1], floorY)) edges++
        }
        return edges
    }

    private fun fallsAway(x: Int, z: Int, floorY: Double): Boolean {
        val low = floorY - EDGE_DEPTH
        val high = floorY + STEP_HEIGHT + EPSILON
        var y = Mth.floor(high)
        while (y >= Mth.floor(low) - 1) {
            val cell = cell(x, y, z)
            if (!cell.loaded || (cell.flags and FLAG_WATER) != 0) return false
            val ranges = cell.ranges
            var i = 0
            while (i < ranges.size) {
                if (ranges[i + 1] >= low && ranges[i] < high) return false
                i += 2
            }
            y--
        }
        return true
    }

    /** Whether anything that hurts sits within one block horizontally, which is worth a detour. */
    private fun hazardBeside(x: Int, y: Int, z: Int, headBlock: Int): Boolean {
        for (offset in OFFSETS) {
            for (by in y - 1..headBlock) {
                if ((cell(x + offset[0], by, z + offset[1]).flags and FLAG_DANGER) != 0) return true
            }
        }
        return false
    }

    /**
     * Hands every stand reachable in one move from here to the visitor. The moves are worked out the first time a stand
     * is asked about and kept until a block near it changes; what is added on every call is only what differs from one
     * search to the next: the region, and what was learnt about the place ([AreaMemory]).
     */
    fun expand(from: Stand, visitor: Visitor) {
        val edges = edgesOf(from)
        val to = edges.to
        for (i in to.indices) {
            val target = to[i]
            var outside = 1.0
            if (bounded && !region.contains(target.x, target.y, target.z)) {
                if (!escapes(target)) continue
                outside = ESCAPE_COST_FACTOR
            }
            visitor.accept(target, edges.cost[i] * outside + AreaMemory.penalty(level, target.key()), MOVE_TYPES[edges.move[i].toInt()])
        }
    }

    private fun edgesOf(from: Stand): Edges {
        val section = section(from.x, from.y, from.z)
        if (section.moves == null || section.movesRules != rulesId) {
            section.moves = arrayOfNulls(TerrainCache.SECTION_SIZE)
            section.movesRules = rulesId
        }
        val index = TerrainCache.index(from.x, from.y, from.z)
        var edges = section.moves!![index]
        if (edges == null) {
            edgeTo.clear()
            edgeCost.clear()
            edgeMove.clear()
            findMoves(from)
            edges = if (edgeTo.isEmpty()) Edges.NONE else Edges(edgeTo.toTypedArray(), edgeCost.toFloatArray(), edgeMove.toByteArray())
            section.moves!![index] = edges
        }
        return edges
    }

    /** Works out every move from a stand into the scratch lists, whatever the region. */
    private fun findMoves(from: Stand) {
        val maxFall = settings.maxFallHeight()
        // A block's height can be walked without jumping when it is made of steps: a stair, or slabs one after
        // the other. Whether this one is is asked below, move by move.
        var maxRise = if (settings.allowJumps()) jumpHeight - JUMP_MARGIN else STAIR_HEIGHT
        // In water that comes higher than a few steps of a pixel up the legs the game gives no jump: the player swims up,
        // holding the jump key, and climbs out onto a block as high as the water - and that is all, whatever the jump boost
        // would give them on land. Water that stands less high than that does not stop a jump, and the player jumps from it
        // as from dry ground.
        if (from.waterDepth > FLUID_JUMP_LIMIT) maxRise = from.waterDepth + WATER_LEDGE
        val highest = Mth.floor(from.floorY + maxRise) + 1
        val lowest = Mth.floor(from.floorY - maxFall) - 1

        for (offset in OFFSETS) {
            val dx = offset[0]
            val dz = offset[1]
            val diagonal = dx != 0 && dz != 0
            if (diagonal && !settings.allowDiagonals()) continue

            var y = highest
            while (y >= lowest) {
                val to = standAnywhere(from.x + dx, y, from.z + dz)
                if (to == null) {
                    y--
                    continue
                }
                val rise = to.floorY - from.floorY
                if (rise > maxRise + EPSILON || rise < -maxFall - EPSILON) {
                    y--
                    continue
                }

                val move: MoveType
                if (from.floating && to.floating) {
                    // Along the surface, the water level may differ a block from one place to the next.
                    if (abs(rise) > 1.0 + EPSILON) {
                        y--
                        continue
                    }
                    move = MoveType.SWIM
                } else if (rise > STEP_HEIGHT + EPSILON) {
                    if (rise <= STAIR_HEIGHT + EPSILON && climbs(from, to)) {
                        move = MoveType.STEP
                    } else if (settings.allowJumps()) {
                        move = MoveType.JUMP
                    } else {
                        y--
                        continue
                    }
                } else if (rise < -STEP_HEIGHT) {
                    // Down a stair is a walk, a step at a time, not a fall off a ledge.
                    move = if (rise >= -STAIR_HEIGHT - EPSILON && climbs(to, from)) MoveType.WALK else MoveType.DROP
                } else if (rise > EPSILON) {
                    move = MoveType.STEP
                } else {
                    move = MoveType.WALK
                }
                // A diagonal jump has to clear two corners at once and lands far less reliably than the
                // pair of cardinal moves it can always be split into. Not worth the routes it unlocks.
                if (move == MoveType.JUMP && diagonal) {
                    y--
                    continue
                }
                if (!canTraverse(from, to, diagonal, move)) {
                    y--
                    continue
                }
                edgeTo.add(to)
                edgeCost.add(cost(from, to, move, diagonal).toFloat())
                edgeMove.add(move.ordinal.toByte())
                y--
            }
        }
    }

    /**
     * Whether the player can walk from the lower stand to the higher one without jumping, because there is a surface
     * between them that is never more than a step higher than the one before.
     *
     * A stair is a block high but two half-block steps, and the stand above it is the top step: measured stand to
     * stand it is a rise of 1, which a jump would be needed for, and with jumping off it was no way up at all. The
     * floor is followed along the line between the two stands, as the player's feet would, and the box of the body
     * (with no margin: the game does not give any) has to fit at every point. A stair taken from the side fails
     * that, because the upper step is in the way of the body at the height of the lower one - which is also what
     * stops the player in the game.
     */
    private fun climbs(lower: Stand, upper: Stand): Boolean {
        val fromX = lower.centerX()
        val fromZ = lower.centerZ()
        val toX = upper.centerX()
        val toZ = upper.centerZ()
        val distance = sqrt((toX - fromX) * (toX - fromX) + (toZ - fromZ) * (toZ - fromZ))
        val samples = max(1, ceil(distance / CLIMB_SAMPLE_STEP).toInt())
        var previous = lower.floorY
        for (i in 1..samples) {
            val t = i / samples.toDouble()
            val x = Mth.lerp(t, fromX, toX)
            val z = Mth.lerp(t, fromZ, toZ)
            val floor = surfaceUnder(x, z, previous, STEP_HEIGHT)
            if (!floor.isFinite() || floor < previous - CLIMB_DIP) return false
            if (!clearBox(playerBox(x, floor, z, 0.0))) return false
            previous = floor
        }
        return abs(previous - upper.floorY) <= CLIMB_END_TOLERANCE
    }

    private fun canTraverse(from: Stand, to: Stand, diagonal: Boolean, move: MoveType): Boolean {
        // The box crosses at the higher of the two floors: a step or a jump rises before it passes the
        // edge, and a drop keeps its height until it leaves the ledge.
        val travelY = max(from.floorY, to.floorY)
        val ceiling = travelY + height - EPSILON
        if (!clearColumn(from.x, from.z, from.floorY + EPSILON, ceiling)) return false
        // One range for the whole of the target column, so a deep drop cannot leave the stretch between
        // the landing and the ledge unchecked.
        if (!clearColumn(to.x, to.z, to.floorY + EPSILON, ceiling)) return false
        if (diagonal && move != MoveType.DROP && abs(to.floorY - from.floorY) > EPSILON) {
            // Diagonally down or up a flight of stairs or slabs. One of the two neighbours is a step of the same
            // flight, as high as the upper stand: open above that height, which is where the body passes it, but not
            // down to the lower floor - and checked from the lower floor up, as below, every diagonal on a slope was
            // refused, and the route went down a flight in a staircase of straight steps. So the body is followed
            // along the line instead, on the floor under it at every point.
            return clearSweptSlope(from, to)
        }
        if (diagonal) {
            // Cutting a corner sweeps the box of the player through both cardinal neighbours, so both have
            // to be open over the full height range. Without this the route clips a corner or low wall.
            val dx = to.x - from.x
            val dz = to.z - from.z
            val lowestFeet = min(from.floorY, to.floorY) + EPSILON
            if (!clearColumn(from.x + dx, from.z, lowestFeet, ceiling) || !clearColumn(from.x, from.z + dz, lowestFeet, ceiling)) {
                return false
            }
        }
        if (move == MoveType.JUMP) {
            // The body rises along an arc, so testing it at landing height over the entire move can reject a
            // valid takeoff. The endpoint columns above still check the route, and the landing gets the full
            // horizontal clearance buffer here.
            return clearBox(playerBox(to.centerX(), to.floorY, to.centerZ(), PATH_CLEARANCE))
        }
        return clearSweptBody(from.centerX(), from.centerZ(), to.centerX(), to.centerZ(), travelY)
    }

    /** Checks the whole center-line of a grid move with a gradually increasing clearance buffer. */
    private fun clearSweptBody(fromX: Double, fromZ: Double, toX: Double, toZ: Double, floorY: Double): Boolean {
        val distance = sqrt((toX - fromX) * (toX - fromX) + (toZ - fromZ) * (toZ - fromZ))
        val samples = max(1, ceil(distance / SLIDE_SAMPLE_STEP).toInt())
        for (i in 1..samples) {
            val t = i / samples.toDouble()
            val x = Mth.lerp(t, fromX, toX)
            val z = Mth.lerp(t, fromZ, toZ)
            // The first stand may already be close to a wall. Let the player leave that spot while the
            // safety buffer grows over the move; every later stand must provide the full clearance.
            if (!clearBox(playerBox(x, floorY, z, PATH_CLEARANCE * t))) return false
        }
        return true
    }

    /**
     * Whether the body can go along the line between two stands of different height, standing on whatever floor is under
     * it at each point - which is a step of the flight wherever it is - with the room around it growing as for any grid
     * move. Nothing that hurts may be under or around it on the way.
     */
    private fun clearSweptSlope(from: Stand, to: Stand): Boolean {
        val fromX = from.centerX()
        val fromZ = from.centerZ()
        val toX = to.centerX()
        val toZ = to.centerZ()
        val distance = sqrt((toX - fromX) * (toX - fromX) + (toZ - fromZ) * (toZ - fromZ))
        val samples = max(1, ceil(distance / SLIDE_SAMPLE_STEP).toInt())
        var previous = from.floorY
        for (i in 1..samples) {
            val t = i / samples.toDouble()
            val x = Mth.lerp(t, fromX, toX)
            val z = Mth.lerp(t, fromZ, toZ)
            // The floor under the box with its room around it, not under the feet alone: the room reaches over the edge
            // of the next step before the feet do, and a box set down on the lower step there is in the face of the higher.
            val clearance = PATH_CLEARANCE * t
            val floor = surfaceUnder(x, z, previous, STEP_HEIGHT, clearance)
            if (!floor.isFinite()) return false
            if (!clearBox(playerBox(x, floor, z, clearance)) || dangerousAt(x, floor, z)) return false
            previous = floor
        }
        return abs(previous - to.floorY) <= CLIMB_END_TOLERANCE
    }

    private fun cost(from: Stand, to: Stand, move: MoveType, diagonal: Boolean): Double {
        val perBlock = minimumCostPerBlock()
        var cost = perBlock * (if (diagonal) DIAGONAL else 1.0) / max(0.2, to.speedFactor)
        when (move) {
            MoveType.STEP -> cost += STEP_EXTRA
            // A jump costs more the higher it goes: with a jump boost on, three blocks up is one hop, and the stairs a couple of blocks
            // to the side would never be taken. A block of rise past the first is JUMP_PER_RISE more.
            MoveType.JUMP -> cost += jumpExtra(to.floorY - from.floorY, settings.jumpCost())
            MoveType.DROP -> cost += DROP_BASE + (from.floorY - to.floorY) * DROP_PER_BLOCK
            else -> Unit
        }
        if (to.floating) {
            cost *= SWIM_MULTIPLIER
        } else if (to.water) {
            cost *= WATER_MULTIPLIER
        }
        if (to.nearHazard) cost += HAZARD_PENALTY
        // Room to move, see SQUEEZE_PENALTY and EDGE_PENALTY. Not in water, where there are no walls to catch on that matter.
        if (!to.floating) {
            cost += min(SQUEEZE_LIMIT, to.squeeze) * SQUEEZE_PENALTY
            cost += min(EDGE_LIMIT, to.edges * EDGE_PENALTY)
        }
        // What was learnt about the place (AreaMemory) is added when the move is handed out, see expand.
        return max(MIN_MOVE_COST, cost)
    }

    /** Whether the box of the player fits in one column over the given height range. */
    fun clearColumn(x: Int, z: Int, lo: Double, hi: Double): Boolean {
        // Start two blocks low: a fence or wall is 1.5 tall and reaches out of its own cell.
        val from = Mth.floor(lo) - 2
        val to = Mth.floor(hi)
        for (y in from..to) {
            val cell = cell(x, y, z)
            if (!cell.loaded) return false
            if (blocks(cell, lo, hi)) return false
        }
        return true
    }

    /** Whether an arbitrary box is free of block collision. Entities deliberately do not count. */
    fun clearBox(box: AABB): Boolean {
        val minY = Mth.floor(box.minY) - 2
        val maxY = Mth.floor(box.maxY)
        for (x in Mth.floor(box.minX)..Mth.floor(box.maxX)) {
            for (z in Mth.floor(box.minZ)..Mth.floor(box.maxZ)) {
                for (y in minY..maxY) {
                    val cell = cell(x, y, z)
                    if (!cell.loaded) return false
                    for (part in cell.parts) {
                        if (part.intersects(box)) return false
                    }
                }
            }
        }
        return true
    }

    /**
     * The collision surface under an arbitrary point, near an expected height. Route smoothing needs
     * this: the line between two stands runs at angles no grid step ever takes, so its floor cannot be
     * read off a column.
     */
    fun surfaceUnder(x: Double, z: Double, expectedY: Double, tolerance: Double): Double =
        surfaceUnder(x, z, expectedY, tolerance, 0.0)

    /** Like [surfaceUnder], under one point instead of the player's footprint. */
    fun floorAtPoint(x: Double, z: Double, expectedY: Double, tolerance: Double): Double =
        surfaceUnder(x, z, expectedY, tolerance, 0.03 - halfWidth)

    /** Like [surfaceUnder], under a footprint this much wider on every side. */
    private fun surfaceUnder(x: Double, z: Double, expectedY: Double, tolerance: Double, extra: Double): Double {
        var best = Double.NaN
        val low = expectedY - tolerance
        val high = expectedY + tolerance
        val footMinX = x - halfWidth - extra + EPSILON
        val footMaxX = x + halfWidth + extra - EPSILON
        val footMinZ = z - halfWidth - extra + EPSILON
        val footMaxZ = z + halfWidth + extra - EPSILON
        for (bx in Mth.floor(footMinX)..Mth.floor(footMaxX)) {
            for (bz in Mth.floor(footMinZ)..Mth.floor(footMaxZ)) {
                var by = Mth.floor(high)
                while (by >= Mth.floor(low) - 2) {
                    val cell = cell(bx, by, bz)
                    if (!cell.loaded) return Double.NaN
                    for (part in cell.parts) {
                        if (part.maxY < low || part.maxY > high) continue
                        if (part.maxX > footMinX && part.minX < footMaxX && part.maxZ > footMinZ && part.minZ < footMaxZ) {
                            best = maxNaN(best, part.maxY)
                        }
                    }
                    by--
                }
            }
        }
        return best
    }

    /**
     * How far the body can be moved sideways from a spot, in the direction given and up to `max`, before it meets
     * something or the middle of it has no floor under it any more.
     */
    fun sideRoom(at: Vec3, dirX: Double, dirZ: Double, max: Double): Double {
        var reached = 0.0
        var moved = 0.1
        while (moved <= max + EPSILON) {
            val x = at.x + dirX * moved
            val z = at.z + dirZ * moved
            val floor = surfaceUnder(x, z, at.y, STEP_HEIGHT, CENTRE_SUPPORT - halfWidth)
            if (!floor.isFinite() || !clearBox(playerBox(x, floor, z, 0.0))) break
            reached = moved
            moved += 0.1
        }
        return reached
    }

    /**
     * How far the player can go from a point in a direction, up to the given length, before the ground ends under them:
     * the floor is followed as a walk follows it, and where it falls away by more than a step - under the whole of the
     * body, which the game holds up by its edge - that is as far as it goes. A wall in the way stops the player and is
     * not the end of the ground, so the full length is returned for it.
     */
    fun groundAhead(from: Vec3, dirX: Double, dirZ: Double, length: Double): Double {
        var floor = from.y
        var along = SLIDE_SAMPLE_STEP
        while (along < length + SLIDE_SAMPLE_STEP) {
            val at = min(along, length)
            val x = from.x + dirX * at
            val z = from.z + dirZ * at
            val under = surfaceUnder(x, z, floor, STEP_HEIGHT)
            if (!under.isFinite()) {
                // Nothing within a step: a wall, or a drop.
                return if (clearBox(playerBox(x, floor, z, 0.0))) max(0.0, at - SLIDE_SAMPLE_STEP) else length
            }
            floor = under
            along += SLIDE_SAMPLE_STEP
        }
        return length
    }

    /**
     * How much room the body has at a spot: the widest margin around the hitbox, up to [ROOM_LIMIT], in
     * which no block is in the way, or -1 if not even the hitbox fits - or there is no floor under the spot.
     */
    fun room(feet: Vec3): Double {
        val floor = surfaceUnder(feet.x, feet.z, feet.y, STEP_HEIGHT)
        if (!floor.isFinite() || abs(floor - feet.y) > 0.2) return -1.0
        var margin = ROOM_LIMIT
        while (margin >= -EPSILON) {
            if (clearBox(playerBox(feet.x, feet.y, feet.z, max(0.0, margin)))) return max(0.0, margin)
            margin -= 0.1
        }
        return -1.0
    }

    /**
     * Whether the player could walk the straight line between two stands. This is what lets a route be
     * pulled taut: the grid path zig-zags, and every zig-zag the player can simply walk across is one
     * the follower should not have to steer around.
     */
    fun canSlide(from: Vec3, to: Vec3): Boolean = line(from, to, SHORTCUT_CLEARANCE, 0.0)

    /**
     * Whether a player standing at the first point could walk the straight line to the second, with the room of a
     * shortcut around them or - not [roomy] - only that of a step of the grid. Unlike [canSlide] and
     * [canWalkLine], which are for two points of a route, this is for somebody who is where they are: right
     * against a wall, perhaps. The room asked for grows from none where they stand to all of it a little way on, so
     * that a line leading away from the wall is one they can take.
     */
    fun canWalkFrom(from: Vec3, to: Vec3, roomy: Boolean): Boolean =
        if (roomy) line(from, to, SHORTCUT_CLEARANCE, 1.0) else line(from, to, PATH_CLEARANCE, 0.5)

    /**
     * Like [canSlide], but with only the room a single step of the grid gets. For checking a stretch of
     * route that was moved a little, which has to be no tighter than the grid steps it replaces were.
     */
    fun canWalkLine(from: Vec3, to: Vec3): Boolean = line(from, to, PATH_CLEARANCE, 0.0)

    /** @param ramp over how many blocks from its start the room asked for grows from none to all of it; 0 for all of it at once */
    private fun line(from: Vec3, to: Vec3, room: Double, ramp: Double): Boolean {
        val distance = from.distanceTo(to)
        if (distance < EPSILON) return true
        val samples = ceil(distance / SLIDE_SAMPLE_STEP).toInt()
        var previousFloor = from.y
        for (i in 1..samples) {
            val t = i / samples.toDouble()
            val x = Mth.lerp(t, from.x, to.x)
            val z = Mth.lerp(t, from.z, to.z)
            val clearance = if (ramp > 0.0) room * min(1.0, t * distance / ramp) else room
            // Each sample is measured against the last one rather than against a straight interpolation,
            // so a flight of stairs reads as a series of steps instead of drifting away from the line. And it is
            // the floor under the box with its room, which reaches the next step before the feet do: set down on
            // the lower step, the box would be in the face of the higher one, and no line across a flight of
            // stairs at an angle would ever be walkable.
            val floor = surfaceUnder(x, z, previousFloor, STEP_HEIGHT, clearance)
            if (!floor.isFinite()) return false
            if (!clearBox(playerBox(x, floor, z, clearance))) return false
            // The middle of the body has to be over the floor too, or no more than a step above it. The game holds a player up
            // by the edge of their box, and so did this check: a line across the corner of a walkway one block wide, with
            // nothing under it but the drop, passed because a corner of the box was still over the walkway - and the walker,
            // which is never exactly on the line, went over the edge.
            val middle = surfaceUnder(x, z, floor, STEP_HEIGHT, CENTRE_SUPPORT - halfWidth)
            if (!middle.isFinite() || middle < floor - STEP_HEIGHT - EPSILON) return false
            if (dangerousAt(x, floor, z) || !insideRegion(x, floor, z)) return false
            previousFloor = floor
        }
        // The line also has to end where the route says it does, not a step above or below it.
        return abs(previousFloor - to.y) <= STEP_HEIGHT
    }

    private fun playerBox(x: Double, floorY: Double, z: Double, clearance: Double): AABB {
        val half = halfWidth + clearance - EPSILON
        return AABB(x - half, floorY + EPSILON, z - half, x + half, floorY + height - EPSILON, z + half)
    }

    /**
     * Whether the whole footprint of the player at this point lies inside the region. A shortcut between
     * two stands crosses ground no stand covers, so it has to be checked sample by sample.
     */
    private fun insideRegion(x: Double, floorY: Double, z: Double): Boolean {
        val y = Mth.floor(floorY + EPSILON)
        for (bx in Mth.floor(x - halfWidth)..Mth.floor(x + halfWidth)) {
            for (bz in Mth.floor(z - halfWidth)..Mth.floor(z + halfWidth)) {
                if (!region.contains(bx, y, bz)) return false
            }
        }
        return true
    }

    private fun dangerousAt(x: Double, floorY: Double, z: Double): Boolean {
        val minY = Mth.floor(floorY - 0.05)
        val maxY = Mth.floor(floorY + height - EPSILON)
        for (bx in Mth.floor(x - halfWidth)..Mth.floor(x + halfWidth)) {
            for (bz in Mth.floor(z - halfWidth)..Mth.floor(z + halfWidth)) {
                for (by in minY..maxY) {
                    val flags = cell(bx, by, bz).flags
                    if ((flags and (FLAG_DANGER or FLAG_AVOID)) != 0) return true
                    if (!settings.allowWater() && (flags and FLAG_WATER) != 0) return true
                }
            }
        }
        return false
    }

    /**
     * Whether a hitbox can be hit from a point. Several spots on the box are tried, because a mob
     * behind a low wall is hittable at the head and not at the belly - and the aim will pick the spot
     * that works, so the route should not reject the stand over the one that does not.
     */
    fun hasLineOfSight(eye: Vec3, box: AABB): Boolean {
        val center = box.center
        val candidates = arrayOf(
            center,
            Vec3(center.x, box.maxY - 0.15, center.z),
            Vec3(center.x, box.minY + 0.25, center.z),
        )
        for (candidate in candidates) {
            if (reaches(eye, candidate)) return true
        }
        return false
    }

    private fun reaches(from: Vec3, to: Vec3): Boolean {
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (hit.type == HitResult.Type.MISS) return true
        return hit.location.distanceToSqr(from) >= to.distanceToSqr(from) - EPSILON
    }

    /** The section of the cache holding this block: the one asked for last if it is that again, which it nearly always is. */
    private fun section(x: Int, y: Int, z: Int): TerrainCache.Section {
        val key = BlockPos.asLong(x shr 4, y shr 4, z shr 4)
        val cache = this.cache
        val memo = memoSection
        if (memo != null && key == memoKey && memoEpoch == cache.epoch && memoNow == cache.now) return memo
        val section = cache.section(x, y, z)
        memoKey = key
        memoSection = section
        memoEpoch = cache.epoch
        memoNow = cache.now
        return section
    }

    private fun cell(x: Int, y: Int, z: Int): Cell {
        val section = section(x, y, z)
        val index = TerrainCache.index(x, y, z)
        var cell = section.cells[index]
        if (cell == null) {
            cell = computeCell(x, y, z)
            section.cells[index] = cell
        }
        return cell
    }

    private fun computeCell(x: Int, y: Int, z: Int): Cell {
        val pos = BlockPos(x, y, z)
        // An unloaded chunk is not empty space, it is unknown space. Routing into it is how a planner
        // walks its player off the edge of what it can actually see - unless it has been seen before and
        // kept, see WorldStore.
        if (!level.hasChunkAt(pos)) {
            val kept = WorldStore.stateAt(level, x, y, z)
            return if (kept == null) Cell.UNLOADED else cellOf(kept, x, y, z, pos, EmptyBlockGetter.INSTANCE)
        }
        return cellOf(level.getBlockState(pos), x, y, z, pos, level)
    }

    /** The cell of a block in this state, its shape asked of the given world: the real one, or nothing around it for a kept block. */
    private fun cellOf(state: BlockState, x: Int, y: Int, z: Int, pos: BlockPos, world: BlockGetter): Cell {
        val shape = state.getCollisionShape(world, pos)
        val local: List<AABB> = if (shape.isEmpty) emptyList() else shape.toAabbs()
        val parts = arrayOfNulls<AABB>(local.size)
        val ranges = DoubleArray(local.size * 2)
        var rangeCount = 0
        val footMinX = x + 0.5 - halfWidth + EPSILON
        val footMaxX = x + 0.5 + halfWidth - EPSILON
        val footMinZ = z + 0.5 - halfWidth + EPSILON
        val footMaxZ = z + 0.5 + halfWidth - EPSILON
        for (i in local.indices) {
            val part = local[i].move(x.toDouble(), y.toDouble(), z.toDouble())
            parts[i] = part
            // Only the parts under the middle of the column can hold up or block a player standing in it,
            // which is the check the search runs millions of times. Keep it as a flat list of spans.
            if (part.maxX > footMinX && part.minX < footMaxX && part.maxZ > footMinZ && part.minZ < footMaxZ) {
                ranges[rangeCount++] = part.minY
                ranges[rangeCount++] = part.maxY
            }
        }
        val flags = flagsOf(state)
        val speedFactor = state.block.speedFactor
        // How high the water stands in this block, which is what a player in it is told by the game about how deep it is.
        val fluidHeight = if (state.fluidState.`is`(FluidTags.WATER)) state.fluidState.getHeight(world, pos) else 0.0f
        if (parts.isEmpty() && flags == 0 && speedFactor == 1.0f) return Cell.EMPTY
        @Suppress("UNCHECKED_CAST")
        return Cell(true, parts as Array<AABB>, if (rangeCount == ranges.size) ranges else ranges.copyOf(rangeCount), flags, speedFactor, fluidHeight)
    }

    companion object {
        /** Slack used wherever a box must not count as touching the surface it rests on. */
        private const val EPSILON = 1.0E-4

        /** What the game lifts the player over without a jump - `Entity.maxUpStep` for players. */
        const val STEP_HEIGHT = 0.6

        /** How high a standing jump actually gets the feet, ignoring potion effects. */
        const val JUMP_HEIGHT = 1.25

        /** How far from a start the region shuts a search may step over shut ground, and how much dearer that ground is to walk. */
        const val ESCAPE_RADIUS = 3.0
        const val ESCAPE_COST_FACTOR = 6.0

        /** Kept off the very top of a jump: the apex is a moment, and a landing on the edge of it is a guess. */
        private const val JUMP_MARGIN = 0.03

        /** The height of one block, which is as much as two steps of a stair or of slabs climb. */
        private const val STAIR_HEIGHT = 1.0

        /**
         * The most water above the feet in which the player can still jump from the ground - the game's own limit for a
         * player. In more than this a jump is only a swim upwards.
         */
        private const val FLUID_JUMP_LIMIT = 0.4

        /** How far up from a player under water to look for the surface they will float up to. */
        private const val SURFACE_SEARCH = 12

        /**
         * How far above the surface of the water a block can be and be climbed onto from the water: the top of a block as high
         * as the water's cell, which is 1/9 over a full block of water - and a little more, for the rounding of 0.8889.
         */
        private const val WATER_LEDGE = 0.12

        /** How finely the floor is followed along a climb, and how far it may sink and how far off the end may be. */
        private const val CLIMB_SAMPLE_STEP = 0.1
        private const val CLIMB_DIP = 0.05
        private const val CLIMB_END_TOLERANCE = 0.01

        /** Ticks a sprinting player needs per block: the unit every cost here is measured in. */
        private const val SPRINT_TICKS_PER_BLOCK = 3.56
        private const val WALK_TICKS_PER_BLOCK = 4.63
        private const val DIAGONAL = 1.41421356
        private const val STEP_EXTRA = 0.6

        /** A jump costs the ground speed it gives up, not just its air time. */
        private const val JUMP_EXTRA = 6.0
        private const val JUMP_PER_RISE = 7.0

        /** What a jump up [rise] blocks costs on top of the walk, in ticks, [scale] being the setting (1 is plain). */
        @JvmStatic
        fun jumpExtra(rise: Double, scale: Double): Double = (JUMP_EXTRA + JUMP_PER_RISE * max(0.0, rise - 1.0)) * scale
        private const val DROP_BASE = 0.8
        private const val DROP_PER_BLOCK = 0.9

        /** Walking in water is roughly half speed and cannot be sprinted. */
        private const val WATER_MULTIPLIER = 2.2

        /** Swimming along the surface is a little quicker than wading, and slower than walking. */
        private const val SWIM_MULTIPLIER = 1.7

        /**
         * What a place costs for every wall right beside it, per side (a wall on a corner counts half): the route keeps a
         * block off walls, pillars and steps too high to walk, where it has the room. A body that runs along a wall at arm's
         * length catches on every block that sticks out of it, and at a corner the inside of the turn is the block it has to
         * get round - kept off it, the route bends round corners wide by itself. A corridor one block wide costs more, not
         * so much that a long detour beats it: the penalty stops at [SQUEEZE_LIMIT].
         */
        private const val SQUEEZE_PENALTY = 1.2
        private const val SQUEEZE_LIMIT = 3.0

        /**
         * What a place costs for every side on which the ground falls away further than a step: the route keeps off the lip
         * of a drop it is not taking, so that a little drift of the walker does not take it down.
         */
        private const val EDGE_PENALTY = 1.1
        private const val EDGE_LIMIT = 2.0

        /** How far the ground beside a place may fall before it is an edge: a little more than a stair, less than a jump. */
        private const val EDGE_DEPTH = 1.1

        /** Enough to buy a detour of a couple of blocks around anything that deals damage. */
        private const val HAZARD_PENALTY = 9.0
        private const val MIN_MOVE_COST = 1.0

        private const val SLIDE_SAMPLE_STEP = 0.25

        /** Extra room around the player's hitbox so small steering errors do not scrape a wall. */
        private const val PATH_CLEARANCE = 0.1

        /**
         * Extra room a shortcut that cuts across open ground must keep from every block. Much more than a step of the
         * grid needs: a corridor one block wide has to stay usable, but a line pulled across a corner is only worth it
         * if the box of the player passes with room to spare, because the walker is never exactly on the line.
         */
        private const val SHORTCUT_CLEARANCE = 0.3

        /** Half the size of the middle of the body that a straight line has to keep over the floor, in blocks. */
        private const val CENTRE_SUPPORT = 0.1

        /** The most margin around the hitbox [room] bothers to look for. */
        private const val ROOM_LIMIT = 0.6

        private const val FLAG_WATER = 1

        /** Deals damage or swallows the player: never stand here, never pass through. */
        private const val FLAG_DANGER = 2

        /** Passable but ruins movement - cobweb. Treated like danger by the planner. */
        private const val FLAG_AVOID = 4

        private val OFFSETS = arrayOf(
            intArrayOf(0, -1), intArrayOf(0, 1), intArrayOf(-1, 0), intArrayOf(1, 0),
            intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1),
        )

        private val NO_STAND = Any()
        private val MOVE_TYPES = MoveType.values()

        /**
         * How high a jump of this player gets the feet, from the jump strength they have and the jump boost effect on them,
         * by the game's own motion: the rise is the speed, and every tick the speed falls by gravity and is damped. Without
         * a boost this comes to the 1.25 of a plain jump. The jump itself is made the way any is, with the jump key - the
         * planner only learns how much a jump can do, it does not do any more than the game lets the player.
         */
        @JvmStatic
        fun jumpHeightOf(player: LocalPlayer): Double {
            var power = player.getAttributeValue(Attributes.JUMP_STRENGTH)
            val boost = player.getEffect(MobEffects.JUMP_BOOST)
            if (boost != null) power += 0.1 * (boost.amplifier + 1)
            val gravity = player.getAttributeValue(Attributes.GRAVITY)
            var height = 0.0
            var tick = 0
            while (tick < 200 && power > 0.0) {
                height += power
                power = (power - gravity) * 0.98
                tick++
            }
            return max(JUMP_HEIGHT, height)
        }

        private fun flagsOf(state: BlockState): Int {
            var flags = 0
            if (state.fluidState.`is`(FluidTags.WATER)) flags = flags or FLAG_WATER
            val block = state.block
            if (state.fluidState.`is`(FluidTags.LAVA) ||
                state.`is`(BlockTags.FIRE) ||
                state.`is`(BlockTags.CAMPFIRES) ||
                block === Blocks.MAGMA_BLOCK ||
                block === Blocks.CACTUS ||
                block === Blocks.SWEET_BERRY_BUSH ||
                block === Blocks.WITHER_ROSE ||
                block === Blocks.POWDER_SNOW ||
                block === Blocks.POINTED_DRIPSTONE
            ) {
                flags = flags or FLAG_DANGER
            }
            if (block === Blocks.COBWEB) flags = flags or FLAG_AVOID
            return flags
        }

        /** Highest collision surface inside the half-open band, over the middle of the column. */
        private fun topIn(cell: Cell, lo: Double, hi: Double): Double {
            var best = Double.NaN
            val ranges = cell.ranges
            var i = 0
            while (i < ranges.size) {
                val top = ranges[i + 1]
                if (top >= lo && top < hi) best = maxNaN(best, top)
                i += 2
            }
            return best
        }

        private fun blocks(cell: Cell, lo: Double, hi: Double): Boolean {
            val ranges = cell.ranges
            var i = 0
            while (i < ranges.size) {
                if (ranges[i] < hi && ranges[i + 1] > lo) return true
                i += 2
            }
            return false
        }

        /** Like `Math.max`, but a NaN is not a value: the other one wins. */
        private fun maxNaN(a: Double, b: Double): Double {
            if (a.isNaN()) return b
            if (b.isNaN()) return a
            return Math.max(a, b)
        }
    }
}
