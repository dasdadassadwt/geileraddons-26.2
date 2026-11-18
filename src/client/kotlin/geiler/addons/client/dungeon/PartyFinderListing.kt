package geiler.addons.client.dungeon

/**
 * Reads a Hypixel Party Finder listing's lore: the floor line and the `Members:` block of up to five
 * ` Name: Class (Level)` rows, where open slots read `Empty`. The row layout follows Skyblocker's listing parser
 * (behavior only, no code copied); it is not verified against a live listing.
 */
object PartyFinderListing {
    const val MAX_MEMBERS = 5

    private val levelPattern = Regex("\\((\\d+)\\)")

    data class Member(val name: String, val dungeonClass: DungeonClass?, val level: Int)

    data class Reading(val floor: DungeonFloor?, val members: List<Member>) {
        fun count(): Int = members.size

        /** The classes no listed member plays, in enum order. */
        fun openClasses(): List<DungeonClass> = DungeonClass.entries.filter { c -> members.none { it.dungeonClass == c } }
    }

    /** The listing in [lore], or null when it has no `Members:` header. */
    @JvmStatic
    fun read(lore: List<String>): Reading? {
        val header = headerIndex(lore)
        if (header < 0) return null
        val members = ArrayList<Member>()
        for (line in rows(lore, header)) member(line.trim())?.let(members::add)
        return Reading(DungeonQueueFloorTracker.parseJoinedListing(lore), members)
    }

    /** Index of the `Members:` line, or -1. */
    @JvmStatic
    fun headerIndex(lines: List<String>): Int = lines.indexOfFirst { it.trim().startsWith("Members:", ignoreCase = true) }

    /** The member rows after the header, Empty rows included: indented lines only, at most [MAX_MEMBERS]. */
    @JvmStatic
    fun rows(lines: List<String>, header: Int): List<String> =
        lines.drop(header + 1).take(MAX_MEMBERS).takeWhile { it.isNotBlank() && it[0].isWhitespace() }

    private fun member(row: String): Member? {
        val colon = row.indexOf(':')
        val name = (if (colon < 0) row else row.substring(0, colon)).trim()
        if (name.isEmpty() || name.equals("Empty", ignoreCase = true)) return null
        val rest = if (colon < 0) "" else row.substring(colon + 1).trim()
        val level = levelPattern.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Member(name, DungeonClass.parse(rest.substringBefore(' ')), level)
    }

    /** ARGB for a class level: red below 30, yellow below 40, green below 50, gold at 50. */
    @JvmStatic
    fun levelColor(level: Int): Int = when {
        level >= 50 -> 0xFFFFAA00.toInt()
        level >= 40 -> 0xFF55FF55.toInt()
        level >= 30 -> 0xFFFFFF55.toInt()
        else -> 0xFFFF5555.toInt()
    }
}
