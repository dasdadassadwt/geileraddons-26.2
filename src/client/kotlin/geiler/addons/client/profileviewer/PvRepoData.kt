package geiler.addons.client.profileviewer

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale

/*
 * Profile Viewer parts computed from NotEnoughUpdates repository constants (`constants/bestiary.json`, `misc.json`,
 * `attribute_shards.json`, `hotmlayout.json`, `hotflayout.json`). The rules follow SkyCrypt's backend and NEU's profile
 * viewer (behavior only). All pure and bounded.
 */

private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
private fun JsonObject.obj(key: String): JsonObject? = get(key)?.obj()
private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.int(key: String): Int? = try { get(key)?.takeIf { it.isJsonPrimitive }?.asInt } catch (_: RuntimeException) { null }
private fun JsonObject.ints(key: String): List<Int> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { try { it.asInt } catch (_: RuntimeException) { null } } ?: emptyList()
private fun JsonObject.strings(key: String): List<String> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: emptyList()
private val CODES = Regex("§.")

/** Bestiary tiers: a mob's tier is how many of its bracket's kill steps its kills (up to the cap) reach. */
class PvBestiary private constructor(val islands: List<Island>) {
    class Mob(val name: String, val kills: Long, val tier: Int, val maxTier: Int)
    class Island(val name: String, val mobs: List<Mob>) {
        val maxed: Int get() = mobs.count { it.tier >= it.maxTier && it.maxTier > 0 }
    }

    val tiers: Int get() = islands.sumOf { i -> i.mobs.sumOf { it.tier } }

    /** The Bestiary milestone is one per ten tiers. */
    val milestone: Int get() = tiers / 10

    companion object {
        private const val MAX_MOBS = 1_000

        @JvmStatic
        fun parse(constants: JsonObject, kills: Map<String, Long>): PvBestiary {
            val brackets = constants.obj("brackets")
            val islands = ArrayList<Island>()
            var count = 0
            fun mobs(group: JsonObject): List<Mob> = group.get("mobs")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
                val mob = element.obj() ?: return@mapNotNull null
                if (++count > MAX_MOBS) return@mapNotNull null
                val steps = brackets?.ints(mob.int("bracket")?.toString() ?: "") ?: emptyList()
                val cap = mob.int("cap")?.toLong() ?: steps.lastOrNull()?.toLong() ?: 0L
                val total = mob.strings("mobs").sumOf { kills[it] ?: 0L }
                val counted = minOf(total, cap)
                Mob((mob.str("name") ?: "?").replace(CODES, ""), total, steps.count { counted >= it }, steps.count { cap >= it })
            } ?: emptyList()
            for ((key, value) in constants.entrySet()) {
                if (key == "brackets" || key == "bracketSets") continue
                val group = value.obj() ?: continue
                val name = group.str("name") ?: key
                val list = ArrayList(mobs(group))
                if (group.get("hasSubcategories")?.asBoolean == true) {
                    for ((_, sub) in group.entrySet()) sub.obj()?.takeIf { it.has("mobs") }?.let { list.addAll(mobs(it)) }
                }
                if (list.isNotEmpty()) islands.add(Island(name, list))
            }
            return PvBestiary(islands)
        }
    }
}

/** Crafted minion tiers against each minion's highest tier (`misc.json` `minions`). */
object PvMinions {
    class Minion(val id: String, val highest: Int, val maxTier: Int)

    private val CRAFT = Regex("([A-Z_]+)_(\\d+)")

    @JvmStatic
    fun parse(misc: JsonObject, crafted: Set<String>): List<Minion> {
        val best = HashMap<String, Int>()
        for (c in crafted) {
            val m = CRAFT.matchEntire(c) ?: continue
            best.merge(m.groupValues[1], m.groupValues[2].toInt(), ::maxOf)
        }
        return misc.obj("minions")?.entrySet()?.map { (key, value) ->
            val id = key.removeSuffix("_GENERATOR")
            Minion(id, best[id] ?: 0, try { value.asInt } catch (_: RuntimeException) { 0 })
        }?.sortedWith(compareByDescending<Minion> { it.highest }.thenBy { it.id }) ?: emptyList()
    }

    /** Unique minion crafts, the number that gives minion slots. */
    @JvmStatic
    fun uniqueCrafts(crafted: Set<String>): Int = crafted.count { CRAFT.matches(it) }
}

/** Missing accessories: every accessory nobody in its upgrade family is owned for, and upgrades for owned ones. */
object PvAccessories {
    class Missing(val missing: List<String>, val upgrades: List<String>)

    @JvmStatic
    fun missing(misc: JsonObject, all: List<String>, owned: Set<String>): Missing {
        val upgradesOf = HashMap<String, List<String>>()
        misc.obj("talisman_upgrades")?.entrySet()?.forEach { (k, v) ->
            upgradesOf[k] = v.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: emptyList()
        }
        val ignored = misc.strings("ignored_talisman").toSet()
        // Everything above an id in its family (transitively).
        fun higher(id: String, seen: MutableSet<String> = HashSet()): Set<String> {
            for (up in upgradesOf[id] ?: emptyList()) if (seen.add(up)) higher(up, seen)
            return seen
        }
        val lowerOf = HashMap<String, MutableSet<String>>()
        for (id in upgradesOf.keys) for (up in higher(id)) lowerOf.getOrPut(up) { HashSet() }.add(id)
        val covered = HashSet<String>()
        for (o in owned) {
            covered.add(o)
            lowerOf[o]?.let(covered::addAll)
        }
        // Upgrades: the direct next step of an owned accessory, when not owned yet.
        val upgrades = owned.flatMap { upgradesOf[it] ?: emptyList() }.filter { it !in covered && it in all && it !in ignored }.distinct()
        // Missing: the lowest listed member of a family nobody owns any part of.
        val missing = all.filter { id ->
            id !in ignored && id !in covered && higher(id).none { it in owned } &&
                (lowerOf[id] ?: emptySet<String>()).none { it in all && it !in ignored }
        }
        return Missing(missing, upgrades)
    }
}

/** Attribute levels from syphoned shards; each level costs the next entry of its rarity's list. */
object PvAttributes {
    class Attribute(val name: String, val shard: String, val rarity: String, val level: Int, val maxLevel: Int, val syphoned: Int, val owned: Int, val itemId: String = "")

    @JvmStatic
    fun level(costs: List<Int>, syphoned: Int): Int {
        var left = syphoned
        var level = 0
        for (cost in costs) {
            if (left < cost) break
            left -= cost
            level++
        }
        return level
    }

    @JvmStatic
    fun parse(constants: JsonObject, stacks: Map<String, Int>, owned: Map<String, Int>): List<Attribute> {
        val costs = constants.obj("attribute_levelling")
        val skip = constants.strings("unconsumable_attributes").toSet()
        val ownedUpper = owned.mapKeys { it.key.uppercase(Locale.ROOT) }
        val list = ArrayList<Attribute>()
        constants.get("attributes")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { e ->
            val a = e.obj() ?: return@forEach
            val bazaar = a.str("bazaarName") ?: return@forEach
            if (bazaar in skip) return@forEach
            val rarity = a.str("rarity") ?: "COMMON"
            val table = costs?.ints(rarity) ?: emptyList()
            val stackId = (a.str("internalName") ?: "").removePrefix("ATTRIBUTE_SHARD_").substringBefore(';').lowercase(Locale.ROOT)
            val syphoned = stacks[stackId] ?: 0
            list.add(Attribute(a.str("abilityName") ?: stackId, a.str("displayName") ?: bazaar, rarity, level(table, syphoned), table.size, syphoned,
                ownedUpper[bazaar.removePrefix("SHARD_")] ?: 0, a.str("internalName") ?: ""))
        }
        return list
    }
}

/** HOTM and HOTF perk grids (`hotmlayout.json` / `hotflayout.json`): position, max level and the profile's level. */
object PvTree {
    enum class Kind { NODE, ABILITY, CORE }

    class Perk(val id: String, val name: String, val x: Int, val y: Int, val level: Int, val maxLevel: Int, val kind: Kind = Kind.NODE)

    /** Hypixel's look: core redstone block, abilities emerald block (selected) or coal block, nodes coal (none), emerald (some), diamond (max). */
    enum class Look { REDSTONE_BLOCK, EMERALD_BLOCK, COAL_BLOCK, COAL, EMERALD, DIAMOND }

    @JvmStatic
    fun look(p: Perk, selectedAbility: String?): Look = when (p.kind) {
        Kind.CORE -> Look.REDSTONE_BLOCK
        Kind.ABILITY -> if (p.id == selectedAbility) Look.EMERALD_BLOCK else Look.COAL_BLOCK
        Kind.NODE -> when {
            p.level <= 0 -> Look.COAL
            p.level >= p.maxLevel -> Look.DIAMOND
            else -> Look.EMERALD
        }
    }

    @JvmStatic
    fun parse(layout: JsonObject, tree: String, levels: Map<String, Int>): List<Perk> =
        layout.obj(tree)?.obj("perks")?.entrySet()?.mapNotNull { (id, value) ->
            val p = value.obj() ?: return@mapNotNull null
            val item = p.str("item") ?: ""
            val kind = when {
                id == "core_of_the_mountain" || id == "center_of_the_forest" || item == ":REDSTONE_BLOCK" -> Kind.CORE
                item.startsWith("(api") || p.get("isAbility")?.let { it.isJsonPrimitive && it.asBoolean } == true -> Kind.ABILITY
                else -> Kind.NODE
            }
            Perk(id, p.str("name") ?: id, p.int("x") ?: return@mapNotNull null, p.int("y") ?: return@mapNotNull null, levels[id] ?: 0, p.int("maxLevel") ?: 1, kind)
        } ?: emptyList()
}

/** Collection categories and tiers from Hypixel's `resources/skyblock/collections`. */
object PvCollections {
    class Item(val id: String, val name: String, val tiers: List<Long>) {
        fun tier(amount: Long): Int = tiers.count { amount >= it }
    }

    class Category(val id: String, val name: String, val items: List<Item>)

    @JvmStatic
    fun parse(json: JsonObject): List<Category> = json.obj("collections")?.entrySet()?.mapNotNull { (id, value) ->
        val c = value.obj() ?: return@mapNotNull null
        val items = c.obj("items")?.entrySet()?.mapNotNull { (itemId, v) ->
            val i = v.obj() ?: return@mapNotNull null
            val tiers = i.get("tiers")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { t ->
                t.obj()?.get("amountRequired")?.let { a -> try { a.asLong } catch (_: RuntimeException) { null } }
            }?.sorted() ?: emptyList()
            Item(itemId, i.str("name") ?: itemId, tiers)
        }?.take(256) ?: emptyList()
        Category(id, c.str("name") ?: id, items)
    } ?: emptyList()
}

/** Garden (`/v2/skyblock/garden` shape) with NEU `garden.json`: level, crop milestones and upgrades, plots, visitors. */
class PvGarden private constructor(
    val level: Int,
    val maxLevel: Int,
    val experience: Long,
    val milestones: Map<String, Int>,
    val collected: Map<String, Long>,
    val cropUpgrades: Map<String, Int>,
    val plots: Int,
    val maxPlots: Int,
    val visitorsServed: Int,
    val uniqueVisitors: Int,
    val maxVisitors: Int,
    val composter: Map<String, Int>,
) {
    companion object {
        /** Level from per-level steps: how many cumulative steps [xp] reaches. */
        private fun stepLevel(steps: List<Int>, xp: Long): Int {
            var total = 0L
            var level = 0
            for (step in steps) {
                total += step
                if (xp < total) break
                level++
            }
            return level
        }

        private fun counts(json: JsonObject?): Map<String, Long> = json?.entrySet()?.mapNotNull { (k, v) ->
            try { if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) k to v.asDouble.toLong() else null } catch (_: RuntimeException) { null }
        }?.take(256)?.toMap() ?: emptyMap()

        /** Garden API resource id to the NEU `crop_milestones` key (`CARROT_ITEM` to `CARROT`). */
        @JvmStatic
        fun cropKey(id: String): String = when (id) {
            "INK_SACK:3" -> "COCOA_BEANS"
            "NETHER_STALK" -> "NETHER_WART"
            "MUSHROOM_COLLECTION" -> "MUSHROOM"
            else -> id.removeSuffix("_ITEM")
        }

        @JvmStatic
        fun parse(garden: JsonObject, constants: JsonObject): PvGarden {
            val xp = garden.get("garden_experience")?.let { try { it.asDouble.toLong() } catch (_: RuntimeException) { 0L } } ?: 0L
            val steps = constants.ints("garden_exp")
            val collected = counts(garden.obj("resources_collected")).entries.associate { cropKey(it.key) to it.value }
            val milestones = constants.obj("crop_milestones")?.entrySet()?.associate { (crop, v) ->
                crop to stepLevel(v.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { try { it.asInt } catch (_: RuntimeException) { null } } ?: emptyList(), collected[crop] ?: 0L)
            } ?: emptyMap()
            val commissions = garden.obj("commission_data")
            return PvGarden(
                stepLevel(steps, xp), steps.size, xp, milestones, collected,
                counts(garden.obj("crop_upgrade_levels")).entries.associate { cropKey(it.key) to it.value.toInt() },
                garden.get("unlocked_plots_ids")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0,
                constants.obj("plots")?.size() ?: 0,
                commissions?.int("total_completed") ?: 0,
                commissions?.int("unique_npcs_served") ?: 0,
                constants.obj("visitors")?.size() ?: 0,
                counts(garden.obj("composter_data")?.obj("upgrades")).mapValues { it.value.toInt() },
            )
        }
    }
}

/** Museum (`/v2/skyblock/museum` member shape) with NEU `museum.json`: donations per category, missing items, value. */
class PvMuseum private constructor(val value: Long, val appraisal: Boolean, val special: Int, val categories: List<Category>) {
    class Category(val name: String, val donated: Int, val max: Int, val missing: List<String>)

    val donated: Int get() = categories.sumOf { it.donated }

    companion object {
        @JvmStatic
        fun parse(member: JsonObject, constants: JsonObject): PvMuseum {
            val children = constants.obj("children")?.entrySet()?.associate { it.key to (it.value.takeIf { v -> v.isJsonPrimitive }?.asString ?: "") } ?: emptyMap()
            val given = member.obj("items")?.keySet()?.toHashSet() ?: HashSet()
            // Donating an item also counts every lower item in its chain.
            val covered = HashSet<String>()
            for (id in given) {
                var cur: String? = id
                var guard = 0
                while (cur != null && cur.isNotEmpty() && covered.add(cur) && guard++ < 32) cur = children[cur]
            }
            val max = constants.obj("max_values")
            val categories = constants.obj("items")?.entrySet()?.map { (cat, list) ->
                val ids = list.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: emptyList()
                Category(cat, ids.count { it in covered }, max?.int(cat) ?: ids.size, ids.filter { it !in covered })
            } ?: emptyList()
            return PvMuseum(
                member.get("value")?.let { try { it.asDouble.toLong() } catch (_: RuntimeException) { 0L } } ?: 0L,
                member.get("appraisal")?.let { it.isJsonPrimitive && it.asBoolean } ?: false,
                member.get("special")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0,
                categories,
            )
        }
    }
}
