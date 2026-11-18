package geiler.addons.client.slayer

import java.util.Locale

/** Reading a Slayer boss label (`☠ Voidgloom Seraph 12M❤`) and printing health and durations for the Slayer HUD. */
object SlayerHudText {
    private val HEALTH = Regex("(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kKmMbB]?)❤")

    /** The last health token in [label] as a number, or null when it has none. Unverified against live labels. */
    @JvmStatic
    fun parseHealth(label: String?): Long? {
        val match = HEALTH.findAll(label ?: return null).lastOrNull() ?: return null
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val scale = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "k" -> 1e3
            "m" -> 1e6
            "b" -> 1e9
            else -> 1.0
        }
        return Math.round(number * scale)
    }

    /** `12M`, `3.5M`, `850k`, `999`: one decimal at most, no trailing `.0`. */
    @JvmStatic
    fun formatHealth(health: Long): String {
        val (value, unit) = when {
            health >= 1_000_000_000L -> health / 1e9 to "B"
            health >= 1_000_000L -> health / 1e6 to "M"
            health >= 1_000L -> health / 1e3 to "k"
            else -> return health.toString()
        }
        return String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0") + unit
    }

    /** The boss name without its leading symbol and trailing health. */
    @JvmStatic
    fun bossName(label: String): String =
        label.replace(HEALTH, "").trim().trimStart { !it.isLetter() }

    /** `12.3s` under a minute, `1m 05s` after. */
    @JvmStatic
    fun duration(seconds: Float): String =
        if (seconds < 60f) String.format(Locale.ROOT, "%.1fs", seconds)
        else String.format(Locale.ROOT, "%dm %02ds", (seconds / 60).toInt(), (seconds % 60).toInt())
}

/**
 * One Slayer quest's timing and the boss's health, fed the sidebar state each tick. Time runs on the caller's
 * millisecond clock. Spawn time is the quest-active to boss-active gap, known only when the quest was seen first.
 */
class SlayerFight {
    class Result(val spawnSeconds: Float?, val killSeconds: Float)

    private var questStart = -1L
    private var bossStart = -1L
    var health = 0L
        private set
    var maxHealth = 0L
        private set

    fun inBoss(): Boolean = bossStart >= 0

    fun fightSeconds(now: Long): Float = if (inBoss()) (now - bossStart) / 1000f else 0f

    /** Advances the fight; returns the timing once, on the tick the sidebar first says the boss is slain. */
    fun update(questActive: Boolean, bossActive: Boolean, bossSlain: Boolean, bossHealth: Long?, now: Long): Result? {
        if (bossSlain) {
            if (!inBoss()) return null
            val result = Result(
                if (questStart >= 0) (bossStart - questStart) / 1000f else null,
                (now - bossStart) / 1000f,
            )
            clear()
            return result
        }
        if (bossActive) {
            if (!inBoss()) {
                bossStart = now
                health = 0
                maxHealth = 0
            }
            if (bossHealth != null) {
                health = bossHealth
                maxHealth = maxOf(maxHealth, bossHealth)
            }
        } else if (questActive) {
            if (inBoss()) clear()
            if (questStart < 0) questStart = now
        } else {
            clear()
        }
        return null
    }

    fun clear() {
        questStart = -1L
        bossStart = -1L
        health = 0
        maxHealth = 0
    }
}
