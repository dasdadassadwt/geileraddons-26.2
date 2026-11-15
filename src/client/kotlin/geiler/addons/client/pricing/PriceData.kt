package geiler.addons.client.pricing

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import net.minecraft.nbt.CompoundTag
import java.util.Locale

/** One Bazaar product; either price can be missing when nobody is buying or selling. */
class BazaarProduct(val id: String, val buyPrice: Double?, val sellPrice: Double?)

/**
 * Parsing of the price data sets Skyblocker's Item Tooltip reads from hysky.de (LGPL-3.0): flat `id -> number`
 * maps and the Bazaar product map. Written independently; no Skyblocker code is included.
 */
object PriceParsing {
    const val MAX_ENTRIES = 60_000

    @JvmStatic
    fun doubleMap(json: String?): Map<String, Double>? = numberMap(json) { it.asDouble }

    @JvmStatic
    fun intMap(json: String?): Map<String, Int>? = numberMap(json) { it.asInt }

    private fun <T> numberMap(json: String?, read: (com.google.gson.JsonPrimitive) -> T): Map<String, T>? {
        val root = parse(json) ?: return null
        if (!root.isJsonObject) return null
        val result = HashMap<String, T>()
        for ((key, value) in root.asJsonObject.entrySet()) {
            if (result.size >= MAX_ENTRIES) return null
            if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) result[key] = read(value.asJsonPrimitive)
        }
        return result
    }

    @JvmStatic
    fun bazaar(json: String?): Map<String, BazaarProduct>? {
        val root = parse(json) ?: return null
        if (!root.isJsonObject) return null
        val result = HashMap<String, BazaarProduct>()
        for ((key, value) in root.asJsonObject.entrySet()) {
            if (result.size >= MAX_ENTRIES) return null
            if (!value.isJsonObject) continue
            val product = value.asJsonObject
            result[key] = BazaarProduct(key, optionalDouble(product.get("buyPrice")), optionalDouble(product.get("sellPrice")))
        }
        return result
    }

    /** `displayName -> bazaarName` out of the NEU repo's `constants/attribute_shards.json`, or null when unusable. */
    @JvmStatic
    fun shardNames(json: String?): Map<String, String>? {
        val root = parse(json) ?: return null
        if (!root.isJsonObject) return null
        val list = root.asJsonObject.get("attributes")
        if (list == null || !list.isJsonArray) return null
        val result = HashMap<String, String>()
        for (entry in list.asJsonArray) {
            if (result.size >= MAX_ENTRIES) return null
            if (!entry.isJsonObject) continue
            val name = entry.asJsonObject.get("displayName")
            val id = entry.asJsonObject.get("bazaarName")
            if (name != null && name.isJsonPrimitive && id != null && id.isJsonPrimitive) result[name.asString] = id.asString
        }
        return result
    }

    private fun optionalDouble(element: JsonElement?): Double? =
        if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asDouble else null

    private fun parse(json: String?): JsonElement? {
        if (json.isNullOrBlank() || json.trimStart().startsWith("<")) return null
        return try {
            JsonParser.parseString(json)
        } catch (_: RuntimeException) {
            null
        }
    }
}

/**
 * Skyblock item ids as the price APIs name them. The id forms follow Skyblocker's `ItemUtils.getSkyblockApiId`
 * and `getNeuName` for the item families that need a rewrite (books, pets, potions, runes, party hats and so on).
 */
object SkyblockItemIds {
    private val TIERS = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

    /** The plain Skyblock `id` of the item's custom data, or an empty string. */
    @JvmStatic
    fun id(tag: CompoundTag): String = tag.getStringOr("id", "")

    /** The id the Bazaar and auction APIs use for this item. */
    @JvmStatic
    fun apiId(tag: CompoundTag): String {
        val id = id(tag)
        if (tag.contains("is_shiny")) return "SHINY_$id"
        when (id) {
            "ENCHANTED_BOOK" -> if (tag.contains("enchantments")) {
                val enchants = tag.getCompoundOrEmpty("enchantments")
                val first = enchants.keySet().firstOrNull() ?: ""
                return "ENCHANTMENT_${first.uppercase(Locale.ENGLISH)}_${enchants.getIntOr(first, 0)}"
            }
            "PET" -> {
                val pet = pet(tag)
                if (pet != null) return "LVL_1_${pet.tier}_${pet.type}"
            }
            "POTION" -> {
                val enhanced = if (tag.getBooleanOr("enhanced", false)) "_ENHANCED" else ""
                val extended = if (tag.getBooleanOr("extended", false)) "_EXTENDED" else ""
                val splash = if (tag.getBooleanOr("splash", false)) "_SPLASH" else ""
                if (tag.contains("potion") && tag.contains("potion_level")) {
                    return (tag.getStringOr("potion", "") + "_" + id + "_" + tag.getIntOr("potion_level", 0) +
                        enhanced + extended + splash).uppercase(Locale.ENGLISH)
                }
            }
            "RUNE", "UNIQUE_RUNE" -> if (tag.contains("runes")) {
                val runes = tag.getCompoundOrEmpty("runes")
                val rune = runes.keySet().firstOrNull() ?: ""
                return "${rune.uppercase(Locale.ENGLISH)}_RUNE_${runes.getIntOr(rune, 0)}"
            }
            "NEW_YEAR_CAKE" -> return id + "_" + tag.getIntOr("new_years_cake", 0)
            "PARTY_HAT_CRAB", "PARTY_HAT_CRAB_ANIMATED", "BALLOON_HAT_2024", "BALLOON_HAT_2025", "CAKE_HAT_2026" ->
                return id + "_" + tag.getStringOr("party_hat_color", "").uppercase(Locale.ENGLISH)
            "PARTY_HAT_SLOTH" -> return id + "_" + tag.getStringOr("party_hat_emoji", "").uppercase(Locale.ENGLISH)
            "MIDAS_SWORD" -> if (tag.getIntOr("winning_bid", 0) >= 50_000_000) return id + "_50M"
            "MIDAS_STAFF" -> if (tag.getIntOr("winning_bid", 0) >= 100_000_000) return id + "_100M"
            "FACTION_RABBIT" -> return id + "_" + tag.getStringOr("faction_rabbit_id", "").uppercase(Locale.ENGLISH)
        }
        return id
    }

    /**
     * The Bazaar id of an attribute shard from its item name (`Fenlord Shard`, `Fenlord Shard x3`, `BUY Fenlord Shard`,
     * or the bare Hunting Box name), or null when the name is not a known shard. Same rule as Skyblocker's
     * `Attributes.getAttributeFromItemName`.
     */
    @JvmStatic
    fun shardApiId(itemName: String, shards: Map<String, String>): String? {
        var name = itemName.removePrefix("BUY ").removePrefix("SELL ")
        val index = name.indexOf("Shard")
        if (index > 0) name = name.substring(0, index - 1)
        return shards[name]
    }

    /** The id NEU and Skyblocker's recipe data use (`TYPE;tier` for pets, `ENCHANT;level` for books, and so on). */
    @JvmStatic
    fun neuName(tag: CompoundTag): String {
        val id = id(tag)
        return when (id) {
            "ENCHANTED_BOOK" -> {
                val enchants = tag.getCompoundOrEmpty("enchantments")
                val first = enchants.keySet().firstOrNull() ?: ""
                "${first.uppercase(Locale.ENGLISH)};${enchants.getIntOr(first, 0)}"
            }
            "PET" -> pet(tag)?.neuName ?: id
            "RUNE", "UNIQUE_RUNE" -> {
                val runes = tag.getCompoundOrEmpty("runes")
                val rune = runes.keySet().firstOrNull() ?: ""
                "${rune.uppercase(Locale.ENGLISH)}_RUNE;${runes.getIntOr(rune, 0)}"
            }
            "POTION" -> "POTION_${tag.getStringOr("potion", "").uppercase(Locale.ENGLISH)};${tag.getIntOr("potion_level", 0)}"
            "PARTY_HAT_CRAB", "BALLOON_HAT_2024", "BALLOON_HAT_2025", "CAKE_HAT_2026" ->
                id + "_" + tag.getStringOr("party_hat_color", "").uppercase(Locale.ENGLISH)
            "PARTY_HAT_CRAB_ANIMATED" ->
                "PARTY_HAT_CRAB_" + tag.getStringOr("party_hat_color", "").uppercase(Locale.ENGLISH) + "_ANIMATED"
            "PARTY_HAT_SLOTH" -> id + "_" + tag.getStringOr("party_hat_emoji", "").uppercase(Locale.ENGLISH)
            "FACTION_RABBIT" -> id + "_" + tag.getStringOr("faction_rabbit_id", "").uppercase(Locale.ENGLISH)
            else -> id.replace(":", "-")
        }
    }

    class Pet(val type: String, val tier: String, val exp: Double) {
        /** George and the NEU repo name a pet `TYPE;tierIndex`. */
        val neuName: String get() = "$type;${TIERS.indexOf(tier).coerceAtLeast(0)}"
    }

    /** The pet described by a `PET` item's `petInfo` JSON string, or null. */
    @JvmStatic
    fun pet(tag: CompoundTag): Pet? {
        if (id(tag) != "PET") return null
        val text = tag.getStringOr("petInfo", "")
        if (text.isEmpty()) return null
        return try {
            val info = JsonParser.parseString(text).asJsonObject
            val type = info.get("type")?.asString ?: return null
            val tier = info.get("tier")?.asString ?: return null
            Pet(type, tier, info.get("exp")?.asDouble ?: 0.0)
        } catch (_: RuntimeException) {
            null
        }
    }
}
