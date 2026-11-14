package geiler.addons.client.macros.area

import it.unimi.dsi.fastutil.longs.LongOpenHashSet

/**
 * Painted cells joined into rectangles for drawing: cells of the same paint and floor height that touch are one rectangle, so the
 * paint reads as a plane on the ground and not a grid of single marks. Plain data, free of game state so checks can run it.
 */
object PaintMesh {
    class Cell(@JvmField val x: Int, @JvmField val y: Int, @JvmField val z: Int, @JvmField val type: Paint, @JvmField val eighths: Int)

    /** The cells from [x0], [z0] up to but not including [x1], [z1], all of one [type] and floor ([y] and [eighths] above it). */
    class Rect(
        @JvmField val type: Paint, @JvmField val y: Int, @JvmField val eighths: Int,
        @JvmField val x0: Int, @JvmField val z0: Int, @JvmField val x1: Int, @JvmField val z1: Int,
    ) {
        fun cells(): Int = (x1 - x0) * (z1 - z0)
    }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    /** Greedy: from the first free cell (lowest z, then x) a run along x, then as many rows of the same run along z as are all painted. */
    @JvmStatic
    fun merge(cells: List<Cell>): List<Rect> {
        // A wall shuts its whole column at whatever floor it was painted from, so its floor height does not set it apart.
        val groups = LinkedHashMap<String, MutableList<Cell>>()
        for (cell in cells) {
            val eighths = if (cell.type == Paint.WALL) 0 else cell.eighths
            groups.getOrPut("${cell.type.ordinal}/${cell.y}/$eighths") { ArrayList() }.add(cell)
        }
        val rects = ArrayList<Rect>()
        for (group in groups.values) {
            val first = group[0]
            val eighths = if (first.type == Paint.WALL) 0 else first.eighths
            val free = LongOpenHashSet(group.size * 2)
            for (cell in group) free.add(key(cell.x, cell.z))
            val ordered = group.sortedWith(compareBy({ it.z }, { it.x }))
            for (cell in ordered) {
                if (!free.contains(key(cell.x, cell.z))) continue
                var x1 = cell.x + 1
                while (free.contains(key(x1, cell.z))) x1++
                var z1 = cell.z + 1
                rows@ while (true) {
                    for (x in cell.x until x1) if (!free.contains(key(x, z1))) break@rows
                    z1++
                }
                for (z in cell.z until z1) for (x in cell.x until x1) free.remove(key(x, z))
                rects.add(Rect(first.type, first.y, eighths, cell.x, cell.z, x1, z1))
            }
        }
        return rects
    }
}
