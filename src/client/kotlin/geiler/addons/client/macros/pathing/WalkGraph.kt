package geiler.addons.client.macros.pathing

import it.unimi.dsi.fastutil.floats.FloatArrayList
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Every place the player can stand around one point, and every move from each of them to the next, written down once: the
 * walkable ground as a map of its connections instead of something to be discovered one search at a time.
 *
 * Two things are done with it. Which of the places can be got to from where the player stands is a walk through this
 * map - a few milliseconds, where a search for something that cannot be reached has to try everything before it may say
 * so - and so a mob behind a wall is known to be out of reach before any search for it is started. And the cost of
 * getting from anywhere to a few far-apart landmarks, worked out on this map once, tells the search how far it still has
 * to go; see [Landmarks].
 *
 * The moves and what they cost are the planner's own - they come out of [Terrain.expand] - so what the map says can be
 * done is what a search can do, no more and no less. The map is bounded the way a search is, only wider by
 * [MARGIN_BLOCKS], and a search that starts that near the middle of it stays inside it. A map goes out of date when the
 * world around it changes; it is told so ([WorldWatch]) and is then no longer believed about what cannot be reached,
 * which has to be certain.
 */
class WalkGraph private constructor(
    private val level: ClientLevel,
    private val signature: Signature,
    private val stands: Array<Terrain.Stand>,
    private val first: IntArray,
    private val to: IntArray,
    private val cost: FloatArray,
    private val index: Long2IntOpenHashMap,
    private val complete: Boolean,
    private val builtMillis: Long,
    private var dirty: Boolean,
) {
    /** Everything that decides what moves there are. A map is only any good for the signature it was made for. */
    data class Signature(
        val region: Region,
        val jumps: Boolean,
        val diagonals: Boolean,
        val water: Boolean,
        val swim: Boolean,
        val maxFall: Double,
        val jumpHeight: Double,
        val sprint: Boolean,
        val straightness: Double,
        val searchRadius: Int,
    )

    private val radius = radiusOf(signature)
    private var landmarks: Landmarks? = null

    fun level(): ClientLevel = level

    fun signature(): Signature = signature

    fun size(): Int = stands.size

    fun edges(): Int = to.size

    /** The stand the map was made around. */
    fun origin(): Terrain.Stand = stands[0]

    fun radius(): Double = radius

    fun complete(): Boolean = complete

    fun builtMillis(): Long = builtMillis

    fun stand(id: Int): Terrain.Stand = stands[id]

    /** The number of a stand in the map, or -1 if it is not in it. */
    fun idOf(stand: Terrain.Stand): Int = index.get(stand.key())

    fun idOf(x: Int, y: Int, z: Int): Int = index.get(BlockPos.asLong(x, y, z))

    /** Whether the world has changed in the area of the map since it was made. */
    fun dirty(): Boolean = dirty

    fun markDirty() {
        dirty = true
    }

    /** Believed about what cannot be reached: whole, up to date with the world, and not too old. */
    fun trusted(now: Long): Boolean = complete && !dirty && now - builtMillis <= TRUSTED_MILLIS

    fun usable(now: Long): Boolean = now - builtMillis <= USABLE_MILLIS

    fun landmarks(): Landmarks? = landmarks

    fun attach(landmarks: Landmarks) {
        this.landmarks = landmarks
    }

    /** Whether a search from this stand stays inside the map: near the middle of it, at about the same height. */
    fun admits(start: Terrain.Stand): Boolean {
        val middle = stands[0]
        val dx = start.centerX() - middle.centerX()
        val dz = start.centerZ() - middle.centerZ()
        return dx * dx + dz * dz <= MARGIN_BLOCKS * MARGIN_BLOCKS && abs(start.floorY - middle.floorY) <= MARGIN_Y
    }

    /** Whether a block position is inside the part of the world the map covers. */
    fun covers(x: Double, y: Double, z: Double): Boolean {
        val middle = stands[0]
        val dx = x - middle.centerX()
        val dz = z - middle.centerZ()
        return dx * dx + dz * dz <= radius * radius && abs(y - middle.floorY) <= PathSearch.MAX_VERTICAL + MARGIN_Y
    }

    /** Whether the world changing at this block can matter to the map. */
    fun touches(x: Int, y: Int, z: Int): Boolean {
        val middle = stands[0]
        val dx = x + 0.5 - middle.centerX()
        val dz = z + 0.5 - middle.centerZ()
        val reach = radius + 2.0
        return dx * dx + dz * dz <= reach * reach && abs(y - middle.floorY) <= PathSearch.MAX_VERTICAL + MARGIN_Y + 2.0
    }

    fun firstEdges(): IntArray = first

    fun edgeTargets(): IntArray = to

    fun edgeCosts(): FloatArray = cost

    /** Whether a chunk is, wholly or in part, inside the area of the map. */
    fun touchesChunk(chunkX: Int, chunkZ: Int): Boolean {
        val middle = stands[0]
        val nearestX = max(chunkX * 16.0, min(middle.centerX(), chunkX * 16.0 + 16.0))
        val nearestZ = max(chunkZ * 16.0, min(middle.centerZ(), chunkZ * 16.0 + 16.0))
        val dx = nearestX - middle.centerX()
        val dz = nearestZ - middle.centerZ()
        return dx * dx + dz * dz <= (radius + 2.0) * (radius + 2.0)
    }

    // ------------------------------------------------------------------ reaching

    /**
     * Everything that can be got to from this stand, or null when the map cannot say: it is not believed (see
     * [trusted]), or the stand is not near enough the middle of it to be sure a search from there stays inside.
     */
    fun reachFrom(start: Terrain.Stand, now: Long): Reach? {
        if (!trusted(now) || !admits(start)) return null
        val id = idOf(start)
        if (id < 0) return null

        val seen = BitSet(stands.size)
        val queue = IntArray(stands.size)
        var head = 0
        var tail = 0
        queue[tail++] = id
        seen.set(id)
        while (head < tail) {
            val node = queue[head++]
            for (edge in first[node] until first[node + 1]) {
                val next = to[edge]
                if (!seen.get(next)) {
                    seen.set(next)
                    queue[tail++] = next
                }
            }
        }
        return Reach(this, seen)
    }

    /**
     * The numbers of the places in the map where the goal is reached, or null if the goal is partly outside the map
     * (nothing can be said then). Empty means the goal cannot be reached from anywhere the map knows.
     */
    fun goalStands(terrain: Terrain, goal: Goal): IntArrayList? {
        val focus = goal.focus(terrain) ?: return null
        val low = focus.minY.toDouble()
        val high = focus.maxY.toDouble()
        if (!covers(focus.minX.toDouble(), low, focus.minZ.toDouble()) || !covers(focus.maxX + 1.0, high, focus.minZ.toDouble()) ||
            !covers(focus.minX.toDouble(), high, focus.maxZ + 1.0) || !covers(focus.maxX + 1.0, low, focus.maxZ + 1.0)
        ) {
            return null
        }

        val found = IntArrayList()
        for (x in focus.minX..focus.maxX) {
            for (z in focus.minZ..focus.maxZ) {
                for (y in focus.minY..focus.maxY) {
                    val stand = terrain.standAt(x, y, z) ?: continue
                    val id = idOf(stand)
                    if (id >= 0 && goal.reached(terrain, stand)) found.add(id)
                }
            }
        }
        return found
    }

    /** The places that can be got to from one stand. */
    class Reach internal constructor(private val graph: WalkGraph, private val places: BitSet) {
        enum class Verdict {
            /** Some place where the goal is reached can be got to. */
            REACHABLE,

            /** None can: no search could ever find a way. */
            UNREACHABLE,

            /** The map does not cover the goal. */
            UNKNOWN,
        }

        fun graph(): WalkGraph = graph

        fun contains(stand: Terrain.Stand): Boolean {
            val id = graph.idOf(stand)
            return id >= 0 && places.get(id)
        }

        fun contains(id: Int): Boolean = id >= 0 && places.get(id)

        fun count(): Int = places.cardinality()

        /** Whether anything that satisfies the goal can be got to. */
        fun verdict(terrain: Terrain, goal: Goal): Verdict {
            val stands = graph.goalStands(terrain, goal) ?: return Verdict.UNKNOWN
            for (i in 0 until stands.size) {
                if (places.get(stands.getInt(i))) return Verdict.REACHABLE
            }
            return Verdict.UNREACHABLE
        }
    }

    // ------------------------------------------------------------------ building

    /** Makes a map a little at a time, in as many ticks as it takes. */
    class Builder(private val level: ClientLevel, private val terrain: Terrain, private val origin: Terrain.Stand) {
        private val signature: Signature = terrain.graphSignature()
        private val radius = radiusOf(signature)
        private val stands = ObjectArrayList<Terrain.Stand>()
        private val first = IntArrayList()
        private val to = IntArrayList()
        private val cost = FloatArrayList()
        private val index = Long2IntOpenHashMap()
        private val visitor = Terrain.Visitor { target, edgeCost, _ -> offer(target, edgeCost) }
        private var cursor = 0
        private var truncated = false
        private var dirty = false

        init {
            index.defaultReturnValue(-1)
            add(origin)
        }

        fun signature(): Signature = signature

        fun level(): ClientLevel = level

        fun origin(): Terrain.Stand = origin

        /** Something in the map's area changed while it was being made. */
        fun markDirty() {
            dirty = true
        }

        fun touches(x: Int, y: Int, z: Int): Boolean {
            val dx = x + 0.5 - origin.centerX()
            val dz = z + 0.5 - origin.centerZ()
            val reach = radius + 2.0
            return dx * dx + dz * dz <= reach * reach && abs(y - origin.floorY) <= PathSearch.MAX_VERTICAL + MARGIN_Y + 2.0
        }

        fun touchesChunk(chunkX: Int, chunkZ: Int): Boolean {
            val nearestX = max(chunkX * 16.0, min(origin.centerX(), chunkX * 16.0 + 16.0))
            val nearestZ = max(chunkZ * 16.0, min(origin.centerZ(), chunkZ * 16.0 + 16.0))
            val dx = nearestX - origin.centerX()
            val dz = nearestZ - origin.centerZ()
            val reach = radius + 2.0
            return dx * dx + dz * dz <= reach * reach
        }

        private fun add(stand: Terrain.Stand): Int {
            val id = stands.size
            stands.add(stand)
            index.put(stand.key(), id)
            return id
        }

        private fun inside(stand: Terrain.Stand): Boolean {
            val dx = stand.centerX() - origin.centerX()
            val dz = stand.centerZ() - origin.centerZ()
            return dx * dx + dz * dz <= radius * radius && abs(stand.floorY - origin.floorY) <= PathSearch.MAX_VERTICAL + MARGIN_Y
        }

        private fun offer(target: Terrain.Stand, edgeCost: Double) {
            if (!inside(target)) return
            var id = index.get(target.key())
            if (id < 0) {
                if (stands.size >= MAX_NODES) {
                    truncated = true
                    return
                }
                id = add(target)
            }
            to.add(id)
            cost.add(edgeCost.toFloat())
        }

        /** Works until the time is up; true once the map is whole. */
        fun step(deadlineNanos: Long): Boolean {
            var sinceClock = 0
            while (cursor < stands.size) {
                if (++sinceClock >= 32) {
                    if (System.nanoTime() >= deadlineNanos) return false
                    sinceClock = 0
                }
                first.add(to.size)
                terrain.expand(stands[cursor], visitor)
                cursor++
            }
            return true
        }

        fun result(now: Long): WalkGraph {
            first.add(to.size)
            return WalkGraph(
                level, signature, stands.toTypedArray(), first.toIntArray(), to.toIntArray(), cost.toFloatArray(), index,
                !truncated, now, dirty,
            )
        }
    }

    companion object {
        /** How much wider the map is than a search, in blocks; a search may start this far from where the map began. */
        const val MARGIN_BLOCKS = 16.0
        const val MARGIN_Y = 8.0

        /** The most places one map holds. A map that is cut short there does not say what cannot be reached. */
        private const val MAX_NODES = 250_000

        /** Longest a map is believed about what is out of reach, whatever it has heard of the world. */
        private const val TRUSTED_MILLIS = 90_000L

        /** Longest it is used at all, as the guide a search gets from its landmarks. */
        private const val USABLE_MILLIS = 600_000L

        @JvmStatic
        fun radiusOf(signature: Signature): Double = (signature.searchRadius + PathSearch.RADIUS_SLACK).toDouble() + MARGIN_BLOCKS
    }
}
