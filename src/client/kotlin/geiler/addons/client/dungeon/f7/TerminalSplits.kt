package geiler.addons.client.dungeon.f7

import java.util.Locale

/**
 * Times the Goldor phase's terminal sections from its chat lines, after Odin's Terminal Times splits (BSD 3-Clause;
 * behavior only, written independently). Real time only: the caller passes the clock reading in milliseconds.
 * Names are `\w{1,16}`, stricter than Odin's `.{1,16}`, to match the section tracker.
 */
class TerminalSplits {
    /** Seconds since the current section started and since the phase started, for a completion line. */
    class Split(val sectionSeconds: Float, val phaseSeconds: Float)

    /** The finished sections and the phase total, printed when the core opens. */
    class Summary(val sectionTimes: List<Float>, val totalSeconds: Float)

    class Observation(val split: Split?, val summary: Summary?)

    private var started = false
    private var completed = 0
    private var total = DEFAULT_TOTAL
    private var gateBlown = false
    private var sectionStart = 0L
    private var phaseStart = 0L
    private val times = ArrayList<Float>()

    /** Forgets the phase; nothing is timed until the Goldor line is seen again. */
    fun clear() {
        started = false
        times.clear()
        resetSection(0L)
    }

    fun observe(message: String, now: Long): Observation {
        if (message == GOLDOR) {
            clear()
            started = true
            phaseStart = now
            sectionStart = now
            return NONE
        }
        if (!started) return NONE
        if (message == GATE) {
            if (completed == total) resetSection(now) else gateBlown = true
            return NONE
        }
        if (message == CORE) {
            resetSection(now)
            val summary = Summary(ArrayList(times), seconds(phaseStart, now))
            return Observation(null, summary)
        }
        val match = TASK.matchEntire(message) ?: return NONE
        val current = match.groupValues[1].toInt()
        val of = match.groupValues[2].toInt()
        val split = Split(seconds(sectionStart, now), seconds(phaseStart, now))
        if ((current == of && gateBlown) || current < completed) {
            resetSection(now)
        } else {
            completed = current
            total = of
        }
        return Observation(split, null)
    }

    private fun resetSection(now: Long) {
        if (started) times.add(seconds(sectionStart, now))
        completed = 0
        total = DEFAULT_TOTAL
        sectionStart = now
        gateBlown = false
    }

    private fun seconds(from: Long, to: Long): Float = (to - from) / 1000f

    companion object {
        private const val DEFAULT_TOTAL = 7
        private const val GOLDOR = "[BOSS] Goldor: Who dares trespass into my domain?"
        private const val GATE = "The gate has been destroyed!"
        private const val CORE = "The Core entrance is opening!"
        private val TASK = Regex("^\\w{1,16} (?:activated|completed) a (?:terminal|lever|device)! \\((\\d)/(\\d)\\)$")
        private val NONE = Observation(null, null)

        /** Two decimals, whatever the machine locale. */
        @JvmStatic
        fun seconds(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
    }
}
