package geiler.addons.client.foraging

import geiler.addons.client.tree.ChatText
import java.util.Locale

/**
 * Forest and Desert Whispers, Foraging's counterpart of mining powder. The amounts come from the tab-list rows
 * `Forest Whispers: 12,345` and `Desert Whispers: 6,789` (the regexes SkyHanni's repo uses for the same widget).
 * Unknown means null, never zero.
 */
object ForagingWhispers {
    class Reading(val forest: Long?, val desert: Long?)

    private val ROW = Regex("^(Forest|Desert) Whispers:\\s*([\\d,]+)$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun read(lines: List<String?>?): Reading? {
        if (lines.isNullOrEmpty()) return null
        var forest: Long? = null
        var desert: Long? = null
        for (line in lines) {
            val match = ROW.matchEntire(ChatText.stripForMatch(line ?: "")) ?: continue
            val amount = match.groupValues[2].replace(",", "").toLongOrNull() ?: continue
            if (match.groupValues[1].lowercase(Locale.ROOT) == "forest") forest = amount else desert = amount
        }
        return if (forest == null && desert == null) null else Reading(forest, desert)
    }

    /** Session gain from successive readings: only growth counts, so spending on the Heart of the Forest is no loss. */
    class Session {
        private var last: Reading? = null
        private var forest = 0L
        private var desert = 0L
        private var startNanos = Long.MIN_VALUE

        fun observe(now: Reading, nanos: Long) {
            val previous = last
            if (previous == null) startNanos = nanos
            else {
                forest += grew(previous.forest, now.forest)
                desert += grew(previous.desert, now.desert)
            }
            last = now
        }

        private fun grew(before: Long?, after: Long?): Long = if (before == null || after == null) 0 else (after - before).coerceAtLeast(0)

        fun gainForest(): Long = forest
        fun gainDesert(): Long = desert

        fun perHour(gain: Long, nowNanos: Long): Long {
            if (startNanos == Long.MIN_VALUE) return 0
            val elapsed = nowNanos - startNanos
            if (elapsed < MIN_ELAPSED_NANOS) return 0
            return Math.round(gain * 3_600_000_000_000.0 / elapsed)
        }

        fun reset() {
            last = null
            forest = 0
            desert = 0
            startNanos = Long.MIN_VALUE
        }

        private companion object {
            /** Under a few seconds the rate is noise, so report zero rather than a wild figure. */
            const val MIN_ELAPSED_NANOS = 5_000_000_000L
        }
    }

    @JvmStatic
    fun lines(reading: Reading, session: Session, nowNanos: Long, showGain: Boolean, showPerHour: Boolean): List<String> {
        val out = ArrayList<String>(2)
        reading.forest?.let { out.add(line("§2Forest", it, session.gainForest(), session, nowNanos, showGain, showPerHour)) }
        reading.desert?.let { out.add(line("§6Desert", it, session.gainDesert(), session, nowNanos, showGain, showPerHour)) }
        return out
    }

    private fun line(label: String, amount: Long, gain: Long, session: Session, nowNanos: Long, showGain: Boolean, showPerHour: Boolean): String {
        val extra = ArrayList<String>(2)
        if (showGain) extra.add("+" + count(gain))
        if (showPerHour) extra.add(count(session.perHour(gain, nowNanos)) + "/h")
        val tail = if (extra.isEmpty()) "" else " §7(" + extra.joinToString(" | ") + ")"
        return "$label §f${count(amount)}$tail"
    }

    private fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value)
}
