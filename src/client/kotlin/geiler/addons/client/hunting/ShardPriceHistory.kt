package geiler.addons.client.hunting

/**
 * Shard prices the mod saw, one bucket per UTC day per shard, for the 3-day and 7-day averages. Hypixel's bazaar
 * endpoint has no history, so the averages cover only the days this client fetched prices. Bounded to [MAX_DAYS] days
 * and [MAX_SHARDS] shards.
 */
class ShardPriceHistory {
    class Day(@JvmField var day: Long = 0, @JvmField var instant: Double = 0.0, @JvmField var offer: Double = 0.0, @JvmField var samples: Int = 0)

    /** Gson-backed: shard id to its days, oldest first. */
    @JvmField var shards: MutableMap<String, MutableList<Day>> = LinkedHashMap()

    fun record(prices: Map<String, ShardPrice>, nowMillis: Long) {
        val today = nowMillis / DAY_MILLIS
        for ((id, price) in prices) {
            if (price.instantSell <= 0.0 && price.sellOffer <= 0.0) continue
            if (id !in shards && shards.size >= MAX_SHARDS) continue
            val days = shards.getOrPut(id) { ArrayList() }
            val bucket = days.lastOrNull()?.takeIf { it.day == today } ?: Day(today).also { days.add(it); days.sortBy { d -> d.day } }
            bucket.instant += price.instantSell
            bucket.offer += price.sellOffer
            bucket.samples++
            while (days.size > MAX_DAYS) days.removeAt(0)
        }
    }

    /** Mean of the daily means over the last [days] days including today; NaN without samples. */
    fun average(id: String, days: Int, nowMillis: Long, offers: Boolean): Double {
        val from = nowMillis / DAY_MILLIS - (days - 1)
        val means = shards[id].orEmpty().filter { it.day >= from && it.samples > 0 }
            .map { (if (offers) it.offer else it.instant) / it.samples }
        return if (means.isEmpty()) Double.NaN else means.average()
    }

    fun days(id: String): Int = shards[id]?.size ?: 0

    companion object {
        const val MAX_DAYS = 8
        const val MAX_SHARDS = 400
        private const val DAY_MILLIS = 86_400_000L
    }
}
