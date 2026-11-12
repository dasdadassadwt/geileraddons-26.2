package geiler.addons.client.dungeon.odin

import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern

/** Tick-based, chat-triggered boss timers for stable boss-room lines; Java-callable record boundary. */
class DungeonBossTimers {
    private val timers = LinkedHashMap<String, Timer>()
    private var powerTimerStarted = false

    fun onChat(plainMessage: String?) {
        if (plainMessage == null) return
        val line = plainMessage.trim { it <= ' ' }
        when {
            GOLDOR.matcher(line).matches() -> start("Goldor", 60, false)
            CORE_OPEN.matcher(line).matches() -> {
                timers.remove("Goldor")
                timers.remove("Goldor Start")
            }

            STORM_END.matcher(line).matches() -> {
                start("Goldor Start", 64, false)
                timers.remove("Storm Pad")
                timers.remove("Storm")
            }

            STORM_START.matcher(line).matches() -> {
                start("Storm Pad", 20, false)
                start("Storm Lightning", 520, false)
                start("Storm", 0, true)
                powerTimerStarted = false
            }

            !powerTimerStarted && STORM_POWER.matcher(line).matches() -> {
                powerTimerStarted = true
                start("Storm PY", 55, false)
            }

            PROFESSOR.matcher(line).matches() -> start("Professor Freeze", 206, false)
        }
    }

    /** Called on server ticks only while the client has confirmed boss-room context. */
    fun tick(inBoss: Boolean) {
        if (!inBoss) return
        timers.keys.toList().forEach { name ->
            val timer = timers.getValue(name)
            timers[name] = if (timer.countUp) Timer(timer.ticks + 1, true)
            else Timer(maxOf(0, timer.ticks - 1), false)
        }
        val goldor = timers["Goldor"]
        if (goldor != null && goldor.ticks == 0) start("Goldor", 60, false)
        val pad = timers["Storm Pad"]
        if (pad != null && pad.ticks == 0) start("Storm Pad", 20, false)
    }

    fun snapshot(): List<DungeonBossTimers.Reading> = java.util.List.copyOf(
        timers.entries.map { (name, timer) -> DungeonBossTimers.Reading(name, timer.ticks, timer.countUp) },
    )

    fun reset() {
        timers.clear()
        powerTimerStarted = false
    }

    private fun start(id: String, ticks: Int, countUp: Boolean) {
        timers[id] = Timer(ticks, countUp)
    }

    private data class Timer(val ticks: Int, val countUp: Boolean)

    @JvmRecord
    data class Reading(val name: String, val ticks: Int, val countUp: Boolean)

    companion object {
        private val GOLDOR = Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
        private val CORE_OPEN = Pattern.compile("^The Core entrance is opening!$")
        private val STORM_END = Pattern.compile("^\\[BOSS] Storm: I should have known that I stood no chance\\.$")
        private val STORM_START = Pattern.compile("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$")
        private val STORM_POWER = Pattern.compile(
            "^\\[BOSS] Storm: (?:ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$",
        )
        private val PROFESSOR = Pattern.compile("^\\[BOSS] The Professor: Oh\\? You found my Guardians' one weakness\\?$")

        @JvmStatic
        fun formatTicks(ticks: Int, showTicks: Boolean, showSymbol: Boolean): String {
            val safe = maxOf(0, ticks)
            return if (showTicks) safe.toString() + (if (showSymbol) "t" else "")
            else String.format(Locale.ROOT, "%.1f%s", safe / 20.0, if (showSymbol) "s" else "")
        }
    }
}
