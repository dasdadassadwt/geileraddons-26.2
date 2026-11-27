package geiler.addons.client.bazaar

/**
 * The pure rules behind Bazaar Buy Presets. Sign markers, the amount menu title and the `Buy up to Nx.` lore line
 * follow Skyblocker's BazaarQuickQuantities and BazaarMax.
 */
object BazaarPresetRules {
    /** One button; a null [amount] is Max. */
    class Preset(val label: String, val amount: Int?)

    const val MAX_BUTTONS = 5
    const val STACKABLE_LIMIT = 71_680
    const val UNSTACKABLE_LIMIT = 256
    private const val INPUT_MARKER = "^^^^^^^^^^^^^^^"
    private val LIMIT_LINE = Regex("Buy up to ([0-9,]+)x\\.")

    @JvmStatic
    fun isAmountSign(lines: Array<String>): Boolean =
        lines.size >= 4 && lines[1] == INPUT_MARKER && lines[3] == "to order"

    /** The menus that lead to the amount sign. */
    @JvmStatic
    fun isAmountMenu(title: String): Boolean = title == "How many do you want?" || title.endsWith("Instant Buy")

    @JvmStatic
    fun parsePresets(text: String): List<Preset> = text.split(',').mapNotNull { part ->
        val token = part.trim()
        if (token.equals("max", ignoreCase = true)) Preset("Max", null)
        else token.toIntOrNull()?.takeIf { it > 0 }?.let { Preset(it.toString(), it) }
    }.distinctBy { it.amount }.take(MAX_BUTTONS)

    /** The per-order limit a menu item states, or 0. */
    @JvmStatic
    fun limitFromLore(lines: List<String>): Int = lines.firstNotNullOfOrNull { line ->
        LIMIT_LINE.find(line)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    } ?: 0

    @JvmStatic
    fun limitFromStackSize(maxStackSize: Int): Int = if (maxStackSize <= 1) UNSTACKABLE_LIMIT else STACKABLE_LIMIT

    /** What a button writes: null hides it (Max with an unknown limit, or a number above a known limit). */
    @JvmStatic
    fun amountOf(preset: Int?, limit: Int): Int? = when {
        preset == null -> limit.takeIf { it > 0 }
        limit > 0 && preset > limit -> null
        else -> preset
    }
}
