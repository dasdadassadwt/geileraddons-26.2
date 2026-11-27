package geiler.addons.client.combat

import java.math.BigDecimal
import java.math.RoundingMode

/** Legion and Bobbin' Time numbers, as SkyHanni's repo data and the SkyBlock wiki give them. */
object LegionRules {
    const val RANGE = 30.0
    private const val LEGION_LIMIT = 20
    private const val LEGION_PER_STACK = 0.07
    private const val BOBBIN_LIMIT = 5
    private const val BOBBIN_PER_STACK = 0.2

    @JvmStatic
    fun inRange(distanceSquared: Double): Boolean = distanceSquared <= RANGE * RANGE

    /** [levels] is the sum of the enchant's levels over the worn armor. */
    @JvmStatic
    fun legionText(levels: Int, players: Int): String =
        row("§d§lLegion: ", players.coerceIn(0, LEGION_LIMIT), levels * LEGION_PER_STACK)

    @JvmStatic
    fun bobbinText(levels: Int, bobbers: Int): String =
        row("§3§lBobbin': ", bobbers.coerceIn(0, BOBBIN_LIMIT), levels * BOBBIN_PER_STACK)

    private fun row(prefix: String, count: Int, perStack: Double): String {
        val percent = BigDecimal.valueOf(count * perStack).setScale(2, RoundingMode.HALF_UP)
        val shown = if (percent.signum() == 0) "0" else percent.stripTrailingZeros().toPlainString()
        return "$prefix§b$count §7(+$shown%)"
    }
}
