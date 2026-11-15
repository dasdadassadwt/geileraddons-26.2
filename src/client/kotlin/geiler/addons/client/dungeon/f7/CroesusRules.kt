package geiler.addons.client.dungeon.f7

import java.util.Locale

/** Pure Croesus rules: menu titles, item pricing, chest profit and menu highlight state (after Odin's Croesus). */
object CroesusRules {
    private val CROESUS_MENU = Regex("""^(?:\(\d+/\d+\) )?Croesus$""")
    private val CHEST_PREVIEW = Regex("""^(?:Master )?Catacombs - ([FloorVI\d ]*)$""")
    private val OPENED_CHEST = Regex("""^(Wood|Iron|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$""")
    private val BOOK = Regex("""^Enchanted Book \(?([\w ]+) (\w+)\)$""")
    private val ESSENCE = Regex("""^(\w+) Essence x(\d+)$""")
    private val SHARD = Regex("""^([A-Za-z ]+) Shard$""")
    private val SHARD_ONE = Regex("""^([A-Za-z ]+) Shard x1$""")
    private val COST = Regex("""^([\d,]+) Coins$""")
    private val STATUS = Regex("""^Opened Chest: (.+)$|^No more chests to open!$""")
    private val OPENED_STATUS = Regex("""^Opened Chest: (.+)$""")
    private val NO_MORE = Regex("""^No more chests to open!$""")

    const val COLOR_FRESH = 0x6655FF55
    const val COLOR_OPENED = 0x66FFAA00
    const val COLOR_NONE = 0x66FF5555
    const val COLOR_BEST = 0x8000AA00.toInt()
    const val COLOR_SECOND = 0x80FFFF55.toInt()
    const val COLOR_KISMET = 0xFF55FFFF.toInt()

    private val ULTIMATE = setOf(
        "Soul Eater", "Combo", "Legion", "One For All", "Rend", "Bank", "Swarm", "Last Stand", "Wisdom", "No Pain No Gain",
    )

    private val REPLACEMENTS = mapOf(
        "Shiny Wither Chestplate" to "WITHER_CHESTPLATE",
        "Shiny Wither Leggings" to "WITHER_LEGGINGS",
        "Shiny Necron's Handle" to "NECRON_HANDLE",
        "Necron's Handle" to "NECRON_HANDLE",
        "Shiny Wither Helmet" to "WITHER_HELMET",
        "Shiny Wither Boots" to "WITHER_BOOTS",
        "Wither Shield" to "WITHER_SHIELD_SCROLL",
        "Implosion" to "IMPLOSION_SCROLL",
        "Shadow Warp" to "SHADOW_WARP_SCROLL",
        "Necron Dye" to "DYE_NECRON",
        "Livid Dye" to "DYE_LIVID",
        "Giant's Sword" to "GIANTS_SWORD",
        "NECROMANCERS_BROOCH" to "NECROMANCER_BROOCH",
        "SHADOW_WARP" to "SHADOW_WARP_SCROLL",
        "SPIRIT_STONE" to "SPIRIT_DECOY",
        "WARPED_STONE" to "AOTE_STONE",
    )

    class ChestItem(val name: String, val price: Double)
    class ChestResult(val name: String, val items: List<ChestItem>, val profit: Double, val slot: Int)
    class MenuState(val hide: Boolean, val color: Int)

    @JvmStatic fun isCroesusMenu(title: String?): Boolean = title != null && CROESUS_MENU.matches(title)
    @JvmStatic fun isChestPreview(title: String?): Boolean = title != null && CHEST_PREVIEW.matches(title)
    @JvmStatic fun isOpenedChest(title: String?): Boolean = title != null && OPENED_CHEST.matches(title)

    /** The price of a preview-lore line (book, essence, shard, or an item name turned into a SkyBlock id). */
    @JvmStatic
    fun parseItemValue(item: String, prices: Map<String, Double>): Double? {
        BOOK.find(item)?.destructured?.let { (name, level) ->
            val ultimate = if (name in ULTIMATE) "ULTIMATE_" else ""
            return prices["ENCHANTED_BOOK-$ultimate${name.uppercase(Locale.ROOT).replace(" ", "_")}-${BlessingRules.roman(level)}"]
        }
        ESSENCE.find(item)?.destructured?.let { (name, quantity) ->
            val price = prices["ESSENCE_${name.uppercase(Locale.ROOT)}"] ?: return null
            return price * quantity.toDouble()
        }
        SHARD.find(item)?.groupValues?.get(1)?.let { shard ->
            return prices["SHARD_${shard.uppercase(Locale.ROOT).replace(" ", "_")}"]
        }
        REPLACEMENTS[item]?.let { return prices[it] }
        return prices[item.uppercase(Locale.ROOT).replace("'", "").replace(" -", "").replace(" ", "_")]
    }

    /** A chest of the Croesus preview screen (a player head whose lore lists Contents and Cost), or null. */
    @JvmStatic
    fun previewChest(slot: Int, name: String, lore: List<String>, prices: Map<String, Double>): ChestResult? {
        val start = lore.indexOfFirst { it == "Contents" } + 1
        if (start == 0) return null
        val end = lore.indexOfFirst { it.isEmpty() }
        if (end < start) return null
        val cost = lore.getOrNull(end + 2)?.let { COST.find(it)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() } ?: 0.0
        val items = ArrayList<ChestItem>()
        var total = 0.0
        for (line in lore.subList(start, end)) {
            val price = parseItemValue(line, prices) ?: 0.0
            total += price
            items.add(ChestItem(line, price))
        }
        return ChestResult(name, items, total - cost, slot)
    }

    @JvmStatic
    fun sorted(chests: List<ChestResult>): List<ChestResult> = chests.sortedByDescending { it.profit }

    /** Slots of the two most profitable chests with a profit above zero, best first. */
    @JvmStatic
    fun topSlots(chests: List<ChestResult>): List<Int> = sorted(chests).filter { it.profit > 0 }.take(2).map { it.slot }

    /** Whether a Croesus menu run is hidden and how it is tinted ([COLOR_FRESH], [COLOR_OPENED], [COLOR_NONE], 0 for none). */
    @JvmStatic
    @JvmOverloads
    fun menuState(lore: List<String>, hideClaimed: Boolean, includeKey: Boolean, keyStruck: Boolean, highlightState: Boolean = true): MenuState {
        val hide = hideClaimed && lore.any { STATUS.matches(it) } && (!includeKey || keyStruck)
        if (hide) return MenuState(true, 0)
        val color = when {
            !highlightState -> 0
            lore.any { NO_MORE.matches(it) } -> COLOR_NONE
            lore.any { OPENED_STATUS.matches(it) } -> COLOR_OPENED
            else -> COLOR_FRESH
        }
        return MenuState(false, color)
    }

    /** One slot of an opened chest screen, reduced to what pricing needs. */
    class OpenedSlot private constructor(
        val kind: Int,
        val name: String,
        val lore: List<String>,
        val itemId: String?,
        val enchant: String?,
        val level: Int,
    ) {
        companion object {
            @JvmStatic fun empty() = OpenedSlot(0, "", emptyList(), null, null, 0)
            @JvmStatic fun chest(lore: List<String>) = OpenedSlot(1, "", lore, null, null, 0)
            @JvmStatic fun book(name: String, enchant: String, level: Int) = OpenedSlot(2, name, emptyList(), null, enchant, level)
            @JvmStatic fun item(name: String, itemId: String?) = OpenedSlot(3, name, emptyList(), itemId, null, 0)
        }
    }

    /** Profit of the chest being looked at: priced contents minus the cost shown by the chest item. */
    @JvmStatic
    fun openedChest(slots: List<OpenedSlot>, prices: Map<String, Double>): ChestResult {
        val items = ArrayList<ChestItem>()
        var cost = 0.0
        for (slot in slots) {
            when (slot.kind) {
                1 -> for (line in slot.lore) COST.find(line)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.let { cost += it }
                2 -> prices["ENCHANTED_BOOK-${slot.enchant?.uppercase(Locale.ROOT)}-${slot.level}"]?.let { items.add(ChestItem(slot.name, it)) }
                3 -> openedItem(slot, prices)?.let { items.add(it) }
            }
        }
        return ChestResult("§eChest", items, items.sumOf { it.price } - cost, -1)
    }

    private fun openedItem(slot: OpenedSlot, prices: Map<String, Double>): ChestItem? {
        ESSENCE.find(slot.name)?.destructured?.let { (name, quantity) ->
            val price = prices["ESSENCE_${name.uppercase(Locale.ROOT)}"] ?: return null
            return ChestItem(slot.name, price * quantity.toDouble())
        }
        SHARD_ONE.find(slot.name)?.groupValues?.get(1)?.let { shard ->
            prices["SHARD_${shard.uppercase(Locale.ROOT).replace(" ", "_").replace("'S", "")}"]?.let { return ChestItem(slot.name, it) }
        }
        return slot.itemId?.let { id -> prices[id]?.let { ChestItem(slot.name, it) } }
    }

    @JvmStatic
    fun money(value: Double): String = "%,.0f".format(Locale.US, value)

    /** The Chest Count HUD text, or null (hidden) while the module is off, outside a dungeon, or with no chest. */
    @JvmStatic
    fun chestCountText(enabled: Boolean, inDungeon: Boolean, count: Int): String? =
        if (enabled && inDungeon && count > 0) "§6Chests: §a$count" else null

    /** HUD lines for a list of chests: the profit line of each, then (unless [minimized]) its items. */
    @JvmStatic
    fun hudLines(chests: List<ChestResult>, minimized: Boolean): List<String> {
        val lines = ArrayList<String>()
        for (chest in chests) {
            val color = if (chest.profit >= 0) "§2" else "§c"
            lines.add("${chest.name} §8- §6Profit: $color${money(chest.profit)}")
            if (!minimized) for (item in chest.items) lines.add("  §7${item.name}: §a${money(item.price)}")
        }
        return lines
    }
}
