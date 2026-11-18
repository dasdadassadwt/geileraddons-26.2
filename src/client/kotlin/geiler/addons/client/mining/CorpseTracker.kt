package geiler.addons.client.mining

/**
 * Remembers each frozen corpse of a Mineshaft by position, so a corpse stays marked while its armor stand is out of
 * render distance and only turns looted when that very corpse is looted. The tab widget gives one looted flag per
 * corpse type, so a type that flips to looted is credited to the nearest not-yet-looted corpse of that type to the
 * player (the one just opened); a type already looted when first read is credited only when it has a single corpse.
 */
class CorpseTracker {
    class Entry(val type: String, val x: Double, val y: Double, val z: Double) {
        var looted = false
    }

    private val corpses = ArrayList<Entry>()
    private val lastTab = HashMap<String, Boolean>()

    /** Notes a corpse stand of [type] at the given spot; true when it is a corpse not seen before. */
    fun see(type: String, x: Double, y: Double, z: Double): Boolean {
        if (corpses.any { it.type == type && dist2(it.x, it.y, it.z, x, y, z) <= SAME_SPOT_SQ }) return false
        corpses.add(Entry(type, x, y, z))
        return true
    }

    /** Applies the tab widget's per-type looted flags; returns the corpses newly marked looted. */
    fun update(tab: Map<String, Boolean>, px: Double, py: Double, pz: Double): List<Entry> {
        val marked = ArrayList<Entry>()
        for ((type, looted) in tab) {
            val before = lastTab.put(type, looted)
            if (!looted) continue
            val ofType = corpses.filter { it.type == type && !it.looted }
            if (before == null) {
                val all = corpses.count { it.type == type }
                if (all == 1) ofType.forEach { it.looted = true; marked.add(it) }
            } else if (before == false) {
                val nearest = ofType.minByOrNull { dist2(it.x, it.y, it.z, px, py, pz) }
                if (nearest != null) {
                    nearest.looted = true
                    marked.add(nearest)
                }
            }
        }
        return marked
    }

    fun entries(): List<Entry> = corpses

    fun clear() {
        corpses.clear()
        lastTab.clear()
    }

    private fun dist2(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return dx * dx + dy * dy + dz * dz
    }

    private companion object {
        const val SAME_SPOT_SQ = 2.25
    }
}
