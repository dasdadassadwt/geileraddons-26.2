package geiler.addons.client.dungeon

import java.util.Locale

/**
 * Reads the Party Finder chest menu for Custom Party Finder: each listing head (leader, note, floor, requirements,
 * members, joinable) and the button slots (pages, refresh, settings, create, de-list). Slot roles follow Skyblocker's
 * `PartyFinderScreen` (behavior only): heads are listings, arrows page, and the bottom row holds an emerald block
 * (refresh), nether star (search settings), redstone block (create), bookshelf (de-list) and your own party's head.
 * Pure; plain strings in.
 */
object PartyFinderEntries {
    class SlotInfo(val index: Int, val itemId: String, val name: String, val lore: List<String>)

    class Party(
        val slot: Int,
        val leader: String,
        val note: String,
        val floor: String,
        val dungeon: String,
        val classRequired: Int,
        val dungeonRequired: Int,
        val members: List<PartyFinderListing.Member>,
        val joinable: Boolean,
        val blocked: String?,
    ) {
        fun openClasses(): List<DungeonClass> = DungeonClass.entries.filter { c -> members.none { it.dungeonClass == c } }
    }

    class Page(
        val parties: List<Party>,
        val previous: Int,
        val next: Int,
        val refresh: Int,
        val settings: Int,
        val create: Int,
        val delist: Int,
        val yourParty: Party?,
        val empty: Boolean,
    )

    private val NUMBER = Regex("(\\d+)")

    @JvmStatic
    fun isFinderTitle(title: String): Boolean = title.trim() == "Party Finder"

    /** The menus around the Party Finder that get the generic themed view (Search Settings, floor and class pickers...). */
    private val DUNGEON_MENUS = setOf(
        "Catacombs Gate", "Search Settings", "Select Floor", "Select Type", "Select Dungeon", "Create Party",
        "Party Settings", "Dungeon Classes", "Catacombs Profile", "Dungeon Rules and Tips", "Class Level Range",
        "Dungeon Level Range", "Edit Note",
    )

    /** Whether the Custom Party Finder draws [title] as a generic menu; [backLore] is its "Go Back" button's first lore line. */
    @JvmStatic
    fun isDungeonMenu(title: String, backLore: String?): Boolean {
        val t = title.trim()
        if (t in DUNGEON_MENUS) return true
        val back = backLore?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return back == "to catacombs gate" || back == "to party finder" || back == "to search settings"
    }

    private val FLOOR = Regex("(?i)\\bfloor\\s+([IVX]+|\\d+)\\b")
    private val BARE_FLOOR = Regex("(?i)^(master mode )?(floor\\s+\\S+|entrance)$")
    private val ROMAN = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7)
    private val CODES = Regex("§.")

    /** "F7", "M7" or "E" for a Catacombs floor named anywhere in [text] ("Master Mode The Catacombs Floor VII"), else null. */
    @JvmStatic
    fun shortFloor(text: String): String? {
        val plain = text.replace(CODES, "")
        val master = plain.contains("Master", ignoreCase = true)
        if (plain.contains("Entrance", ignoreCase = true)) return if (master) null else "E"
        val token = FLOOR.find(plain)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: return null
        val number = token.toIntOrNull() ?: ROMAN[token] ?: return null
        return (if (master) "M" else "F") + number
    }

    /** A menu item name shortened to its floor code when it names a Catacombs floor ("The Catacombs - Floor VII" is "F7"). */
    @JvmStatic
    fun shortLabel(name: String): String {
        val plain = name.replace(CODES, "").trim()
        if (!plain.contains("Catacombs", ignoreCase = true) && !BARE_FLOOR.matches(plain)) return name
        return shortFloor(plain) ?: name
    }

    /** The floor named in the Search Settings button's lore as a code ("Floor: Floor VII" gives "F7"), or "". */
    @JvmStatic
    fun searchedFloor(lore: List<String>): String {
        val dungeon = lore.firstOrNull { it.trim().startsWith("Dungeon:") }?.substringAfter(':')?.trim().orEmpty()
        val floor = lore.firstOrNull { it.trim().startsWith("Floor:") }?.substringAfter(':')?.trim().orEmpty()
        val master = dungeon.contains("Master", ignoreCase = true)
        shortFloor("$dungeon $floor")?.let { return it }
        return when {
            floor.isEmpty() -> dungeon
            master && !floor.startsWith("Master") -> "M " + floor.removePrefix("Floor ").trim()
            else -> floor
        }
    }

    @JvmStatic
    fun party(slot: Int, name: String, lore: List<String>): Party {
        var note = ""
        var floor = "?"
        var dungeon = ""
        var classReq = 0
        var dungeonReq = 0
        for (raw in lore) {
            val line = raw.trim()
            val lower = line.lowercase(Locale.ROOT)
            when {
                lower.startsWith("note:") -> note = line.substringAfter(':').trim()
                lower.startsWith("floor:") -> floor = line.substringAfter(':').trim()
                lower.startsWith("dungeon:") -> dungeon = line.substringAfter(':').trim()
                lower.contains("class level") -> classReq = NUMBER.find(line)?.value?.toIntOrNull() ?: 0
                lower.contains("dungeon level") -> dungeonReq = NUMBER.find(line)?.value?.toIntOrNull() ?: 0
            }
        }
        val members = PartyFinderListing.read(lore)?.members ?: emptyList()
        val last = lore.lastOrNull { it.isNotBlank() }?.trim() ?: ""
        val joinable = last.contains("Click to join", ignoreCase = true)
        val leader = name.trim().removeSuffix("'s Party").removeSuffix("'s party").trim().ifEmpty { members.firstOrNull()?.name ?: "?" }
        val blocked = if (joinable || last.isEmpty() || last.startsWith("Members", ignoreCase = true) || endsWithMemberRow(lore)) null else last
        return Party(slot, leader, note, floor, dungeon, classReq, dungeonReq, members, joinable, blocked)
    }

    /** True when the last line is a member row (indented), not a status line. */
    private fun endsWithMemberRow(lore: List<String>): Boolean = lore.lastOrNull { it.isNotBlank() }?.let { it[0].isWhitespace() } ?: false

    /** Reads a whole Party Finder page; [rows] is the chest height. Missing buttons are -1. */
    @JvmStatic
    fun page(slots: List<SlotInfo>, rows: Int): Page {
        val parties = ArrayList<Party>()
        var previous = -1
        var next = -1
        var refresh = -1
        var settings = -1
        var create = -1
        var delist = -1
        var yours: SlotInfo? = null
        val bottom = (rows - 1) * 9
        val empty = slots.any { it.itemId == "minecraft:bedrock" }
        for (slot in slots) {
            if (slot.index >= rows * 9) break
            val name = slot.name.lowercase(Locale.ROOT)
            if (slot.index < bottom) {
                when {
                    slot.itemId == "minecraft:player_head" && !empty -> parties.add(party(slot.index, slot.name, slot.lore))
                    slot.itemId == "minecraft:arrow" && name.contains("previous") -> previous = slot.index
                    slot.itemId == "minecraft:arrow" && name.contains("next") -> next = slot.index
                }
                continue
            }
            when (slot.itemId) {
                "minecraft:arrow" -> if (name.contains("previous")) previous = slot.index else if (name.contains("next")) next = slot.index
                "minecraft:emerald_block" -> refresh = slot.index
                "minecraft:nether_star" -> settings = slot.index
                "minecraft:redstone_block" -> create = slot.index
                "minecraft:bookshelf" -> delist = slot.index
                "minecraft:player_head" -> yours = slot
            }
        }
        val own = yours?.let { party(it.index, it.name, it.lore) }
        return Page(parties, previous, next, refresh, settings, create, delist, own, empty)
    }

    /** Search over leader, note, floor and member names, case-insensitive. */
    @JvmStatic
    fun matches(party: Party, query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        return party.leader.lowercase(Locale.ROOT).contains(q) || party.note.lowercase(Locale.ROOT).contains(q) ||
            party.floor.lowercase(Locale.ROOT).contains(q) || party.members.any { it.name.lowercase(Locale.ROOT).contains(q) }
    }
}
