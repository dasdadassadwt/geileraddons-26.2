package geiler.addons.client.macros.pathing

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A finished route: a polyline of feet positions, each tagged with how it is entered, plus the few queries the follower
 * needs from it.
 *
 * The grid path that comes out of the search is pulled taut first. A search on a block grid can only turn in 45 degree
 * steps, so crossing open ground produces a staircase of one-block moves that no player would walk; replacing a run of
 * them with the straight line the player can actually walk removes both the zig-zag and the steering work it would cost.
 * Jumps and drops are never smoothed away: they are the places where the follower has to do something specific at a
 * specific spot.
 *
 * Then the route is divided into waypoints, those are moved to the middle of the room there is where one side of them is
 * tight (see [centre]), and last a little off the middle of their blocks. The search keeps off walls and edges by itself
 * where it can (see the squeeze in [Terrain]); the centring is for where it cannot, a passage two blocks wide, in which
 * every row of blocks is the one next to a wall.
 */
class Path private constructor(private val steps: List<Step>, private val length: Double, private val terrain: Terrain) {
    /**
     * One point of the route, and how the player gets into it from the one before.
     *
     * @param waypoint a point the route was divided at, shown as such; the start is never one
     */
    data class Step(val position: Vec3, val move: MoveType, val distance: Double, val waypoint: Boolean = false)

    /** A move ahead of the player that needs handling before it is reached. */
    data class Upcoming(val move: MoveType, val edgeDistance: Double, val from: Vec3, val to: Vec3)

    /** Where the player is on the route, and how far off it they are. */
    data class Projection(val distance: Double, val deviation: Double)

    /** The stretch of route between two points, and how it is travelled. */
    data class Segment(val from: Vec3, val to: Vec3, val move: MoveType)

    /** The terrain the route was planned in: the same world, region and body. */
    fun terrain(): Terrain = terrain

    fun steps(): List<Step> = steps

    fun length(): Double = length

    fun endPoint(): Vec3 = steps[steps.size - 1].position

    /** The point this far along the route, clamped to its ends. */
    fun pointAt(distance: Double): Vec3 {
        val clamped = Mth.clamp(distance, 0.0, length)
        for (i in 1 until steps.size) {
            val step = steps[i]
            if (clamped <= step.distance) {
                val previous = steps[i - 1]
                val span = step.distance - previous.distance
                val t = if (span > 1.0E-6) (clamped - previous.distance) / span else 1.0
                return previous.position.lerp(step.position, t)
            }
        }
        return endPoint()
    }

    /**
     * Where along the route the player is, measured only near where they last were. Projecting against
     * the whole route instead would let a route that passes near itself teleport the progress forward.
     */
    fun project(position: Vec3, fromDistance: Double): Projection = project(position, fromDistance, PROJECTION_AHEAD)

    /** Like [project], looking this far ahead of the last known place instead of the usual stretch. */
    fun project(position: Vec3, fromDistance: Double, ahead: Double): Projection {
        val low = fromDistance - PROJECTION_BEHIND
        val high = fromDistance + ahead
        var bestScore = Double.POSITIVE_INFINITY
        var bestDistance = fromDistance
        var bestDeviation = Double.POSITIVE_INFINITY

        for (i in 1 until steps.size) {
            val previous = steps[i - 1]
            val step = steps[i]
            if (step.distance < low || previous.distance > high) continue

            val from = previous.position
            val delta = step.position.subtract(from)
            val lengthSq = delta.lengthSqr()
            if (lengthSq < 1.0E-6) continue
            // Route progress follows horizontal movement. On a jump, the player's feet stay low until
            // takeoff; including the segment's rise here delays progress and can leave the jump trigger
            // just out of reach while the hitbox is already against the block.
            val horizontalLengthSq = delta.x * delta.x + delta.z * delta.z
            var t = if (horizontalLengthSq > 1.0E-6) {
                ((position.x - from.x) * delta.x + (position.z - from.z) * delta.z) / horizontalLengthSq
            } else {
                position.subtract(from).dot(delta) / lengthSq
            }
            t = Mth.clamp(t, 0.0, 1.0)
            val projected = from.add(delta.scale(t))
            val dx = position.x - projected.x
            val dz = position.z - projected.z
            val dy: Double
            if (step.move == MoveType.DROP || step.move == MoveType.JUMP) {
                // A drop or a jump has the height of its two ends in it, and the player is anywhere between them
                // while on it: still on the ledge as they walk off, in the air on the way down. Measured against
                // the straight line between the ends, a player who has walked past the edge but not yet started to
                // fall is "closer" to the edge point than to the drop, and progress stops there - which turns the
                // steering target around and sends them back.
                val lowest = min(from.y, step.position.y)
                val highest = max(from.y, step.position.y)
                val outside = if (position.y < lowest) lowest - position.y else max(0.0, position.y - highest)
                dy = outside * VERTICAL_WEIGHT
            } else {
                dy = (position.y - projected.y) * VERTICAL_WEIGHT
            }
            val score = dx * dx + dy * dy + dz * dz
            if (score < bestScore) {
                bestScore = score
                bestDistance = previous.distance + sqrt(lengthSq) * t
                bestDeviation = sqrt(dx * dx + dz * dz)
            }
        }

        if (bestScore == Double.POSITIVE_INFINITY) {
            // No segment near enough to measure against - a route of one point, or one left far behind.
            // Report no deviation for the degenerate case and let the caller decide on the length instead.
            return Projection(fromDistance, if (steps.size < 2) 0.0 else Double.POSITIVE_INFINITY)
        }
        return Projection(bestDistance, bestDeviation)
    }

    /**
     * The first jump or drop ahead of the player within the given distance, or null. The edge distance
     * is where the two stands meet, which is the spot a jump has to be pressed before, not the spot the
     * player is aiming to land on.
     */
    fun upcoming(fromDistance: Double, lookahead: Double): Upcoming? {
        for (i in 1 until steps.size) {
            val step = steps[i]
            val move = step.move
            if (move != MoveType.JUMP && move != MoveType.DROP) continue
            val previous = steps[i - 1]
            if (step.distance < fromDistance) continue
            val edge = (previous.distance + step.distance) * 0.5
            if (edge > fromDistance + lookahead) return null
            return Upcoming(move, edge, previous.position, step.position)
        }
        return null
    }

    /** The stretch of route this far along, or null past its end. */
    fun segmentAt(distance: Double): Segment? {
        for (i in 1 until steps.size) {
            val step = steps[i]
            if (distance <= step.distance) return Segment(steps[i - 1].position, step.position, step.move)
        }
        return null
    }

    companion object {
        /** Most grid steps one shortcut may swallow. */
        private const val MAX_SHORTCUT_STEPS = 24

        /** Longest straight line a shortcut may create, in blocks. */
        private const val MAX_SHORTCUT_LENGTH = 20.0

        /**
         * The second pass: short shortcuts with only the room a grid step has. On rough ground - stairs and slabs in a jumble,
         * blocks sticking out of the floor and the walls - the full room of a long shortcut is hardly ever there, and the route
         * stayed a zig-zag of one-block steps. A line no tighter than the steps it replaces is no worse for the walker, and as
         * long as it is short the walker cannot stray far from it.
         */
        private const val LOOSE_SHORTCUT_STEPS = 8
        private const val LOOSE_SHORTCUT_LENGTH = 6.0

        /**
         * Centring: how far to each side a point looks for room, how much is enough to leave it alone, how much difference
         * between the sides is worth a move, and the furthest it moves - then half of that, if the whole does not fit.
         */
        private const val CENTRE_REACH = 1.0
        private const val CENTRE_ENOUGH = 0.5
        private const val CENTRE_WORTH = 0.15
        private const val CENTRE_MAX_SHIFT = 0.5
        private val CENTRE_SCALES = doubleArrayOf(1.0, 0.5)

        /** How many refusals in a row end the search for a longer shortcut from one point. */
        private const val SHORTCUT_PATIENCE = 3

        /** How far back along the route a projection may look, so a doubling-back route cannot skip ahead. */
        private const val PROJECTION_BEHIND = 1.5
        private const val PROJECTION_AHEAD = 6.0

        /** Vertical error counts for less than horizontal: the player is in the air half the time. */
        private const val VERTICAL_WEIGHT = 0.5

        /** Room around the hitbox that is plenty: a waypoint moved off centre has to keep this much, or what it had. */
        private const val ROOM_ENOUGH = 0.5

        /** How much the randomness of the route adds to how far its waypoints are moved off centre. */
        private const val RANDOMNESS_SHARE = 0.5

        /**
         * How far a point of the route may be moved off the middle of its block at full off-centre, in blocks, and the
         * size of the cells of the field that says which way: points a few blocks apart are moved alike, so the route
         * bends gently instead of zig-zagging, and a route planned again moves its points the same way.
         */
        private const val OFF_CENTRE_AMPLITUDE = 0.45
        private const val OFF_CENTRE_CELL = 5.0
        private val OFF_CENTRE_SCALES = doubleArrayOf(1.0, 0.5, 0.25)

        // The constants of the lattice hash, as the original wrote them (unsigned 64-bit hex).
        private val HASH_X = 0x9E3779B97F4A7C15uL.toLong()
        private val HASH_Z = 0xC2B2AE3D27D4EB4FuL.toLong()
        private val HASH_MIX_A = 0xFF51AFD7ED558CCDuL.toLong()
        private val HASH_MIX_B = 0xC4CEB9FE1A85EC53uL.toLong()
        private const val SEED_X = 0x5DEECE66DL
        private const val SEED_Z = 0x2545F4914F6CDD1DL
        private const val TWO_POW_MINUS_53 = 1.0 / 9007199254740992.0

        /**
         * @param randomness 0 to 1: how far the route may stray from the plain line, added to the off-centre
         * @param seed picks which way; the same seed shifts the same way however often the route is planned again
         * @param spacing the longest stretch between two waypoints, in blocks; see [divide]
         * @param offCentre 0 to 1: how far the waypoints are moved off the middle of their blocks, see [shiftOffCentre]
         */
        @JvmStatic
        fun build(terrain: Terrain, chain: List<PathNode>, randomness: Double, seed: Long, spacing: Double, offCentre: Double): Path {
            val raw = ArrayList<Step>(chain.size)
            for (node in chain) raw.add(Step(node.stand.feet(), node.move, 0.0))

            val taut = pullTaut(raw, MAX_SHORTCUT_STEPS, MAX_SHORTCUT_LENGTH) { a, b -> terrain.canSlide(a, b) }
            var pulled = pullTaut(taut, LOOSE_SHORTCUT_STEPS, LOOSE_SHORTCUT_LENGTH) { a, b -> terrain.canWalkLine(a, b) }
            pulled = centre(terrain, divide(terrain, pulled, spacing))
            val away = min(1.0, offCentre + RANDOMNESS_SHARE * randomness)
            if (away > 0.0) pulled = shiftOffCentre(terrain, pulled, away, seed)
            pulled = leadIns(terrain, pulled)
            val measured = ArrayList<Step>(pulled.size)
            var distance = 0.0
            for (i in pulled.indices) {
                if (i > 0) distance += pulled[i - 1].position.distanceTo(pulled[i].position)
                measured.add(Step(pulled[i].position, pulled[i].move, distance, i > 0))
            }
            return Path(measured.toList(), distance, terrain)
        }

        /**
         * Divides the route into waypoints no further apart than `spacing`: a long straight stretch gets points in
         * between, evenly. The route is walked from one waypoint to the next, and it is the waypoints that are moved off the
         * middle of the blocks - with a waypoint every few blocks, a straight run across open ground is a line that bends a
         * little here and there instead of one ruled down the middle of a row of blocks.
         *
         * Only walked stretches are divided. A jump or a drop is a move of its own, from one exact spot to another.
         */
        private fun divide(terrain: Terrain, steps: List<Step>, spacing: Double): List<Step> {
            if (spacing <= 0.0 || steps.size < 2) return steps
            val out = ArrayList<Step>(steps.size * 2)
            out.add(steps[0])
            for (i in 1 until steps.size) {
                val previous = steps[i - 1]
                val step = steps[i]
                val length = previous.position.distanceTo(step.position)
                if (walking(step.move) && length > spacing) {
                    val pieces = ceil(length / spacing).toInt()
                    for (piece in 1 until pieces) {
                        val along = previous.position.lerp(step.position, piece / pieces.toDouble())
                        val floor = terrain.surfaceUnder(along.x, along.z, along.y, Terrain.STEP_HEIGHT)
                        if (floor.isFinite()) out.add(Step(Vec3(along.x, floor, along.z), step.move, 0.0))
                    }
                }
                out.add(step)
            }
            return out
        }

        /**
         * Moves the points of the route to the middle of what room there is to either side of them, where one side is tight.
         *
         * A route is made of block centres. In a passage two blocks wide that is the middle of one of its two rows: 0.2
         * from one wall and 1.2 from the other, all the way along - a line with no room to spare on one side, that the walker
         * cannot run along without touching the wall, while a block of free floor lies on the other. So each point looks
         * how far the body could be moved to its left and to its right (across the way the route is going there) before it
         * meets a wall or runs out of floor, and where the nearer of the two is closer than [CENTRE_ENOUGH] it moves
         * toward the other, to the middle or as far as [CENTRE_MAX_SHIFT] lets it. A point with room on both sides,
         * or with as little on the one as on the other, stays; so do the two ends and the points of a jump or a drop.
         */
        private fun centre(terrain: Terrain, steps: List<Step>): List<Step> {
            if (steps.size < 3) return steps
            val out = ArrayList(steps)
            for (i in 1 until out.size - 1) {
                val point = out[i]
                val next = out[i + 1]
                if (!walking(point.move) || !walking(next.move)) continue
                val before = out[i - 1].position
                val at = point.position
                val after = next.position
                val alongX = after.x - before.x
                val alongZ = after.z - before.z
                val length = sqrt(alongX * alongX + alongZ * alongZ)
                if (length < 1.0E-6) continue
                val sideX = -alongZ / length
                val sideZ = alongX / length
                val left = terrain.sideRoom(at, sideX, sideZ, CENTRE_REACH)
                val right = terrain.sideRoom(at, -sideX, -sideZ, CENTRE_REACH)
                if (min(left, right) >= CENTRE_ENOUGH || abs(left - right) < CENTRE_WORTH) continue
                val shift = Mth.clamp((left - right) * 0.5, -CENTRE_MAX_SHIFT, CENTRE_MAX_SHIFT)
                for (scale in CENTRE_SCALES) {
                    val x = at.x + sideX * shift * scale
                    val z = at.z + sideZ * shift * scale
                    val floor = terrain.surfaceUnder(x, z, at.y, Terrain.STEP_HEIGHT)
                    if (!floor.isFinite()) continue
                    val candidate = Vec3(x, floor, z)
                    if (terrain.canWalkLine(before, candidate) && terrain.canWalkLine(candidate, after)) {
                        out[i] = Step(candidate, point.move, 0.0)
                        break
                    }
                }
            }
            return out
        }

        /**
         * Moves the waypoints of the route off the middle of their blocks, so the player does not walk down the centre line
         * of every row of blocks. Which way and how far comes from a smooth field over the world, made from the seed of the
         * trip: points close together are moved alike, and a route planned again - from a step further on - moves its
         * points the same way, so the line does not twitch with every new plan.
         *
         * The room comes first: a point is moved only where it keeps the room it had (or [ROOM_ENOUGH], whichever
         * is less) and the lines to the points either side are still walkable; otherwise half of
         * the move is tried, then a quarter, then none. In a corridor one block wide nothing moves. Points at a jump or a
         * drop, and the two ends, stay where they are.
         */
        private fun shiftOffCentre(terrain: Terrain, steps: List<Step>, amount: Double, seed: Long): List<Step> {
            if (steps.size < 3) return steps
            val out = ArrayList(steps)
            val amplitude = OFF_CENTRE_AMPLITUDE * amount
            for (i in 1 until steps.size - 1) {
                val point = steps[i]
                val next = steps[i + 1]
                if (!walking(point.move) || !walking(next.move)) continue
                val at = point.position
                val moveX = amplitude * field(seed xor SEED_X, at.x, at.z)
                val moveZ = amplitude * field(seed xor SEED_Z, at.x, at.z)
                val needed = max(0.0, min(ROOM_ENOUGH, terrain.room(at)))
                for (scale in OFF_CENTRE_SCALES) {
                    val x = at.x + moveX * scale
                    val z = at.z + moveZ * scale
                    val floor = terrain.surfaceUnder(x, z, at.y, Terrain.STEP_HEIGHT)
                    if (!floor.isFinite() || abs(floor - at.y) > Terrain.STEP_HEIGHT) continue
                    val candidate = Vec3(x, floor, z)
                    if (terrain.room(candidate) >= needed &&
                        terrain.canWalkLine(out[i - 1].position, candidate) && terrain.canWalkLine(candidate, next.position)
                    ) {
                        out[i] = Step(candidate, point.move, 0.0)
                        break
                    }
                }
            }
            return out
        }

        /** Only the first stretch of the route is looked over for doors: the rest is planned again long before it is walked. */
        private const val GAP_LOOKAHEAD_STEPS = 12

        /**
         * Puts a point in line with every door or gap of a walked stretch, a little before it, and one in the middle of it, so the route goes
         * through straight (see [GapRules]). Not shifted off centre afterwards: these are exact on purpose.
         */
        private fun leadIns(terrain: Terrain, steps: List<Step>): List<Step> {
            if (steps.size < 2) return steps
            val out = ArrayList<Step>(steps.size + 4)
            out.add(steps[0])
            for (i in 1 until steps.size) {
                val a = steps[i - 1]
                val b = steps[i]
                if (i <= GAP_LOOKAHEAD_STEPS && walking(b.move) && walking(a.move)) {
                    val room = GapRules.Room { x, z, dx, dz -> terrain.sideRoom(Vec3(x, floorNear(terrain, x, z, a.position.y), z), dx, dz, GapRules.REACH) }
                    val walk = GapRules.Walk { fx, fz, tx, tz ->
                        val from = Vec3(fx, floorNear(terrain, fx, fz, a.position.y), fz)
                        val to = Vec3(tx, floorNear(terrain, tx, tz, a.position.y), tz)
                        from.y.isFinite() && to.y.isFinite() && terrain.canWalkLine(from, to)
                    }
                    val lead = GapRules.find(a.position.x, a.position.z, b.position.x, b.position.z, room, walk)
                    if (lead != null) {
                        val leadY = floorNear(terrain, lead.leadX, lead.leadZ, a.position.y)
                        val gapY = floorNear(terrain, lead.gapX, lead.gapZ, a.position.y)
                        if (leadY.isFinite() && gapY.isFinite()) {
                            out.add(Step(Vec3(lead.leadX, leadY, lead.leadZ), b.move, 0.0))
                            out.add(Step(Vec3(lead.gapX, gapY, lead.gapZ), b.move, 0.0))
                        }
                    }
                }
                out.add(b)
            }
            return out
        }

        private fun floorNear(terrain: Terrain, x: Double, z: Double, y: Double): Double = terrain.surfaceUnder(x, z, y, Terrain.STEP_HEIGHT)

        /** A smooth value between -1 and 1 that changes over a few blocks: the lattice, blended between its corners. */
        @JvmStatic
        fun field(seed: Long, x: Double, z: Double): Double {
            val fx = x / OFF_CENTRE_CELL
            val fz = z / OFF_CENTRE_CELL
            val x0 = Mth.floor(fx)
            val z0 = Mth.floor(fz)
            val tx = smooth(fx - x0)
            val tz = smooth(fz - z0)
            val top = Mth.lerp(tx, lattice(seed, x0, z0), lattice(seed, x0 + 1, z0))
            val bottom = Mth.lerp(tx, lattice(seed, x0, z0 + 1), lattice(seed, x0 + 1, z0 + 1))
            return Mth.lerp(tz, top, bottom)
        }

        private fun smooth(t: Double): Double = t * t * (3.0 - 2.0 * t)

        @JvmStatic
        fun lattice(seed: Long, x: Int, z: Int): Double {
            var hash = seed xor (x * HASH_X) xor (z * HASH_Z)
            hash = hash xor (hash ushr 33)
            hash *= HASH_MIX_A
            hash = hash xor (hash ushr 33)
            hash *= HASH_MIX_B
            hash = hash xor (hash ushr 33)
            return (hash ushr 11).toDouble() * TWO_POW_MINUS_53 * 2.0 - 1.0
        }

        private fun walking(move: MoveType): Boolean = move == MoveType.WALK || move == MoveType.STEP

        private fun pullTaut(raw: List<Step>, maxSteps: Int, maxLength: Double, walkable: (Vec3, Vec3) -> Boolean): List<Step> {
            if (raw.size <= 2) return raw

            val out = ArrayList<Step>()
            out.add(raw[0])
            var anchor = 0
            while (anchor < raw.size - 1) {
                var chosen = anchor + 1
                val limit = min(raw.size - 1, anchor + maxSteps)
                val from = raw[anchor].position
                // Extended one step at a time rather than trying the longest shortcut first and working back: sweeping the
                // box along a line is the expensive part of building a route. A refusal does not end it at once, though -
                // a line that grazes a boulder or a step too high is often free again a step or two further on, where the
                // route has gone round the thing - only a few in a row do.
                var refused = 0
                var candidate = anchor + 2
                while (candidate <= limit && refused < SHORTCUT_PATIENCE) {
                    if (!onlyWalkable(raw, anchor + 1, candidate)) break
                    val to = raw[candidate].position
                    if (from.distanceTo(to) > maxLength) break
                    if (walkable(from, to)) {
                        chosen = candidate
                        refused = 0
                    } else {
                        refused++
                    }
                    candidate++
                }
                out.add(raw[chosen])
                anchor = chosen
            }
            return out
        }

        private fun onlyWalkable(raw: List<Step>, from: Int, to: Int): Boolean {
            for (i in from..to) {
                val move = raw[i].move
                if (move != MoveType.WALK && move != MoveType.STEP) return false
            }
            return true
        }
    }
}
