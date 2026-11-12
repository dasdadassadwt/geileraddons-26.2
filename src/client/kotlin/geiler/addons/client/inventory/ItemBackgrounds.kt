package geiler.addons.client.inventory

import com.google.gson.JsonParser
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import java.util.WeakHashMap
import java.util.regex.Pattern

/** SkyBlock item rarities with the default and the legacy (plain chat color) background colors. */
enum class SkyblockRarity(private val rgb: Int, private val legacyRgb: Int) {
    COMMON(0xFFFFFF, 0xFFFFFF),
    UNCOMMON(0x55FF55, 0x55FF55),
    RARE(0x459BFF, 0x5555FF),
    EPIC(0xA335EE, 0xAA00AA),
    LEGENDARY(0xFF9000, 0xFFAA00),
    MYTHIC(0xFF55FF, 0xFF55FF),
    DIVINE(0x55FFFF, 0x55FFFF),
    SPECIAL(0xFF5555, 0xFF5555),
    VERY_SPECIAL(0xFF5555, 0xFF5555),
    ULTIMATE(0xD13228, 0xAA0000),
    ADMIN(0xD13228, 0xAA0000);

    /** The word as it appears in item lore, for example "VERY SPECIAL". */
    val display: String = name.replace('_', ' ')

    fun color(legacy: Boolean): Int = if (legacy) legacyRgb else rgb
}

/** Item rarity, Jacob medal and attribute detection for slot backgrounds, memoized per item stack. */
object ItemBackgrounds {
    /** What one stack contributes to the backgrounds; computed once per stack until the cache is cleared. */
    class StackInfo(val skyblockId: String, val rarity: SkyblockRarity?, val jacobColor: Int, val hasAttributes: Boolean)

    private const val MAX_CACHE = 4096
    private val JACOB = Pattern.compile("You placed in the (DIAMOND|PLATINUM|GOLD|SILVER|BRONZE) bracket!")
    private val cache = WeakHashMap<ItemStack, StackInfo>()
    private val EMPTY = StackInfo("", null, -1, false)

    /** Scans lore from the bottom line up for a rarity word; the longer name wins (UNCOMMON over COMMON). */
    @JvmStatic
    fun rarityFromLore(lines: List<String>): SkyblockRarity? {
        for (line in lines.asReversed()) {
            val match = SkyblockRarity.entries.lastOrNull { line.contains(it.display) }
            if (match != null) return match
        }
        return null
    }

    /** A pet's stored tier, one tier higher when it holds the tier boost item. */
    @JvmStatic
    fun petRarity(tier: String?, heldItem: String?): SkyblockRarity? {
        val base = SkyblockRarity.entries.firstOrNull { it.name == tier } ?: return null
        return if (heldItem == "PET_ITEM_TIER_BOOST") SkyblockRarity.entries.getOrElse(base.ordinal + 1) { base } else base
    }

    /** RGB for a Jacob contest medal lore line, or -1. */
    @JvmStatic
    fun jacobColor(lines: List<String>): Int {
        for (line in lines) {
            val matcher = JACOB.matcher(line)
            if (!matcher.find()) continue
            return when (matcher.group(1)) {
                "DIAMOND" -> 0x55FFFF
                "PLATINUM" -> 0x00AAAA
                "GOLD" -> 0xFFAA00
                "SILVER" -> 0xFFFFFF
                else -> 0xFF5555
            }
        }
        return -1
    }

    /** RGB plus an opacity percent (clamped to 0..100) as an ARGB color. */
    @JvmStatic
    fun fillArgb(rgb: Int, opacityPercent: Int): Int =
        ((opacityPercent.coerceIn(0, 100) * 255 + 50) / 100 shl 24) or (rgb and 0xFFFFFF)

    /** Half-width of the disc on each of the 16 rows of a 16x16 item slot. */
    @JvmStatic
    fun circleHalfWidths(): IntArray = IntArray(16) { row ->
        val dy = row + 0.5 - 8.0
        Math.round(Math.sqrt(64.0 - dy * dy)).toInt().coerceIn(1, 8)
    }

    /** The disc as flat triples `[firstRow, endRow, halfWidth]`: adjacent rows of equal width share one rectangle. */
    @JvmStatic
    fun circleRuns(): IntArray {
        val widths = circleHalfWidths()
        val runs = ArrayList<Int>()
        var start = 0
        for (row in 1..widths.size) {
            if (row < widths.size && widths[row] == widths[start]) continue
            runs.add(start); runs.add(row); runs.add(widths[start])
            start = row
        }
        return runs.toIntArray()
    }

    @JvmStatic
    fun info(stack: ItemStack): StackInfo {
        if (stack.isEmpty) return EMPTY
        cache[stack]?.let { return it }
        if (cache.size >= MAX_CACHE) cache.clear()
        val computed = compute(stack)
        cache[stack] = computed
        return computed
    }

    @JvmStatic
    fun clearCache() = cache.clear()

    @JvmStatic
    fun cacheSize(): Int = cache.size

    private fun compute(stack: ItemStack): StackInfo {
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()
        val id = tag?.getStringOr("id", "") ?: ""
        val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()
        val rarity = if (id == "PET") petFromTag(tag?.getStringOr("petInfo", "") ?: "") else rarityFromLore(lore)
        val attributes = tag != null && tag.contains("attributes") && id != "ATTRIBUTE_SHARD"
        return StackInfo(id, rarity, jacobColor(lore), attributes)
    }

    private fun petFromTag(petInfo: String): SkyblockRarity? {
        if (petInfo.isEmpty()) return null
        return try {
            val json = JsonParser.parseString(petInfo).asJsonObject
            val tier = json.get("tier")?.takeIf { it.isJsonPrimitive }?.asString
            val held = json.get("heldItem")?.takeIf { it.isJsonPrimitive }?.asString
            petRarity(tier, held)
        } catch (_: RuntimeException) {
            null
        }
    }
}
