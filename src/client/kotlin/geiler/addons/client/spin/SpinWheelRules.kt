package geiler.addons.client.spin

/** One wheel entry. [chance] is a percentage; the chances of a wheel always add up to 100. */
class SpinEntry(@JvmField var name: String, var chance: Double)

/**
 * The pure rules of the `/ga spin` wheel. Angles are degrees clockwise from the pointer (3 o'clock); entry 0 starts at
 * 0 and each entry covers its share of the circle.
 */
object SpinWheelRules {
    const val MAX_ENTRIES = 24
    const val MAX_NAME = 32

    /** A new entry gets an equal share; the others shrink in proportion. */
    @JvmStatic
    fun add(entries: MutableList<SpinEntry>, name: String): Boolean {
        if (entries.size >= MAX_ENTRIES) return false
        val share = 100.0 / (entries.size + 1)
        for (entry in entries) entry.chance *= (100.0 - share) / 100.0
        entries.add(SpinEntry(name, share))
        return true
    }

    /** Sets one chance; the others keep their proportions and fill the rest (equal shares if they are all 0). */
    @JvmStatic
    fun setChance(entries: MutableList<SpinEntry>, index: Int, value: Double) {
        if (index !in entries.indices || !value.isFinite()) return
        if (entries.size == 1) {
            entries[0].chance = 100.0
            return
        }
        val wanted = value.coerceIn(0.0, 100.0)
        val others = entries.filterIndexed { i, _ -> i != index }
        val sum = others.sumOf { it.chance }
        val rest = 100.0 - wanted
        for (other in others) other.chance = if (sum > 1e-9) other.chance * rest / sum else rest / others.size
        entries[index].chance = wanted
    }

    @JvmStatic
    fun remove(entries: MutableList<SpinEntry>, index: Int) {
        if (index !in entries.indices) return
        entries.removeAt(index)
        normalize(entries)
    }

    /** Repairs saved data: bad values become 0 and the rest scales to 100 (equal shares if nothing is left). */
    @JvmStatic
    fun normalize(entries: MutableList<SpinEntry>) {
        for (entry in entries) if (!entry.chance.isFinite() || entry.chance < 0) entry.chance = 0.0
        val sum = entries.sumOf { it.chance }
        for (entry in entries) entry.chance = if (sum > 1e-9) entry.chance * 100.0 / sum else 100.0 / entries.size
    }

    /** The winner for a [roll] in [0, 1), weighted by chance; -1 for an empty wheel. */
    @JvmStatic
    fun pick(entries: List<SpinEntry>, roll: Double): Int {
        var left = roll * 100.0
        for ((index, entry) in entries.withIndex()) {
            if (entry.chance <= 0) continue
            if (left < entry.chance) return index
            left -= entry.chance
        }
        return entries.indexOfLast { it.chance > 0 }
    }

    /** Where [index] starts on the unturned wheel, in degrees. */
    @JvmStatic
    fun startAngle(entries: List<SpinEntry>, index: Int): Double = entries.take(index).sumOf { it.chance } * 3.6

    /**
     * The final rotation of a spin from [from] that puts [winner] under the pointer after at least [turns] full turns.
     * [inside] (0..1) chooses the spot within the winner's segment, kept away from its edges.
     */
    @JvmStatic
    fun landingRotation(entries: List<SpinEntry>, winner: Int, from: Double, inside: Double, turns: Int): Double {
        val target = startAngle(entries, winner) + entries[winner].chance * 3.6 * (0.1 + 0.8 * inside.coerceIn(0.0, 1.0))
        return from + turns * 360.0 + (-target - from).mod(360.0)
    }

    @JvmStatic
    fun entryAtPointer(entries: List<SpinEntry>, rotation: Double): Int =
        if (entries.isEmpty()) -1 else pick(entries, (-rotation).mod(360.0) / 360.0)

    /** Fast start, long slow finish. */
    @JvmStatic
    fun ease(progress: Double): Double {
        val left = 1.0 - progress.coerceIn(0.0, 1.0)
        return 1.0 - left * left * left * left
    }
}
