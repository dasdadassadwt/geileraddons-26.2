package geiler.addons.client.enchanting

import java.util.regex.Pattern

/** Reads the Superpairs click budget from the "Remaining Clicks: N" item in chest slot 4. */
object SuperpairsClicks {
    const val SLOT = 4
    private val NAME = Pattern.compile("""(?i)remaining\s+clicks\s*:?\s*(\d+)?""")

    /** The item name carries the amount; the stack count mirrors it, so it is the fallback. Returns -1 when this is not the item. */
    @JvmStatic
    fun parse(plainName: String?, stackCount: Int): Int {
        val matcher = NAME.matcher(plainName ?: return -1)
        if (!matcher.find()) return -1
        return matcher.group(1)?.toIntOrNull()?.coerceAtLeast(0) ?: if (stackCount > 0) stackCount else -1
    }
}
