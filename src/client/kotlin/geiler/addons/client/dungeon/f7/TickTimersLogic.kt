package geiler.addons.client.dungeon.f7

import java.util.Locale

/**
 * Pure state of Tick Timers: chat starts boss-room countdowns that then move on server ticks. Behavior and
 * constants follow Odin's `TickTimers` (BSD 3-Clause); written independently.
 */
class TickTimersLogic {
    var goldorTick = -1
        private set
    var goldorStart = -1
        private set
    var pad = -1
        private set
    var lightning = -1
        private set
    var py = -1
        private set
    var stormTick = -1
        private set
    private var pyTriggered = false

    fun onChat(message: String) {
        when {
            GOLDOR.matches(message) -> goldorTick = 60
            CORE_OPENING.matches(message) -> {
                goldorStart = -1
                goldorTick = -1
            }
            STORM_END.matches(message) -> {
                goldorStart = 64
                pad = -1
                stormTick = -1
            }
            STORM_START.matches(message) -> {
                pad = 20
                lightning = 520
                stormTick = 0
            }
            !pyTriggered && STORM_PY.matches(message) -> {
                pyTriggered = true
                py = 55
            }
        }
    }

    /** One server tick; [goldorHud] and [padHud] make those two timers repeat as Odin's do. */
    fun onServerTick(inBoss: Boolean, goldorHud: Boolean, padHud: Boolean) {
        if (!inBoss) return
        if (goldorTick == 0 && goldorStart <= 0 && goldorHud) goldorTick = 60
        if (goldorStart >= 0) goldorStart--
        if (goldorTick >= 0) goldorTick--
        if (pad == 0 && padHud) pad = 20
        if (pad >= 0) pad--
        if (lightning >= 0) lightning--
        if (py >= 0) py--
        if (stormTick >= 0) stormTick++
    }

    fun reset() {
        goldorStart = -1
        goldorTick = -1
        pad = -1
        lightning = -1
        py = -1
        pyTriggered = false
        stormTick = -1
    }

    fun anyActive(): Boolean = goldorStart >= 0 || goldorTick >= 0 || pad >= 0 || lightning >= 0 || py >= 0 ||
        stormTick >= 0

    companion object {
        private val GOLDOR = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
        private val CORE_OPENING = Regex("^The Core entrance is opening!$")
        private val STORM_END = Regex("^\\[BOSS] Storm: I should have known that I stood no chance\\.$")
        private val STORM_START = Regex("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$")
        private val STORM_PY = Regex("^\\[BOSS] Storm: (ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$")

        /** Green at two thirds or more of [max], gold at one third or more, red below; [overrideColor] wins. */
        @JvmStatic
        fun format(
            time: Int,
            max: Int,
            prefix: String,
            overrideColor: String?,
            displayInTicks: Boolean,
            symbol: Boolean,
            showPrefix: Boolean,
        ): String {
            val color = overrideColor ?: when {
                time.toFloat() >= max * 0.66 -> "§a"
                time.toFloat() >= max * 0.33 -> "§6"
                else -> "§c"
            }
            val display = if (displayInTicks) "$time${if (symbol) "t" else ""}"
            else String.format(Locale.US, "%.2f", time / 20f) + if (symbol) "s" else ""
            return "${if (showPrefix) "$prefix " else ""}$color$display"
        }
    }
}

/**
 * M3 Fire Freeze countdown (Odin's Tick Timers fire freeze, moved into its own module): the Professor line
 * starts 206 server ticks; the staff is used when 100 remain.
 */
class FireFreezeTimerLogic {
    var ticks = -1
        private set

    fun onChat(message: String) {
        if (PROFESSOR.matches(message)) ticks = START_TICKS
    }

    fun onServerTick(inBoss: Boolean) {
        if (inBoss && ticks >= 0) ticks--
    }

    fun reset() {
        ticks = -1
    }

    /** Countdown to the staff use, "Use Fire Freeze" until the window closes, else null. */
    fun text(displayInTicks: Boolean, symbol: Boolean, showPrefix: Boolean): String? {
        val untilFreeze = ticks - USE_TICKS
        return when {
            untilFreeze > 0 -> TickTimersLogic.format(
                untilFreeze, START_TICKS - USE_TICKS, "Fire Freeze:", null, displayInTicks, symbol, showPrefix,
            )
            ticks >= 0 -> "Use Fire Freeze"
            else -> null
        }
    }

    companion object {
        const val START_TICKS = 206
        const val USE_TICKS = 100
        private val PROFESSOR = Regex("^\\[BOSS] The Professor: Oh\\? You found my Guardians' one weakness\\?$")
    }
}

/**
 * Devonian's Relic Timer: [spawnTicks] server ticks after Necron's death line the relics spawn. The countdown is an
 * absolute target tick (as in Devonian), so skipped or repeated tick updates cannot make it drift.
 */
class RelicTimerLogic {
    private var spawnTicks = SPAWN_TICKS
    private var startedAt = -1L
    private var target = -1L

    fun setSpawnTicks(ticks: Int) {
        spawnTicks = ticks.coerceAtLeast(1)
    }

    fun onChat(message: String, nowTick: Long) {
        if (!NECRON.matches(message)) return
        startedAt = nowTick
        target = nowTick + spawnTicks
    }

    /** Ticks left at [nowTick], 0 once it ended, or -1 while no countdown runs. */
    fun remaining(nowTick: Long): Int = if (target < 0L) -1 else (target - nowTick).coerceAtLeast(0L).toInt()

    /** Server ticks since the Necron line, or -1 while no countdown has started (diagnostics). */
    fun ticksSinceStart(nowTick: Long): Long = if (startedAt < 0L) -1L else nowTick - startedAt

    /** `§a2.25s` while counting, else null. */
    fun text(nowTick: Long): String? {
        val left = remaining(nowTick)
        return if (left <= 0) null else "§a" + String.format(Locale.US, "%.2fs", left * 0.05)
    }

    fun reset() {
        startedAt = -1L
        target = -1L
    }

    companion object {
        const val SPAWN_TICKS = 45
        private val NECRON = Regex("^\\[BOSS] Necron: All this, for nothing\\.\\.\\.$")
    }
}
