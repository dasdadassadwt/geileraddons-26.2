package geiler.addons.client.enchanting

import com.google.gson.JsonParser

/**
 * Enchant lore parsing, level tiers and missing-enchant lists. The data (`data/skyblock-enchants.json`) is built from
 * SkyHanni-REPO `constants/Enchants.json` (good and max levels) and NotEnoughUpdates-REPO `constants/enchants.json`
 * (enchants per item type and mutually exclusive groups); see `docs/provenance/external-sources.md`.
 */
object EnchantRules {
    enum class Tier { ULTIMATE, PERFECT, GREAT, GOOD, POOR }

    class Enchant(val id: String, val name: String, val good: Int, val max: Int, val kind: String)

    /** [start] until [end] spans the enchant name and level in the line; a stacking counter after it is not included. */
    class Token(val start: Int, val end: Int, val enchant: Enchant, val level: Int)

    class Data(val enchants: Map<String, Enchant>, val pools: Map<String, Set<String>>, val exclusive: List<Set<String>>) {
        val byId: Map<String, Enchant> = enchants.values.associateBy { it.id }
    }

    private val segment = Regex("^(.+?) ([IVXLCDM]+|\\d+)((?: [\\d,.]+[kKmMbB]?)?)$")
    private val romanValues = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

    private val shipped: Data by lazy {
        val resource = EnchantRules::class.java.getResourceAsStream("/data/skyblock-enchants.json")
        resource?.use { load(String(it.readAllBytes(), Charsets.UTF_8)) } ?: Data(emptyMap(), emptyMap(), emptyList())
    }

    @JvmStatic
    fun data(): Data = shipped

    /** Malformed data gives an empty table, so every feature quietly does nothing. */
    @JvmStatic
    fun load(json: String): Data = try {
        val root = JsonParser.parseString(json).asJsonObject
        val enchants = root.getAsJsonObject("enchants").entrySet().associate { (key, value) ->
            val e = value.asJsonObject
            key to Enchant(e.get("id").asString, e.get("name").asString, e.get("good").asInt, e.get("max").asInt, e.get("kind").asString)
        }
        val pools = HashMap<String, Set<String>>()
        for ((type, ids) in root.getAsJsonObject("pools").entrySet()) {
            val key = type.replace('_', ' ').uppercase()
            pools[key] = (pools[key].orEmpty() + ids.asJsonArray.map { it.asString }).toCollection(LinkedHashSet())
        }
        val exclusive = root.getAsJsonArray("exclusive").map { group -> group.asJsonArray.map { it.asString }.toSet() }
        Data(enchants, pools, exclusive)
    } catch (_: RuntimeException) {
        Data(emptyMap(), emptyMap(), emptyList())
    }

    @JvmStatic
    fun roman(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        var total = 0
        for ((i, c) in text.withIndex()) {
            val value = romanValues[c] ?: return null
            val next = text.getOrNull(i + 1)?.let { romanValues[it] } ?: 0
            total += if (value < next) -value else value
        }
        return total
    }

    /** The known enchants named in one lore line (`Sharpness VII, Critical VII`), in order. */
    @JvmStatic
    fun tokens(plain: String, data: Data): List<Token> {
        val found = ArrayList<Token>()
        var offset = 0
        for (part in plain.split(", ")) {
            val match = segment.matchEntire(part.trim())
            if (match != null) {
                val enchant = data.enchants[match.groupValues[1].lowercase()]
                val level = roman(match.groupValues[2])
                if (enchant != null && level != null) {
                    val start = offset + part.indexOf(part.trim())
                    found.add(Token(start, start + match.groupValues[1].length + 1 + match.groupValues[2].length, enchant, level))
                }
            }
            offset += part.length + 2
        }
        return found
    }

    /** SkyHanni's scheme: max is perfect, above the good level great, the good level good, anything lower poor. */
    @JvmStatic
    fun tier(enchant: Enchant, level: Int): Tier = when {
        enchant.kind == "ULTIMATE" -> Tier.ULTIMATE
        level >= enchant.max -> Tier.PERFECT
        level > enchant.good -> Tier.GREAT
        level == enchant.good -> Tier.GOOD
        else -> Tier.POOR
    }

    /** The item type named by the rarity line (`LEGENDARY DUNGEON SWORD`), found from the bottom of the lore up. */
    @JvmStatic
    fun itemType(plainLore: List<String>, data: Data): String? {
        val types = data.pools.keys.sortedByDescending { it.length }
        for (line in plainLore.asReversed()) {
            val text = line.trim().uppercase()
            for (type in types) {
                if (text == type || text.endsWith(" $type")) return type
            }
        }
        return null
    }

    /** Display names of the enchants the item can still take; all unowned ultimates collapse into one "Ultimate". */
    @JvmStatic
    fun missing(type: String, present: Set<String>, data: Data): List<String> {
        val pool = data.pools[type] ?: return emptyList()
        val blocked = data.exclusive.filter { group -> group.any { it in present } }.flatten().toSet()
        val names = ArrayList<String>()
        var ultimate = false
        for (id in pool) {
            if (id in present || id in blocked) continue
            if (id.startsWith("ultimate_")) {
                ultimate = true
                continue
            }
            names.add(data.byId[id]?.name ?: id.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) })
        }
        val hasUltimate = present.any { it.startsWith("ultimate_") }
        if (ultimate && !hasUltimate) names.add("Ultimate")
        return names
    }
}
