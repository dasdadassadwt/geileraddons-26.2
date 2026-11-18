package geiler.addons.client.hunting

import geiler.addons.client.location.SafariBiome
import java.util.regex.Pattern

/** A Hunter NPC's complete offer: [npc] hands over [critter]'s shard for [item]. */
class TradeOffer(val npc: String, val critter: Critter, val item: String)

/** Where the player stood when the dialog opened. [biome] is null when it is unknown. */
class TradeSpot(val biome: SafariBiome?, val x: Int, val y: Int, val z: Int)

/** An offer found this run, with the spot it is at when known. */
class FoundTrade(val offer: TradeOffer, val spot: TradeSpot?) {
    fun distanceSqr(px: Double, py: Double, pz: Double): Double {
        val s = spot ?: return Double.MAX_VALUE
        val dx = s.x + 0.5 - px
        val dy = s.y + 0.5 - py
        val dz = s.z + 0.5 - pz
        return dx * dx + dy * dy + dz * dz
    }
}

/**
 * Reads the roaming Hunter NPCs' dialogs. The shard and the price arrive as two separate lines and every
 * NPC words both differently, so an offer is any NPC line naming a shard and a price is any NPC line asking
 * for something in exchange; the pending offer is held per speaker.
 */
class TradeParser {
    private val pending = HashMap<String, Critter>()
    private var justRegistered: String? = null

    /** The completed trade once its price line lands, or null. Feed only game lines (never player-typed). */
    fun parse(line: String): TradeOffer? {
        justRegistered = null
        val npcLine = NPC_LINE.matcher(line)
        if (!npcLine.matches()) return null
        val npc = npcLine.group(1).trim()
        val text = npcLine.group(2)

        val price = PRICE.matcher(text)
        if (price.find()) {
            val critter = pending.remove(npc) ?: return null
            val item = price.group(1).trim()
            return if (item.isEmpty()) null else TradeOffer(npc, critter, item)
        }
        // "Shard" alone is not enough - plenty of NPC flavour mentions shards - so a species must be named too.
        if (text.contains("Shard")) {
            val critter = CritterSpecies.findIn(text)
            if (critter != null) {
                if (pending.size >= MAX_PENDING) pending.clear()
                pending[npc] = critter
                justRegistered = npc
            }
        }
        return null
    }

    /** The speaker whose offer the last [parse] registered, so the caller can note where the dialog opened. */
    fun offerJustRegistered(): String? = justRegistered

    fun reset() {
        pending.clear()
        justRegistered = null
    }

    companion object {
        private const val MAX_PENDING = 16
        private val NPC_LINE = Pattern.compile("^\\[NPC] ([^:]{1,40}): (.*)$")
        private val PRICE = Pattern.compile(
            "(?:in exchange for(?:,\\s*say,)?|give m-m-me)\\s+(?:an?|the)\\s+(.+?)\\s*[.!?…]*$",
        )
    }
}

/**
 * The party-chat wording for sharing trades. It matches Critter Mod's so users of either mod see each other:
 * `Hunter Dennis (Icy -106 87 -7): Gimmiegold Shard for Purple Gem` and `Trade taken: Gimmiegold Shard`.
 */
object TradeLines {
    class Shared(val npc: String, val spot: TradeSpot, val critter: Critter, val item: String)

    /** `Party > [MVP+] Name: text` -> (name, text); null for anything else. */
    @JvmStatic
    fun partyLine(line: String): Pair<String, String>? {
        val m = PARTY_LINE.matcher(line)
        return if (m.matches()) m.group(1) to m.group(2) else null
    }

    @JvmStatic
    fun announce(offer: TradeOffer, spot: TradeSpot?): String {
        val where = if (spot == null) "" else {
            val biome = spot.biome?.let { it.displayName() + " " } ?: ""
            " (${biome}${spot.x} ${spot.y} ${spot.z})"
        }
        return "${offer.npc}$where: ${offer.critter.name} Shard for ${offer.item}"
    }

    @JvmStatic
    fun taken(critter: Critter): String = "Trade taken: ${critter.name} Shard"

    @JvmStatic
    fun parseShared(partyText: String): Shared? {
        val m = SHARED.matcher(partyText)
        if (!m.matches()) return null
        val critter = CritterSpecies.byName(m.group(6)) ?: return null
        val x = m.group(3).toIntOrNull() ?: return null
        val y = m.group(4).toIntOrNull() ?: return null
        val z = m.group(5).toIntOrNull() ?: return null
        return Shared(m.group(1), TradeSpot(BiomeStyle.fromName(m.group(2)), x, y, z), critter, m.group(7))
    }

    /** The species a partymate announced taking, or null. */
    @JvmStatic
    fun parseTaken(partyText: String): Critter? {
        val m = SHARED_TAKEN.matcher(partyText)
        return if (m.matches()) CritterSpecies.byName(m.group(1)) else null
    }

    /** The species the game says you were just given (`You have been given a Wumpa!`), or null. */
    @JvmStatic
    fun given(line: String): Critter? {
        val m = GIVEN.matcher(line)
        return if (m.matches()) CritterSpecies.byName(m.group(1)) else null
    }

    private val PARTY_LINE = Pattern.compile("^Party > (?:\\[[^]]+] )?(\\w+): (.+)$")
    private val SHARED = Pattern.compile("^(.+?) \\((?:([A-Za-z]+) )?(-?\\d+) (-?\\d+) (-?\\d+)\\): (.+?) Shard for (.+)$")
    private val SHARED_TAKEN = Pattern.compile("^Trade taken: (.+) Shard$")
    private val GIVEN = Pattern.compile("^You have been given an? (.+)!$")
}

/**
 * The offers found this run. One offer is one item for one shard, so a taken trade is dropped by species
 * (all a partymate's announcement carries). Capped so a malicious party cannot grow it without bound.
 */
class TradeBook {
    private val trades = ArrayList<FoundTrade>()

    /** Adds the trade; false when an identical one (same NPC, species and item) is already listed. */
    fun add(trade: FoundTrade): Boolean {
        if (trades.any { it.offer.npc == trade.offer.npc && it.offer.critter == trade.offer.critter && it.offer.item == trade.offer.item }) {
            return false
        }
        if (trades.size >= MAX_TRADES) trades.removeAt(0)
        trades.add(trade)
        return true
    }

    /** Drops every listed offer of [critter]; true if any was listed. */
    fun take(critter: Critter): Boolean = trades.removeIf { it.offer.critter == critter }

    fun has(critter: Critter): Boolean = trades.any { it.offer.critter == critter }

    /** Offers nearest first from the given position; ones without a known spot sort last. */
    fun nearestFirst(px: Double, py: Double, pz: Double): List<FoundTrade> =
        trades.sortedBy { it.distanceSqr(px, py, pz) }

    fun all(): List<FoundTrade> = trades.toList()
    fun reset() = trades.clear()

    private companion object {
        const val MAX_TRADES = 32
    }
}
