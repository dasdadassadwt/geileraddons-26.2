package geiler.addons.client.pricing

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One crafting or forge recipe: [output] items come out of [inputs] (item id to amount). */
class Recipe(val output: Double, val inputs: Map<String, Double>)

/**
 * Recipes out of the NotEnoughUpdates item repository (`items/<ID>.json`) and the crafting cost they imply
 * (Skyblocker's `CraftPriceTooltip`, LGPL-3.0). The cost rules follow Skyblocker: the first valid recipe, the
 * cheaper of buying and crafting each ingredient, a depth limit of 15. Written independently.
 */
object NeuRecipes {
    const val COINS = "SKYBLOCK_COIN"
    private const val MAX_DEPTH = 15

    /** The recipe a repository item file describes, or null; only crafting grids and forge recipes count. */
    @JvmStatic
    fun parseItem(json: String): Pair<String, Recipe>? {
        val root = try {
            JsonParser.parseString(json)
        } catch (_: RuntimeException) {
            return null
        }
        if (!root.isJsonObject) return null
        val item = root.asJsonObject
        val id = item.get("internalname")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        item.get("recipe")?.takeIf { it.isJsonObject }?.let { grid(id, it.asJsonObject) }?.let { return id to it }
        val recipes = item.get("recipes")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        for (element in recipes) {
            if (!element.isJsonObject) continue
            val recipe = element.asJsonObject
            val parsed = when (recipe.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
                "crafting" -> grid(id, recipe)
                "forge" -> forge(id, recipe)
                else -> null
            }
            if (parsed != null) return id to parsed
        }
        return null
    }

    private fun grid(id: String, recipe: JsonObject): Recipe? {
        val inputs = LinkedHashMap<String, Double>()
        for (cell in listOf("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3")) {
            val (ingredient, amount) = ingredient(recipe.get(cell)) ?: continue
            inputs.merge(ingredient, amount, Double::plus)
        }
        if (inputs.isEmpty()) return null
        return Recipe(count(recipe), inputs)
    }

    private fun forge(id: String, recipe: JsonObject): Recipe? {
        val inputs = LinkedHashMap<String, Double>()
        for (element in recipe.get("inputs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null) {
            val (ingredient, amount) = ingredient(element) ?: continue
            inputs.merge(ingredient, amount, Double::plus)
        }
        if (inputs.isEmpty()) return null
        return Recipe(count(recipe), inputs)
    }

    private fun count(recipe: JsonObject): Double =
        recipe.get("count")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it > 0 } ?: 1.0

    /** `ID:amount` (amount defaults to 1); empty cells give null. */
    private fun ingredient(element: JsonElement?): Pair<String, Double>? {
        if (element == null || !element.isJsonPrimitive) return null
        val text = element.asString
        if (text.isEmpty()) return null
        val colon = text.lastIndexOf(':')
        if (colon > 0) {
            val amount = text.substring(colon + 1).toDoubleOrNull()
            if (amount != null && amount > 0) return text.substring(0, colon) to amount
        }
        return text to 1.0
    }

    /** NEU names enchanted books `NAME;level`; the Bazaar names them `ENCHANTMENT_NAME_level`. */
    fun priceKey(id: String, known: (String) -> Boolean): String {
        if (known(id)) return id
        val semicolon = id.indexOf(';')
        if (semicolon > 0) {
            val book = "ENCHANTMENT_${id.substring(0, semicolon)}_${id.substring(semicolon + 1)}"
            if (known(book)) return book
        }
        return id
    }

    /** Crafting costs with a shared cache; [buyOrder] prices Bazaar ingredients at the buy-order price. */
    class Calculator(
        private val recipes: Map<String, Recipe>,
        private val bazaar: Map<String, BazaarProduct>,
        private val lowestBin: Map<String, Double>,
        private val buyOrder: Boolean,
    ) {
        private val cache = HashMap<String, Double>().also { it[COINS] = 1.0; it[""] = 0.0 }

        /** Cost of one [id] when crafted, or -1 when it has no usable recipe. */
        fun itemCost(id: String): Double = itemCost(id, 0)

        private fun itemCost(id: String, depth: Int): Double {
            val recipe = recipes[id] ?: return -1.0
            return recipeCost(recipe, depth + 1) / recipe.output
        }

        private fun recipeCost(recipe: Recipe, depth: Int): Double {
            if (depth >= MAX_DEPTH) return -1.0
            var total = 0.0
            for ((input, amount) in recipe.inputs) {
                val cached = cache[input]
                if (cached != null) {
                    total += cached * amount
                    continue
                }
                var cost = 0.0
                val key = priceKey(input) { bazaar.containsKey(it) || lowestBin.containsKey(it) }
                val product = bazaar[key]
                if (product != null) {
                    cost = (if (buyOrder) product.buyPrice else product.sellPrice) ?: 0.0
                } else {
                    lowestBin[key]?.let { cost = it }
                }
                if (cost > 0) cache[input] = cost
                val crafted = itemCost(input, depth + 1)
                if (crafted > 0) {
                    cost = if (cost > 0) minOf(cost, crafted) else crafted
                    cache[input] = cost
                }
                total += cost * amount
            }
            return total
        }

        fun clear() {
            cache.clear()
            cache[COINS] = 1.0
            cache[""] = 0.0
        }
    }
}
