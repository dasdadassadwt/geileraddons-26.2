package geiler.addons.client.dungeon.f7

import kotlin.math.PI
import kotlin.math.sin

/**
 * Rare Drop Rat without Minecraft types: which drops count (Necron's Handle, the three Wither scrolls, the Fifth Master
 * Star), how they are found in chat lines, and the grow-and-shake curve of the rat.
 */
object RareDropRatRules {
    /** The names that show the rat; a shiny or starred copy still matches. */
    val DROPS = listOf("Necron's Handle", "Implosion", "Shadow Warp", "Wither Shield", "Fifth Master Star")

    private val CHAT_DROP = Regex("""(?:RARE DROP!|RNG METER!|CRAZY RARE DROP!|INSANE DROP!|PRAY TO RNGESUS DROP!) (.+)""")

    /** The drop an item name or lore line names, or null. A Wither Shield scroll is not the Wither Shield ability line. */
    @JvmStatic
    fun drop(name: String): String? {
        val text = name.trim()
        if (text.startsWith("Ability:") || text.contains("Scroll Abilities")) return null
        return DROPS.firstOrNull { text.contains(it) }
    }

    /** The drop a chat line announces, or null. */
    @JvmStatic
    fun chatDrop(line: String): String? {
        val item = CHAT_DROP.find(line)?.groupValues?.get(1) ?: return null
        return drop(item)
    }

    /** Size of the rat at [ms] after the drop, 0 to 1: grows over [growMs], holds, then shrinks out over [fadeMs]. */
    @JvmStatic
    fun scale(ms: Long, growMs: Long, holdMs: Long, fadeMs: Long): Double = when {
        ms < 0 -> 0.0
        ms < growMs -> easeOut(ms.toDouble() / growMs)
        ms < growMs + holdMs -> 1.0
        ms < growMs + holdMs + fadeMs -> 1.0 - (ms - growMs - holdMs).toDouble() / fadeMs
        else -> 0.0
    }

    /** Opacity 0 to 1: full until the fade, then down to 0. */
    @JvmStatic
    fun alpha(ms: Long, growMs: Long, holdMs: Long, fadeMs: Long): Double =
        if (ms < growMs + holdMs) 1.0 else scale(ms, growMs, holdMs, fadeMs)

    /** Shake offset in pixels (x, y) at [ms]: grows with the rat, at most [amplitude]. */
    @JvmStatic
    fun shake(ms: Long, amplitude: Double, size: Double): DoubleArray {
        val strength = amplitude * size
        val t = ms / 1000.0
        return doubleArrayOf(strength * sin(t * 2 * PI * 13.0), strength * sin(t * 2 * PI * 17.0 + 1.3))
    }

    @JvmStatic
    fun done(ms: Long, growMs: Long, holdMs: Long, fadeMs: Long): Boolean = ms >= growMs + holdMs + fadeMs

    private fun easeOut(x: Double): Double = 1.0 - (1.0 - x) * (1.0 - x)
}
