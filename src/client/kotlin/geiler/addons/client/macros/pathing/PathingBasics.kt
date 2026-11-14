package geiler.addons.client.macros.pathing

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * Everything the planner needs to know about what it is allowed to do. A module implements this, so the pathfinder never
 * learns which feature is driving it and stays reusable.
 */
interface PathingSettings {
    /** How close the route has to get: eye-to-hitbox distance the follower aims to stand at. */
    fun approachDistance(): Double

    /** Largest height the route may deliberately walk off, in blocks. */
    fun maxFallHeight(): Double

    /** Whether the route may require jumping up a full block. */
    fun allowJumps(): Boolean

    /**
     * How much a jump costs next to its plain cost, 1 being the plain one: above it routes keep to stairs and slopes and jump less.
     * A jump of more than a block costs more still, by how much higher it goes.
     */
    fun jumpCost(): Double = 1.0

    /** Whether the route may cut corners diagonally instead of only moving along the grid. */
    fun allowDiagonals(): Boolean

    /** Whether the route may wade through water it can still stand up in, head above the surface. */
    fun allowWater(): Boolean

    /** Whether the route may swim across deeper water, along its surface. */
    fun allowSwim(): Boolean

    /**
     * How far a finished route may stray from the plain shortest line, 0 to 1. At 0 the route is the same every
     * time; above it, points along the route are moved to the side wherever the walls leave room for it.
     */
    fun pathRandomness(): Double = 0.0

    /** Which weave the route gets. The same seed gives the same weave, however often the route is re-planned. */
    fun pathSeed(): Long = 0L

    /**
     * The longest stretch between two waypoints of a finished route, in blocks: a longer straight stretch is divided.
     * 0 leaves the route as the search made it.
     */
    fun waypointSpacing(): Double = 0.0

    /** How far the waypoints of a route are moved off the middle of their blocks, 0 to 1. */
    fun offCentre(): Double = 0.0

    /**
     * How much the planner prefers a straight route that keeps to the wall, 0 to 1. Turns cost extra and so does leaving
     * the wall; at 0 neither does.
     */
    fun straightness(): Double = 0.0

    /** Whether the route may use the jump height the player actually has, with a jump boost effect on. */
    fun useJumpBoost(): Boolean = false

    /**
     * Whether the planner keeps a map of the walkable ground around the player, to rule out what cannot be reached
     * without a search and to guide the searches it does make. See [WalkGraph].
     */
    fun useRouteMap(): Boolean = false

    /** Whether the follower will sprint, which is also what the cost model assumes. */
    fun sprint(): Boolean

    /** Whether the follower may jump as it sprints along a straight stretch, where that is the faster way to move. */
    fun sprintJump(): Boolean = false

    /** Whether the follower sprints with this much of the way still ahead: not when it is told to sprint only when far from the end. */
    fun sprintsAt(distance: Double): Boolean = sprint()

    /** How far from the start the search may wander, in blocks. */
    fun searchRadius(): Int

    /** Wall-clock budget one search slice may spend per tick, in milliseconds. */
    fun searchMillisPerTick(): Double
}

/**
 * Where the planner is allowed to stand at all. A block outside the region does not exist as far as the search is
 * concerned: no stand is ever built there, so no route can start, pass through or end in it.
 *
 * Kept as its own interface so the pathfinder never learns what shapes the region - a gate, a box or anything else - and
 * stays reusable without it.
 */
fun interface Region {
    /** Whether the player may stand in the block whose feet cell is at this position. */
    fun contains(x: Int, y: Int, z: Int): Boolean

    /** What a step into this feet cell costs relative to the plain cost: below 1 for ground the route is to lean toward. */
    fun costFactor(x: Int, y: Int, z: Int): Double = 1.0

    /** The lowest [costFactor] anywhere: a search estimate scaled by it stays under the true cost. */
    fun minCostFactor(): Double = 1.0

    /**
     * Whether a body of this width and height carried along the straight line from [from] to [to] - a teleport - passes through
     * anything shut: a wall, forbidden ground, the gate and its margin, outside the bounds. Walking cannot do that, a teleport can.
     */
    fun crossesShut(from: net.minecraft.world.phys.Vec3, to: net.minecraft.world.phys.Vec3, halfWidth: Double, height: Double): Boolean = false

    companion object {
        /** No restriction. */
        @JvmField
        val UNBOUNDED = Region { _, _, _ -> true }
    }
}

/**
 * How one step of a route is travelled. The follower needs this and not just the geometry: walking off a ledge, stepping
 * up a slab and jumping a full block look identical as two points in space and need completely different things from the
 * keyboard.
 */
enum class MoveType {
    /** Level ground. */
    WALK,

    /** Up to `maxUpStep`, so the game lifts the player on its own - slabs, stairs, carpets. */
    STEP,

    /** Higher than the game will step, so the jump key has to be pressed before the edge. */
    JUMP,

    /** Down a ledge: keep walking and let gravity do it. */
    DROP,

    /** Along the surface of deep water, held up by the jump key. */
    SWIM,
}

/** One stand in the search graph, with the bookkeeping A* and the heap need. */
class PathNode(@JvmField val stand: Terrain.Stand) {
    /** How this node was entered, so the follower knows whether it has to jump into it. */
    @JvmField var move = MoveType.WALK
    @JvmField var parent: PathNode? = null
    @JvmField var g = 0.0
    @JvmField var h = 0.0
    @JvmField var f = 0.0
    @JvmField var closed = false

    /** Position in [NodeHeap], or -1 when not queued. Lets the heap re-sort a cheaper node. */
    @JvmField var heapIndex = -1
}

/**
 * Binary min-heap over [PathNode.f] with a decrease-key, which is the one thing a plain `PriorityQueue` cannot do:
 * finding a cheaper way to a node that is already queued has to re-sort that node, and re-adding it instead leaves stale
 * copies that get popped and re-expanded. Each node remembers its own slot, so a decrease is a sift-up from a known index.
 */
class NodeHeap {
    private var nodes = arrayOfNulls<PathNode>(512)
    private var size = 0

    fun isEmpty(): Boolean = size == 0

    fun insert(node: PathNode) {
        if (size == nodes.size) nodes = nodes.copyOf(size * 2)
        nodes[size] = node
        node.heapIndex = size++
        siftUp(node.heapIndex)
    }

    fun pop(): PathNode {
        val first = nodes[0]!!
        first.heapIndex = -1
        val last = nodes[--size]!!
        nodes[size] = null
        if (size > 0) {
            nodes[0] = last
            last.heapIndex = 0
            siftDown(0)
        }
        return first
    }

    /** Called after `node.f` was lowered while it sits in the heap. */
    fun decreased(node: PathNode) {
        siftUp(node.heapIndex)
    }

    private fun siftUp(from: Int) {
        var index = from
        val node = nodes[index]!!
        while (index > 0) {
            val parent = (index - 1) ushr 1
            if (nodes[parent]!!.f <= node.f) break
            move(nodes[parent]!!, index)
            index = parent
        }
        move(node, index)
    }

    private fun siftDown(from: Int) {
        var index = from
        val node = nodes[index]!!
        val half = size ushr 1
        while (index < half) {
            var child = index * 2 + 1
            val right = child + 1
            if (right < size && nodes[right]!!.f < nodes[child]!!.f) child = right
            if (nodes[child]!!.f >= node.f) break
            move(nodes[child]!!, index)
            index = child
        }
        move(node, index)
    }

    private fun move(node: PathNode, index: Int) {
        nodes[index] = node
        node.heapIndex = index
    }
}

/**
 * Where the client's world announces that a block or a chunk has changed, for everything here that keeps what it read
 * from the world: the terrain cache, the route map. All of it is the client thread's, so nothing is locked.
 */
object WorldWatch {
    /** Told about every change; the methods are called on the client thread, from inside the game's own update. */
    interface Listener {
        fun blockChanged(level: ClientLevel, x: Int, y: Int, z: Int)

        /** A chunk came in, was sent again or went away: everything in and one block around it may be different. */
        fun chunkChanged(level: ClientLevel, chunkX: Int, chunkZ: Int)
    }

    private val LISTENERS = ArrayList<Listener>()

    @JvmStatic
    fun add(listener: Listener) {
        if (!LISTENERS.contains(listener)) LISTENERS.add(listener)
    }

    /**
     * A block was replaced. What the planner reads of a block is its collision, its fluid and what kind of block it is,
     * so a change that leaves all of these as they were - a crop growing, a lamp lighting - changes nothing here.
     */
    @JvmStatic
    fun blockChanged(level: ClientLevel, pos: BlockPos, oldState: BlockState, newState: BlockState) {
        if (oldState.block === newState.block && oldState.fluidState == newState.fluidState &&
            oldState.getCollisionShape(level, pos) == newState.getCollisionShape(level, pos)
        ) {
            return
        }
        blockChanged(level, pos.x, pos.y, pos.z)
    }

    @JvmStatic
    fun blockChanged(level: ClientLevel, x: Int, y: Int, z: Int) {
        TerrainCache.blockChanged(level, x, y, z)
        WorldStore.blockChanged(level, x, y, z)
        for (listener in LISTENERS) listener.blockChanged(level, x, y, z)
    }

    @JvmStatic
    fun chunkChanged(level: ClientLevel, chunkX: Int, chunkZ: Int) {
        TerrainCache.chunkChanged(level, chunkX, chunkZ)
        WorldStore.chunkChanged(level, chunkX, chunkZ)
        for (listener in LISTENERS) listener.chunkChanged(level, chunkX, chunkZ)
    }
}
