package geiler.addons.client.slayer

import java.util.Locale

/**
 * Which phase of a Slayer fight the boss labels show. Only phases with a signal this mod already reads are named: a Blaze
 * boss's shield attunement, and a Bloodfiend's Stake and Twinclaws markers. Other slayers name none.
 */
object SlayerPhaseRules {
    @JvmStatic
    fun phase(family: SlayerRules.Family?, labelTexts: List<String>): String? = when (family) {
        SlayerRules.Family.DEMONLORD ->
            labelTexts.firstNotNullOfOrNull(SlayerRules::attunement)?.name?.lowercase(Locale.ROOT)?.replaceFirstChar { it.uppercase() }
        SlayerRules.Family.VAMPIRE -> when {
            labelTexts.any(SlayerRules::hasStakeMarker) -> "Stake"
            labelTexts.any(SlayerRules::hasTwinclaws) -> "Twinclaws"
            else -> "Normal"
        }
        else -> null
    }
}

/** Time spent in each named phase of one fight, summed over revisits, in first-seen order, on the caller's millisecond clock. */
class SlayerPhaseTimes {
    class Row(val name: String, val seconds: Float)

    private val totals = LinkedHashMap<String, Long>()
    private var current: String? = null
    private var since = 0L

    /** A null [phase] changes nothing: the current phase keeps running. */
    fun update(phase: String?, now: Long) {
        if (phase == null || phase == current) return
        close(now)
        if (phase !in totals && totals.size >= MAX_PHASES) {
            current = null
            return
        }
        totals.putIfAbsent(phase, 0L)
        current = phase
        since = now
    }

    fun rows(now: Long): List<Row> = totals.map { (name, millis) ->
        Row(name, (millis + if (name == current) maxOf(0L, now - since) else 0L) / 1000f)
    }

    fun clear() {
        totals.clear()
        current = null
        since = 0L
    }

    private fun close(now: Long) {
        val name = current ?: return
        totals[name] = (totals[name] ?: 0L) + maxOf(0L, now - since)
        current = null
    }

    companion object {
        const val MAX_PHASES = 12

        /** `Ashen 12.3s, Spirit 1m 05s`. */
        @JvmStatic
        fun summary(rows: List<Row>): String = rows.joinToString(", ") { "${it.name} ${SlayerHudText.duration(it.seconds)}" }
    }
}
