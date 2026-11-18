package geiler.addons.client.kuudra

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure Kuudra rules. Crate and pile geometry, build progress colours, the danger zone blocks and the phase model follow
 * the IQ mod (https://github.com/iqaddons/IQ, Apache-2.0); health text and the stun rule follow Odin's Kuudra features.
 */
object KuudraRules {
    const val MAX_PILES = 16
    const val MAX_GIANTS = 16

    private val TIER = Regex("Kuudra's Hollow \\(T([1-5])\\)")
    private val PILE = Regex("^PROGRESS:\\s*(?:§.)?(\\d{1,3})%$")
    private val BUILD = Regex("^Building Progress (\\d{1,3})% \\((\\d{1,2}) Players? Helping\\)$")

    @JvmStatic
    fun tierFrom(sidebar: String): Int? = TIER.find(sidebar)?.groupValues?.get(1)?.toIntOrNull()

    @JvmStatic
    fun pilePercent(name: String): Int? = PILE.find(name.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= 100 }

    /** `[percent, helpers]` from the ballista's tag, or null. */
    @JvmStatic
    fun buildStatus(name: String): IntArray? {
        val match = BUILD.find(name.trim()) ?: return null
        val percent = match.groupValues[1].toInt().takeIf { it <= 100 } ?: return null
        return intArrayOf(percent, match.groupValues[2].toInt())
    }

    @JvmStatic
    fun healthColor(health: Int): String = when {
        health > 99_000 -> "§a"
        health > 75_000 -> "§2"
        health > 50_000 -> "§e"
        health > 25_000 -> "§6"
        health > 10_000 -> "§c"
        else -> "§4"
    }

    /** Kuudra's health out of 100k; T5 at 25k or less can show the real 240M scale instead. */
    @JvmStatic
    fun healthText(health: Float, tier: Int, scaled: Boolean): String {
        val raw = health.roundToInt()
        val color = healthColor(raw)
        if (scaled && tier == 5 && raw <= 25_000) return "$color${(raw * 9.6 / 1000).roundToInt()}M/240M❤"
        return "$color${(raw / 1000.0).roundToInt()}k/100k❤"
    }

    @JvmStatic
    fun shouldStun(tier: Int, percent: Int, threshold: Int): Boolean = tier >= 3 && threshold > 0 && percent >= threshold

    @JvmStatic
    fun buildersColor(builders: Int): String = if (builders >= 3) "§a" else if (builders == 2) "§e" else "§c"

    /** ARGB of an unfinished pile: red at the start through orange and yellow-green to green when nearly done. */
    @JvmStatic
    fun buildProgressColor(percent: Int): Int = when {
        percent <= 20 -> 0xFFA80000.toInt()
        percent <= 40 -> 0xFFFF0000.toInt()
        percent <= 60 -> 0xFFFF8700.toInt()
        percent <= 80 -> 0xFF2E8200.toInt()
        else -> 0xFF7DDA58.toInt()
    }

    /** The tentacle danger zones are green to red terracotta: -1 for any other block, 0 for green and lime, 1 for yellow and up (jump). */
    @JvmStatic
    fun dangerLevel(blockId: String): Int = when (blockId.removePrefix("minecraft:")) {
        "green_terracotta", "lime_terracotta" -> 0
        "yellow_terracotta", "orange_terracotta", "red_terracotta" -> 1
        else -> -1
    }

    /** Where a giant's supply crate lies: 3.7 blocks from it along its yaw plus 130 degrees, at y 75. */
    @JvmStatic
    fun crateFor(giantX: Double, giantZ: Double, yawDegrees: Float): DoubleArray {
        val radians = Math.toRadians(yawDegrees + 130.0)
        return doubleArrayOf(giantX + 3.7 * cos(radians), 75.0, giantZ + 3.7 * sin(radians))
    }

    /** A pile is done when its "SUPPLIES RECEIVED" stand is within 1.5 blocks across and 5 up or down. */
    @JvmStatic
    fun pileCompleted(pileX: Double, pileY: Double, pileZ: Double, standX: Double, standY: Double, standZ: Double): Boolean {
        val dx = pileX - standX
        val dz = pileZ - standZ
        return dx * dx + dz * dz <= 2.25 && Math.abs(pileY - standY) <= 5.0
    }
}
