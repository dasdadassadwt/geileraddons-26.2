package geiler.addons.client.dungeon.f7

import java.util.random.RandomGenerator

/** The parts of Auto i4 without Minecraft types: the bow's shot cooldown from its lore, and when to shoot. */
object AutoI4Rules {
    /** Used when the held bow names no cooldown; a Terminator's is 0.3 s at base. */
    const val DEFAULT_COOLDOWN_MS = 300L

    /** Shooter keys of prefire spots start here, so they never equal a lit panel index (0..8). */
    const val PREFIRE_KEY_OFFSET = 100

    /** The shooter key of prefire spot [ordinal] (0 or 1) of panel [panel]. */
    @JvmStatic
    fun prefireKey(panel: Int, ordinal: Int): Int = PREFIRE_KEY_OFFSET + panel * 2 + ordinal

    private val COOLDOWN = Regex("""Shot Cooldown: (\d+(?:\.\d+)?)s""")

    /** The shot cooldown named in the lore lines of the held bow, in milliseconds. */
    @JvmStatic
    fun cooldownMs(lore: List<String>): Long {
        for (line in lore) {
            val seconds = COOLDOWN.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            return (seconds * 1000.0).toLong().coerceIn(50L, 5_000L)
        }
        return DEFAULT_COOLDOWN_MS
    }

    /**
     * When to shoot. A new target waits a reaction delay drawn between the two values; a shot needs the view on the aim
     * spot and the bow off cooldown. After a shot at the same target (a miss), the next waits a fresh delay again.
     */
    class Shooter(private val random: RandomGenerator) {
        private var target = -1
        private var fireAtMs = 0L
        private var lastShotMs = Long.MIN_VALUE / 2
        private var lastDelay = 0L

        fun lastDelayMs(): Long = lastDelay

        fun reset() {
            target = -1
        }

        /** True when the shot should be sent now. */
        fun update(index: Int, aimed: Boolean, nowMs: Long, minMs: Long, maxMs: Long, cooldownMs: Long): Boolean {
            if (index < 0) {
                target = -1
                return false
            }
            if (index != target) {
                target = index
                draw(nowMs, minMs, maxMs)
            }
            if (!aimed || nowMs < fireAtMs || nowMs - lastShotMs < cooldownMs) return false
            lastShotMs = nowMs
            draw(nowMs, minMs, maxMs)
            return true
        }

        private fun draw(nowMs: Long, minMs: Long, maxMs: Long) {
            lastDelay = LeverAimRules.drawDelayMs(minMs, maxMs, random.nextDouble())
            fireAtMs = nowMs + lastDelay
        }
    }
}
