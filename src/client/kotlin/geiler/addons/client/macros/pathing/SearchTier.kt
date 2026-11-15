package geiler.addons.client.macros.pathing

import kotlin.math.max
import kotlin.math.min

/**
 * How hard a search tries, cheapest first. A walk that is easy is found by the first tier in a few hundred stands and costs next to
 * nothing; only a search that fails there - or runs into its limit - is run again with more room, more patience and a closer estimate.
 * Plain arithmetic, so checks can run it.
 */
enum class SearchTier(
    /** How much the estimate counts against the cost so far, times the plain weight: greedy goes at the goal. */
    val weightScale: Double,
    /** The most stands looked at before this tier gives up. */
    val maxExpansions: Int,
    /** How far beyond the straight line to the goal a stand may be, as a share of that line plus [corridorSlack]; 0 for no corridor. */
    val corridorShare: Double,
    /** Extra room round the search radius, in blocks. */
    val extraRadius: Int,
) {
    /** Open ground: a corridor round the line, a heavy estimate, a few thousand stands. */
    GREEDY(1.5, 2_500, 1.5, 0),

    /** What the search always was. */
    STANDARD(1.0, 60_000, 0.0, 0),

    /** A search that ran into the standard limit: the estimate exact, the limit doubled, the radius wider. */
    THOROUGH(0.0, 120_000, 0.0, 24),
    ;

    /** The weight for a standard weight of [plain]: never under 1, which would make the estimate dishonest. */
    fun weight(plain: Double): Double = max(1.0, if (this == THOROUGH) 1.0 else plain * weightScale)

    /** The furthest from the start, in blocks, a stand may be in this tier, for a goal [blocks] away and a radius of [radius]. */
    fun radius(blocks: Double, radius: Double): Double =
        if (corridorShare <= 0.0) radius + extraRadius else min(radius + extraRadius, blocks * corridorShare + CORRIDOR_SLACK)

    companion object {
        const val CORRIDOR_SLACK = 12.0

        /**
         * The tier to run next after this one found no way, or null when there is nothing more to try. [capped]: the tier gave up at its
         * limit rather than having looked at everything within its bounds. A greedy search always hands on - its bounds were a guess -
         * and a standard one only when it ran out of patience, never when it has seen every stand there is.
         */
        @JvmStatic
        fun next(tier: SearchTier, capped: Boolean): SearchTier? = when (tier) {
            GREEDY -> STANDARD
            STANDARD -> if (capped) THOROUGH else null
            THOROUGH -> null
        }
    }
}
