package geiler.addons.client.fishing

/**
 * Rules of the Sea Creature Cap display: which name tags are mobs and a bounded set of the entities that count as yours.
 * Any `[Lv..]` mob that first appears by your bobber right after you reel in is counted, so common creatures need no catalog.
 */
object SeaCreatureCapRules {
    const val MAX_TRACKED = 64

    private val LABEL = Regex("^\\[Lv\\d+]")

    /** A SkyBlock mob name tag such as `[Lv600] Lord Jawbus 100M❤`. */
    @JvmStatic
    fun isLabel(plain: String): Boolean = LABEL.containsMatchIn(plain)

    @JvmStatic
    fun text(alive: Int, cap: Int): String = if (cap > 0) "Sea Creatures: $alive/$cap" else "Sea Creatures: $alive"

    @JvmStatic
    fun atCap(alive: Int, cap: Int): Boolean = cap > 0 && alive >= cap

    /** Entity ids of the creatures counted as yours, at most [MAX_TRACKED]. */
    class Tracker {
        private val ids = HashSet<Int>()

        /** True when [id] was new and there was room for it. */
        fun add(id: Int): Boolean = ids.size < MAX_TRACKED && ids.add(id)

        /** Drops every id not in [alive]; returns how many were dropped. */
        fun retain(alive: Set<Int>): Int {
            val before = ids.size
            ids.retainAll(alive)
            return before - ids.size
        }

        fun count(): Int = ids.size

        fun snapshot(): List<Int> = ids.toList()

        fun clear() = ids.clear()
    }
}
