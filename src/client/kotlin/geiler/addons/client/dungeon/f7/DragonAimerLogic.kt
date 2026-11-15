package geiler.addons.client.dungeon.f7

/** Median round-trip time over the last 30 seconds of ping samples (Devonian's `Ping.getMedianPing`). */
class PingTracker {
    private class Sample(val timeMillis: Long, val ping: Double)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun add(ping: Double, nowMillis: Long) {
        if (!ping.isFinite() || ping < 0 || ping > 1.0e6) return
        samples.addLast(Sample(nowMillis, ping))
        prune(nowMillis)
    }

    @Synchronized
    fun median(nowMillis: Long): Double {
        prune(nowMillis)
        if (samples.isEmpty()) return 0.0
        val sorted = samples.map { it.ping }.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else 0.5 * (sorted[mid - 1] + sorted[mid])
    }

    @Synchronized
    fun clear() = samples.clear()

    private fun prune(nowMillis: Long) {
        while (samples.isNotEmpty() && samples.first().timeMillis < nowMillis - WINDOW_MILLIS) samples.removeFirst()
    }

    private companion object {
        const val WINDOW_MILLIS = 30_000L
    }
}

/** HUD text rules of the dragon stack aimer. */
object DragonAimerLogic {
    /** Green when far from the run window, red when it is close; [max] is the full range. */
    @JvmStatic
    fun colorForNumber(num: Double, max: Double): String = when {
        num >= max * 0.75 -> "§2"
        num >= max * 0.50 -> "§e"
        num >= max * 0.25 -> "§6"
        else -> "§4"
    }

    /**
     * Milliseconds until the run should start, as a colored string, "§bNOW" once it is due, or null while there is
     * nothing to show. [remainingTicks] counts to the dragon's spawn, [flightTicks] is the arrow's flight time.
     */
    @JvmStatic
    fun hudText(remainingTicks: Int, flightTicks: Double, pingMillis: Double, offsetMillis: Int): String? {
        if (remainingTicks <= 0) return null
        val ttl = ((remainingTicks - flightTicks) * 50 - pingMillis).toInt() + offsetMillis
        if (ttl < -1000) return null
        if (ttl < 0) return "§bNOW"
        return colorForNumber(ttl.toDouble(), 5000.0) + ttl
    }
}
