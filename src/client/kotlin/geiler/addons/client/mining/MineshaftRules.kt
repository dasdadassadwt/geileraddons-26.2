package geiler.addons.client.mining

import java.util.Locale

/**
 * Glacite Mineshaft facts, after SkyHanni's MineshaftDetection and CorpseFinder (LGPL-2.1): which shaft variant the
 * scoreboard names, which corpse a helmet marks, and the tab-list corpse rows. Pure text, so it runs offline.
 */
object MineshaftRules {
    /** The shaft codes the scoreboard shows, with their readable names. */
    private val SHAFT_NAMES: Map<String, String> = linkedMapOf(
        "TOPA_1" to "Topaz 1", "TOPA_2" to "Topaz 2", "SAPP_1" to "Sapphire 1", "SAPP_2" to "Sapphire 2",
        "AMET_1" to "Amethyst 1", "AMET_2" to "Amethyst 2", "AMBE_1" to "Amber 1", "AMBE_2" to "Amber 2",
        "JADE_1" to "Jade 1", "JADE_2" to "Jade 2", "TITA_1" to "Titanium", "UMBE_1" to "Umber",
        "TUNG_1" to "Tungsten", "FAIR_1" to "Vanguard", "RUBY_1" to "Ruby 1", "RUBY_2" to "Ruby 2",
        "RUBY_C" to "Ruby Crystal", "ONYX_1" to "Onyx 1", "ONYX_2" to "Onyx 2", "ONYX_C" to "Onyx Crystal",
        "AQUA_1" to "Aquamarine 1", "AQUA_2" to "Aquamarine 2", "AQUA_C" to "Aquamarine Crystal",
        "CITR_1" to "Citrine 1", "CITR_2" to "Citrine 2", "CITR_C" to "Citrine Crystal",
        "PERI_1" to "Peridot 1", "PERI_2" to "Peridot 2", "PERI_C" to "Peridot Crystal",
        "JASP_1" to "Jasper", "JASP_C" to "Jasper Crystal", "OPAL_1" to "Opal", "OPAL_C" to "Opal Crystal",
        "LITT_L" to "Littlefoot's Den",
    )

    /** The shaft the sidebar names, as its readable name, or null when no line carries a shaft code. */
    @JvmStatic
    fun shaftName(sidebar: List<String>): String? {
        for (line in sidebar) {
            for ((code, name) in SHAFT_NAMES) if (line.contains(code)) return name
        }
        return null
    }

    enum class Corpse(val label: String, val helmetId: String, val argb: Int, val legacyColor: String) {
        LAPIS("Lapis", "LAPIS_ARMOR_HELMET", 0xFF5555FF.toInt(), "§9"),
        TUNGSTEN("Tungsten", "MINERAL_HELMET", 0xFFAAAAAA.toInt(), "§7"),
        UMBER("Umber", "ARMOR_OF_YOG_HELMET", 0xFFFFAA00.toInt(), "§6"),
        VANGUARD("Vanguard", "VANGUARD_HELMET", 0xFFFFFFFF.toInt(), "§f"),
    }

    /** The corpse a helmet's SkyBlock id marks, or null for any other head item. */
    @JvmStatic
    fun corpseForHelmet(skyBlockId: String?): Corpse? = Corpse.entries.firstOrNull { it.helmetId == skyBlockId }

    /** The corpse label parsed from a tab row such as `Lapis`, ignoring case. */
    @JvmStatic
    fun corpseByLabel(label: String): Corpse? = Corpse.entries.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }

    /** `m:ss` for a duration, `h:mm:ss` from an hour up. */
    @JvmStatic
    fun clock(nanos: Long): String {
        val total = (nanos / 1_000_000_000L).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

/** One mineshaft visit: when it started, which variant it was, and the best finished visit so far. */
class MineshaftTimerState {
    private var startNanos = Long.MIN_VALUE
    private var name: String? = null
    private var bestNanos = Long.MAX_VALUE
    private var lastNanos = Long.MIN_VALUE

    fun active(): Boolean = startNanos != Long.MIN_VALUE

    fun enter(nowNanos: Long) {
        if (active()) return
        startNanos = nowNanos
        name = null
    }

    fun setShaft(shaft: String?) {
        if (shaft != null && active()) name = shaft
    }

    fun shaft(): String? = name

    fun elapsed(nowNanos: Long): Long = if (active()) (nowNanos - startNanos).coerceAtLeast(0) else 0

    /** Ends the visit and returns its length, or null if none was running. */
    fun leave(nowNanos: Long): Long? {
        if (!active()) return null
        val length = elapsed(nowNanos)
        startNanos = Long.MIN_VALUE
        lastNanos = length
        if (length < bestNanos) bestNanos = length
        return length
    }

    fun last(): Long? = if (lastNanos == Long.MIN_VALUE) null else lastNanos

    fun best(): Long? = if (bestNanos == Long.MAX_VALUE) null else bestNanos

    fun reset() {
        startNanos = Long.MIN_VALUE
        name = null
        bestNanos = Long.MAX_VALUE
        lastNanos = Long.MIN_VALUE
    }
}
