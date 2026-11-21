package geiler.addons.client.dungeon.helpers

import geiler.addons.client.tree.ChatText
import java.util.EnumMap
import java.util.regex.Pattern

/** Client-observed Bonzo, Spirit, and Phoenix proc activity and cooldown timers. */
class DungeonMaskProcTimers {
    private val timers = EnumMap<Proc, State>(Proc::class.java)

    init {
        Proc.entries.forEach { timers[it] = State(0, 0) }
    }

    /** Returns the proc identified by this exact server chat line, or null when it is unrelated. */
    fun observeChat(plainMessage: String?, inDungeon: Boolean, bonzoCooldownSeconds: Int?): Proc? {
        if (!inDungeon || plainMessage == null) return null

        val message = plainMessage.trim()
        val proc = when {
            BONZO.matcher(message).matches() -> Proc.BONZO
            SPIRIT.matcher(message).matches() -> Proc.SPIRIT
            PHOENIX.matcher(message).matches() -> Proc.PHOENIX
            else -> return null
        }
        val cooldown = if (proc == Proc.BONZO && bonzoCooldownSeconds != null && bonzoCooldownSeconds > 0) {
            bonzoCooldownSeconds
        } else {
            proc.defaultCooldownSeconds
        }
        timers[proc] = State(proc.activeTicks, cooldown * TICKS_PER_SECOND)
        return proc
    }

    /** Call once for each server tick while connected to the observed dungeon instance. */
    fun tick(inDungeon: Boolean) {
        if (!inDungeon) return
        Proc.entries.forEach { proc ->
            val state = timers.getValue(proc)
            timers[proc] = State(maxOf(0, state.activeTicks - 1), maxOf(0, state.cooldownTicks - 1))
        }
    }

    fun reading(proc: Proc?): Reading? {
        if (proc == null) return null
        val state = timers.getValue(proc)
        return Reading(proc, state.activeTicks, state.cooldownTicks)
    }

    fun snapshot(showBonzo: Boolean, showSpirit: Boolean, showPhoenix: Boolean): List<Reading> =
        buildList(3) {
            Proc.entries.forEach { proc ->
                val enabled = when (proc) {
                    Proc.BONZO -> showBonzo
                    Proc.SPIRIT -> showSpirit
                    Proc.PHOENIX -> showPhoenix
                }
                if (enabled) add(reading(proc)!!)
            }
        }

    fun reset() {
        Proc.entries.forEach { timers[it] = State(0, 0) }
    }

    enum class Proc(val activeTicks: Int, val defaultCooldownSeconds: Int) {
        SPIRIT(60, 30), BONZO(60, 180), PHOENIX(80, 60),
    }

    @JvmRecord
    data class Reading(val proc: Proc, val activeTicks: Int, val cooldownTicks: Int)

    private data class State(val activeTicks: Int, val cooldownTicks: Int)

    companion object {
        private const val TICKS_PER_SECOND = 20
        private val BONZO = Pattern.compile("^Your (?:. )?Bonzo's Mask saved your life!$")
        private val SPIRIT = Pattern.compile("^Second Wind Activated! Your Spirit Mask saved your life!$")
        private val PHOENIX = Pattern.compile("^Your Phoenix Pet saved you from certain death!$")
        private val COOLDOWN_LORE = Pattern.compile("^Cooldown: (\\d+)s$")

        /** Reads the last cooldown line in item lore and rejects implausible values. */
        @JvmStatic
        fun bonzoCooldownSeconds(loreLines: List<String?>?): Int? {
            if (loreLines == null) return null
            for (index in loreLines.indices.reversed()) {
                val line = loreLines[index] ?: continue
                val matcher = COOLDOWN_LORE.matcher(ChatText.stripFormattingCodes(line).trim())
                if (matcher.matches()) {
                    val seconds = matcher.group(1).toIntOrNull() ?: return null
                    return seconds.takeIf { it in 1..3600 }
                }
            }
            return null
        }
    }
}
