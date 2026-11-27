package geiler.addons.client.profileviewer

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale

/**
 * The newer parts of a profile member for the Profile Viewer: the skill trees (HOTM, HOTF), Glacite, foraging,
 * attributes and shards, Crimson Isle, Rift, trophy fish, Chocolate Factory, essence, kills and deaths, and the
 * accessory bag. Paths follow the public profiles API as SkyCrypt and NEU's profile viewer read it. Pure; bounded.
 */
class PvSections private constructor(m: JsonObject) {
    class Powder(val available: Long, val spent: Long, val total: Long)

    private val tree = m.obj("skill_tree")
    private val mining = m.obj("mining_core")
    private val glacite = m.obj("glacite_player_data")
    private val foraging = m.obj("foraging")
    private val crimson = m.obj("nether_island_player_data")
    private val rift = m.obj("rift")
    private val stats = m.obj("player_stats")
    private val easter = m.obj("events")?.obj("easter")

    val hotmXp: Long = tree?.obj("experience")?.long("mining") ?: mining?.long("experience") ?: 0L
    val hotmLevel: Int = PvLevels.hotm(hotmXp)
    val coreOfTheMountain: Int = tree?.obj("nodes")?.obj("mining")?.long("core_of_the_mountain")?.toInt() ?: 0
    val mountainTokensSpent: Int = tree?.obj("tokens_spent")?.long("mountain")?.toInt() ?: 0
    val pickaxeAbility: String = ability("mining")
    val miningNodes: Map<String, Int> = levels(tree?.obj("nodes")?.obj("mining"))
    val nucleusRuns: Int
    val crystals: Map<String, String>
    val fossilsDonated: Int = glacite?.array("fossils_donated")?.size() ?: 0
    val fossilDust: Double = glacite?.double("fossil_dust") ?: 0.0
    val corpses: Map<String, Int> = ints(glacite?.obj("corpses_looted"))
    val corpsesLooted: Int = corpses.values.sum()
    val mineshafts: Int = glacite?.long("mineshafts_entered")?.toInt() ?: 0

    val hotfXp: Long = tree?.obj("experience")?.long("foraging") ?: 0L
    val hotfLevel: Int = PvLevels.hotf(hotfXp)
    val centerOfTheForest: Int = tree?.obj("nodes")?.obj("foraging")?.long("center_of_the_forest")?.toInt() ?: 0
    val forestTokensSpent: Int = tree?.obj("tokens_spent")?.long("forest")?.toInt() ?: 0
    val axeAbility: String = ability("foraging")
    val foragingNodes: Map<String, Int> = levels(tree?.obj("nodes")?.obj("foraging"))
    // Current shape: foraging_core.whispers.<forest|desert>.total (now) and .<tier>.spent; old shape kept as fallback.
    private val whispers = m.obj("foraging_core")?.obj("whispers")
    val whispersAvailable: Long = whispers?.obj("forest")?.long("total") ?: m.obj("foraging_core")?.long("forests_whispers") ?: 0L
    val whispersSpent: Long = whispers?.obj("forest")?.let(::spent) ?: m.obj("foraging_core")?.long("forests_whispers_spent") ?: 0L
    val whispersTotal: Long = whispersAvailable + whispersSpent
    val desertWhispersAvailable: Long = whispers?.obj("desert")?.long("total") ?: 0L
    val desertWhispersTotal: Long = desertWhispersAvailable + (whispers?.obj("desert")?.let(::spent) ?: 0L)
    val fishFamily: Int = foraging?.array("fish_family")?.size() ?: 0
    val hinaChapter: Int = foraging?.obj("hina")?.obj("tasks")?.long("tier_claimed")?.toInt() ?: 0
    val treeGifts: Map<String, Int> = ints(foraging?.obj("tree_gifts")?.obj("milestone_tier_claimed"))

    val attributes: Map<String, Int> = ints(m.obj("attributes")?.obj("stacks"))
    val shardsOwned: Int

    /** Owned hunting shards by type (the bazaar id without `SHARD_`). */
    val shardsByType: Map<String, Int>

    val faction: String? = crimson?.string("selected_faction")?.takeIf { it.isNotBlank() }
    val magesReputation: Double = crimson?.double("mages_reputation") ?: 0.0
    val barbariansReputation: Double = crimson?.double("barbarians_reputation") ?: 0.0
    val kuudra: Map<String, Int> = ints(crimson?.obj("kuudra_completed_tiers")).filterKeys { it in KUUDRA_TIERS }
    val dojo: Map<String, Int> = ints(crimson?.obj("dojo")).filterKeys { it.startsWith("dojo_points_") }.mapKeys { it.key.removePrefix("dojo_points_") }
    val matriarchPearls: Int = crimson?.obj("matriarch")?.long("pearls_collected")?.toInt() ?: 0

    val motes: Double = m.obj("currencies")?.double("motes_purse") ?: 0.0
    val lifetimeMotes: Double = stats?.obj("rift")?.double("lifetime_motes_earned") ?: 0.0
    val riftVisits: Int = stats?.obj("rift")?.long("visits")?.toInt() ?: 0
    val enigmaSouls: Int = rift?.obj("enigma")?.array("found_souls")?.size() ?: 0
    val montezumaCats: Int = rift?.obj("dead_cats")?.array("found_cats")?.size() ?: 0
    val grubberStacks: Int = rift?.obj("castle")?.long("grubber_stacks")?.toInt() ?: 0
    val timecharms: Int = rift?.obj("gallery")?.array("secured_trophies")?.size() ?: 0
    val witherEyes: Int = rift?.obj("wither_cage")?.array("killed_eyes")?.size() ?: 0

    val essence: Map<String, Long>
    val kills: Map<String, Long> = longs(stats?.obj("kills")).filterKeys { it != "total" }
    val deaths: Map<String, Long> = longs(stats?.obj("deaths")).filterKeys { it != "total" }
    val totalKills: Long = stats?.obj("kills")?.long("total") ?: kills.values.sum()
    val totalDeaths: Long = stats?.obj("deaths")?.long("total") ?: deaths.values.sum()
    val itemsFished: Long = stats?.obj("items_fished")?.long("total") ?: 0L
    val seaCreatureKills: Long = stats?.long("sea_creature_kills") ?: stats?.obj("pets")?.obj("milestone")?.long("sea_creatures_killed")
        ?: stats?.long("sea_creatures_killed") ?: 0L
    val treasuresFished: Long = stats?.obj("items_fished")?.long("treasure") ?: 0L
    val largeTreasuresFished: Long = stats?.obj("items_fished")?.long("large_treasure") ?: 0L

    private val trophyFish: Map<String, Int> = ints(m.obj("trophy_fish"))
    val trophyTotal: Int = trophyFish["total_caught"] ?: 0

    /** Highest trophy tier per frog (1 bronze to 4 diamond), from the `TROPHY_<FROG>_<TIER>` SkyBlock level tasks. */
    private val frogs: Map<String, Int> = HashMap<String, Int>().also { out ->
        m.obj("leveling")?.array("completed_tasks")?.forEach { e ->
            val task = if (e.isJsonPrimitive) e.asString else return@forEach
            val match = TROPHY_TASK.matchEntire(task) ?: return@forEach
            if (match.groupValues[1] in TROPHY_FROGS) out.merge(match.groupValues[1], TIERS.indexOf(match.groupValues[2]) + 1, ::maxOf)
        }
    }

    /** Highest trophy tier caught for [frog] (`BULLFROG`): 0 none, 1 bronze, 2 silver, 3 gold, 4 diamond. */
    fun trophyFrog(frog: String): Int = frogs[frog] ?: 0

    val chocolate: Long = easter?.long("chocolate") ?: 0L
    val chocolateTotal: Long = easter?.long("total_chocolate") ?: 0L
    val chocolateSincePrestige: Long = easter?.long("chocolate_since_prestige") ?: 0L
    val chocolatePrestige: Int = easter?.long("chocolate_level")?.toInt() ?: if (easter == null) 0 else 1
    val rabbits: Int = easter?.obj("rabbits")?.entrySet()?.count { it.value.long() != null } ?: 0

    private val jacob = m.obj("jacobs_contest")
    val medals: Map<String, Int> = ints(jacob?.obj("medals_inv"))
    val doubleDrops: Int = jacob?.obj("perks")?.long("double_drops")?.toInt() ?: 0
    val farmingCapPerk: Int = jacob?.obj("perks")?.long("farming_level_cap")?.toInt() ?: 0
    val contests: Int = jacob?.obj("contests")?.size() ?: 0
    val uniqueBrackets: Map<String, Int> = jacob?.obj("unique_brackets")?.entrySet()
        ?.associate { it.key to (it.value.takeIf { v -> v.isJsonArray }?.asJsonArray?.size() ?: 0) } ?: emptyMap()
    val personalBests: Map<String, Long> = longs(jacob?.obj("personal_bests"))
    private val bracketCrops: Map<String, Set<String>> = jacob?.obj("unique_brackets")?.entrySet()
        ?.associate { (medal, list) -> medal to (list.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> if (e.isJsonPrimitive) e.asString else null }?.toSet() ?: emptySet()) }
        ?: emptyMap()

    /** Best Jacob's Contest medal ever won for [crop] (`WHEAT`, `CARROT_ITEM`), or null. */
    fun bestMedal(crop: String): String? = MEDALS.firstOrNull { crop in (bracketCrops[it] ?: emptySet()) }

    /** Raw id of the selected ability of `mining` or `foraging` (`pickobulus`), or null. */
    fun abilityId(tree: String): String? = this.tree?.obj("selected_ability")?.string(tree)?.takeIf { it.isNotBlank() }
    val petsSacrificed: Int = m.obj("pets_data")?.obj("pet_care")?.array("pet_types_sacrificed")?.size() ?: 0
    val bestiaryKills: Map<String, Long> = longs(m.obj("bestiary")?.obj("kills"))

    val magicalPower: Int = m.obj("accessory_bag_storage")?.long("highest_magical_power")?.toInt() ?: 0
    val selectedPower: String? = m.obj("accessory_bag_storage")?.string("selected_power")?.takeIf { it.isNotBlank() }?.let(::title)

    init {
        var runs = 0
        val states = LinkedHashMap<String, String>()
        mining?.obj("crystals")?.entrySet()?.forEach { (key, value) ->
            val crystal = value.obj() ?: return@forEach
            runs = maxOf(runs, crystal.long("total_placed")?.toInt() ?: 0)
            if (key.endsWith("_crystal") && states.size < 32) states[key.removeSuffix("_crystal")] = crystal.string("state") ?: "NOT_FOUND"
        }
        nucleusRuns = runs
        crystals = states
        var shards = 0
        val byType = LinkedHashMap<String, Int>()
        m.obj("shards")?.array("owned")?.forEach { e ->
            val shard = e.obj() ?: return@forEach
            val amount = shard.long("amount_owned")?.toInt() ?: 0
            shards += amount
            shard.string("type")?.let { if (byType.size < MAX_ENTRIES) byType.merge(it, amount, Int::plus) }
        }
        shardsOwned = shards
        shardsByType = byType
        val ess = LinkedHashMap<String, Long>()
        m.obj("currencies")?.obj("essence")?.entrySet()?.forEach { (type, value) -> value.obj()?.long("current")?.let { ess[type] = it } }
        essence = ess
    }

    fun powder(kind: String): Powder {
        val available = mining?.long("powder_$kind") ?: 0L
        val spent = mining?.long("powder_spent_$kind") ?: 0L
        return Powder(available, spent, mining?.long("powder_${kind}_total") ?: (available + spent))
    }

    /** Catches of a trophy fish at a tier (`bronze`, `silver`, `gold`, `diamond`). */
    fun trophy(fish: String, tier: String): Int = trophyFish["${fish}_$tier"] ?: 0

    private fun spent(kind: JsonObject): Long = kind.entrySet().sumOf { (key, value) -> if (key == "total") 0L else value.obj()?.long("spent") ?: 0L }

    private fun ability(tree: String): String =
        this.tree?.obj("selected_ability")?.string(tree)?.takeIf { it.isNotBlank() }?.let(::title) ?: "None"

    companion object {
        private const val MAX_ENTRIES = 512
        val KUUDRA_TIERS = listOf("none", "hot", "burning", "fiery", "infernal")
        val DOJO_TESTS = listOf("mob_kb", "wall_jump", "archer", "sword_swap", "snake", "lock_head", "fireball")
        private val DOJO_NAMES = mapOf(
            "mob_kb" to "Force", "wall_jump" to "Stamina", "archer" to "Mastery", "sword_swap" to "Discipline",
            "snake" to "Swiftness", "lock_head" to "Control", "fireball" to "Tenacity",
        )

        /** Test of ... name for a Dojo id (SkyCrypt's mapping, the names the wiki uses). */
        @JvmStatic
        fun dojoName(id: String): String = DOJO_NAMES[id] ?: title(id)

        /** Belt for the summed Dojo points (wiki milestones: 1000 Yellow, 2000 Green, 4000 Blue, 6000 Brown, 7000 Black). */
        @JvmStatic
        fun belt(points: Int): String = when {
            points >= 7000 -> "Black"
            points >= 6000 -> "Brown"
            points >= 4000 -> "Blue"
            points >= 2000 -> "Green"
            points >= 1000 -> "Yellow"
            else -> "White"
        }

        /** Members of The Fish Family (38 on the wiki list). */
        const val FISH_FAMILY = 38
        val TROPHY_FISH = listOf(
            "blobfish", "flyfish", "golden_fish", "gusher", "karate_fish", "lava_horse", "mana_ray", "moldfin",
            "obfuscated_fish_1", "obfuscated_fish_2", "obfuscated_fish_3", "skeleton_fish", "slugfish", "soul_fish",
            "steaming_hot_flounder", "sulphur_skitter", "vanille", "volcanic_stonefish",
        )

        /** Trophy frogs of the Lotus Atoll (Researcher Ribery); ids as the SkyBlock level tasks spell them. */
        @JvmField
        val TROPHY_FROGS = listOf(
            "COMMON_FROG", "TREE_FROG", "CAVE_FROG", "WETLANDS_FROG", "HIGHLANDS_FROG", "SEA_FROG", "BULLFROG",
            "LEAP_FROG", "PUDDLE_JUMPER", "EXPLODING_FROG", "BLESSED_FROG", "REALITY_HOPPER",
        )
        private val TIERS = listOf("BRONZE", "SILVER", "GOLD", "DIAMOND")
        val MEDALS = listOf("diamond", "platinum", "gold", "silver", "bronze")
        private val TROPHY_TASK = Regex("TROPHY_([A-Z_]+)_(BRONZE|SILVER|GOLD|DIAMOND)")

        @JvmStatic
        fun parse(member: JsonObject): PvSections = PvSections(member)

        fun title(id: String): String = id.lowercase(Locale.ROOT).split('_', ' ').filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }

        private fun levels(json: JsonObject?): Map<String, Int> = ints(json)

        private fun ints(json: JsonObject?): Map<String, Int> = longs(json).mapValues { it.value.toInt() }

        private fun longs(json: JsonObject?): Map<String, Long> {
            val out = LinkedHashMap<String, Long>()
            json?.entrySet()?.forEach { (key, value) -> if (out.size < MAX_ENTRIES) value.long()?.let { out[key] = it } }
            return out
        }

        private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
        private fun JsonObject.obj(key: String): JsonObject? = get(key)?.obj()
        private fun JsonObject.array(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        private fun JsonElement.long(): Long? = try { if (isJsonPrimitive && asJsonPrimitive.isNumber) asDouble.toLong() else null } catch (_: RuntimeException) { null }
        private fun JsonObject.long(key: String): Long? = get(key)?.long()
        private fun JsonObject.double(key: String): Double? =
            get(key)?.let { try { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asDouble else null } catch (_: RuntimeException) { null } }
        private fun JsonObject.string(key: String): String? = get(key)?.let { if (it.isJsonPrimitive) it.asString else null }
    }
}
