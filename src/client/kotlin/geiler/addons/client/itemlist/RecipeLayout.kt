package geiler.addons.client.itemlist

import geiler.addons.client.pricing.NeuRecipes
import geiler.addons.client.pricing.PriceSnapshot

/** One slot of a recipe view at cell coordinates ([x], [y]); [label] is a drop chance or the like. */
class Cell(val id: String, val amount: Double, val x: Int, val y: Int, val label: String = "")

/** Where a recipe's slots, arrow and text lines go, in 18-pixel cells. */
class Layout(val inputs: List<Cell>, val outputs: List<Cell>, val arrowX: Int, val arrowY: Int, val cols: Int, val rows: Int, val lines: List<String>)

object RecipeLayout {
    const val MAX_DROPS = 6
    private const val MAX_COLS = 3

    @JvmStatic
    fun of(recipe: ItemRecipe): Layout {
        val lines = ArrayList<String>()
        when (recipe.type) {
            ItemListData.CRAFTING -> {
                val inputs = ArrayList<Cell>()
                val cells = (recipe.extra["grid"] ?: "").split(",")
                for ((i, text) in cells.withIndex()) {
                    val ingredient = ItemListData.ingredient(com.google.gson.JsonPrimitive(text)) ?: continue
                    inputs.add(Cell(ingredient.id, ingredient.amount, i % 3, i / 3))
                }
                val out = recipe.outputs.first()
                return Layout(inputs, listOf(Cell(out.id, out.amount, 4, 1)), 3, 1, 5, 3, lines)
            }
            ItemListData.DROPS -> {
                val chances = (recipe.extra["chances"] ?: "").split(",")
                val shown = recipe.outputs.take(MAX_DROPS)
                val drops = shown.mapIndexed { i, o -> Cell(o.id, o.amount, 2, i, chances.getOrElse(i) { "" }) }
                recipe.extra["level"]?.let { lines.add("Level $it") }
                recipe.extra["coins"]?.let { lines.add("Coins: $it") }
                if (recipe.outputs.size > shown.size) lines.add("+${recipe.outputs.size - shown.size} more drops")
                val mob = recipe.inputs.first()
                return Layout(listOf(Cell(mob.id, mob.amount, 0, 0)), drops, 1, 0, 3, maxOf(shown.size, 1), lines)
            }
        }
        val cols = minOf(MAX_COLS, maxOf(recipe.inputs.size, 1))
        val rows = (recipe.inputs.size + cols - 1) / cols
        val inputs = recipe.inputs.mapIndexed { i, ing -> Cell(ing.id, ing.amount, i % cols, i / cols) }
        val y = maxOf(rows - 1, 0) / 2
        val outputs = recipe.outputs.mapIndexed { i, o -> Cell(o.id, o.amount, cols + 1, y + i) }
        when (recipe.type) {
            ItemListData.FORGE -> recipe.extra["duration"]?.toDoubleOrNull()?.let { lines.add("Duration: " + duration(it)) }
            ItemListData.KAT -> {
                recipe.extra["coins"]?.toDoubleOrNull()?.let { lines.add("Cost: " + SearchCalculator.format(it) + " coins") }
                recipe.extra["time"]?.toDoubleOrNull()?.let { lines.add("Time: " + SearchCalculator.format(it) + " h") }
            }
            ItemListData.ESSENCE -> recipe.extra["star"]?.let { lines.add("Star $it") }
        }
        return Layout(inputs, outputs, cols, y, cols + 2, maxOf(rows, outputs.size), lines)
    }

    /** "1h 5m 3s" for a time in seconds. */
    @JvmStatic
    fun duration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return listOfNotNull(if (h > 0) "${h}h" else null, if (m > 0) "${m}m" else null, if (s > 0 || total == 0L) "${s}s" else null).joinToString(" ")
    }
}

/** Unit prices for the cost breakdown and the search calculator, from the Price Display snapshot. */
object ItemPrices {
    /** What one [id] costs: Bazaar (buy order or instant, per [buyInstantly]), then Lowest BIN, then NPC; null when unknown. */
    @JvmStatic
    fun unit(id: String, snapshot: PriceSnapshot, buyInstantly: Boolean): Double? {
        if (id == NeuRecipes.COINS) return 1.0
        val bazaar = snapshot.bazaar
        val bin = snapshot.lowestBin
        val key = NeuRecipes.priceKey(id) { bazaar?.containsKey(it) == true || bin?.containsKey(it) == true }
        bazaar?.get(key)?.let { p -> (if (buyInstantly) p.buyPrice else p.sellPrice)?.takeIf { it > 0 }?.let { return it } }
        bin?.get(key)?.takeIf { it > 0 }?.let { return it }
        return snapshot.npc?.get(id)?.takeIf { it > 0 }
    }

    /** `bz`, `lb` or `npc` price of [id] only from that one source. */
    @JvmStatic
    fun source(function: String, id: String, snapshot: PriceSnapshot, buyInstantly: Boolean): Double? = when (function) {
        "bz" -> snapshot.bazaar?.get(NeuRecipes.priceKey(id) { snapshot.bazaar?.containsKey(it) == true })
            ?.let { if (buyInstantly) it.buyPrice else it.sellPrice }
        "lb" -> snapshot.lowestBin?.get(id)
        "npc" -> snapshot.npc?.get(id)
        else -> null
    }
}
