package geiler.addons.client.hunting

import java.util.Locale

/** One shard's two top-of-book bazaar prices; 0 where the book is empty. */
class ShardPrice(val instantSell: Double, val sellOffer: Double)

/** Which price a shard is valued at. */
enum class PriceMode { INSTANT_SELL, SELL_OFFER, BOTH }

/**
 * What a run's shards are worth: money per run, time per run and money per hour.
 *
 * A catch gives a random number of shards and each species trades for a different price, so value is the
 * shards received (own catches plus loot share) priced one species at a time. Both prices are sell-side,
 * so the bazaar tax comes off both. Pure so the arithmetic can be checked offline.
 */
object ShardValue {
    /** Bazaar tax on the sell side. */
    const val TAX = 0.0125

    /** Coins for [shards] at one price column. Species with no price count as 0, never as an error. */
    @JvmStatic
    fun value(shards: Map<Critter, Int>, prices: Map<String, ShardPrice>, offers: Boolean): Double {
        var total = 0.0
        for ((critter, count) in shards) {
            val price = prices[critter.bazaarId()] ?: continue
            val each = if (offers) price.sellOffer else price.instantSell
            if (each > 0.0 && count > 0) total += each * count * (1.0 - TAX)
        }
        return total
    }

    /** Coins per hour at the current run length; 0 until a full minute has passed, since seconds extrapolate wildly. */
    @JvmStatic
    fun perHour(coins: Double, elapsedMillis: Long): Double {
        if (elapsedMillis < 60_000L || coins <= 0.0) return 0.0
        return coins * 3_600_000.0 / elapsedMillis
    }

    /** `1.2k`, `3.45M`, `1.2B`; whole coins below a thousand. */
    @JvmStatic
    fun coins(value: Double): String {
        val abs = Math.abs(value)
        return when {
            abs >= 1_000_000_000.0 -> String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000.0)
            abs >= 1_000_000.0 -> String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0)
            abs >= 1_000.0 -> String.format(Locale.ROOT, "%.1fk", value / 1_000.0)
            else -> String.format(Locale.ROOT, "%.0f", value)
        }
    }

    /** `42s`, `12m 05s` or `1h 02m 03s`. */
    @JvmStatic
    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return when {
            h > 0 -> String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s)
            m > 0 -> String.format(Locale.ROOT, "%dm %02ds", m, s)
            else -> "${s}s"
        }
    }
}
