package geiler.addons.client.itemlist

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import geiler.addons.client.inventory.SkyblockRarity
import geiler.addons.client.pricing.Recipe

/** One listed thing: a SkyBlock item, pet, enchanted book, mob, NPC or vanilla item. [lore] keeps colour codes. */
class Entry(
    val id: String,
    val name: String,
    val lore: List<String>,
    val mcId: String,
    val damage: Int,
    val nbt: String,
    val rarity: String,
    val type: String,
    val category: String,
    val wikiUrl: String?,
) {
    val plainName: String = ItemListData.plain(name)

    /** Lowercase name for sorting. */
    val sortName: String = plainName.lowercase()

    /** The rarity's background color, worked out once here so drawing never parses text; null when the entry has no rarity. */
    val rarityColor: SkyblockRarity? = SkyblockRarity.entries.firstOrNull { it.display == rarity }
}

class Ingredient(val id: String, val amount: Double)

/** One recipe of any kind; [extra] holds forge time, Kat cost, drop chances, essence star and the like. */
class ItemRecipe(val type: String, val inputs: List<Ingredient>, val outputs: List<Ingredient>, val extra: Map<String, String>)

class Parsed(val entry: Entry, val recipes: List<ItemRecipe>)

/** Ingredient and drop lookups, built once when the repository loads. */
class RecipeIndex(recipes: List<ItemRecipe>) {
    private val byOutput = HashMap<String, MutableList<ItemRecipe>>()
    private val byInput = HashMap<String, MutableList<ItemRecipe>>()

    init {
        for (recipe in recipes) {
            for (output in recipe.outputs) byOutput.getOrPut(output.id) { ArrayList() }.add(recipe)
            for (input in recipe.inputs) byInput.getOrPut(input.id) { ArrayList() }.add(recipe)
        }
    }

    /** How to get [id]: recipes producing it, plus a mob's own drop table. */
    fun recipesFor(id: String): List<ItemRecipe> {
        val own = byInput[id]?.filter { it.type == ItemListData.DROPS } ?: emptyList()
        return (byOutput[id] ?: emptyList()) + own
    }

    /** What [id] is used in: recipes that consume it (a mob's drops are its recipes, not uses). */
    fun usesOf(id: String): List<ItemRecipe> = byInput[id]?.filter { it.type != ItemListData.DROPS } ?: emptyList()
}

/**
 * Parsers for the NotEnoughUpdates item repository (`items/<ID>.json`, `constants/essencecosts.json`) into [Entry] and
 * [ItemRecipe]. Recipe types and field names follow the repository's own format; written independently.
 */
object ItemListData {
    const val CRAFTING = "crafting"
    const val FORGE = "forge"
    const val NPC_SHOP = "npc_shop"
    const val TRADE = "trade"
    const val DROPS = "drops"
    const val KAT = "katgrade"
    const val ESSENCE = "essence"
    val TYPE_ORDER = listOf(CRAFTING, FORGE, NPC_SHOP, TRADE, DROPS, KAT, ESSENCE)

    const val SORT_BEST = "BEST"
    const val SORT_NAME_ASC = "NAME_ASC"
    const val SORT_NAME_DESC = "NAME_DESC"
    const val SORT_PRICE_ASC = "PRICE_ASC"
    const val SORT_PRICE_DESC = "PRICE_DESC"
    val SORTS = listOf(SORT_BEST, SORT_NAME_ASC, SORT_NAME_DESC, SORT_PRICE_ASC, SORT_PRICE_DESC)

    /** The sort [step] places after [mode] (wraps); an unknown (saved) mode restarts at the first real sort. */
    @JvmStatic
    fun nextSort(mode: String, step: Int): String {
        val at = SORTS.indexOf(mode)
        if (at < 0) return SORT_NAME_ASC
        return SORTS[(at + step + SORTS.size) % SORTS.size]
    }

    const val CAT_ALL = "ALL"
    const val CAT_WEAPON = "WEAPON"
    const val CAT_MATERIAL = "MATERIAL"
    const val CAT_PET = "PET"
    const val CAT_BOOK = "BOOK"
    const val CAT_MOB = "MOB"
    const val CAT_NPC = "NPC"
    const val CAT_VANILLA = "VANILLA"

    private val COLOURS = Regex("§[0-9a-fk-orA-FK-OR]")
    private val RARITIES = listOf(
        "VERY SPECIAL", "SPECIAL", "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "ULTIMATE", "SUPREME",
    )
    private val WEAPONS = setOf("SWORD", "LONGSWORD", "BOW", "SHORTBOW", "WAND", "GAUNTLET", "DUNGEON SWORD", "DUNGEON BOW")
    private val MOB_SUFFIXES = listOf("_MONSTER", "_ANIMAL", "_BOSS", "_MINIBOSS", "_SEA_CREATURE", "_MOB")

    @JvmStatic
    fun plain(text: String): String = COLOURS.replace(text, "")

    @JvmStatic
    fun parseItem(json: String): Parsed? {
        val root = try {
            JsonParser.parseString(json)
        } catch (_: RuntimeException) {
            return null
        }
        if (!root.isJsonObject) return null
        val item = root.asJsonObject
        val id = item.string("internalname") ?: return null
        val lore = item.get("lore")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: emptyList()
        val (rarity, type) = rarityAndType(lore.lastOrNull()?.let { plain(it) } ?: "")
        val wiki = item.get("info")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstNotNullOfOrNull { e -> if (e.isJsonPrimitive && e.asString.startsWith("http")) e.asString else null }
        val entry = Entry(
            id, item.string("displayname") ?: id, lore, item.string("itemid") ?: "minecraft:barrier",
            item.get("damage")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0,
            item.string("nbttag") ?: "", rarity, type, "", wiki,
        )
        val recipes = ArrayList<ItemRecipe>()
        item.get("recipe")?.takeIf { it.isJsonObject }?.let { grid(id, it.asJsonObject) }?.let { recipes.add(it) }
        for (element in item.get("recipes")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()) {
            if (element.isJsonObject) recipe(id, element.asJsonObject)?.let { recipes.add(it) }
        }
        return Parsed(withCategory(entry, recipes), recipes)
    }

    private fun withCategory(e: Entry, recipes: List<ItemRecipe>): Entry {
        val category = when {
            e.mcId == "minecraft:enchanted_book" && e.id.contains(';') -> CAT_BOOK
            e.id.endsWith("_NPC") -> CAT_NPC
            MOB_SUFFIXES.any { e.id.endsWith(it) } || recipes.any { it.type == DROPS && it.inputs.any { i -> i.id == e.id } } -> CAT_MOB
            e.id.contains(';') && e.rarity.isNotEmpty() -> CAT_PET
            e.type in WEAPONS -> CAT_WEAPON
            else -> CAT_MATERIAL
        }
        return Entry(e.id, e.name, e.lore, e.mcId, e.damage, e.nbt, e.rarity, e.type, category, e.wikiUrl)
    }

    /** "LEGENDARY DUNGEON SWORD" gives ("LEGENDARY", "DUNGEON SWORD"); a line that is no rarity gives two blanks. */
    @JvmStatic
    fun rarityAndType(line: String): Pair<String, String> {
        val text = line.trim().uppercase()
        for (rarity in RARITIES) {
            if (text == rarity) return rarity to ""
            if (text.startsWith("$rarity ")) return rarity to text.substring(rarity.length + 1).trim()
        }
        return "" to ""
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun recipe(output: String, json: JsonObject): ItemRecipe? = when (json.string("type")) {
        CRAFTING -> grid(output, json)
        FORGE -> forge(output, json)
        NPC_SHOP -> shop(NPC_SHOP, json.get("cost"), json.get("result"))
        TRADE -> shop(TRADE, json.get("cost"), json.get("result"))
        DROPS -> drops(output, json)
        KAT -> kat(json)
        else -> null
    }

    private fun grid(id: String, json: JsonObject): ItemRecipe? {
        val cells = listOf("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3").map { json.string(it) ?: "" }
        val inputs = cells.mapNotNull { ingredient(JsonPrimitive(it)) }
        if (inputs.isEmpty()) return null
        return ItemRecipe(CRAFTING, merge(inputs), listOf(Ingredient(json.string("overrideOutputId") ?: id, count(json))), mapOf("grid" to cells.joinToString(",")))
    }

    private fun forge(id: String, json: JsonObject): ItemRecipe? {
        val inputs = (json.get("inputs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null).mapNotNull { ingredient(it) }
        if (inputs.isEmpty()) return null
        val extra = HashMap<String, String>()
        json.get("duration")?.takeIf { it.isJsonPrimitive }?.let { extra["duration"] = it.asString }
        return ItemRecipe(FORGE, merge(inputs), listOf(Ingredient(json.string("overrideOutputId") ?: id, count(json))), extra)
    }

    private fun shop(type: String, cost: JsonElement?, result: JsonElement?): ItemRecipe? {
        val inputs = when {
            cost == null -> emptyList()
            cost.isJsonArray -> cost.asJsonArray.mapNotNull { ingredient(it) }
            else -> listOfNotNull(ingredient(cost))
        }
        val output = ingredient(result) ?: return null
        if (inputs.isEmpty()) return null
        return ItemRecipe(type, inputs, listOf(output), emptyMap())
    }

    private fun drops(mob: String, json: JsonObject): ItemRecipe? {
        val outputs = ArrayList<Ingredient>()
        val chances = ArrayList<String>()
        for (drop in json.get("drops")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null) {
            val ingredient = if (drop.isJsonObject) ingredient(drop.asJsonObject.get("id")) else ingredient(drop)
            if (ingredient == null) continue
            outputs.add(ingredient)
            chances.add(if (drop.isJsonObject) drop.asJsonObject.string("chance") ?: "" else "")
        }
        if (outputs.isEmpty()) return null
        val extra = HashMap<String, String>()
        extra["chances"] = chances.joinToString(",")
        for (key in listOf("level", "coins", "xp", "combat_xp", "name")) json.string(key)?.let { extra[key] = it }
        return ItemRecipe(DROPS, listOf(Ingredient(mob, 1.0)), outputs, extra)
    }

    private fun kat(json: JsonObject): ItemRecipe? {
        val input = ingredient(json.get("input")) ?: return null
        val output = ingredient(json.get("output")) ?: return null
        val items = (json.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).mapNotNull { ingredient(it) }
        val extra = HashMap<String, String>()
        json.string("coins")?.let { extra["coins"] = it }
        json.string("time")?.let { extra["time"] = it }
        return ItemRecipe(KAT, listOf(input) + items, listOf(output), extra)
    }

    /** Essence upgrades: one recipe per star level of every item in `essencecosts.json`. */
    @JvmStatic
    fun parseEssence(json: String): List<ItemRecipe> {
        val root = try {
            JsonParser.parseString(json)
        } catch (_: RuntimeException) {
            return emptyList()
        }
        if (!root.isJsonObject) return emptyList()
        val result = ArrayList<ItemRecipe>()
        for ((id, value) in root.asJsonObject.entrySet()) {
            if (!value.isJsonObject) continue
            val entry = value.asJsonObject
            val essence = "ESSENCE_" + (entry.string("type") ?: continue).uppercase()
            val items = entry.get("items")?.takeIf { it.isJsonObject }?.asJsonObject
            for (star in 1..15) {
                val cost = entry.get(star.toString())?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: continue
                val inputs = ArrayList<Ingredient>()
                if (cost > 0) inputs.add(Ingredient(essence, cost))
                items?.get(star.toString())?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { e -> ingredient(e)?.let { inputs.add(it) } }
                if (inputs.isNotEmpty()) result.add(ItemRecipe(ESSENCE, inputs, listOf(Ingredient(id, 1.0)), mapOf("star" to star.toString())))
            }
        }
        return result
    }

    private fun count(json: JsonObject): Double =
        json.get("count")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it > 0 } ?: 1.0

    /** `ID:amount` (amount defaults to 1); empty text gives null. */
    @JvmStatic
    fun ingredient(element: JsonElement?): Ingredient? {
        if (element == null || !element.isJsonPrimitive) return null
        val text = element.asString
        if (text.isEmpty()) return null
        val colon = text.lastIndexOf(':')
        if (colon > 0) {
            val amount = text.substring(colon + 1).toDoubleOrNull()
            if (amount != null && amount > 0) return Ingredient(text.substring(0, colon), amount)
        }
        return Ingredient(text, 1.0)
    }

    private fun merge(inputs: List<Ingredient>): List<Ingredient> {
        val sums = LinkedHashMap<String, Double>()
        for (i in inputs) sums.merge(i.id, i.amount, Double::plus)
        return sums.map { Ingredient(it.key, it.value) }
    }

    /** The crafting-cost table Price Display uses: the first crafting or forge recipe of each output. */
    @JvmStatic
    fun craftTable(recipes: List<ItemRecipe>): Map<String, Recipe> {
        val table = HashMap<String, Recipe>()
        for (recipe in recipes) {
            if (recipe.type != CRAFTING && recipe.type != FORGE) continue
            val output = recipe.outputs.firstOrNull() ?: continue
            table.putIfAbsent(output.id, Recipe(output.amount, recipe.inputs.associate { it.id to it.amount }))
        }
        return table
    }
}
