package geiler.addons.client.location

/**
 * A SkyBlock island, identified by the `mode` string the Hypixel Mod API reports.
 *
 * The mode strings are not the island's readable name and cannot be guessed from it: Torrhus
 * Canyon reports `foraging_3`, not `torrhus_canyon`. They come from the values SkyHanni maps against.
 *
 * The guest variants of the Private Island and Garden are deliberately missing. Hypixel reports
 * no mode for them, so there is nothing here that could distinguish them from the island itself.
 */
enum class Island(
    private val mode: String?,
    private val islandLabel: String,
    private val islandDisplayName: String,
) {
    // General
    PRIVATE_ISLAND("dynamic", "Private Island", "your Private Island"),
    HUB("hub", "Hub", "the Hub"),
    DARK_AUCTION("dark_auction", "Dark Auction", "the Dark Auction"),
    WINTER("winter", "Jerry's Workshop", "Jerry's Workshop"),

    // Farming
    FARMING_ISLANDS("farming_1", "The Farming Islands", "the Farming Islands"),
    GARDEN("garden", "Garden", "the Garden"),

    // Mining
    GOLD_MINE("mining_1", "Gold Mine", "the Gold Mine"),
    DEEP_CAVERNS("mining_2", "Deep Caverns", "the Deep Caverns"),
    DWARVEN_MINES("mining_3", "Dwarven Mines", "the Dwarven Mines"),
    CRYSTAL_HOLLOWS("crystal_hollows", "Crystal Hollows", "the Crystal Hollows"),
    MINESHAFT("mineshaft", "Mineshaft", "a Mineshaft"),

    // Fishing
    BACKWATER_BAYOU("fishing_1", "Backwater Bayou", "the Backwater Bayou"),
    LOTUS_ATOLL("lotus_atoll", "Lotus Atoll", "the Lotus Atoll"),

    // Foraging
    THE_PARK("foraging_1", "The Park", "the Park"),
    GALATEA("foraging_2", "Moonglade Marsh", "Moonglade Marsh"),
    TORRHUS_CANYON("foraging_3", "Torrhus Canyon", "Torrhus Canyon"),

    // Combat
    SPIDERS_DEN("combat_1", "Spider's Den", "the Spider's Den"),
    THE_END("combat_3", "The End", "the End"),
    CRIMSON_ISLE("crimson_isle", "Crimson Isle", "the Crimson Isle"),

    // Dungeons
    DUNGEON_HUB("dungeon_hub", "Dungeon Hub", "the Dungeon Hub"),
    CATACOMBS("dungeon", "Catacombs", "the Catacombs"),
    KUUDRA("kuudra", "Kuudra", "Kuudra"),

    // Special
    THE_RIFT("rift", "The Rift", "the Rift"),
    SAFARI("safari", "Critter Safari", "the Critter Safari"),

    /** The API named an island this mod has no constant for; that is decisive evidence elsewhere. */
    OTHER(null, "Other", "a named island"),

    /** No answer at all: not on Hypixel, the API is off, or its handshake has not finished. */
    NONE(null, "None", "nowhere known");

    /** The Hypixel mode string this island reports (also Skyblocker's island id), or null for the no-answer states. */
    fun mode(): String? = mode

    /** The plain name, for a list that names islands rather than explaining an absence. */
    fun label(): String = islandLabel

    /** Reads as the tail of "Not ..." in the Click GUI's idle explanation. */
    fun displayName(): String = islandDisplayName

    /** Whether this is a real island the API can report, rather than one of the no-answer states. */
    fun selectable(): Boolean = mode != null

    companion object {
        /**
         * @param mode raw mode string, or null if the server sent none
         * @return the matching island, or [OTHER] for an island this mod does not name
         */
        @JvmStatic
        fun fromMode(mode: String?): Island {
            if (mode.isNullOrBlank()) return OTHER
            for (island in values()) {
                if (island.mode != null && island.mode.equals(mode, ignoreCase = true)) return island
            }
            return OTHER
        }
    }
}
