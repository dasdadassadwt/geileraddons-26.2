package geiler.addons.client.hunting

import geiler.addons.client.location.SafariBiome
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
 * The roster, biomes and spawn quotas follow Critter Mod (MrCloudy2, MIT), which measured the quotas
 * over 25 replayed runs. The Macaw looks quota-like but is RNG, so it has none.
 *
 * This is pure logic apart from the biome enum, so it can be exercised offline against real and
 * adversarial labels.
 */
object CritterSpecies {
    private const val SPARKLING = "Sparkling"

    private val ALL: List<Critter> = listOf(
        Critter("Foxtrot", SafariBiome.FOREST, Rarity.COMMON),
        Critter("Bluebird", SafariBiome.FOREST, Rarity.UNCOMMON),
        Critter("Honeybug", SafariBiome.FOREST, Rarity.UNCOMMON),
        Critter("Treefrog", SafariBiome.FOREST, Rarity.UNCOMMON),
        Critter("Woodchucker", SafariBiome.FOREST, Rarity.UNCOMMON),
        Critter("Fluffling", SafariBiome.FOREST, Rarity.RARE),
        Critter("Hideonfloor", SafariBiome.FOREST, Rarity.RARE),
        Critter("Parakeet", SafariBiome.FOREST, Rarity.RARE),
        Critter("Macaw", SafariBiome.FOREST, Rarity.LEGENDARY),

        Critter("Cavernfish", SafariBiome.CAVERN, Rarity.COMMON),
        Critter("Flitter", SafariBiome.CAVERN, Rarity.COMMON),
        Critter("Shyworm", SafariBiome.CAVERN, Rarity.COMMON),
        Critter("Driftling", SafariBiome.CAVERN, Rarity.UNCOMMON),
        Critter("Chuckwalla", SafariBiome.CAVERN, Rarity.RARE),
        Critter("Rockmite", SafariBiome.CAVERN, Rarity.RARE),
        Critter("Scrappy", SafariBiome.CAVERN, Rarity.RARE),
        Critter("Snoozle", SafariBiome.CAVERN, Rarity.RARE),
        Critter("Gemzie", SafariBiome.CAVERN, Rarity.EPIC, 3),

        Critter("Strongarm", SafariBiome.ICY, Rarity.COMMON),
        Critter("Tepid", SafariBiome.ICY, Rarity.COMMON),
        Critter("Polaris", SafariBiome.ICY, Rarity.UNCOMMON),
        Critter("Shuddersquid", SafariBiome.ICY, Rarity.UNCOMMON),
        Critter("Billygoat", SafariBiome.ICY, Rarity.RARE),
        Critter("Mantis Shrimp", SafariBiome.ICY, Rarity.RARE),
        Critter("Nozzlenose", SafariBiome.ICY, Rarity.RARE),
        Critter("Troodon", SafariBiome.ICY, Rarity.RARE, 3),
        Critter("Wumpa", SafariBiome.ICY, Rarity.LEGENDARY, 1),

        Critter("Areita", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        Critter("Bloodbat", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        Critter("Duplico", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        Critter("Gazer", SafariBiome.HAUNTED, Rarity.UNCOMMON, 4),
        Critter("Litterbug", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        Critter("Solsnatcher", SafariBiome.HAUNTED, Rarity.UNCOMMON),
        Critter("Gimmiegold", SafariBiome.HAUNTED, Rarity.RARE),
        Critter("Hideonwall", SafariBiome.HAUNTED, Rarity.RARE),
        Critter("Hideyho", SafariBiome.HAUNTED, Rarity.RARE, 1),
        Critter("Doomspiral", SafariBiome.HAUNTED, Rarity.LEGENDARY, 1),
    )

    /** Lower-cased name to the species, which carries the canonical spelling shown to the user. */
    private val BY_NAME: Map<String, Critter> = ALL.associateBy { it.name.lowercase(Locale.ROOT) }

    /** Longest name first, so a name that is a prefix of another can never win a substring search. */
    private val LONGEST_FIRST: List<Critter> = ALL.sortedByDescending { it.name.length }

    /** The Forest legendary: RNG, not guaranteed, so "everything but this" is its own milestone. */
    @JvmField
    val MACAW: Critter = BY_NAME.getValue("macaw")

    /** Total species count - 37. */
    @JvmStatic
    fun count(): Int = ALL.size

    @JvmStatic
    fun all(): List<Critter> = ALL

    @JvmStatic
    fun inBiome(biome: SafariBiome): List<Critter> = ALL.filter { it.biome == biome }

    /** The species with exactly this name (case-insensitive), or null. */
    @JvmStatic
    fun byName(name: String?): Critter? = if (name == null) null else BY_NAME[name.trim().lowercase(Locale.ROOT)]

    /**
     * The species a chat or NPC line mentions anywhere in it, or null.
     *
     * Only for text the game itself wrote (a shard line, an NPC offer): a roster lookup keeps unseen
     * wordings working. Never use it on a label or on player-typed text.
     */
    @JvmStatic
    fun findIn(line: String?): Critter? {
        if (line == null) return null
        return LONGEST_FIRST.firstOrNull { line.contains(it.name) }
    }

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
        return if (text.isEmpty()) null else BY_NAME[text.lowercase(Locale.ROOT)]?.name
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
