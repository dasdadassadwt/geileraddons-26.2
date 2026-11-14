package geiler.addons.client.macros.pathing

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the search is looking for. Kept separate from the search itself so the same A* can be pointed at one entity, at
 * the nearest of several, or at a plain position without knowing the difference.
 */
interface Goal {
    /**
     * Where a stand that satisfies a goal can be: the block columns and the feet blocks to look at, all bounds
     * included. A goal that can say this can be asked about in advance, see [WalkGraph].
     */
    data class Focus(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int)

    /**
     * Optimistic remaining cost from a stand, in the same ticks-per-block units the moves use. Must
     * never overestimate, or the route stops being the cheapest one available.
     */
    fun heuristic(stand: Terrain.Stand): Double

    /** Whether the route may stop here. */
    fun reached(terrain: Terrain, stand: Terrain.Stand): Boolean

    /** Every stand for which [reached] can be true lies in here; null if that is not known. */
    fun focus(terrain: Terrain): Focus? = null
}

/**
 * Reached once the player stands in the block that was asked for - or in the one above or below it, since a coordinate
 * read off the screen is the feet, and a block that is the floor is as good as the air over it.
 */
class PositionGoal(private val block: BlockPos, terrain: Terrain) : Goal {
    private val center = Vec3(block.x + 0.5, block.y.toDouble(), block.z + 0.5)
    private val costPerBlock = terrain.heuristicCostPerBlock()
    private val maxRise = terrain.maxRisePerMove()

    fun block(): BlockPos = block

    override fun heuristic(stand: Terrain.Stand): Double {
        val dx = stand.centerX() - center.x
        val dz = stand.centerZ() - center.z
        // Height is gained on the way: a step up is a move forward as well, so a goal up a slope is as many moves away as
        // it is far along it - not as far as the straight line to it through the hill. Only where the way is steeper than
        // one move can climb does the height count for more than the distance.
        val climb = max(0.0, center.y - stand.floorY) / maxRise
        return max(0.0, max(sqrt(dx * dx + dz * dz), climb) - SLACK) * costPerBlock
    }

    override fun reached(terrain: Terrain, stand: Terrain.Stand): Boolean =
        stand.x == block.x && stand.z == block.z && abs(stand.y - block.y) <= 1

    override fun focus(terrain: Terrain): Goal.Focus = Goal.Focus(block.x, block.y - 1, block.z, block.x, block.y + 1, block.z)

    companion object {
        /** Slack on the straight-line distance, so the estimate can never be more than the true cost. */
        private const val SLACK = 0.7
    }
}

/**
 * Reached once the player could actually hit one of the given entities from where they stand: close enough, and with
 * something other than a wall between them.
 *
 * Several entities at once is not a convenience. Asking for the nearest one by straight-line distance and then routing to
 * it picks the mob on the other side of a wall over the one around the corner; letting a single search settle for
 * whichever comes first makes that choice by travel time, which is the thing that actually matters, and costs one search
 * instead of several.
 *
 * The hitboxes are snapshotted when the goal is built. A mob keeps moving while the search runs, so the answer is only
 * ever as fresh as the frame it started on - which is why the caller replans.
 */
class EntityGoal(entities: List<Entity>, private val approachDistance: Double, terrain: Terrain) : Goal {
    private class Candidate(val entity: Entity, val box: AABB)

    private val candidates: List<Candidate> = entities.map { Candidate(it, it.boundingBox) }
    private val eyeHeight = terrain.eyeHeight()
    private val costPerBlock = terrain.heuristicCostPerBlock()
    private val maxRise = terrain.maxRisePerMove()
    private var matched: Entity? = null

    fun isEmpty(): Boolean = candidates.isEmpty()

    /** The entity the reached stand can hit, once [reached] has said yes. */
    fun matched(): Entity? = matched

    override fun heuristic(stand: Terrain.Stand): Double {
        val eye = stand.eye(eyeHeight)
        var best = Double.POSITIVE_INFINITY
        for (candidate in candidates) {
            val box = candidate.box
            val flatX = max(max(box.minX - eye.x, 0.0), eye.x - box.maxX)
            val flatZ = max(max(box.minZ - eye.z, 0.0), eye.z - box.maxZ)
            // Height is gained on the way, as in a goal that is a place: only a mob higher than moves can climb counts for more.
            val climb = max(0.0, box.minY - eye.y) / maxRise
            val gap = max(sqrt(flatX * flatX + flatZ * flatZ), climb) - approachDistance
            best = min(best, max(0.0, gap))
        }
        return if (best == Double.POSITIVE_INFINITY) 0.0 else best * costPerBlock
    }

    override fun reached(terrain: Terrain, stand: Terrain.Stand): Boolean {
        val eye = stand.eye(eyeHeight)
        for (candidate in candidates) {
            if (candidate.box.distanceToSqr(eye) > approachDistance * approachDistance) continue
            if (!terrain.hasLineOfSight(eye, candidate.box)) continue
            matched = candidate.entity
            return true
        }
        return false
    }

    override fun focus(terrain: Terrain): Goal.Focus? {
        if (candidates.isEmpty()) return null
        val reach = approachDistance
        var minX = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (candidate in candidates) {
            val box = candidate.box
            minX = min(minX, floor(box.minX - reach).toInt())
            maxX = max(maxX, floor(box.maxX + reach).toInt())
            minZ = min(minZ, floor(box.minZ - reach).toInt())
            maxZ = max(maxZ, floor(box.maxZ + reach).toInt())
            // The eye has to be within reach of the box, and the eye is a fixed height above the feet.
            minY = min(minY, floor(box.minY - reach - eyeHeight).toInt())
            maxY = max(maxY, floor(box.maxY + reach - eyeHeight).toInt())
        }
        return Goal.Focus(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** Everything this goal was built to look for, for when none of it turned out to be reachable. */
    fun entities(): List<Entity> = candidates.map { it.entity }
}
