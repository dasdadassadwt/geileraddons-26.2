package geiler.addons.client.farming

/** Counts pest kills and Garden-active time for session and current-client hourly rates. */
class PestHourlyStats {
    private var sessionKills = 0
    private var totalKills = 0
    private var sessionGardenNanos = 0L
    private var totalGardenNanos = 0L
    private var lastTickNanos = 0L
    private var inGarden = false

    fun tick(garden: Boolean, nowNanos: Long) {
        if (!garden) {
            inGarden = false
            lastTickNanos = 0L
            return
        }
        if (!inGarden) {
            inGarden = true
            lastTickNanos = nowNanos
            sessionKills = 0
            sessionGardenNanos = 0L
            return
        }
        val delta = nowNanos - lastTickNanos
        lastTickNanos = nowNanos
        if (delta <= 0L) return
        sessionGardenNanos = saturatedAdd(sessionGardenNanos, delta)
        totalGardenNanos = saturatedAdd(totalGardenNanos, delta)
    }

    fun recordKill(nowNanos: Long) {
        if (!inGarden) tick(true, nowNanos)
        sessionKills = increment(sessionKills)
        totalKills = increment(totalKills)
    }

    /** Restores only lifetime fields; a new client launch always starts a fresh session rate. */
    fun restoreTotal(kills: Int, gardenNanos: Long) {
        totalKills = maxOf(0, kills)
        totalGardenNanos = maxOf(0L, gardenNanos)
        sessionKills = 0
        sessionGardenNanos = 0L
        lastTickNanos = 0L
        inGarden = false
    }

    /** Resets the visible session counters without changing durable lifetime totals. */
    fun resetSession(nowNanos: Long) {
        sessionKills = 0
        sessionGardenNanos = 0L
        lastTickNanos = if (inGarden) nowNanos else 0L
    }

    fun sessionKills(): Int = sessionKills

    fun totalKills(): Int = totalKills

    fun sessionKillsPerHour(): Double = perHour(sessionKills, sessionGardenNanos)

    fun totalKillsPerHour(): Double = perHour(totalKills, totalGardenNanos)

    fun sessionGardenNanos(): Long = sessionGardenNanos

    fun totalGardenNanos(): Long = totalGardenNanos

    companion object {
        private const val NANOS_PER_HOUR = 3_600_000_000_000L

        private fun perHour(kills: Int, elapsedNanos: Long): Double {
            if (kills <= 0 || elapsedNanos <= 0L) return 0.0
            return kills.toDouble() * NANOS_PER_HOUR.toDouble() / elapsedNanos.toDouble()
        }

        private fun saturatedAdd(left: Long, right: Long): Long {
            if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE
            return left + right
        }

        private fun increment(value: Int): Int =
            if (value == Int.MAX_VALUE) value else value + 1
    }
}
