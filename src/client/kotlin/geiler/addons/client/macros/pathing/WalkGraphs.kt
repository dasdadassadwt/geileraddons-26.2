package geiler.addons.client.macros.pathing

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Util
import kotlin.math.abs

/**
 * Keeps the [WalkGraph] of the ground around the player up to date, a little in every tick, and hands out what it knows.
 *
 * Making the map is as much work as one search that finds nothing, so it is spread over ticks like a search is, with a
 * budget of its own. A new one is made when there is none, when the player has walked out of the middle of the old one,
 * when the rules of the planner have changed, and - not more often than every [MIN_REBUILD_MILLIS] - when the world
 * around it has. The old map stays in use until the new one is whole, landmarks and all.
 */
object WalkGraphs {
    /** What a tick may spend on the map, in milliseconds. */
    private const val BUILD_MILLIS = 6.0

    /** A map is not made again sooner than this, however much the world changes. */
    private const val MIN_REBUILD_MILLIS = 8_000L

    /** The player has to be this close to the middle of the map for it to be of use; further out a new one is made. */
    private const val DRIFT_BLOCKS = WalkGraph.MARGIN_BLOCKS - 4.0
    private const val DRIFT_Y = WalkGraph.MARGIN_Y - 2.0

    private var graph: WalkGraph? = null
    private var building: WalkGraph.Builder? = null
    private var pending: WalkGraph? = null
    private var marking: Landmarks.Builder? = null
    private var lastStartMillis = 0L

    init {
        WorldWatch.add(
            object : WorldWatch.Listener {
                override fun blockChanged(level: ClientLevel, x: Int, y: Int, z: Int) {
                    graph?.let { if (it.level() === level && it.touches(x, y, z)) it.markDirty() }
                    pending?.let { if (it.level() === level && it.touches(x, y, z)) it.markDirty() }
                    building?.let { if (it.level() === level && it.touches(x, y, z)) it.markDirty() }
                }

                override fun chunkChanged(level: ClientLevel, chunkX: Int, chunkZ: Int) {
                    graph?.let { if (it.level() === level && it.touchesChunk(chunkX, chunkZ)) it.markDirty() }
                    pending?.let { if (it.level() === level && it.touchesChunk(chunkX, chunkZ)) it.markDirty() }
                    building?.let { if (it.level() === level && it.touchesChunk(chunkX, chunkZ)) it.markDirty() }
                }
            },
        )
    }

    /** Whether a map is being made now, for the records. */
    @JvmStatic
    fun building(): Boolean = building != null || marking != null

    /** Throws everything away. */
    @JvmStatic
    fun clear() {
        graph = null
        building = null
        pending = null
        marking = null
    }

    /** Call every tick while something is planning routes. Does a slice of the work of keeping the map current. */
    @JvmStatic
    fun maintain(level: ClientLevel, player: LocalPlayer, settings: PathingSettings, region: Region) {
        if (!settings.useRouteMap()) {
            clear()
            return
        }
        val deadline = System.nanoTime() + (BUILD_MILLIS * 1.0E6).toLong()
        val now = Util.getMillis()
        val terrain = Terrain(level, player, settings, region)
        val signature = terrain.graphSignature()
        val making = building
        if (making != null) {
            if (making.level() !== level || making.signature() != signature) {
                building = null
                return
            }
            if (making.step(deadline)) {
                val result = making.result(Util.getMillis())
                pending = result
                building = null
                marking = Landmarks.Builder(result)
            }
            return
        }
        val marks = marking
        if (marks != null) {
            val waiting = pending!!
            if (waiting.level() !== level || waiting.signature() != signature) {
                pending = null
                marking = null
                return
            }
            if (marks.step(deadline)) {
                waiting.attach(marks.result())
                graph = waiting
                pending = null
                marking = null
            }
            return
        }
        if (graph != null && graph!!.level() !== level) graph = null
        val current = graph
        val fits = current != null && current.signature() == signature
        if (fits && !current!!.dirty() && now - current.builtMillis() <= 60_000L && nearMiddle(current, player)) return
        if (fits && now - lastStartMillis < MIN_REBUILD_MILLIS) return
        val start = terrain.standUnder(player) ?: return
        lastStartMillis = now
        building = WalkGraph.Builder(level, terrain, start)
    }

    private fun nearMiddle(map: WalkGraph, player: LocalPlayer): Boolean {
        val middle = map.origin()
        val dx = player.x - middle.centerX()
        val dz = player.z - middle.centerZ()
        return dx * dx + dz * dz <= DRIFT_BLOCKS * DRIFT_BLOCKS && abs(player.y - middle.floorY) <= DRIFT_Y
    }

    /**
     * What can be got to from this stand, or null when the map cannot be sure - there is none that fits the terrain
     * and this start, or it is out of date with the world.
     */
    @JvmStatic
    fun reach(terrain: Terrain, start: Terrain.Stand): WalkGraph.Reach? {
        val map = graph
        if (map == null || map.signature() != terrain.graphSignature()) return null
        return map.reachFrom(start, Util.getMillis())
    }

    private val dijkstra = DijkstraCosts()

    /** The walk further than this many blocks is not costed: nothing is recorded beyond it. */
    private const val COST_LIMIT_BLOCKS = 400.0

    /**
     * For each goal, the cost in blocks of the cheapest walk from [start] to a place that satisfies it: NaN when the map cannot say (there
     * is none that is believed, or the goal is not wholly inside it), infinity when there is no way. Null when there is no map at all.
     * The cost is in the planner's own units over the cheapest block of travel, so a jump or a climb counts as more than its length.
     */
    @JvmStatic
    fun walkCosts(terrain: Terrain, start: Terrain.Stand, goals: List<Goal>): DoubleArray? {
        val map = graph
        if (map == null || map.signature() != terrain.graphSignature()) return null
        val now = Util.getMillis()
        if (!map.trusted(now) || !map.admits(start)) return null
        val source = map.idOf(start)
        if (source < 0) return null

        val stands = ArrayList<it.unimi.dsi.fastutil.ints.IntArrayList?>(goals.size)
        val targets = it.unimi.dsi.fastutil.ints.IntArrayList()
        for (goal in goals) {
            val found = map.goalStands(terrain, goal)
            stands.add(found)
            if (found != null) targets.addAll(found)
        }
        val perBlock = terrain.minimumCostPerBlock()
        val distances = dijkstra.run(
            map.firstEdges(), map.edgeTargets(), map.edgeCosts(), source, targets.toIntArray(), (COST_LIMIT_BLOCKS * perBlock).toFloat(),
        )
        return DoubleArray(goals.size) { i ->
            val found = stands[i]
            if (found == null) {
                Double.NaN
            } else {
                var best = Float.POSITIVE_INFINITY
                for (k in 0 until found.size) best = minOf(best, distances[found.getInt(k)])
                if (best.isInfinite()) Double.POSITIVE_INFINITY else best / perBlock
            }
        }
    }

    /** The goal, told what the landmarks of the map know about the way to it, if there is a map that covers it. */
    @JvmStatic
    fun informed(goal: Goal, terrain: Terrain, start: Terrain.Stand): Goal {
        val map = graph
        if (map == null || map.signature() != terrain.graphSignature()) return goal
        return AltGoal.of(goal, terrain, start, map, Util.getMillis())
    }
}
