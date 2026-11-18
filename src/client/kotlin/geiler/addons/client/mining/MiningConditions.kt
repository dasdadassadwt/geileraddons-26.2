package geiler.addons.client.mining

import geiler.addons.client.tree.ChatText

/**
 * Text rules for the smaller Mining features: heat and cold from the sidebar, goblin spawn messages, the Don Expresso
 * reminder, Fetchur and Puzzler, and the tab-list mining event. Behavior follows SkyHanni's MiningApi, ColdOverlay,
 * HighHeatSound, MiningNotifications and MiningEventsApi (LGPL-2.1) and Skyblocker's Fetchur and Puzzler (LGPL-3.0).
 */
object MiningConditions {
    private val HEAT = Regex("Heat:\\s*(\\d+|IMMUNE)")
    private val COLD = Regex("Cold:\\s*-?(\\d+)")

    /** Heat from the sidebar, 0 when immune, or null when the sidebar shows none. */
    @JvmStatic
    fun heat(sidebar: List<String>): Int? {
        for (line in sidebar) {
            val value = HEAT.find(line)?.groupValues?.get(1) ?: continue
            return if (value == "IMMUNE") 0 else value.toIntOrNull()
        }
        return null
    }

    /** Cold as a positive number from the sidebar (it prints negative), or null when absent. */
    @JvmStatic
    fun cold(sidebar: List<String>): Int? {
        for (line in sidebar) {
            val value = COLD.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            return value
        }
        return null
    }

    /**
     * The overlay's opacity (0..1): nothing at or under the [threshold], rising linearly to [maxAlphaPercent] at 100 cold.
     */
    @JvmStatic
    fun coldAlpha(cold: Int, threshold: Int, maxAlphaPercent: Int): Float {
        if (threshold >= 100) return 0f
        val share = (cold - threshold).toFloat() / (100 - threshold)
        return share.coerceIn(0f, 1f) * (maxAlphaPercent.coerceIn(0, 100) / 100f)
    }

    /** The wolf pant sound that plays at high heat in the Crystal Hollows depths. */
    @JvmStatic
    fun isHeatSound(soundId: String, volume: Float, pitch: Float): Boolean =
        soundId.endsWith("entity.wolf.pant") && pitch == 0.0f && volume == 1.0f

    @JvmStatic
    fun isGoldenGoblinSpawn(plain: String): Boolean = plain.trim() == "A Golden Goblin has spawned!"

    @JvmStatic
    fun isDiamondGoblinSpawn(plain: String): Boolean = plain.trim() == "A Diamond Goblin has spawned!"

    @JvmStatic
    fun isGoblinLabel(name: String): Boolean = name.contains("Golden Goblin") || name.contains("Diamond Goblin")

    @JvmStatic
    fun isSuperprotectronLabel(name: String): Boolean = name.contains("Superprotectron")

    @JvmStatic
    fun isDonExpressoFull(plain: String): Boolean = plain.trim() == "[NPC] Don Expresso: I DON'T FEEL SO GOOD..."

    private val FETCHUR = Regex("^\\[NPC] Fetchur: (?:its|theyre) ([a-zA-Z, \\-]*)$")

    private val FETCHUR_ITEMS = mapOf(
        "yellow and see through" to "Yellow Stained Glass",
        "circular and sometimes moves" to "Compass",
        "expensive minerals" to "Mithril",
        "useful during celebrations" to "Firework Rocket",
        "hot and gives energy" to "Cheap / Decent / Black Coffee",
        "tall and can be opened" to "Any Wooden Door / Iron Door",
        "brown and fluffy" to "Rabbit's Foot",
        "explosive but more than usual" to "Superboom TNT",
        "wearable and grows" to "Pumpkin",
        "shiny and makes sparks" to "Flint and Steel",
        "green and some dudes trade stuff for it" to "Emerald",
        "red and soft" to "Red Wool",
    )

    /** What Fetchur's hint asks for, or the hint itself when it is not a known one; null when [plain] is not his hint. */
    @JvmStatic
    fun fetchurItem(plain: String): String? {
        val hint = FETCHUR.matchEntire(plain.trim())?.groupValues?.get(1) ?: return null
        return FETCHUR_ITEMS[hint] ?: hint
    }

    private val PUZZLER = Regex("^\\[NPC] Puzzler: ([▲▶◀▼]{10})$")

    /** Where Puzzler's ten arrows lead: a block x and z at y 195, walked from the fixed start (181, 135). */
    @JvmStatic
    fun puzzlerSpot(plain: String): Pair<Int, Int>? {
        val arrows = PUZZLER.matchEntire(plain.trim())?.groupValues?.get(1) ?: return null
        var x = 181
        var z = 135
        for (arrow in arrows) {
            when (arrow) {
                '▲' -> z++
                '▼' -> z--
                '◀' -> x++
                '▶' -> x--
            }
        }
        return x to z
    }

    /** A mining event named in the tab list, with the seconds left when the next row says so. */
    class Event(val name: String, val secondsLeft: Long?)

    private val EVENT_NAMES = listOf("GONE WITH THE WIND", "2X POWDER", "DOUBLE POWDER", "GOBLIN RAID", "BETTER TOGETHER", "RAFFLE", "MITHRIL GOURMAND")
    private val ENDS_IN = Regex("(?i)ends in:?\\s*(.+)")
    private val CLOCK = Regex("^(\\d{1,2}):(\\d{2})$")
    private val UNITS = Regex("(\\d+)\\s*([hms])")

    /** The active mining event from the `Event:` tab widget, or null when none or an unknown one is shown. */
    @JvmStatic
    fun miningEvent(tab: List<String?>): Event? {
        val rows = tab.map { ChatText.stripForMatch(it ?: "") }
        val at = rows.indexOfFirst { it.startsWith("Event:", ignoreCase = true) }
        if (at < 0) return null
        val shown = rows[at].substringAfter(':').trim().uppercase()
        val name = EVENT_NAMES.firstOrNull { shown.contains(it) } ?: return null
        val next = rows.getOrNull(at + 1).orEmpty()
        val duration = ENDS_IN.find(next)?.groupValues?.get(1)?.trim()
        return Event(name, duration?.let { seconds(it) })
    }

    @JvmStatic
    fun seconds(text: String): Long? {
        CLOCK.matchEntire(text.trim())?.let { return it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong() }
        var total = 0L
        var any = false
        for (match in UNITS.findAll(text)) {
            any = true
            val value = match.groupValues[1].toLong()
            total += when (match.groupValues[2]) {
                "h" -> value * 3600
                "m" -> value * 60
                else -> value
            }
        }
        return if (any) total else null
    }

    @JvmStatic
    fun eventTitle(name: String): String = name.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
