package geiler.addons.client.waypoint

/** Reads `x: 1, y: 2, z: 3` coordinates a player sent to party, guild or all chat (Odin / NoFrills behaviour). */
object ChatWaypointParser {
    enum class Channel { PARTY, GUILD, ALL }

    class Parsed(val channel: Channel, val sender: String, val x: Int, val y: Int, val z: Int)

    const val MAX_ABS_COORD = 5000
    const val MIN_Y = -64
    const val MAX_Y = 320

    private val LINE = Regex(
        """^(Party > |Guild > )?(?:\[[^\]]*\] ?)*(\w{1,16})(?: \[[^\]]*\])?(?: [^\w\s]{1,2})?: """ +
            """x:\s*(-?\d+),?\s*y:\s*(-?\d+),?\s*z:\s*(-?\d+)""",
        RegexOption.IGNORE_CASE,
    )

    /** [plain] is the chat line without colour codes. Returns null for anything that is not a coordinate message. */
    @JvmStatic
    fun parse(plain: String?): Parsed? {
        if (plain.isNullOrBlank()) return null
        val match = LINE.find(plain.trim()) ?: return null
        val (prefix, sender, xs, ys, zs) = match.destructured
        val x = xs.toIntOrNull() ?: return null
        val y = ys.toIntOrNull() ?: return null
        val z = zs.toIntOrNull() ?: return null
        if (Math.abs(x) > MAX_ABS_COORD || Math.abs(z) > MAX_ABS_COORD || y < MIN_Y || y > MAX_Y) return null
        val channel = when (prefix.trim()) {
            "Party >" -> Channel.PARTY
            "Guild >" -> Channel.GUILD
            else -> Channel.ALL
        }
        return Parsed(channel, sender, x, y, z)
    }
}
