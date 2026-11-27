package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry.Room
import java.util.ArrayDeque

/**
 * The no-duplicates job split on the map, the same for every player because it uses only the map:
 * Mage, Archer and Healer rush blood together; Mage also takes the first room outside blood. Archer and Healer then
 * clear the rooms closer to blood, Berserk and Tank the rooms closer to the entrance. Each pair grows two connected
 * regions from its start, balanced by secrets (each room counts one extra, so empty rooms are not free).
 */
object DungeonAutoSplit {
    private class Node(val key: String, val room: Room) {
        val weight: Int get() = room.maxSecrets() + 1
    }

    /**
     * Room key to the classes that do it; empty until the entrance is on the map. Before blood is known (legal map, a
     * map that is not fully loaded) the known room farthest from the entrance stands in for it, so the split moves
     * toward blood as the map fills in.
     */
    @JvmStatic
    fun split(rooms: List<Room>): Map<String, Set<DungeonClass>> {
        val nodes = rooms.map { Node(key(it), it) }.distinctBy { it.key }.sortedBy { it.key }
        val entrance = nodes.firstOrNull { it.room.type() == DungeonMapGeometry.RoomType.ENTRANCE } ?: return emptyMap()
        val adj = adjacency(nodes)
        val blood = nodes.firstOrNull { it.room.type() == DungeonMapGeometry.RoomType.BLOOD } ?: run {
            val reach = distances(entrance.key, adj)
            nodes.filter { it.key != entrance.key && it.key in reach }
                .maxWithOrNull(compareBy<Node> { reach.getValue(it.key) }.thenByDescending { it.key })
        } ?: return emptyMap()
        val fromBlood = distances(blood.key, adj)
        val fromEntrance = distances(entrance.key, adj)
        val out = LinkedHashMap<String, Set<DungeonClass>>()
        out[blood.key] = setOf(DungeonClass.MAGE, DungeonClass.ARCHER, DungeonClass.HEALER)
        val mageRoom = adj[blood.key].orEmpty().filter { it != entrance.key }
            .minWithOrNull(compareBy<String> { fromEntrance[it] ?: Int.MAX_VALUE }.thenBy { it })
        if (mageRoom != null) out[mageRoom] = setOf(DungeonClass.MAGE)

        val rest = nodes.map { it.key }.filter { it != entrance.key && it != blood.key && it != mageRoom }
        val bloodSide = rest.filter { (fromBlood[it] ?: Int.MAX_VALUE) < (fromEntrance[it] ?: Int.MAX_VALUE) }.toSet()
        val entranceSide = rest.filter { it !in bloodSide }.toSet()
        val weights = nodes.associate { it.key to it.weight }
        grow(bloodSide, listOfNotNull(blood.key, mageRoom), DungeonClass.ARCHER, DungeonClass.HEALER, adj, weights, fromBlood, out)
        grow(entranceSide, listOf(entrance.key), DungeonClass.BERSERK, DungeonClass.TANK, adj, weights, fromEntrance, out)
        return out
    }

    @JvmStatic
    fun key(room: Room): String = room.id()?.takeIf { it.isNotBlank() }
        ?: room.footprint().minWithOrNull(compareBy({ it.z() }, { it.x() }))?.let { "cell:${it.x()},${it.z()}" } ?: "room:${room.name()}"

    private fun grow(
        side: Set<String>, anchors: List<String>, a: DungeonClass, b: DungeonClass,
        adj: Map<String, Set<String>>, weights: Map<String, Int>, dist: Map<String, Int>, out: MutableMap<String, Set<DungeonClass>>,
    ) {
        if (side.isEmpty()) return
        val owned = mapOf(a to LinkedHashSet<String>(), b to LinkedHashSet())
        val total = mutableMapOf(a to 0, b to 0)
        val unassigned = side.toSortedSet()
        val order = compareBy<String> { dist[it] ?: Int.MAX_VALUE }.thenBy { it }
        fun frontier(c: DungeonClass): List<String> {
            // A class with no room yet may start next to the anchors or next to its partner's first rooms.
            val sources = owned.getValue(c).ifEmpty { anchors + owned.values.flatten() }
            return sources.flatMap { adj[it].orEmpty() }.filter { it in unassigned }.distinct()
        }
        while (unassigned.isNotEmpty()) {
            val first = if (total.getValue(b) < total.getValue(a)) b else a
            val second = if (first == a) b else a
            val pick = frontier(first).minWithOrNull(order)?.let { first to it }
                ?: frontier(second).minWithOrNull(order)?.let { second to it }
                // Not reachable inside this side: give it to whoever has less.
                ?: (first to unassigned.minWith(order))
            owned.getValue(pick.first).add(pick.second)
            total[pick.first] = total.getValue(pick.first) + (weights[pick.second] ?: 1)
            unassigned.remove(pick.second)
            out[pick.second] = setOf(pick.first)
        }
    }

    /** Door exits give the edges; a room whose exits are unknown links to every room touching its footprint. */
    private fun adjacency(nodes: List<Node>): Map<String, Set<String>> {
        val byCell = HashMap<Pair<Int, Int>, String>()
        for (n in nodes) for (c in n.room.footprint()) byCell[c.x() to c.z()] = n.key
        val adj = HashMap<String, MutableSet<String>>()
        fun link(x: String, y: String) {
            if (x == y) return
            adj.getOrPut(x) { sortedSetOf() }.add(y)
            adj.getOrPut(y) { sortedSetOf() }.add(x)
        }
        for (n in nodes) {
            if (n.room.exitsKnown() && n.room.exits().isNotEmpty()) {
                for (exit in n.room.exits()) {
                    val cell = exit.cell() ?: continue
                    val d = exit.direction() ?: continue
                    byCell[cell.x() + d.dx() to cell.z() + d.dz()]?.let { link(n.key, it) }
                }
            } else {
                for (c in n.room.footprint()) for (d in DungeonRoomOverlayGeometry.Direction.entries) {
                    byCell[c.x() + d.dx() to c.z() + d.dz()]?.let { link(n.key, it) }
                }
            }
        }
        return adj
    }

    private fun distances(start: String, adj: Map<String, Set<String>>): Map<String, Int> {
        val dist = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        dist[start] = 0
        queue.add(start)
        while (queue.isNotEmpty()) {
            val at = queue.poll()
            for (next in adj[at].orEmpty()) if (next !in dist) {
                dist[next] = dist.getValue(at) + 1
                queue.add(next)
            }
        }
        return dist
    }
}
