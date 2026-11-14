package geiler.addons.client.module.impl

import java.util.Locale
import java.util.regex.Pattern

/** Cooldown timers keyed by SkyBlock item id, started from an ability use and read back per slot or per HUD row. */
class ItemCooldownTracker {
    class Entry(val key: String, val label: String, val startMs: Long, val totalMs: Long) {
        val endMs: Long get() = startMs + totalMs
    }

    /** What a slot or row shows right now: the remaining time or the ready check. */
    data class Reading(val key: String, val label: String, val remainingMs: Long, val totalMs: Long, val ready: Boolean)

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Starts [key]'s cooldown from a use. A use while the previous cooldown still runs is ignored (holding right click
     * repeats the use; the server refuses it anyway), so this only starts when nothing runs. Returns whether it started.
     */
    fun start(key: String, label: String, totalMs: Long, nowMs: Long): Boolean {
        if (key.isEmpty() || totalMs <= 0L) return false
        if (isRunning(key, nowMs)) return false
        put(key, label, nowMs, totalMs)
        return true
    }

    /** Restarts [key] from [startMs] even while it runs, e.g. when the action bar names a different ability. */
    fun replace(key: String, label: String, totalMs: Long, startMs: Long) {
        if (key.isEmpty() || totalMs <= 0L) return
        entries.remove(key)
        put(key, label, startMs, totalMs)
    }

    /** Sets [key]'s remaining time exactly, as the server's "on cooldown for Ns" message states it. */
    fun setRemaining(key: String, remainingMs: Long, nowMs: Long) {
        if (key.isEmpty() || remainingMs <= 0L) return
        val previous = entries[key]
        val total = maxOf(previous?.totalMs ?: 0L, remainingMs)
        entries.remove(key)
        put(key, previous?.label ?: "", nowMs + remainingMs - total, total)
    }

    fun cancel(key: String) {
        entries.remove(key)
    }

    fun isRunning(key: String, nowMs: Long): Boolean = (entries[key]?.endMs ?: Long.MIN_VALUE) > nowMs

    private fun put(key: String, label: String, startMs: Long, totalMs: Long) {
        if (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        entries[key] = Entry(key, label.take(MAX_LABEL), startMs, totalMs)
    }

    /** The reading for [key], or null when it was never used or its ready check has expired. */
    fun reading(key: String, nowMs: Long, checkMs: Long): Reading? {
        val entry = entries[key] ?: return null
        val remaining = entry.endMs - nowMs
        if (remaining > 0L) return Reading(key, entry.label, remaining, entry.totalMs, false)
        if (-remaining > checkMs) {
            entries.remove(key)
            return null
        }
        return Reading(key, entry.label, 0L, entry.totalMs, true)
    }

    /** Every live reading in the order the abilities were used; expired ready checks are dropped. */
    fun readings(nowMs: Long, checkMs: Long): List<Reading> =
        entries.keys.toList().mapNotNull { reading(it, nowMs, checkMs) }

    fun size(): Int = entries.size

    fun clear() = entries.clear()

    companion object {
        const val MAX_ENTRIES = 64

        private const val MAX_LABEL = 48

        private val ACTION_BAR = Pattern.compile("Mana \\(([^)]+)\\)")
        private val ABILITY_LINE = Pattern.compile("^Ability: (.+?)\\s+RIGHT CLICK\\s*$")
        private val ON_COOLDOWN = Pattern.compile("on cooldown for (\\d+(?:\\.\\d+)?)s")
        private val COOLDOWN = Pattern.compile("^Cooldown: (\\d+(?:\\.\\d+)?)s$")

        /** The ability name in an action-bar mana cost such as `-50 Mana (Instant Transmission)`, or null. */
        @JvmStatic
        fun abilityFromActionBar(plain: String?): String? {
            if (plain == null) return null
            val matcher = ACTION_BAR.matcher(plain)
            return if (matcher.find()) matcher.group(1).trim() else null
        }

        /** One right-click ability of an item and the cooldown its lore states. */
        data class Ability(val name: String, val seconds: Double)

        /** Every `Ability: <name>  RIGHT CLICK` in [lore] that is followed by its own `Cooldown: Ns`, in lore order. */
        @JvmStatic
        fun rightClickAbilities(lore: List<String>): List<Ability> {
            val found = ArrayList<Ability>()
            for ((index, raw) in lore.withIndex()) {
                val matcher = ABILITY_LINE.matcher(raw.trim { it <= ' ' })
                if (!matcher.matches()) continue
                val name = matcher.group(1).trim()
                val seconds = cooldownAfter(lore, index) ?: continue
                if (seconds > 0.0) found.add(Ability(name, seconds))
            }
            return found
        }

        /** Seconds from the server's `This ability is on cooldown for Ns.` message, or null. */
        @JvmStatic
        fun cooldownFromChat(plain: String?): Double? {
            if (plain == null) return null
            val matcher = ON_COOLDOWN.matcher(plain)
            return if (matcher.find()) matcher.group(1).toDoubleOrNull() else null
        }

        private fun cooldownAfter(lore: List<String>, abilityIndex: Int): Double? {
            for (index in abilityIndex + 1 until lore.size) {
                val line = lore[index].trim { it <= ' ' }
                if (line.startsWith("Ability: ")) return null
                val matcher = COOLDOWN.matcher(line)
                if (matcher.matches()) return matcher.group(1).toDoubleOrNull()
            }
            return null
        }

        /** The `Cooldown: Ns` that follows the `Ability: <name>` line in [lore], in seconds, or null. */
        @JvmStatic
        fun cooldownSeconds(lore: List<String>, ability: String): Double? {
            val start = lore.indexOfFirst { it.startsWith("Ability: ") && it.contains(ability) }
            return if (start < 0) null else cooldownAfter(lore, start)
        }

        /** Whole seconds from 10 s up, one decimal below, e.g. `42` and `4.2`. */
        @JvmStatic
        fun format(remainingMs: Long): String {
            val ms = maxOf(0L, remainingMs)
            return if (ms >= 10_000L) ((ms + 999L) / 1000L).toString()
            else String.format(Locale.ROOT, "%.1f", ms / 1000.0)
        }
    }
}
