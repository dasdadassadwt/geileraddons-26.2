package geiler.addons.client.collection

import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.TextSetting
import java.util.EnumMap

/** One Collection Tracker instance: its own name, items, islands, price and HUD settings, and its counts. */
class CollectionTracker(val id: Int, name: String) {
    enum class PriceMode(val label: String) {
        INSTANT_SELL("Instant Sell"),
        SELL_OFFER("Sell Offer"),
        LOWEST_BIN("Lowest BIN"),
        AUTO("Bazaar, else BIN");

        companion object {
            @JvmStatic
            fun of(label: String?): PriceMode = entries.firstOrNull { it.label == label } ?: INSTANT_SELL
        }
    }

    val enabled = BooleanSetting("Tracker Enabled", true)
    val name = TextSetting("Tracker Name", "Rename Tracker", name, 40)
    val showTotals = BooleanSetting("Show Totals", "Show Totals (off: Session)", false)
    val showRates = BooleanSetting("Show Rates", true)
    val showProfit = BooleanSetting("Show Profit", true)
    val showItemValues = BooleanSetting("Show Item Values", false)
    val priceMode = ChoiceSetting("Price Mode", PriceMode.INSTANT_SELL.label, *PriceMode.entries.map { it.label }.toTypedArray())
    val idleSeconds = NumberSetting("Idle Hide", "Hide After Idle (s)", 10f, 1800f, 120f, true)
    val background = BooleanSetting("Background", true)
    val islandFilter = BooleanSetting("Island Filter", false)
    val islands = EnumMap<Island, BooleanSetting>(Island::class.java).apply {
        for (island in Island.values()) if (island.selectable()) put(island, BooleanSetting(island.label(), false))
    }

    /** Tracked item ids (NEU ids, as the item list uses), in the order they were added. */
    val items = ArrayList<String>()
    var folderId: String? = null
    val stats = CollectionStats()
    var lastGainMs = 0L

    fun priceMode(): PriceMode = PriceMode.of(priceMode.value())

    /** Allowed here: no island filter, or the current island is ticked. Never on an unknown island while filtered. */
    fun allowedOn(island: Island): Boolean =
        !islandFilter.value() || (island != Island.NONE && islands[island]?.value() == true)

    fun selectedIslands(): List<Island> = islands.filterValues { it.value() }.keys.toList()

    /** Active: enabled, allowed on this island, and a tracked item arrived within the idle window. */
    fun active(island: Island, nowMs: Long): Boolean =
        enabled.value() && allowedOn(island) && lastGainMs > 0L && nowMs - lastGainMs < idleSeconds.value().toLong() * 1000L
}
