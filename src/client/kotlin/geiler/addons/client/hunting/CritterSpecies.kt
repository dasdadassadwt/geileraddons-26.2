package geiler.addons.client.hunting

import geiler.addons.client.tree.ChatText
import java.util.Locale

/**
 * The 37 Critter Safari species, and how to read a rare one out of a name tag.
 *
 * Hypixel labels a critter with an entity whose custom name is exactly the species name, and a
 * rare variant is named for it: `Sparkling Rockmite`. That prefix is the only thing this
 * recognises, rather than searching a label for any species name it contains - a nearby armour
 * stand advertising a `Tepid Shard` must not read as a Tepid.
 *
 * This is pure logic with no Minecraft types, so it can be exercised offline against real and
 * adversarial labels.
 */
object CritterSpecies {
    private const val SPARKLING = "Sparkling"

    private val NAMES = arrayOf(
        // Forest
        "Foxtrot", "Bluebird", "Honeybug", "Treefrog", "Woodchucker", "Fluffling", "Hideonfloor",
        "Parakeet", "Macaw",
        // Cavern
        "Cavernfish", "Flitter", "Shyworm", "Driftling", "Chuckwalla", "Rockmite", "Scrappy",
        "Snoozle", "Gemzie",
        // Icy
        "Strongarm", "Tepid", "Polaris", "Shuddersquid", "Billygoat", "Mantis Shrimp", "Nozzlenose",
        "Troodon", "Wumpa",
        // Haunted
        "Areita", "Bloodbat", "Duplico", "Gazer", "Litterbug", "Solsnatcher", "Gimmiegold",
        "Hideonwall", "Hideyho", "Doomspiral",
    )

    /** Lower-cased name to its canonical spelling, which is what gets shown to the user. */
    private val BY_NAME = buildMap {
        NAMES.forEach { name -> put(name.lowercase(Locale.ROOT), name) }
    }

    /** Total species count - 37. */
    @JvmStatic
    fun count(): Int = NAMES.size

    /**
     * The species a label names outright, or null.
     *
     * Whole-string equality rather than `contains`: a label is the species name and nothing else,
     * so anything longer is a different thing that merely mentions one.
     */
    @JvmStatic
    fun named(label: String?): String? {
        if (label == null) return null
        val text = ChatText.stripForMatch(label)
        return if (text.isEmpty()) null else BY_NAME[text.lowercase(Locale.ROOT)]
    }

    /** The species a label names as a sparkling one, or null if it names none. */
    @JvmStatic
    fun sparkling(label: String?): String? {
        if (label == null) return null
        val text = ChatText.stripForMatch(label)
        if (text.length <= SPARKLING.length) return null
        if (!text.regionMatches(0, SPARKLING, 0, SPARKLING.length, ignoreCase = true)) return null
        return named(text.substring(SPARKLING.length))
    }
}
