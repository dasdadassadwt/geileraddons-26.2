package geiler.addons.client.mining

import java.util.Locale

/**
 * Crystal Hollows rules after Skyblocker's CrystalsLocationsManager (LGPL-3.0): the structures, the bounds of the
 * Hollows, coordinates shared in chat and the sidebar area. Skyblocker's server sharing is not ported.
 */
object CrystalHollowsRules {
    const val CHEST_MESSAGE = "You uncovered a treasure chest!"
    const val NUCLEUS_X = 513
    const val NUCLEUS_Y = 107
    const val NUCLEUS_Z = 513

    enum class Structure(val label: String, val argb: Int, vararg val keywords: String) {
        JUNGLE_TEMPLE("Jungle Temple", 0xFFAA00AA.toInt(), "jungle", "temple"),
        MINES_OF_DIVAN("Mines of Divan", 0xFF55FFFF.toInt(), "divan", "mines"),
        GOBLIN_QUEENS_DEN("Goblin Queen's Den", 0xFFFFAA00.toInt(), "goblin", "queen", "queens", "den"),
        LOST_PRECURSOR_CITY("Lost Precursor City", 0xFF5555FF.toInt(), "lost", "precursor", "city"),
        KHAZAD_DUM("Khazad-dûm", 0xFFFF5555.toInt(), "khazad", "dum"),
        FAIRY_GROTTO("Fairy Grotto", 0xFFFF55FF.toInt(), "fairy", "grotto"),
        DRAGONS_LAIR("Dragon's Lair", 0xFF55FF55.toInt(), "dragon", "dragons", "lair"),
        ;

        /** The word used in commands, for example `jungle_temple`. */
        val key: String get() = name.lowercase(Locale.ROOT)
    }

    private val COORDINATES = Regex("""(?<!\d)(\d{3})\D{1,5}?(\d{2,3})\D{1,5}?(\d{3})(?!\d)""")
    private const val SAME_SPOT_SQ = 50 * 50

    @JvmStatic
    fun inBounds(x: Int, y: Int, z: Int): Boolean = x in 202..823 && z in 202..823 && y in 31..188

    /** The first x y z in a chat line, or null. Callers still check [inBounds]. */
    @JvmStatic
    fun parseCoordinates(message: String): IntArray? {
        val match = COORDINATES.find(message) ?: return null
        return intArrayOf(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
    }

    private fun normalise(text: String): String = text.lowercase(Locale.ROOT).replace('û', 'u')

    /** The structure a chat line talks about, matched by whole words, or null. */
    @JvmStatic
    fun structureIn(message: String): Structure? {
        val words = normalise(message).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()
        return Structure.entries.firstOrNull { structure -> structure.keywords.any { it in words } }
    }

    /** A structure from its command [Structure.key], or null. */
    @JvmStatic
    fun structureForKey(key: String): Structure? = Structure.entries.firstOrNull { it.key == key.lowercase(Locale.ROOT) }

    /** The chat text for a shared spot; [parseCoordinates] and [structureIn] read it back on the other side. */
    @JvmStatic
    fun shareText(name: String, x: Int, y: Int, z: Int): String = "$name: $x $y $z"

    /** The structure the sidebar's area line names, or null in a plain area. */
    @JvmStatic
    fun structureForArea(sidebar: List<String>): Structure? {
        for (line in sidebar) {
            val text = normalise(line)
            Structure.entries.firstOrNull { text.contains(normalise(it.label)) }?.let { return it }
        }
        return null
    }

    @JvmStatic
    fun sameSpot(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int): Boolean {
        val dx = (ax - bx).toLong()
        val dy = (ay - by).toLong()
        val dz = (az - bz).toLong()
        return dx * dx + dy * dy + dz * dz < SAME_SPOT_SQ
    }
}
