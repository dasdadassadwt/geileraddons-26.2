package geiler.addons.client.farming

/** Counts completed Slayer bosses and time spent on active quests for session and lifetime rates. */
class SlayerHourlyStats {
    private var sessionKills = 0
    private var totalKills = 0
    private var sessionActiveNanos = 0L
    private var totalActiveNanos = 0L
    private var lastTickNanos = 0L
    private var active = false

    fun tick(questActive: Boolean, nowNanos: Long) {
        if (!questActive) {
            active = false
            lastTickNanos = 0L
            return
        }
        if (!active) {
            active = true
            lastTickNanos = nowNanos
            return
        }
        val delta = nowNanos - lastTickNanos
        lastTickNanos = nowNanos
        if (delta <= 0L) return
        sessionActiveNanos = saturatedAdd(sessionActiveNanos, delta)
        totalActiveNanos = saturatedAdd(totalActiveNanos, delta)
    }

    fun recordKill(nowNanos: Long) {
        if (sessionKills < Int.MAX_VALUE) sessionKills++
        if (totalKills < Int.MAX_VALUE) totalKills++
        if (active) tick(true, nowNanos)
    }

    fun restoreTotal(kills: Int, activeNanos: Long) {
        totalKills = Math.max(0, kills)
        totalActiveNanos = Math.max(0L, activeNanos)
        sessionKills = 0
        sessionActiveNanos = 0L
        lastTickNanos = 0L
        active = false
    }

    fun resetSession(nowNanos: Long) {
        sessionKills = 0
        sessionActiveNanos = 0L
        lastTickNanos = if (active) nowNanos else 0L
    }

    fun stop() {
        active = false
        lastTickNanos = 0L
    }

    fun sessionKills(): Int = sessionKills
    fun totalKills(): Int = totalKills
    fun sessionActiveNanos(): Long = sessionActiveNanos
    fun totalActiveNanos(): Long = totalActiveNanos
    fun sessionPerHour(): Double = perHour(sessionKills, sessionActiveNanos)
    fun totalPerHour(): Double = perHour(totalKills, totalActiveNanos)

    private fun perHour(kills: Int, nanos: Long): Double =
        if (kills <= 0 || nanos <= 0L) 0.0 else kills.toDouble() * NANOS_PER_HOUR.toDouble() / nanos.toDouble()

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    companion object {
        private const val NANOS_PER_HOUR = 3_600_000_000_000L
    }
}
