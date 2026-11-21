package geiler.addons.client.slayer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Health and health-triggered phases of every Slayer boss tier, so the Slayer HUD can draw a true bar (not "highest
 * seen") and list the phases still to come. Numbers come from the Hypixel SkyBlock wiki boss pages (see
 * `docs/provenance/external-sources.md`); they are unverified against live labels, which is why the HUD logs the first
 * health read next to the table maximum. Time-based abilities (Enrage, Twinclaws, "Boom!") are not listed.
 */
object SlayerBossPhases {
    class Trigger(val name: String, val fraction: Double)

    class Tier(val number: Int, val maxHealth: Long, triggers: List<Trigger>) {
        /** Highest health first, so the list reads in the order the fight reaches them. */
        val triggers: List<Trigger> = triggers.sortedByDescending { it.fraction }
    }

    /** How the vanilla boss bar is divided: none, sixths, or twentieths. */
    enum class Notches { NONE, SIX, TWENTY }

    private fun t(name: String, fraction: Double) = Trigger(name, fraction)

    private const val TWO_THIRDS = 2.0 / 3
    private const val THIRD = 1.0 / 3
    private const val SIXTH = 1.0 / 6
    private const val FIVE_SIXTHS = 5.0 / 6

    private val SHIELDS = listOf(t("Hitshield", TWO_THIRDS), t("Hitshield", THIRD))
    private val STAKE = t("Steak Stake", 0.2)

    private val TABLE: Map<SlayerRules.Family, List<Tier>> = mapOf(
        SlayerRules.Family.REVENANT to listOf(
            Tier(1, 500L, emptyList()), Tier(2, 20_000L, emptyList()), Tier(3, 400_000L, emptyList()),
            Tier(4, 1_500_000L, emptyList()), Tier(5, 10_000_000L, listOf(t("Double TNT", THIRD))),
        ),
        SlayerRules.Family.TARANTULA to listOf(
            Tier(1, 2_500L, emptyList()), Tier(2, 50_000L, emptyList()),
            Tier(3, 1_000_000L, listOf(t("Web of Lies", TWO_THIRDS), t("Web of Lies", THIRD))),
            Tier(4, 2_500_000L, listOf(t("Web of Lies", TWO_THIRDS), t("Web of Lies", THIRD))),
            Tier(5, 10_000_000L, listOf(t("Web of Lies", TWO_THIRDS), t("Web of Lies", THIRD))),
            Tier(6, 20_000_000L, emptyList()),
        ),
        SlayerRules.Family.SVEN to listOf(
            Tier(1, 2_000L, emptyList()), Tier(2, 40_000L, emptyList()),
            Tier(3, 750_000L, listOf(t("Pups", 0.5))), Tier(4, 2_000_000L, listOf(t("Pups", 0.5))),
        ),
        SlayerRules.Family.VOIDGLOOM to listOf(
            Tier(1, 300_000L, SHIELDS),
            Tier(2, 12_000_000L, SHIELDS + t("Yang Glyphs", 0.5)),
            Tier(3, 50_000_000L, SHIELDS + t("Yang Glyphs", TWO_THIRDS) + t("Nukekubi Heads", THIRD)),
            Tier(
                4, 210_000_000L,
                SHIELDS + t("Yang Glyphs", TWO_THIRDS) + t("Nukekubi Heads", THIRD) +
                    listOf(t("Radiation", FIVE_SIXTHS), t("Radiation", 0.5), t("Radiation", SIXTH)),
            ),
        ),
        SlayerRules.Family.DEMONLORD to listOf(
            Tier(1, 2_500_000L, listOf(t("Demonsplit", 0.5))),
            Tier(2, 10_000_000L, listOf(t("Demonsplit", 0.5), t("Fire Pillars", 0.5))),
            Tier(3, 45_000_000L, DEMON_LATE),
            Tier(4, 150_000_000L, DEMON_LATE),
        ),
        SlayerRules.Family.VAMPIRE to listOf(
            Tier(1, 625L, listOf(t("Mania", 0.5), STAKE)),
            Tier(2, 1_100L, listOf(t("Mania", 0.6), STAKE)),
            Tier(3, 1_800L, listOf(t("Mania", 0.75), t("Mania", 0.4), STAKE)),
            Tier(4, 2_400L, listOf(t("Mania", 0.75), t("Mania", 0.4), STAKE)),
            Tier(5, 3_000L, listOf(t("Mania", 0.75), t("Mania", 0.4), STAKE)),
        ),
    )

    private val DEMON_LATE get() = listOf(
        t("Demonsplit", TWO_THIRDS), t("Fire Pillars", TWO_THIRDS), t("Demonsplit", THIRD), t("DDR Apocalypse", THIRD),
    )

    /**
     * The tier whose maximum health is the smallest one at or above [firstHealth], the first label read of a fight (the
     * boss spawns at full health). Null when the family has no table or the read is above every tier.
     */
    @JvmStatic
    fun resolveTier(family: SlayerRules.Family?, firstHealth: Long): Tier? =
        TABLE[family ?: return null]?.firstOrNull { it.maxHealth >= firstHealth }

    /** The health at which [trigger] fires on [tier]. */
    @JvmStatic
    fun threshold(tier: Tier, trigger: Trigger): Long = (trigger.fraction * tier.maxHealth).roundToLong()

    @JvmStatic
    fun notches(tier: Tier): Notches = when {
        tier.triggers.isEmpty() -> Notches.NONE
        tier.triggers.all { onGrid(it.fraction, 6) } -> Notches.SIX
        tier.triggers.all { onGrid(it.fraction, 20) } -> Notches.TWENTY
        else -> Notches.NONE
    }

    private fun onGrid(fraction: Double, steps: Int): Boolean = abs(fraction * steps - Math.round(fraction * steps)) < 1e-6

    /** `33M / 50M · 66%`; health above [max] prints as [max]. */
    @JvmStatic
    fun title(health: Long, max: Long): String {
        val shown = health.coerceIn(0L, maxOf(max, 0L))
        val percent = if (max > 0) (shown * 100 / max).toInt() else 0
        return "${SlayerHudText.formatHealth(shown)} / ${SlayerHudText.formatHealth(max)} · $percent%"
    }

    /** `x Hitshield 67%` (passed), `> Nukekubi Heads 33% · 16.7M` (next), `- Radiation 17% · 40M` (later). */
    @JvmStatic
    fun rowText(row: SlayerPhaseProgress.Row): String {
        val percent = String.format(Locale.ROOT, "%d%%", Math.round(row.fraction * 100))
        val left = if (row.status == SlayerPhaseProgress.Status.PASSED) "" else " · ${SlayerHudText.formatHealth(row.healthToGo)}"
        val mark = when (row.status) {
            SlayerPhaseProgress.Status.PASSED -> "x"
            SlayerPhaseProgress.Status.NEXT -> ">"
            SlayerPhaseProgress.Status.UPCOMING -> "-"
        }
        return "$mark ${row.name} $percent$left"
    }
}

/**
 * The phases one fight has passed. A trigger counts as passed the first time health reaches it and stays passed, since
 * a boss that regenerates is still past the phase it already triggered.
 */
class SlayerPhaseProgress(private val tier: SlayerBossPhases.Tier) {
    enum class Status { PASSED, NEXT, UPCOMING }

    class Row(val name: String, val fraction: Double, val status: Status, val healthToGo: Long)

    private val passed = BooleanArray(tier.triggers.size)

    /** Marks the triggers [health] has reached and returns the ones newly passed. */
    fun update(health: Long): List<SlayerBossPhases.Trigger> {
        val fresh = ArrayList<SlayerBossPhases.Trigger>()
        tier.triggers.forEachIndexed { index, trigger ->
            if (!passed[index] && health <= SlayerBossPhases.threshold(tier, trigger)) {
                passed[index] = true
                fresh.add(trigger)
            }
        }
        return fresh
    }

    fun rows(health: Long): List<Row> {
        var next = true
        return tier.triggers.mapIndexed { index, trigger ->
            val left = maxOf(0L, health - SlayerBossPhases.threshold(tier, trigger))
            val status = when {
                passed[index] -> Status.PASSED
                next -> Status.NEXT.also { next = false }
                else -> Status.UPCOMING
            }
            Row(trigger.name, trigger.fraction, status, left)
        }
    }
}
