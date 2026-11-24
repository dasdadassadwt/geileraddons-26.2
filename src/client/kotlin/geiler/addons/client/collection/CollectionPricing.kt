package geiler.addons.client.collection

import geiler.addons.client.pricing.BazaarProduct
import java.util.Locale

/** Unit values for the Collection Tracker's profit view. Every value is an estimate from the last downloaded data. */
object CollectionPricing {
    /** A unit price and where it came from; null price means no data for this item. */
    class Quote(val price: Double?, val source: String)

    /**
     * Instant Sell uses the top buy order (what selling right now pays), Sell Offer the lowest sell order (what a
     * listed offer would ask), Lowest BIN the cheapest auction. Auto takes the Bazaar instant-sell price and falls back
     * to lowest BIN for items the Bazaar does not trade.
     */
    @JvmStatic
    fun quote(id: String, mode: CollectionTracker.PriceMode, bazaar: Map<String, BazaarProduct>?, bins: Map<String, Double>?): Quote {
        val product = bazaar?.get(id)
        return when (mode) {
            CollectionTracker.PriceMode.INSTANT_SELL -> Quote(product?.sellPrice, "Bazaar instant sell")
            CollectionTracker.PriceMode.SELL_OFFER -> Quote(product?.buyPrice, "Bazaar sell offer")
            CollectionTracker.PriceMode.LOWEST_BIN -> Quote(bins?.get(id), "Lowest BIN")
            CollectionTracker.PriceMode.AUTO ->
                if (product?.sellPrice != null) Quote(product.sellPrice, "Bazaar instant sell") else Quote(bins?.get(id), "Lowest BIN")
        }
    }

    /** Short coin text: 1.2k, 3.4M, 5.6B. */
    @JvmStatic
    fun coins(value: Double): String {
        val abs = Math.abs(value)
        return when {
            abs >= 1e9 -> String.format(Locale.ROOT, "%.2fB", value / 1e9)
            abs >= 1e6 -> String.format(Locale.ROOT, "%.2fM", value / 1e6)
            abs >= 1e3 -> String.format(Locale.ROOT, "%.1fk", value / 1e3)
            else -> String.format(Locale.ROOT, "%.0f", value)
        }
    }

    /** Duration as 1h 05m, 12m 30s or 45s. */
    @JvmStatic
    fun duration(ms: Long): String {
        val seconds = ms / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format(Locale.ROOT, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.ROOT, "%dm %02ds", m, s)
            else -> "${s}s"
        }
    }

    /** Older price data is shown, but marked stale, after this long. */
    const val STALE_MS = 15 * 60_000L
}
