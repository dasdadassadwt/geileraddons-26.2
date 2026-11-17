package geiler.addons.client.mining

import com.google.gson.JsonParser
import java.util.PriorityQueue

/**
 * The Glacite Tunnels walking graph and the route logic of SkyHanni's Tunnels Maps (LGPL-2.1), with its
 * `island_graphs/GLACITE_TUNNELS` data (SkyHanni-REPO, MIT). Nodes are walkable points; the named ones are the
 * gemstone spots, ore fields, fairy souls and the campfire a route can end at.
 */
class GlaciteGraph private constructor(private val nodes: Map<Int, Node>) {
    class Node(val id: Int, val x: Double, val y: Double, val z: Double, val name: String?, val neighbours: Map<Int, Double>) {
        fun distanceTo(px: Double, py: Double, pz: Double): Double {
            val dx = x - px
            val dy = y - py
            val dz = z - pz
            return Math.sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    /** A route: the nodes to walk through in order, and its length in blocks. */
    class Path(val nodes: List<Node>, val length: Double)

    fun size(): Int = nodes.size

    fun node(id: Int): Node? = nodes[id]

    /** The target names a route can end at: gemstones, ores, the campfire and the fairy souls. */
    fun targetNames(): List<String> = byName.keys.toList()

    /** Every node carrying [target]'s name (a target may have several spots). */
    fun spots(target: String): List<Node> = byName[target].orEmpty()

    private val byName: Map<String, List<Node>> = run {
        val map = LinkedHashMap<String, MutableList<Node>>()
        for (node in nodes.values) {
            val label = node.name ?: continue
            if (!isTarget(label)) continue
            map.getOrPut(label) { ArrayList() }.add(node)
        }
        map
    }

    fun nearest(px: Double, py: Double, pz: Double): Node? = nodes.values.minByOrNull { it.distanceTo(px, py, pz) }

    /** Dijkstra over the neighbour weights; null when [to] cannot be reached from [from]. */
    fun shortest(from: Node, to: Node): Path? {
        val dist = HashMap<Int, Double>()
        val previous = HashMap<Int, Int>()
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[from.id] = 0.0
        queue.add(0.0 to from.id)
        while (queue.isNotEmpty()) {
            val (d, id) = queue.poll()
            if (d > (dist[id] ?: Double.MAX_VALUE)) continue
            if (id == to.id) break
            val node = nodes[id] ?: continue
            for ((next, weight) in node.neighbours) {
                if (nodes[next] == null) continue
                val candidate = d + weight
                if (candidate < (dist[next] ?: Double.MAX_VALUE)) {
                    dist[next] = candidate
                    previous[next] = id
                    queue.add(candidate to next)
                }
            }
        }
        val total = dist[to.id] ?: return null
        val route = ArrayList<Node>()
        var at: Int? = to.id
        while (at != null) {
            route.add(nodes[at] ?: return null)
            at = previous[at]
        }
        route.reverse()
        return Path(route, total)
    }

    companion object {
        private val GEMSTONES = setOf("Onyx", "Peridot", "Citrine", "Topaz", "Ruby", "Aquamarine", "Sapphire", "Amethyst", "Jade", "Amber")
        private val ORES = setOf("Tungsten", "Umber", "Mithril")

        private fun isTarget(label: String): Boolean =
            label == "Campfire" || label.startsWith("Fairy Soul") || label in ORES ||
                (label.endsWith(" Gemstone") && label.removeSuffix(" Gemstone") in GEMSTONES)

        /** Drops legacy color codes from a graph label. */
        private fun plain(label: String): String = label.replace(Regex("§."), "").trim()

        @JvmStatic
        fun parse(json: String): GlaciteGraph {
            val root = JsonParser.parseString(json).asJsonObject
            val nodes = LinkedHashMap<Int, Node>()
            for ((key, element) in root.entrySet()) {
                val id = key.toIntOrNull() ?: continue
                val obj = element.asJsonObject
                val parts = obj.get("p").asString.split(":")
                if (parts.size != 3) continue
                val neighbours = LinkedHashMap<Int, Double>()
                obj.getAsJsonObject("e")?.entrySet()?.forEach { (to, weight) ->
                    to.toIntOrNull()?.let { neighbours[it] = weight.asDouble }
                }
                val name = obj.get("n")?.asString?.let { plain(it) }?.takeIf { it.isNotEmpty() }
                nodes[id] = Node(id, parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble(), name, neighbours)
            }
            return GlaciteGraph(nodes)
        }

        /** The target a collector commission points at (`Ruby Gemstone Collector` is Ruby Gemstone), or null. */
        @JvmStatic
        fun targetForCommission(commissionName: String, targets: Collection<String>): String? {
            val match = Regex("^(\\w+(?: \\w+)?) Collector$").matchEntire(commissionName.trim()) ?: return null
            val what = match.groupValues[1]
            if (what.equals("Glacite", true) || what.equals("Scrap", true)) return null
            return targets.firstOrNull { it.equals(what, true) }
                ?: targets.firstOrNull { it.contains(what, true) && !it.startsWith("Fairy") }
        }
    }
}

/**
 * The route state: the chosen target, the goal spot, per-spot cooldowns, and the path to draw. Follows SkyHanni:
 * a spot just reached rests for a minute, the next spot is the nearest one off cooldown, and a path that can cut
 * across from the player to its second node skips the first.
 */
class GlaciteRoute(private val graph: GlaciteGraph) {
    private val cooldownUntil = HashMap<Int, Long>()
    var target: String? = null
        private set
    var goal: GlaciteGraph.Node? = null
        private set
    var path: GlaciteGraph.Path? = null
        private set
    private var lastClosest = -1
    private var lastGoal = -1

    fun setTarget(name: String?, closest: GlaciteGraph.Node?, nowNanos: Long) {
        if (name == target && goal != null) return
        target = name
        goal = null
        path = null
        if (name != null && closest != null) goal = pick(name, closest, nowNanos)
    }

    /** Moves to the next spot of the current target; true when a goal was chosen. */
    fun next(closest: GlaciteGraph.Node?, nowNanos: Long): Boolean {
        val name = target ?: return false
        val from = closest ?: return false
        goal = pick(name, from, nowNanos)
        lastGoal = -1
        return goal != null
    }

    fun clear() {
        target = null
        goal = null
        path = null
        lastClosest = -1
        lastGoal = -1
    }

    fun clearCooldowns() = cooldownUntil.clear()

    /** The spot to head for: nearest off cooldown, else the one that rests the least; it then rests five seconds. */
    private fun pick(name: String, closest: GlaciteGraph.Node, now: Long): GlaciteGraph.Node? {
        val spots = graph.spots(name)
        if (spots.isEmpty()) return null
        val ready = spots.filter { (cooldownUntil[it.id] ?: 0L) <= now }
        val best = ready.minByOrNull { graph.shortest(closest, it)?.length ?: Double.MAX_VALUE }
            ?: spots.minByOrNull { cooldownUntil[it.id] ?: 0L }
            ?: return null
        if ((cooldownUntil[best.id] ?: 0L) <= now) cooldownUntil[best.id] = now + SHORT_REST_NANOS
        return best
    }

    /** Recomputes the path when the closest node or the goal changed; returns true once the goal is reached. */
    fun update(px: Double, py: Double, pz: Double, nowNanos: Long): Boolean {
        val goalNode = goal ?: return false
        val reach = if (goalNode.name == "Campfire") CAMPFIRE_REACH else SPOT_REACH
        if (goalNode.distanceTo(px, py, pz) < reach) {
            cooldownUntil[goalNode.id] = nowNanos + REACHED_REST_NANOS
            goal = null
            path = null
            lastClosest = -1
            lastGoal = -1
            return true
        }
        val closest = graph.nearest(px, py, pz) ?: return false
        if (closest.id == lastClosest && goalNode.id == lastGoal) return false
        lastClosest = closest.id
        lastGoal = goalNode.id
        val full = graph.shortest(closest, goalNode) ?: run {
            path = null
            return false
        }
        val first = full.nodes.firstOrNull()
        val second = full.nodes.getOrNull(1)
        if (first != null && second != null) {
            val toFirst = first.distanceTo(px, py, pz)
            val direct = second.distanceTo(px, py, pz)
            val firstEdge = first.neighbours[second.id] ?: 0.0
            if (direct < toFirst + firstEdge) {
                path = GlaciteGraph.Path(full.nodes.drop(1), full.length - firstEdge + direct)
                return false
            }
            path = GlaciteGraph.Path(full.nodes, full.length + toFirst)
            return false
        }
        path = full
        return false
    }

    companion object {
        private const val SHORT_REST_NANOS = 5_000_000_000L
        private const val REACHED_REST_NANOS = 60_000_000_000L
        private const val SPOT_REACH = 6.0
        private const val CAMPFIRE_REACH = 15.0
    }
}
