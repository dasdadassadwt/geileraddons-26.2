package geiler.addons.client.macros.killer

/**
 * Which of the mobs found are kept when there are more than the search can take: the [near] nearest and [far] more spread over the rest,
 * out to the farthest, so a field of mobs close by does not hide every one that is far away. Plain arithmetic, so checks can run it.
 */
object ScanCut {
    /** Indices to keep, ascending, of mobs sorted by distance with [count] of them. */
    @JvmStatic
    fun pick(count: Int, near: Int, far: Int): IntArray {
        if (count <= near + far) return IntArray(count) { it }
        val kept = ArrayList<Int>()
        for (i in 0 until near) kept.add(i)
        val rest = count - near
        for (n in 1..far) {
            // Evenly over what is left, the last of them the farthest mob.
            val index = near + ((n.toLong() * rest) / far).toInt() - 1
            if (index !in kept) kept.add(index)
        }
        return kept.sorted().toIntArray()
    }
}
