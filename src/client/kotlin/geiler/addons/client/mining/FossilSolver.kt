package geiler.addons.client.mining

/**
 * The Fossil Excavator solver, ported from SkyHanni's FossilSolver, FossilType, FossilShape and FossilMutation
 * (LGPL-2.1). The board is 9 x 6; each fossil has a known shape that may be rotated or flipped. Every placement that
 * fits the tiles already seen is counted, and the tile that lies in the most placements is the best chisel.
 */
object FossilSolver {
    class Tile(val x: Int, val y: Int) {
        constructor(slot: Int) : this(slot % 9, slot / 9)

        fun slot(): Int = x + y * 9
        override fun equals(other: Any?): Boolean = other is Tile && other.x == x && other.y == y
        override fun hashCode(): Int = x * 31 + y
    }

    class Shape(val tiles: List<Tile>) {
        fun width(): Int = tiles.maxOf { it.x } - tiles.minOf { it.x }
        fun height(): Int = tiles.maxOf { it.y } - tiles.minOf { it.y }
        fun moveTo(dx: Int, dy: Int): Shape = Shape(tiles.map { Tile(it.x + dx, it.y + dy) })

        fun rotate(degree: Int): Shape {
            val w = width()
            val h = height()
            return when (degree) {
                90 -> Shape(tiles.map { Tile(it.y, w - it.x) })
                180 -> Shape(tiles.map { Tile(w - it.x, h - it.y) })
                270 -> Shape(tiles.map { Tile(h - it.y, it.x) })
                else -> this
            }
        }

        fun flip(): Shape {
            val h = height()
            return Shape(tiles.map { Tile(it.x, h - it.y) })
        }
    }

    enum class Mutation(val apply: (Shape) -> Shape) {
        ROTATE_0({ it.rotate(0) }),
        ROTATE_90({ it.rotate(90) }),
        ROTATE_180({ it.rotate(180) }),
        ROTATE_270({ it.rotate(270) }),
        FLIP_ROTATE_0({ it.rotate(0).flip() }),
        FLIP_ROTATE_90({ it.rotate(90).flip() }),
        FLIP_ROTATE_180({ it.rotate(180).flip() }),
        FLIP_ROTATE_270({ it.rotate(270).flip() });

        companion object {
            val ONLY_ROTATION = listOf(ROTATE_0, ROTATE_90, ROTATE_180, ROTATE_270)
        }
    }

    private fun shape(vararg points: Int): Shape = Shape(points.toList().chunked(2).map { Tile(it[0], it[1]) })

    enum class Fossil(val label: String, val firstPercentage: String, val shape: Shape, val mutations: List<Mutation>) {
        TUSK("Tusk", "12.5%", shape(0, 2, 0, 3, 0, 4, 1, 1, 2, 0, 3, 1, 3, 3, 4, 2), Mutation.entries),
        WEBBED("Webbed", "10%", shape(0, 2, 1, 1, 2, 0, 3, 0, 3, 1, 3, 2, 3, 3, 4, 0, 5, 1, 6, 2), listOf(Mutation.ROTATE_0, Mutation.FLIP_ROTATE_0)),
        CLUB(
            "Club", "9.1%", shape(0, 2, 0, 3, 1, 2, 1, 3, 2, 1, 3, 0, 4, 0, 5, 0, 6, 0, 6, 2, 7, 1),
            listOf(Mutation.ROTATE_0, Mutation.ROTATE_180, Mutation.FLIP_ROTATE_0, Mutation.FLIP_ROTATE_180),
        ),
        SPINE("Spine", "8.3%", shape(0, 2, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2, 3, 0, 3, 1, 3, 2, 4, 1, 4, 2, 5, 2), Mutation.ONLY_ROTATION),
        CLAW("Claw", "7.7%", shape(0, 3, 1, 2, 1, 4, 2, 1, 2, 3, 3, 1, 3, 2, 3, 4, 4, 0, 4, 1, 4, 2, 4, 3, 5, 1), Mutation.entries),
        FOOTPRINT("Footprint", "7.7%", shape(0, 2, 1, 1, 1, 2, 1, 3, 2, 1, 2, 2, 2, 3, 3, 0, 3, 2, 3, 4, 4, 0, 4, 2, 4, 4), Mutation.ONLY_ROTATION),
        HELIX("Helix", "7.1%", shape(0, 0, 0, 1, 0, 2, 0, 4, 1, 0, 1, 2, 1, 4, 2, 0, 2, 4, 3, 0, 3, 1, 3, 2, 3, 3, 3, 4), Mutation.entries),
        UGLY("Ugly", "6.2%", shape(0, 1, 1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2, 2, 3, 3, 0, 3, 1, 3, 2, 3, 3, 4, 0, 4, 1, 4, 2, 5, 1), Mutation.ONLY_ROTATION);

        fun tileCount(): Int = shape.tiles.size
    }

    private class Step(val x: Int, val y: Int, val chance: Double, val count: Int)

    /** Used under 18 chisel charges: solves most boards in 16 to 18 clicks. */
    private val RISKY_START = listOf(
        Step(4, 2, 0.515, 404), Step(5, 3, 0.393, 196), Step(3, 2, 0.513, 119), Step(7, 2, 0.345, 58),
        Step(1, 3, 0.342, 38), Step(3, 4, 0.6, 25), Step(5, 1, 0.8, 10), Step(4, 3, 1.0, 2),
    )

    /** Used once the player has 18 charges: solves every board in 18 clicks. */
    private val SAFE_START = listOf(
        Step(4, 2, 0.515, 404), Step(5, 4, 0.413, 196), Step(3, 3, 0.461, 115), Step(5, 2, 0.387, 62),
        Step(3, 1, 0.342, 38), Step(7, 3, 0.48, 25), Step(1, 2, 0.846, 13), Step(3, 4, 1.0, 2),
    )

    enum class State { NEXT, COMPLETED, IMPOSSIBLE }

    /** The solver's answer: the slot to chisel next, its chance of hitting fossil, and how many placements remain. */
    class Result(val state: State, val slot: Int, val chance: Double, val placements: Int, val possible: Set<Fossil>)

    /**
     * @param fossil slots already showing a fossil piece
     * @param dirt slots still covered
     * @param percentage the progress text of the first fossil piece (it names the fossil), or null before one is seen
     * @param maxCharges the chisel charges the excavation started with
     */
    @JvmStatic
    fun solve(fossil: Set<Int>, dirt: Set<Int>, percentage: String?, maxCharges: Int): Result {
        val invalid = HashSet<Tile>()
        for (slot in 0..53) if (slot !in fossil && slot !in dirt) invalid.add(Tile(slot))
        val found = fossil.map { Tile(it) }.toSet()
        val sequence = if (maxCharges < 18) RISKY_START else SAFE_START

        if (found.isEmpty() && invalid.all { tile -> sequence.any { it.x == tile.x && it.y == tile.y } }) {
            val taken = invalid.size
            if (taken >= sequence.size) return Result(State.IMPOSSIBLE, -1, 0.0, 0, emptySet())
            val next = sequence[taken]
            return Result(State.NEXT, Tile(next.x, next.y).slot(), next.chance, next.count, emptySet())
        }

        val candidates = if (percentage == null) Fossil.entries else Fossil.entries.filter { it.firstPercentage == percentage }
        val counts = HashMap<Tile, Int>()
        var total = 0
        for (x in 0..8) {
            for (y in 0..5) {
                for (kind in candidates) {
                    for (mutation in kind.mutations) {
                        val placed = mutation.apply(kind.shape).moveTo(x, y)
                        if (!fits(placed, invalid, found)) continue
                        total++
                        for (tile in placed.tiles) counts[tile] = (counts[tile] ?: 0) + 1
                    }
                }
            }
        }
        found.forEach { counts.remove(it) }
        val best = counts.maxByOrNull { it.value }
            ?: return Result(if (fossil.isNotEmpty()) State.COMPLETED else State.IMPOSSIBLE, -1, 0.0, 0, candidates.toSet())
        return Result(State.NEXT, best.key.slot(), best.value.toDouble() / total, total, if (percentage == null) emptySet() else candidates.toSet())
    }

    private fun fits(shape: Shape, invalid: Set<Tile>, found: Set<Tile>): Boolean {
        for (tile in shape.tiles) {
            if (tile in invalid || tile.x < 0 || tile.y < 0 || tile.x >= 9 || tile.y >= 6) return false
        }
        return found.all { it in shape.tiles }
    }
}
