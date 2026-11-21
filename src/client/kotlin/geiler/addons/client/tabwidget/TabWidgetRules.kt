package geiler.addons.client.tabwidget

import geiler.addons.client.location.Island

/**
 * Pure rules of the Tab Widget module: the table of Hypixel tab-list widgets and the split of an ordered tab list into
 * one block per widget. The widget set and the header patterns follow SkyHanni's `TabWidget` enum (written again here, no
 * code copied). A block is the header row plus the rows after it up to a blank row or the next header of any known widget.
 */
object TabWidgetRules {
    /** Longest block shown; the Hypixel widgets are a handful of rows, the player list is cut. */
    const val MAX_LINES = 14

    /** One widget the player can switch on. An empty [islands] set means it is shown on every island. */
    class Widget(
        val id: String,
        val name: String,
        val group: String,
        val header: Regex,
        val islands: Set<Island>,
        val sample: List<String>,
    )

    private val MINING = setOf(Island.DWARVEN_MINES, Island.CRYSTAL_HOLLOWS, Island.MINESHAFT, Island.DEEP_CAVERNS, Island.GOLD_MINE)
    private val FARMING = setOf(Island.GARDEN, Island.FARMING_ISLANDS)
    private val FORAGING = setOf(Island.THE_PARK, Island.GALATEA, Island.TORRHUS_CANYON)
    private val ANY = emptySet<Island>()

    private fun w(id: String, name: String, group: String, header: String, islands: Set<Island>, vararg sample: String) =
        Widget(id, name, group, Regex(header), islands, sample.toList())

    val WIDGETS: List<Widget> = listOf(
        w("players", "Players", "General", """Players \(\d+\)""", ANY, "Players (24)", "[MVP+] Alice", "[VIP] Bob"),
        w("profile", "Profile", "General", """Profile: .+""", ANY, "Profile: Pineapple"),
        w("sblevel", "SB Level", "General", """SB Level: .+""", ANY, "SB Level: [312] 45/100 XP"),
        w("bank", "Bank", "General", """Bank: .+""", ANY, "Bank: 1.2B/1.6B"),
        w("interest", "Interest", "General", """Interest: .+""", ANY, "Interest: 4h 12m (3.4M)"),
        w("soulflow", "Soulflow", "General", """Soulflow: .+""", ANY, "Soulflow: 27,431"),
        w("fairysouls", "Fairy Souls", "General", """Fairy Souls: \d+/\d+""", ANY, "Fairy Souls: 238/247"),
        w("pet", "Pet", "General", """Pet:""", ANY, "Pet:", "[Lvl 100] Golden Dragon"),
        w("pettraining", "Pet Training", "General", """Pet Training:""", ANY, "Pet Training:", "Tiger 4h 10m"),
        w("stats", "Stats", "General", """Stats:""", ANY, "Stats:", "Speed: 400", "Strength: 1,200"),
        w("skills", "Skills", "General", """Skills: ?[\d.]*""", ANY, "Skills: 42.5"),
        w("bestiary", "Bestiary", "General", """Bestiary:""", ANY, "Bestiary:", "Glacite Walker 24"),
        w("collection", "Collection", "General", """Collection:""", ANY, "Collection:", "Wheat 1.2M"),
        w("timers", "Timers", "General", """Timers:""", ANY, "Timers:", "Cookie Buff: 3d 2h"),
        w("firesale", "Fire Sales", "General", """Fire Sales: .*""", ANY, "Fire Sales: 2h 10m"),
        w("election", "Election", "General", """Election: .+""", ANY, "Election: 5d 3h"),
        w("event", "Event", "General", """Event: .+""", ANY, "Event: Spooky Festival"),
        w("dailyquests", "Daily Quests", "General", """Daily Quests:""", ANY, "Daily Quests:", "Kill Zombies 4/10"),
        w("effects", "Active Effects", "General", """Active Effects: \(\d+\)""", ANY, "Active Effects: (3)", "God Potion 4d"),
        w("slayer", "Slayer", "General", """Slayer:""", ANY, "Slayer:", "Revenant Horror V", "12/20 Kills"),

        w("commissions", "Commissions", "Mining", """Commissions:""", MINING, "Commissions:", "Mithril Miner: 45%", "Goblin Slayer: DONE"),
        w("powders", "Powders", "Mining", """Powders:""", MINING, "Powders:", "Mithril: 1,200,000", "Gemstone: 800,000"),
        w("crystals", "Crystals", "Mining", """Crystals:""", MINING, "Crystals:", "Amber: Placed"),
        w("forges", "Forges", "Mining", """Forges:""", setOf(Island.DWARVEN_MINES), "Forges:", "1) Divan Drill 12h", "2) EMPTY"),
        w("pickaxe", "Pickaxe Ability", "Mining", """Pickaxe Ability:""", MINING, "Pickaxe Ability:", "Mining Speed Boost: READY"),
        w("pity", "Pity", "Mining", """Pity:""", ANY, "Pity:", "Scatha 2.1%"),
        w("frozencorpses", "Frozen Corpses", "Mining", """Frozen Corpses:""", setOf(Island.MINESHAFT), "Frozen Corpses:", "Lapis: LOOTED"),

        w("pests", "Pests", "Farming", """Pests:""", setOf(Island.GARDEN), "Pests:", "Plots: 3, 7"),
        w("pesttraps", "Pest Traps", "Farming", """Pest Traps: \d+/\d+""", setOf(Island.GARDEN), "Pest Traps: 2/3"),
        w("visitors", "Visitors", "Farming", """Visitors: \(\d+\)""", setOf(Island.GARDEN), "Visitors: (4)", "Jacob", "Lumber Jack"),
        w("composter", "Composter", "Farming", """Composter:""", setOf(Island.GARDEN), "Composter:", "Organic Matter: 10K"),
        w("gardenlevel", "Garden Level", "Farming", """Garden Level: .+""", setOf(Island.GARDEN), "Garden Level: 12"),
        w("copper", "Copper", "Farming", """Copper: .+""", setOf(Island.GARDEN), "Copper: 4,500"),
        w("sowdust", "Sowdust", "Farming", """Sowdust: .+""", setOf(Island.GARDEN), "Sowdust: 1,200"),
        w("cropmilestones", "Crop Milestones", "Farming", """Crop Milestones:""", setOf(Island.GARDEN), "Crop Milestones:", "Wheat 31"),
        w("jacobs", "Jacob's Contest", "Farming", """Jacob's Contest:.*""", FARMING, "Jacob's Contest: 12m", "Wheat"),
        w("trapper", "Trapper", "Farming", """Trapper:""", setOf(Island.FARMING_ISLANDS), "Trapper:", "Cooldown: 5m"),
        w("minions", "Minions", "Farming", """Minions: \d+/\d+""", setOf(Island.PRIVATE_ISLAND), "Minions: 18/26"),

        w("agatha", "Agatha's Contest", "Foraging", """Agatha's Contest:.*""", FORAGING, "Agatha's Contest: 8m"),
        w("miria", "Miria's Contest", "Foraging", """Miria's Contest:.*""", FORAGING, "Miria's Contest: 8m"),
        w("whispers", "Whispers", "Foraging", """\w+ Whispers: .*""", FORAGING, "Forest Whispers: 1,200"),
        w("beacon", "Foraging Beacon", "Foraging", """\w+ Beacon: \d+ Stacks?""", FORAGING, "Forest Beacon: 3 Stacks"),
        w("starborn", "Starborn Temple", "Foraging", """Starborn Temple:""", FORAGING, "Starborn Temple:", "Cleared: 2"),

        w("dragon", "Dragon", "Combat", """Dragon: \(.*\)""", setOf(Island.THE_END), "Dragon: (Superior)"),
        w("protector", "Protector", "Combat", """Protector: .+""", setOf(Island.THE_END), "Protector: Awakening"),
        w("eyes", "Eyes Placed", "Combat", """Eyes placed: .*""", setOf(Island.THE_END), "Eyes placed: 5"),
        w("trophyfish", "Trophy Fish", "Combat", """Trophy Fish:""", setOf(Island.CRIMSON_ISLE), "Trophy Fish:", "Karate Fish 12"),
        w("reputation", "Reputation", "Combat", """(?:Barbarian|Mage) Reputation:""", setOf(Island.CRIMSON_ISLE), "Mage Reputation:", "Tier 4"),
        w("factionquests", "Faction Quests", "Combat", """Faction Quests:""", setOf(Island.CRIMSON_ISLE), "Faction Quests:", "Kill Magma Cubes 3/10"),
        w("volcano", "Volcano", "Combat", """Volcano: .+""", setOf(Island.CRIMSON_ISLE), "Volcano: Calm"),
        w("rain", "Rain", "Combat", """(?:Thunder|Rain): .+""", FORAGING, "Rain: 12m"),

        w("dungeonparty", "Dungeon Party", "Dungeons", """Party \(\d+\)""", setOf(Island.CATACOMBS), "Party (5)", "Alice [Mage 50]", "Bob [Healer 47]"),
        w("dungeonpuzzles", "Dungeon Puzzles", "Dungeons", """Puzzles: \(\d+\)""", setOf(Island.CATACOMBS), "Puzzles: (2)", "Water Board [DONE]"),
        w("dungeonrooms", "Opened Rooms", "Dungeons", """Opened Rooms: \d+""", setOf(Island.CATACOMBS), "Opened Rooms: 21"),
        w("unclaimed", "Unclaimed Chests", "Dungeons", """Unclaimed chests: \d+""", setOf(Island.DUNGEON_HUB), "Unclaimed chests: 3"),
    )

    /** Headers of tab-list widgets that are not offered: they only end the block above them. */
    private val DELIMITERS = listOf(
        """Info""", """(?:Area|Dungeon): .+""", """Server: .+""", """Gems: .+""", """Coop .*""", """Island""", """Guests \(\d+\)""",
        """Island closes in: .*""", """North Stars: .*""", """Essence:.*""", """Party:.*""", """Broodmother: .+""",
        """Good to know:""", """Shen: .*""", """Advertisement:""", """Scrap: .*""", """Event Trackers:""", """Salts:""",
        """Shard Traps.*""", """Dungeon Stats""", """Player Stats""", """Account Info""", """Skills: \w+ \d+: .*""",
        """Dungeons:""", """Kat: .*""", """Pet Sitter:.*""", """Crystals: \d+""",
    ).map { Regex(it) }

    private val FORMATTING = Regex("§.")

    /** A tab row without formatting codes, no-break spaces or edge spaces; null is an empty row. */
    fun clean(line: String?): String = line.orEmpty().replace(FORMATTING, "").replace(' ', ' ').trim()

    /** The widget a cleaned [row] opens, or null (also for the delimiter-only headers). */
    fun headerOf(row: String): Widget? = WIDGETS.firstOrNull { it.header.matches(row) }

    private fun isDelimiter(row: String): Boolean = DELIMITERS.any { it.matches(row) }

    /**
     * The rows of every widget present in [rows] (an ordered tab list), by widget id. A widget that appears twice keeps its
     * first block; rows before any header, and the rows of headers that are not offered, are dropped.
     */
    fun parse(rows: List<String?>?): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        if (rows.isNullOrEmpty()) return out
        var current: Widget? = null
        var block = ArrayList<String>()
        fun flush() {
            val widget = current
            if (widget != null && widget.id !in out) out[widget.id] = block
            current = null
            block = ArrayList()
        }
        for (raw in rows) {
            val row = clean(raw)
            if (row.isEmpty()) {
                flush()
                continue
            }
            val widget = headerOf(row)
            if (widget != null || isDelimiter(row)) {
                flush()
                current = widget
                if (widget != null) block.add(row)
            } else if (current != null && block.size < MAX_LINES) {
                block.add(row)
            }
        }
        flush()
        return out
    }
}
