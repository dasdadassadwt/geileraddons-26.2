package geiler.addons.client.macros.pathing

/**
 * The cheapest cost from one place to every place, over a map held as arrays: node `n` has the edges `first[n] until first[n + 1]`, each
 * to `to[edge]` at `cost[edge]`. It stops early once every node in `targets` has been settled, and records nothing dearer than `limit`.
 * The result array is reused by the next run, so a caller keeps what it needs from it before running again. Plain arrays and
 * arithmetic, free of game state so checks can run it.
 */
class DijkstraCosts {
    private var dist = FloatArray(0)
    private var settled = BooleanArray(0)
    private var heapNode = IntArray(0)
    private var heapKey = FloatArray(0)
    private var size = 0

    fun run(first: IntArray, to: IntArray, cost: FloatArray, source: Int, targets: IntArray, limit: Float): FloatArray {
        val nodes = first.size - 1
        if (dist.size < nodes) {
            dist = FloatArray(nodes)
            settled = BooleanArray(nodes)
        }
        java.util.Arrays.fill(dist, 0, nodes, Float.POSITIVE_INFINITY)
        java.util.Arrays.fill(settled, 0, nodes, false)
        if (heapNode.size < 64) {
            heapNode = IntArray(64)
            heapKey = FloatArray(64)
        }
        size = 0
        var waiting = 0
        val wanted = BooleanArray(if (targets.isEmpty()) 0 else nodes)
        for (t in targets) {
            if (t in 0 until nodes && !wanted[t]) {
                wanted[t] = true
                waiting++
            }
        }
        if (source !in 0 until nodes) return dist
        dist[source] = 0f
        push(source, 0f)
        while (size > 0) {
            val node = heapNode[0]
            val key = heapKey[0]
            pop()
            if (settled[node]) continue
            settled[node] = true
            if (wanted.isNotEmpty() && wanted[node]) {
                waiting--
                if (waiting == 0) break
            }
            for (edge in first[node] until first[node + 1]) {
                val next = to[edge]
                val through = key + cost[edge]
                if (through > limit || through >= dist[next]) continue
                dist[next] = through
                push(next, through)
            }
        }
        return dist
    }

    private fun push(node: Int, key: Float) {
        if (size == heapNode.size) {
            heapNode = heapNode.copyOf(size * 2)
            heapKey = heapKey.copyOf(size * 2)
        }
        var at = size++
        while (at > 0) {
            val parent = (at - 1) / 2
            if (heapKey[parent] <= key) break
            heapNode[at] = heapNode[parent]
            heapKey[at] = heapKey[parent]
            at = parent
        }
        heapNode[at] = node
        heapKey[at] = key
    }

    private fun pop() {
        size--
        if (size == 0) return
        val node = heapNode[size]
        val key = heapKey[size]
        var at = 0
        while (true) {
            var child = at * 2 + 1
            if (child >= size) break
            if (child + 1 < size && heapKey[child + 1] < heapKey[child]) child++
            if (heapKey[child] >= key) break
            heapNode[at] = heapNode[child]
            heapKey[at] = heapKey[child]
            at = child
        }
        heapNode[at] = node
        heapKey[at] = key
    }
}
