package geiler.addons.client.macros.area

import kotlin.math.abs

/**
 * Which stands a brush stamp paints. The brush is a flat disc on one level of ground: the stand under the aim is the reference, and a
 * stand is painted only when it is on the same level (its feet cell within [TOLERANCE] of the reference) and is reached from the
 * reference over ground that rises or falls no more than [MAX_STEP] from one column to the next. A platform a block or two higher,
 * a ledge over the head or a floor under a bridge is never reached, and with no ground at the aim nothing is painted.
 * Plain data, free of game state so checks can run it.
 */
object BrushLevel {
    /** A place the macro could stand: the feet cell and the height of the floor under the feet. */
    class Stand(val x: Int, val y: Int, val z: Int, val floorY: Double)

    /** How many cells of height from the reference a stand may be and still be the same level: a slab or a stair. */
    const val TOLERANCE = 1

    /** The most the floor may change between two neighbouring columns of the same level. */
    const val MAX_STEP = 1.0

    /** Cells the aim may be above or below the ground it points at and still pick it. */
    const val AIM_REACH = 2

    /**
     * The reference stand: among [candidates] (every stand of the disc), the one whose feet cell is nearest to the aim's height within
     * [AIM_REACH], and of those the one nearest to the aim across; null when none is near enough.
     */
    @JvmStatic
    fun reference(candidates: Collection<Stand>, aimX: Int, aimY: Int, aimZ: Int): Stand? {
        var best: Stand? = null
        var bestScore = Long.MAX_VALUE
        for (stand in candidates) {
            val up = abs(stand.y - aimY)
            if (up > AIM_REACH) continue
            val dx = (stand.x - aimX).toLong()
            val dz = (stand.z - aimZ).toLong()
            val score = up * 1_000_000L + dx * dx + dz * dz
            if (score < bestScore) {
                bestScore = score
                best = stand
            }
        }
        return best
    }

    /** The stands to paint: the reference and every stand of its level that is joined to it over [candidates]. */
    @JvmStatic
    fun select(candidates: Collection<Stand>, reference: Stand?): List<Stand> {
        if (reference == null) return emptyList()
        val byColumn = HashMap<Long, MutableList<Stand>>()
        for (stand in candidates) {
            if (abs(stand.y - reference.y) > TOLERANCE) continue
            byColumn.getOrPut(column(stand.x, stand.z)) { ArrayList() }.add(stand)
        }
        val chosen = ArrayList<Stand>()
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Stand>()
        seen.add(column(reference.x, reference.z))
        queue.addLast(reference)
        while (queue.isNotEmpty()) {
            val from = queue.removeFirst()
            chosen.add(from)
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val key = column(from.x + dx, from.z + dz)
                if (key in seen) continue
                val next = byColumn[key]?.filter { abs(it.floorY - from.floorY) <= MAX_STEP + 1.0E-6 }
                    ?.minByOrNull { abs(it.floorY - from.floorY) } ?: continue
                seen.add(key)
                queue.addLast(next)
            }
        }
        return chosen
    }

    private fun column(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xFFFFFFFFL)
}
