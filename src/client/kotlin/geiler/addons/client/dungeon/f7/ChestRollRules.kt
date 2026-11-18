package geiler.addons.client.dungeon.f7

import com.google.gson.JsonParser
import java.util.Random

/**
 * Pure rules of the Chest Roll case-opening animation: chest tier and floor parsing, the card strip, landing maths, the
 * timeline of one roll and the loot-pool file reader. No Minecraft types, so it is covered by offline checks.
 */
object ChestRollRules {
    /** Dungeon reward chest tiers that can be rolled. Iron exists in the title list but is not a roll tier. */
    enum class Tier(val label: String) {
        WOOD("Wood"), GOLD("Gold"), DIAMOND("Diamond"), EMERALD("Emerald"), OBSIDIAN("Obsidian"), BEDROCK("Bedrock")
    }

    enum class Phase { SPIN, SETTLE, REVEAL, DONE }

    /** One card of the strip. [rarity] is 0 (common) to 5 (mythic); [icon] is a registry id used when there is no real stack. */
    class Card(val name: String, val rarity: Int, val weight: Double, val icon: String)

    private val TIER_TITLE = Regex("""^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$""")
    private val FLOOR_TITLE = Regex("""^(Master )?Catacombs - (?:Floor ([IVX]+|\d+)|Entrance)$""")
    private val ESSENCE = Regex("""^\w+ Essence x\d+$""")
    private val RARITIES = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

    @JvmStatic
    fun tierOf(title: String?): Tier? {
        val label = TIER_TITLE.matchEntire(title ?: return null)?.groupValues?.get(1) ?: return null
        return Tier.entries.first { it.label == label }
    }

    /** "F7" or "M7" for a Croesus preview title like "Master Catacombs - Floor VII"; "F0" is the Entrance. */
    @JvmStatic
    fun floorKey(previewTitle: String?): String? {
        val match = FLOOR_TITLE.matchEntire(previewTitle ?: return null) ?: return null
        val number = match.groupValues[2]
        val floor = if (number.isEmpty()) 0 else number.toIntOrNull() ?: roman(number)
        return (if (match.groupValues[1].isNotEmpty()) "M" else "F") + floor
    }

    private fun roman(text: String): Int {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10)
        var total = 0
        for ((i, c) in text.withIndex()) {
            val v = values.getValue(c)
            total += if (i + 1 < text.length && v < values.getValue(text[i + 1])) -v else v
        }
        return total
    }

    @JvmStatic
    fun rarityIndex(name: String): Int = RARITIES.indexOf(name.uppercase()).coerceAtLeast(0)

    /**
     * The strip of [size] cards with [winner] at [winnerIndex]. Every third filler card is drawn by drop weight, the others
     * uniformly, so rare items still fly by. An empty pool fills the strip with the winner.
     */
    @JvmStatic
    fun buildStrip(winner: Card, pool: List<Card>, size: Int, winnerIndex: Int, rng: Random): List<Card> {
        require(winnerIndex in 0 until size) { "winner index $winnerIndex outside strip of $size" }
        val total = pool.sumOf { it.weight }
        return List(size) { i ->
            when {
                i == winnerIndex || pool.isEmpty() -> winner
                i % 3 == 0 && total > 0 -> weighted(pool, total, rng)
                else -> pool[rng.nextInt(pool.size)]
            }
        }
    }

    private fun weighted(pool: List<Card>, total: Double, rng: Random): Card {
        var x = rng.nextDouble() * total
        for (card in pool) {
            x -= card.weight
            if (x <= 0) return card
        }
        return pool.last()
    }

    /** Track x so the line at [center] ends on the winner card; [jitter] in -1..1 moves the line inside the card. */
    @JvmStatic
    fun landingTrackX(winnerIndex: Int, pitch: Double, width: Double, center: Double, jitter: Double): Double =
        center - (winnerIndex * pitch + width / 2) + jitter.coerceIn(-1.0, 1.0) * (width / 2 - EDGE_MARGIN)

    /** Index of the card under the line at [center] for a track whose first card starts at [trackX]. */
    @JvmStatic
    fun cardUnder(trackX: Double, center: Double, pitch: Double): Int = Math.floor((center - trackX) / pitch).toInt()

    @JvmStatic
    fun easeOut(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        val inv = 1.0 - c
        return 1.0 - inv * inv * inv * inv
    }

    /** The main item of a chest: highest price, essence only when nothing else is there; -1 when empty. */
    @JvmStatic
    fun mainIndex(names: List<String>, prices: List<Double>): Int {
        var best = -1
        var bestEssence = -1
        for (i in names.indices) {
            val price = prices.getOrElse(i) { 0.0 }
            if (ESSENCE.matches(names[i])) {
                if (bestEssence < 0 || price > prices.getOrElse(bestEssence) { 0.0 }) bestEssence = i
            } else if (best < 0 || price > prices.getOrElse(best) { 0.0 }) {
                best = i
            }
        }
        return if (best >= 0) best else bestEssence
    }

    /** Timing of one roll in milliseconds: the spin, a short settle on the winner, then the reveal. */
    class Timeline(private val spinMs: Long, private val settleMs: Long = SETTLE_MS, private val revealMs: Long = REVEAL_MS) {
        val revealStart = spinMs + settleMs
        private val end = revealStart + revealMs

        fun phase(t: Long): Phase = when {
            t < spinMs -> Phase.SPIN
            t < revealStart -> Phase.SETTLE
            t < end -> Phase.REVEAL
            else -> Phase.DONE
        }

        /** Where a click or Esc jumps to: spin and settle go to the reveal, the reveal ends the roll. */
        fun skip(t: Long): Long = when (phase(t)) {
            Phase.SPIN, Phase.SETTLE -> revealStart
            Phase.REVEAL -> end
            Phase.DONE -> t
        }

        fun spinProgress(t: Long): Double = easeOut(if (spinMs <= 0) 1.0 else t.toDouble() / spinMs)
    }

    /** Loot pools by floor key ("F7", "M7"), then tier label ("Bedrock"). Rejects a non-positive weight. */
    @JvmStatic
    fun parseLoot(json: String): Map<String, Map<String, List<Card>>> {
        val floors = JsonParser.parseString(json).asJsonObject.getAsJsonObject("floors")
            ?: throw IllegalArgumentException("Missing \"floors\" object")
        return floors.entrySet().associate { (floor, tiers) ->
            floor to tiers.asJsonObject.entrySet().associate { (tier, items) ->
                tier to items.asJsonArray.map {
                    val o = it.asJsonObject
                    val weight = o.get("w").asDouble
                    require(weight > 0) { "non-positive weight in $floor/$tier: ${o.get("name")}" }
                    Card(o.get("name").asString, rarityIndex(o.get("r").asString), weight, o.get("icon").asString)
                }
            }
        }
    }

    private const val EDGE_MARGIN = 4.0
    const val SETTLE_MS = 700L
    const val REVEAL_MS = 3500L
}
