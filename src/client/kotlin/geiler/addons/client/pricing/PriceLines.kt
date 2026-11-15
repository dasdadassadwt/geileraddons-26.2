package geiler.addons.client.pricing

import java.util.Locale

/** Downloaded price data; a null set has not loaded (yet). */
class PriceSnapshot(
    val npc: Map<String, Double>? = null,
    val george: Map<String, Double>? = null,
    val motes: Map<String, Int>? = null,
    val bazaar: Map<String, BazaarProduct>? = null,
    val lowestBin: Map<String, Double>? = null,
    val oneDayAverage: Map<String, Double>? = null,
    val threeDayAverage: Map<String, Double>? = null,
)

/** The Item Tooltip price settings the line builder reads (Skyblocker's `GeneralConfig.ItemTooltip`). */
class PriceOptions(
    val npc: Boolean,
    val george: Boolean,
    val motes: Boolean,
    val averageBin: Boolean,
    /** ONE_DAY, THREE_DAY or BOTH. */
    val averageType: String,
    val lowestBin: Boolean,
    val bazaar: Boolean,
)

/** What a tooltip line is built from: the item's ids and how many of it the line should price. */
class PriceItem(
    val skyblockId: String,
    val apiId: String,
    val count: Int,
    val pet: SkyblockItemIds.Pet?,
)

/**
 * Builds the price lines of the Item Tooltip in Skyblocker's order and wording (NPC, George, Bazaar, Lowest BIN,
 * averages, Motes). Lines use legacy `§` color codes; label and value are joined with one space.
 */
object PriceLines {
    @JvmStatic
    fun coins(price: Double, count: Int): String = coins(price, count, false)

    /** `1,234.5 Coins`, with `(each)` appended when [count] is above one; [preCounted] means [price] is the total. */
    @JvmStatic
    fun coins(price: Double, count: Int, preCounted: Boolean): String {
        val each = String.format(Locale.ENGLISH, "%1$,.1f", if (preCounted) price / count else price)
        if (count == 1) return "§3$each Coins"
        val total = String.format(Locale.ENGLISH, "%1$,.1f", if (preCounted) price else price * count)
        return "§3$total Coins §7($each each)"
    }

    /** `105 Motes` or `210 Motes (105 each)`; the McGrubber multiplier is 1 (no stacks configured). */
    @JvmStatic
    fun motes(price: Int, count: Int): String {
        val total = String.format(Locale.ENGLISH, "%1$,.1f", price.toDouble() * count).replace(".0", "")
        if (count == 1) return "§3$total Motes"
        val each = String.format(Locale.ENGLISH, "%1$,.1f", price.toDouble()).replace(".0", "")
        return "§3$total Motes §7($each each)"
    }

    private fun line(label: String, color: Char, value: String) = "§$color$label §r$value"

    @JvmStatic
    fun build(data: PriceSnapshot, options: PriceOptions, item: PriceItem): List<String> {
        val lines = ArrayList<String>()
        if (options.npc) {
            val price = data.npc?.get(item.skyblockId)
            if (price != null && price >= 0) lines.add(line("NPC Sell Price:", 'e', coins(price, item.count)))
        }
        if (options.george && item.pet != null && item.pet.exp <= 0) {
            val price = data.george?.get(item.pet.neuName)
            if (price != null && price >= 0) lines.add(line("Pet Sell Price:", 'e', coins(price, 1)))
        }
        val product = data.bazaar?.get(item.apiId)
        if (options.bazaar && product != null) {
            lines.add(line("Bazaar Buy Price:", '6',
                product.buyPrice?.let { coins(it, item.count) } ?: "§cNo data"))
            lines.add(line("Bazaar Sell Price:", '6',
                product.sellPrice?.let { coins(it, item.count) } ?: "§cNo data"))
        }
        // Skytils keeps some Bazaar items in the lowest-BIN data too, so those are skipped here.
        if (options.lowestBin && data.bazaar?.containsKey(item.apiId) != true) {
            data.lowestBin?.get(item.apiId)?.let { lines.add(line("Lowest BIN Price:", '6', coins(it, item.count))) }
        }
        if (options.averageBin) {
            val type = options.averageType
            if (type == "ONE_DAY" || type == "BOTH") {
                data.oneDayAverage?.get(item.apiId)?.let { lines.add(line("1 Day Avg. Price:", '6', coins(it, item.count))) }
            }
            if (type == "THREE_DAY" || type == "BOTH") {
                data.threeDayAverage?.get(item.apiId)?.let { lines.add(line("3 Day Avg. Price:", '6', coins(it, item.count))) }
            }
        }
        if (options.motes) {
            data.motes?.get(item.skyblockId)?.let { lines.add(line("Motes Price:", 'd', motes(it, item.count))) }
        }
        return lines
    }

    private val ESSENCE_COST = Regex("Cost (?<amount>[\\d,]+) (?<type>[A-Za-z]+) Essence")
    val ESSENCE_TYPES = listOf("WITHER", "SPIDER", "UNDEAD", "DRAGON", "GOLD", "DIAMOND", "ICE", "CRIMSON", "FOREST", "FOSSIL", "SAFARI")

    /** The Essence Cost line for an essence shop item whose lore (joined with spaces) shows its cost, else null. */
    @JvmStatic
    fun essenceLine(lore: String, sellPrices: Map<String, Long>): String? {
        val match = ESSENCE_COST.find(lore) ?: return null
        val amount = match.groups["amount"]!!.value.replace(",", "").toLongOrNull() ?: return null
        val price = sellPrices[match.groups["type"]!!.value.uppercase(Locale.ROOT)] ?: return null
        if (price == 0L) return null
        val total = String.format(Locale.ENGLISH, "%,d", price * amount)
        val each = String.format(Locale.ENGLISH, "%,d", price)
        return "§bEssence Cost: §r§3$total coins §7($each each)"
    }

    /** The Crafting Price line, or null when the item has no craft cost. */
    @JvmStatic
    fun craftLine(cost: Double, count: Int): String? = if (cost <= 0) null else line("Crafting Price:", '6', coins(cost, count))

    private val SACK_STORED = Regex("Stored: ([\\d,]+)/\\S+")
    private val GEMSTONE_AMOUNT = Regex(" Amount: ([\\d,]+)")
    private val STASH_COUNT = Regex("x([\\d,]+)$")
    private val COMPOST = Regex("Compost Available: ([\\d,]+)")

    /** The amount a sack item, stash row or composter shows in its lore or name, else null. */
    @JvmStatic
    fun displayedCount(name: String, lore: List<String>, firstLine: String): Int? {
        if (lore.size >= 2 && (lore.first().endsWith("Sack") || lore.first().endsWith("Gemstones"))) {
            if (name.endsWith("Gemstone")) {
                lore.firstNotNullOfOrNull { GEMSTONE_AMOUNT.find(it) }?.let { return number(it) }
            }
            lore.firstNotNullOfOrNull { SACK_STORED.find(it) }?.let { return number(it) }
        }
        STASH_COUNT.find(firstLine)?.let { return number(it) }
        lore.firstNotNullOfOrNull { COMPOST.find(it) }?.let { return number(it) }
        return null
    }

    private fun number(match: MatchResult): Int? = match.groupValues[1].replace(",", "").toIntOrNull()
}
